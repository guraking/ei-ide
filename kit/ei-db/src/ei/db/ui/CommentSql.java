package ei.db.ui;

import ei.db.DbKind;
import ei.db.console.SqlText;
import ei.db.gen.Names;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 표 · 칼럼 주석의 COMMENT ON 문 만들기와 엑셀에서 붙여 넣은 「이름<TAB>주석」 읽기. IntelliJ 클래스를 쓰지 않는다(CommentSqlCheck 로 점검).
 * 이름은 늘 스키마를 붙이고, 따옴표는 필요할 때만(대소문자가 접히지 않는 이름 · 보통 이름 꼴이 아닌 것 · 예약어) 붙인다.
 * 주석 글의 작은따옴표는 '' 로 쓰고 줄바꿈은 그대로 둔다. 빈 주석은 '' 로 써서 주석을 지운다(Oracle · PostgreSQL 모두).
 * MySQL 은 COMMENT ON 이 없어 다루지 않는다(부르는 쪽이 거절한다).
 */
final class CommentSql {
  private CommentSql() {}

  /** 주석을 달 대상의 종류. word 는 PostgreSQL 의 COMMENT ON 뒤 낱말. */
  enum ObjectKind {
    TABLE("TABLE"), FOREIGN_TABLE("FOREIGN TABLE"), VIEW("VIEW"), MATERIALIZED_VIEW("MATERIALIZED VIEW");

    final String word;

    ObjectKind(String word) { this.word = word; }

    /** JDBC getTables 의 TABLE_TYPE(TABLE · PARTITIONED TABLE · FOREIGN TABLE · VIEW · MATERIALIZED VIEW)에서. 모르는 값 · null 은 TABLE. */
    static ObjectKind ofTableType(String type) {
      for (ObjectKind k : new ObjectKind[] {MATERIALIZED_VIEW, FOREIGN_TABLE, VIEW}) {
        if (k.word.equalsIgnoreCase(type)) return k;
      }
      return TABLE;
    }
  }

  /** 고칠 주석 하나. column 이 null 이면 표 주석. */
  record Change(String column, String comment) {}

  /** 붙여 넣은 줄을 칼럼에 맞춘 결과: 표 주석(없으면 null), 칼럼 번호 → 주석, 맞는 칼럼이 없는 이름. */
  record Matched(String tableComment, Map<Integer, String> columns, List<String> unknown) {}

  private static final String STATEMENT_END = ";";

  /** 메타에서 읽은 이름을 SQL 표기로: 따옴표가 필요 없으면 프로젝트 표기(Names.sql, Oracle · PostgreSQL 대문자), 필요하면 저장된 그대로 따옴표. */
  static String name(DbKind kind, String name) {
    String quoted = SqlText.ident(kind, name);
    return quoted.equals(name) ? Names.sql(kind, name) : quoted;
  }

  /** 스키마.표 표기. */
  static String name(DbKind kind, String schema, String table) {
    return name(kind, schema) + "." + name(kind, table);
  }

  /** COMMENT ON 뒤 대상 낱말. Oracle 은 뷰도 TABLE 로 쓰고 구체화 뷰만 따로 쓴다. PostgreSQL 은 종류마다 낱말이 다르다. */
  static String word(DbKind kind, ObjectKind object) {
    if (kind == DbKind.ORACLE) return object == ObjectKind.MATERIALIZED_VIEW ? object.word : ObjectKind.TABLE.word;
    return object.word;
  }

  /** 표 주석 문(끝의 ; 없음). comment 가 null 이면 빈 주석으로 본다. */
  static String table(DbKind kind, ObjectKind object, String schema, String table, String comment) {
    return "COMMENT ON " + word(kind, object) + " " + name(kind, schema, table) + " IS " + SqlText.literal(nz(comment), kind);
  }

  /** 칼럼 주석 문(끝의 ; 없음). 뷰 · 구체화 뷰의 칼럼도 COLUMN 으로 쓴다. */
  static String column(DbKind kind, String schema, String table, String column, String comment) {
    return "COMMENT ON COLUMN " + name(kind, schema, table) + "." + name(kind, column) + " IS " + SqlText.literal(nz(comment), kind);
  }

