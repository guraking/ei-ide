package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.QueryResult;
import org.jetbrains.annotations.NotNull;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 아래 「결과」 창. 실행마다 탭 「결과 N · 표 이름」을 더하고 오래된 것부터 지워 MAX_TABS 개만 둔다. 화면 스레드에서 불린다. */
public final class ResultView {
  private ResultView() {}

  static final String TOOL_WINDOW_ID = "EI Database Result";
  private static final int MAX_TABS = 10;
  private static final Key<Integer> COUNTER = Key.create("ei.db.console.resultCounter");
  private static final Pattern FROM = Pattern.compile("(?i)\\bFROM\\s+([\\w.$#\"]+)");

  /** plugin.xml 의 toolWindow. 줄무늬 이름은 「결과」. */
  public static final class Factory implements ToolWindowFactory, DumbAware {
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
      toolWindow.setStripeTitle(EiDbBundle.message("toolwindow.stripe.EI_Database_Result"));
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
      // 탭은 실행할 때 더한다.
    }
  }

  public static void show(Project project, DbConfig c, QueryResult result) {
    String title = result.isQuery() ? firstTable(result.sql()) : EiDbBundle.message("result.tab.update");
    Icon icon = result.isQuery() ? AllIcons.Nodes.DataTables : AllIcons.Actions.Commit;
    addTab(project, title == null ? EiDbBundle.message("result.tab.query") : title, icon, new ResultPanel(project, c, result, firstTable(result.sql())));
    ConsoleRun.finished(project, c, result.sql(), true);
  }

  public static void showError(Project project, DbConfig c, String sql, Exception error) {
    String message = message(error);
    addTab(project, EiDbBundle.message("result.tab.error"), AllIcons.General.Error, ResultPanel.error(sql, message));
    Notify.error(project, EiDbBundle.message("result.run.failed", c.name, message));
    ConsoleRun.finished(project, c, sql, false);
  }

  /** 사용자가 멈춘 실행. 오류가 아니므로 알림 없이 탭만 더하고, 같은 접속의 남은 문장은 버린다. */
  public static void showCancelled(Project project, DbConfig c, String sql) {
    JBLabel label = new JBLabel(EiDbBundle.message("result.cancelled", c.name));
    label.setForeground(UiColors.WARNING);
    label.setToolTipText(sql);
    label.setBorder(JBUI.Borders.empty(10, 12));
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(label, BorderLayout.NORTH);
    addTab(project, EiDbBundle.message("result.tab.cancelled"), AllIcons.Actions.Suspend, panel);
    ConsoleRun.finished(project, c, sql, false);
  }

  /** 화면에 보일 오류 문구. 메시지가 없으면 예외 클래스 이름 대신 한국어 문구. */
  static String message(Exception e) {
    return e.getMessage() == null || e.getMessage().isBlank() ? EiDbBundle.message("result.unknown.error") : e.getMessage();
  }

  /** 첫 FROM 뒤의 표 이름(스키마 · 따옴표 제외, 쓴 그대로). 주석 · 문자열 안의 FROM 은 보지 않는다. 없으면 null. */
  static String firstTable(String sql) {
    Matcher m = FROM.matcher(SqlText.code(sql));
    if (!m.find()) return null;
    String name = m.group(1).replace("\"", "");
    return name.substring(name.lastIndexOf('.') + 1);
  }

  /** icon 은 탭 종류(조회 · 변경 · 오류 · 취소)를 탭 이름 앞에 보인다. */
  private static void addTab(Project project, String title, Icon icon, JComponent panel) {
    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    if (window == null) {
      Notify.error(project, EiDbBundle.message("result.window.missing"));
      return;
    }
    Integer last = project.getUserData(COUNTER);
    int n = last == null ? 1 : last + 1;
    project.putUserData(COUNTER, n);
    ContentManager contents = window.getContentManager();
    Content content = ContentFactory.getInstance().createContent(panel, EiDbBundle.message("result.tab.title", n, title), false);
    content.setCloseable(true);
    content.setIcon(icon);
    content.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
    contents.addContent(content);
    while (contents.getContentCount() > MAX_TABS) contents.removeContent(contents.getContent(0), true);
    contents.setSelectedContent(content);
    window.show();
  }
}
