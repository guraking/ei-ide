package ei.db.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 구문 분석 대신 쓰는 얕은 SQL 나누기. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 * 문자열 · 주석을 같은 길이의 공백으로 가린 글(mask)에서 키워드를 찾으므로, 찾은 위치는 원문에 그대로 맞는다.
 * 괄호 깊이 0 의 키워드만 절의 시작으로 본다(서브쿼리 · 함수 안은 한 덩어리).
 */
public final class SqlParts {
  private SqlParts() {}

  /** 절 하나. key 는 공백 하나로 줄인 대문자 키워드(「GROUP BY」), body 는 키워드 뒤부터 다음 절 앞까지의 원문(앞뒤 공백 뺌). */
  public record Part(String key, int start, int bodyStart, int end, String body) {}

  /** FROM 의 표 하나. join 은 첫 표 · 쉼표로 나열한 표면 null, 아니면 INNER · LEFT · RIGHT · FULL · CROSS. on 은 ON 뒤 조건(없으면 null). */
  private static final String LATERAL = "LATERAL";

  public record Source(String name, String alias, String join, String on, boolean subquery, String text) {
    /** 조건에서 이 표를 부르는 이름(별칭, 없으면 표 이름). */
    public String ref() { return alias != null ? alias : simpleName(name); }
  }

  private static final Pattern KEYWORD = Pattern.compile(
      "(?<![\\w$#.])(GROUP\\s+BY|ORDER\\s+(?:SIBLINGS\\s+)?BY|CONNECT\\s+BY|START\\s+WITH|UNION\\s+ALL|UNION|MINUS|EXCEPT|INTERSECT"
      + "|FETCH|LIMIT|OFFSET|FOR\\s+UPDATE|WITH|SELECT|FROM|WHERE|HAVING|UPDATE|DELETE|INSERT|MERGE|SET|VALUES|INTO|USING)(?![\\w$#])");
  /** 문장 맨 앞에서만 절로 보는 키워드(START WITH · FOR UPDATE · DO UPDATE 와 가르려고). */
  private static final Set<String> LEADING = Set.of("WITH", "UPDATE", "DELETE", "INSERT", "MERGE");
  public static final Set<String> SET_OPERATORS = Set.of("UNION", "UNION ALL", "MINUS", "EXCEPT", "INTERSECT");
  private static final Pattern BLANKS = Pattern.compile("\\s+");
  private static final Pattern JOIN = Pattern.compile(
      "(?<![\\w$#])(?:NATURAL\\s+)?(INNER\\s+|CROSS\\s+|(?:LEFT|RIGHT|FULL)(?:\\s+OUTER)?\\s+)?JOIN(?![\\w$#])");
  private static final Pattern ON = Pattern.compile("(?<![\\w$#])ON(?![\\w$#])");
  private static final Pattern AND = Pattern.compile("(?<![\\w$#])(AND|BETWEEN)(?![\\w$#])");
  private static final Pattern NESTED_SELECT = Pattern.compile("\\(\\s*(SELECT|WITH)\\b");
  private static final Pattern REF = Pattern.compile("(?<![\\w$#.\"])([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\.\\s*(?=[A-Za-z_\"*])");
  private static final Set<String> NOT_ALIAS = Set.of("ON", "WHERE", "USING", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS",
      "JOIN", "NATURAL", "GROUP", "ORDER", "CONNECT", "START", "UNION", "MINUS", "EXCEPT", "INTERSECT", "FETCH", "LIMIT", "OFFSET", "FOR",
      "SET", "PARTITION", "SAMPLE", "WITH", "HAVING", "WINDOW", "LATERAL", "PIVOT", "UNPIVOT");

  /** 문자열('' 이스케이프) · 줄 주석 · 블록 주석을 공백으로 가린다. 따옴표 문자와 줄바꿈은 남겨 위치 · 줄이 같다. */
  public static String mask(String sql) {
    char[] out = sql.toCharArray();
    int n = out.length;
    int i = 0;
    while (i < n) {
      char ch = sql.charAt(i);
      char next = i + 1 < n ? sql.charAt(i + 1) : 0;
      if (ch == '-' && next == '-') {
        while (i < n && sql.charAt(i) != '\n') out[i++] = ' ';
      } else if (ch == '/' && next == '*') {
        int k = sql.indexOf("*/", i + 2);
        int stop = k < 0 ? n : k + 2;
        for (; i < stop; i++) if (out[i] != '\n') out[i] = ' ';
      } else if (ch == '\'') {
        i++;
        while (i < n) {
          if (sql.charAt(i) == '\'') {
            if (i + 1 < n && sql.charAt(i + 1) == '\'') { out[i] = ' '; out[i + 1] = ' '; i += 2; continue; }
            break;
          }
          if (out[i] != '\n') out[i] = ' ';
          i++;
        }
        i++;
      } else {
        i++;
      }
    }
    return new String(out);
  }

