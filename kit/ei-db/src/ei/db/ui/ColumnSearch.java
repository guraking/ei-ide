package ei.db.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 탐색기 「찾기」: 접속 하나의 기본 스키마에서 칼럼 이름 · 주석, 표 이름 · 주석에 검색어가 든 칼럼을 찾는다(최대 500건).
 * Enter 로 찾고(백그라운드), 줄을 두 번 누르면 open 을 부른다. 창은 모달이 아니라 열어 둔 채 탐색기 · 콘솔을 쓸 수 있다.
 */
final class ColumnSearch extends DialogWrapper {

  record Hit(String table, String column, String type, String comment, String tableComment) {}

  private static final String[] HEADERS = {EiDbBundle.message("common.col.table"), EiDbBundle.message("common.col.column"), EiDbBundle.message("common.col.type"), EiDbBundle.message("common.col.comment")};
  private static final int WIDTH = 760;
  private static final int HEIGHT = 420;

  private final Project project;
  private final DbConfig c;
  private final String schema;
  private final Consumer<Hit> open;
  private final SearchTextField field = new SearchTextField(false);
  private final Model model = new Model();
  private final JBTable table = new JBTable(model);
  private final JBLabel status = new JBLabel(" ", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
  /** 늦게 온 이전 검색 결과를 버리는 번호. */
  private final AtomicInteger generation = new AtomicInteger();

  static void show(Project project, DbConfig c, String schema, Consumer<Hit> open) {
    new ColumnSearch(project, c, schema, open).show();
  }

  private ColumnSearch(Project project, DbConfig c, String schema, Consumer<Hit> open) {
    super(project, false);
    this.project = project;
    this.c = c;
    this.schema = schema;
    this.open = open;
    setModal(false);
    setTitle(EiDbBundle.message("columnsearch.title", c.name, schema));
    setCancelButtonText(EiDbBundle.message("common.close"));
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    field.getTextEditor().getEmptyText().setText(EiDbBundle.message("columnsearch.hint"));
    field.getTextEditor().registerKeyboardAction(e -> search(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
    table.setShowGrid(false);
    table.setAutoResizeMode(JBTable.AUTO_RESIZE_ALL_COLUMNS);
    table.setDefaultRenderer(Object.class, new TipRenderer());
    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(@NotNull MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return false;
        open.accept(model.hits.get(table.convertRowIndexToModel(row)));
        return true;
      }
    }.installOn(table);
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    panel.add(field, BorderLayout.NORTH);
    JBScrollPane scroll = new JBScrollPane(table);
    scroll.setPreferredSize(new Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT)));
    panel.add(scroll, BorderLayout.CENTER);
    status.setText(EiDbBundle.message("columnsearch.status.help"));
    panel.add(status, BorderLayout.SOUTH);
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return field.getTextEditor();
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[]{getCancelAction()};
  }

  private void search() {
    String q = field.getText().trim();
    if (q.isEmpty()) {
      status.setText(EiDbBundle.message("columnsearch.status.empty"));
      return;
    }
    int gen = generation.incrementAndGet();
    status.setText(EiDbBundle.message("common.searching"));
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        List<Hit> hits = find(sessions.connection(c), q);
        later(gen, () -> showHits(hits));
      } catch (Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        later(gen, () -> status.setText(EiDbBundle.message("columnsearch.status.failed", msg)));
      }
    });
  }

  private void later(int gen, Runnable r) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (gen == generation.get() && !isDisposed()) r.run();
    }, ModalityState.any());
  }

  private void showHits(List<Hit> hits) {
    boolean truncated = hits.size() > ColumnSearchSql.MAX_HITS;
    model.set(truncated ? hits.subList(0, ColumnSearchSql.MAX_HITS) : hits);
    if (hits.isEmpty()) status.setText(EiDbBundle.message("common.nothing.found"));
    else status.setText(truncated ? EiDbBundle.message("columnsearch.status.truncated", ColumnSearchSql.MAX_HITS) : EiDbBundle.message("common.rows", hits.size()));
  }

  /** MAX_HITS + 1 건까지 읽어 넘침을 알린다. */
  private List<Hit> find(Connection conn, String q) throws SQLException {
    List<Hit> out = new ArrayList<>();
    String like = ColumnSearchSql.like(q);
    try (PreparedStatement ps = conn.prepareStatement(ColumnSearchSql.sql(c.kind))) {
      ps.setMaxRows(ColumnSearchSql.MAX_HITS + 1);
      ps.setString(1, schema);
      for (int i = 2; i <= 5; i++) ps.setString(i, like);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Meta.Column col = new Meta.Column(rs.getString(2), nz(rs.getString(3)), rs.getInt(4), rs.getInt(5), false, false, true, "");
          out.add(new Hit(rs.getString(1), col.name(), col.typeLabel(), nz(rs.getString(6)), nz(rs.getString(7))));
        }
      }
    }
    return out;
  }

  private static String nz(String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").trim();
  }

  private static final class Model extends AbstractTableModel {
    List<Hit> hits = List.of();

    void set(List<Hit> list) {
      hits = list;
      fireTableDataChanged();
    }

    @Override public int getRowCount() { return hits.size(); }
    @Override public int getColumnCount() { return HEADERS.length; }
    @Override public String getColumnName(int column) { return HEADERS[column]; }

    @Override
    public Object getValueAt(int row, int column) {
      Hit h = hits.get(row);
      return switch (column) {
        case 0 -> h.tableComment().isEmpty() ? h.table() : h.table() + "  " + h.tableComment();
        case 1 -> h.column();
        case 2 -> h.type();
        default -> h.comment();
      };
    }
  }

  /** 칸이 좁아 줄임표가 붙어도 전체 글을 툴팁으로 본다. */
  private static final class TipRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(javax.swing.JTable t, Object value, boolean selected, boolean focused, int row, int column) {
      Component comp = super.getTableCellRendererComponent(t, value, selected, focused, row, column);
      String text = value == null ? "" : value.toString();
      setToolTipText(text.isEmpty() ? null : text);
      return comp;
    }
  }
}
