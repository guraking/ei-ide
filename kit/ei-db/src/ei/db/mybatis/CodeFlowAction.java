package ei.db.mybatis;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 자바 편집기 우클릭 「호출 흐름 보기」 · 「바꾸면 영향 보기」. 결과는 코드 흐름 도구 창에 뒤에서 찾아 보인다.
 * 색인 중에는 꺼진다(DumbAware 아님). 켜짐 판정은 글자 위치의 PSI 만 보고(영향은 이름 참조 하나 해석) 찾기는 하지 않는다.
 */
public abstract class CodeFlowAction extends AnAction {

  /** 이 동작이 다룰 요소. 없으면 null. 읽기 접근 안에서 부른다. */
  abstract @Nullable PsiElement target(PsiElement at);

  abstract void open(Project project, PsiElement target);

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    PsiElement at = caret(e);
    e.getPresentation().setEnabledAndVisible(at != null && target(at) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    PsiElement at = caret(e);
    PsiElement target = at == null ? null : target(at);
    if (project != null && target != null) open(project, target);
  }

  /** 자바 편집기의 글자 위치 요소. 자바 파일이 아니면 null. */
  private static @Nullable PsiElement caret(AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    if (editor == null || !(file instanceof PsiJavaFile)) return null;
    return file.findElementAt(editor.getCaretModel().getOffset());
  }

  /** 글자 위치를 담은 메서드의 호출 흐름. */
  public static final class Flow extends CodeFlowAction {
    @Override
    @Nullable PsiElement target(PsiElement at) {
      PsiMethod m = PsiTreeUtil.getParentOfType(at, PsiMethod.class);
      return m != null && m.getContainingClass() != null ? m : null;
    }

    @Override
    void open(Project project, PsiElement target) {
      CodeFlowView.showFlow(project, SmartPointerManager.getInstance(project).createSmartPsiElementPointer((PsiMethod) target));
    }
  }

  /**
   * 글자 위치의 메서드 · 필드 · 클래스: 선언 이름 위면 그것, 참조 위면 그 참조가 가리키는 것(프로젝트 밖은 뺌),
   * 아니면 글자 위치를 담은 메서드, 그다음 클래스.
   */
  public static final class Impact extends CodeFlowAction {
    @Override
    @Nullable PsiElement target(PsiElement at) {
      if (at instanceof PsiIdentifier) {
        PsiElement parent = at.getParent();
        if (isTarget(parent)) return parent;
        if (parent instanceof PsiJavaCodeReferenceElement ref) {
          PsiElement resolved = ref.resolve();
          if (isTarget(resolved) && resolved.getContainingFile() != null && resolved.getContainingFile().getVirtualFile() != null
              && resolved.isPhysical() && resolved.getManager().isInProject(resolved)) return resolved;
        }
      }
      PsiMethod m = PsiTreeUtil.getParentOfType(at, PsiMethod.class);
      if (m != null && m.getContainingClass() != null) return m;
      PsiClass c = PsiTreeUtil.getParentOfType(at, PsiClass.class);
      return c != null && c.getQualifiedName() != null ? c : null;
    }

    private static boolean isTarget(@Nullable PsiElement e) {
      if (e instanceof PsiMethod m) return m.getContainingClass() != null;
      if (e instanceof PsiField f) return f.getContainingClass() != null;
      return e instanceof PsiClass c && c.getQualifiedName() != null;
    }

    @Override
    void open(Project project, PsiElement target) {
      CodeFlowView.showImpact(project, SmartPointerManager.getInstance(project).createSmartPsiElementPointer(target));
    }
  }
}
