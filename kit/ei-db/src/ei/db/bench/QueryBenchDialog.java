package ei.db.bench;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.console.ConsoleOpener;
import ei.db.console.ConsoleRun;
import ei.db.console.ExplainPlan;
import ei.db.console.SqlParams;
import ei.db.gen.ErdView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 쿼리 작업대. 모달이 아니라 콘솔 · 결과 창을 오가며 쓴다. 처음에는 화면의 FIRST_SIZE 비율로 열고, 사용자가 바꾼 크기는 IntelliJ 가 기억한다.
 * 위: 접속 · 연결 모양 · 값 자리 · 「자세히」. 가운데: 탭(첫 탭은 「무엇을 할까요?」 시작 화면).
 * SQL 칸(고칠 수 있음) · 알림 줄 · 미리 보기 결과는 한 묶음(preview)이고, 탭을 바꿀 때마다 그 탭의 입력 칸 바로 밑(slot)으로 옮겨 붙인다.
 * 아래 단추 줄: 왼쪽 복사 · 실행 · 실행해 보기(PREVIEW_ROWS 건) · 건수 보기 · 조인 그림 · 되돌리기, 오른쪽 편집기에 넣기(파란 단추) · 닫기.
 * 연결 모양 · 값 자리 · 「자세히」 · 마지막 접속은 기억한다. PostgreSQL · MySQL 은 (+) 가 없어 표준 JOIN ON 으로만 만든다.
 * 콘솔에서 SQL 을 들고 열면 「쉬운 설명」 탭, 그 밖에는 시작 화면으로 연다.
 */
public final class QueryBenchDialog extends DialogWrapper {
  private static final double FIRST_SIZE = 0.85;
  private static final float BUILDER_SPLIT = 0.6f;
  private static final float EXPLAIN_SPLIT = 0.4f;
  private static final float TOOLS_SPLIT = 0.45f;
  private static final float RESULT_SPLIT = 0.55f;
  private static final int PREVIEW_ROWS = 20;
  private static final int UNDO_LIMIT = 30;
  private static final int START_COLUMNS = 3;
  private static final String KEY = "ei.db.bench.";
  private static final String STYLE_KEY = KEY + "joinStyle";
  private static final String BIND_KEY = KEY + "bind";
  private static final String DETAILS_KEY = KEY + "details";
  private static final String CONFIG_KEY = KEY + "config";

  private static final int START_TAB = 0;
  private static final int BUILDER_TAB = 1;
  private static final int PURPOSE_TAB = 2;
  private static final int DASHBOARD_TAB = 3;
  private static final int EXAMPLES_TAB = 4;
  private static final int EXPLAIN_TAB = 5;
  private static final int TOOLS_TAB = 6;
  private static final int TOOL_IN = 0;
  private static final int TOOL_DIALECT = 1;

