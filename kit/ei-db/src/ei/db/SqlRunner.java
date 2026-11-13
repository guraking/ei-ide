package ei.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.AppExecutorUtil;
import ei.db.console.BackupSql;
import ei.db.console.Backups;
import ei.db.console.ConsoleRun;
import ei.db.console.GuardDialog;
import ei.db.console.ResultView;
import ei.db.console.RunHistory;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 실행의 공통 입구(콘솔 · MyBatis 실행 · 로그 변환 · 표 데이터 보기). 화면 스레드에서 부른다.
 * 순서: 읽기 전용 접속의 변경 거절 → 위험 문장(WHERE 없는 UPDATE · DELETE, DROP · TRUNCATE)과 운영 접속의 변경은 확인창
 * → 미반영 변경이 있는 접속(Oracle · MySQL)의 DDL 은 함께 커밋된다는 확인 → 백그라운드에서 고치기 전 백업(Backups, UPDATE · DELETE) → 실행
 * → 결과 창에 표시 → 실행 기록(RunHistory)에 남김.
 * 진행 막대를 취소하면 DbSessions.cancel 로 드라이버에 취소를 보낸다.
 */
public final class SqlRunner {
  private SqlRunner() {}

  /** 진행 막대의 취소를 살피는 간격. JDBC 실행은 막혀 있어 진행 표시가 스스로 취소를 알 수 없다. */
  private static final long CANCEL_POLL_MS = 200;

  public static void run(Project project, DbConfig c, String sql, List<Object> binds) {
    run(project, c, sql, binds, null);
  }

  /**
   * onSuccess 는 실행이 오류 · 취소 없이 끝나 결과 창에 보인 뒤 화면 스레드에서 결과와 함께 불린다.
   * 읽기 전용 거절 · 확인창 취소 · 실행 오류 · 실행 취소면 불리지 않는다. null 이면 부르지 않는다.
   */
  public static void run(Project project, DbConfig c, String sql, List<Object> binds, Consumer<QueryResult> onSuccess) {
    run(project, c, sql, binds, onSuccess, BackupSql.Origin.CONSOLE);
  }

  /**
   * backup 은 고치기 전 백업 파일에 적을 종류. null 이면 백업하지 않는다(부르는 쪽이 이미 백업한 결과 표 「적용」).
   * 백업이 실패했거나 행이 한도를 넘어 사용자가 실행을 취소하면 실행 취소와 같게 끝난다(onSuccess 는 불리지 않는다).
   */
  public static void run(Project project, DbConfig c, String sql, List<Object> binds, Consumer<QueryResult> onSuccess, BackupSql.Origin backup) {
    boolean write = SqlGuard.isWrite(sql);
    if (write && c.readOnly) {
      Notify.error(project, EiDbBundle.message("runner.read.only", c.name));
      return;
    }
    String danger = SqlGuard.danger(sql);
    if ((danger != null || (c.production && write)) && !GuardDialog.confirm(project, c, sql, danger, SqlGuard.table(sql))) {
      return;
    }
    if (!ei.db.bench.LintGate.confirm(project, sql)) {
      ConsoleRun.stop(project, c, sql);
      return;
    }
    if (!confirmImplicitCommit(project, c, sql)) {
      ConsoleRun.stop(project, c, sql);
      return;
    }
    queue(project, c, sql, binds, DbSessions.get(project).maxRows(), EiDbBundle.message("runner.progress", c.name), backup, new Outcome() {
      @Override
      public void done(QueryResult result) {
        ResultView.show(project, c, result);
        if (onSuccess != null) onSuccess.accept(result);
      }

      @Override
      public void cancelled() { ResultView.showCancelled(project, c, sql); }

      @Override
      public void failed(Exception e) { ResultView.showError(project, c, sql, e); }
    });
  }

  /**
   * 결과 창의 「더 보기」: 이미 실행해 보인 조회 문장을 limit 건까지 다시 읽는다(위험 문장 확인은 이미 거쳤다).
   * 진행 막대에 읽은 행 수를 보이고 취소할 수 있다. 실행 기록에 남는다. onResult 는 성공했을 때 화면 스레드에서 불린다.
   * 실패는 알림으로, 취소는 아무것도 바꾸지 않는다.
   */
  public static void reread(Project project, DbConfig c, String sql, List<Object> binds, int limit, Consumer<QueryResult> onResult) {
    queue(project, c, sql, binds, limit, EiDbBundle.message("runner.reread.progress", c.name, limit), null, new Outcome() {
      @Override
      public void done(QueryResult result) { onResult.accept(result); }

      @Override
      public void cancelled() { }

      @Override
      public void failed(Exception e) { Notify.error(project, EiDbBundle.message("result.more.failed", c.name, ResultView.message(e))); }
    });
  }

