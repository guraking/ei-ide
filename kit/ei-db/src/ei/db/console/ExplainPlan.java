package ei.db.console;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.SqlGuard;
import ei.db.console.PlanAdvisor.Finding;
import ei.db.console.PlanAdvisor.Index;
import ei.db.console.PlanAdvisor.Kind;
import ei.db.console.PlanAdvisor.Step;
import ei.db.mybatis.SqlValues;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 커서 문장(선택 영역이 있으면 그 첫 문장)의 실행 계획을 단계 표로 보이고 느린 곳 · 고칠 법을 붙인다(PlanPanel).
 * 콘솔 도구줄 단추 · 우클릭 · Ctrl+Shift+E, 매퍼 실행 창의 「느린 곳 찾기」가 부른다. 화면 스레드.
 * 계획은 문장을 실행하지 않는다: Oracle 은 EXPLAIN PLAN ... FOR 후 PLAN_TABLE · DBMS_XPLAN.DISPLAY, PostgreSQL · MySQL 은 EXPLAIN(ANALYZE 없이).
 * 「실제 시간 재기」(Oracle, 조회만)는 문장을 한 번 실행해 V$SQL_PLAN_STATISTICS_ALL 을 읽는다.
 */
public final class ExplainPlan {
  private ExplainPlan() {}

  private static final Logger LOG = Logger.getInstance(ExplainPlan.class);
  private static final String STATEMENT_ID_PREFIX = "EI_";
  private static final String MEASURE_PREFIX = "EI_MEASURE_";
  private static final String ORACLE_DISPLAY = "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, ?, 'TYPICAL'))";
  private static final String ORACLE_STEPS = """
      SELECT ID, PARENT_ID, DEPTH, OPERATION, OPTIONS, OBJECT_OWNER, OBJECT_NAME, COST, CARDINALITY, ACCESS_PREDICATES, FILTER_PREDICATES
        FROM PLAN_TABLE
       WHERE STATEMENT_ID = ?
       ORDER BY ID""";
  /** 맨 앞 주석이 표시라서 이 조회 자신(SELECT 로 시작)은 걸리지 않는다. */
  private static final String FIND_CURSOR = """
      SELECT SQL_ID, CHILD_NUMBER
        FROM V$SQL
       WHERE SQL_TEXT LIKE ?
       ORDER BY LAST_ACTIVE_TIME DESC""";
  private static final String CURSOR_STEPS = """
      SELECT ID, PARENT_ID, DEPTH, OPERATION, OPTIONS, OBJECT_OWNER, OBJECT_NAME, COST, CARDINALITY, ACCESS_PREDICATES, FILTER_PREDICATES
           , LAST_STARTS, LAST_OUTPUT_ROWS, LAST_CR_BUFFER_GETS, LAST_ELAPSED_TIME
        FROM V$SQL_PLAN_STATISTICS_ALL
       WHERE SQL_ID = ?
         AND CHILD_NUMBER = ?
       ORDER BY ID""";
  private static final String CURSOR_DISPLAY = "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, 'ALLSTATS LAST'))";
  /** STATISTICS_LEVEL 기본값. 재기가 끝나면 이것으로 돌린다. */
  private static final String STATISTICS_DEFAULT = "TYPICAL";
  /** 실제 시간을 잴 때 한 번에 받을 행 수. 받은 행은 버리므로 왕복만 줄인다. */
  private static final int MEASURE_FETCH_SIZE = 1000;
  /** 진행 창의 멈춤 단추를 보는 간격. */
  private static final long CANCEL_POLL_MS = 200;
  /** ORA-00942 표 · 뷰 없음, ORA-01031 권한 부족: V$ 뷰를 볼 수 없는 계정. */
  private static final int ORA_NO_TABLE = 942;
  private static final int ORA_NO_PRIVILEGE = 1031;

