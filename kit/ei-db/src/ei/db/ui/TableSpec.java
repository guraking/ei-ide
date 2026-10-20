package ei.db.ui;

import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.Meta;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테이블 정의서: 표 · 뷰마다 칼럼 한 줄씩, 한 장에 모두 잇달아 쓴다.
 * 칸 순서는 HEADER 와 같다. 이름 · 구분 · 표 주석은 그 표의 모든 줄에 되풀어 적어 엑셀 거르기로 표 하나만 볼 수 있게 한다.
 * IntelliJ 클래스를 쓰지 않는다(TableSpecCheck 로 점검).
 */
final class TableSpec {
  private TableSpec() {}

  static final List<String> HEADER = List.of("표", "구분", "표 주석", "No", "칼럼", "형", "길이", "NULL", "PK", "기본값", "주석");
  static final String YES = "Y";
  static final String NO = "N";
  /** 정의서에 싣는 getTables 형. */
  static final String[] TYPES = {"TABLE", "VIEW"};
  private static final String VIEW_TYPE = "VIEW";
  static final String KIND_TABLE = "표";
  static final String KIND_VIEW = "뷰";

  /** columns 는 칼럼 순서대로, defaults 는 칼럼 이름 → 기본값(없는 칼럼은 빈 칸). */
  record Table(Meta.Table table, List<Meta.Column> columns, Map<String, String> defaults) {}

  static List<List<Object>> rows(List<Table> tables) {
    List<List<Object>> out = new ArrayList<>();
    for (Table t : tables) {
      int no = 0;
      for (Meta.Column col : t.columns()) {
        List<Object> row = new ArrayList<>(HEADER.size());
        row.add(t.table().name());
        row.add(kind(t.table()));
        row.add(t.table().comment());
        row.add(++no);
        row.add(col.name());
        row.add(col.type());
        row.add(length(col));
        row.add(col.nullable() ? YES : NO);
        row.add(col.pk() ? YES : "");
        row.add(t.defaults().getOrDefault(col.name(), ""));
        row.add(col.comment());
        out.add(row);
      }
    }
    return out;
  }

  /** getTables 의 TABLE_TYPE 이 VIEW 면 「뷰」, 나머지는 「표」. */
  static String kind(Meta.Table t) {
    return VIEW_TYPE.equalsIgnoreCase(t.type()) ? KIND_VIEW : KIND_TABLE;
  }

  /** 칼럼을 하나라도 읽은 표만, 순서는 그대로. 권한이 없거나 그사이 지워진 표는 칼럼이 비어 빠진다. */
  static List<Table> filled(List<Table> tables) {
    List<Table> out = new ArrayList<>();
    for (Table t : tables) {
      if (!t.columns().isEmpty()) out.add(t);
    }
    return out;
  }

  /** typeLabel 의 괄호 안(「100」 · 「12,2」). 날짜 · LOB 처럼 길이를 붙이지 않는 형은 빈 글자. */
  static String length(Meta.Column col) {
    String label = col.typeLabel();
    if (label.equals(col.type())) return "";
    return label.substring(col.type().length() + 1, label.length() - 1);
  }

  /**
   * 표 하나의 칼럼 · PK · 기본값(COLUMN_DEF, 앞뒤 공백 제거)을 읽는다. 정의서에 쓰지 않는 외래키는 읽지 않는다(fk 는 늘 false).
   * getColumns 의 표 이름 자리는 LIKE 패턴이라 「_」 · 「%」 가 든 이름은 다른 표와도 맞는다: TABLE_NAME 이 같은 줄만 받는다.
   */
  static Table read(Connection conn, DbConfig c, String schema, Meta.Table table) throws SQLException {
    // Meta 와 같은 규칙: MySQL 은 데이터베이스가 catalog 자리, 나머지는 schema 자리.
    String catalog = c.kind == DbKind.MYSQL ? schema : null;
    String schemaArg = c.kind == DbKind.MYSQL ? null : schema;
    DatabaseMetaData md = conn.getMetaData();
    Set<String> pks = new HashSet<>();
    // getPrimaryKeys 의 표 이름은 패턴이 아니라 정확한 이름이다.
    try (ResultSet rs = md.getPrimaryKeys(catalog, schemaArg, table.name())) {
      while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
    }
    List<Meta.Column> columns = new ArrayList<>();
    Map<String, String> defaults = new HashMap<>();
    try (ResultSet rs = md.getColumns(catalog, schemaArg, table.name(), "%")) {
      while (rs.next()) {
        // Oracle 의 COLUMN_DEF 는 LONG 이라 칸 번호 순서(TABLE_NAME → … → REMARKS → COLUMN_DEF)대로 읽는다.
        if (!table.name().equals(rs.getString("TABLE_NAME"))) continue;
        String name = rs.getString("COLUMN_NAME");
        String type = rs.getString("TYPE_NAME");
        int size = rs.getInt("COLUMN_SIZE");
        int scale = rs.getInt("DECIMAL_DIGITS");
        boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
        String comment = trim(rs.getString("REMARKS"));
        String def = rs.getString("COLUMN_DEF");
        columns.add(new Meta.Column(name, type, size, scale, pks.contains(name), false, nullable, comment));
        if (def != null && !def.isBlank()) defaults.put(name, def.trim());
      }
    }
    return new Table(table, columns, defaults);
  }

  private static String trim(String s) { return s == null ? "" : s.trim(); }
}
