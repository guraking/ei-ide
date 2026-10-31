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
    eq(SqlGuard.danger("UPDATE t SET \"WHERE\" = 1"), "WHERE 없는 UPDATE");
    eq(SqlGuard.danger("UPDATE \"T\" SET a = 1 WHERE \"WHERE\" = 1"), null);
    eq(SqlGuard.danger("UPDATE t SET `WHERE` = 1"), "WHERE 없는 UPDATE");
    eq(SqlGuard.danger("UPDATE `t` SET a = 1 WHERE `where` = 1"), null);
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
    // 끝의 ; : 일반 문장은 떼고 PL/SQL 단위는 남긴다.
    eq(SqlGuard.trimStatement(" select case when 1 = 1 then 1 end from dual; "), "select case when 1 = 1 then 1 end from dual");
    eq(SqlGuard.trimStatement("create or replace procedure p is begin null; end p;"), "create or replace procedure p is begin null; end p;");
    eq(SqlGuard.trimStatement("CREATE OR REPLACE PACKAGE BODY pk AS END pk;"), "CREATE OR REPLACE PACKAGE BODY pk AS END pk;");
    eq(SqlGuard.trimStatement("/* c */ CREATE TYPE t AS OBJECT (a NUMBER);"), "/* c */ CREATE TYPE t AS OBJECT (a NUMBER);");
    eq(SqlGuard.trimStatement("begin x; end;"), "begin x; end;");
    eq(SqlGuard.trimStatement("CREATE TABLE t (a NUMBER);"), "CREATE TABLE t (a NUMBER)");
    // 변경 판정: 앞 단어 너머
    eq(SqlGuard.isWrite("WITH d AS (SELECT 1 FROM dual) DELETE FROM t WHERE id IN (SELECT * FROM d)"), true);
    eq(SqlGuard.isWrite("WITH d AS (SELECT 1 x FROM dual) SELECT * FROM d"), false);
    eq(SqlGuard.isWrite("select * from t for update"), false);
    eq(SqlGuard.isWrite("EXPLAIN ANALYZE UPDATE t SET a = 1"), true);
    eq(SqlGuard.isWrite("EXPLAIN SELECT 1"), false);
    eq(SqlGuard.isWrite("replace into t values (1)"), true);
    eq(SqlGuard.isWrite("exec p_run"), true);
    eq(SqlGuard.isWrite("call p_run()"), true);
    eq(SqlGuard.isWrite("select '--' a, 'insert' b from dual"), false);
    eq(SqlGuard.isDdl("comment on table t is 'x'"), true);
    eq(SqlGuard.isDdl("insert into t values (1)"), false);
    eq(SqlGuard.isDdl("alter session set nls_date_format = 'YYYY'"), false);
    eq(Drivers.isOlderDriver("PostgreSQL JDBC Driver 42.2.5", Drivers.PG_MIN_DRIVER), true);
    eq(Drivers.isOlderDriver("PostgreSQL JDBC Driver 9.4.1212", Drivers.PG_MIN_DRIVER), true);
    eq(Drivers.isOlderDriver("PostgreSQL JDBC Driver 42.2.10", Drivers.PG_MIN_DRIVER), false);
    eq(Drivers.isOlderDriver("PostgreSQL JDBC Driver 42.7.4", Drivers.PG_MIN_DRIVER), false);
    eq(Drivers.isOlderDriver("알 수 없음", Drivers.PG_MIN_DRIVER), false);
    eq(Drivers.isOlderDriver(null, Drivers.PG_MIN_DRIVER), false);
    eq(SqlGuard.isDdl("create temporary table t (a int)"), false);
    eq(SqlGuard.isDdl("create global temporary table t (a int)"), true);
    eq(SqlGuard.isWrite("select '" + "x".repeat(50_000) + "' from dual"), false);
    eq(SqlGuard.isWrite("select 'a''b', '' from dual -- update"), false);
    // 위험: WHERE 는 괄호 밖만
    eq(SqlGuard.danger("UPDATE t SET a = (SELECT b FROM u WHERE u.id = 1)"), "WHERE 없는 UPDATE");
    eq(SqlGuard.danger("DELETE FROM t WHERE id IN (SELECT id FROM u)"), null);
    eq(SqlGuard.danger("update t set a = 'x--' where id = 1"), null);
    // 드라이버 판
    eq(Drivers.compareVersions("23.10.0.0", "23.9.0.0") > 0, true);
    eq(Drivers.compareVersions("8.0.33", "8.0.33") == 0, true);
    eq(Drivers.compareVersions("42.7.4", "42.7") > 0, true);
    for (String bad : new String[]{"../../evil", "1.0/x", "1..0"}) {
      try {
        Drivers.latestVersion("<release>" + bad + "</release>");
        throw new AssertionError("거절하지 않음: " + bad);
      } catch (java.io.IOException expected) { /* 거절이 맞다 */ }
    }
    eq(Drivers.decodeSettings("<?xml version=\"1.0\" encoding=\"MS949\"?><a>한글</a>".getBytes(java.nio.charset.Charset.forName("MS949"))), "<?xml version=\"1.0\" encoding=\"MS949\"?><a>한글</a>");
    eq(Drivers.decodeSettings("<a>한글</a>".getBytes(java.nio.charset.Charset.forName("MS949"))), "<a>한글</a>");
    eq(Drivers.decodeSettings("<a>한글</a>".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "<a>한글</a>");
    eq(new QueryResult.BinaryValue(12).toString(), "(이진 12바이트)");
    System.out.println("CoreCheck 통과");
  }
}
