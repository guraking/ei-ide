package ei.db.console;

/** 찾기 칸 조건을 조회 문장에 붙이기(ResultFilter) 점검. */
public class ResultFilterCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  static String flat(String s) { return s.replaceAll("\\s+", " ").strip(); }

  public static void main(String[] args) {
    eq(ResultFilter.isCondition("userid = 'ta9two'"), true);
    eq(ResultFilter.isCondition("name like 'a%'"), true);
    eq(ResultFilter.isCondition("use_yn is null"), true);
    eq(ResultFilter.isCondition("ta9two"), false);
    eq(ResultFilter.isCondition("admin island"), false);
    eq(ResultFilter.isCondition(null), false);

    // 표 하나: WHERE 가 없으면 만들고, 있으면 AND 로 붙인다. ORDER BY 는 뒤에 남는다. 고치기 조건을 그대로 지킨다.
    eq(flat(ResultFilter.apply("SELECT * FROM T_USER;", "ID = 1")), "SELECT * FROM T_USER WHERE (ID = 1 )");
    eq(flat(ResultFilter.apply("select * from app.t_user u order by id", "id = 1")), "select * from app.t_user u WHERE (id = 1 ) order by id");
    eq(flat(ResultFilter.apply("SELECT * FROM T WHERE A = 1 OR B = 2 ORDER BY A", "C = 3")),
        "SELECT * FROM T WHERE (A = 1 OR B = 2 ) AND (C = 3 ) ORDER BY A");
    eq(SqlText.notEditable(ResultFilter.apply("SELECT * FROM T WHERE A = 1 ORDER BY A", "C = 3")), null);
    eq(SqlText.notEditable(ResultFilter.apply("SELECT * FROM APP.T", "C = 3")), null);
    // 줄 주석으로 끝난 문장 · 조건이 뒤 글을 삼키지 않는다.
    String noted = ResultFilter.apply("SELECT * FROM T WHERE A = 1 -- 메모", "C = 3 -- x");
    eq(noted.contains("(A = 1\n) AND (C = 3 -- x\n)"), true);
    eq(noted.endsWith("-- 메모"), true);
    eq(ResultFilter.apply("SELECT A.X FROM A, B -- 메모", "X = 1 -- x").contains("-- 메모\n) ei_f WHERE (X = 1 -- x\n)"), true);
    // 그 밖의 문장은 감싼다.
    eq(flat(ResultFilter.apply("SELECT A.X, B.Y FROM A, B WHERE A.ID = B.ID", "X = 1")),
        "SELECT * FROM (SELECT A.X, B.Y FROM A, B WHERE A.ID = B.ID ) ei_f WHERE (X = 1 )");
    eq(SqlText.isRereadable(ResultFilter.apply("SELECT * FROM T", "ID = 1")), true);
    System.out.println("ResultFilterCheck OK");
  }
}
