package ei.db.console;

import ei.db.DbKind;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** 결과 표에서 만드는 쿼리 · JSON(ResultQueries) 점검. */
public class ResultQueriesCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  static void has(String sql, String part) { if (!sql.contains(part)) throw new AssertionError("없음: " + part + "\n" + sql); }

  public static void main(String[] args) {
    String base = "SELECT *\n  FROM T_USER;";

    eq(ResultQueries.counts(base, List.of("USE_YN")),
        "SELECT\n       USE_YN\n     , COUNT(*) AS CNT\n     , ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS PCT\n"
            + "  FROM (\n        SELECT *\n          FROM T_USER\n       ) x\n GROUP BY USE_YN\n ORDER BY CNT DESC\n");

    String dup = ResultQueries.duplicates(base, List.of("A", "B"));
    has(dup, "COUNT(*) OVER (PARTITION BY x.A, x.B) AS DUP_CNT");
    has(dup, " WHERE y.DUP_CNT > 1\n ORDER BY y.A, y.B\n");
    has(dup, "                SELECT *\n                  FROM T_USER\n               ) x\n       ) y");

    String top = ResultQueries.window(base, ResultQueries.Window.ROW_NUMBER, List.of("USERID"), "REG_TS", true, null, false, 1);
    has(top, "     , ROW_NUMBER() OVER (PARTITION BY x.USERID ORDER BY x.REG_TS DESC) AS RN");
    has(top, "       ) y\n WHERE y.RN = 1\n ORDER BY y.USERID, y.RN\n");
    has(ResultQueries.window(base, ResultQueries.Window.RANK, List.of(), "AMT", false, null, false, 3), "RANK() OVER (ORDER BY x.AMT) AS RN");
    has(ResultQueries.window(base, ResultQueries.Window.RANK, List.of(), "AMT", false, null, false, 3), " WHERE y.RN <= 3\n ORDER BY y.RN\n");
    // 0 이면 자르지 않고 감싸지도 않는다.
    eq(ResultQueries.window(base, ResultQueries.Window.DENSE_RANK, List.of("A"), "AMT", true, null, false, 0),
        "SELECT\n       x.*\n     , DENSE_RANK() OVER (PARTITION BY x.A ORDER BY x.AMT DESC) AS RN\n"
            + "  FROM (\n        SELECT *\n          FROM T_USER\n       ) x\n ORDER BY x.A, x.AMT DESC\n");
    has(ResultQueries.window(base, ResultQueries.Window.RUNNING_SUM, List.of("A"), "DT", false, "AMT", true, 5), "SUM(x.AMT) OVER (PARTITION BY x.A ORDER BY x.DT) AS RUN_SUM");
    eq(ResultQueries.window(base, ResultQueries.Window.RUNNING_SUM, List.of("A"), "DT", false, "AMT", true, 5).contains("y.RN"), false);
    String share = ResultQueries.window(base, ResultQueries.Window.GROUP_SUM, List.of(), "DT", false, "AMT", true, 0);
    has(share, "SUM(x.AMT) OVER () AS GRP_SUM");
    has(share, "ROUND(x.AMT * 100.0 / NULLIF(SUM(x.AMT) OVER (), 0), 1) AS GRP_PCT");
    has(ResultQueries.window(base, ResultQueries.Window.GROUP_COUNT, List.of("A", "B"), "DT", false, null, false, 0), "COUNT(*) OVER (PARTITION BY x.A, x.B) AS GRP_CNT");
    String lag = ResultQueries.window(base, ResultQueries.Window.LAG, List.of("A"), "DT", false, "AMT", true, 0);
    has(lag, "LAG(x.AMT) OVER (PARTITION BY x.A ORDER BY x.DT) AS PREV_VAL");
    has(lag, "x.AMT - LAG(x.AMT) OVER (PARTITION BY x.A ORDER BY x.DT) AS DIFF");
    eq(ResultQueries.window(base, ResultQueries.Window.LAG, List.of("A"), "DT", false, "NM", false, 0).contains("DIFF"), false);
    has(ResultQueries.window(base, ResultQueries.Window.LEAD, List.of(), "DT", false, "NM", false, 0), "LEAD(x.NM) OVER (ORDER BY x.DT) AS NEXT_VAL");

    String pivot = ResultQueries.pivot(base, List.of("USERID"), "CD", "AMT", "SUM", Arrays.asList("01", null, "01 ", new BigDecimal("7")), DbKind.ORACLE);
    has(pivot, "SELECT\n       USERID\n     , SUM(CASE WHEN CD = '01' THEN AMT END) AS \"01\"");
    has(pivot, "     , SUM(CASE WHEN CD IS NULL THEN AMT END) AS \"NULL\"");
    has(pivot, "     , SUM(CASE WHEN CD = '01 ' THEN AMT END) AS \"01_2\"");
    has(pivot, "     , SUM(CASE WHEN CD = 7 THEN AMT END) AS \"7\"");
    has(pivot, " GROUP BY USERID\n ORDER BY USERID\n");
    // 행 칼럼이 없으면 한 줄 집계.
    eq(ResultQueries.pivot(base, List.of(), "CD", "AMT", "COUNT", List.of("A"), DbKind.POSTGRES).contains("GROUP BY"), false);

    String down = ResultQueries.tree(base, "MENU_ID", "UP_MENU_ID", "MENU_NM", null, true, DbKind.ORACLE);
    has(down, " START WITH x.UP_MENU_ID IS NULL\nCONNECT BY NOCYCLE PRIOR x.MENU_ID = x.UP_MENU_ID\n ORDER SIBLINGS BY x.MENU_NM\n");
    has(down, "LPAD(' ', (LEVEL - 1) * 2) || x.MENU_NM AS TREE_NAME");
    String up = ResultQueries.tree(base, "MENU_ID", "UP_MENU_ID", "MENU_NM", List.of("M1", "M2"), false, DbKind.ORACLE);
    has(up, " START WITH x.MENU_ID IN ('M1', 'M2')\nCONNECT BY NOCYCLE PRIOR x.UP_MENU_ID = x.MENU_ID\n");
    String pg = ResultQueries.tree(base, "menu_id", "up_menu_id", "menu_nm", List.of(1), true, DbKind.POSTGRES);
    has(pg, "WITH RECURSIVE src AS (\nSELECT *\n  FROM T_USER\n), tree AS (\n");
    has(pg, " WHERE s.menu_id = 1\n UNION ALL\n");
    has(pg, "  FROM src s, tree t\n WHERE s.up_menu_id = t.menu_id\n   AND t.tree_lv < 50\n");
    has(ResultQueries.tree(base, "menu_id", "up_menu_id", "menu_nm", List.of(1), false, DbKind.POSTGRES), " WHERE s.menu_id = t.up_menu_id\n");

    eq(ResultQueries.json(List.of("ID", "NM"), List.of(Arrays.asList(new BigDecimal("1E+2"), "a\"b\n"), Arrays.asList(null, 1.5)), String::valueOf),
        "[\n  {\n    \"ID\": 100,\n    \"NM\": \"a\\\"b\\n\"\n  },\n  {\n    \"ID\": null,\n    \"NM\": 1.5\n  }\n]\n");
    eq(ResultQueries.json(List.of("ID"), List.of(), String::valueOf), "[]\n");
    System.out.println("ResultQueriesCheck OK");
  }
}
