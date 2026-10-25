package kinggura.sync;

import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.projectView.TreeStructureProvider;
import com.intellij.ide.projectView.ViewSettings;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.checkout.CheckoutListener;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.impl.welcomeScreen.cloneableProjects.CloneableProjectsService;
import com.intellij.psi.PsiDirectory;
import git4idea.GitVcs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Clone 이 끝난 폴더를 새 창 프로젝트로 열지 않고 지금 열린 프로젝트에 모듈로 붙인다(이클립스 워크스페이스처럼).
 * 모듈 파일은 프로젝트의 .idea/modules/「폴더이름.iml」(받은 저장소 안에 IDE 파일을 만들지 않는다), SDK 는 프로젝트 것을 따른다.
 * 프로젝트 VCS 매핑이 <Project> 기본값이 아니면 받은 폴더에 Git · SVN 매핑을 더한다.
 * 받은 폴더와 그 아래 두 단계의 pom.xml · Gradle 파일은 Maven · Gradle 프로젝트로 연결한다(linkBuildFiles).
 * 다음이면 false 를 돌려 IntelliJ 기본 동작(새 프로젝트로 열기)에 맡긴다:
 * 시작 화면(기본 프로젝트)에서 받았을 때, 같은 이름 모듈이 이미 있을 때.
 * 받은 폴더가 프로젝트 폴더 안이어도 따로 모듈로 붙인다.
 * 워크스페이스 프로젝트(C:\dev\git 처럼 Git 저장소 밖 폴더를 연 것)는 열 때와 Clone 뒤에 그 폴더 자체 모듈(「git」)을 빼고,
 * 프로젝트 창 맨 위의 프로젝트 폴더 줄은 HideWorkspaceRoot 가 그 안의 줄들(.idea 빼고)로 바꿔 그린다
 * (IntelliJ 가 열 때마다 다시 등록하므로 등록은 빼지 않는다. 받은 저장소 모듈은 이 폴더 줄 아래에 그려지므로 줄만 지우면 같이 사라진다).
 * 그러면 「git」 줄 없이 받은 저장소 모듈들만 이클립스처럼 나란히 보인다(빈 워크스페이스면 빈 트리, .idea 는 디스크에 그대로).
 * 워크스페이스 판정(workspaceDir): 프로젝트 폴더와 그 위 어디에도 .git · .svn 이 없고, 프로젝트 폴더 아래에 모듈로 붙은 폴더가 하나 이상 있으며,
 * 프로젝트 폴더를 콘텐츠 루트로 가진 모듈이 없거나 있어도 그 루트 하나뿐이며 소스 폴더가 없다.
 * 그래서 빈 워크스페이스는 첫 Clone 전까지 폴더 줄이 보인다.
 */
public final class CloneIntoProject implements CheckoutListener, StartupActivity.DumbAware {
  private static final Logger LOG = Logger.getInstance(CloneIntoProject.class);
  // 「Add as Maven Project」(pom.xml 우클릭) · 「Link Gradle Project」(build.gradle 우클릭) 동작 ID, 2026.2 intellij.maven.xml · intellij.gradle.xml
  private static final String ACTION_LINK_MAVEN = "Maven.AddFileAsMavenProject";
  private static final String ACTION_LINK_GRADLE = "Gradle.ImportExternalProject";
  private static final String POM = "pom.xml";
  private static final List<String> BUILD_FILES = List.of(POM, "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");
  private static final Set<String> SKIP_DIRS = Set.of("target", "build", "out", "bin", "node_modules");
  // 받은 폴더(0) 아래 몇 단계까지 빌드 파일을 찾는가. 저장소 안 한 단계 아래 이클립스 프로젝트(basic\jpa-mybatis-multi)와 그 아래 한 단계를 덮는다.
  private static final int BUILD_DEPTH = 2;

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

