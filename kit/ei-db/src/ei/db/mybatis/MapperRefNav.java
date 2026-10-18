package ei.db.mybatis;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 매퍼 XML 의 include refid · resultMap · extends 값에서 Ctrl+클릭 · Ctrl+B: 그 sql · resultMap 선언으로.
 * 네임스페이스가 없거나 자기 네임스페이스면 같은 파일, 다르면 그 네임스페이스의 매퍼 XML(MapperIndex)에서 찾는다.
 */
public final class MapperRefNav implements GotoDeclarationHandler {
  private static final String MAPPER_MARK = "<mapper";

  @Override
  public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement source, int offset, Editor editor) {
    if (source == null) return null;
    PsiFile file = source.getContainingFile();
    if (file == null) return null;
    CharSequence text = file.getViewProvider().getContents();
    if (!StringUtil.contains(text, MAPPER_MARK)) return null;
    MapperRefs.Ref ref = MapperRefs.refAt(text, offset);
    if (ref == null) return null;
    MapperDoc doc = MapperDoc.parse(text);
    if (ref.namespace() == null || ref.namespace().equals(doc.namespace)) {
      PsiElement at = targetIn(file, doc, ref);
      return at == null ? null : new PsiElement[] {at};
    }
    Project project = source.getProject();
    List<PsiElement> found = new ArrayList<>();
    for (MapperIndex.Entry e : MapperIndex.find(project, ref.namespace())) {
      PsiFile other = PsiManager.getInstance(project).findFile(e.file());
      if (other == null) continue;
      PsiElement at = targetIn(other, MapperDoc.parse(other.getViewProvider().getContents()), ref);
      if (at != null) found.add(at);
    }
    return found.isEmpty() ? null : found.toArray(PsiElement[]::new);
  }

  /** 선언 여는 태그의 이름 토큰(태그 시작 다음 글자). 없으면 null. */
  private static @Nullable PsiElement targetIn(PsiFile file, MapperDoc doc, MapperRefs.Ref ref) {
    int at = MapperRefs.declOffset(doc, ref.tag(), ref.id());
    return at < 0 ? null : file.findElementAt(at + 1);
  }
}
