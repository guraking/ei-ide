package ei.db.ui;

import ei.db.DbConfig;
import ei.db.Meta;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
  /** 이 형 이름으로 끝나는 TABLE_TYPE(VIEW · MATERIALIZED VIEW)은 「뷰」. 정의서에 싣는 형은 Meta.tableAndViewTypes. */
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

  /** getTables 의 TABLE_TYPE 이 VIEW · MATERIALIZED VIEW 면 「뷰」, 나머지(TABLE · PARTITIONED TABLE · FOREIGN TABLE)는 「표」. */
  static String kind(Meta.Table t) {
    return t.type() != null && t.type().toUpperCase(java.util.Locale.ROOT).endsWith(VIEW_TYPE) ? KIND_VIEW : KIND_TABLE;
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

  /** 표 하나의 칼럼 · PK · 기본값(COLUMN_DEF, 앞뒤 공백 제거)을 읽는다. 정의서에 쓰지 않는 외래키는 읽지 않는다(fk 는 늘 false). */
  static Table read(Connection conn, DbConfig c, String schema, Meta.Table table) throws SQLException {
    return of(table, columnsByTable(conn, c, schema, List.of(table)).getOrDefault(table.name(), List.of()), Meta.primaryKeys(conn, c, schema, table.name()));
  }

  /**
   * 표 이름 → 칼럼 줄. 표가 하나면 그 표만, 여럿이면 스키마의 칼럼을 한 번에 읽는다(표마다 따로 읽으면 표 1천 개에 조회 1천 번).
   * 이름 맞춤 · 스키마 거르기 · 기본값 읽기는 Meta.columnRows 가 한다.
   */
  static Map<String, List<Meta.ColumnRow>> columnsByTable(Connection conn, DbConfig c, String schema, List<Meta.Table> tables) throws SQLException {
    String only = tables.size() == 1 ? tables.get(0).name() : null;
    Map<String, List<Meta.ColumnRow>> out = new HashMap<>();
    for (Meta.ColumnRow r : Meta.columnRows(conn, c, schema, only, true)) {
      out.computeIfAbsent(r.table(), k -> new ArrayList<>()).add(r);
    }
    return out;
  }

  /** 읽은 칼럼 줄과 PK 로 정의서 표 하나를 만든다. */
  static Table of(Meta.Table table, List<Meta.ColumnRow> rows, Set<String> pks) {
    List<Meta.Column> columns = new ArrayList<>(rows.size());
    Map<String, String> defaults = new HashMap<>();
    for (Meta.ColumnRow r : rows) {
      Meta.Column col = r.column();
      columns.add(new Meta.Column(col.name(), col.type(), col.size(), col.scale(), pks.contains(col.name()), false, col.nullable(), col.comment()));
      if (r.def() != null) defaults.put(col.name(), r.def());
    }
    return new Table(table, columns, defaults);
  }

}
