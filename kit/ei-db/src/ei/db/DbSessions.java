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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 프로젝트마다 접속 id 하나에 JDBC 접속 하나를 열어 둔다. 자동 커밋이 꺼진 접속은 변경 문장 수를 세어 두고
 * 커밋 · 롤백 때 0 으로 돌린다. 실행 중인 문장을 접속 id 별로 기억해 cancel 로 멈출 수 있다.
 * 상태(접속 · 미반영 변경 · 실행 중)가 바뀌면 리스너를 부른다(탐색기 점 색, 콘솔 배지 · 중지 단추, 상태줄).
 * 메서드는 화면 스레드가 아닌 곳에서 부른다(접속 · 실행이 막힐 수 있다).
 */
@Service(Service.Level.PROJECT)
public final class DbSessions implements Disposable {

  /** 조회 건수 기본값이자 「더 보기」 한 번에 더 읽는 건수. */
  public static final int MAX_ROWS = 500;
  /** 조회 건수 0 은 끝까지 읽는다. */
  public static final int ALL_ROWS = 0;
  /** 직접 쓰는 조회 건수의 상한. 읽을 때 limit + 1 을 쓰므로 int 끝값을 받으면 넘친다. */
  public static final int MAX_ROW_LIMIT = 1_000_000;

  /** 쉼표 · 「건」 을 허용해 읽은 건수. 1 ~ MAX_ROW_LIMIT 가 아니거나 숫자가 아니면 0. */
  public static int parseRowCount(String text) {
    String t = text == null ? "" : text.replace(",", "").replace("건", "").strip();
    try {
      int n = Integer.parseInt(t);
      return n >= 1 && n <= MAX_ROW_LIMIT ? n : 0;
    } catch (NumberFormatException e) {
      return 0; // 숫자가 아니면 호출부가 알림을 띄운다.
    }
  }
  private static final int CLOB_LIMIT = 4000;
  private static final Pattern IDENT = Pattern.compile("[A-Za-z_][\\w$#]*");

  private final Map<String, Connection> open = new ConcurrentHashMap<>();
  private final Map<String, Integer> pending = new ConcurrentHashMap<>();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  /** 접속 id → 지금 실행 중인 문장. 같은 접속에서 겹쳐 돌면 나중 것만 남는다. */
  private final Map<String, Statement> running = new ConcurrentHashMap<>();
  /** cancel 을 보낸 문장. 그 문장의 실패를 CancelledException 으로 바꾸는 데 쓴다. */
  private final Set<Statement> cancelled = ConcurrentHashMap.newKeySet();
  /** 이 프로젝트의 조회 건수(콘솔 도구줄에서 고른다). 저장하지 않으며 IDE 를 다시 열면 MAX_ROWS. */
  private volatile int maxRows = MAX_ROWS;

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

  public boolean isRunning(String id) { return running.containsKey(id); }

  /** cancel 로 멈춘 실행. 실행하던 쪽(run)이 받는다. */
  public static final class CancelledException extends SQLException {
    CancelledException(SQLException cause) {
      super("취소했습니다", cause);
    }
  }

  /** 그 접속에서 실행 중인 문장에 Statement.cancel() 을 보낸다. 실행 중이 아니면 아무것도 하지 않는다. 보냈으면 참. */
  public boolean cancel(String id) throws SQLException {
    Statement st = running.get(id);
    if (st == null) return false;
    cancelled.add(st);
    st.cancel();
    return true;
  }

  public int maxRows() { return maxRows; }

  /** 음수는 받지 않는다. 바꾸면 리스너를 불러 다른 콘솔 도구줄도 맞춘다. */
  public void setMaxRows(int rows) {
    if (rows < 0) throw new IllegalArgumentException("조회 건수는 0(전부) 이상이어야 합니다: " + rows);
    maxRows = rows;
    fire();
  }

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
   * SELECT 는 이 프로젝트의 조회 건수까지만 읽는다.
   */
  public QueryResult run(DbConfig c, String sql, List<Object> binds) throws SQLException {
    return run(c, sql, binds, maxRows);
  }

  /**
   * run 과 같되, 문장을 등록한 뒤 실행 직전에 stop 을 본다. 참이면 실행하지 않고 CancelledException.
   * 접속을 여는 중에 들어온 취소(cancel 할 문장이 아직 없음)를 놓치지 않으려는 것이다.
   */
  public QueryResult run(DbConfig c, String sql, List<Object> binds, BooleanSupplier stop) throws SQLException {
    return run(c, sql, binds, maxRows, stop);
  }

  /** limit 건까지 읽는다(ALL_ROWS 면 끝까지). 「더 보기」가 건수를 늘려 다시 부른다. */
  public QueryResult run(DbConfig c, String sql, List<Object> binds, int limit) throws SQLException {
    return run(c, sql, binds, limit, () -> false);
  }

  private QueryResult run(DbConfig c, String sql, List<Object> binds, int limit, BooleanSupplier stop) throws SQLException {
    Connection conn = connection(c);
    String text = sql.strip();
    if (text.endsWith(";") && !text.toUpperCase().endsWith("END;")) text = text.substring(0, text.length() - 1).strip();
    long start = System.currentTimeMillis();
    PreparedStatement ps = conn.prepareStatement(text);
    running.put(c.id, ps);
    fire();
    try (ps) {
      if (stop.getAsBoolean()) {
        cancelled.add(ps);
        throw new SQLException("실행 전에 취소했습니다");
      }
      // limit + 1 건을 받아 보고 넘치면 잘렸다고 알린다.
      if (limit != ALL_ROWS) ps.setMaxRows(limit + 1);
      for (int i = 0; binds != null && i < binds.size(); i++) ps.setObject(i + 1, binds.get(i));
      boolean isQuery = ps.execute();
      if (!isQuery) {
        int count = ps.getUpdateCount();
        if (!c.autoCommit && SqlGuard.isWrite(text)) {
          pending.merge(c.id, 1, Integer::sum);
          fire();
        }
        return new QueryResult(text, List.of(), List.of(), List.of(), count, System.currentTimeMillis() - start, false, Map.of(), bindList(binds));
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
          if (limit != ALL_ROWS && rows.size() == limit) { truncated = true; break; }
          List<Object> row = new ArrayList<>(cols.size());
          for (int i = 1; i <= cols.size(); i++) row.add(readValue(rs.getObject(i)));
          rows.add(row);
        }
        long millis = System.currentTimeMillis() - start;
        return new QueryResult(text, cols, types, rows, -1, millis, truncated, Comments.forQuery(conn, c, text, cols), bindList(binds));
      }
    } catch (SQLException e) {
      if (cancelled.contains(ps)) throw new CancelledException(e);
      throw e;
    } finally {
      running.remove(c.id, ps);
      cancelled.remove(ps);
      fire();
    }
  }

  private static List<Object> bindList(List<Object> binds) {
    return binds == null ? List.of() : new ArrayList<>(binds);
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
