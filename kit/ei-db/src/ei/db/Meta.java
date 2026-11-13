package ei.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
  /** 메타 이름 패턴의 「모두」. */
  private static final String ALL = "%";
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

  /** getColumns 한 줄: 표 이름, 칼럼(pk · fk 는 거짓), 기본값(withDefault 가 아니거나 없으면 null). */
  public record ColumnRow(String table, Column column, String def) {}

  public static List<Column> columns(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    Set<String> pks = primaryKeys(conn, c, schema, table);
    Set<String> fks = new HashSet<>();
    for (ForeignKey fk : importedKeys(conn, c, schema, table)) fks.add(fk.fkColumn());
    List<Column> out = new ArrayList<>();
    for (ColumnRow r : columnRows(conn, c, schema, table, false)) {
      Column col = r.column();
      out.add(new Column(col.name(), col.type(), col.size(), col.scale(), pks.contains(col.name()), fks.contains(col.name()), col.nullable(), col.comment()));
    }
    return out;
  }

  /** 표의 PK 칼럼 이름. getPrimaryKeys 의 표 이름은 패턴이 아니라 정확한 이름이다. */
  public static Set<String> primaryKeys(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    Set<String> pks = new HashSet<>();
    try (ResultSet rs = conn.getMetaData().getPrimaryKeys(catalog(c, schema), schemaArg(c, schema), table)) {
      while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
    }
    return pks;
  }

  /**
   * 칼럼 줄들(DB 가 돌려준 순서: 표 · 칼럼 순번). table 이 null 이면 스키마의 모든 표를 한 번에 읽는다.
   * getColumns 는 이름을 LIKE 패턴으로 보아 _ · % 가 든 표 이름이면 다른 표의 칼럼도 돌려주므로 table 과 이름이 정확히 같은 줄만,
   * 스키마가 다른 줄(드라이버가 스키마를 무시한 경우)도 뺀다. TABLE_SCHEM 이 비어 오면 스키마는 맞는 것으로 본다.
   * Oracle 의 COLUMN_DEF 는 LONG 이라 칸 번호 순서(TABLE_SCHEM → TABLE_NAME → … → REMARKS → COLUMN_DEF)대로 읽는다.
   */
  public static List<ColumnRow> columnRows(Connection conn, DbConfig c, String schema, String table, boolean withDefault) throws SQLException {
    List<ColumnRow> out = new ArrayList<>();
    String schemaName = schemaArg(c, schema);
    try (ResultSet rs = conn.getMetaData().getColumns(catalog(c, schema), schemaName, table == null ? ALL : table, ALL)) {
      while (rs.next()) {
        String rowSchema = rs.getString("TABLE_SCHEM");
        if (schemaName != null && !schemaName.isEmpty() && rowSchema != null && !schemaName.equals(rowSchema)) continue;
        String rowTable = rs.getString("TABLE_NAME");
        if (table != null && !table.equals(rowTable)) continue;
        String name = rs.getString("COLUMN_NAME");
        Column col = new Column(name, rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
                                false, false, rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, nz(rs.getString("REMARKS")));
        String def = withDefault ? rs.getString("COLUMN_DEF") : null;
        out.add(new ColumnRow(rowTable, col, def == null || def.isBlank() ? null : def.trim()));
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

  /** 이 표를 가리키는 다른 표들의 외래 키(importedKeys 의 반대 방향). table 은 메타 이름 그대로. */
  public static List<ForeignKey> exportedKeys(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    List<ForeignKey> out = new ArrayList<>();
    try (ResultSet rs = conn.getMetaData().getExportedKeys(catalog(c, schema), schemaArg(c, schema), table)) {
      while (rs.next()) {
        out.add(new ForeignKey(nz(rs.getString("FK_NAME")), rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME"),
                               rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")));
      }
    }
    return out;
  }

  /** PK 사전 쿼리. 바인드 1 = 스키마(MySQL 은 데이터베이스). 결과 칼럼: 표 이름, PK 칼럼 이름(PK 칼럼마다 한 줄). */
  private static final String ORACLE_PK_SQL = """
      SELECT cc.TABLE_NAME AS TABLE_NAME
           , cc.COLUMN_NAME AS COLUMN_NAME
        FROM ALL_CONSTRAINTS k, ALL_CONS_COLUMNS cc
       WHERE k.OWNER = ?
         AND k.CONSTRAINT_TYPE = 'P'
         AND k.TABLE_NAME NOT LIKE 'BIN$%'
         AND cc.OWNER = k.OWNER
         AND cc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
         AND cc.TABLE_NAME = k.TABLE_NAME""";
  /** information_schema.table_constraints 는 조회 권한만 있는 표를 보이지 않아 pg_catalog 를 읽는다. */
  private static final String POSTGRES_PK_SQL = """
      SELECT t.relname AS TABLE_NAME
           , a.attname AS COLUMN_NAME
        FROM pg_catalog.pg_constraint k, pg_catalog.pg_class t, pg_catalog.pg_namespace n, pg_catalog.pg_attribute a
       WHERE n.nspname = ?
         AND t.relnamespace = n.oid
         AND k.conrelid = t.oid
         AND k.contype = 'p'
         AND a.attrelid = t.oid
         AND a.attnum = ANY (k.conkey)""";
  private static final String MYSQL_PK_SQL = """
      SELECT ku.TABLE_NAME AS TABLE_NAME
           , ku.COLUMN_NAME AS COLUMN_NAME
        FROM information_schema.KEY_COLUMN_USAGE ku
       WHERE ku.TABLE_SCHEMA = ?
         AND ku.CONSTRAINT_NAME = 'PRIMARY'""";

  public static String primaryKeySql(DbKind kind) {
    return switch (kind) {
      case ORACLE -> ORACLE_PK_SQL;
      case POSTGRES -> POSTGRES_PK_SQL;
      case MYSQL -> MYSQL_PK_SQL;
    };
  }

  /**
   * 스키마의 모든 표의 PK: 표 이름(메타 그대로) → PK 칼럼 이름들(순서는 정해져 있지 않다). PK 가 없는 표는 들어 있지 않다.
   * 표마다 getPrimaryKeys 를 부르지 않도록 데이터 사전(primaryKeySql)을 한 번 읽는다.
   */
  public static Map<String, List<String>> schemaPrimaryKeys(Connection conn, DbConfig c, String schema) throws SQLException {
    Map<String, List<String>> out = new java.util.TreeMap<>();
    try (java.sql.PreparedStatement ps = conn.prepareStatement(primaryKeySql(c.kind))) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
      }
    }
    return out;
  }

  /**
   * SQL 에 쓴 이름을 메타가 돌려주는 이름으로: 따옴표로 감쌌으면 따옴표만 뗀 그대로, 아니면 Oracle 은 대문자 · PostgreSQL 은 소문자
   * (따옴표 없는 이름을 DB 가 그렇게 저장한다), MySQL 은 쓴 그대로.
   */
  public static String storedName(DbConfig c, String written) {
    if (written.startsWith("\"")) return written.replace("\"", "");
    return switch (c.kind) {
      case ORACLE -> written.toUpperCase(Locale.ROOT);
      case POSTGRES -> written.toLowerCase(Locale.ROOT);
      case MYSQL -> written;
    };
  }

  private static String nz(String s) { return s == null ? "" : s.trim(); }
}
