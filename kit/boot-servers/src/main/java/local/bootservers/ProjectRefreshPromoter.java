package local.bootservers;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPromoter;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 이클립스 단축키의 F5 는 디버그가 멈춘 동안 「안으로(Step Into)」에도 켜진다. 프로젝트 창에서는 이클립스 Package Explorer 처럼
 * 새로고침(KingGura.ProjectViewRefresh)을 먼저 고른다. 그 밖의 곳(편집기 · 디버거 창)은 손대지 않는다.
 */
final class ProjectRefreshPromoter implements ActionPromoter {
    private static final String REFRESH_ID = "KingGura.ProjectViewRefresh";

    @Override
    public List<AnAction> promote(@NotNull List<? extends AnAction> actions, @NotNull DataContext context) {
        ToolWindow toolWindow = PlatformDataKeys.TOOL_WINDOW.getData(context);
        if (toolWindow == null || !ToolWindowId.PROJECT_VIEW.equals(toolWindow.getId())) {
            return List.of();
        }
        ActionManager manager = ActionManager.getInstance();
        for (AnAction action : actions) {
            if (REFRESH_ID.equals(manager.getId(action))) {
                return List.of(action);
            }
        }
        return List.of();
    }
}
