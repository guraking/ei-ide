package ei.db.console;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.bench.SqlParts;
import ei.db.console.PlanAdvisor.Step;
import ei.db.console.PlanCompare.How;
import ei.db.console.PlanCompare.Mark;
import ei.db.console.PlanCompare.Note;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.HierarchyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 실행 계획 전후 비교 창. 계획 탭의 「앞 계획과 비교…」 로 연다: 기준으로 찍은 계획과 같은 접속에서 앞서 본 계획 가운데 하나를 골라 지금 계획과 견준다.
 * 위는 요약(총 비용 · 예상 건수, 양쪽 다 실제 시간을 쟀으면 실제 시간 · 읽은 블록), 가운데는 앞 · 뒤 단계 표(달라진 단계는 「변화」 칸 글과 바탕색),
 * 아래는 「바뀐 점」 목록. 단계 맞추기는 PlanCompare. 종류가 다른 DB 의 계획끼리는 비교하지 않는다.
 */
final class PlanCompareDialog extends DialogWrapper {
  /** 처음 열 때 화면에 대한 크기 몫. 사용자가 바꾼 크기는 DIMENSION_KEY 로 기억한다. */
  private static final double FIRST_WIDTH_RATIO = 0.7;
  private static final double FIRST_HEIGHT_RATIO = 0.6;
  private static final String DIMENSION_KEY = "ei.db.PlanCompare";
  private static final float SIDES_SPLIT = 0.5f;
  private static final String SIDES_SPLIT_KEY = "ei.db.plan.compare.sides";
  private static final float NOTES_SPLIT = 0.7f;
  private static final String NOTES_SPLIT_KEY = "ei.db.plan.compare.notes";
  private static final String WIDTHS_KEY = "ei.db.plan.compare.widths.";
  /** 처음 칸 폭 비율(변화 · 단계 · 대상 · 숫자 칸). 창 폭에 맞춰 늘고 사용자가 끌면 그 값을 쓴다. */
  private static final int MARK_WEIGHT = 1;
  private static final int STEP_WEIGHT = 5;
  private static final int OBJECT_WEIGHT = 3;
  private static final int NUMBER_WEIGHT = 2;
  /** 비율 1 의 처음 폭. 자동 맞춤이 창 폭에 맞춰 늘리므로 실제 폭이 아니라 비율의 단위다. */
  private static final int WEIGHT_UNIT = 30;
  /** 계획 단계 한 깊이마다 들여 쓸 폭. */
  private static final int INDENT = 14;
  /** 목록 · 요약에 보일 SQL 첫 줄의 가장 긴 길이. */
  private static final int SQL_PREVIEW_CHARS = 80;
  private static final String ELLIPSIS = "…";
  private static final String UNKNOWN = "-";
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
  private static final Color ADDED_ROW = UiColors.SUCCESS_BG;
  private static final Color REMOVED_ROW = UiColors.DANGER_BAND;
  private static final Color CHANGED_ROW = UiColors.PENDING_BG;

  private final PlanHistory.Entry before;
  private final PlanHistory.Entry after;
  private final PlanCompare.Result result;

