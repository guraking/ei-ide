package ei.db.console;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 콘솔 글을 문장으로 나눈다. 순수 자바(플랫폼 의존 없음).
 * 경계: 따옴표 · 주석 밖의 ; (문장에 포함), 괄호 밖의 빈 줄, / 만 있는 줄(문장에서 뺀다).
 * 빈 줄 뒤가 혼자서는 문장이 될 수 없는 절(CONTINUE_WORDS)이나 , · ) 로 시작하면 빈 줄에서 끊지 않는다.
 * PL/SQL 블록(BEGIN · DECLARE · CREATE [OR REPLACE] PROCEDURE/FUNCTION/PACKAGE [BODY]/TRIGGER/TYPE BODY)은 빈 줄로 나누지 않고
 * 여는 쪽(BEGIN · CASE · DECLARE · 하위 프로그램 머리의 IS/AS)과 END 의 짝이 모두 맞은 뒤의 ; 에서만 끝난다.
 * 하위 프로그램(지역 함수 · 패키지 · 타입 메서드)의 IS/AS 가 연 겹은 그 BEGIN 이 이어받아 END 하나로 닫힌다.
 * END IF · END LOOP · END WHILE · END REPEAT 는 겹을 닫지 않고, END CASE 는 CASE 만 닫는다.
 * CREATE 블록은 짝이 맞은 END 뒤 이름이 없거나 객체 이름과 같을 때만 끝난다(그 밖에는 / 줄에서). PostgreSQL 의 $$ 본문은 하나의 글자열로 본다.
 * 문장 앞의 공백 · 주석은 문장에 넣지 않는다.
 */
public final class SqlSplitter {
  private SqlSplitter() {}

  /** text 안의 [start, end) 위치와 그 글. */
  public record Stmt(int start, int end, String text) {}

