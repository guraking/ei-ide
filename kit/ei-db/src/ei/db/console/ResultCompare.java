package ei.db.console;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.QueryResult;
import org.jetbrains.annotations.NotNull;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/** 「결과」 창의 두 조회 탭을 비교하는 창. 탭 우클릭 「다른 결과와 비교…」로 연다. 불러온 행만 비교한다. */
final class ResultCompare extends DialogWrapper {

  /** 창 크기 · 기준 칼럼 목록 너비(px, 배율 전). */
  private static final int WIDTH = 960;
  private static final int HEIGHT = 520;
  private static final int KEY_LIST_WIDTH = 180;
  /** 칸 너비를 잴 때 보는 앞쪽 행 수 · 칸 여백 · 칸 최대 너비(px, 배율 전). */
  private static final int FIT_ROWS = 50;
  private static final int CELL_PADDING = 16;
  private static final int MAX_COLUMN_WIDTH = 400;
  private static final String ARROW = " → ";
  private static final String NULL_TEXT = "null";

  private static final Color CHANGED_BACKGROUND =
      JBColor.namedColor("EiDb.ResultCompare.changedBackground", new JBColor(new Color(0xFFE7A8), new Color(0x5C4B1F)));
  private static final Color LEFT_ONLY_BACKGROUND =
      JBColor.namedColor("EiDb.ResultCompare.leftOnlyBackground", new JBColor(new Color(0xFDECEA), new Color(0x4A2C2E)));
  private static final Color RIGHT_ONLY_BACKGROUND =
      JBColor.namedColor("EiDb.ResultCompare.rightOnlyBackground", new JBColor(new Color(0xE3F4E1), new Color(0x2C4430)));

  /** 탭 우클릭 메뉴 항목. 「결과」 창의 조회 탭에서만 보이고, 비교할 다른 조회 탭이 있어야 누를 수 있다. */
  public static final class OpenAction extends ToolWindowContextMenuActionBase {
    @Override
    public void update(@NotNull AnActionEvent e, @NotNull ToolWindow toolWindow, Content content) {
      boolean ours = ResultView.TOOL_WINDOW_ID.equals(toolWindow.getId()) && queryPanel(content) != null;
      e.getPresentation().setVisible(ours);
      e.getPresentation().setEnabled(ours && !others(toolWindow, content).isEmpty());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e, @NotNull ToolWindow toolWindow, Content content) {
      ResultPanel left = queryPanel(content);
      List<Content> others = others(toolWindow, content);
      if (left == null || others.isEmpty() || e.getProject() == null) return;
      Project project = e.getProject();
      List<String> names = new ArrayList<>();
      for (Content c : others) names.add(label(c));
      JBPopupFactory.getInstance().createPopupChooserBuilder(names)
          .setTitle("비교할 결과")
          .setItemChosenCallback(name -> {
            Content right = others.get(names.indexOf(name));
            new ResultCompare(project, left, label(content), queryPanel(right), label(right)).show();
          })
          .createPopup()
          .showInBestPositionFor(e.getDataContext());
    }
  }

  private static ResultPanel queryPanel(Content content) {
    return content != null && content.getComponent() instanceof ResultPanel p && p.result().isQuery() ? p : null;
  }

  private static List<Content> others(ToolWindow toolWindow, Content self) {
    List<Content> out = new ArrayList<>();
    for (Content c : toolWindow.getContentManager().getContents()) {
      if (c != self && queryPanel(c) != null) out.add(c);
    }
    return out;
  }

  private static String label(Content c) {
    ResultPanel p = queryPanel(c);
    return c.getDisplayName() + (p == null ? "" : " · " + p.config().name);
  }

  private final QueryResult left;
  private final QueryResult right;
  private final String leftLabel;
  private final String rightLabel;
  private final List<String> common;
  private final CheckBoxList<String> keyList = new CheckBoxList<>();
  private final JBCheckBox showSame = new JBCheckBox("같은 행도 보기");
  private final JBLabel counts = new JBLabel();
  private final JBLabel keyNote = new JBLabel();
  private final DiffModel model = new DiffModel();
  private final JBTable table = new JBTable(model);

