package ei.db.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.Comments;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.Passwords;
import ei.db.SqlRunner;
import ei.db.console.ConsoleOpener;
import ei.db.gen.ErdView;
import ei.db.gen.MapperDialog;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 「데이터베이스」 탐색기: 접속 → 기본 스키마 → 표 · 뷰 묶음 → 표 → 칼럼.
 * 스키마와 표의 자식은 펼칠 때 백그라운드에서 읽는다. 메타 조회는 한 줄로 세워 같은 접속을 두 번 열지 않게 한다.
 * 검색은 이미 읽어 둔 표 · 칼럼만 거른다(읽지 않은 스키마는 그대로 둔다).
 */
final class DbExplorer extends SimpleToolWindowPanel {

  private static final String PLACE = "EiDatabaseExplorer";
  private static final String[] TABLE_TYPES = {"TABLE"};
  private static final String[] VIEW_TYPES = {"VIEW"};
  private static final Message LOADING = new Message("불러오는 중…", false);
  private static final Color CONNECTED = JBColor.namedColor("EiDatabase.connected", new JBColor(0x3E9B47, 0x5FB865));
  private static final Color DANGER = JBColor.namedColor("EiDatabase.production", new JBColor(0xD9363E, 0xF75464));
  private static final Color KEY_TAG = JBColor.namedColor("EiDatabase.keyTag", new JBColor(0xB07400, 0xE0A64F));
  private static final int DOT_SIZE = 8;
  private static final int DOT_BOX = 16;

  private record Conn(DbConfig c) {}
  private record Schema(DbConfig c, String name) {}
  private record Message(String text, boolean error) {}

  private static final class TableItem {
    final DbConfig c;
    final String schema;
    final Meta.Table table;
    /** 아직 안 읽었으면 null. 화면 스레드에서만 바꾼다. */
    List<Meta.Column> columns;

    TableItem(DbConfig c, String schema, Meta.Table table) { this.c = c; this.schema = schema; this.table = table; }
  }

  private record Group(String label, List<TableItem> items) {}

  private final Project project;
  private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
  private final DefaultTreeModel model = new DefaultTreeModel(root);
  private final Tree tree = new Tree(model);
  private final SearchTextField search = new SearchTextField(false);
  private final Set<DefaultMutableTreeNode> loading = Collections.newSetFromMap(new IdentityHashMap<>());
  private final ExecutorService metaQueue = AppExecutorUtil.createBoundedApplicationPoolExecutor("EI Database 탐색기", 1);