  private static final Set<String> NOT_BLOCK_END = Set.of("IF", "LOOP", "WHILE", "REPEAT");
  private static final Set<String> CREATE_MODIFIERS = Set.of("OR", "REPLACE", "EDITIONABLE", "NONEDITIONABLE");
  /** 선언부 겹(DECLARE · 하위 프로그램 머리의 IS/AS). 이어지는 BEGIN 이 이 겹을 이어받는다. */
  private static final String DECL = "DECL";
  /** 앞 문장에 이어지는 절의 첫 낱말. 빈 줄 뒤에 와도 문장을 끊지 않는다. */
  private static final Set<String> CONTINUE_WORDS = Set.of(
      "FROM", "WHERE", "AND", "OR", "ORDER", "GROUP", "HAVING", "OFFSET", "FETCH", "UNION", "MINUS", "INTERSECT", "EXCEPT",
      "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "ON", "CONNECT", "LIMIT", "FOR");

  public static List<Stmt> split(String text) {
    List<Stmt> out = new ArrayList<>();
    Seg seg = new Seg();
    int n = text.length();
    int i = 0;
    while (i < n) {
      char ch = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : 0;
      if (ch == '-' && next == '-') {
        i = indexOrEnd(text, "\n", i);
        continue;
      }
      if (ch == '/' && next == '*') {
        int close = text.indexOf("*/", i + 2);
        i = close < 0 ? n : close + 2;
        continue;
      }
      if (ch == '\n') {
        int lineStart = text.lastIndexOf('\n', i - 1) + 1;
        String line = text.substring(lineStart, i).strip();
        if (line.equals("/") || (line.isEmpty() && !seg.block && seg.depth == 0 && !continues(text, i + 1))) {
          seg.flush(text, out, lineStart);
          seg = new Seg();
        }
        i++;
        continue;
      }
      if (Character.isWhitespace(ch)) {
        i++;
        continue;
      }
      seg.code(i);
      if (ch == ';') {
        seg.other(ch);
        seg.lastCode = i + 1;
        if (seg.endsAtSemicolon()) {
          seg.flush(text, out, i + 1);
          seg = new Seg();
        } else {
          seg.words.add(";");
        }
        i++;
        continue;
      }
      int end = quotedEnd(text, i);
      if (end > i) {
        seg.other(ch);
        if (ch == '$') seg.dollar = true;
        seg.lastCode = end;
        i = end;
        continue;
      }
      if (Character.isLetter(ch) || ch == '_') {
        int j = i + 1;
        while (j < n && isIdent(text.charAt(j))) j++;
        seg.word(text.substring(i, j).toUpperCase(Locale.ROOT), j < n && text.charAt(j) == '.');
        seg.lastCode = j;
        i = j;
        continue;
      }
      seg.other(ch);
      seg.lastCode = i + 1;
      i++;
    }
    // 마지막 줄의 / 는 줄바꿈 없이 끝날 수 있다.
    int lastLine = text.lastIndexOf('\n') + 1;
    if (text.substring(lastLine).strip().equals("/")) seg.lastCode = Math.min(seg.lastCode, lastLine);
    seg.flush(text, out, n);
    return out;
  }

  /**
   * 커서가 있는 문장. 커서가 문장 안(끝 바로 뒤 포함)이면 그 문장, 앞 문장이 끝난 줄에 있으면 앞 문장,
   * 아니면 뒤 문장, 뒤가 없으면 마지막 문장. 문장이 없으면 null.
   */
  public static Stmt at(String text, int caret) {
    List<Stmt> all = split(text);
    Stmt before = null;
    for (Stmt s : all) {
      if (caret >= s.start && caret <= s.end) return s;
      if (s.end < caret) {
        before = s;
        continue;
      }
      if (before != null && text.lastIndexOf('\n', caret - 1) < before.end) return before;
      return s;
    }
    return before;
  }

  /** from 부터 공백 · 주석을 건너뛴 첫 글이 이어지는 절(CONTINUE_WORDS) · , · ) 이면 참. */
  private static boolean continues(String text, int from) {
    int n = text.length();
    int i = from;
    while (i < n) {
      char ch = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : 0;
      if (Character.isWhitespace(ch)) i++;
      else if (ch == '-' && next == '-') i = indexOrEnd(text, "\n", i);
      else if (ch == '/' && next == '*') {
        int close = text.indexOf("*/", i + 2);
        i = close < 0 ? n : close + 2;
      } else break;
    }
    if (i >= n) return false;
    if (text.charAt(i) == ',' || text.charAt(i) == ')') return true;
    int j = i;
    while (j < n && isIdent(text.charAt(j))) j++;
    return CONTINUE_WORDS.contains(text.substring(i, j).toUpperCase(Locale.ROOT));
  }

  private static int indexOrEnd(String text, String what, int from) {
    int k = text.indexOf(what, from);
    return k < 0 ? text.length() : k;
  }

  private static boolean isIdent(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
  }

  /** i 에서 시작하는 따옴표 글(' " ` q'[ ]' $tag$)의 끝 다음 위치. 따옴표가 아니면 i. 닫히지 않으면 글 끝. */
  private static int quotedEnd(String text, int i) {
    int n = text.length();
    char ch = text.charAt(i);
    boolean wordStart = i == 0 || !isIdent(text.charAt(i - 1));
    if ((ch == 'q' || ch == 'Q') && wordStart && i + 2 < n && text.charAt(i + 1) == '\'') {
      char open = text.charAt(i + 2);
      int k = "([{<".indexOf(open);
      char close = k >= 0 ? ")]}>".charAt(k) : open;
      int end = text.indexOf(close + "'", i + 3);
      return end < 0 ? n : end + 2;
    }
    if (ch == '\'' || ch == '"' || ch == '`') {
      int j = i + 1;
      while (j < n) {
        if (text.charAt(j) == ch) {
          if (j + 1 < n && text.charAt(j + 1) == ch) { j += 2; continue; }
          return j + 1;
        }
        j++;
      }
      return n;
    }
    if (ch == '$' && wordStart) {
      int j = i + 1;
      while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
      if (j < n && text.charAt(j) == '$') {
        String tag = text.substring(i, j + 1);
        int end = text.indexOf(tag, j + 1);
        return end < 0 ? n : end + tag.length();
      }
    }
    return i;
  }

  /** 문장 하나를 모으는 중의 상태. */
  private static final class Seg {
    int start = -1;
    int lastCode = -1;
    boolean block;
    boolean decided;
    boolean dollar;
    /** 닫히지 않은 ( 의 수. 괄호 안의 빈 줄은 문장 경계가 아니다. */
    int depth;
    /** 블록 판정용 앞 낱말들. ; 는 ";" 로 들어간다. */
    final List<String> words = new ArrayList<>();
    /** 열린 겹: BEGIN · CASE · DECL. */
    final Deque<String> open = new ArrayDeque<>();
    /** 하위 프로그램 머리(FUNCTION · PROCEDURE · CREATE 머리)를 지나 IS/AS 를 기다리는 중. ; 에서 풀린다(앞선 선언). */
    boolean header;
    /** CREATE 객체 이름을 기다리는 중과 그 이름(스키마를 뗀 대문자). 따옴표 이름이면 null 로 남는다. */
    boolean expectName;
    String name;
    /** END 를 읽고 그 뒤 낱말을 기다리는 중. */
    boolean pendingEnd;
    /** 바로 앞 END 로 겹이 모두 닫혔다. endLabel 은 그 END 뒤 이름(없으면 null). */
    boolean closed;
    String endLabel;

    void code(int i) {
      if (start < 0) start = i;
    }

    void word(String w, boolean dotNext) {
      words.add(w);
      if (!decided && decide()) return;
      if (!block) return;
      if (pendingEnd && afterEnd(w)) return;
      closed = false;
      if (expectName) {
        if (w.equals("BODY")) return;
        name = w;
        expectName = dotNext;
        return;
      }
      switch (w) {
        case "BEGIN" -> {
          if (DECL.equals(open.peek())) open.pop();
          open.push("BEGIN");
          header = false;
        }
        case "DECLARE" -> {
          // BEGIN 안의 DECLARE 는 MySQL 지역 선언이라 겹을 열지 않는다. Oracle 의 안쪽 DECLARE … BEGIN 은 이어지는 BEGIN 이 겹을 연다.
          if (!"BEGIN".equals(open.peek())) open.push(DECL);
          header = false;
        }
        case "CASE" -> open.push("CASE");
        case "IS", "AS" -> {
          if (header) open.push(DECL);
          header = false;
        }
        case "FUNCTION", "PROCEDURE" -> header = true;
        case "END" -> pendingEnd = true;
        default -> { }
      }
    }

    /** 낱말이 아닌 글(; · 따옴표 · 기호). */
    void other(char ch) {
      if (ch == '(') depth++;
      if (ch == ')' && depth > 0) depth--;
      if (!block) return;
      if (pendingEnd) afterEnd(null);
      if (ch != '.') expectName = false;
      if (ch == ';') header = false;
    }

    /** END 뒤 낱말(없으면 null)로 END 를 마무리한다. 그 낱말을 END 의 일부로 썼으면 참. */
    private boolean afterEnd(String next) {
      pendingEnd = false;
      if (next != null && NOT_BLOCK_END.contains(next)) return true;
      if (!open.isEmpty()) open.pop();
      if ("CASE".equals(next)) return true;
      closed = open.isEmpty();
      endLabel = next;
      return next != null;
    }

    /** 처음 몇 낱말로 블록인지 정한다. CREATE 머리 낱말을 여기서 다 썼으면 참. */
    private boolean decide() {
      String first = words.get(0);
      if (first.equals("BEGIN") || first.equals("DECLARE")) {
        block = true;
        decided = true;
        return false;
      }
      decided = true;
      if (!first.equals("CREATE")) return true;
      String last = words.get(words.size() - 1);
      if (words.size() == 1 || CREATE_MODIFIERS.contains(last) || last.equals("TYPE")) {
        decided = false;
        return true;
      }
      boolean typeBody = last.equals("BODY") && words.get(words.size() - 2).equals("TYPE");
      block = typeBody || Set.of("PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER").contains(last);
      expectName = block;
      // 트리거 머리에는 IS/AS 가 없다(REFERENCING … AS 는 별칭).
      header = block && !last.equals("TRIGGER");
      return true;
    }

    /** BEGIN 하나뿐(트랜잭션 시작)이면 블록이 아니다. */
    boolean endsAtSemicolon() {
      if (!block || (words.size() == 1 && words.get(0).equals("BEGIN"))) return true;
      if (dollar) return true;
      return closed && (name == null || endLabel == null || endLabel.equals(name));
    }

    void flush(String text, List<Stmt> out, int limit) {
      if (start < 0) return;
      int end = Math.min(lastCode, limit);
      while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
      if (end <= start || text.substring(start, end).equals(";")) return;
      out.add(new Stmt(start, end, text.substring(start, end)));
    }
  }
}