  private final Project project;
  private final VirtualFile console;
  private final Bench bench;
  private final ComboBox<DbConfig> connection = new ComboBox<>();
  private final JBRadioButton oldStyle = new JBRadioButton("옛 방식 (+)");
  private final JBRadioButton ansiStyle = new JBRadioButton("표준 JOIN ON");
  private final JBLabel styleReason = new JBLabel();
  private final JBRadioButton bindColon = new JBRadioButton(":이름");
  private final JBRadioButton bindMybatis = new JBRadioButton("#{이름}  (MyBatis)");
  private final JBRadioButton bindValue = new JBRadioButton("값 그대로");
  private final JBCheckBox details = new JBCheckBox("자세히");
  private final JBCheckBox lint = new JBCheckBox("실행할 때마다 쿼리 점검");
  private final JBTextArea sql = new JBTextArea();
  private final JBTextArea notes = new JBTextArea();
  private final JBLabel status = new JBLabel();
  private final JPanel noteLine = new JPanel(new BorderLayout(JBUI.scale(8), 0));
  private final DefaultTableModel resultModel = new DefaultTableModel() {
    @Override
    public boolean isCellEditable(int row, int column) { return false; }
  };
  private final JBLabel resultTitle = new JBLabel();
  private final JPanel resultPanel = new JPanel(new BorderLayout());
  private final OnePixelSplitter sqlAndResult = new OnePixelSplitter(true, RESULT_SPLIT);
  private final JPanel preview = new JPanel(new BorderLayout());
  private final JPanel[] slots = new JPanel[TOOLS_TAB + 1];
  private final JBTabbedPane tabs = new JBTabbedPane();
  private final Deque<String> undo = new ArrayDeque<>();
  private final Action undoAction = action("되돌리기", this::undo);
  private final Action insertAction = action("편집기에 넣기", this::insert);
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
    for (int i = 0; i < slots.length; i++) slots[i] = new JPanel(new BorderLayout());
    builder = new BuilderPanel(bench);
    purpose = new PatternPanel(bench, false);
    dashboard = new PatternPanel(bench, true);
    examples = new ExamplePanel(bench, slots[EXAMPLES_TAB]);
    explain = new ExplainPanel(sql::getText);
    tools = new ToolsPanel(bench, sql::getText);
    bench.onReplace(this::remember);
    setTitle("쿼리 작업대");
    setModal(false);
    getCancelAction().putValue(Action.NAME, "닫기");
    undoAction.setEnabled(false);
    init();
    restore(c);
    if (initialSql != null && !initialSql.isBlank()) {
      sql.setText(initialSql.strip());
      tabs.setSelectedIndex(EXPLAIN_TAB);
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
  protected Action @NotNull [] createLeftSideActions() {
    return new Action[]{
        action("복사", () -> {
          CopyPasteManager.getInstance().setContents(new StringSelection(sql.getText()));
          setStatus("복사했습니다.", false);
        }),
        action("실행", this::run),
        action("실행해 보기 (" + PREVIEW_ROWS + "건)", this::tryRun),
        action("건수 보기", () -> {
          if (sql.getText().isBlank()) return;
          setStatus("세는 중…", false);
          bench.count(sql.getText(), t -> setStatus("건수: " + t, false));
        }),
        action("조인 그림", this::diagram),
        undoAction};
  }

  @Override
  protected Action @NotNull [] createActions() { return new Action[]{insertAction, getCancelAction()}; }

  /** 「편집기에 넣기」 는 파란 단추로 보이되 Enter 기본 단추로 두지 않는다(칸에서 Enter 를 쳐도 콘솔에 들어가지 않게). */
  @Override
  protected JButton createJButtonForAction(Action action) {
    JButton b = super.createJButtonForAction(action);
    if (action == insertAction) ToolsPanel.blue(b);
    return b;
  }

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
    oldStyle.setToolTipText("FROM 에 표를 쉼표로 늘어놓고 WHERE 에 a.X = b.X(+) 로 잇습니다(Oracle 만).");
    ansiStyle.setToolTipText("FROM a JOIN b ON a.X = b.X 로 잇습니다(모든 DB).");
    bindColon.setToolTipText("WHERE X = :X  — 콘솔에서 실행할 때 값을 묻습니다.");
    bindMybatis.setToolTipText("WHERE X = #{X}  — 매퍼 XML 에 바로 붙여 넣습니다. < · > 는 CDATA 로 감쌉니다.");
    bindValue.setToolTipText("WHERE X = '값'  — 칸에 친 값을 그대로 넣습니다.");
    details.setToolTipText("작성기에 별칭 · 묶은 뒤 조건(HAVING) · 건너뛸 건수 · DISTINCT 칸을 더 보입니다.");
    lint.setToolTipText("콘솔에서 실행할 때마다 조인 빠짐 · = NULL · NOT IN 같은 실수를 먼저 봅니다.");
    styleReason.setForeground(UIUtil.getContextHelpForeground());
    JPanel top = FormBuilder.createFormBuilder()
        .addLabeledComponent("접속", PatternPanel.full(connection))
        .addLabeledComponent("JOIN 모양", flow(oldStyle, ansiStyle))
        .addComponentToRightColumn(styleReason)
        .addLabeledComponent("값 자리", flow(bindColon, bindMybatis, bindValue))
        .addComponentToRightColumn(flow(details, lint))
        .getPanel();
    top.setBorder(JBUI.Borders.emptyBottom(6));

    buildPreview();
    tabs.addTab("무엇을 할까요?", startPage());
    tabs.addTab("단계별 작성기", split(BUILDER_SPLIT, "builderSplit", builder.component(), slots[BUILDER_TAB]));
    tabs.addTab("목적으로 만들기", stack(purpose.component(), slots[PURPOSE_TAB]));
    tabs.addTab("현황 · 집계", stack(dashboard.component(), slots[DASHBOARD_TAB]));
    tabs.addTab("예제 모음", examples.component());
    tabs.addTab("쉬운 설명", split(EXPLAIN_SPLIT, "explainSplit", slots[EXPLAIN_TAB], explain.component()));
    tabs.addTab("도구", split(TOOLS_SPLIT, "toolsSplit", tools.component(), slots[TOOLS_TAB]));
    tabs.addChangeListener(e -> tabChanged());

    JPanel root = new JPanel(new BorderLayout());
    root.add(top, BorderLayout.NORTH);
    root.add(tabs, BorderLayout.CENTER);

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

  /** SQL 칸(테두리 한 겹) · 알림 줄(비면 숨김) · 미리 보기 결과(실행해 보기 뒤에만)를 한 묶음으로 만든다. */
  private void buildPreview() {
    sql.setFont(EditorFontType.getGlobalPlainFont());
    sql.setTabSize(4);
    sql.getEmptyText().setText("만든 SQL 이 여기에 나옵니다. 직접 쓰거나 붙여 넣어 「쉬운 설명」 · 「도구」 에 쓸 수도 있습니다.");
    notes.setEditable(false);
    notes.setLineWrap(true);
    notes.setWrapStyleWord(true);
    notes.setOpaque(false);
    notes.setForeground(UIUtil.getContextHelpForeground());
    noteLine.setBorder(JBUI.Borders.empty(4, 2, 0, 2));
    noteLine.add(notes, BorderLayout.CENTER);
    noteLine.add(status, BorderLayout.EAST);
    noteLine.setVisible(false);

    JPanel sqlPart = new JPanel(new BorderLayout());
    sqlPart.add(ScrollPaneFactory.createScrollPane(sql), BorderLayout.CENTER);
    sqlPart.add(noteLine, BorderLayout.SOUTH);

    JBTable table = new JBTable(resultModel);
    table.setAutoResizeMode(JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    resultTitle.setBorder(JBUI.Borders.empty(4, 2));
    resultTitle.setForeground(UIUtil.getContextHelpForeground());
    resultPanel.add(resultTitle, BorderLayout.NORTH);
    resultPanel.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
    resultPanel.setVisible(false);

    sqlAndResult.setSplitterProportionKey(KEY + "resultSplit");
    sqlAndResult.setHonorComponentsMinimumSize(false);
    sqlAndResult.setFirstComponent(sqlPart);
    sqlAndResult.setSecondComponent(resultPanel);
    preview.add(sqlAndResult, BorderLayout.CENTER);
  }

  /** 「무엇을 할까요?」: 하고 싶은 일을 누르면 맞는 탭 · 무늬 · 도구를 연다. 단추마다 한 줄 안내를 붙인다. */
  private JComponent startPage() {
    JPanel grid = new JPanel(new GridLayout(0, START_COLUMNS, JBUI.scale(10), JBUI.scale(10)));
    grid.add(card("표 하나 보기", "표를 고르고 볼 칼럼 · 조건을 채웁니다.", () -> tabs.setSelectedIndex(BUILDER_TAB)));
    grid.add(card("두 표 이어 보기", "표를 둘 넣으면 잇는 조건(외래 키)을 먼저 채웁니다.", () -> tabs.setSelectedIndex(BUILDER_TAB)));
    grid.add(card("건수 · 합계 보기", "전체 · 그룹별 건수와 합계 · 평균.", () -> pattern(DASHBOARD_TAB, Patterns.Kind.STATS)));
    grid.add(card("기간별 현황", "일 · 주 · 월 · 연도마다 건수 · 합계.", () -> pattern(DASHBOARD_TAB, Patterns.Kind.BY_PERIOD)));
    grid.add(card("중복 찾기", "같은 값이 2건 이상인 것과 그 건수.", () -> pattern(PURPOSE_TAB, Patterns.Kind.DUPLICATES)));
    grid.add(card("남이 쓴 쿼리 이해하기", "붙여 넣은 SQL 을 절마다 쉬운 말로 풀고 실수를 짚습니다.", () -> tabs.setSelectedIndex(EXPLAIN_TAB)));
    grid.add(card("DB 바꿔 쓰기", "Oracle ↔ PostgreSQL 문법으로 바꿉니다.", () -> tool(TOOL_DIALECT)));
    grid.add(card("값 목록으로 IN 만들기", "엑셀에서 복사한 값으로 IN (…) 을 만듭니다.", () -> tool(TOOL_IN)));
    grid.add(card("예제 보기", "실무 쿼리 " + Examples.ALL.size() + "개를 찾아 씁니다.", () -> tabs.setSelectedIndex(EXAMPLES_TAB)));
    JPanel page = new JPanel(new BorderLayout());
    page.setBorder(JBUI.Borders.empty(16));
    page.add(grid, BorderLayout.NORTH);
    // 스크롤 판에 넣으면 칸이 창 폭을 넘겨 오른쪽 열이 잘린다. 단추 9개는 높이가 넉넉해 창 폭에 맞춘다.
    return page;
  }

  private static JComponent card(String title, String hint, Runnable go) {
    JButton b = new JButton(title);
    b.setHorizontalAlignment(SwingConstants.LEFT);
    b.addActionListener(e -> go.run());
    JBLabel help = new JBLabel(hint);
    help.setForeground(UIUtil.getContextHelpForeground());
    help.setAllowAutoWrapping(true);
    JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    p.add(b, BorderLayout.NORTH);
    p.add(help, BorderLayout.CENTER);
    return p;
  }

  private void pattern(int tab, Patterns.Kind k) {
    tabs.setSelectedIndex(tab);
    (tab == DASHBOARD_TAB ? dashboard : purpose).select(k);
  }

  private void tool(int index) {
    tabs.setSelectedIndex(TOOLS_TAB);
    tools.select(index);
  }

  /** SQL 묶음을 지금 탭의 자리로 옮기고 그 탭의 SQL 을 다시 만든다. */
  private void tabChanged() {
    int i = tabs.getSelectedIndex();
    if (i > START_TAB && i < slots.length) {
      slots[i].add(preview, BorderLayout.CENTER);
      slots[i].revalidate();
      slots[i].repaint();
    }
    refreshActive();
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
    tabs.setSelectedIndex(START_TAB);
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

  /** 라디오를 지금 DB 에 맞춘다. Oracle 이 아니면 옛 방식을 막고 까닭을 그 밑 줄에 보인다(기억한 선택은 그대로 둔다). */
  private void showStyle() {
    boolean oracle = bench.kind() == DbKind.ORACLE;
    oldStyle.setEnabled(oracle);
    (bench.style() == QueryBuilder.JoinStyle.OLD ? oldStyle : ansiStyle).setSelected(true);
    styleReason.setText(oracle ? "" : bench.kind() + " 에는 (+) 가 없어 표준 JOIN ON 으로만 만듭니다.");
    styleReason.setVisible(!oracle);
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
      case BUILDER_TAB -> builder.changed();
      case PURPOSE_TAB -> purpose.refresh();
      case DASHBOARD_TAB -> dashboard.refresh();
      case EXAMPLES_TAB -> examples.refresh();
      case EXPLAIN_TAB -> explain.refresh();
      default -> { }
    }
  }

  /** 탭이 만든 SQL 과 알림을 놓는다. 「쉬운 설명」 · 「도구」 에서 빈 SQL 이 오면 SQL 칸은 그대로 두고 알림만 바꾼다. */
  private void fill(String text, List<String> lines) {
    if (!text.isEmpty() || tabs.getSelectedIndex() <= EXAMPLES_TAB) {
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

  /** 알림 줄 오른쪽 글. 알림 · 상태가 다 비면 줄을 숨긴다. */
  private void setStatus(String text, boolean error) {
    status.setForeground(error ? UIUtil.getErrorForeground() : UIUtil.getContextHelpForeground());
    status.setText(text);
    noteLine.setVisible(!text.isEmpty() || !notes.getText().isEmpty());
    noteLine.revalidate();
  }

  /** 도구가 SQL 칸을 바꾸기 직전 문장을 남긴다(빈 칸 · 같은 문장은 남기지 않는다). */
  private void remember() {
    String now = sql.getText();
    if (now.isBlank() || now.equals(undo.peek())) return;
    undo.push(now);
    while (undo.size() > UNDO_LIMIT) undo.removeLast();
    undoAction.setEnabled(true);
  }

  private void undo() {
    String last = undo.poll();
    undoAction.setEnabled(!undo.isEmpty());
    if (last == null) return;
    filling = true;
    try {
      sql.setText(last);
      sql.setCaretPosition(0);
    } finally {
      filling = false;
    }
    notes.setText("");
    setStatus("도구로 바꾸기 전 문장으로 되돌렸습니다.", false);
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

  /** SELECT · WITH 만 앞 PREVIEW_ROWS 건을 읽어 SQL 칸 밑 표에 보인다. 값 자리가 있으면 콘솔과 같이 값을 묻는다(취소하면 안 함). */
  private void tryRun() {
    String text = sql.getText().strip();
    DbConfig c = bench.config();
    if (text.isEmpty() || c == null) return;
    if (!ExplainPlan.isQuery(text)) {
      setStatus("「실행해 보기」 는 SELECT · WITH 만 됩니다. 바꾸는 문장은 「실행」 으로 콘솔에서 돌리세요.", true);
      return;
    }
    SqlParams.Bound b = ConsoleRun.bind(project, text);
    if (b == null) return;
    setStatus("읽는 중…", false);
    bench.load(() -> DbSessions.get(project).run(c, b.sql(), b.binds(), PREVIEW_ROWS), this::showResult);
  }

  private void showResult(QueryResult r) {
    Object[][] data = new Object[r.rows().size()][];
    for (int i = 0; i < data.length; i++) data[i] = r.rows().get(i).toArray();
    resultModel.setDataVector(data, r.columns().toArray());
    resultTitle.setText(String.format(Locale.ROOT, "앞 %d건%s · %d ms", r.rows().size(), r.truncated() ? " (더 있음)" : "", r.millis()));
    resultPanel.setVisible(true);
    sqlAndResult.revalidate();
    setStatus("", false);
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

  private static Action action(String name, Runnable r) {
    return new AbstractAction(name) {
      @Override
      public void actionPerformed(ActionEvent e) { r.run(); }
    };
  }

  /** 위아래로 나눈 칸. 나눈 비율은 key 로 기억한다. */
  private static JComponent split(float proportion, String key, JComponent first, JComponent second) {
    OnePixelSplitter s = new OnePixelSplitter(true, proportion);
    s.setSplitterProportionKey(KEY + key);
    s.setHonorComponentsMinimumSize(false);
    s.setFirstComponent(first);
    s.setSecondComponent(second);
    return s;
  }

  /** 입력 칸은 위에 원래 높이로, SQL 자리는 남은 높이를 다 쓴다. */
  private static JComponent stack(JComponent form, JComponent slot) {
    JPanel p = new JPanel(new BorderLayout());
    p.add(form, BorderLayout.NORTH);
    p.add(slot, BorderLayout.CENTER);
    return p;
  }

  private static JPanel flow(JComponent... items) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    for (JComponent c : items) p.add(c);
    return p;
  }
}
