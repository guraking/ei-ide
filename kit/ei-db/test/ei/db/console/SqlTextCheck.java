package ei.db.console;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class SqlTextCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  public static void main(String[] args) {
    List<String> cols = List.of("ORDER_ID", "LINE_NO", "QTY", "MEMO");
    List<List<Object>> rows = List.of(Arrays.asList(100231, 1, 2, "it's"), Arrays.asList(100231, 2, null, null));
    eq(SqlText.updates("order_item", cols, rows, Set.of("ORDER_ID", "LINE_NO")),
       "UPDATE order_item SET QTY = 2, MEMO = 'it''s' WHERE ORDER_ID = 100231 AND LINE_NO = 1;\n"
     + "UPDATE order_item SET QTY = NULL, MEMO = NULL WHERE ORDER_ID = 100231 AND LINE_NO = 2;\n");
    String noPk = SqlText.updates("t", List.of("a", "b"), List.of(Arrays.asList(1, "x")), Set.of());
    eq(noPk.startsWith("-- PK 를 찾지 못해 첫 칼럼(a)"), true);
    eq(noPk.endsWith("UPDATE t SET b = 'x' WHERE a = 1;\n"), true);
    eq(SqlText.updates("t", List.of("id"), List.of(Arrays.asList(1)), Set.of("ID")), "");
    System.out.println("SqlTextCheck 통과");
  }
}
