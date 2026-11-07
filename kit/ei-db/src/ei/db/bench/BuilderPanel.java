package ei.db.bench;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.Meta;
import ei.db.QueryResult;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 단계별 작성기: 왼쪽에서 표를 고르고 오른쪽 단계(칼럼 → 연결 → 조건 → 묶기 → 정렬 · 페이징)를 채우면 아래 미리 보기에 SQL 이 바로 나온다.
 * 표를 더하면 그 표의 칼럼 · 외래 키를 읽어 연결을 추천한다(외래 키가 없으면 이름으로 짐작해 「추정」 표시).
 * 「바꿀 때마다 건수 세기」 가 켜져 있으면 바꾼 뒤 COUNT_DEBOUNCE_MS 동안 더 바꾸지 않을 때 지금 조건의 건수를 센다.
 * 표 · 연결을 바꾼 뒤 건수가 늘면 연결 조건이 빠졌거나 1:N 이라고 알린다.
 */
final class BuilderPanel {
  private static final int COUNT_DEBOUNCE_MS = 700;
  private static final float LEFT_SPLIT = 0.28f;
  private static final int MAX_LIMIT = 100_000;
  private static final String NONE = "—";
  private static final String KEY = "ei.db.bench.builder.";

  private final Bench bench;
  private final JPanel root = new JPanel(new BorderLayout());
  private final CollectionListModel<Meta.Table> allTables = new CollectionListModel<>();
  private final List<Meta.Table> loadedTables = new ArrayList<>();
  private final JBList<Meta.Table> tableList = new JBList<>(allTables);
  private final SearchTextField search = new SearchTextField(false);
  private final CollectionListModel<QueryBuilder.Table> chosen = new CollectionListModel<>();
  private final JBList<QueryBuilder.Table> chosenList = new JBList<>(chosen);
  private final JBLabel tableStatus = new JBLabel();

  private final Rows<ColRow> cols = new Rows<>(List.of(
      new Rows.Column<ColRow>("고름", Boolean.class, r -> r.selected, (r, v) -> r.selected = (Boolean) v),
      new Rows.Column<ColRow>("표", String.class, r -> r.table + " (" + r.alias + ")", null),
      new Rows.Column<ColRow>("칼럼", String.class, r -> r.col.name(), null),
      new Rows.Column<ColRow>("형식", String.class, r -> r.col.typeLabel(), null),
      new Rows.Column<ColRow>("설명", String.class, r -> r.col.comment(), null),
      new Rows.Column<ColRow>("집계", Object.class, r -> r.agg == null ? NONE : r.agg.label, (r, v) -> { r.agg = agg(v); if (r.agg != null) r.selected = true; }),
      new Rows.Column<ColRow>("별칭 (AS)", String.class, r -> r.as, (r, v) -> r.as = String.valueOf(v).strip())), this::changed);
  private final JBTable colTable = new JBTable(cols);

  private final Rows<JoinRow> joins = new Rows<>(List.of(
      new Rows.Column<JoinRow>("사용", Boolean.class, r -> r.use, (r, v) -> r.use = (Boolean) v),
      new Rows.Column<JoinRow>("왼쪽 (앞 표)", String.class, r -> r.j.leftAlias() + "." + r.j.leftColumn(), null),
      new Rows.Column<JoinRow>("오른쪽 (뒤 표)", String.class, r -> r.j.rightAlias() + "." + r.j.rightColumn(), null),
      new Rows.Column<JoinRow>("방식", Object.class, r -> r.outer ? OUTER : INNER, (r, v) -> r.outer = OUTER.equals(v)),
      new Rows.Column<JoinRow>("근거", String.class, r -> r.j.guessed() ? "추정 (이름이 같음) — 확인하세요" : r.manual ? "직접 넣음" : "외래 키", null)), this::changed);
  private final JBTable joinTable = new JBTable(joins);
  private static final String INNER = "양쪽에 다 있는 것만 (안쪽 조인)";
  private static final String OUTER = "앞 표는 모두 남기기 (바깥 조인)";

  private final Rows<CondRow> conds = new Rows<>(List.of(
      new Rows.Column<CondRow>("칼럼", Object.class, r -> r.ref(), (r, v) -> r.column(String.valueOf(v))),
      new Rows.Column<CondRow>("조건", Object.class, r -> r.op, (r, v) -> r.op = (QueryBuilder.Op) v),
      new Rows.Column<CondRow>("값 (비우면 실행할 때 넣음)", String.class, r -> r.value, (r, v) -> r.value = String.valueOf(v)),
      new Rows.Column<CondRow>("값 2 (사이 · 기간의 끝)", String.class, r -> r.value2, (r, v) -> r.value2 = String.valueOf(v))), this::changed);
  private final JBTable condTable = new JBTable(conds) {
    @Override
    public TableCellEditor getCellEditor(int row, int column) {
      int c = convertColumnIndexToModel(column);
      int r = convertRowIndexToModel(row);
      if (c == 0) return new DefaultCellEditor(new ComboBox<>(columnRefs().toArray(new String[0])));
      if (c == 1 && r < conds.rows.size()) {
        List<QueryBuilder.Op> ops = new ArrayList<>();
        for (QueryBuilder.Op op : QueryBuilder.Op.values()) if (op.fits(conds.rows.get(r).kind)) ops.add(op);
        return new DefaultCellEditor(new ComboBox<>(ops.toArray(new QueryBuilder.Op[0])));
      }
      return super.getCellEditor(row, column);
    }
  };

