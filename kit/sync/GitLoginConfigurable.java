package kinggura.sync;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.ComponentPanelBuilder;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.event.MouseEvent;
import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 설정 → 버전 관리 → Git 로그인. 서버별 https 로그인을 넣고 고치고 뺀다.
 * 표에는 서버 · 방식 · 아이디만 보이고 비밀번호 · 토큰은 보이지 않는다. 저장은 Apply 때 한 번에 한다.
 */
public final class GitLoginConfigurable implements Configurable {
  private final List<GitLogins.Login> rows = new ArrayList<>();
  /** 마지막 reset · apply 때의 목록(secret 없음). 바뀜 판단용. */
  private List<GitLogins.Login> saved = List.of();
  private boolean secretChanged;
  private final Model model = new Model();
  private JBTable table;

  @Override
  public @Nls String getDisplayName() {
    return SyncBundle.message("gitlogin.title");
  }

  @Override
  public @Nullable JComponent createComponent() {
    table = new JBTable(model);
    table.getEmptyText().setText(SyncBundle.message("gitlogin.empty"));
    JComponent list = ToolbarDecorator.createDecorator(table)
      .setAddAction(button -> add())
      .setEditAction(button -> edit())
      .setRemoveAction(button -> remove())
      .createPanel();
    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(@NotNull MouseEvent event) {
        edit();
        return true;
      }
    }.installOn(table);
    // 설명은 IntelliJ 설정 화면의 회색 안내 글(comment)로, 창 폭에 맞춰 줄을 바꾼다.
    JComponent hint = ComponentPanelBuilder.createCommentComponent(SyncBundle.message("gitlogin.hint"), true);
    hint.setBorder(JBUI.Borders.emptyTop(4));
    return FormBuilder.createFormBuilder().addComponentFillVertically(list, 0).addComponent(hint).getPanel();
  }

  private void add() {
    GitLoginDialog dialog = new GitLoginDialog(null);
    if (!dialog.showAndGet()) return;
    put(dialog.result(), -1);
  }

  private void edit() {
    int row = table.getSelectedRow();
    if (row < 0) return;
    GitLoginDialog dialog = new GitLoginDialog(rows.get(row));
    if (!dialog.showAndGet()) return;
    GitLogins.Login changed = dialog.result();
    // 이번에 넣고 아직 Apply 하지 않은 줄을 비밀번호 칸을 비운 채 고치면, 앞서 넣은 값을 잇는다.
    if (changed.secret() == null) {
      changed = new GitLogins.Login(changed.server(), changed.kind(), changed.user(), rows.get(row).secret());
    }
    put(changed, row);
  }

  /** 같은 서버가 이미 있으면 그 줄을 바꾼다. replace 가 -1 이 아니면 그 줄을 지우고 넣는다. */
  private void put(GitLogins.Login login, int replace) {
    if (replace >= 0) rows.remove(replace);
    GitLogins.Login same = GitLogins.find(rows, login.server());
    if (same != null) rows.remove(same);
    rows.add(login);
    if (login.secret() != null) secretChanged = true;
    model.fireTableDataChanged();
  }

  private void remove() {
    int row = table.getSelectedRow();
    if (row < 0) return;
    rows.remove(row);
    model.fireTableDataChanged();
  }

  @Override
  public boolean isModified() {
    return secretChanged || !GitLogins.format(rows).equals(GitLogins.format(saved));
  }

  @Override
  public void apply() throws ConfigurationException {
    List<GitLogins.Login> copy = List.copyOf(rows);
    try {
      // 비밀번호 저장소(KeePass · 윈도우 자격 증명)는 화면 스레드에서 열지 않는다.
      ProgressManager.getInstance().runProcessWithProgressSynchronously(
        () -> GitLogins.save(copy), SyncBundle.message("gitlogin.saving"), false, null);
    }
    catch (RuntimeException e) {
      throw new ConfigurationException(SyncBundle.message("gitlogin.error.save", e.getMessage()));
    }
    reset();
  }

  @Override
  public void reset() {
    rows.clear();
    rows.addAll(GitLogins.list());
    saved = List.copyOf(rows);
    secretChanged = false;
    model.fireTableDataChanged();
  }

  private final class Model extends AbstractTableModel {
    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return 3;
    }

    @Override
    public String getColumnName(int column) {
      return SyncBundle.message(column == 0 ? "gitlogin.server" : column == 1 ? "gitlogin.kind" : "gitlogin.user");
    }

    @Override
    public Object getValueAt(int row, int column) {
      GitLogins.Login login = rows.get(row);
      if (column == 0) return login.server();
      if (column == 1) return SyncBundle.message(login.kind() == GitLogins.Kind.TOKEN ? "gitlogin.kind.token" : "gitlogin.kind.password");
      return login.user();
    }
  }
}
