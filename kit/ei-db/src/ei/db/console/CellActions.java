package ei.db.console;

import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredListCellRenderer;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.SchemaCache;
import ei.db.SqlRunner;
import ei.db.bench.SqlParts;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 결과 표의 칸 우클릭 메뉴에 더하는 「값 크게 보기」 · 「참조 따라가기」.
 * 참조 따라가기: 누르면 뒤에서 외래 키(하나도 없으면 스키마의 PK 사전)를 읽어 대상을 찾고, 하나면 바로 SELECT 를 실행해 새 결과 탭으로 열고,
 * 여럿이면 고르는 목록을 띄우고, 없으면 알린다. 조건 값은 바인드로 넘기고 실행은 SqlRunner 를 거친다.
 * 메타 읽기에 실패하면 알리고 아무것도 열지 않는다.
 */
final class CellActions {
  private CellActions() {}

  /** 「값 크게 보기」 단축키. JTable 의 F2(고치기)와 겹치지 않는다. */
  private static final KeyStroke BIG_VALUE_KEY = KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0);

  /**
   * menu 의 at 자리부터 두 항목을 끼운다. row 는 모델 행 번호, col 은 결과 칼럼 번호(행 번호 칸 제외).
   * 값이 null 이면 둘 다 끄고, 이진 · LOB 값이면 「참조 따라가기」만 끈다.
   */
  static void addTo(JPopupMenu menu, int at, Project project, DbConfig c, QueryResult result, int row, int col) {
    List<Object> values = new ArrayList<>(result.rows().get(row));
    Object value = values.get(col);
    JMenuItem big = new JMenuItem(EiDbBundle.message("result.menu.big.value"));
    big.setAccelerator(BIG_VALUE_KEY);
    big.setEnabled(value != null);
    big.addActionListener(e -> new BigValueDialog(project, c, result, values, col).show());
    menu.insert(big, at);
    JMenuItem follow = new JMenuItem(EiDbBundle.message("result.menu.follow"));
    boolean followable = FollowRef.followable(value, typeOf(result, col));
    follow.setEnabled(followable);
    if (!followable) follow.setToolTipText(EiDbBundle.message("follow.disabled"));
    follow.addActionListener(e -> follow(project, c, result, values, col));
    menu.insert(follow, at + 1);
  }

  /** 표에서 BIG_VALUE_KEY 를 누르면 마지막으로 고른 칸의 값을 크게 보인다. 고른 칸이 없거나 행 번호 칸 · null 이면 아무것도 하지 않는다. */
  static void installShortcut(JTable grid, Project project, DbConfig c, QueryResult result) {
    DumbAwareAction.create(e -> {
      int viewRow = grid.getSelectionModel().getLeadSelectionIndex();
      int viewColumn = grid.getColumnModel().getSelectionModel().getLeadSelectionIndex();
      if (viewRow < 0 || viewRow >= grid.getRowCount() || viewColumn < 0 || viewColumn >= grid.getColumnCount() || grid.isEditing()) return;
      int col = grid.convertColumnIndexToModel(viewColumn) - 1;
      if (col < 0) return;
      List<Object> values = new ArrayList<>(result.rows().get(grid.convertRowIndexToModel(viewRow)));
      if (values.get(col) != null) new BigValueDialog(project, c, result, values, col).show();
    }).registerCustomShortcutSet(new CustomShortcutSet(BIG_VALUE_KEY), grid);
  }

  static String typeOf(QueryResult result, int col) {
    return col < result.columnTypes().size() ? result.columnTypes().get(col) : "";
  }

  private static void follow(Project project, DbConfig c, QueryResult result, List<Object> row, int col) {
    String column = result.columns().get(col);
    new Task.Backgroundable(project, EiDbBundle.message("follow.progress", column), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        SqlParts.TableRef ref = FollowRef.singleTable(result.sql());
        List<FollowRef.Target> targets;
        String schema;
        DbSessions sessions = DbSessions.get(project);
        try {
          Connection conn = sessions.connection(c);
          try {
            // 기본 스키마를 비운 PostgreSQL 은 접속한 뒤에야 스키마를 안다.
            schema = ref != null && ref.schema() != null ? Meta.storedName(c, ref.schema()) : Meta.schemaOf(c);
            String own = ref == null ? null : Meta.storedName(c, ref.name());
            targets = own == null ? List.of()
                : FollowRef.declared(result.columns(), col, row, SchemaCache.importedKeys(conn, c, schema, own), exportedKeys(conn, c, schema, own, column));
            if (targets.isEmpty()) targets = FollowRef.guessed(column, col, own, SchemaCache.primaryKeys(conn, c, schema));
          } finally {
            sessions.endReadTransaction(c);
          }
        } catch (Exception e) {
          Notify.error(project, EiDbBundle.message("follow.failed", c.name, ResultView.message(e)));
          return;
        }
        if (indicator.isCanceled()) return;
        // 문장에 스키마를 붙여 썼을 때만 만든 SELECT 에도 붙인다(안 붙였으면 원래 문장처럼 접속의 이름 찾기 규칙에 맡긴다).
        String written = ref != null && ref.schema() != null ? schema : null;
        List<FollowRef.Target> found = targets;
        ApplicationManager.getApplication().invokeLater(() -> choose(project, c, written, column, row, found), project.getDisposed());
      }
    }.queue();
  }

  /** 누른 칼럼이 그 표의 PK 칼럼일 때만 그 표를 가리키는 외래 키를 읽는다(Oracle 에서 느린 조회라 PK 가 아니면 읽지 않는다). */
  private static List<Meta.ForeignKey> exportedKeys(Connection conn, DbConfig c, String schema, String table, String column) throws SQLException {
    for (Meta.Column d : SchemaCache.columns(conn, c, schema, table)) {
      if (d.pk() && d.name().equalsIgnoreCase(column)) return SchemaCache.exportedKeys(conn, c, schema, table);
    }
    return List.of();
  }

  private static void choose(Project project, DbConfig c, String schema, String column, List<Object> row, List<FollowRef.Target> targets) {
    if (targets.isEmpty()) {
      Notify.warning(project, EiDbBundle.message("follow.none", column));
      return;
    }
    if (targets.size() == 1) {
      open(project, c, schema, row, targets.get(0));
      return;
    }
    JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
        .setTitle(EiDbBundle.message("follow.choose.title", column))
        .setRenderer(new ColoredListCellRenderer<FollowRef.Target>() {
          @Override
          protected void customizeCellRenderer(@NotNull JList<? extends FollowRef.Target> list, FollowRef.Target value, int index, boolean selected, boolean focused) {
            append(label(value));
          }
        })
        .setNamerForFiltering(CellActions::label)
        .setItemChosenCallback(t -> open(project, c, schema, row, t))
        .createPopup().showCenteredInCurrentWindow(project);
  }

  private static String label(FollowRef.Target t) {
    String key = switch (t.kind()) {
      case REFERENCE -> "follow.item.reference";
      case REFERRED -> "follow.item.referred";
      case GUESS -> "follow.item.guess";
    };
    return EiDbBundle.message(key, t.table(), String.join(", ", t.columns()));
  }

  private static void open(Project project, DbConfig c, String schema, List<Object> row, FollowRef.Target t) {
    List<Object> binds = new ArrayList<>();
    for (int from : t.from()) binds.add(row.get(from));
    if (t.kind() == FollowRef.Kind.GUESS) Notify.info(project, EiDbBundle.message("follow.guessed", t.table(), t.columns().get(0)));
    SqlRunner.run(project, c, FollowRef.select(c.kind, schema, t.table(), null, t.columns()), binds);
  }
}
