package ei.db.ui;

import com.intellij.openapi.diagnostic.Logger;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Meta;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 표 · 뷰 하나의 CREATE 문을 읽는다. 화면 스레드에서 부르지 않는다. 커밋 · 롤백은 하지 않는다(조회와 세션 설정뿐).
 * Oracle: DBMS_METADATA(GET_DDL + 제약이 만들지 않은 인덱스 + 주석). 세션 변환 옵션을 걸고 끝나면(실패해도) 기본값으로 되돌린다:
 * 콘솔과 같은 세션이라 남겨 두면 사용자의 DBMS_METADATA 결과가 달라진다. DBMS_METADATA 가 실패하면(권한 없음 등)
 * JDBC 메타데이터로 조립하고 그 까닭을 fallbackReason 에 담는다. 인덱스 · 주석만 못 읽으면 그 자리에 `--` 한 줄을 남긴다.
 * PostgreSQL: 카탈로그(pg_catalog)로 조립. MySQL: SHOW CREATE TABLE 그대로.
 * 이름(스키마 · 표)은 메타가 돌려준 그대로 받아 바인드로 넘긴다.
 */
final class DdlRead {
  private DdlRead() {}

  private static final Logger LOG = Logger.getInstance(DdlRead.class);

  /** ddl 은 줄바꿈으로 끝난다. fallbackReason 은 메타데이터로 조립했을 때 DBMS_METADATA 가 실패한 까닭, 아니면 null. */
  record Result(String ddl, String fallbackReason) {}

  static Result read(Connection conn, DbConfig c, String schema, String table, boolean storage) throws SQLException {
    return switch (c.kind) {
      case ORACLE -> oracle(conn, c, schema, table, storage);
      case POSTGRES -> new Result(postgres(conn, c, schema, table), null);
      case MYSQL -> new Result(mysql(conn, schema, table), null);
    };
  }

  // ---- Oracle ----

  /** 바인드: 1 = 소유자, 2 = 이름. 구체화 뷰는 TABLE 과 MATERIALIZED VIEW 두 줄이 나온다. */
  private static final String ORACLE_TYPE = """
      SELECT
             o.OBJECT_TYPE
        FROM ALL_OBJECTS o
       WHERE o.OWNER = ?
         AND o.OBJECT_NAME = ?
         AND o.OBJECT_TYPE IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW')""";
  private static final String ORACLE_VIEW = "VIEW";
  private static final String ORACLE_MVIEW = "MATERIALIZED VIEW";

  /** 바인드: 1 = DBMS_METADATA 객체 형, 2 = 이름, 3 = 소유자. */
  private static final String ORACLE_DDL = "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL";
  /** 바인드: 1 = 표 이름, 2 = 소유자. 주석이 하나도 없으면 ORA-31608. */
  private static final String ORACLE_COMMENTS = "SELECT DBMS_METADATA.GET_DEPENDENT_DDL('COMMENT', ?, ?) FROM DUAL";
  private static final int ORA_NO_DEPENDENT = 31608;
  private static final String META_TABLE = "TABLE";
  private static final String META_VIEW = "VIEW";
  private static final String META_MVIEW = "MATERIALIZED_VIEW";
  private static final String META_INDEX = "INDEX";

  /**
   * 바인드: 1 = 표 소유자, 2 = 표 이름. PK · UNIQUE 제약이 쓰는 인덱스는 표 DDL 의 제약이 만들므로 뺀다(따로 만들면 겹친다).
   * LOB 인덱스는 LOB 칼럼이 만든다.
   */
  private static final String ORACLE_INDEXES = """
      SELECT
             i.OWNER
           , i.INDEX_NAME
        FROM ALL_INDEXES i
       WHERE i.TABLE_OWNER = ?
         AND i.TABLE_NAME = ?
         AND i.INDEX_TYPE <> 'LOB'
         AND NOT EXISTS (
                 SELECT 1
                   FROM ALL_CONSTRAINTS c
                  WHERE c.OWNER = i.TABLE_OWNER
                    AND c.TABLE_NAME = i.TABLE_NAME
                    AND c.INDEX_NAME = i.INDEX_NAME
                    AND NVL(c.INDEX_OWNER, c.OWNER) = i.OWNER
             )
       ORDER BY i.INDEX_NAME""";

