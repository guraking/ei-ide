package ei.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

  public static String schemaOf(DbConfig c) {
    if (c.kind == DbKind.POSTGRES && (c.schema == null || c.schema.isBlank())) return "public";
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
    try (ResultSet rs = md.getColumns(catalog(c, schema), schemaArg(c, schema), table, "%")) {
      while (rs.next()) {
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
