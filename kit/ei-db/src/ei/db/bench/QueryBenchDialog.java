package ei.db.bench;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSettings;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.console.ConsoleOpener;
import ei.db.console.ConsoleRun;
import ei.db.gen.ErdView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * 쿼리 작업대. 모달이 아니라 콘솔 · 결과 창을 오가며 쓴다. 처음에는 화면의 FIRST_SIZE 비율로 열고, 사용자가 바꾼 크기는 IntelliJ 가 기억한다.
 * 위: 접속 · 연결 모양(라디오) · 값 자리(라디오) · 「자세히」. 가운데: 탭. 아래: 만든 SQL(고칠 수 있음) · 알림 · 단추.
 * 연결 모양 · 값 자리 · 「자세히」 · 마지막 접속은 기억한다. PostgreSQL · MySQL 은 (+) 가 없어 표준 JOIN ON 으로만 만든다.
 */
public final class QueryBenchDialog extends DialogWrapper {
  private static final double FIRST_SIZE = 0.85;
  private static final float TABS_SPLIT = 0.62f;
  private static final float SQL_SPLIT = 0.7f;
  private static final String KEY = "ei.db.bench.";
  private static final String STYLE_KEY = KEY + "joinStyle";
  private static final String BIND_KEY = KEY + "bind";
  private static final String DETAILS_KEY = KEY + "details";
  private static final String CONFIG_KEY = KEY + "config";
  private static final String TAB_KEY = KEY + "tab";

  private final Project project;
  private final VirtualFile console;
  private final Bench bench;
  private final ComboBox<DbConfig> connection = new ComboBox<>();
  private final JBRadioButton oldStyle = new JBRadioButton("옛 방식 (+)  — FROM 에 쉼표, WHERE 에 a.X = b.X(+)");
  private final JBRadioButton ansiStyle = new JBRadioButton("표준 JOIN ON");
  private final JBLabel styleReason = new JBLabel();
  private final JBRadioButton bindColon = new JBRadioButton(":이름");
  private final JBRadioButton bindMybatis = new JBRadioButton("#{이름}  (MyBatis)");
  private final JBRadioButton bindValue = new JBRadioButton("값 그대로");
  private final JBCheckBox details = new JBCheckBox("자세히 (별칭 · 묶은 뒤 조건 · 건너뛸 건수 · DISTINCT)");
  private final JBCheckBox lint = new JBCheckBox("실행할 때마다 쿼리 점검 (조인 빠짐 · = NULL · NOT IN 등)");
  private final JBTextArea sql = new JBTextArea();
  private final JBTextArea notes = new JBTextArea();
  private final JBTabbedPane tabs = new JBTabbedPane();
  private final JBLabel status = new JBLabel();
  private final BuilderPanel builder;
  private final PatternPanel purpose;
  private final PatternPanel dashboard;
  private final ExamplePanel examples;
  private final ExplainPanel explain;
  private final ToolsPanel tools;
  /** 탭이 SQL 칸을 채우는 중이면 true(사용자가 친 것으로 보지 않는다). */
  private boolean filling;

  /** 작업대를 연다. c · initialSql 은 콘솔에서 열 때 그 접속 · 커서 문장, console 은 「편집기에 넣기」 를 받을 콘솔(없으면 null). */
  public static void open(Project project, @Nullable DbConfig c, @Nullable String initialSql, @Nullable VirtualFile console) {
    if (DbSettings.getInstance().connections().isEmpty()) {
      Notify.info(project, "등록한 DB 접속이 없습니다. 데이터베이스 창에서 접속을 먼저 만드세요.");
      return;
    }
    new QueryBenchDialog(project, c, initialSql, console).show();
  }

  private QueryBenchDialog(Project project, DbConfig c, String initialSql, VirtualFile console) {
    super(project, null, true, IdeModalityType.MODELESS);
    this.project = project;
    this.console = console;
    this.bench = new Bench(project, this::fill, m -> setStatus("읽지 못했습니다: " + m, true));
    builder = new BuilderPanel(bench);
    purpose = new PatternPanel(bench, false);
    dashboard = new PatternPanel(bench, true);
    examples = new ExamplePanel(bench);
    explain = new ExplainPanel(sql::getText);
    tools = new ToolsPanel(bench, sql::getText);
    setTitle("쿼리 작업대");
    setModal(false);
    init();
    restore(c);
    if (initialSql != null && !initialSql.isBlank()) {
      sql.setText(initialSql.strip());
      tabs.setSelectedIndex(4);
      explain.refresh();
    }
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return "ei.db.QueryBench"; }

