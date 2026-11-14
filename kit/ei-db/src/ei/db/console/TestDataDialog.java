package ei.db.console;

import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.FileTypes;
import ei.db.Meta;
import ei.db.QueryResult;
import ei.db.gen.Names;
import ei.db.gen.Ui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 탐색기 「테스트 데이터 만들기」 창. 줄 수 · 씨앗과 칼럼마다 만드는 방식 · 값을 고르면 아래에 처음 몇 줄의 INSERT 문을 미리 보인다.
 * 설정이 틀리면 미리보기에 오류 줄을 보이고 보내는 단추가 꺼진다. DB 는 「현재 최댓값 다음부터」 · 「참조 표에서 실제 값 가져오기」를 누를 때만 읽는다.
 * 바로 넣기는 RowInserter.insert(「엑셀 붙여 넣어 행 넣기」와 같은 길)를 쓴다. 다른 창을 막지 않는 창이다.
 */
public final class TestDataDialog extends DialogWrapper {
  private static final String DIMENSION_KEY = "ei.db.TestDataDialog";
  private static final double FIRST_WIDTH_RATIO = 0.65;
  private static final double FIRST_HEIGHT_RATIO = 0.75;
  private static final float PLAN_PROPORTION = 0.55f;
  private static final String SPLIT_KEY = "ei.db.testdata.split";
  private static final String COLUMNS_KEY = "testdata";
  private static final int GAP = 6;
  private static final int ROW_STEP = 100;
  private static final String PLAIN_NUMBER_FORMAT = "#";
  private static final String SQL_EXT = "sql";
  private static final String COMMENT = "-- ";
  private static final int COL_NAME = 0;
  private static final int COL_TYPE = 1;
  private static final int COL_NULLABLE = 2;
  private static final int COL_METHOD = 3;
  private static final int COL_ARG = 4;
  private static final int COLUMN_COUNT = 5;

  private final Project project;
  private final RowInserter.Table target;
  private final LocalDate today = LocalDate.now();
  private final List<TestData.Method> methods = new ArrayList<>();
  private final List<String> args = new ArrayList<>();
  private final JSpinner rowCount = new JSpinner(new SpinnerNumberModel(TestData.DEFAULT_ROWS, 1, TestData.MAX_ROWS, ROW_STEP));
  private final JSpinner seed = new JSpinner(new SpinnerNumberModel(Long.valueOf(TestData.DEFAULT_SEED), Long.valueOf(0), Long.valueOf(Long.MAX_VALUE), Long.valueOf(1)));
  private final PlanModel model = new PlanModel();
  /** 표는 target · methods · args 가 채워진 뒤(생성자) 만든다: 만들 때 모델을 읽는다. */
  private final JBTable table;
  private final JButton maxButton = new JButton(EiDbBundle.message("testdata.max.next"));
  private final JButton fkButton = new JButton(EiDbBundle.message("testdata.fk.values"));
  private final JBLabel hint = new JBLabel();
  private final EditorTextField preview;
  private final Action consoleAction = new DialogWrapperAction(EiDbBundle.message("rows.to.console")) {
    @Override
    protected void doAction(ActionEvent e) { toConsole(); }
  };
  private final Action fileAction = new DialogWrapperAction(EiDbBundle.message("rows.save.file")) {
    @Override
    protected void doAction(ActionEvent e) { toFile(); }
  };
  private final Action insertAction = new DialogWrapperAction(EiDbBundle.message("rows.insert.now")) {
    @Override
    protected void doAction(ActionEvent e) { insertNow(); }
  };
  private boolean busy;
  private boolean valid;

  /** 표 정보를 뒤에서 읽은 뒤 창을 연다. 읽지 못하면 알림만 뜬다. */
  public static void open(Project project, DbConfig c, String schema, String table) {
    RowInserter.load(project, c, schema, table, t -> new TestDataDialog(project, t).show());
  }

