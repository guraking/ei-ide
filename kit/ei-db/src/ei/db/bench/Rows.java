package ei.db.bench;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.table.JBTable;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 작업대 표의 줄 모델: 칸마다 이름 · 값 읽기 · (고칠 수 있으면) 값 쓰기. 값을 고치면 onEdit 를 부른다.
 * 칸 너비는 weights 비율로 시작해 창 폭에 따라 늘고, 사용자가 끌어 바꾼 너비는 key 로 기억한다.
 */
final class Rows<R> extends AbstractTableModel {

  record Column<R>(String name, Class<?> type, Function<R, Object> get, BiConsumer<R, Object> set) {}

  /** 칸 너비 비율 한 단위(배율 전 px). 실제 너비는 창 폭에 맞춰 늘고 준다. */
  private static final int WEIGHT_UNIT = 40;

  final List<R> rows = new ArrayList<>();
  private final List<Column<R>> columns;
  private final Runnable onEdit;

  Rows(List<Column<R>> columns, Runnable onEdit) {
    this.columns = columns;
    this.onEdit = onEdit;
  }

  @Override public int getRowCount() { return rows.size(); }

  @Override public int getColumnCount() { return columns.size(); }

  @Override public String getColumnName(int c) { return columns.get(c).name(); }

  @Override public Class<?> getColumnClass(int c) { return columns.get(c).type(); }

  @Override public boolean isCellEditable(int r, int c) { return columns.get(c).set() != null; }

  @Override public Object getValueAt(int r, int c) { return columns.get(c).get().apply(rows.get(r)); }

  @Override
  public void setValueAt(Object value, int r, int c) {
    if (r >= rows.size() || columns.get(c).set() == null) return;
    columns.get(c).set().accept(rows.get(r), value);
    fireTableRowsUpdated(r, r);
    onEdit.run();
  }

  void replace(List<R> list) {
    rows.clear();
    rows.addAll(list);
    fireTableDataChanged();
  }

  void add(R row) {
    rows.add(row);
    fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
  }

  /** 고른 줄들을 지운다. 지운 게 있으면 true. */
  boolean removeSelected(JBTable table) {
    if (table.isEditing()) table.getCellEditor().stopCellEditing();
    int[] picked = table.getSelectedRows();
    for (int i = picked.length - 1; i >= 0; i--) rows.remove(table.convertRowIndexToModel(picked[i]));
    fireTableDataChanged();
    return picked.length > 0;
  }

  /** 칸 너비 비율을 놓고, 사용자가 바꾼 너비를 key 로 기억한다. 기억한 너비가 있으면 그것을 비율로 쓴다. */
  static void widths(JBTable table, String key, int... weights) {
    PropertiesComponent props = PropertiesComponent.getInstance();
    String saved = props.getValue(key);
    int[] use = weights.clone();
    if (saved != null) {
      String[] parts = saved.split(",");
      if (parts.length == weights.length) {
        try {
          for (int i = 0; i < parts.length; i++) use[i] = Math.max(1, Integer.parseInt(parts[i]));
        } catch (NumberFormatException e) {
          use = weights.clone(); // 깨진 값이면 처음 비율로
        }
      }
    }
    for (int i = 0; i < use.length && i < table.getColumnCount(); i++) {
      TableColumn col = table.getColumnModel().getColumn(i);
      col.setPreferredWidth(saved != null ? use[i] : use[i] * com.intellij.util.ui.JBUI.scale(WEIGHT_UNIT));
    }
    table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
      @Override
      public void columnMarginChanged(ChangeEvent e) {
        if (table.getTableHeader() == null || table.getTableHeader().getResizingColumn() == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < table.getColumnCount(); i++) {
          if (i > 0) sb.append(',');
          sb.append(table.getColumnModel().getColumn(i).getWidth());
        }
        props.setValue(key, sb.toString());
      }

      @Override public void columnAdded(TableColumnModelEvent e) { }

      @Override public void columnRemoved(TableColumnModelEvent e) { }

      @Override public void columnMoved(TableColumnModelEvent e) { }

      @Override public void columnSelectionChanged(ListSelectionEvent e) { }
    });
  }
}
