package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.components.panels.HorizontalLayout;
import ei.db.BarAction;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.SqlRunner;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.BorderLayout;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * 콘솔 편집기 위의 띠: 운영 경고(운영 접속일 때) + 도구줄(실행 · 전체 실행 · 중지 · 실행 계획 · 실행 기록 · 정렬 · 대문자 · 소문자 · 접속 · 자동 커밋 · 미반영 배지 · 커밋 · 롤백).
 * 편집기 하나에 하나만 만들고 편집기가 닫힐 때 리스너도 풀린다.
 */
final class ConsoleBar extends JPanel {

  private static final Key<ConsoleBar> BAR = Key.create("ei.db.console.bar");
  private static final String RUN_ACTION = "ei.db.console.Run";
  private static final String RUN_ALL_ACTION = "ei.db.console.RunAll";
  private static final String FORMAT_ACTION = "ei.db.console.Format";
  private static final String UPPER_ACTION = "ei.db.console.Upper";
  private static final String LOWER_ACTION = "ei.db.console.Lower";
  private static final String EXPLAIN_ACTION = "ei.db.console.Explain";
  private static final String TOOLBAR_PLACE = "EiDbConsole";
  /** 도구줄 묶음 사이 간격 · 띠 왼쪽 여백(px, 배율 전). */
  private static final int GAP = 8;
  /** 묶음 사이 세로선 높이(px, 배율 전). 도구줄 아이콘 단추 높이보다 조금 낮게 둔다. */
  private static final int SEPARATOR_HEIGHT = 18;
  /** 운영 꼬리표: 위험 색 보통 글. */
  private static final SimpleTextAttributes PRODUCTION_TAG = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UiColors.PRODUCTION);
  private static final String SPACE = "  ";
  private static final String DOT = " · ";

  private final Project project;
  private final VirtualFile file;
  /** 운영 접속 경고: 편집기 위 기본 알림 띠(오류 상태). */
  private final EditorNotificationPanel band = new EditorNotificationPanel(EditorNotificationPanel.Status.Error);
  private final ComboBox<DbConfig> combo = new ComboBox<>();
  private final JBCheckBox autoCommit = new JBCheckBox(EiDbBundle.message("console.autocommit"));
  /** 조회 건수. 목록에서 고르거나 1 이상 숫자를 직접 쓴다. 프로젝트의 모든 콘솔 · 표 데이터 보기에 같이 적용된다. */
  private final ComboBox<Integer> rows = new ComboBox<>(new Integer[] {DbSessions.MAX_ROWS, 1000, 5000, 10000});
  private final JBLabel badge = new JBLabel();
  /** 실행 · 정렬 단추와 커밋 · 롤백 단추. 켜짐 · 보임은 BarAction.update 가 접속 상태로 판정하고 refresh 가 다시 판정하게 한다. */
  private final ActionToolbar runBar;
  private final ActionToolbar commitBar;
  /** 화면을 값에 맞추는 중이면 콤보 · 체크 상자의 변경 처리를 건너뛴다. */
  private boolean updating;

  /** 콘솔 파일이면 띠를 붙인다. plugin.xml 의 editorNotificationProvider. */
  public static final class Provider implements EditorNotificationProvider, DumbAware {
    @Override
    public Function<? super FileEditor, ? extends JComponent> collectNotificationData(@NotNull Project project, @NotNull VirtualFile file) {
      if (!ConsoleOpener.isConsole(file)) return null;
      return fileEditor -> {
        if (!(fileEditor instanceof TextEditor text)) return null;
        ConsoleBar bar = fileEditor.getUserData(BAR);
        if (bar == null) {
          bar = new ConsoleBar(project, file, text.getEditor(), fileEditor);
          fileEditor.putUserData(BAR, bar);
        }
        return bar;
      };
    }
  }

  private ConsoleBar(Project project, VirtualFile file, Editor editor, FileEditor parent) {
    super(new BorderLayout());
    this.project = project;
    this.file = file;

    band.icon(AllIcons.General.Warning);
    add(band, BorderLayout.NORTH);

    runBar = BarAction.toolbar(TOOLBAR_PLACE, editor.getContentComponent(),
        new BarAction(EiDbBundle.message("common.run"), EiDbBundle.message("console.run.tooltip"), AllIcons.Actions.Execute,
            () -> ConsoleRun.runCurrent(project, editor, file)).shortcutOf(RUN_ACTION),
        new BarAction(EiDbBundle.message("action.ei.db.console.RunAll.text"), EiDbBundle.message("console.runall.tooltip"), AllIcons.Actions.RunAll,
            () -> ConsoleRun.runAll(project, editor, file)).shortcutOf(RUN_ALL_ACTION),
        new BarAction(EiDbBundle.message("console.stop"), null, AllIcons.Actions.Suspend, this::cancelRun).enabledWhen(this::isRunning),
        new BarAction(EiDbBundle.message("console.plan.tooltip"), null, AllIcons.Toolwindows.ToolWindowHierarchy,
            () -> ExplainPlan.run(project, editor, file)).shortcutOf(EXPLAIN_ACTION),
        new BarAction(EiDbBundle.message("console.history.tooltip"), null, AllIcons.Vcs.History, () -> HistoryDialog.open(project)),
        Separator.getInstance(),
        new BarAction(EiDbBundle.message("console.format"), EiDbBundle.message("console.format.tooltip"), AllIcons.Actions.ReformatCode,
            () -> ConsoleRun.format(project, editor)).shortcutOf(FORMAT_ACTION),
        new BarAction(EiDbBundle.message("console.upper"), EiDbBundle.message("console.upper.tooltip"), null,
            () -> ConsoleRun.toCase(project, editor, true)).withText().shortcutOf(UPPER_ACTION),
        new BarAction(EiDbBundle.message("console.lower"), EiDbBundle.message("console.lower.tooltip"), null,
            () -> ConsoleRun.toCase(project, editor, false)).withText().shortcutOf(LOWER_ACTION));
    commitBar = BarAction.toolbar(TOOLBAR_PLACE, editor.getContentComponent(),
        new BarAction(EiDbBundle.message("console.commit"), null, AllIcons.Actions.Commit, () -> endTransaction(true))
            .visibleWhen(this::manualCommit).enabledWhen(this::hasPending).withText(),
        new BarAction(EiDbBundle.message("console.rollback"), null, AllIcons.Actions.Rollback, () -> endTransaction(false))
            .visibleWhen(this::manualCommit).enabledWhen(this::hasPending).withText());

    combo.setRenderer(new ConnectionRenderer());
    combo.addPopupMenuListener(new PopupMenuListener() {
      @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { reloadConnections(); }
      @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
      @Override public void popupMenuCanceled(PopupMenuEvent e) { }
    });
    combo.addActionListener(e -> switchConnection());
    rows.setEditable(true);
    rows.setRenderer(SimpleListCellRenderer.create("", n -> EiDbBundle.message("console.rows.item", String.valueOf(n))));
    rows.setToolTipText(EiDbBundle.message("console.rows.tooltip"));
    rows.addActionListener(e -> changeRows());
    autoCommit.addActionListener(e -> toggleAutoCommit());
    badge.setForeground(UiColors.WARNING);

    // 띠 아래 선 하나로 편집기와 나눈다. 단추는 IntelliJ 도구줄(평평한 아이콘 단추)이고 콤보 · 체크 상자만 일반 부품이다.
    JPanel bar = new JPanel(new HorizontalLayout(GAP, SwingConstants.CENTER));
    bar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.emptyLeft(GAP)));
    bar.add(runBar.getComponent());
    bar.add(separator());
    bar.add(combo);
    bar.add(rows);
    bar.add(separator());
    bar.add(autoCommit);
    bar.add(badge);
    bar.add(commitBar.getComponent());
    add(bar, BorderLayout.CENTER);

    DbSessions.get(project).addListener(parent, () -> ApplicationManager.getApplication().invokeLater(this::refresh));
    reloadConnections();
    refresh();
  }

  /** 접속 콤보 줄: DB 종류 아이콘 + 상태 점 · 굵은 이름 · 회색 스키마 · 운영 꼬리표. */
  private final class ConnectionRenderer extends ColoredListCellRenderer<DbConfig> {
    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends DbConfig> list, DbConfig c, int index, boolean selected, boolean hasFocus) {
      if (c == null) return;
      boolean open = DbSessions.get(project).isOpen(c.id);
      setIcon(new KindIcon(c.kind, open ? UiColors.CONNECTED : UiColors.DISCONNECTED, selected ? null : UIUtil.getListBackground()));
      append(c.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
      append(SPACE + c.effectiveSchema(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      if (c.production) {
        append(SPACE + EiDbBundle.message("explorer.production"), PRODUCTION_TAG);
      }
    }
  }

  /** 이 콘솔 접속에서 실행 중일 때만 「실행 중지」를 켠다. */
  private boolean isRunning() {
    DbConfig c = ConsoleOpener.config(project, file);
    return c != null && DbSessions.get(project).isRunning(c.id);
  }

  /** 커밋 · 롤백은 자동 커밋이 꺼진 접속에서만 보인다. */
  private boolean manualCommit() {
    DbConfig c = ConsoleOpener.config(project, file);
    return c != null && !c.autoCommit;
  }

  private boolean hasPending() {
    DbConfig c = ConsoleOpener.config(project, file);
    return c != null && DbSessions.get(project).pending(c.id) > 0;
  }

  /** 묶음 사이 세로선. 줄 가운데에 SEPARATOR_HEIGHT 높이로 선다. */
  private static JComponent separator() {
    JSeparator s = new JSeparator(SwingConstants.VERTICAL);
    s.setPreferredSize(JBUI.size(s.getPreferredSize().width, SEPARATOR_HEIGHT));
    return s;
  }

  private void reloadConnections() {
    updating = true;
    try {
      DefaultComboBoxModel<DbConfig> model = new DefaultComboBoxModel<>();
      String id = file.getUserData(ConsoleOpener.CONFIG_ID);
      DbConfig selected = null;
      for (DbConfig c : DbSettings.getInstance().connections()) {
        model.addElement(c);
        if (c.id.equals(id)) selected = c;
      }
      model.setSelectedItem(selected);
      combo.setModel(model);
    } finally {
      updating = false;
    }
  }

  /** 접속 · 대기 변경 수에 맞춰 띠와 단추를 고친다. 화면 스레드. */
  private void refresh() {
    if (project.isDisposed()) return;
    DbConfig c = ConsoleOpener.config(project, file);
    updating = true;
    try {
      band.setVisible(c != null && c.production);
      rows.setSelectedItem(DbSessions.get(project).maxRows());
      if (c == null) {
        autoCommit.setEnabled(false);
        badge.setIcon(null);
        badge.setText(EiDbBundle.message("console.badge.removed"));
        badge.setToolTipText(null);
        badge.setVisible(true);
        return;
      }
      band.setText(EiDbBundle.message("console.production.warning") + SPACE + c.name + DOT + c.effectiveSchema() + DOT + c.host);
      autoCommit.setEnabled(true);
      autoCommit.setSelected(c.autoCommit);
      int pending = DbSessions.get(project).pending(c.id);
      // 미반영 건수는 짧은 꼬리표로, 전체 문구는 툴팁으로 보인다.
      badge.setText(null);
      badge.setIcon(new Badge(EiDbBundle.message("console.badge.pending.short", pending), UiColors.PENDING_BG, UiColors.PENDING));
      badge.setToolTipText(EiDbBundle.message("console.badge.pending", pending));
      badge.setVisible(pending > 0);
    } finally {
      updating = false;
      runBar.updateActionsAsync();
      commitBar.updateActionsAsync();
      revalidate();
      repaint();
    }
  }

  /** 직접 쓴 값은 글자로 온다. 1 이상 정수(쉼표 · 「건」 허용)만 받고, 아니면 알리고 지금 값으로 되돌린다. */
  private void changeRows() {
    if (updating) return;
    Object v = rows.getSelectedItem();
    String text = v == null ? "" : v.toString();
    int n = DbSessions.parseRowCount(text);
    if (n == 0) {
      Notify.error(project, EiDbBundle.message("console.rows.invalid", DbSessions.MAX_ROW_LIMIT, text));
      refresh();
      return;
    }
    DbSessions.get(project).setMaxRows(n);
  }

  private void switchConnection() {
    if (updating) return;
    DbConfig target = (DbConfig) combo.getSelectedItem();
    if (target == null || target.id.equals(file.getUserData(ConsoleOpener.CONFIG_ID))) return;
    ConsoleOpener.rebind(project, file, target);
    refresh();
  }

  /**
   * 이 접속(이번 세션, 같은 접속의 모든 콘솔)의 자동 커밋을 바꾸고, 열린 접속에도 바로 건다(DbSessions.setAutoCommitOverride).
   * 미반영 변경이 있는데 켜면 드라이버가 커밋하므로 먼저 묻고 커밋으로 처리한다. 접속이 열려 있지 않아도 모든 도구줄을 바로 맞춘다.
   */
  private void toggleAutoCommit() {
    if (updating) return;
    DbConfig c = ConsoleOpener.config(project, file);
    if (c == null) return;
    boolean on = autoCommit.isSelected();
    DbSessions sessions = DbSessions.get(project);
    int pending = sessions.pending(c.id);
    if (on && pending > 0 && Messages.showYesNoDialog(project, EiDbBundle.message("console.autocommit.confirm", pending),
        EiDbBundle.message("console.autocommit"), EiDbBundle.message("console.autocommit.ok"), EiDbBundle.message("common.cancel"), Messages.getWarningIcon()) != Messages.YES) {
      refresh();
      return;
    }
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        if (on && pending > 0) sessions.commit(c.id);
        sessions.setAutoCommitOverride(c.id, on);
      } catch (SQLException e) {
        Notify.error(project, EiDbBundle.message("console.autocommit.failed", c.name, e.getMessage()));
      }
      // 리스너가 같은 접속의 모든 콘솔 도구줄을 다시 그린다(실패했으면 원래 값으로).
      sessions.notifyChanged();
    });
  }

  private void cancelRun() {
    DbConfig c = ConsoleOpener.config(project, file);
    if (c == null) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> SqlRunner.cancel(project, c));
  }

  private void endTransaction(boolean doCommit) {
    DbConfig c = ConsoleOpener.config(project, file);
    if (c == null) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        if (doCommit) DbSessions.get(project).commit(c.id);
        else DbSessions.get(project).rollback(c.id);
        Notify.info(project, EiDbBundle.message(doCommit ? "console.committed" : "console.rolled.back", c.name));
      } catch (SQLException e) {
        Notify.error(project, EiDbBundle.message(doCommit ? "console.commit.failed" : "console.rollback.failed", c.name, e.getMessage()));
      }
    });
  }
}
