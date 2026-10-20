package ei.db.gen;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * 「JSON 으로 DTO 만들기…」: 프로젝트 트리에서 고른 폴더(파일이면 그 폴더)가 소스 폴더 안이면 그 패키지 · 폴더를 기본값으로 연다.
 * 소스 폴더 밖이거나 고른 것이 없으면 DtoDialog 처럼 첫 자바 루트 + 기본 패키지.dto.
 */
public final class JsonDtoAction extends DumbAwareAction {

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabled(e.getProject() != null);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    VirtualFile picked = e.getData(CommonDataKeys.VIRTUAL_FILE);
    VirtualFile dir = picked == null || picked.isDirectory() ? picked : picked.getParent();
    new Task.Backgroundable(project, "DTO 위치 찾는 중", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        JsonDtoDialog.Start start = ReadAction.compute(() -> start(project, dir));
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!project.isDisposed()) new JsonDtoDialog(project, start).show();
        });
      }
    }.queue();
  }

  /** read action 안에서 부른다. */
  private static JsonDtoDialog.Start start(Project project, VirtualFile dir) {
    Targets.Roots roots = Targets.find(project);
    if (dir != null && dir.isValid()) {
      ProjectFileIndex index = ProjectFileIndex.getInstance(project);
      VirtualFile sourceRoot = index.getSourceRootForFile(dir);
      String pkg = index.getPackageNameByDirectory(dir);
      if (sourceRoot != null && pkg != null) return new JsonDtoDialog.Start(roots, sourceRoot.getPath(), pkg);
    }
    return new JsonDtoDialog.Start(roots, roots.java(), roots.basePackage() + ".dto");
  }
}
