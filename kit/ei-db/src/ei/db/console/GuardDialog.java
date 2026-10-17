package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import org.jetbrains.annotations.NotNull;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;

/**
 * 위험 문장 · 운영 접속 변경 확인창. 기본 단추는 「취소」이고, 입력 칸이 있으면 표 이름(대소문자 무시)을 그대로 쳐야
 * 「그래도 실행」이 켜진다. 표를 모르면 「실행」을 친다.
 */
public final class GuardDialog extends DialogWrapper {

  private static final String TYPE_WITHOUT_TABLE = "실행";
  /** SQL 상자에 보이는 최대 줄 수(넘으면 스크롤). */
  private static final int SQL_ROWS = 6;

  private final DbConfig c;
  private final String sql;
  private final String reason;
  private final String table;
  private final JBTextField typed = new JBTextField();
  private final boolean askTyping;

  private GuardDialog(Project project, DbConfig c, String sql, String reason, String table) {
    super(project, false);
    this.c = c;
    this.sql = sql.strip();
    this.reason = reason;
    this.table = table;
    this.askTyping = c.production || reason != null;
    setTitle("실행 확인");
    setOKButtonText("그래도 실행");
    setCancelButtonText("취소");
    getOKAction().putValue(DEFAULT_ACTION, null);
    getCancelAction().putValue(DEFAULT_ACTION, Boolean.TRUE);
    setOKActionEnabled(!askTyping);
    init();
  }

  /** reason 은 SqlGuard.danger 결과(운영 접속의 일반 변경이면 null), table 은 대상 표(모르면 null). 실행해도 되면 true. */
  public static boolean confirm(Project project, DbConfig c, String sql, String reason, String table) {
    boolean ok = new GuardDialog(project, c, sql, reason, table).showAndGet();
    if (!ok) ConsoleRun.stop(project);
    return ok;
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

    JBLabel title = new JBLabel(reason == null ? "운영 접속 변경 확인" : reason + " 를 막았습니다", AllIcons.General.Warning, SwingConstants.LEFT);
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2));
    add(panel, title, 0);

    JBLabel what = new JBLabel(effect());
    add(panel, what, 12);

    JBTextArea box = new JBTextArea(sql);
    box.setEditable(false);
    box.setFont(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
    box.setRows(Math.min(SQL_ROWS, (int) sql.lines().count()));
    box.setBorder(JBUI.Borders.empty(6, 10));
    JBScrollPane scroll = new JBScrollPane(box);
    scroll.setBorder(JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()));
    add(panel, scroll, 12);

    if (askTyping) {
      String expected = expected();
      String prompt = table == null ? "실행하려면 「" + TYPE_WITHOUT_TABLE + "」을 입력하세요" : "실행하려면 표 이름을 그대로 입력하세요";
      JBLabel ask = new JBLabel((c.production ? "운영 접속이라 " : "") + prompt);
      ask.setForeground(UIUtil.getContextHelpForeground());
      add(panel, ask, 12);

      JPanel row = new JPanel(new BorderLayout(JBUI.scale(10), 0));
      row.add(new JBLabel(table == null ? "확인" : "표 이름"), BorderLayout.WEST);
      typed.getEmptyText().setText(expected);
      typed.setFont(box.getFont());
      typed.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) {
          setOKActionEnabled(typed.getText().strip().equalsIgnoreCase(expected));
        }
      });
      row.add(typed, BorderLayout.CENTER);
      add(panel, row, 8);
    }
    panel.setPreferredSize(JBUI.size(520, panel.getPreferredSize().height));
    return panel;
  }

  private static void add(JPanel panel, JComponent child, int gapAbove) {
    child.setAlignmentX(Component.LEFT_ALIGNMENT);
    if (gapAbove > 0) panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(gapAbove)));
    panel.add(child);
  }

  private String expected() {
    return table == null ? TYPE_WITHOUT_TABLE : table;
  }

  /** 이 문장이 표에 하는 일. reason 은 SqlGuard.danger 의 글 그대로 온다. */
  private String effect() {
    String target = table == null ? "대상 표" : table;
    if (reason == null) return "운영 접속 「" + c.name + "」에서 " + target + " 를 바꿉니다.";
    return switch (reason) {
      case "WHERE 없는 DELETE" -> target + " 의 모든 행이 지워집니다.";
      case "WHERE 없는 UPDATE" -> target + " 의 모든 행이 바뀝니다.";
      case "TRUNCATE 문" -> target + " 의 모든 행이 지워지고 롤백할 수 없습니다.";
      case "DROP 문" -> target + " 를 통째로 지웁니다. 롤백할 수 없습니다.";
      default -> target + " 에 " + reason + " 를 실행합니다.";
    };
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return askTyping ? typed : null;
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[]{getCancelAction(), getOKAction()};
  }

  @Override
  protected JButton createJButtonForAction(Action action) {
    JButton button = super.createJButtonForAction(action);
    if (action == getOKAction()) button.setForeground(UiColors.DANGER);
    return button;
  }
}
