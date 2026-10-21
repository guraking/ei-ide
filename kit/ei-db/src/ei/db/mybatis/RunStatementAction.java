package ei.db.mybatis;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

/** 편집기 오른쪽 클릭 · Ctrl+Alt+Enter: 커서를 감싼 매퍼 문장을 실행 창으로 연다. */
public final class RunStatementAction extends AnAction {
  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    boolean ok = false;
    if (editor != null && e.getProject() != null) {
      CharSequence text = editor.getDocument().getCharsSequence();
      ok = StringUtil.contains(text, "<mapper") && MapperDocs.of(e.getProject(), editor).statementAt(editor.getCaretModel().getOffset()) != null;
    }
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (editor == null || e.getProject() == null) return;
    MyBatisRunDialog.openAt(e.getProject(), editor.getDocument().getCharsSequence(), editor.getCaretModel().getOffset());
  }
}
