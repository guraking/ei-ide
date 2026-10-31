package kinggura.sync;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.MessageDiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.ui.PopupHandler;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.RowIcon;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.table.JBTable;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * 아래쪽 「Git Log」 창. 왼쪽 저장소(모듈) 목록에서 고르면 그 폴더 안을 바꾼 최근 커밋을 표로 보여 준다.
 * 다른 사람이 내 소스를(또는 내가 남의 소스를) 되돌리거나 합치며 버리거나 이어 고친 커밋은 메시지 앞 꼬리표와 오른쪽 「… 소스를 건드린 파일」로 알린다(History.clashes).
 * 표 칸은 가지 그래프(가지마다 색이 다른 꺾인 선 위에 커밋 점, 합치기는 속이 빈 점) · 메시지와 브랜치 · 태그 알약 · 작성자 · 날짜 · 짧은 해시.
 * 오른쪽은 고른 커밋의 메시지 · 작성자 · 날짜 · 해시 · 꺾인 선 설명과 바뀐 파일(두 번 누르면 비교 창).
 * 맨 아래까지 내리면 다음 LOG_LIMIT 개를 읽어 밑에 붙인다. 찾기로 거르면 사이가 빠지므로 가지 선 대신 한 줄로 그린다. 기존 Git 로그 창은 그대로 둔다.
 * Git 은 git log, SVN 은 svn log 를 작업 스레드에서 돌린다(SyncPanel.run 과 같은 실행 · 시간 제한). SVN 은 한 줄 기록이다.
 */
final class ProjectHistoryPanel extends JPanel {
  static final String ID = "ProjectHistory";
  // 한 번에 읽는 커밋 수. 목록을 맨 아래까지 내리면 다음 묶음을 읽어 밑에 붙인다.
  private static final int LOG_LIMIT = 200;
  private static final ZoneId ZONE = ZoneId.systemDefault();
  private static final Locale TIME_LOCALE = Locale.forLanguageTag(SyncBundle.message("history.locale"));
  private static final DateTimeFormatter TODAY = timeFormat("history.time.today");
  private static final DateTimeFormatter THIS_YEAR = timeFormat("history.time.year");
  private static final DateTimeFormatter OLDER = timeFormat("history.time.older");
  private static final DateTimeFormatter FULL = timeFormat("history.time.full");
  private static final DateTimeFormatter ROW_TIME = timeFormat("history.time.row");
  private static final int MINUTES_PER_HOUR = 60;
  private static final int SHORT_HASH = 7;
  // 비교 창 내용(git show · svn cat)을 읽는 시간 제한(초). SyncPanel.git 의 기본 제한과 같다.
  private static final long CAT_TIMEOUT_SEC = 120;
  // rev-parse --abbrev-ref 가 브랜치 대신 돌려주는 값(떨어진 HEAD). 이때는 짧은 해시를 보인다.
  private static final String DETACHED = "HEAD";
  // 상태 글자: 추가는 커밋 직전 내용이, 삭제는 커밋 내용이 없다.
  private static final String ADDED = "A";
  private static final String DELETED = "D";
  // 꺾임 설명에 넣는 커밋 제목 최대 글자 수.
  private static final int TIP_SUBJECT_MAX = 60;

  // 크기(JBUI.scale 전). 표 줄 · 가지 칸 · 점은 IntelliJ 기본 Log 탭 비율을 따른다.
  private static final int ROW_H = 26;
  private static final int LANE_W = 16;
  private static final int GRAPH_PAD = 8;
  private static final int MAX_LANES = 12;
  private static final int DOT = 7;
  // 표의 작성자 · 날짜 · 해시 칸 폭(JBUI.scale 전). 메시지 칸이 나머지를 차지한다.
  private static final int AUTHOR_COL_W = 72;
  private static final int TIME_COL_W = 90;
  private static final int HASH_COL_W = 72;
  // 바뀐 파일 줄 앞 상태 글자 칸의 최소 폭(JBUI.scale 전).
  private static final int STATUS_COL_W = 16;
  // 현재 브랜치 알약 안 브랜치 아이콘 크기(16 픽셀 아이콘 대비).
  private static final float PILL_ICON_SCALE = 0.75f;
  private static final int PILL_H = 18;
  private static final int GAP = 8;
  // 찾기 칸 폭(px). 2026-10-29 사용자 요청으로 세 배쯤 넓혔다.
  private static final int SEARCH_W = 400;
  private static final String TOOLBAR_PLACE = "KingGuraGitLogToolbar";
  private static final String POPUP_PLACE = "KingGuraGitLogPopup";
  // 「나로 보기」로 더한 다른 계정(History.Me.aliasKey, 줄마다 하나). 앱 수준이라 모든 프로젝트 · 저장소에 같이 쓴다.
  private static final String ME_ALIASES_KEY = "kinggura.sync.gitlog.me.aliases";
  private static final float LINE_WIDTH = 1.5f;
  // 표와 오른쪽 상세 칸의 폭 비율, 왼쪽 저장소 목록과 나머지의 폭 비율.
  private static final float TABLE_RATIO = 0.62f;
  private static final float REPO_RATIO = 0.15f;
  // 소스 건드림 꼬리표 색: 되돌림은 빨강, 합치며 덮어씀은 주황, 이어 고침은 보라(브랜치 알약의 초록 · 회색 · 황토 · 파랑과 겹치지 않게). 내 소스 · 남의 소스 모두 같은 색.
  private static final Map<History.ClashKind, Color> CLASH_COLOR = Map.of(
    History.ClashKind.REVERTED, new JBColor(0xC03030, 0xF07070),
    History.ClashKind.OVERWRITTEN, new JBColor(0xC0612B, 0xF0A060),
    History.ClashKind.EDITED, new JBColor(0x7A5BB5, 0xB49AF0));

  // 표 칸 번호.
  private static final int COL_GRAPH = 0;
  private static final int COL_MESSAGE = 1;
  private static final int COL_AUTHOR = 2;
  private static final int COL_TIME = 3;
  private static final int COL_HASH = 4;
  private static final String[] COLUMN_KEYS = {"", "history.col.message", "history.col.author", "history.col.date", "history.col.hash"};

  // 색. 가지 선은 테마 밝기에 맞춘 고정 팔레트, 알약은 글자색 + 옅은 바탕 · 테두리.
  // 이웃한 가지끼리 비슷한 색이 붙지 않도록 파랑 · 초록 · 주황 · 보라 · 청록 … 순으로 둔다. 밝은 테마는 흰 바탕에서 읽히는 진한 색,
  // 어두운 테마는 같은 색상을 밝게 한 색(EI White · EI Graphite 의 코드 색과 같은 계열).
  private static final Color[] LIGHT_PALETTE = {
    new Color(0x3B6FB6), new Color(0x2F8F5B), new Color(0xC0612B), new Color(0x7A5BB5), new Color(0x2A8C8C),
    new Color(0xA8862A), new Color(0xB0475E), new Color(0x4E7FA8), new Color(0x6B8E23), new Color(0x8A6A4F)};
  private static final Color[] DARK_PALETTE = {
    new Color(0x6FA8FF), new Color(0x5FCF8F), new Color(0xF0A060), new Color(0xB49AF0), new Color(0x5FD3D3),
    new Color(0xE0C060), new Color(0xE88A9C), new Color(0x8FB8E0), new Color(0xA8C860), new Color(0xC8A080)};
  // 알약 글자색. 밝은 테마(첫 값)에서는 흰 바탕에서 읽히도록 어둡게, 어두운 테마(둘째 값)에서는 밝게.
  // IntelliJ Log 탭의 테마 키(VersionControl.GitLog.*)는 따르지 않는다. 밝은 테마 기본값이 노랑 · 보라라 EI White 와 어울리지 않는다.
  // 현재 브랜치(HEAD)는 테마 강조 파랑 + 브랜치 아이콘, 로컬 브랜치는 초록, 원격(origin/*)은 회색, 태그는 황토.
  static final Color HEAD_COLOR = new JBColor(0x1A5FD0, 0x6FA8FF);
  private static final Color LOCAL_COLOR = new JBColor(0x2F8F5B, 0x5FCF8F);
  private static final Color REMOTE_COLOR = new JBColor(0x6B6B6B, 0xA0A0A0);
  private static final Color TAG_COLOR = new JBColor(0x9A6B00, 0xE0C060);
  private static final int PILL_FILL_ALPHA = 0x22;
  private static final int PILL_BORDER_ALPHA = 0x66;
  private static final String TAG_PREFIX = "tag: ";
  // 바뀐 파일 이름 색은 IDE 의 파일 상태 색(테마를 따름). 이름 바꿈(R)은 IntelliJ 변경 목록처럼 수정 색.
  private static final Map<String, FileStatus> FILE_STATUS = Map.of(
    "M", FileStatus.MODIFIED, "A", FileStatus.ADDED, "D", FileStatus.DELETED, "R", FileStatus.MODIFIED);
  private static final Color DIM = SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor();

