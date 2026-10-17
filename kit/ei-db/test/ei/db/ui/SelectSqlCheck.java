package ei.db.ui;

import ei.db.Meta;

import java.util.List;

/** SELECT 문 만들기 자체 점검: java -cp out:test-out ei.db.ui.SelectSqlCheck */
public class SelectSqlCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static Meta.Column col(String name, String comment) {
    return new Meta.Column(name, "NUMBER", 12, 0, false, false, true, comment);
  }

  static Meta.Column pk(String name, String comment) {
    return new Meta.Column(name, "NUMBER", 12, 0, true, false, false, comment);
  }

  public static void main(String[] args) {
    eq(SelectSql.build("APP", "ORDER_ITEM", List.of(col("ORDER_ID", "주문번호"), col("QTY", ""), col("UNIT_PRICE", "단가\n(원)"))),
       "SELECT t.ORDER_ID,    -- 주문번호\n"
       + "       t.QTY,\n"
       + "       t.UNIT_PRICE   -- 단가 (원)\n"
       + "  FROM APP.ORDER_ITEM t\n"
       + " WHERE 1 = 1");
    eq(SelectSql.build("public", "users", List.of()), "SELECT t.*\n  FROM public.users t\n WHERE 1 = 1");
    List<Meta.Column> cols = List.of(pk("ORDER_ID", "주문번호"), col("QTY", "수량"), col("MEMO", ""));
    eq(SelectSql.insert("APP", "ORDER_ITEM", cols),
       "INSERT INTO APP.ORDER_ITEM (\n"
       + "       ORDER_ID,  -- 주문번호\n"
       + "       QTY,       -- 수량\n"
       + "       MEMO\n"
       + ") VALUES (\n"
       + "       NULL,  -- ORDER_ID NUMBER(12)\n"
       + "       NULL,  -- QTY NUMBER(12)\n"
       + "       NULL   -- MEMO NUMBER(12)\n"
       + ")");
    eq(SelectSql.update("APP", "ORDER_ITEM", cols),
       "UPDATE APP.ORDER_ITEM t\n"
       + "   SET t.QTY = NULL,   -- 수량\n"
       + "       t.MEMO = NULL\n"
       + " WHERE 1 = 1\n"
       + "   AND t.ORDER_ID = NULL  -- 주문번호");
    eq(SelectSql.update("public", "logs", List.of(col("msg", ""))),
       "UPDATE public.logs t\n   SET t.msg = NULL\n WHERE 1 = 1\n   -- PK 가 없습니다. 조건을 직접 쓰세요");
    System.out.println("SelectSqlCheck 통과");
  }
}
