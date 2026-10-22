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
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
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
 * 위쪽은 찾기 칸과 새로고침뿐이다. 찾기로 거르면 사이가 빠지므로 가지 선 대신 한 줄로 그린다. 기존 Git 로그 창은 그대로 둔다.
 * Git 은 git log, SVN 은 svn log 를 작업 스레드에서 돌린다(SyncPanel.run 과 같은 실행 · 시간 제한). SVN 은 한 줄 기록이다.
 */
final class ProjectHistoryPanel extends JPanel {
  static final String ID = "ProjectHistory";
  private static final int LOG_LIMIT = 100;
  private static final ZoneId ZONE = ZoneId.systemDefault();
  private static final Locale TIME_LOCALE = Locale.forLanguageTag(SyncBundle.message("history.locale"));
  private static final DateTimeFormatter TODAY = timeFormat("history.time.today");
  private static final DateTimeFormatter THIS_YEAR = timeFormat("history.time.year");
  private static final DateTimeFormatter OLDER = timeFormat("history.time.older");
  private static final DateTimeFormatter FULL = timeFormat("history.time.full");
  private static final DateTimeFormatter ROW_TIME = timeFormat("history.time.row");
  private static final int MINUTES_PER_HOUR = 60;
  private static final int SHORT_HASH = 7;
  private static final int INITIALS = 2;

  // 크기(JBUI.scale 전). 목록 줄 · 가지 칸 · 점은 IntelliJ 기본 Log 탭 비율을 따른다.
  private static final int ROW_H = 26;
  private static final int LANE_W = 16;
  private static final int GRAPH_PAD = 8;
  private static final int MAX_LANES = 12;
  private static final int DOT = 7;
  private static final int AVATAR_BIG = 40;
  private static final int PILL_H = 18;
  private static final int GAP = 8;
  private static final int SEARCH_COLUMNS = 30;
  private static final float LINE_WIDTH = 1.5f;
  // 동그라미 지름 대비 머리글자 크기.
  private static final float AVATAR_FONT_RATIO = 0.4f;

  // 색. 가지 선 · 작성자 동그라미는 EI Glass 분홍을 첫 색으로 한 고정 팔레트, 알약은 글자색 + 옅은 바탕 · 테두리.
  // 유리 디자인 분류 색 10개. 이웃한 가지끼리 비슷한 색이 붙지 않도록 분홍 · 파랑 · 초록 · 주황 … 순으로 둔다.
  private static final Color[] PALETTE = {
    new Color(0xFF375F), new Color(0x0A84FF), new Color(0x30D158), new Color(0xFF9F0A), new Color(0xBF5AF2),
    new Color(0x40C8C0), new Color(0xFFD60A), new Color(0x64D2FF), new Color(0x7D7AFF), new Color(0xFF6482)};
  private static final Color SELECTED_ROW = new Color(0x30FF375F, true);
  private static final Color AVATAR_TEXT = new Color(0x111111);
  private static final Color LOCAL_BRANCH = new Color(0x5FE39B);
  private static final Color REMOTE_BRANCH = new Color(0x8FB3FF);
  private static final Color TAG = new Color(0xFFD36B);
  private static final Color SVN_BADGE = new Color(0x7FA8FF);
  private static final Color GIT_BADGE = new Color(0xFF8A6A);
  private static final int PILL_FILL_ALPHA = 0x22;
  private static final int PILL_BORDER_ALPHA = 0x66;
  private static final String TAG_PREFIX = "tag: ";
  private static final Map<String, Color> STATUS_COLORS = Map.of(
    "M", new Color(0xFFC061), "A", new Color(0x5FE39B), "D", new Color(0xFF7A95), "R", new Color(0x7FB3FF));
  private static final Color DIM = JBUI.CurrentTheme.ContextHelp.FOREGROUND;

  /** 왼쪽 목록 한 줄. dir 은 모듈의 첫 콘텐츠 루트. */
  private record Target(String name, File dir, SyncPanel.Vcs vcs) {
    @Override public String toString() { return name; }
  }

  /** 읽어 온 기록. branch 는 Git 현재 브랜치 · SVN 저장소 경로. svnRoot · svnPrefix 는 SVN 비교 창용. */
  private record Loaded(Target target, String branch, String svnRoot, String svnPrefix,
                        List<History.Entry> entries, List<History.Row> rows) {}

