package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.gen.DtoDialog;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import java.util.ArrayList;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import javax.swing.SwingUtilities;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Dimension;
import javax.swing.table.JTableHeader;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 결과 탭 하나의 내용: 조회면 도구줄 + 표, 변경이면 한 줄 요약, 오류면 붉은 글. */
final class ResultPanel extends JPanel {

  /** 칸 너비를 잴 때 보는 앞쪽 행 수와 칸 최대 너비. */
  private static final int FIT_ROWS = 50;
  private static final int MAX_COLUMN_WIDTH = 400;
  /** 엑셀이 UTF-8 로 읽게 하는 표식. */
  private static final String BOM = "﻿";

  private final Project project;
  private final QueryResult result;
  private final String table;
  private JBTable grid;

  ResultPanel(Project project, DbConfig c, QueryResult result, String table) {
    super(new BorderLayout());
    this.project = project;
    this.result = result;
    this.table = table;
    if (result.isQuery()) buildQuery(c);
    else buildUpdate(c);
  }

  static JComponent error(String sql, String message) {
    JBTextArea text = new JBTextArea(message + "\n\n" + sql);
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setForeground(UiColors.DANGER);
    text.setFont(monoFont());
    text.setBorder(JBUI.Borders.empty(10, 12));
    return new JBScrollPane(text);
  }

  private static Font monoFont() {
    return EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
  }

  private void buildUpdate(DbConfig c) {
    int count = Math.max(result.updateCount(), 0);
    JBLabel label = new JBLabel(count + "행 변경됨 · " + (c.autoCommit ? "자동 커밋됨" : "커밋 전") + " · " + result.millis() + " ms");
    label.setForeground(c.autoCommit ? UiColors.SUCCESS : UiColors.WARNING);
    label.setToolTipText(result.sql());
    label.setBorder(JBUI.Borders.empty(10, 12));
    add(label, BorderLayout.NORTH);
  }

  /** 두 줄 머리의 위아래 여백(px, 배율 전). */
  private static final int HEADER_PADDING = 10;

