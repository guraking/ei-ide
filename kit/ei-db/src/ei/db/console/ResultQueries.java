package ei.db.console;

import ei.db.DbKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 결과 표에서 고른 칼럼 · 행으로 만드는 쿼리(값별 건수 · 중복 찾기 · 묶음별 N건 · 피벗 · 계층). IntelliJ 클래스를 쓰지 않는다.
 * 원래 조회 문장을 FROM ( … ) x 로 감싸므로 표 하나를 조회한 결과가 아니어도 된다. 칼럼 이름은 부르는 쪽이 SqlText.ident 로 표기해 넘긴다.
 * 모양은 저장소 지침대로: SELECT 는 키워드만 한 줄, 쉼표는 줄 앞, 옛 방식 조인.
 */
final class ResultQueries {
  private ResultQueries() {}

  /** 피벗으로 옆에 펼칠 값의 상한. 넘으면 칼럼이 너무 많아 읽을 수 없다. */
  static final int MAX_PIVOT_VALUES = 50;
  /** 계층 쿼리의 시작 행 상한(Oracle IN 목록의 한계). */
  static final int MAX_TREE_STARTS = 1000;
  /** PostgreSQL · MySQL 재귀 조회가 순환 데이터에서 끝나도록 두는 깊이 상한(Oracle 은 NOCYCLE 을 쓴다). */
  private static final int MAX_TREE_DEPTH = 50;
  /** 피벗 칼럼 별칭의 글자 상한(Oracle 12.1 이하의 이름 길이 한계). */
  private static final int ALIAS_LENGTH = 30;
  private static final String INDENT = "        ";
  private static final String FIRST = "\n       ";
  private static final String NEXT = "\n     , ";

