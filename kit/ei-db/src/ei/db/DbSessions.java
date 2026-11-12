package ei.db;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/**
 * 프로젝트마다 접속 id 하나에 JDBC 접속 하나를 열어 둔다. 자동 커밋이 꺼진 접속은 변경 문장 수를 세어 두고
 * 커밋 · 롤백 때 0 으로 돌린다. 실행 중인 문장을 접속 id 별로 기억해 cancel 로 멈출 수 있다.
 * 상태(접속 · 미반영 변경 · 실행 중)가 바뀌면 리스너를 부른다(탐색기 점 색, 콘솔 배지 · 중지 단추, 상태줄).
 * 메서드는 화면 스레드가 아닌 곳에서 부른다(접속 · 실행이 막힐 수 있다).
 * 닫을 때(끊기 · 프로젝트 닫힘 · 접속 확인 실패) 자동 커밋이 꺼져 있으면 먼저 롤백한다: Oracle 은 close() 때 미반영 변경을 커밋한다.
 * 실행 중인 접속을 닫거나, 취소를 보낸 뒤 ABORT_AFTER_CANCEL_SEC 안에 실행이 끝나지 않으면(망이 끊겨 취소가 닿지 않음) 미반영 변경이 없을 때 접속을 끊는다(abort):
 * 막힌 실행이 풀리고 다음 실행은 새로 접속한다. 미반영 변경이 있으면 알리기만 하고 「끊기」(close)를 기다린다.
 * PostgreSQL 은 자동 커밋이 꺼져 있어도 아무것도 바꾸지 않은 트랜잭션은 조회 뒤 롤백해 닫는다(「idle in transaction」 으로 남아 남의 ALTER · VACUUM 을 막지 않게).
 */
@Service(Service.Level.PROJECT)
public final class DbSessions implements Disposable {

  /** 조회 건수 기본값이자 「더 보기」 한 번에 더 읽는 건수. */
  public static final int MAX_ROWS = 500;
  /** 조회 건수 0 은 끝까지 읽는다. */
  public static final int ALL_ROWS = 0;
  /** 직접 쓰는 조회 건수 · 「더 보기」의 상한. 읽은 행은 모두 메모리에 두므로 넓은 표에서도 IDE 가 멈추지 않을 만큼으로 둔다. */
  public static final int MAX_ROW_LIMIT = 100_000;
  /** 한 번에 받아 오는 행 수(드라이버 기본은 Oracle 10 행이라 500 행이면 망을 50 번 오간다). limit + 1 이 더 작으면 그만큼만. */
  static final int FETCH_SIZE = 500;
  /** 읽은 행 수를 진행 표시에 알리는 간격(행). */
  private static final int PROGRESS_EVERY_ROWS = 1000;
  /** 취소를 보낸 뒤 실행이 끝나기를 기다리는 초. 넘으면 접속을 끊는다. */
  static final int ABORT_AFTER_CANCEL_SEC = 10;
  /** PostgreSQL: 지금 트랜잭션이 무언가 바꿨으면(트랜잭션 번호를 받았으면) 번호, 아니면 null(PostgreSQL 10 부터). */
  private static final String PG_TX_WROTE = "SELECT txid_current_if_assigned()";
  /** 조회 뒤 트랜잭션을 닫아도 되는 문장 머리. SET · LOCK 등은 트랜잭션에 걸린 효과가 롤백으로 사라지므로 뺀다. */
  private static final Pattern READ_HEAD = Pattern.compile("^(?:SELECT|WITH|EXPLAIN|SHOW|VALUES)\\b");
  private static final Logger LOG = Logger.getInstance(DbSessions.class);

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
  /** 열린 접속이 살아 있는지 기다리는 초. */
  private static final int VALID_TIMEOUT_SEC = 3;
  /** 이름으로만 판별하는 Oracle 값 클래스(드라이버를 직접 참조하지 않는다). */
  private static final String ORACLE_SQL = "oracle.sql.";
  /** 접속을 맺을 때 기다리는 초. 서버가 응답하지 않을 때 무한정 막히지 않게 한다. */
  private static final int CONNECT_TIMEOUT_SEC = 10;
  private static final int MILLIS_PER_SEC = 1000;