  /** 세션 변환 옵션. %s 는 저장 옵션(STORAGE · TABLESPACE · SEGMENT_ATTRIBUTES)을 넣을지(TRUE · FALSE). */
  private static final String ORACLE_SET_TRANSFORM = """
      BEGIN
        DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SQLTERMINATOR', TRUE);
        DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'PRETTY', TRUE);
        DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', %1$s);
        DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'TABLESPACE', %1$s);
        DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SEGMENT_ATTRIBUTES', %1$s);
      END;""";
  private static final String ORACLE_RESET_TRANSFORM =
      "BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'DEFAULT'); END;";
  private static final String PLSQL_TRUE = "TRUE";
  private static final String PLSQL_FALSE = "FALSE";

  /** 바인드: 1 = 소유자, 2 = 이름. TEXT · QUERY 는 LONG 이라 그 칸 하나만 읽는다. */
  private static final String ORACLE_VIEW_TEXT = """
      SELECT
             v.TEXT
        FROM ALL_VIEWS v
       WHERE v.OWNER = ?
         AND v.VIEW_NAME = ?""";
  private static final String ORACLE_MVIEW_TEXT = """
      SELECT
             m.QUERY
        FROM ALL_MVIEWS m
       WHERE m.OWNER = ?
         AND m.MVIEW_NAME = ?""";

  /** Oracle 객체 종류. 구체화 뷰는 같은 이름의 표도 있으므로 먼저 본다. 못 찾으면(권한 등) 표로 본다. */
  static CommentSql.ObjectKind oracleKind(Connection conn, String schema, String name) throws SQLException {
    List<String> types = strings(conn, ORACLE_TYPE, schema, name);
    if (types.contains(ORACLE_MVIEW)) return CommentSql.ObjectKind.MATERIALIZED_VIEW;
    return types.contains(ORACLE_VIEW) ? CommentSql.ObjectKind.VIEW : CommentSql.ObjectKind.TABLE;
  }

  private static Result oracle(Connection conn, DbConfig c, String schema, String table, boolean storage) throws SQLException {
    CommentSql.ObjectKind kind = oracleKind(conn, schema, table);
    SQLException cause;
    try {
      return new Result(oracleMetadata(conn, schema, table, kind, storage), null);
    } catch (SQLException e) {
      cause = e;
    }
    try {
      return new Result(DdlBuild.create(c.kind, jdbcTable(conn, c, schema, table, kind)), message(cause));
    } catch (SQLException e) {
      // 대체 길도 실패하면 처음 까닭(권한 없음 등)이 더 쓸모 있다.
      e.addSuppressed(cause);
      throw new SQLException(message(cause) + " / " + message(e), e);
    }
  }

  private static String oracleMetadata(Connection conn, String schema, String table, CommentSql.ObjectKind kind, boolean storage) throws SQLException {
    try {
      execute(conn, ORACLE_SET_TRANSFORM.formatted(storage ? PLSQL_TRUE : PLSQL_FALSE));
      String type = switch (kind) {
        case VIEW -> META_VIEW;
        case MATERIALIZED_VIEW -> META_MVIEW;
        default -> META_TABLE;
      };
      StringBuilder sb = new StringBuilder(oracleDdl(conn, type, table, schema)).append('\n');
      if (kind != CommentSql.ObjectKind.VIEW) {
        try {
          for (String[] index : pairs(conn, ORACLE_INDEXES, schema, table)) {
            sb.append('\n').append(oracleDdl(conn, META_INDEX, index[1], index[0])).append('\n');
          }
        } catch (SQLException e) {
          sb.append('\n').append(EiDbBundle.message("ddl.part.indexes.failed", oneLine(message(e)))).append('\n');
        }
      }
      try {
        String comments = clob(conn, ORACLE_COMMENTS, table, schema);
        if (!comments.isEmpty()) sb.append('\n').append(comments).append('\n');
      } catch (SQLException e) {
        if (e.getErrorCode() != ORA_NO_DEPENDENT) {
          sb.append('\n').append(EiDbBundle.message("ddl.part.comments.failed", oneLine(message(e)))).append('\n');
        }
      }
      return sb.toString();
    } finally {
      try {
        execute(conn, ORACLE_RESET_TRANSFORM);
      } catch (SQLException e) {
        // 권한이 없어 걸지도 못한 경우가 대부분이다. 되돌릴 것이 없으니 기록만 한다.
        LOG.info("DBMS_METADATA 세션 변환 옵션을 되돌리지 못했습니다: " + e.getMessage());
      }
    }
  }

