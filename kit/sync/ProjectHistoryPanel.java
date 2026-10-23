package kinggura.sync;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
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
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ide.IdeTooltip;
import com.intellij.ide.IdeTooltipManager;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.render.RenderingUtil;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.RowIcon;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
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
 * 아래쪽 「Git Log」 창. 왼쪽에서 프로젝트(모듈)를 고르면 그 폴더 안을 바꾼 최근 커밋을 IntelliJ 기본 Log 탭처럼 보여 준다.
 * 가지마다 색이 다른 꺾인 선 위에 커밋 점(합치기는 속이 빈 점), 브랜치 · 태그 알약, 오른쪽에 커밋 내용과 바뀐 파일(두 번 누르면 비교 창).
 * 맨 아래까지 내리면 다음 LOG_LIMIT 개를 읽어 밑에 붙인다. 위쪽은 찾기 칸과 새로고침뿐이다. 찾기로 거르면 사이가 빠지므로 가지 선 대신 한 줄로 그린다. 기존 Git 로그 창은 그대로 둔다.
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
  // 가지 선 풍선은 IDE 기본 풍선 지연(ide.tooltip.initialDelay)을 기다리지 않고 이만큼 뒤에 띄운다.
  private static final int GRAPH_TIP_DELAY_MS = 120;
  // 가지 선 풍선에 넣는 커밋 제목 최대 글자 수.
  private static final int TIP_SUBJECT_MAX = 60;
  private static final int INITIALS = 2;

  // 크기(JBUI.scale 전). 목록 줄 · 가지 칸 · 점은 IntelliJ 기본 Log 탭 비율을 따른다.
  private static final int ROW_H = 26;
  private static final int LANE_W = 16;
  private static final int GRAPH_PAD = 8;
  private static final int MAX_LANES = 12;
  private static final int DOT = 7;
  private static final int AVATAR_BIG = 28;
  // 목록 오른쪽 작성자 · 시간 칸 폭(JBUI.scale 전). 줄마다 같은 자리에 서도록 고정한다.
  private static final int AUTHOR_COL_W = 64;
  private static final int TIME_COL_W = 84;
  // 바뀐 파일 줄 앞 상태 글자 칸의 최소 폭(JBUI.scale 전).
  private static final int STATUS_COL_W = 16;
  // 현재 브랜치 알약 안 브랜치 아이콘 크기(16 픽셀 아이콘 대비).
  private static final float PILL_ICON_SCALE = 0.75f;
  private static final int PILL_H = 18;
  private static final int GAP = 8;
  private static final int SEARCH_COLUMNS = 30;
  private static final String TOOLBAR_PLACE = "KingGuraGitLogToolbar";
  private static final float LINE_WIDTH = 1.5f;
  // 동그라미 지름 대비 머리글자 크기.
  private static final float AVATAR_FONT_RATIO = 0.4f;

  // 색. 가지 선 · 작성자 동그라미는 EI Glass 분홍을 첫 색으로 한 고정 팔레트, 알약은 글자색 + 옅은 바탕 · 테두리.
  // 유리 디자인 분류 색 10개. 이웃한 가지끼리 비슷한 색이 붙지 않도록 분홍 · 파랑 · 초록 · 주황 … 순으로 둔다.
  private static final Color[] PALETTE = {
    new Color(0xFF375F), new Color(0x0A84FF), new Color(0x30D158), new Color(0xFF9F0A), new Color(0xBF5AF2),
    new Color(0x40C8C0), new Color(0xFFD60A), new Color(0x64D2FF), new Color(0x7D7AFF), new Color(0xFF6482)};
  // 동그라미 글자는 팔레트 바탕 위에 그리므로 테마와 무관하게 어둡게 둔다.
  private static final Color AVATAR_TEXT = new Color(0x111111);
  // 알약 글자색. 밝은 테마(첫 값)에서는 흰 바탕에서 읽히도록 어둡게, 어두운 테마(둘째 값)에서는 밝게.
  private static final Color LOCAL_BRANCH = new JBColor(0x2A8A4E, 0x5FE39B);
  private static final Color REMOTE_BRANCH = new JBColor(0x3A6FD8, 0x8FB3FF);
  private static final Color TAG = new JBColor(0x9A6B00, 0xFFD36B);
  private static final Color HEAD_BRANCH = new JBColor(0xD6265A, 0xFF6482);
  // 알약 글자색은 IntelliJ Log 탭과 같은 테마 키를 따르고, 키가 없는 테마에서는 위 색을 쓴다. 현재 브랜치(HEAD)는 강조 색 + 브랜치 아이콘.
  private static final Color HEAD_COLOR = JBColor.namedColor("VersionControl.GitLog.headIconColor", HEAD_BRANCH);
  private static final Color LOCAL_COLOR = JBColor.namedColor("VersionControl.GitLog.localBranchIconColor", LOCAL_BRANCH);
  private static final Color REMOTE_COLOR = JBColor.namedColor("VersionControl.GitLog.remoteBranchIconColor", REMOTE_BRANCH);
  private static final Color TAG_COLOR = JBColor.namedColor("VersionControl.GitLog.tagIconColor", TAG);
  private static final int PILL_FILL_ALPHA = 0x22;
  private static final int PILL_BORDER_ALPHA = 0x66;
  private static final String TAG_PREFIX = "tag: ";
  // 바뀐 파일 이름 색은 IDE 의 파일 상태 색(테마를 따름). 이름 바꿈(R)은 IntelliJ 변경 목록처럼 수정 색.
  private static final Map<String, FileStatus> FILE_STATUS = Map.of(
    "M", FileStatus.MODIFIED, "A", FileStatus.ADDED, "D", FileStatus.DELETED, "R", FileStatus.MODIFIED);
  private static final Color DIM = SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor();

  /** 왼쪽 목록 한 줄. dir 은 모듈의 첫 콘텐츠 루트. */
  private record Target(String name, File dir, SyncPanel.Vcs vcs) {
    @Override public String toString() { return name; }
  }

  /** 읽어 온 기록. branch 는 Git 현재 브랜치 · SVN 저장소 경로. svnRoot · svnPrefix 는 SVN 비교 창용. more 는 다음 묶음이 남았을 수 있음. */
  private record Loaded(Target target, String branch, String svnRoot, String svnPrefix,
                        List<History.Entry> entries, List<History.Row> rows, boolean more) {}

  private final Project project;
  private final JBList<Target> targets = new JBList<>();
  private final DefaultListModel<History.Entry> commitModel = new DefaultListModel<>();
  // 풍선 도움말: 작성자와 긴 날짜(목록에는 짧은 시간만 보인다). 가지 선 칸 위의 풍선은 graphTipAt 이 따로 띄운다.
  private final JBList<History.Entry> commits = new JBList<>(commitModel) {
    @Override
    public String getToolTipText(MouseEvent e) {
      int i = locationToIndex(e.getPoint());
      if (i < 0 || !getCellBounds(i, i).contains(e.getPoint())) return null;
      History.Entry entry = commitModel.get(i);
      return entry.author() + " · " + ROW_TIME.format(entry.time().atZone(ZONE));
    }
  };
  // 지금 걸어 둔 가지 선 풍선의 글. 같은 글이면 풍선을 새로 만들지 않는다.
  private String graphTipText;
  private final JBList<History.FileChange> files = new JBList<>();
  private final SearchTextField filter = new SearchTextField(false);
  private final JLabel projectName = new JLabel(" ");
  private final Pill branch = new Pill();
  private final JLabel status = new JLabel(" ");
  private final JBLabel subject = new JBLabel();
  private final JTextArea message = new JTextArea();
  private final JBLabel avatar = new JBLabel();
  private final JBLabel author = new JBLabel();
  private final JBLabel authorTime = new JBLabel();
  private final JComponent authorRow = new JPanel(new BorderLayout(JBUI.scale(GAP), 0));
  private final ActionLink hash = new ActionLink();
  private final Pill refs = new Pill();
  private final TitledSeparator filesTitle = new TitledSeparator();
  private Loaded loaded;
  private boolean loadingMore;
  private boolean graph;
  private int graphWidth;

  ProjectHistoryPanel(Project project) {
    super(new BorderLayout());
    this.project = project;

    targets.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    targets.getEmptyText().setText(SyncBundle.message("history.empty.targets"));
    commits.getEmptyText().setText(SyncBundle.message("history.empty.commits"));
    targets.setCellRenderer(new TargetRenderer());
    targets.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) load(); });
    commits.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    commits.setFixedCellHeight(JBUI.scale(ROW_H));
    commits.setCellRenderer(new CommitRenderer());
    commits.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetail(); });
    commits.addMouseMotionListener(new MouseAdapter() {
      @Override public void mouseMoved(MouseEvent e) { updateGraphTip(e); }
    });
    files.setCellRenderer(new FileRenderer());
    files.addMouseListener(new MouseAdapter() {
      @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) showDiff(); }
    });
    filter.getTextEditor().setColumns(SEARCH_COLUMNS);
    filter.getTextEditor().getEmptyText().setText(SyncBundle.message("history.search.hint"));
    filter.addDocumentListener(new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) { fillCommits(); }
    });
    hash.addActionListener(e -> {
      History.Entry entry = commits.getSelectedValue();
      if (entry != null) CopyPasteManager.getInstance().setContents(new StringSelection(entry.id()));
    });

    // IntelliJ 기본 창처럼 새로고침은 도구줄 아이콘 단추, 스크롤 상자는 테두리 없이, 칸막이는 1픽셀로 둔다.
    // 창 머리 아래 선은 IntelliJ 가 그으므로 안쪽 칸에 테두리를 더하면 선이 두 겹이 된다.
    DefaultActionGroup actions = new DefaultActionGroup(
      DumbAwareAction.create(SyncBundle.message("history.refresh"), AllIcons.Actions.Refresh, e -> { reloadTargets(); load(); }),
      DumbAwareAction.create(SyncBundle.message("history.legend.action"), AllIcons.General.ContextHelp, e -> showLegend()));
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true);
    toolbar.setTargetComponent(this);
    projectName.setFont(JBUI.Fonts.label().asBold());
    // 왼쪽에 이름 · 브랜치 · 찾기 · 새로고침, 오른쪽 끝에 「최근 N개」.
    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP / 2)));
    left.add(projectName);
    left.add(branch);
    left.add(filter);
    left.add(toolbar.getComponent());
    JPanel top = new JPanel(new BorderLayout());
    top.setBorder(JBUI.Borders.empty(GAP / 2, GAP / 2, 0, GAP));
    top.add(left, BorderLayout.CENTER);
    top.add(status, BorderLayout.EAST);
    status.setForeground(DIM);

    JPanel center = new JPanel(new BorderLayout());
    center.add(top, BorderLayout.NORTH);
    JScrollPane commitScroll = ScrollPaneFactory.createScrollPane(commits, true);
    BoundedRangeModel scroll = commitScroll.getVerticalScrollBar().getModel();
    scroll.addChangeListener(e -> {
      // 목록이 칸보다 길 때만 본다. 찾기로 줄어 칸에 다 들어가면 내릴 수 없으므로 저절로 읽지 않는다.
      boolean overflows = scroll.getMaximum() > scroll.getExtent();
      if (overflows && scroll.getValue() + scroll.getExtent() >= scroll.getMaximum() - JBUI.scale(ROW_H)) loadMore();
    });
    center.add(commitScroll, BorderLayout.CENTER);

    OnePixelSplitter right = new OnePixelSplitter(false, 0.7f);
    right.setFirstComponent(center);
    right.setSecondComponent(detailPanel());
    OnePixelSplitter main = new OnePixelSplitter(false, 0.16f);
    targets.setBorder(JBUI.Borders.emptyTop(GAP / 2));
    main.setFirstComponent(ScrollPaneFactory.createScrollPane(targets, true));
    main.setSecondComponent(right);
    add(main, BorderLayout.CENTER);

    reloadTargets();
  }

  /** 오른쪽: 굵은 제목, 회색 본문, 작성자 동그라미 · 이름 · 시간, 고정폭 해시(누르면 복사), 이 커밋의 알약, 바뀐 파일. */
  private JComponent detailPanel() {
    subject.setFont(JBUI.Fonts.label().biggerOn(1).asBold());
    author.setFont(JBUI.Fonts.label().asBold());
    authorTime.setFont(JBUI.Fonts.smallFont());
    authorTime.setForeground(DIM);
    JPanel names = new JPanel(new GridLayout(0, 1));
    names.add(author);
    names.add(authorTime);
    authorRow.add(avatar, BorderLayout.WEST);
    authorRow.add(names, BorderLayout.CENTER);
    hash.setToolTipText(SyncBundle.message("history.copy"));
    hash.setIcon(AllIcons.Actions.Copy);
    hash.setHorizontalTextPosition(SwingConstants.LEFT);
    hash.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().getSize()));
    message.setEditable(false);
    message.setLineWrap(true);
    message.setWrapStyleWord(true);
    message.setOpaque(false);
    message.setFont(JBUI.Fonts.label());
    message.setForeground(DIM);
    message.setBorder(JBUI.Borders.empty());
    JLabel hint = new JLabel(SyncBundle.message("history.hint"));
    hint.setForeground(DIM);
    hint.setFont(JBUI.Fonts.smallFont());
    hint.setBorder(JBUI.Borders.emptyTop(GAP));

    JPanel head = new JPanel();
    head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
    for (JComponent c : List.of(subject, message, authorRow, hash, refs, filesTitle)) {
      c.setAlignmentX(LEFT_ALIGNMENT);
      head.add(c);
      head.add(Box.createVerticalStrut(JBUI.scale(GAP)));
    }
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(GAP * 2));
    panel.add(head, BorderLayout.NORTH);
    panel.add(ScrollPaneFactory.createScrollPane(files, true), BorderLayout.CENTER);
    panel.add(hint, BorderLayout.SOUTH);
    clearDetail();
    return panel;
  }

  /** 모듈 목록을 이름 순으로 다시 채운다. 모듈이 없으면 프로젝트 폴더 하나. 같은 폴더를 가리키는 모듈은 한 번만. */
  private void reloadTargets() {
    Target selected = targets.getSelectedValue();
    Map<File, Target> byDir = new LinkedHashMap<>();
    Module[] modules = ModuleManager.getInstance(project).getModules().clone();  // 원본 배열은 공유될 수 있어 복사본을 정렬한다.
    Arrays.sort(modules, Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
    for (Module module : modules) {
      VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
      if (roots.length == 0) continue;
      File dir = new File(roots[0].getPath());
      byDir.putIfAbsent(dir, new Target(module.getName(), dir, SyncPanel.unmappedVcs(dir)));
    }
    if (byDir.isEmpty() && project.getBasePath() != null) {
      File base = new File(project.getBasePath());
      byDir.put(base, new Target(project.getName(), base, SyncPanel.unmappedVcs(base)));
    }
    targets.setListData(byDir.values().toArray(Target[]::new));
    if (selected != null) targets.setSelectedValue(selected, true);
  }

  private void load() {
    Target target = targets.getSelectedValue();
    if (target == null) return;
    projectName.setText(target.name());
    status.setText(SyncBundle.message("history.loading"));
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
        if (target != targets.getSelectedValue()) return;  // 읽는 동안 다른 프로젝트를 골랐다.
        loaded = done;
        String current = done == null ? "" : done.branch();
        branch.setRefs(current.isEmpty() ? List.of() : List.of(current), current);
        fillCommits();
        status.setText(done == null ? failure : SyncBundle.message("history.count", done.entries().size()));
      });
    });
  }

  /** 맨 아래까지 내렸을 때 다음 묶음을 읽어 밑에 붙인다. 고른 줄 · 오른쪽 칸은 그대로 둔다. */
  private void loadMore() {
    Loaded before = loaded;
    if (before == null || !before.more() || loadingMore) return;
    loadingMore = true;
    status.setText(SyncBundle.message("history.loading"));
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
        if (loaded != before) return;  // 읽는 동안 다른 프로젝트를 골랐거나 새로 읽었다.
        if (done == null) {
          // 실패한 묶음은 다시 내릴 때 또 시도하지 않도록 끝으로 본다.
          loaded = new Loaded(before.target(), before.branch(), before.svnRoot(), before.svnPrefix(), before.entries(), before.rows(),
                              false);
          status.setText(Objects.toString(failure, SyncBundle.message("history.count", before.entries().size())));
          return;
        }
        loaded = done;
        updateGraphWidth();
        String word = searchWord();
        for (History.Entry entry : done.entries().subList(before.entries().size(), done.entries().size())) {
          if (matches(entry, word)) commitModel.addElement(entry);
        }
        commits.repaint();  // 앞 묶음 마지막 줄의 아래 선도 새 줄로 이어진다.
        status.setText(SyncBundle.message("history.count", done.entries().size()));
      });
    });
  }

  /**
   * 기록을 읽는다. before 가 null 이면 처음 LOG_LIMIT 개, 아니면 before 뒤의 다음 LOG_LIMIT 개를 읽어 before 에 이어 붙인 결과.
   * Git 은 --skip 으로, SVN 은 마지막 리비전 바로 앞부터(-r N-1:1) 읽는다. SVN 은 부모를 「목록의 다음 리비전」으로 두므로
   * 앞 묶음 마지막 커밋의 부모를 새 묶음 첫 커밋으로 잇는다.
   */
  private static Loaded read(Target target, Loaded before) throws IOException, InterruptedException {
    File dir = target.dir();
    List<History.Entry> entries = before == null ? new ArrayList<>() : new ArrayList<>(before.entries());
    List<History.Entry> page;
    String branch;
    String svnRoot = null;
    String svnPrefix = null;
    if (target.vcs() == SyncPanel.Vcs.GIT) {
      branch = before == null ? gitOrEmpty(dir, "rev-parse", "--abbrev-ref", "HEAD") : before.branch();
      page = History.parseGitLog(SyncPanel.git(dir, History.gitLogArgs(LOG_LIMIT, entries.size())));
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
    entries.addAll(page);
    return new Loaded(target, branch, svnRoot, svnPrefix, entries, History.layout(entries), page.size() == LOG_LIMIT);
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
      entries.set(lastIndex, new History.Entry(last.id(), List.of(page.get(0).id()), last.author(), last.time(), last.refs(),
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

  /** 찾기 글로 목록을 다시 채운다. 찾기 칸이 비어 있을 때만 가지 선을 그린다. */
  private void fillCommits() {
    commitModel.clear();
    clearDetail();
    if (loaded == null) return;
    String word = searchWord();
    graph = word.isEmpty();
    updateGraphWidth();
    for (History.Entry entry : loaded.entries()) {
      if (matches(entry, word)) commitModel.addElement(entry);
    }
  }

  private String searchWord() {
    return filter.getText().trim().toLowerCase(Locale.ROOT);
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
  }

  /** 가지 선 · 점 · 글자 밝기의 뜻. 목록 가운데에 풍선으로 띄우고 바깥을 누르면 닫는다. */
  private void showLegend() {
    String html = "<html>" + String.join("<br>", SyncBundle.message("history.legend.dot"), SyncBundle.message("history.legend.merge.dot"),
                                         SyncBundle.message("history.legend.split"), SyncBundle.message("history.legend.merge"),
                                         SyncBundle.message("history.legend.passing"), SyncBundle.message("history.legend.color"),
                                         SyncBundle.message("history.legend.hover")) + "</html>";
    JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(html, MessageType.INFO, null)
      .setHideOnClickOutside(true)
      .createBalloon()
      .showInCenterOf(commits);
  }

  /**
   * 가지 선 칸 위에서는 짧은 지연의 풍선을 목록에 걸고, 벗어나면 떼어 기본 풍선(작성자 · 날짜)으로 돌아간다.
   * 글이 바뀔 때만 새 풍선을 거는데, IdeTooltipManager 는 같은 풍선을 다시 띄우지 않기 때문이다.
   */
  private void updateGraphTip(MouseEvent e) {
    String text = graphTipAt(e);
    if (Objects.equals(text, graphTipText)) return;
    graphTipText = text;
    IdeTooltipManager tips = IdeTooltipManager.getInstance();
    if (text == null) {
      tips.setCustomTooltip(commits, null);
      tips.hideCurrent(null);
      return;
    }
    JBLabel label = new JBLabel(text);
    label.setBorder(JBUI.Borders.empty(GAP / 2));
    tips.setCustomTooltip(commits, new IdeTooltip(commits, e.getPoint(), label) {
      @Override public int getShowDelay() { return GRAPH_TIP_DELAY_MS; }
      @Override public int getInitialReshowDelay() { return GRAPH_TIP_DELAY_MS; }
    });
  }

  /**
   * 마우스 아래 가지 선의 뜻. 점이 있는 칸이면 이 커밋이 꺾인 선으로 이어진 커밋들(합쳐 들임 · 갈라져 나감 · 합쳐 감),
   * 곧게 지나가는 선이면 그 선이 닿는 아래 커밋. 가지 선 칸 밖이거나 선이 없는 칸이면 null.
   */
  private String graphTipAt(MouseEvent e) {
    int i = commits.locationToIndex(e.getPoint());
    if (!graph || loaded == null || i < 0 || i >= loaded.rows().size() || e.getX() >= graphWidth
        || !commits.getCellBounds(i, i).contains(e.getPoint())) return null;
    int lane = (e.getX() - JBUI.scale(GRAPH_PAD)) / JBUI.scale(LANE_W);
    History.Row row = loaded.rows().get(i);
    if (lane != row.col()) {
      int target = History.passingTarget(loaded.rows(), i, lane);
      return target < 0 ? null : SyncBundle.message("history.graph.passing", describe(loaded.entries().get(target)));
    }
    History.Entry entry = loaded.entries().get(i);
    List<String> lines = new ArrayList<>();
    if (entry.merge()) lines.add(SyncBundle.message("history.legend.merge.dot"));
    for (History.Link link : History.links(loaded.entries(), loaded.rows(), i)) {
      String key = switch (link.kind()) {
        case MERGED_FROM -> "history.graph.merged.from";
        case BRANCHED_TO -> "history.graph.branched.to";
        case MERGED_INTO -> "history.graph.merged.into";
      };
      lines.add(SyncBundle.message(key, describe(link.other())));
    }
    if (lines.isEmpty()) lines.add(SyncBundle.message("history.graph.straight"));
    return "<html>" + String.join("<br>", lines) + "</html>";
  }

  /** 풍선용 커밋 이름: 브랜치(있으면) · 짧은 해시 · 제목. 목록 밖이라 제목을 모르면 해시만. */
  private static String describe(History.Entry entry) {
    String id = entry.id().length() > SHORT_HASH ? entry.id().substring(0, SHORT_HASH) : "r" + entry.id();
    String subject = entry.subject();
    if (subject.length() > TIP_SUBJECT_MAX) subject = subject.substring(0, TIP_SUBJECT_MAX) + "…";
    String name = entry.refs().isEmpty() ? id : "[" + entry.refs().get(0) + "] " + id;
    return StringUtil.escapeXmlEntities(subject.isEmpty() ? name : name + " " + subject);
  }

  private static int laneCount(History.Row row) {
    int max = row.col();
    for (History.Seg s : row.top()) max = Math.max(max, Math.max(s.from(), s.to()));
    for (History.Seg s : row.bottom()) max = Math.max(max, Math.max(s.from(), s.to()));
    return max + 1;
  }

  private void clearDetail() {
    subject.setText("");
    message.setText("");
    message.setVisible(false);
    authorRow.setVisible(false);
    hash.setVisible(false);
    refs.setRefs(List.of(), null);
    filesTitle.setVisible(false);
    files.setListData(new History.FileChange[0]);
    files.getEmptyText().setText(SyncBundle.message("history.empty.select"));
  }

  private void showDetail() {
    History.Entry entry = commits.getSelectedValue();
    if (entry == null) {
      clearDetail();
      return;
    }
    // 첫 줄은 제목, 나머지는 본문(없으면 숨긴다).
    String text = entry.message().strip();
    int nl = text.indexOf('\n');
    String body = nl < 0 ? "" : text.substring(nl + 1).strip();
    subject.setText(entry.subject());
    message.setText(body);
    message.setVisible(!body.isEmpty());
    avatar.setIcon(new Avatar(entry.author(), JBUI.scale(AVATAR_BIG)));
    author.setText(entry.author());
    authorTime.setText(FULL.format(entry.time().atZone(ZONE)) + " · " + when(entry.time()));
    authorRow.setVisible(true);
    hash.setText(entry.id().length() > SHORT_HASH ? entry.id().substring(0, SHORT_HASH) : "r" + entry.id());
    hash.setVisible(true);
    refs.setRefs(entry.refs(), loaded == null ? null : loaded.branch());
    filesTitle.setText(SyncBundle.message("history.files", entry.files().size()));
    filesTitle.setVisible(true);
    files.setListData(entry.files().toArray(History.FileChange[]::new));
    files.getEmptyText().setText(SyncBundle.message("history.empty.files"));
  }

  /** 고른 파일의 커밋 직전 내용과 커밋 내용을 비교 창으로 연다. 한쪽에 없으면(추가 · 삭제 · 첫 커밋) 빈 내용. */
  private void showDiff() {
    History.Entry entry = commits.getSelectedValue();
    History.FileChange file = files.getSelectedValue();
    Loaded source = loaded;
    if (entry == null || file == null || source == null) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String before;
      String after;
      try {
        before = contentAt(source, entry, file, true);
        after = contentAt(source, entry, file, false);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      SwingUtilities.invokeLater(() -> {
        FileType type = FileTypeManager.getInstance().getFileTypeByFileName(new File(file.path()).getName());
        DiffContentFactory factory = DiffContentFactory.getInstance();
        DiffManager.getInstance().showDiff(project, new SimpleDiffRequest(
          file.path() + " (" + entry.id() + ")", factory.create(project, before, type), factory.create(project, after, type),
          SyncBundle.message("history.before"), entry.author() + " · " + FULL.format(entry.time().atZone(ZONE))));
      });
    });
  }

  /** Git 은 「해시:./경로」(작업 폴더 기준), SVN 은 「저장소 URL@리비전」. 그 시점에 파일이 없으면 빈 문자열. */
  private static String contentAt(Loaded source, History.Entry entry, History.FileChange file, boolean before) throws InterruptedException {
    try {
      if (source.target().vcs() == SyncPanel.Vcs.GIT) {
        return SyncPanel.git(source.target().dir(), "show", entry.id() + (before ? "^" : "") + ":./" + file.path());
      }
      long rev = Long.parseLong(entry.id()) - (before ? 1 : 0);
      return SyncPanel.run(source.target().dir(), "svn", "cat", source.svnRoot() + source.svnPrefix() + "/" + file.path() + "@" + rev);
    }
    catch (IOException | NumberFormatException e) {
      return "";
    }
  }

  /** 화면 글 묶음에 적힌 시간 모양. 오전 · 오후 글자는 history.locale 을 따른다. */
  private static DateTimeFormatter timeFormat(String key) {
    return DateTimeFormatter.ofPattern(SyncBundle.message(key), TIME_LOCALE);
  }

  /** 목록에 보일 시간: 한 시간 안은 「N분 전」, 오늘은 시각, 올해는 월-일 시각, 그 전은 날짜만(모양은 화면 글 묶음). */
  static String when(Instant time) {
    Duration ago = Duration.between(time, Instant.now());
    if (!ago.isNegative() && ago.toMinutes() < MINUTES_PER_HOUR) return SyncBundle.message("history.minutes.ago", Math.max(1, ago.toMinutes()));
    ZonedDateTime at = time.atZone(ZONE);
    LocalDate today = LocalDate.now(ZONE);
    if (at.toLocalDate().equals(today)) return TODAY.format(at);
    return (at.getYear() == today.getYear() ? THIS_YEAR : OLDER).format(at);
  }

  private static String initials(String name) {
    String trimmed = name.strip();
    if (trimmed.isEmpty()) return "?";
    int end = trimmed.offsetByCodePoints(0, Math.min(INITIALS, trimmed.codePointCount(0, trimmed.length())));
    return trimmed.substring(0, end);
  }

  private static Color colorOf(String key) {
    return PALETTE[Math.floorMod(key.hashCode(), PALETTE.length)];
  }

  private static Color laneColor(int lane) {
    return PALETTE[lane % PALETTE.length];
  }

  private static Color refColor(String ref, boolean head) {
    if (head) return HEAD_COLOR;
    if (ref.startsWith(TAG_PREFIX)) return TAG_COLOR;
    return ref.contains("/") ? REMOTE_COLOR : LOCAL_COLOR;
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

  /** 작성자 동그라미: 작성자마다 고정 색, 이름 앞 두 글자. */
  private static final class Avatar implements Icon {
    private final String name;
    private final int size;

    Avatar(String name, int size) {
      this.name = name;
      this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = smooth(g);
      g2.setColor(colorOf(name));
      g2.fillOval(x, y, size, size);
      g2.setColor(AVATAR_TEXT);
      g2.setFont(JBUI.Fonts.label().asBold().deriveFont(size * AVATAR_FONT_RATIO));
      FontMetrics fm = g2.getFontMetrics();
      String text = initials(name);
      g2.drawString(text, x + (size - fm.stringWidth(text)) / 2, y + (size + fm.getAscent() - fm.getDescent()) / 2);
      g2.dispose();
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }
  }

  /** 알약 줄: 위쪽 현재 브랜치, 오른쪽 칸의 이 커밋 브랜치 · 태그. head 와 같은 이름은 현재 브랜치 모양. */
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
        w += pillWidth(fm, ref, pillIcon(isHead, refColor(ref, isHead))) + JBUI.scale(GAP) / 2;
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
        Color color = refColor(ref, isHead);
        x += paintPill(this, g2, ref, color, pillIcon(isHead, color), x, 0) + JBUI.scale(GAP) / 2;
      }
      g2.dispose();
    }
  }

  /** 프로젝트(모듈) 아이콘 · 이름, 뒤에 흐린 Git · SVN 표시. */
  private static final class TargetRenderer extends ColoredListCellRenderer<Target> {
    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends Target> list, Target value, int index, boolean selected, boolean focused) {
      setIcon(AllIcons.Nodes.Module);
      append(value.name());
      append("  " + (value.vcs() == SyncPanel.Vcs.GIT ? "Git" : "SVN"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
    }
  }

  /** 커밋 한 줄: 가지 선 · 동그라미, 메시지 · 알약, 오른쪽에 파일 수 · 작성자 · 시간. */
  private final class CommitRenderer extends JComponent implements ListCellRenderer<History.Entry> {
    private History.Entry entry;
    private int index;
    private int last;
    private boolean selected;
    private Color background;
    private Color foreground;

    @Override
    public Component getListCellRendererComponent(JList<? extends History.Entry> list, History.Entry value, int index,
                                                  boolean selected, boolean focused) {
      this.entry = value;
      this.index = index;
      this.last = list.getModel().getSize() - 1;
      this.selected = selected;
      this.background = selected ? RenderingUtil.getSelectionBackground(list) : list.getBackground();
      this.foreground = selected ? RenderingUtil.getSelectionForeground(list) : list.getForeground();
      setFont(list.getFont());
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
      int cy = h / 2;
      g2.setColor(background);
      g2.fillRect(0, 0, w, h);

      History.Row row = graph && loaded != null && index < loaded.rows().size() ? loaded.rows().get(index) : null;
      int col = row == null ? 0 : row.col();
      Graphics2D lane = (Graphics2D)g2.create(0, 0, graphWidth, h);
      lane.setStroke(new BasicStroke(JBUI.scale(LINE_WIDTH)));
      if (row == null) {
        lane.setColor(laneColor(0));
        lane.drawLine(laneX(0), index == 0 ? cy : 0, laneX(0), index == last ? cy : h);
      }
      else {
        for (History.Seg s : row.top()) segment(lane, s, 0, cy);
        for (History.Seg s : row.bottom()) segment(lane, s, cy, h);
      }
      // 커밋은 가지 색 점, 합치기 커밋은 속이 빈 점.
      int x = laneX(col);
      int d = JBUI.scale(DOT);
      lane.setColor(entry.merge() ? background : laneColor(col));
      lane.fillOval(x - d / 2, cy - d / 2, d, d);
      lane.setColor(laneColor(col));
      lane.drawOval(x - d / 2, cy - d / 2, d, d);
      lane.dispose();

      g2.setFont(getFont());
      FontMetrics fm = g2.getFontMetrics();
      int baseline = (h + fm.getAscent() - fm.getDescent()) / 2;
      int gap = JBUI.scale(GAP);
      // 오른쪽: 회색 작성자 · 짧은 시간을 고정 폭 칸에(시간은 오른쪽 맞춤). 합치기 커밋은 제목도 흐리게(고른 줄은 선택 글자색).
      Color text = entry.merge() && !selected ? DIM : foreground;
      Color meta = selected ? foreground : DIM;
      String time = when(entry.time());
      g2.setColor(meta);
      g2.drawString(time, w - gap * 2 - fm.stringWidth(time), baseline);
      int right = w - gap * 3 - JBUI.scale(TIME_COL_W) - JBUI.scale(AUTHOR_COL_W);
      g2.drawString(fit(entry.author(), fm, JBUI.scale(AUTHOR_COL_W)), right, baseline);

      int textX = graphWidth + gap;
      int room = right - gap - textX;
      String subject = fit(entry.subject(), fm, room);
      g2.setColor(text);
      g2.drawString(subject, textX, baseline);
      int pillX = textX + fm.stringWidth(subject) + gap;
      String head = loaded == null ? null : loaded.branch();
      for (String ref : entry.refs()) {
        boolean isHead = ref.equals(head);
        Color color = refColor(ref, isHead);
        Icon icon = pillIcon(isHead, color);
        if (pillX + pillWidth(fm, ref, icon) > right - gap) break;
        pillX += paintPill(this, g2, ref, color, icon, pillX, cy - JBUI.scale(PILL_H) / 2) + gap / 2;
      }
      g2.dispose();
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
