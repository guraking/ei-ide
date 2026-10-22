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
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.gen.DtoDialog;
import ei.db.gen.EnumDialog;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.sql.Connection;
import ei.db.Meta;
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
import javax.swing.table.TableRowSorter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import ei.db.SqlRunner;
import org.jetbrains.annotations.NotNull;
import javax.swing.DefaultCellEditor;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import java.awt.Color;

/** 결과 탭 하나의 내용: 조회면 도구줄 + 표, 변경이면 한 줄 요약, 오류면 붉은 글. */
final class ResultPanel extends JPanel {

  /** 칸 너비를 잴 때 보는 앞쪽 행 수와 칸 최대 너비. */
  private static final int FIT_ROWS = 50;
  private static final int MAX_COLUMN_WIDTH = 400;
  /** 엑셀이 UTF-8 로 읽게 하는 표식. */
  private static final String BOM = "﻿";

  /** 검색칸 너비 · 반영 미리보기 크기(px, 배율 전). */
  private static final int SEARCH_WIDTH = 180;
  private static final int PREVIEW_WIDTH = 640;
  private static final int PREVIEW_HEIGHT = 360;
  /** 고쳤지만 아직 반영하지 않은 칸의 바탕. */
  private static final Color EDITED_BACKGROUND = new JBColor(new Color(0xFFF1C2), new Color(0x4D4421));

  private final Project project;
  private final DbConfig config;
  private final QueryResult result;
  private final String table;
  private JBTable grid;
  private TableRowSorter<Model> sorter;
  private JBLabel count;
  private SearchTextField searchBox;
  private JButton applyButton;
  private JButton revertButton;
  /** 고친 값: 모델 행 → (결과 칼럼 번호 → 새 값). 반영 전까지 화면에만 있다. */
  private final Map<Integer, Map<Integer, Object>> edits = new TreeMap<>();
  /** 결과 칼럼마다 PK 여부. null 이면 아직 표 정의를 읽지 않았다. */
  private boolean[] pkColumns;
  /** 고치기를 막는 이유. null 이면 막지 않거나 아직 모른다. */
  private String editBlocked;
  private boolean loadingEdit;

  ResultPanel(Project project, DbConfig c, QueryResult result, String table) {
    super(new BorderLayout());
    this.project = project;
    this.config = c;
    this.result = result;
    this.table = table;
    if (result.isQuery()) buildQuery(c);
    else buildUpdate(c);
  }

  /** 결과 비교(ResultCompare)가 읽는 값. */
  QueryResult result() { return result; }

  DbConfig config() { return config; }

