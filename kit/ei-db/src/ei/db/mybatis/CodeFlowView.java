package ei.db.mybatis;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 아래쪽 「코드 흐름」 도구 창: 호출 흐름 · 바꾸면 영향 · 표에서 찾기 · 안 쓰는 것 네 탭.
 * 창을 열어도 아무것도 찾지 않는다. 「조회」 · 목록 고르기 · 우클릭 메뉴로만 찾고, 찾기는 뒤에서(진행 표시 · 취소 가능) 돈다.
 * 결과는 탭이 들고 있다가 다음 찾기나 창을 닫을 때 버린다. 찾는 데 걸린 시간은 idea.log 에 남긴다.
 */
public final class CodeFlowView {
  private CodeFlowView() {}

  static final String TOOL_WINDOW_ID = "ei.db.CodeFlow";
  private static final Logger LOG = Logger.getInstance(CodeFlowView.class);
  private static final String SPLIT_KEY = "ei.db.codeflow.flow.split";
  private static final float SPLIT_PROPORTION = 0.35f;
  /** 처음 펼쳐 둘 나무 깊이. */
  private static final int EXPAND_LEVELS = 3;

  /** 도구 창을 만든다. */
  public static final class Factory implements ToolWindowFactory, DumbAware {
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
      toolWindow.setStripeTitle(EiDbBundle.message("toolwindow.stripe.ei.db.CodeFlow"));
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
      ContentManager cm = toolWindow.getContentManager();
      add(cm, new FlowTab(project), "codeflow.tab.flow");
      add(cm, new ImpactTab(project), "codeflow.tab.impact");
      add(cm, new TableTab(project), "codeflow.tab.table");
      add(cm, new UnusedTab(project), "codeflow.tab.unused");
    }

