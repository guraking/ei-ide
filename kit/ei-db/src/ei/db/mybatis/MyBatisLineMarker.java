package ei.db.mybatis;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 매퍼 XML 의 select · insert · update · delete 여는 태그 이름 옆에 실행 아이콘을 단다.
 * XML PSI 클래스가 없어 토큰 종류 이름(XML_NAME, XML_START_TAG_START)과 글자로 판정한다.
 */
public final class MyBatisLineMarker implements LineMarkerProvider {
  private static final String TOOLTIP = "MyBatis 쿼리 실행";

  @Override
  public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
    if (!isStatementTagName(element)) return null;
    return new LineMarkerInfo<>(element, element.getTextRange(), AllIcons.RunConfigurations.TestState.Run,
        e -> TOOLTIP,
        (mouse, e) -> MyBatisRunDialog.openAt(e.getProject(), e.getContainingFile().getViewProvider().getContents(), e.getTextOffset()),
        GutterIconRenderer.Alignment.CENTER, () -> TOOLTIP);
  }

  /** 매퍼 XML 안 select · insert · update · delete 여는 태그의 이름 토큰이면 true. */
  static boolean isStatementTagName(PsiElement element) {
    if (element.getFirstChild() != null || !MapperDoc.STATEMENT_TAGS.contains(element.getText())) return false;
    if (!"XML_NAME".equals(typeName(element))) return false;
    PsiElement prev = element.getPrevSibling();
    if (prev == null || !"XML_START_TAG_START".equals(typeName(prev))) return false;
    PsiFile file = element.getContainingFile();
    return file != null && StringUtil.contains(file.getViewProvider().getContents(), "<mapper");
  }

  private static String typeName(PsiElement e) {
    ASTNode node = e.getNode();
    return node == null ? null : node.getElementType().toString();
  }
}
