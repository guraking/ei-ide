package ei.db.mybatis;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.gen.Ui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 「바꾼 것 다시 시험」 창(모달 아님): 커밋하지 않은 바뀐 매퍼 SELECT 문장은 고치기 전 · 지금 결과를 견주고(MapperCompare),
 * 그 변경이 닿는 API 는 저장한 요청을 다시 보내 지난 응답과 견준다(서버 창의 ApiReplay).
 * 창을 열면 한 번 모으고, 「돌리기」를 눌러야 체크한 줄만 차례로 돌린다. 줄을 두 번 누르면 비교 창(돌리기 전이면 그 문장 · 메서드)으로 간다.
 * 데이터를 바꿀 수 있는 요청(GET · HEAD · OPTIONS 밖)은 처음에 체크하지 않는다.
 */
final class RetestDialog extends DialogWrapper {

  private static final Logger LOG = Logger.getInstance(RetestDialog.class);
  private static final String DIMENSION_KEY = "ei.db.mybatis.RetestDialog";
  private static final double FIRST_WIDTH_RATIO = 0.55;
  private static final double FIRST_HEIGHT_RATIO = 0.5;
  private static final int CHECK = 0;
  private static final int KIND = 1;
  private static final int NAME = 2;
  private static final int WHY = 3;
  private static final int RESULT = 4;

