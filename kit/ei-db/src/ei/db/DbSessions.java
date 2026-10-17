package ei.db;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 프로젝트마다 접속 id 하나에 JDBC 접속 하나를 열어 둔다. 자동 커밋이 꺼진 접속은 변경 문장 수를 세어 두고
 * 커밋 · 롤백 때 0 으로 돌린다. 상태가 바뀌면 리스너를 부른다(탐색기 점 색, 콘솔 배지, 상태줄).
 * 메서드는 화면 스레드가 아닌 곳에서 부른다(접속 · 실행이 막힐 수 있다).
 */
@Service(Service.Level.PROJECT)
public final class DbSessions implements Disposable {

  public static final int MAX_ROWS = 500;
  private static final int CLOB_LIMIT = 4000;
  private static final Pattern IDENT = Pattern.compile("[A-Za-z_][\\w$#]*");

  private final Map<String, Connection> open = new ConcurrentHashMap<>();
  private final Map<String, Integer> pending = new ConcurrentHashMap<>();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  public static DbSessions get(Project project) {
    return project.getService(DbSessions.class);
  }

  public void addListener(Disposable parent, Runnable listener) {
    listeners.add(listener);
    Disposer.register(parent, () -> listeners.remove(listener));
  }

  private void fire() {
    for (Runnable r : listeners) r.run();
  }

  public boolean isOpen(String id) { return open.containsKey(id); }

  public int pending(String id) { return pending.getOrDefault(id, 0); }

  public Connection connection(DbConfig c) throws SQLException {
    Connection conn = open.get(c.id);
    if (conn != null) {
      if (conn.isValid(3)) return conn;
      close(c.id);
    }
    String password = Passwords.get(c);
    if (password == null) throw new SQLException("저장된 비밀번호가 없습니다. 접속 설정에서 비밀번호를 넣으세요.");
    Properties props = new Properties();
    props.setProperty("user", c.user);
    props.setProperty("password", password);
    if (c.kind == DbKind.ORACLE) props.setProperty("remarksReporting", "true");
    try {
      conn = Drivers.load(c).connect(c.url(), props);
    } catch (ReflectiveOperationException | java.io.IOException e) {
      throw new SQLException(e.getMessage(), e);
    }
    if (conn == null) throw new SQLException("드라이버가 주소를 받지 않았습니다: " + c.url());
    conn.setAutoCommit(c.autoCommit);
    conn.setReadOnly(c.readOnly);
    String schema = c.schema == null ? "" : c.schema.trim();
    if (!schema.isEmpty()) {
      if (!IDENT.matcher(schema).matches()) throw new SQLException("스키마 이름이 올바르지 않습니다: " + schema);
      switch (c.kind) {
        case ORACLE -> { try (Statement st = conn.createStatement()) { st.execute("ALTER SESSION SET CURRENT_SCHEMA = " + schema); } }
        case MYSQL -> conn.setCatalog(schema);
        case POSTGRES -> conn.setSchema(schema);
      }
    }
    open.put(c.id, conn);
    pending.put(c.id, 0);
    fire();
    return conn;
  }

  /** 접속 시험용: 열어 보고 DB 이름 · 판을 돌려준 뒤 닫는다. */
  public static String test(DbConfig c, String password) throws Exception {
    Properties props = new Properties();
    props.setProperty("user", c.user);
    props.setProperty("password", password == null ? "" : password);
    try (Connection conn = Drivers.load(c).connect(c.url(), props)) {
      if (conn == null) throw new SQLException("드라이버가 주소를 받지 않았습니다: " + c.url());
      return conn.getMetaData().getDatabaseProductName() + " " + conn.getMetaData().getDatabaseMajorVersion();
    }
  }

  /**
   * 한 문장을 실행한다. binds 는 ? 자리 값(순서대로). 끝의 ; 는 떼되 PL/SQL 블록(END;)은 그대로 둔다.
   * SELECT 는 MAX_ROWS 까지만 읽는다.
   */
  public QueryResult run(DbConfig c, String sql, List<Object> binds) throws SQLException {
    Connection conn = connection(c);
    String text = sql.strip();
    if (text.endsWith(";") && !text.toUpperCase().endsWith("END;")) text = text.substring(0, text.length() - 1).strip();
    long start = System.currentTimeMillis();
    try (PreparedStatement ps = conn.prepareStatement(text)) {
      ps.setMaxRows(MAX_ROWS + 1);
      for (int i = 0; binds != null && i < binds.size(); i++) ps.setObject(i + 1, binds.get(i));
      boolean isQuery = ps.execute();
      if (!isQuery) {
        int count = ps.getUpdateCount();
        if (!c.autoCommit && SqlGuard.isWrite(text)) {
          pending.merge(c.id, 1, Integer::sum);
          fire();
        }
        return new QueryResult(text, List.of(), List.of(), List.of(), count, System.currentTimeMillis() - start, false, Map.of());
      }
      try (ResultSet rs = ps.getResultSet()) {
        ResultSetMetaData md = rs.getMetaData();
        List<String> cols = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
          cols.add(md.getColumnLabel(i));
          types.add(md.getColumnTypeName(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
          if (rows.size() == MAX_ROWS) { truncated = true; break; }
          List<Object> row = new ArrayList<>(cols.size());
          for (int i = 1; i <= cols.size(); i++) row.add(readValue(rs.getObject(i)));
          rows.add(row);
        }
        long millis = System.currentTimeMillis() - start;
        return new QueryResult(text, cols, types, rows, -1, millis, truncated, Comments.forQuery(conn, c, text, cols));
      }
    }
  }

  private static Object readValue(Object v) throws SQLException {
    if (v instanceof Clob clob) {
      long len = clob.length();
      return clob.getSubString(1, (int) Math.min(len, CLOB_LIMIT)) + (len > CLOB_LIMIT ? "…" : "");
    }
    if (v instanceof Blob blob) return "(BLOB " + blob.length() + " 바이트)";
    return v;
  }

  public void commit(String id) throws SQLException {
    Connection conn = open.get(id);
    if (conn != null) conn.commit();
    pending.put(id, 0);
    fire();
  }

  public void rollback(String id) throws SQLException {
    Connection conn = open.get(id);
    if (conn != null) conn.rollback();
    pending.put(id, 0);
    fire();
  }

  /** 미반영 변경은 롤백된다(드라이버 기본 동작). 부르는 쪽이 먼저 확인받는다. */
  public void close(String id) {
    Connection conn = open.remove(id);
    pending.remove(id);
    Comments.clear(id);
    if (conn != null) {
      try { conn.close(); } catch (SQLException ignored) { /* 이미 끊긴 접속 */ }
    }
    fire();
  }

  @Override
  public void dispose() {
    for (String id : new ArrayList<>(open.keySet())) close(id);
  }
}
