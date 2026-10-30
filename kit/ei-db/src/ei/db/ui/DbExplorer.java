package ei.db.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ide.CopyProvider;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
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
import com.intellij.ui.content.Content;
import com.intellij.ui.tabs.JBTabsEx;
import com.intellij.ui.tabs.JBTabsFactory;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.TabsListener;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import ei.db.Comments;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.Passwords;
import ei.db.SqlRunner;
import ei.db.console.Badge;
import ei.db.console.ConsoleOpener;
import ei.db.console.KindIcon;
import ei.db.console.XlsxExport;
import ei.db.gen.ErdView;
import ei.db.gen.FeatureDialog;
import ei.db.gen.MapperDialog;
import ei.db.gen.Names;
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
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
 * 「데이터베이스」 탐색기: 맨 위 접속 탭(한 번에 한 접속) → 기본 스키마 → 표 · 뷰 · 프로그램 묶음 → 표 → 칼럼.
 * 접속마다 나무 뿌리(Conn)를 따로 두고 고른 탭의 뿌리만 모델에 올린다. 스키마와 표의 자식은 펼칠 때 백그라운드에서 읽는다.
 * 메타 조회는 한 줄로 세워 같은 접속을 두 번 열지 않게 한다. 검색은 이미 읽어 둔 표 · 칼럼만 거른다(읽지 않은 스키마는 그대로 둔다).
 */
final class DbExplorer extends SimpleToolWindowPanel {

  private static final String PLACE = "EiDatabaseExplorer";
  private static final String[] TABLE_TYPES = {"TABLE"};
  private static final String[] VIEW_TYPES = {"VIEW"};
  /** 아직 안 읽은 마디의 자리 자식. 읽는 동안은 검색 줄 아래 진행 막대가 알리므로 글은 비운다. */
  private static final Message LOADING = new Message("", false);
  private static final Color CONNECTED = JBColor.namedColor("EiDatabase.connected", new JBColor(0x3E9B47, 0x5FB865));
  private static final Color DANGER = JBColor.namedColor("EiDatabase.production", new JBColor(0xD9363E, 0xF75464));
  /** 이름 · 주석 · 주소 사이 간격 글. */
  private static final String SPACE = "  ";
  private static final String DOT = " · ";
  /** 칼럼 줄의 PK · FK 꼬리표 칸 최소 폭(px, 배율 전). 키가 아닌 칼럼도 같은 폭을 비워 이름 줄을 맞춘다. */
  private static final int KEY_TAG_WIDTH = 24;
  /** 찾기 칸 입력이 이만큼 멈춘 뒤에 나무를 다시 거른다(글자마다 전체를 다시 그리지 않게). */
  private static final int FILTER_DELAY_MS = 200;
  /** 찾기 줄 여백(px, 배율 전). */
  private static final int SEARCH_INSET = 4;

  private record Conn(DbConfig c) {}
  private record Schema(DbConfig c, String name) {}
  private record Message(String text, boolean error) {}
  /** Oracle 스키마 아래 「프로그램」 묶음(패키지 · 프로시저 · 함수). 펼칠 때 읽는다. */
  private record ProgramGroup(DbConfig c, String schema) {}
  private record Program(DbConfig c, String schema, ProgramSql.Item item) {}

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
  /** 접속이 하나도 없을 때의 빈 뿌리(빈 나무 안내가 보인다). */
  private final DefaultMutableTreeNode empty = new DefaultMutableTreeNode();
  private final DefaultTreeModel model = new DefaultTreeModel(empty);
  private final Tree tree = new Tree(model);
  /** 접속 id → 그 접속의 나무 뿌리(Conn 마디). 고른 탭의 뿌리만 model 에 올린다. */
  private final Map<String, DefaultMutableTreeNode> conns = new LinkedHashMap<>();
  /** 접속 탭. 탭의 object 는 DbConfig. */
  private final JBTabsEx tabs;
  private final SearchTextField search = new SearchTextField(false);
  private final Alarm filterAlarm;
  private final Set<DefaultMutableTreeNode> loading = Collections.newSetFromMap(new IdentityHashMap<>());
  /** loading 이 비어 있지 않은 동안 검색 줄 아래에 보이는 진행 막대. */
  private final Gauge gauge = Gauge.create(EiDbBundle.message("explorer.loading"));
  private final ExecutorService metaQueue = AppExecutorUtil.createBoundedApplicationPoolExecutor("EI Database 탐색기", 1);
  /** 창 머리에 「접속 N / M」을 보일 내용 탭. attach 전에는 null. */
  private Content content;

