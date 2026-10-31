package kinggura.sync;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.vcsUtil.VcsUtil;
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
 * 폴더 · Git 저장소 밖 파일 · 추적하지 않는(새 · 무시) 파일은 건너뛴다. 동기화 창의 같은 메뉴와 동작이 같다(SyncPanel.setHidden).
 * 저장소 하나가 실패해도 나머지는 계속하고, 실패한 저장소 이름과 까닭을 알린다.
 */
public class AssumeUnchangedAction extends AnAction implements DumbAware {

  /** 알림 설정(Settings → Notifications)에서 이 알림을 가려 끌 수 있게 붙이는 표시 ID. */
  private static final String NOTIFICATION_ID = "kinggura.sync.assumeUnchanged";
  // git 한 번에 넘기는 경로 수. 윈도우 명령줄 한도(32767자)를 경로 100개 × 최대 260자로도 넘지 않게 나눈다.
  private static final int PATHS_PER_CALL = 100;

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
      int count = 0;
      List<String> failures = new ArrayList<>();
      for (Map.Entry<File, List<String>> entry : byRoot.entrySet()) {
        List<String> paths = entry.getValue();
        try {
          for (int from = 0; from < paths.size(); from += PATHS_PER_CALL) {
            List<String> part = paths.subList(from, Math.min(from + PATHS_PER_CALL, paths.size()));
            SyncPanel.setHidden(entry.getKey(), part, on());
            count += part.size();
          }
        }
        catch (IOException ex) {
          failures.add(entry.getKey().getName() + ": " + ex.getMessage());
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      List<String> lines = new ArrayList<>();
      if (count > 0 || failures.isEmpty()) lines.add(SyncPanel.hideMessage(count, on()));
      lines.addAll(failures);
      String m = String.join("\n", lines);
      boolean error = !failures.isEmpty();
      ApplicationManager.getApplication().invokeLater(() -> {
        // 변경 목록은 바꾼 파일만 다시 읽는다. 동기화 창은 변경 목록 알림으로는 모른 척 목록을 다시 읽지 않으므로 따로 부른다.
        List<FilePath> touched = new ArrayList<>();
        for (Map.Entry<File, List<String>> entry : byRoot.entrySet()) {
          for (String rel : entry.getValue()) touched.add(VcsUtil.getFilePath(new File(entry.getKey(), rel), false));
        }
        VcsDirtyScopeManager.getInstance(project).filePathsDirty(touched, List.of());
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(SyncToolWindowFactory.ID);
        Content content = window == null ? null : window.getContentManager().getContent(0);
        if (content != null && content.getComponent() instanceof SyncPanel panel) panel.recompareAssumed();
        // 결과는 IntelliJ VCS 알림 풍선으로 알린다(모달 창으로 작업을 막지 않는다). 실패는 오류 풍선.
        String title = on() ? "Assume Unchanged" : "No Assume Unchanged";
        if (error) VcsNotifier.getInstance(project).notifyError(NOTIFICATION_ID, title, m);
        else VcsNotifier.getInstance(project).notifyInfo(NOTIFICATION_ID, title, m);
      });
    });
  }

  /** 고른 파일 가운데 Git 이 추적하는 파일을 루트별로 묶는다. 경로는 루트 기준 상대 경로('/' 구분)다. */
  private static Map<File, List<String>> byRoot(AnActionEvent e) {
    Map<File, List<String>> result = new LinkedHashMap<>();
    Project project = e.getProject();
    VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    if (project == null || files == null) return result;
    ProjectLevelVcsManager vcs = ProjectLevelVcsManager.getInstance(project);
    ChangeListManager changes = ChangeListManager.getInstance(project);
    for (VirtualFile file : files) {
      // 추적하지 않는 파일이 섞이면 git update-index 가 통째로 실패하므로 뺀다.
      if (file.isDirectory() || changes.isUnversioned(file) || changes.isIgnoredFile(file)) continue;
      VcsRoot root = vcs.getVcsRootObjectFor(file);
      if (root == null || root.getVcs() == null || !"Git".equals(root.getVcs().getName()) || root.getPath() == null) continue;
      String rel = file.getPath().substring(root.getPath().getPath().length() + 1);
      result.computeIfAbsent(new File(root.getPath().getPath()), k -> new ArrayList<>()).add(rel);
    }
    return result;
  }
}
