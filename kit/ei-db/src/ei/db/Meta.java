package ei.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DatabaseMetaData 로 표 · 칼럼 · 외래키를 읽는다. 주석은 REMARKS(Oracle 은 접속 때 remarksReporting=true 로 켠다).
 * MySQL 은 데이터베이스가 catalog 자리, Oracle · PostgreSQL 은 schema 자리라 둘을 갈라 넘긴다.
 */
public final class Meta {
  private Meta() {}

  public record Table(String name, String type, String comment) {}

  public record Column(String name, String type, int size, int scale, boolean pk, boolean fk, boolean nullable, String comment) {
    /** NUMBER(12,2) · VARCHAR2(100) 꼴. */
    public String typeLabel() {
      if (size <= 0 || type.contains("DATE") || type.contains("TIME") || type.contains("LOB")) return type;
      return scale > 0 ? type + "(" + size + "," + scale + ")" : type + "(" + size + ")";
    }
  }

  public record ForeignKey(String name, String fkTable, String fkColumn, String pkTable, String pkColumn) {}

  /** PostgreSQL 에서 기본 스키마를 비웠고 아직 접속해 보지 않았을 때 쓰는 스키마. */
  private static final String PG_DEFAULT_SCHEMA = "public";
  private static final String[] ORACLE_TABLE_TYPES = {"TABLE"};
  private static final String[] ORACLE_VIEW_TYPES = {"VIEW"};
  /** PostgreSQL 드라이버의 TABLE_TYPE 값. 분할 표 · 외부 표는 표로, 구체화 뷰는 뷰로 묶는다. */
  private static final String[] PG_TABLE_TYPES = {"TABLE", "PARTITIONED TABLE", "FOREIGN TABLE"};
  private static final String[] PG_VIEW_TYPES = {"VIEW", "MATERIALIZED VIEW"};
  /** 접속 id → 접속 뒤 읽은 PostgreSQL 현재 스키마(search_path 의 첫 실재 스키마). 기본 스키마를 비운 접속만. */
  private static final Map<String, String> CURRENT_SCHEMA = new ConcurrentHashMap<>();

  /** DbSessions 가 기본 스키마를 비운 PostgreSQL 접속을 연 뒤 부른다. null(search_path 에 실재 스키마 없음)이면 기억을 지운다. */
  public static void rememberSchema(String id, String schema) {
    if (schema == null || schema.isBlank()) CURRENT_SCHEMA.remove(id); else CURRENT_SCHEMA.put(id, schema);
  }

  /** 표로 보일 TABLE_TYPE. */
  public static String[] tableTypes(DbConfig c) {
    return c.kind == DbKind.POSTGRES ? PG_TABLE_TYPES.clone() : ORACLE_TABLE_TYPES.clone();
  }

  /** 뷰로 보일 TABLE_TYPE. */
  public static String[] viewTypes(DbConfig c) {
    return c.kind == DbKind.POSTGRES ? PG_VIEW_TYPES.clone() : ORACLE_VIEW_TYPES.clone();
  }

  /** 표 · 뷰를 함께 읽을 TABLE_TYPE. */
  public static String[] tableAndViewTypes(DbConfig c) {
    String[] tables = tableTypes(c);
    String[] views = viewTypes(c);
    String[] out = Arrays.copyOf(tables, tables.length + views.length);
    System.arraycopy(views, 0, out, tables.length, views.length);
    return out;
  }

  /** 기본 스키마를 비운 PostgreSQL 접속이라 접속해야 현재 스키마를 아는데 아직 모르면 true. 이때 schemaOf 는 public 으로 짐작한다. */
  public static boolean schemaUnknown(DbConfig c) {
    return c.kind == DbKind.POSTGRES && (c.schema == null || c.schema.isBlank()) && !CURRENT_SCHEMA.containsKey(c.id);
  }

  /** PostgreSQL 에서 비웠으면 접속 뒤 읽은 현재 스키마, 아직 모르면 public. */
  public static String schemaOf(DbConfig c) {
    if (c.kind == DbKind.POSTGRES && (c.schema == null || c.schema.isBlank())) return CURRENT_SCHEMA.getOrDefault(c.id, PG_DEFAULT_SCHEMA);
    if (c.kind == DbKind.MYSQL && (c.schema == null || c.schema.isBlank())) return c.service;
    return c.effectiveSchema();
  }

  private static String catalog(DbConfig c, String schema) { return c.kind == DbKind.MYSQL ? schema : null; }

  private static String schemaArg(DbConfig c, String schema) { return c.kind == DbKind.MYSQL ? null : schema; }

  public static List<String> schemas(Connection conn, DbConfig c) throws SQLException {
    List<String> out = new ArrayList<>();
    DatabaseMetaData md = conn.getMetaData();
    try (ResultSet rs = c.kind == DbKind.MYSQL ? md.getCatalogs() : md.getSchemas()) {
      while (rs.next()) out.add(rs.getString(1));
    }
    return out;
  }

  /** types 예: {"TABLE"}, {"VIEW"}. */
  public static List<Table> tables(Connection conn, DbConfig c, String schema, String[] types) throws SQLException {
    List<Table> out = new ArrayList<>();
    try (ResultSet rs = conn.getMetaData().getTables(catalog(c, schema), schemaArg(c, schema), "%", types)) {
      while (rs.next()) {
        String name = rs.getString("TABLE_NAME");
        if (name.startsWith("BIN$")) continue; // Oracle 휴지통
        out.add(new Table(name, rs.getString("TABLE_TYPE"), nz(rs.getString("REMARKS"))));
      }
    }
    return out;
  }

  public static List<Column> columns(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    DatabaseMetaData md = conn.getMetaData();
    Set<String> pks = new HashSet<>();
    try (ResultSet rs = md.getPrimaryKeys(catalog(c, schema), schemaArg(c, schema), table)) {
      while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
    }
    Set<String> fks = new HashSet<>();
    for (ForeignKey fk : importedKeys(conn, c, schema, table)) fks.add(fk.fkColumn());
    List<Column> out = new ArrayList<>();
    // getColumns 는 이름을 LIKE 패턴으로 보아 _ · % 가 든 표 이름이면 다른 표의 칼럼도 돌려준다. 이름이 정확히 같은 줄만 쓴다.
    String schemaName = schemaArg(c, schema);
    String catalogName = catalog(c, schema);
    try (ResultSet rs = md.getColumns(catalogName, schemaName, table, "%")) {
      while (rs.next()) {
        if (schemaName != null && !schemaName.isEmpty() && !schemaName.equals(rs.getString("TABLE_SCHEM"))) continue;
        if (!table.equals(rs.getString("TABLE_NAME"))) continue;
        String name = rs.getString("COLUMN_NAME");
        out.add(new Column(name, rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
                           pks.contains(name), fks.contains(name), rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                           nz(rs.getString("REMARKS"))));
      }
    }
    return out;
  }

  public static List<ForeignKey> importedKeys(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    List<ForeignKey> out = new ArrayList<>();
    try (ResultSet rs = conn.getMetaData().getImportedKeys(catalog(c, schema), schemaArg(c, schema), table)) {
      while (rs.next()) {
        out.add(new ForeignKey(nz(rs.getString("FK_NAME")), rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME"),
                               rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")));
      }
    }
    return out;
  }

  private static String nz(String s) { return s == null ? "" : s.trim(); }
}