  private static String oracleDdl(Connection conn, String type, String name, String owner) throws SQLException {
    String ddl = clob(conn, ORACLE_DDL, type, name, owner);
    if (ddl.isEmpty()) throw new SQLException(EiDbBundle.message("ddl.not.found", owner + "." + name));
    return ddl;
  }

  // ---- JDBC 메타데이터로 조립(Oracle 대체 길) ----

  /**
   * 칼럼(형 · 기본값 · NOT NULL · 주석) · PK · FK · 인덱스 · 표 주석을 DatabaseMetaData 로 읽는다. 뷰 · 구체화 뷰 본문은 데이터 사전에서 읽는다.
   * 빠지는 것: 저장 옵션, CHECK · UNIQUE 제약(UNIQUE 는 유일 인덱스로 나온다), 함수 기반 인덱스의 식, 분할, 문자 길이 단위(CHAR · BYTE).
   */
  private static DdlBuild.Table jdbcTable(Connection conn, DbConfig c, String schema, String table, CommentSql.ObjectKind kind) throws SQLException {
    List<DdlBuild.Column> columns = new ArrayList<>();
    for (Meta.ColumnRow r : Meta.columnRows(conn, c, schema, table, true)) {
      Meta.Column col = r.column();
      columns.add(new DdlBuild.Column(col.name(), col.typeLabel(), r.def(), null, !col.nullable(), col.comment()));
    }
    if (columns.isEmpty()) throw new SQLException(EiDbBundle.message("ddl.not.found", schema + "." + table));
    DatabaseMetaData md = conn.getMetaData();
    String comment = "";
    try (ResultSet rs = md.getTables(null, schema, table, null)) {
      while (rs.next()) {
        if (table.equals(rs.getString("TABLE_NAME")) && rs.getString("REMARKS") != null) comment = rs.getString("REMARKS");
      }
    }
    if (kind == CommentSql.ObjectKind.VIEW || kind == CommentSql.ObjectKind.MATERIALIZED_VIEW) {
      List<String> text = strings(conn, kind == CommentSql.ObjectKind.VIEW ? ORACLE_VIEW_TEXT : ORACLE_MVIEW_TEXT, schema, table);
      if (text.isEmpty() || text.get(0).isBlank()) throw new SQLException(EiDbBundle.message("ddl.not.found", schema + "." + table));
      return new DdlBuild.Table(kind, schema, table, columns, List.of(), null, text.get(0), List.of(), comment);
    }

    List<DdlBuild.Constraint> constraints = new ArrayList<>();
    String pkName = "";
    Map<Integer, String> pk = new TreeMap<>();
    try (ResultSet rs = md.getPrimaryKeys(null, schema, table)) {
      while (rs.next()) {
        pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        if (rs.getString("PK_NAME") != null) pkName = rs.getString("PK_NAME");
      }
    }
    if (!pk.isEmpty()) constraints.add(new DdlBuild.Constraint(pkName, DdlBuild.primaryKey(c.kind, new ArrayList<>(pk.values()))));

    // FK 이름 → 묶음. 이름 없는 FK 는 참조 표로 묶는다.
    Map<String, Fk> fks = new LinkedHashMap<>();
    try (ResultSet rs = md.getImportedKeys(null, schema, table)) {
      while (rs.next()) {
        String fkName = rs.getString("FK_NAME");
        String refSchema = rs.getString("PKTABLE_SCHEM");
        String refTable = rs.getString("PKTABLE_NAME");
        Fk fk = fks.computeIfAbsent(fkName == null ? UNNAMED + refSchema + "." + refTable : fkName,
            k -> new Fk(fkName == null ? "" : fkName, refSchema == null ? schema : refSchema, refTable, new TreeMap<>(), new TreeMap<>()));
        int seq = rs.getInt("KEY_SEQ");
        fk.columns().put(seq, rs.getString("FKCOLUMN_NAME"));
        fk.refColumns().put(seq, rs.getString("PKCOLUMN_NAME"));
      }
    }
    for (Fk fk : fks.values()) {
      constraints.add(new DdlBuild.Constraint(fk.name(),
          DdlBuild.foreignKey(c.kind, new ArrayList<>(fk.columns().values()), fk.refSchema(), fk.refTable(), new ArrayList<>(fk.refColumns().values()))));
    }

    // 인덱스 이름 → 칼럼(순번대로). PK 인덱스는 제약이 만든다. approximate = true: Oracle 드라이버가 통계를 다시 모으지(ANALYZE) 않게.
    Map<String, Map<Integer, String>> indexColumns = new LinkedHashMap<>();
    Map<String, Boolean> unique = new LinkedHashMap<>();
    try (ResultSet rs = md.getIndexInfo(null, schema, table, false, true)) {
      while (rs.next()) {
        String index = rs.getString("INDEX_NAME");
        String column = rs.getString("COLUMN_NAME");
        if (index == null || column == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic || index.equals(pkName)) continue;
        indexColumns.computeIfAbsent(index, k -> new TreeMap<>()).put((int) rs.getShort("ORDINAL_POSITION"), column);
        unique.put(index, !rs.getBoolean("NON_UNIQUE"));
      }
    }
    List<String> indexes = new ArrayList<>();
    for (Map.Entry<String, Map<Integer, String>> e : indexColumns.entrySet()) {
      if (DdlBuild.backsPrimaryKey(unique.get(e.getKey()), new ArrayList<>(e.getValue().values()), new ArrayList<>(pk.values()))) continue;
      indexes.add(DdlBuild.index(c.kind, schema, e.getKey(), unique.get(e.getKey()), table, new ArrayList<>(e.getValue().values())));
    }
    return new DdlBuild.Table(kind, schema, table, columns, constraints, null, null, indexes, comment);
  }

