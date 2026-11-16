package kinggura.sync;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 커밋 창 · 변경 목록 · 프로젝트 트리 우클릭의 「이 PC 에서만 무시」. 아직 Git 에 올리지 않은(새) 파일 · 폴더를 저장소의 .git/info/exclude 에 적어
 * 커밋 목록에서 뺀다. .gitignore 와 형식이 같지만 저장소에 올라가지 않아 이 PC 에서만 적용된다. 이미 추적 중인 파일은 Assume Unchanged 를 쓴다.
 * 「이 PC 에서만 무시 풀기」(Undo)는 고른 파일 · 폴더와 글자 그대로 같은 줄만 지운다. 그 줄이 있는 것만 메뉴에 보인다.
 * 저장소 하나가 실패해도 나머지는 계속하고, 실패한 저장소 이름과 까닭을 알린다. 동기화 창의 같은 메뉴(SyncPanel.excludeLocal)도 add 를 쓴다.
 */
public class LocalExcludeAction extends AnAction implements DumbAware {

  /** 알림 설정(Settings → Notifications)에서 이 알림을 가려 끌 수 있게 붙이는 표시 ID. */
  private static final String NOTIFICATION_ID = "kinggura.sync.localExclude";
  private static final String DOT_GIT = ".git";
  // 연결 작업본 · 하위 모듈의 .git 파일 첫 줄 머리(gitdir: <경로>), 연결 작업본의 공용 .git 폴더를 가리키는 파일 이름.
  private static final String GITDIR_PREFIX = "gitdir:";
  private static final String COMMONDIR = "commondir";
  private static final String EXCLUDE = "info/exclude";

  /** 「이 PC 에서만 무시 풀기」. */
  public static final class Undo extends LocalExcludeAction {
    @Override boolean on() { return false; }
  }

