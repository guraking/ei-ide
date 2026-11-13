package ei.db.console;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.gen.Ui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 탐색기 「엑셀 붙여 넣어 행 넣기」 창. 위에 붙여 넣은 글을 읽어 가운데 표에 미리 보이고(머리를 누르면 칸마다 칼럼을 바꿔 고른다),
 * 아래에 문제 목록을 보인다. 글 · 체크 · 칼럼 고르기가 바뀔 때마다 다시 읽는다(DB 는 건드리지 않는다).
 * 막는 문제가 하나라도 있거나 행이 PasteRows.MAX_ROWS 를 넘으면 「바로 넣기」가 꺼진다. 다른 창을 막지 않는 창이라 넣는 동안 진행 막대를 볼 수 있다.
 */
public final class PasteRowsDialog extends DialogWrapper {
  private static final String DIMENSION_KEY = "ei.db.PasteRowsDialog";
  private static final double FIRST_WIDTH_RATIO = 0.7;
  private static final double FIRST_HEIGHT_RATIO = 0.75;
  private static final float PASTE_PROPORTION = 0.25f;
  private static final float PREVIEW_PROPORTION = 0.72f;
  private static final String PASTE_SPLIT_KEY = "ei.db.paste.split.text";
  private static final String PREVIEW_SPLIT_KEY = "ei.db.paste.split.preview";
  private static final int GAP = 6;
  /** 문제 목록에 보이는 줄 수 한도(넘으면 남은 건수만 알린다). */
  private static final int PROBLEM_LIST_MAX = 300;
  private static final String PICK_MARK = " ▾";
  private static final String PART_SEPARATOR = " · ";

  private final Project project;
  private final RowInserter.Table target;
  private final JBTextArea pasted = new JBTextArea();
  private final JBCheckBox header = new JBCheckBox(EiDbBundle.message("paste.header"));
  private final JBCheckBox trim = new JBCheckBox(EiDbBundle.message("paste.trim"), true);
  private final PreviewModel model = new PreviewModel();
  /** 표는 target · data · mapping 이 채워진 뒤(생성자) 만든다: 만들 때 모델을 읽는다. */
  private final JBTable table;
  private final CollectionListModel<String> problemLines = new CollectionListModel<>();
  private final JBList<String> problemList = new JBList<>(problemLines);
  private final JBLabel status = new JBLabel();
  /** 사용자가 머리에서 직접 고른 칸 → 칼럼 번호(PasteRows.SKIP 포함). 칸 수가 바뀌면 비운다. */
  private final Map<Integer, Integer> picked = new HashMap<>();
  private final Action consoleAction = new DialogWrapperAction(EiDbBundle.message("rows.to.console")) {
    @Override
    protected void doAction(ActionEvent e) { toConsole(); }
  };
  private final Action insertAction = new DialogWrapperAction(EiDbBundle.message("rows.insert.now")) {
    @Override
    protected void doAction(ActionEvent e) { insertNow(); }
  };

  /** 사용자가 「첫 줄은 칼럼 이름」을 직접 바꿨는지. 바꾸기 전까지는 붙여 넣은 글을 보고 저절로 켜고 끈다. */
  private boolean headerTouched;
  private boolean busy;
  private List<List<String>> data = List.of();
  private int[] mapping = new int[0];
  private PasteRows.Result result = new PasteRows.Result(List.of(), List.of(), List.of());
  /** 표에서 표시할 칸: (행, 칸) → 그 칸의 첫 문제. 줄 전체 문제는 칸 PasteRows.WHOLE. */
  private final Map<Long, PasteRows.Problem> marks = new HashMap<>();
  /** 문제 목록의 줄 번호 → 문제(넘쳐서 줄인 줄은 없음). */
  private final List<PasteRows.Problem> listed = new ArrayList<>();

  /** 표 정보를 뒤에서 읽은 뒤 창을 연다. 읽지 못하면 알림만 뜬다. */
  public static void open(Project project, DbConfig c, String schema, String table) {
    RowInserter.load(project, c, schema, table, t -> new PasteRowsDialog(project, t).show());
  }

  private PasteRowsDialog(Project project, RowInserter.Table target) {
    super(project, true, IdeModalityType.MODELESS);
    this.project = project;
    this.target = target;
    this.table = new JBTable(model);
    setTitle(EiDbBundle.message("paste.title", target.name()));
    setCancelButtonText(EiDbBundle.message("common.close"));
    init();
    refresh();
  }

