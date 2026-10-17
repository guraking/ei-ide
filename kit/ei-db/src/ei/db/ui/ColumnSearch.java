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

  private static final String[] HEADERS = {"표", "칼럼", "형식", "주석"};
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
    setTitle("표 · 칼럼 찾기 · " + c.name + " · " + schema);
    setCancelButtonText("닫기");
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    field.getTextEditor().getEmptyText().setText("칼럼 · 표 이름 또는 주석, Enter 로 찾기");
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
    status.setText("두 번 누르면 탐색기에서 그 표를 고르고, 탐색기에 없으면 SELECT 문을 콘솔에 엽니다.");
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
      status.setText("찾을 글자를 넣으세요.");
      return;
    }
    int gen = generation.incrementAndGet();
    status.setText("찾는 중…");
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        List<Hit> hits = find(sessions.connection(c), q);
        later(gen, () -> showHits(hits));
      } catch (Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        later(gen, () -> status.setText("찾지 못했습니다: " + msg));
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
    if (hits.isEmpty()) status.setText("찾은 것이 없습니다.");
    else status.setText(truncated ? ColumnSearchSql.MAX_HITS + "건까지만 보입니다. 검색어를 좁히세요." : hits.size() + "건");
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
