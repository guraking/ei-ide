package ei.db.bench;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.SchemaCache;
import ei.db.SqlGuard;
import ei.db.console.ConsoleRun;
import ei.db.console.SqlParams;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 작업대 탭들이 함께 쓰는 것: 지금 접속, 연결 모양 · 값 자리 선택, 아래 미리 보기로 SQL 보내기, 표 · 칼럼 · 외래 키 읽기.
 * 읽기는 풀 스레드에서 하고 결과는 화면 스레드에서 넘긴다(작업대가 모달이 아니어도 ModalityState.any 로 바로 돈다).
 * 접속이 바뀌면 읽어 둔 것을 비운다. 표 · 칼럼 · 외래 키는 SchemaCache 를 거쳐 자동완성 · 탐색기와 같이 쓴다. 실패는 onError 로 한국어 글을 넘긴다.
 * 창을 닫으면(close) 작업대가 보낸 문장(run)을 멈추고, 그 뒤에 온 결과 · 오류는 버린다.
 */
final class Bench {
  /** 한 칸에 문장이 둘 이상일 때의 거절 글. 뒤 문장은 위험 문장 · 운영 확인을 거치지 않으므로 보내지 않는다. */
  static final String MANY_STATEMENTS = "문장이 둘 이상입니다. 한 문장만 두거나 「에디터에 넣기」 로 콘솔에서 돌리세요.";
  /** 건수는 한 행만 읽는다. */
  private static final int COUNT_ROWS = 1;

  /** 작업대가 보낸 문장 하나: 접속과 DbSessions 가 등록한 문장. */
  private record Running(DbConfig config, AtomicReference<Statement> statement) {}

  /** 미리 보기 받는 곳: SQL 과 아래에 보일 알림 줄들. */
  interface Sink { void show(String sql, List<String> notes); }

  final Project project;
  private final Sink sink;
  private final Consumer<String> onError;
  private DbConfig config;
  private QueryBuilder.JoinStyle style = QueryBuilder.JoinStyle.OLD;
  private QueryBuilder.Bind bind = QueryBuilder.Bind.COLON;
  private List<Meta.Table> tables;
  private final Map<String, List<Meta.Column>> columns = new ConcurrentHashMap<>();
  private final Map<String, List<Meta.ForeignKey>> fks = new ConcurrentHashMap<>();
  private final List<Runnable> listeners = new ArrayList<>();
  private Runnable beforeReplace = () -> { };
  /** 접속이 바뀔 때마다 오른다. 읽기가 끝났을 때 값이 다르면 그 결과는 버린다. */
  private int generation;
  /** 창을 닫았으면 참. 풀 스레드가 실행 직전에 본다. */
  private volatile boolean closed;
  private final Set<Running> runs = ConcurrentHashMap.newKeySet();

  Bench(Project project, Sink sink, Consumer<String> onError) {
    this.project = project;
    this.sink = sink;
    this.onError = onError;
  }

  DbConfig config() { return config; }

  DbKind kind() { return config == null ? DbKind.ORACLE : config.kind; }

  /** 고른 JOIN 모양. (+) 가 없는 DB 의 옛 방식은 QueryBuilder 가 안쪽 조인만 쉼표로 쓴다. */
  QueryBuilder.JoinStyle style() { return style; }

  QueryBuilder.Bind bind() { return bind; }

  String schema() { return config == null ? null : Meta.schemaOf(config); }

  /** 접속 · 모양이 바뀌면 부를 것(탭들이 미리 보기를 다시 만든다). */
  void onChange(Runnable r) { listeners.add(r); }

  void setConfig(DbConfig c) {
    config = c;
    generation++;
    tables = null;
    columns.clear();
    fks.clear();
    fire();
  }

  void setStyle(QueryBuilder.JoinStyle s) {
    style = s;
    fire();
  }

  void setBind(QueryBuilder.Bind b) {
    bind = b;
    fire();
  }

  private void fire() {
    for (Runnable r : listeners) r.run();
  }

  void show(String sql, List<String> notes) { sink.show(sql, notes); }