  private final Rows<HavingRow> havings = new Rows<>(List.of(
      new Rows.Column<HavingRow>("집계", Object.class, r -> r.label, (r, v) -> r.label = String.valueOf(v)),
      new Rows.Column<HavingRow>("비교", Object.class, r -> r.op, (r, v) -> r.op = String.valueOf(v)),
      new Rows.Column<HavingRow>("값", String.class, r -> r.value, (r, v) -> r.value = String.valueOf(v))), this::changed);
  private final JBTable havingTable = new JBTable(havings) {
    @Override
    public TableCellEditor getCellEditor(int row, int column) {
      int c = convertColumnIndexToModel(column);
      if (c == 0) return new DefaultCellEditor(new ComboBox<>(aggLabels().toArray(new String[0])));
      if (c == 1) return new DefaultCellEditor(new ComboBox<>(QueryBuilder.COMPARE.toArray(new String[0])));
      return super.getCellEditor(row, column);
    }
  };

  private final Rows<OrderRow> orders = new Rows<>(List.of(
      new Rows.Column<OrderRow>("칼럼", Object.class, r -> r.expr, (r, v) -> r.expr = String.valueOf(v)),
      new Rows.Column<OrderRow>("방향", Object.class, r -> r.desc ? DESC : ASC, (r, v) -> r.desc = DESC.equals(v))), this::changed);
  private static final String ASC = "작은 것부터 (ASC)";
  private static final String DESC = "큰 것부터 (DESC)";
  private final JBTable orderTable = new JBTable(orders) {
    @Override
    public TableCellEditor getCellEditor(int row, int column) {
      int c = convertColumnIndexToModel(column);
      if (c == 0) return new DefaultCellEditor(new ComboBox<>(orderCandidates().toArray(new String[0])));
      if (c == 1) return new DefaultCellEditor(new ComboBox<>(new String[]{ASC, DESC}));
      return super.getCellEditor(row, column);
    }
  };

  private final JBCheckBox countAll = new JBCheckBox("전체 건수 칼럼 넣기 (COUNT(*) AS CNT)");
  private final JBCheckBox distinct = new JBCheckBox("똑같은 줄은 하나만 (DISTINCT)");
  private final JBLabel groupLabel = new JBLabel();
  /** 5단계에서 직접 고른 묶을 칼럼 「별칭.칼럼」. 표를 빼면 그 표 것은 spec 에서 저절로 빠진다. */
  private final List<String> groupRefs = new ArrayList<>();
  private final CollectionListModel<String> groupModel = new CollectionListModel<>();
  private final JBList<String> groupList = new JBList<>(groupModel);
  private final JBIntSpinner limit = new JBIntSpinner(0, 0, MAX_LIMIT);
  private final JBIntSpinner offset = new JBIntSpinner(0, 0, MAX_LIMIT);
  private final JPanel havingPanel = new JPanel(new BorderLayout());
  private final JPanel offsetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
  private final JBCheckBox autoCount = new JBCheckBox("바꿀 때마다 건수 세기", true);
  private final JBLabel countLabel = new JBLabel();
  private final Timer countTimer = new Timer(COUNT_DEBOUNCE_MS, e -> count());
  private final List<Section> sections = new ArrayList<>();
  /** 표 하나가 보일 줄 수(이보다 많으면 표 안에서 스크롤). */
  private static final int VISIBLE_ROWS = 8;

  /** 건수 세기 순번. 늦게 온 앞 결과는 버린다. */
  private int countGeneration;
  private Long lastCount;
  private int lastShape = -1;

  private static final class ColRow {
    final String table;
    final String alias;
    final Meta.Column col;
    boolean selected;
    QueryBuilder.Agg agg;
    String as = "";

    ColRow(String table, String alias, Meta.Column col) { this.table = table; this.alias = alias; this.col = col; }
  }

  private static final class JoinRow {
    final QueryBuilder.Join j;
    boolean use = true;
    boolean outer;
    final boolean manual;

    JoinRow(QueryBuilder.Join j, boolean manual) { this.j = j; this.manual = manual; this.outer = j.outer(); }
  }

  private final class CondRow {
    String alias;
    String name;
    QueryBuilder.ColKind kind = QueryBuilder.ColKind.OTHER;
    QueryBuilder.Op op = QueryBuilder.Op.EQ;
    String value = "";
    String value2 = "";

    String ref() { return alias + "." + name; }

    void column(String ref) {
      int dot = ref.indexOf('.');
      if (dot < 0) return;
      alias = ref.substring(0, dot);
      name = ref.substring(dot + 1);
      kind = QueryBuilder.kindOf(typeOf(alias, name));
      if (!op.fits(kind)) op = QueryBuilder.Op.EQ;
    }
  }

  private static final class HavingRow {
    String label;
    String op = ">";
    String value = "0";
  }

  private static final class OrderRow {
    String expr;
    boolean desc;
  }

  BuilderPanel(Bench bench) {
    this.bench = bench;
    countTimer.setRepeats(false);
    build();
  }

  JComponent component() { return root; }