  /** 외래 키 하나. 칼럼은 KEY_SEQ → 이름. */
  private record Fk(String name, String refSchema, String refTable, Map<Integer, String> columns, Map<Integer, String> refColumns) {}

  /** 이름 없는 외래 키를 묶는 키의 머리(이름에 올 수 없는 글자). */
  private static final String UNNAMED = "\0";

  // ---- PostgreSQL ----

  /** 바인드: 1 = 스키마, 2 = 이름. 결과: oid, relkind(r 표 · p 분할 표 · f 외부 표 · v 뷰 · m 구체화 뷰), 표 주석. */
  private static final String PG_CLASS = """
      SELECT
             c.oid
           , c.relkind
           , pg_catalog.obj_description(c.oid, 'pg_class')
        FROM pg_catalog.pg_class c
           , pg_catalog.pg_namespace n
       WHERE n.oid = c.relnamespace
         AND n.nspname = ?
         AND c.relname = ?
         AND c.relkind IN ('r', 'p', 'f', 'v', 'm')""";

  /**
   * 바인드: 1 = 표 oid. %1$s · %2$s 는 identity · generated 칸(없는 판에서는 빈 글 상수).
   * 결과: 이름, 형, NOT NULL, 기본값 식, identity, generated, 주석, 그 칼럼이 가진 시퀀스(serial · identity, 없으면 NULL).
   */
  private static final String PG_COLUMNS = """
      SELECT
             a.attname
           , pg_catalog.format_type(a.atttypid, a.atttypmod)
           , a.attnotnull
           , (SELECT pg_catalog.pg_get_expr(d.adbin, d.adrelid) FROM pg_catalog.pg_attrdef d WHERE d.adrelid = a.attrelid AND d.adnum = a.attnum)
           , %1$s
           , %2$s
           , pg_catalog.col_description(a.attrelid, a.attnum)
           , pg_catalog.pg_get_serial_sequence(CAST(CAST(a.attrelid AS regclass) AS text), a.attname)
        FROM pg_catalog.pg_attribute a
       WHERE a.attrelid = CAST(? AS oid)
         AND a.attnum > 0
         AND NOT a.attisdropped
       ORDER BY a.attnum""";
  private static final String PG_IDENTITY_COLUMN = "a.attidentity";
  private static final String PG_GENERATED_COLUMN = "a.attgenerated";
  private static final String PG_NO_COLUMN = "''";
  /** identity 칼럼(attidentity)이 생긴 판, 생성 칼럼(attgenerated)이 생긴 판. */
  private static final int PG_IDENTITY_SINCE = 10;
  private static final int PG_GENERATED_SINCE = 12;
  private static final String PG_IDENTITY_ALWAYS = "a";
  private static final String PG_IDENTITY_BY_DEFAULT = "d";
  private static final String PG_GENERATED_STORED = "s";
  /** serial 칼럼: 칼럼이 가진 시퀀스의 nextval 이 기본값인 정수 칼럼. 시퀀스가 없는 곳에서도 실행되게 serial 형으로 쓴다. */
  private static final String PG_NEXTVAL = "nextval(";
  private static final Map<String, String> PG_SERIAL_TYPES = Map.of("smallint", "smallserial", "integer", "serial", "bigint", "bigserial");
  /** 가상 생성 칼럼(18 부터). */
  private static final String PG_GENERATED_VIRTUAL = "v";

