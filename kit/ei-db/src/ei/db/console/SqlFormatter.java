package ei.db.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL 정렬. 절 키워드를 6칸 오른쪽 맞춤(강물 정렬)으로 줄 앞에 두고, SELECT · SET · GROUP BY · ORDER BY 목록은 칼럼마다 한 줄,
 * 쉼표는 줄 앞(「     , 칼럼」), SELECT 는 키워드만 한 줄에 두고 첫 칼럼부터 다음 줄에 쓴다. INSERT 칼럼 · VALUES 값도 쉼표를 줄 앞에 둔다.
 * WHERE · HAVING · ON 의 AND · OR 는 줄마다, FROM 의 표 목록은 한 줄(구식 조인)로 둔다. 괄호 안이 SELECT 이면 한 단계 들여 쓰고,
 * 그 밖의 괄호(함수 · IN 목록 · (+))는 한 줄로 둔다. 키워드만 대문자로 바꾸고 이름 · 문자열 · 주석은 그대로 둔다.
 * 구문 분석기가 아니라 낱말 단위로 보므로 처음 보는 문법은 낱말 사이 공백만 정리된다.
 */
final class SqlFormatter {
  private SqlFormatter() {}

  private static final int RIVER = 6;
  /** 줄 끝 주석 자리 표시. 다 쓴 뒤 같은 덩어리의 줄 끝 주석을 한 열에 맞추고 공백 두 칸으로 바꾼다. */
  private static final char MARK = '\u0001';
  private static final String COMMENT_GAP = "  ";
  private static final String LIST_INDENT = " ".repeat(RIVER + 1);
  /** 앞 쉼표 줄의 머리. 쉼표 뒤 칼럼이 LIST_INDENT 와 같은 열에서 시작한다. */
  private static final String LEAD_COMMA = " ".repeat(RIVER - 1) + ", ";

  private static final Set<String> KEYWORDS = Set.of(
      "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS", "AS", "ON",
      "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "GROUP", "ORDER", "BY", "HAVING", "UNION", "ALL",
      "MINUS", "INTERSECT", "EXCEPT", "DISTINCT", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE", "USING",
      "WHEN", "THEN", "ELSE", "END", "CASE", "MATCHED", "ASC", "DESC", "NULLS", "FIRST", "LAST", "WITH", "CONNECT",
      "START", "PRIOR", "ESCAPE", "OVER", "PARTITION", "LIMIT", "OFFSET", "FETCH", "NEXT", "ROWS", "ONLY", "SYSDATE");
  /** 줄을 바꿔 강물 정렬하는 절 머리(첫 낱말). */
  private static final Set<String> CLAUSES = Set.of(
      "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "INSERT", "VALUES", "UPDATE", "SET", "DELETE", "MERGE",
      "USING", "CONNECT", "START", "LIMIT", "OFFSET", "FETCH", "WITH");
  private static final Set<String> JOIN_HEADS = Set.of("JOIN", "LEFT", "RIGHT", "INNER", "FULL", "CROSS");
  /** 절 머리 뒤에 붙여 한 머리로 보는 낱말: GROUP BY, INSERT INTO, LEFT OUTER JOIN … */
  private static final Map<String, Set<String>> FOLLOW = Map.ofEntries(
      Map.entry("GROUP", Set.of("BY")),
      Map.entry("ORDER", Set.of("BY")),
      Map.entry("CONNECT", Set.of("BY")),
      Map.entry("INSERT", Set.of("INTO")),
      Map.entry("MERGE", Set.of("INTO")),
      Map.entry("DELETE", Set.of("FROM")),
      Map.entry("START", Set.of("WITH")),
      Map.entry("SELECT", Set.of("DISTINCT")),
      Map.entry("LEFT", Set.of("OUTER", "JOIN")),
      Map.entry("RIGHT", Set.of("OUTER", "JOIN")),
      Map.entry("FULL", Set.of("OUTER", "JOIN")),
      Map.entry("INNER", Set.of("JOIN")),
      Map.entry("CROSS", Set.of("JOIN")));
  private static final Set<String> SET_OPS = Set.of("UNION", "MINUS", "INTERSECT", "EXCEPT");
  /** 칼럼마다 줄을 바꾸는 목록 절. */
  private static final Set<String> LIST_CLAUSES = Set.of("SELECT", "SET", "GROUP", "ORDER");
  /** AND · OR 에서 줄을 바꾸는 조건 절. */
  private static final Set<String> CONDITION_CLAUSES = Set.of("WHERE", "HAVING", "ON", "START");
  /** 뒤에 ( 가 와도 띄어 쓰는 키워드(그 밖의 낱말 + ( 는 함수 호출로 본다). */
  private static final Set<String> SPACE_BEFORE_PAREN = Set.of(
      "IN", "VALUES", "EXISTS", "AS", "ON", "AND", "OR", "NOT", "FROM", "JOIN", "WHERE", "SELECT", "USING", "INTO", "OVER", "THEN", "ELSE", "WHEN");

