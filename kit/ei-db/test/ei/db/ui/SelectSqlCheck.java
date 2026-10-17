package ei.db.ui;

import ei.db.DbKind;
import ei.db.Meta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    eq(SelectSql.build(DbKind.ORACLE, "APP", "ORDER_ITEM", List.of(col("ORDER_ID", "주문번호"), col("QTY", ""), col("UNIT_PRICE", "단가\n(원)"))),
       "SELECT\n"
       + "       t.ORDER_ID    -- 주문번호\n"
       + "     , t.QTY\n"
       + "     , t.UNIT_PRICE  -- 단가 (원)\n"
       + "  FROM APP.ORDER_ITEM t\n"
       + " WHERE 1 = 1");
    eq(SelectSql.build(DbKind.POSTGRES, "public", "users", List.of()), "SELECT\n       t.*\n  FROM PUBLIC.USERS t\n WHERE 1 = 1");
    List<Meta.Column> cols = List.of(pk("ORDER_ID", "주문번호"), col("QTY", "수량"), col("MEMO", ""));
    eq(SelectSql.insert(DbKind.ORACLE, "APP", "ORDER_ITEM", cols),
       "INSERT INTO APP.ORDER_ITEM (\n"
       + "       ORDER_ID  -- 주문번호\n"
       + "     , QTY       -- 수량\n"
       + "     , MEMO\n"
       + ") VALUES (\n"
       + "       NULL  -- ORDER_ID NUMBER(12)\n"
       + "     , NULL  -- QTY NUMBER(12)\n"
       + "     , NULL  -- MEMO NUMBER(12)\n"
       + ")");
    eq(SelectSql.update(DbKind.ORACLE, "APP", "ORDER_ITEM", cols),
       "UPDATE APP.ORDER_ITEM t\n"
       + "   SET t.QTY = NULL   -- 수량\n"
       + "     , t.MEMO = NULL\n"
       + " WHERE 1 = 1\n"
       + "   AND t.ORDER_ID = NULL  -- 주문번호");
    eq(SelectSql.update(DbKind.ORACLE, "APP", "LOGS", List.of(col("MSG", ""))),
       "UPDATE APP.LOGS t\n   SET t.MSG = NULL\n WHERE 1 = 1\n   -- PK 가 없습니다. 조건을 직접 쓰세요");
    // 조인: FK 가 있으면 FK 로, 없으면 PK 와 이름이 같은 칼럼으로 추정, 그래도 없으면 직접 쓰라는 줄.
    Map<String, List<Meta.Column>> cols2 = new LinkedHashMap<>();
    cols2.put("ORDERS", List.of(pk("ORDER_ID", "주문번호"), col("CUST_ID", "")));
    cols2.put("ORDER_ITEM", List.of(pk("ORDER_ID", ""), pk("SEQ", ""), col("QTY", "수량")));
    cols2.put("CUSTOMER", List.of(pk("CUST_ID", "고객"), col("CUST_NM", "")));
    cols2.put("CODE", List.of(pk("CD", "")));
    eq(SelectSql.join(DbKind.ORACLE, "APP", List.of("ORDERS", "ORDER_ITEM", "CUSTOMER", "CODE"), cols2,
         List.of(new Meta.ForeignKey("FK_OI_O", "ORDER_ITEM", "ORDER_ID", "ORDERS", "ORDER_ID"))),
       "SELECT\n"
       + "       o.ORDER_ID   -- 주문번호\n"
       + "     , o.CUST_ID\n"
       + "     , oi.ORDER_ID\n"
       + "     , oi.SEQ\n"
       + "     , oi.QTY       -- 수량\n"
       + "     , c.CUST_ID    -- 고객\n"
       + "     , c.CUST_NM\n"
       + "     , c2.CD\n"
       + "  FROM APP.ORDERS o, APP.ORDER_ITEM oi, APP.CUSTOMER c, APP.CODE c2\n"
       + " WHERE 1 = 1\n"
       + "   AND oi.ORDER_ID = o.ORDER_ID  -- FK FK_OI_O\n"
       + "   AND o.CUST_ID = c.CUST_ID     -- " + SelectSql.GUESS_NOTE + "\n"
       + "   -- CODE 와 이을 조건을 찾지 못했습니다. 직접 쓰세요");
    // PostgreSQL 은 SET 왼쪽에 별칭을 붙이지 않는다(WHERE 는 붙인다).
    eq(SelectSql.update(DbKind.POSTGRES, "public", "order_item", List.of(pk("order_id", ""), col("qty", ""))),
       "UPDATE PUBLIC.ORDER_ITEM t\n   SET QTY = NULL\n WHERE 1 = 1\n   AND t.ORDER_ID = NULL");
    // MySQL 은 이름을 메타 그대로, 접히지 않는 이름은 따옴표.
    eq(SelectSql.build(DbKind.MYSQL, "shop", "OrderItem", List.of(col("qty", ""))),
       "SELECT\n       t.qty\n  FROM shop.OrderItem t\n WHERE 1 = 1");
    eq(SelectSql.build(DbKind.POSTGRES, "public", "OrderItem", List.of(col("Qty", ""), col("unit_price", ""))),
       "SELECT\n       t.\"Qty\"\n     , t.UNIT_PRICE\n  FROM PUBLIC.\"OrderItem\" t\n WHERE 1 = 1");
    eq(SelectSql.insert(DbKind.ORACLE, "APP", "Log", List.of(col("MSG", ""))),
       "INSERT INTO APP.\"Log\" (\n       MSG\n) VALUES (\n       NULL  -- MSG NUMBER(12)\n)");
    // 첫 표가 홀로면 그 표도 경고한다.
    Map<String, List<Meta.Column>> cols3 = new LinkedHashMap<>();
    cols3.put("CODE", List.of(pk("CD", "")));
    cols3.put("ORDERS", List.of(pk("ORDER_ID", "")));
    cols3.put("ORDER_ITEM", List.of(pk("ORDER_ID", ""), pk("SEQ", "")));
    String j3 = SelectSql.join(DbKind.ORACLE, "APP", List.of("CODE", "ORDERS", "ORDER_ITEM"), cols3, List.of());
    if (!j3.endsWith("   AND o.ORDER_ID = oi.ORDER_ID  -- " + SelectSql.GUESS_NOTE + "\n   -- CODE 와 이을 조건을 찾지 못했습니다. 직접 쓰세요")) {
      throw new AssertionError(j3);
    }
    System.out.println("SelectSqlCheck 통과");
  }
}
