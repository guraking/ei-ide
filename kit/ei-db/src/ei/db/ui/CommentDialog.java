package ei.db.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.FileTypes;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.SqlRunner;
import ei.db.console.ConsoleOpener;
import ei.db.gen.Ui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 탐색기 「주석 고치기」: 표 주석 한 줄과 칼럼 주석을 한 창에서 고쳐 COMMENT ON 문으로 한꺼번에 적용한다.
 * 열면 백그라운드에서 표 주석 · 칼럼을 DB 에서 새로 읽는다(캐시가 아니라). 고친 칸은 바탕색으로 표시하고, 아래에 바뀐 것만의 문장을 미리 보인다.
 * 「적용」은 SqlRunner.runAll 로 실행한다: 읽기 전용 거절 · 운영 확인 · Oracle 의 암묵 커밋 확인이 그대로 걸린다.
 * 모두 성공하면 기준 값을 새 값으로 바꾼다. 중간에 실패하거나 멈추면 어디까지 들어갔는지 가정하지 않고 DB 의 주석을 다시 읽어 기준 값을 맞춘다
 * (못 넣은 고친 값은 화면에 남아 다시 적용할 수 있다). 다시 읽지 못하면 기준 값을 그대로 두고 알린다.
 * 표 · 칼럼 캐시(SchemaCache · Comments)는 DDL 실행 때 DbSessions 가 비운다. 탐색기 나무는 새로고침하면 새 주석이 보인다.
 * 「붙여 넣기」는 클립보드의 「이름<TAB>주석」 줄로 이름이 같은 칼럼을 채운다(주석 칸이 빈 줄은 건너뛴다). MySQL 은 COMMENT ON 이 없어 창을 열지 않고 알린다.
 * 창은 모달이 아니다.
 */
final class CommentDialog extends DialogWrapper {

  /** 처음 열 때 화면 폭 · 높이에 대한 창 크기 비율. 사용자가 바꾼 크기는 IntelliJ 가 기억한다. */
  private static final double FIRST_WIDTH_RATIO = 0.5;
  private static final double FIRST_HEIGHT_RATIO = 0.7;
  private static final String DIMENSION_KEY = "ei.db.ui.CommentDialog";
  /** 표와 미리보기를 나누는 처음 비율(끌어 바꾼 비율은 기억한다) · 칸 너비 기억 키. */
  private static final float SPLIT = 0.7f;
  private static final String SPLIT_KEY = "ei.db.ui.CommentDialog.split";
  private static final String COLUMNS_KEY = "comments";
  private static final int GAP = 6;
  /** 결과 표의 고친 칸과 같은 바탕색. */
  private static final Color EDITED_BACKGROUND = new JBColor(new Color(0xFFF1C2), new Color(0x4D4421));
  private static final int COL_NAME = 0;
  private static final int COL_TYPE = 1;
  private static final int COL_COMMENT = 2;
  private static final String[] HEADERS = {
      EiDbBundle.message("common.col.column"), EiDbBundle.message("common.col.type"), EiDbBundle.message("common.col.comment")};
  /** 알림에 적는 못 맞춘 이름 수의 상한(넘으면 「외 N개」). */
  private static final int MAX_UNKNOWN_SHOWN = 20;
  private static final String NAME_SEPARATOR = ", ";
  /** 스윙 표 속성: 고치던 칸이 초점을 잃으면(단추를 누르면) 값을 확정한다. */
  private static final String COMMIT_ON_FOCUS_LOST = "terminateEditOnFocusLost";

  /** 칼럼 한 줄. base 는 DB 에 있는 주석, value 는 화면 값. */
  private static final class Row {
    final String name;
    final String type;
    String base;
    String value;

    Row(String name, String type, String comment) {
      this.name = name;
      this.type = type;
      this.base = comment;
      this.value = comment;
    }

    boolean edited() { return !base.equals(value); }
  }

  private final Project project;
  private final DbConfig c;
  private final String schema;
  private final String table;
  private final List<Row> rows = new ArrayList<>();
  private final Model model = new Model();
  private final JBTable grid = new JBTable(model) {
    @Override
    public @NotNull Component prepareRenderer(@NotNull TableCellRenderer renderer, int row, int column) {
      Component comp = super.prepareRenderer(renderer, row, column);
      boolean edited = convertColumnIndexToModel(column) == COL_COMMENT && rows.get(convertRowIndexToModel(row)).edited();
      if (!isCellSelected(row, column)) comp.setBackground(edited ? EDITED_BACKGROUND : getBackground());
      return comp;
    }
  };
  private final JBTextField tableField = new JBTextField();
  private final EditorTextField preview;
  private final JBLabel status = new JBLabel(" ");
  private final Gauge gauge = Gauge.create(EiDbBundle.message("explorer.loading"));
  /** DB 에 있는 표 주석. 읽기 전에는 null. */
  private String tableBase;
  private CommentSql.ObjectKind objectKind = CommentSql.ObjectKind.TABLE;
  /** 적용을 실행하는 동안 참: 같은 문장을 두 번 보내지 않게 단추를 끈다. */
  private boolean applying;
  /** 읽기를 시작한 때의 화면 상태. 결과는 이 상태로 돌려보낸다(미리보기 글을 바꾸므로 ModalityState.any() 는 쓸 수 없다). */
  private ModalityState modality = ModalityState.nonModal();