  /** 메뉴 항목: Git 메뉴 · 커밋 창 툴바와 우클릭. 서버 창 플러그인이 있을 때만 등록된다(ei-db-bootservers.xml). */
  public static final class OpenAction extends AnAction implements DumbAware {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabledAndVisible(e.getProject() != null && !e.getProject().isDefault());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      if (e.getProject() != null) new RetestDialog(e.getProject()).show();
    }
  }

  private final Project project;
  private final Model model = new Model();
  private final JBTable table = new JBTable(model);
  private final JBLabel status = new JBLabel();
  private final JButton recollect = new JButton(EiDbBundle.message("retest.recollect"), AllIcons.Actions.Refresh);
  /** 돌고 있는 모으기 · 돌리기의 진행(없으면 null). 화면 스레드에서만 바꾼다. */
  private @Nullable ProgressIndicator collecting;
  private @Nullable ProgressIndicator running;
  private boolean busy;

  private RetestDialog(Project project) {
    super(project, true, IdeModalityType.MODELESS);
    this.project = project;
    setTitle(EiDbBundle.message("retest.title"));
    setOKButtonText(EiDbBundle.message("retest.run"));
    getOKAction().putValue(Action.SMALL_ICON, AllIcons.Actions.Execute);
    setCancelButtonText(EiDbBundle.message("common.close"));
    init();
    collect();
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() { return Ui.firstSize(FIRST_WIDTH_RATIO, FIRST_HEIGHT_RATIO); }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    table.setDefaultRenderer(String.class, new Renderer());
    table.getTableHeader().setReorderingAllowed(false);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    Ui.fitColumn(table, CHECK, "");
    Ui.fitColumn(table, KIND, EiDbBundle.message("retest.kind.mapper"));
    Ui.rememberColumns(table, DIMENSION_KEY);
    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(@NotNull MouseEvent event) {
        int row = table.rowAtPoint(event.getPoint());
        if (row < 0 || row >= model.items.size()) return false;
        open(model.items.get(row));
        return true;
      }
    }.installOn(table);
    recollect.addActionListener(e -> collect());
    status.setForeground(UIUtil.getContextHelpForeground());
    JPanel top = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    top.add(status, BorderLayout.CENTER);
    top.add(recollect, BorderLayout.EAST);
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
    panel.add(top, BorderLayout.NORTH);
    panel.add(new JBScrollPane(table), BorderLayout.CENTER);
    return panel;
  }

  /** 체크한 줄을 차례로 돌린다. 돌리는 중에 누르면 멈춘다. 창은 닫지 않는다. */
  @Override
  protected void doOKAction() {
    if (running != null) {
      running.cancel();
      return;
    }
    if (busy) return;
    List<RetestCollect.Item> todo = new ArrayList<>();
    for (RetestCollect.Item item : model.items) {
      if (item.checked && item.kind != RetestCollect.Kind.API_NONE) todo.add(item);
    }
    if (todo.isEmpty()) {
      setErrorText(EiDbBundle.message("retest.nothing.checked"), table);
      return;
    }
    setErrorText(null);
    for (RetestCollect.Item item : todo) {
      item.result = EiDbBundle.message("retest.waiting");
      item.outcome = null;
    }
    model.fireTableDataChanged();
    setBusy(true);
    new Task.Backgroundable(project, EiDbBundle.message("retest.progress.run"), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        ApplicationManager.getApplication().invokeLater(() -> {
          if (busy && !isDisposed()) running = indicator;
          updateButtons();
        }, ModalityState.any());
        indicator.setIndeterminate(false);
        for (int i = 0; i < todo.size(); i++) {
          indicator.checkCanceled();
          if (isDisposed()) return;
          RetestCollect.Item item = todo.get(i);
          indicator.setFraction((double) i / todo.size());
          indicator.setText2(item.name);
          show(item, EiDbBundle.message("retest.running"), null);
          runOne(item, indicator);
        }
      }

      @Override
      public void onFinished() {
        // 멈추면 아직 돌지 않은 줄의 「기다리는 중」 · 「돌리는 중」을 「멈췄어요」로 바꾼다.
        String waiting = EiDbBundle.message("retest.waiting");
        String now = EiDbBundle.message("retest.running");
        for (RetestCollect.Item item : todo) {
          if (item.outcome == null && (waiting.equals(item.result) || now.equals(item.result))) item.result = EiDbBundle.message("retest.stopped");
        }
        running = null;
        if (!isDisposed()) {
          model.fireTableDataChanged();
          setBusy(false);
        }
      }
    }.queue();
  }

  private void runOne(RetestCollect.Item item, ProgressIndicator indicator) {
    try {
      if (item.kind == RetestCollect.Kind.MAPPER) {
        VirtualFile file = item.file;
        if (file == null || !file.isValid()) {
          show(item, EiDbBundle.message("retest.result.failed", EiDbBundle.message("retest.file.gone")), null);
          return;
        }
        CharSequence now = ReadAction.compute(() -> RetestCollect.currentText(file));
        MapperCompare.Outcome o = MapperCompare.run(project, file, item.before, now, item.statementId, indicator);
        show(item, mapperText(o), o);
      } else {
        ApiBridge.Result r = ApiBridge.resend(project, item.apiKey);
        show(item, apiText(r), r);
      }
    } catch (ProcessCanceledException e) {
      throw e;
    } catch (RuntimeException e) {
      // 한 줄의 실패로 나머지를 멈추지 않는다.
      LOG.warn("바꾼 것 다시 시험: " + item.name, e);
      show(item, EiDbBundle.message("retest.result.failed", String.valueOf(e.getMessage())), null);
    }
  }

  private static String mapperText(MapperCompare.Outcome o) {
    if (!o.ok()) return EiDbBundle.message("retest.result.failed", o.message());
    if (o.sameSql()) return EiDbBundle.message("retest.result.sql.same");
    int before = o.before() == null ? 0 : o.before().rows().size();
    int now = o.now() == null ? 0 : o.now().rows().size();
    return o.same() ? EiDbBundle.message("retest.result.mapper.same", now) : EiDbBundle.message("retest.result.mapper.diff", before, now);
  }

  private static String apiText(ApiBridge.Result r) {
    return switch (RetestRules.apiVerdict(r.error(), r.beforeCode(), r.nowCode(), r.same())) {
      case FAILED -> EiDbBundle.message("retest.result.failed", r.error());
      case NO_BASE -> EiDbBundle.message("retest.result.api.nobase", r.nowCode());
      case STATUS -> EiDbBundle.message("retest.result.api.status", r.beforeCode(), r.nowCode());
      case BODY -> EiDbBundle.message("retest.result.api.body", r.nowCode());
      case SAME -> EiDbBundle.message("retest.result.api.same", r.nowCode());
    };
  }

  /** 아무 스레드에서. 줄의 결과 글 · 비교 재료를 화면 스레드에서 바꾼다(창이 닫혔으면 버린다). */
  private void show(RetestCollect.Item item, String text, @Nullable Object outcome) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (isDisposed()) return;
      item.result = text;
      item.outcome = outcome;
      int row = model.items.indexOf(item);
      if (row >= 0) model.fireTableRowsUpdated(row, row);
    }, ModalityState.any());
  }

  /** 바뀐 것을 다시 모은다. 모으는 동안 표는 비우고 단추는 끈다. */
  private void collect() {
    if (busy) return;
    model.set(List.of());
    table.getEmptyText().setText(EiDbBundle.message("retest.collecting"));
    status.setText("");
    setBusy(true);
    new Task.Backgroundable(project, EiDbBundle.message("retest.progress.collect"), true) {
      private RetestCollect.Gathered gathered;
      private String failure;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!isDisposed()) collecting = indicator;
        }, ModalityState.any());
        try {
          gathered = RetestCollect.gather(project, indicator);
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (RuntimeException e) {
          LOG.warn("바꾼 것 다시 시험: 모으기 실패", e);
          failure = String.valueOf(e.getMessage());
        }
      }

      @Override
      public void onFinished() {
        collecting = null;
        if (isDisposed()) return;
        setBusy(false);
        if (gathered == null) {
          table.getEmptyText().setText(failure == null ? EiDbBundle.message("retest.stopped")
                                                       : EiDbBundle.message("retest.collect.failed", failure));
          return;
        }
        table.getEmptyText().setText(EiDbBundle.message("retest.empty"));
        for (RetestCollect.Item item : gathered.items()) {
          if (item.changesData) item.result = EiDbBundle.message("retest.data.change");
          if (item.kind == RetestCollect.Kind.API_NONE) item.result = EiDbBundle.message("retest.no.saved");
        }
        model.set(gathered.items());
        status.setText(summary(gathered));
      }
    }.queue();
  }

  private static String summary(RetestCollect.Gathered g) {
    int mappers = 0;
    int apis = 0;
    int unsaved = 0;
    for (RetestCollect.Item item : g.items()) {
      switch (item.kind) {
        case MAPPER -> mappers++;
        case API -> apis++;
        case API_NONE -> unsaved++;
      }
    }
    StringBuilder out = new StringBuilder(EiDbBundle.message("retest.summary", mappers, apis, unsaved));
    if (g.skippedRoots() > 0) out.append(" · ").append(EiDbBundle.message("retest.cap", RetestCollect.MAX_ROOTS));
    if (g.skippedFiles() > 0) out.append(" · ").append(EiDbBundle.message("retest.cap.files", RetestCollect.MAX_FILES));
    if (g.failedFiles() > 0) out.append(" · ").append(EiDbBundle.message("retest.files.failed", g.failedFiles()));
    return out.toString();
  }

  /** 비교 결과가 있으면 비교 창, 없으면 그 문장 · 메서드로 간다. */
  private void open(RetestCollect.Item item) {
    try {
      if (item.outcome instanceof MapperCompare.Outcome o) {
        MapperCompare.show(project, o);
      } else if (item.outcome instanceof ApiBridge.Result r) {
        ApiBridge.showDiff(project, r);
      } else if (item.nav != null && item.nav.canNavigate()) {
        item.nav.navigate(true);
      }
    } catch (RuntimeException e) {
      LOG.warn("바꾼 것 다시 시험: 열기 실패 " + item.name, e);
      Notify.error(project, EiDbBundle.message("retest.result.failed", String.valueOf(e.getMessage())));
    }
  }

  private void setBusy(boolean on) {
    busy = on;
    updateButtons();
  }

  private void updateButtons() {
    if (isDisposed()) return;
    recollect.setEnabled(!busy);
    getOKAction().setEnabled(!busy || running != null);
    getOKAction().putValue(Action.NAME, EiDbBundle.message(running != null ? "retest.stop" : "retest.run"));
    table.setEnabled(!busy || running != null);
  }

  @Override
  protected void dispose() {
    if (collecting != null) collecting.cancel();
    if (running != null) running.cancel();
    super.dispose();
  }

  private final class Model extends AbstractTableModel {
    private final List<RetestCollect.Item> items = new ArrayList<>();
    private final String[] heads = {"", EiDbBundle.message("retest.col.kind"), EiDbBundle.message("retest.col.name"),
                                    EiDbBundle.message("retest.col.why"), EiDbBundle.message("retest.col.result")};

    void set(List<RetestCollect.Item> list) {
      items.clear();
      items.addAll(list);
      fireTableDataChanged();
    }

    @Override public int getRowCount() { return items.size(); }

    @Override public int getColumnCount() { return heads.length; }

    @Override public String getColumnName(int column) { return heads[column]; }

    @Override public Class<?> getColumnClass(int column) { return column == CHECK ? Boolean.class : String.class; }

    @Override
    public boolean isCellEditable(int row, int column) {
      return column == CHECK && !busy && items.get(row).kind != RetestCollect.Kind.API_NONE;
    }

    @Override
    public Object getValueAt(int row, int column) {
      RetestCollect.Item item = items.get(row);
      return switch (column) {
        case CHECK -> item.checked;
        case KIND -> EiDbBundle.message(item.kind == RetestCollect.Kind.MAPPER ? "retest.kind.mapper" : "retest.kind.api");
        case NAME -> item.name;
        case WHY -> item.why;
        default -> item.result;
      };
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
      if (column == CHECK && isCellEditable(row, column)) {
        items.get(row).checked = Boolean.TRUE.equals(value);
        fireTableCellUpdated(row, column);
      }
    }
  }

  /** 저장한 요청이 없는 API 줄은 흐리게. */
  private final class Renderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int row, int column) {
      Component c = super.getTableCellRendererComponent(t, value, selected, focused, row, column);
      if (!selected && row < model.items.size()) {
        c.setForeground(model.items.get(row).kind == RetestCollect.Kind.API_NONE ? UIUtil.getContextHelpForeground() : t.getForeground());
      }
      return c;
    }
  }
}
