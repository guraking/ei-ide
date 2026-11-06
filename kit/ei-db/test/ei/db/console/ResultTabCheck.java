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
    System.out.println("ResultTabCheck 통과");
  }
}
