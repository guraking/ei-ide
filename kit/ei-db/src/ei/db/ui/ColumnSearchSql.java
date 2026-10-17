package ei.db.ui;

import ei.db.DbKind;

import java.util.Locale;

/**
 * 표 · 칼럼 찾기 데이터 사전 쿼리. 순수 자바.
 * 바인드: 1 = 스키마, 2~5 = like(검색어)(칼럼 이름 · 칼럼 주석 · 표 이름 · 표 주석, 대문자 비교).
 * 결과 칼럼 순서: 표 이름, 칼럼 이름, 형식, 크기, 소수 자리, 칼럼 주석, 표 주석. 정렬은 표 이름 · 칼럼 순번.
 * LIKE 의 이스케이프 글자는 세 DB 의 문자열 규칙이 같은 '!' 를 쓴다(MySQL 에서 '\' 는 문자열 안 이스케이프라 피한다).
 */
final class ColumnSearchSql {
  private ColumnSearchSql() {}

  static final int MAX_HITS = 500;
  private static final char ESCAPE = '!';

  private static final String ORACLE = """
      SELECT c.TABLE_NAME AS TABLE_NAME
           , c.COLUMN_NAME AS COLUMN_NAME
           , c.DATA_TYPE AS TYPE_NAME
           , NVL(c.DATA_PRECISION, c.CHAR_LENGTH) AS TYPE_SIZE
           , NVL(c.DATA_SCALE, 0) AS TYPE_SCALE
           , cc.COMMENTS AS COLUMN_COMMENT
           , tc.COMMENTS AS TABLE_COMMENT
           , c.COLUMN_ID AS ORD
        FROM ALL_TAB_COLUMNS c, ALL_COL_COMMENTS cc, ALL_TAB_COMMENTS tc
       WHERE c.OWNER = ?
         AND c.TABLE_NAME NOT LIKE 'BIN$%'
         AND cc.OWNER(+) = c.OWNER
         AND cc.TABLE_NAME(+) = c.TABLE_NAME
         AND cc.COLUMN_NAME(+) = c.COLUMN_NAME
         AND tc.OWNER(+) = c.OWNER
         AND tc.TABLE_NAME(+) = c.TABLE_NAME""";

  private static final String POSTGRES = """
      SELECT c.table_name AS TABLE_NAME
           , c.column_name AS COLUMN_NAME
           , c.data_type AS TYPE_NAME
           , COALESCE(CASE WHEN c.data_type = 'numeric' THEN c.numeric_precision ELSE c.character_maximum_length END, 0) AS TYPE_SIZE
           , COALESCE(CASE WHEN c.data_type = 'numeric' THEN c.numeric_scale END, 0) AS TYPE_SCALE
           , COALESCE(col_description(pc.oid, CAST(c.ordinal_position AS INTEGER)), '') AS COLUMN_COMMENT
           , COALESCE(obj_description(pc.oid, 'pg_class'), '') AS TABLE_COMMENT
           , c.ordinal_position AS ORD
        FROM information_schema.columns c, pg_catalog.pg_class pc, pg_catalog.pg_namespace pn
       WHERE c.table_schema = ?
         AND pn.nspname = c.table_schema
         AND pc.relnamespace = pn.oid
         AND pc.relname = c.table_name""";

  /** 형식은 COLUMN_TYPE(varchar(100) 꼴)을 그대로 쓰므로 크기는 0. */
  private static final String MYSQL = """
      SELECT c.TABLE_NAME AS TABLE_NAME
           , c.COLUMN_NAME AS COLUMN_NAME
           , UPPER(c.COLUMN_TYPE) AS TYPE_NAME
           , 0 AS TYPE_SIZE
           , 0 AS TYPE_SCALE
           , c.COLUMN_COMMENT AS COLUMN_COMMENT
           , t.TABLE_COMMENT AS TABLE_COMMENT
           , c.ORDINAL_POSITION AS ORD
        FROM information_schema.COLUMNS c, information_schema.TABLES t
       WHERE c.TABLE_SCHEMA = ?
         AND t.TABLE_SCHEMA = c.TABLE_SCHEMA
         AND t.TABLE_NAME = c.TABLE_NAME""";

  static String sql(DbKind kind) {
    String inner = switch (kind) {
      case ORACLE -> ORACLE;
      case POSTGRES -> POSTGRES;
      case MYSQL -> MYSQL;
    };
    String like = " LIKE ? ESCAPE '" + ESCAPE + "'";
    return "SELECT x.*\n  FROM (\n" + inner + "\n       ) x\n"
           + " WHERE UPPER(x.COLUMN_NAME)" + like + "\n"
           + "    OR UPPER(x.COLUMN_COMMENT)" + like + "\n"
           + "    OR UPPER(x.TABLE_NAME)" + like + "\n"
           + "    OR UPPER(x.TABLE_COMMENT)" + like + "\n"
           + " ORDER BY x.TABLE_NAME, x.ORD";
  }

  /** 들어 있음 비교용 LIKE 값: 대문자, % _ ! 는 이스케이프. */
  static String like(String query) {
    StringBuilder sb = new StringBuilder("%");
    for (char ch : query.trim().toUpperCase(Locale.ROOT).toCharArray()) {
      if (ch == '%' || ch == '_' || ch == ESCAPE) sb.append(ESCAPE);
      sb.append(ch);
    }
    return sb.append('%').toString();
  }
}
