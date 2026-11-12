package ei.db.console;

/** 같은 결과 탭을 다시 쓸지 가르는 ResultView.sameKey 점검. */
public class ResultTabCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  public static void main(String[] args) {
    String k = ResultView.sameKey(ResultView.RUN, "c1", "SELECT * FROM EMP");
    // 공백 · 줄바꿈 · 끝 「;」 만 다르면 같은 문장.
    eq(ResultView.sameKey(ResultView.RUN, "c1", "  SELECT *\r\n\t  FROM EMP ;\n"), k);
    eq(ResultView.sameKey(ResultView.RUN, "c1", "SELECT * FROM EMP;;"), k);
    // 접속 · 탭 종류 · 대소문자 · 주석이 다르면 다른 문장.
    eq(ResultView.sameKey(ResultView.RUN, "c2", "SELECT * FROM EMP").equals(k), false);
    eq(ResultView.sameKey(ResultView.PLAN, "c1", "SELECT * FROM EMP").equals(k), false);
    eq(ResultView.sameKey(ResultView.RUN, "c1", "select * from emp").equals(k), false);
    eq(ResultView.sameKey(ResultView.RUN, "c1", "SELECT * FROM EMP -- a").equals(k), false);
    eq(ResultView.sameKey(ResultView.RUN, "c1", null), ResultView.sameKey(ResultView.RUN, "c1", ""));
    // 함수 안의 FROM 은 표 이름이 아니다.
    eq(ResultView.firstTable("SELECT EXTRACT(YEAR FROM d) y, TRIM(LEADING '0' FROM c) FROM APP.EMP e"), "EMP");
    eq(ResultView.firstTable("SELECT substring(x FROM 2) FROM \"Dept\""), "Dept");
    eq(ResultView.firstTable("SELECT (SELECT 1 FROM dual) a FROM t -- FROM x"), "t");
    eq(ResultView.firstTable("SELECT 1"), null);
    // 인라인 뷰(쪽 나누기 감싸기)는 안쪽 FROM 의 표.
    eq(ResultView.firstTable("SELECT * FROM (SELECT ROWNUM rn, a.* FROM (SELECT EXTRACT(YEAR FROM d) y FROM APP.EMP e ORDER BY 1) a) WHERE rn <= 10"), "EMP");
    eq(ResultView.firstTable("SELECT * FROM(SELECT a FROM t)"), "t");
    eq(ResultView.firstTable("SELECT fromage FROM t"), "t");
    // 쓴 그대로의 따옴표는 tableRef 에 남는다.
    eq(ResultView.tableRef("SELECT * FROM app.\"order\" o"), "\"order\"");
    eq(ResultView.firstTable("SELECT * FROM app.\"order\" o"), "order");
    // WITH 뒤 바깥 문장 · 표 함수 · DELETE.
    eq(ResultView.firstTable("WITH w AS (SELECT * FROM src) SELECT * FROM w"), "w");
    eq(ResultView.firstTable("SELECT * FROM generate_series(1, 3) g"), "generate_series");
    eq(ResultView.firstTable("select a from emp e join dept d on d.id = e.d"), "emp");
    System.out.println("ResultTabCheck 통과");
  }
}