  /** runAll 의 확인창에 보일 때 문장 사이에 넣는 글. */
  private static final String STATEMENT_SEPARATOR = ";\n";

  /**
   * 바인드 없는 문장 여럿을 확인 한 번으로 차례로 실행한다(탐색기 「주석 고치기」). 화면 스레드에서 부른다. 결과 탭은 만들지 않고 문장마다 실행 기록에 남긴다.
   * 확인은 run 과 같되 한 번씩만: 읽기 전용 접속의 변경 거절 → 위험 문장 · 운영 접속의 변경 확인(문장을 이어 붙여 보인다) → 실행 전 점검
   * → 미반영 변경이 있는 접속(Oracle · MySQL)의 DDL 확인. 거절 · 취소면 아무것도 실행하지 않고 false(onDone 은 불리지 않는다).
   * 앞 문장이 실패하거나 취소되면 멈춘다. onDone 은 화면 스레드에서 (성공한 문장 수, 멈춘 까닭 — 모두 성공이면 null)로 한 번 불린다.
   */
  public static boolean runAll(Project project, DbConfig c, List<String> sqls, java.util.function.BiConsumer<Integer, String> onDone) {
    if (sqls.isEmpty()) return false;
    boolean write = false;
    String danger = null;
    String ddl = null;
    for (String sql : sqls) {
      write |= SqlGuard.isWrite(sql);
      if (danger == null) danger = SqlGuard.danger(sql);
      if (ddl == null && SqlGuard.isDdl(sql)) ddl = sql;
    }
    if (write && c.readOnly) {
      Notify.error(project, EiDbBundle.message("runner.read.only", c.name));
      return false;
    }
    if ((danger != null || (c.production && write)) && !GuardDialog.confirm(project, c, String.join(STATEMENT_SEPARATOR, sqls), danger, null)) return false;
    for (String sql : sqls) {
      if (!ei.db.bench.LintGate.confirm(project, sql)) return false;
    }
    if (ddl != null && !confirmImplicitCommit(project, c, ddl)) return false;
    runNext(project, c, List.copyOf(sqls), 0, onDone);
    return true;
  }

  /** runAll 의 i 번째 문장. 끝까지 갔거나 멈추면 onDone. */
  private static void runNext(Project project, DbConfig c, List<String> sqls, int i, java.util.function.BiConsumer<Integer, String> onDone) {
    if (i == sqls.size()) {
      onDone.accept(i, null);
      return;
    }
    // 주석 문처럼 행을 바꾸지 않는 문장 묶음이라 고치기 전 백업은 하지 않는다(null).
    queue(project, c, sqls.get(i), List.of(), DbSessions.get(project).maxRows(), EiDbBundle.message("runner.progress", c.name), null, new Outcome() {
      @Override
      public void done(QueryResult result) { runNext(project, c, sqls, i + 1, onDone); }

      @Override
      public void cancelled() { onDone.accept(i, EiDbBundle.message("sessions.cancelled")); }

      @Override
      public void failed(Exception e) { onDone.accept(i, ResultView.message(e)); }
    });
  }

  /** 실행 결과를 받는 곳. 모두 화면 스레드에서(프로젝트가 닫혔으면 부르지 않는다). */
  private interface Outcome {
    void done(QueryResult result);
    void cancelled();
    void failed(Exception e);
  }

