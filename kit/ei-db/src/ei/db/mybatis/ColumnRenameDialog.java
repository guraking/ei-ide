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
import ei.db.EiDbBundle;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

/** 「칼럼 이름 바꾸기」 입력 창. 확인하면 input() 으로 값을 준다. */
final class ColumnRenameDialog extends DialogWrapper {
  /** 입력 칸 폭은 화면 폭의 이 비율(칸은 그 줄을 다 채운다). */
  private static final double FORM_SCREEN_RATIO = 0.3;

  /** 창에서 받은 값. table 이 비면 모든 매퍼. */
  record Input(String oldName, String newName, String table, boolean renameDto) {}

  /** 미리보기 한 줄. stamp 는 찾을 때의 문서 수정 표시(바꾸기 전에 견준다). line 은 1부터. */
  record Row(VirtualFile file, Document doc, long stamp, ColumnRename.Hit hit, int line, String lineText) {}

  private final JBTextField oldField = new JBTextField();
  private final JBTextField newField = new JBTextField();
  private final JBTextField tableField = new JBTextField();
  private final JBCheckBox dtoBox = new JBCheckBox(EiDbBundle.message("columnrename.dto.box"), true);

  ColumnRenameDialog(Project project, String initialOld) {
    super(project);
    oldField.setText(initialOld == null ? "" : initialOld);
    tableField.getEmptyText().setText(EiDbBundle.message("columnrename.table.hint"));
    setTitle(EiDbBundle.message("columnrename.title"));
    init();
  }

  Input input() {
    return new Input(oldField.getText().trim(), newField.getText().trim(), tableField.getText().trim(), dtoBox.isSelected());
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel form = FormBuilder.createFormBuilder()
        .addLabeledComponent(EiDbBundle.message("columnrename.old"), oldField)
        .addLabeledComponent(EiDbBundle.message("columnrename.new"), newField)
        .addLabeledComponent(EiDbBundle.message("columnrename.table"), tableField)
        .addComponent(dtoBox)
        .getPanel();
    int width = (int) (Toolkit.getDefaultToolkit().getScreenSize().width * FORM_SCREEN_RATIO);
    form.setPreferredSize(new Dimension(Math.max(width, form.getPreferredSize().width), form.getPreferredSize().height));
    return form;
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
    private static final int COL_LINE = 2;
    /** 칸의 처음 너비 비율(체크 · 파일 · 줄 · 내용). 창 폭에 따라 함께 늘고 사용자가 끌어 바꿀 수 있다. */
    private static final int[] COLUMN_WEIGHTS = {1, 5, 1, 14};
    /** 처음 열 때 화면 폭 · 높이에 대한 창 크기 비율. 사용자가 바꾼 크기는 IntelliJ 가 기억한다. */
    private static final double FIRST_WIDTH_RATIO = 0.5;
    private static final double FIRST_HEIGHT_RATIO = 0.45;
    private static final String DIMENSION_KEY = "ei.db.mybatis.ColumnRenameDialog.Preview";

    private final List<Row> rows;
    private final DefaultTableModel model = new DefaultTableModel(new Object[] {EiDbBundle.message("columnrename.col.change"), EiDbBundle.message("common.col.file"), EiDbBundle.message("common.col.line"), EiDbBundle.message("columnrename.col.content")}, 0) {
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
      summary = EiDbBundle.message("columnrename.summary", in.oldName(), in.newName(), rows.size());
      setTitle(EiDbBundle.message("columnrename.preview.title"));
      setOKButtonText(EiDbBundle.message("columnrename.ok"));
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
      int unit = (int) (Toolkit.getDefaultToolkit().getScreenSize().width * FIRST_WIDTH_RATIO) / sum(COLUMN_WEIGHTS);
      for (int c = 0; c < COLUMN_WEIGHTS.length; c++) table.getColumnModel().getColumn(c).setPreferredWidth(unit * COLUMN_WEIGHTS[c]);
      JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(8)));
      p.add(new JBLabel(summary), BorderLayout.NORTH);
      p.add(new JBScrollPane(table), BorderLayout.CENTER);
      return p;
    }

    private static int sum(int[] values) {
      int total = 0;
      for (int v : values) total += v;
      return total;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

    @Override
    public Dimension getInitialSize() {
      Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
      return new Dimension((int) (screen.width * FIRST_WIDTH_RATIO), (int) (screen.height * FIRST_HEIGHT_RATIO));
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
      return chosen().isEmpty() ? new ValidationInfo(EiDbBundle.message("columnrename.none.chosen")) : null;
    }
  }
}