  private ResultCompare(Project project, ResultPanel left, String leftLabel, ResultPanel right, String rightLabel) {
    super(project, false);
    this.left = left.result();
    this.right = right.result();
    this.leftLabel = leftLabel;
    this.rightLabel = rightLabel;
    this.common = ResultDiff.commonColumns(this.left.columns(), this.right.columns());
    List<String> keys = defaultKeys(left, right);
    keyList.setItems(common, name -> name);
    for (String k : keys) keyList.setItemSelected(k, true);
    keyList.setCheckBoxListListener((index, value) -> refresh());
    showSame.addActionListener(e -> refresh());
    setTitle("결과 비교");
    init();
    refresh();
  }

  /** 기준 칼럼 기본값: 한쪽 결과가 PK 를 알고 그 칼럼이 모두 공통이면 PK, 아니면 첫 공통 칼럼. 공통 칼럼이 없으면 없음(행 순서). */
  private List<String> defaultKeys(ResultPanel a, ResultPanel b) {
    for (ResultPanel p : List.of(a, b)) {
      boolean[] pk = p.knownPkColumns();
      if (pk == null) continue;
      List<String> keys = new ArrayList<>();
      boolean allCommon = true;
      for (int i = 0; i < pk.length; i++) {
        if (!pk[i]) continue;
        String name = matchCommon(p.result().columns().get(i));
        if (name == null) allCommon = false;
        else keys.add(name);
      }
      if (allCommon && !keys.isEmpty()) return keys;
    }
    return common.isEmpty() ? List.of() : List.of(common.get(0));
  }

