package ei.db.console;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.FileTypes;
import ei.db.Notify;
import ei.db.bench.SqlParts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 「고치기 전 백업 보기」: 한 접속의 백업 파일 목록(시각 · 종류 · 표 · 행 수 · 원래 SQL)과 고른 파일의 내용 미리보기.
 * 「콘솔에서 열기」는 그 접속의 새 콘솔에 파일 내용(머리 주석 + 되돌리는 SQL)을 넣기만 하고 실행하지 않는다. 「파일 지우기」는 확인받고 지운다.
 * 목록 · 파일 읽기 · 지우기는 화면 스레드 밖에서 한다.
 */
public final class BackupDialog extends DialogWrapper {

  /** 처음 열 때 화면에 대한 크기 몫. 사용자가 바꾼 크기는 DIMENSION_KEY 로 기억한다. */
  private static final double FIRST_WIDTH_RATIO = 0.55;
  private static final double FIRST_HEIGHT_RATIO = 0.6;
  private static final String DIMENSION_KEY = "ei.db.BackupDialog";
  /** 목록과 미리보기 사이 분할 비율(사용자가 끈 값은 SPLIT_KEY 로 기억). */
  private static final String SPLIT_KEY = "ei.db.BackupDialog.split";
  private static final float SPLIT_DEFAULT = 0.4f;
  /** 칸 너비의 서로 비율(창 폭에 따라 함께 늘어난다): 시각 · 종류 · 표 · 행 수 · 원래 SQL. */
  private static final int[] COLUMN_WEIGHTS = {4, 4, 4, 2, 12};
  private static final int WEIGHT_UNIT = 30;
  private static final int COL_TIME = 0;
  private static final int COL_KIND = 1;
  private static final int COL_TABLE = 2;
  private static final int COL_ROWS = 3;
  private static final int COL_SQL = 4;
  private static final String COMMENT = "-- ";
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final Project project;
  private final DbConfig config;
  private final List<Backups.Entry> entries = new ArrayList<>();
  private final Model model = new Model();
  private final JBTable table = new JBTable(model);
  private final Document doc = EditorFactory.getInstance().createDocument("");
  private final Editor preview;
  private final Action deleteAction = new DialogWrapperAction(EiDbBundle.message("backup.delete.file")) {
    @Override protected void doAction(ActionEvent e) { deleteSelected(); }
  };
  /** 미리보기에 불러오려는 파일. 늦게 온 다른 파일의 내용을 버리는 데 쓴다. */
  private Path wanted;
  /** 미리보기에 다 불러온 내용. 불러오는 중 · 실패면 null. */
  private String loadedText;