  @Override
  public Dimension getInitialSize() {
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    return new Dimension((int) (screen.width * FIRST_SIZE), (int) (screen.height * FIRST_SIZE));
  }

  @Override
  protected Action @NotNull [] createActions() { return new Action[]{getCancelAction()}; }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    for (DbConfig c : DbSettings.getInstance().connections()) connection.addItem(c);
    connection.setRenderer(SimpleListCellRenderer.create("", c -> c.name + "  (" + c.kind + ")"));
    ButtonGroup styles = new ButtonGroup();
    styles.add(oldStyle);
    styles.add(ansiStyle);
    ButtonGroup binds = new ButtonGroup();
    binds.add(bindColon);
    binds.add(bindMybatis);
    binds.add(bindValue);
    styleReason.setForeground(UIUtil.getContextHelpForeground());
    JPanel styleRow = flow(oldStyle, ansiStyle, styleReason);
    JPanel bindRow = flow(bindColon, bindMybatis, bindValue);
    JPanel optionRow = flow(details, lint);
    JPanel top = FormBuilder.createFormBuilder()
        .addLabeledComponent("접속", connection)
        .addLabeledComponent("JOIN 모양", styleRow)
        .addLabeledComponent("값 자리", bindRow)
        .addComponent(optionRow)
        .getPanel();
    top.setBorder(JBUI.Borders.emptyBottom(6));

    tabs.addTab("단계별 작성기", builder.component());
    tabs.addTab("목적으로 만들기", purpose.component());
    tabs.addTab("현황 · 집계", dashboard.component());
    tabs.addTab("예제 모음", examples.component());
    tabs.addTab("쉬운 설명", explain.component());
    tabs.addTab("도구", tools.component());
    tabs.addChangeListener(e -> {
      PropertiesComponent.getInstance().setValue(TAB_KEY, tabs.getSelectedIndex(), 0);
      refreshActive();
    });

    sql.setFont(EditorFontType.getGlobalPlainFont());
    sql.setTabSize(4);
    sql.getEmptyText().setText("만든 SQL 이 여기에 나옵니다. 직접 쓰거나 붙여 넣고 「쉬운 설명」 · 「도구」 에 쓸 수도 있습니다.");
    notes.setEditable(false);
    notes.setLineWrap(true);
    notes.setWrapStyleWord(true);
    notes.setBackground(UIUtil.getPanelBackground());
    notes.setBorder(JBUI.Borders.empty(6));
    JBSplitter sqlSplit = new JBSplitter(false, SQL_SPLIT);
    sqlSplit.setSplitterProportionKey(KEY + "sqlSplit");
    sqlSplit.setHonorComponentsMinimumSize(false);
    sqlSplit.setFirstComponent(ScrollPaneFactory.createScrollPane(sql));
    sqlSplit.setSecondComponent(ScrollPaneFactory.createScrollPane(notes));

    JButton insert = ToolsPanel.blue(new JButton("편집기에 넣기"));
    insert.addActionListener(e -> insert());
    JButton copy = new JButton("복사");
    copy.addActionListener(e -> {
      CopyPasteManager.getInstance().setContents(new StringSelection(sql.getText()));
      setStatus("복사했습니다.", false);
    });
    JButton run = new JButton("실행");
    run.addActionListener(e -> run());
    JButton count = new JButton("건수 보기");
    count.addActionListener(e -> {
      if (sql.getText().isBlank()) return;
      setStatus("세는 중…", false);
      bench.count(sql.getText(), t -> setStatus("건수: " + t, false));
    });
    JButton diagram = new JButton("조인 그림");
    diagram.addActionListener(e -> diagram());
    JPanel buttonRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    buttonRow.add(flow(insert, copy, run, count, diagram), BorderLayout.WEST);
    buttonRow.add(status, BorderLayout.CENTER);

    JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    bottom.add(sqlSplit, BorderLayout.CENTER);
    bottom.add(buttonRow, BorderLayout.SOUTH);

    OnePixelSplitter split = new OnePixelSplitter(true, TABS_SPLIT);
    split.setSplitterProportionKey(KEY + "split");
    split.setHonorComponentsMinimumSize(false);
    split.setFirstComponent(tabs);
    split.setSecondComponent(bottom);

    JPanel root = new JPanel(new BorderLayout());
    root.add(top, BorderLayout.NORTH);
    root.add(split, BorderLayout.CENTER);

