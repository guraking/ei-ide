package local.bootservers;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiConditionalExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @Controller 클래스의 뷰 이름 글(핸들러 메서드가 돌려주는 글, new ModelAndView("x"), setViewName("x"))을
 * Ctrl+클릭하면 그 JSP 로 간다. 뷰 경로는 SpringWebConfig 의 prefix · suffix 로 계산하고 모든 웹 뿌리에서 찾는다.
 */
public final class ViewNameGotoHandler implements GotoDeclarationHandler {

    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String MODEL_AND_VIEW = "ModelAndView";
    private static final String SET_VIEW_NAME = "setViewName";

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null || DumbService.isDumb(sourceElement.getProject())
                || !(sourceElement.getParent() instanceof PsiLiteralExpression literal)
                || !(literal.getValue() instanceof String viewName) || !isViewLiteral(literal)) {
            return null;
        }
        Project project = literal.getProject();
        PsiManager manager = PsiManager.getInstance(project);
        List<PsiElement> targets = new ArrayList<>();
        for (VirtualFile file : resolve(project, viewName)) {
            PsiFile psi = manager.findFile(file);
            if (psi != null) {
                targets.add(psi);
            }
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }

    /** 뷰 이름이 가리키는 JSP 파일들(prefix · suffix 조합 × 웹 뿌리). */
    static List<VirtualFile> resolve(Project project, String viewName) {
        SpringWebConfig.ViewSettings settings = SpringWebConfig.viewSettings(project);
        Set<VirtualFile> result = new LinkedHashSet<>();
        for (String prefix : settings.prefixes()) {
            for (String suffix : settings.suffixes()) {
                String path = ViewPath.resolve(prefix, suffix, viewName);
                if (path == null) {
                    continue;
                }
                for (VirtualFile root : settings.webRoots()) {
                    VirtualFile file = root.isValid() ? root.findFileByRelativePath(path.substring(1)) : null;
                    if (file != null && !file.isDirectory()) {
                        result.add(file);
                    }
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** @Controller 클래스 안에서 뷰 이름 자리에 놓인 글인가. */
    static boolean isViewLiteral(PsiLiteralExpression literal) {
        PsiElement child = literal;
        PsiElement parent = literal.getParent();
        while (parent instanceof PsiParenthesizedExpression
                || parent instanceof PsiConditionalExpression conditional && conditional.getCondition() != child) {
            child = parent;
            parent = parent.getParent();
        }
        boolean viewPosition;
        if (parent instanceof PsiReturnStatement) {
            viewPosition = PsiTreeUtil.getParentOfType(parent, PsiMethod.class, PsiLambdaExpression.class) instanceof PsiMethod;
        } else if (parent instanceof PsiExpressionList arguments && arguments.getExpressions().length > 0
                && arguments.getExpressions()[0] == child) {
            viewPosition = isModelAndViewNew(arguments.getParent()) || isSetViewName(arguments.getParent());
        } else {
            viewPosition = false;
        }
        if (!viewPosition) {
            return false;
        }
        PsiClass owner = PsiTreeUtil.getParentOfType(literal, PsiClass.class);
        return owner != null && AnnotationUtil.isAnnotated(owner, CONTROLLER, 0);
    }

    private static boolean isModelAndViewNew(PsiElement element) {
        if (!(element instanceof PsiNewExpression newExpression)) {
            return false;
        }
        PsiJavaCodeReferenceElement reference = newExpression.getClassReference();
        return reference != null && MODEL_AND_VIEW.equals(reference.getReferenceName());
    }

    private static boolean isSetViewName(PsiElement element) {
        return element instanceof PsiMethodCallExpression call
                && SET_VIEW_NAME.equals(call.getMethodExpression().getReferenceName());
    }
}