  /** changes 순서대로 문장 하나씩(끝의 ; 없음). */
  static List<String> statements(DbKind kind, ObjectKind object, String schema, String table, List<Change> changes) {
    List<String> out = new ArrayList<>(changes.size());
    for (Change ch : changes) {
      out.add(ch.column() == null ? table(kind, object, schema, table, ch.comment()) : column(kind, schema, table, ch.column(), ch.comment()));
    }
    return out;
  }

  /** 콘솔 · 미리보기용 글: 문장마다 ; 로 끝내고 한 줄씩. 문장이 없으면 빈 글. */
  static String script(List<String> statements) {
    StringBuilder sb = new StringBuilder();
    for (String s : statements) sb.append(s).append(STATEMENT_END).append('\n');
    return sb.toString();
  }

  /**
   * 엑셀 · 메모장에서 복사한 줄들을 이름 → 주석으로(나온 순서). 칸은 탭으로 나뉘고, 큰따옴표로 감싼 칸은 탭 · 줄바꿈을 품으며 안의 "" 는 " 하나다.
   * 첫 칸이 이름, 둘째 칸이 주석이고 셋째 칸부터는 버린다. 이름 · 주석의 앞뒤 공백은 뗀다.
   * 탭이 없는 줄(주석 칸이 없음)과 이름이 빈 줄은 건너뛴다: 이름만 붙여 넣어 주석이 지워지지 않게.
   * 같은 이름이 또 나오면 뒤의 것을 쓴다. null · 빈 글이면 빈 맵.
   */
  static Map<String, String> parsePaste(String text) {
    Map<String, String> out = new LinkedHashMap<>();
    if (text == null) return out;
    for (List<String> row : rows(text.replace("\r\n", "\n").replace('\r', '\n'))) {
      if (row.size() < 2) continue;
      String name = row.get(0).strip();
      if (!name.isEmpty()) out.put(name, row.get(1).strip());
    }
    return out;
  }

  private static final char QUOTE = '"';
  private static final char TAB = '\t';
  private static final char LINE = '\n';

  /** 탭 구분 글을 줄 · 칸으로. 닫히지 않은 따옴표 칸은 글 끝까지를 그 칸으로 본다. */
  private static List<List<String>> rows(String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    int i = 0;
    int n = text.length();
    while (i < n) {
      char ch = text.charAt(i);
      if (ch == QUOTE && cell.isEmpty()) {
        i++;
        while (i < n) {
          char q = text.charAt(i++);
          if (q != QUOTE) {
            cell.append(q);
          } else if (i < n && text.charAt(i) == QUOTE) {
            cell.append(QUOTE);
            i++;
          } else {
            break;
          }
        }
        // 닫는 따옴표 뒤의 글은 다음 탭 · 줄바꿈까지 그 칸에 잇는다.
        continue;
      }
      if (ch == TAB || ch == LINE) {
        row.add(cell.toString());
        cell.setLength(0);
        if (ch == LINE) {
          rows.add(row);
          row = new ArrayList<>();
        }
      } else {
        cell.append(ch);
      }
      i++;
    }
    row.add(cell.toString());
    rows.add(row);
    return rows;
  }

  /**
   * 붙여 넣은 이름을 칼럼에 맞춘다. 대소문자는 가리지 않는다. 칼럼에 없는 이름이 표 이름과 같으면 표 주석으로,
   * 그 밖에는 unknown 에 넣는다(붙여 넣은 순서).
   */
  static Matched match(String table, List<String> columns, Map<String, String> pasted) {
    String tableComment = null;
    Map<Integer, String> byColumn = new LinkedHashMap<>();
    List<String> unknown = new ArrayList<>();
    for (Map.Entry<String, String> e : pasted.entrySet()) {
      int at = indexOf(columns, e.getKey());
      if (at >= 0) byColumn.put(at, e.getValue());
      else if (e.getKey().equalsIgnoreCase(table)) tableComment = e.getValue();
      else unknown.add(e.getKey());
    }
    return new Matched(tableComment, byColumn, unknown);
  }

  private static int indexOf(List<String> columns, String name) {
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).equalsIgnoreCase(name)) return i;
    }
    return -1;
  }

  private static String nz(String s) { return s == null ? "" : s; }
}
