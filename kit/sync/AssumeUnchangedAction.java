package kinggura.sync;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 커밋 창 · 변경 목록 · 프로젝트 트리 우클릭 메뉴의 「Assume Unchanged」. 고른 파일을 Git 저장소별로 묶어 git update-index --skip-worktree 를 돌린다.
 * 「No Assume Unchanged」(Undo)는 같은 파일의 skip-worktree 와 예전 방식 assume-unchanged 표시를 모두 푼다. 모른 척 중이 아닌 파일에 풀기를 해도 아무 일도 없다.
 * 폴더 · Git 저장소 밖 파일은 건너뛴다. 동기화 창의 같은 메뉴와 동작이 같다(SyncPanel.setHidden).
 */
public class AssumeUnchangedAction extends AnAction implements DumbAware {

  /** 프로젝트 트리 우클릭의 「No Assume Unchanged」. */
  public static final class Undo extends AssumeUnchangedAction {
    @Override boolean on() { return false; }
  }

  boolean on() { return true; }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(e.getProject() != null && !byRoot(e).isEmpty());
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Map<File, List<String>> byRoot = byRoot(e);
    if (project == null || byRoot.isEmpty()) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String message;
      try {
        int count = 0;
        for (Map.Entry<File, List<String>> entry : byRoot.entrySet()) {
          SyncPanel.setHidden(entry.getKey(), entry.getValue(), on());
          count += entry.getValue().size();
        }
        message = SyncPanel.hideMessage(count, on());
      }
      catch (IOException ex) {
        message = ex.getMessage();
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      String m = message;
      ApplicationManager.getApplication().invokeLater(() -> {
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
        Messages.showInfoMessage(project, m, on() ? "Assume Unchanged" : "No Assume Unchanged");
      });
    });
  }

  /** 고른 파일을 Git 루트별로 묶는다. 경로는 루트 기준 상대 경로('/' 구분)다. */
  private static Map<File, List<String>> byRoot(AnActionEvent e) {
    Map<File, List<String>> result = new LinkedHashMap<>();
    Project project = e.getProject();
    VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    if (project == null || files == null) return result;
    ProjectLevelVcsManager vcs = ProjectLevelVcsManager.getInstance(project);
    for (VirtualFile file : files) {
      if (file.isDirectory()) continue;
      VcsRoot root = vcs.getVcsRootObjectFor(file);
      if (root == null || root.getVcs() == null || !"Git".equals(root.getVcs().getName()) || root.getPath() == null) continue;
      String rel = file.getPath().substring(root.getPath().getPath().length() + 1);
      result.computeIfAbsent(new File(root.getPath().getPath()), k -> new ArrayList<>()).add(rel);
    }
    return result;
  }
}
