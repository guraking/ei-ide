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
import com.intellij.openapi.ui.ComboBox;
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
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
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
 * 아래쪽 「Git Log」 창. 위 도구줄에서 프로젝트(모듈)를 고르면 그 폴더 안을 바꾼 최근 커밋을 표로 보여 준다.
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
  private static final float LINE_WIDTH = 1.5f;
  // 표와 오른쪽 상세 칸의 폭 비율.
  private static final float TABLE_RATIO = 0.62f;

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

  /** 도구줄 저장소 콤보 한 줄. dir 은 모듈의 첫 콘텐츠 루트. */
  private record Target(String name, File dir, SyncPanel.Vcs vcs) {
    @Override public String toString() { return name; }
  }

  /** 읽어 온 기록. branch 는 Git 현재 브랜치 · SVN 저장소 경로. svnRoot · svnPrefix 는 SVN 비교 창용. more 는 다음 묶음이 남았을 수 있음. */
  private record Loaded(Target target, String branch, String svnRoot, String svnPrefix,
                        List<History.Entry> entries, List<History.Row> rows, boolean more) {}

  private final Project project;
  private final ComboBox<Target> targets = new ComboBox<>();
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
      return entry.author() + " · " + ROW_TIME.format(entry.time().atZone(ZONE));
    }
  };
  private final JBList<History.FileChange> files = new JBList<>();
  private final SearchTextField filter = new SearchTextField(false);
  private final Pill branch = new Pill();
  private final JLabel status = new JLabel(" ");
  private final JBLabel subject = new JBLabel();
  private final JTextArea message = new JTextArea();
  // 흐린 「작성자 · 날짜 · 」 뒤에 해시(누르면 복사).
  private final JBLabel meta = new JBLabel();
  private final ActionLink hash = new ActionLink();
  private final JComponent metaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
  // 고른 커밋의 꺾인 선이 어느 커밋과 이어지는지. 긴 글은 「…」로 잘리고 전체는 풍선 도움말.
  private final JBLabel bend = new JBLabel();
  private final TitledSeparator filesTitle = new TitledSeparator();
  private final Font hashFont = new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().getSize());
  private Loaded loaded;
  private boolean loadingMore;
  private boolean graph;
  private int graphWidth;

  ProjectHistoryPanel(Project project) {
    super(new BorderLayout());
    this.project = project;

    targets.setRenderer(new TargetRenderer());
    targets.addActionListener(e -> load());
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

    // 도구줄: 왼쪽에 저장소 콤보 · 현재 브랜치 알약 · 새로고침 · 설명 · 「최근 N개」, 오른쪽 끝에 찾기 칸.
    // 창 머리 아래 선은 IntelliJ 가 그으므로 안쪽 칸에 테두리를 더하면 선이 두 겹이 된다.
    DefaultActionGroup actions = new DefaultActionGroup(
      DumbAwareAction.create(SyncBundle.message("history.refresh"), AllIcons.Actions.Refresh, e -> reloadTargets()),
      DumbAwareAction.create(SyncBundle.message("history.legend.action"), AllIcons.General.ContextHelp, e -> showLegend()));
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true);
    toolbar.setTargetComponent(this);
    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP / 2)));
    left.add(targets);
    left.add(branch);
    left.add(toolbar.getComponent());
    left.add(status);
    status.setForeground(DIM);
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
    add(top, BorderLayout.NORTH);
    add(main, BorderLayout.CENTER);

    reloadTargets();
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
    for (JComponent c : List.of(subject, message, metaRow, bend, filesTitle)) {
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

  /** 모듈 콤보를 이름 순으로 다시 채우고 고른 모듈의 기록을 다시 읽는다. 모듈이 없으면 프로젝트 폴더 하나. 같은 폴더를 가리키는 모듈은 한 번만. 고른 것은 그대로 둔다. */
  private void reloadTargets() {
    Target selected = selectedTarget();
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
    // setModel 은 첫 항목을 고르되 콤보 동작 이벤트를 내지 않고, setSelectedItem 은 낸다(→ load). 그래서 되고를 때만 따로 읽는다.
    targets.setModel(new DefaultComboBoxModel<>(byDir.values().toArray(Target[]::new)));
    commits.getEmptyText().setText(SyncBundle.message(byDir.isEmpty() ? "history.empty.targets" : "history.empty.commits"));
    if (selected != null && byDir.containsValue(selected)) targets.setSelectedItem(selected);
    else load();
  }

  private Target selectedTarget() {
    return (Target)targets.getSelectedItem();
  }

  private History.Entry selectedEntry() {
    int row = commits.getSelectedRow();
    return row < 0 || row >= shown.size() ? null : shown.get(row);
  }

  private void load() {
    Target target = selectedTarget();
    if (target == null) return;
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
        if (!target.equals(selectedTarget())) return;  // 읽는 동안 다른 프로젝트를 골랐다.
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
        int first = shown.size();
        for (History.Entry entry : done.entries().subList(before.entries().size(), done.entries().size())) {
          if (matches(entry, word)) shown.add(entry);
        }
        if (shown.size() > first) commitModel.fireTableRowsInserted(first, shown.size() - 1);
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

  /** 찾기 글로 표를 다시 채운다. 찾기 칸이 비어 있을 때만 가지 선을 그린다. */
  private void fillCommits() {
    shown.clear();
    clearDetail();
    if (loaded != null) {
      String word = searchWord();
      graph = word.isEmpty();
      updateGraphWidth();
      for (History.Entry entry : loaded.entries()) {
        if (matches(entry, word)) shown.add(entry);
      }
    }
    commitModel.fireTableDataChanged();
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
    fixWidth(commits.getColumnModel().getColumn(COL_GRAPH), graphWidth);
  }

  /** 가지 선 · 점 · 글자 밝기의 뜻. 표 가운데에 풍선으로 띄우고 바깥을 누르면 닫는다. */
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
    meta.setToolTipText(FULL.format(entry.time().atZone(ZONE)));
    hash.setText(shortId(entry));
    metaRow.setVisible(true);
    // 찾기로 줄인 목록은 가지 선이 없어 꺾임도 없다. 가지 선을 그릴 때는 표 순서가 읽어 온 순서와 같다.
    bend.setText(graph ? bendText(commits.getSelectedRow()) : "");
    bend.setToolTipText(bend.getText().isEmpty() ? null : bend.getText());
    bend.setVisible(!bend.getText().isEmpty());
    filesTitle.setText(SyncBundle.message("history.files", entry.files().size()));
    filesTitle.setVisible(true);
    files.setListData(entry.files().toArray(History.FileChange[]::new));
    files.getEmptyText().setText(SyncBundle.message("history.empty.files"));
  }

  /** 고른 파일의 커밋 직전 내용과 커밋 내용을 비교 창으로 연다. 한쪽에 없으면(추가 · 삭제 · 첫 커밋) 빈 내용. */
  private void showDiff() {
    History.Entry entry = selectedEntry();
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

  /** 표에 보일 시간: 한 시간 안은 「N분 전」, 오늘 · 어제는 「오늘 HH:mm」 · 「어제 HH:mm」, 올해는 월-일 시각, 그 전은 날짜만(모양은 화면 글 묶음). */
  static String when(Instant time) {
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
      if (value == null) return;
      setIcon(AllIcons.Nodes.Module);
      append(value.name());
      append("  " + (value.vcs() == SyncPanel.Vcs.GIT ? "Git" : "SVN"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
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
      String fitted = fit(text, fm, room);
      g2.drawString(fitted, gap, baseline);
      if (column != COL_MESSAGE) return;
      int pillX = gap + fm.stringWidth(fitted) + gap;
      int cy = h / 2;
      String head = loaded == null ? null : loaded.branch();
      for (String ref : entry.refs()) {
        boolean isHead = ref.equals(head);
        Color color = refColor(ref, isHead);
        Icon icon = pillIcon(isHead, color);
        if (pillX + pillWidth(fm, ref, icon) > w - gap) break;
        pillX += paintPill(this, g2, ref, color, icon, pillX, cy - JBUI.scale(PILL_H) / 2) + gap / 2;
      }
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
