package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * 콘솔 편집기 위의 띠: 운영 경고(운영 접속일 때) + 도구줄(실행 · 전체 실행 · 정렬 · 대문자 · 소문자 · 접속 · 자동 커밋 · 미반영 배지 · 커밋 · 롤백).
 * 편집기 하나에 하나만 만들고 편집기가 닫힐 때 리스너도 풀린다.
 */
final class ConsoleBar extends JPanel {

  private static final Key<ConsoleBar> BAR = Key.create("ei.db.console.bar");
  private static final String RUN_ACTION = "ei.db.console.Run";
  private static final String RUN_ALL_ACTION = "ei.db.console.RunAll";
  private static final String FORMAT_ACTION = "ei.db.console.Format";
  private static final String UPPER_ACTION = "ei.db.console.Upper";
  private static final String LOWER_ACTION = "ei.db.console.Lower";

  private final Project project;
  private final VirtualFile file;
  private final JPanel band = new JPanel(new BorderLayout());
  private final JBLabel bandTarget = new JBLabel();
  private final ComboBox<DbConfig> combo = new ComboBox<>();
  private final JBCheckBox autoCommit = new JBCheckBox("자동 커밋");
  private final JBLabel badge = new JBLabel();
  private final JButton commit = new JButton("커밋", AllIcons.Actions.Commit);
  private final JButton rollback = new JButton("롤백", AllIcons.Actions.Rollback);
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