  /** 도구가 SQL 칸의 문장을 바꿔 놓을 때 부른다. 바꾸기 전 문장을 「되돌리기」 로 돌아갈 수 있게 남긴다. */
  void replace(String sql, List<String> notes) {
    beforeReplace.run();
    sink.show(sql, notes);
  }

  void onReplace(Runnable r) { beforeReplace = r; }

  /** 기본 스키마의 표 · 뷰. 읽어 둔 것이 있으면 바로, 없으면 읽은 뒤 화면 스레드에서. */
  void tables(Consumer<List<Meta.Table>> done) {
    if (tables != null) {
      done.accept(tables);
      return;
    }
    DbConfig c = config;
    if (c == null) return;
    load(() -> meta(c, conn -> SchemaCache.tables(conn, c, Meta.schemaOf(c))), list -> {
      tables = list;
      done.accept(list);
    });
  }

  /** 표의 칼럼(이름은 메타 그대로). */
  void columns(String table, Consumer<List<Meta.Column>> done) {
    String key = JoinAdvisor.key(table);
    List<Meta.Column> have = columns.get(key);
    if (have != null) {
      done.accept(have);
      return;
    }
    DbConfig c = config;
    if (c == null) return;
    load(() -> meta(c, conn -> SchemaCache.columns(conn, c, Meta.schemaOf(c), table)), list -> {
      columns.put(key, list);
      done.accept(list);
    });
  }

  /** 표의 외래 키(그 표가 다른 표를 가리키는 것). */
  void foreignKeys(String table, Consumer<List<Meta.ForeignKey>> done) {
    String key = JoinAdvisor.key(table);
    List<Meta.ForeignKey> have = fks.get(key);
    if (have != null) {
      done.accept(have);
      return;
    }
    DbConfig c = config;
    if (c == null) return;
    load(() -> meta(c, conn -> SchemaCache.importedKeys(conn, c, Meta.schemaOf(c), table)), list -> {
      fks.put(key, list);
      done.accept(list);
    });
  }

  /** 읽어 둔 칼럼(없으면 빈 목록). */
  List<Meta.Column> cachedColumns(String table) {
    return columns.getOrDefault(JoinAdvisor.key(table), List.of());
  }

  List<Meta.ForeignKey> cachedForeignKeys(String table) {
    return fks.getOrDefault(JoinAdvisor.key(table), List.of());
  }

  /** SELECT 의 건수를 센다. 값 자리가 있으면 값을 묻는다(취소하면 아무것도 안 함). 결과 · 거절 글은 done 으로 넘긴다. */
  void count(String select, Consumer<String> done) {
    DbConfig c = config;
    if (c == null) {
      done.accept("접속을 먼저 고르세요.");
      return;
    }
    if (manyStatements(select, c.kind)) {
      done.accept(MANY_STATEMENTS);
      return;
    }
    String sql;
    try {
      sql = SqlTools.countOf(select);
    } catch (IllegalArgumentException e) {
      done.accept(e.getMessage());
      return;
    }
    SqlParams.Bound b = ConsoleRun.bind(project, sql);
    if (b == null) return;
    load(() -> run(c, b.sql(), b.binds(), COUNT_ROWS), r -> {
      Object v = r.rows().isEmpty() || r.rows().get(0).isEmpty() ? null : r.rows().get(0).get(0);
      if (v == null) {
        done.accept("건수를 읽지 못했습니다.");
        return;
      }
      try {
        done.accept(String.format(java.util.Locale.ROOT, "%,d건", new java.math.BigDecimal(v.toString().strip()).longValue()));
      } catch (NumberFormatException e) {
        done.accept("건수를 읽지 못했습니다: " + v);
      }
    });
  }

  /** 따옴표 · 주석 밖의 ; 로 나눈 문장이 둘 이상이면 참. */
  static boolean manyStatements(String sql, DbKind kind) { return SqlGuard.hasSeveralStatements(sql, kind); }

  boolean isClosed() { return closed; }

  /** yieldBusy 실행이 그 접속에 다른 문장(콘솔 · 배치)이 돌고 있어 보내지 않았을 때 던진다. */
  static final class Busy extends SQLException {
    Busy() { super("busy"); }
  }

