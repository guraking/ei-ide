package kinggura.sync;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static kinggura.sync.SyncPanel.git;

/**
 * Git 메뉴 「Merge Branches…」(역머지, 반대 방향도 된다). 서버 브랜치 목록을 읽어 From(기준 브랜치) · Into(개인 브랜치)를 고르게 하고,
 * 합치기 전에 들어올 파일 · 내 파일과 겹치는 파일을 보여 준다. Merge 를 눌러야 합친다.
 * 고른 두 브랜치는 저장소의 .git/config 에 남겨 다음에 그대로 고른다(ReverseMerge.CONFIG_*).
 */
public final class ReverseMergeAction extends DumbAwareAction {
  private static final String ACTION_RESOLVE = "Git.ResolveConflicts";
  /** 내 파일 줄 글자색. 동기화 창 · 설명서에서 「주의」에 쓰는 주황. */
  static final JBColor MINE_COLOR = new JBColor(0xB45F06, 0xFF9F0A);
  private static final int TABLE_W = 760;
  private static final int TABLE_H = 320;
  private static final int PATH_COL_W = 360;
  // From · Into 상자 너비. 「fe-기능이름/날짜/아이디」 같은 긴 브랜치 이름이 잘리지 않게 두 상자가 표 너비를 나눠 쓴다.
  private static final int COMBO_W = 300;

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
        try {
          remote = ReverseMerge.remoteOf(root, current);
          names = ReverseMerge.serverBranches(root, remote);
          savedFrom = ReverseMerge.config(root, ReverseMerge.CONFIG_FROM);
          savedInto = ReverseMerge.config(root, ReverseMerge.CONFIG_INTO);
        }
        catch (IOException ex) {
          throw new RuntimeException(ex.getMessage(), ex);
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(SyncBundle.message("sync.interrupted"), ex);
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
    private final RowModel model = new RowModel();
    private final JBCheckBox push = new JBCheckBox();
    // 미리 보기를 다시 읽을 때마다 하나 올린다(EDT 에서만). 늦게 끝난 옛 계산이 새 결과를 덮지 않게 한다.
    private int seq;
    private boolean ready;

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
      fromBox.setRenderer(SimpleListCellRenderer.create("", name -> remote + "/" + name));
      fromBox.setMinimumAndPreferredWidth(JBUI.scale(COMBO_W));
      intoBox.setMinimumAndPreferredWidth(JBUI.scale(COMBO_W));
      push.setSelected(true);
      setTitle(SyncBundle.message("rmerge.title"));
      setOKButtonText(SyncBundle.message("rmerge.button.merge"));
      init();
      fromBox.addActionListener(e -> reload());
      intoBox.addActionListener(e -> reload());
      reload();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
      // IntelliJ Pull 창처럼 어느 저장소 · 원격에서 받는지 먼저 보인다. From 은 원격 브랜치, Into 는 로컬 브랜치다.
      JPanel where = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
      where.add(new JBLabel(SyncBundle.message("rmerge.repository")));
      where.add(new JBLabel(root.getName()).withFont(JBFont.label().asBold()));
      where.add(new JBLabel(root.getPath(), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER));
      JPanel pick = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
      pick.add(new JBLabel(SyncBundle.message("rmerge.from")));
      pick.add(fromBox);
      pick.add(new JBLabel(SyncBundle.message("rmerge.into")));
      pick.add(intoBox);

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
      // From 은 로컬이 아니라 서버 브랜치를 합친다. 상자에는 origin/ 을 붙여 보이고 아래 줄에 적는다.
      rows.add(new JBLabel(SyncBundle.message("rmerge.from.server", remote), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER));
      top.add(rows, BorderLayout.NORTH);
      JPanel lines = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(2)));
      lines.add(summary);
      lines.add(warn);
      lines.add(unpushed);
      unpushed.setForeground(NamedColorUtil.getErrorForeground());
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
      model.set(List.of());
      String from = from();
      String into = into();
      push.setText(SyncBundle.message("rmerge.push", remote + "/" + into));
      if (from == null || into == null || from.equals(into)) {
        summary.setText(SyncBundle.message("rmerge.same"));
        return;
      }
      summary.setText(SyncBundle.message("rmerge.loading"));
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        ReverseMerge.Preview preview = null;
        String error = null;
        int localOnly = 0;
        try {
          ReverseMerge.fetch(root, remote, from, into);
          preview = ReverseMerge.preview(root, remote, from, into, current);
          localOnly = ReverseMerge.unpushed(root, remote, from);
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
        SwingUtilities.invokeLater(() -> {
          if (mySeq != seq || isDisposed()) return;
          if (failure != null) {
            summary.setText(failure);
            summary.setToolTipText(failure);
            return;
          }
          model.set(result.files());
          if (notOnServer > 0) unpushed.setText(SyncBundle.message("rmerge.unpushed", remote, from, notOnServer));
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
        String left;
        String right;
        try {
          left = show(intoTip, item.path());
          right = show(ReverseMerge.remoteRef(remote, from), item.path());
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
        SwingUtilities.invokeLater(() -> {
          var type = FileTypeManager.getInstance().getFileTypeByFileName(new File(item.path()).getName());
          DiffContentFactory factory = DiffContentFactory.getInstance();
          DiffManager.getInstance().showDiff(project, new SimpleDiffRequest(item.path(), factory.create(project, left, type),
                                                                            factory.create(project, right, type), into, from));
        });
      });
    }

    private String show(String ref, String path) throws InterruptedException {
      try {
        return git(root, "show", ref + ":" + path);
      }
      catch (IOException missing) {
        return "";  // 그 브랜치에 없는 파일(새로 생겼거나 지워짐)
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

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          try {
            ReverseMerge.saveConfig(root, ReverseMerge.CONFIG_FROM, from);
            ReverseMerge.saveConfig(root, ReverseMerge.CONFIG_INTO, into);
            result = ReverseMerge.merge(root, remote, from, into, current, pushAfter);
          }
          catch (IOException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
          }
          catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(SyncBundle.message("sync.interrupted"), ex);
          }
          finally {
            // 성공 · 실패 모두 브랜치 · 파일이 바뀌었을 수 있어 IDE 가 알던 상태를 새로 읽는다.
            VfsUtil.markDirtyAndRefresh(false, true, true, repo.getRoot());
            repo.update();
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
          }
        }

        @Override
        public void onSuccess() {
          if (result == ReverseMerge.Result.CONFLICT) {
            Messages.showWarningDialog(project, SyncBundle.message("rmerge.conflict", from, into), SyncBundle.message("rmerge.title"));
            resolveConflicts(repo.getRoot());
            return;
          }
          Messages.showInfoMessage(project, pushAfter ? SyncBundle.message("rmerge.done.pushed", from, into, remote)
                                                      : SyncBundle.message("rmerge.done", from, into), SyncBundle.message("rmerge.title"));
        }

        @Override
        public void onThrowable(@NotNull Throwable error) {
          Messages.showErrorDialog(project, error.getMessage(), SyncBundle.message("rmerge.title"));
        }
      });
    }

    /** IntelliJ 충돌 해결 창을 이 저장소로 연다. */
    private void resolveConflicts(VirtualFile rootFile) {
      AnAction action = ActionManager.getInstance().getAction(ACTION_RESOLVE);
      if (action == null) return;
      var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, rootFile).build();
      AnActionEvent event = AnActionEvent.createFromAnAction(action, null, "KingGuraReverseMerge", context);
      ActionUtil.performActionDumbAwareWithCallbacks(action, event);
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
