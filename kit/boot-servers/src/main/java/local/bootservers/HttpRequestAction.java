package local.bootservers;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Run 메뉴의 「HTTP 요청…」. 실행 중인 서버(없으면 설정 파일 · 8080)의 뿌리 주소로 요청 창을 연다. */
public final class HttpRequestAction extends DumbAwareAction {

    private static final String ROOT_PATH = "/";

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            HttpRequestDialog.open(project, null, HttpText.METHODS[0], ROOT_PATH);
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
