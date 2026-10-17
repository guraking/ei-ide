package ei.db.console;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** SQL 콘솔에서 「별칭.」 을 치면 칼럼 자동완성(기본 동작 「CodeCompletion」)을 바로 띄운다. 글자 입력 때의 자동 띄움은 IDE 기본 동작을 따른다. */
public final class DotAutoPopup extends TypedHandlerDelegate {
  private static final String COMPLETION_ACTION = "CodeCompletion";

  @Override
  public @NotNull Result charTyped(char ch, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    VirtualFile vf = file.getVirtualFile();
    if (ch != '.' || vf == null || ConsoleOpener.config(vf) == null) return Result.CONTINUE;
    AnAction action = ActionManager.getInstance().getAction(COMPLETION_ACTION);
    if (action != null) {
      // 점이 문서에 들어간 뒤에 띄워야 한다.
      ApplicationManager.getApplication().invokeLater(
          () -> ActionManager.getInstance().tryToExecute(action, null, editor.getContentComponent(), "EiDbConsole", true));
    }
    return Result.CONTINUE;
  }
}
