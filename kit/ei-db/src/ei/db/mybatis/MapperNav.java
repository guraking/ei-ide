package ei.db.mybatis;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.search.GlobalSearchScope;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 매퍼 인터페이스 ↔ 매퍼 XML 이동. 짝은 namespace = 인터페이스 정규 이름, 문장 id = 메서드 이름.
 * 같은 namespace 의 XML 이 여럿이면 처음 찾은 것으로 간다.
 */
final class MapperNav {
  private MapperNav() {}

  /** XML 쪽 위치. offset 은 문장 여는 태그(인터페이스면 0). */
  record Target(VirtualFile file, int offset) {}

  /** 인터페이스 이름이나 그 메서드 이름 토큰이면 대응하는 XML 위치. 아니면 null. 읽기 접근 안에서 부른다. */
  static @Nullable Target xmlTarget(PsiElement element) {
    // PsiIdentifier 는 클래스패스에 상위 형식이 없어 쓰지 못한다. 이름 토큰인지는 소유자에게 묻는다.
    PsiElement parent = element.getParent();
    if (!(parent instanceof PsiNameIdentifierOwner owner) || owner.getNameIdentifier() != element) return null;
    if (parent instanceof PsiMethod m) {
      PsiClass c = m.getContainingClass();
      if (c == null || !c.isInterface()) return null;
      for (MapperIndex.Entry e : MapperIndex.find(element.getProject(), c.getQualifiedName())) {
        Integer at = e.ids().get(m.getName());
        if (at != null) return new Target(e.file(), at);
      }
      return null;
    }
    if (parent instanceof PsiClass c && c.isInterface()) {
      List<MapperIndex.Entry> found = MapperIndex.find(element.getProject(), c.getQualifiedName());
      return found.isEmpty() ? null : new Target(found.get(0).file(), 0);
    }
    return null;
  }

  /** 매퍼 XML 문장 태그 이름 토큰이면 그 id 의 인터페이스 메서드. 아니면 null. 읽기 접근 안에서 부른다. */
  static @Nullable PsiMethod javaTarget(PsiElement element) {
    if (!MyBatisLineMarker.isStatementTagName(element)) return null;
    Project project = element.getProject();
    if (DumbService.isDumb(project)) return null;
    MapperIndex.Entry e = MapperIndex.entry(element.getContainingFile().getViewProvider().getVirtualFile());
    if (e == null || e.namespace() == null) return null;
    int tagStart = element.getPrevSibling().getTextRange().getStartOffset();
    String id = null;
    for (Map.Entry<String, Integer> s : e.ids().entrySet()) {
      if (s.getValue() == tagStart) id = s.getKey();
    }
    if (id == null) return null;
    PsiClass c = JavaPsiFacade.getInstance(project).findClass(e.namespace(), GlobalSearchScope.allScope(project));
    if (c == null) return null;
    PsiMethod[] ms = c.findMethodsByName(id, true);
    return ms.length > 0 ? ms[0] : null;
  }

  /**
   * 화면 스레드에서 부른다. 색인 조회 · 파일 읽기는 짧은 모달 진행(읽기 접근) 안에서 하고 결과만 돌려준다.
   * 찾지 못하면 알리고 null, 사용자가 취소하면 조용히 null.
   */
  private static <T> @Nullable T findWithProgress(Project project, Supplier<T> find) {
    T found;
    try {
      found = ProgressManager.getInstance().runProcessWithProgressSynchronously(
          (ThrowableComputable<T, RuntimeException>) () -> ReadAction.compute(find::get), FINDING, true, project);
    } catch (ProcessCanceledException ex) {
      return null; // 사용자가 진행 창에서 취소한 경우다.
    }
    if (found == null) Notify.info(project, NOT_FOUND);
    return found;
  }

  private static final String FINDING = "이동할 곳 찾는 중";
  private static final String NOT_FOUND = "이동할 곳을 찾지 못했습니다";

  /** 자바 매퍼 인터페이스와 그 메서드 옆: XML 로 이동. */
  public static final class JavaMarker implements LineMarkerProvider {
    private static final String TOOLTIP = "매퍼 XML 로 이동";

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
      if (xmlTarget(element) == null) return null;
      return new LineMarkerInfo<>(element, element.getTextRange(), AllIcons.Gutter.ImplementedMethod,
          e -> TOOLTIP, (mouse, e) -> open(e), GutterIconRenderer.Alignment.LEFT, () -> TOOLTIP);
    }

    /** 누른 때 다시 찾는다(그 사이 XML 이 바뀌었을 수 있다). */
    private static void open(PsiElement e) {
      Project project = e.getProject();
      Target t = findWithProgress(project, () -> xmlTarget(e));
      if (t != null) new OpenFileDescriptor(project, t.file(), t.offset()).navigate(true);
    }
  }

  /** 매퍼 XML 문장 옆: 인터페이스 메서드로 이동. 실행 아이콘(MyBatisLineMarker)과 따로 단다. */
  public static final class XmlMarker implements LineMarkerProvider {
    private static final String TOOLTIP = "인터페이스 메서드로 이동";

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
      if (javaTarget(element) == null) return null;
      return new LineMarkerInfo<>(element, element.getTextRange(), AllIcons.Gutter.ImplementingMethod,
          e -> TOOLTIP, (mouse, e) -> open(e), GutterIconRenderer.Alignment.LEFT, () -> TOOLTIP);
    }

    private static void open(PsiElement e) {
      PsiMethod m = findWithProgress(e.getProject(), () -> javaTarget(e));
      if (m != null) m.navigate(true);
    }
  }

  /** 매퍼 인터페이스 · 메서드 이름에서 Ctrl+클릭 · Ctrl+B: XML 로. */
  public static final class GotoXml implements GotoDeclarationHandler {
    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement source, int offset, Editor editor) {
      if (source == null) return null;
      Target t = xmlTarget(source);
      if (t == null) return null;
      PsiFile xml = PsiManager.getInstance(source.getProject()).findFile(t.file());
      if (xml == null) return null;
      PsiElement at = t.offset() == 0 ? xml : xml.findElementAt(t.offset());
      return at == null ? null : new PsiElement[] {at};
    }
  }
}