  boolean on() { return true; }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(e.getProject() != null && !targets(e).isEmpty());
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Map<File, List<String>> byRoot = targets(e);
    if (project == null || byRoot.isEmpty()) return;
    boolean on = on();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      int count = 0;
      List<String> failures = new ArrayList<>();
      for (Map.Entry<File, List<String>> entry : byRoot.entrySet()) {
        try {
          count += on ? add(entry.getKey(), entry.getValue()) : remove(entry.getKey(), entry.getValue());
        }
        catch (IOException ex) {
          failures.add(entry.getKey().getName() + ": " + ex.getMessage());
        }
      }
      refresh(byRoot.keySet());
      List<String> lines = new ArrayList<>();
      if (count > 0 || failures.isEmpty()) lines.add(message(count, on));
      lines.addAll(failures);
      String m = String.join("\n", lines);
      ApplicationManager.getApplication().invokeLater(() -> {
        // IntelliJ 는 info/exclude 를 고친 것만으로는 새 파일 목록을 다시 읽지 않을 수 있어 고른 경로를 다시 보게 한다.
        VcsDirtyScopeManager.getInstance(project).filePathsDirty(paths(byRoot), List.of());
        // 동기화 창은 변경 목록 알림으로 다시 비교하지 않으므로 바꾼 저장소만 따로 다시 비교하게 한다.
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(SyncToolWindowFactory.ID);
        Content content = window == null ? null : window.getContentManager().getContent(0);
        if (content != null && content.getComponent() instanceof SyncPanel panel) panel.recompareRoots(byRoot.keySet());
        String title = SyncBundle.message(on ? "action.KingGura.LocalExclude.text" : "action.KingGura.LocalExclude.Undo.text");
        if (failures.isEmpty()) VcsNotifier.getInstance(project).notifyInfo(NOTIFICATION_ID, title, m);
        else VcsNotifier.getInstance(project).notifyError(NOTIFICATION_ID, title, m);
      }, project.getDisposed());
    });
  }

  /**
   * 고친 info/exclude 를 IntelliJ 파일 목록에 바로 알린다. Git 플러그인은 이 파일이 바뀐 것을 보고 저장소 전체를 다시 읽어
   * 프로젝트 트리 색(무시 · 새 파일)을 바꾼다. 알리지 않으면 파일 감시가 알아챌 때까지 옛 색이 남는다. 위치를 못 읽은 저장소는 건너뛴다.
   */
  static void refresh(Collection<File> roots) {
    for (File root : roots) {
      try {
        VfsUtil.markDirtyAndRefresh(true, false, false, excludeFile(root));
      }
      catch (IOException ignored) {
        // add · remove 가 같은 까닭으로 이미 실패를 알렸다.
      }
    }
  }

  /** 무시하기 · 풀기 뒤 알림 문구. 동기화 창 메뉴와 같이 쓴다. */
  static String message(int count, boolean on) {
    return SyncBundle.message(on ? "sync.exclude.done" : "sync.unexclude.done", count);
  }

  /** root 저장소의 info/exclude 끝에 패턴("/경로", 폴더는 "/경로/")을 이스케이프해 더한다. 넘긴 패턴 수를 돌려준다(이미 있던 줄은 다시 넣지 않는다). */
  static int add(File root, List<String> patterns) throws IOException {
    File exclude = excludeFile(root);
    File info = exclude.getParentFile();
    if (!info.isDirectory() && !info.mkdirs()) throw new IOException(SyncBundle.message("sync.exclude.no.dir", info.getPath()));
    SyncPanel.appendIgnoreLines(exclude, patterns);
    return patterns.size();
  }

  /** root 저장소의 info/exclude 에서 패턴(이스케이프한 줄)과 글자 그대로 같은 줄을 지운다. 지운 줄 수를 돌려준다. 파일이 없으면 0. */
  static int remove(File root, List<String> patterns) throws IOException {
    File exclude = excludeFile(root);
    if (!exclude.isFile()) return 0;
    Set<String> drop = new HashSet<>();
    for (String p : patterns) drop.add(SyncPanel.gitignoreEscape(p));
    String text = Files.readString(exclude.toPath(), StandardCharsets.UTF_8);
    String eol = text.contains("\r\n") ? "\r\n" : "\n";
    StringBuilder kept = new StringBuilder();
    int removed = 0;
    for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
      if (drop.contains(line)) removed++;
      else kept.append(line).append(eol);
    }
    if (removed == 0) return 0;
    // split(-1) 은 끝 줄바꿈 뒤에 빈 조각을 하나 남기므로 마지막에 붙인 줄바꿈 하나를 뗀다.
    Files.writeString(exclude.toPath(), kept.substring(0, kept.length() - eol.length()), StandardCharsets.UTF_8);
    return removed;
  }

  /** root 저장소의 info/exclude 의 줄들. 파일이 없거나 못 읽으면 빈 묶음. */
  static Set<String> excludedLines(File root) {
    try {
      File exclude = excludeFile(root);
      if (!exclude.isFile()) return Set.of();
      return new HashSet<>(List.of(Files.readString(exclude.toPath(), StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n")));
    }
    catch (IOException ex) {
      return Set.of();
    }
  }

  /**
   * 저장소의 info/exclude 위치. 보통은 root/.git/info/exclude 다. 하위 모듈 · 연결 작업본은 .git 이 「gitdir: 경로」 한 줄짜리 파일이고,
   * 연결 작업본은 그 폴더의 commondir 이 가리키는 공용 폴더에 info/exclude 가 있다(git rev-parse --git-path info/exclude 와 같은 위치).
   */
  static File excludeFile(File root) throws IOException {
    File gitDir = new File(root, DOT_GIT);
    if (gitDir.isFile()) {
      String line = Files.readString(gitDir.toPath(), StandardCharsets.UTF_8).trim();
      if (!line.startsWith(GITDIR_PREFIX)) throw new IOException(SyncBundle.message("sync.exclude.no.dir", gitDir.getPath()));
      gitDir = resolve(root, line.substring(GITDIR_PREFIX.length()).trim());
      File common = new File(gitDir, COMMONDIR);
      if (common.isFile()) gitDir = resolve(gitDir, Files.readString(common.toPath(), StandardCharsets.UTF_8).trim());
    }
    return new File(gitDir, EXCLUDE);
  }

  private static File resolve(File base, String path) {
    File f = new File(path);
    return f.isAbsolute() ? f : new File(base, path);
  }

  private static List<FilePath> paths(Map<File, List<String>> byRoot) {
    List<FilePath> result = new ArrayList<>();
    for (Map.Entry<File, List<String>> entry : byRoot.entrySet()) {
      for (String pattern : entry.getValue()) result.add(VcsUtil.getFilePath(new File(entry.getKey(), pattern.substring(1)), pattern.endsWith("/")));
    }
    return result;
  }

  /**
   * 고른 파일 · 폴더를 Git 루트별 패턴("/루트 기준 경로", 폴더는 끝에 "/")으로 묶는다.
   * 무시하기는 Git 이 모르는(새) 것만, 풀기는 info/exclude 에 같은 줄이 있는 것만 넣는다. 저장소 폴더 자체 · Git 밖 · SVN 은 건너뛴다.
   */
  private Map<File, List<String>> targets(AnActionEvent e) {
    Map<File, List<String>> result = new LinkedHashMap<>();
    Project project = e.getProject();
    VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    if (project == null || files == null) return result;
    ProjectLevelVcsManager vcs = ProjectLevelVcsManager.getInstance(project);
    ChangeListManager changes = ChangeListManager.getInstance(project);
    Map<File, Set<String>> excluded = new LinkedHashMap<>();
    for (VirtualFile file : files) {
      if (on() && !changes.isUnversioned(file)) continue;
      VcsRoot root = vcs.getVcsRootObjectFor(file);
      if (root == null || root.getVcs() == null || !"Git".equals(root.getVcs().getName()) || root.getPath() == null) continue;
      String rel = VfsUtilCore.getRelativePath(file, root.getPath());
      if (rel == null || rel.isEmpty()) continue;
      String pattern = "/" + rel + (file.isDirectory() ? "/" : "");
      File dir = new File(root.getPath().getPath());
      if (!on() && !excluded.computeIfAbsent(dir, LocalExcludeAction::excludedLines).contains(SyncPanel.gitignoreEscape(pattern))) continue;
      result.computeIfAbsent(dir, k -> new ArrayList<>()).add(pattern);
    }
    return result;
  }
}
