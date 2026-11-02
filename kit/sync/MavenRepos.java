package kinggura.sync;

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

  private MavenRepos() {
  }

  /**
   * 이 폴더들 안의 pom 을 Maven 무시 목록에 넣거나(closed) 뺀다. 바뀐 것이 있으면 Maven 을 다시 읽는다(Reload All Maven Projects 와 같음).
   * IDE 를 켠 직후처럼 Maven 목록이 아직 안 읽혔으면 RETRY_SECONDS 마다 다시 해 본다(최대 RETRY_LIMIT 번). Maven 프로젝트가 아니면 하지 않는다.
   */
  static void setClosed(Project project, Collection<String> roots, boolean closed) {
    setClosed(project, List.copyOf(roots), closed, 0);
  }

  private static void setClosed(Project project, List<String> roots, boolean closed, int attempt) {
    if (project.isDisposed()) return;
    MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
    boolean loading = !manager.isInitialized() || (manager.isMavenizedProject() && manager.getProjects().isEmpty());
    if (loading) {
      if (attempt < RETRY_LIMIT) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
          () -> ApplicationManager.getApplication().invokeLater(() -> setClosed(project, roots, closed, attempt + 1), project.getDisposed()),
          RETRY_SECONDS, TimeUnit.SECONDS);
      }
      return;
    }
    if (!manager.isMavenizedProject()) return;
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
    manager.setIgnoredState(change, closed);
    manager.forceUpdateAllProjectsOrFindAllAvailablePomFiles();
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
