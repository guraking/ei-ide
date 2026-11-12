package ei.db.mybatis;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;

/** 편집기 오른쪽 클릭 · Ctrl+Alt+Enter: 커서를 감싼 매퍼 문장을 실행 창으로 연다. */
public class RunStatementAction extends AnAction {
  /** 편집기 오른쪽 클릭 「Find Slow Spots」: 같은 창에서 값을 받아 실행 계획의 느린 곳을 찾는다. */
  public static final class FindSlow extends RunStatementAction {
    @Override
    boolean findSlow() { return true; }
  }

  boolean findSlow() { return false; }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    boolean ok = false;
    if (editor != null && e.getProject() != null) {
      CharSequence text = editor.getDocument().getCharsSequence();
      ok = MapperDocs.isMapper(text) && MapperDocs.of(e.getProject(), editor).statementAt(editor.getCaretModel().getOffset()) != null;
    }
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (editor == null || e.getProject() == null) return;
    MyBatisRunDialog.openAt(e.getProject(), editor.getDocument().getCharsSequence(), editor.getCaretModel().getOffset(), findSlow());
  }
}
