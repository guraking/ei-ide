package ei.db.mybatis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SELECT 목록의 칼럼 이름과 resultType 클래스의 필드를 MyBatis 자동 매핑 규칙(대소문자 무시, 밑줄 → 낙타 표기)으로 견준다.
 */
public final class ResultColumns {
  private ResultColumns() {}

  private static final int SIMILAR_DISTANCE = 2;
  private static final Set<String> NOT_ALIAS = Set.of("END", "FROM", "DISTINCT", "ALL", "UNIQUE");

  /** 칼럼 없는 SELECT 값이거나 필드가 SELECT 에 없는 경우 하나. */
  public static final class Issue {
    /** true: SELECT 의 column 을 받을 필드가 없음. false: field 가 SELECT 에 없음. */
    public final boolean columnWithoutField;
    public final String column;
    public final String field;
    /** columnWithoutField 일 때 비슷한 필드 이름. 없으면 null. */
    public final String similar;

    Issue(boolean columnWithoutField, String column, String field, String similar) {
      this.columnWithoutField = columnWithoutField;
      this.column = column;
      this.field = field;
      this.similar = similar;
    }
  }

  /**
   * 맨 앞 SELECT 의 칼럼 이름들(별칭 우선, 테이블 접두 제거). 별칭 없는 식은 이름을 알 수 없어 뺀다.
   * SELECT 가 아니거나 * 를 쓰면 null(비교하지 않음).
   */
  public static List<String> of(String sql) {
    String s = blankQuotedAndComments(sql);
    int sel = keyword(s, "SELECT", 0);
    if (sel < 0) return null;
    int from = keyword(s, "FROM", sel + "SELECT".length());
    int stop = from < 0 ? s.length() : from;
    List<String> out = new ArrayList<>();
    int depth = 0, begin = sel + "SELECT".length();
    for (int i = begin; i <= stop; i++) {
      char c = i < stop ? s.charAt(i) : ',';
      if (c == '(') depth++;
      if (c == ')') depth--;
      if (c == ',' && depth == 0) {
        String item = sql.substring(begin, i).trim();
        if (item.endsWith("*")) return null;
        String name = name(item, out.isEmpty());
        if (name != null) out.add(name);
        begin = i + 1;
      }
    }
    return out;
  }

  private static String name(String item, boolean firstItem) {
    String masked = blankQuotedAndComments(item).trim();
    String raw = item.trim();
    if (firstItem) {
      for (String w : List.of("DISTINCT ", "ALL ", "UNIQUE ")) {
        if (masked.toUpperCase(Locale.ROOT).startsWith(w)) {
          masked = masked.substring(w.length()).trim();
          raw = raw.substring(raw.length() - masked.length());
        }
      }
    }
    int cut = lastTopLevelSpace(masked);
    String last = cut < 0 ? raw : raw.substring(cut + 1).trim();
    if (cut >= 0) {
      String prev = masked.substring(0, cut).trim();
      boolean as = prev.toUpperCase(Locale.ROOT).endsWith(" AS") || prev.equalsIgnoreCase("AS");
      char end = prev.isEmpty() ? ' ' : prev.charAt(prev.length() - 1);
      boolean operator = "+-*/|=<>,(".indexOf(end) >= 0;
      if (!as && (operator || NOT_ALIAS.contains(last.toUpperCase(Locale.ROOT)))) return null;
    } else if (last.contains("(")) {
      return null;
    }
    if (last.startsWith("\"") && last.endsWith("\"") && last.length() > 2) return last.substring(1, last.length() - 1);
    if (last.contains(".")) last = last.substring(last.lastIndexOf('.') + 1);
    return last.matches("[A-Za-z_][\\w$#]*") ? last.toUpperCase(Locale.ROOT) : null;
  }

