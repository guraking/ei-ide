package local.bootservers;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

/** Tools 메뉴 「저장한 API 요청 다시 보내기…」: Send API Request 로 보낸 요청들을 한꺼번에 다시 보내 지난 응답과 견준다. */
public final class ApiResendAction extends DumbAwareAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() != null) {
            new ApiResendDialog(e.getProject()).show();
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
