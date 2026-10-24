package kinggura.sync;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.checkout.CheckoutListener;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitVcs;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Clone 이 끝난 폴더를 새 창 프로젝트로 열지 않고 지금 열린 프로젝트에 모듈로 붙인다(이클립스 워크스페이스처럼).
 * 모듈 파일은 받은 폴더의 「폴더이름.iml」(ExcludeIdeFiles 가 *.iml 을 Git 에서 감춘다), SDK 는 프로젝트 것을 따른다.
 * 프로젝트 VCS 매핑이 <Project> 기본값이 아니면 받은 폴더에 Git 매핑을 더한다.
 * 다음이면 false 를 돌려 IntelliJ 기본 동작(새 프로젝트로 열기)에 맡긴다:
 * 시작 화면(기본 프로젝트)에서 받았을 때, 같은 이름 모듈이 이미 있을 때.
 * 받은 폴더가 이미 프로젝트 안이면 붙이지 않고 true(이미 보인다).
 */
public final class CloneIntoProject implements CheckoutListener {

  @Override
  public boolean processCheckedOutDirectory(@NotNull Project project, @NotNull Path directory) {
    if (project.isDefault() || project.isDisposed()) return false;
    VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
    if (dir == null) return false;
    if (ReadAction.compute(() -> ProjectFileIndex.getInstance(project).isInContent(dir))) return true;
    String name = dir.getName();
    if (ModuleManager.getInstance(project).findModuleByName(name) != null) return false;

    ApplicationManager.getApplication().invokeAndWait(() -> {
      if (project.isDisposed()) return;
      Module module = WriteAction.compute(() -> ModuleManager.getInstance(project)
        .newModule(directory.resolve(name + ".iml"), ModuleTypeManager.getInstance().getDefaultModuleType().getId()));
      ModuleRootModificationUtil.addContentRoot(module, dir);
      ModuleRootModificationUtil.setSdkInherited(module);
      addGitMapping(project, directory);
    });
    return true;
  }

  private static void addGitMapping(Project project, Path directory) {
    if (!Files.isDirectory(directory.resolve(".git"))) return;
    ProjectLevelVcsManager vcs = ProjectLevelVcsManager.getInstance(project);
    List<VcsDirectoryMapping> mappings = new ArrayList<>(vcs.getDirectoryMappings());
    for (VcsDirectoryMapping mapping : mappings) {
      if (mapping.isDefaultMapping()) return;
    }
    mappings.add(new VcsDirectoryMapping(directory.toString().replace('\\', '/'), GitVcs.NAME));
    vcs.setDirectoryMappings(mappings);
  }
}
