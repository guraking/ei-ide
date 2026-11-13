package ei.db.console;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.util.concurrency.AppExecutorUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.gen.Names;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 「엑셀 붙여 넣어 행 넣기」 · 「테스트 데이터 만들기」가 함께 쓰는 길: 표 정보 읽기, INSERT 묶음을 콘솔 · 파일 · DB 로 보내기.
 * 바로 넣기는 읽기 전용 접속을 거절하고, 운영 접속은 GuardDialog(표 이름 입력)로, 그 밖은 표 · 행 수 확인창으로 확인한 뒤
 * DbSessions.insertBatch 로 같은 접속 세션에 넣는다(자동 커밋 · 미반영 건수 규칙은 그쪽이 지킨다). 메서드는 화면 스레드에서 부른다.
 */
final class RowInserter {
  private RowInserter() {}

  /** 진행 막대의 취소를 살피는 간격. */
  private static final long CANCEL_POLL_MS = 200;
  private static final String SQL_EXT = "sql";
  /** COLUMN_DEF 가 이 글이면 기본값이 없는 것으로 본다(Oracle 의 DEFAULT NULL). */
  private static final String NULL_DEFAULT = "NULL";

  /** 표 하나의 칼럼(PK · FK 표시 포함) · 기본값(칼럼 이름 → COLUMN_DEF) · 외래키. */
  record Table(DbConfig c, String schema, String name, List<Meta.Column> columns, Map<String, String> defaults, List<Meta.ForeignKey> fks) {
    String sqlName() { return Names.sql(c.kind, schema, name); }

    Set<String> withDefault() { return defaults.keySet(); }
  }

