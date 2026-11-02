package local.bootservers;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Set;

/**
 * Search 메뉴 · 편집기 우클릭의 「Regex Workbench…」. 편집기에서 고른 글이 있으면 시험 글로 넣는다.
 * 문법은 편집 중인 파일이 프론트 파일(FRONT_EXTENSIONS)이면 JavaScript, 다른 파일이면 Java, 편집기가 없으면 지난번 문법.
 */
public final class RegexBenchAction extends DumbAwareAction {

    private static final Set<String> FRONT_EXTENSIONS = Set.of("js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "html", "htm", "jsp");

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        String selected = editor != null ? editor.getSelectionModel().getSelectedText() : null;
        RegexBench.Flavor flavor = null;
        if (editor != null) {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            String extension = file != null ? file.getExtension() : null;
            flavor = extension != null && FRONT_EXTENSIONS.contains(extension.toLowerCase()) ? RegexBench.Flavor.JAVASCRIPT : RegexBench.Flavor.JAVA;
        }
        new RegexBenchDialog(project, flavor, selected != null ? selected : "").show();
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
