package ei.db.console;

import ei.db.DbKind;

import java.util.List;

/** 고친 쿼리 만들기 자체 점검: java -cp out:test-out ei.db.console.SqlRewriteCheck */
public class SqlRewriteCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static String fix(String sql) { return SqlRewrite.rewrite(sql, DbKind.ORACLE).sql(); }

  public static void main(String[] args) {
    // TO_CHAR 날짜 = 값 → 하루 범위, 월 형식 → ADD_MONTHS, 그 밖 형식은 그대로.
    eq(fix("WHERE TO_CHAR(e.HIRE_DT, 'YYYYMMDD') = :day AND e.X = 1"),
        "WHERE e.HIRE_DT >= TO_DATE(:day, 'YYYYMMDD') AND e.HIRE_DT < TO_DATE(:day, 'YYYYMMDD') + 1 AND e.X = 1");
    eq(fix("where to_char(reg_dt,'YYYYMM') = '202611'"),
        "where reg_dt >= TO_DATE('202611', 'YYYYMM') AND reg_dt < ADD_MONTHS(TO_DATE('202611', 'YYYYMM'), 1)");
    eq(SqlRewrite.rewrite("where to_char(reg_dt,'YYYY-MM') = ?", DbKind.POSTGRES).sql(),
        "where reg_dt >= TO_DATE(?, 'YYYY-MM') AND reg_dt < TO_DATE(?, 'YYYY-MM') + INTERVAL '1 month'");
    eq(fix("WHERE TO_CHAR(dt, 'HH24') = '10'"), "WHERE TO_CHAR(dt, 'HH24') = '10'");
    eq(fix("WHERE TRUNC(a.REG_DT) = #{day}"), "WHERE a.REG_DT >= #{day} AND a.REG_DT < #{day} + 1");

    // NVL: 기본값과 값이 같으면 OR IS NULL, 다르면 칼럼 = 값, 바인드면 둘 다 담는다.
    eq(fix("AND NVL(USE_YN, 'Y') = 'Y'"), "AND (USE_YN = 'Y' OR USE_YN IS NULL)");
    eq(fix("AND NVL(USE_YN, 'N') = 'Y'"), "AND USE_YN = 'Y'");
    eq(fix("AND COALESCE(t.CNT, 0) = ?"), "AND (t.CNT = ? OR (t.CNT IS NULL AND 0 = ?))");

    // SUBSTR 앞부분 = 값 → LIKE. 문자 길이가 다르면 뜻이 달라 그대로.
    eq(fix("WHERE SUBSTR(CODE, 1, 3) = 'ABC'"), "WHERE CODE LIKE 'ABC' || '%'");
    eq(fix("WHERE SUBSTR(CODE, 1, 3) = 'AB'"), "WHERE SUBSTR(CODE, 1, 3) = 'AB'");
    eq(fix("WHERE SUBSTR(CODE, 2, 3) = 'ABC'"), "WHERE SUBSTR(CODE, 2, 3) = 'ABC'");

    // UPPER: 값 쪽으로 옮긴다(화면이 조건을 알린다).
    eq(fix("WHERE UPPER(u.NAME) = :name"), "WHERE u.NAME = UPPER(:name)");

    // 바뀐 곳 위치와 칼럼, 여러 곳.
    String sql = "SELECT * FROM T WHERE NVL(A, 'Y') = 'Y' AND SUBSTR(B, 1, 2) = 'XY'";
    SqlRewrite.Result r = SqlRewrite.rewrite(sql, DbKind.ORACLE);
    eq(r.sql(), "SELECT * FROM T WHERE (A = 'Y' OR A IS NULL) AND B LIKE 'XY' || '%'");
    eq(r.changes().size(), 2);
    SqlRewrite.Change first = r.changes().get(0);
    eq(first.rule(), "nvl");
    eq(first.column(), "A");
    eq(sql.substring(first.beforeStart(), first.beforeEnd()), "NVL(A, 'Y') = 'Y'");
    eq(r.sql().substring(first.afterStart(), first.afterEnd()), "(A = 'Y' OR A IS NULL)");
    SqlRewrite.Change second = r.changes().get(1);
    eq(r.sql().substring(second.afterStart(), second.afterEnd()), "B LIKE 'XY' || '%'");

    // 바꿀 곳 없음.
    eq(SqlRewrite.rewrite("SELECT 1 FROM DUAL", DbKind.ORACLE).changes(), List.of());
    System.out.println("SqlRewriteCheck 통과");
  }
}