  /** 끝의 ; 와 공백을 뺀 원래 문장을 FROM 의 하위 조회로: 「  FROM (\n 문장 \n       ) x」. */
  private static String from(String base, String indent) {
    String sql = base.strip();
    while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).strip();
    return "\n" + indent + "  FROM (\n" + (indent + INDENT + sql).replace("\n", "\n" + indent + INDENT) + "\n" + indent + "       ) x";
  }

  private static String list(List<String> columns, String prefix) {
    StringBuilder sb = new StringBuilder();
    for (String c : columns) sb.append(sb.isEmpty() ? "" : ", ").append(prefix).append(c);
    return sb.toString();
  }

  /** 고른 칼럼의 값 묶음마다 건수와 전체 대비 비율(%), 많은 순. */
  static String counts(String base, List<String> columns) {
    StringBuilder sb = new StringBuilder("SELECT");
    for (int i = 0; i < columns.size(); i++) sb.append(i == 0 ? FIRST : NEXT).append(columns.get(i));
    sb.append(NEXT).append("COUNT(*) AS CNT");
    sb.append(NEXT).append("ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS PCT");
    sb.append(from(base, ""));
    sb.append("\n GROUP BY ").append(list(columns, ""));
    sb.append("\n ORDER BY CNT DESC\n");
    return sb.toString();
  }

  /** 고른 칼럼의 값이 같은 행이 둘 이상인 행만(겹친 건수 DUP_CNT 와 함께), 겹친 값끼리 모아서. */
  static String duplicates(String base, List<String> columns) {
    return "SELECT" + FIRST + "y.*"
        + "\n  FROM (\n" + INDENT + "SELECT" + "\n" + INDENT + "       x.*"
        + "\n" + INDENT + "     , COUNT(*) OVER (PARTITION BY " + list(columns, "x.") + ") AS DUP_CNT"
        + from(base, INDENT)
        + "\n       ) y"
        + "\n WHERE y.DUP_CNT > 1"
        + "\n ORDER BY " + list(columns, "y.") + "\n";
  }

  /** 분석 함수(… OVER (PARTITION BY … ORDER BY …))의 종류. value 는 값 칼럼이 필요한지, ranking 은 「앞에서 N건만」을 걸 수 있는지. */
  enum Window {
    ROW_NUMBER(false, true), RANK(false, true), DENSE_RANK(false, true),
    RUNNING_SUM(true, false), GROUP_SUM(true, false), GROUP_COUNT(false, false), LAG(true, false), LEAD(true, false);

    final boolean value;
    final boolean ranking;

    Window(boolean value, boolean ranking) {
      this.value = value;
      this.ranking = ranking;
    }
  }

  /**
   * 원래 결과의 모든 칼럼 뒤에 분석 함수 칼럼을 붙인다. groups 는 PARTITION BY(비어 있으면 전체가 한 묶음), order 는 ORDER BY 칼럼(descending 이면 큰 값부터).
   * value 는 Window.value 인 함수의 값 칼럼. numeric 이면 LAG 에 앞 행과의 차이(DIFF) 칼럼도 붙인다.
   * top 이 1 이상이고 순번 · 순위 함수면 묶음마다 그 순위까지만 남긴다(1 이면 묶음별 첫 1건). 0 이면 모두.
   */
  static String window(String base, Window fn, List<String> groups, String order, boolean descending, String value, boolean numeric, int top) {
    String partition = groups.isEmpty() ? "" : "PARTITION BY " + list(groups, "x.");
    String ordered = "(" + partition + (partition.isEmpty() ? "" : " ") + "ORDER BY x." + order + (descending ? " DESC" : "") + ")";
    String grouped = "(" + partition + ")";
    List<String> added = new ArrayList<>();
    switch (fn) {
      case ROW_NUMBER -> added.add("ROW_NUMBER() OVER " + ordered + " AS RN");
      case RANK -> added.add("RANK() OVER " + ordered + " AS RN");
      case DENSE_RANK -> added.add("DENSE_RANK() OVER " + ordered + " AS RN");
      case RUNNING_SUM -> added.add("SUM(x." + value + ") OVER " + ordered + " AS RUN_SUM");
      case GROUP_SUM -> {
        added.add("SUM(x." + value + ") OVER " + grouped + " AS GRP_SUM");
        added.add("ROUND(x." + value + " * 100.0 / NULLIF(SUM(x." + value + ") OVER " + grouped + ", 0), 1) AS GRP_PCT");
      }
      case GROUP_COUNT -> added.add("COUNT(*) OVER " + grouped + " AS GRP_CNT");
      case LAG -> {
        added.add("LAG(x." + value + ") OVER " + ordered + " AS PREV_VAL");
        if (numeric) added.add("x." + value + " - LAG(x." + value + ") OVER " + ordered + " AS DIFF");
      }
      case LEAD -> added.add("LEAD(x." + value + ") OVER " + ordered + " AS NEXT_VAL");
    }
    boolean cut = top > 0 && fn.ranking;
    String indent = cut ? INDENT : "";
    StringBuilder inner = new StringBuilder(indent).append("SELECT\n").append(indent).append("       x.*");
    for (String a : added) inner.append("\n").append(indent).append("     , ").append(a);
    inner.append(from(base, indent));
    String alias = cut ? "y." : "x.";
    String sort = (groups.isEmpty() ? "" : list(groups, alias) + ", ") + alias + (cut ? "RN" : order + (descending ? " DESC" : ""));
    if (!cut) return inner + "\n ORDER BY " + sort + "\n";
    return "SELECT" + FIRST + "y.*\n  FROM (\n" + inner + "\n       ) y"
        + "\n WHERE y.RN " + (top == 1 ? "= 1" : "<= " + top)
        + "\n ORDER BY " + sort + "\n";
  }

  /**
   * rows 칼럼으로 묶고 pivot 칼럼의 값(values)마다 칼럼 하나씩 옆으로 펼친다: 집계(CASE WHEN pivot = 값 THEN value END).
   * Oracle · PostgreSQL · MySQL 에서 같은 글로 돈다. values 의 null 은 IS NULL 로 견준다. 별칭은 값 글자(ALIAS_LENGTH 까지, 겹치면 번호를 붙임).
   */
  static String pivot(String base, List<String> rows, String pivot, String value, String aggregate, List<Object> values, DbKind kind) {
    if (values.isEmpty() || values.size() > MAX_PIVOT_VALUES) throw new IllegalArgumentException("values: " + values.size());
    StringBuilder sb = new StringBuilder("SELECT");
    for (int i = 0; i < rows.size(); i++) sb.append(i == 0 ? FIRST : NEXT).append(rows.get(i));
    List<String> used = new ArrayList<>();
    for (Object v : values) {
      String condition = v == null ? pivot + " IS NULL" : pivot + " = " + SqlText.literal(v, kind);
      sb.append(sb.length() == "SELECT".length() ? FIRST : NEXT)
          .append(aggregate).append("(CASE WHEN ").append(condition).append(" THEN ").append(value).append(" END) AS ").append(alias(v, used, kind));
    }
    sb.append(from(base, ""));
    if (!rows.isEmpty()) sb.append("\n GROUP BY ").append(list(rows, "")).append("\n ORDER BY ").append(list(rows, ""));
    return sb.append('\n').toString();
  }

  private static String alias(Object value, List<String> used, DbKind kind) {
    String text = value == null ? "NULL" : String.valueOf(value).strip();
    if (text.isEmpty()) text = "EMPTY";
    text = text.replace("\"", "").replace("`", "").replace("\n", " ");
    if (text.length() > ALIAS_LENGTH) text = text.substring(0, ALIAS_LENGTH);
    String name = text;
    for (int n = 2; used.contains(name.toUpperCase(java.util.Locale.ROOT)); n++) {
      String tail = "_" + n;
      name = text.substring(0, Math.min(text.length(), ALIAS_LENGTH - tail.length())) + tail;
    }
    used.add(name.toUpperCase(java.util.Locale.ROOT));
    char quote = kind == DbKind.MYSQL ? '`' : '"';
    return quote + name + quote;
  }

  /**
   * child(자기 ID) · parent(부모 ID) 칼럼으로 잇는 계층 조회. 단계(LV) · 들여쓴 이름(TREE_NAME) · 경로(PATH) 칼럼을 앞에 붙인다.
   * starts 가 null 이면 parent 가 NULL 인 행에서, 아니면 child 값이 starts 에 든 행에서 시작한다(비어 있으면 안 됨, MAX_TREE_STARTS 까지).
   * down 이면 자식 쪽으로 내려가고 아니면 부모 쪽으로 올라간다. Oracle 은 CONNECT BY, 그 밖은 WITH RECURSIVE.
   */
  static String tree(String base, String child, String parent, String name, List<Object> starts, boolean down, DbKind kind) {
    if (starts != null && (starts.isEmpty() || starts.size() > MAX_TREE_STARTS)) throw new IllegalArgumentException("starts: " + starts.size());
    String start;
    if (starts == null) {
      start = parent + " IS NULL";
    } else {
      StringBuilder in = new StringBuilder();
      for (Object v : starts) in.append(in.isEmpty() ? "" : ", ").append(SqlText.literal(v, kind));
      start = child + (starts.size() == 1 ? " = " + in : " IN (" + in + ")");
    }
    if (kind == DbKind.ORACLE) {
      return "SELECT" + FIRST + "LEVEL AS LV"
          + NEXT + "LPAD(' ', (LEVEL - 1) * 2) || x." + name + " AS TREE_NAME"
          + NEXT + "SYS_CONNECT_BY_PATH(x." + name + ", ' > ') AS PATH"
          + NEXT + "x.*"
          + from(base, "")
          + "\n START WITH x." + start
          + "\nCONNECT BY NOCYCLE " + (down ? "PRIOR x." + child + " = x." + parent : "PRIOR x." + parent + " = x." + child)
          + "\n ORDER SIBLINGS BY x." + name + "\n";
    }
    // shortcut: MySQL 은 || 가 이어 붙이기가 아니고 CAST AS TEXT 도 없다. MySQL 접속에서 쓰게 되면 CONCAT · CHAR 로 가른다.
    String text = "CAST(s." + name + " AS TEXT)";
    String sql = base.strip();
    while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).strip();
    return "WITH RECURSIVE src AS (\n" + sql + "\n), tree AS (\n"
        + "SELECT" + FIRST + "1 AS tree_lv" + NEXT + text + " AS tree_path" + NEXT + "s.*"
        + "\n  FROM src s"
        + "\n WHERE s." + start
        + "\n UNION ALL\n"
        + "SELECT" + FIRST + "t.tree_lv + 1" + NEXT + "t.tree_path || ' > ' || " + text + NEXT + "s.*"
        + "\n  FROM src s, tree t"
        + "\n WHERE " + (down ? "s." + parent + " = t." + child : "s." + child + " = t." + parent)
        + "\n   AND t.tree_lv < " + MAX_TREE_DEPTH
        + "\n)\n"
        + "SELECT" + FIRST + "REPEAT('  ', t.tree_lv - 1) || CAST(t." + name + " AS TEXT) AS tree_name" + NEXT + "t.*"
        + "\n  FROM tree t"
        + "\n ORDER BY t.tree_path\n";
  }

  /** 행들을 칼럼 이름을 키로 한 JSON 배열로. 숫자 · 참거짓은 그대로, null 은 null, 그 밖은 texts 가 준 글자를 문자열로. 두 칸 들여쓰기. */
  static String json(List<String> columns, List<List<Object>> rows, java.util.function.Function<Object, String> texts) {
    StringBuilder sb = new StringBuilder("[");
    for (int r = 0; r < rows.size(); r++) {
      sb.append(r == 0 ? "\n  {" : ",\n  {");
      for (int c = 0; c < columns.size(); c++) {
        Object v = rows.get(r).get(c);
        sb.append(c == 0 ? "\n    " : ",\n    ").append(quote(columns.get(c))).append(": ");
        if (v == null) sb.append("null");
        else if (v instanceof java.math.BigDecimal d) sb.append(d.toPlainString());
        else if (v instanceof Boolean || (v instanceof Number n && Double.isFinite(n.doubleValue()))) sb.append(v);
        else sb.append(quote(texts.apply(v)));
      }
      sb.append("\n  }");
    }
    return sb.append(rows.isEmpty() ? "]\n" : "\n]\n").toString();
  }

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
          else sb.append(ch);
        }
      }
    }
    return sb.append('"').toString();
  }
}