  /** 왼쪽 저장소 목록 한 줄. dir 은 모듈의 첫 콘텐츠 루트, branch 는 Git 현재 브랜치(History.gitHead, SVN · 못 읽으면 빈 글). */
  private record Target(String name, File dir, SyncPanel.Vcs vcs, String branch) {
    @Override public String toString() { return name; }
  }

  /**
   * 읽어 온 기록. branch 는 Git 현재 브랜치(떨어진 HEAD 면 짧은 해시) · SVN 저장소 경로. svnRoot · svnPrefix 는 SVN 비교 창용. more 는 다음 묶음이 남았을 수 있음.
   * start 는 첫 묶음 때의 HEAD 해시(Git, 다음 묶음도 여기서 센다), remotes 는 원격 이름(원격 브랜치 알약 색을 고른다). SVN 은 null · 빈 목록.
   * me 는 이 저장소의 나, sides 는 다른 사람 합치기 커밋의 부모별 바뀐 파일, clashes 는 내 소스를 건드린 커밋(History.clashes). SVN 은 NONE · 빈 맵.
   */
  private record Loaded(Target target, String branch, String svnRoot, String svnPrefix, String start, List<String> remotes,
                        List<History.Entry> entries, List<History.Row> rows, boolean more,
                        History.Me me, Map<String, List<List<History.FileChange>>> sides, Map<String, List<History.Clash>> clashes) {}

  private final Project project;
  private final JBList<Target> targets = new JBList<>();
  // 켜면 내 소스 · 남의 소스를 건드린 커밋만 보인다. Git 저장소에서만 보인다.
  private final JBCheckBox onlyMine = new JBCheckBox();
  // 표에 보이는 커밋(찾기로 거른 뒤). 가지 선을 그릴 때는 loaded.entries() 와 순서 · 개수가 같다.
  private final List<History.Entry> shown = new ArrayList<>();
  private final CommitModel commitModel = new CommitModel();
  // 풍선 도움말: 작성자와 긴 날짜(표에는 짧은 시간만 보인다).
  private final JBTable commits = new JBTable(commitModel) {
    @Override
    public String getToolTipText(MouseEvent e) {
      int i = rowAtPoint(e.getPoint());
      if (i < 0 || i >= shown.size()) return null;
      History.Entry entry = shown.get(i);
      return entry.author() + " · " + stamp(ROW_TIME, entry.time());
    }
  };
  private final JBList<History.FileChange> files = new JBList<>();
  private final SearchTextField filter = new SearchTextField(false);
  private final Pill branch = new Pill();
  private final JLabel status = new JLabel(" ");
  // 읽는 동안 상태 글 자리에 보이는 진행 막대와 글.
  private final Gauge gauge = new Gauge(SyncBundle.message("history.loading"));
  private final JBLabel subject = new JBLabel();
  private final JTextArea message = new JTextArea();
  // 흐린 「작성자 · 날짜 · 」 뒤에 해시(누르면 복사).
  private final JBLabel meta = new JBLabel();
  private final ActionLink hash = new ActionLink();
  private final JComponent metaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
  // 고른 커밋의 꺾인 선이 어느 커밋과 이어지는지. 긴 글은 「…」로 잘리고 전체는 풍선 도움말.
  private final JBLabel bend = new JBLabel();
  private final TitledSeparator filesTitle = new TitledSeparator();
  // 고른 커밋이 내 소스(또는 남의 소스)를 건드린 파일. 없으면 제목과 함께 숨긴다. 두 번 누르면 바뀌기 전 판 ↔ 그 커밋 판 비교.
  private final TitledSeparator clashTitle = new TitledSeparator();
  private final JBList<History.Clash> clashList = new JBList<>();
  private final Font hashFont = new Font(EditorColorsManager.getInstance().getGlobalScheme().getEditorFontName(), Font.PLAIN, JBUI.Fonts.label().getSize());
  private Loaded loaded;
  private boolean loadingMore;
  // 마지막으로 시작한 load 번호와 화면에 반영한 번호. 새로고침을 연달아 누르면 마지막 결과만 반영하고 그것만 진행 줄을 숨긴다.
  private int loadSeq;
  private int shownSeq;
  private boolean graph;
  private int graphWidth;

