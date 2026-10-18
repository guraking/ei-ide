package local.bootservers;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JSP 편집기 우클릭 「컨트롤러로 이동」.
 * 이 JSP 경로를 prefix · suffix 로 뷰 이름으로 되돌려 프로젝트 자바 파일의 글에서 찾고,
 * @Controller 의 뷰 이름 자리이면서 다시 계산한 JSP 가 이 파일인 것만 고른다. 여럿이면 고르기 창을 띄운다.
 */
public final class GoToControllerAction extends DumbAwareAction {

    private static final String TITLE = "컨트롤러로 이동";
    private static final String JSP_EXTENSION = "jsp";
    private static final String LABEL_SEPARATOR = "  —  ";

    /** 찾은 뷰 이름 글. offset 은 글이 시작하는 위치. */
    private record Found(String label, VirtualFile file, int offset) {
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && isJsp(file));
    }

    private static boolean isJsp(VirtualFile file) {
        return file != null && !file.isDirectory() && JSP_EXTENSION.equalsIgnoreCase(file.getExtension());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile jsp = e.getData(CommonDataKeys.VIRTUAL_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || !isJsp(jsp)) {
            return;
        }
        if (DumbService.isDumb(project)) {
            Messages.showWarningDialog(project, "색인 중에는 컨트롤러를 찾을 수 없습니다. 색인이 끝난 뒤 다시 시도하세요.", TITLE);
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "컨트롤러 찾는 중", true) {
            private List<Found> found;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                found = ReadAction.nonBlocking(() -> collect(project, jsp))
                        .inSmartMode(project)
                        .wrapProgress(indicator)
                        .executeSynchronously();
            }

            @Override
            public void onSuccess() {
                show(project, editor, found);
            }
        });
    }

    private static void show(Project project, Editor editor, List<Found> found) {
        if (found.isEmpty()) {
            String message = "이 JSP 를 뷰로 쓰는 컨트롤러를 찾지 못했습니다.";
            if (editor != null && !editor.isDisposed()) {
                HintManager.getInstance().showInformationHint(editor, message);
            } else {
                Messages.showInfoMessage(project, message, TITLE);
            }
            return;
        }
        if (found.size() == 1) {
            navigate(project, found.get(0));
            return;
        }
        JBPopup popup = JBPopupFactory.getInstance().createPopupChooserBuilder(found)
                .setTitle(TITLE)
                .setRenderer(new SimpleListCellRenderer<Found>() {
                    @Override
                    public void customize(@NotNull JList<? extends Found> list, Found value, int index,
                                          boolean selected, boolean hasFocus) {
                        setText(value.label());
                    }
                })
                .setItemChosenCallback(item -> navigate(project, item))
                .createPopup();
        if (editor != null && !editor.isDisposed()) {
            popup.showInBestPositionFor(editor);
        } else {
            popup.showCenteredInCurrentWindow(project);
        }
    }

    private static void navigate(Project project, Found item) {
        if (item.file().isValid()) {
            new OpenFileDescriptor(project, item.file(), item.offset()).navigate(true);
        }
    }

    /** 읽기 동작 안에서 부른다. */
    private static List<Found> collect(Project project, VirtualFile jsp) {
        Set<String> words = new LinkedHashSet<>();
        SpringWebConfig.ViewSettings settings = SpringWebConfig.viewSettings(project);
        for (VirtualFile root : settings.webRoots()) {
            String relative = root.isValid() ? VfsUtilCore.getRelativePath(jsp, root) : null;
            if (relative == null) {
                continue;
            }
            words.add(relative);
            for (String prefix : settings.prefixes()) {
                for (String suffix : settings.suffixes()) {
                    String name = ViewPath.viewName(prefix, suffix, relative);
                    if (name != null) {
                        words.add(name);
                    }
                }
            }
        }
        GlobalSearchScope scope = GlobalSearchScope.getScopeRestrictedByFileTypes(
                GlobalSearchScope.projectScope(project), JavaFileType.INSTANCE);
        PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
        Set<PsiLiteralExpression> literals = new LinkedHashSet<>();
        for (String word : words) {
            helper.processElementsWithWord((element, offsetInElement) -> {
                ProgressManager.checkCanceled();
                PsiLiteralExpression literal = PsiTreeUtil.getParentOfType(element, PsiLiteralExpression.class, false);
                if (literal != null && !literals.contains(literal) && literal.getValue() instanceof String viewName
                        && ViewNameGotoHandler.isViewLiteral(literal)
                        && ViewNameGotoHandler.resolve(project, viewName).contains(jsp)) {
                    literals.add(literal);
                }
                return true;
            }, scope, word, UsageSearchContext.IN_STRINGS, true);
        }
        List<Found> result = new ArrayList<>();
        for (PsiLiteralExpression literal : literals) {
            VirtualFile file = literal.getContainingFile().getVirtualFile();
            if (file != null) {
                result.add(new Found(label(literal), file, literal.getTextRange().getStartOffset()));
            }
        }
        result.sort(Comparator.comparing(Found::label));
        return result;
    }

    /** 「클래스.메서드()  —  "뷰 이름"」 */
    private static String label(PsiLiteralExpression literal) {
        PsiMethod method = PsiTreeUtil.getParentOfType(literal, PsiMethod.class);
        PsiClass owner = PsiTreeUtil.getParentOfType(literal, PsiClass.class);
        String where = (owner == null ? "" : owner.getName() + ".") + (method == null ? "" : method.getName() + "()");
        return where + LABEL_SEPARATOR + literal.getText();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
