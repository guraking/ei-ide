package ei.db.console;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.QueryResult;
import org.jetbrains.annotations.Nls;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 「고치기 전 백업」: UPDATE · DELETE 를 실행하기 직전에 바뀔 행을 같은 접속(같은 세션)에서 읽어 되돌리는 SQL 을 파일로 남긴다.
 * 파일은 IDE 설정 폴더의 ei-db/backup/접속 이름/yyyyMMdd-HHmmss_표.sql (UTF-8). 문장 읽기 · 되돌리는 SQL · 머리 형식은 BackupSql.
 * 백업하지 못하는 문장은 알리고 실행은 그대로 둔다. 읽기 · 쓰기에 실패했거나 행이 한도를 넘으면 백업 없이 실행할지 묻는다(묻지 않고 실행하지 않는다).
 * 보존 일수가 지난 파일은 그 접속 폴더에 새 백업을 쓸 때 지운다.
 */
public final class Backups {
  private Backups() {}

  private static final Logger LOG = Logger.getInstance(Backups.class);
  private static final String KEY_ENABLED = "ei.db.backup.enabled";
  private static final String KEY_ROW_LIMIT = "ei.db.backup.rowLimit";
  private static final String KEY_KEEP_DAYS = "ei.db.backup.keepDays";
  static final int DEFAULT_ROW_LIMIT = 10_000;
  static final int DEFAULT_KEEP_DAYS = 30;
  /** 보존 일수 0 은 지우지 않는다. */
  static final int KEEP_FOREVER = 0;
  private static final int MAX_KEEP_DAYS = 3650;
  /** 같은 초 · 같은 표의 파일 이름에 붙이는 번호의 끝. 넘으면 쓰기 실패로 본다. */
  private static final int MAX_SAME_SECOND = 1000;
  /** 머리를 찾으려고 읽는 줄 수의 끝(원래 SQL 이 긴 파일도 머리는 이 안에 끝난다). 넘으면 우리 파일이 아닌 것으로 본다. */
  private static final int MAX_HEADER_LINES = 5000;
  /** 머리의 원래 SQL 에 적는 결과 표 문장 수. 나머지는 「외 N개」로 줄인다. */
  private static final int GRID_SQL_SHOWN = 3;

  static boolean enabled() {
    return PropertiesComponent.getInstance().getBoolean(KEY_ENABLED, true);
  }

  /** 백업할 행 수 한도(1 ~ DbSessions.MAX_ROW_LIMIT). 넘으면 백업 없이 실행할지 묻는다. */
  static int rowLimit() {
    int n = PropertiesComponent.getInstance().getInt(KEY_ROW_LIMIT, DEFAULT_ROW_LIMIT);
    return Math.max(1, Math.min(n, DbSessions.MAX_ROW_LIMIT));
  }

  static int keepDays() {
    int n = PropertiesComponent.getInstance().getInt(KEY_KEEP_DAYS, DEFAULT_KEEP_DAYS);
    return Math.max(KEEP_FOREVER, Math.min(n, MAX_KEEP_DAYS));
  }

  /** 그 접속의 백업 폴더(없을 수 있다). */
  static Path dir(DbConfig c) {
    return PathManager.getConfigDir().resolve("ei-db").resolve("backup").resolve(BackupSql.safeName(c.name));
  }

  /**
   * SqlRunner 가 확인창을 모두 지난 뒤 실행 직전에, 실행과 같은 백그라운드 작업 안에서 부른다(화면 스레드에서 부르지 않는다).
   * sql 이 표 하나짜리 UPDATE · DELETE 면 같은 접속에서 같은 WHERE 로 읽어 파일을 남긴다. binds 중 WHERE 쪽 값만 쓴다.
   * 돌려주는 값이 거짓이면 실행하지 않는다(사용자가 취소했거나 백업 조회 중 실행을 멈춤).
   * 백업 대상이 아닌 문장 · 0행 · 백업 끔은 아무것도 하지 않고 참. stop · registered 는 DbSessions.run 과 같다(백업 조회도 멈출 수 있다).
   */
  public static boolean beforeRun(Project project, DbConfig c, String sql, List<Object> binds, BackupSql.Origin origin,
                                  BooleanSupplier stop, AtomicReference<Statement> registered) {
    if (!enabled()) return true;
    int bindCount = binds == null ? 0 : binds.size();
    BackupSql.Parsed parsed = BackupSql.parse(sql, bindCount, c.kind);
    if (parsed.none()) return true;
    if (parsed.target() == null) {
      Notify.warning(project, EiDbBundle.message("backup.skipped", parsed.unsupported()));
      return true;
    }
    BackupSql.Target t = parsed.target();
    int limit = rowLimit();
    try {
      List<Object> whereBinds = bindCount == 0 ? List.of() : new ArrayList<>(binds.subList(t.bindsBefore(), t.bindsBefore() + t.whereBinds()));
      DbSessions sessions = DbSessions.get(project);
      QueryResult rows = sessions.run(c, BackupSql.select(t), whereBinds, limit, stop, registered, null);
      if (rows.truncated()) return ask(project, EiDbBundle.message("backup.too.many", t.table(), limit));
      if (rows.rows().isEmpty()) return true;
      Set<String> pks = t.delete() ? Set.of() : primaryKeys(sessions, c, t);
      BackupSql.Restore restore = BackupSql.restore(t.delete(), t.table(), rows.columns(), rows.columnTypes(), rows.rows(), pks, c.kind);
      write(c, origin, t.table(), sql, rows.rows().size(), restore);
      return true;
    } catch (DbSessions.CancelledException e) {
      return false;
    } catch (SQLException | IOException | RuntimeException e) {
      return ask(project, EiDbBundle.message("backup.failed.ask", reason(e)));
    }
  }

