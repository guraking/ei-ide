package local.bootservers;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.ide.projectView.impl.ProjectRootsUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * 프로젝트 트리의 모듈 최상위 폴더 옆에 서버 상태(시작 중 · 실행 중 :포트 · 실패)를 회색 글자로 붙인다. 중지된 모듈은 그대로 둔다.
 * 기존 위치 글자(경로 · 산출물 이름 등)가 있으면 지우지 않고 뒤에 덧붙인다.
 */
public final class ServerStatusDecorator implements ProjectViewNodeDecorator {

    @Override
    public void decorate(ProjectViewNode<?> node, PresentationData data) {
        Project project = node.getProject();
        VirtualFile file = node.getVirtualFile();
        if (project == null || file == null || !anyShown(project) || !ProjectRootsUtil.isModuleContentRoot(file, project)) {
            return;
        }
        Module module = ModuleUtilCore.findModuleForFile(file, project);
        if (module == null) {
            return;
        }
        ServerLauncher.State state = ServerLauncher.state(module);
        if (state == ServerLauncher.State.STOPPED) {
            return;
        }
        String text = state.label;
        ServerRegistry.Run run = ServerRegistry.getInstance(project).get(module.getName());
        if (state == ServerLauncher.State.RUNNING && run != null && run.port != null) {
            text += " :" + run.port;
        }
        String location = data.getLocationString();
        data.setLocationString(location == null || location.isEmpty() ? text : location + " · " + text);
    }

    /** 붙일 상태가 있을 수 있는가: 살아 있는 실행 프로세스나 실패한 실행 기록이 하나라도 있으면 참. 없으면 모든 모듈이 중지됨이다. */
    private static boolean anyShown(Project project) {
        for (ProcessHandler handler : ExecutionManager.getInstance(project).getRunningProcesses()) {
            if (!handler.isProcessTerminated()) {
                return true;
            }
        }
        return ServerRegistry.getInstance(project).anyFailed();
    }
}