  private static int lastTopLevelSpace(String s) {
    int depth = 0, found = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      if (c == ')') depth--;
      if (Character.isWhitespace(c) && depth == 0) found = i;
    }
    return found;
  }

  /** 문자열 · 주석 내용을 공백으로 바꿔 위치는 그대로 두고 괄호 · 키워드 찾기에서 빼낸다. 따옴표 이름은 남긴다. */
  private static String blankQuotedAndComments(String s) {
    StringBuilder b = new StringBuilder(s);
    int i = 0;
    while (i < s.length()) {
      int skip = SqlValues.skipQuotedOrComment(s, i);
      if (skip > i && s.charAt(i) != '"') {
        for (int k = i; k < skip; k++) {
          if (b.charAt(k) != '\n') b.setCharAt(k, ' ');
        }
      }
      i = Math.max(skip, i + 1);
    }
    return b.toString();
  }

  /** 괄호 밖에 낱말로 선 keyword 의 위치. 없으면 -1. */
  private static int keyword(String s, String word, int from) {
    String upper = s.toUpperCase(Locale.ROOT);
    int depth = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      if (c == ')') depth--;
      if (i < from || depth != 0 || !upper.startsWith(word, i)) continue;
      boolean before = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_';
      int e = i + word.length();
      boolean after = e >= s.length() || !Character.isLetterOrDigit(s.charAt(e)) && s.charAt(e) != '_';
      if (before && after) return i;
    }
    return -1;
  }

  /** ORDER_ID → orderId. 이미 소문자가 섞인 이름(따옴표 별칭)은 그대로. */
  public static String camel(String column) {
    if (!column.equals(column.toUpperCase(Locale.ROOT))) return column;
    StringBuilder b = new StringBuilder();
    boolean up = false;
    for (char c : column.toLowerCase(Locale.ROOT).toCharArray()) {
      if (c == '_') {
        up = b.length() > 0;
        continue;
      }
      b.append(up ? Character.toUpperCase(c) : c);
      up = false;
    }
    return b.toString();
  }

  private static String key(String name) { return name.replace("_", "").toLowerCase(Locale.ROOT); }

  /** 칼럼 → 필드 없음을 먼저, 그다음 SELECT 에 없는 필드를 필드 차례로. */
  public static List<Issue> compare(List<String> columns, List<String> fields) {
    List<Issue> out = new ArrayList<>();
    Set<String> fieldKeys = new HashSet<>();
    for (String f : fields) fieldKeys.add(key(f));
    Set<String> columnKeys = new HashSet<>();
    for (String c : columns) columnKeys.add(key(c));
    List<String> unused = new ArrayList<>();
    for (String f : fields) {
      if (!columnKeys.contains(key(f))) unused.add(f);
    }
    for (String c : columns) {
      if (fieldKeys.contains(key(c))) continue;
      out.add(new Issue(true, c, camel(c), similar(camel(c), unused)));
    }
    for (String f : unused) out.add(new Issue(false, null, f, null));
    return out;
  }

  private static String similar(String name, List<String> candidates) {
    String n = name.toLowerCase(Locale.ROOT);
    String best = null;
    int bestScore = Integer.MAX_VALUE;
    for (String c : candidates) {
      String l = c.toLowerCase(Locale.ROOT);
      int d = distance(n, l);
      boolean near = d <= SIMILAR_DISTANCE || l.contains(n) || n.contains(l) || subsequence(n, l);
      if (near && d < bestScore) {
        best = c;
        bestScore = d;
      }
    }
    return best;
  }

  /** a 의 글자가 같은 첫 글자로 시작해 b 안에 차례대로 들어 있는지(qty → quantity). */
  private static boolean subsequence(String a, String b) {
    if (a.isEmpty() || b.isEmpty() || a.charAt(0) != b.charAt(0)) return false;
    int k = 0;
    for (int i = 0; i < b.length() && k < a.length(); i++) {
      if (b.charAt(i) == a.charAt(k)) k++;
    }
    return k == a.length();
  }

  static int distance(String a, String b) {
    int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return prev[b.length()];
  }
}
