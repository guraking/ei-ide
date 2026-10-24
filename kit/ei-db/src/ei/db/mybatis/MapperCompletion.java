package ei.db.mybatis;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 매퍼 XML 문장 안의 자동 완성.
 * 「#{」 · 「${」 뒤: 그 문장에서 쓸 수 있는 매개변수 이름(StatementParams).
 * select 의 「AS 」 뒤: resultType 클래스 필드 가운데 SELECT 에 아직 없는 것. 필드 이름 그대로 넣고(자동 매핑은 대소문자를 가리지 않는다),
 * 밑줄 대문자(ORDER_ID)로 쳐도 찾는다. resultMap 을 쓰거나 resultType 이 Map · 기본형이면 내지 않는다.
 */
public final class MapperCompletion extends CompletionContributor {
  private static final String SELECT = "select";
  private static final String STATIC = "static";

  @Override
  public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
    if (parameters.getCompletionType() != CompletionType.BASIC) return;
    PsiFile file = parameters.getOriginalFile();
    Project project = file.getProject();
    if (!MapperDocs.isMapper(file)) return;
    CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
    int offset = Math.min(parameters.getOffset(), text.length());
    MapperDoc doc = MapperDocs.of(file);
    MapperDoc.Node s = doc.statementAt(offset);
    if (s == null) return;

    int start = MapperParams.paramPrefixStart(text, offset);
    if (start >= 0) {
      CompletionResultSet rs = result.withPrefixMatcher(text.subSequence(start, offset).toString());
      Set<String> seen = new HashSet<>();
      for (StatementParams.Param p : StatementParams.of(project, doc, s)) {
        if (seen.add(p.name())) rs.addElement(LookupElementBuilder.create(p.name()).withIcon(p.icon()).withTypeText(p.type()));
      }
      rs.stopHere();
      return;
    }

    start = ResultColumns.aliasPrefixStart(text, offset);
    if (start < 0 || !SELECT.equals(s.name) || s.attr("resultMap") != null || DumbService.isDumb(project)) return;
    PsiClass dto = DtoFields.find(project, s.attr("resultType"));
    if (dto == null) return;
    Set<String> used = new HashSet<>();
    List<String> columns = ResultColumns.of(doc.plainText(s));
    if (columns != null) {
      for (String c : columns) used.add(ResultColumns.key(c));
    }
    CompletionResultSet rs = result.withPrefixMatcher(text.subSequence(start, offset).toString()).caseInsensitive();
    for (PsiField f : dto.getAllFields()) {
      if (f.hasModifierProperty(STATIC) || !used.add(ResultColumns.key(f.getName()))) continue;
      rs.addElement(LookupElementBuilder.create(f.getName())
          .withLookupString(WhereGen.column(f.getName()))
          .withIcon(AllIcons.Nodes.Field)
          .withTypeText(f.getType().getPresentableText()));
    }
    rs.stopHere();
  }
}