  private enum Kind { WORD, STRING, NUMBER, SYMBOL, OPEN, CLOSE, COMMA, SEMI, LINE_COMMENT, TRAIL_COMMENT, BLOCK_COMMENT, DOT }

  private record Tok(Kind kind, String text) {
    String upper() { return text.toUpperCase(Locale.ROOT); }
    boolean is(String word) { return kind == Kind.WORD && upper().equals(word); }
  }

  static String format(String sql) {
    List<Tok> toks = lex(sql);
    StringBuilder out = new StringBuilder();
    int start = 0;
    for (int i = 0; i <= toks.size(); i++) {
      if (i == toks.size() || toks.get(i).kind == Kind.SEMI) {
        if (i > start) {
          if (out.length() > 0) out.append("\n\n");
          out.append(block(toks.subList(start, i), ""));
          if (i < toks.size()) out.append(';');
        }
        start = i + 1;
      }
    }
    return out.toString();
  }

  /**
   * 대 · 소문자 바꾸기. 문자열 · 따옴표 이름 · 주석 · MyBatis #{…} · ${…} 는 값이나 자바 이름이라 그대로 두고 나머지 글자만 바꾼다.
   * 공백 · 줄바꿈은 건드리지 않는다.
   */
  static String changeCase(String sql, boolean upper) {
    StringBuilder sb = new StringBuilder(sql.length());
    int i = 0;
    int n = sql.length();
    while (i < n) {
      int e = skipEnd(sql, i);
      if (e > i) {
        sb.append(sql, i, e);
        i = e;
        continue;
      }
      char c = sql.charAt(i);
      sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
      i++;
    }
    return sb.toString();
  }

  /** i 에서 바꾸지 않을 덩어리(문자열 · 따옴표 이름 · 주석 · #{…})가 시작하면 그 끝, 아니면 i. 닫히지 않으면 글 끝. */
  private static int skipEnd(String s, int i) {
    int n = s.length();
    char c = s.charAt(i);
    char d = i + 1 < n ? s.charAt(i + 1) : 0;
    if (c == '-' && d == '-') {
      int e = s.indexOf('\n', i);
      return e < 0 ? n : e;
    }
    if (c == '/' && d == '*') {
      int e = s.indexOf("*/", i + 2);
      return e < 0 ? n : e + 2;
    }
    if ((c == '#' || c == '$') && d == '{') {
      int e = s.indexOf('}', i);
      return e < 0 ? n : e + 1;
    }
    if (c == '\'' || c == '"' || c == '`') {
      int e = s.indexOf(c, i + 1);
      return e < 0 ? n : e + 1; // '' 이스케이프는 두 덩어리로 나뉘어도 결과가 같다.
    }
    return i;
  }

