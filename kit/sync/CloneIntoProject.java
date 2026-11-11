package kinggura.sync;

import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.util.PropertiesComponent;
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
import com.intellij.openapi.module.UnloadedModuleDescription;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsConfiguration;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.checkout.CheckoutListener;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.impl.welcomeScreen.cloneableProjects.CloneableProjectsService;
import com.intellij.psi.PsiDirectory;
import com.intellij.workspaceModel.ide.JpsProjectLoadedListener;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 시작 화면(기본 프로젝트)에서 받았을 때, 대소문자만 다른 것까지 같은 이름 모듈이 이미 있을 때(Close Project 로 내린 모듈 포함, 윈도우에서 .iml 이 서로 덮이므로.
 * 폴더가 없어진 내린 모듈은 켤 때와 받기 전에 지우므로 세지 않는다, dropStaleClosed),
 * SVN 으로 받았을 때, 열린 프로젝트가 SVN 프로젝트일 때(svnProject).
 * IntelliJ 는 SVN 이 하나라도 섞이면 Commit 창 대신 커밋 대화상자를 쓰므로(CommitModeManager.canSetNonModal) Git 과 SVN 을 한 프로젝트에 섞지 않는다.
 * 받은 폴더가 프로젝트 폴더 안이어도 따로 모듈로 붙인다.
 * 워크스페이스 프로젝트(C:\dev\git 처럼 Git 저장소 밖 폴더를 연 것)는 열 때와 Clone 뒤에 그 폴더 자체 모듈(「git」)을 빼고,
 * 프로젝트 창 맨 위의 프로젝트 폴더 줄은 HideWorkspaceRoot 가 그 안의 저장소 모듈 줄들로 바꿔 그린다
 * (IntelliJ 가 열 때마다 다시 등록하므로 등록은 빼지 않는다. 받은 저장소 모듈은 이 폴더 줄 아래에 그려지므로 줄만 지우면 같이 사라진다).
 * 그러면 「git」 줄 없이 받은 저장소 모듈들만 이클립스처럼 나란히 보인다(빈 워크스페이스면 빈 트리, .idea 는 디스크에 그대로).
 * 워크스페이스 판정(workspaceDir): 프로젝트 폴더에 빌드 파일(BUILD_FILES)이 없고, 프로젝트 폴더와 그 위(사용자 폴더 · 드라이브 맨 위는 빼고)에 .git · .svn 이 없고,
 * 프로젝트 폴더 아래에 모듈로 붙은 폴더가 하나 이상 있으며, 프로젝트 폴더를 콘텐츠 루트로 가진 모듈이 없거나 있어도 그 루트 하나뿐이며 소스 폴더가 없다.
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
  private static final String PACKAGE_JSON = "package.json";
  private static final String ECLIPSE_CLASSPATH = ".classpath";
  private static final Pattern CLASSPATH_ENTRY = Pattern.compile("<classpathentry\\b[^>]*>");
  private static final Pattern ENTRY_PATH = Pattern.compile("\\bpath=\"([^\"]*)\"");
  private static final String KIND_SRC = "(?s).*\\bkind=\"src\".*";
  private static final String KIND_OUTPUT = "(?s).*\\bkind=\"output\".*";
  // .classpath 에 적지 않아도 소스 · 웹 폴더로 보는 이클립스 기본 폴더(Dynamic Web Project · MyEclipse).
  private static final List<String> ECLIPSE_SOURCE_DIRS = List.of("src", "WebContent", "WebRoot");
  // 자동으로 제외한 폴더 URL 목록(프로젝트 설정). 여기 있는 폴더는 켤 때 다시 제외하지 않는다(excludeDirs).
  private static final String AUTO_EXCLUDED_KEY = "kinggura.sync.autoExcluded";

  @Override
  public void runActivity(@NotNull Project project) {
    excludeBuildOutput(project);
    attachWorkspaceRepos(project);
    ApplicationManager.getApplication().invokeLater(() -> removeWorkspaceModule(project), project.getDisposed());
    // 캐시 없이 연 프로젝트는 아래 AfterJpsLoad 가 오지 않을 수 있어 색인 뒤에도 한 번 한다. 두 번 해도 결과가 같다.
    DumbService.getInstance(project).smartInvokeLater(() -> tidyClosed(project));
  }

  /** 켤 때 닫은 저장소 정리: 폴더가 없어진 것은 지우고(dropStaleClosed), 나머지는 Maven · 편집기를 맞춘다(CloseRepoAction.syncClosed). EDT. */
  private static void tidyClosed(Project project) {
    if (project.isDisposed()) return;
    dropStaleClosed(project, null);
    CloseRepoAction.syncClosed(project);
  }

  /**
   * IntelliJ 는 캐시로 모듈을 먼저 올리고 몇 초 뒤 프로젝트 파일(.iml · modules.xml)을 다시 읽어 맞춘다(JpsProjectModelSynchronizer).
   * 그 전에 지운 닫힌 모듈은 그때 되살아나므로, 다시 읽기가 끝난 뒤(JpsProjectLoadedListener)에도 정리한다.
   */
  public static final class AfterJpsLoad implements JpsProjectLoadedListener {
    private final Project project;

    public AfterJpsLoad(Project project) {
      this.project = project;
    }

    @Override
    public void loaded() {
      ApplicationManager.getApplication().invokeLater(() -> tidyClosed(project), project.getDisposed());
    }
  }

  @Override
  public boolean processCheckedOutDirectory(@NotNull Project project, @NotNull Path directory) {
    // 이클립스 Clone 의 「Initial branch」: 모듈로 붙이거나 새 창으로 열기 전에 받을 브랜치로 바꿔 Maven · Gradle 이 그 브랜치 파일을 읽게 한다.
    CloneBranch.choose(project.isDefault() || project.isDisposed() ? null : project, directory.toFile());
    String base = project.isDefault() || project.isDisposed() ? null : project.getBasePath();
    if (base == null) return false;
    VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
    if (dir == null) return false;
    String name = dir.getName();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      if (!project.isDisposed()) dropStaleClosed(project, directory);
    });
    if (moduleNameTaken(project, name)) return false;
    if (Files.isDirectory(directory.resolve(".svn")) || svnProject(project)) return false;

    // 이 리스너는 Clone 작업 스레드에서 불린다(CloneableProjectsService.runCloneTask). 모듈 · 매핑은 EDT 에서 바꾼다.
    // 붙이다 실패하면 만든 모듈을 지우고 false 를 돌려 IntelliJ 기본 동작(새 프로젝트로 열기)에 맡긴다. 반쯤 붙은 모듈을 남기지 않는다.
    AtomicReference<Module> created = new AtomicReference<>();
    AtomicBoolean mapped = new AtomicBoolean();
    String path = directory.toString().replace('\\', '/');
    try {
      ApplicationManager.getApplication().invokeAndWait(() -> {
        if (project.isDisposed()) return;
        created.set(WriteAction.compute(() -> ModuleManager.getInstance(project)
          .newModule(Path.of(base, Project.DIRECTORY_STORE_FOLDER, "modules", name + ".iml"), ModuleTypeManager.getInstance().getDefaultModuleType().getId())));
        ModuleRootModificationUtil.addContentRoot(created.get(), dir);
        ModuleRootModificationUtil.setSdkInherited(created.get());
        excludeDirs(created.get(), outputDirs(directory), true);
        mapped.set(true);
        addVcsMapping(project, directory);
        removeWorkspaceModule(project);
      });
    }
    catch (RuntimeException e) {
      LOG.warn("Clone 한 폴더를 모듈로 붙이지 못해 새 프로젝트로 엽니다: " + directory, e);
      Module module = created.get();
      ApplicationManager.getApplication().invokeAndWait(() -> {
        if (project.isDisposed()) return;
        if (module != null && !module.isDisposed()) disposeModule(project, module);
        if (mapped.get()) CloseRepoAction.removeVcsMappings(project, List.of(path));
      });
      return false;
    }
    // 붙이는 사이 프로젝트가 닫혔으면 붙은 곳이 없으므로 새 프로젝트로 연다.
    if (created.get() == null || project.isDisposed()) return false;
    // Git Clone 은 이 리스너 뒤에 최근 목록에 올리므로 onCloneSuccess 에서 한 번 더 뺀다.
    RecentProjectsManager.getInstance().removePath(path);
    ForgetRecent.ATTACHED.add(path);
    linkBuildFiles(project, directory);
    return true;
  }

  /**
   * 폴더가 없어진 닫은 저장소(내린 모듈)를 프로젝트에서 지운다. EDT 에서 부른다.
   * 콘텐츠 루트가 모두 디스크에 없거나 지금 받은 폴더(cloned) 안이면 지운다(닫은 뒤 탐색기에서 지우고 같은 자리에 다시 받은 경우).
   * 남겨 두면 다시 열 줄도 없이 이름만 잡고 있어, 같은 이름으로 다시 받은 저장소가 워크스페이스에 붙지 않고 새 창으로 열린다(moduleNameTaken).
   * 콘텐츠 루트를 알 수 없는 내린 모듈(CloseRepoAction.rootsOf 가 빔)은 건드리지 않는다.
   */
  private static void dropStaleClosed(Project project, @Nullable Path cloned) {
    ModuleManager modules = ModuleManager.getInstance(project);
    List<UnloadedModuleDescription> stale = new ArrayList<>();
    List<String> staleRoots = new ArrayList<>();
    for (UnloadedModuleDescription description : modules.getUnloadedModuleDescriptions()) {
      List<String> roots = CloseRepoAction.rootsOf(project, description);
      boolean gone = !roots.isEmpty();
      for (String path : roots) {
        Path root = Path.of(FileUtil.toSystemDependentName(path));
        if (Files.exists(root) && (cloned == null || !root.startsWith(cloned))) gone = false;
      }
      if (gone) {
        stale.add(description);
        staleRoots.addAll(roots);
      }
    }
    if (stale.isEmpty()) return;
    LOG.info("폴더가 없어진 닫은 저장소를 지웁니다: " + stale);
    WriteAction.run(() -> modules.removeUnloadedModules(stale));
    // 닫은 기록도 뺀다. 남으면 같은 자리에 다시 받은 저장소를 켤 때 다시 닫는다(CloseRepoAction.reclose).
    CloseRepoAction.forgetClosed(project, staleRoots);
    // 닫을 때 넣은 「등록 안 된 루트 무시」도 뺀다. 남으면 같은 자리에 IDE 밖에서 다시 받은 저장소를 IntelliJ 가 찾지 않아 동기화 창 · Git Log 에 안 보인다.
    VcsConfiguration.getInstance(project).removeFromIgnoredUnregisteredRoots(staleRoots);
  }

  /** 올라온 모듈이나 내린 모듈 중 대소문자를 가리지 않고 같은 이름이 있으면 true. 새 모듈 만들기는 같은 이름의 내린 모듈을 지우고, 윈도우에서는 .iml 이 대소문자만 달라도 덮인다. */
  private static boolean moduleNameTaken(Project project, String name) {
    ModuleManager modules = ModuleManager.getInstance(project);
    for (Module module : modules.getModules()) {
      if (module.getName().equalsIgnoreCase(name)) return true;
    }
    for (UnloadedModuleDescription description : modules.getUnloadedModuleDescriptions()) {
      if (description.getName().equalsIgnoreCase(name)) return true;
    }
    return false;
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
      DumbService.getInstance(project).smartInvokeLater(() -> performLink(project, actionId, vf));
    }
  }

  /** 빌드 파일 하나에 연결 동작을 실행한다. 동작이 없거나(플러그인 꺼짐) 이미 연결된 파일이면 건너뛴다. EDT 에서 부른다. */
  private static void performLink(Project project, String actionId, VirtualFile vf) {
    AnAction action = ActionManager.getInstance().getAction(actionId);
    if (action == null || project.isDisposed()) return;
    var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, vf).build();
    AnActionEvent event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context);
    // update 가 이미 연결된 파일이면 숨긴다. 그때 실행하면 Maven 은 다시 읽고 같은 폴더의 다른 빌드 도구 연결을 끊는다.
    ActionUtil.updateAction(action, event);
    if (event.getPresentation().isEnabledAndVisible()) ActionUtil.performAction(action, event);
  }

  /**
   * 열 때 저장소의 빌드 파일 중 아직 연결 안 된 것을 연결한다(linkBuildFiles 와 같은 파일, 같은 동작). 색인이 끝난 뒤 EDT 에서 돈다.
   * pom.xml 은 Maven 목록이 읽힌 뒤 목록에 없는 것만 연결한다(MavenRepos.linkIfUnknown). 켠 직후 목록이 비어 있을 때 연결하면
   * 「Add as Maven Project」 가 이미 연결된 pom 에도 보여 열 때마다 Maven 을 처음부터 다시 읽기 때문이다.
   */
  private static void linkOnOpen(Project project, Path repo) {
    for (Path file : buildFiles(repo)) {
      VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
      if (vf == null) continue;
      if (!file.getFileName().toString().equals(POM)) {
        DumbService.getInstance(project).smartInvokeLater(() -> performLink(project, ACTION_LINK_GRADLE, vf));
      }
      else if (CloseRepoAction.mavenEnabled()) {
        DumbService.getInstance(project).smartInvokeLater(() -> MavenRepos.linkIfUnknown(project, vf, () -> performLink(project, ACTION_LINK_MAVEN, vf), 0));
      }
    }
  }

  /**
   * 워크스페이스로 쓰는 폴더(C:\dev\git 처럼 저장소들을 담은 폴더)를 열 때마다 이클립스의 Import Existing Projects 처럼 그 안의 저장소를 붙인다.
   * 모듈이 아닌 저장소 폴더는 Clone 과 같은 모듈로 붙이고(attachRepo), 저장소마다 아직 연결 안 된 pom.xml · Gradle 파일을 연결한다(linkBuildFiles).
   * 대상 폴더는 looseWorkspace · repoDirs 가 고른다. 닫은 저장소(내린 모듈)와 겹치는 폴더는 붙이지도 연결하지도 않는다.
   * 시작 작업 스레드에서 부른다. 폴더 찾기는 이 스레드에서, 모듈 붙이기는 EDT 에서 한다. 바로 뒤에 오는 removeWorkspaceModule 보다 먼저 EDT 에 올린다.
   */
  private static void attachWorkspaceRepos(Project project) {
    String base = project.isDefault() ? null : project.getBasePath();
    if (base == null || !looseWorkspace(Path.of(base)) || svnProject(project)) return;
    List<String> closed = ReadAction.compute(() -> closedPathsIfLoose(project, base));
    if (closed == null) return;
    List<Path> repos = new ArrayList<>();
    for (Path dir : repoDirs(Path.of(base))) {
      String path = dir.toString().replace('\\', '/');
      boolean overlaps = false;
      for (String root : closed) {
        if (FileUtil.isAncestor(root, path, false) || FileUtil.isAncestor(path, root, false)) overlaps = true;
      }
      if (!overlaps) repos.add(dir);
    }
    if (repos.isEmpty()) return;
    ApplicationManager.getApplication().invokeLater(() -> attachRepos(project, repos), project.getDisposed());
    // 연결은 색인이 끝난 뒤 EDT 에서 돈다(smartInvokeLater). 위 모듈 붙이기보다 늦게 돈다.
    for (Path repo : repos) linkOnOpen(project, repo);
  }

  /** 프로젝트 폴더에 빌드 파일이 없고, 사용자 폴더 · 드라이브 맨 위가 아니고, 그 폴더와 그 위에 .git · .svn 이 없으면 true(workspaceDir 의 폴더 판정과 같음). */
  static boolean looseWorkspace(Path base) {
    if (aboveWorkspaces(base)) return false;
    for (String file : BUILD_FILES) {
      if (Files.isRegularFile(base.resolve(file))) return false;
    }
    for (Path dir = base; dir != null && !aboveWorkspaces(dir); dir = dir.getParent()) {
      if (Files.exists(dir.resolve(".git")) || Files.isDirectory(dir.resolve(".svn"))) return false;
    }
    return true;
  }

  /**
   * 닫은 저장소(내린 모듈)의 콘텐츠 루트 경로. 프로젝트가 닫혔거나, 프로젝트 폴더를 콘텐츠 루트로 가진 모듈에 소스 폴더가 있으면 null
   * (소스 폴더를 잡은 폴더 모듈은 사용자가 꾸민 프로젝트이므로 워크스페이스로 보지 않는다, workspaceDir 과 같음). 읽기 잠금 안에서 부른다.
   */
  private static @Nullable List<String> closedPathsIfLoose(Project project, String base) {
    if (project.isDisposed()) return null;
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
      for (VirtualFile root : rootManager.getContentRoots()) {
        if (FileUtil.pathsEqual(root.getPath(), base) && rootManager.getSourceRoots().length != 0) return null;
      }
    }
    List<String> paths = new ArrayList<>();
    for (VirtualFile root : CloseRepoAction.closedRoots(project)) paths.add(root.getPath());
    return paths;
  }

  /** 저장소 폴더들을 차례로 모듈로 붙인다(attachRepo). EDT 에서 부른다. */
  private static void attachRepos(Project project, List<Path> repos) {
    for (Path repo : repos) {
      if (project.isDisposed()) return;
      attachRepo(project, repo);
    }
  }

  /**
   * 워크스페이스 바로 아래 저장소 폴더: .git 이나 package.json 이 있거나 그 안에 빌드 파일(buildFiles)이 있는 폴더. 이름순.
   * 모듈이 아닌 폴더는 워크스페이스 트리에서 안 보이므로(HideWorkspaceRoot) Git 없이 복사한 화면 프로젝트도 붙인다.
   * 점으로 시작하는 폴더, 빌드 결과 폴더(SKIP_DIRS), SVN 작업본(Git 과 한 프로젝트에 섞지 않음)은 뺀다. 폴더를 읽지 못하면 빈 목록.
   */
  static List<Path> repoDirs(Path base) {
    List<Path> children = new ArrayList<>();
    try (var stream = Files.newDirectoryStream(base, Files::isDirectory)) {
      for (Path child : stream) children.add(child);
    }
    catch (IOException e) {
      LOG.warn("워크스페이스 폴더를 읽지 못했습니다: " + base, e);
      return List.of();
    }
    children.sort(null);
    List<Path> repos = new ArrayList<>();
    for (Path child : children) {
      String name = child.getFileName().toString();
      if (name.startsWith(".") || SKIP_DIRS.contains(name) || Files.isDirectory(child.resolve(".svn"))) continue;
      if (Files.exists(child.resolve(".git")) || Files.isRegularFile(child.resolve(PACKAGE_JSON)) || !buildFiles(child).isEmpty()) repos.add(child);
    }
    return repos;
  }

  /**
   * 저장소 폴더를 Clone 과 같은 모듈(.idea/modules/폴더이름.iml, 프로젝트 SDK, 빌드 결과 제외, Git 매핑)로 붙인다. EDT 에서 부른다.
   * 그 폴더나 그 위 · 아래 폴더가 이미 어느 모듈의 콘텐츠 루트이거나(프로젝트 폴더 모듈은 빼고), 같은 이름 모듈이 있으면(moduleNameTaken) 붙이지 않는다.
   * 붙이다 실패하면 만든 모듈과 매핑을 되돌리고 기록만 남긴다.
   */
  private static void attachRepo(Project project, Path repo) {
    VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repo);
    if (dir == null || moduleNameTaken(project, dir.getName())) return;
    String base = project.getBasePath();
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
        if (FileUtil.pathsEqual(root.getPath(), base)) continue;
        if (VfsUtilCore.isAncestor(root, dir, false) || VfsUtilCore.isAncestor(dir, root, false)) return;
      }
    }
    Module created = null;
    boolean mapped = false;
    try {
      created = WriteAction.compute(() -> ModuleManager.getInstance(project)
        .newModule(Path.of(base, Project.DIRECTORY_STORE_FOLDER, "modules", dir.getName() + ".iml"), ModuleTypeManager.getInstance().getDefaultModuleType().getId()));
      ModuleRootModificationUtil.addContentRoot(created, dir);
      ModuleRootModificationUtil.setSdkInherited(created);
      excludeDirs(created, outputDirs(repo), true);
      mapped = true;
      addVcsMapping(project, repo);
    }
    catch (RuntimeException e) {
      LOG.warn("워크스페이스의 저장소 폴더를 모듈로 붙이지 못했습니다: " + repo, e);
      if (created != null && !created.isDisposed()) disposeModule(project, created);
      if (mapped) CloseRepoAction.removeVcsMappings(project, List.of(repo.toString().replace('\\', '/')));
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
      for (Map.Entry<Module, List<Path>> entry : found.entrySet()) excludeDirs(entry.getKey(), entry.getValue(), false);
    }, project.getDisposed());
  }

  /**
   * 아직 제외가 아닌 폴더를 그 폴더를 품은 콘텐츠 루트의 제외 폴더로 더한다. EDT 에서 부른다.
   * 소스 폴더이거나 소스 폴더를 품은 폴더는 건너뛴다(이클립스 출력 폴더를 소스 폴더와 같게 둔 프로젝트). 더할 것이 없으면 모듈을 고치지 않는다.
   * 한 번 제외한(또는 이미 제외였던) 폴더는 AUTO_EXCLUDED_KEY 에 적고, 켤 때(fresh=false)는 적힌 폴더를 다시 제외하지 않는다.
   * 사용자가 「Cancel Exclusion」으로 푼 것을 지키기 위해서다. 방금 받은 저장소(fresh=true)는 적힌 것이어도 제외한다(지우고 다시 받은 경우).
   */
  static void excludeDirs(Module module, List<Path> dirs, boolean fresh) {
    if (module.isDisposed()) return;
    ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
    List<String> have = List.of(rootManager.getExcludeRootUrls());
    List<String> sources = List.of(rootManager.getSourceRootUrls());
    PropertiesComponent properties = PropertiesComponent.getInstance(module.getProject());
    List<String> done = new ArrayList<>();
    List<String> saved = properties.getList(AUTO_EXCLUDED_KEY);
    if (saved != null) done.addAll(saved);
    int doneBefore = done.size();
    List<String> add = new ArrayList<>();
    for (Path dir : dirs) {
      String url = VfsUtilCore.pathToUrl(FileUtil.toSystemIndependentName(dir.toString()));
      boolean recorded = done.contains(url);
      if (!recorded) done.add(url);
      if (have.contains(url) || add.contains(url) || coversAny(url, sources) || (recorded && !fresh)) continue;
      add.add(url);
    }
    if (done.size() != doneBefore) properties.setList(AUTO_EXCLUDED_KEY, done);
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
   * .classpath 의 첫 출력 폴더(<classpathentry kind="output" path="bin"/>)를 상대 경로로 돌려준다.
   * 없거나 프로젝트 폴더 자신(「.」 · 빈 값)이거나 절대 경로 · 위로 나가는 경로면 null.
   * 같은 .classpath 의 소스 폴더(kind="src")나 ECLIPSE_SOURCE_DIRS 와 같거나 그것을 품은 폴더여도 null(대소문자 무시, 윈도우 경로).
   */
  static @Nullable String eclipseOutput(String classpath) {
    List<String> sources = new ArrayList<>(ECLIPSE_SOURCE_DIRS);
    String output = null;
    Matcher tag = CLASSPATH_ENTRY.matcher(classpath);
    while (tag.find()) {
      String entry = tag.group();
      Matcher path = ENTRY_PATH.matcher(entry);
      String value = path.find() ? path.group(1).replace('\\', '/') : "";
      while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
      if (entry.matches(KIND_SRC)) sources.add(value);
      else if (output == null && entry.matches(KIND_OUTPUT)) output = value;
    }
    if (output == null || output.isEmpty() || output.equals(".") || output.startsWith("/") || output.contains(":") || output.equals("..") || output.startsWith("../") || output.contains("/../")) return null;
    String lower = output.toLowerCase(Locale.ROOT);
    for (String source : sources) {
      String s = source.toLowerCase(Locale.ROOT);
      if (s.equals(lower) || s.startsWith(lower + "/")) return null;
    }
    return output;
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
   * 프로젝트 폴더에 빌드 파일이 있거나(zip 으로 받은 다중 모듈 프로젝트 · 맨 위 묶음 빌드 파일), Git · SVN 저장소 안이거나,
   * 그 폴더 아래에 모듈로 붙은 폴더가 하나도 없으면(그냥 폴더 · SVN 작업본 · 소스 폴더를 아직 안 정한 프로젝트) 워크스페이스가 아니다.
   */
  static @Nullable VirtualFile workspaceDir(Project project) {
    String base = project.isDefault() ? null : project.getBasePath();
    if (base == null) return null;
    for (String file : BUILD_FILES) {
      if (Files.isRegularFile(Path.of(base, file))) return null;
    }
    for (Path dir = Path.of(base); dir != null && !aboveWorkspaces(dir); dir = dir.getParent()) {
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
    // 저장소를 모두 닫아도(Close Project) 워크스페이스로 본다.
    for (VirtualFile root : CloseRepoAction.closedRoots(project)) {
      if (VfsUtilCore.isAncestor(baseDir, root, true)) attached = true;
    }
    return attached ? baseDir : null;
  }

  /** 사용자 폴더이거나 그 위, 또는 드라이브 맨 위면 true. 여기 우연히 있는 .git · .svn 은 저장소로 보지 않는다. */
  static boolean aboveWorkspaces(Path dir) {
    return dir.getParent() == null || Path.of(System.getProperty("user.home")).startsWith(dir);
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

  /**
   * 워크스페이스 프로젝트의 프로젝트 창 맨 위에서 프로젝트 폴더 줄을 그 안의 저장소 모듈 줄들로 바꾼다(모듈로 붙지 않은 폴더 · 파일 · .idea 는 안 보임).
   * 워크스페이스 바로 아래가 아닌 저장소(git\그룹\저장소)는 그것을 품은 중간 폴더 줄(「그룹」) 아래에 보인다. 중간 폴더 줄에도 저장소 · 중간 폴더만 보인다.
   * 닫은 저장소(내린 모듈의 콘텐츠 루트)는 원래 자리에 닫힌 줄(CloseRepoAction.ClosedRepoNode)로 보인다. IntelliJ 는 내린 모듈 폴더를 프로젝트 밖으로 보고
   * 트리에서 빼므로(ProjectViewDirectoryHelper.shouldBeShown) 여기서 넣는다. 워크스페이스 밖에 붙였다가 닫은 저장소는 맨 위에 넣는다.
   */
  public static final class HideWorkspaceRoot implements TreeStructureProvider, com.intellij.openapi.project.DumbAware {
    @Override
    public @NotNull Collection<AbstractTreeNode<?>> modify(@NotNull AbstractTreeNode<?> parent,
                                                           @NotNull Collection<AbstractTreeNode<?>> children,
                                                           ViewSettings settings) {
      Project project = parent.getProject();
      if (project == null || project.isDisposed()) return children;
      VirtualFile baseDir = workspaceDir(project);
      if (baseDir == null) return withClosedOnly(project, parent, children, settings);
      // 워크스페이스 폴더 자체 모듈(Maven 다시 읽기 사이 잠깐 다시 등록됨)의 루트는 저장소 루트로 치지 않는다.
      List<VirtualFile> loaded = new ArrayList<>();
      for (Module module : ModuleManager.getInstance(project).getModules()) {
        for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
          if (!root.equals(baseDir)) loaded.add(root);
        }
      }
      List<VirtualFile> repos = new ArrayList<>(loaded);
      repos.addAll(CloseRepoAction.closedRoots(project));
      if (parent.getValue() instanceof Project) {
        List<AbstractTreeNode<?>> shown = new ArrayList<>();
        AbstractTreeNode<?> workspaceNode = null;
        for (AbstractTreeNode<?> child : children) {
          if (child.getValue() instanceof PsiDirectory dir && baseDir.equals(dir.getVirtualFile())) workspaceNode = child;
          else shown.add(child);
        }
        List<AbstractTreeNode<?>> inner = new ArrayList<>();
        if (workspaceNode != null) {
          for (AbstractTreeNode<?> node : workspaceNode.getChildren()) {
            if (!(node.getValue() instanceof PsiDirectory dir) || !leadsToRepo(dir.getVirtualFile(), repos)) continue;
            node.setParent(parent);
            inner.add(node);
          }
        }
        shown.addAll(0, inner);
        // 밖에 붙였다가 닫은 저장소는 IntelliJ 가 맨 위에 보통 폴더 줄로 남기기도 해 맨 위 줄 전체에서 닫힌 줄로 바꾼다.
        List<AbstractTreeNode<?>> result = CloseRepoAction.withClosedRepos(project, parent, shown, baseDir, settings);
        CloseRepoAction.addOutsideClosedRepos(project, parent, result, baseDir, loaded, settings);
        return result;
      }
      if (!(parent.getValue() instanceof PsiDirectory parentDir)) return children;
      VirtualFile dir = parentDir.getVirtualFile();
      List<AbstractTreeNode<?>> shown = new ArrayList<>(children);
      if (between(dir, baseDir, loaded)) {
        // 모듈로 붙은 저장소 폴더와 그것을 품은 중간 폴더만 보인다. 워크스페이스 폴더 모듈이 다시 등록된 사이(Maven 다시 읽기 · 모듈 가져오기)에
        // 붙이지 않은 옆 폴더 · .idea · 낱 파일이 잠깐 보였다 사라지지 않게 한다.
        shown.removeIf(node -> !(node.getValue() instanceof PsiDirectory d) || !leadsToRepo(d.getVirtualFile(), repos));
      }
      return CloseRepoAction.withClosedRepos(project, parent, shown, dir, settings);
    }

    /**
     * 워크스페이스가 아닌 프로젝트(저장소를 프로젝트 폴더 밖에 붙인 창 등)에서는 줄을 숨기지 않고 닫은 저장소 줄만 넣는다.
     * IntelliJ 는 내린 모듈 폴더를 그리지 않아 닫힌 줄이 없으면 다시 열 수 없다.
     */
    private static Collection<AbstractTreeNode<?>> withClosedOnly(Project project, AbstractTreeNode<?> parent,
                                                                  Collection<AbstractTreeNode<?>> children, ViewSettings settings) {
      if (CloseRepoAction.closedRoots(project).isEmpty()) return children;
      List<AbstractTreeNode<?>> shown = new ArrayList<>(children);
      if (parent.getValue() instanceof PsiDirectory dir) return CloseRepoAction.withClosedRepos(project, parent, shown, dir.getVirtualFile(), settings);
      if (!(parent.getValue() instanceof Project)) return children;
      String base = project.getBasePath();
      VirtualFile baseDir = base == null ? null : LocalFileSystem.getInstance().findFileByPath(base);
      if (baseDir == null) return children;
      List<VirtualFile> loaded = new ArrayList<>();
      for (Module module : ModuleManager.getInstance(project).getModules()) loaded.addAll(List.of(ModuleRootManager.getInstance(module).getContentRoots()));
      List<AbstractTreeNode<?>> result = CloseRepoAction.withClosedRepos(project, parent, shown, baseDir, settings);
      CloseRepoAction.addOutsideClosedRepos(project, parent, result, baseDir, loaded, settings);
      return result;
    }

    /** 이 폴더가 저장소 루트(올라온 · 닫은 모듈의 콘텐츠 루트)이거나 그것을 품으면 true. */
    private static boolean leadsToRepo(VirtualFile dir, List<VirtualFile> repos) {
      for (VirtualFile repo : repos) {
        if (VfsUtilCore.isAncestor(dir, repo, false)) return true;
      }
      return false;
    }

    /** 워크스페이스 폴더 안이고 어느 저장소 모듈 폴더 안도 아닌 중간 폴더면 true. */
    private static boolean between(VirtualFile dir, VirtualFile baseDir, List<VirtualFile> loaded) {
      if (!VfsUtilCore.isAncestor(baseDir, dir, true)) return false;
      for (VirtualFile root : loaded) {
        if (VfsUtilCore.isAncestor(root, dir, false)) return false;
      }
      return true;
    }
  }

  /**
   * 받은 폴더에 .git 이면 Git, .svn 이면 SVN 매핑을 넣는다(SVN Checkout 은 IntelliJ 가 매핑을 넣지 않는다).
   * 프로젝트 폴더 안에 Git 으로 받으면 IntelliJ Clone 이 VFS 가 폴더를 알기 전에 같은 매핑을 먼저 넣어
   * 「Invalid mapping」 으로 버리고, 같은 목록을 다시 넣으면 다시 계산하지 않는다(NewMappings.updateVcsMappings).
   * 그래서 그 매핑을 뺀 목록을 한 번 넣었다가 다시 넣어, VFS 에 올라온 뒤 루트를 바로 다시 계산하게 한다.
   */
  static void addVcsMapping(Project project, Path directory) {
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
