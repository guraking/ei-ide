package ei.db.mybatis;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.JBUI;
import ei.db.DbConfig;
import ei.db.DbSettings;
import ei.db.Notify;
import ei.db.SqlRunner;
import ei.db.console.ConsoleOpener;
import ei.db.console.SqlFormatter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 실행 콘솔 오른쪽 클릭 메뉴의 MyBatis 로그 동작들. 선택 글이 있으면 그것을, 없으면 커서 줄과 그 위 줄들을 읽는다.
 * 로그(Preparing)를 찾을 때만 보인다.
 */
public final class LogActions {
  private LogActions() {}

  /** 커서 위로 Preparing 을 찾는 줄 수와, 그 아래로 Parameters 를 찾는 줄 수. */
  private static final int LOOK_BACK = 200;
  private static final int LOOK_AHEAD = 20;
  /** 쿼리 목록에 띄울 최대 수(최근 것부터). */
  private static final int MAX_PICK = 50;
  /** 같은 쿼리를 한 줄로 줄이기 전에 콘솔 끝에서부터 읽는 쿼리 로그 수. */
  private static final int MAX_SCAN = 1000;
  /** 고르기 창 강조 테두리 두께(px). */
  private static final int POPUP_BORDER = 2;
  private static final String NEW_CONSOLE = "새 콘솔…";

  static Editor editor(AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (editor != null) return editor;
    ConsoleView console = e.getData(LangDataKeys.CONSOLE_VIEW);
    return console instanceof ConsoleViewImpl impl ? impl.getEditor() : null;
  }

  static MyBatisLog.Entry find(AnActionEvent e) {
    Editor editor = editor(e);
    if (editor == null) return null;
    String selected = editor.getSelectionModel().getSelectedText();
    if (selected != null && !selected.isBlank()) return MyBatisLog.parse(selected);
    Document doc = editor.getDocument();
    if (doc.getLineCount() == 0) return null;
    int caretLine = doc.getLineNumber(Math.min(editor.getCaretModel().getOffset(), doc.getTextLength()));
    int from = Math.max(0, caretLine - LOOK_BACK);
    int to = Math.min(doc.getLineCount() - 1, caretLine + LOOK_AHEAD);
    CharSequence text = doc.getImmutableCharSequence();
    List<String> lines = new ArrayList<>();
    for (int k = from; k <= to; k++) lines.add(text.subSequence(doc.getLineStartOffset(k), doc.getLineEndOffset(k)).toString());
    MyBatisLog.Entry entry = MyBatisLog.parse(lines, caretLine - from);
    if (entry != null) return entry;
    // MyBatis 로그가 없으면 log4jdbc sqltiming 로그(값이 채워진 SQL)를 찾는다.
    List<MyBatisLog.Entry> timing = MyBatisLog.parseAll(lines, 1);
    return timing.isEmpty() ? null : timing.get(0);
  }

  /** 마지막에 쓴 접속을 앞에 둔 목록. */
  static List<DbConfig> connections(Project project) {
    List<DbConfig> all = new ArrayList<>(DbSettings.getInstance().connections());
    DbConfig last = DbSettings.getInstance().find(PropertiesComponent.getInstance(project).getValue(MyBatisRunDialog.CONNECTION_KEY, ""));
    if (last != null && all.remove(last)) all.add(0, last);
    return all;
  }