    connection.addActionListener(e -> connectionChanged());
    oldStyle.addActionListener(e -> styleChosen());
    ansiStyle.addActionListener(e -> styleChosen());
    bindColon.addActionListener(e -> bindChosen());
    bindMybatis.addActionListener(e -> bindChosen());
    bindValue.addActionListener(e -> bindChosen());
    details.addActionListener(e -> {
      PropertiesComponent.getInstance().setValue(DETAILS_KEY, details.isSelected());
      builder.setDetails(details.isSelected());
      refreshActive();
    });
    lint.addActionListener(e -> LintGate.setEnabled(lint.isSelected()));
    sql.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        if (!filling) setStatus("", false);
      }
    });
    bench.onChange(this::refreshActive);
    return root;
  }

  /** 기억한 선택을 되살린다. 콘솔에서 열었으면 그 접속을 먼저 고른다. */
  private void restore(DbConfig preferred) {
    PropertiesComponent props = PropertiesComponent.getInstance();
    QueryBuilder.JoinStyle style = QueryBuilder.JoinStyle.OLD.name().equals(props.getValue(STYLE_KEY, QueryBuilder.JoinStyle.OLD.name()))
        ? QueryBuilder.JoinStyle.OLD : QueryBuilder.JoinStyle.ANSI;
    QueryBuilder.Bind bind = QueryBuilder.Bind.COLON;
    for (QueryBuilder.Bind b : QueryBuilder.Bind.values()) if (b.name().equals(props.getValue(BIND_KEY))) bind = b;
    bench.setStyle(style);
    bench.setBind(bind);
    (bind == QueryBuilder.Bind.MYBATIS ? bindMybatis : bind == QueryBuilder.Bind.VALUE ? bindValue : bindColon).setSelected(true);
    details.setSelected(props.getBoolean(DETAILS_KEY, false));
    builder.setDetails(details.isSelected());
    lint.setSelected(LintGate.enabled());
    DbConfig pick = preferred;
    if (pick == null) pick = DbSettings.getInstance().find(props.getValue(CONFIG_KEY, ""));
    if (pick != null) {
      for (int i = 0; i < connection.getItemCount(); i++) {
        if (connection.getItemAt(i).id.equals(pick.id)) connection.setSelectedIndex(i);
      }
    }
    connectionChanged();
    tabs.setSelectedIndex(Math.max(0, Math.min(tabs.getTabCount() - 1, props.getInt(TAB_KEY, 0))));
  }

  private void connectionChanged() {
    DbConfig c = (DbConfig) connection.getSelectedItem();
    if (c != null) PropertiesComponent.getInstance().setValue(CONFIG_KEY, c.id);
    bench.setConfig(c);
    showStyle();
    builder.connectionChanged();
    purpose.connectionChanged();
    dashboard.connectionChanged();
    refreshActive();
  }

  /** 라디오를 지금 DB 에 맞춘다. Oracle 이 아니면 옛 방식을 막고 까닭을 옆에 보인다(기억한 선택은 그대로 둔다). */
  private void showStyle() {
    boolean oracle = bench.kind() == DbKind.ORACLE;
    oldStyle.setEnabled(oracle);
    (bench.style() == QueryBuilder.JoinStyle.OLD ? oldStyle : ansiStyle).setSelected(true);
    styleReason.setText(oracle ? "" : bench.kind() + " 에는 (+) 가 없어 표준 JOIN ON 으로만 만듭니다.");
  }

  private void styleChosen() {
    if (!oldStyle.isEnabled()) return;
    QueryBuilder.JoinStyle s = oldStyle.isSelected() ? QueryBuilder.JoinStyle.OLD : QueryBuilder.JoinStyle.ANSI;
    PropertiesComponent.getInstance().setValue(STYLE_KEY, s.name());
    bench.setStyle(s);
  }

  private void bindChosen() {
    QueryBuilder.Bind b = bindMybatis.isSelected() ? QueryBuilder.Bind.MYBATIS : bindValue.isSelected() ? QueryBuilder.Bind.VALUE : QueryBuilder.Bind.COLON;
    PropertiesComponent.getInstance().setValue(BIND_KEY, b.name());
    bench.setBind(b);
  }

  /** 지금 탭이 SQL 을 만드는 탭이면 다시 만든다. 「쉬운 설명」 · 「도구」 는 SQL 칸을 그대로 둔다. */
  private void refreshActive() {
    switch (tabs.getSelectedIndex()) {
      case 0 -> builder.changed();
      case 1 -> purpose.refresh();
      case 2 -> dashboard.refresh();
      case 3 -> examples.refresh();
      case 4 -> explain.refresh();
      default -> { }
    }
  }

  /** 탭이 만든 SQL 과 알림을 아래에 놓는다. 빈 SQL 이면 SQL 칸은 그대로 두고 알림만 바꾼다. */
  private void fill(String text, List<String> lines) {
    if (!text.isEmpty() || tabs.getSelectedIndex() <= 3) {
      filling = true;
      try {
        sql.setText(text);
        sql.setCaretPosition(0);
      } finally {
        filling = false;
      }
    }
    notes.setText(lines.isEmpty() ? "" : "· " + String.join("\n· ", lines));
    setStatus("", false);
  }

  private void setStatus(String text, boolean error) {
    status.setForeground(error ? UIUtil.getErrorForeground() : UIUtil.getContextHelpForeground());
    status.setText(text);
  }

  /** 콘솔에서 열었으면 그 콘솔 끝에 붙이고, 아니면 고른 접속의 새 콘솔을 연다. */
  private void insert() {
    String text = sql.getText().strip();
    DbConfig c = bench.config();
    if (text.isEmpty() || c == null) return;
    if (console != null && ConsoleOpener.append(project, console, text)) {
      setStatus("콘솔 끝에 넣었습니다.", false);
      return;
    }
    ConsoleOpener.open(project, c, text);
    setStatus("콘솔을 열고 넣었습니다.", false);
  }

  private void run() {
    String text = sql.getText().strip();
    DbConfig c = bench.config();
    if (text.isEmpty() || c == null) return;
    ConsoleRun.runSql(project, c, text);
  }

  /** SQL 칸의 FROM 표와 연결 조건을 ERD 창에 그린다. 연결이 없는 표는 빨간 테두리. */
  private void diagram() {
    DbConfig c = bench.config();
    String text = sql.getText();
    if (c == null || text.isBlank()) return;
    SqlLint.Graph g;
    try {
      g = SqlLint.graph(text);
    } catch (RuntimeException e) {
      setStatus("FROM 을 읽지 못했습니다.", true);
      return;
    }
    Map<String, String> tableOf = new HashMap<>();
    Set<String> tables = new LinkedHashSet<>();
    for (SqlParts.Source s : g.sources()) {
      if (s.subquery()) continue;
      String name = metaName(c.kind, s.name());
      tableOf.put(s.ref().toUpperCase(Locale.ROOT), name);
      tables.add(name);
    }
    if (tables.isEmpty()) {
      setStatus("FROM 에 표가 없습니다.", true);
      return;
    }
    List<Meta.ForeignKey> links = new ArrayList<>();
    for (SqlLint.Link l : g.links()) {
      String left = tableOf.get(l.leftRef().toUpperCase(Locale.ROOT));
      String right = tableOf.get(l.rightRef().toUpperCase(Locale.ROOT));
      if (left == null || right == null || l.leftColumn().isEmpty()) continue;
      links.add(new Meta.ForeignKey("조인", right, metaName(c.kind, l.rightColumn()), left, metaName(c.kind, l.leftColumn())));
    }
    Set<String> unlinked = new java.util.HashSet<>();
    for (String ref : SqlLint.unlinked(g)) {
      String name = tableOf.get(ref.toUpperCase(Locale.ROOT));
      if (name != null) unlinked.add(name.toUpperCase(Locale.ROOT));
    }
    ErdView.openQuery(project, c, bench.schema(), new ArrayList<>(tables), links, unlinked);
    setStatus(unlinked.isEmpty() ? "ERD 창에 그렸습니다." : "연결 조건이 없는 표(빨간 테두리): " + String.join(", ", unlinked), !unlinked.isEmpty());
  }

  /** 쿼리 속 이름 → 메타 이름. 따옴표로 감싼 이름은 그대로, 아니면 Oracle 대문자 · 그 밖은 소문자(DB 가 접어 두는 꼴). */
  private static String metaName(DbKind kind, String name) {
    String n = SqlParts.simpleName(name);
    if (n.startsWith("\"") && n.endsWith("\"") && n.length() > 1) return n.substring(1, n.length() - 1);
    return kind == DbKind.ORACLE ? n.toUpperCase(Locale.ROOT) : n.toLowerCase(Locale.ROOT);
  }

  private static JPanel flow(JComponent... items) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    for (JComponent c : items) p.add(c);
    return p;
  }
}