  /**
   * 화면 하나 분의 결과. actual 이면 실제 시간을 잰 것, millis · fetched 는 그때만 뜻이 있다. note 는 덧붙일 안내(없으면 null).
   * source 는 사용자가 쓴 모양의 문장(콘솔은 바인드 전 글, 매퍼는 값을 넣은 글)으로 「이렇게 바꾸세요」를 이것으로 만든다.
   */
  public record Plan(String sql, List<Object> binds, String source, DbKind kind, List<Step> steps, List<Finding> findings, String raw,
                     String note, boolean actual, long millis, long fetched) {}

  public static void run(Project project, Editor editor, VirtualFile file) {
    DbConfig c = ConsoleOpener.config(project, file);
    if (c == null) {
      Notify.error(project, EiDbBundle.message("console.connection.removed"));
      return;
    }
    String sql = statement(editor);
    if (sql == null) {
      Notify.info(project, EiDbBundle.message("explain.no.statement"));
      return;
    }
    List<SqlParams.Bound> bound = BindDialog.ask(project, List.of(sql));
    if (bound == null) return;
    SqlParams.Bound stmt = bound.get(0);
    analyze(project, c, stmt.sql(), stmt.binds(), sql);
  }

  /** sql 의 ? 자리에 binds 를 넣은 문장을 분석해 결과 창에 탭을 더한다. binds 는 없으면 null. source 는 Plan.source. 화면 스레드. */
  public static void analyze(Project project, DbConfig c, String sql, List<Object> binds, String source) {
    new Task.Backgroundable(project, EiDbBundle.message("plan.progress", c.name), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          Plan plan = plan(DbSessions.get(project), c, sql, binds, source);
          ApplicationManager.getApplication().invokeLater(() -> ResultView.showPlan(project, c, plan));
        } catch (Exception e) {
          ApplicationManager.getApplication().invokeLater(() -> ResultView.showError(project, c, sql, e));
        }
      }
    }.queue();
  }

  /** 「실제 시간 재기」. Oracle 의 조회 문장만. 진행 창의 멈춤으로 실행 중에도 멈춘다. 화면 스레드. */
  static void measure(Project project, DbConfig c, String sql, List<Object> binds, String source) {
    if (c.kind != DbKind.ORACLE) {
      Notify.info(project, EiDbBundle.message("plan.measure.oracle.only"));
      return;
    }
    if (!isQuery(sql)) {
      Notify.info(project, EiDbBundle.message("plan.measure.write"));
      return;
    }
    new Task.Backgroundable(project, EiDbBundle.message("plan.measure.progress", c.name, 0), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          Plan plan = measured(DbSessions.get(project), c, sql, binds, source, indicator);
          ApplicationManager.getApplication().invokeLater(() -> {
            ResultView.showPlan(project, c, plan);
            Notify.info(project, EiDbBundle.message("plan.measure.done", c.name, plan.millis(), plan.fetched()));
          });
        } catch (Exception e) {
          if (indicator.isCanceled()) ApplicationManager.getApplication().invokeLater(() -> ResultView.showCancelled(project, c, sql));
          else ApplicationManager.getApplication().invokeLater(() -> ResultView.showError(project, c, sql, e));
        }
      }
    }.queue();
  }

  /** SELECT · WITH 로 시작하고 바꾸는 문장이 아닌 것. 「실제 시간 재기」 단추를 켤지도 이것으로 정한다. */
  static boolean isQuery(String sql) {
    String head = SqlText.code(sql).strip().toUpperCase(Locale.ROOT);
    return (head.startsWith("SELECT") || head.startsWith("WITH")) && !SqlGuard.isWrite(sql);
  }

  /** 선택 영역의 첫 문장, 없으면 커서 문장. 끝의 ; 는 뗀다. 없으면 null. */
  private static String statement(Editor editor) {
    String selected = editor.getSelectionModel().getSelectedText();
    SqlSplitter.Stmt s;
    if (selected != null && !selected.isBlank()) {
      List<SqlSplitter.Stmt> all = SqlSplitter.split(selected);
      s = all.isEmpty() ? null : all.get(0);
    } else {
      s = SqlSplitter.at(editor.getDocument().getText(), editor.getCaretModel().getOffset());
    }
    if (s == null) return null;
    String text = s.text().strip();
    return text.endsWith(";") ? text.substring(0, text.length() - 1).strip() : text;
  }

  private static Plan plan(DbSessions sessions, DbConfig c, String sql, List<Object> binds, String source) throws SQLException {
    List<Step> steps;
    String raw;
    if (c.kind == DbKind.ORACLE) {
      // STATEMENT_ID 는 바인드를 받지 않아(문자열 리터럴만) 숫자로만 만든 값을 이어 붙인다.
      String id = STATEMENT_ID_PREFIX + System.nanoTime();
      // EXPLAIN PLAN 은 바인드 값을 받지 않으므로(ORA-01006) 값은 리터럴로 넣는다. 계획만 보고 실행하지 않는다.
      // 리터럴: 문자열은 ' 겹치기, 숫자 그대로, NULL, 날짜는 ANSI DATE · TIMESTAMP 글자(NLS 설정과 무관).
      String text = binds == null ? sql : SqlValues.inline(sql, binds);
      try (Statement st = sessions.connection(c).createStatement()) {
        st.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + id + "' FOR " + text);
      }
      QueryResult rows = sessions.run(c, ORACLE_STEPS, List.of(id), DbSessions.ALL_ROWS);
      steps = PlanParse.oracle(rows.columns(), rows.rows());
      raw = lines(sessions.run(c, ORACLE_DISPLAY, List.of(id), DbSessions.ALL_ROWS));
    } else {
      QueryResult r = sessions.run(c, "EXPLAIN " + sql, binds, DbSessions.ALL_ROWS);
      steps = c.kind == DbKind.POSTGRES ? PlanParse.postgres(firstColumn(r)) : PlanParse.mysql(r.columns(), r.rows());
      raw = c.kind == DbKind.POSTGRES ? lines(r) : table(r);
    }
    return finish(sessions, c, sql, binds, source, steps, raw, false, 0, 0);
  }

  /**
   * 문장을 한 번 실행해 단계마다 실제 값을 읽는다. 받은 행은 세기만 하고 버린다.
   * STATISTICS_LEVEL = ALL 은 이 세션에서 재는 동안만 두고 끝나면(실패해도) 기본값으로 돌린다.
   */
  private static Plan measured(DbSessions sessions, DbConfig c, String sql, List<Object> binds, String source, ProgressIndicator indicator) throws SQLException {
    Connection conn = sessions.connection(c);
    String marker = "/* " + MEASURE_PREFIX + System.nanoTime() + " */ ";
    long start = System.currentTimeMillis();
    long fetched = 0;
    setStatisticsLevel(conn, "ALL");
    try (PreparedStatement ps = conn.prepareStatement(marker + sql)) {
      ScheduledFuture<?> watch = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
          () -> cancelWhenAsked(indicator, ps), CANCEL_POLL_MS, CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
      try {
        ps.setFetchSize(MEASURE_FETCH_SIZE);
        for (int i = 0; binds != null && i < binds.size(); i++) ps.setObject(i + 1, binds.get(i));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            indicator.checkCanceled();
            if (++fetched % MEASURE_FETCH_SIZE == 0) indicator.setText(EiDbBundle.message("plan.measure.progress", c.name, fetched));
          }
        }
      } finally {
        watch.cancel(false);
      }
    } finally {
      setStatisticsLevel(conn, STATISTICS_DEFAULT);
    }
    long millis = System.currentTimeMillis() - start;
    try {
      QueryResult cursor = sessions.run(c, FIND_CURSOR, List.of(marker.strip() + "%"), DbSessions.ALL_ROWS);
      if (cursor.rows().isEmpty()) throw new SQLException(EiDbBundle.message("plan.measure.not.found"));
      List<Object> key = List.of(cursor.rows().get(0).get(0), cursor.rows().get(0).get(1));
      QueryResult rows = sessions.run(c, CURSOR_STEPS, key, DbSessions.ALL_ROWS);
      String raw = lines(sessions.run(c, CURSOR_DISPLAY, key, DbSessions.ALL_ROWS));
      return finish(sessions, c, sql, binds, source, PlanParse.oracle(rows.columns(), rows.rows()), raw, true, millis, fetched);
    } catch (SQLException e) {
      if (e.getErrorCode() == ORA_NO_TABLE || e.getErrorCode() == ORA_NO_PRIVILEGE) {
        throw new SQLException(EiDbBundle.message("plan.measure.no.privilege", e.getMessage()), e);
      }
      throw e;
    }
  }

  private static void setStatisticsLevel(Connection conn, String level) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("ALTER SESSION SET STATISTICS_LEVEL = " + level);
    }
  }

  private static void cancelWhenAsked(ProgressIndicator indicator, PreparedStatement ps) {
    if (!indicator.isCanceled()) return;
    try {
      ps.cancel();
    } catch (SQLException e) {
      // 이미 끝났거나 닫힌 문장이다. 멈춤은 fetch 고리의 checkCanceled 가 마저 처리한다.
      LOG.info("측정 문장 취소 실패", e);
    }
  }

  private static Plan finish(DbSessions sessions, DbConfig c, String sql, List<Object> binds, String source, List<Step> steps, String raw,
                             boolean actual, long millis, long fetched) throws SQLException {
    String note = null;
    Map<String, List<Index>> indexes = Map.of();
    try {
      indexes = indexes(sessions.connection(c), c, steps);
    } catch (SQLException e) {
      note = EiDbBundle.message("plan.index.failed", ResultView.message(e));
    }
    return new Plan(sql, binds, source, c.kind, steps, PlanAdvisor.analyze(steps, indexes), raw, note, actual, millis, fetched);
  }

  /**
   * 전체 읽기 단계 표의 인덱스(표 이름 대문자 → 인덱스들). approximate=true 로 부른다:
   * Oracle 드라이버는 false 면 표를 ANALYZE 한다.
   */
  private static Map<String, List<Index>> indexes(Connection conn, DbConfig c, List<Step> steps) throws SQLException {
    Map<String, List<Index>> out = new HashMap<>();
    DatabaseMetaData md = conn.getMetaData();
    for (Step s : steps) {
      if (s.kind() != Kind.FULL_SCAN || s.object().isEmpty()) continue;
      String key = s.object().toUpperCase(Locale.ROOT);
      if (out.containsKey(key)) continue;
      String catalog = c.kind == DbKind.MYSQL ? conn.getCatalog() : null;
      String schema = c.kind == DbKind.ORACLE && !s.owner().isEmpty() ? s.owner() : null;
      Map<String, TreeMap<Short, String>> byName = new TreeMap<>();
      try (ResultSet rs = md.getIndexInfo(catalog, schema, s.object(), false, true)) {
        while (rs.next()) {
          String name = rs.getString("INDEX_NAME");
          String column = rs.getString("COLUMN_NAME");
          if (name == null || column == null) continue; // 표 통계 행
          byName.computeIfAbsent(name, k -> new TreeMap<>()).put(rs.getShort("ORDINAL_POSITION"), column.replace("\"", "").toUpperCase(Locale.ROOT));
        }
      }
      List<Index> list = new ArrayList<>();
      for (Map.Entry<String, TreeMap<Short, String>> e : byName.entrySet()) list.add(new Index(e.getKey(), new ArrayList<>(e.getValue().values())));
      out.put(key, list);
    }
    return out;
  }

  private static List<String> firstColumn(QueryResult r) {
    List<String> out = new ArrayList<>();
    for (List<Object> row : r.rows()) out.add(row.isEmpty() || row.get(0) == null ? "" : row.get(0).toString());
    return out;
  }

  private static String lines(QueryResult r) { return String.join("\n", firstColumn(r)); }

  /** MySQL EXPLAIN 표를 「칼럼 | 칼럼」 줄 글로. */
  private static String table(QueryResult r) {
    StringBuilder b = new StringBuilder(String.join(" | ", r.columns()));
    for (List<Object> row : r.rows()) {
      b.append('\n');
      for (int i = 0; i < row.size(); i++) b.append(i == 0 ? "" : " | ").append(row.get(i) == null ? "" : row.get(i));
    }
    return b.toString();
  }
}
