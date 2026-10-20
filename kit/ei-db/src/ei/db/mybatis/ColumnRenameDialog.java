package ei.db.mybatis;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/** 「칼럼 이름 바꾸기」 입력 창. 확인하면 input() 으로 값을 준다. */
final class ColumnRenameDialog extends DialogWrapper {
  static final String TITLE = "칼럼 이름 바꾸기";
  private static final int FIELD_COLUMNS = 28;

  /** 창에서 받은 값. table 이 비면 모든 매퍼. */
  record Input(String oldName, String newName, String table, boolean renameDto) {}

  /** 미리보기 한 줄. stamp 는 찾을 때의 문서 수정 표시(바꾸기 전에 견준다). line 은 1부터. */
  record Row(VirtualFile file, Document doc, long stamp, ColumnRename.Hit hit, int line, String lineText) {}

  private final JBTextField oldField = new JBTextField(FIELD_COLUMNS);
  private final JBTextField newField = new JBTextField(FIELD_COLUMNS);
  private final JBTextField tableField = new JBTextField(FIELD_COLUMNS);
  private final JBCheckBox dtoBox = new JBCheckBox("DTO 필드도 바꾸기(resultMap property · #{} · getter · setter)", true);

  ColumnRenameDialog(Project project, String initialOld) {
    super(project);
    oldField.setText(initialOld == null ? "" : initialOld);
    tableField.getEmptyText().setText("비우면 모든 매퍼");
    setTitle(TITLE);
    init();
  }

  Input input() {
    return new Input(oldField.getText().trim(), newField.getText().trim(), tableField.getText().trim(), dtoBox.isSelected());
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    return FormBuilder.createFormBuilder()
        .addLabeledComponent("옛 칼럼 이름", oldField)
        .addLabeledComponent("새 칼럼 이름", newField)
        .addLabeledComponent("표 이름(선택)", tableField)
        .addComponent(dtoBox)
        .getPanel();
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return oldField.getText().isEmpty() ? oldField : newField;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    Input in = input();
    String message = ColumnRename.validate(in.oldName(), in.newName(), in.table());
    if (message == null) return null;
    JComponent at = ColumnRename.NAME.matcher(in.oldName()).matches() ? newField : oldField;
    if (message.startsWith("표")) at = tableField;
    return new ValidationInfo(message, at);
  }

  /** 찾은 곳을 보여 주고 바꿀 곳만 남기게 한다. 확인하면 chosen() 이 체크된 줄들. */
  static final class Preview extends DialogWrapper {
    private static final int COL_CHECK = 0;
    private static final int COL_FILE = 1;
    private static final int COL_LINE = 2;
    private static final int COL_TEXT = 3;
    private static final int CHECK_WIDTH = 48;
    private static final int FILE_WIDTH = 220;
    private static final int LINE_WIDTH = 56;
    private static final int WIDTH = 900;
    private static final int HEIGHT = 420;

    private final List<Row> rows;
    private final DefaultTableModel model = new DefaultTableModel(new Object[] {"바꿈", "파일", "줄", "내용"}, 0) {
      @Override
      public Class<?> getColumnClass(int column) {
        return column == COL_CHECK ? Boolean.class : column == COL_LINE ? Integer.class : String.class;
      }

      @Override
      public boolean isCellEditable(int row, int column) {
        return column == COL_CHECK;
      }
    };
    private final String summary;

    Preview(Project project, Input in, List<Row> rows) {
      super(project);
      this.rows = rows;
      for (Row r : rows) model.addRow(new Object[] {Boolean.TRUE, r.file().getName(), r.line(), r.lineText()});
      summary = in.oldName() + " → " + in.newName() + " · " + rows.size() + "곳";
      setTitle(TITLE + " — 미리보기");
      setOKButtonText("바꾸기");
      init();
    }

    List<Row> chosen() {
      List<Row> out = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) {
        if (Boolean.TRUE.equals(model.getValueAt(i, COL_CHECK))) out.add(rows.get(i));
      }
      return out;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
      JBTable table = new JBTable(model);
      table.setShowGrid(false);
      table.getColumnModel().getColumn(COL_CHECK).setMaxWidth(JBUI.scale(CHECK_WIDTH));
      table.getColumnModel().getColumn(COL_FILE).setPreferredWidth(JBUI.scale(FILE_WIDTH));
      table.getColumnModel().getColumn(COL_LINE).setMaxWidth(JBUI.scale(LINE_WIDTH));
      table.getColumnModel().getColumn(COL_TEXT).setPreferredWidth(JBUI.scale(WIDTH - FILE_WIDTH - CHECK_WIDTH - LINE_WIDTH));
      JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(8)));
      p.add(new JBLabel(summary), BorderLayout.NORTH);
      p.add(new JBScrollPane(table), BorderLayout.CENTER);
      p.setPreferredSize(new Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT)));
      return p;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
      return chosen().isEmpty() ? new ValidationInfo("바꿀 곳을 하나 이상 고르세요.") : null;
    }
  }
}
