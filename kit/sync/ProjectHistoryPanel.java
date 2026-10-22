package kinggura.sync;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * 아래쪽 「기록」 창. 왼쪽에서 프로젝트(모듈)를 고르면 그 폴더 안을 바꾼 최근 커밋만 시간 · 작성자 · 메시지 한 줄씩 보여 준다.
 * Git 로그 창과 달리 가지 그림 · 가지 목록이 없고 합치기(Merge) 커밋은 뺀다. 커밋을 고르면 바뀐 파일, 파일을 두 번 누르면 비교 창.
 * Git 은 git log, SVN 은 svn log 를 작업 스레드에서 돌린다(SyncPanel.run 과 같은 실행 · 시간 제한).
 */
final class ProjectHistoryPanel extends JPanel {
  static final String ID = "ProjectHistory";
  static final String TITLE = "기록";
  private static final int LOG_LIMIT = 100;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private static final String[] COLUMNS = {"시간", "작성자", "메시지"};
  private static final int TIME_WIDTH = 130;
  private static final int AUTHOR_WIDTH = 110;

  /** 왼쪽 목록 한 줄. dir 은 모듈의 첫 콘텐츠 루트. */
  private record Target(String name, File dir) {
    @Override public String toString() { return name; }
  }

  /** svnRoot · svnPrefix 는 SVN 일 때만(비교 창에서 svn cat 할 URL). */
  private record Loaded(Target target, SyncPanel.Vcs vcs, String svnRoot, String svnPrefix, List<History.Entry> entries) {}

  private final Project project;
  private final JBList<Target> targets = new JBList<>();
  private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
    @Override public boolean isCellEditable(int row, int column) { return false; }
  };
  private final JBTable table = new JBTable(model);
  private final JBList<History.FileChange> files = new JBList<>();
  private final SearchTextField filter = new SearchTextField(false);
  private final JLabel status = new JLabel(" ");
  private Loaded loaded;
  private List<History.Entry> shown = List.of();

  ProjectHistoryPanel(Project project) {
    super(new BorderLayout());
    this.project = project;

    targets.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    targets.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) load(); });
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getColumnModel().getColumn(0).setPreferredWidth(TIME_WIDTH);
    table.getColumnModel().getColumn(0).setMaxWidth(TIME_WIDTH);
    table.getColumnModel().getColumn(1).setPreferredWidth(AUTHOR_WIDTH);
    table.getColumnModel().getColumn(1).setMaxWidth(AUTHOR_WIDTH * 2);
    table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showFiles(); });
    files.addMouseListener(new MouseAdapter() {
      @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) showDiff(); }
    });
    filter.addDocumentListener(new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) { fillTable(); }
    });

    JButton refresh = new JButton("새로고침");
    refresh.addActionListener(e -> { reloadTargets(); load(); });
    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
    top.add(new JLabel("메시지 · 작성자 찾기"));
    top.add(filter);
    top.add(refresh);
    top.add(status);

    JBSplitter right = new JBSplitter(false, 0.6f);
    right.setFirstComponent(new JBScrollPane(table));
    right.setSecondComponent(new JBScrollPane(files));
    JPanel center = new JPanel(new BorderLayout());
    center.add(top, BorderLayout.NORTH);
    center.add(right, BorderLayout.CENTER);
    JBSplitter main = new JBSplitter(false, 0.18f);
    main.setFirstComponent(new JBScrollPane(targets));
    main.setSecondComponent(center);
    add(main, BorderLayout.CENTER);

    reloadTargets();
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
      byDir.putIfAbsent(dir, new Target(module.getName(), dir));
    }
    if (byDir.isEmpty() && project.getBasePath() != null) {
      File base = new File(project.getBasePath());
      byDir.put(base, new Target(project.getName(), base));
    }
    targets.setListData(byDir.values().toArray(Target[]::new));
    if (selected != null) targets.setSelectedValue(selected, true);
  }

  private void load() {
    Target target = targets.getSelectedValue();
    if (target == null) return;
    status.setText(target.name() + " 기록을 읽는 중…");
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
      String message = error;
      SwingUtilities.invokeLater(() -> {
        if (target != targets.getSelectedValue()) return;  // 읽는 동안 다른 프로젝트를 골랐다.
        loaded = done;
        fillTable();
        status.setText(done == null ? message : done.vcs() + " · 최근 " + done.entries().size() + "개");
      });
    });
  }

  private static Loaded read(Target target) throws IOException, InterruptedException {
    SyncPanel.Vcs vcs = SyncPanel.unmappedVcs(target.dir());
    if (vcs == SyncPanel.Vcs.GIT) {
      return new Loaded(target, vcs, null, null, History.parseGitLog(SyncPanel.git(target.dir(), History.gitLogArgs(LOG_LIMIT))));
    }
    String[] info = History.parseSvnInfo(SyncPanel.run(target.dir(), "svn", "info", "--xml"));
    String xml = SyncPanel.run(target.dir(), "svn", "log", "--xml", "-v", "-l", String.valueOf(LOG_LIMIT));
    return new Loaded(target, vcs, info[0], info[1], History.parseSvnLog(xml, info[1]));
  }

  /** 찾기 글자가 메시지나 작성자에 들어 있는 커밋만(대소문자 무시). */
  private void fillTable() {
    model.setRowCount(0);
    files.setListData(new History.FileChange[0]);
    List<History.Entry> result = new ArrayList<>();
    if (loaded != null) {
      String word = filter.getText().trim().toLowerCase(Locale.ROOT);
      for (History.Entry entry : loaded.entries()) {
        if (word.isEmpty() || entry.message().toLowerCase(Locale.ROOT).contains(word)
            || entry.author().toLowerCase(Locale.ROOT).contains(word)) {
          result.add(entry);
          model.addRow(new Object[]{TIME.format(entry.time()), entry.author(), entry.message()});
        }
      }
    }
    shown = result;
  }

  private History.Entry selectedEntry() {
    int row = table.getSelectedRow();
    return row < 0 || row >= shown.size() ? null : shown.get(row);
  }

  private void showFiles() {
    History.Entry entry = selectedEntry();
    files.setListData(entry == null ? new History.FileChange[0] : entry.files().toArray(History.FileChange[]::new));
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
          "이전", entry.author() + " · " + TIME.format(entry.time())));
      });
    });
  }

  /** Git 은 「해시:./경로」(작업 폴더 기준), SVN 은 「저장소 URL@리비전」. 그 시점에 파일이 없으면 빈 문자열. */
  private static String contentAt(Loaded source, History.Entry entry, History.FileChange file, boolean before) throws InterruptedException {
    try {
      if (source.vcs() == SyncPanel.Vcs.GIT) {
        return SyncPanel.git(source.target().dir(), "show", entry.id() + (before ? "^" : "") + ":./" + file.path());
      }
      long rev = Long.parseLong(entry.id()) - (before ? 1 : 0);
      return SyncPanel.run(source.target().dir(), "svn", "cat", source.svnRoot() + source.svnPrefix() + "/" + file.path() + "@" + rev);
    }
    catch (IOException | NumberFormatException e) {
      return "";
    }
  }

  /** 아래쪽 「기록」 창. 창 id 는 설정 호환을 위해 영어, 표시 이름만 한국어. */
  public static final class Factory implements ToolWindowFactory, DumbAware {
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
      toolWindow.setStripeTitle(TITLE);
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
      toolWindow.getContentManager().addContent(ContentFactory.getInstance().createContent(new ProjectHistoryPanel(project), "", false));
    }
  }
}
