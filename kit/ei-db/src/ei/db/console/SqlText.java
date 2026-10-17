package ei.db.console;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 결과 행을 SQL 글로 바꾸는 순수 함수들(INSERT · UPDATE 복사). */
final class SqlText {
  private SqlText() {}

  /** ANSI 표기(TIMESTAMP · DATE 글자)라 Oracle · MySQL · PostgreSQL 에서 같이 쓴다. */
  static String literal(Object v) {
    if (v == null) return "NULL";
    if (v instanceof Number) return text(v);
    if (v instanceof Timestamp t) return "TIMESTAMP '" + t + "'";
    if (v instanceof java.sql.Date d) return "DATE '" + d + "'";
    return "'" + text(v).replace("'", "''") + "'";
  }

  static String text(Object v) {
    return v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v);
  }

  /**
   * 행마다 UPDATE 문 하나. SET 은 PK 가 아닌 칼럼, WHERE 는 PK 칼럼(null 이면 IS NULL).
   * pkUpper(대문자 칼럼 이름)가 결과 칼럼과 하나도 겹치지 않으면 첫 칼럼으로 조건을 걸고 위에 확인하라는 주석을 단다.
   */
  static String updates(String table, List<String> columns, List<List<Object>> rows, Set<String> pkUpper) {
    boolean[] key = new boolean[columns.size()];
    boolean anyKey = false;
    for (int i = 0; i < columns.size(); i++) {
      key[i] = pkUpper.contains(columns.get(i).toUpperCase(Locale.ROOT));
      anyKey |= key[i];
    }
    StringBuilder sb = new StringBuilder();
    if (!anyKey) {
      if (!columns.isEmpty()) key[0] = true;
      sb.append("-- PK 를 찾지 못해 첫 칼럼(").append(columns.isEmpty() ? "" : columns.get(0)).append(")으로 조건을 걸었습니다. 실행 전에 WHERE 를 확인하세요.\n");
    }
    for (List<Object> row : rows) {
      StringBuilder set = new StringBuilder();
      StringBuilder where = new StringBuilder();
      for (int i = 0; i < columns.size(); i++) {
        Object v = row.get(i);
        if (key[i]) {
          if (where.length() > 0) where.append(" AND ");
          where.append(columns.get(i)).append(v == null ? " IS NULL" : " = " + literal(v));
        } else {
          if (set.length() > 0) set.append(", ");
          set.append(columns.get(i)).append(" = ").append(literal(v));
        }
      }
      if (set.length() == 0) continue; // PK 만 고른 결과는 바꿀 칼럼이 없다.
      sb.append("UPDATE ").append(table).append(" SET ").append(set).append(" WHERE ").append(where).append(";\n");
    }
    return sb.toString();
  }
}
