package ei.db.mybatis;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import ei.db.EiDbBundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 매퍼 인터페이스 ↔ 매퍼 XML 의 빠진 짝 검사. 짝은 namespace = 인터페이스 정규 이름, 문장 id = 메서드 이름.
 * 색인 중에는 검사하지 않는다(두 검사 모두 DumbAware 가 아니다).
 */
final class MapperPairInspection {
  private MapperPairInspection() {}

  private static final String MAPPER_TAG = "<mapper";

  private static ProblemDescriptor[] toArray(List<ProblemDescriptor> list) {
    return list.isEmpty() ? null : list.toArray(ProblemDescriptor.EMPTY_ARRAY);
  }

  /** 매퍼 XML: 인터페이스(있을 때)에 같은 이름 메서드가 없는 문장. 상위 인터페이스 메서드도 짝으로 본다. */
  public static final class Xml extends LocalInspectionTool {
    @Override
    public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
      CharSequence text = file.getViewProvider().getContents();
      if (!StringUtil.contains(text, MAPPER_TAG)) return null;
      Project project = file.getProject();
      if (DumbService.isDumb(project)) return null;
      MapperDoc doc = MapperDocs.of(file);
      if (doc.namespace == null || doc.statements.isEmpty()) return null;
      PsiClass mapper = JavaPsiFacade.getInstance(project).findClass(doc.namespace, GlobalSearchScope.allScope(project));
      if (mapper == null || !mapper.isInterface()) return null;
      Set<String> methods = new HashSet<>();
      for (PsiMethod m : mapper.getAllMethods()) methods.add(m.getName());
      List<String> ids = new ArrayList<>();
      for (MapperDoc.Node s : doc.statements) ids.add(s.attr("id"));
      Set<String> missing = new HashSet<>(LayerRules.withoutPair(ids, methods));
      if (missing.isEmpty()) return null;
      List<ProblemDescriptor> out = new ArrayList<>();
      for (MapperDoc.Node s : doc.statements) {
        ProgressManager.checkCanceled();
        String id = s.attr("id");
        if (!missing.contains(id)) continue;
        PsiElement at = file.findElementAt(s.start + 1); // 여는 태그 이름 토큰
        if (at == null) continue;
        out.add(manager.createProblemDescriptor(at, EiDbBundle.message("inspection.mapper.no.method", mapper.getName(), id),
            (LocalQuickFix) null, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly));
      }
      return toArray(out);
    }
  }

  /**
   * 매퍼 인터페이스: 같은 namespace 의 어느 XML 에도 문장이 없는 메서드. 그 namespace 의 XML 이 하나도 없으면
   * 매퍼가 아닌 것으로 보고 검사하지 않는다. default · static · SQL 어노테이션(@Select 등) 메서드는 건너뛴다.
   */
  public static final class Java extends LocalInspectionTool {
    @Override
    public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
      if (!(file instanceof PsiClassOwner owner)) return null;
      Project project = file.getProject();
      if (DumbService.isDumb(project)) return null;
      List<ProblemDescriptor> out = new ArrayList<>();
      for (PsiClass c : owner.getClasses()) {
        ProgressManager.checkCanceled();
        if (!c.isInterface() || c.isAnnotationType()) continue;
        List<MapperIndex.Entry> xmls = MapperIndex.find(project, c.getQualifiedName());
        if (xmls.isEmpty()) continue;
        Set<String> ids = new HashSet<>();
        for (MapperIndex.Entry e : xmls) ids.addAll(e.ids().keySet());
        for (PsiMethod m : c.getMethods()) {
          if (ids.contains(m.getName())) continue;
          boolean needs = LayerRules.needsStatement(m.hasModifierProperty(PsiModifier.DEFAULT),
              m.hasModifierProperty(PsiModifier.STATIC), LayerNav.annotationNames(m));
          if (!needs) continue;
          PsiElement at = ((PsiNameIdentifierOwner) m).getNameIdentifier();
          if (at == null) continue;
          out.add(manager.createProblemDescriptor(at, EiDbBundle.message("inspection.mapper.no.statement", c.getName(), m.getName()),
              (LocalQuickFix) null, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly));
        }
      }
      return toArray(out);
    }
  }
}
