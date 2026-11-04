package ei.db.console;

import ei.db.console.PlanAdvisor.Kind;
import ei.db.console.PlanAdvisor.Step;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DB 마다 다른 실행 계획 결과를 PlanAdvisor.Step 목록으로 바꾼다. IntelliJ 클래스 없이 돈다(PlanAdvisorCheck). */
public final class PlanParse {
  private PlanParse() {}

  /** PostgreSQL 마디 줄: 「  ->  Seq Scan on t a  (cost=0.00..12.50 rows=250 width=36)」. */
  private static final Pattern PG_NODE = Pattern.compile("^(\\s*)(?:->\\s+)?(.+?)\\s+\\(cost=[\\d.]+\\.\\.([\\d.]+) rows=(\\d+) width=\\d+\\)");
  private static final Pattern PG_DETAIL = Pattern.compile("^\\s*(Filter|Join Filter|Index Cond|Hash Cond|Merge Cond|Recheck Cond):\\s*(.*)$");
  private static final Pattern PG_ON = Pattern.compile("\\bon\\s+(?:\\w+\\.)?(\\w+)");
  private static final Pattern PG_ACCESS = Pattern.compile("Index Cond|Hash Cond|Merge Cond|Recheck Cond");

  /**
   * Oracle PLAN_TABLE · V$SQL_PLAN_STATISTICS_ALL 의 행. 칼럼은 이름으로 찾는다(대소문자 무시).
   * 쓰는 칼럼: ID, PARENT_ID, DEPTH, OPERATION, OPTIONS, OBJECT_OWNER, OBJECT_NAME, COST, CARDINALITY,
   * ACCESS_PREDICATES, FILTER_PREDICATES, 실제 값은 LAST_STARTS, LAST_OUTPUT_ROWS, LAST_CR_BUFFER_GETS, LAST_ELAPSED_TIME(마이크로초).
   */
  public static List<Step> oracle(List<String> columns, List<List<Object>> rows) {
    List<Step> out = new ArrayList<>();
    for (List<Object> r : rows) {
      String op = text(columns, r, "OPERATION");
      String options = text(columns, r, "OPTIONS");
      Long parent = number(columns, r, "PARENT_ID");
      Long depth = number(columns, r, "DEPTH");
      out.add(new Step(number(columns, r, "ID").intValue(), parent == null ? -1 : parent.intValue(), depth == null ? 0 : depth.intValue(),
          (op + " " + options).strip(), oracleKind(op, options), text(columns, r, "OBJECT_OWNER"), text(columns, r, "OBJECT_NAME"),
          number(columns, r, "COST"), number(columns, r, "CARDINALITY"),
          text(columns, r, "ACCESS_PREDICATES"), text(columns, r, "FILTER_PREDICATES"), "",
          number(columns, r, "LAST_STARTS"), number(columns, r, "LAST_OUTPUT_ROWS"),
          number(columns, r, "LAST_CR_BUFFER_GETS"), number(columns, r, "LAST_ELAPSED_TIME")));
    }
    return out;
  }

  static Kind oracleKind(String op, String options) {
    String o = options == null ? "" : options;
    return switch (op == null ? "" : op) {
      case "TABLE ACCESS" -> o.contains("FULL") ? Kind.FULL_SCAN : Kind.OTHER;
      case "INDEX" -> o.contains("SKIP SCAN") ? Kind.INDEX_SKIP : o.contains("FULL SCAN") ? Kind.INDEX_FULL : Kind.OTHER;
      case "MERGE JOIN" -> o.contains("CARTESIAN") ? Kind.CARTESIAN : Kind.OTHER;
      case "NESTED LOOPS" -> Kind.NESTED_LOOP;
      // SORT AGGREGATE 는 건수를 하나로 모으는 일이라 정렬이 아니다.
      case "SORT" -> o.contains("AGGREGATE") ? Kind.OTHER : Kind.SORT;
      case "HASH" -> o.contains("GROUP BY") || o.contains("UNIQUE") ? Kind.SORT : Kind.OTHER;
      case "FILTER" -> Kind.SUBQUERY_FILTER;
      default -> Kind.OTHER;
    };
  }

