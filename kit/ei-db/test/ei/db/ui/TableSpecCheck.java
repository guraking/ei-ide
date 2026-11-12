package ei.db.ui;

import ei.db.DbConfig;
import ei.db.Meta;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

/** 테이블 정의서 자체 점검: java -cp out:test-out ei.db.ui.TableSpecCheck */
public class TableSpecCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  /** 줄마다 칸 이름 → 값. next · getString · getInt · close 만 받는다. */
  static ResultSet rs(List<Map<String, Object>> rows) {
    int[] at = {-1};
    return (ResultSet) Proxy.newProxyInstance(TableSpecCheck.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (p, m, a) -> switch (m.getName()) {
      case "next" -> ++at[0] < rows.size();
      case "getString" -> (String) rows.get(at[0]).get((String) a[0]);
      case "getInt" -> (Integer) rows.get(at[0]).getOrDefault((String) a[0], 0);
      case "close" -> null;
      default -> throw new UnsupportedOperationException(m.getName());
    });
  }

  /** getColumns 는 패턴 일치를 흉내 내 받은 표 이름과 상관없이 모든 줄을 돌려준다. */
  static Connection conn(List<Map<String, Object>> pkRows, List<Map<String, Object>> colRows) {
    DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(TableSpecCheck.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
      case "getPrimaryKeys" -> rs(pkRows);
      case "getColumns" -> rs(colRows);
      default -> throw new UnsupportedOperationException(m.getName());
    });
    return (Connection) Proxy.newProxyInstance(TableSpecCheck.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
      if (m.getName().equals("getMetaData")) return md;
      throw new UnsupportedOperationException(m.getName());
    });
  }

  static Map<String, Object> col(String table, String name, String type, int size, int nullable, String remarks, String def) {
    java.util.HashMap<String, Object> m = new java.util.HashMap<>();
    m.put("TABLE_NAME", table);
    m.put("COLUMN_NAME", name);
    m.put("TYPE_NAME", type);
    m.put("COLUMN_SIZE", size);
    m.put("DECIMAL_DIGITS", 0);
    m.put("NULLABLE", nullable);
    m.put("REMARKS", remarks);
    m.put("COLUMN_DEF", def);
    return m;
  }

  public static void main(String[] args) throws Exception {
    Meta.Column id = new Meta.Column("ID", "NUMBER", 12, 0, true, false, false, "번호");
    Meta.Column price = new Meta.Column("PRICE", "NUMBER", 12, 2, false, false, true, "");
    Meta.Column name = new Meta.Column("NAME", "VARCHAR2", 100, 0, false, false, true, "이름");
    Meta.Column reg = new Meta.Column("REG_DT", "DATE", 7, 0, false, false, false, "등록일");
    Meta.Column ts = new Meta.Column("UPD_TS", "TIMESTAMP(6)", 11, 6, false, false, true, "");
    Meta.Column memo = new Meta.Column("MEMO", "CLOB", 4000, 0, false, false, true, "");
    Meta.Column zero = new Meta.Column("FLAG", "BOOL", 0, 0, false, false, true, "");

    eq(TableSpec.length(id), "12");
    eq(TableSpec.length(price), "12,2");
    eq(TableSpec.length(name), "100");
    eq(TableSpec.length(reg), "");
    eq(TableSpec.length(ts), "");
    eq(TableSpec.length(memo), "");
    eq(TableSpec.length(zero), "");

    eq(TableSpec.kind(new Meta.Table("V_ITEM", "VIEW", "")), TableSpec.KIND_VIEW);
    eq(TableSpec.kind(new Meta.Table("ITEM", "TABLE", "")), TableSpec.KIND_TABLE);
    // PostgreSQL 의 구체화 뷰는 뷰, 분할 표 · 외부 표는 표.
    eq(TableSpec.kind(new Meta.Table("MV_SALES", "MATERIALIZED VIEW", "")), TableSpec.KIND_VIEW);
    eq(TableSpec.kind(new Meta.Table("ORDERS", "PARTITIONED TABLE", "")), TableSpec.KIND_TABLE);
    eq(TableSpec.kind(new Meta.Table("EXT", "FOREIGN TABLE", "")), TableSpec.KIND_TABLE);

    TableSpec.Table item = new TableSpec.Table(new Meta.Table("ITEM", "TABLE", "상품"), List.of(id, name), Map.of("NAME", "'없음'"));
    TableSpec.Table log = new TableSpec.Table(new Meta.Table("V_LOG", "VIEW", ""), List.of(reg), Map.of());
    TableSpec.Table empty = new TableSpec.Table(new Meta.Table("EMPTY", "TABLE", ""), List.of(), Map.of());

    // 칼럼이 없는 표는 정의서 · 표 수에서 빠진다.
    eq(TableSpec.filled(List.of(item, empty, log)), List.of(item, log));
    eq(TableSpec.filled(List.of(empty)), List.of());

    List<List<Object>> rows = TableSpec.rows(List.of(item, log, empty));
    eq(rows.size(), 3);
    eq(rows.get(0), List.of("ITEM", "표", "상품", 1, "ID", "NUMBER", "12", "N", "Y", "", "번호"));
    eq(rows.get(1), List.of("ITEM", "표", "상품", 2, "NAME", "VARCHAR2", "100", "Y", "", "'없음'", "이름"));
    eq(rows.get(2), List.of("V_LOG", "뷰", "", 1, "REG_DT", "DATE", "", "N", "", "", "등록일"));
    for (List<Object> row : rows) eq(row.size(), TableSpec.HEADER.size());

    // 「USER_ROLE」 의 「_」 는 LIKE 에서 아무 한 글자라 USERXROLE 칼럼도 돌아온다: 이름이 같은 줄만 받는다.
    Connection conn = conn(
        List.of(Map.of("COLUMN_NAME", "ID")),
        List.of(col("USER_ROLE", "ID", "NUMBER", 10, DatabaseMetaData.columnNoNulls, " 번호 ", null),
                col("USERXROLE", "OTHER", "VARCHAR2", 20, DatabaseMetaData.columnNullable, "", "'x'"),
                col("USER_ROLE", "NM", "VARCHAR2", 50, DatabaseMetaData.columnNullable, null, " 'a' ")));
    TableSpec.Table read = TableSpec.read(conn, new DbConfig(), "APP", new Meta.Table("USER_ROLE", "TABLE", ""));
    eq(read.columns(), List.of(new Meta.Column("ID", "NUMBER", 10, 0, true, false, false, "번호"),
                               new Meta.Column("NM", "VARCHAR2", 50, 0, false, false, true, "")));
    eq(read.defaults(), Map.of("NM", "'a'"));

    // 맞는 줄이 하나도 없으면 칼럼이 비어 filled 에서 빠진다.
    TableSpec.Table none = TableSpec.read(conn(List.of(), List.of(col("A_B", "X", "DATE", 7, 1, "", null))),
                                          new DbConfig(), "APP", new Meta.Table("AXB", "TABLE", ""));
    eq(TableSpec.filled(List.of(none)), List.of());
    // 표가 여럿이면 스키마의 칼럼을 한 번에 읽어 표별로 나눈다. 다른 스키마 줄은 뺀다.
    Map<String, Object> a1 = col("A", "ID", "NUMBER", 10, DatabaseMetaData.columnNoNulls, "", null);
    a1.put("TABLE_SCHEM", "APP");
    Map<String, Object> elsewhere = col("A", "X", "DATE", 7, DatabaseMetaData.columnNullable, "", null);
    elsewhere.put("TABLE_SCHEM", "OTHER");
    Map<String, Object> b1 = col("B", "NM", "VARCHAR2", 20, DatabaseMetaData.columnNullable, "", " 'b' ");
    Map<String, List<Meta.ColumnRow>> byTable = TableSpec.columnsByTable(conn(List.of(), List.of(a1, elsewhere, b1)), new DbConfig(), "APP",
        List.of(new Meta.Table("A", "TABLE", ""), new Meta.Table("B", "TABLE", "")));
    eq(byTable.keySet(), java.util.Set.of("A", "B"));
    eq(byTable.get("A").size(), 1);
    TableSpec.Table b = TableSpec.of(new Meta.Table("B", "TABLE", ""), byTable.get("B"), java.util.Set.of("NM"));
    eq(b.columns(), List.of(new Meta.Column("NM", "VARCHAR2", 20, 0, true, false, true, "")));
    eq(b.defaults(), Map.of("NM", "'b'"));
    System.out.println("TableSpecCheck OK");
  }
}
