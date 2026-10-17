package local.bootservers;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import org.jetbrains.annotations.NotNull;

/**
 * 아래쪽에 붙은 도구 창 하나를 닫으면, 그 창이 열리면서 가렸던 직전 창을 다시 연다.
 * 한 단계만 되돌린다. 다시 연 창을 닫으면 더 거슬러 올라가지 않고 아래쪽이 빈다.
 * 대상은 아래쪽 · 붙박이(DOCKED) · 왼쪽 칸 창뿐이다. 떠 있는 창, 자동 숨김(SLIDING) 창, 오른쪽 칸(split) 창은 보지 않는다.
 */
public final class ReopenPreviousToolWindow implements ToolWindowManagerListener {

    private final Project project;
    /** 지금 아래쪽에 열린 창 id, 그 창이 가린 직전 창 id. 없으면 null. */
    private String current;
    private String previous;

    public ReopenPreviousToolWindow(Project project) {
        this.project = project;
    }

    @Override
    public void toolWindowShown(@NotNull ToolWindow toolWindow) {
        if (!tracked(toolWindow) || toolWindow.getId().equals(current)) return;
        previous = current;
        current = toolWindow.getId();
    }

    @Override
    public void stateChanged(@NotNull ToolWindowManager manager, @NotNull ToolWindow toolWindow, @NotNull ToolWindowManagerEventType changeType) {
        if (changeType != ToolWindowManagerEventType.HideToolWindow || !toolWindow.getId().equals(current)) return;
        String closed = current;
        // 다른 창을 열어 가려진 경우에도 숨김 알림이 온다. 그 창이 열린 뒤에 보고 아래쪽이 비었을 때만 닫은 것으로 본다.
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!closed.equals(current) || bottomOpen(manager)) return;
            ToolWindow back = previous == null ? null : manager.getToolWindow(previous);
            current = null;
            previous = null;
            if (back != null && back.isAvailable()) back.show();
        }, project.getDisposed());
    }

    private static boolean tracked(ToolWindow window) {
        return window.getAnchor() == ToolWindowAnchor.BOTTOM && window.getType() == ToolWindowType.DOCKED && !window.isSplitMode();
    }

    private static boolean bottomOpen(ToolWindowManager manager) {
        for (String id : manager.getToolWindowIds()) {
            ToolWindow window = manager.getToolWindow(id);
            if (window != null && window.isVisible() && tracked(window)) return true;
        }
        return false;
    }
}