  private void buildQuery(DbConfig c) {
    boolean twoLineHeader = !result.comments().isEmpty();
    grid = new JBTable(new Model(result)) {
      @Override
      protected JTableHeader createDefaultTableHeader() {
        return new JBTableHeader() {
          // 주석 줄이 있으면 머리를 두 줄 높이로 둔다. 기본 머리는 한 줄 높이로 고정돼 아래 줄이 잘린다.
          @Override
          public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            if (twoLineHeader) d.height = Math.max(d.height, getFontMetrics(getFont()).getHeight() * 2 + JBUI.scale(HEADER_PADDING));
            return d;
          }
        };
      }
    };
    grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    grid.setDefaultRenderer(Object.class, new CellRenderer());
    grid.getTableHeader().setReorderingAllowed(false);
    // 칸 단위로 고를 수 있게 한다(Ctrl+C 는 고른 칸만 복사). 머리를 누르면 그 칼럼 전체를 고른다.
    grid.setCellSelectionEnabled(true);
    grid.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) { onHeader(e); }
      @Override public void mouseReleased(MouseEvent e) { onHeader(e); }
    });
    grid.addMouseListener(new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) { onCell(e); }
      @Override public void mouseReleased(MouseEvent e) { onCell(e); }
    });
    fitColumns(grid);

    JPanel bar = new JPanel();
    bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
    bar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(3, 8)));
    JBLabel count = new JBLabel(result.rows().size() + "건");
    count.setForeground(UiColors.SUCCESS);
    JBLabel millis = new JBLabel(" · " + result.millis() + " ms");
    millis.setForeground(UIUtil.getContextHelpForeground());
    bar.add(count);
    bar.add(millis);
    if (result.truncated()) {
      JBLabel cut = new JBLabel(" · " + DbSessions.MAX_ROWS + "건에서 자름");
      cut.setForeground(UiColors.WARNING);
      bar.add(cut);
    }
    bar.add(Box.createHorizontalGlue());
    bar.add(button("복사", AllIcons.Actions.Copy, this::copyTsv));
    bar.add(button("CSV 내보내기", AllIcons.Actions.Download, this::exportCsv));
    bar.add(button("INSERT 문으로", null, this::copyInserts));
    bar.add(button("DTO 만들기", null, () -> DtoDialog.show(project, c, result)));

    add(bar, BorderLayout.NORTH);
    add(new JBScrollPane(grid), BorderLayout.CENTER);
  }

  private static JButton button(String text, javax.swing.Icon icon, Runnable action) {
    JButton b = new JButton(text, icon);
    b.addActionListener(e -> action.run());
    return b;
  }

  private static void fitColumns(JBTable table) {
    for (int col = 0; col < table.getColumnCount(); col++) {
      TableColumn column = table.getColumnModel().getColumn(col);
      Component header = table.getTableHeader().getDefaultRenderer()
          .getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, col);
      int width = header.getPreferredSize().width;
      int rows = Math.min(table.getRowCount(), FIT_ROWS);
      for (int row = 0; row < rows; row++) {
        width = Math.max(width, table.prepareRenderer(table.getCellRenderer(row, col), row, col).getPreferredSize().width);
      }
      column.setPreferredWidth(Math.min(width + JBUI.scale(16), JBUI.scale(MAX_COLUMN_WIDTH)));
    }
  }

  /** 고른 행(없으면 전체)의 모델 행 번호. */
  private int[] chosenRows() {
    int[] selected = grid.getSelectedRows();
    if (selected.length > 0) return selected;
    int[] all = new int[result.rows().size()];
    for (int i = 0; i < all.length; i++) all[i] = i;
    return all;
  }

  /** 머리 왼쪽 누름: 칼럼 고르기(Ctrl 은 더하기). 오른쪽 누름: 칼럼 복사 메뉴. 0번(행 번호) 칼럼은 건너뛴다. */
  private void onHeader(MouseEvent e) {
    int view = grid.getTableHeader().columnAtPoint(e.getPoint());
    if (view < 0) return;
    int col = grid.convertColumnIndexToModel(view);
    if (e.isPopupTrigger()) {
      if (col > 0) columnMenu(col - 1).show(e.getComponent(), e.getX(), e.getY());
      return;
    }
    if (e.getID() != MouseEvent.MOUSE_PRESSED || !SwingUtilities.isLeftMouseButton(e) || grid.getRowCount() == 0) return;
    if (e.isControlDown()) grid.addColumnSelectionInterval(view, view);
    else grid.setColumnSelectionInterval(view, view);
    grid.setRowSelectionInterval(0, grid.getRowCount() - 1);
  }

  /** 칸 오른쪽 누름: 값 · 칼럼 · 행 복사 메뉴. 누른 칸이 골라져 있지 않으면 그 칸만 고른다. */
  private void onCell(MouseEvent e) {
    if (!e.isPopupTrigger()) return;
    int row = grid.rowAtPoint(e.getPoint());
    int view = grid.columnAtPoint(e.getPoint());
    if (row < 0 || view < 0) return;
    if (!grid.isCellSelected(row, view)) grid.changeSelection(row, view, false, false);
    int col = grid.convertColumnIndexToModel(view);
    JPopupMenu menu = col > 0 ? columnMenu(col - 1) : new JPopupMenu();
    if (col > 0) {
      Object v = result.rows().get(grid.convertRowIndexToModel(row)).get(col - 1);
      menu.insert(item("값 복사", () -> copy(v == null ? "" : text(v), "값을 복사했습니다.")), 0);
    }
    menu.addSeparator();
    menu.add(item("고른 행 복사 (머리 포함)", this::copyTsv));
    menu.show(e.getComponent(), e.getX(), e.getY());
  }

  /** index 는 결과 칼럼 번호(행 번호 칼럼 제외). */
  private JPopupMenu columnMenu(int index) {
    String name = result.columns().get(index);
    JPopupMenu menu = new JPopupMenu();
    menu.add(item("칼럼 이름 복사 (" + name + ")", () -> copy(name, "칼럼 이름을 복사했습니다.")));
    menu.add(item("칼럼 이름 모두 복사", () -> copy(String.join(", ", result.columns()), "칼럼 이름 " + result.columns().size() + "개를 복사했습니다.")));
    menu.add(item("이 칼럼 값 복사 (줄마다 하나)", () -> {
      List<String> values = columnValues(index);
      copy(String.join("\n", values), values.size() + "개 값을 복사했습니다.");
    }));
    menu.add(item("이 칼럼 값을 IN 목록으로 복사", () -> {
      List<String> quoted = new ArrayList<>();
      for (String v : columnValues(index)) quoted.add("'" + v.replace("'", "''") + "'");
      copy("(" + String.join(", ", quoted) + ")", quoted.size() + "개 값을 IN 목록으로 복사했습니다.");
    }));
    return menu;
  }

  /** 고른 행(없으면 전체)의 한 칼럼 값. null 은 빈 글자. */
  private List<String> columnValues(int index) {
    List<String> out = new ArrayList<>();
    for (int row : chosenRows()) {
      Object v = result.rows().get(row).get(index);
      out.add(v == null ? "" : text(v));
    }
    return out;
  }

  private static JMenuItem item(String text, Runnable action) {
    JMenuItem item = new JMenuItem(text);
    item.addActionListener(e -> action.run());
    return item;
  }

  private void copy(String text, String message) {
    CopyPasteManager.getInstance().setContents(new StringSelection(text));
    Notify.info(project, message);
  }

  private void copyTsv() {
    StringBuilder sb = new StringBuilder(String.join("\t", result.columns())).append('\n');
    for (int row : chosenRows()) {
      List<Object> values = result.rows().get(row);
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) sb.append('\t');
        Object v = values.get(i);
        if (v != null) sb.append(text(v).replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
      }
      sb.append('\n');
    }
    CopyPasteManager.getInstance().setContents(new StringSelection(sb.toString()));
    Notify.info(project, chosenRows().length + "행을 복사했습니다.");
  }

  private void exportCsv() {
    FileSaverDescriptor descriptor = new FileSaverDescriptor("CSV 내보내기", "결과를 CSV 파일로 저장합니다", "csv");
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        .save((table == null ? "result" : table) + ".csv");
    if (target == null) return;
    Path path = target.getFile().toPath();
    StringBuilder sb = new StringBuilder(BOM);
    appendCsvLine(sb, List.copyOf(result.columns()));
    for (List<Object> row : result.rows()) appendCsvLine(sb, row);
    String csv = sb.toString();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Files.writeString(path, csv, StandardCharsets.UTF_8);
        Notify.info(project, "CSV 로 저장했습니다: " + path);
      } catch (IOException e) {
        Notify.error(project, "CSV 저장 실패: " + e.getMessage());
      }
    });
  }

  private static void appendCsvLine(StringBuilder sb, List<?> values) {
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(',');
      Object v = values.get(i);
      if (v == null) continue;
      String s = text(v);
      if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
      sb.append(s);
    }
    sb.append("\r\n");
  }

  private void copyInserts() {
    String target = table;
    if (target == null) {
      target = Messages.showInputDialog(project, "INSERT 할 표 이름", "INSERT 문으로", null);
      if (target == null || target.isBlank()) return;
      target = target.strip();
    }
    String head = "INSERT INTO " + target + " (" + String.join(", ", result.columns()) + ") VALUES (";
    StringBuilder sb = new StringBuilder();
    int[] rows = chosenRows();
    for (int row : rows) {
      sb.append(head);
      List<Object> values = result.rows().get(row);
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(literal(values.get(i)));
      }
      sb.append(");\n");
    }
    CopyPasteManager.getInstance().setContents(new StringSelection(sb.toString()));
    Notify.info(project, "INSERT 문 " + rows.length + "개를 복사했습니다.");
  }

  /** ANSI 표기(TIMESTAMP · DATE 글자)라 Oracle · MySQL · PostgreSQL 에서 같이 쓴다. */
  static String literal(Object v) {
    if (v == null) return "NULL";
    if (v instanceof Number) return text(v);
    if (v instanceof Timestamp t) return "TIMESTAMP '" + t + "'";
    if (v instanceof java.sql.Date d) return "DATE '" + d + "'";
    return "'" + text(v).replace("'", "''") + "'";
  }

  static String text(Object v) {
    return v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v);
  }

  /** 0번 칸은 행 번호. 머리글은 칼럼 이름 + 한글 주석(작고 흐리게) 두 줄. 읽기 전용. */
  private static final class Model extends AbstractTableModel {
    private final QueryResult result;
    private final String[] headers;

    Model(QueryResult result) {
      this.result = result;
      Map<String, String> comments = result.comments();
      boolean anyComment = comments != null && !comments.isEmpty();
      String muted = ColorUtil.toHex(UIUtil.getContextHelpForeground());
      headers = new String[result.columns().size() + 1];
      headers[0] = "#";
      for (int i = 0; i < result.columns().size(); i++) {
        String name = result.columns().get(i);
        if (!anyComment) {
          headers[i + 1] = name;
          continue;
        }
        String comment = comments.get(name);
        String second = comment == null || comment.isBlank() ? "&nbsp;" : StringUtil.escapeXmlEntities(comment);
        headers[i + 1] = "<html>" + StringUtil.escapeXmlEntities(name) + "<br><font size='-1' color='#" + muted + "'>" + second + "</font></html>";
      }
    }

    @Override public int getRowCount() { return result.rows().size(); }
    @Override public int getColumnCount() { return headers.length; }
    @Override public String getColumnName(int column) { return headers[column]; }
    @Override public boolean isCellEditable(int row, int column) { return false; }

    @Override
    public Object getValueAt(int row, int column) {
      return column == 0 ? new RowNumber(row + 1) : result.rows().get(row).get(column - 1);
    }
  }

  private record RowNumber(int value) {
    @Override public String toString() { return String.valueOf(value); }
  }

  /** NULL 은 흐린 기울임 「null」, 숫자 · 행 번호는 오른쪽 정렬. */
  private static final class CellRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
      super.getTableCellRendererComponent(table, value == null ? "null" : text(value), selected, focused, row, column);
      setFont(table.getFont().deriveFont(value == null ? Font.ITALIC : Font.PLAIN));
      boolean muted = value == null || value instanceof RowNumber;
      // DefaultTableCellRenderer 는 지정한 글자색을 다음 칸에도 쓰므로 매번 정한다.
      if (!selected) setForeground(muted ? UIUtil.getContextHelpForeground() : table.getForeground());
      setHorizontalAlignment(value instanceof Number || value instanceof RowNumber ? SwingConstants.RIGHT : SwingConstants.LEFT);
      return this;
    }
  }
}