  DbExplorer(Project project, Disposable parent) {
    super(true, true);
    this.project = project;

    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setToggleClickCount(0); // 더블클릭은 아래 DoubleClickListener 가 맡는다(표는 실행, 나머지는 펼치기).
    tree.setCellRenderer(new Renderer());
    tree.addTreeWillExpandListener(new TreeWillExpandListener() {
      @Override public void treeWillExpand(TreeExpansionEvent e) { loadIfNeeded((DefaultMutableTreeNode) e.getPath().getLastPathComponent()); }
      @Override public void treeWillCollapse(TreeExpansionEvent e) {}
    });
    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(@NotNull MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) return false;
        if (node(path).getUserObject() instanceof TableItem t) {
          viewData(t);
        } else if (tree.isExpanded(path)) {
          tree.collapsePath(path);
        } else {
          tree.expandPath(path);
        }
        return true;
      }
    }.installOn(tree);
    tree.addMouseListener(new PopupHandler() {
      @Override
      public void invokePopup(Component comp, int x, int y) {
        TreePath path = tree.getPathForLocation(x, y);
        if (path == null) return;
        if (!tree.isPathSelected(path)) tree.setSelectionPath(path);
        ActionGroup menu = menuFor(node(path));
        if (menu != null) ActionManager.getInstance().createActionPopupMenu(PLACE, menu).getComponent().show(comp, x, y);
      }
    });

    search.getTextEditor().getEmptyText().setText("표 · 칼럼 찾기");
    search.addDocumentListener(new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) { applyFilter(); }
    });

    DefaultActionGroup bar = new DefaultActionGroup();
    bar.add(new Act("접속 추가", AllIcons.General.Add, () -> true, this::addConnection));
    bar.add(new Act("새로고침", AllIcons.Actions.Refresh, () -> true, this::refreshSelected));
    bar.add(new Act("새 콘솔", AllIcons.Nodes.Console, () -> selectedConfig() != null, () -> ConsoleOpener.openNew(project, selectedConfig())));
    bar.add(new Act("접속 끊기", AllIcons.Actions.Suspend, this::selectedIsOpen, () -> disconnect(selectedConfig())));
    bar.add(new Act("편집", AllIcons.Actions.Edit, () -> selectedConfig() != null, () -> edit(selectedConfig())));
    bar.add(new Act("삭제", AllIcons.General.Remove, () -> selectedConfig() != null, () -> delete(selectedConfig())));
    bar.addSeparator();
    bar.add(new Act("찾기", AllIcons.Actions.Search, () -> selectedConfig() != null, () -> openSearch(selectedConfig())));
    DefaultActionGroup more = DefaultActionGroup.createPopupGroup(() -> "더 보기");
    more.getTemplatePresentation().setIcon(AllIcons.Actions.More);
    more.add(new Act("접속 내보내기", AllIcons.ToolbarDecorator.Export, () -> !DbSettings.getInstance().connections().isEmpty(), this::exportConnections));
    more.add(new Act("접속 가져오기", AllIcons.ToolbarDecorator.Import, () -> true, this::importConnections));
    bar.add(more);
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(PLACE, bar, true);
    toolbar.setTargetComponent(this);
    setToolbar(toolbar.getComponent());

    JPanel top = new JPanel(new BorderLayout());
    top.setBorder(JBUI.Borders.empty(6));
    top.add(search, BorderLayout.CENTER);
    JBLabel hint = new JBLabel("<html>표 더블클릭 → 데이터 보기<br>우클릭 → SELECT · INSERT · UPDATE 문 만들기<br>도구줄 찾기 → 스키마 전체에서 표 · 칼럼 찾기</html>",
                               UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
    hint.setBorder(JBUI.Borders.empty(6, 8));
    JPanel body = new JPanel(new BorderLayout());
    body.add(top, BorderLayout.NORTH);
    body.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER);
    body.add(hint, BorderLayout.SOUTH);
    setContent(body);

    DbSessions.get(project).addListener(parent, () -> ApplicationManager.getApplication().invokeLater(tree::repaint, project.getDisposed()));
    rebuild();
  }

  // ---- 나무 만들기 · 읽기 ----

  private void rebuild() {
    root.removeAllChildren();
    for (DbConfig c : DbSettings.getInstance().connections()) {
      DefaultMutableTreeNode conn = new DefaultMutableTreeNode(new Conn(c));
      conn.add(schemaNode(c));
      root.add(conn);
    }
    model.reload();
  }

  private static DefaultMutableTreeNode schemaNode(DbConfig c) {
    DefaultMutableTreeNode s = new DefaultMutableTreeNode(new Schema(c, Meta.schemaOf(c)));
    s.add(new DefaultMutableTreeNode(LOADING));
    return s;
  }

  private void loadIfNeeded(DefaultMutableTreeNode node) {
    if (node.getChildCount() != 1 || node(node.getChildAt(0)).getUserObject() != LOADING || !loading.add(node)) return;
    Object o = node.getUserObject();
    if (o instanceof Schema s) {
      DbSessions sessions = DbSessions.get(project);
      background(node, s.c(), () -> {
        Connection conn = sessions.connection(s.c());
        List<Meta.Table> tables = Meta.tables(conn, s.c(), s.name(), TABLE_TYPES);
        List<Meta.Table> views = Meta.tables(conn, s.c(), s.name(), VIEW_TYPES);
        return () -> fillSchema(node, s, tables, views);
      });
    } else if (o instanceof TableItem t) {
      withColumns(node, t, cols -> {
        loading.remove(node); // 이미 읽어 둔 칼럼이면 background 를 거치지 않는다.
        fillTable(node, t);
      });
    }
  }

  /** 백그라운드 일. 성공하면 돌려받은 일을 화면 스레드에서, 실패하면 오류 마디 하나 + 알림. */
  private interface Job { Runnable call() throws Exception; }

  private void background(DefaultMutableTreeNode node, DbConfig c, Job job) {
    metaQueue.execute(() -> {
      Runnable done;
      try {
        done = job.call();
      } catch (Exception e) {
        done = () -> showError(node, c, e);
      }
      Runnable finish = done;
      ApplicationManager.getApplication().invokeLater(() -> {
        loading.remove(node);
        finish.run();
      }, project.getDisposed());
    });
  }

  private void withColumns(DefaultMutableTreeNode node, TableItem t, Consumer<List<Meta.Column>> then) {
    if (t.columns != null) {
      then.accept(t.columns);
      return;
    }
    DbSessions sessions = DbSessions.get(project);
    background(node, t.c, () -> {
      List<Meta.Column> cols = Meta.columns(sessions.connection(t.c), t.c, t.schema, t.table.name());
      return () -> {
        t.columns = cols;
        then.accept(cols);
      };
    });
  }

  private void showError(DefaultMutableTreeNode node, DbConfig c, Exception e) {
    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    Notify.error(project, c.name + " 목록을 읽지 못했습니다: " + msg);
    if (!attached(node)) return;
    node.removeAllChildren();
    node.add(new DefaultMutableTreeNode(new Message("읽지 못함: " + msg, true)));
    model.nodeStructureChanged(node);
  }

  /** 읽는 사이 새로고침 · 검색으로 마디가 바뀌었으면 false(그 마디는 고치지 않는다). */
  private boolean attached(DefaultMutableTreeNode node) {
    return node.getRoot() == root;
  }

  private void fillSchema(DefaultMutableTreeNode node, Schema s, List<Meta.Table> tables, List<Meta.Table> views) {
    if (!attached(node)) return;
    node.removeAllChildren();
    node.add(new DefaultMutableTreeNode(new Group("표", items(s, tables))));
    node.add(new DefaultMutableTreeNode(new Group("뷰", items(s, views))));
    String q = query();
    for (int i = 0; i < node.getChildCount(); i++) fillGroup(node(node.getChildAt(i)), q);
    model.nodeStructureChanged(node);
  }

  private static List<TableItem> items(Schema s, List<Meta.Table> tables) {
    List<TableItem> out = new ArrayList<>(tables.size());
    for (Meta.Table t : tables) out.add(new TableItem(s.c(), s.name(), t));
    return out;
  }

  private void fillTable(DefaultMutableTreeNode node, TableItem t) {
    if (!attached(node)) return;
    node.removeAllChildren();
    for (Meta.Column col : visibleColumns(t, query())) node.add(new DefaultMutableTreeNode(col));
    model.nodeStructureChanged(node);
  }

  // ---- 검색 ----

  private String query() {
    return search.getText().trim().toUpperCase(Locale.ROOT);
  }

  private static boolean hit(String name, String comment, String q) {
    return name.toUpperCase(Locale.ROOT).contains(q) || comment.toUpperCase(Locale.ROOT).contains(q);
  }

  private static boolean tableHit(TableItem t, String q) {
    return q.isEmpty() || hit(t.table.name(), t.table.comment(), q);
  }

  /** 표 자체가 맞으면 모든 칼럼, 아니면 맞는 칼럼만. 칼럼을 아직 안 읽었으면 빈 목록. */
  private static List<Meta.Column> visibleColumns(TableItem t, String q) {
    if (t.columns == null || tableHit(t, q)) return t.columns == null ? List.of() : t.columns;
    List<Meta.Column> out = new ArrayList<>();
    for (Meta.Column col : t.columns) {
      if (hit(col.name(), col.comment(), q)) out.add(col);
    }
    return out;
  }

  /** 묶음의 표 마디를 검색어로 다시 만든다. 칼럼이 맞아서 남은 표는 펼칠 목록에 넣는다. */
  private List<DefaultMutableTreeNode> fillGroup(DefaultMutableTreeNode groupNode, String q) {
    List<DefaultMutableTreeNode> expand = new ArrayList<>();
    groupNode.removeAllChildren();
    for (TableItem t : ((Group) groupNode.getUserObject()).items()) {
      boolean self = tableHit(t, q);
      List<Meta.Column> cols = visibleColumns(t, q);
      if (!self && cols.isEmpty()) continue;
      DefaultMutableTreeNode tn = new DefaultMutableTreeNode(t);
      if (t.columns == null) {
        tn.add(new DefaultMutableTreeNode(LOADING));
      } else {
        for (Meta.Column col : cols) tn.add(new DefaultMutableTreeNode(col));
      }
      groupNode.add(tn);
      if (!self) expand.add(tn);
    }
    return expand;
  }

  private void applyFilter() {
    String q = query();
    for (int i = 0; i < root.getChildCount(); i++) {
      DefaultMutableTreeNode conn = node(root.getChildAt(i));
      for (int j = 0; j < conn.getChildCount(); j++) {
        DefaultMutableTreeNode schema = node(conn.getChildAt(j));
        if (!(schema.getUserObject() instanceof Schema)) continue;
        for (int k = 0; k < schema.getChildCount(); k++) {
          DefaultMutableTreeNode group = node(schema.getChildAt(k));
          if (!(group.getUserObject() instanceof Group)) continue;
          List<DefaultMutableTreeNode> expand = fillGroup(group, q);
          model.nodeStructureChanged(group);
          if (!q.isEmpty()) tree.expandPath(new TreePath(group.getPath()));
          for (DefaultMutableTreeNode tn : expand) tree.expandPath(new TreePath(tn.getPath()));
        }
      }
    }
  }

  // ---- 선택 ----

  private static DefaultMutableTreeNode node(Object o) {
    return (DefaultMutableTreeNode) (o instanceof TreePath p ? p.getLastPathComponent() : o);
  }

  private static DbConfig configOf(DefaultMutableTreeNode n) {
    for (TreeNode t = n; t != null; t = t.getParent()) {
      if (node(t).getUserObject() instanceof Conn c) return c.c();
    }
    return null;
  }

  private DbConfig selectedConfig() {
    TreePath p = tree.getSelectionPath();
    return p == null ? null : configOf(node(p));
  }

  private boolean selectedIsOpen() {
    DbConfig c = selectedConfig();
    return c != null && DbSessions.get(project).isOpen(c.id);
  }

  /** 고른 표 중 기준 표와 같은 접속 · 스키마인 것의 이름(기준 표 포함). */
  private List<String> selectedTables(TableItem base) {
    List<String> out = new ArrayList<>();
    TreePath[] paths = tree.getSelectionPaths();
    if (paths != null) {
      for (TreePath p : paths) {
        if (node(p).getUserObject() instanceof TableItem t && t.c == base.c && t.schema.equals(base.schema)) out.add(t.table.name());
      }
    }
    if (!out.contains(base.table.name())) out.add(base.table.name());
    return out;
  }

  // ---- 메뉴 ----

  private ActionGroup menuFor(DefaultMutableTreeNode n) {
    DefaultActionGroup g = new DefaultActionGroup();
    Object o = n.getUserObject();
    if (o instanceof Conn conn) {
      DbConfig c = conn.c();
      if (DbSessions.get(project).isOpen(c.id)) {
        g.add(new Act("끊기", AllIcons.Actions.Suspend, () -> true, () -> disconnect(c)));
      } else {
        g.add(new Act("연결", AllIcons.Actions.Execute, () -> true, () -> connect(c)));
      }
      g.add(new Act("새 콘솔", AllIcons.Nodes.Console, () -> true, () -> ConsoleOpener.openNew(project, c)));
      g.addSeparator();
      g.add(new Act("편집", AllIcons.Actions.Edit, () -> true, () -> edit(c)));
      g.add(new Act("삭제", AllIcons.General.Remove, () -> true, () -> delete(c)));
      return g;
    }
    if (o instanceof Schema s) {
      g.add(new Act("ERD 열기 (전체)", null, () -> true, () -> ErdView.open(project, s.c(), s.name(), null)));
      return g;
    }
    if (o instanceof TableItem t) {
      g.add(new Act("데이터 보기", AllIcons.Actions.Execute, () -> true, () -> viewData(t)));
      List<String> picked = selectedTables(t);
      if (picked.size() > 1) {
        g.add(new Act("SELECT 문 만들기 (표 " + picked.size() + "개 조인)", null, () -> true, () -> joinSelect(n, t, picked)));
      } else {
        g.add(new Act("SELECT 문 만들기", null, () -> true, () -> withColumns(n, t,
            cols -> ConsoleOpener.open(project, t.c, SelectSql.build(t.schema, t.table.name(), cols)))));
      }
      g.add(new Act("INSERT 문 만들기", null, () -> true, () -> withColumns(n, t,
          cols -> ConsoleOpener.open(project, t.c, SelectSql.insert(t.schema, t.table.name(), cols)))));
      g.add(new Act("UPDATE 문 만들기", null, () -> true, () -> withColumns(n, t,
          cols -> ConsoleOpener.open(project, t.c, SelectSql.update(t.schema, t.table.name(), cols)))));
      g.add(new Act("매퍼 뼈대 만들기", null, () -> true, () -> MapperDialog.show(project, t.c, t.schema, t.table.name())));
      g.addSeparator();
      g.add(new Act("ERD 열기", null, () -> true, () -> ErdView.open(project, t.c, t.schema, picked)));
      return g;
    }
    return null;
  }

  /** 고른 표들의 칼럼 · FK 를 뒤에서 읽어 조인 SELECT 를 콘솔에 연다. */
  private void joinSelect(DefaultMutableTreeNode node, TableItem base, List<String> tables) {
    DbSessions sessions = DbSessions.get(project);
    background(node, base.c, () -> {
      Connection conn = sessions.connection(base.c);
      Map<String, List<Meta.Column>> columns = new HashMap<>();
      List<Meta.ForeignKey> fks = new ArrayList<>();
      for (String table : tables) {
        columns.put(table, Meta.columns(conn, base.c, base.schema, table));
        fks.addAll(Meta.importedKeys(conn, base.c, base.schema, table));
      }
      String sql = SelectSql.join(base.schema, tables, columns, fks);
      return () -> ConsoleOpener.open(project, base.c, sql);
    });
  }

  private void viewData(TableItem t) {
    SqlRunner.run(project, t.c, "SELECT * FROM " + t.schema + "." + t.table.name(), List.of());
  }

  // ---- 표 · 칼럼 찾기 ----

  private void openSearch(DbConfig c) {
    String schema = Meta.schemaOf(c);
    ColumnSearch.show(project, c, schema, hit -> {
      if (!reveal(c, hit.table())) openSelect(c, schema, hit.table());
    });
  }

  /** 이미 읽어 둔 스키마에 그 표 마디가 보이면 고르고 펼친다. 없으면(안 읽음 · 검색으로 숨김) false. */
  private boolean reveal(DbConfig c, String table) {
    for (int i = 0; i < root.getChildCount(); i++) {
      DefaultMutableTreeNode conn = node(root.getChildAt(i));
      if (((Conn) conn.getUserObject()).c() != c) continue;
      for (int j = 0; j < conn.getChildCount(); j++) {
        DefaultMutableTreeNode schema = node(conn.getChildAt(j));
        for (int k = 0; k < schema.getChildCount(); k++) {
          DefaultMutableTreeNode group = node(schema.getChildAt(k));
          for (int m = 0; m < group.getChildCount(); m++) {
            DefaultMutableTreeNode tn = node(group.getChildAt(m));
            if (tn.getUserObject() instanceof TableItem t && t.table.name().equals(table)) {
              TreePath path = new TreePath(tn.getPath());
              tree.setSelectionPath(path);
              tree.expandPath(path);
              tree.scrollPathToVisible(path);
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private void openSelect(DbConfig c, String schema, String table) {
    DbSessions sessions = DbSessions.get(project);
    // 나무에 없는 표라 붙어 있지 않은 마디를 넘긴다: 실패하면 알림만 하고 나무는 건드리지 않는다.
    background(new DefaultMutableTreeNode(), c, () -> {
      List<Meta.Column> cols = Meta.columns(sessions.connection(c), c, schema, table);
      String sql = SelectSql.build(schema, table, cols);
      return () -> ConsoleOpener.open(project, c, sql);
    });
  }

  // ---- 접속 내보내기 · 가져오기 ----

  private static final String EXPORT_FILE = "ei-db-connections.json";
  private static final String JSON = "json";

  /** 모든 접속을 JSON 파일 하나로. 비밀번호는 넣지 않는다. */
  private void exportConnections() {
    FileSaverDescriptor desc = new FileSaverDescriptor("접속 내보내기", "비밀번호는 파일에 넣지 않습니다.", JSON);
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(desc, project).save(EXPORT_FILE);
    if (target == null) return;
    Path path = target.getFile().toPath();
    String json = ConnectionsJson.write(DbSettings.getInstance().connections());
    int count = DbSettings.getInstance().connections().size();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Files.writeString(path, json, StandardCharsets.UTF_8);
        Notify.info(project, "접속 " + count + "개를 내보냈습니다: " + path);
      } catch (IOException e) {
        Notify.error(project, "접속을 내보내지 못했습니다: " + e.getMessage());
      }
    });
  }

  /** 파일의 접속을 더한다(기존 접속은 바꾸지 않는다). 같은 이름은 「이름 (가져옴)」. */
  private void importConnections() {
    VirtualFile file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor(JSON).withTitle("접속 가져오기"), project, null);
    if (file == null) return;
    Path path = file.toNioPath();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<DbConfig> read;
      try {
        read = ConnectionsJson.read(Files.readString(path, StandardCharsets.UTF_8));
      } catch (IOException | IllegalArgumentException e) {
        Notify.error(project, "접속을 가져오지 못했습니다: " + e.getMessage());
        return;
      }
      ApplicationManager.getApplication().invokeLater(() -> addImported(read), project.getDisposed());
    });
  }

  private void addImported(List<DbConfig> read) {
    DbSettings settings = DbSettings.getInstance();
    Set<String> names = new HashSet<>();
    for (DbConfig c : settings.connections()) names.add(c.name);
    for (DbConfig c : read) {
      c.name = ConnectionsJson.uniqueName(c.name, names);
      names.add(c.name);
      settings.put(c);
    }
    rebuild();
    Notify.info(project, "접속 " + read.size() + "개를 가져왔습니다. 비밀번호는 접속 편집에서 넣으세요.");
  }

  // ---- 접속 다루기 ----

  private void addConnection() {
    if (ConnectDialog.edit(project, null) != null) rebuild();
  }

  private void edit(DbConfig c) {
    if (ConnectDialog.edit(project, c) != null) rebuild();
  }

  private void connect(DbConfig c) {
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        sessions.connection(c);
      } catch (Exception e) {
        Notify.error(project, c.name + " 에 연결하지 못했습니다: " + e.getMessage());
      }
    });
  }

  /** 미반영 변경이 있으면 롤백된다고 먼저 묻는다. 끊기로 했으면 true. */
  private boolean confirmRollback(DbConfig c, String title) {
    int pending = DbSessions.get(project).pending(c.id);
    return pending == 0
           || Messages.showYesNoDialog(project, "미반영 변경 " + pending + "건이 롤백됩니다. 계속할까요?", title, Messages.getWarningIcon()) == Messages.YES;
  }

  private void closeInBackground(DbConfig c) {
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> sessions.close(c.id));
  }

  private void disconnect(DbConfig c) {
    if (c == null || !confirmRollback(c, "접속 끊기")) return;
    closeInBackground(c);
  }

  private void delete(DbConfig c) {
    if (Messages.showYesNoDialog(project, c.name + " 접속을 삭제할까요?", "접속 삭제", Messages.getWarningIcon()) != Messages.YES) return;
    if (!confirmRollback(c, "접속 삭제")) return;
    DbSettings.getInstance().remove(c.id);
    Passwords.set(c, null);
    closeInBackground(c);
    rebuild();
  }

  /** 고른 접속만(없으면 전부) 다시 읽는다. 칼럼 주석 캐시도 비운다. */
  private void refreshSelected() {
    DbConfig c = selectedConfig();
    if (c == null) {
      for (DbConfig each : DbSettings.getInstance().connections()) Comments.clear(each.id);
      rebuild();
      return;
    }
    Comments.clear(c.id);
    for (int i = 0; i < root.getChildCount(); i++) {
      DefaultMutableTreeNode conn = node(root.getChildAt(i));
      if (((Conn) conn.getUserObject()).c() == c) {
        conn.removeAllChildren();
        conn.add(schemaNode(c));
        model.nodeStructureChanged(conn);
      }
    }
  }

  // ---- 그리기 ----

  private final class Renderer extends ColoredTreeCellRenderer {
    @Override
    public void customizeCellRenderer(@NotNull JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
      DefaultMutableTreeNode n = node(value);
      Object o = n.getUserObject();
      if (o instanceof Conn conn) {
        DbConfig c = conn.c();
        setIcon(new Dot(DbSessions.get(project).isOpen(c.id) ? CONNECTED : JBColor.GRAY));
        append(c.name, c.production ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DANGER) : SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        if (c.production) append(" 운영", new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, DANGER));
        append("  " + (c.readOnly ? "읽기 전용" : c.host + ":" + c.port), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof Schema s) {
        setIcon(AllIcons.Nodes.DataSchema);
        append(s.name());
        append("  기본 스키마", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof Group g) {
        append(g.label());
        String count = n.getChildCount() == g.items().size() ? String.valueOf(g.items().size()) : n.getChildCount() + " / " + g.items().size();
        append("  " + count, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof TableItem ti) {
        setIcon(AllIcons.Nodes.DataTables);
        append(ti.table.name());
        if (!ti.table.comment().isEmpty()) append("  " + ti.table.comment(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      } else if (o instanceof Meta.Column col) {
        setIcon(AllIcons.Nodes.DataColumn);
        if (col.pk() || col.fk()) append(col.pk() ? "PK " : "FK ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER | SimpleTextAttributes.STYLE_BOLD, KEY_TAG));
        append(col.name());
        if (!col.comment().isEmpty()) append("  " + col.comment(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append("  " + col.typeLabel(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof Message m) {
        append(m.text(), m.error() ? SimpleTextAttributes.ERROR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
      }
    }
  }

  /** 접속 상태 점. */
  private record Dot(Color color) implements Icon {
    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        int size = JBUI.scale(DOT_SIZE);
        g2.fillOval(x + (getIconWidth() - size) / 2, y + (getIconHeight() - size) / 2, size, size);
      } finally {
        g2.dispose();
      }
    }

    @Override public int getIconWidth() { return JBUI.scale(DOT_BOX); }
    @Override public int getIconHeight() { return JBUI.scale(DOT_BOX); }
  }

  /** 켜짐 조건이 있는 단순 동작. 조건은 선택 상태만 보므로 화면 스레드에서 판정한다. */
  private static final class Act extends DumbAwareAction {
    private final BooleanSupplier enabled;
    private final Runnable run;

    Act(String text, Icon icon, BooleanSupplier enabled, Runnable run) {
      super(text, null, icon);
      this.enabled = enabled;
      this.run = run;
    }

    @Override public void actionPerformed(@NotNull AnActionEvent e) { run.run(); }
    @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(enabled.getAsBoolean()); }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
  }
}
