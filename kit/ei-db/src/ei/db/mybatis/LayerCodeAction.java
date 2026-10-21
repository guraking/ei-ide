package ei.db.mybatis;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 편집기 오른쪽 클릭: 매퍼 XML 의 문장 안, 또는 매퍼 인터페이스(같은 namespace 의 XML 이 있고 그 안에 같은 id 문장이 있는)
 * 메서드 안에서 「계층 코드 만들기」 창을 연다.
 */
public final class LayerCodeAction extends AnAction {
  private static final String FINDING = "서비스 · 컨트롤러 클래스 찾는 중";

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    Project project = e.getProject();
    boolean ok = false;
    if (editor != null && project != null) {
      CharSequence text = editor.getDocument().getCharsSequence();
      int offset = editor.getCaretModel().getOffset();
      if (StringUtil.contains(text, "<mapper")) {
        ok = MapperDocs.of(project, editor).statementAt(offset) != null;
      } else if (!DumbService.isDumb(project)) {
        PsiMethod m = mapperMethodAt(e.getData(CommonDataKeys.PSI_FILE), offset);
        ok = m != null && hasStatement(project, m);
      }
    }
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    Project project = e.getProject();
    if (editor == null || project == null) return;
    if (DumbService.isDumb(project)) {
      Notify.warning(project, "색인 중에는 계층 코드를 만들 수 없습니다. 색인이 끝난 뒤 다시 해 주세요.");
      return;
    }
    CharSequence text = editor.getDocument().getImmutableCharSequence();
    int offset = editor.getCaretModel().getOffset();
    VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
    PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
    new Task.Backgroundable(project, FINDING, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        LayerCodeDialog.Info info = ReadAction.nonBlocking(() -> {
          LayerCode.Located at = locate(project, text, offset, file, psiFile);
          return at == null ? null : LayerCodeDialog.gather(project, at);
        }).inSmartMode(project).wrapProgress(indicator).executeSynchronously();
        ApplicationManager.getApplication().invokeLater(() -> {
          if (info == null) Notify.error(project, "커서 위치에서 매퍼 문장(select · insert · update · delete)을 찾지 못했습니다.");
          else new LayerCodeDialog(project, info).show();
        }, project.getDisposed());
      }
    }.queue();
  }

  /** 읽기 접근 안에서 부른다. */
  private static @Nullable LayerCode.Located locate(Project project, CharSequence text, int offset,
                                                    @Nullable VirtualFile file, @Nullable PsiFile psiFile) {
    if (StringUtil.contains(text, "<mapper")) {
      MapperDoc doc = MapperDoc.parse(text);
      MapperDoc.Node s = doc.statementAt(offset);
      return s == null || file == null || doc.namespace == null ? null : new LayerCode.Located(file, doc, s);
    }
    PsiMethod m = mapperMethodAt(psiFile, offset);
    if (m == null || m.getContainingClass() == null) return null;
    return LayerCode.locate(project, m.getContainingClass().getQualifiedName(), m.getName());
  }

  /** 커서를 감싼 인터페이스 메서드. 없으면 null. 읽기 접근 안에서 부른다. */
  private static @Nullable PsiMethod mapperMethodAt(@Nullable PsiFile file, int offset) {
    if (file == null) return null;
    PsiElement at = file.findElementAt(offset);
    PsiMethod m = PsiTreeUtil.getParentOfType(at, PsiMethod.class);
    PsiClass c = m == null ? null : m.getContainingClass();
    return c != null && c.isInterface() && c.getQualifiedName() != null ? m : null;
  }

  private static boolean hasStatement(Project project, PsiMethod m) {
    PsiClass c = m.getContainingClass();
    if (c == null) return false;
    for (MapperIndex.Entry entry : MapperIndex.find(project, c.getQualifiedName())) {
      if (entry.ids().containsKey(m.getName())) return true;
    }
    return false;
  }
}