  ProjectHistoryPanel(Project project) {
    super(new BorderLayout());
    this.project = project;

    targets.setCellRenderer(new TargetRenderer());
    targets.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    targets.getEmptyText().setText(SyncBundle.message("history.empty.targets"));
    targets.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) load(); });
    onlyMine.setText(SyncBundle.message("history.mine.only"));
    onlyMine.setToolTipText(SyncBundle.message("history.mine.only.tip"));
    onlyMine.setVisible(false);
    onlyMine.addActionListener(e -> fillCommits());
    clashList.setCellRenderer(new ClashRenderer());
    clashList.addMouseListener(new MouseAdapter() {
      @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) showClashDiff(); }
    });
    commits.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    commits.setRowHeight(JBUI.scale(ROW_H));
    commits.setShowGrid(false);
    commits.setIntercellSpacing(new Dimension(0, 0));
    commits.getTableHeader().setReorderingAllowed(false);
    commits.getTableHeader().setForeground(DIM);
    commits.setDefaultRenderer(Object.class, new CommitRenderer());
    fixWidth(commits.getColumnModel().getColumn(COL_AUTHOR), JBUI.scale(AUTHOR_COL_W));
    fixWidth(commits.getColumnModel().getColumn(COL_TIME), JBUI.scale(TIME_COL_W));
    fixWidth(commits.getColumnModel().getColumn(COL_HASH), JBUI.scale(HASH_COL_W));
    commits.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetail(); });
    // 오른쪽 단추로 누른 줄을 먼저 고른다(우클릭 메뉴가 그 줄의 작성자를 쓴다). PopupHandler 보다 먼저 달아야 먼저 불린다.
    commits.addMouseListener(new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) { selectRowAt(e); }
      @Override public void mouseReleased(MouseEvent e) { selectRowAt(e); }
    });
    PopupHandler.installPopupMenu(commits, new DefaultActionGroup(new MeAliasAction()), POPUP_PLACE);
    files.setCellRenderer(new FileRenderer());
    files.addMouseListener(new MouseAdapter() {
      @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) showDiff(); }
    });
    filter.setPreferredSize(new Dimension(JBUI.scale(SEARCH_W), filter.getPreferredSize().height));
    filter.getTextEditor().setFont(JBUI.Fonts.label());
    filter.getTextEditor().getEmptyText().setText(SyncBundle.message("history.search.hint"));
    filter.addDocumentListener(new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) { fillCommits(); }
    });
    hash.addActionListener(e -> {
      History.Entry entry = selectedEntry();
      if (entry != null) CopyPasteManager.getInstance().setContents(new StringSelection(entry.id()));
    });

    // 도구줄: 왼쪽에 현재 브랜치 알약 · 새로고침 · 설명 · 「내 소스 건드린 커밋만」 · 「최근 N개」, 오른쪽 끝에 찾기 칸.
    // 창 머리 아래 선은 IntelliJ 가 그으므로 안쪽 칸에 테두리를 더하면 선이 두 겹이 된다.
    DefaultActionGroup actions = new DefaultActionGroup(
      DumbAwareAction.create(SyncBundle.message("history.refresh"), AllIcons.Actions.Refresh, e -> reloadTargets()),
      DumbAwareAction.create(SyncBundle.message("history.legend.action"), AllIcons.General.ContextHelp, e -> showLegend()));
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true);
    toolbar.setTargetComponent(this);
    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP / 2)));
    left.add(branch);
    left.add(toolbar.getComponent());
    left.add(onlyMine);
    left.add(status);
    status.setForeground(DIM);
    left.add(gauge);
    JPanel top = new JPanel(new BorderLayout());
    top.setBorder(JBUI.Borders.empty(GAP / 2, GAP / 2, 0, GAP));
    top.add(left, BorderLayout.WEST);
    top.add(filter, BorderLayout.EAST);

    JScrollPane commitScroll = ScrollPaneFactory.createScrollPane(commits, true);
    commitScroll.setBorder(JBUI.Borders.empty());
    BoundedRangeModel scroll = commitScroll.getVerticalScrollBar().getModel();
    scroll.addChangeListener(e -> {
      // 목록이 칸보다 길 때만 본다. 찾기로 줄어 칸에 다 들어가면 내릴 수 없으므로 저절로 읽지 않는다.
      boolean overflows = scroll.getMaximum() > scroll.getExtent();
      if (overflows && scroll.getValue() + scroll.getExtent() >= scroll.getMaximum() - JBUI.scale(ROW_H)) loadMore();
    });

    OnePixelSplitter main = new OnePixelSplitter(false, TABLE_RATIO);
    main.setFirstComponent(commitScroll);
    main.setSecondComponent(detailPanel());
    JPanel head = new JPanel(new BorderLayout());
    head.add(top, BorderLayout.CENTER);
    head.add(gauge.line(), BorderLayout.SOUTH);
    JPanel right = new JPanel(new BorderLayout());
    right.add(head, BorderLayout.NORTH);
    right.add(main, BorderLayout.CENTER);
    OnePixelSplitter repos = new OnePixelSplitter(false, REPO_RATIO);
    repos.setFirstComponent(ScrollPaneFactory.createScrollPane(targets, true));
    repos.setSecondComponent(right);
    add(repos, BorderLayout.CENTER);

    reloadTargets();
  }

  private void selectRowAt(MouseEvent e) {
    if (!SwingUtilities.isRightMouseButton(e)) return;
    int row = commits.rowAtPoint(e.getPoint());
    if (row >= 0) commits.setRowSelectionInterval(row, row);
  }

  /** 「나로 보기」로 더한 계정들. */
  private static Set<String> meAliases() {
    Set<String> result = new LinkedHashSet<>();
    for (String line : PropertiesComponent.getInstance().getValue(ME_ALIASES_KEY, "").split("\n")) {
      if (!line.isBlank()) result.add(line.trim());
    }
    return result;
  }

  /**
   * 우클릭 「○○ 도 나로 보기」 · 「○○ 을 나에서 빼기」: 고른 커밋 작성자를 「나」에 더하거나 뺀다(같은 사람의 다른 계정을 서로 남으로 보지 않게).
   * git config 의 나인 작성자 · SVN · 고른 줄 없음에는 보이지 않는다. 바꾸면 기록을 다시 읽어 꼬리표를 새로 찾는다.
   */
  private final class MeAliasAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      History.Entry entry = selectedEntry();
      boolean show = entry != null && loaded != null && loaded.target().vcs() == SyncPanel.Vcs.GIT && !loaded.me().configured(entry);
      e.getPresentation().setEnabledAndVisible(show);
      if (show) {
        boolean alias = meAliases().contains(History.Me.aliasKey(entry));
        e.getPresentation().setText(SyncBundle.message(alias ? "history.me.remove" : "history.me.add", entry.author()));
      }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      History.Entry entry = selectedEntry();
      if (entry == null) return;
      Set<String> aliases = meAliases();
      String key = History.Me.aliasKey(entry);
      if (!aliases.remove(key)) aliases.add(key);
      PropertiesComponent.getInstance().setValue(ME_ALIASES_KEY, String.join("\n", aliases), "");
      load();
    }
  }

  /** 표 칸 폭을 고정한다. 남는 폭은 메시지 칸이 차지한다. */
  private static void fixWidth(TableColumn column, int width) {
    column.setMinWidth(width);
    column.setMaxWidth(width);
    column.setPreferredWidth(width);
  }

  /** 오른쪽: 굵은 제목, 본문, 흐린 작성자 · 날짜 · 고정폭 해시(누르면 복사), 꺾인 선 설명, 「바뀐 파일 N」 구분줄, 파일 목록, 맨 아래 안내. */
  private JComponent detailPanel() {
    subject.setFont(JBUI.Fonts.label().asBold());
    meta.setForeground(DIM);
    hash.setToolTipText(SyncBundle.message("history.copy"));
    hash.setIcon(AllIcons.Actions.Copy);
    hash.setHorizontalTextPosition(SwingConstants.LEFT);
    hash.setFont(hashFont);
    metaRow.add(meta);
    metaRow.add(hash);
    bend.setForeground(DIM);
    message.setEditable(false);
    message.setLineWrap(true);
    message.setWrapStyleWord(true);
    message.setOpaque(false);
    message.setFont(JBUI.Fonts.label());
    message.setBorder(JBUI.Borders.empty());
    JLabel hint = new JLabel(SyncBundle.message("history.hint"));
    hint.setForeground(DIM);
    hint.setBorder(JBUI.Borders.emptyTop(GAP));

    JPanel head = new JPanel();
    head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
    for (JComponent c : List.of(subject, message, metaRow, bend, clashTitle, clashList, filesTitle)) {
      c.setAlignmentX(LEFT_ALIGNMENT);
      head.add(c);
      head.add(Box.createVerticalStrut(JBUI.scale(GAP / 2)));
    }
    JScrollPane fileScroll = ScrollPaneFactory.createScrollPane(files, true);
    fileScroll.setBorder(JBUI.Borders.empty());
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(GAP, GAP + GAP / 2));
    panel.add(head, BorderLayout.NORTH);
    panel.add(fileScroll, BorderLayout.CENTER);
    panel.add(hint, BorderLayout.SOUTH);
    clearDetail();
    return panel;
  }

  /**
   * 저장소 목록을 이름 순으로 다시 채우고 고른 저장소의 기록을 다시 읽는다. 모듈이 없으면 프로젝트 폴더 하나. 같은 폴더를 가리키는 모듈은 한 번만.
   * 고른 것은 폴더로 다시 찾아 그대로 둔다(브랜치가 바뀌어도). 없으면 첫 줄.
   */
  private void reloadTargets() {
    Target selected = selectedTarget();
    Map<File, Target> byDir = new LinkedHashMap<>();
    Module[] modules = ModuleManager.getInstance(project).getModules().clone();  // 원본 배열은 공유될 수 있어 복사본을 정렬한다.
    Arrays.sort(modules, Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
    for (Module module : modules) {
      VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
      if (roots.length == 0) continue;
      File dir = new File(roots[0].getPath());
      byDir.putIfAbsent(dir, target(module.getName(), dir));
    }
    if (byDir.isEmpty() && project.getBasePath() != null) {
      File base = new File(project.getBasePath());
      byDir.put(base, target(project.getName(), base));
    }
    // Gradle 소스 세트 모듈(supp.main · supp.test)처럼 다른 모듈 폴더 안에 있고 같은 저장소인 모듈은 뺀다. 그 안의 다른 저장소(중첩 저장소)는 남긴다.
    List<File> nested = new ArrayList<>();
    for (File dir : byDir.keySet()) {
      if (insideSameRepo(dir, byDir.keySet())) nested.add(dir);
    }
    byDir.keySet().removeAll(nested);
    // setListData 는 고른 줄을 비우고(→ load 는 고른 것이 없어 그냥 끝남), setSelectedIndex 가 다시 고르며 load 를 부른다.
    List<File> dirs = new ArrayList<>(byDir.keySet());
    targets.setListData(byDir.values().toArray(Target[]::new));
    commits.getEmptyText().setText(SyncBundle.message(byDir.isEmpty() ? "history.empty.targets" : "history.empty.commits"));
    targets.setSelectedIndex(selected == null ? 0 : Math.max(0, dirs.indexOf(selected.dir())));
  }

  /** dir 이 dirs 가운데 다른 폴더 안에 있고, 둘의 저장소 폴더(.git · .svn 이 있는 가장 가까운 위 폴더)가 같은가. */
  private static boolean insideSameRepo(File dir, Set<File> dirs) {
    File repo = repoRoot(dir);
    for (File other : dirs) {
      if (!other.equals(dir) && dir.toPath().startsWith(other.toPath()) && Objects.equals(repo, repoRoot(other))) return true;
    }
    return false;
  }

  /** .git(폴더 · 파일) 이나 .svn 이 있는 가장 가까운 위 폴더. 없으면 null. */
  private static File repoRoot(File dir) {
    for (File d = dir.getAbsoluteFile(); d != null; d = d.getParentFile()) {
      if (new File(d, ".git").exists() || new File(d, ".svn").isDirectory()) return d;
    }
    return null;
  }

  private static Target target(String name, File dir) {
    SyncPanel.Vcs vcs = SyncPanel.unmappedVcs(dir);
    return new Target(name, dir, vcs, vcs == SyncPanel.Vcs.GIT ? History.gitHead(dir) : "");
  }

  private Target selectedTarget() {
    return targets.getSelectedValue();
  }

  private History.Entry selectedEntry() {
    int row = commits.getSelectedRow();
    return row < 0 || row >= shown.size() ? null : shown.get(row);
  }

  /** 읽는 동안 상태 글 자리에 진행 막대를 보인다. */
  private void loading(boolean on) {
    if (on) status.setText("");
    gauge.setVisible(on);
  }

  private void load() {
    Target target = selectedTarget();
    if (target == null) return;
    int request = ++loadSeq;
    loading(true);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Loaded result;
      String error = null;
      try {
        result = read(target, null);
      }
      catch (IOException e) {
        result = null;
        error = e.getMessage();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      Loaded done = result;
      String failure = error;
      SwingUtilities.invokeLater(() -> {
        if (request != loadSeq || !target.equals(selectedTarget())) return;  // 읽는 동안 다시 읽기 시작했거나 다른 프로젝트를 골랐다.
        shownSeq = request;
        loaded = done;
        String current = done == null ? "" : done.branch();
        branch.setRefs(current.isEmpty() ? List.of() : List.of(current), current);
        onlyMine.setVisible(target.vcs() == SyncPanel.Vcs.GIT);
        fillCommits();
        loading(false);
        status.setText(done == null ? failure : SyncBundle.message("history.count", done.entries().size()));
      });
    });
  }

  /** 맨 아래까지 내렸을 때 다음 묶음을 읽어 밑에 붙인다. 고른 줄 · 오른쪽 칸은 그대로 둔다. */
  private void loadMore() {
    Loaded before = loaded;
    if (before == null || !before.more() || loadingMore || shownSeq != loadSeq) return;  // 처음부터 다시 읽는 중이면 기다린다.
    int request = loadSeq;
    loadingMore = true;
    loading(true);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Loaded result = null;
      String error = null;
      try {
        result = read(before.target(), before);
      }
      catch (IOException e) {
        error = e.getMessage();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      Loaded done = result;
      String failure = error;
      SwingUtilities.invokeLater(() -> {
        loadingMore = false;
        if (loaded != before || request != loadSeq) return;  // 읽는 동안 다른 프로젝트를 골랐거나 새로 읽기 시작했다.
        if (done == null) {
          // 실패한 묶음은 다시 내릴 때 또 시도하지 않도록 끝으로 본다.
          loaded = new Loaded(before.target(), before.branch(), before.svnRoot(), before.svnPrefix(), before.start(), before.remotes(),
                              before.entries(), before.rows(), false, before.me(), before.sides(), before.clashes());
          loading(false);
          status.setText(Objects.toString(failure, SyncBundle.message("history.count", before.entries().size())));
          return;
        }
        loaded = done;
        if (onlyMine.isSelected()) {
          // 앞서 읽은 내 커밋을 새 묶음의 더 오래된 커밋과 이어 보면 이미 보인 커밋도 새로 걸릴 수 있어 처음부터 다시 거른다.
          fillCommits();
          loading(false);
          status.setText(SyncBundle.message("history.count", done.entries().size()));
          return;
        }
        updateGraphWidth();
        String word = searchWord();
        int first = shown.size();
        for (History.Entry entry : done.entries().subList(before.entries().size(), done.entries().size())) {
          if (visible(entry, word)) shown.add(entry);
        }
        if (shown.size() > first) commitModel.fireTableRowsInserted(first, shown.size() - 1);
        commits.repaint();  // 앞 묶음 마지막 줄의 아래 선도 새 줄로 이어진다.
        loading(false);
        status.setText(SyncBundle.message("history.count", done.entries().size()));
      });
    });
  }

  /**
   * 기록을 읽는다. before 가 null 이면 처음 LOG_LIMIT 개, 아니면 before 뒤의 다음 LOG_LIMIT 개를 읽어 before 에 이어 붙인 결과.
   * Git 은 첫 묶음 때의 HEAD 해시에서 --skip 으로, SVN 은 마지막 리비전 바로 앞부터(-r N-1:1) 읽는다. SVN 은 부모를 「목록의 다음 리비전」으로 두므로
   * 앞 묶음 마지막 커밋의 부모를 새 묶음 첫 커밋으로 잇는다. 이미 목록에 있는 커밋은 다시 붙이지 않는다.
   */
  private static Loaded read(Target target, Loaded before) throws IOException, InterruptedException {
    File dir = target.dir();
    List<History.Entry> entries = before == null ? new ArrayList<>() : new ArrayList<>(before.entries());
    List<History.Entry> page;
    String branch;
    String svnRoot = null;
    String svnPrefix = null;
    String start = null;
    List<String> remotes = List.of();
    History.Me me = History.Me.NONE;
    Map<String, List<List<History.FileChange>>> sides = before == null ? new HashMap<>() : new HashMap<>(before.sides());
    if (target.vcs() == SyncPanel.Vcs.GIT) {
      if (before == null) {
        me = History.parseMe(gitOrEmpty(dir, "config", "--get-regexp", "^user\\.(name|email)$")).withAliases(meAliases());
        branch = gitOrEmpty(dir, "rev-parse", "--abbrev-ref", "HEAD");
        if (DETACHED.equals(branch)) branch = gitOrEmpty(dir, "rev-parse", "--short", "HEAD");
        String head = gitOrEmpty(dir, "rev-parse", "HEAD");
        start = head.isEmpty() ? DETACHED : head;
        List<String> names = new ArrayList<>();
        for (String name : gitOrEmpty(dir, "remote").split("\n")) {
          if (!name.isBlank()) names.add(name.trim());
        }
        remotes = names;
      }
      else {
        branch = before.branch();
        start = before.start();
        remotes = before.remotes();
        me = before.me();
      }
      page = History.parseGitLog(SyncPanel.git(dir, History.gitLogArgs(start, LOG_LIMIT, entries.size())));
    }
    else {
      if (before == null) {
        String[] info = History.parseSvnInfo(SyncPanel.run(dir, "svn", "info", "--xml"));
        svnRoot = info[0];
        svnPrefix = info[1];
      }
      else {
        svnRoot = before.svnRoot();
        svnPrefix = before.svnPrefix();
      }
      branch = svnPrefix;
      page = entries.isEmpty() ? History.parseSvnLog(SyncPanel.run(dir, "svn", "log", "--xml", "-v", "-l", String.valueOf(LOG_LIMIT)), svnPrefix)
                               : nextSvnPage(dir, entries, svnPrefix);
    }
    Set<String> seen = new HashSet<>();
    for (History.Entry entry : entries) seen.add(entry.id());
    List<String> merges = new ArrayList<>();
    for (History.Entry entry : page) {
      if (!seen.add(entry.id())) continue;
      entries.add(entry);
      if (target.vcs() == SyncPanel.Vcs.GIT && entry.merge()) merges.add(entry.id());
    }
    // 합치기 커밋이 있을 때만 git 을 한 번 더 부른다(부모마다 따로 비교, History.clashes).
    if (!merges.isEmpty() && me.known()) sides.putAll(History.parseSides(SyncPanel.git(dir, History.sideArgs(merges))));
    return new Loaded(target, branch, svnRoot, svnPrefix, start, remotes, entries, History.layout(entries), page.size() == LOG_LIMIT,
                      me, sides, History.clashes(entries, me, sides));
  }

  /** entries(비어 있지 않음) 의 마지막 리비전 앞의 다음 묶음. 마지막이 1 이하면 빈 목록. 리비전이 숫자가 아니면 IOException. */
  private static List<History.Entry> nextSvnPage(File dir, List<History.Entry> entries, String svnPrefix)
    throws IOException, InterruptedException {
    int lastIndex = entries.size() - 1;
    History.Entry last = entries.get(lastIndex);
    long revision;
    try {
      revision = Long.parseLong(last.id());
    }
    catch (NumberFormatException e) {
      throw new IOException("svn 리비전을 읽지 못했습니다: " + last.id());
    }
    if (revision <= 1) return List.of();
    String range = (revision - 1) + ":1";
    List<History.Entry> page = History.parseSvnLog(
      SyncPanel.run(dir, "svn", "log", "--xml", "-v", "-l", String.valueOf(LOG_LIMIT), "-r", range), svnPrefix);
    if (!page.isEmpty()) {
      entries.set(lastIndex, new History.Entry(last.id(), List.of(page.get(0).id()), last.author(), last.email(), last.time(), last.refs(),
                                               last.message(), last.files()));
    }
    return page;
  }

  /** 없어도 되는 값(브랜치 이름)을 읽는다. 실패 · 빈 값이면 null. */
  private static String gitOrNull(File dir, String... args) throws InterruptedException {
    try {
      String value = SyncPanel.git(dir, args).trim();
      return value.isEmpty() ? null : value;
    }
    catch (IOException e) {
      return null;
    }
  }

  private static String gitOrEmpty(File dir, String... args) throws InterruptedException {
    String value = gitOrNull(dir, args);
    return value == null ? "" : value;
  }

  /** 찾기 글 · 「내 소스 건드린 커밋만」으로 표를 다시 채운다. 둘 다 꺼져 있을 때만 가지 선을 그린다. */
  private void fillCommits() {
    shown.clear();
    clearDetail();
    if (loaded != null) {
      String word = searchWord();
      graph = word.isEmpty() && !onlyMine.isSelected();
      updateGraphWidth();
      for (History.Entry entry : loaded.entries()) {
        if (visible(entry, word)) shown.add(entry);
      }
    }
    commitModel.fireTableDataChanged();
  }

  private String searchWord() {
    return filter.getText().trim().toLowerCase(Locale.ROOT);
  }

  /** 찾기 글에 맞고, 「내 소스 건드린 커밋만」이 켜져 있으면 내 소스를 건드린 커밋인가. */
  private boolean visible(History.Entry entry, String word) {
    return matches(entry, word) && (!onlyMine.isSelected() || clashesOf(entry).size() > 0);
  }

  private List<History.Clash> clashesOf(History.Entry entry) {
    return loaded == null ? List.of() : loaded.clashes().getOrDefault(entry.id(), List.of());
  }

  /** 찾기 글(소문자, 빈 글이면 모두)이 제목이나 작성자에 들어 있는가. */
  private static boolean matches(History.Entry entry, String word) {
    return word.isEmpty() || entry.subject().toLowerCase(Locale.ROOT).contains(word) || entry.author().toLowerCase(Locale.ROOT).contains(word);
  }

  /** 가지 선 칸 폭. 가지 선을 그릴 때만 읽어 온 줄 가운데 가장 넓은 줄에 맞춘다. */
  private void updateGraphWidth() {
    int lanes = 1;
    if (graph) {
      for (History.Row row : loaded.rows()) lanes = Math.max(lanes, laneCount(row));
    }
    graphWidth = JBUI.scale(Math.min(lanes, MAX_LANES) * LANE_W + GRAPH_PAD * 2);
    fixWidth(commits.getColumnModel().getColumn(COL_GRAPH), graphWidth);
  }

  /** 가지 선 · 점 · 글자 밝기의 뜻. 표 가운데에 풍선으로 띄우고 바깥을 누르면 닫는다. */
  private void showLegend() {
    String html = "<html>" + String.join("<br>", SyncBundle.message("history.legend.dot"), SyncBundle.message("history.legend.merge.dot"),
                                         SyncBundle.message("history.legend.split"), SyncBundle.message("history.legend.merge"),
                                         SyncBundle.message("history.legend.passing"), SyncBundle.message("history.legend.color"),
                                         SyncBundle.message("history.legend.hover"), SyncBundle.message("history.legend.mine")) + "</html>";
    JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(html, MessageType.INFO, null)
      .setHideOnClickOutside(true)
      .createBalloon()
      .showInCenterOf(commits);
  }

  /**
   * index 번 커밋(가지 선을 그린 전체 목록 기준)의 꺾인 선이 이어진 커밋들: 합쳐 들임 · 갈라져 나감 · 합쳐 감.
   * 꺾인 선이 없으면 곧게 이어진 보통 커밋이라고 알린다.
   */
  private String bendText(int index) {
    History.Entry entry = loaded.entries().get(index);
    List<String> parts = new ArrayList<>();
    if (entry.merge()) parts.add(SyncBundle.message("history.legend.merge.dot"));
    for (History.Link link : History.links(loaded.entries(), loaded.rows(), index)) {
      String key = switch (link.kind()) {
        case MERGED_FROM -> "history.graph.merged.from";
        case BRANCHED_TO -> "history.graph.branched.to";
        case MERGED_INTO -> "history.graph.merged.into";
      };
      parts.add(SyncBundle.message(key, describe(link.other())));
    }
    if (parts.isEmpty()) parts.add(SyncBundle.message("history.graph.straight"));
    return String.join("   ", parts);
  }

  /** 꺾임 설명용 커밋 이름: 브랜치(있으면) · 짧은 해시 · 제목. 목록 밖이라 제목을 모르면 해시만. */
  private static String describe(History.Entry entry) {
    String id = shortId(entry);
    String subject = entry.subject();
    if (subject.length() > TIP_SUBJECT_MAX) subject = subject.substring(0, TIP_SUBJECT_MAX) + "…";
    String name = entry.refs().isEmpty() ? id : "[" + entry.refs().get(0) + "] " + id;
    return subject.isEmpty() ? name : name + " " + subject;
  }

  /** 표 · 상세에 보일 짧은 id: Git 은 앞 SHORT_HASH 글자, SVN 은 「r리비전」. */
  private static String shortId(History.Entry entry) {
    return entry.id().length() > SHORT_HASH ? entry.id().substring(0, SHORT_HASH) : "r" + entry.id();
  }

  private static int laneCount(History.Row row) {
    int max = row.col();
    for (History.Seg s : row.top()) max = Math.max(max, Math.max(s.from(), s.to()));
    for (History.Seg s : row.bottom()) max = Math.max(max, Math.max(s.from(), s.to()));
    return max + 1;
  }

  private void clearDetail() {
    subject.setVisible(false);
    message.setText("");
    message.setVisible(false);
    metaRow.setVisible(false);
    bend.setText("");
    bend.setToolTipText(null);
    bend.setVisible(false);
    clashTitle.setVisible(false);
    clashList.setListData(new History.Clash[0]);
    clashList.setVisible(false);
    filesTitle.setVisible(false);
    files.setListData(new History.FileChange[0]);
    files.getEmptyText().setText(SyncBundle.message("history.empty.select"));
  }

  private void showDetail() {
    History.Entry entry = selectedEntry();
    if (entry == null) {
      clearDetail();
      return;
    }
    // 첫 줄은 제목, 나머지는 본문(없으면 숨긴다).
    String text = entry.message().strip();
    int nl = text.indexOf('\n');
    String body = nl < 0 ? "" : text.substring(nl + 1).strip();
    subject.setText(entry.subject());
    subject.setVisible(true);
    message.setText(body);
    message.setVisible(!body.isEmpty());
    meta.setText(entry.author() + " · " + when(entry.time()) + " · ");
    meta.setToolTipText(stamp(FULL, entry.time()));
    hash.setText(shortId(entry));
    metaRow.setVisible(true);
    // 찾기로 줄인 목록은 가지 선이 없어 꺾임도 없다. 가지 선을 그릴 때는 표 순서가 읽어 온 순서와 같다.
    bend.setText(graph ? bendText(commits.getSelectedRow()) : "");
    bend.setToolTipText(bend.getText().isEmpty() ? null : bend.getText());
    bend.setVisible(!bend.getText().isEmpty());
    List<History.Clash> clashes = clashesOf(entry);
    // 한 커밋의 꼬리표는 모두 같은 쪽이다(작성자가 나면 남의 소스, 아니면 내 소스).
    boolean others = !clashes.isEmpty() && clashes.get(0).others();
    clashTitle.setText(SyncBundle.message(others ? "history.others.files" : "history.mine.files", clashes.size()));
    clashTitle.setVisible(!clashes.isEmpty());
    clashList.setListData(clashes.toArray(History.Clash[]::new));
    clashList.setVisible(!clashes.isEmpty());
    filesTitle.setText(SyncBundle.message("history.files", entry.files().size()));
    filesTitle.setVisible(true);
    files.setListData(entry.files().toArray(History.FileChange[]::new));
    files.getEmptyText().setText(SyncBundle.message("history.empty.files"));
  }

  /**
   * 고른 파일의 커밋 직전 내용과 커밋 내용을 비교 창으로 연다. 추가된 파일은 직전 쪽, 삭제된 파일은 커밋 쪽이 빈 내용.
   * 이름을 바꾼 파일은 직전 쪽을 옛 경로로 읽는다. 내용은 바이트로 넘겨 IDE 가 파일 인코딩(프로젝트 설정 · BOM)으로 읽게 한다.
   * 읽기 실패(시간 초과 · 인증 등)는 빈 내용으로 숨기지 않고 비교 창에 오류 문구로 보인다.
   */
  private void showDiff() {
    History.Entry entry = selectedEntry();
    History.FileChange file = files.getSelectedValue();
    Loaded source = loaded;
    if (entry == null || file == null || source == null) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      byte[] before = null;
      byte[] after = null;
      String error = null;
      try {
        before = ADDED.equals(file.status()) ? new byte[0] : contentAt(source, entry, file.oldPath(), true);
        after = DELETED.equals(file.status()) ? new byte[0] : contentAt(source, entry, file.path(), false);
      }
      catch (IOException e) {
        error = e.getMessage();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      byte[] beforeBytes = before;
      byte[] afterBytes = after;
      String failure = error;
      SwingUtilities.invokeLater(() -> {
        if (failure != null) {
          DiffManager.getInstance().showDiff(project, new MessageDiffRequest(failure));
          return;
        }
        File dir = source.target().dir();
        FilePath beforePath = VcsUtil.getFilePath(new File(dir, file.oldPath()), false);
        FilePath afterPath = VcsUtil.getFilePath(new File(dir, file.path()), false);
        DiffContentFactory factory = DiffContentFactory.getInstance();
        try {
          DiffManager.getInstance().showDiff(project, new SimpleDiffRequest(
            file.path() + " (" + entry.id() + ")", factory.createFromBytes(project, beforeBytes, beforePath),
            factory.createFromBytes(project, afterBytes, afterPath),
            SyncBundle.message("history.before"), entry.author() + " · " + stamp(FULL, entry.time())));
        }
        catch (IOException e) {
          DiffManager.getInstance().showDiff(project, new MessageDiffRequest(e.getMessage()));
        }
      });
    });
  }

  /**
   * 고른 「내 소스를 건드린 파일」의 내 커밋 판(왼쪽)과 이 커밋 판(오른쪽)을 비교 창으로 연다. 내용은 해시로 바로 읽는다(git cat-file).
   * 없는 파일(해시가 0 만)은 빈 내용. 읽기 실패는 비교 창에 오류 문구로 보인다.
   */
  private void showClashDiff() {
    History.Entry entry = selectedEntry();
    History.Clash clash = clashList.getSelectedValue();
    Loaded source = loaded;
    if (entry == null || clash == null || source == null) return;
    File dir = source.target().dir();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      byte[][] contents;
      try {
        contents = new byte[][]{blob(dir, clash.ownerChange().newBlob()), blob(dir, clash.change().newBlob())};
      }
      catch (IOException e) {
        SwingUtilities.invokeLater(() -> DiffManager.getInstance().showDiff(project, new MessageDiffRequest(e.getMessage())));
        return;
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      SwingUtilities.invokeLater(() -> {
        FilePath path = VcsUtil.getFilePath(new File(dir, clash.change().path()), false);
        DiffContentFactory factory = DiffContentFactory.getInstance();
        try {
          DiffManager.getInstance().showDiff(project, new SimpleDiffRequest(
            clash.change().path() + " (" + entry.id() + ")", factory.createFromBytes(project, contents[0], path),
            factory.createFromBytes(project, contents[1], path),
            SyncBundle.message("history.mine.commit", ownerName(clash), shortId(clash.owner()), stamp(FULL, clash.owner().time())),
            entry.author() + " · " + stamp(FULL, entry.time())));
        }
        catch (IOException e) {
          DiffManager.getInstance().showDiff(project, new MessageDiffRequest(e.getMessage()));
        }
      });
    });
  }

  /** 바뀌기 전 판을 만든 사람: 내 소스면 「내」, 남의 소스면 그 작성자. */
  private static String ownerName(History.Clash clash) {
    return clash.others() ? clash.owner().author() : SyncBundle.message("history.mine.me");
  }

  /** 꼬리표 글: 내 소스 · 남의 소스와 건드린 방식. */
  private static String clashLabel(boolean others, History.ClashKind kind) {
    return SyncBundle.message("history.clash." + (others ? "others." : "mine.") + kind.name().toLowerCase(Locale.ROOT));
  }

  /** 해시의 파일 내용. 0 만인 해시(없는 파일)는 빈 내용. */
  private static byte[] blob(File dir, String hash) throws IOException, InterruptedException {
    return hash.chars().allMatch(c -> c == '0') ? new byte[0] : SyncPanel.gitBytes(dir, "cat-file", "blob", hash);
  }

  /** Git 은 「해시:./경로」(작업 폴더 기준, 직전은 첫 부모), SVN 은 「저장소 URL@리비전」. 실패는 IOException. */
  private static byte[] contentAt(Loaded source, History.Entry entry, String path, boolean before) throws IOException, InterruptedException {
    File dir = source.target().dir();
    if (source.target().vcs() == SyncPanel.Vcs.GIT) {
      return SyncPanel.gitBytes(dir, "show", entry.id() + (before ? "^" : "") + ":./" + path);
    }
    long rev;
    try {
      rev = Long.parseLong(entry.id()) - (before ? 1 : 0);
    }
    catch (NumberFormatException e) {
      throw new IOException("svn 리비전을 읽지 못했습니다: " + entry.id());
    }
    return SyncPanel.runBytes(dir, CAT_TIMEOUT_SEC, "svn", "cat", source.svnRoot() + source.svnPrefix() + "/" + path + "@" + rev);
  }

  /** 화면 글 묶음에 적힌 시간 모양. 오전 · 오후 글자는 history.locale 을 따른다. */
  private static DateTimeFormatter timeFormat(String key) {
    return DateTimeFormatter.ofPattern(SyncBundle.message(key), TIME_LOCALE);
  }

  /** 시간을 모양대로. 날짜가 없는 리비전(SVN 권한 없음, Instant.EPOCH)은 빈 글. */
  private static String stamp(DateTimeFormatter format, Instant time) {
    return Instant.EPOCH.equals(time) ? "" : format.format(time.atZone(ZONE));
  }

  /** 표에 보일 시간: 날짜가 없으면 빈 글, 한 시간 안은 「N분 전」, 오늘 · 어제는 「오늘 HH:mm」 · 「어제 HH:mm」, 올해는 월-일 시각, 그 전은 날짜만(모양은 화면 글 묶음). */
  static String when(Instant time) {
    if (Instant.EPOCH.equals(time)) return "";
    Duration ago = Duration.between(time, Instant.now());
    if (!ago.isNegative() && ago.toMinutes() < MINUTES_PER_HOUR) return SyncBundle.message("history.minutes.ago", Math.max(1, ago.toMinutes()));
    ZonedDateTime at = time.atZone(ZONE);
    LocalDate today = LocalDate.now(ZONE);
    LocalDate date = at.toLocalDate();
    if (date.equals(today)) return SyncBundle.message("history.today", TODAY.format(at));
    if (date.equals(today.minusDays(1))) return SyncBundle.message("history.yesterday", TODAY.format(at));
    return (at.getYear() == today.getYear() ? THIS_YEAR : OLDER).format(at);
  }

  /** 그릴 때마다 고른다. 테마를 바꾸면 다시 그릴 때 바로 바뀐다. */
  private static Color[] palette() {
    return JBColor.isBright() ? LIGHT_PALETTE : DARK_PALETTE;
  }

  private static Color laneColor(int lane) {
    Color[] palette = palette();
    return palette[lane % palette.length];
  }

  /** 알약 색. 원격 브랜치는 원격 이름(remotes)으로 시작하는 것만이라 feature/x 같은 로컬 브랜치는 로컬 색. */
  private static Color refColor(String ref, boolean head, List<String> remotes) {
    if (head) return HEAD_COLOR;
    if (ref.startsWith(TAG_PREFIX)) return TAG_COLOR;
    for (String remote : remotes) {
      if (ref.startsWith(remote + "/")) return REMOTE_COLOR;
    }
    return LOCAL_COLOR;
  }

  /** 현재 브랜치 알약 안에 그릴 브랜치 아이콘(알약 글자색으로 물들임). 다른 알약은 null. */
  private static Icon pillIcon(boolean head, Color color) {
    return head ? IconUtil.scale(IconUtil.colorize(AllIcons.Vcs.Branch, color), null, PILL_ICON_SCALE) : null;
  }

  /** 알약 폭: 글 · 아이콘 · 양쪽 여백. */
  private static int pillWidth(FontMetrics fm, String text, Icon icon) {
    int gap = JBUI.scale(GAP);
    return fm.stringWidth(text) + gap * 2 + (icon == null ? 0 : icon.getIconWidth() + gap / 2);
  }

  private static Color alpha(Color c, int a) {
    return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
  }

  /** 알약 하나를 그리고 폭을 돌려준다. y 는 알약 위쪽. icon 은 글 앞에 그린다(없으면 null). */
  private static int paintPill(Component c, Graphics2D g, String text, Color color, Icon icon, int x, int y) {
    FontMetrics fm = g.getFontMetrics();
    int h = JBUI.scale(PILL_H);
    int w = pillWidth(fm, text, icon);
    int textX = x + JBUI.scale(GAP);
    g.setColor(alpha(color, PILL_FILL_ALPHA));
    g.fillRoundRect(x, y, w, h, h, h);
    g.setColor(alpha(color, PILL_BORDER_ALPHA));
    g.drawRoundRect(x, y, w - 1, h - 1, h, h);
    if (icon != null) {
      icon.paintIcon(c, g, textX - JBUI.scale(GAP) / 2, y + (h - icon.getIconHeight()) / 2);
      textX += icon.getIconWidth();
    }
    g.setColor(color);
    g.drawString(text, textX, y + (h + fm.getAscent() - fm.getDescent()) / 2);
    return w;
  }

  private static Graphics2D smooth(Graphics g) {
    Graphics2D g2 = (Graphics2D)g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    return g2;
  }

  /** 알약 줄: 도구줄의 현재 브랜치. head 와 같은 이름은 현재 브랜치 모양. */
  private static final class Pill extends JComponent {
    private List<String> refs = List.of();
    private String head;

    Pill() {
      setFont(JBUI.Fonts.label());
    }

    void setRefs(List<String> refs, String head) {
      this.refs = refs;
      this.head = head;
      setVisible(!refs.isEmpty());
      revalidate();
      repaint();
    }

    @Override
    public Dimension getPreferredSize() {
      if (refs.isEmpty()) return new Dimension(0, 0);
      FontMetrics fm = getFontMetrics(getFont());
      int w = 0;
      for (String ref : refs) {
        boolean isHead = ref.equals(head);
        w += pillWidth(fm, ref, pillIcon(isHead, refColor(ref, isHead, List.of()))) + JBUI.scale(GAP) / 2;
      }
      return new Dimension(w + 1, JBUI.scale(PILL_H) + 1);
    }

    @Override public Dimension getMaximumSize() { return getPreferredSize(); }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = smooth(g);
      g2.setFont(getFont());
      int x = 0;
      for (String ref : refs) {
        boolean isHead = ref.equals(head);
        Color color = refColor(ref, isHead, List.of());
        x += paintPill(this, g2, ref, color, pillIcon(isHead, color), x, 0) + JBUI.scale(GAP) / 2;
      }
      g2.dispose();
    }
  }

  /** 프로젝트(모듈) 아이콘 · 이름, 뒤에 흐린 Git · SVN 표시와 현재 브랜치. */
  private static final class TargetRenderer extends ColoredListCellRenderer<Target> {
    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends Target> list, Target value, int index, boolean selected, boolean focused) {
      if (value == null) return;
      setIcon(AllIcons.Nodes.Module);
      append(value.name());
      append("  " + (value.vcs() == SyncPanel.Vcs.GIT ? "Git" : "SVN"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      if (!value.branch().isEmpty()) append("  " + value.branch(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      setToolTipText(value.dir().getPath());
    }
  }

  /** 소스를 건드린 파일: 색 있는 「되돌림」 · 「덮어씀」 · 「이어 고침」, 파일 이름, 흐린 「← 내(또는 작성자) 커밋 해시 · 날짜」. */
  private static final class ClashRenderer extends ColoredListCellRenderer<History.Clash> {
    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends History.Clash> list, History.Clash value, int index,
                                         boolean selected, boolean focused) {
      History.ClashKind kind = value.kind();
      append(SyncBundle.message("history.mine.kind." + kind.name().toLowerCase(Locale.ROOT)) + "  ",
             new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, CLASH_COLOR.get(kind)));
      append(value.change().path());
      append("  " + SyncBundle.message("history.mine.from", ownerName(value), shortId(value.owner()), when(value.owner().time())),
             SimpleTextAttributes.GRAYED_ATTRIBUTES);
      setToolTipText(value.change().path());
    }
  }

  /** 표 모델: shown 의 줄. 칸 값은 그리지 않고(CommitRenderer 가 Entry 로 그린다) 머리글만 쓴다. */
  private final class CommitModel extends AbstractTableModel {
    @Override public int getRowCount() { return shown.size(); }
    @Override public int getColumnCount() { return COLUMN_KEYS.length; }
    @Override public String getColumnName(int column) { return COLUMN_KEYS[column].isEmpty() ? "" : SyncBundle.message(COLUMN_KEYS[column]); }
    @Override public Object getValueAt(int row, int column) { return shown.get(row); }
  }

  /** 커밋 한 줄의 칸: 가지 선 · 점, 메시지 · 알약, 흐린 작성자 · 시간, 고정폭 흐린 해시. 합치기 커밋은 메시지도 흐리게. */
  private final class CommitRenderer extends JComponent implements TableCellRenderer {
    private History.Entry entry;
    private int index;
    private int column;
    private int last;
    private boolean selected;
    private Color background;
    private Color foreground;

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
      this.entry = value instanceof History.Entry e ? e : null;
      this.index = row;
      this.column = column;
      this.last = table.getRowCount() - 1;
      this.selected = selected;
      this.background = selected ? table.getSelectionBackground() : table.getBackground();
      this.foreground = selected ? table.getSelectionForeground() : table.getForeground();
      setFont(column == COL_HASH ? hashFont : table.getFont());
      return this;
    }

    private int laneX(int lane) {
      return JBUI.scale(GRAPH_PAD) + JBUI.scale(LANE_W) * lane + JBUI.scale(LANE_W) / 2;
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = smooth(g);
      int w = getWidth();
      int h = getHeight();
      g2.setColor(background);
      g2.fillRect(0, 0, w, h);
      if (entry != null) {
        if (column == COL_GRAPH) paintGraph(g2, h);
        else paintText(g2, w, h);
      }
      g2.dispose();
    }

    private void paintGraph(Graphics2D g2, int h) {
      int cy = h / 2;
      History.Row row = graph && loaded != null && index < loaded.rows().size() ? loaded.rows().get(index) : null;
      int col = row == null ? 0 : row.col();
      g2.setStroke(new BasicStroke(JBUI.scale(LINE_WIDTH)));
      if (row == null) {
        g2.setColor(laneColor(0));
        g2.drawLine(laneX(0), index == 0 ? cy : 0, laneX(0), index == last ? cy : h);
      }
      else {
        for (History.Seg s : row.top()) segment(g2, s, 0, cy);
        for (History.Seg s : row.bottom()) segment(g2, s, cy, h);
      }
      // 커밋은 가지 색 점, 합치기 커밋은 속이 빈 점.
      int x = laneX(col);
      int d = JBUI.scale(DOT);
      g2.setColor(entry.merge() ? background : laneColor(col));
      g2.fillOval(x - d / 2, cy - d / 2, d, d);
      g2.setColor(laneColor(col));
      g2.drawOval(x - d / 2, cy - d / 2, d, d);
    }

    private void paintText(Graphics2D g2, int w, int h) {
      g2.setFont(getFont());
      FontMetrics fm = g2.getFontMetrics();
      int baseline = (h + fm.getAscent() - fm.getDescent()) / 2;
      int gap = JBUI.scale(GAP);
      int room = w - gap * 2;
      // 작성자 · 시간 · 해시는 흐리게, 합치기 커밋은 메시지도 흐리게(고른 줄은 선택 글자색).
      boolean dim = column != COL_MESSAGE || entry.merge();
      g2.setColor(dim && !selected ? DIM : foreground);
      String text = switch (column) {
        case COL_MESSAGE -> entry.subject();
        case COL_AUTHOR -> entry.author();
        case COL_TIME -> when(entry.time());
        default -> shortId(entry);
      };
      int x = gap;
      int cy = h / 2;
      if (column == COL_MESSAGE) x += paintClashPill(g2, x, cy);
      String fitted = fit(text, fm, room - (x - gap));
      g2.drawString(fitted, x, baseline);
      if (column != COL_MESSAGE) return;
      int pillX = x + fm.stringWidth(fitted) + gap;
      String head = loaded == null ? null : loaded.branch();
      for (String ref : entry.refs()) {
        boolean isHead = ref.equals(head);
        Color color = refColor(ref, isHead, loaded == null ? List.of() : loaded.remotes());
        Icon icon = pillIcon(isHead, color);
        if (pillX + pillWidth(fm, ref, icon) > w - gap) break;
        pillX += paintPill(this, g2, ref, color, icon, pillX, cy - JBUI.scale(PILL_H) / 2) + gap / 2;
      }
    }

    /** 소스를 건드린 커밋이면 메시지 앞에 가장 센 방식(History.strongest)의 꼬리표를 그리고 차지한 폭(뒤 여백 포함)을 돌려준다. */
    private int paintClashPill(Graphics2D g2, int x, int cy) {
      List<History.Clash> clashes = clashesOf(entry);
      if (clashes.isEmpty()) return 0;
      History.ClashKind kind = History.strongest(clashes);
      String label = clashLabel(clashes.get(0).others(), kind);
      return paintPill(this, g2, label, CLASH_COLOR.get(kind), null, x, cy - JBUI.scale(PILL_H) / 2) + JBUI.scale(GAP) / 2;
    }

    private void segment(Graphics2D g, History.Seg s, int y1, int y2) {
      int x1 = laneX(s.from());
      int x2 = laneX(s.to());
      g.setColor(laneColor(s.lane()));
      g.drawLine(x1, y1, x2, y2);
    }
  }

  /** 폭에 맞게 글을 자르고 「…」를 붙인다. */
  private static String fit(String text, FontMetrics fm, int width) {
    if (fm.stringWidth(text) <= width) return text;
    String ellipsis = "…";
    int end = text.length();
    while (end > 0 && fm.stringWidth(text.substring(0, end)) + fm.stringWidth(ellipsis) > width) end--;
    return text.substring(0, end) + ellipsis;
  }

  /** 바뀐 파일: 상태 색 굵은 상태 글자, 파일 형식 아이콘, 상태 색 이름(삭제는 취소선), 흐린 상위 폴더 경로. 풍선 도움말은 상태 글자와 전체 경로. */
  private static final class FileRenderer extends ColoredListCellRenderer<History.FileChange> {
    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends History.FileChange> list, History.FileChange value, int index,
                                         boolean selected, boolean focused) {
      String path = value.path();
      int slash = path.lastIndexOf('/');
      String name = path.substring(slash + 1);
      Icon fileIcon = FileTypeManager.getInstance().getFileTypeByFileName(name).getIcon();
      if (fileIcon == null) fileIcon = AllIcons.FileTypes.Unknown;
      FileStatus status = FILE_STATUS.get(value.status());
      Color color = status == null ? DIM : status.getColor();
      Font bold = JBUI.Fonts.label().asBold();
      int statusWidth = Math.max(JBUI.scale(STATUS_COL_W), getFontMetrics(bold).stringWidth(value.status()));
      setIcon(new RowIcon(new SyncPanel.StatusIcon(value.status(), color, bold, statusWidth, fileIcon.getIconHeight()), fileIcon));
      int style = FileStatus.DELETED.equals(status) ? SimpleTextAttributes.STYLE_STRIKEOUT : SimpleTextAttributes.STYLE_PLAIN;
      append(name, status == null ? SimpleTextAttributes.REGULAR_ATTRIBUTES : new SimpleTextAttributes(style, status.getColor()));
      if (slash > 0) append("  " + path.substring(0, slash), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      setToolTipText(value.status() + "  " + path);
    }
  }

  /** 아래쪽 「Git Log」 창. 창 id 는 설정 호환을 위해 영어, 표시 이름만 한국어. */
  public static final class Factory implements ToolWindowFactory, DumbAware {
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
      toolWindow.setStripeTitle(SyncBundle.message("history.title"));
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
      toolWindow.getContentManager().addContent(ContentFactory.getInstance().createContent(new ProjectHistoryPanel(project), "", false));
    }
  }
}
