package ei.db.console;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 바인드 자리 찾기 · 바꾸기 자체 점검: java -cp out:test-out ei.db.console.SqlParamsCheck */
public class SqlParamsCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static List<String> labels(String sql) {
    List<String> out = new ArrayList<>();
    for (SqlParams.Param p : SqlParams.find(sql)) out.add(p.label());
    return out;
  }

  public static void main(String[] args) {
    eq(labels("SELECT * FROM T WHERE A = :a AND B = #{b} AND C = #{c,jdbcType=VARCHAR} ORDER BY ${col}"),
       List.of(":a", "#{b}", "#{c}", "${col}"));
    // 문자열 · 주석 · :: 형변환 · 시간 형식 · 트리거 행 참조 · 숫자 자리 · $$ 본문은 자리가 아니다.
    eq(labels("SELECT ':x', \"a:b\", q'[:y]' -- :z\n /* #{w} */ FROM T WHERE D = TO_DATE(:d, 'HH24:MI') AND E::text = :e"),
       List.of(":d", ":e"));
    eq(labels("BEGIN :NEW.ID := :1; END;"), List.of());
    eq(labels("SELECT $$ :p $$ , x::int FROM t"), List.of());
    eq(labels("SELECT a:b FROM t"), List.of());

    SqlParams.Bound b = SqlParams.apply("SELECT * FROM ${tbl} WHERE A = :a AND B = #{b, jdbcType=NUMERIC} AND C = :a AND D = :d",
                                         Map.of("tbl", "APP.T", "a", "'007'", "b", "12", "d", ""));
    eq(b.sql(), "SELECT * FROM APP.T WHERE A = ? AND B = ? AND C = ? AND D = ?");
    eq(b.binds(), Arrays.asList("007", 12L, "007", null));
    eq(SqlParams.apply("SELECT 1 FROM DUAL", Map.of()).binds(), null);
    eq(SqlParams.value("1.5"), new java.math.BigDecimal("1.5"));
    eq(SqlParams.value("abc"), "abc");
    eq(SqlParams.value("007"), "007");
    eq(SqlParams.distinct(List.of("A = :a", "B = :a AND C = #{c}")).size(), 2);
    // Oracle 실행 계획: ? 를 리터럴로(문자열 안의 ? 는 그대로).
    SqlParams.Bound o = SqlParams.apply("SELECT '?' FROM T WHERE A = :a AND B = :b AND C = :c AND D = :d",
                                         Map.of("a", "O'Neil", "b", "12", "c", "", "d", "2026-10-17"));
    eq(ei.db.mybatis.SqlValues.inline(o.sql(), o.binds()),
       "SELECT '?' FROM T WHERE A = 'O''Neil' AND B = 12 AND C = NULL AND D = DATE '2026-10-17'");
    System.out.println("SqlParamsCheck 통과");
  }
}