  private final Project project;
  private final JBList<Target> targets = new JBList<>();
  private final DefaultListModel<History.Entry> commitModel = new DefaultListModel<>();
  private final JBList<History.Entry> commits = new JBList<>(commitModel);
  private final JBList<History.FileChange> files = new JBList<>();
  private final SearchTextField filter = new SearchTextField(false);
  private final JLabel projectName = new JLabel(" ");
  private final Pill branch = new Pill();
  private final JLabel status = new JLabel(" ");
  private final JLabel author = new JLabel(" ");
  private final JButton hash = new JButton();
  private final JTextArea message = new JTextArea();
  private final JLabel filesTitle = new JLabel(" ");
  private Loaded loaded;
  private boolean graph;
  private int graphWidth;

  ProjectHistoryPanel(Project project) {
    super(new BorderLayout());
    this.project = project;

    targets.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    targets.setCellRenderer(new TargetRenderer());
    targets.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) load(); });
    commits.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    commits.setFixedCellHeight(JBUI.scale(ROW_H));
    commits.setCellRenderer(new CommitRenderer());
    commits.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetail(); });
    files.setCellRenderer(new FileRenderer());
    files.addMouseListener(new MouseAdapter() {
      @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) showDiff(); }
    });
    filter.getTextEditor().setColumns(SEARCH_COLUMNS);
    filter.addDocumentListener(new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) { fillCommits(); }
    });
    hash.addActionListener(e -> {
      History.Entry entry = commits.getSelectedValue();
      if (entry != null) CopyPasteManager.getInstance().setContents(new StringSelection(entry.id()));
    });

    JButton refresh = new JButton(SyncBundle.message("history.refresh"));
    refresh.addActionListener(e -> { reloadTargets(); load(); });
    projectName.setFont(JBUI.Fonts.label().biggerOn(2).asBold());
    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP / 2)));
    top.add(projectName);
    top.add(branch);
    top.add(filter);
    top.add(refresh);
    top.add(status);
    status.setForeground(DIM);

    JPanel center = new JPanel(new BorderLayout());
    center.add(top, BorderLayout.NORTH);
    center.add(new JBScrollPane(commits), BorderLayout.CENTER);

    JBSplitter right = new JBSplitter(false, 0.7f);
    right.setFirstComponent(center);
    right.setSecondComponent(detailPanel());
    JBSplitter main = new JBSplitter(false, 0.16f);
    main.setFirstComponent(new JBScrollPane(targets));
    main.setSecondComponent(right);
    add(main, BorderLayout.CENTER);

    reloadTargets();
  }

  /** 오른쪽: 작성자 동그라미 · 이름 · 시간, 해시(누르면 복사), 전체 메시지, 바뀐 파일. */
  private JComponent detailPanel() {
    author.setFont(JBUI.Fonts.label().biggerOn(1).asBold());
    author.setIconTextGap(JBUI.scale(GAP + GAP / 2));
    hash.setToolTipText(SyncBundle.message("history.copy"));
    message.setEditable(false);
    message.setLineWrap(true);
    message.setWrapStyleWord(true);
    message.setOpaque(false);
    message.setFont(JBUI.Fonts.label().biggerOn(1));
    filesTitle.setForeground(DIM);
    JLabel hint = new JLabel(SyncBundle.message("history.hint"));
    hint.setForeground(DIM);

    JPanel head = new JPanel();
    head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
    for (JComponent c : List.of(author, hash, message, filesTitle)) {
      c.setAlignmentX(LEFT_ALIGNMENT);
      head.add(c);
      head.add(Box.createVerticalStrut(JBUI.scale(GAP)));
    }
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(JBUI.Borders.empty(GAP * 2));
    panel.add(head, BorderLayout.NORTH);
    panel.add(new JBScrollPane(files), BorderLayout.CENTER);
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
        result = read(target);
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
        branch.setText(done == null ? "" : done.branch());
        fillCommits();
        status.setText(done == null ? failure : SyncBundle.message("history.count", done.entries().size()));
      });
    });
  }

  private static Loaded read(Target target) throws IOException, InterruptedException {
    File dir = target.dir();
    if (target.vcs() == SyncPanel.Vcs.GIT) {
      List<History.Entry> entries = History.parseGitLog(SyncPanel.git(dir, History.gitLogArgs(LOG_LIMIT)));
      return new Loaded(target, gitOrEmpty(dir, "rev-parse", "--abbrev-ref", "HEAD"), null, null, entries, History.layout(entries));
    }
    String[] info = History.parseSvnInfo(SyncPanel.run(dir, "svn", "info", "--xml"));
    String xml = SyncPanel.run(dir, "svn", "log", "--xml", "-v", "-l", String.valueOf(LOG_LIMIT));
    List<History.Entry> entries = History.parseSvnLog(xml, info[1]);
    return new Loaded(target, info[1], info[0], info[1], entries, History.layout(entries));
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
    String word = filter.getText().trim().toLowerCase(Locale.ROOT);
    graph = word.isEmpty();
    int lanes = 1;
    if (graph) {
      for (History.Row row : loaded.rows()) lanes = Math.max(lanes, laneCount(row));
    }
    graphWidth = JBUI.scale(Math.min(lanes, MAX_LANES) * LANE_W + GRAPH_PAD * 2);
    for (History.Entry entry : loaded.entries()) {
      if (!word.isEmpty() && !entry.subject().toLowerCase(Locale.ROOT).contains(word)
          && !entry.author().toLowerCase(Locale.ROOT).contains(word)) continue;
      commitModel.addElement(entry);
    }
  }

  private static int laneCount(History.Row row) {
    int max = row.col();
    for (History.Seg s : row.top()) max = Math.max(max, Math.max(s.from(), s.to()));
    for (History.Seg s : row.bottom()) max = Math.max(max, Math.max(s.from(), s.to()));
    return max + 1;
  }

  private void clearDetail() {
    author.setText(" ");
    author.setIcon(null);
    hash.setVisible(false);
    message.setText("");
    filesTitle.setText(" ");
    files.setListData(new History.FileChange[0]);
  }

  private void showDetail() {
    History.Entry entry = commits.getSelectedValue();
    if (entry == null) {
      clearDetail();
      return;
    }
    author.setIcon(new Avatar(entry.author(), JBUI.scale(AVATAR_BIG)));
    author.setText("<html>" + escape(entry.author()) + "<br><font color='#" + hex(DIM) + "' size='-1'>"
                   + FULL.format(entry.time().atZone(ZONE)) + " · " + when(entry.time()) + "</font></html>");
    hash.setText(entry.id().length() > SHORT_HASH ? entry.id().substring(0, SHORT_HASH) : "r" + entry.id());
    hash.setVisible(true);
    message.setText(entry.message());
    filesTitle.setText(SyncBundle.message("history.files", entry.files().size()));
    files.setListData(entry.files().toArray(History.FileChange[]::new));
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

  private static Color refColor(String ref) {
    if (ref.startsWith(TAG_PREFIX)) return TAG;
    return ref.contains("/") ? REMOTE_BRANCH : LOCAL_BRANCH;
  }

  private static Color alpha(Color c, int a) {
    return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
  }

  private static String hex(Color c) {
    return String.format("%06x", c.getRGB() & 0xFFFFFF);
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** 알약 하나를 그리고 폭을 돌려준다. y 는 알약 위쪽. */
  private static int paintPill(Graphics2D g, String text, Color color, int x, int y) {
    FontMetrics fm = g.getFontMetrics();
    int h = JBUI.scale(PILL_H);
    int w = fm.stringWidth(text) + JBUI.scale(GAP) * 2;
    g.setColor(alpha(color, PILL_FILL_ALPHA));
    g.fillRoundRect(x, y, w, h, h, h);
    g.setColor(alpha(color, PILL_BORDER_ALPHA));
    g.drawRoundRect(x, y, w - 1, h - 1, h, h);
    g.setColor(color);
    g.drawString(text, x + JBUI.scale(GAP), y + (h + fm.getAscent() - fm.getDescent()) / 2);
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

  /** 위쪽 현재 브랜치 알약. */
  private static final class Pill extends JComponent {
    private String text = "";

    Pill() {
      setFont(JBUI.Fonts.label());
    }

    void setText(String text) {
      this.text = text;
      revalidate();
      repaint();
    }

    @Override
    public Dimension getPreferredSize() {
      if (text.isEmpty()) return new Dimension(0, 0);
      return new Dimension(getFontMetrics(getFont()).stringWidth(text) + JBUI.scale(GAP) * 2 + 1, JBUI.scale(PILL_H) + 1);
    }

    @Override
    protected void paintComponent(Graphics g) {
      if (text.isEmpty()) return;
      Graphics2D g2 = smooth(g);
      g2.setFont(getFont());
      paintPill(g2, text, LOCAL_BRANCH, 0, 0);
      g2.dispose();
    }
  }

  /** 프로젝트 이름과 Git · SVN 표시. */
  private static final class TargetRenderer extends JPanel implements ListCellRenderer<Target> {
    private final JLabel name = new JLabel();
    private final JLabel badge = new JLabel();

    TargetRenderer() {
      super(new BorderLayout());
      setBorder(JBUI.Borders.empty(GAP / 2 + 2, GAP + 2));
      add(name, BorderLayout.CENTER);
      add(badge, BorderLayout.EAST);
      badge.setFont(JBUI.Fonts.smallFont());
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Target> list, Target value, int index, boolean selected, boolean focused) {
      name.setText(value.name());
      boolean git = value.vcs() == SyncPanel.Vcs.GIT;
      badge.setText(git ? "Git" : "SVN");
      badge.setForeground(git ? GIT_BADGE : SVN_BADGE);
      setBackground(selected ? SELECTED_ROW : list.getBackground());
      name.setForeground(list.getForeground());
      return this;
    }
  }

  /** 커밋 한 줄: 가지 선 · 동그라미, 메시지 · 알약, 오른쪽에 파일 수 · 작성자 · 시간. */
  private final class CommitRenderer extends JComponent implements ListCellRenderer<History.Entry> {
    private History.Entry entry;
    private int index;
    private int last;
    private boolean selected;
    private Color background;

    @Override
    public Component getListCellRendererComponent(JList<? extends History.Entry> list, History.Entry value, int index,
                                                  boolean selected, boolean focused) {
      this.entry = value;
      this.index = index;
      this.last = list.getModel().getSize() - 1;
      this.selected = selected;
      this.background = list.getBackground();
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
      g2.setColor(selected ? SELECTED_ROW : background);
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
      // 오른쪽: 굵은 작성자, 그 뒤 날짜 전체. 합치기 커밋은 둘 다 흐리게.
      String time = ROW_TIME.format(entry.time().atZone(ZONE));
      int right = w - gap * 2 - fm.stringWidth(time);
      g2.setColor(entry.merge() ? DIM : UIUtil.getListForeground());
      g2.drawString(time, right, baseline);
      Font bold = getFont().deriveFont(Font.BOLD);
      FontMetrics bm = g2.getFontMetrics(bold);
      right -= gap + bm.stringWidth(entry.author());
      g2.setFont(bold);
      g2.drawString(entry.author(), right, baseline);
      g2.setFont(getFont());

      int textX = graphWidth + gap;
      int room = right - gap - textX;
      String subject = fit(entry.subject(), fm, room);
      g2.setColor(entry.merge() ? DIM : UIUtil.getListForeground());
      g2.drawString(subject, textX, baseline);
      int pillX = textX + fm.stringWidth(subject) + gap;
      for (String ref : entry.refs()) {
        int pw = fm.stringWidth(ref) + gap * 2;
        if (pillX + pw > right - gap) break;
        pillX += paintPill(g2, ref, refColor(ref), pillX, cy - JBUI.scale(PILL_H) / 2) + gap / 2;
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

  /** 바뀐 파일: 색 상자 안 상태 글자, 파일 이름, 흐린 상위 폴더 이름. */
  private static final class FileRenderer extends JPanel implements ListCellRenderer<History.FileChange> {
    private final JLabel status = new JLabel("", SwingConstants.CENTER);
    private final JLabel name = new JLabel();
    private final JLabel folder = new JLabel();

    FileRenderer() {
      super(new BorderLayout(JBUI.scale(GAP), 0));
      setBorder(JBUI.Borders.empty(GAP / 2, GAP));
      status.setOpaque(true);
      status.setFont(JBUI.Fonts.smallFont().asBold());
      status.setPreferredSize(JBUI.size(PILL_H, PILL_H));
      folder.setForeground(DIM);
      add(status, BorderLayout.WEST);
      add(name, BorderLayout.CENTER);
      add(folder, BorderLayout.EAST);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends History.FileChange> list, History.FileChange value, int index,
                                                  boolean selected, boolean focused) {
      Color color = STATUS_COLORS.getOrDefault(value.status(), DIM);
      status.setText(value.status());
      status.setForeground(color);
      status.setBackground(alpha(color, PILL_BORDER_ALPHA / 2));
      File file = new File(value.path());
      name.setText(file.getName());
      name.setToolTipText(value.path());
      File parent = file.getParentFile();
      folder.setText(parent == null ? "" : parent.getName());
      setBackground(selected ? SELECTED_ROW : list.getBackground());
      name.setForeground(list.getForeground());
      return this;
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