  /**
   * 백그라운드 실행(진행 막대 · 취소) → outcome → 실행 기록. limit 건까지 읽는다.
   * backup 이 null 이 아니면 실행 직전에 같은 작업 안에서 고치기 전 백업을 한다. 백업이 실행을 막으면 취소로 끝낸다.
   */
  private static void queue(Project project, DbConfig c, String sql, List<Object> binds, int limit, String title, BackupSql.Origin backup,
                            Outcome outcome) {
    new Task.Backgroundable(project, title, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        AtomicBoolean failed = new AtomicBoolean();
        AtomicReference<Statement> mine = new AtomicReference<>();
        ScheduledFuture<?> watch = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            () -> cancelIfAsked(project, c, mine, indicator, failed), CANCEL_POLL_MS, CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
        long start = System.currentTimeMillis();
        try {
          if (backup != null && !Backups.beforeRun(project, c, sql, binds, backup, indicator::isCanceled, mine)) {
            ApplicationManager.getApplication().invokeLater(outcome::cancelled, project.getDisposed());
            record(project, c, sql, binds, System.currentTimeMillis() - start, RunHistory.NO_COUNT, false);
            return;
          }
          QueryResult result = DbSessions.get(project).run(c, sql, binds, limit, indicator::isCanceled, mine,
              rows -> indicator.setText2(EiDbBundle.message("runner.rows.read", rows)));
          ApplicationManager.getApplication().invokeLater(() -> outcome.done(result), project.getDisposed());
          record(project, c, sql, binds, result.millis(), result.isQuery() ? result.rows().size() : result.updateCount(), true);
        } catch (DbSessions.CancelledException e) {
          ApplicationManager.getApplication().invokeLater(outcome::cancelled, project.getDisposed());
          record(project, c, sql, binds, System.currentTimeMillis() - start, RunHistory.NO_COUNT, false);
        } catch (Exception e) {
          ApplicationManager.getApplication().invokeLater(() -> outcome.failed(e), project.getDisposed());
          record(project, c, sql, binds, System.currentTimeMillis() - start, RunHistory.NO_COUNT, false);
        } finally {
          watch.cancel(false);
        }
      }
    }.queue();
  }

  /** Oracle · MySQL 은 DDL 앞에서 미반영 변경을 커밋한다. 그런 변경이 있으면 실행해도 되는지 묻는다. PostgreSQL 은 DDL 도 트랜잭션 안이라 묻지 않는다. */
  private static boolean confirmImplicitCommit(Project project, DbConfig c, String sql) {
    int pending = DbSessions.get(project).pending(c.id);
    if (pending == 0 || c.kind == DbKind.POSTGRES || !SqlGuard.isDdl(sql)) return true;
    return Messages.showOkCancelDialog(project, EiDbBundle.message("runner.ddl.confirm", pending), EiDbBundle.message("runner.ddl.confirm.title"),
        EiDbBundle.message("common.run"), EiDbBundle.message("common.cancel"), Messages.getWarningIcon()) == Messages.OK;
  }

  /**
   * 취소가 눌린 뒤에는 작업이 끝날 때까지 간격마다 다시 보낸다. 실행 직전에 보낸 취소를 드라이버가 무시해도 실행 중에 다시 닿게 하려는 것이다.
   * 실행 시작 전의 취소는 DbSessions.run 의 stop 이 막는다. 보내기에 실패하면 알림이 쌓이지 않게 그만 보낸다.
   * 이 실행이 등록한 문장(mine)만 멈춘다. 같은 접속에서 다른 콘솔이 돌리는 문장은 건드리지 않는다.
   */
  private static void cancelIfAsked(Project project, DbConfig c, AtomicReference<Statement> mine, ProgressIndicator indicator, AtomicBoolean failed) {
    if (!indicator.isCanceled() || failed.get()) return;
    try {
      DbSessions.get(project).cancel(c.id, mine.get());
    } catch (SQLException e) {
      failed.set(true);
      Notify.error(project, EiDbBundle.message("runner.cancel.failed", c.name, e.getMessage()));
    }
  }

  /** 그 접속에서 실행 중인 문장을 멈춘다(콘솔 「중지」 · 진행 막대 취소). 화면 스레드가 아닌 곳에서 부른다. 실패는 알리고 거짓. */
  public static boolean cancel(Project project, DbConfig c) {
    try {
      DbSessions.get(project).cancel(c.id);
      return true;
    } catch (SQLException e) {
      Notify.error(project, EiDbBundle.message("runner.cancel.failed", c.name, e.getMessage()));
      return false;
    }
  }

  /** 결과를 보인 뒤 부른다(파일 쓰기가 결과 표시를 늦추지 않게). */
  private static void record(Project project, DbConfig c, String sql, List<Object> binds, long millis, int count, boolean ok) {
    try {
      RunHistory.get().add(new RunHistory.Entry(System.currentTimeMillis(), c.id, c.name, millis, count, ok, sql.strip(),
          binds == null ? List.of() : binds));
    } catch (IOException e) {
      Notify.error(project, EiDbBundle.message("runner.history.save.failed", e.getMessage()));
    }
  }
}