  private final Action applyAction = new DialogWrapperAction(EiDbBundle.message("comments.apply")) {
    @Override
    protected void doAction(ActionEvent e) { apply(); }
  };
  private final Action consoleAction = new DialogWrapperAction(EiDbBundle.message("querypick.send")) {
    @Override
    protected void doAction(ActionEvent e) {
      if (!stopEditing()) return;
      ConsoleOpener.open(project, c, script());
    }
  };
  private final Action pasteAction = new DialogWrapperAction(EiDbBundle.message("comments.paste")) {
    @Override
    protected void doAction(ActionEvent e) { paste(); }
  };

  /** table 은 메타 이름 그대로. 화면 스레드에서 부른다. */
  static void show(Project project, DbConfig c, String schema, String table) {
    if (c.kind == DbKind.MYSQL) {
      Notify.warning(project, EiDbBundle.message("comments.mysql.unsupported"));
      return;
    }
    new CommentDialog(project, c, schema, table).show();
  }

  private CommentDialog(Project project, DbConfig c, String schema, String table) {
    super(project, false);
    this.project = project;
    this.c = c;
    this.schema = schema;
    this.table = table;
    preview = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, FileTypes.byExtension("sql"), true, false);
    preview.addSettingsProvider(e -> {
      e.setVerticalScrollbarVisible(true);
      e.setHorizontalScrollbarVisible(true);
    });
    setModal(false);
    setTitle(EiDbBundle.message("comments.title", c.name, schema + "." + table));
    setCancelButtonText(EiDbBundle.message("common.close"));
    init();
    refresh();
    load();
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() { return Ui.firstSize(FIRST_WIDTH_RATIO, FIRST_HEIGHT_RATIO); }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {applyAction, consoleAction, getCancelAction()};
  }

  @Override
  protected Action @NotNull [] createLeftSideActions() {
    return new Action[] {pasteAction};
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() { return tableField; }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    tableField.setEnabled(false);
    tableField.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) { refresh(); }
    });
    JPanel tableRow = new JPanel(new BorderLayout(JBUI.scale(GAP), 0));
    tableRow.add(new JBLabel(EiDbBundle.message("comments.table.comment")), BorderLayout.WEST);
    tableRow.add(tableField, BorderLayout.CENTER);
    JPanel head = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    head.add(tableRow, BorderLayout.NORTH);
    head.add(gauge.line(), BorderLayout.SOUTH);

    grid.setShowGrid(false);
    grid.putClientProperty(COMMIT_ON_FOCUS_LOST, Boolean.TRUE);
    grid.getEmptyText().setText("");
    Ui.rememberColumns(grid, COLUMNS_KEY);

    JBSplitter split = new JBSplitter(true, SPLIT);
    split.setSplitterProportionKey(SPLIT_KEY);
    split.setHonorComponentsMinimumSize(false);
    split.setFirstComponent(new JBScrollPane(grid));
    split.setSecondComponent(preview);

    JPanel foot = new JPanel(new BorderLayout());
    foot.add(gauge, BorderLayout.WEST);
    foot.add(status, BorderLayout.CENTER);
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    panel.add(head, BorderLayout.NORTH);
    panel.add(split, BorderLayout.CENTER);
    panel.add(foot, BorderLayout.SOUTH);
    return panel;
  }

  // ---- 읽기 ----

  private record Loaded(CommentSql.ObjectKind kind, String tableComment, List<Meta.ColumnRow> columns) {}

  private void load() {
    gauge.setVisible(true);
    modality = ModalityState.current();
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Loaded loaded = read(sessions.connection(c));
        later(() -> show(loaded));
      } catch (Exception e) {
        String msg = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        later(() -> {
          gauge.setVisible(false);
          status(EiDbBundle.message("comments.load.failed", msg), true);
          Notify.error(project, EiDbBundle.message("comments.load.failed", msg));
        });
      } finally {
        sessions.endReadTransaction(c);
      }
    });
  }

  /** 표 종류 · 표 주석 · 칼럼을 DB 에서 읽는다. getTables 는 이름을 LIKE 패턴으로 보므로 이름이 정확히 같은 줄만 쓴다. */
  private Loaded read(Connection conn) throws SQLException {
    String type = null;
    String comment = "";
    try (ResultSet rs = conn.getMetaData().getTables(null, schema, table, Meta.tableAndViewTypes(c))) {
      while (rs.next()) {
        if (!table.equals(rs.getString("TABLE_NAME"))) continue;
        type = rs.getString("TABLE_TYPE");
        comment = rs.getString("REMARKS") == null ? "" : rs.getString("REMARKS");
      }
    }
    CommentSql.ObjectKind kind = c.kind == DbKind.ORACLE ? DdlRead.oracleKind(conn, schema, table) : CommentSql.ObjectKind.ofTableType(type);
    return new Loaded(kind, comment, Meta.columnRows(conn, c, schema, table, false));
  }

  private void later(Runnable r) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!isDisposed()) r.run();
    }, modality);
  }

  private void show(Loaded loaded) {
    gauge.setVisible(false);
    if (loaded.columns().isEmpty()) {
      status(EiDbBundle.message("comments.no.columns", schema + "." + table), true);
      return;
    }
    objectKind = loaded.kind();
    for (Meta.ColumnRow r : loaded.columns()) rows.add(new Row(r.column().name(), r.column().typeLabel(), r.column().comment()));
    model.fireTableDataChanged();
    // 한 줄 글 칸은 줄바꿈을 공백으로 바꾼다. 칸에 들어간 글을 기준으로 삼아 읽자마자 「바뀜」으로 보이지 않게 한다.
    tableField.setText(loaded.tableComment());
    tableBase = tableField.getText();
    tableField.setEnabled(true);
    status(EiDbBundle.message("comments.help"), false);
    refresh();
  }

  // ---- 고친 것 ----

  /** 지금 화면에서 바뀐 주석들: 표 주석 먼저, 칼럼은 표 순서대로. 읽기 전이면 빈 목록. */
  private List<CommentSql.Change> changes() {
    List<CommentSql.Change> out = new ArrayList<>();
    if (tableBase == null) return out;
    if (!tableBase.equals(tableField.getText())) out.add(new CommentSql.Change(null, tableField.getText()));
    for (Row r : rows) {
      if (r.edited()) out.add(new CommentSql.Change(r.name, r.value));
    }
    return out;
  }

  private String script() {
    return CommentSql.script(CommentSql.statements(c.kind, objectKind, schema, table, changes()));
  }

  /** 미리보기와 단추를 지금 화면 값에 맞춘다. */
  private void refresh() {
    String script = script();
    preview.setText(script);
    boolean any = !script.isEmpty();
    applyAction.setEnabled(any && !applying);
    consoleAction.setEnabled(any);
    pasteAction.setEnabled(tableBase != null && !applying);
    grid.repaint();
  }

  /** 고치던 칸을 확정한다. 확정하지 못하면 false. */
  private boolean stopEditing() {
    return !grid.isEditing() || grid.getCellEditor().stopCellEditing();
  }

  private void status(String text, boolean error) {
    status.setText(text);
    status.setToolTipText(text);
    status.setForeground(error ? UIUtil.getErrorForeground() : UIUtil.getContextHelpForeground());
  }

  // ---- 적용 ----

  private void apply() {
    if (applying || !stopEditing()) return;
    List<CommentSql.Change> changes = changes();
    if (changes.isEmpty()) return;
    List<String> sqls = CommentSql.statements(c.kind, objectKind, schema, table, changes);
    applying = SqlRunner.runAll(project, c, sqls, (done, error) -> applied(changes, done, error));
    if (applying) status(EiDbBundle.message("comments.applying", sqls.size()), false);
    refresh();
  }

  /** 모두 성공했으면 기준 값을 바꾸고, 멈췄으면 DB 에서 다시 읽어 맞춘다(rebase). 창이 그사이 닫혔어도 결과는 알린다. */
  private void applied(List<CommentSql.Change> changes, int done, String error) {
    // 멈췄으면 다시 읽기가 끝날 때까지 적용 단추를 꺼 둔다.
    applying = error != null;
    for (int i = 0; error == null && i < done; i++) {
      CommentSql.Change ch = changes.get(i);
      if (ch.column() == null) {
        tableBase = ch.comment();
      } else {
        for (Row r : rows) {
          if (r.name.equals(ch.column())) r.base = ch.comment();
        }
      }
    }
    String text;
    if (error != null) {
      text = EiDbBundle.message("comments.apply.stopped", done, changes.size(), error);
      Notify.error(project, text);
    } else {
      boolean uncommitted = DbSessions.get(project).pending(c.id) > 0;
      text = EiDbBundle.message(uncommitted ? "comments.apply.done.uncommitted" : "comments.apply.done", done);
      Notify.info(project, text);
    }
    if (isDisposed()) return;
    status(text, error != null);
    refresh();
    if (error != null) rebase(text);
  }

  /** DB 의 주석을 다시 읽어 기준 값을 맞춘다. stopped 는 적용이 멈춘 까닭 글(다시 읽지 못하면 그 뒤에 덧붙여 보인다). */
  private void rebase(String stopped) {
    gauge.setVisible(true);
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Loaded loaded = read(sessions.connection(c));
        later(() -> rebased(loaded));
      } catch (Exception e) {
        String msg = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        later(() -> {
          status(stopped + " " + EiDbBundle.message("comments.reload.failed", msg), true);
          rebased(null);
        });
      } finally {
        sessions.endReadTransaction(c);
      }
    });
  }

  /** 다시 읽은 주석을 기준 값으로 삼는다. 고치지 않은 칸은 새 값을 보이고 고친 칸은 화면 값을 둔다. loaded 가 null 이면 기준 값을 그대로 둔다. */
  private void rebased(Loaded loaded) {
    gauge.setVisible(false);
    applying = false;
    if (loaded != null && !loaded.columns().isEmpty()) {
      Map<String, String> now = new java.util.HashMap<>();
      for (Meta.ColumnRow r : loaded.columns()) now.put(r.column().name(), r.column().comment());
      for (Row r : rows) {
        String comment = now.get(r.name);
        if (comment == null) continue;
        if (!r.edited()) r.value = comment;
        r.base = comment;
      }
      if (tableBase.equals(tableField.getText())) {
        tableField.setText(loaded.tableComment());
        tableBase = tableField.getText();
      } else {
        // 한 줄 글 칸이 줄바꿈을 공백으로 바꾸는 것과 같게 맞춘다.
        tableBase = loaded.tableComment().replace('\n', ' ');
      }
      model.fireTableDataChanged();
    }
    refresh();
  }

  // ---- 붙여 넣기 ----

  private void paste() {
    if (!stopEditing()) return;
    String text = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
    Map<String, String> pasted = CommentSql.parsePaste(text);
    int skipped = CommentSql.blankComments(text);
    if (pasted.isEmpty() && skipped == 0) {
      status(EiDbBundle.message("comments.paste.empty"), true);
      return;
    }
    List<String> names = new ArrayList<>(rows.size());
    for (Row r : rows) names.add(r.name);
    CommentSql.Matched m = CommentSql.match(table, names, pasted);
    for (Map.Entry<Integer, String> e : m.columns().entrySet()) rows.get(e.getKey()).value = e.getValue();
    if (m.tableComment() != null) tableField.setText(m.tableComment());
    int filled = m.columns().size() + (m.tableComment() == null ? 0 : 1);
    status(skipped > 0 ? EiDbBundle.message("comments.paste.done.skipped", filled, skipped) : EiDbBundle.message("comments.paste.done", filled), false);
    if (!m.unknown().isEmpty()) {
      List<String> shown = m.unknown().subList(0, Math.min(MAX_UNKNOWN_SHOWN, m.unknown().size()));
      String list = String.join(NAME_SEPARATOR, shown);
      int more = m.unknown().size() - shown.size();
      Notify.warning(project, more > 0 ? EiDbBundle.message("comments.paste.unknown.more", m.unknown().size(), list, more)
                                       : EiDbBundle.message("comments.paste.unknown", m.unknown().size(), list));
    }
    refresh();
  }

  private final class Model extends AbstractTableModel {
    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return HEADERS.length; }
    @Override public String getColumnName(int column) { return HEADERS[column]; }
    @Override public boolean isCellEditable(int row, int column) { return column == COL_COMMENT && !applying; }

    @Override
    public Object getValueAt(int row, int column) {
      Row r = rows.get(row);
      return switch (column) {
        case COL_NAME -> r.name;
        case COL_TYPE -> r.type;
        default -> r.value;
      };
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
      if (column != COL_COMMENT) return;
      Row r = rows.get(row);
      String text = value == null ? "" : value.toString();
      // 칸 편집기는 줄바꿈을 공백으로 바꾼다. 그것 말고 바뀐 것이 없으면 여러 줄 주석(DB 값 · 붙여 넣은 값)을 그대로 둔다.
      if (text.equals(r.base.replace('\n', ' '))) r.value = r.base;
      else if (!text.equals(r.value.replace('\n', ' '))) r.value = text;
      refresh();
    }
  }
}
