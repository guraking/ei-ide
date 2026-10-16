package kinggura.sync;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.NoDiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 이클립스 Team › Synchronize Workspace 와 같은 창.
 *
 * 프로젝트에 등록된 모든 VCS 루트(Git · SVN)를 대상으로 한다. 루트가 둘 이상이면 저장소별로 묶어 보여 준다.
 * 새로고침하면 Git 은 원격을 받아만 오고(fetch) 현재 브랜치와 그 원격 추적 브랜치(@{u})를 비교하고,
 * SVN 은 svn status -u 로 서버의 최신 상태와 작업본을 비교해
 * 들어올 변경 · 나갈 변경 · 충돌(양쪽에서 같은 파일을 바꿈, 또는 미해결)로 나눠 보여 준다.
 * 나갈 변경에는 아직 커밋하지 않은 작업본 변경도 포함한다(이클립스와 같은 기준).
 * 파일을 고르면 오른쪽에 원격 버전과 내 작업본을 비교한다. 아래 단추는 IDE 의 기존 Git 동작을 그대로 부른다.
 *
 * git · svn 은 PATH 의 실행 파일을 쓴다. SVN 은 CLI 만 쓰므로 svn4idea 플러그인이 없어도 동작한다.
 * shortcut: Git 설정 화면의 git 경로를 따르지 않는다, PATH 에 git 이 없는 PC 를 지원해야 할 때 Git4Idea 의 실행 파일 관리자로 바꾼다.
 */
final class SyncPanel extends JPanel {
  enum Kind { CONFLICT("충돌"), INCOMING("들어올 변경"), OUTGOING("나갈 변경");
    final String label;
    Kind(String label) { this.label = label; }
  }

  enum Vcs { GIT, SVN }

  /** 비교 대상 저장소 하나. Git 은 branch ↔ upstream, SVN 은 branch 가 없고 upstream 자리에 작업본 리비전이 온다. */
  record Repo(File root, Vcs vcs, String branch, String upstream) {
    String label() { return vcs == Vcs.GIT ? branch + " ↔ " + upstream : "svn 리비전 " + upstream; }
    String remoteName() { return vcs == Vcs.GIT ? upstream : "svn HEAD"; }
    @Override public String toString() { return root.getName() + " (" + label() + ")"; }
  }

  record Change(Kind kind, String status, String path, Repo repo) {
    @Override public String toString() { return "[" + status + "] " + path; }
  }

  private static final long CMD_TIMEOUT_SEC = 120;
  private static final int REPO_CHANGE_DELAY_MS = 500;
  private static final Set<String> UNMERGED = Set.of("DD", "AU", "UD", "UA", "DU", "AA", "UU");
  // ProjectLevelVcsManager 가 주는 AbstractVcs.getName() 값. GitVcs.NAME · SvnVcs.VCS_NAME 과 같다.
  private static final String VCS_NAME_GIT = "Git";
  private static final String VCS_NAME_SVN = "svn";
  // svn status --xml 의 item 값 가운데 변경으로 볼 것. 서버 쪽(repos-status)은 작업본에만 있는 값이 올 수 없다.
  private static final Set<String> SVN_CHANGED = Set.of("modified", "added", "deleted", "replaced");
  private static final Set<String> SVN_LOCAL_CHANGED = Set.of("modified", "added", "deleted", "replaced", "unversioned", "missing");
  private static final String SVN_ITEM_CONFLICTED = "conflicted";
  private static final String SVN_ITEM_UNVERSIONED = "unversioned";
  private static final String SVN_ITEM_MISSING = "missing";
  // 새 파일 표시. Git 은 "??", SVN 은 "?"(svn 자체 표기). 되돌리기에서 제외하는 기준이다.
  private static final Set<String> UNTRACKED_STATUS = Set.of("??", "?");
  private static final String SVN_IGNORE_PROP = "svn:ignore";

  private final Project project;
  private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
  private final DiffRequestPanel diffPanel;
  private final JLabel title = new JLabel("Synchronize");
  private final JLabel status = new JLabel(" ");
  private final JButton refreshButton = new JButton("새로고침");
  private final Map<String, JToggleButton> modeButtons = new LinkedHashMap<>();

