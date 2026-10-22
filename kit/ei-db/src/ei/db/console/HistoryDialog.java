package ei.db.console;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBList;
import com.intellij.ui.DocumentAdapter;
import com.intellij.util.ui.JBUI;
import ei.db.DbConfig;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.SqlRunner;
import ei.db.mybatis.SqlValues;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「실행 기록」 목록. 검색칸 글(접속 이름 · 문장, 대소문자 무시)로 거른다.
 * 확인 · Enter 는 그 기록의 접속 콘솔에 문장(바인드 값은 리터럴로 채움)을 붙여 넣고, 두 번 누르면 붙여 넣고 원래 문장과 값으로 바로 실행한다.
 */
final class HistoryDialog extends DialogWrapper {

  /** 목록 한 줄에 보일 문장 글자 수. 넘으면 줄임표, 전체는 툴팁. */
  private static final int PREVIEW_CHARS = 160;
  private static final int WIDTH = 760;
  private static final int HEIGHT = 440;

  private final Project project;
  private final List<RunHistory.Entry> all;
  private final CollectionListModel<RunHistory.Entry> model = new CollectionListModel<>();
  private final JBList<RunHistory.Entry> list = new JBList<>(model);
  private final SearchTextField search = new SearchTextField(false);
  private final SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA);

  /** 기록 파일은 백그라운드에서 읽고 화면 스레드에서 연다. */
  static void open(Project project) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        List<RunHistory.Entry> entries = RunHistory.get().entries();
        ApplicationManager.getApplication().invokeLater(() -> new HistoryDialog(project, entries).show(), ModalityState.any());
      } catch (IOException e) {
        Notify.error(project, EiDbBundle.message("history.read.failed", e.getMessage()));
      }
    });
  }

  private HistoryDialog(Project project, List<RunHistory.Entry> all) {
    super(project, true);
    this.project = project;
    this.all = all;
    setTitle(EiDbBundle.message("history.title"));
    setOKButtonText(EiDbBundle.message("history.insert"));
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setEmptyText(EiDbBundle.message("history.empty"));
    list.setCellRenderer(SimpleListCellRenderer.create((label, e, index) -> {
      label.setText(line(e));
      label.setToolTipText(pasteSql(e));
    }));
    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(@NotNull MouseEvent event) {
        if (list.getSelectedValue() == null) return false;
        insert(true);
        return true;
      }
    }.installOn(list);
    search.addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        filter();
      }
    });
    filter();
    init();
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    panel.add(search, BorderLayout.NORTH);
    panel.add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER);
    panel.setPreferredSize(JBUI.size(WIDTH, HEIGHT));
    return panel;
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return search;
  }

  @Override
  protected void doOKAction() {
    if (list.getSelectedValue() == null) return;
    insert(false);
  }

  /** 시각 · 접속 · 걸린 시간 · 건수 · 성공/실패 · 문장(한 줄로). */
  private String line(RunHistory.Entry e) {
    String count = e.count() == RunHistory.NO_COUNT ? "" : EiDbBundle.message("history.count", e.count());
    String sql = pasteSql(e).replaceAll("\\s+", " ");
    if (sql.length() > PREVIEW_CHARS) sql = sql.substring(0, PREVIEW_CHARS) + "…";
    return time.format(new Date(e.time())) + " · " + e.configName() + " · " + e.millis() + " ms" + count
        + " · " + EiDbBundle.message(e.ok() ? "history.ok" : "history.failed") + " · " + sql;
  }

  private void filter() {
    String q = search.getText().strip().toLowerCase(Locale.ROOT);
    List<RunHistory.Entry> shown = new ArrayList<>();
    for (RunHistory.Entry e : all) {
      if (q.isEmpty() || pasteSql(e).toLowerCase(Locale.ROOT).contains(q) || e.configName().toLowerCase(Locale.ROOT).contains(q)) shown.add(e);
    }
    model.replaceAll(shown);
    if (!shown.isEmpty()) list.setSelectedIndex(0);
  }

  /** 기록의 접속 콘솔에 붙여 넣고, run 이면 그 콘솔 설정(자동 커밋 포함)으로 실행한다. */
  private void insert(boolean run) {
    RunHistory.Entry e = list.getSelectedValue();
    DbConfig c = DbSettings.getInstance().find(e.configId());
    if (c == null) {
      Notify.error(project, EiDbBundle.message("history.connection.removed", e.configName()));
      return;
    }
    close(OK_EXIT_CODE);
    VirtualFile file = ConsoleOpener.open(project, c, pasteSql(e));
    if (!run) return;
    DbConfig console = ConsoleOpener.config(project, file);
    if (console != null) SqlRunner.run(project, console, e.sql(), e.binds());
  }

  /** 콘솔에 넣을 글. 바인드 값이 있으면 ? 자리에 리터럴로 채운다(실행은 원래 문장 + 값으로 한다). */
  private static String pasteSql(RunHistory.Entry e) {
    return e.binds().isEmpty() ? e.sql() : SqlValues.inline(e.sql(), e.binds());
  }
}