  void setDetails(boolean on) {
    distinct.setVisible(on);
    havingPanel.setVisible(on);
    offsetRow.setVisible(on);
    int alias = colTable.getColumnModel().getColumnCount() > 6 ? 6 : -1;
    if (alias >= 0) {
      javax.swing.table.TableColumn c = colTable.getColumnModel().getColumn(alias);
      c.setMinWidth(on ? JBUI.scale(1) : 0);
      c.setMaxWidth(on ? Integer.MAX_VALUE : 0);
      if (on && c.getPreferredWidth() == 0) c.setPreferredWidth(JBUI.scale(80));
    }
  }

  private void build() {
    tableList.setCellRenderer(new ColoredListCellRenderer<>() {
      @Override
      protected void customizeCellRenderer(@NotNull JList<? extends Meta.Table> list, Meta.Table t, int index, boolean selected, boolean focus) {
        setIcon("VIEW".equals(t.type()) ? AllIcons.Nodes.DataSchema : AllIcons.Nodes.DataTables);
        append(t.name());
        if (!t.comment().isEmpty()) append("  " + t.comment(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
    });
    tableList.getEmptyText().setText("접속을 고르면 표 목록을 읽습니다");
    tableList.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) addSelectedTables();
      }
    });
    tableList.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) addSelectedTables();
      }
    });
    search.addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) { filter(); }
    });
    search.getTextEditor().getEmptyText().setText("표 이름 · 설명으로 찾기");
    chosenList.setCellRenderer(new ColoredListCellRenderer<>() {
      @Override
      protected void customizeCellRenderer(@NotNull JList<? extends QueryBuilder.Table> list, QueryBuilder.Table t, int index, boolean selected, boolean focus) {
        setIcon(AllIcons.Nodes.DataTables);
        append(t.name());
        append("  " + t.alias(), SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
      }
    });
    chosenList.getEmptyText().setText("위 목록에서 두 번 눌러 표를 더하세요");
    JButton add = new JButton("표 더하기", AllIcons.General.Add);
    add.addActionListener(e -> addSelectedTables());
    JButton remove = new JButton("표 빼기", AllIcons.General.Remove);
    remove.addActionListener(e -> removeSelectedTable());
    JPanel tableButtons = buttons(add, remove);

    JPanel left = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    top.add(new JBLabel("1. 표 고르기"), BorderLayout.NORTH);
    top.add(search, BorderLayout.CENTER);
    JBSplitter tablesSplit = new JBSplitter(true, 0.6f);
    tablesSplit.setHonorComponentsMinimumSize(false);
    tablesSplit.setFirstComponent(ScrollPaneFactory.createScrollPane(tableList));
    left.setBorder(JBUI.Borders.empty(4, 8, 8, 0));
    JPanel chosenPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    chosenPanel.add(tableButtons, BorderLayout.NORTH);
    chosenPanel.add(ScrollPaneFactory.createScrollPane(chosenList), BorderLayout.CENTER);
    chosenPanel.add(tableStatus, BorderLayout.SOUTH);
    tablesSplit.setSecondComponent(chosenPanel);
    left.add(top, BorderLayout.NORTH);
    left.add(tablesSplit, BorderLayout.CENTER);

    // 2. 칼럼
    colTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer());
    colTable.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(new ComboBox<>(aggChoices())));
    colTable.getEmptyText().setText("표를 더하면 칼럼이 여기에 나옵니다. 안 고르면 모든 칼럼(*)");
    Rows.widths(colTable, KEY + "cols", 1, 2, 4, 3, 5, 3, 3);
    countAll.addActionListener(e -> changed());
    distinct.addActionListener(e -> changed());
    JPanel colPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    JButton selectAll = new JButton("모두 고르기");
    selectAll.addActionListener(e -> selectAll(selectAll));
    JButton selectNone = new JButton("모두 풀기");
    selectNone.addActionListener(e -> {
      for (ColRow r : cols.rows) r.selected = false;
      cols.fireTableDataChanged();
      changed();
    });
    colPanel.add(stack(buttons(selectAll, selectNone), countAll, distinct), BorderLayout.NORTH);
    colPanel.add(scroll(colTable), BorderLayout.CENTER);

    // 3. 연결
    joinTable.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new ComboBox<>(new String[]{INNER, OUTER})));
    joinTable.getEmptyText().setText("표를 둘 이상 고르면 외래 키를 따라 연결을 추천합니다");
    Rows.widths(joinTable, KEY + "joins", 1, 4, 4, 5, 4);
    JButton suggest = new JButton("다시 추천", AllIcons.Actions.Refresh);
    suggest.addActionListener(e -> suggestJoins(true));
    JButton addJoin = new JButton("연결 직접 넣기", AllIcons.General.Add);
    addJoin.addActionListener(e -> addJoin());
    JButton removeJoin = new JButton("빼기", AllIcons.General.Remove);
    removeJoin.addActionListener(e -> { if (joins.removeSelected(joinTable)) changed(); });
    JPanel joinPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    joinPanel.add(buttons(suggest, addJoin, removeJoin), BorderLayout.NORTH);
    joinPanel.add(scroll(joinTable), BorderLayout.CENTER);

    // 4. 조건
    condTable.getEmptyText().setText("「조건 넣기」 로 거를 조건을 더하세요");
    Rows.widths(condTable, KEY + "conds", 4, 5, 4, 4);
    JButton addCond = new JButton("조건 넣기", AllIcons.General.Add);
    addCond.addActionListener(e -> addCond());
    JButton removeCond = new JButton("빼기", AllIcons.General.Remove);
    removeCond.addActionListener(e -> { if (conds.removeSelected(condTable)) changed(); });
    JPanel condPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    condPanel.add(buttons(addCond, removeCond), BorderLayout.NORTH);
    condPanel.add(scroll(condTable), BorderLayout.CENTER);

    // 5. 묶기
    Rows.widths(havingTable, KEY + "having", 5, 2, 3);
    JButton addHaving = new JButton("묶은 뒤 조건 넣기 (HAVING)", AllIcons.General.Add);
    addHaving.addActionListener(e -> addHaving());
    JButton removeHaving = new JButton("빼기", AllIcons.General.Remove);
    removeHaving.addActionListener(e -> { if (havings.removeSelected(havingTable)) changed(); });
    havingPanel.add(buttons(addHaving, removeHaving), BorderLayout.NORTH);
    havingPanel.add(scroll(havingTable), BorderLayout.CENTER);
    JButton addGroup = new JButton("묶을 칼럼 더하기…", AllIcons.General.Add);
    addGroup.addActionListener(e -> addGroups(addGroup));
    JButton removeGroup = new JButton("빼기", AllIcons.General.Remove);
    removeGroup.addActionListener(e -> {
      boolean any = false;
      for (String v : groupList.getSelectedValuesList()) any |= groupRefs.remove(v);
      if (any) changed();
    });
    groupList.getEmptyText().setText("「묶을 칼럼 더하기…」 로 묶을 칼럼을 고르세요");
    groupList.setVisibleRowCount(VISIBLE_ROWS / 2);
    JPanel groupTop = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    groupTop.add(buttons(addGroup, removeGroup), BorderLayout.NORTH);
    groupTop.add(ScrollPaneFactory.createScrollPane(groupList), BorderLayout.CENTER);
    groupTop.add(groupLabel, BorderLayout.SOUTH);
    JPanel groupPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
    groupPanel.add(groupTop, BorderLayout.NORTH);
    groupPanel.add(havingPanel, BorderLayout.CENTER);

    // 6. 정렬 · 페이징
    Rows.widths(orderTable, KEY + "orders", 5, 3);
    JButton addOrder = new JButton("정렬 넣기", AllIcons.General.Add);
    addOrder.addActionListener(e -> addOrder());
    JButton removeOrder = new JButton("빼기", AllIcons.General.Remove);
    removeOrder.addActionListener(e -> { if (orders.removeSelected(orderTable)) changed(); });
    limit.addChangeListener(e -> changed());
    offset.addChangeListener(e -> changed());
    JPanel limitRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
    limitRow.add(new JBLabel("가져올 건수 (0 = 모두)"));
    limitRow.add(limit);
    offsetRow.add(new JBLabel("건너뛸 건수 (페이지 시작)  "));
    offsetRow.add(offset);
    limitRow.add(offsetRow);
    JPanel orderPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    orderPanel.add(buttons(addOrder, removeOrder), BorderLayout.NORTH);
    orderPanel.add(scroll(orderTable), BorderLayout.CENTER);
    orderPanel.add(limitRow, BorderLayout.SOUTH);

    sections.add(new Section("2. 칼럼 고르기", "결과에 보일 칼럼에 체크하세요. 아무것도 안 고르면 모든 칼럼(*)입니다. 「집계」 에서 건수 · 합계 · 평균을 고르면 묶기는 자동으로 맞춥니다.", colPanel, true));
    sections.add(new Section("3. 표 잇기 (JOIN)", "표가 둘 이상이면 어떤 칼럼으로 이을지 정합니다. 외래 키를 따라 자동으로 넣고, 「추정」 은 이름으로 짐작한 것이라 맞는지 보세요.", joinPanel, false));
    sections.add(new Section("4. 거를 조건 (WHERE)", "「조건 넣기」 → 칼럼 · 조건 · 값을 고릅니다. 값을 비우면 실행할 때 묻고, 넣으면 건수 세기에 씁니다. 여러 값은 쉼표로, 날짜는 " + QueryBuilder.DATE_FORMAT + ".", condPanel, true));
    sections.add(new Section("5. 묶기 (GROUP BY)", "「묶을 칼럼 더하기…」 로 값마다 묶을 칼럼(예: 고객, 상태)을 고르면 SELECT 와 GROUP BY 에 들어가고, 집계를 안 골랐으면 건수(CNT)가 붙습니다. 2단계에서 집계를 고른 경우 집계 아닌 칼럼은 저절로 묶입니다(「자동」).", groupPanel, false));
    sections.add(new Section("6. 정렬 · 건수 자르기", "어떤 순서로 볼지, 몇 건만 볼지 정합니다.", orderPanel, false));
    JPanel page = new JPanel(new com.intellij.ui.components.panels.VerticalLayout(JBUI.scale(10)));
    page.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
    for (int i = 0; i < sections.size(); i++) {
      Section sec = sections.get(i);
      if (i + 1 < sections.size()) {
        Section next = sections.get(i + 1);
        sec.next(() -> {
          next.open(true);
          javax.swing.SwingUtilities.invokeLater(() -> page.scrollRectToVisible(next.panel.getBounds()));
        });
      }
      page.add(sec.panel);
    }
    JBScrollPane pageScroll = new JBScrollPane(page);
    pageScroll.setBorder(JBUI.Borders.empty());
    pageScroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));

    JPanel countRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    countRow.add(autoCount, BorderLayout.WEST);
    countRow.add(countLabel, BorderLayout.CENTER);
    JButton countNow = new JButton("지금 세기");
    countNow.addActionListener(e -> count());
    countRow.add(countNow, BorderLayout.EAST);
    autoCount.addActionListener(e -> { if (autoCount.isSelected()) countTimer.restart(); });

    countRow.setBorder(JBUI.Borders.empty(4, 8));
    JPanel right = new JPanel(new BorderLayout());
    right.add(pageScroll, BorderLayout.CENTER);
    right.add(countRow, BorderLayout.SOUTH);

    JBSplitter split = new JBSplitter(false, LEFT_SPLIT);
    split.setSplitterProportionKey(KEY + "split");
    split.setHonorComponentsMinimumSize(false);
    split.setFirstComponent(left);
    split.setSecondComponent(right);
    root.add(split, BorderLayout.CENTER);
  }

  /** 접속이 바뀌면 표 목록을 다시 읽고 고른 것을 비운다. */
  void connectionChanged() {
    chosen.removeAll();
    cols.replace(List.of());
    joins.replace(List.of());
    conds.replace(List.of());
    havings.replace(List.of());
    orders.replace(List.of());
    allTables.removeAll();
    loadedTables.clear();
    lastCount = null;
    lastShape = -1;
    countLabel.setText("");
    if (bench.config() == null) return;
    tableList.getEmptyText().setText("표 목록을 읽는 중…");
    bench.tables(list -> {
      loadedTables.clear();
      loadedTables.addAll(list);
      tableList.getEmptyText().setText(list.isEmpty() ? "이 스키마에 표가 없습니다" : "찾는 표가 없습니다");
      filter();
    });
  }

  private void filter() {
    String q = search.getText().strip().toLowerCase(Locale.ROOT);
    List<Meta.Table> shown = new ArrayList<>();
    for (Meta.Table t : loadedTables) {
      if (q.isEmpty() || t.name().toLowerCase(Locale.ROOT).contains(q) || t.comment().toLowerCase(Locale.ROOT).contains(q)) shown.add(t);
    }
    allTables.replaceAll(shown);
  }

  private void addSelectedTables() {
    for (Meta.Table t : tableList.getSelectedValuesList()) addTable(t.name());
  }

  /** 표를 더하고 칼럼 · 외래 키를 읽은 뒤 연결을 추천한다. 같은 표를 다시 더하면 다른 별칭(자기 조인). */
  void addTable(String name) {
    Set<String> used = new HashSet<>();
    for (QueryBuilder.Table t : chosen.getItems()) used.add(t.alias());
    QueryBuilder.Table t = new QueryBuilder.Table(name, QueryBuilder.aliasFor(name, used));
    chosen.add(t);
    tableStatus.setText(name + " 칼럼 읽는 중…");
    bench.columns(name, list -> {
      if (!chosen.getItems().contains(t)) return;
      for (Meta.Column c : list) cols.add(new ColRow(t.name(), t.alias(), c));
      tableStatus.setText("");
      bench.foreignKeys(name, fks -> suggestJoins(false));
      changed();
    });
  }

  private void removeSelectedTable() {
    QueryBuilder.Table t = chosenList.getSelectedValue();
    if (t == null) return;
    chosen.remove(t);
    cols.rows.removeIf(r -> r.alias.equals(t.alias()));
    cols.fireTableDataChanged();
    joins.rows.removeIf(r -> r.j.leftAlias().equals(t.alias()) || r.j.rightAlias().equals(t.alias()));
    joins.fireTableDataChanged();
    conds.rows.removeIf(r -> t.alias().equals(r.alias));
    conds.fireTableDataChanged();
    String prefix = t.alias() + ".";
    orders.rows.removeIf(r -> r.expr != null && r.expr.contains(prefix));
    orders.fireTableDataChanged();
    changed();
  }

  /** 추천을 더한다. reset 이면 직접 넣지 않은 연결을 지우고 다시 추천한다. 이미 있는 연결(같은 칼럼 쌍)은 다시 넣지 않는다. */
  private void suggestJoins(boolean reset) {
    if (reset) {
      joins.rows.removeIf(r -> !r.manual);
      joins.fireTableDataChanged();
    }
    Map<String, List<Meta.Column>> columns = new HashMap<>();
    List<Meta.ForeignKey> fks = new ArrayList<>();
    for (QueryBuilder.Table t : chosen.getItems()) {
      columns.put(JoinAdvisor.key(t.name()), bench.cachedColumns(t.name()));
      fks.addAll(bench.cachedForeignKeys(t.name()));
    }
    Set<String> have = new HashSet<>();
    for (JoinRow r : joins.rows) have.add(pairKey(r.j));
    for (QueryBuilder.Join j : JoinAdvisor.suggest(chosen.getItems(), columns, fks)) {
      if (have.add(pairKey(j))) joins.add(new JoinRow(j, false));
    }
    changed();
  }

  private static String pairKey(QueryBuilder.Join j) {
    return j.leftAlias() + "." + j.leftColumn() + "=" + j.rightAlias() + "." + j.rightColumn();
  }

  /** 두 칼럼을 골라 연결을 넣는다. 앞에 고른 표가 왼쪽이 되도록 바꿔 넣는다. */
  private void addJoin() {
    List<String> refs = columnRefs();
    if (chosen.getSize() < 2 || refs.isEmpty()) {
      tableStatus.setText("표를 둘 이상 고른 뒤 넣으세요.");
      return;
    }
    ComboBox<String> leftBox = new ComboBox<>(refs.toArray(new String[0]));
    ComboBox<String> rightBox = new ComboBox<>(refs.toArray(new String[0]));
    JBCheckBox outer = new JBCheckBox(OUTER);
    DialogWrapper d = new DialogWrapper(bench.project, root, false, DialogWrapper.IdeModalityType.IDE) {
      {
        setTitle("연결 직접 넣기");
        init();
      }

      @Override
      protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("앞 표 칼럼", leftBox)
            .addLabeledComponent("뒤 표 칼럼", rightBox)
            .addComponent(outer)
            .getPanel();
      }
    };
    if (!d.showAndGet()) return;
    String l = String.valueOf(leftBox.getSelectedItem());
    String r = String.valueOf(rightBox.getSelectedItem());
    String la = l.substring(0, l.indexOf('.'));
    String ra = r.substring(0, r.indexOf('.'));
    if (la.equals(ra)) {
      tableStatus.setText("서로 다른 두 표의 칼럼을 고르세요.");
      return;
    }
    if (index(la) > index(ra)) {
      String s = l; l = r; r = s;
      String a = la; la = ra; ra = a;
    }
    JoinRow row = new JoinRow(new QueryBuilder.Join(la, l.substring(la.length() + 1), ra, r.substring(ra.length() + 1), outer.isSelected(), false), true);
    joins.add(row);
    changed();
  }

  private int index(String alias) {
    List<QueryBuilder.Table> items = chosen.getItems();
    for (int i = 0; i < items.size(); i++) if (items.get(i).alias().equals(alias)) return i;
    return -1;
  }

  private void addCond() {
    List<String> refs = columnRefs();
    if (refs.isEmpty()) {
      tableStatus.setText("표를 먼저 고르세요.");
      return;
    }
    CondRow r = new CondRow();
    ColRow picked = null;
    for (ColRow c : cols.rows) if (c.selected) { picked = c; break; }
    r.column(picked != null ? picked.alias + "." + picked.col.name() : refs.get(0));
    conds.add(r);
    sections.get(2).open(true);
    changed();
  }

  private void addHaving() {
    List<String> labels = aggLabels();
    if (labels.isEmpty()) {
      tableStatus.setText("칼럼 단계에서 집계를 하나 고르거나 「전체 건수 칼럼 넣기」 를 켜세요.");
      return;
    }
    HavingRow r = new HavingRow();
    r.label = labels.get(0);
    havings.add(r);
    changed();
  }

  private void addOrder() {
    List<String> c = orderCandidates();
    if (c.isEmpty()) {
      tableStatus.setText("표를 먼저 고르세요.");
      return;
    }
    OrderRow r = new OrderRow();
    r.expr = c.get(0);
    orders.add(r);
    changed();
  }

  /** 고른 표들의 「별칭.칼럼」. */
  private List<String> columnRefs() {
    List<String> out = new ArrayList<>();
    for (ColRow r : cols.rows) out.add(r.alias + "." + r.col.name());
    return out;
  }

  private String typeOf(String alias, String name) {
    for (ColRow r : cols.rows) if (r.alias.equals(alias) && r.col.name().equals(name)) return r.col.type();
    return null;
  }

  /** HAVING 에 쓸 집계의 이름(별칭). */
  private List<String> aggLabels() {
    List<String> out = new ArrayList<>();
    for (QueryBuilder.Col c : QueryBuilder.selected(spec())) if (c.agg() != null) out.add(c.label(bench.kind()));
    return out;
  }

  /** 정렬 후보: 묶었으면 묶은 칼럼 · 집계 별칭, 아니면 모든 칼럼. */
  private List<String> orderCandidates() {
    QueryBuilder.Spec s = spec();
    List<String> group = QueryBuilder.groupBy(s);
    List<String> out = new ArrayList<>();
    if (!group.isEmpty()) {
      out.addAll(group);
      out.addAll(aggLabels());
      return out;
    }
    for (ColRow r : cols.rows) out.add(r.alias + "." + ei.db.gen.Names.sql(bench.kind(), r.col.name()));
    return out;
  }

  private static Object[] aggChoices() {
    List<Object> out = new ArrayList<>();
    out.add(NONE);
    for (QueryBuilder.Agg a : QueryBuilder.Agg.values()) out.add(a.label);
    return out.toArray();
  }

  private static QueryBuilder.Agg agg(Object v) {
    for (QueryBuilder.Agg a : QueryBuilder.Agg.values()) if (a.label.equals(v)) return a;
    return null;
  }

  /** 화면 상태 → 작성기 상태. */
  QueryBuilder.Spec spec() {
    QueryBuilder.Spec s = new QueryBuilder.Spec();
    s.kind = bench.kind();
    s.style = bench.style();
    s.bind = bench.bind();
    s.distinct = distinct.isVisible() && distinct.isSelected();
    s.tables.addAll(chosen.getItems());
    if (countAll.isSelected() && !s.tables.isEmpty()) s.cols.add(new QueryBuilder.Col(null, "*", null, "", QueryBuilder.Agg.COUNT, null));
    for (ColRow r : cols.rows) {
      if (r.selected) s.cols.add(new QueryBuilder.Col(r.alias, r.col.name(), r.col.type(), r.col.comment(), r.agg, r.as));
    }
    for (String ref : groupRefs) {
      for (ColRow r : cols.rows) {
        if ((r.alias + "." + r.col.name()).equals(ref)) s.groups.add(new QueryBuilder.Col(r.alias, r.col.name(), r.col.type(), r.col.comment(), null, null));
      }
    }
    for (JoinRow r : joins.rows) {
      if (r.use) s.joins.add(new QueryBuilder.Join(r.j.leftAlias(), r.j.leftColumn(), r.j.rightAlias(), r.j.rightColumn(), r.outer, r.j.guessed()));
    }
    for (CondRow r : conds.rows) s.conds.add(new QueryBuilder.Cond(r.alias, r.name, r.kind, r.op, r.value, r.value2));
    for (HavingRow r : havings.rows) {
      for (QueryBuilder.Col c : QueryBuilder.selected(s)) {
        if (c.agg() != null && r.label != null && r.label.equals(c.label(s.kind))) s.havings.add(new QueryBuilder.Having(c, r.op, r.value));
      }
    }
    for (OrderRow r : orders.rows) if (r.expr != null && !r.expr.isBlank()) s.orders.add(new QueryBuilder.Order(r.expr, r.desc));
    s.limit = limit.getNumber();
    s.offset = offsetRow.isVisible() ? offset.getNumber() : 0;
    return s;
  }

  /** 무엇이든 바뀌면: 미리 보기를 다시 만들고, 건수 세기를 예약한다. */
  void changed() {
    QueryBuilder.Spec s = spec();
    List<String> group = QueryBuilder.groupBy(s);
    List<String> manual = new ArrayList<>();
    for (QueryBuilder.Col c : s.groups) manual.add(c.alias() + "." + c.column());
    groupRefs.retainAll(manual);
    groupModel.replaceAll(new ArrayList<>(groupRefs));
    List<String> auto = new ArrayList<>();
    for (QueryBuilder.Col c : s.groups) manual.set(manual.indexOf(c.alias() + "." + c.column()), c.expr(s.kind));
    for (String g : group) if (!manual.contains(g)) auto.add(g);
    groupLabel.setText(group.isEmpty() ? "지금은 묶지 않습니다." : auto.isEmpty() ? "GROUP BY: " + String.join(", ", group)
        : "GROUP BY: " + String.join(", ", group) + "   (자동: " + String.join(", ", auto) + " — 2단계에서 집계 없이 고른 칼럼)");
    if (s.tables.isEmpty()) {
      bench.show("", List.of("왼쪽에서 표를 고르면 SQL 이 여기에 만들어집니다."));
      return;
    }
    List<String> notes = new ArrayList<>(QueryBuilder.problems(s));
    for (SqlLint.Issue i : SqlLint.check(QueryBuilder.render(s))) {
      if (i.title().startsWith("GROUP BY")) notes.add(i.detail());
    }
    bench.show(QueryBuilder.render(s), notes);
    if (autoCount.isSelected()) countTimer.restart();
  }

  /** 지금 조건의 건수를 센다(값을 넣은 조건만). */
  private void count() {
    DbConfig c = bench.config();
    QueryBuilder.Spec s = spec();
    String sql = QueryBuilder.countSql(s);
    if (c == null || sql == null) return;
    int shape = s.tables.size() * 1000 + s.joins.size();
    int skipped = QueryBuilder.unfilled(s);
    int gen = ++countGeneration;
    countLabel.setForeground(UIUtil.getContextHelpForeground());
    countLabel.setText("세는 중…");
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      String text;
      Long value = null;
      try {
        QueryResult r = DbSessions.get(bench.project).run(c, sql, List.of());
        Object v = r.rows().isEmpty() || r.rows().get(0).isEmpty() ? null : r.rows().get(0).get(0);
        value = v instanceof Number n ? n.longValue() : v == null ? null : Long.parseLong(v.toString().strip());
        text = value == null ? "건수를 읽지 못했습니다" : String.format(Locale.ROOT, "지금 %,d건", value);
      } catch (Exception e) {
        text = "건수를 세지 못했습니다: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      }
      String shown = text;
      Long got = value;
      ApplicationManager.getApplication().invokeLater(() -> {
        if (gen != countGeneration) return;
        String extra = skipped > 0 ? "  (값을 비운 조건 " + skipped + "개는 빼고 셌습니다)" : "";
        countLabel.setForeground(got == null ? UIUtil.getErrorForeground() : UIUtil.getLabelForeground());
        String jump = "";
        if (got != null && lastCount != null && shape != lastShape && got > lastCount && lastCount > 0) {
          jump = String.format(Locale.ROOT, "  ⚠ 표 · 연결을 바꾼 뒤 %,d건 → %,d건 (%.1f배). 연결 조건이 빠졌거나 1:N 관계입니다.", lastCount, got, (double) got / lastCount);
          countLabel.setForeground(UIUtil.getErrorForeground());
        }
        countLabel.setText(shown + extra + jump);
        if (got != null) {
          lastCount = got;
          lastShape = shape;
        }
      }, ModalityState.any());
    });
  }

  /** 표를 VISIBLE_ROWS 줄 높이로 보이는 스크롤 칸(가로는 줄 폭을 다 채운다). */
  private static JComponent scroll(JBTable table) {
    // JBTable 은 행이 없으면 보이는 높이를 머리줄만큼으로 잡아 빈 표 · 안내 글이 잘린다. 행 수와 상관없이 VISIBLE_ROWS 줄 높이를 둔다(폭은 레이아웃이 정한다).
    return new JBScrollPane(table) {
      @Override
      public java.awt.Dimension getPreferredSize() {
        java.awt.Dimension d = super.getPreferredSize();
        java.awt.Insets in = getInsets();
        int header = table.getTableHeader() == null ? 0 : table.getTableHeader().getPreferredSize().height;
        d.height = header + table.getRowHeight() * VISIBLE_ROWS + in.top + in.bottom;
        return d;
      }
    };
  }

  /** 넣은 표들의 칼럼 목록에서 묶을 칼럼을 여러 개 고른다(글자를 치면 거름). 이미 고른 것은 목록에서 뺀다. */
  private void addGroups(JComponent anchor) {
    List<String> choices = new ArrayList<>();
    for (ColRow r : cols.rows) {
      String ref = r.alias + "." + r.col.name();
      if (groupRefs.contains(ref)) continue;
      choices.add(r.col.comment() == null || r.col.comment().isBlank() ? ref : ref + "  — " + r.col.comment());
    }
    if (choices.isEmpty()) {
      bench.show(QueryBuilder.render(spec()), List.of(cols.rows.isEmpty() ? "왼쪽에서 표를 먼저 더하세요." : "더 고를 칼럼이 없습니다."));
      return;
    }
    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
        .setTitle("묶을 칼럼 (Ctrl · Shift 로 여러 개, 글자를 치면 거름)")
        .setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        .setNamerForFiltering(v -> v)
        .setItemsChosenCallback(picked -> {
          for (String v : picked) groupRefs.add(v.split("  — ")[0]);
          changed();
        })
        .createPopup()
        .showUnderneathOf(anchor);
  }

  /** 표가 하나면 모든 칼럼을, 여럿이면 「모든 표 · 표마다」 를 골라 체크한다. */
  private void selectAll(JComponent anchor) {
    List<QueryBuilder.Table> items = chosen.getItems();
    if (items.size() <= 1) {
      mark(null);
      return;
    }
    List<String> choices = new ArrayList<>();
    choices.add("모든 표의 칼럼");
    for (QueryBuilder.Table t : items) choices.add(t.name() + " (" + t.alias() + ") 칼럼만");
    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
        .setItemChosenCallback(c -> mark(c.equals(choices.get(0)) ? null : items.get(choices.indexOf(c) - 1).alias()))
        .createPopup()
        .showUnderneathOf(anchor);
  }

  /** alias 표(null 이면 모든 표)의 칼럼을 모두 체크한다. */
  private void mark(String alias) {
    for (ColRow r : cols.rows) if (alias == null || r.alias.equals(alias)) r.selected = true;
    cols.fireTableDataChanged();
    changed();
  }

  /** 접고 펼 수 있는 단계 하나: 머리(▸/▾ 제목), 할 일 한 줄, 내용, 「다음 ▸」. */
  private static final class Section {
    final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    private final JBLabel head = new JBLabel();
    private final String title;
    private final JComponent body;
    private final JPanel content = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    private final JButton nextButton = new JButton("다음 ▸");

    Section(String title, String help, JComponent body, boolean open) {
      this.title = title;
      this.body = body;
      head.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
      head.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) { open(!content.isVisible()); }
      });
      JBLabel helpLabel = new JBLabel(help);
      helpLabel.setForeground(UIUtil.getContextHelpForeground());
      helpLabel.setAllowAutoWrapping(true);
      helpLabel.setCopyable(false);
      nextButton.setVisible(false);
      JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      south.add(nextButton);
      content.add(helpLabel, BorderLayout.NORTH);
      content.add(body, BorderLayout.CENTER);
      content.add(south, BorderLayout.SOUTH);
      content.setBorder(JBUI.Borders.emptyLeft(18));
      panel.add(head, BorderLayout.NORTH);
      panel.add(content, BorderLayout.CENTER);
      open(open);
    }

    void next(Runnable r) {
      nextButton.setVisible(true);
      nextButton.addActionListener(e -> r.run());
    }

    void open(boolean on) {
      content.setVisible(on);
      head.setIcon(on ? AllIcons.General.ArrowDown : AllIcons.General.ArrowRight);
      head.setText(title);
      panel.revalidate();
    }
  }

  private static JPanel buttons(JComponent... items) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
    for (JComponent c : items) p.add(c);
    return p;
  }

  private static JPanel stack(Component... items) {
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    for (Component c : items) {
      if (c instanceof JComponent j) j.setAlignmentX(Component.LEFT_ALIGNMENT);
      p.add(c);
    }
    return p;
  }

  /** 다른 탭(조인 그림 등)이 고른 표 이름을 쓴다. */
  List<QueryBuilder.Table> tables() { return chosen.getItems(); }
}