  /** 글자마다 괄호 깊이(여는 괄호 자신은 바깥 깊이). masked 를 받는다. */
  static int[] depth(String masked) {
    int[] d = new int[masked.length()];
    int depth = 0;
    for (int i = 0; i < masked.length(); i++) {
      char ch = masked.charAt(i);
      if (ch == ')' && depth > 0) depth--;
      d[i] = depth;
      if (ch == '(') depth++;
    }
    return d;
  }

  /** 깊이 0 의 절들(앞에서부터). 맨 앞 키워드 앞의 글은 버린다. 끝의 ; 는 마지막 절에서 뺀다. */
  public static List<Part> split(String sql) {
    String masked = mask(sql);
    String upper = masked.toUpperCase(Locale.ROOT);
    int[] depth = depth(masked);
    int first = firstToken(masked);
    List<int[]> marks = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    Matcher m = KEYWORD.matcher(upper);
    while (m.find()) {
      if (depth[m.start()] != 0) continue;
      String key = BLANKS.matcher(m.group(1)).replaceAll(" ");
      if (LEADING.contains(key) && m.start() != first) continue;
      String prev = keys.isEmpty() ? "" : keys.get(keys.size() - 1);
      if (key.equals("INTO") && !(prev.equals("INSERT") || prev.equals("MERGE"))) continue;
      if (key.equals("USING") && !prev.equals("INTO")) continue;
      if (key.equals("SET") && !prev.equals("UPDATE")) continue;
      if (key.equals("VALUES") && !(prev.equals("INTO") || prev.equals("INSERT"))) continue;
      marks.add(new int[]{m.start(), m.end()});
      keys.add(key);
    }
    int end = sql.length();
    while (end > 0 && (Character.isWhitespace(masked.charAt(end - 1)) || masked.charAt(end - 1) == ';')) end--;
    List<Part> out = new ArrayList<>();
    for (int i = 0; i < marks.size(); i++) {
      int stop = i + 1 < marks.size() ? marks.get(i + 1)[0] : Math.max(end, marks.get(i)[1]);
      int bodyStart = marks.get(i)[1];
      out.add(new Part(keys.get(i), marks.get(i)[0], bodyStart, stop, sql.substring(bodyStart, Math.max(bodyStart, stop)).strip()));
    }
    return out;
  }

  /** 첫 SELECT 묶음(UNION 등 집합 연산 앞까지). WITH 절은 앞에 그대로 둔다. */
  public static List<Part> mainBlock(List<Part> parts) {
    List<Part> out = new ArrayList<>();
    for (Part p : parts) {
      if (SET_OPERATORS.contains(p.key())) break;
      out.add(p);
    }
    return out;
  }

  /** key 인 첫 절. 없으면 null. */
  public static Part find(List<Part> parts, String key) {
    for (Part p : parts) if (p.key().equals(key)) return p;
    return null;
  }

  /** 깊이 0 의 쉼표로 나눈 조각(앞뒤 공백 뺌, 빈 조각 뺌). */
  public static List<String> splitCommas(String text) {
    String masked = mask(text);
    int[] depth = depth(masked);
    List<String> out = new ArrayList<>();
    int from = 0;
    for (int i = 0; i <= text.length(); i++) {
      if (i == text.length() || (masked.charAt(i) == ',' && depth[i] == 0)) {
        String piece = text.substring(from, i).strip();
        if (!piece.isEmpty()) out.add(piece);
        from = i + 1;
      }
    }
    return out;
  }

  /** 깊이 0 의 AND 로 나눈 조건들. BETWEEN a AND b 의 AND 는 나누지 않는다. 바깥을 감싼 괄호 하나는 그대로 둔다. */
  public static List<String> splitAnd(String text) {
    String masked = mask(text);
    String upper = masked.toUpperCase(Locale.ROOT);
    int[] depth = depth(masked);
    List<String> out = new ArrayList<>();
    int from = 0;
    boolean between = false;
    Matcher m = AND.matcher(upper);
    while (m.find()) {
      if (depth[m.start()] != 0) continue;
      if (m.group(1).equals("BETWEEN")) { between = true; continue; }
      if (between) { between = false; continue; }
      String piece = text.substring(from, m.start()).strip();
      if (!piece.isEmpty()) out.add(piece);
      from = m.end();
    }
    String last = text.substring(from).strip();
    if (!last.isEmpty()) out.add(last);
    return out;
  }

  /** FROM 절 본문을 표들로 나눈다(쉼표 · JOIN). 구분자는 깊이 0 의 것만 본다. */
  public static List<Source> sources(String from) {
    String masked = mask(from);
    String upper = masked.toUpperCase(Locale.ROOT);
    int[] depth = depth(masked);
    // 구분자마다 [시작, 끝]과 그 뒤 표의 조인 종류(쉼표면 null). 위치 순서로 모은다.
    java.util.TreeMap<Integer, Object[]> seps = new java.util.TreeMap<>();
    for (int i = 0; i < masked.length(); i++) {
      if (masked.charAt(i) == ',' && depth[i] == 0) seps.put(i, new Object[]{i + 1, null});
    }
    Matcher j = JOIN.matcher(upper);
    while (j.find()) {
      if (depth[j.start()] != 0) continue;
      String kind = j.group(1) == null ? "INNER" : BLANKS.matcher(j.group(1).strip()).replaceAll(" ").replace(" OUTER", "");
      seps.put(j.start(), new Object[]{j.end(), kind});
    }
    List<Source> out = new ArrayList<>();
    int start = 0;
    String join = null;
    for (java.util.Map.Entry<Integer, Object[]> e : seps.entrySet()) {
      add(out, from.substring(start, e.getKey()), join);
      start = (Integer) e.getValue()[0];
      join = (String) e.getValue()[1];
    }
    add(out, from.substring(start), join);
    return out;
  }

