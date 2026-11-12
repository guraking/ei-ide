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
    // 서브쿼리 안 별칭 · 따옴표 이름 · MERGE · INSERT 대상.
    m = SqlAliases.parse("SELECT x. FROM (SELECT * FROM emp x WHERE x.a = 1) v JOIN \"Dept\" d ON d.id = v.d");
    eq(m.get("X"), "emp"); eq(m.get("D"), "Dept"); eq(m.containsKey("V"), false);
    m = SqlAliases.parse("MERGE INTO tgt t USING src s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.v = s.v");
    eq(m.get("T"), "tgt"); eq(m.get("S"), "src");
    m = SqlAliases.parse("INSERT INTO log_t (a) SELECT a FROM src");
    eq(m.get("LOG_T"), "log_t"); eq(m.get("SRC"), "src");
    m = SqlAliases.parse("select e. from emp e where");
    eq(m.get("E"), "emp");
    eq(SqlAliases.statementAround("select 1;\nselect * from t;", 12), "\nselect * from t");
    System.out.println("SqlAliasesCheck 통과");
  }
}