  private String matchCommon(String name) {
    for (String c : common) {
      if (c.equalsIgnoreCase(name)) return c;
    }
    return null;
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.add(new JBLabel("왼쪽: " + leftLabel + "   ·   오른쪽: " + rightLabel));
    if (left.truncated() || right.truncated()) {
      JBLabel cut = new JBLabel("불러온 행만 비교합니다(왼쪽 " + left.rows().size() + "건 · 오른쪽 " + right.rows().size()
                                + "건). 잘린 결과는 결과 탭에서 더 읽은 뒤 비교하세요.");
      cut.setForeground(UiColors.WARNING);
      top.add(cut);
    }
    List<String> leftOnly = new ArrayList<>(left.columns());
    List<String> rightOnly = new ArrayList<>(right.columns());
    leftOnly.removeIf(c -> matchCommon(c) != null);
    rightOnly.removeIf(c -> matchCommon(c) != null);
    if (!leftOnly.isEmpty() || !rightOnly.isEmpty()) {
      JBLabel cols = new JBLabel("비교하지 않는 칼럼 · 왼쪽에만: " + (leftOnly.isEmpty() ? "없음" : String.join(", ", leftOnly))
                                 + " · 오른쪽에만: " + (rightOnly.isEmpty() ? "없음" : String.join(", ", rightOnly)));
      cols.setForeground(UIUtil.getContextHelpForeground());
      top.add(cols);
    }
    JPanel line = new JPanel(new BorderLayout());
    line.add(counts, BorderLayout.CENTER);
    line.add(showSame, BorderLayout.EAST);
    top.add(line);
    top.setBorder(JBUI.Borders.emptyBottom(6));

    JPanel keys = new JPanel(new BorderLayout());
    keys.add(new JBLabel("기준 칼럼"), BorderLayout.NORTH);
    keys.add(new JBScrollPane(keyList), BorderLayout.CENTER);
    keyNote.setForeground(UIUtil.getContextHelpForeground());
    keys.add(keyNote, BorderLayout.SOUTH);
    keys.setPreferredSize(new Dimension(JBUI.scale(KEY_LIST_WIDTH), 0));
    keys.setBorder(JBUI.Borders.emptyRight(8));

    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.getTableHeader().setReorderingAllowed(false);
    table.setDefaultRenderer(Object.class, new DiffRenderer());

    JPanel panel = new JPanel(new BorderLayout());
    panel.add(top, BorderLayout.NORTH);
    panel.add(keys, BorderLayout.WEST);
    panel.add(new JBScrollPane(table), BorderLayout.CENTER);
    panel.setPreferredSize(new Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT)));
    return panel;
  }

  @Override
  protected Action @NotNull [] createActions() {
    getOKAction().putValue(Action.NAME, "닫기");
    return new Action[]{getOKAction()};
  }

  private void refresh() {
    List<String> keys = keyList.getCheckedItems();
    ResultDiff.Outcome outcome = ResultDiff.compare(left.columns(), left.rows(), right.columns(), right.rows(), keys);
    keyNote.setText(keys.isEmpty() ? "<html>고른 칼럼이 없어 행 순서로 비교합니다.</html>" : "");
    counts.setText("같음 " + outcome.count(ResultDiff.Kind.SAME) + " · 다름 " + outcome.count(ResultDiff.Kind.CHANGED)
                   + " · 왼쪽에만 " + outcome.count(ResultDiff.Kind.LEFT_ONLY) + " · 오른쪽에만 " + outcome.count(ResultDiff.Kind.RIGHT_ONLY));
    List<ResultDiff.Row> shown = new ArrayList<>();
    for (ResultDiff.Row r : outcome.rows()) {
      if (showSame.isSelected() || r.kind() != ResultDiff.Kind.SAME) shown.add(r);
    }
    model.set(outcome.columns(), shown);
    fitColumns();
  }

  /** 머리글과 앞쪽 FIT_ROWS 행이 들어가는 너비(최대 MAX_COLUMN_WIDTH). 넘는 글은 칸 풍선 도움말로 본다. */
  private void fitColumns() {
    for (int col = 0; col < table.getColumnCount(); col++) {
      TableColumn column = table.getColumnModel().getColumn(col);
      int width = table.getTableHeader().getDefaultRenderer()
          .getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, col).getPreferredSize().width;
      int rows = Math.min(table.getRowCount(), FIT_ROWS);
      for (int row = 0; row < rows; row++) {
        width = Math.max(width, table.prepareRenderer(table.getCellRenderer(row, col), row, col).getPreferredSize().width);
      }
      column.setPreferredWidth(Math.min(width + JBUI.scale(CELL_PADDING), JBUI.scale(MAX_COLUMN_WIDTH)));
    }
  }

  private static String kindText(ResultDiff.Kind kind) {
    return switch (kind) {
      case SAME -> "같음";
      case CHANGED -> "다름";
      case LEFT_ONLY -> "왼쪽에만";
      case RIGHT_ONLY -> "오른쪽에만";
    };
  }

  private static String text(Object v) {
    return v == null ? NULL_TEXT : SqlText.text(v);
  }

  /** 0번 칸은 구분, 나머지는 공통 칼럼. 다른 칸은 「왼쪽 → 오른쪽」. */
  private static final class DiffModel extends AbstractTableModel {
    private List<String> columns = List.of();
    private List<ResultDiff.Row> rows = List.of();

    void set(List<String> columns, List<ResultDiff.Row> rows) {
      boolean sameColumns = this.columns.equals(columns);
      this.columns = columns;
      this.rows = rows;
      if (sameColumns) fireTableDataChanged();
      else fireTableStructureChanged();
    }

    ResultDiff.Row row(int index) { return rows.get(index); }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return columns.size() + 1; }
    @Override public String getColumnName(int column) { return column == 0 ? "구분" : columns.get(column - 1); }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      ResultDiff.Row r = rows.get(rowIndex);
      if (columnIndex == 0) return kindText(r.kind());
      int c = columnIndex - 1;
      if (r.left() == null) return text(r.right().get(c));
      if (r.right() == null || !r.changed()[c]) return text(r.left().get(c));
      return text(r.left().get(c)) + ARROW + text(r.right().get(c));
    }
  }

  /** 다른 칸 · 한쪽에만 있는 행에 바탕색. 바탕색은 다음 칸에도 남으므로 매번 정한다. */
  private static final class DiffRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
      super.getTableCellRendererComponent(table, value, selected, focused, row, column);
      setToolTipText(value == null ? null : value.toString());
      if (selected) return this;
      ResultDiff.Row r = ((DiffModel) table.getModel()).row(table.convertRowIndexToModel(row));
      Color background = table.getBackground();
      if (r.kind() == ResultDiff.Kind.LEFT_ONLY) background = LEFT_ONLY_BACKGROUND;
      else if (r.kind() == ResultDiff.Kind.RIGHT_ONLY) background = RIGHT_ONLY_BACKGROUND;
      else if (column > 0 && r.changed()[table.convertColumnIndexToModel(column) - 1]) background = CHANGED_BACKGROUND;
      setBackground(background);
      setForeground(table.getForeground());
      return this;
    }
  }
}
