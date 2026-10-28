package kinggura.sync;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
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
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ComboboxSpeedSearch;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
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
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
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
  private static final int TABLE_W = 1000;
  private static final int TABLE_H = 420;
  private static final int PATH_COL_W = 480;
  // From · Into 상자의 최소 너비. 두 상자는 창 너비를 반씩 나눠 꽉 채우고, 창을 늘리면 같이 늘어난다(「fe-기능이름/날짜/아이디」 같은 긴 이름).
  private static final int COMBO_W = 300;
  // 찾기 칸 모서리 둥글기(px). 새 UI 콤보 상자 · 펼친 목록의 모서리와 비슷하게 맞춘다.
  private static final int SEARCH_ARC = 8;

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

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        // 서버가 느릴 때 취소를 누르면 기다리던 git 을 끝낸다(작업 스레드를 끊으면 SyncPanel.run 이 프로세스를 죽인다).
        Future<?> work = ApplicationManager.getApplication().executeOnPooledThread(() -> {
          Map<String, String> cfg = ReverseMerge.configs(root);
          remote = ReverseMerge.remoteOf(cfg, current);
          savedFrom = cfg.get(ReverseMerge.CONFIG_FROM);
          savedInto = cfg.get(ReverseMerge.CONFIG_INTO);
          // 받아 둔 브랜치 목록으로 바로 열고 서버 목록은 창이 뜬 뒤 합친다(VPN 에서 ls-remote 는 몇 초 걸린다).
          // 아직 기준 브랜치를 받은 적이 없으면(처음 쓰는 저장소) 서버에 물어야 From 을 채울 수 있다.
          names = ReverseMerge.knownBranches(root, remote);
          if (ReverseMerge.pick(names, savedFrom, ReverseMerge.FROM_CANDIDATES) == null) names = ReverseMerge.serverBranches(root, remote);
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
        new PreviewDialog(project, repo, root, remote, current, names, from, into).show();
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
    private final JBLabel summary = new JBLabel();
    private final JBLabel warn = new JBLabel();
    private final JBLabel unpushed = new JBLabel();
    private final JBLabel intoUnpushed = new JBLabel();
    // 미리 보기 계산을 하나씩 차례로 돌린다. 같은 저장소에 fetch 가 겹치면 「cannot lock ref」로 실패한다.
    private final ExecutorService worker = AppExecutorUtil.createBoundedApplicationPoolExecutor("EI Merge Branches", 1);
    private final RowModel model = new RowModel();
    private final JBCheckBox push = new JBCheckBox();
    // 미리 보기를 다시 읽을 때마다 하나 올린다(EDT 에서만 쓰고 worker 가 읽는다). 늦게 끝난 옛 계산이 새 결과를 덮지 않게 한다.
    private volatile int seq;
    private boolean ready;
    // 이 창에서 이미 받아 온 브랜치. 같은 브랜치를 다시 고르면 fetch 하지 않는다(worker 스레드에서만 쓴다).
    private final Set<String> fetched = new HashSet<>();

    PreviewDialog(Project project, GitRepository repo, File root, String remote, String current, List<String> names, String from, String into) {
      super(project, true);
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
          setText(remote + "/" + name);
        }
      };
      fromBox.setRenderer(remoteName);
      intoBox.setRenderer(remoteName);
      // 브랜치가 수십 개라 상자를 연 채 글자를 치면 이름이 맞는 브랜치로 건너뛴다(origin/ 은 빼고 맞춘다).
      // 찾기 칸은 IntelliJ 기본으로 Enter · Esc · 포커스 이동에만 닫힌다. 펼친 목록에서 브랜치를 고르면 목록과 함께 닫는다.
      hideSearchWithList(fromBox, roundSearch(fromBox));
      hideSearchWithList(intoBox, roundSearch(intoBox));
      fromBox.setMinimumAndPreferredWidth(JBUI.scale(COMBO_W));
      intoBox.setMinimumAndPreferredWidth(JBUI.scale(COMBO_W));
      push.setSelected(true);
      setTitle(SyncBundle.message("rmerge.title"));
      setOKButtonText(SyncBundle.message("rmerge.button.merge"));
      init();
      fromBox.addActionListener(e -> reload());
      intoBox.addActionListener(e -> reload());
      reload();
      addServerBranches();
    }

    /** 서버 브랜치 목록을 뒤에서 받아 받아 둔 목록에 없던 것을 두 상자에 더한다. 서버에 닿지 못하면 받아 둔 목록만 남는다(미리 보기의 fetch 가 그 오류를 보인다). */
    private void addServerBranches() {
      worker.execute(() -> {
        List<String> names;
        try {
          names = ReverseMerge.serverBranches(root, remote);
        }
        catch (IOException unreachable) {
          return;
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
        SwingUtilities.invokeLater(() -> {
          if (isDisposed()) return;
          for (ComboBox<String> box : List.of(fromBox, intoBox)) {
            DefaultComboBoxModel<String> items = (DefaultComboBoxModel<String>) box.getModel();
            for (String name : names) {
              if (items.getIndexOf(name) < 0) items.addElement(name);
            }
          }
        });
      });
    }

    /**
     * ComboboxSpeedSearch.installOn 과 같고, 찾기 칸만 상자 · 펼친 목록처럼 모서리를 둥글게 그린다.
     * IntelliJ 찾기 칸은 각진 선 테두리를 코드로 박아 두어 테마 키로는 둥글게 할 수 없다.
     */
    private static ComboboxSpeedSearch roundSearch(ComboBox<String> box) {
      ComboboxSpeedSearch search = new ComboboxSpeedSearch(box, null) {
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

    @Override
    protected @Nullable JComponent createCenterPanel() {
      // IntelliJ Pull 창처럼 어느 저장소 · 원격에서 받는지 먼저 보인다.
      // From 은 원격 브랜치를 합치고, Into 는 원격 브랜치에 맞춘 로컬 브랜치에 합친 뒤 같은 원격 브랜치로 푸시한다.
      JPanel where = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
      where.add(new JBLabel(SyncBundle.message("rmerge.repository")));
      where.add(new JBLabel(root.getName()).withFont(JBFont.label().asBold()));
      where.add(new JBLabel(root.getPath(), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER));
      JPanel pick = new JPanel(new GridBagLayout());
      GridBagConstraints c = new GridBagConstraints();
      c.fill = GridBagConstraints.HORIZONTAL;
      c.insets = JBUI.insetsRight(8);
      pick.add(new JBLabel(SyncBundle.message("rmerge.from")), c);
      c.weightx = 1;
      pick.add(fromBox, c);
      c.weightx = 0;
      pick.add(new JBLabel(SyncBundle.message("rmerge.into")), c);
      c.weightx = 1;
      c.insets = JBUI.emptyInsets();
      pick.add(intoBox, c);

      JBTable table = new JBTable(model);
      table.setShowGrid(false);
      table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(PATH_COL_W));
      table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
          super.getTableCellRendererComponent(t, value, selected, focus, row, column);
          ReverseMerge.Incoming item = model.rows.get(t.convertRowIndexToModel(row));
          setIcon(column == 0 && item.mine() ? AllIcons.General.Warning : null);
          if (!selected) setForeground(item.mine() ? MINE_COLOR : t.getForeground());
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
      scroll.setPreferredSize(JBUI.size(TABLE_W, TABLE_H));

      JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(8)));
      JPanel rows = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(6)));
      rows.add(where);
      rows.add(pick);
      // 로컬이 아니라 서버 브랜치끼리 합친다는 것을 아래 줄에 적는다.
      rows.add(new JBLabel(SyncBundle.message("rmerge.from.server", remote), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER));
      top.add(rows, BorderLayout.NORTH);
      JPanel lines = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(2)));
      lines.add(summary);
      lines.add(warn);
      lines.add(unpushed);
      lines.add(intoUnpushed);
      unpushed.setForeground(NamedColorUtil.getErrorForeground());
      intoUnpushed.setForeground(NamedColorUtil.getErrorForeground());
      top.add(lines, BorderLayout.SOUTH);
      warn.setForeground(MINE_COLOR);
      JPanel hints = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(2)));
      hints.add(new JBLabel(SyncBundle.message("rmerge.hint"), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER));
      hints.add(new JBLabel(SyncBundle.message("rmerge.hint.mine", ReverseMerge.MINE_DAYS), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER));
      JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(6)));
      bottom.add(hints, BorderLayout.NORTH);
      bottom.add(push, BorderLayout.SOUTH);
      JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
      panel.add(top, BorderLayout.NORTH);
      panel.add(scroll, BorderLayout.CENTER);
      panel.add(bottom, BorderLayout.SOUTH);
      return panel;
    }

    private String from() { return (String) fromBox.getSelectedItem(); }
    private String into() { return (String) intoBox.getSelectedItem(); }

    /** 두 브랜치를 받아 와 미리 보기를 다시 계산한다. 같은 브랜치를 고르면 계산하지 않고 Merge 를 끈다. */
    private void reload() {
      int mySeq = ++seq;
      ready = false;
      setOKActionEnabled(false);
      warn.setText(" ");
      unpushed.setText(" ");
      intoUnpushed.setText(" ");
      model.set(List.of());
      String from = from();
      String into = into();
      push.setText(SyncBundle.message("rmerge.push", remote + "/" + into));
      if (from == null || into == null) {
        summary.setText(SyncBundle.message("rmerge.pick"));
        return;
      }
      if (from.equals(into)) {
        summary.setText(SyncBundle.message("rmerge.same"));
        return;
      }
      summary.setText(SyncBundle.message("rmerge.loading"));
      worker.execute(() -> {
        if (mySeq != seq || isDisposed()) return;  // 그사이 다른 브랜치를 골랐거나 창을 닫았다. 새 계산만 돌린다.
        ReverseMerge.Preview preview = null;
        String error = null;
        int localOnly = 0;
        int intoLocalOnly = 0;
        try {
          List<String> missing = new ArrayList<>();
          for (String b : List.of(from, into)) {
            if (!fetched.contains(b)) missing.add(b);
          }
          if (!missing.isEmpty()) {
            ReverseMerge.fetch(root, remote, missing.toArray(String[]::new));
            fetched.addAll(missing);
          }
          preview = ReverseMerge.preview(root, remote, from, into, current);
          localOnly = ReverseMerge.unpushed(root, remote, from);
          intoLocalOnly = ReverseMerge.unpushed(root, remote, into);
        }
        catch (IOException ex) {
          error = ex.getMessage();
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
        ReverseMerge.Preview result = preview;
        String failure = error;
        int notOnServer = localOnly;
        int intoNotOnServer = intoLocalOnly;
        SwingUtilities.invokeLater(() -> {
          if (mySeq != seq || isDisposed()) return;
          if (failure != null) {
            summary.setText(failure);
            summary.setToolTipText(failure);
            return;
          }
          model.set(result.files());
          if (notOnServer > 0) unpushed.setText(SyncBundle.message("rmerge.unpushed", remote, from, notOnServer));
          if (intoNotOnServer > 0) intoUnpushed.setText(SyncBundle.message("rmerge.unpushed.into", into, intoNotOnServer));
          if (result.commits() == 0) {
            summary.setText(SyncBundle.message("rmerge.up.to.date", into, from));
            return;
          }
          summary.setText(SyncBundle.message("rmerge.summary", from, result.commits(), result.files().size()));
          if (result.mineCount() > 0) warn.setText(SyncBundle.message("rmerge.warn", result.mineCount()));
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
      boolean pushAfter = push.isSelected();
      super.doOKAction();
      // 열린 편집기의 저장 안 한 내용이 합치기에 빠지지 않게 먼저 저장한다. 단추 클릭은 쓰기 락 없이 오므로 락을 잡는다.
      WriteIntentReadAction.run((Runnable) () -> FileDocumentManager.getInstance().saveAllDocuments());
      ProgressManager.getInstance().run(new Task.Backgroundable(project, SyncBundle.message("rmerge.running", from, into), false) {
        private ReverseMerge.Result result;
        private Set<String> conflicts = Set.of();

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          try {
            ReverseMerge.saveConfig(root, ReverseMerge.CONFIG_FROM, from);
            ReverseMerge.saveConfig(root, ReverseMerge.CONFIG_INTO, into);
            result = ReverseMerge.merge(root, remote, from, into, current, pushAfter);
            if (result == ReverseMerge.Result.CONFLICT) conflicts = ReverseMerge.conflicts(root);
          }
          catch (IOException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
          }
          catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(SyncBundle.message("sync.interrupted"), ex);
          }
          finally {
            refreshIde();
          }
        }

        @Override
        public void onSuccess() {
          if (result == ReverseMerge.Result.CONFLICT) {
            Messages.showWarningDialog(project, SyncBundle.message("rmerge.conflict", from, into), SyncBundle.message("rmerge.title"));
            resolveThenFinish(conflicts, from, into, pushAfter);
            return;
          }
          showDone(from, into, pushAfter);
        }

        @Override
        public void onThrowable(@NotNull Throwable error) {
          Messages.showErrorDialog(project, error.getMessage(), SyncBundle.message("rmerge.title"));
        }
      });
    }

    /** 합치기가 끝났다는 알림. */
    private void showDone(String from, String into, boolean pushAfter) {
      Messages.showInfoMessage(project, pushAfter ? SyncBundle.message("rmerge.done.pushed", from, into, remote)
                                                  : SyncBundle.message("rmerge.done", from, into), SyncBundle.message("rmerge.title"));
    }

    /** 성공 · 실패 모두 브랜치 · 파일이 바뀌었을 수 있어 IDE 가 알던 상태를 새로 읽는다. 작업 스레드에서 부른다. */
    private void refreshIde() {
      VfsUtil.markDirtyAndRefresh(false, true, true, repo.getRoot());
      repo.update();
      VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
    }

    /**
     * 충돌 파일을 IntelliJ 충돌 해결 창(3-way)으로 연다. 창을 닫은 뒤 충돌이 모두 풀렸으면 합치기 커밋 · 푸시 · 원래 브랜치로 돌아오기를 이어서 한다.
     * Git 메뉴의 Resolve Conflicts 는 변경 목록이 충돌을 알아챈 뒤에만 파일을 찾으므로, 막 끝난 합치기에서는 git 이 알려 준 충돌 파일을 직접 넘긴다.
     * 충돌이 남았거나 합치기를 취소했으면 마무리하지 않고 안내만 한다.
     */
    private void resolveThenFinish(Set<String> paths, String from, String into, boolean pushAfter) {
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
          try {
            finished = ReverseMerge.finish(root, remote, into, current, pushAfter);
          }
          catch (IOException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
          }
          catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(SyncBundle.message("sync.interrupted"), ex);
          }
          finally {
            refreshIde();
          }
        }

        @Override
        public void onSuccess() {
          if (finished) showDone(from, into, pushAfter);
          else Messages.showWarningDialog(project, SyncBundle.message("rmerge.conflict.left", into), SyncBundle.message("rmerge.title"));
        }

        @Override
        public void onThrowable(@NotNull Throwable error) {
          Messages.showErrorDialog(project, error.getMessage(), SyncBundle.message("rmerge.title"));
        }
      });
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
