package kinggura.sync;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.ScreenUtil;
import com.intellij.ui.ScrollingUtil;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 이클립스 Clone 마법사의 「Initial branch」: Git Clone 이 끝나면 서버 브랜치 가운데 받을 것을 고르게 하고, 고른 브랜치로 바꾼다(원격 추적 설정).
 * Clone 은 서버 브랜치를 모두 받아 오므로 고르기와 바꾸기는 서버에 다시 묻지 않는다.
 * 기본 선택은 Clone 이 꺼낸 서버 기본 브랜치, 내 이름(git user.name · 메일 앞부분 · Git 로그인 아이디)이 들어간 브랜치는 목록 위로 올린다.
 * 서버 브랜치가 하나뿐이거나 빈 저장소, 기본 브랜치를 알 수 없으면 창을 띄우지 않는다. 취소하면 기본 브랜치 그대로다.
 * 바꾸기에 실패하면 알리고 기본 브랜치로 둔다(Clone 결과는 그대로).
 */
final class CloneBranch {
  private static final Logger LOG = Logger.getInstance(CloneBranch.class);
  private static final String REMOTES = "refs/remotes/";
  private static final String HEAD = "HEAD";
  // 이보다 짧은 이름 조각은 거의 모든 브랜치에 들어가 「내 브랜치」 구분이 안 된다(예: 「me」 · 「k」).
  private static final int MIN_NAME_PART = 3;

  private CloneBranch() {}

  /** 서버 기본 브랜치(예: origin, main)와 서버 브랜치 목록. */
  record Remote(String name, String defaultBranch, List<String> branches) {}