  /** 결과 칼럼마다 PK 여부. 고치기를 시작해 표 정의를 읽었고 고칠 수 있는 결과일 때만 있고, 아니면 null. */
  boolean[] knownPkColumns() { return editBlocked == null ? pkColumns : null; }

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
    boolean auto = DbSessions.get(project).autoCommit(c);
    JBLabel label = new JBLabel(EiDbBundle.message(auto ? "result.updated.auto" : "result.updated.pending", count) + " · " + result.millis() + " ms");
    label.setForeground(auto ? UiColors.SUCCESS : UiColors.WARNING);
    label.setToolTipText(result.sql());
    label.setBorder(JBUI.Borders.empty(10, 12));
    add(label, BorderLayout.NORTH);
  }

  /** 두 줄 머리의 위아래 여백(px, 배율 전). */
  private static final int HEADER_PADDING = 10;

  private void buildQuery(DbConfig c) {
    boolean twoLineHeader = !result.comments().isEmpty();
    Model model = new Model(result);
    grid = new JBTable(model) {
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
    sorter = new TableRowSorter<>(model) {
      // 머리 한 번 누름은 칼럼 고르기라 정렬하지 않는다. 정렬은 머리 두 번 누름 · 우클릭 메뉴로 setSortKeys 를 부른다.
      @Override public void toggleSortOrder(int column) { }
    };
    for (int i = 0; i < model.getColumnCount(); i++) sorter.setComparator(i, ResultPanel::compareValues);
    grid.setRowSorter(sorter);
    grid.setDefaultRenderer(Object.class, new CellRenderer());
    grid.setDefaultEditor(Object.class, new ValueEditor());
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
    count = new JBLabel(EiDbBundle.message("common.rows", result.rows().size()));
    count.setForeground(UiColors.SUCCESS);
    JBLabel millis = new JBLabel(" · " + result.millis() + " ms");
    millis.setForeground(UIUtil.getContextHelpForeground());
    bar.add(count);
    bar.add(millis);
    if (result.truncated()) {
      JBLabel cut = new JBLabel(EiDbBundle.message("result.truncated", result.rows().size()));
      cut.setForeground(UiColors.WARNING);
      bar.add(cut);
    }
    // 다시 읽기는 문장을 처음부터 다시 실행하므로 변경이 섞인 문장(INSERT … RETURNING 등)에는 두지 않는다.
    if (result.truncated() && SqlText.isRereadable(result.sql())) {
      bar.add(button(EiDbBundle.message("result.more", DbSessions.MAX_ROWS), null, () -> readMore(c, result.rows().size() + DbSessions.MAX_ROWS)));
      JBTextField wanted = new JBTextField(String.valueOf(result.rows().size() + DbSessions.MAX_ROWS), 6);
      wanted.setToolTipText(EiDbBundle.message("result.wanted.tooltip"));
      wanted.setMaximumSize(wanted.getPreferredSize());
      Runnable byInput = () -> readWanted(c, wanted.getText());
      wanted.addActionListener(e -> byInput.run());
      bar.add(Box.createHorizontalStrut(JBUI.scale(8)));
      bar.add(wanted);
      bar.add(new JBLabel(EiDbBundle.message("result.rows.unit")));
      bar.add(button(EiDbBundle.message("result.fetch"), null, byInput));
    }
    bar.add(Box.createHorizontalStrut(JBUI.scale(8)));
    bar.add(searchField());
    bar.add(Box.createHorizontalGlue());
    applyButton = button(EiDbBundle.message("result.apply"), AllIcons.Actions.Commit, EiDbBundle.message("result.apply.tooltip"), this::applyEdits);
    revertButton = button(EiDbBundle.message("result.revert"), AllIcons.Actions.Rollback, EiDbBundle.message("result.revert.tooltip"), this::revertEdits);
    bar.add(applyButton);
    bar.add(revertButton);
    updateEditButtons();
    bar.add(button(EiDbBundle.message("common.copy"), AllIcons.Actions.Copy, this::copyTsv));
    bar.add(button("CSV", AllIcons.Actions.Download, EiDbBundle.message("result.csv.tooltip"), this::exportCsv));
    bar.add(button(EiDbBundle.message("result.excel"), AllIcons.Actions.Download, EiDbBundle.message("result.excel.tooltip"), this::exportXlsx));
    bar.add(button("INSERT", null, EiDbBundle.message("result.insert.tooltip"), this::copyInserts));
    bar.add(button("UPDATE", null, EiDbBundle.message("result.update.tooltip"), () -> copyUpdates(c)));
    bar.add(button("DTO", null, EiDbBundle.message("dto.command"), () -> DtoDialog.show(project, c, result)));
    bar.add(button("enum", null, EiDbBundle.message("result.enum.tooltip"), () -> EnumDialog.show(project, c, result)));

    add(bar, BorderLayout.NORTH);
    add(new JBScrollPane(grid), BorderLayout.CENTER);
    // 결과 창 안에서 Ctrl+F(찾기 단축키)는 IDE 찾기 대신 검색칸으로 간다.
    DumbAwareAction.create(e -> searchBox.getTextEditor().requestFocusInWindow()).registerCustomShortcutSet(CommonShortcuts.getFind(), this);
  }

  /** 결과 안에서 찾기: 글자가 든 칸이 하나라도 있는 행만 보인다(대소문자 무시, null 칸 · 행 번호 제외). */
  private JComponent searchField() {
    searchBox = new SearchTextField(false);
    searchBox.getTextEditor().getEmptyText().setText(EiDbBundle.message("result.search.hint"));
    searchBox.setToolTipText(EiDbBundle.message("result.search.tooltip"));
    Dimension size = new Dimension(JBUI.scale(SEARCH_WIDTH), searchBox.getPreferredSize().height);
    searchBox.setPreferredSize(size);
    searchBox.setMaximumSize(size);
    searchBox.addDocumentListener(new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) { filter(searchBox.getText()); }
    });
    return searchBox;
  }

  private void filter(String text) {
    String needle = text.toLowerCase(Locale.ROOT);
    sorter.setRowFilter(needle.isEmpty() ? null : new Contains(needle));
    int total = result.rows().size();
    count.setText(needle.isEmpty() ? EiDbBundle.message("common.rows", total) : EiDbBundle.message("result.filtered", grid.getRowCount(), total));
  }

  private static final class Contains extends RowFilter<Model, Integer> {
    private final String needle;

    Contains(String needle) { this.needle = needle; }

    @Override
    public boolean include(Entry<? extends Model, ? extends Integer> entry) {
      for (int i = 1; i < entry.getValueCount(); i++) {
        Object v = entry.getValue(i);
        if (v != null && text(v).toLowerCase(Locale.ROOT).contains(needle)) return true;
      }
      return false;
    }
  }

  /**
   * 같은 문장을 limit 건까지 다시 읽어 이 탭을 바꾼다. 처음부터 다시 실행하므로 그 사이 바뀐 데이터나 ORDER BY 없는 순서는 달라질 수 있다.
   * 이미 실행된 조회 문장이라 위험 문장 확인은 다시 하지 않는다.
   */
  private void readMore(DbConfig c, int limit) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        QueryResult more = DbSessions.get(project).run(c, result.sql(), result.binds(), limit);
        ApplicationManager.getApplication().invokeLater(() -> replaceWith(new ResultPanel(project, c, more, table)));
      } catch (Exception e) {
        Notify.error(project, EiDbBundle.message("result.more.failed", c.name, ResultView.message(e)));
      }
    });
  }

  /** 직접 쓴 건수(쉼표 · 「건」 허용)로 다시 읽는다. 1 ~ MAX_ROW_LIMIT 가 아니면 알리고 읽지 않는다. */
  private void readWanted(DbConfig c, String text) {
    int n = DbSessions.parseRowCount(text);
    if (n == 0) {
      Notify.error(project, EiDbBundle.message("result.wanted.invalid", DbSessions.MAX_ROW_LIMIT, text));
      return;
    }
    readMore(c, n);
  }

  /** 이 패널이 든 결과 탭의 내용을 바꾼다. 그 사이 탭을 닫았으면 아무것도 하지 않는다. */
  private void replaceWith(ResultPanel next) {
    if (project.isDisposed()) return;
    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ResultView.TOOL_WINDOW_ID);
    if (window == null) return;
    Content content = window.getContentManager().getContent(this);
    if (content != null) content.setComponent(next);
  }

  private static JButton button(String text, javax.swing.Icon icon, Runnable action) {
    JButton b = new JButton(text, icon);
    b.addActionListener(e -> action.run());
    return b;
  }

  private static JButton button(String text, javax.swing.Icon icon, String tooltip, Runnable action) {
    JButton b = button(text, icon, action);
    b.setToolTipText(tooltip);
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

  /** 고른 행(없으면 전체)의 모델 행 번호. 정렬돼 있으면 화면에 보이는 순서대로. */
  private int[] chosenRows() {
    int[] selected = grid.getSelectedRows();
    int n = selected.length > 0 ? selected.length : grid.getRowCount();
    int[] out = new int[n];
    for (int i = 0; i < n; i++) out[i] = grid.convertRowIndexToModel(selected.length > 0 ? selected[i] : i);
    return out;
  }

  /**
   * 정렬 비교. 같은 형식이면 그 형식의 순서(숫자 · 날짜 · 글자), 형식이 다른 숫자끼리는 값 크기, 나머지는 보이는 글자 순서.
   * null 은 RowSorter 가 따로 맨 앞(오름차순)으로 보낸다.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compareValues(Object a, Object b) {
    if (a instanceof RowNumber x && b instanceof RowNumber y) return Integer.compare(x.value(), y.value());
    if (a instanceof Comparable ca && a.getClass() == b.getClass()) return ca.compareTo(b);
    if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
    return text(a).compareTo(text(b));
  }

  /** order 가 null 이면 정렬을 풀어 처음 읽은 순서로 돌린다. modelColumn 은 행 번호 칸을 포함한 번호. */
  private void sortBy(int modelColumn, SortOrder order) {
    grid.getRowSorter().setSortKeys(order == null ? null : List.of(new RowSorter.SortKey(modelColumn, order)));
  }

  /** 머리 두 번 누름: 그 칼럼이 오름차순이면 내림차순, 아니면 오름차순. */
  private void toggleSort(int modelColumn) {
    List<? extends RowSorter.SortKey> keys = grid.getRowSorter().getSortKeys();
    boolean ascending = !keys.isEmpty() && keys.get(0).getColumn() == modelColumn && keys.get(0).getSortOrder() == SortOrder.ASCENDING;
    sortBy(modelColumn, ascending ? SortOrder.DESCENDING : SortOrder.ASCENDING);
  }

  /** 머리 왼쪽 누름: 칼럼 고르기(Ctrl 은 더하기), 두 번 누르면 정렬. 오른쪽 누름: 칼럼 복사 · 정렬 메뉴. 0번(행 번호) 칼럼은 건너뛴다. */
  private void onHeader(MouseEvent e) {
    int view = grid.getTableHeader().columnAtPoint(e.getPoint());
    if (view < 0) return;
    int col = grid.convertColumnIndexToModel(view);
    if (e.isPopupTrigger()) {
      if (col > 0) columnMenu(col - 1).show(e.getComponent(), e.getX(), e.getY());
      return;
    }
    if (e.getID() != MouseEvent.MOUSE_PRESSED || !SwingUtilities.isLeftMouseButton(e) || grid.getRowCount() == 0) return;
    if (e.getClickCount() == 2) toggleSort(col);
    if (e.isControlDown()) grid.addColumnSelectionInterval(view, view);
    else grid.setColumnSelectionInterval(view, view);
    grid.setRowSelectionInterval(0, grid.getRowCount() - 1);
  }

  /** 칸 오른쪽 누름: 값 · 칼럼 · 행 복사 메뉴. 누른 칸이 골라져 있지 않으면 그 칸만 고른다. */
  private void onCell(MouseEvent e) {
    if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
      int row = grid.rowAtPoint(e.getPoint());
      int view = grid.columnAtPoint(e.getPoint());
      if (row >= 0 && view >= 0) beginEdit(row, view);
      return;
    }
    if (!e.isPopupTrigger()) return;
    int row = grid.rowAtPoint(e.getPoint());
    int view = grid.columnAtPoint(e.getPoint());
    if (row < 0 || view < 0) return;
    if (!grid.isCellSelected(row, view)) grid.changeSelection(row, view, false, false);
    int col = grid.convertColumnIndexToModel(view);
    JPopupMenu menu = col > 0 ? columnMenu(col - 1) : new JPopupMenu();
    if (col > 0) {
      Object v = result.rows().get(grid.convertRowIndexToModel(row)).get(col - 1);
      menu.insert(item(EiDbBundle.message("result.menu.copy.value"), () -> copy(v == null ? "" : text(v), EiDbBundle.message("result.copied.value"))), 0);
    }
    menu.addSeparator();
    menu.add(item(EiDbBundle.message("result.menu.copy.rows"), this::copyTsv));
    menu.show(e.getComponent(), e.getX(), e.getY());
  }

  /** index 는 결과 칼럼 번호(행 번호 칼럼 제외). */
  private JPopupMenu columnMenu(int index) {
    String name = result.columns().get(index);
    JPopupMenu menu = new JPopupMenu();
    menu.add(item(EiDbBundle.message("result.menu.copy.column.name", name), () -> copy(name, EiDbBundle.message("result.copied.column.name"))));
    menu.add(item(EiDbBundle.message("result.menu.copy.column.names"), () -> copy(String.join(", ", result.columns()), EiDbBundle.message("result.copied.column.names", result.columns().size()))));
    menu.add(item(EiDbBundle.message("result.menu.copy.column.values"), () -> {
      List<String> values = columnValues(index);
      copy(String.join("\n", values), EiDbBundle.message("result.copied.values", values.size()));
    }));
    menu.add(item(EiDbBundle.message("result.menu.copy.in.list"), () -> {
      List<String> quoted = new ArrayList<>();
      for (String v : columnValues(index)) quoted.add("'" + v.replace("'", "''") + "'");
      copy("(" + String.join(", ", quoted) + ")", EiDbBundle.message("result.copied.in.list", quoted.size()));
    }));
    menu.addSeparator();
    menu.add(item(EiDbBundle.message("result.sort.asc"), () -> sortBy(index + 1, SortOrder.ASCENDING)));
    menu.add(item(EiDbBundle.message("result.sort.desc"), () -> sortBy(index + 1, SortOrder.DESCENDING)));
    menu.add(item(EiDbBundle.message("result.sort.none"), () -> sortBy(index + 1, null)));
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
    Notify.info(project, EiDbBundle.message("result.copied.rows", chosenRows().length));
  }

  /** 저장할 파일을 묻는다. 취소하면 null. */
  private Path askSavePath(String title, String description, String extension) {
    FileSaverDescriptor descriptor = new FileSaverDescriptor(title, description, extension);
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        .save((table == null ? "result" : table) + "." + extension);
    return target == null ? null : target.getFile().toPath();
  }

  /** 화면에 보이는(거르고 정렬된) 행을 보이는 순서대로. 값은 처음 읽은 값(반영 전 고친 값 제외). */
  private List<List<Object>> visibleRows() {
    List<List<Object>> rows = new ArrayList<>();
    for (int i = 0; i < grid.getRowCount(); i++) rows.add(result.rows().get(grid.convertRowIndexToModel(i)));
    return rows;
  }

  private void exportCsv() {
    Path path = askSavePath(EiDbBundle.message("result.csv.export"), EiDbBundle.message("result.csv.export.description"), "csv");
    if (path == null) return;
    StringBuilder sb = new StringBuilder(BOM);
    appendCsvLine(sb, List.copyOf(result.columns()));
    for (List<Object> row : visibleRows()) appendCsvLine(sb, row);
    String csv = sb.toString();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Files.writeString(path, csv, StandardCharsets.UTF_8);
        Notify.info(project, EiDbBundle.message("result.csv.saved", path));
      } catch (IOException e) {
        Notify.error(project, EiDbBundle.message("result.csv.failed", e.getMessage()));
      }
    });
  }

  private void exportXlsx() {
    Path path = askSavePath(EiDbBundle.message("result.excel.export"), EiDbBundle.message("result.excel.export.description"), "xlsx");
    if (path == null) return;
    List<List<Object>> rows = visibleRows();
    List<String> comments = commentRow();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        XlsxWriter.write(path, result.columns(), comments, rows);
        Notify.info(project, EiDbBundle.message("result.excel.saved", path));
      } catch (IOException e) {
        Notify.error(project, EiDbBundle.message("result.excel.failed", e.getMessage()));
      }
    });
  }

  /** 칼럼 순서대로 한글 주석(없으면 빈 글자). 주석이 하나도 없으면 null. */
  private List<String> commentRow() {
    Map<String, String> comments = result.comments();
    if (comments == null) return null;
    List<String> out = new ArrayList<>();
    boolean any = false;
    for (String name : result.columns()) {
      String comment = comments.getOrDefault(name, "");
      out.add(comment == null ? "" : comment);
      any |= comment != null && !comment.isBlank();
    }
    return any ? out : null;
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
      target = Messages.showInputDialog(project, EiDbBundle.message("result.insert.table"), EiDbBundle.message("result.insert.title"), null);
      if (target == null || target.isBlank()) return;
      target = target.strip();
    }
    List<List<Object>> rows = new ArrayList<>();
    for (int row : chosenRows()) rows.add(result.rows().get(row));
    String sql;
    try {
      sql = SqlText.inserts(target, result.columns(), result.columnTypes(), rows, config.kind);
    } catch (IllegalArgumentException e) {
      Notify.error(project, e.getMessage());
      return;
    }
    CopyPasteManager.getInstance().setContents(new StringSelection(sql));
    Notify.info(project, EiDbBundle.message("result.insert.copied", rows.size()) + lobNotice(sql));
  }

  /** 고른 행(없으면 전체)을 UPDATE 문으로 복사. PK 는 접속해서 표 정의로 찾으므로 뒤에서 읽은 뒤 복사한다. */
  private void copyUpdates(DbConfig c) {
    String target = table;
    if (target == null) {
      target = Messages.showInputDialog(project, EiDbBundle.message("result.update.table"), EiDbBundle.message("result.update.title"), null);
      if (target == null || target.isBlank()) return;
      target = target.strip();
    }
    String name = target;
    int[] rowIndexes = chosenRows();
    List<List<Object>> rows = new ArrayList<>();
    for (int row : rowIndexes) rows.add(result.rows().get(row));
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Set<String> pks = new HashSet<>();
      try {
        for (Meta.Column col : tableColumns(c, name)) {
          if (col.pk()) pks.add(col.name().toUpperCase(Locale.ROOT));
        }
      } catch (Exception e) {
        // PK 를 못 읽어도 첫 칼럼 조건 + 확인 주석으로 만든다(SqlText.updates).
      }
      String sql;
      try {
        sql = SqlText.updates(name, result.columns(), result.columnTypes(), rows, pks, c.kind);
      } catch (IllegalArgumentException e) {
        Notify.error(project, e.getMessage());
        return;
      }
      ApplicationManager.getApplication().invokeLater(() -> {
        CopyPasteManager.getInstance().setContents(new StringSelection(sql));
        Notify.info(project, EiDbBundle.message("result.update.copied", rows.size()) + (pks.isEmpty() ? EiDbBundle.message("result.update.no.pk") : "") + lobNotice(sql));
      });
    });
  }

  private static String lobNotice(String sql) {
    return sql.startsWith(SqlText.LOB_EXCLUDED) ? EiDbBundle.message("result.lob.excluded") : "";
  }

  /**
   * 표 정의 칼럼(스키마 · 따옴표를 뗀 이름으로, 접속 스키마에서). Oracle 은 대문자, PostgreSQL 은 소문자로 저장되므로
   * 쓴 그대로 · 대문자 · 소문자 순서로 찾는다. 못 찾으면 빈 목록. 화면 스레드에서 부르지 않는다.
   */
  private List<Meta.Column> tableColumns(DbConfig c, String name) throws Exception {
    String bare = name.substring(name.lastIndexOf('.') + 1).replace("\"", "");
    Connection conn = DbSessions.get(project).connection(c);
    for (String candidate : new String[]{bare, bare.toUpperCase(Locale.ROOT), bare.toLowerCase(Locale.ROOT)}) {
      List<Meta.Column> columns = Meta.columns(conn, c, Meta.schemaOf(c), candidate);
      if (!columns.isEmpty()) return columns;
    }
    return List.of();
  }

  /**
   * 칸 두 번 누름: 고치기 시작. 처음엔 표 정의를 뒤에서 읽어 PK 를 찾고, 고칠 수 없으면 이유를 알린다.
   * 고칠 수 있는 칸이면 JTable 이 이미 편집을 시작했으므로 그대로 둔다.
   */
  private void beginEdit(int viewRow, int viewColumn) {
    int col = grid.convertColumnIndexToModel(viewColumn) - 1;
    if (col < 0 || grid.isEditing()) return;
    if (table == null) {
      Notify.error(project, EiDbBundle.message("result.edit.single.table"));
      return;
    }
    if (pkColumns == null && editBlocked == null) {
      loadEditInfo(viewRow, viewColumn);
      return;
    }
    String reason = editBlocked != null ? editBlocked : cellBlocked(col);
    if (reason != null) {
      Notify.error(project, reason);
      return;
    }
    if (viewRow < grid.getRowCount()) grid.editCellAt(viewRow, viewColumn);
  }

  private void loadEditInfo(int viewRow, int viewColumn) {
    String early = config.readOnly ? EiDbBundle.message("result.edit.read.only") : SqlText.notEditable(result.sql());
    if (early != null) {
      editBlocked = early;
      Notify.error(project, early);
      return;
    }
    if (loadingEdit) return;
    loadingEdit = true;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        List<Meta.Column> defs = tableColumns(config, table);
        ApplicationManager.getApplication().invokeLater(() -> {
          loadingEdit = false;
          boolean[] keys = new boolean[result.columns().size()];
          editBlocked = editBlockReason(defs, keys);
          pkColumns = keys;
          beginEdit(viewRow, viewColumn);
        }, project.getDisposed());
      } catch (Exception e) {
        // 다음 두 번 누름에서 다시 읽게 상태를 두지 않는다.
        ApplicationManager.getApplication().invokeLater(() -> loadingEdit = false);
        Notify.error(project, EiDbBundle.message("result.edit.no.definition", ResultView.message(e)));
      }
    });
  }

  /** 결과 칼럼이 모두 이 표의 칼럼이고 PK 칼럼이 모두 결과에 있어야 고칠 수 있다. keys 에 결과 칼럼마다 PK 여부를 채운다. */
  private String editBlockReason(List<Meta.Column> defs, boolean[] keys) {
    if (defs.isEmpty()) return EiDbBundle.message("result.edit.table.missing", table);
    Map<String, Boolean> pkByName = new HashMap<>();
    for (Meta.Column d : defs) pkByName.put(d.name().toUpperCase(Locale.ROOT), d.pk());
    if (!pkByName.containsValue(true)) return EiDbBundle.message("result.edit.no.pk", table);
    for (int i = 0; i < keys.length; i++) {
      Boolean pk = pkByName.get(result.columns().get(i).toUpperCase(Locale.ROOT));
      if (pk == null) return EiDbBundle.message("result.edit.column.missing", result.columns().get(i), table);
      keys[i] = pk;
      if (pk && SqlText.isUneditableType(typeOf(i))) return EiDbBundle.message("result.edit.pk.binary", result.columns().get(i));
    }
    Set<String> shown = new HashSet<>();
    for (String name : result.columns()) shown.add(name.toUpperCase(Locale.ROOT));
    for (Meta.Column d : defs) {
      if (d.pk() && !shown.contains(d.name().toUpperCase(Locale.ROOT))) return EiDbBundle.message("result.edit.pk.missing", d.name());
    }
    return null;
  }

  /** 이 칼럼(결과 칼럼 번호)만 고칠 수 없는 이유. 고칠 수 있으면 null. */
  private String cellBlocked(int col) {
    String name = result.columns().get(col);
    if (pkColumns[col]) return EiDbBundle.message("result.edit.pk.column", name);
    if (SqlText.isUneditableType(typeOf(col))) return EiDbBundle.message("result.edit.lob.column", name);
    return null;
  }

  private String typeOf(int col) {
    return col < result.columnTypes().size() ? result.columnTypes().get(col) : "";
  }

  private boolean isEditable(int col) {
    return pkColumns != null && editBlocked == null && cellBlocked(col) == null;
  }

  /** 편집기에서 받은 글을 칼럼 형식 값으로 바꿔 둔다. 처음 값과 같아지면 고친 표시를 지운다. 형식이 틀리면 알리고 두지 않는다. */
  private void edit(int row, int col, String text) {
    Object value;
    try {
      value = SqlText.parseEdit(text, typeOf(col));
    } catch (IllegalArgumentException e) {
      Notify.error(project, result.columns().get(col) + ": " + e.getMessage());
      return;
    }
    Map<Integer, Object> changed = edits.computeIfAbsent(row, k -> new TreeMap<>());
    if (literal(value).equals(literal(result.rows().get(row).get(col)))) changed.remove(col);
    else changed.put(col, value);
    if (changed.isEmpty()) edits.remove(row);
    updateEditButtons();
  }

  private void updateEditButtons() {
    applyButton.setText(EiDbBundle.message("result.apply.count", edits.size()));
    applyButton.setVisible(!edits.isEmpty());
    revertButton.setVisible(!edits.isEmpty());
    applyButton.getParent().revalidate();
  }

  private void revertEdits() {
    if (grid.isEditing()) grid.getCellEditor().cancelCellEditing();
    edits.clear();
    updateEditButtons();
    grid.repaint();
  }

  /**
   * 고친 행마다 UPDATE 문(SET 은 고친 칼럼, WHERE 는 PK 처음 값)을 만들어 미리보기로 확인받고 SqlRunner 로 차례로 실행한다.
   * 실행은 ? 자리 + binds 로 하고(값 이스케이프를 드라이버에 맡김) 미리보기에만 리터럴 문장을 보인다.
   * 운영 접속 확인 · 읽기 전용 거절은 SqlRunner 가 한다. 실행에 넘긴 뒤 화면 값은 고친 값으로 둔다.
   */
  private void applyEdits() {
    if (grid.isEditing() && !grid.getCellEditor().stopCellEditing()) return;
    if (edits.isEmpty()) return;
    List<SqlText.Update> statements = new ArrayList<>();
    for (Map.Entry<Integer, Map<Integer, Object>> e : edits.entrySet()) statements.add(updateStatement(e.getKey(), e.getValue()));
    if (!confirmApply(statements)) return;
    for (SqlText.Update u : statements) SqlRunner.run(project, config, u.sql(), u.binds());
    for (Map.Entry<Integer, Map<Integer, Object>> e : edits.entrySet()) {
      for (Map.Entry<Integer, Object> v : e.getValue().entrySet()) result.rows().get(e.getKey()).set(v.getKey(), v.getValue());
    }
    edits.clear();
    updateEditButtons();
    grid.repaint();
    Notify.info(project, EiDbBundle.message("result.apply.done", statements.size()));
  }

  private SqlText.Update updateStatement(int row, Map<Integer, Object> changed) {
    List<String> names = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    for (Map.Entry<Integer, Object> v : changed.entrySet()) {
      names.add(result.columns().get(v.getKey()));
      values.add(v.getValue());
    }
    int setCount = names.size();
    for (int i = 0; i < pkColumns.length; i++) {
      if (!pkColumns[i]) continue;
      names.add(result.columns().get(i));
      values.add(result.rows().get(row).get(i));
    }
    boolean[] key = new boolean[names.size()];
    Arrays.fill(key, setCount, key.length, true);
    return SqlText.updateWithBinds(table, names, values, key);
  }

  private boolean confirmApply(List<SqlText.Update> statements) {
    List<String> previews = new ArrayList<>();
    for (SqlText.Update u : statements) previews.add(u.preview());
    JBTextArea text = new JBTextArea(String.join("\n", previews));
    text.setEditable(false);
    text.setFont(monoFont());
    JBScrollPane scroll = new JBScrollPane(text);
    scroll.setPreferredSize(new Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(PREVIEW_HEIGHT)));
    JBLabel note = new JBLabel(EiDbBundle.message("result.apply.note", config.name) + (DbSessions.get(project).autoCommit(config) ? "" : EiDbBundle.message("result.apply.note.commit")));
    note.setBorder(JBUI.Borders.emptyBottom(8));
    DialogBuilder dialog = new DialogBuilder(project);
    dialog.setTitle(EiDbBundle.message("result.apply.title", statements.size()));
    dialog.setNorthPanel(note);
    dialog.setCenterPanel(scroll);
    dialog.addOkAction().setText(EiDbBundle.message("common.run"));
    dialog.addCancelAction();
    return dialog.showAndGet();
  }

  /** ANSI 표기(TIMESTAMP · DATE 글자)라 Oracle · MySQL · PostgreSQL 에서 같이 쓴다. */
  static String literal(Object v) {
    return SqlText.literal(v);
  }

  static String text(Object v) {
    return SqlText.text(v);
  }

  /** 0번 칸은 행 번호. 머리글은 칼럼 이름 + 한글 주석(작고 흐리게) 두 줄. 고친 값(edits)이 있으면 그 값을 보인다. */
  private final class Model extends AbstractTableModel {
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
    @Override public boolean isCellEditable(int row, int column) { return column > 0 && isEditable(column - 1); }

    @Override
    public Object getValueAt(int row, int column) {
      if (column == 0) return new RowNumber(row + 1);
      return isEdited(row, column) ? edits.get(row).get(column - 1) : result.rows().get(row).get(column - 1);
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
      edit(row, column - 1, (String) value);
      fireTableRowsUpdated(row, row);
    }

    boolean isEdited(int row, int column) {
      Map<Integer, Object> changed = edits.get(row);
      return column > 0 && changed != null && changed.containsKey(column - 1);
    }
  }

  /** 고칠 때는 보이는 글(숫자는 지수 표기 없이) 그대로 시작하고, null 은 빈 칸으로 시작한다. */
  private static final class ValueEditor extends DefaultCellEditor {
    ValueEditor() { super(new JBTextField()); }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
      return super.getTableCellEditorComponent(table, value == null ? "" : text(value), selected, row, column);
    }
  }

  private record RowNumber(int value) {
    @Override public String toString() { return String.valueOf(value); }
  }

  /** NULL 은 흐린 기울임 「null」, 숫자 · 행 번호는 오른쪽 정렬. */
  private static final class CellRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
      // setBackground 는 다음 칸에도 남는 바탕색이 되므로 매번 비우고 시작한다(고친 칸 바탕).
      setBackground(null);
      super.getTableCellRendererComponent(table, value == null ? "null" : text(value), selected, focused, row, column);
      setFont(table.getFont().deriveFont(value == null ? Font.ITALIC : Font.PLAIN));
      boolean muted = value == null || value instanceof RowNumber;
      // DefaultTableCellRenderer 는 지정한 글자색을 다음 칸에도 쓰므로 매번 정한다.
      if (!selected) setForeground(muted ? UIUtil.getContextHelpForeground() : table.getForeground());
      if (!selected && table.getModel() instanceof Model m
          && m.isEdited(table.convertRowIndexToModel(row), table.convertColumnIndexToModel(column))) setBackground(EDITED_BACKGROUND);
      setHorizontalAlignment(value instanceof Number || value instanceof RowNumber ? SwingConstants.RIGHT : SwingConstants.LEFT);
      return this;
    }
  }
}
