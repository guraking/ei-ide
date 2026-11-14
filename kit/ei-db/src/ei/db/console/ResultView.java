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
import ei.db.bench.SqlParts;
import org.jetbrains.annotations.NotNull;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.regex.Pattern;

/**
 * 아래 「결과」 창. 실행마다 탭 「결과 N · 표 이름」을 더하고 오래된 것부터 지워 MAX_TABS 개만 둔다.
 * 같은 접속에서 같은 문장(sameKey)을 다시 실행하면 새 탭 대신 그 탭의 내용 · 이름 · 아이콘을 바꾼다.
 * 실행 결과 · 오류 · 취소는 한 탭을 함께 쓰고, 「느린 곳」 탭은 따로 쓴다. 화면 스레드에서 불린다.
 */
public final class ResultView {
  private ResultView() {}

  static final String TOOL_WINDOW_ID = "EI Database Result";
  private static final int MAX_TABS = 10;
  private static final Key<Integer> COUNTER = Key.create("ei.db.console.resultCounter");
  /** 탭이 보이는 결과의 접속 · 문장. 같은 값이면 그 탭을 다시 쓴다. */
  private static final Key<String> SAME = Key.create("ei.db.console.resultSame");
  static final String RUN = "run";
  static final String PLAN = "plan";
  private static final String CHANGE = "change";
  private static final Pattern BLANKS = Pattern.compile("\\s+");
  private static final Pattern TRAILING = Pattern.compile("[\\s;]+$");

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
    if (!result.isQuery()) {
      showChange(project, c, result);
      ConsoleRun.finished(project, c, result.sql(), true);
      return;
    }
    String title = result.isQuery() ? firstTable(result.sql()) : EiDbBundle.message("result.tab.update");
    Icon icon = result.isQuery() ? AllIcons.Nodes.DataTables : AllIcons.Actions.Commit;
    String tabTitle = tabTitle(project, title == null ? EiDbBundle.message("result.tab.query") : title);
    addTab(project, sameKey(RUN, c.id, result.sql()), tabTitle, icon, new ResultPanel(project, c, result, firstTable(result.sql()), tabTitle));
    ConsoleRun.finished(project, c, result.sql(), true);
  }

  /**
   * 변경 문장의 결과는 접속마다 「변경」 탭 하나에 줄로 쌓는다(문장 100개를 실행해도 탭은 하나).
   * 그 탭을 닫았거나 MAX_TABS 로 밀려났으면 새로 만든다.
   */
  private static void showChange(Project project, DbConfig c, QueryResult result) {
    boolean pending = !ei.db.DbSessions.get(project).autoCommit(c);
    String same = sameKey(CHANGE, c.id, null);
    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    if (window != null) {
      for (Content old : window.getContentManager().getContents()) {
        if (same.equals(old.getUserData(SAME)) && old.getComponent() instanceof ChangeLog log) {
          log.add(result, pending);
          window.getContentManager().setSelectedContent(old);
          window.show();
          return;
        }
      }
    }
    ChangeLog log = new ChangeLog();
    log.add(result, pending);
    addTab(project, same, tabTitle(project, EiDbBundle.message("result.tab.update")), AllIcons.Actions.Commit, log);
  }

  public static void showError(Project project, DbConfig c, String sql, Exception error) {
    String message = message(error);
    JComponent panel = ResultPanel.error(sql, message);
    JComponent help = ErrorHelp.panel(project, c, sql, error);
    if (help != null) {
      JPanel withHelp = new JPanel(new BorderLayout());
      withHelp.add(help, BorderLayout.NORTH);
      withHelp.add(panel, BorderLayout.CENTER);
      panel = withHelp;
    }
    addTab(project, sameKey(RUN, c.id, sql), tabTitle(project, EiDbBundle.message("result.tab.error")), AllIcons.General.Error, panel);
    Notify.error(project, EiDbBundle.message("result.run.failed", c.name, message));
    ConsoleRun.finished(project, c, sql, false);
  }

  /** 「느린 곳」 탭. 심각한 곳이 있으면 오류 아이콘, 주의만 있으면 경고 아이콘. */
  public static void showPlan(Project project, DbConfig c, ExplainPlan.Plan plan) {
    String name = firstTable(plan.sql());
    String title = tabTitle(project, EiDbBundle.message(plan.actual() ? "plan.tab.actual" : "plan.tab",
        name == null ? EiDbBundle.message("result.tab.query") : name));
    java.util.Collection<PlanAdvisor.Level> levels = PlanAdvisor.levels(plan.findings()).values();
    Icon icon = levels.contains(PlanAdvisor.Level.DANGER) ? AllIcons.General.Error
        : levels.isEmpty() ? AllIcons.Actions.Preview : AllIcons.General.Warning;
    addTab(project, sameKey(PLAN, c.id, plan.sql()), title, icon, new PlanPanel(project, c, plan));
  }

  /** 사용자가 멈춘 실행. 오류가 아니므로 알림 없이 탭만 더하고, 같은 접속의 남은 문장은 버린다. */
  public static void showCancelled(Project project, DbConfig c, String sql) {
    JBLabel label = new JBLabel(EiDbBundle.message("result.cancelled", c.name));
    label.setForeground(UiColors.WARNING);
    label.setToolTipText(sql);
    label.setBorder(JBUI.Borders.empty(10, 12));
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(label, BorderLayout.NORTH);
    addTab(project, sameKey(RUN, c.id, sql), tabTitle(project, EiDbBundle.message("result.tab.cancelled")), AllIcons.Actions.Suspend, panel);
    ConsoleRun.finished(project, c, sql, false);
  }

  /** 화면에 보일 오류 문구. 메시지가 없으면 예외 클래스 이름 대신 한국어 문구. */
  public static String message(Exception e) {
    return e.getMessage() == null || e.getMessage().isBlank() ? EiDbBundle.message("result.unknown.error") : e.getMessage();
  }

  /** 바깥 문장의 첫 FROM 첫 표 이름(스키마 · 따옴표 제외, 쓴 그대로). 규칙은 SqlParts.firstTable. 없으면 null. */
  static String firstTable(String sql) {
    String ref = tableRef(sql);
    return ref == null ? null : ref.replace("\"", "");
  }

  /** firstTable 과 같은 표를 쓴 그대로의 따옴표와 함께(스키마 제외). 복사한 INSERT · UPDATE 에 쓴다. 없으면 null. */
  static String tableRef(String sql) {
    return SqlParts.firstTable(sql);
  }

  /** 다음 번호를 붙인 탭 이름 「결과 N · 이름」. 결과 패널의 머리줄에도 같은 글을 쓴다. */
  private static String tabTitle(Project project, String name) {
    Integer last = project.getUserData(COUNTER);
    int n = last == null ? 1 : last + 1;
    project.putUserData(COUNTER, n);
    return EiDbBundle.message("result.tab.title", n, name);
  }

  /**
   * 같은 탭을 다시 쓸지 가르는 값: 탭 종류 · 접속 id · 문장. 문장은 공백 · 줄바꿈을 한 칸으로 줄이고 끝의 「;」 · 공백을 뺀다.
   * 대소문자 · 주석이 다르면 다른 문장이다. sql 이 null 이면 빈 문장으로 본다.
   */
  static String sameKey(String kind, String connectionId, String sql) {
    String text = sql == null ? "" : TRAILING.matcher(BLANKS.matcher(sql.strip()).replaceAll(" ")).replaceAll("");
    return kind + "\n" + connectionId + "\n" + text;
  }

  /**
   * icon 은 탭 종류(조회 · 변경 · 오류 · 취소)를 탭 이름 앞에 보인다. title 은 tabTitle 로 번호를 붙인 글.
   * same 이 같은 탭이 있으면 그 자리에서 내용을 바꾸고, 없으면 새 탭을 끝에 더한다.
   */
  private static void addTab(Project project, String same, String title, Icon icon, JComponent panel) {
    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    if (window == null) {
      Notify.error(project, EiDbBundle.message("result.window.missing"));
      return;
    }
    ContentManager contents = window.getContentManager();
    for (Content old : contents.getContents()) {
      if (same.equals(old.getUserData(SAME))) {
        old.setComponent(panel);
        old.setDisplayName(title);
        old.setIcon(icon);
        contents.setSelectedContent(old);
        window.show();
        return;
      }
    }
    Content content = ContentFactory.getInstance().createContent(panel, title, false);
    content.putUserData(SAME, same);
    content.setCloseable(true);
    content.setIcon(icon);
    content.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
    contents.addContent(content);
    while (contents.getContentCount() > MAX_TABS) contents.removeContent(contents.getContent(0), true);
    contents.setSelectedContent(content);
    window.show();
  }
}
