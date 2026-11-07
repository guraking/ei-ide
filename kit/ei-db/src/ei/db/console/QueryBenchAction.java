package ei.db.console;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import ei.db.DbConfig;
import ei.db.bench.QueryBenchDialog;
import org.jetbrains.annotations.NotNull;

/**
 * 「Query Workbench…」: 도구 메뉴에서는 언제나, 콘솔 오른쪽 단추 메뉴(InConsole)에서는 콘솔일 때만 보인다.
 * 콘솔에서 열면 그 접속을 고르고, 선택 영역(없으면 커서가 있는 문장)을 SQL 칸에 넣어 「쉬운 설명」 탭으로 연다.
 */
public class QueryBenchAction extends DumbAwareAction {
  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(e.getProject() != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (editor == null || !ConsoleOpener.isConsole(file)) {
      QueryBenchDialog.open(project, null, null, null);
      return;
    }
    DbConfig c = ConsoleOpener.config(project, file);
    String selected = editor.getSelectionModel().getSelectedText();
    String sql = selected;
    if (sql == null || sql.isBlank()) {
      SqlSplitter.Stmt stmt = SqlSplitter.at(editor.getDocument().getText(), editor.getCaretModel().getOffset());
      sql = stmt == null ? null : stmt.text();
    }
    QueryBenchDialog.open(project, c, sql, file);
  }

  /** 콘솔 오른쪽 단추 메뉴용: 콘솔 편집기에서만 보인다. */
  public static final class InConsole extends QueryBenchAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabledAndVisible(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null
          && ConsoleOpener.isConsole(e.getData(CommonDataKeys.VIRTUAL_FILE)));
    }
  }
}
