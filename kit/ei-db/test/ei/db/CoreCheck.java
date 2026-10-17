package ei.db;

import java.util.List;

/** 핵심 판정 자체 점검: java -cp out:test-out ei.db.CoreCheck */
public class CoreCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  public static void main(String[] args) {
    eq(SqlGuard.danger("DELETE FROM order_item;"), "WHERE 없는 DELETE");
    eq(SqlGuard.danger("delete order_item where id = 1"), null);
    eq(SqlGuard.danger("UPDATE product SET a = 'WHERE'"), "WHERE 없는 UPDATE");
    eq(SqlGuard.danger("-- WHERE\nUPDATE product SET a = 1"), "WHERE 없는 UPDATE");
    eq(SqlGuard.danger("truncate table app.orders"), "TRUNCATE 문");
    eq(SqlGuard.table("DELETE FROM app.\"ORDER_ITEM\""), "ORDER_ITEM");
    eq(SqlGuard.isWrite("  select * from dual"), false);
    eq(SqlGuard.isWrite("/* x */ insert into t values (1)"), true);
    eq(Comments.tablesOf("SELECT a FROM order_item oi, product p WHERE 1 = 1"), List.of("ORDER_ITEM", "PRODUCT"));
    eq(Comments.tablesOf("select * from app.orders o join users u on o.user_id = u.user_id order by 1"), List.of("ORDERS", "USERS"));
    eq(new DbConfig().url(), "jdbc:oracle:thin:@//localhost:1521/");
    eq(Drivers.repository(null), "https://repo1.maven.org/maven2/");
    eq(Drivers.repository("<settings><mirrors><mirror><id>n</id><mirrorOf>*</mirrorOf><url>http://nexus.local/repo</url></mirror></mirrors></settings>"), "http://nexus.local/repo/");
    eq(Drivers.repository("<mirror><mirrorOf>snapshots</mirrorOf><url>http://x/</url></mirror>"), "https://repo1.maven.org/maven2/");
    try {
      eq(Drivers.latestVersion("<metadata><versioning><release>23.26.3.0.0</release></versioning></metadata>"), "23.26.3.0.0");
      eq(Drivers.latestVersion("<versions><version>1.0</version><version>1.1</version></versions>"), "1.1");
    } catch (java.io.IOException e) { throw new AssertionError(e); }
    eq(Drivers.sha1("abc".getBytes()), "a9993e364706816aba3e25717850c26c9cd0d89d");
    System.out.println("CoreCheck 통과");
  }
}