    private static void add(ContentManager cm, Tab tab, String titleKey) {
      Content c = ContentFactory.getInstance().createContent(tab, EiDbBundle.message(titleKey), false);
      Disposer.register(c, tab);
      cm.addContent(c);
    }
  }

  /** 도구 창을 열고 type 탭을 고른 뒤 then 을 부른다. 화면 스레드에서 부른다. */
  static <T extends Tab> void show(Project project, Class<T> type, Consumer<T> then) {
    ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    if (tw == null) return;
    tw.activate(() -> {
      ContentManager cm = tw.getContentManager();
      for (Content c : cm.getContents()) {
        if (type.isInstance(c.getComponent())) {
          cm.setSelectedContent(c);
          then.accept(type.cast(c.getComponent()));
          return;
        }
      }
    });
  }

  /** DB 탐색기 표 우클릭 「코드에서 쓰는 곳」: 표에서 찾기 탭에 표 이름을 넣고 찾는다. */
  public static void findTable(Project project, String table) {
    show(project, TableTab.class, t -> t.find(table, ""));
  }

  // ---------------------------------------------------------------- 공용 부품

  /** 뒤에서 돌릴 일. 취소되면 ProcessCanceledException 을 던진다. */
  interface Work<T> {
    T run(ProgressIndicator indicator);
  }

  /** 한 자리(목록 · 나무)의 찾기. 새로 시작하면 앞의 것을 취소하고, 늦게 끝난 앞의 결과는 버린다. */
  private static final class Runner implements Disposable {
    private final Project project;
    private volatile ProgressIndicator current;
    private volatile long generation;
    private boolean disposed;

    Runner(Project project) { this.project = project; }

    /** 화면 스레드에서 부른다. done · failed 도 화면 스레드에서 불린다(이 Runner 가 마지막으로 시작한 일일 때만). */
    <T> void start(String title, String op, Work<T> work, Consumer<T> done, Runnable failed) {
      ProgressIndicator before = current;
      if (before != null) before.cancel();
      long gen = ++generation;
      new Task.Backgroundable(project, title, true) {
        private T result;

        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          // 시작 전에 새 찾기가 들어왔으면 돌지 않는다(앞의 취소는 아직 지시자가 없을 때 닿지 않는다).
          if (gen != generation) return;
          current = indicator;
          long started = System.currentTimeMillis();
          result = work.run(indicator);
          LOG.info("코드 흐름 " + op + ": " + (System.currentTimeMillis() - started) + " ms");
        }

        @Override
        public void onSuccess() {
          if (live(gen)) done.accept(result);
        }

        @Override
        public void onCancel() {
          if (live(gen)) failed.run();
        }

        @Override
        public void onThrowable(@NotNull Throwable error) {
          LOG.warn("코드 흐름 " + op + " 실패", error);
          if (!live(gen)) return;
          failed.run();
          Notify.error(project, EiDbBundle.message("codeflow.failed", String.valueOf(error.getMessage())));
        }
      }.queue();
    }

    private boolean live(long gen) { return !disposed && gen == generation && !project.isDisposed(); }

    @Override
    public void dispose() {
      disposed = true;
      ProgressIndicator now = current;
      if (now != null) now.cancel();
    }
  }

  /** 결과 나무. 두 번 누르기 · Enter 로 그 자리로 간다. */
  private static final class ResultTree extends Tree {
    ResultTree(String empty) {
      super(new DefaultTreeModel(null));
      setRootVisible(true);
      getEmptyText().setText(empty);
      setCellRenderer(new ColoredTreeCellRenderer() {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
          if (!(value instanceof DefaultMutableTreeNode n) || !(n.getUserObject() instanceof CodeFlow.Item item)) return;
          setIcon(item.icon);
          append(item.text);
          if (item.note != null && !item.note.isEmpty()) append("  " + item.note, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
      });
      TreeSpeedSearch.installOn(this);
      new DoubleClickListener() {
        @Override
        protected boolean onDoubleClick(@NotNull MouseEvent event) { return navigateSelected(); }
      }.installOn(this);
      registerKeyboardAction((ActionEvent e) -> navigateSelected(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
    }

    private boolean navigateSelected() {
      TreePath path = getSelectionPath();
      Object node = path == null ? null : path.getLastPathComponent();
      if (!(node instanceof DefaultMutableTreeNode n) || !(n.getUserObject() instanceof CodeFlow.Item item)) return false;
      Navigatable nav = item.nav;
      if (nav == null || !nav.canNavigate()) return false;
      nav.navigate(true);
      return true;
    }

    /** root 를 보여 준다. null 이면 비우고 empty 글을 보인다. */
    void show(@Nullable CodeFlow.Item root, String empty) {
      getEmptyText().setText(empty);
      setModel(new DefaultTreeModel(root == null ? null : node(root)));
      if (root != null) TreeUtil.expand(this, EXPAND_LEVELS);
    }

    private static DefaultMutableTreeNode node(CodeFlow.Item item) {
      DefaultMutableTreeNode n = new DefaultMutableTreeNode(item);
      for (CodeFlow.Item k : item.kids) n.add(node(k));
      return n;
    }
  }

  /** 탭 하나. 결과 나무와 그 찾기를 가진다. */
  abstract static class Tab extends JPanel implements Disposable {
    final Project project;
    final Runner runner;
    final ResultTree tree;

    Tab(Project project, String empty) {
      super(new BorderLayout());
      this.project = project;
      this.runner = new Runner(project);
      this.tree = new ResultTree(empty);
      Disposer.register(this, runner);
    }

    /** 나무 자리 찾기를 시작한다. 결과가 null 이면 gone 글을 보인다. */
    void search(String op, Work<CodeFlow.Item> work, String gone) {
      tree.show(null, EiDbBundle.message("codeflow.searching"));
      runner.start(EiDbBundle.message("codeflow.progress"), op, work,
          root -> tree.show(root, gone),
          () -> tree.show(null, EiDbBundle.message("codeflow.stopped")));
    }

    /** find 로 찾은 뒤(요소가 지워졌으면 null) 화면 묶음을 따로 읽어 붙인다. */
    void searchFound(String op, java.util.concurrent.Callable<CodeFlow.Found> find) {
      search(op, indicator -> {
        CodeFlow.Found found = CodeFlow.read(project, find);
        if (found == null) return null;
        if (found.screens()) {
          indicator.setText(EiDbBundle.message("codeflow.step.screens"));
          found.root().kids.add(CodeFlow.read(project, () -> CodeFlow.screensGroup(project, found.apis())));
        }
        return found.root();
      }, EiDbBundle.message("codeflow.gone"));
    }

    @Override
    public void dispose() {}
  }

  private static JButton button(String text, Runnable run) {
    JButton b = new JButton(text, AllIcons.Actions.Find);
    b.addActionListener(e -> run.run());
    return b;
  }

  // ---------------------------------------------------------------- 1 호출 흐름

  /** 왼쪽 URL 목록(조회 · 거르기), 오른쪽 고른 URL 의 호출 흐름. */
  static final class FlowTab extends Tab {
    private final CollectionListModel<CodeFlow.Api> model = new CollectionListModel<>();
    private final JBList<CodeFlow.Api> list = new JBList<>(model);
    private final SearchTextField filter = new SearchTextField(false);
    private final Runner listRunner;
    private List<CodeFlow.Api> all = List.of();
    /** 오른쪽에 흐름을 보인 URL(우클릭 메뉴로 다른 메서드를 보이면 null). */
    private CodeFlow.Api shown;

    FlowTab(Project project) {
      super(project, EiDbBundle.message("codeflow.flow.empty"));
      listRunner = new Runner(project);
      Disposer.register(this, listRunner);
      list.getEmptyText().setText(EiDbBundle.message("codeflow.flow.list.empty"));
      list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      list.setCellRenderer(new ColoredListCellRenderer<>() {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends CodeFlow.Api> l, CodeFlow.Api api, int index, boolean selected, boolean hasFocus) {
          append(api.label());
          append("  " + api.where(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
      });
      list.addListSelectionListener(e -> {
        if (!e.getValueIsAdjusting()) showSelected();
      });
      // 우클릭 「호출 흐름 보기」 로 오른쪽이 바뀐 뒤 같은 URL 을 다시 누르면 선택이 그대로라 위에서 잡히지 않는다.
      list.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) { showSelected(); }
      });
      filter.addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) { applyFilter(); }
      });

      JPanel top = new JPanel(new BorderLayout(JBUI.scale(6), 0));
      top.add(button(EiDbBundle.message("codeflow.query"), this::load), BorderLayout.WEST);
      top.add(filter, BorderLayout.CENTER);
      JPanel left = new JPanel(new BorderLayout());
      left.add(top, BorderLayout.NORTH);
      left.add(new JBScrollPane(list), BorderLayout.CENTER);
      JBSplitter split = new JBSplitter(false, SPLIT_KEY, SPLIT_PROPORTION);
      split.setHonorComponentsMinimumSize(false);
      split.setFirstComponent(left);
      split.setSecondComponent(new JBScrollPane(tree));
      add(split, BorderLayout.CENTER);
    }

    private void load() {
      list.getEmptyText().setText(EiDbBundle.message("codeflow.searching"));
      model.removeAll();
      listRunner.start(EiDbBundle.message("codeflow.progress"), "URL 목록",
          indicator -> CodeFlow.read(project, () -> CodeFlow.apis(project)),
          apis -> {
            all = apis;
            list.getEmptyText().setText(EiDbBundle.message("codeflow.flow.list.none"));
            applyFilter();
          },
          () -> list.getEmptyText().setText(EiDbBundle.message("codeflow.stopped")));
    }

    private void applyFilter() {
      String q = filter.getText().trim().toLowerCase(Locale.ROOT);
      List<CodeFlow.Api> shown = new ArrayList<>();
      for (CodeFlow.Api a : all) {
        if (q.isEmpty() || a.label().toLowerCase(Locale.ROOT).contains(q) || a.where().toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
      }
      model.replaceAll(shown);
    }

    /** 고른 URL 이 오른쪽에 보이는 것과 다르면 그 흐름을 보인다. */
    private void showSelected() {
      CodeFlow.Api api = list.getSelectedValue();
      if (api == null || api == shown) return;
      showMethod(api.method());
      shown = api;
    }

    /** method 에서 시작하는 호출 흐름을 오른쪽에 보인다. */
    void showMethod(SmartPsiElementPointer<PsiMethod> method) {
      shown = null;
      searchFound("호출 흐름", () -> {
        PsiMethod m = method.getElement();
        return m == null ? null : CodeFlow.forward(project, m);
      });
    }
  }

  // ---------------------------------------------------------------- 2 바꾸면 영향

  /** 우클릭 「바꾸면 영향 보기」 결과. */
  static final class ImpactTab extends Tab {
    ImpactTab(Project project) {
      super(project, EiDbBundle.message("codeflow.impact.empty"));
      add(new JBScrollPane(tree), BorderLayout.CENTER);
    }

    void show(SmartPsiElementPointer<? extends PsiElement> target) {
      searchFound("영향", () -> {
        PsiElement e = target.getElement();
        return e == null ? null : CodeFlow.impact(project, e);
      });
    }
  }

  // ---------------------------------------------------------------- 3 표에서 찾기

  /** 표 · 칼럼 이름을 넣고 「조회」. */
  static final class TableTab extends Tab {
    private final JBTextField table = new JBTextField();
    private final JBTextField column = new JBTextField();

    TableTab(Project project) {
      super(project, EiDbBundle.message("codeflow.table.empty"));
      table.getEmptyText().setText(EiDbBundle.message("codeflow.table.hint"));
      column.getEmptyText().setText(EiDbBundle.message("codeflow.column.hint"));
      table.addActionListener(e -> find());
      column.addActionListener(e -> find());
      JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      buttons.add(button(EiDbBundle.message("codeflow.query"), this::find));
      JPanel form = FormBuilder.createFormBuilder()
          .addLabeledComponent(EiDbBundle.message("codeflow.table.label"), table)
          .addLabeledComponent(EiDbBundle.message("codeflow.column.label"), column)
          .addComponent(buttons)
          .getPanel();
      form.setBorder(JBUI.Borders.empty(4, 6));
      add(form, BorderLayout.NORTH);
      add(new JBScrollPane(tree), BorderLayout.CENTER);
    }

    void find(String tableName, String columnName) {
      table.setText(tableName);
      column.setText(columnName);
      find();
    }

    private void find() {
      String t = table.getText().trim();
      String c = column.getText().trim();
      if (t.isEmpty() || FlowRules.splitTable(t)[1].isEmpty()) {
        tree.show(null, EiDbBundle.message("codeflow.table.need"));
        return;
      }
      searchFound("표에서 찾기", () -> CodeFlow.tableUsers(project, t, c));
    }
  }

  // ---------------------------------------------------------------- 7 안 쓰는 것

  /** 「조회」로 화면에서 안 부르는 API · 부르는 곳 없는 매퍼 SQL · 아무도 안 쓰는 DTO 를 모은다. */
  static final class UnusedTab extends Tab {
    UnusedTab(Project project) {
      super(project, EiDbBundle.message("codeflow.unused.empty"));
      JPanel top = new JPanel(new BorderLayout(JBUI.scale(8), 0));
      top.setBorder(JBUI.Borders.empty(4, 6));
      top.add(button(EiDbBundle.message("codeflow.query"), this::find), BorderLayout.WEST);
      JBLabel note = new JBLabel(EiDbBundle.message("codeflow.unused.note"));
      note.setForeground(SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor());
      top.add(note, BorderLayout.CENTER);
      add(top, BorderLayout.NORTH);
      add(new JBScrollPane(tree), BorderLayout.CENTER);
    }

    private void find() {
      search("안 쓰는 것", this::collect, EiDbBundle.message("codeflow.gone"));
    }

    /** 묶음마다 목록을 한 번 읽고, 항목마다 짧은 읽기로 확인한다(그 사이 편집이 막히지 않게). */
    private CodeFlow.Item collect(ProgressIndicator indicator) {
      CodeFlow.Item root = new CodeFlow.Item(EiDbBundle.message("codeflow.tab.unused"), "", AllIcons.Actions.Find, null);
      indicator.setIndeterminate(false);

      indicator.setText(EiDbBundle.message("codeflow.unused.step.api"));
      List<CodeFlow.Api> apis = CodeFlow.read(project, () -> CodeFlow.apis(project));
      List<CodeFlow.Front> fronts = CodeFlow.read(project, () -> CodeFlow.fronts(project));
      CodeFlow.Item api = group(root, "codeflow.unused.api", AllIcons.Nodes.PpWeb);
      if (fronts.isEmpty()) {
        api.note = EiDbBundle.message("codeflow.note.no.front");
      } else {
        for (CodeFlow.Api a : apis) {
          indicator.checkCanceled();
          Pattern p = FlowRules.frontPattern(a.url());
          if (p == null || called(p, a, fronts)) continue;
          api.kids.add(new CodeFlow.Item(a.label(), a.where(), AllIcons.Nodes.Method, new CodeFlow.PsiNav(a.method())));
        }
        api.note = EiDbBundle.message("codeflow.note.count", api.kids.size());
      }

      indicator.setText(EiDbBundle.message("codeflow.unused.step.sql"));
      List<CodeFlow.Statement> statements = CodeFlow.read(project, () -> CodeFlow.statements(project));
      Set<String> nested = CodeFlow.read(project, () -> CodeFlow.nestedSelects(project));
      CodeFlow.Item sql = group(root, "codeflow.unused.sql", AllIcons.FileTypes.Xml);
      for (int i = 0; i < statements.size(); i++) {
        indicator.setFraction((double) i / statements.size());
        CodeFlow.Statement s = statements.get(i);
        CodeFlow.Item item = CodeFlow.read(project, () -> CodeFlow.unusedStatement(project, s, nested));
        if (item != null) sql.kids.add(item);
      }
      sql.note = EiDbBundle.message("codeflow.note.count", sql.kids.size());

      indicator.setText(EiDbBundle.message("codeflow.unused.step.dto"));
      List<SmartPsiElementPointer<PsiClass>> dtos = CodeFlow.read(project, () -> CodeFlow.dtos(project));
      Set<String> xmlTypes = CodeFlow.read(project, () -> CodeFlow.mapperTypeNames(project));
      CodeFlow.Item dto = group(root, "codeflow.unused.dto", AllIcons.Nodes.Class);
      for (int i = 0; i < dtos.size(); i++) {
        indicator.setFraction((double) i / dtos.size());
        SmartPsiElementPointer<PsiClass> d = dtos.get(i);
        CodeFlow.Item item = CodeFlow.read(project, () -> CodeFlow.unusedDto(project, d, xmlTypes));
        if (item != null) dto.kids.add(item);
      }
      dto.note = EiDbBundle.message("codeflow.note.count", dto.kids.size());
      return root;
    }

    private static CodeFlow.Item group(CodeFlow.Item root, String key, javax.swing.Icon icon) {
      CodeFlow.Item g = new CodeFlow.Item(EiDbBundle.message(key), "", icon, null);
      root.kids.add(g);
      return g;
    }

    /** 낱말 조각이 모두 든 화면 파일에서만 정규식을 돌린다(파일 수 × URL 수만큼 글 전체를 훑지 않게). */
    private static boolean called(Pattern p, CodeFlow.Api api, List<CodeFlow.Front> fronts) {
      for (CodeFlow.Front f : fronts) {
        if (f.mayCall(api) && FlowRules.find(p, f.text()) >= 0) return true;
      }
      return false;
    }
  }

  /** 우클릭 메뉴에서: 호출 흐름 탭에 method 의 흐름을 보인다. */
  static void showFlow(Project project, SmartPsiElementPointer<PsiMethod> method) {
    show(project, FlowTab.class, t -> t.showMethod(method));
  }

  /** 우클릭 메뉴에서: 영향 탭에 target 의 영향을 보인다. */
  static void showImpact(Project project, SmartPsiElementPointer<? extends PsiElement> target) {
    show(project, ImpactTab.class, t -> t.show(target));
  }
}