  /** limit 건까지 실행한다. 창을 닫으면 실행 전이면 보내지 않고, 실행 중이면 close 가 멈춘다. 풀 스레드에서 부른다. */
  QueryResult run(DbConfig c, String sql, List<Object> binds, int limit) throws SQLException {
    return run(c, sql, binds, limit, false, () -> false, new AtomicReference<>());
  }

  /**
   * run 과 같되 stop 이 켜지면 보내지 않거나(자리를 기다리는 중) 읽기를 멈추고, statement 에 DbSessions 가 등록한 문장을 둔다(cancel 로 멈출 때 쓴다).
   * yieldBusy 면 그 접속에 다른 문장이 돌 때 자리를 기다리지 않고 Busy 를 던진다(사용자 문장 뒤에 줄 서지 않는다).
   */
  QueryResult run(DbConfig c, String sql, List<Object> binds, int limit, boolean yieldBusy, BooleanSupplier stop,
                  AtomicReference<Statement> statement) throws SQLException {
    Running r = new Running(c, statement);
    runs.add(r);
    DbSessions sessions = DbSessions.get(project);
    AtomicBoolean busy = new AtomicBoolean();
    BooleanSupplier quit = () -> {
      if (closed || stop.getAsBoolean()) return true;
      // 등록 전(자리를 기다리는 중)에만 본다. 등록된 뒤의 running 은 이 문장 자신이다.
      if (yieldBusy && statement.get() == null && sessions.isRunning(c.id)) busy.set(true);
      return busy.get();
    };
    try {
      return sessions.run(c, sql, binds, limit, quit, statement, null);
    } catch (SQLException e) {
      if (busy.get() || yieldBusy && statement.get() == null && sessions.isRunning(c.id)) throw new Busy();
      throw e;
    } finally {
      runs.remove(r);
    }
  }

  /** run(…, statement) 로 보낸 문장 하나를 풀 스레드에서 멈춘다. 아직 등록 전이면 아무것도 하지 않는다(그 쪽 stop 으로 그만둔다). */
  void cancel(DbConfig c, AtomicReference<Statement> statement) {
    Running r = new Running(c, statement);
    ApplicationManager.getApplication().executeOnPooledThread(() -> cancel(r));
  }

  /** 창을 닫을 때 부른다. 도는 문장마다 풀 스레드에서 취소를 보낸다(보내기가 네트워크를 기다릴 수 있다). */
  void close() {
    closed = true;
    for (Running r : runs) ApplicationManager.getApplication().executeOnPooledThread(() -> cancel(r));
  }

  private void cancel(Running r) {
    try {
      DbSessions.get(project).cancel(r.config().id, r.statement().get());
    } catch (SQLException e) {
      Notify.error(project, EiDbBundle.message("runner.cancel.failed", r.config().name, e.getMessage()));
    }
  }

  interface Reader<T> { T read() throws Exception; }

  private interface MetaReader<T> { T read(Connection conn) throws SQLException; }

  /** 접속에서 메타를 읽고(SchemaCache 를 거친다), PostgreSQL 자동 커밋 꺼짐이면 그 뒤 빈 트랜잭션을 닫는다. 풀 스레드에서 부른다. */
  private <T> T meta(DbConfig c, MetaReader<T> reader) throws SQLException {
    DbSessions sessions = DbSessions.get(project);
    T value = reader.read(sessions.connection(c));
    sessions.endReadTransaction(c);
    return value;
  }

  /** 풀 스레드에서 읽고, 접속이 그대로이고 창이 열려 있으면 화면 스레드에서 done. 실패도 같은 때만 onError. */
  <T> void load(Reader<T> reader, Consumer<T> done) {
    int gen = generation;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        T value = reader.read();
        ApplicationManager.getApplication().invokeLater(() -> {
          if (current(gen)) done.accept(value);
        }, ModalityState.any());
      } catch (Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        ApplicationManager.getApplication().invokeLater(() -> {
          if (current(gen)) onError.accept(message);
        }, ModalityState.any());
      }
    });
  }

  /** gen 때의 접속이 그대로이고 창 · 프로젝트가 열려 있으면 참. 화면 스레드에서 부른다. */
  private boolean current(int gen) { return gen == generation && !closed && !project.isDisposed(); }
}