  /** 한 문장(또는 괄호 안 하위 질의)을 여러 줄로. indent 는 모든 줄 앞에 붙는다. */
  private static String block(List<Tok> toks, String indent) {
    StringBuilder sb = new StringBuilder();
    String clause = "";
    boolean betweenOpen = false;
    List<String> insertColumns = null;
    boolean newLine = true;
    for (int i = 0; i < toks.size(); i++) {
      Tok t = toks.get(i);
      String u = t.upper();
      if (t.kind == Kind.WORD && SET_OPS.contains(u)) {
        String op = u + (i + 1 < toks.size() && toks.get(i + 1).is("ALL") ? " ALL" : "");
        if (op.endsWith(" ALL")) i++;
        breakLine(sb);
        sb.append('\n').append(indent).append(op).append('\n');
        clause = "";
        newLine = true;
        continue;
      }
      if (t.kind == Kind.WORD && isHead(toks, i)) {
        StringBuilder head = new StringBuilder(u);
        Set<String> follow = FOLLOW.getOrDefault(u, Set.of());
        int j = i + 1;
        while (j < toks.size() && toks.get(j).kind == Kind.WORD && follow.contains(toks.get(j).upper())) {
          head.append(' ').append(toks.get(j).upper());
          j++;
        }
        if (u.equals("VALUES") && i > 0 && toks.get(i - 1).kind == Kind.CLOSE && clause.equals("INSERT")) {
          // INSERT 칼럼 목록을 닫은 줄에 이어 「) VALUES (」 로 쓴다.
          sb.append(' ').append(head);
        } else {
          breakLine(sb);
          sb.append(indent).append(pad(head.toString()));
        }
        i = j - 1;
        clause = JOIN_HEADS.contains(u) ? "JOIN" : u;
        betweenOpen = false;
        newLine = false;
        if (u.equals("SELECT")) {
          breakLine(sb);
          sb.append(indent).append(LIST_INDENT);
          newLine = true;
        }
        continue;
      }
      if (t.is("ON") && clause.equals("JOIN")) {
        breakLine(sb);
        sb.append(indent).append(pad("ON"));
        clause = "ON";
        newLine = false;
        continue;
      }
      if ((t.is("AND") || t.is("OR")) && CONDITION_CLAUSES.contains(clause)) {
        if (t.is("AND") && betweenOpen) {
          betweenOpen = false;
        } else {
          breakLine(sb);
          sb.append(indent).append(pad(u));
          newLine = false;
          continue;
        }
      }
      if (t.is("BETWEEN")) betweenOpen = true;
      if (t.kind == Kind.COMMA) {
        if (LIST_CLAUSES.contains(clause)) {
          // 「a, -- 주석」 의 주석은 앞 칼럼 줄 끝에 남기고, 쉼표는 다음 줄 앞으로 보낸다.
          if (i + 1 < toks.size() && toks.get(i + 1).kind == Kind.TRAIL_COMMENT) sb.append(MARK).append(toks.get(++i).text);
          breakLine(sb);
          sb.append(indent).append(LEAD_COMMA);
          newLine = true;
        } else {
          sb.append(", ");
          newLine = true;
        }
        continue;
      }
      if (t.kind == Kind.OPEN) {
        int close = matching(toks, i);
        List<Tok> inner = toks.subList(i + 1, close);
        boolean sub = !inner.isEmpty() && (inner.get(0).is("SELECT") || inner.get(0).is("WITH"));
        if (!sub && (clause.equals("INSERT") || clause.equals("VALUES"))) {
          // INSERT 칼럼 · VALUES 값은 줄마다 하나. 개수가 같으면 값 줄 끝에 칼럼 이름을 달아 어느 값인지 보이게 한다.
          List<String> items = items(inner);
          if (clause.equals("INSERT")) insertColumns = items;
          boolean named = clause.equals("VALUES") && insertColumns != null && insertColumns.size() == items.size();
          sb.append(newLine ? "(" : " (");
          for (int k = 0; k < items.size(); k++) {
            breakLine(sb);
            sb.append(indent).append(k == 0 ? LIST_INDENT : LEAD_COMMA).append(items.get(k));
            if (named) sb.append(MARK).append("-- ").append(insertColumns.get(k));
          }
          breakLine(sb);
          sb.append(indent).append(')');
          i = close;
          newLine = false;
          continue;
        }
        if (!newLine && needsSpaceBeforeParen(toks, i)) sb.append(' ');
        if (sub) {
          sb.append("(\n").append(block(inner, indent + LIST_INDENT)).append('\n').append(indent).append(LIST_INDENT).append(')');
        } else {
          sb.append('(').append(inline(inner)).append(')');
        }
        i = close;
        newLine = false;
        continue;
      }
      if (t.kind == Kind.BLOCK_COMMENT && LIST_CLAUSES.contains(clause) && sb.indexOf(String.valueOf(MARK), sb.lastIndexOf("\n") + 1) < 0
          && (i + 1 == toks.size() || toks.get(i + 1).kind == Kind.COMMA || toks.get(i + 1).kind == Kind.WORD && isHead(toks, i + 1))) {
        // 칼럼 뒤 /* 주석 */ 도 줄 끝 주석처럼 한 열에 맞춘다.
        sb.append(MARK).append(t.text);
        newLine = false;
        continue;
      }
      if (t.kind == Kind.TRAIL_COMMENT && sb.length() > 0) {
        sb.append(MARK).append(t.text);
        breakLine(sb);
        sb.append(indent).append(LIST_INDENT);
        newLine = true;
        continue;
      }
      if (t.kind == Kind.LINE_COMMENT || t.kind == Kind.TRAIL_COMMENT) {
        breakLine(sb);
        sb.append(indent).append(sb.length() == 0 ? "" : LIST_INDENT).append(t.text);
        breakLine(sb);
        sb.append(indent).append(LIST_INDENT);
        newLine = true;
        continue;
      }
      if (sb.length() == 0) sb.append(indent);
      else if (!newLine && spaceBetween(toks.get(i - 1), t)) sb.append(' ');
      sb.append(word(t));
      newLine = false;
    }
    return trimLines(sb.toString());
  }

