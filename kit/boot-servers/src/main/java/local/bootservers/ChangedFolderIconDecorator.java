package local.bootservers;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.IconUtil;
import com.intellij.util.containers.ContainerUtil;

import javax.swing.Icon;
import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프로젝트 트리에서 아래에 바뀐 파일 · 새 파일이 있는 폴더 · 패키지 아이콘을 폴더 이름과 같은 색으로 칠한다(상위 폴더까지).
 * 상태는 FileStatusManager.getRecursiveStatus 를 쓰므로 Git · SVN 모두 되고, 설정 → Version Control
 * 「Show directories with changed descendants」(apply_defaults 21 이 기본으로 켬)가 꺼져 있으면 칠하지 않는다.
 * 폴더 자체가 새로 추가된 경우처럼 폴더의 상태가 따로 있으면 플랫폼 표시를 그대로 둔다.
 */
public final class ChangedFolderIconDecorator implements ProjectViewNodeDecorator {

    // 원래 아이콘 → (색 RGB → 칠한 아이콘). 노드를 다시 그릴 때마다 새 아이콘을 만들지 않게 한다.
    // 칠한 아이콘이 원래 아이콘을 잡고 있으므로 값은 부드러운 참조로 둔다(메모리가 모자라면 비워지고 다시 만든다).
    // 색은 RGB 로 구분하므로 테마를 바꿔 상태 색이 달라지면 새로 칠한다.
    private static final Map<Icon, Map<Integer, Icon>> COLORIZED = ContainerUtil.createConcurrentWeakKeySoftValueMap();

    @Override
    public void decorate(ProjectViewNode<?> node, PresentationData data) {
        Project project = node.getProject();
        VirtualFile file = node.getVirtualFile();
        Icon icon = data.getIcon(false);
        if (project == null || project.isDisposed() || file == null || !file.isDirectory() || icon == null) {
            return;
        }
        FileStatus status = FileStatusManager.getInstance(project).getRecursiveStatus(file);
        if (status != FileStatus.NOT_CHANGED_IMMEDIATE && status != FileStatus.NOT_CHANGED_RECURSIVE) {
            return;
        }
        Color color = status.getColor();
        if (color != null) {
            data.setIcon(COLORIZED.computeIfAbsent(icon, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(color.getRGB(), rgb -> IconUtil.colorize(icon, new Color(rgb, true))));
        }
    }
}