  /** 접속이 하나면 바로, 여럿이면 고르는 목록을 띄운다. */
  static void chooseConnection(Project project, Editor editor, Consumer<DbConfig> then) {
    List<DbConfig> list = connections(project);
    if (list.isEmpty()) {
      Notify.error(project, "접속이 없습니다. 먼저 데이터베이스 접속을 추가하세요.");
      return;
    }
    if (list.size() == 1) {
      then.accept(list.get(0));
      return;
    }
    showCentered(JBPopupFactory.getInstance().createPopupChooserBuilder(list)
        .setTitle("실행할 접속")
        .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("", MyBatisRunDialog::connectionLabel))
        .setItemChosenCallback(then::accept)
        .createPopup(), project);
  }

  /**
   * 보낼 쿼리를 고른다. 선택 글이 있으면 그것을 읽고, 하나뿐이면 묻지 않고 run 이면 실행, 아니면 콘솔로 보낸다.
   * 여럿이면 QueryPickDialog(같은 쿼리는 최근 것 하나, 최근 것이 위, 커서 위 가장 가까운 것을 미리 고름)에서
   * 「콘솔로 보내기」 · 「실행」을 고른다. 쿼리가 없으면 아무것도 하지 않는다.
   */
  static void chooseEntry(Project project, Editor editor, boolean run) {
    String selected = editor.getSelectionModel().getSelectedText();
    if (selected != null && !selected.isBlank()) {
      MyBatisLog.Entry entry = MyBatisLog.parse(selected);
      if (entry != null) sendOrRun(project, editor, entry, run);
      return;
    }
    Document doc = editor.getDocument();
    List<MyBatisLog.Entry> found = new ArrayList<>(MyBatisLog.distinct(MyBatisLog.parseAll(List.of(doc.getText().split("\n", -1)), MAX_SCAN), MAX_PICK));
    if (found.isEmpty()) return;
    if (found.size() == 1) {
      sendOrRun(project, editor, found.get(0), run);
      return;
    }
    Collections.reverse(found);
    int caretLine = doc.getLineNumber(Math.min(editor.getCaretModel().getOffset(), doc.getTextLength()));
    MyBatisLog.Entry near = found.get(0);
    for (MyBatisLog.Entry entry : found) {
      if (entry.line <= caretLine) {
        near = entry;
        break;
      }
    }
    QueryPickDialog dialog = new QueryPickDialog(project, found, near);
    dialog.show();
    MyBatisLog.Entry chosen = dialog.selected();
    if (chosen == null) return;
    if (dialog.getExitCode() == QueryPickDialog.OK_EXIT_CODE) sendOrRun(project, editor, chosen, false);
    else if (dialog.getExitCode() == QueryPickDialog.RUN_EXIT) sendOrRun(project, editor, chosen, true);
  }

  /** 콘솔을 골라 로그 값을 채운 SQL 을 붙이고, run 이면 그 접속으로 실행까지 한다. */
  private static void sendOrRun(Project project, Editor editor, MyBatisLog.Entry entry, boolean run) {
    chooseConsole(project, editor, t -> {
      t.append(project, SqlValues.inline(entry.sql, entry.values, t.config().kind));
      if (run) SqlRunner.run(project, t.config(), entry.sql, new ArrayList<>(entry.values));
    });
  }

  /** 고르기 창(콘솔 · 접속)은 로그 글과 섞이지 않게 IDE 창 가운데에, 강조색 테두리를 둘러 띄운다. */
  private static void showCentered(JBPopup popup, Project project) {
    popup.getContent().setBorder(JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), POPUP_BORDER));
    popup.showCenteredInCurrentWindow(project);
  }

  /** 보낼 곳. file 이 있으면 그 콘솔, 없으면 그 접속의 마지막 콘솔(없으면 새로), fresh 면 그 접속의 새 콘솔. */
  record Target(DbConfig config, VirtualFile file, boolean fresh) {
    /** sql 은 콘솔 「정렬」과 같은 SqlFormatter 로 정렬해 붙인다. 정렬에 실패하면 알리고 받은 그대로 붙인다. */
    void append(Project project, String raw) {
      String sql;
      try {
        sql = SqlFormatter.format(raw);
      } catch (RuntimeException ex) {
        Notify.warning(project, "SQL 정렬에 실패했습니다(" + ex + "). 정렬하지 않은 SQL 을 붙입니다.");
        sql = raw;
      }
      PropertiesComponent.getInstance(project).setValue(MyBatisRunDialog.CONNECTION_KEY, config.id);
      if (file != null) {
        ConsoleOpener.append(project, file, sql);
        return;
      }
      if (fresh) ConsoleOpener.openNew(project, config);
      ConsoleOpener.open(project, config, sql);
    }
  }

  /** 보낼 콘솔을 고른다. 열린 콘솔 탭 끝에 「새 콘솔…」(접속을 고른다)을 둔다. 열린 콘솔이 없으면 접속만 고른다. */
  static void chooseConsole(Project project, Editor editor, Consumer<Target> then) {
    List<Object> items = new ArrayList<>(ConsoleOpener.openConsoles(project));
    if (items.isEmpty()) {
      chooseConnection(project, editor, c -> then.accept(new Target(c, null, false)));
      return;
    }
    items.add(NEW_CONSOLE);
    showCentered(JBPopupFactory.getInstance().createPopupChooserBuilder(items)
        .setTitle("보낼 콘솔")
        .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("", v -> v instanceof VirtualFile f ? f.getNameWithoutExtension() : NEW_CONSOLE))
        .setItemChosenCallback(v -> {
          if (!(v instanceof VirtualFile f)) {
            chooseConnection(project, editor, c -> then.accept(new Target(c, null, true)));
            return;
          }
          DbConfig c = ConsoleOpener.connection(project, f);
          if (c == null) Notify.error(project, "이 콘솔의 접속이 지워졌습니다: " + f.getName());
          else then.accept(new Target(c, f, false));
        })
        .createPopup(), project);
  }

  private abstract static class LogAction extends AnAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabledAndVisible(e.getProject() != null && find(e) != null);
    }
  }

  /** 「데이터베이스 콘솔에서 실행」: 쿼리 · 콘솔을 골라 그 콘솔 끝에 붙이고 그 접속으로 실행한다. */
  public static final class Run extends LogAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      Editor editor = editor(e);
      if (project == null || editor == null) return;
      chooseEntry(project, editor, true);
    }
  }

  /**
   * 「쿼리 콘솔로 보내기」: 실행하지 않고 고른 콘솔 끝에 SQL 을 붙인다(chooseConsole).
   * MyBatis 로그(선택 글, 없으면 고른 쿼리)면 ? 를 로그 값으로 채운 SQL, 아니면 선택한 글 그대로. 둘 다 없으면 보이지 않는다.
   */
  public static final class Send extends AnAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabledAndVisible(e.getProject() != null && (find(e) != null || selected(e) != null));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      Editor editor = editor(e);
      if (project == null || editor == null) return;
      String selected = selected(e);
      if (selected != null && MyBatisLog.parse(selected) == null) {
        chooseConsole(project, editor, t -> t.append(project, selected));
        return;
      }
      chooseEntry(project, editor, false);
    }

    /** 공백이 아닌 선택 글. 없으면 null. */
    private static String selected(AnActionEvent e) {
      Editor editor = editor(e);
      String text = editor == null ? null : editor.getSelectionModel().getSelectedText();
      return text == null || text.isBlank() ? null : text;
    }
  }
}