  /**
   * 계획 탭 머리줄에 둘 단추 둘: 「기준으로 삼기」 와 「앞 계획과 비교…」. 비교할 계획이 없으면 비교 단추는 꺼지고 풍선 도움말로 까닭을 알린다.
   * 다른 탭에서 기준을 찍었을 수 있어 화면에 보일 때마다 켜짐을 다시 정한다.
   */
  static JPanel buttons(Project project, PlanHistory.Entry current) {
    PlanHistory history = PlanHistory.get(project);
    JButton pin = new JButton(EiDbBundle.message("plan.compare.pin"));
    JButton compare = new JButton(EiDbBundle.message("plan.compare.open"));
    Runnable refresh = () -> {
      boolean any = !history.candidates(current).isEmpty();
      compare.setEnabled(any);
      compare.setToolTipText(EiDbBundle.message(any ? "plan.compare.open.tooltip" : "plan.compare.none"));
      boolean pinned = history.baseline() == current;
      pin.setEnabled(!pinned);
      pin.setToolTipText(EiDbBundle.message(pinned ? "plan.compare.pinned" : "plan.compare.pin.tooltip"));
    };
    pin.addActionListener(e -> {
      history.pin(current);
      refresh.run();
      Notify.info(project, EiDbBundle.message("plan.compare.pin.done", label(current, false)));
    });
    compare.addActionListener(e -> choose(project, current, compare));
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
    panel.setOpaque(false);
    panel.add(pin);
    panel.add(compare);
    panel.addHierarchyListener(e -> {
      if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing()) refresh.run();
    });
    refresh.run();
    return panel;
  }

  /** 비교할 앞 계획을 고르는 목록(시각 · SQL 첫 줄 · 총 비용). 하나뿐이어도 무엇과 견주는지 보이도록 목록을 띄운다. */
  private static void choose(Project project, PlanHistory.Entry current, JComponent anchor) {
    PlanHistory history = PlanHistory.get(project);
    List<PlanHistory.Entry> entries = history.candidates(current);
    if (entries.isEmpty()) {
      Notify.info(project, EiDbBundle.message("plan.compare.none"));
      return;
    }
    List<String> labels = new ArrayList<>();
    for (PlanHistory.Entry e : entries) labels.add(label(e, e == history.baseline()));
    JBPopupFactory.getInstance().createPopupChooserBuilder(labels)
        .setTitle(EiDbBundle.message("plan.compare.choose"))
        .setItemChosenCallback(picked -> {
          PlanHistory.Entry chosen = entries.get(labels.indexOf(picked));
          if (chosen.plan().kind() != current.plan().kind()) {
            Notify.warning(project, EiDbBundle.message("plan.compare.kind.differs", chosen.plan().kind(), current.plan().kind()));
            return;
          }
          new PlanCompareDialog(project, chosen, current).show();
        })
        .createPopup()
        .showUnderneathOf(anchor);
  }

  /** 「[기준] 14:03:11 · 접속 · SQL 첫 줄 · 비용 1,234」. 같은 글이 둘이면 목록에서 가릴 수 없어 시각을 초까지 둔다. */
  private static String label(PlanHistory.Entry e, boolean baseline) {
    Long cost = PlanCompare.totalCost(e.plan().steps());
    String text = EiDbBundle.message("plan.compare.entry", TIME.format(Instant.ofEpochMilli(e.at())), e.connectionName(), firstLine(e.plan()),
        cost == null ? UNKNOWN : String.format("%,d", cost), EiDbBundle.message(e.plan().actual() ? "plan.compare.entry.actual" : "plan.compare.entry.estimate"));
    return baseline ? EiDbBundle.message("plan.compare.entry.baseline", text) : text;
  }

  private static String firstLine(ExplainPlan.Plan plan) {
    String sql = plan.source() == null || plan.source().isBlank() ? plan.sql() : plan.source();
    String line = sql == null ? "" : SqlParts.oneLine(sql.strip().lines().findFirst().orElse(""));
    return line.length() > SQL_PREVIEW_CHARS ? line.substring(0, SQL_PREVIEW_CHARS) + ELLIPSIS : line;
  }

  private PlanCompareDialog(Project project, PlanHistory.Entry before, PlanHistory.Entry after) {
    super(project, false);
    this.before = before;
    this.after = after;
    this.result = PlanCompare.compare(before.plan().steps(), after.plan().steps());
    setTitle(EiDbBundle.message("plan.compare.title"));
    init();
  }

  @Override
  protected JComponent createCenterPanel() {
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.setBorder(JBUI.Borders.emptyBottom(6));
    top.add(new JBLabel(EiDbBundle.message("plan.compare.side.before", label(before, false))));
    top.add(new JBLabel(EiDbBundle.message("plan.compare.side.after", label(after, false))));
    List<Step> b = before.plan().steps();
    List<Step> a = after.plan().steps();
    top.add(summary("plan.col.cost", PlanCompare.totalCost(b), PlanCompare.totalCost(a)));
    top.add(summary("plan.col.rows", PlanCompare.totalRows(b), PlanCompare.totalRows(a)));
    boolean bothActual = before.plan().actual() && after.plan().actual();
    if (bothActual) {
      top.add(summary("plan.col.actual.ms", before.plan().millis(), after.plan().millis()));
      top.add(summary("plan.col.buffers", PlanCompare.buffers(b), PlanCompare.buffers(a)));
    } else if (before.plan().actual() || after.plan().actual()) {
      JBLabel oneSide = new JBLabel(EiDbBundle.message("plan.compare.actual.one.side"));
      oneSide.setForeground(UIUtil.getContextHelpForeground());
      top.add(oneSide);
    }
    if (!SqlParts.oneLine(String.valueOf(before.plan().sql())).equals(SqlParts.oneLine(String.valueOf(after.plan().sql())))) {
      JBLabel other = new JBLabel(EiDbBundle.message("plan.compare.sql.differs"));
      other.setForeground(UiColors.WARNING);
      top.add(other);
    }

    OnePixelSplitter sides = new OnePixelSplitter(false, SIDES_SPLIT_KEY, SIDES_SPLIT);
    sides.setHonorComponentsMinimumSize(false);
    sides.setFirstComponent(side("plan.compare.before", before.plan(), result.before()));
    sides.setSecondComponent(side("plan.compare.after", after.plan(), result.after()));

    List<String> lines = new ArrayList<>();
    for (Note n : result.notes()) lines.add(text(n));
    JBList<String> notes = new JBList<>(lines);
    notes.getEmptyText().setText(EiDbBundle.message(b.isEmpty() || a.isEmpty() ? "plan.compare.empty.plan" : "plan.compare.no.change"));
    JPanel notesPanel = new JPanel(new BorderLayout());
    notesPanel.add(heading("plan.compare.notes", lines.size()), BorderLayout.NORTH);
    notesPanel.add(ScrollPaneFactory.createScrollPane(notes, true), BorderLayout.CENTER);

    OnePixelSplitter split = new OnePixelSplitter(true, NOTES_SPLIT_KEY, NOTES_SPLIT);
    split.setHonorComponentsMinimumSize(false);
    split.setFirstComponent(sides);
    split.setSecondComponent(notesPanel);

    JPanel panel = new JPanel(new BorderLayout());
    panel.add(top, BorderLayout.NORTH);
    panel.add(split, BorderLayout.CENTER);
    return panel;
  }

  /** 「비용: 1,234 → 777 (↓ 37%)」. 모르는 값은 「-」, 변화율을 못 내면 괄호를 뺀다. */
  private static JBLabel summary(String labelKey, Long before, Long after) {
    Long percent = PlanCompare.percent(before, after);
    String change = percent == null ? "" : EiDbBundle.message(percent < 0 ? "plan.compare.down" : percent > 0 ? "plan.compare.up" : "plan.compare.flat", Math.abs(percent));
    return new JBLabel(EiDbBundle.message("plan.compare.summary", EiDbBundle.message(labelKey), number(before), number(after), change));
  }

  private static String number(Long n) { return n == null ? UNKNOWN : String.format("%,d", n); }

  private static JBLabel heading(String key, Object... args) {
    JBLabel label = new JBLabel(EiDbBundle.message(key, args));
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setBorder(JBUI.Borders.empty(4, 6));
    return label;
  }

  private JComponent side(String titleKey, ExplainPlan.Plan plan, Map<Integer, Mark> marks) {
    Model model = new Model(plan, marks);
    JBTable table = new JBTable(model);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setDefaultRenderer(Object.class, new Renderer(model));
    table.getTableHeader().setReorderingAllowed(false);
    table.getEmptyText().setText(EiDbBundle.message("plan.compare.empty.plan"));
    String widthsKey = WIDTHS_KEY + model.getColumnCount();
    restoreWidths(table, widthsKey);
    table.getColumnModel().addColumnModelListener(new WidthSaver(table, widthsKey));
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(heading(titleKey), BorderLayout.NORTH);
    panel.add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER);
    return panel;
  }

  // ---------------------------------------------------------------- 바뀐 점 글

  private static String text(Note n) {
    return switch (n.kind()) {
      case JOIN -> EiDbBundle.message("plan.compare.note.join", n.before().raw(), n.after().raw());
      case ADDED -> EiDbBundle.message("plan.compare.note.added", described(n.object(), text(n.after())));
      case REMOVED -> EiDbBundle.message("plan.compare.note.removed", described(n.object(), text(n.before())));
      case ACCESS -> {
        // 읽는 방법은 같고 단계 이름만 다르면(정렬 방향 등) 단계 이름을 그대로 보인다.
        boolean sameHow = n.before().same(n.after());
        String from = sameHow ? n.before().raw() : text(n.before());
        String to = sameHow ? n.after().raw() : text(n.after());
        yield EiDbBundle.message("plan.compare.note.access", described(n.object(), from), to);
      }
    };
  }

  /** 「대상 읽는 방법」. 대상이 없거나 읽는 방법 글이 이미 그 이름으로 시작하면(인덱스 단계) 대상을 거듭 쓰지 않는다. */
  private static String described(String object, String how) {
    return object.isEmpty() || how.startsWith(object + " ") ? how : object + " " + how;
  }

  private static String text(How h) {
    if (h.access() == PlanCompare.Access.OTHER) return h.raw();
    return EiDbBundle.message("plan.compare.how." + h.access().name(), h.index()).strip();
  }

  // ---------------------------------------------------------------- 표

  private static final class Model extends AbstractTableModel {
    private final List<String> columns = new ArrayList<>(List.of("plan.compare.col.mark", "plan.col.step", "plan.col.object", "plan.col.rows", "plan.col.cost"));
    private final List<Step> steps;
    private final Map<Integer, Mark> marks;

    Model(ExplainPlan.Plan plan, Map<Integer, Mark> marks) {
      this.steps = plan.steps();
      this.marks = marks;
      if (plan.actual()) columns.addAll(List.of("plan.col.actual.ms", "plan.col.buffers"));
    }

    Mark mark(int row) { return marks.getOrDefault(steps.get(row).id(), Mark.SAME); }

    @Override public int getRowCount() { return steps.size(); }
    @Override public int getColumnCount() { return columns.size(); }
    @Override public String getColumnName(int column) { return EiDbBundle.message(columns.get(column)); }

    @Override
    public Object getValueAt(int row, int column) {
      Step s = steps.get(row);
      return switch (columns.get(column)) {
        case "plan.compare.col.mark" -> mark(row) == Mark.SAME ? "" : EiDbBundle.message("plan.compare.mark." + mark(row).name());
        case "plan.col.step" -> s;
        case "plan.col.object" -> s.object();
        case "plan.col.rows" -> s.rows();
        case "plan.col.cost" -> s.cost();
        case "plan.col.actual.ms" -> s.elapsedMicros() == null ? null : s.elapsedMicros() / MICROS_PER_MILLI;
        case "plan.col.buffers" -> s.buffers();
        default -> null;
      };
    }
  }

  private static final long MICROS_PER_MILLI = 1000;
  private static final int STEP_COLUMN = 1;

  /** 달라진 줄은 바탕색(추가 · 삭제 · 바뀜)과 「변화」 칸 글로 함께 알린다. 단계 칸은 깊이만큼 들여 쓰고 숫자는 오른쪽 맞춤. */
  private static final class Renderer extends ColoredTableCellRenderer {
    private final Model model;

    Renderer(Model model) { this.model = model; }

    @Override
    protected void customizeCellRenderer(@NotNull JTable t, @Nullable Object value, boolean selected, boolean hasFocus, int row, int column) {
      Mark mark = model.mark(t.convertRowIndexToModel(row));
      if (!selected && mark != Mark.SAME) setBackground(mark == Mark.ADDED ? ADDED_ROW : mark == Mark.REMOVED ? REMOVED_ROW : CHANGED_ROW);
      setIpad(JBUI.emptyInsets());
      if (value instanceof Step step) {
        setTextAlign(SwingConstants.LEFT);
        setIpad(JBUI.insetsLeft(step.depth() * INDENT));
        append(step.operation());
        setToolTipText(step.operation());
      } else if (value instanceof Long n) {
        setTextAlign(SwingConstants.RIGHT);
        append(String.format("%,d", n));
      } else if (value != null) {
        setTextAlign(SwingConstants.LEFT);
        append(value.toString());
      }
    }
  }

  private static void restoreWidths(JBTable table, String key) {
    TableColumnModel cols = table.getColumnModel();
    String saved = PropertiesComponent.getInstance().getValue(key, "");
    String[] parts = saved.isEmpty() ? new String[0] : saved.split(",");
    for (int i = 0; i < cols.getColumnCount(); i++) {
      int width = (i == 0 ? MARK_WEIGHT : i == STEP_COLUMN ? STEP_WEIGHT : i == STEP_COLUMN + 1 ? OBJECT_WEIGHT : NUMBER_WEIGHT) * WEIGHT_UNIT;
      if (parts.length == cols.getColumnCount()) {
        try {
          width = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
          // 손상된 값은 처음 비율로 둔다.
        }
      }
      // 자동 맞춤이라 이 값은 칸끼리의 비율로 쓰인다.
      cols.getColumn(i).setPreferredWidth(JBUI.scale(width));
    }
  }

  /** 칸 폭이 바뀌면 지금 폭들을 기억한다. 앞 · 뒤 표가 같은 값을 같이 쓴다(칸 수가 같을 때). */
  private record WidthSaver(JBTable table, String key) implements TableColumnModelListener {
    @Override
    public void columnMarginChanged(ChangeEvent e) {
      // 창이 뜨기 전(폭 0)의 값은 기억하지 않는다.
      if (!table.isShowing()) return;
      TableColumnModel cols = table.getColumnModel();
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < cols.getColumnCount(); i++) b.append(i == 0 ? "" : ",").append(JBUI.unscale(cols.getColumn(i).getWidth()));
      PropertiesComponent.getInstance().setValue(key, b.toString());
    }

    @Override public void columnAdded(TableColumnModelEvent e) { }
    @Override public void columnRemoved(TableColumnModelEvent e) { }
    @Override public void columnMoved(TableColumnModelEvent e) { }
    @Override public void columnSelectionChanged(ListSelectionEvent e) { }
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() {
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    return new Dimension((int) (screen.width * FIRST_WIDTH_RATIO), (int) (screen.height * FIRST_HEIGHT_RATIO));
  }

  @Override
  protected Action @NotNull [] createActions() {
    getOKAction().putValue(Action.NAME, EiDbBundle.message("common.close"));
    return new Action[]{getOKAction()};
  }
}
