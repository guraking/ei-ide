package ei.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.AppExecutorUtil;
import ei.db.console.ConsoleRun;
import ei.db.console.GuardDialog;
import ei.db.console.ResultView;
import ei.db.console.RunHistory;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 실행의 공통 입구(콘솔 · MyBatis 실행 · 로그 변환 · 표 데이터 보기). 화면 스레드에서 부른다.
 * 순서: 읽기 전용 접속의 변경 거절 → 위험 문장(WHERE 없는 UPDATE · DELETE, DROP · TRUNCATE)과 운영 접속의 변경은 확인창
 * → 미반영 변경이 있는 접속(Oracle · MySQL)의 DDL 은 함께 커밋된다는 확인 → 백그라운드 실행 → 결과 창에 표시 → 실행 기록(RunHistory)에 남김.
 * 진행 막대를 취소하면 DbSessions.cancel 로 드라이버에 취소를 보낸다.
 */
public final class SqlRunner {
  private SqlRunner() {}

  /** 진행 막대의 취소를 살피는 간격. JDBC 실행은 막혀 있어 진행 표시가 스스로 취소를 알 수 없다. */
  private static final long CANCEL_POLL_MS = 200;

  public static void run(Project project, DbConfig c, String sql, List<Object> binds) {
    boolean write = SqlGuard.isWrite(sql);
    if (write && c.readOnly) {
      Notify.error(project, EiDbBundle.message("runner.read.only", c.name));
      return;
    }
    String danger = SqlGuard.danger(sql);
    if ((danger != null || (c.production && write)) && !GuardDialog.confirm(project, c, sql, danger, SqlGuard.table(sql))) {
      return;
    }
    if (!confirmImplicitCommit(project, c, sql)) {
      ConsoleRun.stop(project, c, sql);
      return;
    }
    new Task.Backgroundable(project, EiDbBundle.message("runner.progress", c.name), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        AtomicBoolean failed = new AtomicBoolean();
        ScheduledFuture<?> watch = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            () -> cancelIfAsked(project, c, indicator, failed), CANCEL_POLL_MS, CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
        long start = System.currentTimeMillis();
        try {
          QueryResult result = DbSessions.get(project).run(c, sql, binds, indicator::isCanceled);
          ApplicationManager.getApplication().invokeLater(() -> ResultView.show(project, c, result));
          record(project, c, sql, binds, result.millis(), result.isQuery() ? result.rows().size() : result.updateCount(), true);
        } catch (DbSessions.CancelledException e) {
          ApplicationManager.getApplication().invokeLater(() -> ResultView.showCancelled(project, c, sql));
          record(project, c, sql, binds, System.currentTimeMillis() - start, RunHistory.NO_COUNT, false);
        } catch (Exception e) {
          ApplicationManager.getApplication().invokeLater(() -> ResultView.showError(project, c, sql, e));
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
   */
  private static void cancelIfAsked(Project project, DbConfig c, ProgressIndicator indicator, AtomicBoolean failed) {
    if (indicator.isCanceled() && !failed.get() && !cancel(project, c)) failed.set(true);
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
