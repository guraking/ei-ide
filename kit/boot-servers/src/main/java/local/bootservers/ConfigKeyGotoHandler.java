package local.bootservers;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 주석 속성 글 안의 ${키:기본값}(@Value("${a.b}"), @Value("#{'${a.b}'}"), @Scheduled(cron = "${x}") 등)을 Ctrl+클릭하면
 * 프로젝트 리소스 폴더의 application*.yml · yaml · properties 마다 그 키 줄로 간다(프로필 파일마다 따로 고를 수 있다).
 * 키 비교는 느슨한 바인딩(ConfigKeys.normalize).
 */
public final class ConfigKeyGotoHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null || DumbService.isDumb(sourceElement.getProject())
                || !(sourceElement.getParent() instanceof PsiLiteralExpression literal)
                || !(literal.getValue() instanceof String)
                || PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class) == null) {
            return null;
        }
        String key = ConfigKeys.placeholderKeyAt(literal.getText(), offset - literal.getTextRange().getStartOffset());
        if (key == null) {
            return null;
        }
        Project project = literal.getProject();
        PsiManager manager = PsiManager.getInstance(project);
        List<PsiElement> targets = new ArrayList<>();
        for (VirtualFile file : SpringWebConfig.configFiles(project)) {
            PsiFile psi = manager.findFile(file);
            if (psi == null) {
                continue;
            }
            for (ConfigKeys.Hit hit : ConfigKeys.find(psi.getText(), key, SpringWebConfig.isYaml(file))) {
                PsiElement target = target(psi, hit.offset());
                if (target != null) {
                    targets.add(target);
                }
            }
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }

    /**
     * 키 위치의 요소. 같은 위치에서 시작하는 이름 있는 요소(yml 키-값, properties 항목)가 있으면 그것을 써서
     * 고르기 창에 키 이름이 보이게 하고, 없으면 그 위치의 잎 요소.
     */
    private static PsiElement target(PsiFile psi, int offset) {
        PsiElement leaf = psi.findElementAt(offset);
        PsiElement element = leaf;
        while (element != null && !(element instanceof PsiFile)) {
            if (element.getTextRange().getStartOffset() != offset) {
                break;
            }
            if (element instanceof PsiNamedElement && element instanceof NavigatablePsiElement) {
                return element;
            }
            element = element.getParent();
        }
        return leaf;
    }
}
