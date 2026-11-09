package kinggura.sync;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 닫은 저장소를 Maven 에서 빼고 되돌린다(이클립스에서 닫은 프로젝트 대신 로컬 저장소 jar 를 쓰는 것처럼).
 * IntelliJ Maven 은 내린 모듈을 가져오지 않지만(WorkspaceProjectImporter 가 UnloadedModulesNameHolder 로 건너뜀) 그 pom 은 Maven 목록에 남아,
 * 그 저장소를 쓰는 다른 모듈은 내린 모듈에 의존한 채로 남아 컴파일 오류가 난다. 그래서 닫을 때 그 pom 을 무시 목록(Maven 창의 Ignore Projects 와 같음)에 넣고
 * 다시 읽어, 쓰는 쪽 의존이 로컬 저장소 jar 로 바뀌게 한다. 열 때 무시를 풀고 다시 읽어 모듈 의존으로 돌린다.
 * Maven 플러그인이 켜져 있을 때만 이 클래스를 읽는다(CloseRepoAction.mavenEnabled). EDT 에서 부른다.
 */
final class MavenRepos {
  private static final String PACKAGING_POM = "pom";
  // IDE 를 켠 직후 Maven 목록이 읽히기를 기다리는 간격과 횟수(합쳐 5분).
  private static final int RETRY_SECONDS = 3;
  private static final int RETRY_LIMIT = 100;
  // 닫을 때 우리가 Maven 무시 목록에 넣은 pom 경로(프로젝트 설정). 열 때 이것만 푼다(open).
  private static final String IGNORED_KEY = "kinggura.sync.mavenIgnored";

  private MavenRepos() {
  }

  /**
   * 이 폴더들 안의 pom 을 Maven 무시 목록에 넣거나(closed) 뺀다(open). 바뀐 것이 있으면 Maven 을 다시 읽는다(Reload All Maven Projects 와 같음).
   * IDE 를 켠 직후처럼 Maven 목록이 아직 안 읽혔으면 RETRY_SECONDS 마다 다시 해 본다(최대 RETRY_LIMIT 번). Maven 프로젝트가 아니면 하지 않는다.
   */
  static void setClosed(Project project, Collection<String> roots, boolean closed) {
    setClosed(project, List.copyOf(roots), closed, !closed, 0);
  }

  /** 폴더가 없어져 지운 닫은 저장소의 Maven 무시를 푼다(CloseRepoAction.forgetClosed). 열기와 같되 「다시 읽는 중」 알림은 띄우지 않는다. */
  static void forget(Project project, Collection<String> roots) {
    setClosed(project, List.copyOf(roots), false, false, 0);
  }