  /**
   * 바인드: 1 = 표 oid. PK → UNIQUE → CHECK → 배제 → FK 순. NOT NULL 제약(18 부터 n)은 칼럼 줄에 쓴다.
   * %s 는 분할 표를 가리키는 FK 가 분할마다 만든 복제 FK 를 빼는 조건(conparentid 가 없는 판에서는 빈 글).
   */
  private static final String PG_CONSTRAINTS = """
      SELECT
             c.conname
           , pg_catalog.pg_get_constraintdef(c.oid)
        FROM pg_catalog.pg_constraint c
       WHERE c.conrelid = CAST(? AS oid)
         AND c.contype IN ('p', 'u', 'c', 'x', 'f')%s
       ORDER BY CASE c.contype WHEN 'p' THEN 0 WHEN 'u' THEN 1 WHEN 'c' THEN 2 WHEN 'x' THEN 3 ELSE 4 END
           , c.conname""";

  private static final String PG_NO_FK_CLONES = "\n   AND (c.contype <> 'f' OR c.conparentid = 0)";
  /** pg_constraint.conparentid 가 생긴 판. */
  private static final int PG_CONPARENT_SINCE = 11;

  /** 바인드: 1 = 표 oid. 이 표의 PK · UNIQUE · 배제 제약이 만든 인덱스와 부모(분할 표)의 인덱스가 만든 분할 쪽 인덱스는 뺀다. */
  private static final String PG_INDEXES = """
      SELECT
             pg_catalog.pg_get_indexdef(i.indexrelid)
        FROM pg_catalog.pg_index i
           , pg_catalog.pg_class ic
       WHERE ic.oid = i.indexrelid
         AND i.indrelid = CAST(? AS oid)
         AND NOT EXISTS (
                 SELECT 1
                   FROM pg_catalog.pg_constraint c
                  WHERE c.conindid = i.indexrelid
                    AND c.conrelid = i.indrelid
                    AND c.contype IN ('p', 'u', 'x')
             )
         AND NOT EXISTS (
                 SELECT 1
                   FROM pg_catalog.pg_inherits h
                  WHERE h.inhrelid = i.indexrelid
             )
       ORDER BY ic.relname""";

  /** 바인드: 1 = 표 oid. 분할(자식) 표일 때만 한 줄: 부모 스키마, 부모 이름, 경계(FOR VALUES … · DEFAULT). relispartition 은 10 부터. */
  private static final String PG_PARTITION_OF = """
      SELECT
             pn.nspname
           , p.relname
           , pg_catalog.pg_get_expr(c.relpartbound, c.oid)
        FROM pg_catalog.pg_class c
           , pg_catalog.pg_inherits h
           , pg_catalog.pg_class p
           , pg_catalog.pg_namespace pn
       WHERE c.oid = CAST(? AS oid)
         AND c.relispartition
         AND h.inhrelid = c.oid
         AND p.oid = h.inhparent
         AND pn.oid = p.relnamespace""";
  private static final int PG_PARTITION_SINCE = 10;