  private volatile List<Repo> repos = List.of();
  private List<Change> changes = List.of();
  private Kind modeFilter;  // null 이면 전체

  SyncPanel(Project project, Disposable parent) {
    super(new BorderLayout());
    this.project = project;
    this.diffPanel = DiffManager.getInstance().createRequestPanel(project, parent, null);

    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    top.add(title);
    top.add(refreshButton);
    ButtonGroup group = new ButtonGroup();
    addMode(top, group, "전체", null);
    addMode(top, group, "들어올", Kind.INCOMING);
    addMode(top, group, "나갈", Kind.OUTGOING);
    addMode(top, group, "충돌", Kind.CONFLICT);
    modeButtons.get("전체").setSelected(true);
    top.add(status);
    add(top, BorderLayout.NORTH);

    JBSplitter splitter = new JBSplitter(false, 0.35f);
    splitter.setFirstComponent(new JBScrollPane(tree));
    splitter.setSecondComponent(diffPanel.getComponent());
    add(splitter, BorderLayout.CENTER);

    JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    bottom.add(actionButton("Pull", "Git.Pull"));
    bottom.add(actionButton("Commit…", "CheckinProject"));
    bottom.add(actionButton("Push", "Vcs.Push"));
    bottom.add(actionButton("Merge Tool", "Git.ResolveConflicts"));
    add(bottom, BorderLayout.SOUTH);

    tree.setRootVisible(false);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
    tree.addTreeSelectionListener(e -> showSelectedDiff());
    tree.addMouseListener(new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) { showPopup(e); }
      @Override public void mouseReleased(MouseEvent e) { showPopup(e); }
    });
    refreshButton.addActionListener(e -> refresh());

    // 커밋 · Push · Pull · 브랜치 전환으로 Git 상태가 바뀌면 fetch 없이 다시 비교한다.
    // SVN 은 저장소 알림이 없으므로 IDE 의 변경 목록이 갱신될 때(커밋 · 업데이트 뒤) 다시 비교한다.
    // 한 동작에 알림이 여러 번 오므로 마지막 알림 뒤 잠깐 기다렸다가 한 번만 비교한다.
    javax.swing.Timer debounce = new javax.swing.Timer(REPO_CHANGE_DELAY_MS, e -> recompare());
    debounce.setRepeats(false);
    project.getMessageBus().connect(parent).subscribe(GitRepository.GIT_REPO_CHANGE,
      (GitRepositoryChangeListener) repository -> SwingUtilities.invokeLater(debounce::restart));
    project.getMessageBus().connect(parent).subscribe(ChangeListListener.TOPIC, new ChangeListListener() {
      @Override public void changeListUpdateDone() { SwingUtilities.invokeLater(debounce::restart); }
    });
  }

  /** 이클립스 Synchronize 의 파일 우클릭 메뉴: 무시 목록에 넣기 · 변경 되돌리기. 여러 파일(여러 저장소)을 골라도 된다. */
  private void showPopup(MouseEvent e) {
    if (!e.isPopupTrigger()) return;
    TreePath clicked = tree.getPathForLocation(e.getX(), e.getY());
    if (clicked != null && !tree.isPathSelected(clicked)) tree.setSelectionPath(clicked);
    List<Change> selected = selectedChanges();
    if (selected.isEmpty()) return;

    boolean allGit = selected.stream().allMatch(c -> c.repo().vcs() == Vcs.GIT);
    String ignoreName = allGit ? ".gitignore" : "무시 목록";
    JPopupMenu menu = new JPopupMenu();
    JMenuItem ignoreFiles = new JMenuItem(ignoreName + " 에 추가 (" + selected.size() + "개 파일)");
    ignoreFiles.addActionListener(a -> ignore(selected, false));
    menu.add(ignoreFiles);

    // .idea/misc.xml 처럼 폴더째 무시하는 경우가 많아 상위 폴더 단위도 둔다.
    List<String> folders = selected.stream().map(Change::path).filter(p -> p.contains("/"))
      .map(p -> "/" + p.substring(0, p.lastIndexOf('/') + 1)).distinct().toList();
    if (!folders.isEmpty()) {
      JMenuItem ignoreFolders = new JMenuItem("폴더째 " + ignoreName + " 에 추가 (" + String.join(", ", folders) + ")");
      ignoreFolders.addActionListener(a -> ignore(selected, true));
      menu.add(ignoreFolders);
    }

    // 되돌리기는 이미 VCS 가 관리하는 파일의 내 변경만 대상이다. 새 파일을 지우는 일은 하지 않는다.
    List<Change> revertable = selected.stream()
      .filter(c -> c.kind() == Kind.OUTGOING && !UNTRACKED_STATUS.contains(c.status())).toList();
    if (!revertable.isEmpty()) {
      menu.addSeparator();
      JMenuItem revert = new JMenuItem("내 변경 되돌리기 (" + revertable.size() + "개 파일)");
      revert.addActionListener(a -> revert(revertable));
      menu.add(revert);
    }
    menu.show(tree, e.getX(), e.getY());
  }

  private List<Change> selectedChanges() {
    List<Change> result = new ArrayList<>();
    TreePath[] paths = tree.getSelectionPaths();
    if (paths == null) return result;
    for (TreePath path : paths) {
      if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Change c) {
        result.add(c);
      }
    }
    return result;
  }

  /** 저장소별로 나눠 각 저장소의 무시 목록에 넣는다. 패턴은 저장소 기준 "/경로" 또는 폴더면 "/경로/". */
  private void ignore(List<Change> selected, boolean folders) {
    List<String> done = new ArrayList<>();
    for (Map.Entry<Repo, List<Change>> entry : byRepo(selected).entrySet()) {
      List<String> patterns = new ArrayList<>();
      for (Change c : entry.getValue()) {
        String p = c.path();
        if (folders) {
          if (!p.contains("/")) continue;
          p = p.substring(0, p.lastIndexOf('/') + 1);
        }
        if (!patterns.contains("/" + p)) patterns.add("/" + p);
      }
      if (patterns.isEmpty()) continue;
      try {
        if (entry.getKey().vcs() == Vcs.GIT) addToGitignore(entry.getKey().root(), patterns);
        else addToSvnIgnore(entry.getKey().root(), patterns);
        done.addAll(patterns);
      }
      catch (IOException ex) {
        status.setText("무시 목록을 쓰지 못했습니다: " + ex.getMessage());
        return;
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    status.setText("무시 목록에 추가했습니다: " + String.join(", ", done));
    recompare();
  }

  /** 저장소 맨 위 .gitignore 끝에 패턴을 더한다. 이미 있는 줄은 다시 넣지 않는다. */
  static void addToGitignore(File root, List<String> patterns) throws IOException {
    File gitignore = new File(root, ".gitignore");
    String text = gitignore.isFile() ? Files.readString(gitignore.toPath(), StandardCharsets.UTF_8) : "";
    Set<String> existing = new HashSet<>(Arrays.asList(text.replace("\r\n", "\n").split("\n")));
    StringBuilder add = new StringBuilder(text.isEmpty() || text.endsWith("\n") ? "" : "\n");
    for (String p : patterns) if (existing.add(p)) add.append(p).append('\n');
    Files.writeString(gitignore.toPath(), text + add, StandardCharsets.UTF_8);
  }

  /**
   * SVN 은 폴더마다 svn:ignore 속성에 그 폴더 바로 아래 이름을 적는다.
   * "/a/b/c.txt" 는 a/b 폴더에 "c.txt", "/a/b/" 는 a 폴더에 "b" 로 넣는다. 이미 있는 이름은 다시 넣지 않는다.
   * 속성 변경은 작업본에만 남으므로 커밋해야 다른 사람에게도 적용된다(.gitignore 와 같은 조건).
   */
  static void addToSvnIgnore(File root, List<String> patterns) throws IOException, InterruptedException {
    Map<String, List<String>> byDir = new LinkedHashMap<>();
    for (String pattern : patterns) {
      String p = pattern.substring(1);
      if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
      int slash = p.lastIndexOf('/');
      String dir = slash < 0 ? "." : p.substring(0, slash);
      byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(p.substring(slash + 1));
    }
    for (Map.Entry<String, List<String>> entry : byDir.entrySet()) {
      String old;
      try {
        old = run(root, "svn", "propget", SVN_IGNORE_PROP, entry.getKey()).trim();
      }
      catch (IOException noProperty) {
        old = "";
      }
      Set<String> existing = new LinkedHashSet<>(Arrays.asList(old.split("\n")));
      existing.remove("");
      existing.addAll(entry.getValue());
      run(root, "svn", "propset", SVN_IGNORE_PROP, String.join("\n", existing), entry.getKey());
    }
  }

  /** 작업본 · 스테이징의 변경을 마지막 커밋 상태로 되돌린다. 되돌린 내용은 복구할 수 없어 먼저 확인받는다. */
  private void revert(List<Change> targets) {
    String list = targets.stream().map(Change::path).limit(10).reduce((a, b) -> a + "\n" + b).orElse("");
    int answer = Messages.showYesNoDialog(project,
      "아래 파일의 내 변경을 지우고 마지막 커밋 상태로 되돌립니다. 되돌린 변경은 복구할 수 없습니다.\n\n" + list
        + (targets.size() > 10 ? "\n… 외 " + (targets.size() - 10) + "개" : ""),
      "내 변경 되돌리기", Messages.getWarningIcon());
    if (answer != Messages.YES) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String message;
      try {
        for (Map.Entry<Repo, List<Change>> entry : byRepo(targets).entrySet()) {
          Repo repo = entry.getKey();
          List<String> args = new ArrayList<>(repo.vcs() == Vcs.GIT
                                              ? List.of("restore", "--staged", "--worktree", "--")
                                              : List.of("revert", "--"));
          for (Change c : entry.getValue()) args.add(c.path());
          if (repo.vcs() == Vcs.GIT) git(repo.root(), args.toArray(String[]::new));
          else run(repo.root(), "svn", args.toArray(String[]::new));
        }
        message = targets.size() + "개 파일을 되돌렸습니다.";
      }
      catch (IOException ex) {
        message = ex.getMessage();
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      String m = message;
      SwingUtilities.invokeLater(() -> { status.setText(m); recompare(); });
    });
  }

  /** 명령은 저장소마다 따로 실행해야 하므로 고른 파일을 저장소별로 나눈다. 순서는 고른 순서를 지킨다. */
  static Map<Repo, List<Change>> byRepo(List<Change> changes) {
    Map<Repo, List<Change>> result = new LinkedHashMap<>();
    for (Change c : changes) result.computeIfAbsent(c.repo(), k -> new ArrayList<>()).add(c);
    return result;
  }

  /** fetch 없이 지금 상태로만 다시 비교한다(무시 · 되돌리기 · 커밋 직후). SVN 은 서버에 닿지 못하면 작업본만 비교한다. */
  private void recompare() {
    List<Repo> targets = repos;
    if (targets.isEmpty()) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<Change> result = new ArrayList<>();
      String error = null;
      try {
        for (Repo repo : targets) {
          if (repo.vcs() == Vcs.GIT) {
            result.addAll(compare(repo));
            continue;
          }
          try {
            result.addAll(compareSvn(repo, true));
          }
          catch (IOException offline) {
            error = offline.getMessage();
            result.addAll(compareSvn(repo, false));
          }
        }
      }
      catch (IOException ex) {
        error = ex.getMessage();
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      String message = error;
      SwingUtilities.invokeLater(() -> {
        changes = result;
        rebuildTree();
        if (message != null) status.setText(message);
      });
    });
  }

  private void addMode(JPanel top, ButtonGroup group, String text, Kind kind) {
    JToggleButton button = new JToggleButton(text);
    button.addActionListener(e -> { modeFilter = kind; rebuildTree(); });
    group.add(button);
    modeButtons.put(text, button);
    top.add(button);
  }

  private JButton actionButton(String text, String actionId) {
    JButton button = new JButton(text);
    button.addActionListener(e -> {
      AnAction action = ActionManager.getInstance().getAction(actionId);
      if (action == null) {
        status.setText(text + " 동작을 찾을 수 없습니다(" + actionId + ").");
        return;
      }
      ActionUtil.invokeAction(action, this, "KingGuraSynchronize", null, null);
    });
    return button;
  }

  /** 프로젝트에 등록된 VCS 루트(Git · SVN). 하나도 없으면 프로젝트 폴더를 Git 저장소로 본다(등록 전 프로젝트도 쓸 수 있게). */
  private List<Map.Entry<File, Vcs>> vcsRoots() {
    List<Map.Entry<File, Vcs>> result = new ArrayList<>();
    for (VcsRoot root : ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()) {
      if (root.getVcs() == null) continue;
      String name = root.getVcs().getName();
      File dir = new File(root.getPath().getPath());
      if (VCS_NAME_GIT.equals(name)) result.add(Map.entry(dir, Vcs.GIT));
      else if (VCS_NAME_SVN.equals(name)) result.add(Map.entry(dir, Vcs.SVN));
    }
    if (result.isEmpty() && project.getBasePath() != null) result.add(Map.entry(new File(project.getBasePath()), Vcs.GIT));
    return result;
  }

  /**
   * 비교 결과를 다시 만든다. git · svn 은 백그라운드에서 돈다.
   * 먼저 마지막으로 받아 둔 원격 상태로 바로 비교해 보여 주고, 그다음 원격을 받아 와(fetch · svn status -u) 한 번 더 비교한다.
   * 원격 작업은 네트워크에 따라 오래 걸릴 수 있어 그동안 목록을 비워 두지 않는다.
   * 저장소 하나가 실패해도(원격 추적 브랜치 없음 · 서버 연결 불가) 나머지는 계속 비교하고 오류는 상태 줄에 모아 보인다.
   */
  void refresh() {
    List<Map.Entry<File, Vcs>> roots = vcsRoots();
    if (roots.isEmpty()) {
      status.setText("프로젝트 폴더를 알 수 없습니다.");
      return;
    }
    refreshButton.setEnabled(false);
    status.setText("비교 중…");
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<String> errors = new ArrayList<>();
      List<Repo> found = new ArrayList<>();
      List<Change> result = new ArrayList<>();
      try {
        for (Map.Entry<File, Vcs> entry : roots) {
          try {
            Repo repo = entry.getValue() == Vcs.GIT ? gitRepo(entry.getKey()) : svnRepo(entry.getKey());
            found.add(repo);
            result.addAll(repo.vcs() == Vcs.GIT ? compare(repo) : compareSvn(repo, false));
          }
          catch (IOException e) {
            errors.add(e.getMessage());
          }
        }
        repos = List.copyOf(found);
        List<Change> local = List.copyOf(result);
        SwingUtilities.invokeLater(() -> {
          title.setText(found.size() == 1 ? found.get(0).label() : "저장소 " + found.size() + "개");
          changes = local;
          rebuildTree();
          status.setText(summary(local) + " — 원격 변경을 받아 오는 중…");
        });
        List<Change> fetched = new ArrayList<>();
        for (Repo repo : found) {
          try {
            if (repo.vcs() == Vcs.GIT) {
              git(repo.root(), "fetch", "--quiet");
              fetched.addAll(compare(repo));
            }
            else {
              fetched.addAll(compareSvn(repo, true));
            }
          }
          catch (IOException e) {
            errors.add(e.getMessage());
            fetched.addAll(repo.vcs() == Vcs.GIT ? compare(repo) : compareSvn(repo, false));  // 원격 실패 시 지금 비교 결과는 남긴다.
          }
        }
        result = fetched;
      }
      catch (IOException e) {
        errors.add(e.getMessage());
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        errors.add("중단됨");
      }
      List<Change> finalResult = result;
      String message = summary(finalResult) + " — 마지막 Fetch " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
      if (!errors.isEmpty()) message = String.join(" / ", errors);
      String finalMessage = message;
      SwingUtilities.invokeLater(() -> {
        changes = finalResult;
        status.setText(finalMessage);
        refreshButton.setEnabled(true);
        rebuildTree();
      });
    });
  }

  /** Git 루트의 실제 최상위 폴더 · 브랜치 · 원격 추적 브랜치를 알아낸다. 추적 브랜치가 없으면 비교할 수 없으므로 오류. */
  static Repo gitRepo(File dir) throws IOException, InterruptedException {
    File root = new File(git(dir, "rev-parse", "--show-toplevel").trim());
    String branch = git(root, "rev-parse", "--abbrev-ref", "HEAD").trim();
    try {
      return new Repo(root, Vcs.GIT, branch, git(root, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}").trim());
    }
    catch (IOException noUpstream) {
      throw new IOException("'" + branch + "' 브랜치에 원격 추적 브랜치가 없습니다. 먼저 Push 로 원격 브랜치를 연결하세요.");
    }
  }

  /** SVN 작업본의 리비전은 서버 없이 svn info 로 읽는다(표시용). */
  static Repo svnRepo(File root) throws IOException, InterruptedException {
    return new Repo(root, Vcs.SVN, null, run(root, "svn", "info", "--show-item", "revision").trim());
  }

  /** 들어올 · 나갈 · 충돌로 나눈다. 같은 경로가 양쪽에 있으면 충돌로만 보인다. */
  static List<Change> compare(Repo repo) throws IOException, InterruptedException {
    File root = repo.root();
    Map<String, String> incoming = parseNameStatus(git(root, "diff", "--name-status", "HEAD...@{u}"));
    Map<String, String> outgoing = parseNameStatus(git(root, "diff", "--name-status", "@{u}...HEAD"));
    Set<String> unmerged = new TreeSet<>();
    // 새 파일은 폴더 단위로만 받는다(-unormal). 무시되지 않은 큰 폴더 속을 다 훑으면 느려진다.
    // 폴더는 ".idea/" 처럼 끝이 / 인 한 줄로 나오며, 그대로 .gitignore 에 넣을 수 있다.
    for (String line : git(root, "-c", "core.untrackedCache=true", "status", "--porcelain=v1", "-unormal").split("\n")) {
      if (line.length() < 4) continue;
      String xy = line.substring(0, 2);
      String path = line.substring(3);
      int arrow = path.indexOf(" -> ");
      if (arrow >= 0) path = path.substring(arrow + 4);
      path = unquote(path);
      if (UNMERGED.contains(xy)) unmerged.add(path);
      else outgoing.putIfAbsent(path, xy.trim().isEmpty() ? "M" : xy.trim());
    }
    return merge(repo, incoming, outgoing, unmerged);
  }

  /**
   * SVN 은 svn status --xml 한 번으로 작업본(wc-status)과 서버(repos-status, -u 일 때만) 상태를 같이 받는다.
   * remote 가 true 면 서버에 물어보므로 네트워크가 필요하고, 실패하면 IOException 이다.
   */
  static List<Change> compareSvn(Repo repo, boolean remote) throws IOException, InterruptedException {
    String xml = remote ? run(repo.root(), "svn", "status", "-u", "--xml") : run(repo.root(), "svn", "status", "--xml");
    return parseSvnStatus(repo, xml);
  }

  /**
   * svn status --xml 을 들어올 · 나갈 · 충돌로 나눈다.
   * entry 마다 wc-status(작업본) 와, -u 로 받았을 때만 repos-status(서버) 가 있다.
   * 서버 쪽이 바뀌면 들어올 변경, 작업본이 바뀌면 나갈 변경, 둘 다거나 작업본이 conflicted 면 충돌.
   * 경로는 루트 기준 상대 경로로 바꾸고 구분자는 / 로 통일한다(윈도우 svn 은 \ 를 준다).
   */
  static List<Change> parseSvnStatus(Repo repo, String xml) throws IOException {
    Map<String, String> incoming = new LinkedHashMap<>();
    Map<String, String> outgoing = new LinkedHashMap<>();
    Set<String> unmerged = new TreeSet<>();
    NodeList entries;
    try {
      entries = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
        .parse(new InputSource(new StringReader(xml))).getElementsByTagName("entry");
    }
    catch (Exception e) {
      throw new IOException("svn status 결과를 읽지 못했습니다: " + e.getMessage());
    }
    for (int i = 0; i < entries.getLength(); i++) {
      Element entry = (Element)entries.item(i);
      String path = entry.getAttribute("path").replace('\\', '/');
      if (path.startsWith("./")) path = path.substring(2);
      if (path.isEmpty() || path.equals(".")) continue;
      String local = childItem(entry, "wc-status");
      String server = childItem(entry, "repos-status");
      if (SVN_ITEM_CONFLICTED.equals(local)) unmerged.add(path);
      else if (SVN_LOCAL_CHANGED.contains(local)) outgoing.put(path, svnStatusLetter(local));
      if (SVN_CHANGED.contains(server)) incoming.put(path, svnStatusLetter(server));
    }
    return merge(repo, incoming, outgoing, unmerged);
  }

  private static String childItem(Element entry, String tag) {
    NodeList list = entry.getElementsByTagName(tag);
    return list.getLength() == 0 ? "" : ((Element)list.item(0)).getAttribute("item");
  }

  /** svn 명령줄과 같은 한 글자 표시: M · A · D · R, 새 파일 ?, 없어진 파일 !. */
  static String svnStatusLetter(String item) {
    if (SVN_ITEM_UNVERSIONED.equals(item)) return "?";
    if (SVN_ITEM_MISSING.equals(item)) return "!";
    return item.substring(0, 1).toUpperCase(Locale.ROOT);
  }

  private static List<Change> merge(Repo repo, Map<String, String> incoming, Map<String, String> outgoing, Set<String> unmerged) {
    List<Change> result = new ArrayList<>();
    for (String path : new TreeSet<>(incoming.keySet())) {
      if (outgoing.containsKey(path) || unmerged.contains(path)) result.add(new Change(Kind.CONFLICT, "충돌", path, repo));
      else result.add(new Change(Kind.INCOMING, incoming.get(path), path, repo));
    }
    for (String path : unmerged) {
      if (!incoming.containsKey(path)) result.add(new Change(Kind.CONFLICT, "미해결", path, repo));
    }
    for (String path : new TreeSet<>(outgoing.keySet())) {
      if (!incoming.containsKey(path) && !unmerged.contains(path)) result.add(new Change(Kind.OUTGOING, outgoing.get(path), path, repo));
    }
    return result;
  }

  /** "M\tpath", "R100\told\tnew" 형식에서 상태와 최종 경로를 꺼낸다. */
  static Map<String, String> parseNameStatus(String output) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String line : output.split("\n")) {
      String[] parts = line.split("\t");
      if (parts.length < 2) continue;
      result.put(unquote(parts[parts.length - 1]), parts[0].substring(0, 1));
    }
    return result;
  }

  private static String unquote(String path) {
    return path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"") ? path.substring(1, path.length() - 1) : path;
  }

  static String summary(List<Change> changes) {
    Map<Kind, Integer> count = new EnumMap<>(Kind.class);
    for (Change c : changes) count.merge(c.kind(), 1, Integer::sum);
    if (changes.isEmpty()) return "원격과 같습니다";
    return "들어올 " + count.getOrDefault(Kind.INCOMING, 0) + " · 나갈 " + count.getOrDefault(Kind.OUTGOING, 0)
           + " · 충돌 " + count.getOrDefault(Kind.CONFLICT, 0);
  }

  /** 저장소가 하나면 종류별 묶음만, 둘 이상이면 저장소 → 종류별 묶음으로 보인다. */
  private void rebuildTree() {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    if (repos.size() <= 1) {
      addKindGroups(root, changes);
    }
    else {
      for (Repo repo : repos) {
        DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode(repo);
        addKindGroups(repoNode, changes.stream().filter(c -> c.repo().equals(repo)).toList());
        root.add(repoNode);
      }
    }
    tree.setModel(new DefaultTreeModel(root));
    for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    // 목록을 새로 만들면 고른 파일이 사라지므로, 지난 비교 내용이 남지 않게 비교 화면도 비운다.
    diffPanel.setRequest(NoDiffRequest.INSTANCE);
  }

  private void addKindGroups(DefaultMutableTreeNode parent, List<Change> source) {
    for (Kind kind : Kind.values()) {
      if (modeFilter != null && modeFilter != kind) continue;
      List<Change> ofKind = source.stream().filter(c -> c.kind() == kind).toList();
      if (ofKind.isEmpty()) continue;
      DefaultMutableTreeNode group = new DefaultMutableTreeNode(kind.label + " " + ofKind.size());
      for (Change c : ofKind) group.add(new DefaultMutableTreeNode(c));
      parent.add(group);
    }
  }

  /** 고른 파일의 원격 버전(Git @{u} · SVN HEAD)과 내 작업본을 비교해 보여 준다. 한쪽에 없으면 빈 내용으로 비교한다. */
  private void showSelectedDiff() {
    if (!(tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node)
        || !(node.getUserObject() instanceof Change change)) {
      return;
    }
    Repo repo = change.repo();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String remote;
      try {
        remote = repo.vcs() == Vcs.GIT
                 ? git(repo.root(), "show", repo.upstream() + ":" + change.path())
                 : run(repo.root(), "svn", "cat", "-r", "HEAD", change.path());
      }
      catch (IOException e) {
        remote = "";  // 원격에 없는 파일(새로 추가했거나 원격에서 지운 파일)
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      File local = new File(repo.root(), change.path());
      String mine;
      try {
        mine = local.isFile() ? new String(Files.readAllBytes(local.toPath()), StandardCharsets.UTF_8) : "";
      }
      catch (IOException e) {
        mine = "";
      }
      String remoteText = remote;
      String mineText = mine;
      SwingUtilities.invokeLater(() -> {
        FileType type = FileTypeManager.getInstance().getFileTypeByFileName(local.getName());
        DiffContentFactory factory = DiffContentFactory.getInstance();
        diffPanel.setRequest(new SimpleDiffRequest(change.path(),
                                                   factory.create(project, remoteText, type),
                                                   factory.create(project, mineText, type),
                                                   "원격 (" + repo.remoteName() + ")", "내 작업본"));
      });
    });
  }

  /** git 을 실행한다. 경로는 항상 따옴표 없이 UTF-8 그대로 받는다. */
  static String git(File dir, String... args) throws IOException, InterruptedException {
    List<String> full = new ArrayList<>(List.of("-c", "core.quotepath=false"));
    full.addAll(List.of(args));
    return run(dir, "git", full.toArray(String[]::new));
  }

  /** 명령을 실행해 표준 출력을 돌려준다. 0 이 아닌 종료 코드 · 시간 초과는 오류 메시지를 담은 IOException 으로 바꾼다. */
  static String run(File dir, String exe, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of(exe));
    command.addAll(List.of(args));
    String label = exe + " " + verb(args);
    File errFile = File.createTempFile("kinggura-sync", ".err");
    try {
      // 표준 오류는 파일로 받아 출력 버퍼가 차서 멈추는 일을 막는다.
      Process process = new ProcessBuilder(command).directory(dir).redirectError(errFile).start();
      byte[] out = process.getInputStream().readAllBytes();
      if (!process.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException(label + " 이(가) " + CMD_TIMEOUT_SEC + "초 안에 끝나지 않았습니다.");
      }
      if (process.exitValue() != 0) {
        String err = Files.readString(errFile.toPath(), StandardCharsets.UTF_8).trim();
        throw new IOException(label + " 실패: " + (err.isEmpty() ? "종료 코드 " + process.exitValue() : err));
      }
      return new String(out, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
    finally {
      Files.deleteIfExists(errFile.toPath());
    }
  }

  /** 오류 메시지에 쓸 하위 명령 이름. git 의 "-c 키=값" 앞 옵션은 건너뛴다. */
  private static String verb(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-c")) i++;
      else return args[i];
    }
    return "";
  }
}