  /** 표 정보를 뒤에서 읽어 then 에 넘긴다. 읽지 못했거나 칼럼이 없으면 알리고 then 은 부르지 않는다. */
  static void load(Project project, DbConfig c, String schema, String table, Consumer<Table> then) {
    DbSessions sessions = DbSessions.get(project);
    new Task.Backgroundable(project, EiDbBundle.message("rows.loading", table), false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        Table read;
        try {
          Connection conn = sessions.connection(c);
          Set<String> pks = Meta.primaryKeys(conn, c, schema, table);
          List<Meta.ForeignKey> fks = Meta.importedKeys(conn, c, schema, table);
          List<Meta.Column> columns = new ArrayList<>();
          Map<String, String> defaults = new HashMap<>();
          for (Meta.ColumnRow r : Meta.columnRows(conn, c, schema, table, true)) {
            Meta.Column col = r.column();
            boolean fk = false;
            for (Meta.ForeignKey k : fks) fk |= k.fkColumn().equals(col.name());
            columns.add(new Meta.Column(col.name(), col.type(), col.size(), col.scale(), pks.contains(col.name()), fk, col.nullable(), col.comment()));
            if (r.def() != null && !NULL_DEFAULT.equalsIgnoreCase(r.def())) defaults.put(col.name(), r.def());
          }
          read = new Table(c, schema, table, columns, defaults, fks);
        } catch (SQLException | RuntimeException e) {
          Notify.error(project, EiDbBundle.message("rows.load.failed", table, e.getMessage()));
          return;
        } finally {
          sessions.endReadTransaction(c);
        }
        if (read.columns().isEmpty()) {
          Notify.error(project, EiDbBundle.message("rows.no.columns", table));
          return;
        }
        ApplicationManager.getApplication().invokeLater(() -> then.accept(read), project.getDisposed());
      }
    }.queue();
  }

  /**
   * INSERT 문을 새 콘솔에 넣는다(실행하지 않는다). 행이 InsertRows.CONSOLE_MAX_ROWS 를 넘으면 넣지 않고 파일로 저장할지 묻는다.
   * 콘솔에 넣었으면 참.
   */
  static boolean toConsole(Project project, DbConfig c, InsertRows.Batch batch) {
    int n = batch.rows().size();
    if (n == 0) return false;
    if (n > InsertRows.CONSOLE_MAX_ROWS) {
      int answer = Messages.showYesNoDialog(project, EiDbBundle.message("rows.console.too.many", n, InsertRows.CONSOLE_MAX_ROWS),
          EiDbBundle.message("rows.to.console"), Messages.getQuestionIcon());
      if (answer == Messages.YES) saveFile(project, batch);
      return false;
    }
    ConsoleOpener.openNew(project, c);
    ConsoleOpener.open(project, c, InsertRows.text(batch));
    return true;
  }

  /** 저장할 곳을 물어 INSERT 문을 UTF-8 .sql 파일로 쓴다(뒤에서, 진행 막대). 실패하면 알린다. */
  static void saveFile(Project project, InsertRows.Batch batch) {
    if (batch.rows().isEmpty()) return;
    FileSaverDescriptor desc = new FileSaverDescriptor(EiDbBundle.message("rows.save.file"), EiDbBundle.message("rows.save.description"), SQL_EXT);
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(desc, project).save(EiDbBundle.message("rows.save.name") + "." + SQL_EXT);
    if (target == null) return;
    Path path = target.getFile().toPath();
    new Task.Backgroundable(project, EiDbBundle.message("rows.save.progress", path.getFileName()), false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(false);
        int total = batch.rows().size();
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
          for (int r = 0; r < total; r++) {
            if (r > 0) w.write('\n');
            w.write(InsertRows.statement(batch, r));
            indicator.setFraction((double) (r + 1) / total);
          }
        } catch (IOException | RuntimeException e) {
          Notify.error(project, EiDbBundle.message("rows.save.failed", path, e.getMessage()));
          return;
        }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        Notify.info(project, EiDbBundle.message("rows.save.done", total, path));
      }
    }.queue();
  }

  /**
   * 확인받고 batch 를 표에 넣는다. done 은 끝났을 때(거절 · 확인 취소 포함) 화면 스레드에서 한 번 불리며, 모든 행이 들어갔을 때만 참을 받는다.
   * 실패하면 몇 번째 행인지와 DB 오류 글을, 실패 · 취소 때 이미 들어간 행 수와 커밋 여부를 알린다. 실행 기록에 남긴다.
   */
  static void insert(Project project, DbConfig c, String table, InsertRows.Batch batch, Consumer<Boolean> done) {
    DbSessions sessions = DbSessions.get(project);
    int total = batch.rows().size();
    if (total == 0) {
      done.accept(false);
      return;
    }
    if (c.readOnly) {
      Notify.error(project, EiDbBundle.message("runner.read.only", c.name));
      done.accept(false);
      return;
    }
    String sql;
    try {
      sql = InsertRows.template(batch);
    } catch (IllegalArgumentException e) {
      Messages.showErrorDialog(project, e.getMessage(), EiDbBundle.message("rows.insert.now"));
      done.accept(false);
      return;
    }
    if (!confirm(project, c, table, sql, total, sessions.autoCommit(c))) {
      done.accept(false);
      return;
    }
    List<List<Object>> binds = InsertRows.binds(batch);
    new Task.Backgroundable(project, EiDbBundle.message("rows.insert.progress", c.name, table), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(false);
        AtomicReference<Statement> mine = new AtomicReference<>();
        ScheduledFuture<?> watch = watchCancel(project, c, mine, indicator);
        long start = System.currentTimeMillis();
        try {
          DbSessions.BatchResult r = sessions.insertBatch(c, sql, binds, indicator::isCanceled, mine, n -> {
            indicator.setFraction((double) n / total);
            indicator.setText2(EiDbBundle.message("rows.insert.count", n, total));
          });
          boolean ok = r.error() == null && !r.cancelled() && r.inserted() == total;
          record(project, c, sql, System.currentTimeMillis() - start, r.inserted(), ok);
          ApplicationManager.getApplication().invokeLater(() -> {
            report(project, c, table, total, r, sessions.pending(c.id));
            done.accept(ok);
          }, project.getDisposed());
        } catch (SQLException | RuntimeException e) {
          record(project, c, sql, System.currentTimeMillis() - start, 0, false);
          ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, EiDbBundle.message("rows.insert.not.started", table, e.getMessage()), EiDbBundle.message("rows.insert.now"));
            done.accept(false);
          }, project.getDisposed());
        } finally {
          watch.cancel(false);
        }
      }
    }.queue();
  }

  /** 운영 접속은 GuardDialog(표 이름을 쳐야 실행), 그 밖은 표 · 행 수 · 자동 커밋 상태를 보이는 확인창. */
  private static boolean confirm(Project project, DbConfig c, String table, String sql, int total, boolean autoCommit) {
    if (c.production) return GuardDialog.confirm(project, c, sql + "\n" + EiDbBundle.message("rows.insert.guard.rows", total), null, table);
    String text = EiDbBundle.message(autoCommit ? "rows.insert.confirm.auto" : "rows.insert.confirm.manual", c.name, table, total);
    return Messages.showOkCancelDialog(project, text, EiDbBundle.message("rows.insert.now"), EiDbBundle.message("common.insert"),
        EiDbBundle.message("common.cancel"), Messages.getQuestionIcon()) == Messages.OK;
  }

  /** 결과 알림. 다 들어갔으면 알림 풍선, 실패 · 취소는 놓치지 않게 대화 상자. */
  private static void report(Project project, DbConfig c, String table, int total, DbSessions.BatchResult r, int pending) {
    String state = EiDbBundle.message(r.autoCommit() ? "rows.insert.state.auto" : "rows.insert.state.manual", r.inserted(), pending);
    if (r.inserted() > 0 && (r.error() != null || r.cancelled())) state += "\n" + EiDbBundle.message("rows.insert.retry.hint", r.inserted());
    String title = EiDbBundle.message("rows.insert.now");
    if (r.error() != null) {
      String where = r.failedRow() == DbSessions.NO_ROW
          ? EiDbBundle.message("rows.insert.failed", table, r.error().getMessage())
          : EiDbBundle.message("rows.insert.failed.row", table, r.failedRow() + 1, total, r.error().getMessage());
      Messages.showErrorDialog(project, where + "\n\n" + state, title);
    } else if (r.cancelled()) {
      Messages.showWarningDialog(project, EiDbBundle.message("rows.insert.cancelled", table, total) + "\n\n" + state, title);
    } else {
      Notify.info(project, EiDbBundle.message("rows.insert.done", c.name, table) + " " + state);
    }
  }

  /**
   * 조회 한 문장을 뒤에서 실행해(진행 막대 · 취소) limit 행까지 읽고 then 에 넘긴다. 실패하면 대화 상자로 알리고, 취소면 아무것도 하지 않는다.
   * 「현재 최댓값 다음부터」 · 「참조 표에서 실제 값 가져오기」가 쓴다(누를 때만).
   */
  static void query(Project project, DbConfig c, String title, String sql, int limit, Consumer<QueryResult> then) {
    DbSessions sessions = DbSessions.get(project);
    new Task.Backgroundable(project, title, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        AtomicReference<Statement> mine = new AtomicReference<>();
        ScheduledFuture<?> watch = watchCancel(project, c, mine, indicator);
        try {
          QueryResult result = sessions.run(c, sql, null, limit, indicator::isCanceled, mine, null);
          ApplicationManager.getApplication().invokeLater(() -> then.accept(result), project.getDisposed());
        } catch (DbSessions.CancelledException e) {
          // 사용자가 취소했다. 알릴 것이 없다.
        } catch (SQLException | RuntimeException e) {
          ApplicationManager.getApplication().invokeLater(
              () -> Messages.showErrorDialog(project, EiDbBundle.message("rows.query.failed", e.getMessage()), title), project.getDisposed());
        } finally {
          watch.cancel(false);
        }
      }
    }.queue();
  }

  /**
   * 진행 막대가 취소되면 이 실행이 등록한 문장(mine)에 취소를 한 번 보낸다(문장이 아직 등록되지 않았으면 등록될 때까지 살핀다).
   * 되풀이해 보내지 않는다: 늦게 닿은 취소가 배치의 되돌리기 · 커밋을 끊지 않게 한다. 보내기에 실패하면 알린다.
   */
  private static ScheduledFuture<?> watchCancel(Project project, DbConfig c, AtomicReference<Statement> mine, ProgressIndicator indicator) {
    AtomicBoolean sent = new AtomicBoolean();
    return AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
      if (!indicator.isCanceled() || sent.get()) return;
      try {
        if (DbSessions.get(project).cancel(c.id, mine.get())) sent.set(true);
      } catch (SQLException | RuntimeException e) {
        sent.set(true);
        Notify.error(project, EiDbBundle.message("runner.cancel.failed", c.name, e.getMessage()));
      }
    }, CANCEL_POLL_MS, CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
  }

  private static void record(Project project, DbConfig c, String sql, long millis, int count, boolean ok) {
    try {
      RunHistory.get().add(new RunHistory.Entry(System.currentTimeMillis(), c.id, c.name, millis, count, ok, sql, List.of()));
    } catch (IOException | RuntimeException e) {
      Notify.error(project, EiDbBundle.message("runner.history.save.failed", e.getMessage()));
    }
  }
}
