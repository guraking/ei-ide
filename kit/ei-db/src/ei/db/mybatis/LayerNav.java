package ei.db.mybatis;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.Processor;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 컨트롤러 · 서비스 메서드 옆 계층 이동 아이콘. 누르면 그 메서드가 부르는 서비스 메서드(인터페이스면 구현),
 * 매퍼 인터페이스 메서드, 매퍼 XML 문장을 차례로 찾아 하나면 바로 가고 여럿이면 목록을 띄운다.
 * 아이콘은 어노테이션 이름과 "필드.메서드()" 호출 모양만 보고 달며(해석 없음), 찾기는 누를 때 진행 창 안에서 한다.
 * 매퍼 인터페이스 자체에는 달지 않는다(MapperNav 가 맡는다).
 */
public final class LayerNav implements LineMarkerProvider {
  private static final String TOOLTIP = "계층 이동(서비스 · 매퍼 · XML)";
  private static final String TITLE = "계층 이동";
  private static final String FINDING = "호출 계층 찾는 중";
  private static final String NOT_FOUND = "이 메서드가 부르는 서비스 · 매퍼를 찾지 못했습니다";
  private static final String INDEXING = "색인 중에는 계층 이동을 할 수 없습니다";
  private static final String LABEL_BEAN = "서비스";
  private static final String LABEL_MAPPER = "매퍼";
  private static final String LABEL_XML = "XML";
  private static final String INDENT = "    ";

  /** 서비스 → 서비스 → … 로 따라가는 최대 깊이. */
  private static final int MAX_DEPTH = 4;
  /** 인터페이스 메서드 하나에서 찾는 구현 수 상한. */
  private static final int MAX_IMPLS = 5;
  /** 목록 줄 수 상한. */
  private static final int MAX_STEPS = 60;

  /** 목록 한 줄. depth 는 들여쓰기 단계, target 은 누르면 갈 곳. */
  record Step(int depth, String kind, String text, Icon icon, Navigatable target) {
    String label() { return INDENT.repeat(depth) + kind + "  " + text; }
  }

