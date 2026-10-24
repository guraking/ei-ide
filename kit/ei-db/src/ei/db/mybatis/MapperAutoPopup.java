package ei.db.mybatis;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** 매퍼 XML 에서 「#{」 · 「${」 나 「AS 」 를 치면 자동 완성(MapperCompletion)을 바로 띄운다. 낼 것이 없으면 목록은 뜨지 않는다. */
public final class MapperAutoPopup extends TypedHandlerDelegate {
  @Override
  public @NotNull Result charTyped(char ch, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    if ((ch != '{' && ch != ' ') || !MapperDocs.isMapper(file)) return Result.CONTINUE;
    CharSequence text = editor.getDocument().getCharsSequence();
    int offset = editor.getCaretModel().getOffset();
    boolean param = ch == '{' && MapperParams.paramPrefixStart(text, offset) == offset;
    boolean alias = ch == ' ' && ResultColumns.aliasPrefixStart(text, offset) == offset;
    if (param || alias) AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
    return Result.CONTINUE;
  }
}