  /** 대문자 PK 칼럼 이름. 표를 찾지 못하면(뷰 · 동의어 · 다른 대소문자) 빈 집합이라 INSERT 로 남는다. */
  private static Set<String> primaryKeys(DbSessions sessions, DbConfig c, BackupSql.Target t) throws SQLException {
    Connection conn = sessions.connection(c);
    try {
      String schema = t.schema() == null ? Meta.schemaOf(c) : Meta.storedName(c, t.schema());
      Set<String> out = new HashSet<>();
      for (String pk : Meta.primaryKeys(conn, c, schema, Meta.storedName(c, t.name()))) out.add(pk.toUpperCase(Locale.ROOT));
      return out;
    } finally {
      sessions.endReadTransaction(c);
    }
  }

  /** 결과 표가 남길 백업 하나. sqls 는 실행할 문장들(리터럴 꼴, 머리에는 앞 몇 개만 적는다), rows 는 행 수, restore 는 되돌리는 문장들. */
  record Draft(BackupSql.Origin origin, String table, List<String> sqls, int rows, BackupSql.Restore restore) {}

  /**
   * 결과 표의 「적용」: drafts 를 파일로 남긴 뒤 then 을 화면 스레드에서 부른다. 화면 스레드에서 부른다.
   * 백업이 꺼져 있거나 남길 것이 없으면 바로 then. 쓰기에 실패하면 백업 없이 실행할지 묻고, 취소하면 then 을 부르지 않는다.
   */
  static void saveThen(Project project, DbConfig c, List<Draft> drafts, Runnable then) {
    if (!enabled() || drafts.isEmpty()) {
      then.run();
      return;
    }
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      boolean go;
      try {
        for (Draft d : drafts) write(c, d.origin(), d.table(), gridSql(d.sqls()), d.rows(), d.restore());
        go = true;
      } catch (IOException | RuntimeException e) {
        go = ask(project, EiDbBundle.message("backup.failed.ask", reason(e)));
      }
      if (go) ApplicationManager.getApplication().invokeLater(then, project.getDisposed());
    });
  }

  private static String gridSql(List<String> sqls) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < sqls.size() && i < GRID_SQL_SHOWN; i++) sb.append(sqls.get(i).strip()).append('\n');
    if (sqls.size() > GRID_SQL_SHOWN) sb.append(EiDbBundle.message("backup.sql.more", sqls.size() - GRID_SQL_SHOWN));
    return sb.toString();
  }

  /** 백업 없이 실행할지 묻는다. 어느 스레드에서 불러도 된다. 프로젝트가 닫혔으면 거짓. */
  static boolean ask(Project project, String message) {
    AtomicBoolean yes = new AtomicBoolean();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      if (project.isDisposed()) return;
      yes.set(Messages.showYesNoDialog(project, message, EiDbBundle.message("backup.ask.title"), EiDbBundle.message("backup.ask.run"),
          EiDbBundle.message("common.cancel"), Messages.getWarningIcon()) == Messages.YES);
    });
    return yes.get();
  }

  private static String reason(Exception e) {
    return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage().strip();
  }

  /** 파일 하나를 새로 쓴다(있는 파일은 덮지 않고 번호를 붙인다). 쓴 뒤 그 폴더의 오래된 파일을 지운다. */
  private static synchronized Path write(DbConfig c, BackupSql.Origin origin, String table, String sql, int rows, BackupSql.Restore restore)
      throws IOException {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Path dir = dir(c);
    Files.createDirectories(dir);
    String text = BackupSql.format(new BackupSql.Header(now, c.name, origin, table, rows, restore.excluded(), restore.mode(), sql)) + restore.body();
    for (int nth = 1; nth <= MAX_SAME_SECOND; nth++) {
      Path file = dir.resolve(BackupSql.fileName(now, table, nth));
      try {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException e) {
        continue;
      }
      prune(dir, keepDays());
      return file;
    }
    throw new IOException(EiDbBundle.message("backup.name.exhausted", dir));
  }

  /** 고친 시각이 days 일보다 오래된 .sql 파일을 지운다. 지우지 못한 파일은 다음 백업 때 다시 지우므로 기록만 남긴다. */
  private static void prune(Path dir, int days) {
    if (days == KEEP_FOREVER) return;
    Instant cut = Instant.now().minus(Duration.ofDays(days));
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + BackupSql.EXTENSION)) {
      for (Path f : files) {
        if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toInstant().isBefore(cut)) Files.delete(f);
      }
    } catch (IOException e) {
      LOG.info("오래된 백업 파일을 지우지 못했습니다: " + e.getMessage());
    }
  }

  /** 목록 한 줄. header 가 null 이면 우리 머리가 없는 파일이다(이름만 보인다). */
  record Entry(Path file, BackupSql.Header header) {}

  /** 그 접속 폴더의 백업 파일을 새것부터. 폴더가 없으면 빈 목록. 머리만 읽는다. 화면 스레드에서 부르지 않는다. */
  static List<Entry> list(DbConfig c) throws IOException {
    Path dir = dir(c);
    List<Entry> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) return out;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + BackupSql.EXTENSION)) {
      for (Path f : files) {
        if (Files.isRegularFile(f)) out.add(new Entry(f, readHeader(f)));
      }
    }
    // 파일 이름이 시각으로 시작하므로 이름 역순이 새것부터다.
    out.sort((a, b) -> b.file().getFileName().toString().compareTo(a.file().getFileName().toString()));
    return out;
  }

  private static BackupSql.Header readHeader(Path file) {
    List<String> lines = new ArrayList<>();
    try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while (lines.size() < MAX_HEADER_LINES && (line = in.readLine()) != null) {
        lines.add(line);
        if (line.equals(BackupSql.END)) break;
      }
    } catch (IOException e) {
      // 읽지 못한 파일(UTF-8 이 아닌 글 등)도 목록에는 이름으로 보이고, 고르면 미리보기가 오류를 알린다.
      return null;
    }
    return BackupSql.parseHeader(lines);
  }

  /** 설정 → 도구 → 고치기 전 백업. */
  public static final class Page implements Configurable {
    private JBCheckBox on;
    private JBIntSpinner limit;
    private JBIntSpinner days;

    @Override
    public @Nls String getDisplayName() { return EiDbBundle.message("backup.settings.title"); }

    @Override
    public JComponent createComponent() {
      on = new JBCheckBox(EiDbBundle.message("backup.settings.enabled"));
      limit = new JBIntSpinner(DEFAULT_ROW_LIMIT, 1, DbSessions.MAX_ROW_LIMIT);
      days = new JBIntSpinner(DEFAULT_KEEP_DAYS, KEEP_FOREVER, MAX_KEEP_DAYS);
      reset();
      JBLabel where = new JBLabel(EiDbBundle.message("backup.settings.where", PathManager.getConfigDir().resolve("ei-db").resolve("backup")),
          UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
      where.setCopyable(true);
      return FormBuilder.createFormBuilder()
          .addComponent(on)
          .addLabeledComponent(EiDbBundle.message("backup.settings.limit"), limit)
          .addTooltip(EiDbBundle.message("backup.settings.limit.hint"))
          .addLabeledComponent(EiDbBundle.message("backup.settings.days"), days)
          .addTooltip(EiDbBundle.message("backup.settings.days.hint"))
          .addComponent(where)
          .addComponentFillVertically(new JPanel(), 0)
          .getPanel();
    }

    @Override
    public boolean isModified() {
      return on.isSelected() != enabled() || limit.getNumber() != rowLimit() || days.getNumber() != keepDays();
    }

    @Override
    public void apply() {
      PropertiesComponent p = PropertiesComponent.getInstance();
      p.setValue(KEY_ENABLED, on.isSelected(), true);
      p.setValue(KEY_ROW_LIMIT, limit.getNumber(), DEFAULT_ROW_LIMIT);
      p.setValue(KEY_KEEP_DAYS, days.getNumber(), DEFAULT_KEEP_DAYS);
    }

    @Override
    public void reset() {
      on.setSelected(enabled());
      limit.setNumber(rowLimit());
      days.setNumber(keepDays());
    }
  }
}