  private TestDataDialog(Project project, RowInserter.Table target) {
    super(project, true, IdeModalityType.MODELESS);
    this.project = project;
    this.target = target;
    for (Meta.Column col : target.columns()) {
      TestData.Plan p = TestData.defaultPlan(col, target.withDefault().contains(col.name()), today);
      methods.add(p.method());
      args.add(p.arg());
    }
    table = new JBTable(model);
    preview = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, FileTypes.byExtension(SQL_EXT), true, false);
    preview.addSettingsProvider(e -> {
      e.setVerticalScrollbarVisible(true);
      e.setHorizontalScrollbarVisible(true);
    });
    setTitle(EiDbBundle.message("testdata.title", target.name()));
    setCancelButtonText(EiDbBundle.message("common.close"));
    init();
    refresh();
  }

  @Override
  protected JComponent createCenterPanel() {
    rowCount.setEditor(new JSpinner.NumberEditor(rowCount, PLAIN_NUMBER_FORMAT));
    seed.setEditor(new JSpinner.NumberEditor(seed, PLAIN_NUMBER_FORMAT));
    rowCount.addChangeListener(e -> refresh());
    seed.addChangeListener(e -> refresh());
    JPanel counts = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0));
    counts.add(new JBLabel(EiDbBundle.message("testdata.rows")));
    counts.add(rowCount);
    counts.add(new JBLabel(EiDbBundle.message("testdata.seed")));
    counts.add(seed);

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    table.getTableHeader().setReorderingAllowed(false);
    table.getColumnModel().getColumn(COL_METHOD).setCellEditor(new MethodEditor());
    table.getColumnModel().getColumn(COL_METHOD).setCellRenderer(new DefaultTableCellRenderer() {
      @Override
      protected void setValue(Object value) {
        setText(value instanceof TestData.Method m ? label(m) : "");
      }
    });
    Ui.fitColumn(table, COL_NULLABLE, "");
    Ui.rememberColumns(table, COLUMNS_KEY);
    table.getSelectionModel().addListSelectionListener(e -> updateTools());

    maxButton.addActionListener(e -> readMax());
    fkButton.addActionListener(e -> readFkValues());
    hint.setForeground(UIUtil.getContextHelpForeground());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0));
    buttons.add(maxButton);
    buttons.add(fkButton);
    JPanel tools = new JPanel(new BorderLayout(JBUI.scale(GAP), 0));
    tools.add(buttons, BorderLayout.WEST);
    tools.add(hint, BorderLayout.CENTER);

    JPanel plan = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    plan.add(counts, BorderLayout.NORTH);
    plan.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
    plan.add(tools, BorderLayout.SOUTH);

    JBSplitter split = new JBSplitter(true, PLAN_PROPORTION);
    split.setSplitterProportionKey(SPLIT_KEY);
    split.setHonorComponentsMinimumSize(false);
    split.setFirstComponent(plan);
    split.setSecondComponent(preview);
    if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
    return split;
  }

  private static String label(TestData.Method m) {
    return EiDbBundle.message("testdata.method." + m.name());
  }

  private boolean hasDefault(Meta.Column col) {
    return target.withDefault().contains(col.name());
  }

  private List<TestData.Plan> plans() {
    List<TestData.Plan> out = new ArrayList<>();
    for (int i = 0; i < target.columns().size(); i++) out.add(new TestData.Plan(target.columns().get(i), methods.get(i), args.get(i)));
    return out;
  }

  private int rows() { return ((Number) rowCount.getValue()).intValue(); }

  private long seedValue() { return ((Number) seed.getValue()).longValue(); }

  private InsertRows.Batch batch(TestData.Output o) {
    return new InsertRows.Batch(target.c().kind, target.sqlName(), o.columns(), o.rows());
  }

  /** 미리보기 · 단추를 지금 설정에 맞춘다. 오류 · 주의는 SQL 주석 줄로 미리보기 맨 위에 보인다. */
  private void refresh() {
    TestData.Output o = TestData.preview(target.c().kind, plans(), target.withDefault(), rows(), seedValue());
    StringBuilder text = new StringBuilder();
    for (String e : o.errors()) text.append(COMMENT).append(EiDbBundle.message("testdata.error", e)).append('\n');
    for (String w : o.warnings()) text.append(COMMENT).append(EiDbBundle.message("testdata.warning", w)).append('\n');
    if (rows() > InsertRows.CONSOLE_MAX_ROWS) {
      text.append(COMMENT).append(EiDbBundle.message("testdata.prefer.file", InsertRows.CONSOLE_MAX_ROWS)).append('\n');
    }
    if (text.length() > 0) text.append('\n');
    text.append(InsertRows.text(batch(o)));
    preview.setText(text.toString());
    valid = o.errors().isEmpty();
    updateButtons();
    updateTools();
  }

  private void updateButtons() {
    consoleAction.setEnabled(valid && !busy);
    fileAction.setEnabled(valid && !busy);
    insertAction.setEnabled(valid && !busy);
  }

  /** 고른 줄에 맞춰 도구 단추와 값 칸 안내를 바꾼다. */
  private void updateTools() {
    int row = table.getSelectedRow();
    boolean picked = row >= 0 && row < methods.size();
    Meta.Column col = picked ? target.columns().get(row) : null;
    maxButton.setEnabled(picked && methods.get(row) == TestData.Method.SEQUENCE);
    fkButton.setEnabled(picked && col.fk() && TestData.methods(col, hasDefault(col)).contains(TestData.Method.LIST));
    hint.setText(picked ? EiDbBundle.message("testdata.hint." + methods.get(row).name()) : "");
  }

  private void stopEditing() {
    if (table.isEditing()) table.getCellEditor().stopCellEditing();
  }

  /** 「현재 최댓값 다음부터」: 고른 칼럼의 MAX 를 읽어 순번 시작값을 MAX + 1 로 둔다. 표가 비었으면 1. */
  private void readMax() {
    stopEditing();
    int row = table.getSelectedRow();
    if (row < 0) return;
    DbConfig c = target.c();
    String column = Names.sql(c.kind, target.columns().get(row).name());
    String sql = "SELECT\n       MAX(t." + column + ")\n  FROM " + target.sqlName() + " t";
    RowInserter.query(project, c, EiDbBundle.message("testdata.max.next"), sql, 1, result -> {
      if (isDisposed()) return;
      Object max = firstValue(result);
      if (max == null) {
        model.setValueAt(BigDecimal.ONE.toPlainString(), row, COL_ARG);
        return;
      }
      try {
        model.setValueAt(new BigDecimal(SqlText.text(max).strip()).toBigInteger().add(java.math.BigInteger.ONE).toString(), row, COL_ARG);
      } catch (NumberFormatException e) {
        Messages.showErrorDialog(project, EiDbBundle.message("testdata.max.not.number", SqlText.text(max)), getTitle());
      }
    });
  }

  private static Object firstValue(QueryResult result) {
    return result.rows().isEmpty() || result.rows().get(0).isEmpty() ? null : result.rows().get(0).get(0);
  }

  /**
   * 「참조 표에서 실제 값 가져오기」: 고른 FK 칼럼이 가리키는 표의 칼럼 값을 TestData.FK_SAMPLE_MAX 개까지 읽어 「목록 돌려 쓰기」 값으로 넣는다.
   * 참조 표가 비었으면 알린다. NULL · 쉼표가 든 값은 목록에 쓸 수 없어 뺀다.
   */
  private void readFkValues() {
    stopEditing();
    int row = table.getSelectedRow();
    if (row < 0) return;
    DbConfig c = target.c();
    Meta.Column col = target.columns().get(row);
    Meta.ForeignKey fk = null;
    for (Meta.ForeignKey k : target.fks()) {
      if (fk == null && k.fkColumn().equals(col.name())) fk = k;
    }
    if (fk == null) return;
    String parent = fk.pkTable();
    String sql = "SELECT\n       t." + Names.sql(c.kind, fk.pkColumn()) + "\n  FROM " + Names.sql(c.kind, target.schema(), parent) + " t";
    RowInserter.query(project, c, EiDbBundle.message("testdata.fk.values"), sql, TestData.FK_SAMPLE_MAX, result -> {
      if (isDisposed()) return;
      List<String> values = new ArrayList<>();
      for (List<Object> r : result.rows()) {
        String v = r.isEmpty() || r.get(0) == null ? "" : SqlText.text(r.get(0)).strip();
        if (!v.isEmpty() && !v.contains(TestData.LIST_SEPARATOR) && !values.contains(v)) values.add(v);
      }
      if (values.isEmpty()) {
        Messages.showInfoMessage(project, EiDbBundle.message("testdata.fk.empty", parent), getTitle());
        return;
      }
      methods.set(row, TestData.Method.LIST);
      args.set(row, String.join(TestData.LIST_SEPARATOR + " ", values));
      model.fireTableRowsUpdated(row, row);
      refresh();
    });
  }

  /** 지금 설정으로 모든 행을 만든다(행이 많을 수 있어 진행 창을 띄우고 뒤에서). 오류가 있으면 알리고 null. */
  private InsertRows.Batch generate() {
    stopEditing();
    List<TestData.Plan> plans = plans();
    int count = rows();
    long seedNow = seedValue();
    TestData.Output o = ProgressManager.getInstance().runProcessWithProgressSynchronously(
        () -> TestData.generate(target.c().kind, plans, target.withDefault(), count, seedNow), getTitle(), false, project);
    if (!o.errors().isEmpty()) {
      Messages.showErrorDialog(project, String.join("\n", o.errors()), getTitle());
      return null;
    }
    return batch(o);
  }

  private void toConsole() {
    InsertRows.Batch b = generate();
    if (b != null && RowInserter.toConsole(project, target.c(), b)) close(OK_EXIT_CODE);
  }

  private void toFile() {
    InsertRows.Batch b = generate();
    if (b != null) RowInserter.saveFile(project, b);
  }

  private void insertNow() {
    InsertRows.Batch b = generate();
    if (b == null) return;
    busy = true;
    updateButtons();
    RowInserter.insert(project, target.c(), target.name(), b, ok -> {
      busy = false;
      if (isDisposed()) return;
      if (ok) close(OK_EXIT_CODE);
      else updateButtons();
    });
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[]{consoleAction, fileAction, insertAction, getCancelAction()};
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() { return Ui.firstSize(FIRST_WIDTH_RATIO, FIRST_HEIGHT_RATIO); }

  @Override
  public JComponent getPreferredFocusedComponent() { return table; }

  /** 칼럼마다 한 줄: 칼럼 · 형식 · NULL 허용 · 만드는 방식(고침) · 방식 값/범위(고침). 방식을 바꾸면 값 칸을 그 방식의 기본값으로 채운다. */
  private final class PlanModel extends AbstractTableModel {
    @Override
    public int getRowCount() { return target.columns().size(); }

    @Override
    public int getColumnCount() { return COLUMN_COUNT; }

    @Override
    public String getColumnName(int column) {
      return switch (column) {
        case COL_NAME -> EiDbBundle.message("common.col.column");
        case COL_TYPE -> EiDbBundle.message("common.col.type");
        case COL_NULLABLE -> EiDbBundle.message("testdata.col.nullable");
        case COL_METHOD -> EiDbBundle.message("testdata.col.method");
        default -> EiDbBundle.message("testdata.col.arg");
      };
    }

    @Override
    public Class<?> getColumnClass(int column) {
      return column == COL_NULLABLE ? Boolean.class : Object.class;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
      return column == COL_METHOD || column == COL_ARG;
    }

    @Override
    public Object getValueAt(int row, int column) {
      Meta.Column col = target.columns().get(row);
      return switch (column) {
        case COL_NAME -> col.comment().isBlank() ? col.name() : col.name() + "  " + col.comment();
        case COL_TYPE -> col.typeLabel() + (col.pk() ? " PK" : "") + (col.fk() ? " FK" : "");
        case COL_NULLABLE -> col.nullable();
        case COL_METHOD -> methods.get(row);
        default -> args.get(row);
      };
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
      if (column == COL_METHOD && value instanceof TestData.Method m) {
        if (m == methods.get(row)) return;
        methods.set(row, m);
        args.set(row, TestData.defaultArg(target.columns().get(row), m, today));
      } else if (column == COL_ARG) {
        args.set(row, value == null ? "" : value.toString());
      } else {
        return;
      }
      fireTableRowsUpdated(row, row);
      refresh();
    }
  }

  /** 만드는 방식 칸: 그 줄의 칼럼에서 고를 수 있는 방식만 든 목록 상자. */
  private final class MethodEditor extends DefaultCellEditor {
    private final ComboBox<TestData.Method> combo;

    @SuppressWarnings("unchecked")
    MethodEditor() {
      super(new ComboBox<TestData.Method>());
      combo = (ComboBox<TestData.Method>) getComponent();
      combo.setRenderer(SimpleListCellRenderer.create("", TestDataDialog::label));
    }

    @Override
    public Component getTableCellEditorComponent(JTable t, Object value, boolean selected, int row, int column) {
      Meta.Column col = target.columns().get(row);
      combo.setModel(new CollectionComboBoxModel<>(TestData.methods(col, hasDefault(col)), (TestData.Method) value));
      return combo;
    }
  }
}