  /** Clone 리스너 스레드에서 부른다(EDT 아님). Git 저장소가 아니면 아무것도 하지 않는다. */
  static void choose(@Nullable Project project, File dir) {
    if (!new File(dir, ".git").exists()) return;
    Remote remote;
    List<String> mine;
    try {
      remote = remote(dir);
      if (remote == null || remote.branches().size() < 2) return;
      mine = mine(remote.branches(), nameParts(dir));
    }
    catch (IOException e) {
      LOG.warn("받은 저장소의 서버 브랜치를 읽지 못해 기본 브랜치로 둡니다: " + dir, e);
      return;
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    AtomicReference<String> picked = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      if (project != null && project.isDisposed()) return;
      Dialog dialog = new Dialog(project, dir.getName(), remote, mine);
      if (dialog.showAndGet()) picked.set(dialog.selected());
    }, ModalityState.defaultModalityState());
    String branch = picked.get();
    if (branch == null || branch.equals(remote.defaultBranch())) return;
    try {
      checkout(dir, remote.name(), branch);
    }
    catch (IOException e) {
      LOG.warn("받을 브랜치로 바꾸지 못했습니다: " + dir + " " + branch, e);
      ApplicationManager.getApplication().invokeLater(
        () -> Messages.showErrorDialog(project, SyncBundle.message("clonebranch.failed", branch, remote.defaultBranch(), e.getMessage()),
                                       SyncBundle.message("clonebranch.title")),
        ModalityState.nonModal());
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 지금 브랜치의 추적 대상(origin/main)으로 원격 이름 · 기본 브랜치를 알고, 그 원격의 브랜치를 모은다. 추적 대상이 없으면 null. */
  static @Nullable Remote remote(File dir) throws IOException, InterruptedException {
    String upstream;
    try {
      upstream = SyncPanel.git(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}").trim();
    }
    catch (IOException e) {
      // 빈 저장소 · 추적 없는 브랜치: 고를 것이 없다.
      return null;
    }
    int slash = upstream.indexOf('/');
    if (slash <= 0 || slash == upstream.length() - 1) return null;
    String name = upstream.substring(0, slash);
    String refs = SyncPanel.git(dir, "for-each-ref", "--format=%(refname)", REMOTES + name + "/");
    return new Remote(name, upstream.substring(slash + 1), parseBranches(refs, name));
  }

  /** git for-each-ref --format=%(refname) refs/remotes/<remote>/ 출력 → 브랜치 이름(원격 이름 뺌, HEAD 뺌, 이름순). */
  static List<String> parseBranches(String refs, String remote) {
    String prefix = REMOTES + remote + "/";
    List<String> result = new ArrayList<>();
    for (String line : refs.split("\n")) {
      String ref = line.trim();
      if (!ref.startsWith(prefix)) continue;
      String branch = ref.substring(prefix.length());
      if (!branch.isEmpty() && !branch.equals(HEAD)) result.add(branch);
    }
    result.sort(String.CASE_INSENSITIVE_ORDER);
    return result;
  }

  /** 받은 저장소에서 본 「나」의 이름 조각: user.name(빈칸 뺌) · 메일 앞부분 · Git 로그인 아이디. */
  private static Set<String> nameParts(File dir) throws InterruptedException {
    List<String> words = new ArrayList<>();
    try {
      History.Me me = History.parseMe(SyncPanel.git(dir, "config", "--get-regexp", "^user\\.(name|email)$"));
      words.add(me.name());
      words.add(me.email());
    }
    catch (IOException e) {
      // user.name · email 이 없으면 git 이 1 로 끝난다. 로그인 아이디만 쓴다.
    }
    for (GitLogins.Login login : GitLogins.list()) words.add(login.user());
    return nameParts(words);
  }

  /** 이름 · 메일 · 아이디 → 소문자 조각. 메일은 @ 앞만, 빈칸은 뺀다. MIN_NAME_PART 보다 짧은 것은 버린다. */
  static Set<String> nameParts(Collection<String> words) {
    Set<String> parts = new LinkedHashSet<>();
    for (String word : words) {
      if (word == null) continue;
      String part = word.trim();
      int at = part.indexOf('@');
      if (at >= 0) part = part.substring(0, at);
      part = part.replace(" ", "").toLowerCase(Locale.ROOT);
      if (part.length() >= MIN_NAME_PART) parts.add(part);
    }
    return parts;
  }

  /** 이름 조각이 들어간 브랜치(대소문자 무시), branches 순서 그대로. */
  static List<String> mine(List<String> branches, Set<String> parts) {
    List<String> result = new ArrayList<>();
    for (String branch : branches) {
      String lower = branch.toLowerCase(Locale.ROOT);
      for (String part : parts) {
        if (lower.contains(part)) {
          result.add(branch);
          break;
        }
      }
    }
    return result;
  }

  /** 목록 순서: 내 브랜치 → 기본 브랜치 → 나머지(각각 이름순). filter 가 비어 있지 않으면 그 글이 들어간 것만(대소문자 무시). */
  static List<String> ordered(Remote remote, List<String> mine, String filter) {
    List<String> order = new ArrayList<>(mine);
    if (!order.contains(remote.defaultBranch())) order.add(remote.defaultBranch());
    for (String branch : remote.branches()) {
      if (!order.contains(branch)) order.add(branch);
    }
    String key = filter.trim().toLowerCase(Locale.ROOT);
    if (key.isEmpty()) return order;
    List<String> shown = new ArrayList<>();
    for (String branch : order) {
      if (branch.toLowerCase(Locale.ROOT).contains(key)) shown.add(branch);
    }
    return shown;
  }

  /** 서버 브랜치 remote/branch 를 같은 이름의 내 브랜치로 꺼내고 추적을 건다. 같은 이름 내 브랜치가 이미 있으면 그리로 바꾼다. */
  static void checkout(File dir, String remote, String branch) throws IOException, InterruptedException {
    boolean exists = !SyncPanel.git(dir, "for-each-ref", "--format=%(refname)", "refs/heads/" + branch).isBlank();
    if (exists) SyncPanel.git(dir, SyncPanel.GIT_LONG_TIMEOUT_SEC, "checkout", branch, "--");
    else SyncPanel.git(dir, SyncPanel.GIT_LONG_TIMEOUT_SEC, "checkout", "--track", remote + "/" + branch, "--");
  }

  /** 「받을 브랜치」 창: 찾기 칸 + 서버 브랜치 목록. 두 번 누르기 · Enter 는 「받기」. */
  private static final class Dialog extends DialogWrapper {
    // 처음 열 때 창 크기: IDE 가 뜬 화면 폭 · 높이에 대한 비율. 그 뒤는 사용자가 바꾼 크기를 기억한다(getDimensionServiceKey).
    private static final double FIRST_W = 0.4;
    private static final double FIRST_H = 0.5;
    private final @Nullable Project project;
    private final Remote remote;
    private final List<String> mine;
    private final SearchTextField search = new SearchTextField(false);
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JBList<String> list = new JBList<>(model);
    private final String repoName;

    Dialog(@Nullable Project project, String repoName, Remote remote, List<String> mine) {
      super(project, true);
      this.project = project;
      this.repoName = repoName;
      this.remote = remote;
      this.mine = mine;
      setTitle(SyncBundle.message("clonebranch.title"));
      setOKButtonText(SyncBundle.message("clonebranch.ok"));
      setCancelButtonText(SyncBundle.message("clonebranch.cancel"));
      list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      list.setCellRenderer(new Renderer());
      list.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          if (e.getClickCount() == 2 && list.getSelectedValue() != null) doOKAction();
        }
      });
      search.addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) {
          refill();
        }
      });
      // 찾기 칸에서 위 · 아래 화살표로 목록을 옮긴다.
      ScrollingUtil.installMoveUpAction(list, search.getTextEditor());
      ScrollingUtil.installMoveDownAction(list, search.getTextEditor());
      refill();
      list.setSelectedValue(remote.defaultBranch(), true);
      init();
    }

    private void refill() {
      String keep = list.getSelectedValue();
      model.clear();
      for (String branch : ordered(remote, mine, search.getText())) model.addElement(branch);
      if (keep != null && model.contains(keep)) list.setSelectedValue(keep, true);
      else if (!model.isEmpty()) list.setSelectedIndex(0);
      setOKActionEnabled(!model.isEmpty());
    }

    @Nullable String selected() {
      return list.getSelectedValue();
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
      return "kinggura.sync.CloneBranch";
    }

    @Override
    public Dimension getInitialSize() {
      Window ide = WindowManager.getInstance().suggestParentWindow(project);
      Rectangle screen = ide != null ? ScreenUtil.getScreenRectangle(ide) : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
      return new Dimension((int) (screen.width * FIRST_W), (int) (screen.height * FIRST_H));
    }

    @Override
    protected JComponent createCenterPanel() {
      JBLabel hint = new JBLabel(SyncBundle.message("clonebranch.hint", repoName, remote.name(), remote.defaultBranch()));
      hint.setAllowAutoWrapping(true);
      JBScrollPane scroll = new JBScrollPane(list);
      return FormBuilder.createFormBuilder()
        .addComponent(hint)
        .addComponent(search)
        .addComponentFillVertically(scroll, 0)
        .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
      return search;
    }

    /** 내 브랜치 · 기본 브랜치에 회색 꼬리표. */
    private final class Renderer extends ColoredListCellRenderer<String> {
      @Override
      protected void customizeCellRenderer(@NotNull JList<? extends String> list, String branch, int index, boolean selected, boolean focus) {
        append(branch);
        if (mine.contains(branch)) append("   " + SyncBundle.message("clonebranch.tag.mine"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        if (branch.equals(remote.defaultBranch())) append("   " + SyncBundle.message("clonebranch.tag.default"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
    }
  }
}