  /** PostgreSQL EXPLAIN 글 줄(「QUERY PLAN」 칸). 마디의 깊이는 들여쓰기로, 조건 줄은 바로 앞 마디에 붙인다. */
  public static List<Step> postgres(List<String> lines) {
    List<Step> out = new ArrayList<>();
    Deque<int[]> stack = new ArrayDeque<>(); // {들여쓰기, 단계 id}
    for (String line : lines) {
      Matcher n = PG_NODE.matcher(line);
      if (n.find()) {
        int indent = n.group(1).length();
        while (!stack.isEmpty() && stack.peek()[0] >= indent) stack.pop();
        int id = out.size();
        String name = n.group(2).strip();
        Matcher on = PG_ON.matcher(name);
        out.add(new Step(id, stack.isEmpty() ? -1 : stack.peek()[1], stack.size(), name, pgKind(name), "", on.find() ? on.group(1) : "",
            Math.round(Double.parseDouble(n.group(3))), Long.parseLong(n.group(4)), "", "", "", null, null, null, null));
        stack.push(new int[] {indent, id});
        continue;
      }
      Matcher d = PG_DETAIL.matcher(line);
      if (d.find() && !out.isEmpty()) {
        Step last = out.remove(out.size() - 1);
        boolean access = PG_ACCESS.matcher(d.group(1)).matches();
        out.add(new Step(last.id(), last.parentId(), last.depth(), last.operation(), last.kind(), last.owner(), last.object(),
            last.cost(), last.rows(), access ? join(last.access(), d.group(2)) : last.access(), access ? last.filter() : join(last.filter(), d.group(2)),
            last.extra(), null, null, null, null));
      }
    }
    return out;
  }

  static Kind pgKind(String name) {
    if (name.startsWith("Seq Scan") || name.startsWith("Parallel Seq Scan")) return Kind.FULL_SCAN;
    if (name.startsWith("Nested Loop")) return Kind.NESTED_LOOP;
    if (name.startsWith("Sort") || name.startsWith("Incremental Sort") || name.startsWith("HashAggregate")) return Kind.SORT;
    return Kind.OTHER;
  }

  /** MySQL EXPLAIN 표. 단계는 평평하다(모두 뿌리). type=ALL 이 전체 읽기, index 가 인덱스 전체 읽기. */
  public static List<Step> mysql(List<String> columns, List<List<Object>> rows) {
    List<Step> out = new ArrayList<>();
    for (List<Object> r : rows) {
      String type = text(columns, r, "type");
      String key = text(columns, r, "key");
      Kind kind = "ALL".equals(type) ? Kind.FULL_SCAN : "index".equals(type) ? Kind.INDEX_FULL : Kind.OTHER;
      out.add(new Step(out.size(), -1, 0, "type=" + type + (key.isEmpty() ? "" : " · " + key), kind, "", text(columns, r, "table"),
          null, number(columns, r, "rows"), "", "", text(columns, r, "Extra"), null, null, null, null));
    }
    return out;
  }

  private static String join(String a, String b) { return a == null || a.isEmpty() ? b : a + " AND " + b; }

  private static int index(List<String> columns, String name) {
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).toUpperCase(Locale.ROOT).equals(name.toUpperCase(Locale.ROOT))) return i;
    }
    return -1;
  }

  /** 칼럼이 없거나 값이 null 이면 빈 글. */
  static String text(List<String> columns, List<Object> row, String name) {
    int i = index(columns, name);
    Object v = i < 0 ? null : row.get(i);
    return v == null ? "" : v.toString().strip();
  }

  /** 칼럼이 없거나 값이 null · 숫자가 아니면 null. */
  static Long number(List<String> columns, List<Object> row, String name) {
    int i = index(columns, name);
    Object v = i < 0 ? null : row.get(i);
    if (v instanceof Number num) return num.longValue();
    if (v == null) return null;
    try {
      return Long.parseLong(v.toString().strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
