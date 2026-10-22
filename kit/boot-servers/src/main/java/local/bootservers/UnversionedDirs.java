package local.bootservers;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.openapi.vcs.changes.ChangeListManager;

import java.util.HashSet;
import java.util.Set;

/**
 * 버전 관리에 안 넣은 새 파일의 상위 폴더 경로(시스템 독립 경로, 새 파일 자신은 빼고)를 프로젝트에 담아 둔다.
 * 변경 목록 갱신이 끝날 때마다 다시 만들고, 내용이 바뀌었을 때만 프로젝트 트리를 다시 그린다.
 * ChangedFolderIconDecorator 는 폴더마다 이 집합에서 한 번 찾기만 한다.
 */
public final class UnversionedDirs implements ChangeListListener {

    private static final Key<Set<String>> DIRS = Key.create("ei.unversioned.dirs");

    private final Project project;

    public UnversionedDirs(Project project) {
        this.project = project;
    }

    /** 이 폴더 아래(하위 폴더 포함)에 버전 관리에 안 넣은 새 파일이 있는가. 첫 갱신 전에는 false. */
    static boolean contains(Project project, String dirPath) {
        Set<String> dirs = project.getUserData(DIRS);
        return dirs != null && dirs.contains(dirPath);
    }

    @Override
    public void changeListUpdateDone() {
        if (project.isDisposed()) {
            return;
        }
        Set<String> dirs = new HashSet<>();
        for (FilePath path : ChangeListManager.getInstance(project).getUnversionedFilesPaths()) {
            // 이미 담긴 폴더를 만나면 그 위는 앞서 담았으므로 멈춘다.
            FilePath parent = path.getParentPath();
            while (parent != null && dirs.add(parent.getPath())) {
                parent = parent.getParentPath();
            }
        }
        if (dirs.equals(project.getUserData(DIRS))) {
            return;
        }
        project.putUserData(DIRS, Set.copyOf(dirs));
        ApplicationManager.getApplication().invokeLater(() -> ProjectView.getInstance(project).refresh(), project.getDisposed());
    }
}
