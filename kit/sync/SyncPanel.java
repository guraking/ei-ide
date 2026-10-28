package kinggura.sync;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.MessageDiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.vcsUtil.VcsUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.RowIcon;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.tree.TreeUtil;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 이클립스 Team › Synchronize Workspace 와 같은 창.
 *
 * 프로젝트에 등록된 모든 VCS 루트(Git · SVN)를 대상으로 한다. 루트가 둘 이상이면 저장소별로 묶어 보여 준다.
 * 새로고침하면 Git 은 원격을 받아만 오고(fetch) 현재 브랜치와 그 원격 추적 브랜치(@{u})를 비교하고,
 * SVN 은 svn status -u 로 서버의 최신 상태와 작업본을 비교해
 * 들어올 변경 · 나갈 변경 · 충돌(양쪽에서 같은 파일을 바꿈, 또는 미해결)로 나눠 보여 준다.
 * 나갈 변경에는 아직 커밋하지 않은 작업본 변경도 포함한다(이클립스와 같은 기준).
 * 파일을 고르면 오른쪽에 원격 버전과 내 작업본을 비교한다. 아래 단추는 IDE 의 기존 Git 동작을 그대로 부른다.
 * git · svn 명령은 모두 작업 스레드에서 돈다.
 *
 * git · svn 은 PATH 의 실행 파일을 쓴다. SVN 은 CLI 만 쓰므로 svn4idea 플러그인이 없어도 동작한다.
 * shortcut: Git 설정 화면의 git 경로를 따르지 않는다, PATH 에 git 이 없는 PC 를 지원해야 할 때 Git4Idea 의 실행 파일 관리자로 바꾼다.
 */
final class SyncPanel extends SimpleToolWindowPanel {
  /** ASSUMED 는 변경이 아니라 skip-worktree(예전 판은 assume-unchanged)로 모른 척 중인 Git 파일이다. 「모두 보기」에서만 맨 아래 묶음으로 보인다. */
  enum Kind { CONFLICT, INCOMING, OUTGOING, ASSUMED;
    /** 트리 묶음 줄에 보일 이름. */
    String label() {
      return switch (this) {
        case CONFLICT -> SyncBundle.message("sync.kind.conflict");
        case INCOMING -> SyncBundle.message("sync.kind.incoming");
        case OUTGOING -> SyncBundle.message("sync.kind.outgoing");
        case ASSUMED -> SyncBundle.message("sync.kind.assumed");
      };
    }
  }

  enum Vcs { GIT, SVN }

  /** 비교 대상 저장소 하나. Git 은 branch ↔ upstream, SVN 은 branch 가 없고 upstream 자리에 작업본 리비전이 온다. */
  record Repo(File root, Vcs vcs, String branch, String upstream) {
    String label() { return vcs == Vcs.GIT ? branch + " ↔ " + upstream : SyncBundle.message("sync.repo.svn.revision", upstream); }
    String remoteName() { return vcs == Vcs.GIT ? upstream : "svn HEAD"; }
    @Override public String toString() { return root.getName() + " (" + label() + ")"; }
  }

  record Change(Kind kind, String status, String path, Repo repo) {
    @Override public String toString() { return "[" + status + "] " + path; }
  }

  /** 트리 묶음 줄: 종류와 그 종류 파일 수. */
  record Group(Kind kind, int count) {
    @Override public String toString() { return kind.label() + " " + count; }
  }

  /** 파일 줄 앞 상태 글자(M · A · ?? 등). 파일 형식 아이콘과 RowIcon 으로 묶어 글자 · 아이콘 · 이름 순으로 보인다. 폭은 width 로 고정해 아이콘을 줄 맞춘다. */
  static final class StatusIcon implements Icon {
    private final String text;
    private final Color color;
    private final Font font;
    private final int width;
    private final int height;

    StatusIcon(String text, Color color, Font font, int width, int height) {
      this.text = text;
      this.color = color;
      this.font = font;
      this.width = width;
      this.height = height;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g2.setFont(font);
      g2.setColor(color);
      FontMetrics fm = g2.getFontMetrics();
      g2.drawString(text, x, y + (height + fm.getAscent() - fm.getDescent()) / 2);
      g2.dispose();
    }

    @Override public int getIconWidth() { return width; }
    @Override public int getIconHeight() { return height; }
  }

  /** 「내 변경 되돌리기」 메뉴에서 파일 하나에 할 일. UNSTAGE 는 파일 내용은 두고 스테이징만 푼다. */
  enum Undo { REVERT, UNSTAGE, NONE }