  @Override
  protected JComponent createCenterPanel() {
    pasted.getEmptyText().setText(EiDbBundle.message("paste.text.empty"));
    pasted.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        if (pasted.getDocument().getLength() == 0) headerTouched = false;
        refresh();
      }
    });
    JButton paste = new JButton(EiDbBundle.message("paste.from.clipboard"));
    paste.addActionListener(e -> pasteClipboard());
    header.addActionListener(e -> {
      headerTouched = true;
      picked.clear();
      refresh();
    });
    trim.addActionListener(e -> refresh());
    JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0));
    options.add(paste);
    options.add(header);
    options.add(trim);
    JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    top.add(ScrollPaneFactory.createScrollPane(pasted), BorderLayout.CENTER);
    top.add(options, BorderLayout.SOUTH);

    table.setDefaultRenderer(Object.class, new MarkRenderer());
    table.getTableHeader().setReorderingAllowed(false);
    table.getTableHeader().setToolTipText(EiDbBundle.message("paste.header.tooltip"));
    table.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        int view = table.columnAtPoint(e.getPoint());
        if (view > 0) pickColumn(table.convertColumnIndexToModel(view) - 1, e);
      }
    });
    table.setCellSelectionEnabled(true);
    table.getEmptyText().setText(EiDbBundle.message("paste.empty"));

    problemList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    problemList.getEmptyText().setText(EiDbBundle.message("paste.problems.empty"));
    problemList.addListSelectionListener(e -> showProblem(problemList.getSelectedIndex()));
    status.setForeground(UIUtil.getContextHelpForeground());
    JPanel problems = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    problems.add(status, BorderLayout.NORTH);
    problems.add(ScrollPaneFactory.createScrollPane(problemList), BorderLayout.CENTER);

    OnePixelSplitter lower = splitter(PREVIEW_PROPORTION, PREVIEW_SPLIT_KEY, ScrollPaneFactory.createScrollPane(table), problems);
    return splitter(PASTE_PROPORTION, PASTE_SPLIT_KEY, top, lower);
  }

  /** 위아래 칸을 비율로 나누고 끌어 바꾼 비율을 기억한다. */
  private static OnePixelSplitter splitter(float proportion, String key, JComponent first, JComponent second) {
    OnePixelSplitter s = new OnePixelSplitter(true, proportion);
    s.setSplitterProportionKey(key);
    s.setHonorComponentsMinimumSize(false);
    s.setFirstComponent(first);
    s.setSecondComponent(second);
    return s;
  }

  private void pasteClipboard() {
    String text = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
    if (text == null || text.isBlank()) {
      Messages.showInfoMessage(project, EiDbBundle.message("paste.clipboard.empty"), getTitle());
      return;
    }
    pasted.setText(text);
  }

  /** 붙여 넣은 글을 다시 읽어 표 · 문제 목록 · 단추를 맞춘다. */
  private void refresh() {
    List<Meta.Column> columns = target.columns();
    List<List<String>> all = PasteRows.parse(pasted.getText());
    if (!headerTouched) header.setSelected(!all.isEmpty() && PasteRows.looksLikeHeader(all.get(0), columns));
    List<String> head = header.isSelected() && !all.isEmpty() ? all.get(0) : null;
    data = head == null ? all : all.subList(1, all.size());
    int[] next = head == null ? PasteRows.byOrder(PasteRows.width(data), columns.size()) : PasteRows.match(head, columns);
    if (next.length != mapping.length) picked.clear();
    for (Map.Entry<Integer, Integer> e : picked.entrySet()) next[e.getKey()] = e.getValue();
    mapping = next;
    // 한도를 넘는 붙여 넣기는 값 검사를 하지 않는다(어차피 보낼 수 없고, 매우 큰 글에서 화면이 멈추지 않게).
    result = data.size() > PasteRows.MAX_ROWS
        ? new PasteRows.Result(List.of(), List.of(), List.of())
        : PasteRows.convert(target.c().kind, columns, target.withDefault(), mapping, head, data, trim.isSelected());

    marks.clear();
    listed.clear();
    List<String> lines = new ArrayList<>();
    int blocking = 0;
    for (PasteRows.Problem p : result.problems()) {
      if (p.blocking()) blocking++;
      if (p.row() != PasteRows.WHOLE) marks.putIfAbsent(markKey(p.row(), p.cell()), p);
      if (lines.size() < PROBLEM_LIST_MAX) {
        lines.add(line(p));
        listed.add(p);
      }
    }
    int total = result.problems().size();
    if (total > lines.size()) lines.add(EiDbBundle.message("paste.problems.more", total - lines.size()));
    problemLines.replaceAll(lines);
    model.fireTableStructureChanged();
    if (data.isEmpty()) status.setText(EiDbBundle.message("paste.status.empty"));
    else if (data.size() > PasteRows.MAX_ROWS) status.setText(EiDbBundle.message("paste.over.limit", data.size(), PasteRows.MAX_ROWS));
    else status.setText(EiDbBundle.message("paste.status", data.size(), blocking, total - blocking));
    updateButtons();
  }

  private static long markKey(int row, int cell) {
    return ((long) row << Integer.SIZE) | (cell & 0xFFFFFFFFL);
  }

  /** 문제 목록 한 줄: 「3줄 · 칼럼 · 이유」. 막지 않는 문제는 앞에 「주의」. */
  private static String line(PasteRows.Problem p) {
    List<String> parts = new ArrayList<>();
    if (!p.blocking()) parts.add(EiDbBundle.message("paste.problem.warning"));
    if (p.row() != PasteRows.WHOLE) parts.add(EiDbBundle.message("paste.problem.row", p.row() + 1));
    else if (p.cell() != PasteRows.WHOLE) parts.add(EiDbBundle.message("paste.problem.cell", p.cell() + 1));
    if (!p.column().isEmpty()) parts.add(p.column());
    parts.add(p.reason());
    return String.join(PART_SEPARATOR, parts);
  }

  /** 문제 목록에서 고른 문제의 칸을 표에서 고르고 보이게 한다. */
  private void showProblem(int index) {
    if (index < 0 || index >= listed.size()) return;
    PasteRows.Problem p = listed.get(index);
    if (p.row() == PasteRows.WHOLE || p.row() >= model.getRowCount()) return;
    int column = p.cell() == PasteRows.WHOLE ? 0 : p.cell() + 1;
    if (column >= model.getColumnCount()) return;
    table.changeSelection(p.row(), table.convertColumnIndexToView(column), false, false);
  }

  /** 머리를 눌렀을 때: 그 칸에 넣을 칼럼(또는 「넣지 않음」)을 고르는 목록. */
  private void pickColumn(int cell, MouseEvent e) {
    if (cell < 0 || cell >= mapping.length) return;
    List<Integer> choices = new ArrayList<>();
    choices.add(PasteRows.SKIP);
    for (int i = 0; i < target.columns().size(); i++) choices.add(i);
    JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
        .setRenderer(SimpleListCellRenderer.create("", this::choiceLabel))
        .setNamerForFiltering(this::choiceLabel)
        .setItemChosenCallback(choice -> {
          picked.put(cell, choice);
          refresh();
        })
        .createPopup()
        .show(new RelativePoint(e));
  }

  private String choiceLabel(Integer choice) {
    if (choice == PasteRows.SKIP) return EiDbBundle.message("paste.skip");
    Meta.Column col = target.columns().get(choice);
    return col.comment().isBlank() ? col.name() : col.name() + "  " + col.comment();
  }

  private boolean sendable() {
    return !busy && !data.isEmpty() && data.size() <= PasteRows.MAX_ROWS && !result.columns().isEmpty();
  }

  private void updateButtons() {
    consoleAction.setEnabled(sendable());
    insertAction.setEnabled(sendable() && !result.blocked());
  }

  private InsertRows.Batch batch() {
    return new InsertRows.Batch(target.c().kind, target.sqlName(), result.columns(), result.rows());
  }

  private void toConsole() {
    if (!sendable()) return;
    if (result.blocked() && Messages.showYesNoDialog(project, EiDbBundle.message("paste.console.with.problems"), getTitle(),
        Messages.getWarningIcon()) != Messages.YES) {
      return;
    }
    if (RowInserter.toConsole(project, target.c(), batch())) close(OK_EXIT_CODE);
  }

  private void insertNow() {
    if (!sendable() || result.blocked()) return;
    busy = true;
    updateButtons();
    RowInserter.insert(project, target.c(), target.name(), batch(), ok -> {
      busy = false;
      if (isDisposed()) return;
      if (ok) close(OK_EXIT_CODE);
      else updateButtons();
    });
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[]{consoleAction, insertAction, getCancelAction()};
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() { return Ui.firstSize(FIRST_WIDTH_RATIO, FIRST_HEIGHT_RATIO); }

  @Override
  public JComponent getPreferredFocusedComponent() { return pasted; }

  /** 첫 칸은 데이터 행 번호(1 부터), 다음부터 붙여 넣은 칸. 머리는 맞춘 칼럼 이름(없으면 「넣지 않음」). */
  private final class PreviewModel extends AbstractTableModel {
    @Override
    public int getRowCount() { return data.size(); }

    @Override
    public int getColumnCount() { return mapping.length + 1; }

    @Override
    public String getColumnName(int column) {
      if (column == 0) return EiDbBundle.message("paste.col.no");
      int m = mapping[column - 1];
      return (m == PasteRows.SKIP ? EiDbBundle.message("paste.skip") : target.columns().get(m).name()) + PICK_MARK;
    }

    @Override
    public Object getValueAt(int row, int column) {
      if (column == 0) return row + 1;
      List<String> cells = data.get(row);
      return column - 1 < cells.size() ? cells.get(column - 1) : "";
    }
  }

  /** 문제 칸 표시: 막는 문제는 오류 바탕, 주의는 주의 글자색. 풍선 도움말에 이유. */
  private final class MarkRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
      Component c = super.getTableCellRendererComponent(t, value, selected, focus, row, column);
      int modelColumn = t.convertColumnIndexToModel(column);
      PasteRows.Problem p = marks.get(markKey(row, modelColumn == 0 ? PasteRows.WHOLE : modelColumn - 1));
      setToolTipText(p == null ? null : p.reason());
      if (!selected) {
        c.setBackground(p != null && p.blocking() ? UiColors.DANGER_BAND : t.getBackground());
        c.setForeground(p != null && !p.blocking() ? UiColors.WARNING : t.getForeground());
      }
      return c;
    }
  }
}
