package kinggura.sync;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.module.UnloadedModuleDescription;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsConfiguration;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프로젝트 트리 우클릭의 「Close Project」 · 「Open Project」. 이클립스처럼 워크스페이스 안 저장소 하나만 닫고 다시 연다.
 * 닫기: 고른 줄 중 모듈의 콘텐츠 루트인 폴더를 그 모듈째 내린다(ModuleManager 의 unloaded 모듈, IntelliJ Load/Unload Modules 와 같은 상태).
 * 콘텐츠 루트가 그 폴더 안에 있는 다른 모듈(Gradle · Maven 하위 모듈)도 함께 내린다. 열기도 같은 범위를 함께 올린다.
 * 내린 모듈은 색인 · 검색 · 컴파일에서 빠지고 트리에는 UnloadedModule 아이콘 줄로 남는다. 상태는 IntelliJ 가 workspace.xml 에 저장한다.
 * 그 폴더의 VCS 매핑도 떼어 동기화 창 · Git Log · 커밋 창에서 빠지게 하고, 「등록 안 된 VCS 루트」 알림이 뜨지 않게 무시 목록에 넣는다.
 * 열기: 내린 모듈의 콘텐츠 루트 줄을 고르면 모듈을 다시 올리고 매핑을 다시 붙인다(CloneIntoProject.addVcsMapping, .git · .svn 으로 판정).
 * 폴더 · 파일은 건드리지 않는다. 여러 줄을 골라 한꺼번에 닫고 열 수 있다.
 */
public class CloseRepoAction extends DumbAwareAction {

  /** 트리 우클릭의 「Open Project」. */
  public static final class Open extends CloseRepoAction {
    @Override boolean close() { return false; }
  }

  boolean close() { return true; }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabledAndVisible(project != null && !targets(project, e).isEmpty());
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    Map<String, List<String>> targets = targets(project, e);
    if (targets.isEmpty()) return;
    ModuleManager modules = ModuleManager.getInstance(project);
    Set<String> unloaded = new LinkedHashSet<>();
    for (UnloadedModuleDescription description : modules.getUnloadedModuleDescriptions()) unloaded.add(description.getName());
    if (close()) unloaded.addAll(targets.keySet());
    else unloaded.removeAll(targets.keySet());
    // EDT 에서 부르면 모달 진행 창을 띄우고 끝날 때까지 기다린다(ModuleManagerBridgeImpl.setUnloadedModulesSync).
    modules.setUnloadedModulesSync(new ArrayList<>(unloaded));

    List<String> roots = new ArrayList<>();
    for (List<String> paths : targets.values()) roots.addAll(paths);
    VcsConfiguration configuration = VcsConfiguration.getInstance(project);
    if (close()) {
      removeVcsMappings(project, roots);
      configuration.addIgnoredUnregisteredRoots(roots);
    }
    else {
      configuration.removeFromIgnoredUnregisteredRoots(roots);
      for (String root : roots) CloneIntoProject.addVcsMapping(project, Path.of(root));
    }
  }

  /**
   * 고른 줄 중 닫기(열기)할 모듈 이름 → 그 모듈 콘텐츠 루트 경로('/' 구분). 닫기는 올라온 모듈, 열기는 내린 모듈의 루트 줄만 센다.
   * 그 루트 줄 안에 콘텐츠 루트가 있는 같은 상태의 모듈(하위 모듈)도 넣는다.
   */
  private Map<String, List<String>> targets(Project project, AnActionEvent e) {
    Map<String, List<String>> targets = new LinkedHashMap<>();
    VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    if (files == null) {
      VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
      if (file == null) return targets;
      files = new VirtualFile[]{file};
    }
    for (VirtualFile file : files) {
      if (!file.isDirectory()) continue;
      if (close()) {
        Module module = ModuleUtilCore.findModuleForFile(file, project);
        if (module == null || !List.of(ModuleRootManager.getInstance(module).getContentRoots()).contains(file)) continue;
        for (Module inner : ModuleManager.getInstance(project).getModules()) {
          List<String> paths = new ArrayList<>();
          boolean under = false;
          for (VirtualFile root : ModuleRootManager.getInstance(inner).getContentRoots()) {
            paths.add(root.getPath());
            if (VfsUtilCore.isAncestor(file, root, false)) under = true;
          }
          if (under) targets.put(inner.getName(), paths);
        }
        continue;
      }
      var unloaded = ModuleManager.getInstance(project).getUnloadedModuleDescriptions();
      boolean selected = false;
      for (UnloadedModuleDescription description : unloaded) {
        for (VirtualFilePointer root : description.getContentRoots()) {
          if (root.getUrl().equals(file.getUrl())) selected = true;
        }
      }
      if (!selected) continue;
      for (UnloadedModuleDescription description : unloaded) {
        List<String> paths = new ArrayList<>();
        boolean under = false;
        for (VirtualFilePointer root : description.getContentRoots()) {
          paths.add(root.getPresentableUrl().replace('\\', '/'));
          if (VfsUtilCore.isEqualOrAncestor(file.getUrl(), root.getUrl())) under = true;
        }
        if (under) targets.put(description.getName(), paths);
      }
    }
    return targets;
  }

  /** 이 경로들과 같은 폴더의 VCS 매핑을 뺀다. 프로젝트 전체(<Project>) 매핑은 폴더별로 뗄 수 없으므로 그대로 둔다. */
  static void removeVcsMappings(Project project, List<String> roots) {
    ProjectLevelVcsManager vcs = ProjectLevelVcsManager.getInstance(project);
    List<VcsDirectoryMapping> kept = new ArrayList<>();
    boolean removed = false;
    for (VcsDirectoryMapping mapping : vcs.getDirectoryMappings()) {
      boolean match = false;
      for (String root : roots) {
        if (!mapping.isDefaultMapping() && FileUtil.pathsEqual(mapping.getDirectory(), root)) match = true;
      }
      if (match) removed = true;
      else kept.add(mapping);
    }
    if (removed) vcs.setDirectoryMappings(kept);
  }
}
