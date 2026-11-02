package kinggura.sync;

import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ViewSettings;
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.ModuleListener;
import com.intellij.openapi.ui.Messages;
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
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiManager;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 프로젝트 트리 우클릭의 「Close Project」 · 「Open Project」. 이클립스처럼 워크스페이스 안 저장소 하나만 닫고 다시 연다.
 * 닫기: 고른 줄 중 모듈의 콘텐츠 루트인 폴더를 그 모듈째 내린다(ModuleManager 의 unloaded 모듈, IntelliJ Load/Unload Modules 와 같은 상태).
 * 콘텐츠 루트가 그 폴더 안에 있는 다른 모듈(Gradle · Maven 하위 모듈)도 함께 내린다. 열기도 같은 범위를 함께 올린다.
 * 내린 모듈은 색인 · 검색 · 컴파일에서 빠지고 트리에는 닫힌 줄(ClosedRepoNode, UnloadedModule 아이콘 · 흐린 글자 · 펼치지 않음)로 남는다.
 * 상태는 IntelliJ 가 workspace.xml 에 저장한다.
 * 닫기 · 열기 뒤의 정리(VCS 매핑 · Maven · 편집기)는 sync 가 한다. IntelliJ 의 Load/Unload Modules · Remove Module 로 바꿔도 ModuleSync 가 같은 sync 를 부른다.
 * 닫기는 워크스페이스 프로젝트(CloneIntoProject.workspaceDir)의 저장소 줄에서만 보인다. 열기는 어느 프로젝트에서나 보여 예전에 닫은 것도 열 수 있다.
 * 닫을 저장소를 다른 모듈이 쓰는데 그 jar 가 로컬 Maven 저장소에 없으면(그 모듈이 오류 남) 닫기 전에 묻는다.
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
    if (close() && !confirmDependents(project, targets.keySet())) return;
    // 내리기 전에 저장한다. 내린 뒤 그 파일 편집기는 sync 가 닫는다.
    if (close()) FileDocumentManager.getInstance().saveAllDocuments();
    ModuleManager modules = ModuleManager.getInstance(project);
    Set<String> unloaded = new LinkedHashSet<>();
    for (UnloadedModuleDescription description : modules.getUnloadedModuleDescriptions()) unloaded.add(description.getName());
    if (close()) unloaded.addAll(targets.keySet());
    else unloaded.removeAll(targets.keySet());
    // EDT 에서 부르면 모달 진행 창을 띄우고 끝날 때까지 기다린다(ModuleManagerBridgeImpl.setUnloadedModulesSync).
    modules.setUnloadedModulesSync(new ArrayList<>(unloaded));

    List<String> roots = new ArrayList<>();
    for (List<String> paths : targets.values()) roots.addAll(paths);
    // 열기는 고른 줄을 우리가 닫은 것으로 본다. 이 판 전에 닫아 CLOSED_KEY 에 없는 저장소도 매핑 · Maven 이 돌아온다.
    if (close()) sync(project, roots, List.of());
    else sync(project, List.of(), roots);
  }

  /**
   * 닫을 모듈을 쓰는(의존하는) 다른 올라온 모듈이 있고, 닫을 모듈 중 그 jar 를 로컬 Maven 저장소에서 찾을 수 없는 것이 있으면 묻는다.
   * 닫으면 Maven 이 그 pom 을 빼고 다시 읽어 쓰는 쪽은 로컬 저장소 jar 를 쓴다(MavenRepos). jar 가 없으면 쓰는 쪽에서 컴파일 오류가 난다.
   * Maven 이 아닌 모듈은 jar 로 바꿀 수 없으므로 쓰는 쪽이 있으면 묻는다. 계속하면 true.
   */
  private static boolean confirmDependents(Project project, Set<String> closing) {
    ModuleManager modules = ModuleManager.getInstance(project);
    Set<String> users = new LinkedHashSet<>();
    Set<String> used = new LinkedHashSet<>();
    for (Module module : modules.getModules()) {
      if (closing.contains(module.getName())) continue;
      for (String name : ModuleRootManager.getInstance(module).getDependencyModuleNames()) {
        if (!closing.contains(name)) continue;
        users.add(module.getName());
        used.add(name);
      }
    }
    if (users.isEmpty()) return true;
    List<Module> usedModules = new ArrayList<>();
    for (String name : used) {
      Module module = modules.findModuleByName(name);
      if (module != null) usedModules.add(module);
    }
    List<String> missing = mavenEnabled() ? MavenRepos.missingJars(project, usedModules) : new ArrayList<>(used);
    if (missing.isEmpty()) return true;
    String text = SyncBundle.message("close.repo.dependents", String.join(", ", users), String.join("\n", missing));
    return Messages.showOkCancelDialog(project, text, SyncBundle.message("close.repo.dependents.title"),
                                       SyncBundle.message("close.repo.dependents.ok"), Messages.getCancelButton(), Messages.getWarningIcon()) == Messages.OK;
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
    VirtualFile workspace = close() ? CloneIntoProject.workspaceDir(project) : null;
    if (close() && workspace == null) return targets;
    for (VirtualFile file : files) {
      if (!file.isDirectory()) continue;
      if (close()) {
        if (file.equals(workspace)) continue;
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
        if (containsPath(rootsOf(project, description), file.getPath())) selected = true;
      }
      if (!selected) continue;
      for (UnloadedModuleDescription description : unloaded) {
        List<String> paths = rootsOf(project, description);
        boolean under = false;
        for (String root : paths) {
          if (FileUtil.isAncestor(file.getPath(), root, false)) under = true;
        }
        if (under) targets.put(description.getName(), paths);
      }
    }
    return targets;
  }

  /** 내린 모듈의 콘텐츠 루트 중 디스크에 있는 폴더(rootsOf). */
  static List<VirtualFile> closedRoots(Project project) {
    List<VirtualFile> roots = new ArrayList<>();
    for (UnloadedModuleDescription description : ModuleManager.getInstance(project).getUnloadedModuleDescriptions()) {
      for (String path : rootsOf(project, description)) {
        VirtualFile root = LocalFileSystem.getInstance().findFileByPath(path);
        if (root != null && root.isDirectory()) roots.add(root);
      }
    }
    return roots;
  }

  /**
   * 내린 모듈의 콘텐츠 루트 경로('/' 구분).
   * Maven 으로 읽은 모듈(모듈 정보를 .idea 밖 IDE 시스템 폴더에 두는 모듈)이나 저장 전에 내린 모듈은 IDE 를 다시 켜면 IntelliJ 가 루트를 잃는다(설명의 콘텐츠 루트가 빈다).
   * 그래서 내릴 때 적어 둔 경로(ROOTS_KEY + 이름, rememberRoots)를 쓰고, 그것도 없으면(이 판 전에 닫음) 워크스페이스 폴더 바로 아래 같은 이름 폴더를 쓴다.
   * 셋 다 없으면 빈 목록.
   */
  static List<String> rootsOf(Project project, UnloadedModuleDescription description) {
    List<String> roots = new ArrayList<>();
    for (VirtualFilePointer pointer : description.getContentRoots()) roots.add(VfsUtilCore.urlToPath(pointer.getUrl()));
    if (!roots.isEmpty()) return roots;
    List<String> saved = PropertiesComponent.getInstance(project).getList(ROOTS_KEY + description.getName());
    if (saved != null && !saved.isEmpty()) return new ArrayList<>(saved);
    String base = project.getBasePath();
    if (base != null && Files.isDirectory(Path.of(base, description.getName()))) roots.add(base + "/" + description.getName());
    return roots;
  }

  /** 지금 내린 모듈마다 IntelliJ 가 아는 콘텐츠 루트를 적어 둔다(rootsOf). 다시 올린 모듈의 기록은 지운다. */
  private static void rememberRoots(Project project) {
    PropertiesComponent properties = PropertiesComponent.getInstance(project);
    for (UnloadedModuleDescription description : ModuleManager.getInstance(project).getUnloadedModuleDescriptions()) {
      List<String> roots = new ArrayList<>();
      for (VirtualFilePointer pointer : description.getContentRoots()) roots.add(VfsUtilCore.urlToPath(pointer.getUrl()));
      if (!roots.isEmpty()) properties.setList(ROOTS_KEY + description.getName(), roots);
    }
    for (Module module : ModuleManager.getInstance(project).getModules()) properties.unsetValue(ROOTS_KEY + module.getName());
  }

  /**
   * 폴더 dir 의 아래 줄 목록(shown)에서 닫은 저장소 폴더 줄을 ClosedRepoNode 로 바꾸고, dir 바로 아래인데 목록에 없는 닫은 저장소는 닫힌 줄로 더한다.
   * dir 은 워크스페이스 폴더(맨 위 줄)이거나 트리의 어느 폴더 줄이다(중간 폴더 · 저장소 안 하위 모듈 자리).
   */
  static List<AbstractTreeNode<?>> withClosedRepos(Project project, AbstractTreeNode<?> parent, List<AbstractTreeNode<?>> shown,
                                                   VirtualFile dir, ViewSettings settings) {
    List<VirtualFile> closed = closedRoots(project);
    if (closed.isEmpty()) return new ArrayList<>(shown);
    PsiManager psi = PsiManager.getInstance(project);
    Set<VirtualFile> placed = new LinkedHashSet<>();
    List<AbstractTreeNode<?>> result = new ArrayList<>();
    for (AbstractTreeNode<?> node : shown) {
      VirtualFile file = node.getValue() instanceof PsiDirectory d ? d.getVirtualFile() : null;
      if (file == null || !closed.contains(file) || node instanceof ClosedRepoNode) {
        result.add(node);
        if (file != null) placed.add(file);
        continue;
      }
      ClosedRepoNode replaced = new ClosedRepoNode(project, (PsiDirectory)node.getValue(), settings);
      replaced.setParent(parent);
      result.add(replaced);
      placed.add(file);
    }
    for (VirtualFile root : closed) {
      if (placed.contains(root) || !dir.equals(root.getParent())) continue;
      PsiDirectory found = psi.findDirectory(root);
      if (found == null) continue;
      ClosedRepoNode added = new ClosedRepoNode(project, found, settings);
      added.setParent(parent);
      result.add(added);
      placed.add(root);
    }
    return result;
  }

  /**
   * 워크스페이스 폴더 밖에 있고 올라온 모듈 폴더 안도 아닌 닫은 저장소를 맨 위 닫힌 줄로 더한다(올라온 모듈 안이면 그 폴더 줄이 withClosedRepos 로 넣는다).
   * IntelliJ 는 내린 모듈 폴더를 트리에 그리지 않아, 밖에 붙였다가 닫은 저장소는 다시 열 줄이 없다.
   */
  static void addOutsideClosedRepos(Project project, AbstractTreeNode<?> parent, List<AbstractTreeNode<?>> shown,
                                    VirtualFile baseDir, List<VirtualFile> loadedRoots, ViewSettings settings) {
    PsiManager psi = PsiManager.getInstance(project);
    for (VirtualFile root : closedRoots(project)) {
      if (VfsUtilCore.isAncestor(baseDir, root, false)) continue;
      boolean inside = false;
      for (VirtualFile loaded : loadedRoots) {
        if (VfsUtilCore.isAncestor(loaded, root, false)) inside = true;
      }
      for (AbstractTreeNode<?> node : shown) {
        if (node.getValue() instanceof PsiDirectory d && root.equals(d.getVirtualFile())) inside = true;
      }
      PsiDirectory dir = inside ? null : psi.findDirectory(root);
      if (dir == null) continue;
      ClosedRepoNode added = new ClosedRepoNode(project, dir, settings);
      added.setParent(parent);
      shown.add(added);
    }
  }

  /** 닫은 저장소 폴더 줄. 이클립스의 닫힌 프로젝트처럼 이름만 흐리게 보이고 펼쳐지지 않는다. 우클릭 「Open Project」로 연다. */
  static final class ClosedRepoNode extends PsiDirectoryNode {
    ClosedRepoNode(Project project, @NotNull PsiDirectory value, ViewSettings settings) {
      super(project, value, settings);
    }

    @Override
    protected void updateImpl(@NotNull PresentationData data) {
      super.updateImpl(data);
      data.setIcon(AllIcons.Modules.UnloadedModule);
      data.setForcedTextForeground(UIUtil.getInactiveTextColor());
      data.setTooltip(SyncBundle.message("close.repo.closed.tooltip"));
    }

    @Override
    public Collection<AbstractTreeNode<?>> getChildrenImpl() {
      // List.of() 는 안 된다. 트리 구조가 아래 줄 목록에 contains(null) 을 불러 NullPointerException 이 난다(AbstractTreeStructureBase.getChildElements).
      return new ArrayList<>();
    }

    @Override
    public boolean isAlwaysLeaf() {
      return true;
    }
  }

  // 우리가 닫은 콘텐츠 루트 경로('/' 구분). 「등록 안 된 VCS 루트」 무시 목록에는 사용자가 알림에서 직접 무시한 저장소도 있어 그것과 가른다.
  private static final String CLOSED_KEY = "kinggura.sync.closedRoots";
  // + 모듈 이름: 내린 모듈의 콘텐츠 루트 경로. IDE 를 다시 켜면 IntelliJ 가 잃는 루트를 대신한다(rootsOf).
  private static final String ROOTS_KEY = "kinggura.sync.closedRoot.";
  private static final String MAVEN_PLUGIN = "org.jetbrains.idea.maven";

  /**
   * 닫기 · 열기 뒤 정리. EDT 에서 부른다. 워크스페이스 프로젝트가 아니면 열기 단추로 고른 것(opened)만 되돌린다. 같은 일을 두 번 불러도 결과가 같다.
   * removed: 방금 빠진 모듈의 콘텐츠 루트. 같은 경로를 콘텐츠 루트로 가진 올라온 모듈이 있으면(Maven 이 모듈을 다시 만듦) 건너뛴다.
   *   내린 모듈의 루트면 닫힘: VCS 매핑을 떼고, 등록 안 된 루트 알림 무시 목록 · CLOSED_KEY 에 넣고, 그 폴더 파일 편집기를 저장해 닫고, Maven 에서 그 아래 pom 을 뺀다.
   *   모듈째 지운 것(Remove Module)이면 VCS 매핑만 뗀다.
   * opened: 열기 단추로 고른 루트. 올라온 모듈의 루트 중 CLOSED_KEY 에 있는 것과 함께 열림: 무시 목록 · CLOSED_KEY 에서 빼고 VCS 매핑을 붙이고 Maven pom 을 되돌린다.
   */
  static void sync(Project project, Collection<String> removed, Collection<String> opened) {
    if (project.isDisposed()) return;
    if (CloneIntoProject.workspaceDir(project) == null) {
      if (opened.isEmpty()) return;
      removed = List.of();
    }
    List<String> loaded = new ArrayList<>();
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) loaded.add(root.getPath());
    }
    rememberRoots(project);
    List<String> unloaded = new ArrayList<>();
    for (UnloadedModuleDescription description : ModuleManager.getInstance(project).getUnloadedModuleDescriptions()) {
      unloaded.addAll(rootsOf(project, description));
    }
    PropertiesComponent properties = PropertiesComponent.getInstance(project);
    List<String> ours = new ArrayList<>();
    List<String> saved = properties.getList(CLOSED_KEY);
    if (saved != null) ours.addAll(saved);

    List<String> closing = new ArrayList<>();
    List<String> gone = new ArrayList<>();
    for (String root : removed) {
      if (containsPath(loaded, root)) continue;
      if (containsPath(unloaded, root)) closing.add(root);
      else gone.add(root);
    }
    // 열기 단추로 고른 루트는 올라온 모듈의 루트로 안 보여도 넣는다. IDE 를 다시 켜 루트를 잃은 Maven 모듈은 Maven 을 다시 읽어야 루트가 돌아온다.
    List<String> opening = new ArrayList<>(opened);
    for (String root : loaded) {
      if (containsPath(ours, root) && !containsPath(opening, root)) opening.add(root);
    }

    List<String> detach = new ArrayList<>(closing);
    detach.addAll(gone);
    removeVcsMappings(project, detach);
    VcsConfiguration configuration = VcsConfiguration.getInstance(project);
    if (!closing.isEmpty()) {
      configuration.addIgnoredUnregisteredRoots(closing);
      closeEditors(project, closing);
      if (mavenEnabled()) MavenRepos.setClosed(project, closing, true);
    }
    if (!opening.isEmpty()) {
      configuration.removeFromIgnoredUnregisteredRoots(opening);
      for (String root : opening) CloneIntoProject.addVcsMapping(project, Path.of(root));
      if (mavenEnabled()) MavenRepos.setClosed(project, opening, false);
    }
    for (String root : closing) {
      if (!containsPath(ours, root)) ours.add(root);
    }
    ours.removeIf(root -> containsPath(opening, root) || !containsPath(unloaded, root));
    properties.setList(CLOSED_KEY, ours);
  }

  /** 켤 때 한 번: 이 판 전에 닫아 둔 저장소도 Maven 에서 빼고 편집기를 닫는다. 색인이 끝난 뒤(Maven 목록이 읽힌 뒤) 부른다. */
  static void syncClosed(Project project) {
    List<String> roots = new ArrayList<>();
    for (VirtualFile root : closedRoots(project)) roots.add(root.getPath());
    sync(project, roots, List.of());
  }

  private static boolean containsPath(Collection<String> paths, String path) {
    for (String p : paths) {
      if (FileUtil.pathsEqual(p, path)) return true;
    }
    return false;
  }

  /** Maven 플러그인이 켜져 있으면 true. 꺼져 있으면 MavenRepos 를 읽지 않는다(그 클래스가 Maven 클래스를 쓴다). */
  private static boolean mavenEnabled() {
    PluginId id = PluginId.getId(MAVEN_PLUGIN);
    return PluginManagerCore.getPlugin(id) != null && !PluginManagerCore.isDisabled(id);
  }

  /** 이 폴더들 안 파일의 편집기를 저장하고 닫는다. 이클립스처럼 닫은 저장소의 파일을 계속 고치지 않게 한다(VCS 매핑이 없어 변경이 커밋에서 빠진다). */
  private static void closeEditors(Project project, List<String> roots) {
    FileEditorManager editors = FileEditorManager.getInstance(project);
    FileDocumentManager documents = FileDocumentManager.getInstance();
    for (VirtualFile file : editors.getOpenFiles()) {
      boolean inside = false;
      for (String root : roots) {
        if (FileUtil.isAncestor(root, file.getPath(), false)) inside = true;
      }
      if (!inside) continue;
      Document document = documents.getCachedDocument(file);
      if (document != null) documents.saveDocument(document);
      editors.closeFile(file);
    }
  }

  /**
   * 모듈이 빠지거나 더해질 때 sync 를 부른다(우리 단추 · IntelliJ Load/Unload Modules · Remove Module · Maven 다시 읽기 모두).
   * 빠지기 직전에 콘텐츠 루트를 모아 두고, 한 번에 여러 모듈이 바뀌어도 EDT 에서 한 번만 돈다.
   */
  public static final class ModuleSync implements ModuleListener {
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    @Override
    public void beforeModuleRemoved(@NotNull Project project, @NotNull Module module) {
      for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) removed.add(root.getPath());
      schedule(project);
    }

    @Override
    public void modulesAdded(@NotNull Project project, @NotNull List<? extends Module> modules) {
      schedule(project);
    }

    private void schedule(Project project) {
      if (!scheduled.compareAndSet(false, true)) return;
      ApplicationManager.getApplication().invokeLater(() -> {
        scheduled.set(false);
        List<String> roots = new ArrayList<>(removed);
        removed.removeAll(roots);
        sync(project, roots, List.of());
      }, project.getDisposed());
    }
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
