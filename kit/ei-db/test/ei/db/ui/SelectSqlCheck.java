package ei.db.ui;

import ei.db.Meta;

import java.util.List;

/** SELECT 문 만들기 자체 점검: java -cp out:test-out ei.db.ui.SelectSqlCheck */
public class SelectSqlCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static Meta.Column col(String name, String comment) {
    return new Meta.Column(name, "NUMBER", 12, 0, false, false, true, comment);
  }

  public static void main(String[] args) {
    eq(SelectSql.build("APP", "ORDER_ITEM", List.of(col("ORDER_ID", "주문번호"), col("QTY", ""), col("UNIT_PRICE", "단가\n(원)"))),
       "SELECT t.ORDER_ID,    -- 주문번호\n"
       + "       t.QTY,\n"
       + "       t.UNIT_PRICE   -- 단가 (원)\n"
       + "  FROM APP.ORDER_ITEM t\n"
       + " WHERE 1 = 1");
    eq(SelectSql.build("public", "users", List.of()), "SELECT t.*\n  FROM public.users t\n WHERE 1 = 1");
    System.out.println("SelectSqlCheck 통과");
  }
}
