package kinggura.sync;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.ui.TableUtil;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 설정 → 버전 관리 → 커밋 문구 이름표. 「문구 넣기」(CommitSummaryAction)가 코드에서 한국어 이름을 못 찾을 때 쓰는 영어 낱말 = 한국어 표.
 * 클래스 · 파일 · 폴더 이름(kickoff, partDev 의 part · dev)을 찾는다. 대소문자는 가리지 않는다. 값은 IDE 전체에 하나(PropertiesComponent)다.
 */
public final class CommitSummarySettings implements Configurable {
  private static final String KEY = "kinggura.sync.commitNames";
  private static final int WORD = 0;
  private static final int KOREAN = 1;
  private DefaultTableModel model;
  private JBTable table;

  /** 저장된 이름표(소문자 영어 낱말 → 한국어). */
  static Map<String, String> names() {
    return CommitSummary.parseNames(PropertiesComponent.getInstance().getValue(KEY, ""));
  }

  @Override
  public @Nls String getDisplayName() {
    return SyncBundle.message("commitnames.title");
  }

  @Override
  public @Nullable JComponent createComponent() {
    model = new DefaultTableModel(new Object[]{SyncBundle.message("commitnames.word"), SyncBundle.message("commitnames.korean")}, 0);
    table = new JBTable(model);
    table.getEmptyText().setText(SyncBundle.message("commitnames.empty"));
    JComponent list = ToolbarDecorator.createDecorator(table)
      .setAddAction(button -> {
        TableUtil.stopEditing(table);
        model.addRow(new Object[]{"", ""});
        int row = model.getRowCount() - 1;
        table.getSelectionModel().setSelectionInterval(row, row);
        table.editCellAt(row, WORD);
        TableUtil.scrollSelectionToVisible(table);
      })
      .setRemoveAction(button -> {
        TableUtil.stopEditing(table);
        int[] selected = table.getSelectedRows();
        for (int i = selected.length - 1; i >= 0; i--) model.removeRow(table.convertRowIndexToModel(selected[i]));
      })
      .disableUpDownActions()
      .createPanel();
    JComponent hint = ComponentPanelBuilder.createCommentComponent(SyncBundle.message("commitnames.hint"), true);
    hint.setBorder(JBUI.Borders.emptyTop(4));
    reset();
    return FormBuilder.createFormBuilder().addComponentFillVertically(list, 0).addComponent(hint).getPanel();
  }

  @Override
  public boolean isModified() {
    if (model == null) return false;
    try {
      return !CommitSummary.formatNames(rows()).equals(PropertiesComponent.getInstance().getValue(KEY, ""));
    }
    catch (ConfigurationException e) {
      return true;
    }
  }

  @Override
  public void apply() throws ConfigurationException {
    TableUtil.stopEditing(table);
    PropertiesComponent.getInstance().setValue(KEY, CommitSummary.formatNames(rows()), "");
  }

  @Override
  public void reset() {
    if (model == null) return;
    TableUtil.stopEditing(table);
    model.setRowCount(0);
    for (Map.Entry<String, String> entry : CommitSummary.parseNamesKeepCase(PropertiesComponent.getInstance().getValue(KEY, "")).entrySet()) {
      model.addRow(new Object[]{entry.getKey(), entry.getValue()});
    }
  }

  @Override
  public void disposeUIResources() {
    model = null;
    table = null;
  }

  /** 표의 줄. 둘 다 빈 줄은 건너뛰고, 한쪽만 비었거나 · 낱말 모양이 틀렸거나 · 같은 낱말이 두 번이면 막는다. */
  private List<String[]> rows() throws ConfigurationException {
    List<String[]> rows = new ArrayList<>();
    for (int i = 0; i < model.getRowCount(); i++) {
      String word = Objects.toString(model.getValueAt(i, WORD), "").trim();
      String korean = Objects.toString(model.getValueAt(i, KOREAN), "").trim();
      if (word.isEmpty() && korean.isEmpty()) continue;
      String problem = CommitSummary.nameProblem(rows, word, korean);
      if (problem != null) throw new ConfigurationException(SyncBundle.message(problem, i + 1, word));
      rows.add(new String[]{word, korean});
    }
    return rows;
  }
}
