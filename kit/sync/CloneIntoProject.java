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
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clone 이 끝난 폴더를 새 창 프로젝트로 열지 않고 지금 열린 프로젝트에 모듈로 붙인다(이클립스 워크스페이스처럼).
 * 모듈 파일은 프로젝트의 .idea/modules/「폴더이름.iml」(받은 저장소 안에 IDE 파일을 만들지 않는다), SDK 는 프로젝트 것을 따른다.
 * 프로젝트 VCS 매핑이 <Project> 기본값이 아니면 받은 폴더에 Git · SVN 매핑을 더한다.
 * 받은 폴더와 그 아래 두 단계의 pom.xml · Gradle 파일은 Maven · Gradle 프로젝트로 연결한다(linkBuildFiles).
 * 열 때와 Clone 뒤에 모듈마다 빌드 결과 · 내려받은 라이브러리 폴더를 제외 폴더로 표시한다(excludeBuildOutput). 인덱스 · 검색 · TODO 가 읽지 않는다.
 * 다음이면 false 를 돌려 IntelliJ 기본 동작(새 프로젝트로 열기)에 맡긴다:
 * 시작 화면(기본 프로젝트)에서 받았을 때, 같은 이름 모듈이 이미 있을 때,
 * SVN 으로 받았을 때, 열린 프로젝트가 SVN 프로젝트일 때(svnProject).
 * IntelliJ 는 SVN 이 하나라도 섞이면 Commit 창 대신 커밋 대화상자를 쓰므로(CommitModeManager.canSetNonModal) Git 과 SVN 을 한 프로젝트에 섞지 않는다.
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
  // 이 파일이 있는 폴더 → 그 옆의 빌드 결과 · 라이브러리 폴더(Maven · Gradle · npm 기본 위치).
  private static final Map<String, String> OUTPUT_DIRS = Map.of(
    POM, "target",
    "build.gradle", "build", "build.gradle.kts", "build", "settings.gradle", "build", "settings.gradle.kts", "build",
    "package.json", "node_modules");
  private static final String ECLIPSE_CLASSPATH = ".classpath";
  private static final Pattern CLASSPATH_ENTRY = Pattern.compile("<classpathentry\\b[^>]*>");
  private static final Pattern ENTRY_PATH = Pattern.compile("\\bpath=\"([^\"]*)\"");

  @Override
  public void runActivity(@NotNull Project project) {
    excludeBuildOutput(project);
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
    if (Files.isDirectory(directory.resolve(".svn")) || svnProject(project)) return false;

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
        excludeDirs(created.get(), outputDirs(directory));
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
    // Git Clone 은 이 리스너 뒤에 최근 목록에 올리므로 onCloneSuccess 에서 한 번 더 뺀다.
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
   * 모듈마다 콘텐츠 루트 아래의 빌드 결과 폴더를 제외로 표시한다. 폴더 찾기는 이 스레드(시작 작업, EDT 아님)에서, 표시는 EDT 에서 한다.
   */
  private static void excludeBuildOutput(Project project) {
    Map<Module, List<Path>> found = new LinkedHashMap<>();
    // 워크스페이스 폴더 자체 모듈은 removeWorkspaceModule 이 뺀다. 그 모듈에 저장소 안 폴더를 제외로 넣지 않는다.
    VirtualFile workspace = ReadAction.compute(() -> project.isDisposed() ? null : workspaceDir(project));
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      VirtualFile[] roots = ReadAction.compute(() -> module.isDisposed() ? VirtualFile.EMPTY_ARRAY : ModuleRootManager.getInstance(module).getContentRoots());
      List<Path> dirs = new ArrayList<>();
      for (VirtualFile root : roots) {
        if (root.isInLocalFileSystem() && !root.equals(workspace)) dirs.addAll(outputDirs(root.toNioPath()));
      }
      if (!dirs.isEmpty()) found.put(module, dirs);
    }
    if (found.isEmpty()) return;
    ApplicationManager.getApplication().invokeLater(() -> {
      for (Map.Entry<Module, List<Path>> entry : found.entrySet()) excludeDirs(entry.getKey(), entry.getValue());
    }, project.getDisposed());
  }

  /**
   * 아직 제외가 아닌 폴더를 그 폴더를 품은 콘텐츠 루트의 제외 폴더로 더한다. EDT 에서 부른다.
   * 소스 폴더이거나 소스 폴더를 품은 폴더는 건너뛴다(이클립스 출력 폴더를 소스 폴더와 같게 둔 프로젝트). 더할 것이 없으면 모듈을 고치지 않는다.
   */
  static void excludeDirs(Module module, List<Path> dirs) {
    if (module.isDisposed()) return;
    ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
    List<String> have = List.of(rootManager.getExcludeRootUrls());
    List<String> sources = List.of(rootManager.getSourceRootUrls());
    List<String> add = new ArrayList<>();
    for (Path dir : dirs) {
      String url = VfsUtilCore.pathToUrl(FileUtil.toSystemIndependentName(dir.toString()));
      if (have.contains(url) || add.contains(url) || coversAny(url, sources)) continue;
      add.add(url);
    }
    if (add.isEmpty()) return;
    ModuleRootModificationUtil.updateModel(module, model -> {
      for (ContentEntry entry : model.getContentEntries()) {
        for (String url : add) {
          if (!url.equals(entry.getUrl()) && VfsUtilCore.isEqualOrAncestor(entry.getUrl(), url)) entry.addExcludeFolder(url);
        }
      }
    });
  }

  private static boolean coversAny(String url, List<String> sources) {
    for (String source : sources) {
      if (VfsUtilCore.isEqualOrAncestor(url, source)) return true;
    }
    return false;
  }

  /**
   * 빌드 결과 · 라이브러리 폴더 목록. 루트와 그 아래 BUILD_DEPTH 단계의 폴더마다
   * pom.xml 옆 target, Gradle 파일 옆 build, package.json 옆 node_modules, 이클립스 .classpath 의 출력 폴더(kind="output")를 고른다.
   * 아직 없는 폴더도 고른다(받은 직후에는 빌드 전이라 없다). 점 폴더와 고른 폴더 · 빌드 결과 폴더 아래로는 내려가지 않는다.
   */
  static List<Path> outputDirs(Path root) {
    List<Path> found = new ArrayList<>();
    collectOutputDirs(root, 0, found);
    return found;
  }

  private static void collectOutputDirs(Path dir, int depth, List<Path> found) {
    List<Path> here = new ArrayList<>();
    for (Map.Entry<String, String> entry : OUTPUT_DIRS.entrySet()) {
      Path out = dir.resolve(entry.getValue());
      if (Files.isRegularFile(dir.resolve(entry.getKey())) && !here.contains(out)) here.add(out);
    }
    Path classpath = dir.resolve(ECLIPSE_CLASSPATH);
    if (Files.isRegularFile(classpath)) {
      try {
        String output = eclipseOutput(Files.readString(classpath));
        if (output != null && !here.contains(dir.resolve(output))) here.add(dir.resolve(output));
      }
      catch (IOException | RuntimeException e) {
        LOG.warn("이클립스 .classpath 를 읽지 못했습니다: " + classpath, e);
      }
    }
    here.sort(null);
    found.addAll(here);
    if (depth == BUILD_DEPTH) return;
    List<Path> children = new ArrayList<>();
    try (var stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
      for (Path child : stream) children.add(child);
    }
    catch (IOException e) {
      LOG.warn("빌드 결과 폴더를 찾다가 폴더를 읽지 못했습니다: " + dir, e);
      return;
    }
    children.sort(null);
    for (Path child : children) {
      String name = child.getFileName().toString();
      if (!name.startsWith(".") && !SKIP_DIRS.contains(name) && !here.contains(child)) collectOutputDirs(child, depth + 1, found);
    }
  }

  /**
   * .classpath 의 출력 폴더(<classpathentry kind="output" path="bin"/>)를 상대 경로로 돌려준다.
   * 없거나 프로젝트 폴더 자신(「.」 · 빈 값)이거나 절대 경로 · 위로 나가는 경로면 null.
   */
  static @Nullable String eclipseOutput(String classpath) {
    Matcher tag = CLASSPATH_ENTRY.matcher(classpath);
    while (tag.find()) {
      String entry = tag.group();
      if (!entry.matches("(?s).*\\bkind=\"output\".*")) continue;
      Matcher path = ENTRY_PATH.matcher(entry);
      if (!path.find()) return null;
      String value = path.group(1).replace('\\', '/');
      while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
      if (value.isEmpty() || value.equals(".") || value.startsWith("/") || value.contains(":") || value.equals("..") || value.startsWith("../") || value.contains("/../")) return null;
      return value;
    }
    return null;
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

  /** 프로젝트에 SVN 매핑이 있거나 프로젝트 폴더가 SVN 작업본 안이면 true. */
  private static boolean svnProject(Project project) {
    for (VcsDirectoryMapping mapping : ProjectLevelVcsManager.getInstance(project).getDirectoryMappings()) {
      if (SyncPanel.VCS_NAME_SVN.equals(mapping.getVcs())) return true;
    }
    return inSvn(Path.of(project.getBasePath()));
  }

  /** 이 폴더나 그 위 어디에 .svn 폴더가 있으면 true. */
  static boolean inSvn(Path dir) {
    for (Path d = dir; d != null; d = d.getParent()) {
      if (Files.isDirectory(d.resolve(".svn"))) return true;
    }
    return false;
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
