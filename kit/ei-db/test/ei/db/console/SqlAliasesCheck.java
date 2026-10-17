package ei.db.console;

import java.util.Map;

public class SqlAliasesCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  public static void main(String[] args) {
    Map<String, String> m = SqlAliases.parse("SELECT oi. FROM order_item oi, app.product p WHERE oi.product_id = p.product_id");
    eq(m.get("OI"), "order_item"); eq(m.get("P"), "product"); eq(m.get("ORDER_ITEM"), "order_item");
    m = SqlAliases.parse("select * from orders o left join users u on o.user_id = u.user_id");
    eq(m.get("O"), "orders"); eq(m.get("U"), "users");
    m = SqlAliases.parse("SELECT * FROM T");
    eq(m.get("T"), "T");
    m = SqlAliases.parse("UPDATE product SET x = 1");
    eq(m.get("PRODUCT"), "product"); eq(m.containsKey("SET"), false);
    m = SqlAliases.parse("SELECT a FROM orders AS o WHERE 1 = 1");
    eq(m.get("O"), "orders");
    eq(SqlAliases.statementAround("select 1;\nselect * from t;", 12), "\nselect * from t");
    System.out.println("SqlAliasesCheck 통과");
  }
}
