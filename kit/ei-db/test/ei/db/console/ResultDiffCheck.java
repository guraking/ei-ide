package ei.db.console;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** 결과 비교 자체 점검: java -cp out:test-out ei.db.console.ResultDiffCheck */
public class ResultDiffCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static List<Object> row(Object... v) { return Arrays.asList(v); }

  public static void main(String[] args) {
    // 숫자는 형식 · 소수 자리와 상관없이 값으로.
    eq(ResultDiff.same(1, new BigDecimal("1.0")), true);
    eq(ResultDiff.same(1L, 1.0d), true);
    eq(ResultDiff.same(new BigDecimal("0.00"), 0), true);
    eq(ResultDiff.same(new BigDecimal("1.5"), 1), false);
    eq(ResultDiff.same(null, null), true);
    eq(ResultDiff.same(null, ""), false);
    eq(ResultDiff.same("1", 1), false);
    eq(ResultDiff.same(Double.NaN, Double.NaN), true);

    // 칼럼은 대소문자 무시, 왼쪽 순서, 겹치는 이름은 처음 것.
    eq(ResultDiff.commonColumns(List.of("ID", "Name", "AGE", "id"), List.of("name", "id", "DEPT")), List.of("ID", "Name"));

    List<String> lc = List.of("ID", "NAME", "SAL");
    List<String> rc = List.of("sal", "id", "name", "DEPT");
    List<List<Object>> left = List.of(row(1, "A", new BigDecimal("100.00")), row(2, "B", 200), row(3, "C", 300), row(null, "N", 1));
    List<List<Object>> right = List.of(row(100, 1, "A", "X"), row(250, 2, "B", "X"), row(400, 4, "D", "X"), row(1L, null, "N", "X"));
    ResultDiff.Outcome o = ResultDiff.compare(lc, left, rc, right, List.of("id"));
    eq(o.columns(), lc);
    eq(o.leftOnlyColumns(), List.of());
    eq(o.rightOnlyColumns(), List.of("DEPT"));
    eq(o.count(ResultDiff.Kind.SAME), 2);
    eq(o.count(ResultDiff.Kind.CHANGED), 1);
    eq(o.count(ResultDiff.Kind.LEFT_ONLY), 1);
    eq(o.count(ResultDiff.Kind.RIGHT_ONLY), 1);
    ResultDiff.Row changed = o.rows().get(1);
    eq(changed.kind(), ResultDiff.Kind.CHANGED);
    eq(Arrays.toString(changed.changed()), "[false, false, true]");
    eq(changed.right(), row(2, "B", 250));
    // null 기준 값끼리 짝지음.
    eq(o.rows().get(3).kind(), ResultDiff.Kind.SAME);
    eq(o.rows().get(4).kind(), ResultDiff.Kind.RIGHT_ONLY);
    eq(o.rows().get(4).right(), row(4, "D", 400));

    // 기준 없음: 행 순서.
    ResultDiff.Outcome byOrder = ResultDiff.compare(List.of("A"), List.of(row(1), row(2)), List.of("a"), List.of(row(2)), List.of());
    eq(byOrder.rows().get(0).kind(), ResultDiff.Kind.CHANGED);
    eq(byOrder.rows().get(1).kind(), ResultDiff.Kind.LEFT_ONLY);

    // 같은 기준 값이 여러 행이면 순서대로 짝짓는다.
    ResultDiff.Outcome dup = ResultDiff.compare(List.of("K", "V"), List.of(row(1, "a"), row(1, "b")),
                                                List.of("K", "V"), List.of(row(1, "a"), row(1, "c"), row(1, "d")), List.of("K"));
    eq(dup.count(ResultDiff.Kind.SAME), 1);
    eq(dup.count(ResultDiff.Kind.CHANGED), 1);
    eq(dup.count(ResultDiff.Kind.RIGHT_ONLY), 1);

    // 숫자 기준 값은 형식이 달라도 맞는다.
    ResultDiff.Outcome num = ResultDiff.compare(List.of("K"), List.of(row(new BigDecimal("7.0"))), List.of("K"), List.of(row(7L)), List.of("K"));
    eq(num.count(ResultDiff.Kind.SAME), 1);

    try {
      ResultDiff.compare(List.of("A"), List.of(), List.of("B"), List.of(), List.of("A"));
      throw new AssertionError("없는 기준 칼럼을 받아들임");
    } catch (IllegalArgumentException expected) {
      // 양쪽에 없는 기준 칼럼은 거절해야 한다.
    }
    System.out.println("ResultDiffCheck 통과");
  }
}