  /** 줄을 바꿀 절 머리인가. LEFT · RIGHT 는 MySQL 함수 이름이기도 해서 뒤에 JOIN · OUTER 가 올 때만 머리로 본다. */
  private static boolean isHead(List<Tok> toks, int i) {
    String u = toks.get(i).upper();
    if (CLAUSES.contains(u) || u.equals("JOIN") || u.equals("INNER") || u.equals("CROSS")) return true;
    if (!JOIN_HEADS.contains(u) || i + 1 >= toks.size()) return false;
    return toks.get(i + 1).is("JOIN") || toks.get(i + 1).is("OUTER");
  }

  /** 괄호 안을 한 줄로. 하위 질의가 아닌 괄호(함수 인자, IN 목록, (+)). */
  private static String inline(List<Tok> toks) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < toks.size(); i++) {
      Tok t = toks.get(i);
      if (t.kind == Kind.OPEN) {
        int close = matching(toks, i);
        if (i > 0 && needsSpaceBeforeParen(toks, i)) sb.append(' ');
        sb.append('(').append(inline(toks.subList(i + 1, close))).append(')');
        i = close;
        continue;
      }
      if (t.kind == Kind.COMMA) { sb.append(", "); continue; }
      if (i > 0 && toks.get(i - 1).kind != Kind.COMMA && spaceBetween(toks.get(i - 1), t)) sb.append(' ');
      sb.append(word(t));
      if (t.kind == Kind.LINE_COMMENT || t.kind == Kind.TRAIL_COMMENT) sb.append('\n');
    }
    return sb.toString();
  }

  /** 괄호 안을 바깥 쉼표로 나눠 한 줄씩. */
  private static List<String> items(List<Tok> toks) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i <= toks.size(); i++) {
      Kind k = i < toks.size() ? toks.get(i).kind : Kind.COMMA;
      if (k == Kind.OPEN) depth++;
      else if (k == Kind.CLOSE) depth--;
      else if (k == Kind.COMMA && depth == 0) {
        out.add(inline(toks.subList(start, i)).strip());
        start = i + 1;
      }
    }
    return out;
  }

  /** 고정폭 글꼴에서 한글 · 한자 등 넓은 글자는 두 칸으로 센다. */
  private static int displayWidth(String s) {
    int w = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean wide = c >= '\u1100' && c <= '\u115F' || c >= '\u2E80' && c <= '\uA4CF' || c >= '\uAC00' && c <= '\uD7A3'
          || c >= '\uF900' && c <= '\uFAFF' || c >= '\uFF00' && c <= '\uFF60';
      w += wide ? 2 : 1;
    }
    return w;
  }

  private static boolean needsSpaceBeforeParen(List<Tok> toks, int open) {
    if (open == 0) return false;
    Tok prev = toks.get(open - 1);
    if (prev.kind == Kind.WORD) return KEYWORDS.contains(prev.upper()) && SPACE_BEFORE_PAREN.contains(prev.upper()) || prev.is("THEN");
    return prev.kind != Kind.OPEN && prev.kind != Kind.DOT;
  }

  private static boolean spaceBetween(Tok prev, Tok t) {
    if (t.kind == Kind.DOT || prev.kind == Kind.DOT) return false;
    if (t.kind == Kind.CLOSE || prev.kind == Kind.OPEN) return false;
    if (prev.kind == Kind.SYMBOL && prev.text.equals("::") || t.kind == Kind.SYMBOL && t.text.equals("::")) return false;
    return true;
  }

  private static String word(Tok t) {
    return t.kind == Kind.WORD && KEYWORDS.contains(t.upper()) ? t.upper() : t.text;
  }

  private static String pad(String head) {
    String first = head.split(" ")[0];
    return first.length() >= RIVER ? head : " ".repeat(RIVER - first.length()) + head;
  }

  private static int matching(List<Tok> toks, int open) {
    int depth = 0;
    for (int i = open; i < toks.size(); i++) {
      if (toks.get(i).kind == Kind.OPEN) depth++;
      else if (toks.get(i).kind == Kind.CLOSE && --depth == 0) return i;
    }
    return toks.size(); // 닫히지 않은 괄호: 끝까지를 안으로 본다.
  }

  /** 끝 공백을 지우고, 줄이 끝나지 않았으면 줄을 바꾼다. 빈 줄은 만들지 않는다. */
  private static void breakLine(StringBuilder sb) {
    int end = sb.length();
    while (end > 0 && sb.charAt(end - 1) == ' ') end--;
    sb.setLength(end);
    if (end > 0 && sb.charAt(end - 1) != '\n') sb.append('\n');
  }

  /** 줄 끝 공백 · 앞뒤 빈 줄을 지우고, 이어진 줄들의 줄 끝 주석(MARK)을 가장 긴 줄 뒤 한 열에 맞춘다. */
  private static String trimLines(String s) {
    String[] lines = s.split("\n", -1);
    for (int i = 0; i < lines.length; i++) lines[i] = lines[i].stripTrailing();
    int i = 0;
    while (i < lines.length) {
      if (lines[i].indexOf(MARK) < 0) { i++; continue; }
      int end = i;
      int width = 0;
      while (end < lines.length && lines[end].indexOf(MARK) >= 0) {
        width = Math.max(width, displayWidth(lines[end].substring(0, lines[end].indexOf(MARK)).stripTrailing()));
        end++;
      }
      for (int k = i; k < end; k++) {
        int m = lines[k].indexOf(MARK);
        String code = lines[k].substring(0, m).stripTrailing();
        lines[k] = code + " ".repeat(width - displayWidth(code)) + COMMENT_GAP + lines[k].substring(m + 1);
      }
      i = end;
    }
    return String.join("\n", lines).replaceAll("^\n+", "").replaceAll("\n+$", "");
  }

  /** 낱말 나누기. 문자열 · 따옴표 이름 · 주석은 한 덩어리로 그대로 둔다. MyBatis #{…} · ${…} 도 한 낱말. */
  private static List<Tok> lex(String s) {
    List<Tok> out = new ArrayList<>();
    int i = 0;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) { i++; continue; }
      if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
        int e = s.indexOf('\n', i);
        e = e < 0 ? n : e;
        int back = i - 1;
        while (back >= 0 && s.charAt(back) != '\n' && Character.isWhitespace(s.charAt(back))) back--;
        boolean trailing = back >= 0 && s.charAt(back) != '\n';
        out.add(new Tok(trailing ? Kind.TRAIL_COMMENT : Kind.LINE_COMMENT, s.substring(i, e).stripTrailing()));
        i = e;
        continue;
      }
      if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
        int e = s.indexOf("*/", i + 2);
        e = e < 0 ? n : e + 2;
        out.add(new Tok(Kind.BLOCK_COMMENT, s.substring(i, e)));
        i = e;
        continue;
      }
      if (c == '\'' || c == '"' || c == '`') {
        int e = i + 1;
        while (e < n) {
          if (s.charAt(e) == c) {
            if (c == '\'' && e + 1 < n && s.charAt(e + 1) == '\'') { e += 2; continue; }
            break;
          }
          e++;
        }
        e = Math.min(e + 1, n);
        out.add(new Tok(c == '\'' ? Kind.STRING : Kind.WORD, s.substring(i, e)));
        i = e;
        continue;
      }
      if ((c == '#' || c == '$') && i + 1 < n && s.charAt(i + 1) == '{') {
        int e = s.indexOf('}', i);
        e = e < 0 ? n : e + 1;
        out.add(new Tok(Kind.WORD, s.substring(i, e)));
        i = e;
        continue;
      }
      if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == ':' && i + 1 < n && Character.isLetter(s.charAt(i + 1)) || c == '?') {
        int e = i + 1;
        while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '_' || s.charAt(e) == '$' || s.charAt(e) == '#')) e++;
        String w = s.substring(i, e);
        out.add(new Tok(Character.isDigit(c) ? Kind.NUMBER : Kind.WORD, w));
        i = e;
        continue;
      }
      switch (c) {
        case '(' -> out.add(new Tok(Kind.OPEN, "("));
        case ')' -> out.add(new Tok(Kind.CLOSE, ")"));
        case ',' -> out.add(new Tok(Kind.COMMA, ","));
        case ';' -> out.add(new Tok(Kind.SEMI, ";"));
        case '.' -> out.add(new Tok(Kind.DOT, "."));
        default -> {
          String two = i + 1 < n ? s.substring(i, i + 2) : "";
          if (Set.of("<=", ">=", "<>", "!=", "||", "::", ":=", "=>").contains(two)) {
            out.add(new Tok(Kind.SYMBOL, two));
            i += 2;
            continue;
          }
          out.add(new Tok(Kind.SYMBOL, String.valueOf(c)));
        }
      }
      i++;
    }
    return out;
  }
}