    // 이 리스너는 Clone 작업 스레드에서 불린다(CloneableProjectsService.runCloneTask). 모듈 · 매핑은 EDT 에서 바꾼다.
    // 붙이다 실패하면 만든 모듈을 지우고 false 를 돌려 IntelliJ 기본 동작(새 프로젝트로 열기)에 맡긴다. 반쯤 붙은 모듈을 남기지 않는다.
    AtomicReference<Module> created = new AtomicReference<>();
    try {
      ApplicationManager.getApplication().invokeAndWait(() -> {
        if (project.isDisposed()) return;
        created.set(WriteAction.compute(() -> ModuleManager.getInstance(project)
          .newModule(Path.of(base, Project.DIRECTORY_STORE_FOLDER, "modules", name + ".iml"), ModuleTypeManager.getInstance().getDefaultModuleType().getId())));
        ModuleRootModificationUtil.addContentRoot(created.get(), dir);
        ModuleRootModificationUtil.setSdkInherited(created.get());
        addVcsMapping(project, directory);
        removeWorkspaceModule(project);
      });
    }
    catch (RuntimeException e) {
      LOG.warn("Clone 한 폴더를 모듈로 붙이지 못해 새 프로젝트로 엽니다: " + directory, e);
      Module module = created.get();
      if (module != null) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
          if (!project.isDisposed() && !module.isDisposed()) disposeModule(project, module);
        });
      }
      return false;
    }
    // SVN 은 최근 목록에 올린 뒤 이 리스너를 부르고, Git 은 이 리스너 뒤에 올린다. 지금 한 번 빼고, Git 은 onCloneSuccess 에서 한 번 더 뺀다.
    String path = directory.toString().replace('\\', '/');
    RecentProjectsManager.getInstance().removePath(path);
    ForgetRecent.ATTACHED.add(path);
    linkBuildFiles(project, directory);
    return true;
  }

  /**
   * 받은 폴더와 그 아래 BUILD_DEPTH 단계에서 찾은 pom.xml · Gradle 파일을 IntelliJ 의 「Add as Maven Project」 · 「Link Gradle Project」 로 연결한다
   * (이클립스의 Import existing Maven project 처럼). 인덱싱이 끝난 뒤 EDT 에서 부른다. 동작이 없거나(플러그인 꺼짐) 이미 연결된 파일이면 건너뛴다.
   */
  private static void linkBuildFiles(Project project, Path directory) {
    for (Path file : buildFiles(directory)) {
      VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
      if (vf == null) continue;
      String actionId = file.getFileName().toString().equals(POM) ? ACTION_LINK_MAVEN : ACTION_LINK_GRADLE;
      DumbService.getInstance(project).smartInvokeLater(() -> {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null || project.isDisposed()) return;
        var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, vf).build();
        AnActionEvent event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context);
        // update 가 이미 연결된 파일이면 숨긴다. 그때 실행하면 Maven 은 다시 읽고 같은 폴더의 다른 빌드 도구 연결을 끊는다.
        ActionUtil.updateAction(action, event);
        if (event.getPresentation().isEnabledAndVisible()) ActionUtil.performAction(action, event);
      });
    }
  }

  /**
   * 빌드 파일 목록. 한 폴더에 pom.xml 이 있으면 그것을, 없으면 settings.gradle(.kts) · build.gradle(.kts) 중 앞의 것을 고르고 그 아래로는 내려가지 않는다
   * (다중 모듈은 맨 위 파일 하나가 아래 모듈까지 읽는다). 점으로 시작하는 폴더와 빌드 결과 폴더는 보지 않는다.
   */
  static List<Path> buildFiles(Path dir) {
    List<Path> found = new ArrayList<>();
    collectBuildFiles(dir, 0, found);
    return found;
  }

  private static void collectBuildFiles(Path dir, int depth, List<Path> found) {
    for (String name : BUILD_FILES) {
      if (Files.isRegularFile(dir.resolve(name))) {
        found.add(dir.resolve(name));
        return;
      }
    }
    if (depth == BUILD_DEPTH) return;
    List<Path> children = new ArrayList<>();
    try (var stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
      for (Path child : stream) children.add(child);
    }
    catch (IOException e) {
      LOG.warn("빌드 파일을 찾다가 폴더를 읽지 못했습니다: " + dir, e);
      return;
    }
    children.sort(null);
    for (Path child : children) {
      String name = child.getFileName().toString();
      if (!name.startsWith(".") && !SKIP_DIRS.contains(name)) collectBuildFiles(child, depth + 1, found);
    }
  }

  /**
   * IntelliJ Git Clone 은 CheckoutListener 처리가 끝난 뒤 받은 폴더를 최근 프로젝트 목록에 올린다(CloneableProjectsService.onSuccess).
   * 워크스페이스에 모듈로 붙인 폴더는 따로 연 프로젝트가 아니므로, 그 뒤 EDT 로 오는 onCloneSuccess 에서 목록에서 뺀다.
   */
  public static final class ForgetRecent implements CloneableProjectsService.CloneProjectListener {
    static final Set<String> ATTACHED = ConcurrentHashMap.newKeySet();

    @Override
    public void onCloneSuccess() {
      for (String path : ATTACHED) {
        RecentProjectsManager.getInstance().removePath(path);
        ATTACHED.remove(path);
      }
    }
  }

  /**
   * 워크스페이스면 프로젝트 폴더를 돌려준다. 아니면 null.
   * Git · SVN 저장소 안이거나, 그 폴더 아래에 모듈로 붙은 폴더가 하나도 없으면(그냥 폴더 · SVN 작업본 · 소스 폴더를 아직 안 정한 프로젝트) 워크스페이스가 아니다.
   */
  private static @Nullable VirtualFile workspaceDir(Project project) {
    String base = project.isDefault() ? null : project.getBasePath();
    if (base == null) return null;
    for (Path dir = Path.of(base); dir != null; dir = dir.getParent()) {
      if (Files.exists(dir.resolve(".git")) || Files.isDirectory(dir.resolve(".svn"))) return null;
    }
    VirtualFile baseDir = LocalFileSystem.getInstance().findFileByNioFile(Path.of(base));
    if (baseDir == null) return null;
    boolean attached = false;
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
      VirtualFile[] roots = rootManager.getContentRoots();
      if (List.of(roots).contains(baseDir) && (roots.length != 1 || rootManager.getSourceRoots().length != 0)) return null;
      for (VirtualFile root : roots) {
        if (VfsUtilCore.isAncestor(baseDir, root, true)) attached = true;
      }
    }
    return attached ? baseDir : null;
  }

  private static void removeWorkspaceModule(Project project) {
    VirtualFile baseDir = workspaceDir(project);
    if (baseDir == null) return;
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      if (!List.of(ModuleRootManager.getInstance(module).getContentRoots()).contains(baseDir)) continue;
      disposeModule(project, module);
      return;
    }
  }

  /** 모듈을 프로젝트에서 뺀다. EDT 에서 부른다. */
  private static void disposeModule(Project project, Module module) {
    WriteAction.run(() -> {
      ModifiableModuleModel model = ModuleManager.getInstance(project).getModifiableModel();
      model.disposeModule(module);
      model.commit();
    });
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

  /**
   * 받은 폴더에 .git 이면 Git, .svn 이면 SVN 매핑을 넣는다(SVN Checkout 은 IntelliJ 가 매핑을 넣지 않는다).
   * 프로젝트 폴더 안에 Git 으로 받으면 IntelliJ Clone 이 VFS 가 폴더를 알기 전에 같은 매핑을 먼저 넣어
   * 「Invalid mapping」 으로 버리고, 같은 목록을 다시 넣으면 다시 계산하지 않는다(NewMappings.updateVcsMappings).
   * 그래서 그 매핑을 뺀 목록을 한 번 넣었다가 다시 넣어, VFS 에 올라온 뒤 루트를 바로 다시 계산하게 한다.
   */
  private static void addVcsMapping(Project project, Path directory) {
    String vcsName = Files.exists(directory.resolve(".git")) ? GitVcs.NAME
                     : Files.isDirectory(directory.resolve(".svn")) ? SyncPanel.VCS_NAME_SVN : null;
    if (vcsName == null) return;
    ProjectLevelVcsManager vcs = ProjectLevelVcsManager.getInstance(project);
    String path = directory.toString().replace('\\', '/');
    List<VcsDirectoryMapping> mappings = new ArrayList<>();
    boolean present = false;
    for (VcsDirectoryMapping mapping : vcs.getDirectoryMappings()) {
      if (mapping.isDefaultMapping()) return;
      if (FileUtil.pathsEqual(mapping.getDirectory(), path)) present = true;
      else mappings.add(mapping);
    }
    if (present) vcs.setDirectoryMappings(new ArrayList<>(mappings));
    mappings.add(new VcsDirectoryMapping(path, vcsName));
    vcs.setDirectoryMappings(mappings);
  }
}