  private final Project project;
  private final Map<String, Connection> open = new ConcurrentHashMap<>();
  /** 접속 id 별 잠금. 같은 접속을 동시에 두 번 열거나, 여는 중에 닫지 않게 한다. */
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  /** 접속 id → 콘솔에서 바꾼 자동 커밋. 없으면 접속 설정 값. 저장하지 않는다(세션 동안). */
  private final Map<String, Boolean> autoCommitOverrides = new ConcurrentHashMap<>();
  private final Map<String, Integer> pending = new ConcurrentHashMap<>();
  /**
   * 커밋 · 롤백 뒤 SELECT · WITH 가 아닌 문장(SET · LOCK · 변경 등)을 실행한 접속 id. 이 트랜잭션에는 롤백하면 사라질 효과가 있을 수 있어
   * endIdleTransaction 이 닫지 않는다. 커밋 · 롤백 · 새로 열기 · 자동 커밋 켜기에서 뺀다.
   */
  private final Set<String> txTouched = ConcurrentHashMap.newKeySet();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  /** 접속 id → 지금 실행 중인 문장. 같은 접속에서 겹쳐 돌면 나중 것만 남는다. */
  private final Map<String, Statement> running = new ConcurrentHashMap<>();
  /** cancel 을 보낸 문장. 그 문장의 실패를 CancelledException 으로 바꾸는 데 쓴다. */
  private final Set<Statement> cancelled = ConcurrentHashMap.newKeySet();
  /** 이 프로젝트의 조회 건수(콘솔 도구줄에서 고른다). 저장하지 않으며 IDE 를 다시 열면 MAX_ROWS. */
  private volatile int maxRows = MAX_ROWS;

  public DbSessions(Project project) {
    this.project = project;
  }

  public static DbSessions get(Project project) {
    return project.getService(DbSessions.class);
  }

  public void addListener(Disposable parent, Runnable listener) {
    listeners.add(listener);
    Disposer.register(parent, () -> listeners.remove(listener));
  }

  /** 접속 id 별 자동 커밋 덮어쓰기(콘솔에서 바꾼 값). null 이면 접속 설정 값. */
  public Boolean autoCommitOverride(String id) {
    return autoCommitOverrides.get(id);
  }

  /** 이 접속에 실제로 쓰는 자동 커밋: 덮어쓰기, 없으면 접속 설정 값. */
  public boolean autoCommit(DbConfig c) {
    Boolean on = autoCommitOverrides.get(c.id);
    return on != null ? on : c.autoCommit;
  }

  /**
   * 접속 id 별 자동 커밋을 바꾸고(null 은 접속 설정 값으로 되돌림), 열린 접속에도 건다. 같은 접속의 모든 콘솔 · 탐색기가 이 값을 쓴다.
   * 켜는데 미반영 변경이 있으면 먼저 커밋한다(확인은 호출부가 받는다). 걸지 못하면 앞 값으로 되돌리고 알린다.
   */
  public void setAutoCommitOverride(String id, Boolean on) {
    synchronized (lock(id)) {
      Boolean before = autoCommitOverrides.get(id);
      putOverride(id, on);
      Connection conn = open.get(id);
      DbConfig c = DbSettings.getInstance().find(id);
      if (conn != null && c != null) {
        try {
          applyAutoCommit(id, conn, autoCommit(c));
        } catch (SQLException e) {
          putOverride(id, before);
          Notify.error(project, EiDbBundle.message("sessions.autocommit.failed", c.name, e.getMessage()));
        }
      }
    }
    notifyChanged();
  }

  private void putOverride(String id, Boolean on) {
    if (on == null) autoCommitOverrides.remove(id); else autoCommitOverrides.put(id, on);
  }

  private void applyAutoCommit(String id, Connection conn, boolean on) throws SQLException {
    if (conn.getAutoCommit() == on) return;
    if (on && pending(id) > 0) conn.commit();
    conn.setAutoCommit(on);
    if (on) {
      pending.put(id, 0);
      txTouched.remove(id);
    }
  }