  private static void add(List<Source> out, String piece, String join) {
    if (!piece.isBlank()) out.add(source(piece.strip(), join));
  }

  private static Source source(String piece, String join) {
    String masked = mask(piece);
    int[] depth = depth(masked);
    String upper = masked.toUpperCase(Locale.ROOT);
    String item = piece;
    String on = null;
    Matcher m = ON.matcher(upper);
    while (m.find()) {
      if (depth[m.start()] == 0) {
        item = piece.substring(0, m.start()).strip();
        on = piece.substring(m.end()).strip();
        break;
      }
    }
    // LATERAL (…) 은 안쪽에서 바깥 표를 부르므로 잇는 조건이 안에 있다. 낱말을 떼고 괄호 칸으로 읽는다.
    if (item.regionMatches(true, 0, LATERAL, 0, LATERAL.length()) && item.length() > LATERAL.length()
        && !Character.isLetterOrDigit(item.charAt(LATERAL.length())) && item.charAt(LATERAL.length()) != '_') {
      item = item.substring(LATERAL.length()).strip();
    }
    if (item.startsWith("(")) {
      int close = matching(mask(item), 0);
      String rest = close < 0 ? "" : item.substring(close + 1).strip();
      return new Source(item.substring(0, close < 0 ? item.length() : close + 1), alias(rest.split("\\s+")), join, on, true, piece);
    }
    // 표 함수(GENERATE_SERIES(1, 10) AS g(N)): 이름은 닫는 괄호까지, 그 뒤가 별칭.
    int paren = item.indexOf('(');
    java.util.regex.Matcher space = BLANKS.matcher(item);
    if (paren > 0 && (!space.find() || paren < space.start())) {
      int close = matching(mask(item), paren);
      if (close > 0) return new Source(item.substring(0, close + 1), alias(item.substring(close + 1).strip().split("\\s+")), join, on, false, piece);
    }
    String[] words = item.split("\\s+");
    String name = words[0];
    String[] rest = new String[words.length - 1];
    System.arraycopy(words, 1, rest, 0, rest.length);
    return new Source(name, alias(rest), join, on, false, piece);
  }

  private static String alias(String[] words) {
    int k = 0;
    if (k < words.length && words[k].equalsIgnoreCase("AS")) k++;
    if (k >= words.length || words[k].isEmpty()) return null;
    String a = words[k].replaceFirst("\\(.*$", "");
    if (a.isEmpty()) return null;
    return NOT_ALIAS.contains(a.toUpperCase(Locale.ROOT)) ? null : a;
  }

  /** masked 의 open 위치 여는 괄호에 맞는 닫는 괄호 위치. 없으면 -1. */
  static int matching(String masked, int open) {
    int depth = 0;
    for (int i = open; i < masked.length(); i++) {
      if (masked.charAt(i) == '(') depth++;
      if (masked.charAt(i) == ')' && --depth == 0) return i;
    }
    return -1;
  }

  /** 조건에 나온 「이름.」 꼴의 표 별칭들(대문자, 따옴표 뺌). 괄호 안 서브쿼리는 보지 않는다. */
  public static Set<String> refs(String cond) {
    String masked = blankNested(mask(cond));
    Set<String> out = new java.util.LinkedHashSet<>();
    Matcher m = REF.matcher(masked);
    while (m.find()) out.add(m.group(1).replace("\"", "").toUpperCase(Locale.ROOT));
    return out;
  }

  /** 서브쿼리 「(SELECT …)」 를 공백으로 가린 글. masked 를 받는다. */
  static String blankNested(String masked) {
    char[] out = masked.toCharArray();
    Matcher m = NESTED_SELECT.matcher(masked.toUpperCase(Locale.ROOT));
    while (m.find()) {
      int close = matching(masked, m.start());
      int stop = close < 0 ? out.length : close + 1;
      for (int i = m.start(); i < stop; i++) if (out[i] != '\n') out[i] = ' ';
    }
    return new String(out);
  }

  /** 스키마 · 따옴표를 뗀 이름. */
  public static String simpleName(String name) {
    String n = name.replace("\"", "");
    return n.substring(n.lastIndexOf('.') + 1);
  }

  private static int firstToken(String masked) {
    for (int i = 0; i < masked.length(); i++) if (!Character.isWhitespace(masked.charAt(i))) return i;
    return 0;
  }

  /** 공백 · 줄바꿈을 한 칸으로 줄인 한 줄 글(설명 · 경고에 SQL 을 보일 때). */
  public static String oneLine(String text) {
    return BLANKS.matcher(text.strip()).replaceAll(" ");
  }
}
