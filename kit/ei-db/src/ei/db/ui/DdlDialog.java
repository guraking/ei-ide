package ei.db.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.FileTypes;
import ei.db.Notify;
import ei.db.console.ConsoleOpener;
import ei.db.gen.Ui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 탐색기 「DDL 보기」: 고른 표 · 뷰의 CREATE 문을 차례로 이어 읽기 전용 편집기에 보인다. 열면 백그라운드에서 읽는다(DdlRead).
 * 표 하나를 못 읽으면 그 자리에 `--` 한 줄을 남기고 다음 표로 간다. 접속부터 실패하면 머리줄에 까닭을 보인다.
 * 「복사」 · 「콘솔로 보내기」(실행하지 않는다). 「저장 옵션 포함」(Oracle 만 보인다)을 바꾸면 다시 읽는다.
 * 창은 모달이 아니라 열어 둔 채 탐색기 · 콘솔을 쓸 수 있다. 창을 닫으면 남은 표는 읽지 않는다.
 */
final class DdlDialog extends DialogWrapper {

  /** 처음 열 때 화면 폭 · 높이에 대한 창 크기 비율. 사용자가 바꾼 크기는 IntelliJ 가 기억한다. */
  private static final double FIRST_WIDTH_RATIO = 0.5;
  private static final double FIRST_HEIGHT_RATIO = 0.65;
  private static final String DIMENSION_KEY = "ei.db.ui.DdlDialog";
  /** 머리줄 · 편집기 사이 간격(배율 전). */
  private static final int GAP = 6;
  private static final String NAME_SEPARATOR = ", ";

  private final Project project;
  private final DbConfig c;
  private final String schema;
  private final List<String> tables;
  private final EditorTextField editor;
  private final JBLabel status = new JBLabel(" ");
  private final JBCheckBox storage = new JBCheckBox(EiDbBundle.message("ddl.storage"));
  private final Gauge gauge = Gauge.create(EiDbBundle.message("explorer.loading"));
  /** 늦게 온 이전 읽기 결과를 버리고, 창이 닫히면 읽기를 멈추게 하는 번호. */
  private final AtomicInteger generation = new AtomicInteger();
  /** 읽기를 시작한 때의 화면 상태. 결과는 이 상태로 돌려보낸다(편집기 글을 바꾸므로 ModalityState.any() 는 쓸 수 없다). */
  private ModalityState modality = ModalityState.nonModal();
  private final Action copyAction = new DialogWrapperAction(EiDbBundle.message("common.copy")) {
    @Override
    protected void doAction(ActionEvent e) {
      CopyPasteManager.copyTextToClipboard(editor.getText());
      status(EiDbBundle.message("ddl.copied"), false);
    }
  };
  private final Action consoleAction = new DialogWrapperAction(EiDbBundle.message("querypick.send")) {
    @Override
    protected void doAction(ActionEvent e) {
      ConsoleOpener.open(project, c, editor.getText());
    }
  };

  /** tables 는 같은 접속 · 스키마의 표 · 뷰 이름(메타 그대로). 화면 스레드에서 부른다. */
  static void show(Project project, DbConfig c, String schema, List<String> tables) {
    new DdlDialog(project, c, schema, tables).show();
  }