  DbExplorer(Project project, Disposable parent) {
    super(true, true);
    this.project = project;

    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setToggleClickCount(0); // 더블클릭은 아래 DoubleClickListener 가 맡는다(표는 실행, 나머지는 펼치기).
    tree.setCellRenderer(new Renderer());
    // 접속이 하나도 없으면 빈 나무 대신 안내와 「접속 추가」 링크를 보인다.
    tree.getEmptyText().setText(EiDbBundle.message("explorer.empty"))
        .appendLine(EiDbBundle.message("connect.title.add"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, e -> addConnection());
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
        } else if (node(path).getUserObject() instanceof Program p) {
          openSource(p);
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

    // 접속 탭: 글자 앞 상태 점(연결 초록 · 끊김 회색), 운영 접속은 빨간 글자. 우클릭에 접속 메뉴.
    tabs = (JBTabsEx) JBTabsFactory.createTabs(project, parent);
    tabs.setPopupGroup(connectionMenu(), PLACE, false);
    tabs.addListener(new TabsListener() {
      @Override
      public void selectionChanged(TabInfo oldSelection, TabInfo newSelection) {
        showConnection(newSelection == null ? null : (DbConfig) newSelection.getObject());
      }
    });

    // 찾기 칸: 글자는 읽어 둔 표 · 칼럼을 거르고, Enter 는 스키마 전체 찾기 창을 연다.
    search.getTextEditor().getEmptyText().setText(EiDbBundle.message("explorer.search.hint"));
    filterAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent);
    search.addDocumentListener(new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) {
        filterAlarm.cancelAllRequests();
        filterAlarm.addRequest(DbExplorer.this::applyFilter, FILTER_DELAY_MS);
      }
    });
    search.getTextEditor().addActionListener(e -> {
      if (selectedConfig() != null) openSearch(selectedConfig());
    });

    // 창 위 도구줄(1065 전 배치). 묶음: 추가 · 새로고침 │ 콘솔 · 찾기 │ 끊기 · 편집 · 삭제 │ 더 보기(내보내기 · 가져오기).
    DefaultActionGroup bar = new DefaultActionGroup();
    bar.add(new Act(EiDbBundle.message("connect.title.add"), AllIcons.General.Add, () -> true, this::addConnection));
    bar.add(new Act(EiDbBundle.message("explorer.refresh"), AllIcons.Actions.Refresh, () -> true, this::refreshSelected));
    bar.addSeparator();
    bar.add(new Act(EiDbBundle.message("explorer.new.console"), AllIcons.Nodes.Console, () -> selectedConfig() != null, () -> ConsoleOpener.openNew(project, selectedConfig())));
    bar.add(new Act(EiDbBundle.message("explorer.find"), AllIcons.Actions.Search, () -> selectedConfig() != null, () -> openSearch(selectedConfig())));
    bar.addSeparator();
    bar.add(new Act(EiDbBundle.message("explorer.disconnect"), AllIcons.Actions.Suspend, this::selectedIsOpen, () -> disconnect(selectedConfig())));
    bar.add(new Act(EiDbBundle.message("explorer.edit"), AllIcons.Actions.Edit, () -> selectedConfig() != null, () -> edit(selectedConfig())));
    bar.add(new Act(EiDbBundle.message("explorer.delete"), AllIcons.General.Remove, () -> selectedConfig() != null, () -> delete(selectedConfig())));
    bar.addSeparator();
    DefaultActionGroup more = DefaultActionGroup.createPopupGroup(() -> EiDbBundle.message("explorer.more"));
    more.getTemplatePresentation().setIcon(AllIcons.Actions.More);
    more.add(new Act(EiDbBundle.message("explorer.export"), AllIcons.ToolbarDecorator.Export, () -> !DbSettings.getInstance().connections().isEmpty(), this::exportConnections));
    more.add(new Act(EiDbBundle.message("explorer.import"), AllIcons.ToolbarDecorator.Import, () -> true, this::importConnections));
    bar.add(more);
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(PLACE, bar, true);
    toolbar.setTargetComponent(this);
    JPanel head = new JPanel(new BorderLayout());
    head.add(toolbar.getComponent(), BorderLayout.CENTER);
    head.add(gauge.line(), BorderLayout.SOUTH);
    setToolbar(head);

    JPanel searchRow = new JPanel(new BorderLayout());
    searchRow.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(SEARCH_INSET)));
    searchRow.add(search, BorderLayout.CENTER);
    JPanel top = new JPanel(new BorderLayout());
    top.add(tabs.getComponent(), BorderLayout.NORTH);
    top.add(searchRow, BorderLayout.CENTER);
    // 막대가 숨어 있으면 GridBag 이 자리를 잡지 않아 나무가 검색 줄에 붙는다.
    JPanel gaugeRow = new JPanel(new GridBagLayout());
    gaugeRow.add(gauge, new GridBagConstraints(0, 0, 1, 1, 1, 0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL,
        JBUI.insets(SEARCH_INSET), 0, 0));
    top.add(gaugeRow, BorderLayout.SOUTH);
    JPanel body = new JPanel(new BorderLayout());
    body.add(top, BorderLayout.NORTH);
    body.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER);
    setContent(body);

    DbSessions.get(project).addListener(parent, () -> ApplicationManager.getApplication().invokeLater(() -> {
      tree.repaint();
      updateTabs();
      updateTitle();
    }, project.getDisposed()));
    rebuild();
  }

  /** 창 머리에 「접속 N / M」(열린 접속 / 전체 접속)을 보일 내용 탭을 잇는다. */
  void attach(Content content) {
    this.content = content;
    updateTitle();
  }

  private void updateTitle() {
    if (content == null) return;
    List<DbConfig> all = DbSettings.getInstance().connections();
    DbSessions sessions = DbSessions.get(project);
    int open = 0;
    for (DbConfig c : all) {
      if (sessions.isOpen(c.id)) open++;
    }
    content.setDisplayName(all.isEmpty() ? "" : EiDbBundle.message("explorer.title.count", open, all.size()));
  }

  // ---- 접속 탭 ----

  /** 접속마다 탭과 나무 뿌리를 새로 만든다. 고르고 있던 접속이 남아 있으면 그대로 고르고, 없으면 첫 접속. */
  private void rebuild() {
    DbConfig before = selectedConfig();
    String keep = before == null ? null : before.id;
    conns.clear();
    tabs.removeAllTabs();
    TabInfo select = null;
    for (DbConfig c : DbSettings.getInstance().connections()) {
      DefaultMutableTreeNode conn = new DefaultMutableTreeNode(new Conn(c));
      conn.add(schemaNode(c));
      conns.put(c.id, conn);
      TabInfo info = new TabInfo(new JPanel()).setObject(c);
      tabs.addTab(info);
      if (select == null || c.id.equals(keep)) select = info;
    }
    updateTabs();
    if (select != null) tabs.select(select, false);
    showConnection(select == null ? null : (DbConfig) select.getObject());
    updateTitle();
  }

  /** 탭 글자 · 상태 점 · 운영 색 · 툴팁(주소 · 운영 · 읽기 전용)을 접속 상태에 맞춘다. */
  private void updateTabs() {
    DbSessions sessions = DbSessions.get(project);
    for (TabInfo info : tabs.getTabs()) {
      DbConfig c = (DbConfig) info.getObject();
      info.setText(c.name);
      info.setIcon(new KindIcon(c.kind, sessions.isOpen(c.id) ? CONNECTED : JBColor.GRAY, null));
      info.setDefaultForeground(c.production ? DANGER : null);
      String tip = c.host + ":" + c.port
                   + (c.production ? DOT + EiDbBundle.message("explorer.production") : "")
                   + (c.readOnly ? DOT + EiDbBundle.message("explorer.read.only") : "");
      info.setTooltipText(tip);
    }
  }

  /** 그 접속의 뿌리를 나무에 올린다(null 이면 빈 나무). 찾는 글이 있으면 그 뿌리에 다시 거른다. */
  private void showConnection(DbConfig c) {
    DefaultMutableTreeNode root = c == null ? empty : conns.get(c.id);
    if (root == null) root = empty;
    if (model.getRoot() != root) model.setRoot(root);
    if (!query().isEmpty()) applyFilter();
  }

  /** 탭 우클릭 메뉴. 대상은 우클릭한 탭(없으면 고른 탭)의 접속이다. */
  private ActionGroup connectionMenu() {
    DefaultActionGroup g = new DefaultActionGroup();
    g.add(new Act(EiDbBundle.message("explorer.connect"), AllIcons.Actions.Execute, () -> !targetIsOpen(), () -> connect(targetConfig())));
    g.add(new Act(EiDbBundle.message("explorer.disconnect.short"), AllIcons.Actions.Suspend, this::targetIsOpen, () -> disconnect(targetConfig())));
    g.add(new Act(EiDbBundle.message("explorer.new.console"), AllIcons.Nodes.Console, () -> true, () -> ConsoleOpener.openNew(project, targetConfig())));
    g.add(new Act(EiDbBundle.message("explorer.sessions"), null, () -> true, () -> SqlRunner.run(project, targetConfig(), SessionSql.sessions(targetConfig().kind), List.of())));
    g.add(new Act(EiDbBundle.message("explorer.locks"), null, () -> true, () -> SqlRunner.run(project, targetConfig(), SessionSql.locks(targetConfig().kind), List.of())));
    g.addSeparator();
    g.add(new Act(EiDbBundle.message("connect.title.add"), AllIcons.General.Add, () -> true, this::addConnection));
    g.add(new Act(EiDbBundle.message("explorer.edit"), AllIcons.Actions.Edit, () -> true, () -> edit(targetConfig())));
    g.add(new Act(EiDbBundle.message("explorer.delete"), AllIcons.General.Remove, () -> true, () -> delete(targetConfig())));
    return g;
  }

  /** 우클릭한 탭의 접속. 우클릭이 아니면 고른 탭. 탭이 없으면 null. */
  private DbConfig targetConfig() {
    TabInfo info = tabs.getTargetInfo();
    return info == null ? null : (DbConfig) info.getObject();
  }

  private boolean targetIsOpen() {
    DbConfig c = targetConfig();
    return c != null && DbSessions.get(project).isOpen(c.id);
  }

  // ---- 나무 만들기 · 읽기 ----

  private static DefaultMutableTreeNode schemaNode(DbConfig c) {
    DefaultMutableTreeNode s = new DefaultMutableTreeNode(new Schema(c, Meta.schemaOf(c)));
    s.add(new DefaultMutableTreeNode(LOADING));
    return s;
  }

  private void loadIfNeeded(DefaultMutableTreeNode node) {
    if (node.getChildCount() != 1 || node(node.getChildAt(0)).getUserObject() != LOADING || !loading.add(node)) return;
    showGauge();
    Object o = node.getUserObject();
    if (o instanceof Schema s) {
      DbSessions sessions = DbSessions.get(project);
      background(node, s.c(), () -> {
        Connection conn = sessions.connection(s.c());
        List<Meta.Table> tables = Meta.tables(conn, s.c(), s.name(), TABLE_TYPES);
        List<Meta.Table> views = Meta.tables(conn, s.c(), s.name(), VIEW_TYPES);
        return () -> fillSchema(node, s, tables, views);
      });
    } else if (o instanceof ProgramGroup pg) {
      DbSessions sessions = DbSessions.get(project);
      background(node, pg.c(), () -> {
        List<ProgramSql.Item> items = Programs.list(sessions.connection(pg.c()), pg.schema());
        return () -> fillPrograms(node, pg, items);
      });
    } else if (o instanceof TableItem t) {
      withColumns(node, t, cols -> {
        loading.remove(node); // 이미 읽어 둔 칼럼이면 background 를 거치지 않는다.
        showGauge();
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
        showGauge();
        finish.run();
      }, project.getDisposed());
    });
  }

  private void showGauge() {
    gauge.setVisible(!loading.isEmpty());
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
    Notify.error(project, EiDbBundle.message("explorer.list.failed", c.name, msg));
    if (!attached(node)) return;
    node.removeAllChildren();
    node.add(new DefaultMutableTreeNode(new Message(EiDbBundle.message("explorer.node.failed", msg), true)));
    model.nodeStructureChanged(node);
  }

  /** 읽는 사이 새로고침 · 탭 바꾸기로 마디가 보이는 나무에서 빠졌으면 false(그 마디는 고치지 않고, 다시 펼칠 때 다시 읽는다). */
  private boolean attached(DefaultMutableTreeNode node) {
    return node.getRoot() == model.getRoot();
  }

  private void fillSchema(DefaultMutableTreeNode node, Schema s, List<Meta.Table> tables, List<Meta.Table> views) {
    if (!attached(node)) return;
    node.removeAllChildren();
    node.add(new DefaultMutableTreeNode(new Group(EiDbBundle.message("explorer.group.tables"), items(s, tables))));
    node.add(new DefaultMutableTreeNode(new Group(EiDbBundle.message("explorer.group.views"), items(s, views))));
    String q = query();
    for (int i = 0; i < node.getChildCount(); i++) fillGroup(node(node.getChildAt(i)), q);
    if (s.c().kind == DbKind.ORACLE) {
      DefaultMutableTreeNode programs = new DefaultMutableTreeNode(new ProgramGroup(s.c(), s.name()));
      programs.add(new DefaultMutableTreeNode(LOADING));
      node.add(programs);
    }
    model.nodeStructureChanged(node);
  }

  private void fillPrograms(DefaultMutableTreeNode node, ProgramGroup pg, List<ProgramSql.Item> items) {
    if (!attached(node)) return;
    node.removeAllChildren();
    for (ProgramSql.Item item : items) node.add(new DefaultMutableTreeNode(new Program(pg.c(), pg.schema(), item)));
    if (items.isEmpty()) node.add(new DefaultMutableTreeNode(new Message(EiDbBundle.message("common.none"), false)));
    model.nodeStructureChanged(node);
  }

  /** 소스를 CREATE OR REPLACE 문으로 콘솔에 붙인다. 고친 뒤 콘솔에서 실행하면 컴파일된다. */
  private void openSource(Program p) {
    DbSessions sessions = DbSessions.get(project);
    background(new DefaultMutableTreeNode(), p.c(), () -> {
      String sql = Programs.source(sessions.connection(p.c()), p.schema(), p.item());
      return () -> {
        if (sql.isEmpty()) {
          Notify.error(project, EiDbBundle.message("explorer.source.failed", p.item().name()));
        } else {
          ConsoleOpener.open(project, p.c(), sql);
        }
      };
    });
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

  /** 보이는 접속의 묶음만 거른다. 숨은 접속은 탭을 고를 때(showConnection) 거른다. */
  private void applyFilter() {
    String q = query();
    DefaultMutableTreeNode conn = node(model.getRoot());
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

  // ---- 선택 ----

  private static DefaultMutableTreeNode node(Object o) {
    return (DefaultMutableTreeNode) (o instanceof TreePath p ? p.getLastPathComponent() : o);
  }

  /** 고른 탭의 접속. 탭이 없으면 null. */
  private DbConfig selectedConfig() {
    TabInfo info = tabs.getSelectedInfo();
    return info == null ? null : (DbConfig) info.getObject();
  }

  private boolean selectedIsOpen() {
    DbConfig c = selectedConfig();
    return c != null && DbSessions.get(project).isOpen(c.id);
  }

  /** 고른 표 · 칼럼의 이름을 한 줄에 하나씩(나무 순서). 표 · 칼럼이 없으면 빈 글. */
  private String selectedNames() {
    StringBuilder out = new StringBuilder();
    TreePath[] paths = tree.getSelectionPaths();
    if (paths != null) {
      for (TreePath p : paths) {
        Object o = node(p).getUserObject();
        String name = o instanceof TableItem t ? t.table.name() : o instanceof Meta.Column col ? col.name() : null;
        if (name == null) continue;
        if (!out.isEmpty()) out.append('\n');
        out.append(name);
      }
    }
    return out.toString();
  }

  private void copyNames() {
    String names = selectedNames();
    if (!names.isEmpty()) CopyPasteManager.copyTextToClipboard(names);
  }

  /** 나무에서 Ctrl+C(IDE 의 Copy)는 고른 표 · 칼럼 이름을 복사한다. */
  @Override
  public void uiDataSnapshot(@NotNull DataSink sink) {
    super.uiDataSnapshot(sink);
    String names = selectedNames();
    if (names.isEmpty()) return;
    sink.set(PlatformDataKeys.COPY_PROVIDER, new CopyProvider() {
      @Override public void performCopy(@NotNull DataContext dataContext) { CopyPasteManager.copyTextToClipboard(names); }
      @Override public boolean isCopyEnabled(@NotNull DataContext dataContext) { return true; }
      @Override public boolean isCopyVisible(@NotNull DataContext dataContext) { return true; }
      @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    });
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

  /** 나무 마디의 우클릭 메뉴. 접속 메뉴는 탭(connectionMenu)에 있다. */
  private ActionGroup menuFor(DefaultMutableTreeNode n) {
    DefaultActionGroup g = new DefaultActionGroup();
    Object o = n.getUserObject();
    if (o instanceof Schema s) {
      g.add(new Act(EiDbBundle.message("explorer.erd.all"), null, () -> true, () -> ErdView.open(project, s.c(), s.name(), null)));
      g.add(new Act(EiDbBundle.message("explorer.spec"), AllIcons.ToolbarDecorator.Export, () -> true, () -> exportSpec(s.c(), s.name(), null)));
      return g;
    }
    if (o instanceof Program p) {
      g.add(new Act(EiDbBundle.message("explorer.open.source"), AllIcons.Actions.Edit, () -> true, () -> openSource(p)));
      g.add(new Act(EiDbBundle.message("explorer.compile.errors"), null, () -> true,
          () -> SqlRunner.run(project, p.c(), ProgramSql.ERRORS, List.of(p.schema(), p.item().name()))));
      return g;
    }
    if (o instanceof Meta.Column) {
      g.add(new Act(EiDbBundle.message("explorer.copy.column"), AllIcons.Actions.Copy, () -> true, this::copyNames));
      return g;
    }
    if (o instanceof TableItem t) {
      g.add(new Act(EiDbBundle.message("explorer.copy.table"), AllIcons.Actions.Copy, () -> true, this::copyNames));
      g.addSeparator();
      g.add(new Act(EiDbBundle.message("explorer.view.data"), AllIcons.Actions.Execute, () -> true, () -> viewData(t)));
      List<String> picked = selectedTables(t);
      if (picked.size() > 1) {
        g.add(new Act(EiDbBundle.message("explorer.select.join", picked.size()), null, () -> true, () -> joinSelect(n, t, picked)));
      } else {
        g.add(new Act(EiDbBundle.message("explorer.select"), null, () -> true, () -> withColumns(n, t,
            cols -> ConsoleOpener.open(project, t.c, SelectSql.build(t.c.kind, t.schema, t.table.name(), cols)))));
      }
      g.add(new Act(EiDbBundle.message("explorer.insert"), null, () -> true, () -> withColumns(n, t,
          cols -> ConsoleOpener.open(project, t.c, SelectSql.insert(t.c.kind, t.schema, t.table.name(), cols)))));
      g.add(new Act(EiDbBundle.message("explorer.update"), null, () -> true, () -> withColumns(n, t,
          cols -> ConsoleOpener.open(project, t.c, SelectSql.update(t.c.kind, t.schema, t.table.name(), cols)))));
      g.add(new Act(EiDbBundle.message("mapper.command"), null, () -> true, () -> MapperDialog.show(project, t.c, t.schema, t.table.name())));
      g.add(new Act(EiDbBundle.message("action.ei.db.gen.Feature.text"), null, () -> true, () -> FeatureDialog.show(project, t.c, t.schema, t.table.name())));
      g.addSeparator();
      g.add(new Act(EiDbBundle.message("explorer.erd"), null, () -> true, () -> ErdView.open(project, t.c, t.schema, picked)));
      g.add(new Act(EiDbBundle.message("explorer.spec"), AllIcons.ToolbarDecorator.Export, () -> true, () -> exportSpec(t.c, t.schema, picked)));
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
      String sql = SelectSql.join(base.c.kind, base.schema, tables, columns, fks);
      return () -> ConsoleOpener.open(project, base.c, sql);
    });
  }

  private void viewData(TableItem t) {
    SqlRunner.run(project, t.c, "SELECT * FROM " + Names.sql(t.c.kind, t.schema, t.table.name()), List.of());
  }

  // ---- 테이블 정의서 ----

  private static final String XLSX = "xlsx";

  /** 표 · 뷰 정의를 엑셀 한 장으로. picked 가 null 이면 스키마의 모든 표 · 뷰, 아니면 그 이름의 것만(DB 가 돌려준 순서). */
  private void exportSpec(DbConfig c, String schema, List<String> picked) {
    String base = picked != null && picked.size() == 1 ? picked.get(0) : schema;
    FileSaverDescriptor desc = new FileSaverDescriptor(EiDbBundle.message("explorer.spec.title"), EiDbBundle.message("explorer.spec.description"), XLSX);
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(desc, project).save(base + EiDbBundle.message("explorer.spec.file.suffix") + "." + XLSX);
    if (target == null) return;
    Path path = target.getFile().toPath();
    // 큰 스키마는 오래 걸려 탐색기의 metaQueue 를 막지 않도록 따로 진행 창(취소 가능)에서 읽는다.
    new Task.Backgroundable(project, EiDbBundle.message("explorer.spec.progress", schema), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        List<TableSpec.Table> specs = new ArrayList<>();
        try {
          Connection conn = DbSessions.get(project).connection(c);
          List<Meta.Table> targets = new ArrayList<>();
          for (Meta.Table t : Meta.tables(conn, c, schema, TableSpec.TYPES)) {
            if (picked == null || picked.contains(t.name())) targets.add(t);
          }
          indicator.setIndeterminate(false);
          for (int i = 0; i < targets.size(); i++) {
            indicator.checkCanceled();
            Meta.Table t = targets.get(i);
            indicator.setText2(EiDbBundle.message("explorer.spec.progress.table", i + 1, targets.size(), t.name()));
            indicator.setFraction((double) i / targets.size());
            specs.add(TableSpec.read(conn, c, schema, t));
          }
        } catch (SQLException e) {
          Notify.error(project, EiDbBundle.message("explorer.spec.read.failed", e.getMessage()));
          return;
        }
        List<TableSpec.Table> filled = TableSpec.filled(specs);
        if (filled.isEmpty()) {
          Notify.warning(project, EiDbBundle.message("explorer.spec.empty", schema));
          return;
        }
        indicator.checkCanceled();
        try {
          XlsxExport.write(path, TableSpec.HEADER, null, TableSpec.rows(filled));
        } catch (IOException e) {
          Notify.error(project, EiDbBundle.message("explorer.spec.save.failed", e.getMessage()));
          return;
        }
        Notify.info(project, EiDbBundle.message("explorer.spec.saved", filled.size(), path));
      }
    }.queue();
  }

  // ---- 표 · 칼럼 찾기 ----

  private void openSearch(DbConfig c) {
    String schema = Meta.schemaOf(c);
    ColumnSearch.show(project, c, schema, hit -> {
      if (!reveal(c, hit.table())) openSelect(c, schema, hit.table());
    });
  }

  /** 그 접속의 읽어 둔 스키마에 그 표 마디가 보이면 탭을 고르고 펼친다. 없으면(안 읽음 · 검색으로 숨김) false. */
  private boolean reveal(DbConfig c, String table) {
    DefaultMutableTreeNode conn = conns.get(c.id);
    if (conn == null) return false;
    for (int j = 0; j < conn.getChildCount(); j++) {
      DefaultMutableTreeNode schema = node(conn.getChildAt(j));
      for (int k = 0; k < schema.getChildCount(); k++) {
        DefaultMutableTreeNode group = node(schema.getChildAt(k));
        for (int m = 0; m < group.getChildCount(); m++) {
          DefaultMutableTreeNode tn = node(group.getChildAt(m));
          if (tn.getUserObject() instanceof TableItem t && t.table.name().equals(table)) {
            showConnection(c);
            TreePath path = new TreePath(tn.getPath());
            tree.setSelectionPath(path);
            tree.expandPath(path);
            tree.scrollPathToVisible(path);
            return true;
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
      String sql = SelectSql.build(c.kind, schema, table, cols);
      return () -> ConsoleOpener.open(project, c, sql);
    });
  }

  // ---- 접속 내보내기 · 가져오기 ----

  private static final String EXPORT_FILE = "ei-db-connections.json";
  private static final String JSON = "json";

  /** 모든 접속을 JSON 파일 하나로. 비밀번호는 넣지 않는다. */
  private void exportConnections() {
    FileSaverDescriptor desc = new FileSaverDescriptor(EiDbBundle.message("explorer.export"), EiDbBundle.message("explorer.export.description"), JSON);
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(desc, project).save(EXPORT_FILE);
    if (target == null) return;
    Path path = target.getFile().toPath();
    String json = ConnectionsJson.write(DbSettings.getInstance().connections());
    int count = DbSettings.getInstance().connections().size();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Files.writeString(path, json, StandardCharsets.UTF_8);
        Notify.info(project, EiDbBundle.message("explorer.exported", count, path));
      } catch (IOException e) {
        Notify.error(project, EiDbBundle.message("explorer.export.failed", e.getMessage()));
      }
    });
  }

  /** 파일의 접속을 더한다(기존 접속은 바꾸지 않는다). 같은 이름은 「이름 (가져옴)」. */
  private void importConnections() {
    VirtualFile file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor(JSON).withTitle(EiDbBundle.message("explorer.import")), project, null);
    if (file == null) return;
    Path path = file.toNioPath();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<DbConfig> read;
      try {
        read = ConnectionsJson.read(Files.readString(path, StandardCharsets.UTF_8));
      } catch (IOException | IllegalArgumentException e) {
        Notify.error(project, EiDbBundle.message("explorer.import.failed", e.getMessage()));
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
    Notify.info(project, EiDbBundle.message("explorer.imported", read.size()));
  }

  // ---- 접속 다루기 ----

  private void addConnection() {
    if (ConnectDialog.edit(project, null) != null) rebuild();
  }

  private void edit(DbConfig c) {
    if (c == null) return;
    if (ConnectDialog.edit(project, c) != null) rebuild();
  }

  /** 저장된 비밀번호가 없으면(저장을 끈 접속의 IDE 재실행 뒤) 먼저 묻고 IDE 를 끌 때까지 기억한다. */
  private void connect(DbConfig c) {
    if (c == null) return;
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      if (!sessions.isOpen(c.id) && Passwords.get(c) == null) {
        ApplicationManager.getApplication().invokeLater(() -> askPasswordAndConnect(c), project.getDisposed());
        return;
      }
      openSession(c);
    });
  }

  private void askPasswordAndConnect(DbConfig c) {
    String pw = Messages.showPasswordDialog(project, EiDbBundle.message("explorer.password.prompt", c.name, c.user), EiDbBundle.message("connect.password"), null);
    if (pw == null) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      SessionPassword.set(c, pw);
      openSession(c);
    });
  }

  /** 백그라운드에서 부른다. */
  private void openSession(DbConfig c) {
    try {
      DbSessions.get(project).connection(c);
    } catch (Exception e) {
      Notify.error(project, EiDbBundle.message("explorer.connect.failed", c.name, e.getMessage()));
    }
  }

  /** 미반영 변경이 있으면 롤백된다고 먼저 묻는다. 끊기로 했으면 true. */
  private boolean confirmRollback(DbConfig c, String title) {
    int pending = DbSessions.get(project).pending(c.id);
    return pending == 0
           || Messages.showYesNoDialog(project, EiDbBundle.message("explorer.rollback.confirm", pending), title, Messages.getWarningIcon()) == Messages.YES;
  }

  private void closeInBackground(DbConfig c) {
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> sessions.close(c.id));
  }

  private void disconnect(DbConfig c) {
    if (c == null || !confirmRollback(c, EiDbBundle.message("explorer.disconnect"))) return;
    closeInBackground(c);
  }

  private void delete(DbConfig c) {
    if (c == null) return;
    if (Messages.showYesNoDialog(project, EiDbBundle.message("explorer.delete.confirm", c.name), EiDbBundle.message("explorer.delete.title"), Messages.getWarningIcon()) != Messages.YES) return;
    if (!confirmRollback(c, EiDbBundle.message("explorer.delete.title"))) return;
    DbSettings.getInstance().remove(c.id);
    DbSessions sessions = DbSessions.get(project);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Passwords.set(c, null);
      SessionPassword.set(c, null);
      sessions.close(c.id);
    });
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
    DefaultMutableTreeNode conn = conns.get(c.id);
    if (conn == null) return;
    conn.removeAllChildren();
    conn.add(schemaNode(c));
    model.nodeStructureChanged(conn);
  }

  // ---- 그리기 ----

  private final class Renderer extends ColoredTreeCellRenderer {
    @Override
    public void customizeCellRenderer(@NotNull JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
      DefaultMutableTreeNode n = node(value);
      Object o = n.getUserObject();
      setFont(t.getFont());
      if (o instanceof Schema s) {
        setIcon(AllIcons.Nodes.DataSchema);
        append(s.name(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        append(EiDbBundle.message("explorer.default.schema"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof Group g) {
        setIcon(AllIcons.Nodes.Folder);
        append(g.label());
        String count = n.getChildCount() == g.items().size() ? String.valueOf(g.items().size()) : n.getChildCount() + " / " + g.items().size();
        append("  " + count, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof TableItem ti) {
        setIcon(AllIcons.Nodes.DataTables);
        append(ti.table.name());
        if (!ti.table.comment().isEmpty()) append("  " + ti.table.comment(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        if (ti.columns != null) append(SPACE + EiDbBundle.message("explorer.column.count", ti.columns.size()), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof Meta.Column col) {
        // PK 노랑 · FK 파랑 꼬리표 칸, 코드 글꼴 이름, 흐린 주석 · 형식.
        setIcon(new ColumnIcon(col.pk() ? Badge.pk(KEY_TAG_WIDTH) : col.fk() ? Badge.fk(KEY_TAG_WIDTH) : null));
        setFont(codeFont(t.getFont()));
        append(col.name());
        if (!col.comment().isEmpty()) append(SPACE + col.comment(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append(SPACE + col.typeLabel(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof ProgramGroup) {
        setIcon(AllIcons.Nodes.Package);
        append(EiDbBundle.message("explorer.programs"));
        append(EiDbBundle.message("explorer.programs.hint"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
      } else if (o instanceof Program p) {
        setIcon(ProgramSql.PACKAGE.equals(p.item().type()) ? AllIcons.Nodes.Package : AllIcons.Nodes.Function);
        append(p.item().name());
        append("  " + p.item().type(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        if (p.item().invalid()) append("  " + ProgramSql.INVALID, SimpleTextAttributes.ERROR_ATTRIBUTES);
      } else if (o instanceof Message m) {
        append(m.text(), m.error() ? SimpleTextAttributes.ERROR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
      }
    }

    /** 편집기 글꼴(고정폭)을 나무 글자 크기로. */
    private static Font codeFont(Font treeFont) {
      return EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN).deriveFont(treeFont.getSize2D());
    }
  }

  /** 칼럼 줄 앞의 PK · FK 꼬리표 칸. 키가 아니면(tag 가 null) 칸만 비워 이름 줄을 맞춘다. */
  private record ColumnIcon(Badge tag) implements Icon {
    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      if (tag != null) tag.paintIcon(c, g, x, y + (getIconHeight() - tag.getIconHeight()) / 2);
    }

    @Override public int getIconWidth() { return Math.max(Badge.pk(KEY_TAG_WIDTH).getIconWidth(), Badge.fk(KEY_TAG_WIDTH).getIconWidth()); }
    @Override public int getIconHeight() { return Badge.pk(KEY_TAG_WIDTH).getIconHeight(); }
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
