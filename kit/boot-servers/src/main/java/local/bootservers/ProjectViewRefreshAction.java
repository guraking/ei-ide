package local.bootservers;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;

/**
 * 프로젝트 창에서 F5 로 고른 프로젝트 · 폴더 · 파일을 디스크에서 다시 읽는다(우클릭 「Reload from Disk」 와 같은 동작).
 * 이클립스 키맵은 F5 를 디버그 「안으로(Step Into)」 에 주므로, 프로젝트 창에 초점이 있을 때만 켜서 편집기의 F5 는 그대로 둔다.
 */
public final class ProjectViewRefreshAction extends DumbAwareAction {

    /** 플랫폼 프로젝트 창 우클릭 「Reload from Disk」(SynchronizeCurrentFileAction)의 액션 id. */
    private static final String RELOAD_FROM_DISK_ID = "SynchronizeCurrentFile";

    @Override
    public void update(AnActionEvent e) {
        ToolWindow window = e.getData(PlatformDataKeys.TOOL_WINDOW);
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        e.getPresentation().setEnabled(window != null && ToolWindowId.PROJECT_VIEW.equals(window.getId())
                && files != null && files.length > 0
                && ActionManager.getInstance().getAction(RELOAD_FROM_DISK_ID) != null);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        AnAction reload = ActionManager.getInstance().getAction(RELOAD_FROM_DISK_ID);
        if (reload != null) {
            ActionUtil.invokeAction(reload, e.getDataContext(), e.getPlace(), e.getInputEvent(), null);
        }
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
