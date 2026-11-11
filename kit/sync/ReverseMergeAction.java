package kinggura.sync;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ex.ApplicationUtil;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ComboboxSpeedSearch;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScreenUtil;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Git 메뉴 「Merge Branches…」(역머지, 반대 방향도 된다). 서버 브랜치 목록을 읽어 From(기준 브랜치) · Into(개인 브랜치)를 고르게 하고,
 * 합치기 전에 들어올 파일 · 내 파일과 겹치는 파일을 보여 준다. Merge 를 눌러야 합친다.
 * 고른 두 브랜치는 저장소의 .git/config 에 남겨 다음에 그대로 고른다(ReverseMerge.CONFIG_*).
 */
public final class ReverseMergeAction extends DumbAwareAction {
  /** 내 파일 줄 글자색. 동기화 창 · 설명서에서 「주의」에 쓰는 주황. */
  static final JBColor MINE_COLOR = new JBColor(0xB45F06, 0xFF9F0A);
  /** 내 파일 줄 · 「내 파일」 칩의 바탕(옅은 주황). */
  private static final JBColor MINE_BG = new JBColor(0xFFF3E0, 0x4A3416);
  /** 단계 번호 동그라미 · 「커밋」 칩의 파랑(Git Log 현재 브랜치와 같은 색). */
  private static final Color STEP_COLOR = ProjectHistoryPanel.HEAD_COLOR;
  /** 창 맨 위 저장소 이름 글자색. 동기화 창 Resolve Conflicts 단추와 같은 빨강. */
  private static final Color REPO_COLOR = SyncPanel.RESOLVE_BG;
  private static final JBColor COMMITS_BG = new JBColor(0xE3EDFB, 0x25324A);
  private static final JBColor FILES_BG = new JBColor(0xEBECF0, 0x3C3F41);
  private static final JBColor FILES_FG = new JBColor(0x5A5D63, 0xB0B3B8);
  /** 처음 열 때 창 크기: 화면 너비 · 높이에 대한 비율. 사용자가 바꾼 크기는 기억한다(DIMENSION_KEY). */
  private static final double FIRST_W = 0.6;
  private static final double FIRST_H = 0.65;
  private static final String DIMENSION_KEY = "kinggura.sync.MergeBranches";
  /** 표 칸(파일 · 작성자 · 때 · 커밋 제목)의 처음 너비 비율(천분율). 표 너비가 바뀔 때마다 이 비율로 나누고, 머리글을 끌어 바꾼 비율은 COL_RATIO_KEY 로 기억한다. */
  private static final int[] COL_RATIO = {480, 120, 100, 300};
  private static final int RATIO_SCALE = 1000;
  private static final String COL_RATIO_KEY = "kinggura.sync.mergeBranches.col.ratio.";
  /** 대소문자만 바뀐 이름을 묻는 창에 보이는 줄 수. 나머지는 개수로 알린다. */
  private static final int CASE_LIST_MAX = 10;
  // 찾기 칸 모서리 둥글기(px). 새 UI 콤보 상자 · 펼친 목록의 모서리와 비슷하게 맞춘다.
  private static final int SEARCH_ARC = 8;
  // 칸 사이 간격 · 단계 제목 아래 내용의 왼쪽 들여쓰기
  private static final int GAP = 8;
  private static final int STEP_INDENT = 26;
  // 단계 사이 · 단계 안 줄 사이 세로 간격
  private static final int STEP_GAP = 14;
  private static final int ROW_GAP = 6;
  private static final int CHIP_PAD_V = 1;
  private static final int CHIP_PAD_H = 7;