  private static final long CMD_TIMEOUT_SEC = 120;
  // svn update 는 받을 양에 따라 오래 걸린다. 중간에 끊으면 작업본이 잠겨(E155037) svn cleanup 이 필요하므로 따로 길게 둔다.
  private static final long SVN_UPDATE_TIMEOUT_SEC = TimeUnit.MINUTES.toSeconds(30);
  // 서버에 닿는 git 명령(fetch · ls-remote · push)과 작업본을 바꾸는 merge · checkout 의 시간 제한.
  // 도중에 끊으면 .lock 파일이나 반쯤 바뀐 작업본이 남으므로 CMD_TIMEOUT_SEC 보다 길게 둔다.
  static final long GIT_LONG_TIMEOUT_SEC = TimeUnit.MINUTES.toSeconds(30);
  private static final int PATH_LIST_MAX = 10;
  private static final int REPO_CHANGE_DELAY_MS = 500;
  // 동시에 비교하는 저장소 수. 저장소마다 git 을 셋 넷 띄우므로, 저장소가 많은 작업 공간에서 git 수십 개가 한꺼번에 돌아 IDE 가 굼뜨지 않게 한다.
  private static final int MAX_PARALLEL_REPOS = 3;
  private static final Semaphore REPO_SLOTS = new Semaphore(MAX_PARALLEL_REPOS);
  // 우리가 띄운 git · svn 명령마다 걸린 시간을 적는다. Help › Diagnostic Tools › Debug Log Settings 에 「#kinggura.sync.time」을 넣으면 idea.log 에 보인다.
  private static final Logger TIME_LOG = Logger.getInstance("#kinggura.sync.time");
  private static final String TOOLBAR_PLACE = "KingGuraSynchronizeToolbar";
  // 기본(강조색) 단추 위에 놓는 흰 받기 아이콘. 테마마다 기본 단추 바탕이 강조색이고 글자가 흰색이다.
  private static final String PULL_SELECTED_ICON = "/icons/ei/pullSelected.svg";
  // 도구줄 글 사이 · 아래 단추 사이 간격(JBUI.scale 전).
  private static final int GAP = 8;
  // 파일 줄 앞 상태 글자 칸의 최소 폭(JBUI.scale 전). 「M」 · 「??」 · 「충돌」 뒤 파일 아이콘이 한 줄로 서게 한다.
  private static final int STATUS_COL_W = 24;
  // 위쪽 요약에서 종류별 숫자 앞에 붙는 표시.
  private static final String MARK_INCOMING = "↓";
  private static final String MARK_OUTGOING = "↑";
  private static final String MARK_CONFLICT = "⚠";
  private static final String SUMMARY_SEP = " · ";
  private static final Set<String> UNMERGED = Set.of("DD", "AU", "UD", "UA", "DU", "AA", "UU");
  // ProjectLevelVcsManager 가 주는 AbstractVcs.getName() 값. GitVcs.NAME · SvnVcs.VCS_NAME 과 같다.
  private static final String VCS_NAME_GIT = "Git";
  private static final String GIT = "git";
  // 자격 증명이 없어 git 이 실패할 때 내는 문구(git 2.x, 영어 · 기본 메시지). 이때 오류에 저장 방법 안내를 붙인다.
  private static final List<String> GIT_AUTH_ERRORS = List.of("could not read Username", "could not read Password", "Authentication failed");
  static final String VCS_NAME_SVN = "svn";
  private static final String ACTION_PULL = "Git.Pull";
  private static final String ACTION_REVERSE_MERGE = "KingGura.ReverseMerge";
  private static final String ACTION_PUSH = "Vcs.Push";
  private static final String ACTION_COMMIT = "CheckinProject";
  // 고른 파일만 체크된 채로 커밋 창을 연다(CommonCheckinFilesAction, VcsDataKeys.FILE_PATHS 를 읽는다).
  private static final String ACTION_COMMIT_FILES = "CheckinFiles";
  // IDE 의 「Update Project」(Ctrl+T). 프로젝트의 모든 VCS 루트를 받는다(Git 은 받기 방식을 묻고, SVN 은 svn update).
  private static final String ACTION_UPDATE_ALL = "Vcs.UpdateProject";
  // 설치본에 같이 넣은 VisualSVN 명령줄 도구(apply_defaults 19 의 SVN 기본 경로와 같은 곳).
  private static final String BUNDLED_SVN = "plugins/kinggura-sync/svn/bin/svn.exe";
  // svn status --xml 의 item 값 가운데 변경으로 볼 것. 서버 쪽(repos-status)은 작업본에만 있는 값이 올 수 없다.
  private static final Set<String> SVN_CHANGED = Set.of("modified", "added", "deleted", "replaced");
  private static final Set<String> SVN_LOCAL_CHANGED = Set.of("modified", "added", "deleted", "replaced", "unversioned", "missing");
  private static final String SVN_ITEM_CONFLICTED = "conflicted";
  private static final String SVN_ITEM_UNVERSIONED = "unversioned";
  private static final String SVN_ITEM_MISSING = "missing";
  // 새 파일 표시. Git 은 "??", SVN 은 "?"(svn 자체 표기). 되돌리기에서 제외하는 기준이다.
  private static final Set<String> UNTRACKED_STATUS = Set.of("??", "?");
  // Git 상태 글자 가운데 경로가 HEAD 에 없을 수 있는 것: 추가 · 이름 바꿈 · 복사. restore --worktree 를 하면 파일이 지워진다.
  private static final String GIT_NEW_PATH_LETTERS = "ARC";
  private static final String SVN_IGNORE_PROP = "svn:ignore";
  // svn propget 이 속성이 없을 때 내는 경고 코드(svn 1.9 부터 종료 코드 1 과 함께 나온다).
  private static final String SVN_PROPERTY_NOT_FOUND = "W200017";
  // git ls-files -v 의 첫 글자: skip-worktree 는 'S', assume-unchanged 는 소문자(h · s 등).
  private static final char SKIP_WORKTREE_TAG = 'S';
  // svn 은 오류 문구를 운영체제 문자셋(한국어 윈도우는 CP949)으로 낸다. Java 18 부터 기본 문자셋은 UTF-8 이라 native.encoding 을 쓴다.
  private static final Charset NATIVE_CHARSET = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));

  private final Project project;
  private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
  private final DiffRequestPanel diffPanel;
  private final SimpleColoredComponent title = new SimpleColoredComponent();
  private final SimpleColoredComponent status = new SimpleColoredComponent();
  // 목록 우클릭 메뉴가 아래 단추와 같은 동작을 하도록 단추를 눌러 부른다.
  private JButton pullButton;
  private JButton pushButton;
  private JButton resolveButton;
  // 새로고침이 도는 동안 참(EDT 에서만). 도구줄의 새로고침 동작은 이 값이 참이면 꺼진다.
  private boolean busy;
  // 새로고침이 도는 동안 VCS 매핑이 바뀌었다(EDT 에서만). 끝나면 한 번만 다시 새로고침한다(fetch 가 겹치지 않게).
  private boolean refreshAgain;
  // 위쪽 요약 끝의 회색 글(「원격 확인 HH:mm」 등, EDT 에서만). fetch 없는 다시 비교 뒤에도 같은 글로 요약을 다시 그린다.
  private String summaryTail = "";

  private volatile List<Repo> repos = List.of();
  private List<Change> changes = List.of();
  // 비교를 시작할 때마다 하나 올린다(EDT 에서만). 늦게 끝난 옛 비교가 새 결과를 덮지 않게 끝날 때 이 값과 맞춰 본다.
  private int compareSeq;
  private Kind modeFilter;  // null 이면 전체
  // 알림을 모아 두었다가 다시 비교할 저장소(EDT 에서만). Git 알림이 온 루트, 변경 목록에서 경로가 달라진 곳(Git · SVN).
  private final Set<File> pendingRoots = new HashSet<>();
  private final Set<String> pendingPaths = new HashSet<>();
  // 알림 때 브랜치가 목록의 것과 달라진 Git 루트(EDT 에서만). 추적 브랜치도 바뀌므로 저장소 정보부터 다시 읽는다.
  private final Set<File> switchedRoots = new HashSet<>();
  // Git 루트별 마지막 알림 때의 브랜치(EDT 에서만).
  private final Map<File, String> lastBranch = new HashMap<>();
  // 이 창이 fetch 중인 Git 루트. 그동안 오는 저장소 알림은 fetch 가 낸 것이라 다시 비교하지 않는다.
  private final Set<File> fetching = ConcurrentHashMap.newKeySet();
  // SVN 루트별 마지막 서버 확인(svn status -u)의 들어올 변경(경로 → 상태 글자). 작업본만 다시 비교할 때 들어올 변경을 이것으로 가른다.
  // 서버 확인이 실패하면 지워 그때 목록(작업본만)과 맞춘다.
  private final Map<File, Map<String, String>> svnIncoming = new ConcurrentHashMap<>();
  // 창이 숨어 있는지(stateChanged 에서 갱신). 숨어 있는 동안 온 알림은 다시 비교하지 않고 stale 만 세워 두었다가 창이 보일 때 한 번 비교한다.
  private volatile boolean hidden;
  private volatile boolean stale;
  // 지난 변경 목록 알림 때 IDE 가 알던 작업본 변경 · 새 파일 경로. 다음 알림과 비교해 달라진 경로만 본다(알림 스레드에서만).
  private volatile Set<String> lastLocalPaths = Set.of();
  // Git 루트별 역머지 기준 브랜치와의 차이(「develop 보다 3개 뒤 · 내 파일 2개 겹침」). 기준 브랜치를 고른 적 없거나 같으면 없다.
  private final Map<File, ReverseNote> reverseNotes = new ConcurrentHashMap<>();

  /** 위쪽 줄 · 저장소 줄 끝에 붙는 역머지 안내. warn 이면 내 파일과 겹쳐 주황으로 보인다. */
  record ReverseNote(String text, boolean warn) {}

  SyncPanel(Project project, Disposable parent) {
    super(true, true);
    this.project = project;
    this.diffPanel = DiffManager.getInstance().createRequestPanel(project, parent, null);
    diffPanel.setRequest(new MessageDiffRequest(SyncBundle.message("sync.diff.empty")));

    // IntelliJ 기본 창처럼 위쪽은 아이콘 도구줄(새로고침 · 보기 거르기), 그 오른쪽에 저장소 이름과 상태 글.
    // 창 머리 아래 선은 IntelliJ 가 그으므로 스크롤 상자는 테두리 없이, 칸막이는 1픽셀로 둔다.
    DefaultActionGroup actions = new DefaultActionGroup();
    actions.add(new RefreshAction("sync.refresh.all", AllIcons.Actions.Refresh, this::refresh));
    actions.add(new RefreshAction("sync.refresh.selected", AllIcons.Actions.ForceRefresh, this::refreshSelected));
    actions.addSeparator();
    actions.add(new ModeAction("sync.mode.all", AllIcons.Actions.ListChanges, null));
    actions.add(new ModeAction("sync.mode.incoming", AllIcons.Actions.Download, Kind.INCOMING));
    actions.add(new ModeAction("sync.mode.outgoing", AllIcons.Actions.Upload, Kind.OUTGOING));
    actions.add(new ModeAction("sync.mode.conflict", AllIcons.Vcs.Merge, Kind.CONFLICT));
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true);
    toolbar.setTargetComponent(this);
    title.setOpaque(false);
    status.setOpaque(false);
    title.append(SyncBundle.message("sync.title"));
    // 위쪽 줄의 역머지 안내(「develop 보다 3개 뒤」)를 누르면 Reverse Merge 창을 그 저장소로 연다.
    title.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (repos.size() != 1 || !reverseNotes.containsKey(repos.get(0).root())) return;
        VirtualFile root = LocalFileSystem.getInstance().findFileByIoFile(repos.get(0).root());
        if (root == null) return;
        DataContext context = SimpleDataContext.builder().setParent(DataManager.getInstance().getDataContext(SyncPanel.this))
          .add(CommonDataKeys.VIRTUAL_FILE, root).build();
        runAction(SyncBundle.message("rmerge.title"), ACTION_REVERSE_MERGE, context);
      }
    });
    JPanel labels = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0));
    labels.add(title);
    labels.add(status);
    // FlowLayout 은 줄을 위에 붙이므로, 도구줄 높이에 맞춰 세로 가운데로 놓는다.
    JPanel middle = new JPanel(new GridBagLayout());
    GridBagConstraints left = new GridBagConstraints();
    left.weightx = 1;
    left.fill = GridBagConstraints.HORIZONTAL;
    middle.add(labels, left);
    JPanel top = new JPanel(new BorderLayout());
    top.add(toolbar.getComponent(), BorderLayout.WEST);
    top.add(middle, BorderLayout.CENTER);
    setToolbar(top);

    OnePixelSplitter splitter = new OnePixelSplitter(false, 0.35f);
    splitter.setFirstComponent(ScrollPaneFactory.createScrollPane(tree, true));
    splitter.setSecondComponent(diffPanel.getComponent());

    // 아래 단추 줄: 위쪽 1픽셀 선으로 목록과 가르고, 단추 사이 · 가장자리는 IntelliJ 기본 간격.
    JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP / 2)));
    bottom.setBorder(JBUI.Borders.customLineTop(JBColor.border()));
    // 받기(Pull)는 이 창의 주된 동작이라 기본 단추 모양으로 둔다.
    JButton pull = actionButton(SyncBundle.message("sync.button.pull"), IconLoader.getIcon(PULL_SELECTED_ICON, SyncPanel.class), ACTION_PULL, true);
    pullButton = pull;
    pull.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, Boolean.TRUE);
    bottom.add(actionButton(SyncBundle.message("sync.button.update.all"), AllIcons.Vcs.ShelveSilent, ACTION_UPDATE_ALL, false));
    bottom.add(pull);
    // 올리기: 고른 저장소(없으면 전부)로 IntelliJ Push 창. 커밋하고 올리기: 커밋 창을 연다(그 창의 Commit and Push 로 한 번에).
    pushButton = actionButton(SyncBundle.message("sync.button.push"), AllIcons.Vcs.Push, ACTION_PUSH, true);
    bottom.add(pushButton);
    bottom.add(actionButton(SyncBundle.message("sync.button.commit.push"), AllIcons.Actions.Commit, ACTION_COMMIT, false));
    resolveButton = actionButton(SyncBundle.message("sync.button.resolve"), AllIcons.Vcs.Merge, "Git.ResolveConflicts", false);
    bottom.add(resolveButton);
    // 고른 저장소(없으면 지금 저장소)로 Merge Branches 창을 연다.
    bottom.add(actionButton(SyncBundle.message("sync.button.reverse"), AllIcons.Vcs.Branch, ACTION_REVERSE_MERGE, true));
    JPanel content = new JPanel(new BorderLayout());
    content.add(splitter, BorderLayout.CENTER);
    content.add(bottom, BorderLayout.SOUTH);
    setContent(content);

    tree.setRootVisible(false);
    tree.getEmptyText().setText(SyncBundle.message("sync.empty"))
      .appendSecondaryText(SyncBundle.message("sync.empty.link"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, e -> { if (!busy) refresh(); });
    // 묶음 줄과 파일 줄은 종류별로 IDE 의 파일 상태 색(테마를 따름)으로 그린다. 충돌 빨강 · 들어올 파랑(수정됨) · 나갈 보라(병합됨).
    // 묶음 줄: 종류 아이콘 · 굵은 이름 · 회색 개수. 파일 줄: 회색 상태 글자 · 파일 형식 아이콘 · 이름 · 회색 상위 폴더, 풍선 도움말은 전체 경로.
    tree.setCellRenderer(new ColoredTreeCellRenderer() {
      @Override
      public void customizeCellRenderer(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
        FileStatus fileStatus = statusColorOf(kindOf(node));
        Color color = fileStatus == null ? null : fileStatus.getColor();
        Object item = node.getUserObject();
        setToolTipText(null);
        if (item instanceof Group group) {
          setIcon(iconOf(group.kind()));
          append(group.kind().label(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color));
          append(" " + group.count(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        else if (item instanceof Change change) {
          String path = change.path();
          int slash = path.lastIndexOf('/');
          String name = path.substring(slash + 1);
          Icon fileIcon = FileTypeManager.getInstance().getFileTypeByFileName(name).getIcon();
          if (fileIcon == null) fileIcon = AllIcons.FileTypes.Unknown;
          Font small = JBUI.Fonts.smallFont();
          int statusWidth = Math.max(JBUI.scale(STATUS_COL_W), getFontMetrics(small).stringWidth(change.status()));
          setIcon(new RowIcon(new StatusIcon(change.status(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES.getFgColor(), small,
                                             statusWidth, fileIcon.getIconHeight()), fileIcon));
          append(name, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color));
          if (slash > 0) append("  " + path.substring(0, slash), SimpleTextAttributes.GRAYED_ATTRIBUTES);
          setToolTipText(path);
        }
        else {
          append(Objects.toString(item, ""));
          if (item instanceof Repo repo) appendReverseNote(this, repo);
        }
      }
    });
    ToolTipManager.sharedInstance().registerComponent(tree);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
    tree.addTreeSelectionListener(e -> showSelectedDiff());
    tree.addMouseListener(new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) { showPopup(e); }
      @Override public void mouseReleased(MouseEvent e) { showPopup(e); }
    });

    // 커밋 · Push · Pull · 브랜치 전환으로 Git 상태가 바뀌면 그 저장소만 fetch 없이 다시 비교한다.
    // 변경 목록이 갱신되면(편집 · 저장마다 온다) 변경 · 새 파일 경로가 달라진 저장소(Git · SVN)만 다시 비교한다.
    // 이 알림으로 하는 비교는 서버에 묻지 않는다. SVN 의 들어올 변경은 마지막 서버 확인 결과를 쓴다.
    // 한 동작에 알림이 여러 번 오므로 마지막 알림 뒤 잠깐 기다렸다가 한 번만 비교한다.
    javax.swing.Timer debounce = new javax.swing.Timer(REPO_CHANGE_DELAY_MS, e -> recomparePending());
    debounce.setRepeats(false);
    project.getMessageBus().connect(parent).subscribe(GitRepository.GIT_REPO_CHANGE,
      (GitRepositoryChangeListener) repository -> {
        File root = new File(repository.getRoot().getPath());
        if (fetching.contains(root)) return;
        // 브랜치가 없는(detached) HEAD 는 rev-parse --abbrev-ref 처럼 "HEAD" 로 본다.
        String branch = Objects.requireNonNullElse(repository.getCurrentBranchName(), "HEAD");
        SwingUtilities.invokeLater(() -> {
          // 같은 브랜치로 또 온 알림은 다시 읽지 않는다. 추적 브랜치가 없어 다시 읽기가 실패한 브랜치에서 알림마다 fetch 하지 않게 한다.
          boolean moved = !branch.equals(lastBranch.put(root, branch));
          for (Repo repo : repos) {
            if (moved && repo.vcs() == Vcs.GIT && repo.root().equals(root) && !branch.equals(repo.branch())) switchedRoots.add(root);
          }
          pendingRoots.add(root);
          debounce.restart();
        });
      });
    project.getMessageBus().connect(parent).subscribe(ChangeListListener.TOPIC, new ChangeListListener() {
      @Override public void changeListUpdateDone() {
        // 창이 숨어 있으면 경로를 모으지 않는다. 창이 보일 때 모든 저장소를 다시 비교한다.
        if (hidden) {
          stale = true;
          return;
        }
        Set<String> now = localChangePaths();
        Set<String> moved = new HashSet<>(now);
        moved.removeAll(lastLocalPaths);
        for (String path : lastLocalPaths) {
          if (!now.contains(path)) moved.add(path);
        }
        lastLocalPaths = now;
        SwingUtilities.invokeLater(() -> { pendingPaths.addAll(moved); debounce.restart(); });
      }
    });
    project.getMessageBus().connect(parent).subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
      @Override public void stateChanged(@org.jetbrains.annotations.NotNull ToolWindowManager manager) {
        ToolWindow window = manager.getToolWindow(SyncToolWindowFactory.ID);
        hidden = window != null && !window.isVisible();
        if (!hidden && stale) {
          stale = false;
          recompare(repos, false, true);
        }
      }
    });
    // IDE 를 켠 직후에는 VCS 매핑이 잡히기 전에 창이 먼저 비교를 시작해 프로젝트 폴더로 대신 비교할 수 있다
    // (워크스페이스 폴더는 저장소가 아니라 rev-parse 가 실패한다). 매핑이 잡히거나 바뀌면 다시 비교한다.
    project.getMessageBus().connect(parent).subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
      (com.intellij.openapi.vcs.VcsMappingListener) () -> SwingUtilities.invokeLater(() -> {
        if (busy) refreshAgain = true;
        else refresh();
      }));
  }

  /**
   * 이클립스 Synchronize 의 우클릭 메뉴. 여러 파일(여러 저장소)을 골라도 된다.
   * 파일 줄: 받기 · 커밋 · 올리기 · 충돌 해결, 무시 목록에 넣기 · 변경 되돌리기.
   * 묶음 줄(들어올 · 나갈 · 충돌): 그 묶음의 파일 전부로 받기 · 커밋 · 올리기 · 충돌 해결 · 변경 되돌리기.
   */
  private void showPopup(MouseEvent e) {
    if (!e.isPopupTrigger()) return;
    TreePath clicked = tree.getPathForLocation(e.getX(), e.getY());
    if (clicked != null && !tree.isPathSelected(clicked)) tree.setSelectionPath(clicked);
    List<Change> all = selectedChanges();
    List<Change> grouped = selectedGroupChanges();
    if (all.isEmpty() && grouped.isEmpty()) return;
    JPopupMenu menu = new JPopupMenu();
    addSyncItems(menu, grouped.isEmpty() ? all : grouped);
    // 묶음 줄만 골랐으면 무시 목록 · 모른 척하기 없이 되돌리기만 둔다(수십 개 파일을 한 번에 무시하지 않게).
    if (all.isEmpty()) {
      List<Change> revertable = grouped.stream().filter(c -> undoOf(c) == Undo.REVERT).toList();
      if (!revertable.isEmpty()) {
        int skipped = (int) grouped.stream().filter(c -> undoOf(c) == Undo.UNSTAGE).count();
        if (menu.getComponentCount() > 0) menu.addSeparator();
        JMenuItem revert = new JMenuItem(SyncBundle.message("sync.menu.revert", revertable.size()));
        revert.addActionListener(a -> revert(revertable, skipped));
        menu.add(revert);
      }
      if (menu.getComponentCount() > 0) menu.show(tree, e.getX(), e.getY());
      return;
    }
    if (menu.getComponentCount() > 0) menu.addSeparator();
    // 모른 척 중인 파일은 변경이 아니므로 무시 · 되돌리기 대신 풀기만 둔다.
    List<Change> assumed = all.stream().filter(c -> c.kind() == Kind.ASSUMED).toList();
    if (!assumed.isEmpty()) {
      JMenuItem unassume = new JMenuItem(SyncBundle.message("sync.menu.unassume", assumed.size()));
      unassume.addActionListener(a -> assumeUnchanged(assumed, false));
      menu.add(unassume);
    }
    List<Change> selected = all.stream().filter(c -> c.kind() != Kind.ASSUMED).toList();
    if (selected.isEmpty()) {
      menu.show(tree, e.getX(), e.getY());
      return;
    }
    if (!assumed.isEmpty()) menu.addSeparator();

    boolean allGit = selected.stream().allMatch(c -> c.repo().vcs() == Vcs.GIT);
    String ignoreName = allGit ? ".gitignore" : SyncBundle.message("sync.ignore.list");
    JMenuItem ignoreFiles = new JMenuItem(SyncBundle.message("sync.menu.ignore", ignoreName, selected.size()));
    ignoreFiles.addActionListener(a -> ignore(selected, false));
    menu.add(ignoreFiles);

    // .idea/misc.xml 처럼 폴더째 무시하는 경우가 많아 상위 폴더 단위도 둔다.
    List<String> folders = selected.stream().map(Change::path).filter(p -> p.contains("/"))
      .map(p -> "/" + p.substring(0, p.lastIndexOf('/') + 1)).distinct().toList();
    if (!folders.isEmpty()) {
      JMenuItem ignoreFolders = new JMenuItem(SyncBundle.message("sync.menu.ignore.folders", ignoreName, String.join(", ", folders)));
      ignoreFolders.addActionListener(a -> ignore(selected, true));
      menu.add(ignoreFolders);
    }

    // 되돌리기는 HEAD 에 있는 파일의 내 변경만 대상이다. 새 파일 · 이름 바꾼 파일 · 복사한 파일은 지우지 않고 스테이징만 풀 수 있다.
    List<Change> revertable = selected.stream().filter(c -> undoOf(c) == Undo.REVERT).toList();
    List<Change> unstageable = selected.stream().filter(c -> undoOf(c) == Undo.UNSTAGE).toList();
    if (!revertable.isEmpty() || !unstageable.isEmpty()) menu.addSeparator();
    if (!revertable.isEmpty()) {
      JMenuItem revert = new JMenuItem(SyncBundle.message("sync.menu.revert", revertable.size()));
      revert.addActionListener(a -> revert(revertable, unstageable.size()));
      menu.add(revert);
    }
    if (!unstageable.isEmpty()) {
      JMenuItem unstage = new JMenuItem(SyncBundle.message("sync.menu.unstage", unstageable.size()));
      unstage.addActionListener(a -> unstage(unstageable));
      menu.add(unstage);
    }
    // 이미 추적 중이라 무시 목록으로는 못 빼는 파일(.iml 등)은 git update-index --skip-worktree 로 변경을 모른 척한다.
    // 내 작업본에만 적용된다. SVN 에는 대응 기능이 없다.
    List<Change> assumable = revertable.stream().filter(c -> c.repo().vcs() == Vcs.GIT).toList();
    if (!assumable.isEmpty()) {
      JMenuItem assume = new JMenuItem(SyncBundle.message("sync.menu.assume", assumable.size()));
      assume.addActionListener(a -> assumeUnchanged(assumable, true));
      menu.add(assume);
    }
    menu.show(tree, e.getX(), e.getY());
  }

  /**
   * 나갈 변경 하나에 「내 변경 되돌리기」로 할 일. 새 파일(?)은 아무것도 하지 않는다.
   * Git 에서 상태에 A · R · C 가 있으면 경로가 HEAD 에 없을 수 있어 스테이징만 푼다. SVN 의 revert 는 추가한 파일도 디스크에 남기므로 그대로 되돌린다.
   */
  static Undo undoOf(Change c) {
    if (c.kind() != Kind.OUTGOING || UNTRACKED_STATUS.contains(c.status())) return Undo.NONE;
    if (c.repo().vcs() != Vcs.GIT) return Undo.REVERT;
    for (char letter : c.status().toCharArray()) {
      if (GIT_NEW_PATH_LETTERS.indexOf(letter) >= 0) return Undo.UNSTAGE;
    }
    return Undo.REVERT;
  }

  /**
   * 받기 · 커밋 · 올리기 · 충돌 해결 항목. 받기 · 올리기 · 충돌 해결은 아래 단추를 눌러 같은 코드로 한다(고른 줄의 저장소가 대상).
   * SVN 은 들어올 파일만 svn update 할 수 있고, 커밋하면 바로 서버라 올리기가 없다. Git 은 파일 단위 받기가 없다.
   */
  private void addSyncItems(JPopupMenu menu, List<Change> targets) {
    List<Change> incoming = targets.stream().filter(c -> c.kind() == Kind.INCOMING).toList();
    List<Change> outgoing = targets.stream().filter(c -> c.kind() == Kind.OUTGOING).toList();
    boolean conflict = targets.stream().anyMatch(c -> c.kind() == Kind.CONFLICT);
    if (!incoming.isEmpty() || conflict) {
      JMenuItem pull = new JMenuItem(SyncBundle.message("sync.menu.pull"), pullButton.getIcon());
      pull.addActionListener(a -> pullButton.doClick());
      menu.add(pull);
    }
    List<Change> svnIncoming = incoming.stream().filter(c -> c.repo().vcs() == Vcs.SVN).toList();
    if (!svnIncoming.isEmpty()) {
      JMenuItem update = new JMenuItem(SyncBundle.message("sync.menu.update.files", svnIncoming.size()));
      update.addActionListener(a -> runOnRepos(svnIncoming, repo -> List.of("update", "--non-interactive", "--"),
                                               done -> SyncBundle.message("sync.update.files.done", done)));
      menu.add(update);
    }
    if (!outgoing.isEmpty()) {
      JMenuItem commit = new JMenuItem(SyncBundle.message("sync.menu.commit", outgoing.size()), AllIcons.Actions.Commit);
      commit.addActionListener(a -> commitFiles(commit.getText(), outgoing));
      menu.add(commit);
    }
    if (outgoing.stream().anyMatch(c -> c.repo().vcs() == Vcs.GIT)) {
      JMenuItem push = new JMenuItem(SyncBundle.message("sync.menu.push"), pushButton.getIcon());
      push.addActionListener(a -> pushButton.doClick());
      menu.add(push);
    }
    if (conflict) {
      JMenuItem resolve = new JMenuItem(SyncBundle.message("sync.menu.resolve"), resolveButton.getIcon());
      resolve.addActionListener(a -> resolveButton.doClick());
      menu.add(resolve);
    }
  }

  /** 고른 파일만 체크된 채로 IDE 커밋 창을 연다. 지운 파일도 넘기도록 VirtualFile 대신 FilePath 로 넘긴다. */
  private void commitFiles(String text, List<Change> targets) {
    List<FilePath> paths = new ArrayList<>();
    for (Change c : targets) paths.add(VcsUtil.getFilePath(new File(c.repo().root(), c.path()), false));
    DataContext context = SimpleDataContext.builder().setParent(DataManager.getInstance().getDataContext(this))
      .add(VcsDataKeys.FILE_PATHS, paths)
      .build();
    runAction(text, ACTION_COMMIT_FILES, context);
  }

  /** 고른 묶음 줄(들어올 · 나갈 · 충돌) 아래 파일 전부. */
  private List<Change> selectedGroupChanges() {
    List<Change> result = new ArrayList<>();
    TreePath[] paths = tree.getSelectionPaths();
    if (paths == null) return result;
    for (TreePath path : paths) {
      if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Group) {
        for (int i = 0; i < node.getChildCount(); i++) {
          if (((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject() instanceof Change c) result.add(c);
        }
      }
    }
    return result;
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

  /**
   * 저장소별로 나눠 각 저장소의 무시 목록에 넣는다. 패턴은 저장소 기준 "/경로" 또는 폴더면 "/경로/".
   * 파일 쓰기 · svn 실행이 있어 작업 스레드에서 돌고, 한 저장소가 실패하면 거기서 멈추고 그때까지 넣은 것을 함께 알린다.
   */
  private void ignore(List<Change> selected, boolean folders) {
    // 열린 .gitignore 의 저장 안 한 내용과 디스크에 쓴 줄이 서로 덮지 않게 먼저 저장하고, 쓴 뒤 IDE 가 다시 읽게 한다.
    saveAllDocuments();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<String> done = new ArrayList<>();
      List<File> written = new ArrayList<>();
      String message;
      try {
        for (Map.Entry<Repo, List<Change>> entry : byRepo(selected).entrySet()) {
          List<String> patterns = ignorePatterns(entry.getValue(), folders);
          if (patterns.isEmpty()) continue;
          File root = entry.getKey().root();
          if (entry.getKey().vcs() == Vcs.GIT) {
            addToGitignore(root, patterns);
            written.add(new File(root, ".gitignore"));
          }
          else addToSvnIgnore(root, patterns);
          done.addAll(patterns);
        }
        message = SyncBundle.message("sync.ignore.done", String.join(", ", done));
      }
      catch (IOException ex) {
        message = SyncBundle.message("sync.ignore.failed", ex.getMessage())
                  + (done.isEmpty() ? "" : " " + SyncBundle.message("sync.ignore.failed.partial", String.join(", ", done)));
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      LocalFileSystem.getInstance().refreshIoFiles(written, true, false, null);
      String m = message;
      SwingUtilities.invokeLater(() -> { showStatus(m); recompare(); });
    });
  }

  /** 편집기에 저장 안 한 내용을 디스크에 쓴다. 단추 · 메뉴 클릭은 쓰기 락 없이 오므로 락을 잡는다. EDT 에서 부른다. */
  private static void saveAllDocuments() {
    WriteIntentReadAction.run((Runnable) () -> FileDocumentManager.getInstance().saveAllDocuments());
  }

  private static List<String> ignorePatterns(List<Change> changes, boolean folders) {
    List<String> patterns = new ArrayList<>();
    for (Change c : changes) {
      String p = c.path();
      if (folders) {
        if (!p.contains("/")) continue;
        p = p.substring(0, p.lastIndexOf('/') + 1);
      }
      if (!patterns.contains("/" + p)) patterns.add("/" + p);
    }
    return patterns;
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
      String old = parseSvnProperty(svnPropget(root, entry.getKey()));
      Set<String> existing = new LinkedHashSet<>(Arrays.asList(old.replace("\r\n", "\n").split("\n")));
      existing.remove("");
      existing.addAll(entry.getValue());
      run(root, "svn", "propset", SVN_IGNORE_PROP, String.join("\n", existing), entry.getKey());
    }
  }

  /**
   * 폴더의 svn:ignore 를 --xml 로 읽는다. 속성이 없다는 경고만 빈 값으로 보고,
   * 그 밖의 실패(작업본 아님 · 잠김 · svn 없음)는 그대로 던져 덮어쓰지 않게 한다.
   */
  private static String svnPropget(File root, String dir) throws IOException, InterruptedException {
    try {
      return run(root, "svn", "propget", SVN_IGNORE_PROP, "--xml", dir);
    }
    catch (IOException e) {
      if (e.getMessage() != null && e.getMessage().contains(SVN_PROPERTY_NOT_FOUND)) return "";
      throw e;
    }
  }

  /** svn propget --xml 결과에서 첫 property 값을 꺼낸다. 빈 글 · property 없음은 빈 값. */
  static String parseSvnProperty(String xml) throws IOException {
    if (xml.isBlank()) return "";
    NodeList props = xmlElements(xml, "property", "svn propget");
    return props.getLength() == 0 ? "" : props.item(0).getTextContent();
  }

  /**
   * HEAD 에 있는 파일의 작업본 · 스테이징 변경을 마지막 커밋 상태로 되돌린다. 되돌린 내용은 복구할 수 없어 먼저 확인받는다.
   * skipped 는 같이 골랐지만 새 경로라 대상에서 뺀 파일 수다(확인창에 알린다).
   */
  private void revert(List<Change> targets, int skipped) {
    int answer = Messages.showYesNoDialog(project,
      SyncBundle.message("sync.revert.confirm") + "\n\n" + pathList(targets)
        + (skipped > 0 ? "\n\n" + SyncBundle.message("sync.revert.skipped", skipped) : ""),
      SyncBundle.message("sync.revert.title"), Messages.getWarningIcon());
    if (answer != Messages.YES) return;
    // 저장 안 한 편집 내용이 되돌린 뒤 자동 저장으로 다시 디스크에 쓰이지 않게 먼저 저장한다(되돌리면 그 내용도 같이 지워진다).
    saveAllDocuments();
    runOnRepos(targets, repo -> repo.vcs() == Vcs.GIT
                                ? List.of("restore", "--staged", "--worktree", "--")
                                : List.of("revert", "--"),
               done -> SyncBundle.message("sync.revert.done", done)
                       + (done < targets.size() ? " " + SyncBundle.message("sync.revert.committed", targets.size() - done) : ""));
  }

  /** 스테이징(색인)에서만 빼고 파일 내용은 그대로 둔다. 디스크를 바꾸지 않으므로 확인받지 않는다. */
  private void unstage(List<Change> targets) {
    runOnRepos(targets, repo -> List.of("restore", "--staged", "--"),
               done -> SyncBundle.message("sync.unstage.done", done));
  }

  private static String pathList(List<Change> targets) {
    String list = String.join("\n", targets.stream().map(Change::path).limit(PATH_LIST_MAX).toList());
    return targets.size() > PATH_LIST_MAX ? list + "\n" + SyncBundle.message("sync.path.more", targets.size() - PATH_LIST_MAX) : list;
  }

  /**
   * 저장소마다 앞 인자(head) 뒤에 경로를 붙여 git 또는 svn 을 작업 스레드에서 돌리고, 결과를 상태 줄에 적은 뒤 다시 비교한다.
   * Git 은 HEAD 와 다른 경로만 넘긴다. 커밋했지만 아직 올리지 않은 파일도 나갈 변경이지만 되돌릴 작업본 변경이 없다.
   * success 는 실제로 넘긴 파일 수로 알림 글을 만든다. 끝나면 바뀐 파일을 IDE 가 다시 읽게 한다.
   */
  private void runOnRepos(List<Change> targets, java.util.function.Function<Repo, List<String>> head,
                          java.util.function.IntFunction<String> success) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String message;
      List<File> touched = new ArrayList<>();
      try {
        int done = 0;
        for (Map.Entry<Repo, List<Change>> entry : byRepo(targets).entrySet()) {
          Repo repo = entry.getKey();
          List<String> paths = new ArrayList<>();
          for (Change c : entry.getValue()) paths.add(c.path());
          if (repo.vcs() == Vcs.GIT) paths.retainAll(changedFromHead(repo.root(), paths));
          if (paths.isEmpty()) continue;
          List<String> args = new ArrayList<>(head.apply(repo));
          args.addAll(paths);
          if (repo.vcs() == Vcs.GIT) git(repo.root(), args.toArray(String[]::new));
          else run(repo.root(), "svn", args.toArray(String[]::new));
          done += paths.size();
          for (String path : paths) touched.add(new File(repo.root(), path));
        }
        message = success.apply(done);
      }
      catch (IOException ex) {
        message = ex.getMessage();
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      LocalFileSystem.getInstance().refreshIoFiles(touched, true, false, null);
      String m = message;
      SwingUtilities.invokeLater(() -> { showStatus(m); recompare(); });
    });
  }

  /** paths 가운데 HEAD 와 내용이 다른(스테이징 · 작업본) 경로. 경로는 NUL 로 나눠 받아 이스케이프가 없다. */
  private static Set<String> changedFromHead(File root, List<String> paths) throws IOException, InterruptedException {
    List<String> args = new ArrayList<>(List.of("diff", "--name-only", "-z", "HEAD", "--"));
    args.addAll(paths);
    return new HashSet<>(Arrays.asList(git(root, args.toArray(String[]::new)).split("\0")));
  }

  /** 모른 척하기 · 풀기 뒤 알림 문구. 프로젝트 트리 메뉴(AssumeUnchangedAction)와 같이 쓴다. */
  static String hideMessage(int count, boolean on) {
    return on ? SyncBundle.message("sync.assume.done", count) : SyncBundle.message("sync.unassume.done", count);
  }

  /**
   * on 이면 파일 내용은 그대로 두고 git 이 변경을 보고하지 않게 한다(skip-worktree). 받기 · 브랜치 전환으로 git 이 이 파일을 바꿔야 하면
   * git 은 표시를 그대로 둔 채 그 동작을 멈추므로 먼저 풀어야 한다.
   * on 이 아니면 skip-worktree 와 예전 방식인 assume-unchanged 표시를 모두 풀어 내 변경이 다시 나갈 변경으로 보이게 한다. 파일 내용은 어느 쪽도 바꾸지 않는다.
   */
  private void assumeUnchanged(List<Change> targets, boolean on) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String message;
      try {
        for (Map.Entry<Repo, List<Change>> entry : byRepo(targets).entrySet()) {
          setHidden(entry.getKey().root(), entry.getValue().stream().map(Change::path).toList(), on);
        }
        message = hideMessage(targets.size(), on);
      }
      catch (IOException ex) {
        message = ex.getMessage();
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      String m = message;
      SwingUtilities.invokeLater(() -> { showStatus(m); recompare(); });
    });
  }

  /**
   * 루트 기준 경로들에 skip-worktree 를 걸거나 푼다. 풀 때는 assume-unchanged 도 푼다.
   * git update-index 는 한 번에 표시 하나만 바꾸므로(--no-assume-unchanged 와 함께 주면 skip-worktree 는 그대로) 따로 부른다.
   */
  static void setHidden(File root, List<String> paths, boolean on) throws IOException, InterruptedException {
    List<String> flags = on ? List.of("--skip-worktree") : List.of("--no-skip-worktree", "--no-assume-unchanged");
    for (String flag : flags) {
      List<String> args = new ArrayList<>(List.of("update-index", flag, "--"));
      args.addAll(paths);
      git(root, args.toArray(String[]::new));
    }
  }

  /** 명령은 저장소마다 따로 실행해야 하므로 고른 파일을 저장소별로 나눈다. 순서는 고른 순서를 지킨다. */
  static Map<Repo, List<Change>> byRepo(List<Change> changes) {
    Map<Repo, List<Change>> result = new LinkedHashMap<>();
    for (Change c : changes) result.computeIfAbsent(c.repo(), k -> new ArrayList<>()).add(c);
    return result;
  }

  /** 저장소 하나를 처음 읽은 결과. remote 는 같이 출발시킨 원격 확인(fetch · svn status -u 뒤 비교)이다. */
  private record Scanned(Repo repo, List<Change> changes, Future<List<Change>> remote) {}

  /** 저장소 하나에 할 일. 결과는 입력 순서대로 모인다. */
  private interface RepoWork<T, R> {
    R run(T item) throws IOException, InterruptedException;
  }

  /**
   * items 마다 work 를 작업 스레드에서 동시에 돌리고 입력 순서대로 결과를 돌려준다(저장소가 많아도 가장 느린 하나만큼 걸린다).
   * 하나가 실패하면 그 예외를 그대로 던지고, 아직 도는 나머지는 중단한다.
   */
  private static <T, R> List<R> inParallel(List<T> items, RepoWork<T, R> work) throws IOException, InterruptedException {
    List<Future<R>> futures = new ArrayList<>();
    for (T item : items) {
      futures.add(ApplicationManager.getApplication().executeOnPooledThread(() -> work.run(item)));
    }
    List<R> results = new ArrayList<>();
    try {
      for (Future<R> future : futures) results.add(await(future));
    }
    finally {
      for (Future<R> future : futures) future.cancel(true);
    }
    return results;
  }

  /** IDE 가 아는 작업본 변경(변경 목록)과 새 파일의 절대 경로. */
  private Set<String> localChangePaths() {
    ChangeListManager manager = ChangeListManager.getInstance(project);
    Set<String> result = new HashSet<>();
    for (com.intellij.openapi.vcs.changes.Change change : manager.getAllChanges()) {
      ContentRevision revision = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
      if (revision != null) result.add(revision.getFile().getPath());
    }
    for (FilePath path : manager.getUnversionedFilesPaths()) result.add(path.getPath());
    return result;
  }

  /** 모아 둔 알림에 해당하는 저장소만 서버에 묻지 않고 다시 비교하고 모은 것을 비운다. 창이 숨어 있으면 비교하지 않고 stale 만 세운다. */
  private void recomparePending() {
    for (File root : switchedRoots) {
      pendingRoots.remove(root);
      refreshRepo(root, Vcs.GIT);
    }
    switchedRoots.clear();
    if (hidden) {
      stale = true;
      pendingRoots.clear();
      pendingPaths.clear();
      return;
    }
    // 커밋 · 브랜치 전환이 온 저장소는 역머지 안내도 다시 세고, 파일 저장만 온 저장소는 비교만 한다(안내 세기는 git 을 여섯 번 더 띄운다).
    List<Repo> changed = new ArrayList<>();
    List<Repo> saved = new ArrayList<>();
    for (Repo repo : repos) {
      if (pendingRoots.contains(repo.root())) {
        changed.add(repo);
        continue;
      }
      for (String path : pendingPaths) {
        if (FileUtil.isAncestor(repo.root(), new File(path), false)) {
          saved.add(repo);
          break;
        }
      }
    }
    pendingRoots.clear();
    pendingPaths.clear();
    recompare(changed, false, false, true);
    recompare(saved, false, false, false);
  }

  /** 작업 스레드의 결과를 기다린다. 작업이 던진 IOException · InterruptedException 은 그대로 다시 던진다. */
  private static <R> R await(Future<R> future) throws IOException, InterruptedException {
    try {
      return future.get();
    }
    catch (ExecutionException e) {
      if (e.getCause() instanceof IOException io) throw io;
      if (e.getCause() instanceof InterruptedException ie) throw ie;
      throw new IOException(e.getCause());
    }
  }

  /** fetch 없이 지금 상태로만 모든 저장소를 다시 비교한다(무시 · 되돌리기 · 커밋 직후). */
  private void recompare() {
    recompare(repos, true, true);
  }

  /** 모른 척 중 표시가 바뀐 뒤(프로젝트 트리 메뉴) 모든 저장소를 서버에 묻지 않고 다시 비교한다. 창이 숨어 있으면 보일 때 한다. EDT 에서 부른다. */
  void recompareAssumed() {
    if (hidden) stale = true;
    else recompare(repos, false, true);
  }

  /**
   * fetch 없이 targets 만 다시 비교하고 목록에서 그 저장소 것만 바꾼다.
   * full 이면 새 비교 차례를 잡아 그보다 늦게 끝난 옛 비교를 버리고, SVN 은 서버에 묻는다(닿지 못하면 작업본만).
   * 아니면 차례를 잡지 않아 저장소별 비교끼리 서로 버리지 않고 도중에 전체 비교가 새로 시작됐을 때만 결과를 버리며,
   * SVN 은 작업본만 읽고 들어올 변경은 마지막 서버 확인 결과(svnIncoming)를 쓴다.
   * assumed 가 거짓이면 git ls-files -v 를 건너뛰고 지금 목록에 있던 그 저장소의 모른 척 중 항목을 그대로 둔다.
   */
  private void recompare(List<Repo> targets, boolean full, boolean assumed) {
    recompare(targets, full, assumed, true);
  }

  /** recompare 와 같고, note 가 거짓이면 저장소 줄의 역머지 안내(「develop 보다 N개 뒤」)를 다시 세지 않고 지난 것을 둔다. */
  private void recompare(List<Repo> targets, boolean full, boolean assumed, boolean note) {
    if (targets.isEmpty()) return;
    int seq = full ? ++compareSeq : compareSeq;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<Change> result = new ArrayList<>();
      AtomicReference<String> error = new AtomicReference<>();
      try {
        for (List<Change> part : inParallel(targets, repo -> {
          REPO_SLOTS.acquire();
          try {
            if (repo.vcs() == Vcs.GIT) {
              // 커밋 · 브랜치 전환 뒤 위쪽 줄의 「develop 보다 N개 뒤 · 내 파일 N개 겹침」도 마지막으로 받아 둔 서버 상태로 다시 센다.
              if (note) updateReverseNote(repo, false);
              return compare(repo, assumed);
            }
            if (!full) return compareSvnLocal(repo);
            try {
              return compareSvnRemote(repo);
            }
            catch (IOException offline) {
              error.set(offline.getMessage());
              svnIncoming.remove(repo.root());
              return compareSvn(repo, false);
            }
          }
          finally {
            REPO_SLOTS.release();
          }
        })) {
          result.addAll(part);
        }
      }
      catch (IOException ex) {
        error.set(ex.getMessage());
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
      String message = error.get();
      Set<File> roots = new HashSet<>();
      for (Repo repo : targets) roots.add(repo.root());
      SwingUtilities.invokeLater(() -> {
        if (seq != compareSeq) return;
        List<Change> next = new ArrayList<>();
        for (Change c : changes) {
          if (!roots.contains(c.repo().root()) || (!assumed && c.kind() == Kind.ASSUMED)) next.add(c);
        }
        next.addAll(result);
        showTitle(repos);
        // 새로고침 중이면 위쪽 상태 글은 그쪽 진행 글을 둔다.
        if (message != null) showStatus(message);
        else if (!busy) showSummary(next, summaryTail);
        // 저장소 순서만 달라졌을 뿐 항목이 같으면 트리를 다시 만들지 않는다. 다시 만들면 고른 줄과 비교 화면이 비워진다.
        if (next.size() == changes.size() && new HashSet<>(next).equals(new HashSet<>(changes))) {
          tree.repaint();  // 저장소 줄 끝의 역머지 안내만 바뀌었을 수 있다.
          return;
        }
        changes = next;
        rebuildTree();
      });
    });
  }

  /** 도구줄 새로고침 동작. 새로고침이 도는 동안(busy) 꺼진다. 글은 풍선 도움말로 보인다. */
  private final class RefreshAction extends DumbAwareAction {
    private final Runnable run;

    RefreshAction(String key, Icon icon, Runnable run) {
      super(SyncBundle.message(key), null, icon);
      this.run = run;
    }

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(!busy); }
    @Override public void actionPerformed(@NotNull AnActionEvent e) { run.run(); }
  }

  /** 도구줄 보기 거르기. 넷 중 하나만 눌린 상태(라디오)이고, kind 가 null 이면 전체. 아이콘 옆에 글도 보인다. */
  private final class ModeAction extends DumbAwareToggleAction {
    private final Kind kind;

    ModeAction(String key, Icon icon, Kind kind) {
      super(SyncBundle.message(key), null, icon);
      this.kind = kind;
      getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
    }

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public boolean isSelected(@NotNull AnActionEvent e) { return modeFilter == kind; }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
      if (!state) return;  // 눌린 것을 다시 눌러도 거르기는 풀리지 않는다.
      modeFilter = kind;
      rebuildTree();
    }
  }

  /**
   * IDE 동작을 부르는 단추. forSelectedRepo 면 트리에서 고른 저장소들의 루트를 VIRTUAL_FILE(_ARRAY) 로 넘겨
   * 받기 창은 첫 저장소로 열린다(DvcsUtil.guessRepositoryForOperation 이 VIRTUAL_FILE 을 먼저 본다).
   * 고른 저장소가 없으면 IDE 기본(마지막 편집 파일의 저장소)을 따른다.
   */
  private JButton actionButton(String text, Icon icon, String actionId, boolean forSelectedRepo) {
    JButton button = new JButton(text, icon);
    button.addActionListener(e -> {
      // Git.Pull 은 Git 전용이라 SVN 저장소에서는 꺼져 있다. SVN 은 svn update 로 받는다.
      // Git 과 SVN 이 섞여 있으면 받기는 SVN 저장소를 svn update 로 받고 Git 저장소는 Git.Pull 로 이어 받는다.
      List<Repo> targets = forSelectedRepo ? buttonTargets() : List.of();
      if ((ACTION_PULL.equals(actionId) || ACTION_UPDATE_ALL.equals(actionId)) && !repos.isEmpty()) {
        // 받기는 IntelliJ 받기 창을 거치지 않고 저장소마다 직접 받는다. SVN 은 svn update, Git 은 git pull --ff-only(그냥 못 받는 저장소만 IntelliJ 받기 창).
        // 마지막 원격 확인 결과로 들어올 변경 · 충돌이 있는 저장소만 받고, 하나도 없으면 알림 창만 띄운다.
        List<Repo> svnRepos = new ArrayList<>();
        List<Repo> gitRepos = new ArrayList<>();
        for (Repo repo : forSelectedRepo ? targets : repos) {
          if (!hasIncoming(List.of(repo))) continue;
          (repo.vcs() == Vcs.SVN ? svnRepos : gitRepos).add(repo);
        }
        if (svnRepos.isEmpty() && gitRepos.isEmpty()) {
          Messages.showInfoMessage(project, SyncBundle.message("sync.nothing.to.pull"), text);
          return;
        }
        if (!svnRepos.isEmpty()) svnUpdate(button, svnRepos);
        if (!gitRepos.isEmpty()) gitPull(button, text, gitRepos);
        return;
      }
      if (ACTION_PUSH.equals(actionId)) {
        pushIfAhead(button, text, targets);
        return;
      }
      runAction(text, actionId, forSelectedRepo ? selectedGitRootsContext() : DataManager.getInstance().getDataContext(this));
    });
    return button;
  }

  /**
   * Git 저장소마다 git pull --ff-only 를 작업 스레드에서 동시에 돌린다.
   * IntelliJ Update Project 는 저장소마다 확인 명령을 차례로 띄워 저장소 7개면 40초가 넘지만, 이렇게 하면 가장 느린 fetch 하나만큼 걸린다.
   * 그냥 받지 못하는 저장소(내 커밋과 갈라짐 · 저장 안 한 변경과 겹침 · 충돌 중)는 전처럼 IntelliJ 받기 창(Git.Pull)으로 넘긴다.
   * 받는 동안 그 루트를 fetching 에 두어 저장소 알림으로 따로 다시 비교하지 않게 하고, 끝나면 파일 보기를 새로 읽고 한 번 다시 비교한다.
   */
  private void gitPull(JButton button, String text, List<Repo> targets) {
    List<File> roots = new ArrayList<>();
    for (Repo repo : targets) roots.add(repo.root());
    button.setEnabled(false);
    showStatus(SyncBundle.message("sync.git.pulling", text, roots.size()));
    fetching.addAll(roots);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Map<File, String> failed = Collections.synchronizedMap(new LinkedHashMap<>());
      try {
        inParallel(roots, root -> {
          try {
            git(root, GIT_LONG_TIMEOUT_SEC, "pull", "--ff-only", "--quiet");
          }
          catch (IOException e) {
            failed.put(root, e.getMessage());
          }
          return null;
        });
      }
      catch (IOException unreachable) {
        // 저장소별 실패는 failed 에 담겼다.
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fetching.removeAll(roots);
        SwingUtilities.invokeLater(() -> button.setEnabled(true));
        return;
      }
      fetching.removeAll(roots);
      LocalFileSystem.getInstance().refreshIoFiles(roots, true, true, null);
      List<String> names = new ArrayList<>();
      for (File root : failed.keySet()) names.add(root.getName());
      String message = failed.isEmpty()
                       ? SyncBundle.message("sync.git.pulled", text, roots.size())
                       : SyncBundle.message("sync.git.pulled.partly", text, roots.size() - failed.size(), String.join(", ", names));
      // IDE 의 invokeLater 로 연다(SwingUtilities 로 연 받기 창은 쓰기 안전하지 않은 문맥이 된다. pushIfAhead 와 같다).
      ApplicationManager.getApplication().invokeLater(() -> {
        button.setEnabled(true);
        showStatus(message);
        recompare();
        if (!failed.isEmpty()) runAction(text, ACTION_PULL, rootsContext(new ArrayList<>(failed.keySet())));
      }, ModalityState.nonModal(), project.getDisposed());
    });
  }

  /** repos 가운데 들어올 변경 · 충돌이 하나라도 있는 저장소가 있는가. */
  private boolean hasIncoming(List<Repo> targets) {
    for (Change c : changes) {
      if ((c.kind() == Kind.INCOMING || c.kind() == Kind.CONFLICT) && targets.contains(c.repo())) return true;
    }
    return false;
  }

  /**
   * 고른 Git 저장소(없으면 전부)에 원격보다 앞선 커밋이 있을 때만 IntelliJ Push 창을 연다. 없으면 Push 창 대신 알림 창을 띄운다.
   * 커밋 수는 작업 스레드에서 저장소마다 동시에 git rev-list 로 센다(저장소가 많아도 하나만큼만 기다린다).
   * 세지 못한 저장소(추적 브랜치 없음 등)는 Push 창이 다루도록 앞선 것으로 본다.
   */
  private void pushIfAhead(JButton button, String text, List<Repo> targets) {
    List<File> roots = new ArrayList<>();
    for (Repo repo : targets) {
      if (repo.vcs() == Vcs.GIT) roots.add(repo.root());
    }
    if (roots.isEmpty()) {
      runPush(text);
      return;
    }
    button.setEnabled(false);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      boolean ahead = false;
      try {
        for (Boolean one : inParallel(roots, root -> {
          try {
            return Integer.parseInt(git(root, "rev-list", "--count", "@{u}..HEAD").trim()) > 0;
          }
          catch (IOException | NumberFormatException e) {
            return true;
          }
        })) {
          ahead |= one;
        }
      }
      catch (IOException e) {
        ahead = true;
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      finally {
        SwingUtilities.invokeLater(() -> button.setEnabled(true));
      }
      boolean found = ahead;
      // IDE 의 invokeLater 로 연다. SwingUtilities.invokeLater 로 연 Push 창은 쓰기 안전하지 않은 문맥이 되어,
      // 창의 Push 단추가 파일을 저장할 때 「Write-unsafe context」 오류가 난다.
      ApplicationManager.getApplication().invokeLater(() -> {
        if (found) runPush(text);
        else Messages.showInfoMessage(project, SyncBundle.message("sync.nothing.to.push"), text);
      }, ModalityState.nonModal(), project.getDisposed());
    });
  }

  /** 고른 Git 저장소들의 루트를 넘겨 IntelliJ Push 창을 연다. */
  private void runPush(String text) {
    runAction(text, ACTION_PUSH, selectedGitRootsContext());
  }

  /** 트리에서 고른 Git 저장소 루트를 VIRTUAL_FILE(_ARRAY) 로 담은 동작 문맥. 고른 것이 없으면 이 창의 기본 문맥. */
  private DataContext selectedGitRootsContext() {
    List<File> files = new ArrayList<>();
    for (Repo repo : selectedRepos()) {
      // Git 동작에 SVN 루트를 넘기면 첫 루트가 SVN 일 때 받기 창이 저장소를 고르지 못한다.
      if (repo.vcs() == Vcs.GIT) files.add(repo.root());
    }
    return rootsContext(files);
  }

  /** files 의 Git 루트를 VIRTUAL_FILE(_ARRAY) 로 담은 동작 문맥. 비어 있으면 이 창의 기본 문맥. */
  private DataContext rootsContext(List<File> files) {
    DataContext context = DataManager.getInstance().getDataContext(this);
    List<VirtualFile> roots = new ArrayList<>();
    for (File file : files) {
      VirtualFile root = LocalFileSystem.getInstance().findFileByIoFile(file);
      if (root != null) roots.add(root);
    }
    if (roots.isEmpty()) return context;
    return SimpleDataContext.builder().setParent(context)
      .add(CommonDataKeys.VIRTUAL_FILE, roots.get(0))
      .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, roots.toArray(VirtualFile[]::new))
      .build();
  }

  /** 단추가 다룰 저장소: 트리에서 고른 것, 없으면 전부. */
  private List<Repo> buttonTargets() {
    List<Repo> targets = selectedRepos();
    return targets.isEmpty() ? repos : targets;
  }

  /**
   * SVN 저장소마다 svn update 를 작업 스레드에서 돌리고, 파일 보기를 새로 읽은 뒤 다시 비교한다. 실패하면 그 오류를 상태 줄에 적는다.
   * 같은 작업본에 update 가 겹쳐 돌면 잠금 오류가 나므로 끝날 때까지(실패 · 중단 포함) 단추를 꺼 둔다.
   */
  private void svnUpdate(JButton button, List<Repo> targets) {
    String text = button.getText();
    button.setEnabled(false);
    showStatus(SyncBundle.message("sync.svn.updating", text));
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        String message = SyncBundle.message("sync.svn.updated", text, targets.size());
        List<File> roots = new ArrayList<>();
        try {
          for (Repo repo : targets) {
            roots.add(repo.root());
            run(repo.root(), SVN_UPDATE_TIMEOUT_SEC, "svn", "update", "--non-interactive");
          }
        }
        catch (IOException ex) {
          message = ex.getMessage();
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
        // 일부만 받고 실패했어도 바뀐 파일이 있을 수 있어 받은 저장소는 모두 새로 읽는다.
        LocalFileSystem.getInstance().refreshIoFiles(roots, true, true, null);
        String m = message;
        SwingUtilities.invokeLater(() -> { showStatus(m); recompare(); });
      }
      finally {
        SwingUtilities.invokeLater(() -> button.setEnabled(true));
      }
    });
  }

  /** IDE 동작 하나를 context 로 부른다. 동작이 꺼져 있으면(예: 충돌 해결은 git 이 실제 충돌 상태인 파일이 있을 때만) 아무 일도 없이 끝나므로 이유를 상태줄에 적는다. */
  private void runAction(String text, String actionId, DataContext context) {
    AnAction action = ActionManager.getInstance().getAction(actionId);
    if (action == null) {
      showStatus(SyncBundle.message("sync.action.missing", text, actionId));
      return;
    }
    AnActionEvent event = AnActionEvent.createFromAnAction(action, null, "KingGuraSynchronize", context);
    ActionUtil.updateAction(action, event);
    if (!event.getPresentation().isEnabled()) {
      showStatus("Git.ResolveConflicts".equals(actionId)
        ? SyncBundle.message("sync.action.disabled.resolve", text) : SyncBundle.message("sync.action.disabled", text));
      return;
    }
    ActionUtil.performActionDumbAwareWithCallbacks(action, event);
  }

  /** 파일 줄이면 그 종류, 묶음 줄이면 첫 자식 파일의 종류(묶음은 같은 종류 파일만 담는다). 저장소 줄은 null. */
  private static Kind kindOf(DefaultMutableTreeNode node) {
    Object value = node.getUserObject();
    if (value instanceof Group g) return g.kind();
    return value instanceof Change c ? c.kind() : null;
  }

  /** 위쪽 상태 글: 진행 · 오류 · 알림은 회색 글 한 조각. */
  private void showStatus(String text) {
    status.clear();
    status.setToolTipText(null);
    status.append(Objects.toString(text, ""), SimpleTextAttributes.GRAYED_ATTRIBUTES);
  }

  /** 위쪽 상태 글: 종류 색 숫자(↓ 들어올 · ↑ 나갈 · ⚠ 충돌, 0 은 뺀다) 뒤에 회색 tail. 풍선 도움말은 글로 쓴 요약. */
  private void showSummary(List<Change> list, String tail) {
    Map<Kind, Integer> count = new EnumMap<>(Kind.class);
    for (Change c : list) count.merge(c.kind(), 1, Integer::sum);
    int assumed = count.getOrDefault(Kind.ASSUMED, 0);
    status.clear();
    if (list.size() == assumed) {
      status.append(summary(list), SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }
    else {
      String lead = "";
      for (Map.Entry<Kind, String> mark : List.of(Map.entry(Kind.INCOMING, MARK_INCOMING), Map.entry(Kind.OUTGOING, MARK_OUTGOING),
                                                  Map.entry(Kind.CONFLICT, MARK_CONFLICT))) {
        int n = count.getOrDefault(mark.getKey(), 0);
        if (n == 0) continue;
        FileStatus color = statusColorOf(mark.getKey());
        status.append(lead + mark.getValue() + n, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color == null ? null : color.getColor()));
        lead = " ";
      }
      if (assumed > 0) status.append(SUMMARY_SEP + Kind.ASSUMED.label() + " " + assumed, SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }
    status.append(SUMMARY_SEP + tail, SimpleTextAttributes.GRAYED_ATTRIBUTES);
    status.setToolTipText(summary(list));
    summaryTail = tail;
  }

  /** 위쪽 저장소 글: Git 저장소 하나면 브랜치 아이콘 · 굵은 브랜치 · 회색 「↔ upstream」, 그 밖에는 글 한 조각. */
  private void showTitle(List<Repo> list) {
    title.clear();
    title.setIcon(null);
    title.setCursor(null);
    if (list.size() == 1 && list.get(0).vcs() == Vcs.GIT) {
      Repo repo = list.get(0);
      title.setIcon(AllIcons.Vcs.Branch);
      title.append(repo.root().getName() + "  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
      title.append(repo.branch(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
      title.append(" ↔ " + repo.upstream(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      appendReverseNote(title, repo);
      title.setCursor(Cursor.getPredefinedCursor(reverseNotes.containsKey(repo.root()) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
      return;
    }
    title.append(list.size() == 1 ? list.get(0).toString() : SyncBundle.message("sync.repo.count", list.size()));
  }

  /** 묶음 줄 아이콘. 도구줄 보기 거르기와 같은 그림을 쓰고, 충돌은 경고 표시. */
  private static Icon iconOf(Kind kind) {
    return switch (kind) {
      case CONFLICT -> AllIcons.General.Warning;
      case INCOMING -> AllIcons.Actions.Download;
      case OUTGOING -> AllIcons.Actions.Upload;
      case ASSUMED -> AllIcons.Actions.Show;
    };
  }

  /** 종류별 글자색으로 쓸 IDE 파일 상태. 모른 척 중 · 저장소 줄은 기본 글자색(null). */
  private static FileStatus statusColorOf(Kind kind) {
    if (kind == null) return null;
    return switch (kind) {
      case CONFLICT -> FileStatus.MERGED_WITH_CONFLICTS;
      case INCOMING -> FileStatus.MODIFIED;
      case OUTGOING -> FileStatus.MERGE;
      case ASSUMED -> null;
    };
  }

  /** 트리에서 고른 모든 줄이 속한 저장소(겹치지 않게, 고른 순서). */
  private List<Repo> selectedRepos() {
    Set<Repo> result = new LinkedHashSet<>();
    TreePath[] paths = tree.getSelectionPaths();
    if (paths == null) return new ArrayList<>(result);
    for (TreePath path : paths) {
      Repo repo = repoOf(path);
      if (repo != null) result.add(repo);
    }
    return new ArrayList<>(result);
  }

  /** 트리에서 고른 줄(저장소 줄 · 묶음 줄 · 파일 줄)이 속한 저장소. 고른 줄이 없거나 저장소를 알 수 없는 줄이면 null. */
  private Repo selectedRepo() {
    TreePath path = tree.getSelectionPath();
    return path == null ? null : repoOf(path);
  }

  /** 줄 하나가 속한 저장소. 저장소 줄 · 그 아래 묶음 줄 · 파일 줄이면 그 저장소, 아니면 null. */
  private static Repo repoOf(TreePath path) {
    for (Object part : path.getPath()) {
      Object value = ((DefaultMutableTreeNode) part).getUserObject();
      if (value instanceof Repo r) return r;
      if (value instanceof Change c) return c.repo();
    }
    return null;
  }

  /**
   * 프로젝트에 등록된 VCS 루트(Git · SVN). 하나도 없으면 프로젝트 폴더를 unmappedVcs 가 고른 종류로 본다(등록 전 프로젝트도 쓸 수 있게).
   * 프로젝트 폴더와 그 위에 .git · .svn 이 없으면(워크스페이스 폴더) 넣지 않는다. 저장소가 아니라 비교가 실패한다.
   */
  private List<Map.Entry<File, Vcs>> vcsRoots() {
    List<Map.Entry<File, Vcs>> result = new ArrayList<>();
    for (VcsRoot root : ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()) {
      if (root.getVcs() == null) continue;
      String name = root.getVcs().getName();
      File dir = new File(root.getPath().getPath());
      if (VCS_NAME_GIT.equals(name)) result.add(Map.entry(dir, Vcs.GIT));
      else if (VCS_NAME_SVN.equals(name)) result.add(Map.entry(dir, Vcs.SVN));
    }
    if (result.isEmpty() && project.getBasePath() != null) {
      File base = new File(project.getBasePath());
      if (underVcs(base)) result.add(Map.entry(base, unmappedVcs(base)));
    }
    return result;
  }

  /**
   * VCS 루트가 등록되지 않은 폴더의 종류. dir 부터 위로 올라가며 가장 가까운 .svn 또는 .git 을 따른다.
   * SVN 1.7 부터는 작업본 맨 위에만 .svn 이 있어 프로젝트 폴더가 작업본 안쪽이면 위에서 찾아야 한다. 둘 다 없으면 Git.
   */
  /** dir 이나 그 위 폴더에 .svn 폴더 또는 .git 이 있으면 true. */
  static boolean underVcs(File dir) {
    for (File d = dir.getAbsoluteFile(); d != null; d = d.getParentFile()) {
      if (new File(d, ".svn").isDirectory() || new File(d, ".git").exists()) return true;
    }
    return false;
  }

  static Vcs unmappedVcs(File dir) {
    for (File d = dir.getAbsoluteFile(); d != null; d = d.getParentFile()) {
      if (new File(d, ".svn").isDirectory()) return Vcs.SVN;
      if (new File(d, ".git").exists()) return Vcs.GIT;
    }
    return Vcs.GIT;
  }

  /**
   * 비교 결과를 다시 만든다. git · svn 은 백그라운드에서 돈다.
   * 마지막으로 받아 둔 원격 상태로 바로 비교해 보여 주고, 원격을 받아 와(fetch · svn status -u) 한 번 더 비교한다.
   * 원격 확인은 저장소 정보를 읽자마자 따로 출발시켜 지금 상태 비교와 동시에 돈다(다른 저장소의 지금 상태 비교를 기다리지 않는다).
   * 원격 작업은 네트워크에 따라 오래 걸릴 수 있어 그동안 목록을 비워 두지 않는다.
   * 저장소 하나가 실패해도(원격 추적 브랜치 없음 · 서버 연결 불가) 나머지는 계속 비교하고 오류는 상태 줄에 모아 보인다.
   */
  void refresh() {
    List<Map.Entry<File, Vcs>> roots = vcsRoots();
    if (roots.isEmpty()) {
      // 워크스페이스 폴더는 VCS 매핑이 잡히기 전이거나 받은 저장소가 없으면 루트가 비어 있다. 오류가 아니라 저장소 0개로 보인다.
      showStatus(project.getBasePath() == null ? SyncBundle.message("sync.no.project") : SyncBundle.message("sync.repo.count", 0));
      return;
    }
    busy = true;
    showStatus(SyncBundle.message("sync.comparing"));
    int seq = ++compareSeq;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<String> errors = Collections.synchronizedList(new ArrayList<>());
      List<Repo> found = new ArrayList<>();
      List<Change> result = new ArrayList<>();
      List<Scanned> scannedAll = new ArrayList<>();
      List<Future<List<Change>>> remotes = Collections.synchronizedList(new ArrayList<>());
      try {
        for (Scanned scanned : inParallel(roots, entry -> {
          REPO_SLOTS.acquire();
          try {
            Repo repo = entry.getValue() == Vcs.GIT ? gitRepo(entry.getKey()) : svnRepo(entry.getKey());
            Future<List<Change>> remote = ApplicationManager.getApplication().executeOnPooledThread(() -> remoteCompare(repo, errors));
            remotes.add(remote);
            return new Scanned(repo, repo.vcs() == Vcs.GIT ? compare(repo) : compareSvn(repo, false), remote);
          }
          catch (IOException e) {
            errors.add(e.getMessage());
            return null;
          }
          finally {
            REPO_SLOTS.release();
          }
        })) {
          if (scanned == null) continue;
          scannedAll.add(scanned);
          found.add(scanned.repo());
          result.addAll(scanned.changes());
        }
        repos = List.copyOf(found);
        List<Change> local = List.copyOf(result);
        SwingUtilities.invokeLater(() -> {
          showTitle(found);
          if (seq != compareSeq) return;
          changes = local;
          rebuildTree();
          showSummary(local, SyncBundle.message("sync.fetching.tail"));
        });
        List<Change> fetched = new ArrayList<>();
        for (Scanned scanned : scannedAll) fetched.addAll(await(scanned.remote()));
        result = fetched;
      }
      catch (IOException e) {
        errors.add(e.getMessage());
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        errors.add(SyncBundle.message("sync.interrupted"));
        // 새로고침 자체가 중단될 때만 원격 확인을 멈춘다. 그 밖에는 끝까지 둔다. 도중에 죽인 git fetch 는 .lock 파일을 남겨 다음 fetch 를 막을 수 있다.
        for (Future<List<Change>> remote : remotes) remote.cancel(true);
      }
      List<Change> finalResult = result;
      String checkedTail = SyncBundle.message("sync.last.check.tail", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
      String errorText = errors.isEmpty() ? null : String.join(" / ", errors);
      SwingUtilities.invokeLater(() -> {
        endBusy();
        showTitle(repos);
        // 그사이 다시 비교(recompare)가 시작됐으면 목록은 그쪽 결과를 따르고, 원격 확인 결과 · 오류는 그대로 알린다.
        if (errorText != null) showStatus(errorText);
        else showSummary(finalResult, checkedTail);
        if (seq != compareSeq) return;
        changes = finalResult;
        rebuildTree();
      });
    });
  }

  /**
   * 트리에서 고른 저장소 하나만 원격을 받아 와(fetch · svn status -u) 다시 비교하고, 목록에서 그 저장소 줄만 바꾼다.
   * 저장소가 하나뿐이면 고르지 않아도 그 저장소를 쓴다. 실패하면 그 저장소의 이전 목록을 그대로 두고 오류를 상태 줄에 보인다.
   */
  private void refreshSelected() {
    Repo selected = selectedRepo();
    if (selected == null && repos.size() == 1) selected = repos.get(0);
    if (selected == null) {
      showStatus(SyncBundle.message("sync.select.repo"));
      return;
    }
    refreshRepo(selected.root(), selected.vcs());
  }

  /** file 이 속한 VCS 루트 하나만 비교한다(프로젝트 트리 우클릭으로 창을 열 때). 가장 안쪽 루트를 고르고, 없으면 전체를 비교한다. */
  void refreshFor(VirtualFile file) {
    File target = new File(file.getPath());
    Map.Entry<File, Vcs> best = null;
    for (Map.Entry<File, Vcs> entry : vcsRoots()) {
      if (FileUtil.isAncestor(entry.getKey(), target, false)
          && (best == null || entry.getKey().getPath().length() > best.getKey().getPath().length())) {
        best = entry;
      }
    }
    if (best == null) refresh();
    else refreshRepo(best.getKey(), best.getValue());
  }

  /** 저장소 하나만 원격을 받아 와 다시 비교하고 목록에서 그 저장소 것만 바꾼다. 목록에 없던 저장소면 더한다. */
  private void refreshRepo(File root, Vcs vcs) {
    String name = root.getName();
    busy = true;
    showStatus(SyncBundle.message("sync.repo.fetching", name));
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Repo updated = null;
      List<Change> fresh = List.of();
      String error = null;
      try {
        // 브랜치를 바꿨을 수 있어 저장소 정보부터 다시 읽는다.
        updated = vcs == Vcs.GIT ? gitRepo(root) : svnRepo(root);
        if (updated.vcs() == Vcs.GIT) {
          fetch(updated.root());
          updateReverseNote(updated, true);
          fresh = compare(updated);
        }
        else {
          fresh = compareSvnRemote(updated);
        }
      }
      catch (IOException e) {
        error = name + ": " + e.getMessage();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        error = name + ": " + SyncBundle.message("sync.interrupted");
      }
      Repo finalRepo = updated;
      List<Change> finalFresh = fresh;
      String finalError = error;
      SwingUtilities.invokeLater(() -> {
        endBusy();
        if (finalError != null) {
          showStatus(finalError);
          return;
        }
        List<Repo> nextRepos = new ArrayList<>();
        boolean known = false;
        for (Repo r : repos) {
          boolean same = r.root().equals(finalRepo.root());
          known |= same;
          nextRepos.add(same ? finalRepo : r);
        }
        if (!known) nextRepos.add(finalRepo);
        repos = List.copyOf(nextRepos);
        List<Change> next = new ArrayList<>();
        for (Change c : changes) {
          if (!c.repo().root().equals(finalRepo.root())) next.add(c);
        }
        next.addAll(finalFresh);
        changes = next;
        showTitle(repos);
        rebuildTree();
        showSummary(next, SyncBundle.message("sync.repo.checked.tail", name));
      });
    });
  }

  /** 새로고침이 끝났다. 도는 동안 매핑이 바뀌었으면 이제 한 번 새로고침한다. EDT 에서 부른다. */
  private void endBusy() {
    busy = false;
    if (refreshAgain) {
      refreshAgain = false;
      refresh();
    }
  }

  /** 원격을 받아 와 다시 비교한다. 원격 실패는 errors 에 적고 지금 상태 비교 결과를 돌려준다. */
  private List<Change> remoteCompare(Repo repo, List<String> errors) throws IOException, InterruptedException {
    try {
      if (repo.vcs() == Vcs.GIT) {
        fetch(repo.root());
        updateReverseNote(repo, true);
        return compare(repo);
      }
      return compareSvnRemote(repo);
    }
    catch (IOException e) {
      errors.add(e.getMessage());
      if (repo.vcs() == Vcs.GIT) return compare(repo);
      svnIncoming.remove(repo.root());
      return compareSvn(repo, false);
    }
  }

  /** git fetch. 도는 동안 그 루트를 fetching 에 두어 fetch 가 낸 저장소 알림으로 다시 비교하지 않게 한다. */
  private void fetch(File root) throws IOException, InterruptedException {
    fetching.add(root);
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "fetch", "--quiet");
    }
    finally {
      fetching.remove(root);
    }
  }

  /**
   * 역머지 기준 브랜치(Git 메뉴 Merge Branches 에서 고른 From)가 있으면 지금 브랜치와의 차이를 reverseNotes 에 남긴다.
   * fetch 면 그 브랜치만 먼저 받아 오고, 아니면 마지막으로 받아 둔 것으로 센다.
   * 실패해도(서버에서 지워짐 · 아직 받은 적 없음 등) 동기화 비교는 계속하고 안내만 지운다.
   */
  private void updateReverseNote(Repo repo, boolean fetch) throws InterruptedException {
    File root = repo.root();
    // 설정은 git 한 번으로 다 읽는다(From · 원격 이름 · 내 이름 · 이메일).
    Map<String, String> cfg = ReverseMerge.configs(root);
    String from = cfg.get(ReverseMerge.CONFIG_FROM);
    if (from == null || from.equals(repo.branch())) {
      reverseNotes.remove(root);
      return;
    }
    fetching.add(root);
    try {
      String remote = ReverseMerge.remoteOf(cfg, repo.branch());
      if (fetch) ReverseMerge.fetch(root, remote, from);
      ReverseMerge.Preview preview = ReverseMerge.preview(root, cfg, remote, from, repo.branch(), repo.branch());
      int mine = preview.mineCount();
      if (preview.commits() == 0) reverseNotes.remove(root);
      else reverseNotes.put(root, new ReverseNote(mine > 0 ? SyncBundle.message("rmerge.note.mine", from, preview.commits(), mine)
                                                           : SyncBundle.message("rmerge.note", from, preview.commits()), mine > 0));
    }
    catch (IOException e) {
      reverseNotes.remove(root);
    }
    finally {
      fetching.remove(root);
    }
  }

  /** 역머지 안내가 있으면 component 끝에 붙인다. */
  private void appendReverseNote(SimpleColoredComponent component, Repo repo) {
    ReverseNote note = reverseNotes.get(repo.root());
    if (note == null) return;
    component.append("   " + note.text(), note.warn()
                                         ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ReverseMergeAction.MINE_COLOR)
                                         : SimpleTextAttributes.GRAYED_ATTRIBUTES);
  }

  /** 서버에 물어 SVN 저장소를 비교하고, 받은 들어올 변경을 svnIncoming 에 남긴다. 서버에 닿지 못하면 IOException(남긴 것은 그대로). */
  private List<Change> compareSvnRemote(Repo repo) throws IOException, InterruptedException {
    Map<String, String> incoming = new LinkedHashMap<>();
    List<Change> result = compareSvn(repo, true, incoming);
    svnIncoming.put(repo.root(), incoming);
    return result;
  }

  /** 서버에 묻지 않고 SVN 작업본만 다시 비교한다. 들어올 변경은 마지막 서버 확인 결과(svnIncoming)로 가르고, 없으면 작업본 변경만 나온다. */
  private List<Change> compareSvnLocal(Repo repo) throws IOException, InterruptedException {
    return compareSvn(repo, false, new LinkedHashMap<>(svnIncoming.getOrDefault(repo.root(), Map.of())));
  }

  /** Git 루트의 실제 최상위 폴더 · 브랜치 · 원격 추적 브랜치를 알아낸다. 추적 브랜치가 없으면 비교할 수 없으므로 오류. */
  static Repo gitRepo(File dir) throws IOException, InterruptedException {
    // 보통은 한 번에 읽는다(최상위 폴더 · 브랜치 · 추적 브랜치 세 줄). 실패하면 어느 것이 없는지 가리려고 하나씩 읽는다.
    try {
      String[] lines = git(dir, "rev-parse", "--show-toplevel", "--abbrev-ref", "HEAD", "@{u}").trim().split("\n");
      if (lines.length == 3) return new Repo(new File(lines[0].trim()), Vcs.GIT, lines[1].trim(), lines[2].trim());
    }
    catch (IOException oneShotFailed) {
      // 아래에서 하나씩 다시 읽어 알맞은 오류를 낸다.
    }
    File root = new File(git(dir, "rev-parse", "--show-toplevel").trim());
    String branch = git(root, "rev-parse", "--abbrev-ref", "HEAD").trim();
    try {
      return new Repo(root, Vcs.GIT, branch, git(root, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}").trim());
    }
    catch (IOException noUpstream) {
      throw new IOException(SyncBundle.message("sync.no.upstream", branch));
    }
  }

  /** SVN 작업본의 리비전은 서버 없이 svn info 로 읽는다(표시용). */
  static Repo svnRepo(File root) throws IOException, InterruptedException {
    return new Repo(root, Vcs.SVN, null, run(root, "svn", "info", "--show-item", "revision").trim());
  }

  /** 들어올 · 나갈 · 충돌로 나눈다. 같은 경로가 양쪽에 있으면 충돌로만 보인다. */
  static List<Change> compare(Repo repo) throws IOException, InterruptedException {
    return compare(repo, true);
  }

  /** assumed 가 거짓이면 git ls-files -v 를 건너뛰어 모른 척 중(ASSUMED) 항목 없이 돌려준다. 그 항목은 부르는 쪽이 지난 결과의 것을 쓴다. */
  static List<Change> compare(Repo repo, boolean assumed) throws IOException, InterruptedException {
    File root = repo.root();
    // 명령들은 서로 기다릴 필요가 없어 동시에 돌린다. 윈도우는 git 을 띄우는 비용이 커서 차례로 돌리면 그만큼 늦다.
    List<String[]> commands = new ArrayList<>(List.of(
      new String[]{"diff", "--name-status", "HEAD...@{u}"},
      new String[]{"diff", "--name-status", "@{u}...HEAD"},
      // --no-optional-locks: 저장할 때마다 도는 비교가 index.lock 을 잡아 IntelliJ 의 커밋 · 체크아웃을 막지 않게 한다.
      new String[]{"--no-optional-locks", "-c", "core.untrackedCache=true", "status", "--porcelain=v1", "-unormal"}));
    if (assumed) commands.add(new String[]{"ls-files", "-v"});
    List<String> out = inParallel(commands, args -> git(root, args));
    Map<String, String> incoming = parseNameStatus(out.get(0));
    Map<String, String> outgoing = parseNameStatus(out.get(1));
    Set<String> unmerged = new TreeSet<>();
    // 새 파일은 폴더 단위로만 받는다(-unormal). 무시되지 않은 큰 폴더 속을 다 훑으면 느려진다.
    // 폴더는 ".idea/" 처럼 끝이 / 인 한 줄로 나오며, 그대로 .gitignore 에 넣을 수 있다.
    for (String line : out.get(2).split("\n")) {
      if (line.length() < 4) continue;
      String xy = line.substring(0, 2);
      String path = line.substring(3);
      int arrow = path.indexOf(" -> ");
      if (arrow >= 0) path = path.substring(arrow + 4);
      path = unquote(path);
      // 저장소 안의 다른 저장소(회사 supp-backend 안의 basic 등)는 바깥 저장소의 새 파일이 아니다. 무시 목록에 넣으면 팀 .gitignore 가 바뀐다.
      if (UNTRACKED_STATUS.contains(xy) && isNestedRepo(new File(root, path))) continue;
      // 작업본 상태가 커밋된 상태보다 우선한다. 첫 글자(HEAD ↔ 색인)가 A · R · C 인지로 되돌리기 대상을 가르기 때문이다(undoOf).
      if (UNMERGED.contains(xy)) unmerged.add(path);
      else outgoing.put(path, xy.trim().isEmpty() ? "M" : xy.trim());
    }
    List<Change> result = merge(repo, incoming, outgoing, unmerged);
    if (!assumed) return result;
    // 부분 체크아웃(sparse checkout)이 밖으로 뺀 파일도 skip-worktree 지만 디스크에 없으므로, 디스크에 있는 것만 모른 척 중으로 본다.
    for (String path : parseAssumed(out.get(3), p -> new File(root, p).exists())) {
      result.add(new Change(Kind.ASSUMED, "Assume Unchanged", path, repo));
    }
    return result;
  }

  /**
   * git ls-files -v 출력에서 모른 척 중인 파일 경로를 꺼낸다. 첫 글자가 소문자면 assume-unchanged, 'S' 면 skip-worktree 다.
   * 'S' 는 부분 체크아웃에서도 붙으므로 onDisk 가 참인 경로만 넣는다. 대문자 H 등은 보통 파일.
   */
  static List<String> parseAssumed(String output, java.util.function.Predicate<String> onDisk) {
    List<String> result = new ArrayList<>();
    for (String line : output.split("\n")) {
      if (line.length() < 3 || line.charAt(1) != ' ') continue;
      char tag = line.charAt(0);
      String path = unquote(line.substring(2));
      if (Character.isLowerCase(tag) || (tag == SKIP_WORKTREE_TAG && onDisk.test(path))) result.add(path);
    }
    return result;
  }

  /**
   * SVN 은 svn status --xml 한 번으로 작업본(wc-status)과 서버(repos-status, -u 일 때만) 상태를 같이 받는다.
   * remote 가 true 면 서버에 물어보므로 네트워크가 필요하고, 실패하면 IOException 이다.
   */
  static List<Change> compareSvn(Repo repo, boolean remote) throws IOException, InterruptedException {
    return compareSvn(repo, remote, new LinkedHashMap<>());
  }

  /** incoming 에 미리 넣어 둔 서버 쪽 변경도 들어올 변경으로 보고, 이번에 받은 서버 쪽 변경은 incoming 에 더한다. */
  static List<Change> compareSvn(Repo repo, boolean remote, Map<String, String> incoming) throws IOException, InterruptedException {
    String xml = remote ? run(repo.root(), "svn", "status", "-u", "--xml") : run(repo.root(), "svn", "status", "--xml");
    return parseSvnStatus(repo, xml, incoming);
  }

  /**
   * svn status --xml 을 들어올 · 나갈 · 충돌로 나눈다.
   * entry 마다 wc-status(작업본) 와, -u 로 받았을 때만 repos-status(서버) 가 있다.
   * 서버 쪽이 바뀌면 들어올 변경, 작업본이 바뀌면 나갈 변경, 둘 다거나 작업본이 conflicted 면 충돌.
   * 경로는 루트 기준 상대 경로로 바꾸고 구분자는 / 로 통일한다(윈도우 svn 은 \ 를 준다).
   * incoming 은 미리 들어 있는 것(지난 서버 확인 결과)에 이번 서버 쪽 변경을 더해 쓴다.
   */
  static List<Change> parseSvnStatus(Repo repo, String xml, Map<String, String> incoming) throws IOException {
    Map<String, String> outgoing = new LinkedHashMap<>();
    Set<String> unmerged = new TreeSet<>();
    NodeList entries = xmlElements(xml, "entry", "svn status");
    for (int i = 0; i < entries.getLength(); i++) {
      Element entry = (Element)entries.item(i);
      String path = entry.getAttribute("path").replace('\\', '/');
      if (path.startsWith("./")) path = path.substring(2);
      if (path.isEmpty() || path.equals(".")) continue;
      String local = childItem(entry, "wc-status");
      if (SVN_ITEM_UNVERSIONED.equals(local) && isNestedRepo(new File(repo.root(), path))) continue;
      String server = childItem(entry, "repos-status");
      if (SVN_ITEM_CONFLICTED.equals(local)) unmerged.add(path);
      else if (SVN_LOCAL_CHANGED.contains(local)) outgoing.put(path, svnStatusLetter(local));
      if (SVN_CHANGED.contains(server)) incoming.put(path, svnStatusLetter(server));
    }
    return merge(repo, incoming, outgoing, unmerged);
  }

  /** svn --xml 출력에서 tag 요소를 모두 찾는다. 읽지 못하면 어느 명령의 결과인지(what) 담아 IOException. */
  private static NodeList xmlElements(String xml, String tag, String what) throws IOException {
    try {
      javax.xml.parsers.DocumentBuilder builder = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder();
      builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());  // 기본 처리기는 표준 오류에 따로 찍는다. 이 처리기는 예외만 던진다.
      return builder.parse(new InputSource(new StringReader(xml))).getElementsByTagName(tag);
    }
    catch (Exception e) {
      throw new IOException(what + " 결과를 읽지 못했습니다: " + e.getMessage());
    }
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
      if (outgoing.containsKey(path) || unmerged.contains(path)) result.add(new Change(Kind.CONFLICT, SyncBundle.message("sync.status.conflict"), path, repo));
      else result.add(new Change(Kind.INCOMING, incoming.get(path), path, repo));
    }
    for (String path : unmerged) {
      if (!incoming.containsKey(path)) result.add(new Change(Kind.CONFLICT, SyncBundle.message("sync.status.unresolved"), path, repo));
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

  /** 폴더가 그 자체로 Git · SVN 저장소의 맨 위인가. */
  static boolean isNestedRepo(File dir) {
    return new File(dir, ".git").exists() || new File(dir, ".svn").isDirectory();
  }

  /**
   * git 이 큰따옴표로 감싼 경로를 푼다(따옴표 · 역슬래시 · 제어 문자가 든 이름). \" \\ \t \n 과 \세자리8진수(바이트)를 되돌린다.
   * core.quotepath=false 라 한글은 감싸지 않지만, 감쌀 때는 8진수 바이트가 UTF-8 로 나오므로 바이트로 모아 UTF-8 로 읽는다.
   */
  static String unquote(String path) {
    if (path.length() < 2 || !path.startsWith("\"") || !path.endsWith("\"")) return path;
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    for (int i = 1; i < path.length() - 1; i++) {
      char c = path.charAt(i);
      if (c != '\\' || i + 1 >= path.length() - 1) {
        bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        continue;
      }
      char next = path.charAt(++i);
      if (next >= '0' && next <= '7' && i + 2 < path.length() - 1) {
        bytes.write(Integer.parseInt(path.substring(i, i + 3), 8));
        i += 2;
      }
      else bytes.write(switch (next) {
        case 't' -> '\t';
        case 'n' -> '\n';
        case 'r' -> '\r';
        default -> next;  // \" · \\
      });
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  static String summary(List<Change> changes) {
    Map<Kind, Integer> count = new EnumMap<>(Kind.class);
    for (Change c : changes) count.merge(c.kind(), 1, Integer::sum);
    int assumed = count.getOrDefault(Kind.ASSUMED, 0);
    String tail = assumed > 0 ? " · Assume Unchanged " + assumed : "";
    if (changes.size() == assumed) return "원격과 같습니다" + tail;
    return "들어올 " + count.getOrDefault(Kind.INCOMING, 0) + " · 나갈 " + count.getOrDefault(Kind.OUTGOING, 0)
           + " · 충돌 " + count.getOrDefault(Kind.CONFLICT, 0) + tail;
  }

  /** 저장소가 하나면 종류별 묶음만, 둘 이상이면 저장소 → 종류별 묶음으로 보인다. */
  private void rebuildTree() {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    // 저장소가 하나여도 저장소 줄을 두어 파일이 어느 저장소 것인지 보이게 한다.
    // 하나뿐인 저장소에 변경이 없으면 빈 트리로 두어 「변경 없음」 안내가 보이게 한다.
    for (Repo repo : repos) {
      DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode(repo);
      addKindGroups(repoNode, changes.stream().filter(c -> c.repo().equals(repo)).toList());
      if (repos.size() == 1 && repoNode.getChildCount() == 0) continue;
      root.add(repoNode);
    }
    tree.setModel(new DefaultTreeModel(root));
    TreeUtil.expandAll(tree);
    // 목록을 새로 만들면 고른 파일이 사라지므로, 지난 비교 내용이 남지 않게 비교 화면도 비운다.
    diffPanel.setRequest(new MessageDiffRequest(SyncBundle.message("sync.diff.empty")));
  }

  private void addKindGroups(DefaultMutableTreeNode parent, List<Change> source) {
    for (Kind kind : Kind.values()) {
      if (modeFilter != null && modeFilter != kind) continue;
      List<Change> ofKind = source.stream().filter(c -> c.kind() == kind).toList();
      if (ofKind.isEmpty()) continue;
      DefaultMutableTreeNode group = new DefaultMutableTreeNode(new Group(kind, ofKind.size()));
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
      byte[] remote;
      try {
        remote = repo.vcs() == Vcs.GIT
                 ? gitBytes(repo.root(), "show", repo.upstream() + ":" + change.path())
                 : runBytes(repo.root(), CMD_TIMEOUT_SEC, "svn", "cat", "-r", "HEAD", change.path());
      }
      catch (IOException e) {
        remote = new byte[0];  // 원격에 없는 파일(새로 추가했거나 원격에서 지운 파일)
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      File local = new File(repo.root(), change.path());
      byte[] mine;
      try {
        mine = local.isFile() ? Files.readAllBytes(local.toPath()) : new byte[0];
      }
      catch (IOException e) {
        mine = new byte[0];
      }
      byte[] remoteBytes = remote;
      byte[] mineBytes = mine;
      SwingUtilities.invokeLater(() -> {
        // 바이트로 넘겨 IDE 가 파일 인코딩(프로젝트 설정 · BOM)으로 읽게 한다. MS949 파일의 한글이 깨지지 않는다.
        FilePath path = VcsUtil.getFilePath(local, false);
        DiffContentFactory factory = DiffContentFactory.getInstance();
        try {
          diffPanel.setRequest(new SimpleDiffRequest(change.path(),
                                                     factory.createFromBytes(project, remoteBytes, path),
                                                     factory.createFromBytes(project, mineBytes, path),
                                                     SyncBundle.message("sync.diff.remote", repo.remoteName()), SyncBundle.message("sync.diff.local")));
        }
        catch (IOException e) {
          diffPanel.setRequest(new MessageDiffRequest(e.getMessage()));
        }
      });
    });
  }

  /** git 을 실행한다. 경로는 항상 따옴표 없이 UTF-8 그대로 받는다. */
  static String git(File dir, String... args) throws IOException, InterruptedException {
    return git(dir, CMD_TIMEOUT_SEC, args);
  }

  /** git 과 같고 시간 제한(초)만 따로 준다. 서버 · 작업본을 바꾸는 명령은 GIT_LONG_TIMEOUT_SEC 를 쓴다. */
  static String git(File dir, long timeoutSec, String... args) throws IOException, InterruptedException {
    return run(dir, timeoutSec, GIT, gitArgs(args));
  }

  /** git 과 같고 표준 출력을 바이트 그대로 돌려준다. */
  static byte[] gitBytes(File dir, String... args) throws IOException, InterruptedException {
    return runBytes(dir, CMD_TIMEOUT_SEC, GIT, gitArgs(args));
  }

  private static String[] gitArgs(String... args) {
    List<String> full = new ArrayList<>(List.of("-c", "core.quotepath=false"));
    full.addAll(List.of(args));
    return full.toArray(String[]::new);
  }

  /** 설치본에 같이 넣은 svn.exe 가 있으면 그 경로, 없으면 PATH 에서 찾을 「svn」. */
  static String svnExecutable() {
    File bundled = new File(PathManager.getHomePath(), BUNDLED_SVN);
    return bundled.isFile() ? bundled.getPath() : VCS_NAME_SVN;
  }

  /**
   * 명령을 실행해 표준 출력(UTF-8)을 돌려준다. 0 이 아닌 종료 코드 · 시간 초과는 오류 메시지를 담은 IOException 으로 바꾼다.
   * 출력 · 오류를 모두 파일로 받아 버퍼가 차서 멈추지 않게 하고, 그래서 waitFor 의 시간 제한이 실제로 걸린다.
   * 시간 초과 · 중단(interrupt) 때는 프로세스와 그 자식(git 의 원격 연결 등)을 강제로 끝낸다.
   * 오류 출력은 git 은 UTF-8, svn 은 운영체제 문자셋으로 읽는다. svn 의 표준 출력이 필요한 곳은 --xml(UTF-8)로 받는다.
   */
  static String run(File dir, String exe, String... args) throws IOException, InterruptedException {
    return run(dir, CMD_TIMEOUT_SEC, exe, args);
  }

  /** run 과 같고 시간 제한(초)만 따로 준다. */
  static String run(File dir, long timeoutSec, String exe, String... args) throws IOException, InterruptedException {
    // 깨진 바이트가 있어도(이진 파일 · 다른 인코딩) 예외 대신 대체 문자로 읽는다.
    return new String(runBytes(dir, timeoutSec, exe, args), StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  /**
   * run 과 같고 표준 출력을 바이트 그대로 돌려준다(비교 창이 파일 인코딩으로 읽게).
   * 표준 입력은 바로 닫아, 자격 증명을 묻는 git 이 입력을 기다리며 시간 제한까지 멈추지 않고 곧바로 실패하게 한다.
   */
  static byte[] runBytes(File dir, long timeoutSec, String exe, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of(VCS_NAME_SVN.equals(exe) ? svnExecutable() : exe));
    command.addAll(List.of(args));
    String label = exe + " " + verb(args);
    File outFile = File.createTempFile("kinggura-sync", ".out");
    File errFile = File.createTempFile("kinggura-sync", ".err");
    long started = System.currentTimeMillis();
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(dir).redirectOutput(outFile).redirectError(errFile);
      // 설정 → Git 로그인에 넣은 서버는 git 이 비밀번호 도우미를 띄우지 않게 인증을 미리 넘긴다.
      if (GIT.equals(exe)) builder.environment().putAll(GitLogins.env(builder.environment()));
      Process process = builder.start();
      process.getOutputStream().close();
      try {
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
          kill(process);
          throw new IOException(label + " 이(가) " + timeoutSec + "초 안에 끝나지 않아 멈췄습니다.");
        }
      }
      catch (InterruptedException e) {
        kill(process);
        throw e;
      }
      if (TIME_LOG.isDebugEnabled()) {
        TIME_LOG.debug("[" + dir.getName() + "] " + label + " " + (System.currentTimeMillis() - started) + " ms: " + String.join(" ", command));
      }
      if (process.exitValue() != 0) {
        Charset errCharset = "svn".equals(exe) ? NATIVE_CHARSET : StandardCharsets.UTF_8;
        String err = new String(Files.readAllBytes(errFile.toPath()), errCharset).trim();
        String hint = GIT.equals(exe) && containsAny(err, GIT_AUTH_ERRORS) ? "\n" + SyncBundle.message("sync.git.auth") : "";
        throw new IOException(label + " 실패: " + (err.isEmpty() ? "종료 코드 " + process.exitValue() : err) + hint);
      }
      return Files.readAllBytes(outFile.toPath());
    }
    finally {
      // 강제로 끝낸 프로세스가 윈도우에서 아직 파일을 쥐고 있을 수 있다. 원래 예외를 덮지 않게 지우기 실패는 IDE 종료 때로 미룬다.
      if (!outFile.delete()) outFile.deleteOnExit();
      if (!errFile.delete()) errFile.deleteOnExit();
    }
  }

  private static boolean containsAny(String text, List<String> parts) {
    for (String part : parts) if (text.contains(part)) return true;
    return false;
  }

  private static void kill(Process process) {
    for (ProcessHandle child : process.descendants().toList()) child.destroyForcibly();
    process.destroyForcibly();
  }

  /** 오류 메시지에 쓸 하위 명령 이름. git 의 "-c 키=값" · "--no-optional-locks" 같은 앞 옵션은 건너뛴다. */
  private static String verb(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-c")) i++;
      else if (!args[i].startsWith("-")) return args[i];
    }
    return "";
  }
}