    JBLabel warning = new JBLabel("운영 접속 · 변경은 매번 확인합니다", AllIcons.General.Warning, SwingConstants.LEFT);
    warning.setForeground(UiColors.DANGER_TEXT);
    warning.setFont(warning.getFont().deriveFont(Font.BOLD));
    bandTarget.setForeground(UiColors.DANGER_TEXT);
    band.setBackground(UiColors.DANGER_BAND);
    band.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(UiColors.DANGER_LINE, 0, 0, 1, 0),
        JBUI.Borders.customLine(UiColors.DANGER, 0, 4, 0, 0), JBUI.Borders.empty(6, 10)));
    band.add(warning, BorderLayout.WEST);
    band.add(bandTarget, BorderLayout.EAST);
    add(band, BorderLayout.NORTH);

    JButton run = new JButton("실행", AllIcons.Actions.Execute);
    run.setToolTipText("커서가 있는 문장 또는 선택 영역 실행");
    run.addActionListener(e -> ConsoleRun.runCurrent(project, editor, file));
    JBLabel runKey = new JBLabel(KeymapUtil.getFirstKeyboardShortcutText(RUN_ACTION));
    runKey.setForeground(UIUtil.getContextHelpForeground());
    JButton runAll = new JButton("전체 실행", AllIcons.Actions.RunAll);
    String allKey = KeymapUtil.getFirstKeyboardShortcutText(RUN_ALL_ACTION);
    runAll.setToolTipText(allKey.isEmpty() ? "모든 문장을 차례로 실행" : "모든 문장을 차례로 실행 (" + allKey + ")");
    runAll.addActionListener(e -> ConsoleRun.runAll(project, editor, file));

    JButton format = textButton("정렬", AllIcons.Actions.ReformatCode, "선택 영역(없으면 전체) SQL 정렬", FORMAT_ACTION);
    format.addActionListener(e -> ConsoleRun.format(project, editor));
    JButton upper = textButton("대문자", null, "선택 영역(없으면 전체) 대문자로 · 문자열 · 주석은 그대로", UPPER_ACTION);
    upper.addActionListener(e -> ConsoleRun.toCase(project, editor, true));
    JButton lower = textButton("소문자", null, "선택 영역(없으면 전체) 소문자로 · 문자열 · 주석은 그대로", LOWER_ACTION);
    lower.addActionListener(e -> ConsoleRun.toCase(project, editor, false));

    combo.setRenderer(SimpleListCellRenderer.create("", ConsoleBar::label));
    combo.addPopupMenuListener(new PopupMenuListener() {
      @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { reloadConnections(); }
      @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
      @Override public void popupMenuCanceled(PopupMenuEvent e) { }
    });
    combo.addActionListener(e -> switchConnection());
    autoCommit.addActionListener(e -> toggleAutoCommit());
    badge.setForeground(UiColors.WARNING);
    commit.addActionListener(e -> endTransaction(true));
    rollback.addActionListener(e -> endTransaction(false));

    JPanel bar = new JPanel();
    bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
    bar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(4, 8)));
    bar.add(run);
    bar.add(Box.createHorizontalStrut(JBUI.scale(4)));
    bar.add(runKey);
    bar.add(Box.createHorizontalStrut(JBUI.scale(8)));
    bar.add(runAll);
    bar.add(separator());
    bar.add(format);
    bar.add(Box.createHorizontalStrut(JBUI.scale(4)));
    bar.add(upper);
    bar.add(Box.createHorizontalStrut(JBUI.scale(4)));
    bar.add(lower);
    bar.add(separator());
    bar.add(combo);
    bar.add(separator());
    bar.add(autoCommit);
    bar.add(Box.createHorizontalStrut(JBUI.scale(8)));
    bar.add(badge);
    bar.add(Box.createHorizontalStrut(JBUI.scale(8)));
    bar.add(commit);
    bar.add(Box.createHorizontalStrut(JBUI.scale(4)));
    bar.add(rollback);
    bar.add(Box.createHorizontalGlue());
    add(bar, BorderLayout.CENTER);

    combo.setMaximumSize(combo.getPreferredSize());
    DbSessions.get(project).addListener(parent, () -> ApplicationManager.getApplication().invokeLater(this::refresh));
    reloadConnections();
    refresh();
  }

  private static String label(DbConfig c) {
    return c.name + " · " + c.effectiveSchema();
  }

  /** 단축키가 있으면 풀이 끝에 붙인다. */
  private static JButton textButton(String text, Icon icon, String tip, String actionId) {
    JButton b = new JButton(text, icon);
    String key = KeymapUtil.getFirstKeyboardShortcutText(actionId);
    b.setToolTipText(key.isEmpty() ? tip : tip + " (" + key + ")");
    return b;
  }

  private static JComponent separator() {
    JSeparator s = new JSeparator(SwingConstants.VERTICAL);
    s.setMaximumSize(new Dimension(JBUI.scale(1), JBUI.scale(18)));
    JPanel wrap = new JPanel(new BorderLayout());
    wrap.setBorder(JBUI.Borders.empty(0, 8));
    wrap.add(s, BorderLayout.CENTER);
    wrap.setMaximumSize(new Dimension(JBUI.scale(17), JBUI.scale(18)));
    return wrap;
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
      combo.setMaximumSize(combo.getPreferredSize());
    } finally {
      updating = false;
    }
  }

  /** 접속 · 대기 변경 수에 맞춰 띠와 단추를 고친다. 화면 스레드. */
  private void refresh() {
    if (project.isDisposed()) return;
    DbConfig c = ConsoleOpener.config(file);
    updating = true;
    try {
      band.setVisible(c != null && c.production);
      if (c == null) {
        autoCommit.setEnabled(false);
        badge.setText("접속이 지워졌습니다");
        badge.setVisible(true);
        commit.setVisible(false);
        rollback.setVisible(false);
        return;
      }
      bandTarget.setText(label(c));
      autoCommit.setEnabled(true);
      autoCommit.setSelected(c.autoCommit);
      int pending = DbSessions.get(project).pending(c.id);
      badge.setText("미반영 변경 " + pending + "건");
      badge.setVisible(pending > 0);
      commit.setVisible(!c.autoCommit);
      rollback.setVisible(!c.autoCommit);
      commit.setEnabled(pending > 0);
      rollback.setEnabled(pending > 0);
    } finally {
      updating = false;
      revalidate();
      repaint();
    }
  }

  private void switchConnection() {
    if (updating) return;
    DbConfig target = (DbConfig) combo.getSelectedItem();
    if (target == null || target.id.equals(file.getUserData(ConsoleOpener.CONFIG_ID))) return;
    ConsoleOpener.rebind(project, file, target);
    refresh();
  }

  /**
   * 이 콘솔(이번 세션)의 자동 커밋을 바꾸고, 열린 접속에도 바로 건다. 미반영 변경이 있는데 켜면 드라이버가 커밋하므로
   * 먼저 묻고 커밋으로 처리한다.
   */
  private void toggleAutoCommit() {
    if (updating) return;
    DbConfig c = ConsoleOpener.config(file);
    if (c == null) return;
    boolean on = autoCommit.isSelected();
    DbSessions sessions = DbSessions.get(project);
    int pending = sessions.pending(c.id);
    if (on && pending > 0 && Messages.showYesNoDialog(project, "미반영 변경 " + pending + "건이 커밋됩니다. 자동 커밋을 켤까요?",
        "자동 커밋", "커밋하고 켜기", "취소", Messages.getWarningIcon()) != Messages.YES) {
      refresh();
      return;
    }
    file.putUserData(ConsoleOpener.AUTO_COMMIT, on);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        if (!sessions.isOpen(c.id)) return;
        if (on && pending > 0) sessions.commit(c.id);
        sessions.connection(c).setAutoCommit(on);
      } catch (SQLException e) {
        Notify.error(project, c.name + " 자동 커밋을 바꾸지 못했습니다: " + e.getMessage());
      }
      ApplicationManager.getApplication().invokeLater(this::refresh);
    });
  }

  private void endTransaction(boolean doCommit) {
    DbConfig c = ConsoleOpener.config(file);
    if (c == null) return;
    String what = doCommit ? "커밋" : "롤백";
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        if (doCommit) DbSessions.get(project).commit(c.id);
        else DbSessions.get(project).rollback(c.id);
        Notify.info(project, c.name + " " + what + "했습니다.");
      } catch (SQLException e) {
        Notify.error(project, c.name + " " + what + " 실패: " + e.getMessage());
      }
    });
  }
}
