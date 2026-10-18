package ei.db.mybatis;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 매퍼 인터페이스(같은 namespace 의 매퍼 XML 이 있는) 형식 필드를 가진 자바 클래스에서, 멤버 선언이 시작될 자리의 자동 완성에
 * 그 매퍼의 문장 · 메서드 가운데 이 클래스에 아직 같은 이름 메서드가 없는 것을 낸다. 고르면 서비스 메서드 전체를 넣는다.
 * 후보를 고를 때는 필드 형식 · 매퍼 메서드 이름 · MapperIndex 의 id 만 보고, XML 을 읽는 일은 넣을 때 한다.
 */
public final class ServiceMethodCompletion extends CompletionContributor {
  /** 앞 글자가 이것이면 멤버 선언이 시작될 자리다(클래스 여는 괄호, 앞 멤버의 끝). */
  private static final String MEMBER_START_CHARS = "{};";
  private static final String TAIL_PREFIX = "  서비스 메서드 → ";

  @Override
  public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
    if (parameters.getCompletionType() != CompletionType.BASIC) return;
    PsiElement pos = parameters.getPosition();
    Project project = pos.getProject();
    if (DumbService.isDumb(project)) return;
    PsiClass cls = memberLevelClass(pos, parameters.getEditor().getDocument());
    if (cls == null || cls.isInterface()) return;
    Set<String> offered = new LinkedHashSet<>();
    for (PsiField f : cls.getFields()) {
      if (!(f.getType() instanceof PsiClassType ct)) continue;
      PsiClass mapper = ct.resolve();
      if (mapper == null || !mapper.isInterface() || mapper.getQualifiedName() == null) continue;
      List<MapperIndex.Entry> entries = MapperIndex.find(project, mapper.getQualifiedName());
      if (entries.isEmpty()) continue;
      Set<String> ids = new LinkedHashSet<>();
      for (PsiMethod m : mapper.getMethods()) ids.add(m.getName());
      for (MapperIndex.Entry e : entries) ids.addAll(e.ids().keySet());
      for (String id : ids) {
        if (cls.findMethodsByName(id, false).length > 0 || !offered.add(id)) continue;
        result.addElement(LookupElementBuilder.create(id)
            .withIcon(AllIcons.Nodes.Method)
            .withTailText(TAIL_PREFIX + f.getName(), true)
            .withTypeText(mapper.getName())
            .withInsertHandler(new Insert(f.getName(), mapper.getQualifiedName(), id)));
      }
    }
  }

  /**
   * 커서가 클래스 몸통에서 멤버 선언이 시작될 자리면 그 클래스. 메서드 본문 · 식 · 매개변수 목록 안이거나
   * 앞 글자(공백 제외)가 { } ; 가 아니면(앞에 형식 · 수식어 · 주석이 있으면) null.
   */
  private static @Nullable PsiClass memberLevelClass(PsiElement pos, Document document) {
    PsiElement scope = PsiTreeUtil.getParentOfType(pos, PsiClass.class, PsiCodeBlock.class, PsiExpression.class, PsiParameterList.class);
    if (!(scope instanceof PsiClass cls)) return null;
    // shortcut: 멤버 첫머리 판단을 앞 글자로 한다. 주석 · 어노테이션 바로 뒤에서는 후보가 나오지 않는다. 필요해지면 PSI 앞 형제로 본다.
    CharSequence text = document.getCharsSequence();
    int i = Math.min(pos.getTextRange().getStartOffset(), text.length()) - 1;
    while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
    return i >= 0 && MEMBER_START_CHARS.indexOf(text.charAt(i)) >= 0 ? cls : null;
  }

  /** 고른 이름을 서비스 메서드 전체로 바꾼다. 쓰기 동작 안에서 불린다. */
  private record Insert(String mapperField, String namespace, String id) implements InsertHandler<LookupElement> {
    @Override
    public void handleInsert(@NotNull InsertionContext ctx, @NotNull LookupElement item) {
      Project project = ctx.getProject();
      ServiceCode.Sig sig = sig(project);
      if (sig == null) {
        Notify.warning(project, id + " 문장을 매퍼 인터페이스 · XML 에서 찾지 못해 이름만 넣었습니다.");
        return;
      }
      String text = ServiceCode.serviceMethod(mapperField, id, sig);
      Document doc = ctx.getDocument();
      doc.replaceString(ctx.getStartOffset(), ctx.getTailOffset(), text);
      RangeMarker range = doc.createRangeMarker(ctx.getStartOffset(), ctx.getStartOffset() + text.length());
      PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
      pdm.commitDocument(doc);
      PsiFile file = ctx.getFile();
      try {
        LayerCode.importAll(file, text, ServiceCode.importsOf(sig));
        pdm.doPostponedOperationsAndUnblockDocument(doc);
        pdm.commitDocument(doc);
        if (range.isValid()) CodeStyleManager.getInstance(project).reformatText(file, range.getStartOffset(), range.getEndOffset());
      } catch (IncorrectOperationException ex) {
        Notify.warning(project, "서비스 메서드는 넣었지만 들여오기 · 줄 맞춤을 하지 못했습니다: " + ex.getMessage());
      } finally {
        range.dispose();
      }
    }

    /** 매퍼 인터페이스 메서드가 있으면 그 모양, 없으면 XML 로 짐작. 둘 다 없으면 null. */
    private @Nullable ServiceCode.Sig sig(Project project) {
      PsiMethod m = LayerCode.mapperMethod(project, namespace, id);
      if (m != null) return LayerCode.sigOf(m);
      LayerCode.Located at = LayerCode.locate(project, namespace, id);
      return at == null ? null : LayerCode.sigFor(project, at.doc(), at.statement());
    }
  }
}