  private Object lock(String id) {
    return locks.computeIfAbsent(id, k -> new Object());
  }

  /** 상태가 바뀌었음을 리스너에게 알린다(콘솔 도구줄 · 탐색기 갱신). */
  public void notifyChanged() {
    for (Runnable r : listeners) r.run();
  }

  public boolean isOpen(String id) { return open.containsKey(id); }

  /** 이미 열린 접속. 없으면 null. 새로 열지도, 살아 있는지 묻지도 않는다(자동완성처럼 로그인 · 대기를 일으키면 안 되는 곳에서 쓴다). */
  public Connection openConnection(String id) { return open.get(id); }

  /**
   * 스키마 이름(Meta.schemaOf)을 쓰기 전에 부른다. 기본 스키마를 비운 PostgreSQL 은 접속해야 현재 스키마를 알므로,
   * 아직 모르고 열지 않았으면 작업 스레드에서 열고 then 을 화면 스레드에서 부른다(열린 대화 상자 위에서도 돈다).
   * 열기에 실패하면 알리고 then 은 부르지 않는다. 그 밖에는 바로 부른다. 화면 스레드에서 부른다.
   */
  public void whenSchemaKnown(DbConfig c, Runnable then) {
    if (!Meta.schemaUnknown(c) || isOpen(c.id)) {
      then.run();
      return;
    }
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      try {
        connection(c);
      } catch (SQLException | RuntimeException e) {
        Notify.error(project, EiDbBundle.message("sessions.connect.failed", c.name, e.getMessage()));
        return;
      }
      ApplicationManager.getApplication().invokeLater(then, ModalityState.any(), project.getDisposed());
    });
  }

  public int pending(String id) { return pending.getOrDefault(id, 0); }

  public boolean isRunning(String id) { return running.containsKey(id); }

  /** cancel 로 멈춘 실행. 실행하던 쪽(run)이 받는다. */
  public static final class CancelledException extends SQLException {
    CancelledException(SQLException cause) {
      super(EiDbBundle.message("sessions.cancelled"), cause);
    }
  }

  /** 그 접속에서 실행 중인 문장에 Statement.cancel() 을 보낸다. 실행 중이 아니면 아무것도 하지 않는다. 보냈으면 참. */
  public boolean cancel(String id) throws SQLException {
    return cancel(id, running.get(id));
  }

  /**
   * st 가 아직 그 접속에서 실행 중일 때만 cancel 한다. 같은 접속에서 뒤에 시작한 다른 콘솔의 문장은 멈추지 않는다. 보냈으면 참.
   * 그 문장에 처음 보낼 때 ABORT_AFTER_CANCEL_SEC 뒤의 확인을 걸어 둔다(cancel 자체가 막혀도 확인은 돈다).
   */
  public boolean cancel(String id, Statement st) throws SQLException {
    if (st == null || running.get(id) != st) return false;
    if (cancelled.add(st)) {
      AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> abortIfStuck(id, st), ABORT_AFTER_CANCEL_SEC, TimeUnit.SECONDS);
    }
    st.cancel();
    return true;
  }

  /**
   * 취소한 문장이 아직 돌고 있으면 그 접속을 목록에서 빼고 끊는다. 막힌 실행은 오류로 풀려 CancelledException 이 된다.
   * 미반영 변경이 있으면 끊지 않고 알리기만 한다(끊으면 변경이 사라진다): 사용자가 「끊기」로 정할 수 있다(close 는 실행 중이면 끊는다).
   * 접속 id 잠금을 잡지 않는다: 막힌 접속을 쓰는 다른 일이 잠금을 쥐고 있을 수 있다.
   */
  private void abortIfStuck(String id, Statement st) {
    if (running.get(id) != st) return;
    DbConfig c = DbSettings.getInstance().find(id);
    String name = c == null ? id : c.name;
    int lost = pending(id);
    if (lost > 0) {
      Notify.error(project, EiDbBundle.message("sessions.stuck.pending", name, ABORT_AFTER_CANCEL_SEC, lost));
      return;
    }
    Connection conn = open.get(id);
    if (conn == null || !open.remove(id, conn)) return;
    pending.remove(id);
    txTouched.remove(id);
    Comments.clear(id);
    abort(conn);
    Notify.error(project, EiDbBundle.message("sessions.aborted", name, ABORT_AFTER_CANCEL_SEC));
    notifyChanged();
  }

  /** 접속을 바로 끊는다(망을 기다리지 않는다). 서버는 미반영 변경을 롤백한다. */
  private static void abort(Connection conn) {
    try {
      conn.abort(AppExecutorUtil.getAppExecutorService());
    } catch (SQLException | RuntimeException e) {
      // 끊는 것도 실패하면 이미 끊긴 접속이다. 더 할 수 있는 일이 없다.
      LOG.info("접속 끊기 실패: " + e.getMessage());
    }
  }

  public int maxRows() { return maxRows; }

  /** 음수는 받지 않는다. 바꾸면 리스너를 불러 다른 콘솔 도구줄도 맞춘다. */
  public void setMaxRows(int rows) {
    if (rows < 0) throw new IllegalArgumentException(EiDbBundle.message("sessions.bad.row.limit", rows));
    maxRows = rows;
    notifyChanged();
  }

  /**
   * 열린 접속을 돌려주거나 새로 연다. 같은 접속 id 는 한 번만 연다.
   * 열린 접속이 응답하지 않을 때: 미반영 변경이 있고 접속이 닫히지 않았으면(느린 망일 수 있음) 다시 열지 않고 오류,
   * 그 밖에는 닫고 다시 열며 사라진 미반영 변경 건수를 알린다.
   */
  public Connection connection(DbConfig c) throws SQLException {
    synchronized (lock(c.id)) {
      Connection conn = open.get(c.id);
      if (conn != null) {
        // 다른 콘솔이 실행 중인 접속에 isValid 를 보내면 PostgreSQL 은 소켓 시간 제한으로 그 실행을 끊고 Oracle 은 막힌다.
        if (running.containsKey(c.id) || conn.isValid(VALID_TIMEOUT_SEC)) return conn;
        int lost = pending(c.id);
        if (lost > 0 && !conn.isClosed()) {
          throw new SQLException(EiDbBundle.message("sessions.not.responding", c.name, VALID_TIMEOUT_SEC, lost));
        }
        close(c.id);
        if (lost > 0) Notify.error(project, EiDbBundle.message("sessions.lost", c.name, lost));
      }
      return openNew(c);
    }
  }

  private Connection openNew(DbConfig c) throws SQLException {
    String schema = c.schema == null ? "" : c.schema.trim();
    if (!schema.isEmpty() && !IDENT.matcher(schema).matches()) throw new SQLException(EiDbBundle.message("sessions.bad.schema", schema));
    String password = Passwords.get(c);
    if (password == null) throw new SQLException(EiDbBundle.message("sessions.no.password"));
    Connection conn = connect(c, password);
    try {
      conn.setAutoCommit(autoCommit(c));
      // Oracle 의 setReadOnly 는 서버에서 막지 않는다(드라이버 표시뿐). 변경 거절은 SqlRunner 가 문장으로 판정한다.
      conn.setReadOnly(c.readOnly);
      if (!schema.isEmpty()) {
        switch (c.kind) {
          case ORACLE -> { try (Statement st = conn.createStatement()) { st.execute("ALTER SESSION SET CURRENT_SCHEMA = " + schema); } }
          case MYSQL -> conn.setCatalog(schema);
          case POSTGRES -> conn.setSchema(schema);
        }
      } else if (c.kind == DbKind.POSTGRES) {
        Meta.rememberSchema(c.id, conn.getSchema());
      }
    } catch (SQLException | RuntimeException e) {
      closeWithoutCommit(conn);
      throw e;
    }
    warnOldDriver(c, conn);
    open.put(c.id, conn);
    pending.put(c.id, 0);
    txTouched.remove(c.id);
    notifyChanged();
    return conn;
  }

  /** PostgreSQL 드라이버가 Drivers.PG_MIN_DRIVER 보다 낮으면 알린다. 판을 읽지 못하면 알리지 않는다(접속은 그대로 쓴다). */
  private void warnOldDriver(DbConfig c, Connection conn) {
    if (c.kind != DbKind.POSTGRES) return;
    try {
      String version = conn.getMetaData().getDriverVersion();
      if (Drivers.isOlderDriver(version, Drivers.PG_MIN_DRIVER)) {
        Notify.warning(project, EiDbBundle.message("sessions.old.pg.driver", c.name, version));
      }
    } catch (SQLException e) {
      // 판 확인은 안내용이라 실패해도 접속을 막지 않는다.
    }
  }

  /** 드라이버를 읽어 연다. 드라이버가 주소를 받지 않으면 오류. */
  private static Connection connect(DbConfig c, String password) throws SQLException {
    Connection conn;
    try {
      conn = Drivers.load(c).connect(c.url(), props(c, password));
    } catch (ReflectiveOperationException | java.io.IOException e) {
      throw new SQLException(e.getMessage(), e);
    }
    if (conn == null) throw new SQLException(EiDbBundle.message("sessions.url.rejected", c.url()));
    return conn;
  }

  /**
   * 접속 속성. PostgreSQL: 읽기 전용 접속은 readOnlyMode=always 로 서버가 변경을 막게 하고,
   * stringtype=unspecified 로 글 바인드를 서버가 칼럼 형(bool · uuid · json · enum · 시각)에 맞춰 읽게 하고,
   * autosave=always 로 문장마다 저장점을 두어 자동 커밋이 꺼진 상태의 오류가 트랜잭션 전체를 망치지 않게 한다(성공하면 저장점 해제).
   */
  private static Properties props(DbConfig c, String password) {
    Properties props = new Properties();
    props.setProperty("user", c.user);
    props.setProperty("password", password == null ? "" : password);
    switch (c.kind) {
      case ORACLE -> {
        props.setProperty("remarksReporting", "true");
        props.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(CONNECT_TIMEOUT_SEC * MILLIS_PER_SEC));
      }
      case POSTGRES -> {
        props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SEC));
        props.setProperty("stringtype", "unspecified");
        if (c.readOnly) props.setProperty("readOnlyMode", "always");
        props.setProperty("autosave", "always");
        props.setProperty("cleanupSavepoints", "true");
        // 망이 끊긴 접속을 운영체제가 알아채게 한다. 읽기 시간 제한(socketTimeout)은 긴 조회도 끊으므로 두지 않는다.
        props.setProperty("tcpKeepAlive", "true");
      }
      case MYSQL -> props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SEC * MILLIS_PER_SEC));
    }
    return props;
  }

  /** 접속 시험용: 열어 보고 DB 이름 · 판을 돌려준 뒤 닫는다. */
  public static String test(DbConfig c, String password) throws Exception {
    try (Connection conn = connect(c, password)) {
      return conn.getMetaData().getDatabaseProductName() + " " + conn.getMetaData().getDatabaseMajorVersion();
    }
  }

  /**
   * 한 문장을 실행한다. binds 는 ? 자리 값(순서대로). 끝의 ; 는 SqlGuard.trimStatement 대로 뗀다.
   * SELECT 는 이 프로젝트의 조회 건수까지만 읽는다. Oracle 컴파일 경고(PL/SQL 단위 생성)는 알림으로 띄운다.
   */
  public QueryResult run(DbConfig c, String sql, List<Object> binds) throws SQLException {
    return run(c, sql, binds, maxRows);
  }

  /**
   * run 과 같되, 문장을 등록한 뒤 실행 직전에 stop 을 본다. 참이면 실행하지 않고 CancelledException.
   * 접속을 여는 중에 들어온 취소(cancel 할 문장이 아직 없음)를 놓치지 않으려는 것이다.
   * registered 에는 등록한 문장을 넣는다. 부르는 쪽은 cancel(id, 그 문장) 으로 자기 실행만 멈춘다.
   */
  public QueryResult run(DbConfig c, String sql, List<Object> binds, BooleanSupplier stop, AtomicReference<Statement> registered) throws SQLException {
    return execute(c, sql, binds, maxRows, stop, registered, null);
  }

  /** limit 건까지 읽는다(ALL_ROWS 면 끝까지). */
  public QueryResult run(DbConfig c, String sql, List<Object> binds, int limit) throws SQLException {
    return run(c, sql, binds, limit, () -> false, new AtomicReference<>(), null);
  }

  /**
   * limit 건까지 읽되 stop · registered 는 run(c, sql, binds, stop, registered) 와 같다. 행을 읽는 중에도 stop 을 보고 멈춘다.
   * progress 는 읽은 행 수를 PROGRESS_EVERY_ROWS 마다 받는다(null 이면 알리지 않음). 「더 보기」가 쓴다.
   */
  public QueryResult run(DbConfig c, String sql, List<Object> binds, int limit, BooleanSupplier stop, AtomicReference<Statement> registered,
                         IntConsumer progress) throws SQLException {
    return execute(c, sql, binds, limit, stop, registered, progress);
  }

  /**
   * 바인드가 없으면 Statement 로 보낸다: PreparedStatement 는 ? 를 자리 표시로 보아 PostgreSQL jsonb 의 ? · ?| · ?& 연산자가 실패한다.
   * 문장은 running 에서 뺀 뒤 닫는다(끝 무렵의 중지가 닫힌 문장에 cancel 을 보내지 않게).
   */
  private QueryResult execute(DbConfig c, String sql, List<Object> binds, int limit, BooleanSupplier stop, AtomicReference<Statement> registered,
                              IntConsumer progress) throws SQLException {
    Connection conn = connection(c);
    String text = SqlGuard.trimStatement(sql);
    long start = System.currentTimeMillis();
    boolean plain = binds == null || binds.isEmpty();
    Statement ps = plain ? conn.createStatement() : conn.prepareStatement(text);
    running.put(c.id, ps);
    registered.set(ps);
    notifyChanged();
    try (ps) {
      try {
        if (stop.getAsBoolean()) {
          cancelled.add(ps);
          throw new SQLException(EiDbBundle.message("sessions.cancelled.before"));
        }
        // limit + 1 건을 받아 보고 넘치면 잘렸다고 알린다.
        if (limit != ALL_ROWS) ps.setMaxRows(limit + 1);
        ps.setFetchSize(limit == ALL_ROWS ? FETCH_SIZE : Math.min(limit + 1, FETCH_SIZE));
        boolean isQuery;
        if (plain) {
          isQuery = ps.execute(text);
        } else {
          PreparedStatement prepared = (PreparedStatement) ps;
          for (int i = 0; i < binds.size(); i++) prepared.setObject(i + 1, binds.get(i));
          isQuery = prepared.execute();
        }
        boolean readOnly = READ_HEAD.matcher(SqlGuard.normalize(text)).find() && !SqlGuard.isWrite(text);
        if (!readOnly) txTouched.add(c.id);
        warnCompileErrors(c, ps, text);
        countChange(c, conn, text);
        if (!isQuery) {
          int count = ps.getUpdateCount();
          return new QueryResult(text, List.of(), List.of(), List.of(), count, System.currentTimeMillis() - start, false, Map.of(), bindList(binds));
        }
        try (ResultSet rs = ps.getResultSet()) {
          ResultSetMetaData md = rs.getMetaData();
          List<String> cols = new ArrayList<>();
          List<String> types = new ArrayList<>();
          List<Integer> precisions = new ArrayList<>();
          List<Integer> scales = new ArrayList<>();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            cols.add(md.getColumnLabel(i));
            types.add(md.getColumnTypeName(i));
            precisions.add(md.getPrecision(i));
            scales.add(md.getScale(i));
          }
          List<List<Object>> rows = new ArrayList<>();
          boolean truncated = false;
          while (rs.next()) {
            if (limit != ALL_ROWS && rows.size() == limit) { truncated = true; break; }
            List<Object> row = new ArrayList<>(cols.size());
            for (int i = 1; i <= cols.size(); i++) row.add(readValue(rs, i));
            rows.add(row);
            if (rows.size() % PROGRESS_EVERY_ROWS == 0) {
              // 드라이버는 행을 받는 사이의 cancel 을 무시할 수 있어 여기서도 멈춘다.
              if (stop.getAsBoolean()) {
                cancelled.add(ps);
                throw new SQLException(EiDbBundle.message("sessions.cancelled"));
              }
              if (progress != null) progress.accept(rows.size());
            }
          }
          long millis = System.currentTimeMillis() - start;
          rs.close();
          // 주석 읽기(메타 조회)도 트랜잭션을 열므로 그 뒤에 닫는다.
          Map<String, String> comments = Comments.forQuery(conn, c, text, cols);
          if (readOnly && running.get(c.id) == ps) endIdleTransaction(c, conn);
          return new QueryResult(text, cols, types, rows, -1, millis, truncated, comments, bindList(binds), precisions, scales);
        }
      } catch (SQLException e) {
        if (cancelled.contains(ps)) throw new CancelledException(e);
        throw e;
      } finally {
        running.remove(c.id, ps);
        cancelled.remove(ps);
        notifyChanged();
      }
    }
  }

  /**
   * 메타 읽기(탐색기 · 자동완성 · 작업대 · 정의서) 뒤에 부른다. PostgreSQL 에서 자동 커밋이 꺼져 있으면 메타 조회도 트랜잭션을 연다.
   * 그 접속에서 실행 중인 문장이 없을 때만 endIdleTransaction 대로 닫는다. 화면 스레드에서 부르지 않는다.
   */
  public void endReadTransaction(DbConfig c) {
    Connection conn = open.get(c.id);
    if (conn == null || running.containsKey(c.id)) return;
    endIdleTransaction(c, conn);
  }

  /**
   * PostgreSQL 에서 자동 커밋이 꺼져 있고 미반영 변경 · txTouched 가 없을 때, 지금 트랜잭션이 아무것도 바꾸지 않았으면
   * (변경하는 함수 · FOR UPDATE 잠금이 있으면 트랜잭션 번호가 생긴다) 롤백해 닫는다. 확인에 실패하면(PostgreSQL 9 등) 그대로 둔다.
   * Oracle · MySQL 은 바꾸지 않는다.
   */
  private void endIdleTransaction(DbConfig c, Connection conn) {
    if (c.kind != DbKind.POSTGRES || pending(c.id) > 0 || txTouched.contains(c.id)) return;
    try {
      if (conn.getAutoCommit()) return;
      try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(PG_TX_WROTE)) {
        if (rs.next() && rs.getObject(1) == null) conn.rollback();
      }
    } catch (SQLException e) {
      LOG.info("읽기 트랜잭션을 닫지 못했습니다: " + e.getMessage());
    }
  }

  private static List<Object> bindList(List<Object> binds) {
    return binds == null ? List.of() : new ArrayList<>(binds);
  }

  /**
   * 자동 커밋이 꺼진 접속의 미반영 건수를 맞춘다. Oracle · MySQL 의 DDL 은 앞 변경까지 커밋하므로 0 으로 돌리고 알린다
   * (DDL 자신도 바로 반영된다). PostgreSQL 의 DDL 은 트랜잭션 안이라 변경 한 건으로 센다.
   */
  private void countChange(DbConfig c, Connection conn, String text) throws SQLException {
    if (conn.getAutoCommit() || !SqlGuard.isWrite(text)) return;
    if (c.kind != DbKind.POSTGRES && SqlGuard.isDdl(text)) {
      Integer committed = pending.put(c.id, 0);
      if (committed != null && committed > 0) {
        Notify.info(project, EiDbBundle.message("sessions.ddl.committed", c.name, committed));
      }
    } else {
      pending.merge(c.id, 1, Integer::sum);
    }
    notifyChanged();
  }

  /** Oracle 은 컴파일 오류가 있는 PL/SQL 단위를 만들어도 실패가 아닌 경고로 돌려준다. */
  private void warnCompileErrors(DbConfig c, Statement st, String text) throws SQLException {
    if (c.kind != DbKind.ORACLE || !SqlGuard.isPlsql(text)) return;
    SQLWarning w = st.getWarnings();
    if (w != null) Notify.error(project, EiDbBundle.message("sessions.compile.errors", c.name));
  }

  /**
   * 화면 · 복사에 쓸 값. CLOB 은 앞 CLOB_LIMIT 자, 이진 값은 QueryResult.BinaryValue(길이만).
   * Oracle 날짜 · 시각 클래스(oracle.sql.TIMESTAMP 등)는 표준 자바 형으로 바꾼다(드라이버 객체를 결과에 남기지 않는다).
   */
  private static Object readValue(ResultSet rs, int i) throws SQLException {
    Object v = rs.getObject(i);
    if (v instanceof Clob clob) {
      long len = clob.length();
      return clob.getSubString(1, (int) Math.min(len, CLOB_LIMIT)) + (len > CLOB_LIMIT ? "…" : "");
    }
    if (v instanceof Blob blob) return new QueryResult.BinaryValue(blob.length());
    if (v instanceof byte[] bytes) return new QueryResult.BinaryValue(bytes.length);
    String type = v == null ? "" : v.getClass().getName();
    if (type.startsWith(ORACLE_SQL)) {
      if (type.endsWith(".TIMESTAMPTZ")) return rs.getObject(i, OffsetDateTime.class);
      if (type.endsWith(".TIMESTAMP") || type.endsWith(".TIMESTAMPLTZ") || type.endsWith(".DATE")) return rs.getTimestamp(i);
    }
    return v;
  }

  public void commit(String id) throws SQLException {
    Connection conn = open.get(id);
    if (conn != null) conn.commit();
    pending.put(id, 0);
    txTouched.remove(id);
    notifyChanged();
  }

  public void rollback(String id) throws SQLException {
    Connection conn = open.get(id);
    if (conn != null) conn.rollback();
    pending.put(id, 0);
    txTouched.remove(id);
    notifyChanged();
  }

  /** 미반영 변경은 롤백된다. 부르는 쪽이 먼저 확인받는다. 실행 중이면 롤백 · 닫기가 그 실행 뒤에 막히므로 접속을 끊는다(서버가 롤백). */
  public void close(String id) {
    boolean busy = running.containsKey(id);
    Connection conn = detach(id);
    if (conn != null) {
      if (busy) abort(conn); else closeWithoutCommit(conn);
    }
    notifyChanged();
  }

  /** 접속을 목록에서 빼고(미반영 건수 · 주석 캐시도) 돌려준다. 닫지는 않는다. */
  private Connection detach(String id) {
    Connection conn;
    synchronized (lock(id)) {
      conn = open.remove(id);
      pending.remove(id);
      txTouched.remove(id);
    }
    Comments.clear(id);
    return conn;
  }

  /** 자동 커밋이 꺼져 있으면 롤백 후 닫는다. 롤백 · 닫기가 실패하면 close() 가 커밋할 수 있으니(Oracle) 접속을 끊어 서버가 롤백하게 한다. */
  private static void closeWithoutCommit(Connection conn) {
    try {
      if (!conn.getAutoCommit()) conn.rollback();
      conn.close();
    } catch (SQLException e) {
      abort(conn);
    }
  }

  /** 롤백 · 닫기는 망을 거쳐 막힐 수 있어(망이 끊긴 경우) 화면 스레드가 아닌 풀 스레드에서 한다. 접속은 먼저 목록에서 뺀다. 실행 중인 접속은 끊는다. */
  @Override
  public void dispose() {
    List<Connection> closing = new ArrayList<>();
    List<Connection> busy = new ArrayList<>();
    for (String id : new ArrayList<>(open.keySet())) {
      boolean running = this.running.containsKey(id);
      Connection conn = detach(id);
      if (conn != null) (running ? busy : closing).add(conn);
    }
    for (Connection conn : busy) abort(conn);
    if (closing.isEmpty()) return;
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      for (Connection conn : closing) closeWithoutCommit(conn);
    });
  }
}
