package ei.db.mybatis;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultCellEditor;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/** 동적 조건 만들기 창: DTO 필드마다 사용 여부 · 칼럼 이름 · 비교 방식을 고르고 표 별칭을 정한다. */
final class WhereDialog extends DialogWrapper {
  /** DTO 필드 하나. type 은 정규 이름. */
  record Field(String name, String type) {}

  private static final String TITLE = "동적 조건 만들기";
  private static final int USE = 0;
  private static final int NAME = 1;
  private static final int TYPE = 2;
  private static final int COLUMN = 3;
  private static final int OP = 4;
  private static final String[] HEADERS = {"사용", "필드", "형식", "칼럼", "비교"};
  private static final int ALIAS_COLUMNS = 10;
  private static final int TABLE_WIDTH = 640;
  private static final int TABLE_HEIGHT = 320;
  private static final int USE_WIDTH = 50;
  private static final int OP_WIDTH = 80;

  private final List<Field> fields;
  private final JBTextField alias = new JBTextField(ALIAS_COLUMNS);
  private final DefaultTableModel model;
  private final JBTable table;

  WhereDialog(Project project, String dtoName, List<Field> fields, String guessedAlias) {
    super(project);
    this.fields = fields;
    model = new DefaultTableModel(HEADERS, 0) {
      @Override
      public Class<?> getColumnClass(int column) { return column == USE ? Boolean.class : String.class; }

      @Override
      public boolean isCellEditable(int row, int column) { return column == USE || column == COLUMN || column == OP; }
    };
    for (Field f : fields) {
      model.addRow(new Object[]{Boolean.FALSE, f.name(), ServiceCode.simple(f.type()), WhereGen.column(f.name()), WhereGen.EQ});
    }
    table = new JBTable(model);
    table.getColumnModel().getColumn(OP).setCellEditor(new DefaultCellEditor(new ComboBox<>(WhereGen.OPS.toArray(new String[0]))));
    table.getColumnModel().getColumn(USE).setMaxWidth(JBUI.scale(USE_WIDTH));
    table.getColumnModel().getColumn(OP).setMaxWidth(JBUI.scale(OP_WIDTH));
    alias.setText(guessedAlias);
    setTitle(TITLE + " — " + dtoName);
    setOKButtonText("넣기");
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    top.add(new JBLabel("표 별칭 (비우면 붙이지 않음): "));
    top.add(alias);
    JBScrollPane scroll = new JBScrollPane(table);
    scroll.setPreferredSize(JBUI.size(TABLE_WIDTH, TABLE_HEIGHT));
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
    panel.add(top, BorderLayout.NORTH);
    panel.add(scroll, BorderLayout.CENTER);
    return panel;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    stopEditing();
    String a = alias.getText().trim();
    if (!a.isEmpty() && !WhereGen.validName(a)) return new ValidationInfo("표 별칭은 영문 · 숫자 · _ 로 된 이름이어야 합니다.", alias);
    boolean any = false;
    for (int r = 0; r < model.getRowCount(); r++) {
      if (!Boolean.TRUE.equals(model.getValueAt(r, USE))) continue;
      any = true;
      String col = String.valueOf(model.getValueAt(r, COLUMN)).trim();
      if (!WhereGen.validName(col)) return new ValidationInfo(fields.get(r).name() + " 의 칼럼 이름이 올바르지 않습니다: " + col, table);
    }
    return any ? null : new ValidationInfo("조건으로 쓸 필드를 하나 이상 고르세요.", table);
  }

  /** 고른 필드의 조건(표 순서). OK 로 닫힌 뒤 부른다. */
  List<WhereGen.Cond> conds() {
    stopEditing();
    List<WhereGen.Cond> out = new ArrayList<>();
    for (int r = 0; r < model.getRowCount(); r++) {
      if (!Boolean.TRUE.equals(model.getValueAt(r, USE))) continue;
      Field f = fields.get(r);
      out.add(new WhereGen.Cond(f.name(), String.valueOf(model.getValueAt(r, COLUMN)).trim(), f.type(),
          String.valueOf(model.getValueAt(r, OP))));
    }
    return out;
  }

  String alias() { return alias.getText().trim(); }

  private void stopEditing() {
    if (table.isEditing() && table.getCellEditor() != null) table.getCellEditor().stopCellEditing();
  }
}
