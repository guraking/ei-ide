package ei.db.console;

import ei.db.console.PlanAdvisor.Finding;
import ei.db.console.PlanAdvisor.Index;
import ei.db.console.PlanAdvisor.Kind;
import ei.db.console.PlanAdvisor.Level;
import ei.db.console.PlanAdvisor.Step;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/** 실행 계획 분석 자체 점검: java -cp out:resources:test-out ei.db.console.PlanAdvisorCheck */
public class PlanAdvisorCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }
  static void ok(boolean b, String what) { if (!b) throw new AssertionError(what); }

  static final List<String> ORACLE_COLS = List.of("ID", "PARENT_ID", "DEPTH", "OPERATION", "OPTIONS", "OBJECT_OWNER", "OBJECT_NAME",
      "COST", "CARDINALITY", "ACCESS_PREDICATES", "FILTER_PREDICATES");

  static List<Object> row(Object... v) { return Arrays.asList(v); }

  static BigDecimal n(long v) { return BigDecimal.valueOf(v); }

  static Finding rule(List<Finding> fs, String rule) {
    for (Finding f : fs) if (f.rule().equals(rule)) return f;
    throw new AssertionError("지적 없음: " + rule + " in " + fs);
  }

  static boolean has(List<Finding> fs, String rule) {
    for (Finding f : fs) if (f.rule().equals(rule)) return true;
    return false;
  }

  static final List<Finding> ALL = new ArrayList<>();

  static List<Finding> analyze(List<Step> steps, Map<String, List<Index>> idx) {
    List<Finding> fs = PlanAdvisor.analyze(steps, idx);
    ALL.addAll(fs);
    return fs;
  }

  public static void main(String[] args) {
    // 큰 표 전체 읽기 + 날짜 칼럼에 TO_CHAR: 인덱스 후보 · 범위 조건 예시.
    List<Step> plan = PlanParse.oracle(ORACLE_COLS, List.of(
        row(n(0), null, n(0), "SELECT STATEMENT", null, null, null, n(1000), n(50), null, null),
        row(n(1), n(0), n(1), "TABLE ACCESS", "FULL", "HR", "EMP", n(990), n(50), null,
            "TO_CHAR(\"E\".\"HIRE_DT\",'YYYYMMDD')='20260101' AND \"E\".\"DEPT_ID\"=10")));
    eq(plan.get(1).kind(), Kind.FULL_SCAN);
    eq(plan.get(0).parentId(), -1);
    List<Finding> fs = analyze(plan, Map.of());
    Finding full = rule(fs, "fullnoindex");
    eq(full.level(), Level.DANGER);
    eq(full.args().get(1), "DEPT_ID, HIRE_DT");
    eq(full.example(), "CREATE INDEX IX_EMP_DEPT_ID ON HR.EMP (DEPT_ID, HIRE_DT)");
    Finding date = rule(fs, "fn.date");
    eq(date.args(), List.of("HIRE_DT", "TO_CHAR"));
    eq(date.example(), "HIRE_DT >= TO_DATE('20260101', 'YYYYMMDD')\n   AND HIRE_DT < TO_DATE('20260101', 'YYYYMMDD') + 1");
    eq(PlanAdvisor.levels(fs).get(1), Level.DANGER);
    eq(PlanAdvisor.levels(fs).get(0), null);

    // 같은 칼럼으로 시작하는 인덱스가 있으면 「안 씀」.
    fs = analyze(plan, Map.of("EMP", List.of(new Index("IX_EMP_DEPT", List.of("DEPT_ID")))));
    eq(rule(fs, "fullunused").args(), List.of("EMP", "IX_EMP_DEPT", "DEPT_ID"));
    ok(!has(fs, "fullnoindex"), "인덱스가 있으면 후보를 내지 않는다");

    // 작은 표 전체 읽기는 넘긴다(비용 비율 < 20%, 건수 < BIG_ROWS).
    List<Step> small = PlanParse.oracle(ORACLE_COLS, List.of(
        row(n(0), null, n(0), "SELECT STATEMENT", null, null, null, n(100), n(5), null, null),
        row(n(1), n(0), n(1), "HASH JOIN", null, null, null, n(100), n(5), "\"A\".\"ID\"=\"B\".\"A_ID\"", null),
        row(n(2), n(1), n(2), "TABLE ACCESS", "FULL", "HR", "CODE", n(3), n(20), null, null),
        row(n(3), n(1), n(2), "TABLE ACCESS", "BY INDEX ROWID", "HR", "ORD", n(97), n(5), null, null)));
    fs = analyze(small, Map.of());
    ok(!has(fs, "fullnowhere") && !has(fs, "fullnoindex"), "작은 표 전체 읽기는 지적하지 않는다: " + fs);
    // ORD 단계 자기 몫 97% 는 「비용 몰림」.
    eq(rule(fs, "cost").args(), List.of(97L));

    // 중첩 반복 안쪽의 전체 읽기, 곱하기 조인, 정렬.
    List<Step> loop = PlanParse.oracle(ORACLE_COLS, List.of(
        row(n(0), null, n(0), "SELECT STATEMENT", null, null, null, n(5000), n(20000), null, null),
        row(n(1), n(0), n(1), "SORT", "ORDER BY", null, null, n(5000), n(20000), null, null),
        row(n(2), n(1), n(2), "NESTED LOOPS", null, null, null, n(4000), n(20000), null, null),
        row(n(3), n(2), n(3), "TABLE ACCESS", "FULL", "HR", "DEPT", n(10), n(500), null, null),
        row(n(4), n(2), n(3), "TABLE ACCESS", "FULL", "HR", "EMP", n(3990), n(40), null, "\"E\".\"DEPT_ID\"=\"D\".\"ID\""),
        row(n(5), n(0), n(1), "MERGE JOIN", "CARTESIAN", null, null, n(0), n(1), null, null)));
    fs = analyze(loop, Map.of());
    Finding lf = rule(fs, "loopfull");
    eq(lf.stepId(), 4);
    eq(lf.args(), List.of("EMP", 500L));
    eq(lf.example(), "CREATE INDEX IX_EMP_DEPT_ID ON HR.EMP (DEPT_ID)");
    eq(rule(fs, "cartesian").level(), Level.DANGER);
    eq(rule(fs, "sort").stepId(), 1);
    eq(PlanParse.oracleKind("SORT", "AGGREGATE"), Kind.OTHER);
    eq(PlanParse.oracleKind("INDEX", "SKIP SCAN"), Kind.INDEX_SKIP);
    eq(PlanParse.oracleKind("INDEX", "FAST FULL SCAN"), Kind.INDEX_FULL);

    // 실제 시간: 자기 몫 시간 비율 · 예상과 실제 건수 차이 · 형 변환 · 앞 % LIKE · NVL.
    List<String> actualCols = new ArrayList<>(ORACLE_COLS);
    actualCols.addAll(List.of("LAST_STARTS", "LAST_OUTPUT_ROWS", "LAST_CR_BUFFER_GETS", "LAST_ELAPSED_TIME"));
    List<Step> actual = PlanParse.oracle(actualCols, List.of(
        row(n(0), null, n(0), "SELECT STATEMENT", null, null, null, n(50), n(1), null, null, n(1), n(30000), n(9000), n(2_000_000)),
        row(n(1), n(0), n(1), "TABLE ACCESS", "BY INDEX ROWID", "HR", "EMP", n(50), n(1), null,
            "INTERNAL_FUNCTION(\"E\".\"EMP_NO\") AND \"E\".\"NAME\" LIKE '%KIM%' AND NVL(\"E\".\"USE_YN\",'Y')='Y'",
            n(1), n(30000), n(9000), n(1_900_000))));
    fs = analyze(actual, Map.of());
    eq(rule(fs, "time").args(), List.of(95L));
    Finding est = rule(fs, "estimate");
    eq(est.args(), List.of(1L, 30000L));
    eq(est.example(), "EXEC DBMS_STATS.GATHER_TABLE_STATS('HR', 'EMP')");
    eq(rule(fs, "fn.convert").args(), List.of("EMP_NO", "INTERNAL_FUNCTION"));
    eq(rule(fs, "fn.nvl").example(), "(USE_YN = :value OR USE_YN IS NULL)");
    eq(rule(fs, "likeprefix").level(), Level.WARNING);

    // PostgreSQL 글 계획: 들여쓰기로 부모, 조건 줄은 앞 마디, 형 변환.
    List<Step> pg = PlanParse.postgres(List.of(
        "Sort  (cost=2000.00..2100.00 rows=40000 width=64)",
        "  Sort Key: e.name",
        "  ->  Hash Join  (cost=10.00..1500.00 rows=40000 width=64)",
        "        Hash Cond: (e.dept_id = d.id)",
        "        ->  Seq Scan on emp e  (cost=0.00..1400.00 rows=40000 width=32)",
        "              Filter: ((emp_no)::text = '10'::text)",
        "        ->  Hash  (cost=5.00..5.00 rows=100 width=32)",
        "              ->  Seq Scan on dept d  (cost=0.00..5.00 rows=100 width=32)"));
    eq(pg.size(), 5);
    eq(pg.get(2).object(), "emp");
    eq(pg.get(2).parentId(), 1);
    eq(pg.get(4).parentId(), 3);
    eq(pg.get(1).access(), "(e.dept_id = d.id)");
    eq(pg.get(2).filter(), "((emp_no)::text = '10'::text)");
    eq(pg.get(0).kind(), Kind.SORT);
    fs = analyze(pg, Map.of());
    eq(rule(fs, "fullnoindex").stepId(), 2);
    eq(rule(fs, "fn.convert").args(), List.of("emp_no", "::"));
    ok(!has(fs, "fullnowhere"), "dept 는 작은 표");

    // MySQL 표: type=ALL, Extra.
    List<Step> my = PlanParse.mysql(List.of("id", "select_type", "table", "type", "possible_keys", "key", "rows", "Extra"), List.of(
        row(1L, "SIMPLE", "orders", "ALL", null, null, 120000L, "Using where; Using filesort"),
        row(1L, "SIMPLE", "users", "ALL", null, null, 300L, "Using join buffer (hash join)")));
    fs = analyze(my, Map.of());
    eq(rule(fs, "fullunknown").args(), List.of("orders"));
    eq(rule(fs, "sort").stepId(), 0);
    eq(rule(fs, "joinbuffer").stepId(), 1);

    // 빈 계획.
    eq(PlanAdvisor.analyze(List.of(), Map.of()), List.of());

    // 나온 지적의 문구가 모두 있고 자리값으로 만들어진다.
    ResourceBundle messages = ResourceBundle.getBundle("messages.EiDbMessages", Locale.ROOT);
    for (Finding f : ALL) {
      for (String part : List.of("title", "cause", "fix")) {
        String key = "plan." + f.rule() + "." + part;
        ok(messages.containsKey(key), "문구 없음: " + key);
        String text = new MessageFormat(messages.getString(key), Locale.ROOT).format(f.args().toArray());
        ok(!text.contains("{"), "자리값 남음: " + key + " → " + text);
      }
    }
    for (Level l : Level.values()) ok(messages.containsKey("plan.level." + l.name()), "수준 문구 없음: " + l);
    System.out.println("PlanAdvisorCheck 통과 (지적 " + ALL.size() + "개)");
  }
}