  /** 분할 표의 인덱스 정의에 붙는 낱말. 그대로 실행하면 분할에는 인덱스가 생기지 않아(유효하지 않은 인덱스) 뗀다. */
  private static final String PG_INDEX_ON_ONLY = " ON ONLY ";
  private static final String PG_INDEX_ON = " ON ";

  private static final String PG_VIEW = "SELECT pg_catalog.pg_get_viewdef(CAST(? AS oid), true)";
  private static final String PG_PARTITION_KEY = "SELECT pg_catalog.pg_get_partkeydef(CAST(? AS oid))";
  private static final String PG_RELKIND_PARTITIONED = "p";
  private static final String PG_RELKIND_FOREIGN = "f";
  private static final String PG_RELKIND_VIEW = "v";
  private static final String PG_RELKIND_MVIEW = "m";

  private static String postgres(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    long oid;
    String relkind;
    String comment;
    try (PreparedStatement ps = conn.prepareStatement(PG_CLASS)) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new SQLException(EiDbBundle.message("ddl.not.found", schema + "." + table));
        oid = rs.getLong(1);
        relkind = rs.getString(2);
        comment = rs.getString(3);
      }
    }
    int major = conn.getMetaData().getDatabaseMajorVersion();
    String columnSql = PG_COLUMNS.formatted(major >= PG_IDENTITY_SINCE ? PG_IDENTITY_COLUMN : PG_NO_COLUMN,
                                            major >= PG_GENERATED_SINCE ? PG_GENERATED_COLUMN : PG_NO_COLUMN);
    List<DdlBuild.Column> columns = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(columnSql)) {
      ps.setLong(1, oid);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String expr = rs.getString(4);
          String identity = rs.getString(5);
          String generated = null;
          if (PG_IDENTITY_ALWAYS.equals(identity)) generated = "GENERATED ALWAYS AS IDENTITY";
          else if (PG_IDENTITY_BY_DEFAULT.equals(identity)) generated = "GENERATED BY DEFAULT AS IDENTITY";
          else if (PG_GENERATED_STORED.equals(rs.getString(6)) && expr != null) generated = "GENERATED ALWAYS AS (" + expr + ") STORED";
          else if (PG_GENERATED_VIRTUAL.equals(rs.getString(6)) && expr != null) generated = "GENERATED ALWAYS AS (" + expr + ") VIRTUAL";
          String type = rs.getString(2);
          if (generated == null && expr != null && expr.startsWith(PG_NEXTVAL) && rs.getString(8) != null && PG_SERIAL_TYPES.containsKey(type)) {
            type = PG_SERIAL_TYPES.get(type);
            expr = null;
          }
          columns.add(new DdlBuild.Column(rs.getString(1), type, expr, generated, rs.getBoolean(3), rs.getString(7)));
        }
      }
    }
    if (PG_RELKIND_VIEW.equals(relkind) || PG_RELKIND_MVIEW.equals(relkind)) {
      boolean mview = PG_RELKIND_MVIEW.equals(relkind);
      List<String> indexes = mview ? strings(conn, PG_INDEXES, oid) : List.of();
      List<String> query = strings(conn, PG_VIEW, oid);
      if (query.isEmpty() || query.get(0).isBlank()) throw new SQLException(EiDbBundle.message("ddl.not.found", schema + "." + table));
      CommentSql.ObjectKind kind = mview ? CommentSql.ObjectKind.MATERIALIZED_VIEW : CommentSql.ObjectKind.VIEW;
      return DdlBuild.create(c.kind, new DdlBuild.Table(kind, schema, table, columns, List.of(), null, query.get(0), indexes, comment));
    }
    List<DdlBuild.Constraint> constraints = new ArrayList<>();
    for (String[] row : pairs(conn, PG_CONSTRAINTS.formatted(major >= PG_CONPARENT_SINCE ? PG_NO_FK_CLONES : ""), oid)) constraints.add(new DdlBuild.Constraint(row[0], row[1]));
    String tail = null;
    if (PG_RELKIND_PARTITIONED.equals(relkind)) {
      List<String> key = strings(conn, PG_PARTITION_KEY, oid);
      if (!key.isEmpty() && !key.get(0).isBlank()) tail = "PARTITION BY " + key.get(0);
    }
    boolean foreign = PG_RELKIND_FOREIGN.equals(relkind);
    CommentSql.ObjectKind kind = foreign ? CommentSql.ObjectKind.FOREIGN_TABLE : CommentSql.ObjectKind.TABLE;
    List<String> indexes = strings(conn, PG_INDEXES, oid);
    if (PG_RELKIND_PARTITIONED.equals(relkind)) indexes.replaceAll(index -> index.replaceFirst(PG_INDEX_ON_ONLY, PG_INDEX_ON));
    DdlBuild.Table built = new DdlBuild.Table(kind, schema, table, columns, constraints, tail, null, indexes, comment);
    if (!foreign && major >= PG_PARTITION_SINCE) {
      try (PreparedStatement ps = conn.prepareStatement(PG_PARTITION_OF); ResultSet rs = query(ps, oid)) {
        if (rs.next() && rs.getString(3) != null) return DdlBuild.createPartition(c.kind, built, rs.getString(1), rs.getString(2), rs.getString(3));
      }
    }
    String ddl = DdlBuild.create(c.kind, built);
    // 외부 표의 SERVER · OPTIONS 는 조립하지 않는다. 그대로 실행하면 실패하므로 맨 위에 알린다.
    return foreign ? EiDbBundle.message("ddl.part.foreign") + "\n" + ddl : ddl;
  }

  // ---- MySQL ----

  private static final String MYSQL_SHOW = "SHOW CREATE TABLE ";
  /** SHOW CREATE TABLE · VIEW 결과의 CREATE 문 칸. */
  private static final int MYSQL_DDL_COLUMN = 2;

  private static String mysql(Connection conn, String schema, String table) throws SQLException {
    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(MYSQL_SHOW + backtick(schema) + "." + backtick(table))) {
      if (!rs.next()) throw new SQLException(EiDbBundle.message("ddl.not.found", schema + "." + table));
      return DdlBuild.statement(rs.getString(MYSQL_DDL_COLUMN)) + "\n";
    }
  }

  private static String backtick(String name) {
    return '`' + name.replace("`", "``") + '`';
  }

  // ---- 읽기 도우미 ----

  private static void execute(Connection conn, String sql) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute(sql);
    }
  }

  /** 바인드를 걸고 실행한다. 값은 글 또는 Long(oid). 결과는 부르는 쪽이 닫는다(문장을 닫으면 함께 닫힌다). */
  private static ResultSet query(PreparedStatement ps, Object... binds) throws SQLException {
    for (int i = 0; i < binds.length; i++) {
      if (binds[i] instanceof Long n) ps.setLong(i + 1, n); else ps.setString(i + 1, (String) binds[i]);
    }
    return ps.executeQuery();
  }

  /** 첫 칸을 줄마다(null 은 빈 글). */
  private static List<String> strings(Connection conn, String sql, Object... binds) throws SQLException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = query(ps, binds)) {
      while (rs.next()) out.add(nz(rs.getString(1)));
    }
    return out;
  }

  /** 첫 두 칸을 줄마다. */
  private static List<String[]> pairs(Connection conn, String sql, Object... binds) throws SQLException {
    List<String[]> out = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = query(ps, binds)) {
      while (rs.next()) out.add(new String[] {rs.getString(1), rs.getString(2)});
    }
    return out;
  }

  /** 첫 줄 첫 칸의 CLOB 을 끝까지 읽어 앞뒤 공백을 뗀다(결과 창과 달리 자르지 않는다). 줄이 없거나 NULL 이면 빈 글. */
  private static String clob(Connection conn, String sql, String... binds) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = query(ps, (Object[]) binds)) {
      if (!rs.next()) return "";
      Clob clob = rs.getClob(1);
      if (clob == null) return "";
      try {
        return clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE)).strip();
      } finally {
        clob.free();
      }
    }
  }

  private static String message(SQLException e) {
    return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage().strip();
  }

  private static String oneLine(String s) {
    return s.replaceAll("\\s+", " ");
  }

  private static String nz(String s) { return s == null ? "" : s; }
}
