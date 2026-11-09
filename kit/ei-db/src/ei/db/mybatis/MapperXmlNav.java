package ei.db.mybatis;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 매퍼 XML 속성 값에서 Ctrl+클릭 · Ctrl+B: 자바 선언으로.
 * 형식 속성(parameterType 등)은 그 클래스(전체 이름 또는 별칭, {@link DtoFields#find}), mapper namespace 는 매퍼 인터페이스,
 * 문장(select · insert · update · delete) id 는 그 인터페이스의 같은 이름 메서드. 찾지 못하면 다른 이동 처리에 넘긴다.
 */
public final class MapperXmlNav implements GotoDeclarationHandler {
  private static final Set<String> TYPE_ATTRS = Set.of("parameterType", "resultType", "type", "ofType", "javaType");
  private static final String MAPPER_TAG = "mapper";
  private static final String NAMESPACE_ATTR = "namespace";
  private static final String ID_ATTR = "id";

  @Override
  public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement source, int offset, Editor editor) {
    if (source == null) return null;
    PsiFile file = source.getContainingFile();
    if (file == null || !MapperDocs.isMapper(file)) return null;
    XmlAttribute attr = PsiTreeUtil.getParentOfType(source, XmlAttribute.class);
    XmlAttributeValue valueElement = attr == null ? null : attr.getValueElement();
    if (valueElement == null || !valueElement.getTextRange().containsOffset(offset)) return null;
    String value = attr.getValue();
    if (value == null || value.isBlank()) return null;
    value = value.trim();
    Project project = source.getProject();
    String name = attr.getName();
    XmlTag tag = attr.getParent();
    PsiElement target = null;
    if (TYPE_ATTRS.contains(name)) {
      target = DtoFields.find(project, value, file.getVirtualFile());
    } else if (NAMESPACE_ATTR.equals(name) && MAPPER_TAG.equals(tag.getName())) {
      target = mapperClass(project, value);
    } else if (ID_ATTR.equals(name) && MapperDoc.STATEMENT_TAGS.contains(tag.getName())) {
      XmlTag mapper = tag.getParentTag();
      PsiClass c = mapper == null ? null : mapperClass(project, mapper.getAttributeValue(NAMESPACE_ATTR));
      PsiMethod[] ms = c == null ? PsiMethod.EMPTY_ARRAY : c.findMethodsByName(value, true);
      target = ms.length > 0 ? ms[0] : null;
    }
    return target == null ? null : new PsiElement[] {target};
  }

  private static @Nullable PsiClass mapperClass(Project project, @Nullable String namespace) {
    if (namespace == null || namespace.isBlank()) return null;
    return JavaPsiFacade.getInstance(project).findClass(namespace.trim(), GlobalSearchScope.allScope(project));
  }
}
