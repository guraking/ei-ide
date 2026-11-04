package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.EiDbBundle;
import ei.db.console.PlanAdvisor.Finding;
import ei.db.console.PlanAdvisor.Level;
import ei.db.console.PlanAdvisor.Step;

import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「느린 곳」 결과 탭. 왼쪽은 계획 단계 표(느린 단계를 심각 · 주의 색으로), 오른쪽은 고른 단계의 원인 · 고칠 법 · 예시 · 조건.
 * 두 칸은 끌 수 있는 분할선(비율 기억), 표 칸은 창 폭을 따라 늘고 사용자가 끈 폭을 기억한다. 「원문」 탭은 DB 가 낸 계획 글 그대로.
 */
final class PlanPanel extends JPanel {
  private static final String SPLIT_KEY = "ei.db.plan.split";
  private static final float SPLIT_DEFAULT = 0.6f;
  private static final String WIDTHS_KEY = "ei.db.plan.widths.";
  /** 처음 열 때 칸 폭 비율(단계 · 대상 · 숫자 칸). 창 폭에 맞춰 늘고 사용자가 끌면 그 값을 쓴다. */
  private static final int STEP_WEIGHT = 4;
  private static final int OBJECT_WEIGHT = 2;
  private static final int NUMBER_WEIGHT = 1;
  /** 비율 1 의 처음 폭. 자동 맞춤이 창 폭에 맞춰 늘리므로 실제 폭이 아니라 비율의 단위다. */
  private static final int WEIGHT_UNIT = 60;
  /** 계획 단계 한 깊이마다 들여 쓸 폭. */
  private static final int INDENT = 14;
  private static final Color DANGER_ROW = UiColors.DANGER_BAND;
  private static final Color WARNING_ROW = JBUI.CurrentTheme.Banner.WARNING_BACKGROUND;

  private final ExplainPlan.Plan plan;
  private final Map<Integer, Level> levels;
  private final Map<Integer, Double> shares;
  private final JBTable table;
  private final JPanel side = new WidthTracking();

  PlanPanel(Project project, DbConfig c, ExplainPlan.Plan plan) {
    super(new BorderLayout());
    this.plan = plan;
    this.levels = PlanAdvisor.levels(plan.findings());
    this.shares = PlanAdvisor.shares(plan.steps(), PlanAdvisor.children(plan.steps()), false);
    side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
    side.setBorder(JBUI.Borders.empty(8, 12));

    table = new JBTable(new Model());
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setDefaultRenderer(Object.class, new Renderer());
    table.getTableHeader().setReorderingAllowed(false);
    restoreWidths();
    table.getColumnModel().addColumnModelListener(new WidthSaver());
    table.getSelectionModel().addListSelectionListener(this::selected);

    OnePixelSplitter split = new OnePixelSplitter(false, SPLIT_KEY, SPLIT_DEFAULT);
    split.setHonorComponentsMinimumSize(false);
    // 테두리는 분할선 한 줄만 둔다. 스크롤 칸 · 탭 내용 테두리가 겹치면 바깥이 두꺼워진다.
    split.setFirstComponent(ScrollPaneFactory.createScrollPane(table, true));
    split.setSecondComponent(ScrollPaneFactory.createScrollPane(side, true));

    JBTextArea raw = new JBTextArea(plan.raw());
    raw.setEditable(false);
    raw.setFont(monospace());
    raw.setBorder(JBUI.Borders.empty(6, 10));
    JBTabbedPane tabs = new JBTabbedPane();
    tabs.setTabComponentInsets(JBUI.emptyInsets());
    tabs.addTab(EiDbBundle.message("plan.tab.analysis"), split);
    tabs.addTab(EiDbBundle.message("plan.tab.raw"), ScrollPaneFactory.createScrollPane(raw, true));

    add(header(project, c), BorderLayout.NORTH);
    add(tabs, BorderLayout.CENTER);
    selectWorst();
  }