  @Override
  public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
    PsiMethod m = methodOfName(element);
    if (m == null || m.getBody() == null) return null;
    PsiClass c = m.getContainingClass();
    if (c == null || c.isInterface()) return null;
    if (LayerRules.kind(annotationNames(c), annotationNames(m)) == null) return null;
    if (!callsField(c, m)) return null;
    return new LineMarkerInfo<>(element, element.getTextRange(), AllIcons.Hierarchy.Subtypes,
        e -> TOOLTIP, LayerNav::open, GutterIconRenderer.Alignment.LEFT, () -> TOOLTIP);
  }

  /** 메서드 이름 토큰이면 그 메서드. PsiIdentifier 대신 소유자의 이름 토큰과 견준다. */
  private static @Nullable PsiMethod methodOfName(PsiElement element) {
    PsiElement parent = element.getParent();
    if (!(parent instanceof PsiMethod m)) return null;
    return ((PsiNameIdentifierOwner) m).getNameIdentifier() == element ? m : null;
  }

  /** 어노테이션 짧은 이름들(글자 그대로, 해석 없음). */
  static List<String> annotationNames(PsiModifierListOwner owner) {
    List<String> names = new ArrayList<>();
    for (PsiAnnotation a : owner.getAnnotations()) {
      PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
      String name = ref == null ? null : ref.getReferenceName();
      if (name != null) names.add(name);
    }
    return names;
  }

  /** 본문에 "필드.메서드()" 또는 "this.필드.메서드()" 호출이 있으면 true. 필드는 이 클래스에 선언된 것. */
  private static boolean callsField(PsiClass c, PsiMethod m) {
    Set<String> fields = new HashSet<>();
    for (PsiField f : c.getFields()) fields.add(f.getName());
    if (fields.isEmpty()) return false;
    for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(m.getBody(), PsiMethodCallExpression.class)) {
      PsiExpression q = call.getMethodExpression().getQualifierExpression();
      if (!(q instanceof PsiReferenceExpression r)) continue;
      PsiExpression outer = r.getQualifierExpression();
      boolean plain = outer == null || "this".equals(outer.getText());
      if (plain && fields.contains(r.getReferenceName())) return true;
    }
    return false;
  }

  private static void open(MouseEvent mouse, PsiElement element) {
    Project project = element.getProject();
    if (DumbService.isDumb(project)) {
      Notify.info(project, INDEXING);
      return;
    }
    List<Step> steps;
    try {
      steps = ProgressManager.getInstance().runProcessWithProgressSynchronously(
          (ThrowableComputable<List<Step>, RuntimeException>) () -> ReadAction.compute(() -> chain(element)),
          FINDING, true, project);
    } catch (ProcessCanceledException ex) {
      return; // 사용자가 진행 창에서 취소한 경우다.
    } catch (IndexNotReadyException ex) {
      Notify.info(project, INDEXING);
      return;
    }
    if (steps == null || steps.isEmpty()) {
      Notify.info(project, NOT_FOUND);
      return;
    }
    if (steps.size() == 1) {
      navigate(steps.get(0));
      return;
    }
    ListPopup popup = JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<>(TITLE, steps) {
      @Override
      public @NotNull String getTextFor(Step value) { return value.label(); }

      @Override
      public Icon getIconFor(Step value) { return value.icon(); }

      @Override
      public @Nullable PopupStep<?> onChosen(Step value, boolean finalChoice) {
        return doFinalStep(() -> navigate(value));
      }
    });
    if (mouse != null) popup.show(new RelativePoint(mouse));
    else popup.showInFocusCenter();
  }

  private static void navigate(Step step) {
    if (step.target().canNavigate()) step.target().navigate(true);
  }

  /** 읽기 접근 안에서 부른다. 요소가 무효가 됐으면 빈 목록. */
  private static List<Step> chain(PsiElement element) {
    List<Step> out = new ArrayList<>();
    if (!element.isValid()) return out;
    PsiMethod m = methodOfName(element);
    if (m == null) return out;
    Set<PsiMethod> seen = new HashSet<>();
    seen.add(m);
    walk(m, 0, out, seen);
    return out;
  }

  /** method 본문의 "필드.메서드()" 호출을 따라간다. 매퍼면 메서드 · XML 문장, 빈이면 구현 메서드를 담고 그 안으로 들어간다. */
  private static void walk(PsiMethod method, int depth, List<Step> out, Set<PsiMethod> seen) {
    if (depth >= MAX_DEPTH || method.getBody() == null) return;
    Project project = method.getProject();
    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
    for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
      ProgressManager.checkCanceled();
      if (out.size() >= MAX_STEPS) return;
      PsiClass type = qualifierFieldClass(call);
      if (type == null) continue;
      String name = call.getMethodExpression().getReferenceName();
      if (name == null) continue;
      List<MapperIndex.Entry> xmls = type.isInterface() ? MapperIndex.find(project, type.getQualifiedName()) : List.of();
      if (!xmls.isEmpty()) {
        addMapper(project, type, name, xmls, depth, out, seen);
        continue;
      }
      VirtualFile vf = PsiUtilCore.getVirtualFile(type);
      if (vf == null || !scope.contains(vf) || !LayerRules.isBean(type.isInterface(), annotationNames(type))) continue;
      PsiMethod called = call.resolveMethod();
      if (called == null) continue;
      for (PsiMethod target : implementations(called, scope)) {
        if (!seen.add(target)) continue;
        out.add(new Step(depth, LABEL_BEAN, text(target), AllIcons.Nodes.Method, target));
        walk(target, depth + 1, out, seen);
      }
    }
  }

  /** 호출 대상이 필드면 그 필드 형식의 클래스. 아니면 null. */
  private static @Nullable PsiClass qualifierFieldClass(PsiMethodCallExpression call) {
    PsiExpression q = call.getMethodExpression().getQualifierExpression();
    if (!(q instanceof PsiReferenceExpression r) || !(r.resolve() instanceof PsiField f)) return null;
    return f.getType() instanceof PsiClassType t ? t.resolve() : null;
  }

  /** 매퍼 메서드 줄과 그 아래 XML 문장 줄들. 같은 매퍼 메서드는 한 번만. */
  private static void addMapper(Project project, PsiClass mapper, String name, List<MapperIndex.Entry> xmls,
                                int depth, List<Step> out, Set<PsiMethod> seen) {
    PsiMethod[] ms = mapper.findMethodsByName(name, true);
    if (ms.length > 0) {
      if (!seen.add(ms[0])) return;
      out.add(new Step(depth, LABEL_MAPPER, mapper.getName() + "." + name, AllIcons.Nodes.Interface, ms[0]));
    }
    for (MapperIndex.Entry e : xmls) {
      Integer at = e.ids().get(name);
      if (at == null) continue;
      out.add(new Step(depth + 1, LABEL_XML, e.file().getName() + " · " + name, AllIcons.FileTypes.Xml,
          new OpenFileDescriptor(project, e.file(), at)));
    }
  }

  /** 본문이 있으면 그 메서드, 없으면(추상 · 인터페이스) 프로젝트 안 구현(최대 MAX_IMPLS). 구현이 없으면 그 메서드. */
  private static Collection<PsiMethod> implementations(PsiMethod called, GlobalSearchScope scope) {
    if (called.getBody() != null) return List.of(called);
    List<PsiMethod> found = new ArrayList<>();
    OverridingMethodsSearch.search(called, scope, true).forEach((Processor<PsiMethod>) impl -> {
      ProgressManager.checkCanceled();
      if (impl.getBody() != null) found.add(impl);
      return found.size() < MAX_IMPLS;
    });
    return found.isEmpty() ? List.of(called) : found;
  }

  private static String text(PsiMethod m) {
    PsiClass c = m.getContainingClass();
    return (c == null ? "" : c.getName() + ".") + m.getName();
  }
}
