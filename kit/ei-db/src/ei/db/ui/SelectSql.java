package ei.db.ui;

import ei.db.Meta;

import java.util.ArrayList;
import java.util.List;

/**
 * 탐색기 「SELECT · INSERT · UPDATE 문 만들기」의 본문. 칼럼마다 한 줄이고, 주석이 있으면 줄 끝에 `-- 주석` 을 같은 열에 맞춰 붙인다.
 * 값 자리는 NULL 로 두고, 조건을 바로 이어 쓰도록 문장 끝 ; 은 붙이지 않는다.
 */
final class SelectSql {
  private SelectSql() {}

  private static final String ALIAS = "t";
  private static final String FIRST = "SELECT ";
  private static final String NEXT = "       ";
  private static final String VALUE = "NULL";

  static String build(String schema, String table, List<Meta.Column> columns) {
    StringBuilder sb = new StringBuilder();
    if (columns.isEmpty()) {
      sb.append(FIRST).append(ALIAS).append(".*\n");
    } else {
      List<String> items = new ArrayList<>();
      List<String> comments = new ArrayList<>();
      for (Meta.Column col : columns) {
        items.add(ALIAS + "." + col.name());
        comments.add(col.comment());
      }
      lines(sb, FIRST, NEXT, items, ",", comments);
    }
    sb.append("  FROM ").append(schema).append('.').append(table).append(' ').append(ALIAS).append('\n');
    sb.append(" WHERE 1 = 1");
    return sb.toString();
  }

  /** 모든 칼럼에 NULL 을 넣는 INSERT. 값 줄 끝에는 칼럼 이름 · 형식을 달아 어느 값인지 보이게 한다. */
  static String insert(String schema, String table, List<Meta.Column> columns) {
    List<String> names = new ArrayList<>();
    List<String> comments = new ArrayList<>();
    List<String> values = new ArrayList<>();
    List<String> valueNotes = new ArrayList<>();
    for (Meta.Column col : columns) {
      names.add(col.name());
      comments.add(col.comment());
      values.add(VALUE);
      valueNotes.add(col.name() + " " + col.typeLabel());
    }
    StringBuilder sb = new StringBuilder("INSERT INTO ").append(schema).append('.').append(table).append(" (\n");
    lines(sb, NEXT, NEXT, names, ",", comments);
    sb.append(") VALUES (\n");
    lines(sb, NEXT, NEXT, values, ",", valueNotes);
    sb.append(")");
    return sb.toString();
  }

  /** PK 가 아닌 칼럼은 SET, PK 칼럼은 WHERE 조건으로. PK 가 없으면 조건을 직접 쓰라는 줄을 남긴다. */
  static String update(String schema, String table, List<Meta.Column> columns) {
    List<String> sets = new ArrayList<>();
    List<String> setNotes = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    List<String> keyNotes = new ArrayList<>();
    for (Meta.Column col : columns) {
      String item = ALIAS + "." + col.name() + " = " + VALUE;
      if (col.pk()) { keys.add(item); keyNotes.add(col.comment()); }
      else { sets.add(item); setNotes.add(col.comment()); }
    }
    StringBuilder sb = new StringBuilder("UPDATE ").append(schema).append('.').append(table).append(' ').append(ALIAS).append('\n');
    if (sets.isEmpty()) sb.append("   SET -- 바꿀 칼럼이 없습니다(모두 PK)\n");
    else lines(sb, "   SET ", NEXT, sets, ",", setNotes);
    sb.append(" WHERE 1 = 1");
    if (keys.isEmpty()) {
      sb.append("\n   -- PK 가 없습니다. 조건을 직접 쓰세요");
    } else {
      sb.append('\n');
      lines(sb, "   AND ", "   AND ", keys, "", keyNotes);
      sb.setLength(sb.length() - 1);
    }
    return sb.toString();
  }

  /** 첫 줄은 first, 다음 줄은 next 로 시작하고 마지막 줄을 빼고 separator 를 붙인다. 주석은 같은 열에 맞춘다(줄바꿈은 공백으로). */
  private static void lines(StringBuilder sb, String first, String next, List<String> items, String separator, List<String> comments) {
    int width = 0;
    for (String item : items) width = Math.max(width, item.length() + separator.length());
    for (int i = 0; i < items.size(); i++) {
      String item = items.get(i) + (i < items.size() - 1 ? separator : "");
      String comment = comments.get(i).replaceAll("\\s+", " ").trim();
      sb.append(i == 0 ? first : next).append(comment.isEmpty() ? item : pad(item, width) + "  -- " + comment).append('\n');
    }
  }

  private static String pad(String s, int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }
}
