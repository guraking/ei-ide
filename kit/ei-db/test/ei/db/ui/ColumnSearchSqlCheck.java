package ei.db.ui;

import ei.db.DbKind;

/** 표 · 칼럼 찾기 쿼리 자체 점검: java -cp out:test-out ei.db.ui.ColumnSearchSqlCheck */
public class ColumnSearchSqlCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    eq(ColumnSearchSql.like(" user_id "), "%USER!_ID%");
    eq(ColumnSearchSql.like("100%!"), "%100!%!!%");
    eq(ColumnSearchSql.like("주문"), "%주문%");
    for (DbKind k : DbKind.values()) {
      String sql = ColumnSearchSql.sql(k);
      eq(sql.chars().filter(ch -> ch == '?').count(), 5L);
      if (sql.contains("||") || !sql.contains("ESCAPE '!'")) throw new AssertionError(sql);
    }
    System.out.println("ColumnSearchSqlCheck 통과");
  }
}