  /** 그 접속의 백업 목록을 뒤에서 읽어 창을 연다. 읽지 못하면 알린다. */
  public static void open(Project project, DbConfig c) {
    if (c == null) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        List<Backups.Entry> entries = Backups.list(c);
        // 창 안에서 미리보기 문서를 바꾸므로(쓰기 동작) 쓰기가 허용되는 문맥에서 연다.
        ApplicationManager.getApplication().invokeLater(() -> new BackupDialog(project, c, entries).show(), ModalityState.nonModal(), project.getDisposed());
      } catch (IOException | RuntimeException e) {
        Notify.error(project, EiDbBundle.message("backup.list.failed", c.name, e.getMessage()));
      }
    });
  }

  private BackupDialog(Project project, DbConfig c, List<Backups.Entry> entries) {
    super(project, true);
    this.project = project;
    this.config = c;
    this.entries.addAll(entries);
    setTitle(EiDbBundle.message("backup.dialog.title", c.name));
    setOKButtonText(EiDbBundle.message("backup.open.console"));
    setCancelButtonText(EiDbBundle.message("common.close"));
    preview = EditorFactory.getInstance().createViewer(doc, project);
    if (preview instanceof EditorEx ex) {
      ex.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, FileTypes.byExtension("sql")));
    }
    preview.getSettings().setLineNumbersShown(true);
    preview.getSettings().setFoldingOutlineShown(false);
    preview.getSettings().setAdditionalLinesCount(0);
    Disposer.register(getDisposable(), () -> EditorFactory.getInstance().releaseEditor(preview));
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getEmptyText().setText(EiDbBundle.message("backup.dialog.empty"));
    table.getTableHeader().setReorderingAllowed(false);
    for (int i = 0; i < COLUMN_WEIGHTS.length; i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(JBUI.scale(COLUMN_WEIGHTS[i] * WEIGHT_UNIT));
    }
    table.getSelectionModel().addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting()) showSelected();
    });
    init();
    // 처음에는 고르지 않는다: 창이 뜨기 전에는 이 창의 문맥으로 화면 갱신을 예약할 수 없다. 사용자가 고르면 읽는다.
    showSelected();
  }

  @Override
  protected JComponent createCenterPanel() {
    OnePixelSplitter split = new OnePixelSplitter(true, SPLIT_KEY, SPLIT_DEFAULT);
    split.setHonorComponentsMinimumSize(false);
    split.setFirstComponent(ScrollPaneFactory.createScrollPane(table));
    split.setSecondComponent(preview.getComponent());
    return split;
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[]{getOKAction(), deleteAction, getCancelAction()};
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() {
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    return new Dimension((int) (screen.width * FIRST_WIDTH_RATIO), (int) (screen.height * FIRST_HEIGHT_RATIO));
  }

  @Override
  public JComponent getPreferredFocusedComponent() { return table; }

  private Backups.Entry selected() {
    int row = table.getSelectedRow();
    return row < 0 || row >= entries.size() ? null : entries.get(row);
  }

  /**
   * 고른 파일을 뒤에서 읽어 미리보기에 보인다. 읽는 동안 · 고른 것이 없을 때 · 실패는 주석 한 줄로 알린다.
   * 화면 갱신은 부른 때의 문맥(이 창)으로 예약한다: 창이 열려 있는 동안 돌고, 문서를 바꿀 수 있다.
   */
  private void showSelected() {
    Backups.Entry e = selected();
    ModalityState state = ModalityState.current();
    loadedText = null;
    wanted = e == null ? null : e.file();
    updateButtons();
    if (e == null) {
      setPreview(COMMENT + EiDbBundle.message(entries.isEmpty() ? "backup.dialog.empty" : "backup.dialog.pick"));
      return;
    }
    setPreview(COMMENT + EiDbBundle.message("backup.dialog.loading"));
    Path file = e.file();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String text;
      boolean ok;
      try {
        text = Files.readString(file, StandardCharsets.UTF_8);
        ok = true;
      } catch (IOException | RuntimeException ex) {
        text = COMMENT + EiDbBundle.message("backup.dialog.read.failed", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        ok = false;
      }
      String shown = text;
      boolean read = ok;
      ApplicationManager.getApplication().invokeLater(() -> {
        if (isDisposed() || !file.equals(wanted)) return;
        if (read) loadedText = shown;
        setPreview(shown);
        updateButtons();
      }, state);
    });
  }

  private void setPreview(String text) {
    String t = text.replace("\r", "");
    ApplicationManager.getApplication().runWriteAction(() -> doc.setText(t));
    preview.getScrollingModel().scrollVertically(0);
  }

  /** 「콘솔에서 열기」는 내용을 다 불러온 뒤에만, 「파일 지우기」는 고른 파일이 있을 때만 켠다. */
  private void updateButtons() {
    setOKActionEnabled(loadedText != null);
    deleteAction.setEnabled(selected() != null);
  }

  /** 그 접속의 새 콘솔을 열어 파일 내용을 넣는다. 실행하지 않는다. */
  @Override
  protected void doOKAction() {
    String text = loadedText;
    if (text == null) return;
    close(OK_EXIT_CODE);
    ConsoleOpener.openNew(project, config);
    ConsoleOpener.open(project, config, text);
    Notify.info(project, EiDbBundle.message("backup.opened", config.name));
  }

  private void deleteSelected() {
    Backups.Entry e = selected();
    if (e == null) return;
    if (Messages.showYesNoDialog(getContentPanel(), EiDbBundle.message("backup.delete.confirm", e.file().getFileName()),
        EiDbBundle.message("backup.delete.title"), Messages.getWarningIcon()) != Messages.YES) {
      return;
    }
    setErrorText(null);
    ModalityState state = ModalityState.current();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String failed = null;
      try {
        Files.deleteIfExists(e.file());
      } catch (IOException | RuntimeException ex) {
        failed = EiDbBundle.message("backup.delete.failed", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
      }
      String error = failed;
      ApplicationManager.getApplication().invokeLater(() -> {
        if (isDisposed()) return;
        if (error != null) {
          setErrorText(error);
          return;
        }
        int at = entries.indexOf(e);
        if (at < 0) return;
        entries.remove(at);
        model.fireTableRowsDeleted(at, at);
        if (entries.isEmpty() || table.getSelectedRow() < 0) showSelected();
      }, state);
    });
  }

  private final class Model extends AbstractTableModel {
    private final String[] names = {
        EiDbBundle.message("backup.col.time"), EiDbBundle.message("backup.col.kind"), EiDbBundle.message("backup.col.table"),
        EiDbBundle.message("backup.col.rows"), EiDbBundle.message("backup.col.sql")};

    @Override public int getRowCount() { return entries.size(); }
    @Override public int getColumnCount() { return names.length; }
    @Override public String getColumnName(int column) { return names[column]; }

    /** 머리가 없는 파일은 원래 SQL 칸에 파일 이름만 보인다. */
    @Override
    public Object getValueAt(int row, int column) {
      Backups.Entry e = entries.get(row);
      BackupSql.Header h = e.header();
      if (h == null) return column == COL_SQL ? EiDbBundle.message("backup.unknown.file", e.file().getFileName()) : "";
      return switch (column) {
        case COL_TIME -> TIME.format(h.time());
        case COL_KIND -> h.origin().label();
        case COL_TABLE -> h.table();
        case COL_ROWS -> h.rows();
        case COL_SQL -> SqlParts.oneLine(h.sql());
        default -> "";
      };
    }
  }
}
