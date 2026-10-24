package kinggura.sync;

import com.intellij.ide.projectView.TreeStructureProvider;
import com.intellij.ide.projectView.ViewSettings;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.checkout.CheckoutListener;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import git4idea.GitVcs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Clone 이 끝난 폴더를 새 창 프로젝트로 열지 않고 지금 열린 프로젝트에 모듈로 붙인다(이클립스 워크스페이스처럼).
 * 모듈 파일은 프로젝트의 .idea/modules/「폴더이름.iml」(받은 저장소 안에 IDE 파일을 만들지 않는다), SDK 는 프로젝트 것을 따른다.
 * 프로젝트 VCS 매핑이 <Project> 기본값이 아니면 받은 폴더에 Git 매핑을 더한다.
 * 다음이면 false 를 돌려 IntelliJ 기본 동작(새 프로젝트로 열기)에 맡긴다:
 * 시작 화면(기본 프로젝트)에서 받았을 때, 같은 이름 모듈이 이미 있을 때.
 * 받은 폴더가 프로젝트 폴더 안이어도 따로 모듈로 붙인다.
 * 워크스페이스 프로젝트(C:\dev\git 처럼 Git 저장소 밖 폴더를 연 것)는 열 때와 Clone 뒤에 그 폴더 자체 모듈(「git」)을 빼고,
 * 프로젝트 창 맨 위의 프로젝트 폴더 줄은 HideWorkspaceRoot 가 그 안의 줄들(.idea 빼고)로 바꿔 그린다
 * (IntelliJ 가 열 때마다 다시 등록하므로 등록은 빼지 않는다. 받은 저장소 모듈은 이 폴더 줄 아래에 그려지므로 줄만 지우면 같이 사라진다).
 * 그러면 「git」 줄 없이 받은 저장소 모듈들만 이클립스처럼 나란히 보인다(빈 워크스페이스면 빈 트리, .idea 는 디스크에 그대로).
 * 워크스페이스 판정(isWorkspace): 프로젝트 폴더와 그 위 어디에도 .git 이 없고,
 * 프로젝트 폴더를 콘텐츠 루트로 가진 모듈이 없거나 있어도 그 루트 하나뿐이며 소스 폴더가 없다.
 */
public final class CloneIntoProject implements CheckoutListener, StartupActivity.DumbAware {

  @Override
  public void runActivity(@NotNull Project project) {
    ApplicationManager.getApplication().invokeLater(() -> removeWorkspaceModule(project), project.getDisposed());
  }

  @Override
  public boolean processCheckedOutDirectory(@NotNull Project project, @NotNull Path directory) {
    String base = project.isDefault() || project.isDisposed() ? null : project.getBasePath();
    if (base == null) return false;
    VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
    if (dir == null) return false;
    String name = dir.getName();
    if (ModuleManager.getInstance(project).findModuleByName(name) != null) return false;

    ApplicationManager.getApplication().invokeAndWait(() -> {
      if (project.isDisposed()) return;
      Module module = WriteAction.compute(() -> ModuleManager.getInstance(project)
        .newModule(Path.of(base, Project.DIRECTORY_STORE_FOLDER, "modules", name + ".iml"), ModuleTypeManager.getInstance().getDefaultModuleType().getId()));
      ModuleRootModificationUtil.addContentRoot(module, dir);
      ModuleRootModificationUtil.setSdkInherited(module);
      addGitMapping(project, directory);
      removeWorkspaceModule(project);
    });
    return true;
  }

  /** 워크스페이스면 프로젝트 폴더를 돌려준다. 아니면 null. */
  private static @Nullable VirtualFile workspaceDir(Project project) {
    String base = project.isDefault() ? null : project.getBasePath();
    if (base == null) return null;
    for (Path dir = Path.of(base); dir != null; dir = dir.getParent()) {
      if (Files.exists(dir.resolve(".git"))) return null;
    }
    VirtualFile baseDir = LocalFileSystem.getInstance().findFileByNioFile(Path.of(base));
    if (baseDir == null) return null;
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
      VirtualFile[] roots = rootManager.getContentRoots();
      if (List.of(roots).contains(baseDir) && (roots.length != 1 || rootManager.getSourceRoots().length != 0)) return null;
    }
    return baseDir;
  }

  private static void removeWorkspaceModule(Project project) {
    VirtualFile baseDir = workspaceDir(project);
    if (baseDir == null) return;
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      if (!List.of(ModuleRootManager.getInstance(module).getContentRoots()).contains(baseDir)) continue;
      WriteAction.run(() -> {
        ModifiableModuleModel model = ModuleManager.getInstance(project).getModifiableModel();
        model.disposeModule(module);
        model.commit();
      });
      return;
    }
  }

  /** 워크스페이스 프로젝트의 프로젝트 창 맨 위에서 프로젝트 폴더 줄을 그 안의 줄들(.idea 빼고)로 바꾼다. */
  public static final class HideWorkspaceRoot implements TreeStructureProvider, com.intellij.openapi.project.DumbAware {
    @Override
    public @NotNull Collection<AbstractTreeNode<?>> modify(@NotNull AbstractTreeNode<?> parent,
                                                           @NotNull Collection<AbstractTreeNode<?>> children,
                                                           ViewSettings settings) {
      if (!(parent.getValue() instanceof Project project)) return children;
      VirtualFile baseDir = workspaceDir(project);
      if (baseDir == null) return children;
      List<AbstractTreeNode<?>> shown = new ArrayList<>();
      for (AbstractTreeNode<?> child : children) {
        if (!(child.getValue() instanceof PsiDirectory dir && baseDir.equals(dir.getVirtualFile()))) {
          shown.add(child);
          continue;
        }
        VirtualFile store = baseDir.findChild(Project.DIRECTORY_STORE_FOLDER);
        for (AbstractTreeNode<?> inner : child.getChildren()) {
          if (inner.getValue() instanceof PsiDirectory innerDir && innerDir.getVirtualFile().equals(store)) continue;
          inner.setParent(parent);
          shown.add(inner);
        }
      }
      return shown;
    }
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