  @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabledAndVisible(project != null && GitBranchUtil.guessRepositoryForOperation(project, e.getDataContext()) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    GitRepository repo = GitBranchUtil.guessRepositoryForOperation(project, e.getDataContext());
    if (repo == null) return;
    String current = repo.getCurrentBranchName();
    if (current == null) {
      Messages.showErrorDialog(project, SyncBundle.message("rmerge.detached"), SyncBundle.message("rmerge.title"));
      return;
    }
    File root = new File(repo.getRoot().getPath());
    ProgressManager.getInstance().run(new Task.Modal(project, SyncBundle.message("rmerge.loading.branches"), true) {
      private String remote;
      private List<String> names;
      private String savedFrom;
      private String savedInto;
      // git config 에 지금 적혀 있는 From · Into(지금 브랜치 것이 아니어도 그대로). 병합 때 같은 값이면 다시 적지 않는다.
      private String storedFrom;
      private String storedInto;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        // 서버가 느릴 때 취소를 누르면 기다리던 git 을 끝낸다(작업 스레드를 끊으면 SyncPanel.run 이 프로세스를 죽인다).
        // AppExecutorUtil: executeOnPooledThread(Callable) 는 예외를 삼키고 null 을 돌려줘 브랜치 읽기 실패가 안내 없이 넘어간다.
        Future<?> work = AppExecutorUtil.getAppExecutorService().submit(() -> {
          // 설정 · 대소문자 구분 · 받아 둔 원격 브랜치는 서로 기다릴 필요가 없어 동시에 읽는다. 원격 브랜치는 모든 원격 것을 읽고 원격 이름으로 거른다.
          Future<Map<String, String>> configs = AppExecutorUtil.getAppExecutorService().submit(() -> ReverseMerge.configs(root));
          List<String> local = ReverseMerge.gitAll(root, List.of(ReverseMerge.IGNORE_CASE,
                                                                 new String[]{"for-each-ref", "--format=%(refname)", "refs/remotes/"}));
          Map<String, String> cfg = SyncPanel.await(configs);
          remote = ReverseMerge.remoteOf(cfg, current);
          savedFrom = cfg.get(ReverseMerge.CONFIG_FROM);
          savedInto = cfg.get(ReverseMerge.CONFIG_INTO);
          storedFrom = savedFrom;
          storedInto = savedInto;
          // 지금 브랜치에 합친 기억만 쓴다. 다른 브랜치나 반대 방향(지금 브랜치 → develop)의 기억은 버린다.
          if (!ReverseMerge.rememberedFor(savedInto, current, Boolean.parseBoolean(local.get(0).trim()))) {
            savedFrom = null;
            savedInto = null;
          }
          // 받아 둔 브랜치 목록으로 바로 열고 서버 목록은 창이 뜬 뒤 합친다(VPN 에서 ls-remote 는 몇 초 걸린다).
          // 아직 기준 브랜치를 받은 적이 없으면(처음 쓰는 저장소) 서버에 물어야 From 을 채울 수 있다.
          names = ReverseMerge.parseRefs(local.get(1), remote);
          // Into · 기억된 이름도 마찬가지다. 받아 둔 이름은 윈도우에서 대소문자가 서버와 다를 수 있어(Feature/ ↔ feature/) 정확히 같은 이름이 없으면 서버에 묻는다.
          // 그대로 열면 기억된 쪽만 기본값(develop)으로 바뀌어 From · Into 가 같아질 수 있다.
          if (ReverseMerge.pick(names, savedFrom, ReverseMerge.FROM_CANDIDATES) == null
              || ReverseMerge.pick(names, savedInto, List.of(current)) == null
              || savedFrom != null && !names.contains(savedFrom)
              || savedInto != null && !names.contains(savedInto)) {
            names = ReverseMerge.serverBranches(root, remote);
          }
          return null;
        });
        try {
          ApplicationUtil.runWithCheckCanceled(work, indicator);
        }
        catch (ProcessCanceledException canceled) {
          work.cancel(true);
          throw canceled;
        }
        catch (ExecutionException ex) {
          Throwable cause = ex.getCause();
          throw new RuntimeException(cause instanceof InterruptedException ? SyncBundle.message("sync.interrupted") : cause.getMessage(), cause);
        }
      }

      @Override
      public void onSuccess() {
        if (names.isEmpty()) {
          Messages.showErrorDialog(project, SyncBundle.message("rmerge.no.branches", remote), SyncBundle.message("rmerge.title"));
          return;
        }
        String from = ReverseMerge.pick(names, savedFrom, ReverseMerge.FROM_CANDIDATES);
        // 개인 브랜치는 저장해 둔 것, 없으면 지금 브랜치(서버에 같은 이름이 있을 때).
        String into = ReverseMerge.pick(names, savedInto, List.of(current));
        // 같은 브랜치로 열지 않는다(지금 브랜치가 develop 일 때). Into 를 비워 고르라는 안내가 보이게 한다.
        if (into != null && into.equals(from)) into = null;
        new PreviewDialog(project, repo, root, remote, current, names, from, into, storedFrom, storedInto).show();
      }

      @Override
      public void onThrowable(@NotNull Throwable error) {
        Messages.showErrorDialog(project, error.getMessage(), SyncBundle.message("rmerge.title"));
      }
    });
  }

  /** From · Into 를 고르고 들어올 파일을 보는 창. 고른 값이 바뀔 때마다 두 브랜치를 받아 와 다시 계산한다. */
  private static final class PreviewDialog extends DialogWrapper {
    private final Project project;
    private final GitRepository repo;
    private final File root;
    private final String remote;
    private final String current;
    private final ComboBox<String> fromBox;
    private final ComboBox<String> intoBox;
    private final JBLabel unpushed = new JBLabel();
    private final JBLabel intoUnpushed = new JBLabel();
    // 미리 보기 계산을 하나씩 차례로 돌린다. 같은 저장소에 fetch 가 겹치면 「cannot lock ref」로 실패한다.
    private final ExecutorService worker = AppExecutorUtil.createBoundedApplicationPoolExecutor("EI Merge Branches", 1);
    // 서버 브랜치 목록 받기(addServerBranches). 창을 닫으면 끊는다.
    private Future<?> serverList;
    private final RowModel model = new RowModel();
    // 미리 보기 표. 비교 전 · 받는 중 · 실패 · 합칠 것 없음은 표의 빈 글(StatusText)로 보인다.
    private final JBTable table = new JBTable(model);
    // 단계 2 제목 옆 요약 칩: 커밋 수(파랑) · 파일 수(회색) · 내 파일과 겹치는 수(주황, 없으면 숨김)
    private final Chip commitsChip = new Chip(COMMITS_BG, STEP_COLOR, null);
    private final Chip filesChip = new Chip(FILES_BG, FILES_FG, null);
    private final Chip mineChip = new Chip(MINE_BG, MINE_COLOR, AllIcons.General.Warning);
    private final JBCheckBox push = new JBCheckBox();
    // 두 브랜치를 받아 와 비교하는 동안 단계 2 제목 옆에 보이는 진행 막대와 글.
    private final Gauge gauge = new Gauge(SyncBundle.message("rmerge.loading"));
    // 미리 보기를 다시 읽을 때마다 하나 올린다(EDT 에서만 쓰고 worker 가 읽는다). 늦게 끝난 옛 계산이 새 결과를 덮지 않게 한다.
    private volatile int seq;
    private boolean ready;
    // 미리 본 From 커밋. Merge 는 이 커밋을 합친다(그사이 자동 fetch 가 받아 온 커밋은 넣지 않는다). EDT 에서만 쓴다.
    private String fromTip;
    // git config 에 적혀 있는 From · Into. 병합 때 고른 값과 같으면 git config 를 다시 부르지 않는다(회사 PC 에서 한 번에 0.3 초 넘게 걸린다).
    private volatile String storedFrom;
    private volatile String storedInto;

    PreviewDialog(Project project, GitRepository repo, File root, String remote, String current, List<String> names, String from, String into,
                  String storedFrom, String storedInto) {
      super(project, true);
      this.storedFrom = storedFrom;
      this.storedInto = storedInto;
      this.project = project;
      this.repo = repo;
      this.root = root;
      this.remote = remote;
      this.current = current;
      fromBox = new ComboBox<>(names.toArray(String[]::new));
      intoBox = new ComboBox<>(names.toArray(String[]::new));
      fromBox.setSelectedItem(from);
      intoBox.setSelectedItem(into);
      // 두 상자 모두 서버 브랜치를 가리키므로 origin/ 을 붙여 보인다.
      SimpleListCellRenderer<String> remoteName = new SimpleListCellRenderer<>() {
        @Override
        public void customize(@NotNull JList<? extends String> list, String name, int index, boolean selected, boolean hasFocus) {
          setText(name == null ? "" : remote + "/" + name);
        }
      };
      fromBox.setRenderer(remoteName);
      intoBox.setRenderer(remoteName);
      // 브랜치가 수십 개라 상자를 연 채 글자를 치면 이름이 맞는 브랜치로 건너뛴다(origin/ 은 빼고 맞춘다).
      // 찾기 칸은 IntelliJ 기본으로 Enter · Esc · 포커스 이동에만 닫힌다. 펼친 목록에서 브랜치를 고르면 목록과 함께 닫는다.
      hideSearchWithList(fromBox, roundSearch(fromBox));
      hideSearchWithList(intoBox, roundSearch(intoBox));
      // 두 상자는 글 길이와 상관없이 창 너비를 반씩 나눈다(GridBag weightx). 기본 너비가 가장 긴 이름을 따라가면 한쪽만 넓어지고 창 최소 너비도 커진다.
      fromBox.setPrototypeDisplayValue("");
      intoBox.setPrototypeDisplayValue("");
      push.setSelected(true);
      setTitle(SyncBundle.message("rmerge.title"));
      setOKButtonText(SyncBundle.message("rmerge.button.merge"));
      init();
      // 회사 PC 는 fetch 가 수 초 걸리므로 브랜치를 고르는 동안에는 받지 않는다. Compare 단추를 눌러야 받아 와 비교한다.
      fromBox.addActionListener(e -> reload(false));
      intoBox.addActionListener(e -> reload(false));
      reload(false);
      addServerBranches();
    }

    /**
     * 서버 브랜치 목록을 뒤에서 받아 받아 둔 목록에 없던 것을 두 상자에 더하고, 서버에 없는 이름(지워진 브랜치 · 대소문자만 다른 옛 이름)은 뺀다.
     * 고른 이름이 빠지면 대소문자만 다르면 서버 이름을, 지워졌으면 빈칸을 고른다. 서버에 닿지 못하면 받아 둔 목록만 남는다(미리 보기의 fetch 가 그 오류를 보인다).
     * 미리 보기 worker 밖에서 짧은 시간 제한으로 돌려, 서버가 멈춰도 비교가 그 뒤에 줄 서지 않게 한다. 창을 닫으면 끊는다.
     */
    private void addServerBranches() {
      serverList = ApplicationManager.getApplication().executeOnPooledThread(() -> {
        List<String> names;
        try {
          names = ReverseMerge.serverBranches(root, remote, SyncPanel.CMD_TIMEOUT_SEC);
        }
        catch (IOException unreachable) {
          return;
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
        // 빈 목록(빈 저장소 · 읽지 못한 출력)이면 받아 둔 목록을 지우지 않는다.
        if (names.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
          if (isDisposed()) return;
          for (ComboBox<String> box : List.of(fromBox, intoBox)) {
            DefaultComboBoxModel<String> items = (DefaultComboBoxModel<String>) box.getModel();
            // 받아 둔 이름 중 대소문자만 서버와 다른 것은 서버 이름으로 바꾼다. 그 이름으로는 서버에서 받을 수 없다.
            // 서버에서 지워진 브랜치도 뺀다. 받아 둔 원격 추적 참조는 서버에서 지워져도 남아, 고르면 fetch 가 「couldn't find remote ref」로 실패한다.
            String selected = (String) box.getSelectedItem();
            boolean selectedGone = selected != null && !names.contains(selected);
            String fixedSelected = ReverseMerge.serverSpelling(names, selected);
            for (int k = items.getSize() - 1; k >= 0; k--) {
              if (!names.contains(items.getElementAt(k))) items.removeElementAt(k);
            }
            for (String name : names) {
              if (items.getIndexOf(name) < 0) items.addElement(name);
            }
            // 고른 것을 빼면 콤보가 옆 이름을 대신 고르므로, 서버 이름 또는 빈칸으로 다시 맞춘다.
            if (selectedGone) box.setSelectedItem(fixedSelected);
          }
        });
      });
    }

    @Override
    protected void dispose() {
      if (serverList != null) serverList.cancel(true);
      super.dispose();
    }

    /**
     * ComboboxSpeedSearch.installOn 과 같고, 찾기 칸만 상자 · 펼친 목록처럼 모서리를 둥글게 그린다.
     * IntelliJ 찾기 칸은 각진 선 테두리를 코드로 박아 두어 테마 키로는 둥글게 할 수 없다.
     */
    private static ComboboxSpeedSearch roundSearch(ComboBox<String> box) {
      ComboboxSpeedSearch search = new ComboboxSpeedSearch(box, null) {
        // 브랜치 이름 어디든 친 글이 들어 있으면 맞다(대소문자 무시). 기본 맞추기는 「/ . -」 를 낱말 경계로 보아 전체 이름을 치면 못 찾는다.
        @Override
        protected boolean isMatchingElement(Object element, String pattern) {
          String text = getElementText(element);
          return text != null && pattern != null && StringUtil.containsIgnoreCase(text, pattern);
        }

        @Override
        protected @NotNull SearchPopup createPopup(String s) {
          SearchPopup popup = new SearchPopup(s) {
            @Override
            protected void paintComponent(Graphics g) {
              Graphics2D g2 = (Graphics2D)g.create();
              g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
              g2.setColor(getBackground());
              int arc = JBUI.scale(SEARCH_ARC);
              g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
              g2.dispose();
            }

            @Override
            protected void paintBorder(Graphics g) {
              Graphics2D g2 = (Graphics2D)g.create();
              g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
              g2.setColor(BORDER_COLOR);
              int arc = JBUI.scale(SEARCH_ARC);
              g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
              g2.dispose();
            }
          };
          // 둥근 모서리 바깥은 뒤 화면이 보이게 한다.
          popup.setOpaque(false);
          return popup;
        }
      };
      search.setupListeners();
      return search;
    }

    private static void hideSearchWithList(ComboBox<String> box, ComboboxSpeedSearch search) {
      box.addPopupMenuListener(new PopupMenuListener() {
        @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}
        @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { search.hidePopup(); }
        @Override public void popupMenuCanceled(PopupMenuEvent e) {}
      });
    }

    /** 단계 제목 줄: 굵은 제목. 뒤에 칩을 더 붙일 수 있다. */
    private static JPanel step(String titleKey) {
      JPanel row = new JPanel(new HorizontalLayout(JBUI.scale(GAP)));
      row.add(new JBLabel(SyncBundle.message(titleKey)).withFont(JBFont.label().asBold()));
      return row;
    }

    // 저장소 이름 글자 크기: 기본 글자보다 키우는 단계.
    private static final int REPO_FONT_STEP = 2;

    private static JBLabel dim(String text) {
      return new JBLabel(text, UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
      // 맨 위: 어느 저장소를 합치는지. 합치기 전에 꼭 확인할 값이라 이름만 빨강(Resolve Conflicts 단추 색) · 조금 큰 굵은 글씨로, 경로는 옆에 작게.
      // 단계 제목과 같은 HorizontalLayout 이라 왼쪽 줄이 맞는다.
      JPanel where = new JPanel(new HorizontalLayout(JBUI.scale(GAP)));
      where.add(new JBLabel(SyncBundle.message("rmerge.repo")));
      JBLabel name = new JBLabel(root.getName()).withFont(JBFont.label().biggerOn(REPO_FONT_STEP).asBold());
      name.setForeground(REPO_COLOR);
      where.add(name);
      where.add(dim(root.getPath()));

      // 단계 1: From ⇄ Into. 두 상자가 창 너비를 반씩 나눈다. From 은 원격 브랜치를 합치고, Into 는 원격에 맞춘 로컬 브랜치에 합친 뒤 같은 원격 브랜치로 푸시한다.
      fromBox.setToolTipText(SyncBundle.message("rmerge.from"));
      intoBox.setToolTipText(SyncBundle.message("rmerge.into"));
      JPanel branches = new JPanel(new GridBagLayout());
      GridBagConstraints c = new GridBagConstraints();
      c.fill = GridBagConstraints.HORIZONTAL;
      c.weightx = 1;
      branches.add(fromBox, c);
      c.weightx = 0;
      c.insets = JBUI.insets(0, GAP);
      // 가운데 단추: From 과 Into 를 맞바꾸고 바로 다시 비교한다.
      branches.add(new InplaceButton(SyncBundle.message("rmerge.swap"), AllIcons.Actions.SwapPanels, e -> {
        Object from = fromBox.getSelectedItem();
        fromBox.setSelectedItem(intoBox.getSelectedItem());
        intoBox.setSelectedItem(from);
        reload(true);
      }), c);
      c.weightx = 1;
      c.insets = JBUI.emptyInsets();
      branches.add(intoBox, c);
      // Compare 는 브랜치 줄 오른쪽 끝, 저장소 띠와 같은 파랑 바탕으로 눈에 띄게.
      JButton compare = new JButton(SyncBundle.message("rmerge.compare"));
      SyncPanel.tint(compare, STEP_COLOR);
      compare.addActionListener(e -> reload(true));
      c.weightx = 0;
      c.insets = JBUI.insetsLeft(GAP);
      branches.add(compare, c);
      branches.setBorder(JBUI.Borders.emptyLeft(STEP_INDENT));
      unpushed.setForeground(NamedColorUtil.getErrorForeground());
      intoUnpushed.setForeground(NamedColorUtil.getErrorForeground());
      unpushed.setBorder(JBUI.Borders.emptyLeft(STEP_INDENT));
      intoUnpushed.setBorder(JBUI.Borders.emptyLeft(STEP_INDENT));

      // 단계 2: 제목 + 요약 칩, 오른쪽에 비교 안내.
      JPanel previewTitle = step("rmerge.step.preview");
      previewTitle.add(commitsChip);
      previewTitle.add(filesChip);
      previewTitle.add(mineChip);
      previewTitle.add(gauge);
      JPanel previewHead = new JPanel(new BorderLayout());
      previewHead.add(previewTitle, BorderLayout.WEST);
      previewHead.add(dim(SyncBundle.message("rmerge.hint")), BorderLayout.EAST);
      previewHead.add(gauge.line(), BorderLayout.SOUTH);

      table.setShowGrid(false);
      table.getTableHeader().setReorderingAllowed(false);
      // 머리글 경계를 끌고 놓으면 그때 칸 비율을 기억한다. 표 너비는 아래 scroll 이 바뀔 때마다 그 비율로 나눈다.
      TableColumnModel columns = table.getColumnModel();
      int[] ratios = new int[COL_RATIO.length];
      for (int col = 0; col < ratios.length; col++) ratios[col] = PropertiesComponent.getInstance().getInt(COL_RATIO_KEY + col, COL_RATIO[col]);
      table.getTableHeader().addMouseListener(new MouseAdapter() {
        @Override public void mouseReleased(MouseEvent e) {
          int total = table.getWidth();
          if (total <= 0) return;
          for (int col = 0; col < ratios.length; col++) {
            ratios[col] = Math.max(1, columns.getColumn(col).getWidth() * RATIO_SCALE / total);
            PropertiesComponent.getInstance().setValue(COL_RATIO_KEY + col, ratios[col], COL_RATIO[col]);
          }
        }
      });
      table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
          super.getTableCellRendererComponent(t, value, selected, focus, row, column);
          ReverseMerge.Incoming item = model.rows.get(t.convertRowIndexToModel(row));
          setIcon(column == 0 && item.mine() ? AllIcons.General.Warning : null);
          if (!selected) {
            setForeground(item.mine() ? MINE_COLOR : t.getForeground());
            setBackground(item.mine() ? MINE_BG : t.getBackground());
          }
          setToolTipText(column == 0 ? item.path() : null);
          return this;
        }
      });
      table.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          int row = table.rowAtPoint(e.getPoint());
          if (e.getClickCount() == 2 && row >= 0) showDiff(model.rows.get(table.convertRowIndexToModel(row)));
        }
      });
      JScrollPane scroll = ScrollPaneFactory.createScrollPane(table);
      // JTable 은 늘어난 너비를 칸마다 똑같이 나눠 비율이 무너지므로, 보이는 너비가 바뀔 때마다 칸 너비를 비율대로 다시 준다.
      scroll.getViewport().addComponentListener(new ComponentAdapter() {
        @Override public void componentResized(ComponentEvent e) {
          int total = scroll.getViewport().getWidth();
          int sum = 0;
          for (int r : ratios) sum += r;
          if (total <= 0 || sum <= 0) return;
          for (int col = 0; col < ratios.length; col++) columns.getColumn(col).setPreferredWidth(total * ratios[col] / sum);
          table.doLayout();
        }
      });
      scroll.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0));

      // 단계 3: 합친 뒤 Push 여부. 커밋 안 한 변경이 합칠 파일과 겹치면 합칠 때 Stash 할지 묻는다(runMerge).
      push.setBorder(JBUI.Borders.emptyLeft(STEP_INDENT));
      JBLabel stashHint = dim(SyncBundle.message("rmerge.stash.hint", SyncBundle.message("rmerge.stash.button")));
      stashHint.setBorder(JBUI.Borders.emptyLeft(STEP_INDENT));

      JPanel top = FormBuilder.createFormBuilder()
        .addComponent(where)
        .addComponent(step("rmerge.step.pick"), JBUI.scale(ROW_GAP))
        .addComponent(branches, JBUI.scale(ROW_GAP))
        .addComponent(unpushed, JBUI.scale(ROW_GAP))
        .addComponent(intoUnpushed, 0)
        .addComponent(previewHead, JBUI.scale(STEP_GAP))
        .getPanel();
      JPanel bottom = FormBuilder.createFormBuilder()
        .addComponent(step("rmerge.step.after"))
        .addComponent(push, JBUI.scale(ROW_GAP))
        .addComponent(stashHint, 0)
        .getPanel();
      JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(ROW_GAP)));
      panel.add(top, BorderLayout.NORTH);
      panel.add(scroll, BorderLayout.CENTER);
      panel.add(bottom, BorderLayout.SOUTH);
      return panel;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

    @Override
    public Dimension getInitialSize() {
      // IDE 창이 있는 모니터 기준(노트북 + 큰 모니터를 함께 쓸 때 다른 화면 크기로 열리지 않게).
      Window ide = WindowManager.getInstance().suggestParentWindow(project);
      Rectangle screen = ide != null ? ScreenUtil.getScreenRectangle(ide) : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
      return new Dimension((int) (screen.width * FIRST_W), (int) (screen.height * FIRST_H));
    }

    private String from() { return (String) fromBox.getSelectedItem(); }
    private String into() { return (String) intoBox.getSelectedItem(); }

    /** 표를 비우고 그 자리에 안내 글을 보인다. */
    private void status(String text) {
      table.getEmptyText().setText(text);
    }

    /** 요약 칩을 미리 보기 결과로 채운다. null 이면 모두 숨긴다. */
    private void showChips(@Nullable ReverseMerge.Preview preview) {
      commitsChip.setVisible(preview != null);
      filesChip.setVisible(preview != null);
      mineChip.setVisible(preview != null && preview.mineCount() > 0);
      if (preview == null) return;
      commitsChip.setText(SyncBundle.message("rmerge.chip.commits", preview.commits()));
      filesChip.setText(SyncBundle.message("rmerge.chip.files", preview.files().size()));
      mineChip.setText(SyncBundle.message("rmerge.chip.mine", preview.mineCount()));
      mineChip.setToolTipText("<html>" + SyncBundle.message("rmerge.warn", preview.mineCount()) + "<br>"
                              + SyncBundle.message("rmerge.hint.mine", ReverseMerge.MINE_DAYS) + "</html>");
    }

    /**
     * 미리 보기를 비우고 Merge 를 끈다. fetch 가 참이면(Compare 단추) 두 브랜치를 서버에서 새로 받아 와 미리 보기를 다시 계산하고, 거짓이면 Compare 를 누르라고 안내만 한다.
     * 같은 브랜치를 고르면 계산하지 않는다.
     */
    private void reload(boolean fetch) {
      int mySeq = ++seq;
      ready = false;
      setOKActionEnabled(false);
      unpushed.setText(" ");
      intoUnpushed.setText(" ");
      model.set(List.of());
      showChips(null);
      table.setToolTipText(null);
      table.setPaintBusy(false);
      gauge.setVisible(false);
      String from = from();
      String into = into();
      push.setText(into == null ? SyncBundle.message("rmerge.push.none") : SyncBundle.message("rmerge.push", remote + "/" + into));
      if (from == null || into == null) {
        status(SyncBundle.message("rmerge.pick"));
        return;
      }
      if (from.equals(into)) {
        status(SyncBundle.message("rmerge.same"));
        return;
      }
      if (!fetch) {
        status(SyncBundle.message("rmerge.press.compare"));
        return;
      }
      status("");
      gauge.setVisible(true);
      table.setPaintBusy(true);
      worker.execute(() -> {
        if (mySeq != seq || isDisposed()) return;  // 그사이 다른 브랜치를 골랐거나 창을 닫았다. 새 계산만 돌린다.
        ReverseMerge.Preview preview = null;
        String error = null;
        int localOnly = 0;
        int intoLocalOnly = 0;
        String localOnlyTop = "";
        try {
          // 창을 열어 둔 사이 남이 올린 커밋도 보이게 Compare 를 누를 때마다 받는다.
          ReverseMerge.fetch(root, remote, from, into);
          // 올리지 않은 내 커밋 수는 미리 보기와 서로 기다릴 필요가 없어 동시에 센다.
          Future<Integer> fromCount = AppExecutorUtil.getAppExecutorService().submit(() -> ReverseMerge.unpushed(root, remote, from));
          Future<Integer> intoCount = AppExecutorUtil.getAppExecutorService().submit(() -> ReverseMerge.unpushed(root, remote, into));
          preview = ReverseMerge.preview(root, remote, from, into, current);
          localOnly = SyncPanel.await(fromCount);
          if (localOnly > 0) localOnlyTop = ReverseMerge.lastUnpushed(root, remote, from);
          intoLocalOnly = SyncPanel.await(intoCount);
        }
        catch (IOException ex) {
          error = ex.getMessage();
        }
        catch (RuntimeException ex) {
          // 예상 못 한 오류도 안내하고 진행 표시를 끈다.
          error = String.valueOf(ex);
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
        ReverseMerge.Preview result = preview;
        String failure = error;
        int notOnServer = localOnly;
        int intoNotOnServer = intoLocalOnly;
        String notOnServerTop = localOnlyTop;
        SwingUtilities.invokeLater(() -> {
          if (mySeq != seq || isDisposed()) return;
          table.setPaintBusy(false);
          gauge.setVisible(false);
          if (failure != null) {
            status(failure);
            table.setToolTipText(failure);
            return;
          }
          model.set(result.files());
          if (notOnServer > 0) unpushed.setText(SyncBundle.message("rmerge.unpushed", remote, from, notOnServer, StringUtil.escapeXmlEntities(notOnServerTop)));
          if (intoNotOnServer > 0) intoUnpushed.setText(SyncBundle.message("rmerge.unpushed.into", into, intoNotOnServer));
          if (result.commits() == 0) {
            status(SyncBundle.message("rmerge.up.to.date", into, from));
            return;
          }
          showChips(result);
          fromTip = result.fromTip();
          ready = true;
          setOKActionEnabled(true);
        });
      });
    }

    /** 개인 브랜치 쪽 파일(왼쪽) ↔ 기준 브랜치 쪽 파일(오른쪽). 한쪽에 없으면 빈 글. */
    private void showDiff(ReverseMerge.Incoming item) {
      String from = from();
      String into = into();
      String intoTip = ReverseMerge.intoTip(remote, into, current);
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        byte[] left;
        byte[] right;
        try {
          left = show(intoTip, item.path());
          right = show(ReverseMerge.remoteRef(remote, from), item.path());
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
        // 이 대화상자의 모달 상태로 연다. SwingUtilities.invokeLater 로 열면 IDE 가 쓰기 안전하지 않은 문맥으로 본다.
        ApplicationManager.getApplication().invokeLater(() -> {
          // 바이트로 넘겨 IDE 가 파일 인코딩(프로젝트 설정 · BOM)으로 읽게 한다. MS949 파일의 한글이 깨지지 않는다.
          FilePath path = VcsUtil.getFilePath(new File(root, item.path()), false);
          DiffContentFactory factory = DiffContentFactory.getInstance();
          try {
            DiffManager.getInstance().showDiff(project, new SimpleDiffRequest(item.path(), factory.createFromBytes(project, left, path),
                                                                              factory.createFromBytes(project, right, path), into, from));
          }
          catch (IOException ex) {
            Messages.showErrorDialog(project, ex.getMessage(), SyncBundle.message("rmerge.title"));
          }
        }, ModalityState.stateForComponent(getContentPanel()));
      });
    }

    private byte[] show(String ref, String path) throws InterruptedException {
      try {
        return SyncPanel.gitBytes(root, "show", ref + ":" + path);
      }
      catch (IOException missing) {
        return new byte[0];  // 그 브랜치에 없는 파일(새로 생겼거나 지워짐)
      }
    }

    @Override
    protected void doOKAction() {
      if (!ready) return;
      String from = from();
      String into = into();
      String tip = fromTip;
      boolean pushAfter = push.isSelected();
      super.doOKAction();
      // 열린 편집기의 저장 안 한 내용이 합치기에 빠지지 않게 먼저 저장한다. 단추 클릭은 쓰기 락 없이 오므로 락을 잡는다.
      WriteIntentReadAction.run((Runnable) () -> FileDocumentManager.getInstance().saveAllDocuments());
      runMerge(from, tip, into, pushAfter, false, false);
    }

    /**
     * 작업 스레드에서 from 의 tip(미리 본 커밋)을 합친다. 커밋 안 한 변경이 합칠 파일과 겹쳐 멈추면(DirtyException) Stash 해 두고 합칠지 묻고, 고르면 stash 를 참으로 다시 한다.
     * From 쪽이 파일 이름 대소문자만 바꿔 멈추면(CaseRenameException) 목록을 보여 이름을 맞추고 합칠지 묻고, 고르면 matchCase 를 참으로 다시 한다.
     * 충돌이면 충돌 창을 띄워 마무리한다(resolveThenFinish).
     */
    private void runMerge(String from, String tip, String into, boolean pushAfter, boolean stash, boolean matchCase) {
      ProgressManager.getInstance().run(new Task.Backgroundable(project, SyncBundle.message("rmerge.running", from, into), false) {
        private ReverseMerge.Result result;
        private Set<String> conflicts = Set.of();

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          Start start = start();
          String before = start.head();
          Set<String> dirtyBefore = start.dirty();
          try {
            if (!from.equals(storedFrom)) {
              ReverseMerge.saveConfig(root, ReverseMerge.CONFIG_FROM, from);
              storedFrom = from;
            }
            if (!into.equals(storedInto)) {
              ReverseMerge.saveConfig(root, ReverseMerge.CONFIG_INTO, into);
              storedInto = into;
            }
            result = ReverseMerge.merge(root, remote, from, tip, into, current, pushAfter, stash, matchCase);
            if (result != ReverseMerge.Result.MERGED) conflicts = ReverseMerge.conflicts(root);
          }
          catch (IOException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
          }
          catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(SyncBundle.message("sync.interrupted"), ex);
          }
          finally {
            refreshIde(before, dirtyBefore);
          }
        }

        @Override
        public void onSuccess() {
          if (result != ReverseMerge.Result.MERGED) {
            // 이번 합치기가 Stash 를 했을 때만 마무리 뒤 되살린다. 예전에 남은 같은 이름의 Stash 는 꺼내지 않는다.
            boolean stashed = result == ReverseMerge.Result.CONFLICT_STASHED;
            String text = SyncBundle.message("rmerge.conflict", from, into);
            if (stashed) text += "\n\n" + SyncBundle.message("rmerge.stash.kept");
            Messages.showWarningDialog(project, text, SyncBundle.message("rmerge.title"));
            resolveThenFinish(conflicts, from, into, pushAfter, stashed);
            return;
          }
          showDone(from, into, pushAfter);
        }

        @Override
        public void onThrowable(@NotNull Throwable error) {
          if (error.getCause() instanceof ReverseMerge.CaseRenameException caseError) {
            if (Messages.showOkCancelDialog(project, SyncBundle.message("rmerge.case.ask", remote + "/" + from, renameList(caseError.renames), into),
                                            SyncBundle.message("rmerge.title"), SyncBundle.message("rmerge.case.button"), Messages.getCancelButton(),
                                            Messages.getQuestionIcon()) == Messages.OK) {
              runMerge(from, tip, into, pushAfter, stash, true);
            }
            return;
          }
          if (error.getCause() instanceof ReverseMerge.DirtyException) {
            if (Messages.showOkCancelDialog(project, SyncBundle.message("rmerge.dirty.ask"), SyncBundle.message("rmerge.title"),
                                            SyncBundle.message("rmerge.stash.button"), Messages.getCancelButton(), Messages.getQuestionIcon()) == Messages.OK) {
              runMerge(from, tip, into, pushAfter, true, matchCase);
            }
            return;
          }
          if (error.getCause() instanceof ReverseMerge.PushRejectedException) {
            if (Messages.showOkCancelDialog(project, error.getMessage() + "\n\n" + SyncBundle.message("rmerge.retry.ask", remote + "/" + into),
                                            SyncBundle.message("rmerge.title"), SyncBundle.message("rmerge.retry.button"), Messages.getCancelButton(),
                                            Messages.getQuestionIcon()) == Messages.OK) {
              runMerge(from, tip, into, pushAfter, stash, matchCase);
            }
            return;
          }
          Messages.showErrorDialog(project, error.getMessage(), SyncBundle.message("rmerge.title"));
        }
      });
    }

    /** 「지금 이름 → 새 이름」 줄 목록. 폴더 이름이 바뀌면 파일이 많아 CASE_LIST_MAX 줄까지만 보이고 나머지는 개수로 알린다. */
    private static String renameList(Map<String, String> renames) {
      StringBuilder text = new StringBuilder();
      int shown = 0;
      for (Map.Entry<String, String> r : renames.entrySet()) {
        if (shown == CASE_LIST_MAX) {
          text.append(SyncBundle.message("rmerge.case.more", renames.size() - shown));
          break;
        }
        text.append(r.getKey()).append("\n  → ").append(r.getValue()).append('\n');
        shown++;
      }
      return text.toString().trim();
    }

    /** 합치기가 끝났다는 알림 풍선. 누르지 않아도 사라지고, 「커밋 보기」로 Git Log 창을 연다. */
    private void showDone(String from, String into, boolean pushAfter) {
      SyncPanel.balloon(project, false, SyncBundle.message("rmerge.title"),
                        pushAfter ? SyncBundle.message("rmerge.done.pushed", from, into, remote) : SyncBundle.message("rmerge.done", from, into),
                        SyncPanel.showCommitsAction(project));
    }

    /** 성공 · 실패 모두 브랜치 · 파일이 바뀌었을 수 있어 before(시작 때 HEAD) 이후 달라진 파일과 저장소 정보를 IDE 에 새로 읽힌다. 작업 스레드에서 부른다. */
    private void refreshIde(@Nullable String before, Set<String> dirtyBefore) {
      SyncPanel.refreshChanged(project, root, before, dirtyBefore);
      repo.update();
    }

    /** 합치기 · 마무리를 시작할 때의 HEAD 와 커밋 안 한 변경 파일. */
    private record Start(@Nullable String head, Set<String> dirty) {}

    /** head() 와 dirtyNow() 를 동시에 읽는다. 작업본을 바꾸기 전에 둘 다 읽고 돌아온다. */
    private Start start() {
      Future<Set<String>> dirty = AppExecutorUtil.getAppExecutorService().submit(this::dirtyNow);
      String head = head();
      try {
        return new Start(head, dirty.get());
      }
      catch (ExecutionException e) {
        // dirtyNow 는 실패를 빈 집합으로 돌려주므로 오지 않는다. 와도 dirtyNow 의 실패와 같게 다룬다.
        return new Start(head, Set.of());
      }
      catch (InterruptedException e) {
        dirty.cancel(true);
        Thread.currentThread().interrupt();
        return new Start(head, Set.of());
      }
    }

    /** 지금 HEAD 커밋. 읽지 못하면 null(refreshIde 가 저장소 전체를 다시 읽는다). */
    private @Nullable String head() {
      try {
        return SyncPanel.git(root, "rev-parse", "HEAD").trim();
      }
      catch (IOException e) {
        return null;
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }

    /** 지금 커밋 안 한 변경이 있는 파일(Stash 로 치우면 디스크에서 바뀐다). 읽지 못하면 빈 집합. */
    private Set<String> dirtyNow() {
      try {
        return ReverseMerge.lines(SyncPanel.git(root, "diff", "--name-only", "--no-renames", "HEAD"));
      }
      catch (IOException e) {
        return Set.of();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Set.of();
      }
    }

    /**
     * 충돌 파일을 IntelliJ 충돌 해결 창(3-way)으로 연다. 창을 닫은 뒤 충돌이 모두 풀렸으면 합치기 커밋 · 푸시 · 원래 브랜치로 돌아오기를 이어서 한다.
     * Git 메뉴의 Resolve Conflicts 는 변경 목록이 충돌을 알아챈 뒤에만 파일을 찾으므로, 막 끝난 합치기에서는 git 이 알려 준 충돌 파일을 직접 넘긴다.
     * 충돌이 남았거나 합치기를 취소했으면 마무리하지 않고 안내만 한다.
     */
    private void resolveThenFinish(Set<String> paths, String from, String into, boolean pushAfter, boolean stashed) {
      List<VirtualFile> files = new ArrayList<>();
      for (String path : paths) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(new File(root, path));
        if (file != null) files.add(file);
      }
      WriteIntentReadAction.run((Runnable) () -> AbstractVcsHelper.getInstance(project).showMergeDialog(files, GitVcs.getInstance(project).getMergeProvider()));
      ProgressManager.getInstance().run(new Task.Backgroundable(project, SyncBundle.message("rmerge.finishing", from, into), false) {
        private boolean finished;

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          Start start = start();
          String before = start.head();
          Set<String> dirtyBefore = start.dirty();
          try {
            finished = ReverseMerge.finish(root, remote, into, current, pushAfter, stashed);
          }
          catch (IOException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
          }
          catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(SyncBundle.message("sync.interrupted"), ex);
          }
          finally {
            refreshIde(before, dirtyBefore);
          }
        }

        @Override
        public void onSuccess() {
          if (finished) showDone(from, into, pushAfter);
          else Messages.showWarningDialog(project, SyncBundle.message("rmerge.conflict.left", into)
                                                   + (stashed ? "\n\n" + SyncBundle.message("rmerge.stash.left", current) : ""), SyncBundle.message("rmerge.title"));
        }

        @Override
        public void onThrowable(@NotNull Throwable error) {
          Messages.showErrorDialog(project, error.getMessage(), SyncBundle.message("rmerge.title"));
        }
      });
    }
  }

  /** 둥근 바탕의 짧은 숫자 표시(커밋 · 파일 · 내 파일). */
  private static final class Chip extends JBLabel {
    private final Color fill;

    Chip(Color fill, Color text, @Nullable Icon icon) {
      this.fill = fill;
      setForeground(text);
      setIcon(icon);
      setFont(JBFont.small().asBold());
      setBorder(JBUI.Borders.empty(CHIP_PAD_V, CHIP_PAD_H));
      setOpaque(false);
      setVisible(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(fill);
      g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
      g2.dispose();
      super.paintComponent(g);
    }
  }

  /** 미리 보기 표: 파일 · 작성자 · 때 · 커밋 제목. */
  private static final class RowModel extends AbstractTableModel {
    private List<ReverseMerge.Incoming> rows = List.of();

    void set(List<ReverseMerge.Incoming> next) {
      rows = next;
      fireTableDataChanged();
    }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return 4; }

    @Override
    public String getColumnName(int column) {
      return SyncBundle.message(switch (column) {
        case 0 -> "rmerge.col.file";
        case 1 -> "rmerge.col.author";
        case 2 -> "rmerge.col.date";
        default -> "rmerge.col.commit";
      });
    }

    @Override
    public Object getValueAt(int row, int column) {
      ReverseMerge.Incoming r = rows.get(row);
      return switch (column) {
        case 0 -> r.path();
        case 1 -> r.author();
        case 2 -> r.when();
        default -> r.subject();
      };
    }
  }
}