  private JComponent header(Project project, DbConfig c) {
    long danger = 0;
    for (Level l : levels.values()) if (l == Level.DANGER) danger++;
    long warning = levels.size() - danger;
    String text = levels.isEmpty() ? EiDbBundle.message("plan.summary.none") : EiDbBundle.message("plan.summary", levels.size(), danger, warning);
    if (plan.actual()) text += EiDbBundle.message("plan.summary.actual", plan.millis(), plan.fetched());
    Icon icon = danger > 0 ? AllIcons.General.Error : warning > 0 ? AllIcons.General.Warning : AllIcons.General.InspectionsOK;
    JBLabel summary = new JBLabel(text, icon, SwingConstants.LEFT);
    JPanel lines = new JPanel();
    lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
    lines.setOpaque(false);
    lines.add(summary);
    if (plan.note() != null) {
      JBLabel note = new JBLabel(plan.note());
      note.setForeground(UiColors.WARNING);
      lines.add(note);
    }
    JPanel head = new JPanel(new BorderLayout(JBUI.scale(10), 0));
    head.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 12)));
    head.add(lines, BorderLayout.CENTER);
    if (!plan.actual() && plan.kind() == DbKind.ORACLE && ExplainPlan.isQuery(plan.sql())) {
      JButton measure = new JButton(EiDbBundle.message("plan.measure"), AllIcons.Actions.Profile);
      measure.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
      measure.setToolTipText(EiDbBundle.message("plan.measure.tooltip"));
      measure.addActionListener(e -> ExplainPlan.measure(project, c, plan.sql(), plan.binds()));
      head.add(measure, BorderLayout.EAST);
    }
    return head;
  }

  /** 처음에는 가장 무거운 단계(심각 → 주의)를 고른다. 지적이 없으면 고르지 않고 안내만 둔다. */
  private void selectWorst() {
    int pick = -1;
    for (int i = 0; i < plan.steps().size(); i++) {
      Level l = levels.get(plan.steps().get(i).id());
      if (l == Level.DANGER) { pick = i; break; }
      if (l == Level.WARNING && pick < 0) pick = i;
    }
    if (pick >= 0) table.getSelectionModel().setSelectionInterval(pick, pick);
    else showSide(null);
  }

  private void selected(ListSelectionEvent e) {
    if (e.getValueIsAdjusting()) return;
    int row = table.getSelectedRow();
    showSide(row < 0 ? null : plan.steps().get(table.convertRowIndexToModel(row)));
  }

  private void showSide(Step step) {
    side.removeAll();
    if (step == null) {
      side.add(prose(EiDbBundle.message(levels.isEmpty() ? "plan.summary.none" : "plan.side.pick")));
    } else {
      List<Finding> mine = new ArrayList<>();
      for (Finding f : plan.findings()) if (f.stepId() == step.id()) mine.add(f);
      if (mine.isEmpty()) side.add(prose(EiDbBundle.message("plan.side.none")));
      for (Finding f : mine) addFinding(f);
      addCode("plan.side.access", step.access());
      addCode("plan.side.filter", step.filter());
    }
    side.revalidate();
    side.repaint();
  }

  private void addFinding(Finding f) {
    Object[] args = f.args().toArray();
    String key = "plan." + f.rule() + ".";
    JBLabel title = new JBLabel(EiDbBundle.message("plan.level." + f.level().name()) + " · " + EiDbBundle.message(key + "title", args),
        f.level() == Level.DANGER ? AllIcons.General.Error : AllIcons.General.Warning, SwingConstants.LEFT);
    title.setFont(title.getFont().deriveFont(Font.BOLD));
    title.setBorder(JBUI.Borders.emptyTop(side.getComponentCount() == 0 ? 0 : 14));
    side.add(left(title));
    side.add(heading("plan.side.cause"));
    side.add(prose(EiDbBundle.message(key + "cause", args)));
    side.add(heading("plan.side.fix"));
    side.add(prose(EiDbBundle.message(key + "fix", args)));
    if (f.example() != null) addCode("plan.side.example", f.example());
  }

  /** 제목 + 고정폭 글 상자 + 「복사」. 글이 비면 아무것도 넣지 않는다. */
  private void addCode(String titleKey, String code) {
    if (code == null || code.isBlank()) return;
    JBTextArea area = new JBTextArea(code);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setFont(monospace());
    area.setBorder(JBUI.Borders.empty(6, 8));
    ActionLink copy = new ActionLink(EiDbBundle.message("common.copy"), e -> {
      CopyPasteManager.getInstance().setContents(new StringSelection(code));
    });
    JPanel head = new JPanel(new BorderLayout());
    head.setOpaque(false);
    head.add(heading(titleKey), BorderLayout.CENTER);
    head.add(copy, BorderLayout.EAST);
    JPanel box = new JPanel(new BorderLayout());
    box.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
    box.add(area, BorderLayout.CENTER);
    side.add(left(head));
    side.add(left(box));
  }

  private static JComponent heading(String key) {
    JBLabel label = new JBLabel(EiDbBundle.message(key));
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setBorder(JBUI.Borders.empty(8, 0, 2, 0));
    return left(label);
  }

  /** 칸 폭에 맞춰 줄을 바꾸는 읽기 전용 글. */
  private static JComponent prose(String text) {
    JBTextArea area = new JBTextArea(text);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setOpaque(false);
    area.setFont(UIUtil.getLabelFont());
    area.setBorder(JBUI.Borders.empty());
    return left(area);
  }

  /** BoxLayout 세로 줄에서 왼쪽에 붙이고 가로로 다 채운다. */
  private static JComponent left(JComponent c) {
    c.setAlignmentX(Component.LEFT_ALIGNMENT);
    c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getMaximumSize().height));
    return c;
  }

  private static Font monospace() {
    return EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
  }

  private String widthsKey() { return WIDTHS_KEY + table.getColumnCount(); }

  private void restoreWidths() {
    TableColumnModel cols = table.getColumnModel();
    String saved = PropertiesComponent.getInstance().getValue(widthsKey(), "");
    String[] parts = saved.isEmpty() ? new String[0] : saved.split(",");
    for (int i = 0; i < cols.getColumnCount(); i++) {
      int width = (i == 0 ? STEP_WEIGHT : i == 1 ? OBJECT_WEIGHT : NUMBER_WEIGHT) * WEIGHT_UNIT;
      if (parts.length == cols.getColumnCount()) {
        try {
          width = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
          // 손상된 값은 처음 비율로 둔다.
        }
      }
      // 자동 맞춤(AUTO_RESIZE_SUBSEQUENT_COLUMNS)이라 이 값은 칸끼리의 비율로 쓰인다.
      cols.getColumn(i).setPreferredWidth(JBUI.scale(width));
    }
  }

  /** 칸 폭이 바뀌면(사용자가 끌거나 창이 바뀌면) 지금 폭들을 기억한다. 다음에는 그 비율로 연다. */
  private final class WidthSaver implements TableColumnModelListener {
    @Override
    public void columnMarginChanged(ChangeEvent e) {
      TableColumnModel cols = table.getColumnModel();
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < cols.getColumnCount(); i++) b.append(i == 0 ? "" : ",").append(JBUI.unscale(cols.getColumn(i).getWidth()));
      PropertiesComponent.getInstance().setValue(widthsKey(), b.toString());
    }

    @Override public void columnAdded(TableColumnModelEvent e) { }
    @Override public void columnRemoved(TableColumnModelEvent e) { }
    @Override public void columnMoved(TableColumnModelEvent e) { }
    @Override public void columnSelectionChanged(ListSelectionEvent e) { }
  }

  private final class Model extends AbstractTableModel {
    private final List<String> columns = new ArrayList<>(List.of("plan.col.step", "plan.col.object", "plan.col.rows", "plan.col.cost", "plan.col.share"));

    Model() {
      if (plan.actual()) columns.addAll(List.of("plan.col.starts", "plan.col.actual.rows", "plan.col.actual.ms", "plan.col.buffers"));
    }

    @Override public int getRowCount() { return plan.steps().size(); }
    @Override public int getColumnCount() { return columns.size(); }
    @Override public String getColumnName(int column) { return EiDbBundle.message(columns.get(column)); }

    @Override
    public Object getValueAt(int row, int column) {
      Step s = plan.steps().get(row);
      return switch (columns.get(column)) {
        case "plan.col.step" -> s;
        case "plan.col.object" -> s.object();
        case "plan.col.rows" -> s.rows();
        case "plan.col.cost" -> s.cost();
        case "plan.col.share" -> shares.containsKey(s.id()) ? PlanAdvisor.percent(shares.get(s.id())) + "%" : null;
        case "plan.col.starts" -> s.starts();
        case "plan.col.actual.rows" -> s.actualRows();
        case "plan.col.actual.ms" -> s.elapsedMicros() == null ? null : s.elapsedMicros() / 1000;
        case "plan.col.buffers" -> s.buffers();
        default -> null;
      };
    }
  }

  /** 단계 칸은 깊이만큼 들여 쓰고 수준 아이콘, 숫자는 오른쪽 맞춤(천 단위 쉼표). 지적이 있는 줄은 수준 색 바탕. */
  private final class Renderer extends ColoredTableCellRenderer {
    @Override
    protected void customizeCellRenderer(JTable t, Object value, boolean selected, boolean hasFocus, int row, int column) {
      Step s = plan.steps().get(t.convertRowIndexToModel(row));
      Level level = levels.get(s.id());
      if (!selected && level != null) setBackground(level == Level.DANGER ? DANGER_ROW : WARNING_ROW);
      if (value instanceof Step step) {
        setTextAlign(SwingConstants.LEFT);
        setIpad(JBUI.insetsLeft(step.depth() * INDENT));
        setIcon(level == Level.DANGER ? AllIcons.General.Error : level == Level.WARNING ? AllIcons.General.Warning : null);
        append(step.operation());
        setToolTipText(step.owner().isEmpty() ? null : step.owner() + "." + step.object());
        return;
      }
      setIpad(JBUI.emptyInsets());
      if (value instanceof Long n) {
        setTextAlign(SwingConstants.RIGHT);
        append(String.format("%,d", n));
      } else if (value != null) {
        setTextAlign(column == 1 ? SwingConstants.LEFT : SwingConstants.RIGHT);
        append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
      }
    }
  }

  /** 스크롤 칸 안에서 가로 폭을 칸에 맞춰 글이 줄바꿈되게 한다. */
  private static final class WidthTracking extends JPanel implements Scrollable {
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return JBUI.scale(16); }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
  }
}
