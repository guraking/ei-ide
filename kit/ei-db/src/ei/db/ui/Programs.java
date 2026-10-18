package ei.db.ui;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Oracle 프로그램 목록 · 소스를 JDBC 로 읽는다. 화면 스레드에서 부르지 않는다. */
final class Programs {
  private Programs() {}

  static List<ProgramSql.Item> list(Connection conn, String owner) throws SQLException {
    List<String[]> rows = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(ProgramSql.LIST)) {
      ps.setString(1, owner);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
      }
    }
    return ProgramSql.merge(rows);
  }

  /** CREATE OR REPLACE 문. 소스를 읽을 권한이 없거나 없어진 프로그램이면 빈 문자열. */
  static String source(Connection conn, String owner, ProgramSql.Item item) throws SQLException {
    List<String[]> rows = new ArrayList<>();
    List<Object> types = ProgramSql.sourceTypes(item.type());
    try (PreparedStatement ps = conn.prepareStatement(ProgramSql.SOURCE)) {
      ps.setString(1, owner);
      ps.setString(2, item.name());
      ps.setString(3, (String) types.get(0));
      ps.setString(4, (String) types.get(1));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) rows.add(new String[] {rs.getString(1), rs.getString(2)});
      }
    }
    return ProgramSql.ddl(owner, rows);
  }
}
