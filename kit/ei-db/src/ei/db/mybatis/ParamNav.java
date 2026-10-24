package ei.db.mybatis;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;

/**
 * 매퍼 XML 의 #{이름} · ${이름} 첫 마디에서 Ctrl+클릭 · Ctrl+B: parameterType 클래스의 필드 · getter, 또는 매퍼 메서드의 매개변수로.
 * 이름을 찾지 못하거나 bind · foreach 이름이면 다른 이동 처리에 넘긴다.
 */
public final class ParamNav implements GotoDeclarationHandler {
  @Override
  public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement source, int offset, Editor editor) {
    if (source == null) return null;
    PsiFile file = source.getContainingFile();
    if (file == null || !MapperDocs.isMapper(file)) return null;
    MapperDoc doc = MapperDocs.of(file);
    MapperDoc.Node s = doc.statementAt(offset);
    if (s == null) return null;
    MapperParams.Ref ref = MapperParams.refAt(file.getViewProvider().getContents(), s, offset);
    if (ref == null) return null;
    for (StatementParams.Param p : StatementParams.of(source.getProject(), doc, s)) {
      if (p.target() != null && p.name().equals(ref.name())) return new PsiElement[] {p.target()};
    }
    return null;
  }
}