  private static void setClosed(Project project, List<String> roots, boolean closed, boolean notify, int attempt) {
    if (project.isDisposed()) return;
    MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
    boolean loading = !manager.isInitialized() || (manager.isMavenizedProject() && manager.getProjects().isEmpty());
    if (loading) {
      if (attempt < RETRY_LIMIT) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
          () -> ApplicationManager.getApplication().invokeLater(() -> setClosed(project, roots, closed, notify, attempt + 1), project.getDisposed()),
          RETRY_SECONDS, TimeUnit.SECONDS);
      }
      return;
    }
    if (!manager.isMavenizedProject()) return;
    if (!closed) {
      open(project, manager, roots, notify);
      return;
    }
    List<MavenProject> change = new ArrayList<>();
    for (MavenProject maven : manager.getProjects()) {
      if (manager.getIgnoredState(maven) == closed) continue;
      for (String root : roots) {
        if (FileUtil.isAncestor(root, maven.getDirectory(), false)) {
          change.add(maven);
          break;
        }
      }
    }
    if (change.isEmpty()) return;
    List<String> ours = ignoredByUs(project);
    for (MavenProject maven : change) {
      if (!ours.contains(maven.getPath())) ours.add(maven.getPath());
    }
    PropertiesComponent.getInstance(project).setList(IGNORED_KEY, ours);
    manager.setIgnoredState(change, true);
    manager.forceUpdateAllProjectsOrFindAllAvailablePomFiles();
  }

  /**
   * 이 폴더들 안의 pom 무시를 푼다. 닫을 때 우리가 무시한 pom(IGNORED_KEY)만 풀고, 사용자가 Maven 창에서 직접 무시한 pom 은 그대로 둔다.
   * 우리 기록이 하나도 없는 폴더(기록을 남기기 전 판에서 닫음)는 그 안의 무시를 모두 푼다.
   * Maven 목록이 아니라 무시 경로 목록을 보므로, 폴더를 지워 Maven 목록에서 빠진 pom 도 풀린다(같은 자리에 다시 받은 경우).
   */
  private static void open(Project project, MavenProjectsManager manager, List<String> roots, boolean notify) {
    List<String> ours = ignoredByUs(project);
    List<String> unignore = toUnignore(manager.getIgnoredFilesPaths(), ours, roots);
    if (ours.removeIf(path -> anyAncestor(roots, path))) PropertiesComponent.getInstance(project).setList(IGNORED_KEY, ours);
    if (unignore.isEmpty()) return;
    manager.removeIgnoredFilesPaths(unignore);
    manager.forceUpdateAllProjectsOrFindAllAvailablePomFiles();
    // 다시 읽는 동안 Maven 이 그 모듈을 지웠다 새로 만들어 트리에서 줄이 잠깐 빈다.
    if (notify) SyncPanel.balloon(project, false, SyncBundle.message("open.repo.maven.title"), SyncBundle.message("open.repo.maven"));
  }

  /** 무시 경로(ignored) 중 열 폴더(roots) 안이고, 우리가 무시한 것(ours)이거나 그 폴더에 우리 기록이 없는 것. */
  static List<String> toUnignore(List<String> ignored, List<String> ours, List<String> roots) {
    List<String> result = new ArrayList<>();
    for (String path : ignored) {
      for (String root : roots) {
        if (FileUtil.isAncestor(root, path, false) && (containsPath(ours, path) || !anyUnder(ours, root))) {
          result.add(path);
          break;
        }
      }
    }
    return result;
  }

  private static List<String> ignoredByUs(Project project) {
    List<String> saved = PropertiesComponent.getInstance(project).getList(IGNORED_KEY);
    return saved == null ? new ArrayList<>() : new ArrayList<>(saved);
  }

  private static boolean containsPath(List<String> paths, String path) {
    for (String p : paths) {
      if (FileUtil.pathsEqual(p, path)) return true;
    }
    return false;
  }

  private static boolean anyUnder(List<String> paths, String root) {
    for (String p : paths) {
      if (FileUtil.isAncestor(root, p, false)) return true;
    }
    return false;
  }

  private static boolean anyAncestor(List<String> roots, String path) {
    for (String root : roots) {
      if (FileUtil.isAncestor(root, path, false)) return true;
    }
    return false;
  }

  /**
   * 이 모듈들 중 닫으면 쓰는 쪽이 jar 로 바꿔 쓸 수 없을 수 있는 것의 이름. Maven 모듈이 아니거나 좌표를 모르거나, jar 묶음인데 로컬 저장소에 그 jar 가 없으면 넣는다.
   * 묶음이 pom 인 모듈(부모 · 묶음 pom)은 jar 가 없어도 되므로 넣지 않는다.
   */
  static List<String> missingJars(Project project, Collection<Module> modules) {
    MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
    Path repository = manager.getRepositoryPath();
    List<String> missing = new ArrayList<>();
    for (Module module : modules) {
      MavenProject maven = mavenProjectOf(manager, module);
      MavenId id = maven == null ? null : maven.getMavenId();
      if (repository == null || id == null || id.getGroupId() == null || id.getArtifactId() == null || id.getVersion() == null) {
        missing.add(module.getName());
        continue;
      }
      if (PACKAGING_POM.equals(maven.getPackaging())) continue;
      String jar = id.getArtifactId() + "-" + id.getVersion() + ".jar";
      Path file = repository.resolve(id.getGroupId().replace('.', '/')).resolve(id.getArtifactId()).resolve(id.getVersion()).resolve(jar);
      if (!Files.isRegularFile(file)) missing.add(jar);
    }
    return missing;
  }

  /**
   * 콘텐츠 루트가 pom 폴더와 같은 Maven 프로젝트. 없으면 null.
   * MavenProjectsManager.findProject(Module) 는 Clone 뒤 「Add as Maven Project」 로 붙인 모듈(우리가 만든 .iml 모듈)에 null 을 돌려줘 폴더로 찾는다.
   */
  private static MavenProject mavenProjectOf(MavenProjectsManager manager, Module module) {
    MavenProject found = manager.findProject(module);
    if (found != null) return found;
    for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
      for (MavenProject maven : manager.getProjects()) {
        if (FileUtil.pathsEqual(maven.getDirectory(), root.getPath())) return maven;
      }
    }
    return null;
  }
}