  private DdlDialog(Project project, DbConfig c, String schema, List<String> tables) {
    super(project, false);
    this.project = project;
    this.c = c;
    this.schema = schema;
    this.tables = List.copyOf(tables);
    editor = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, FileTypes.byExtension("sql"), true, false);
    editor.addSettingsProvider(e -> {
      e.setVerticalScrollbarVisible(true);
      e.setHorizontalScrollbarVisible(true);
      e.getSettings().setLineNumbersShown(true);
    });
    setModal(false);
    setTitle(tables.size() == 1 ? EiDbBundle.message("ddl.title", c.name, schema + "." + tables.get(0))
                                : EiDbBundle.message("ddl.title.many", c.name, schema, tables.size()));
    setCancelButtonText(EiDbBundle.message("common.close"));
    init();
    storage.setVisible(c.kind == DbKind.ORACLE);
    storage.addActionListener(e -> load());
    load();
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() { return Ui.firstSize(FIRST_WIDTH_RATIO, FIRST_HEIGHT_RATIO); }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {copyAction, consoleAction, getCancelAction()};
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel head = new JPanel(new BorderLayout(JBUI.scale(GAP), 0));
    head.add(status, BorderLayout.CENTER);
    head.add(storage, BorderLayout.EAST);
    head.add(gauge.line(), BorderLayout.SOUTH);
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    panel.add(head, BorderLayout.NORTH);
    panel.add(editor, BorderLayout.CENTER);
    return panel;
  }

  @Override
  protected void dispose() {
    generation.incrementAndGet();
    super.dispose();
  }

  /** 한 번 읽은 결과. failed 는 못 읽은 표, assembled 는 메타데이터로 조립한 표, reason 은 그 첫 까닭. */
  private record Loaded(String text, List<String> failed, List<String> assembled, String reason) {}

  private void load() {
    int gen = generation.incrementAndGet();
    modality = ModalityState.current();
    boolean withStorage = storage.isSelected();
    gauge.setVisible(true);
    storage.setEnabled(false);
    status(EiDbBundle.message("explorer.loading"), false);
    setActions(false);
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Loaded loaded = read(sessions.connection(c), gen, withStorage);
        if (loaded != null) later(gen, () -> show(loaded));
      } catch (Exception e) {
        String msg = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        later(gen, () -> {
          finished();
          status(EiDbBundle.message("ddl.failed", msg), true);
          Notify.error(project, EiDbBundle.message("ddl.failed", msg));
        });
      } finally {
        sessions.endReadTransaction(c);
      }
    });
  }

  /** 표마다 차례로 읽는다. 그사이 창이 닫혔거나 다시 읽기를 시작했으면 멈추고 null. */
  private Loaded read(Connection conn, int gen, boolean withStorage) {
    StringBuilder text = new StringBuilder();
    List<String> failed = new ArrayList<>();
    List<String> assembled = new ArrayList<>();
    String reason = null;
    for (String table : tables) {
      if (gen != generation.get()) return null;
      if (!text.isEmpty()) text.append('\n');
      try {
        DdlRead.Result r = DdlRead.read(conn, c, schema, table, withStorage);
        text.append(r.ddl());
        if (r.fallbackReason() != null) {
          assembled.add(table);
          if (reason == null) reason = r.fallbackReason();
        }
      } catch (SQLException e) {
        failed.add(table);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replaceAll("\\s+", " ").strip();
        text.append(EiDbBundle.message("ddl.part.table.failed", table, msg)).append('\n');
      }
    }
    return new Loaded(text.toString(), failed, assembled, reason);
  }

  private void later(int gen, Runnable r) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (gen == generation.get() && !isDisposed()) r.run();
    }, modality);
  }

  private void finished() {
    gauge.setVisible(false);
    storage.setEnabled(true);
  }

  private void show(Loaded loaded) {
    finished();
    editor.setText(loaded.text());
    boolean allFailed = loaded.failed().size() == tables.size();
    setActions(!allFailed && !loaded.text().isBlank());
    if (!loaded.failed().isEmpty()) {
      String names = String.join(NAME_SEPARATOR, loaded.failed());
      status(EiDbBundle.message("ddl.some.failed", loaded.failed().size(), tables.size(), names), true);
      if (allFailed) Notify.error(project, EiDbBundle.message("ddl.some.failed", loaded.failed().size(), tables.size(), names));
    } else if (!loaded.assembled().isEmpty()) {
      status(EiDbBundle.message("ddl.assembled", String.join(NAME_SEPARATOR, loaded.assembled()), loaded.reason().replaceAll("\\s+", " ")), false);
    } else {
      status(EiDbBundle.message("ddl.loaded", tables.size()), false);
    }
  }

  private void status(String text, boolean error) {
    status.setText(text);
    status.setToolTipText(text);
    status.setForeground(error ? UIUtil.getErrorForeground() : UIUtil.getContextHelpForeground());
  }

  private void setActions(boolean on) {
    copyAction.setEnabled(on);
    consoleAction.setEnabled(on);
  }
}
