package ei.db.ui;

import ei.db.Meta;

import java.util.List;

/**
 * 탐색기 「SELECT 문 만들기」의 본문. 칼럼마다 한 줄이고, 주석이 있으면 줄 끝에 `-- 주석` 을 같은 열에 맞춰 붙인다.
 * 조건을 바로 이어 쓰도록 `WHERE 1 = 1` 로 끝내고 문장 끝 ; 은 붙이지 않는다.
 */
final class SelectSql {
  private SelectSql() {}

  private static final String ALIAS = "t";
  private static final String FIRST = "SELECT ";
  private static final String NEXT = "       ";

  static String build(String schema, String table, List<Meta.Column> columns) {
    StringBuilder sb = new StringBuilder();
    if (columns.isEmpty()) {
      sb.append(FIRST).append(ALIAS).append(".*\n");
    } else {
      int width = 0;
      for (Meta.Column col : columns) width = Math.max(width, ALIAS.length() + 1 + col.name().length() + 1);
      for (int i = 0; i < columns.size(); i++) {
        Meta.Column col = columns.get(i);
        String item = ALIAS + "." + col.name() + (i < columns.size() - 1 ? "," : "");
        String comment = col.comment().replaceAll("\\s+", " ").trim();
        String line = (i == 0 ? FIRST : NEXT) + (comment.isEmpty() ? item : pad(item, width) + "  -- " + comment);
        sb.append(line).append('\n');
      }
    }
    sb.append("  FROM ").append(schema).append('.').append(table).append(' ').append(ALIAS).append('\n');
    sb.append(" WHERE 1 = 1");
    return sb.toString();
  }

  private static String pad(String s, int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }
}
