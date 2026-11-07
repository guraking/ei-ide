package ei.db.bench;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.Meta;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 「목적으로 만들기」 · 「현황 · 집계」 탭: 무늬를 고르면 그 무늬에 필요한 칸만 보이고, 칸을 채우는 대로 미리 보기에 문장이 나온다.
 * 표 칸은 접속의 표 목록에서, 칼럼 칸은 고른 표의 칼럼에서 고르거나 직접 칠 수 있다. 여러 칼럼 칸은 쉼표로 나눈다.
 */
final class PatternPanel {
  private static final int MAX_TOP_N = 10_000;
  private static final int DEFAULT_TOP_N = 10;

  private final Bench bench;
  private final JPanel root = new JPanel(new BorderLayout());
  private final ComboBox<Patterns.Kind> kind = new ComboBox<>();
  private final JBLabel description = new JBLabel();
  private final ComboBox<String> table = editable();
  private final ComboBox<String> table2 = editable();
  private final JBTextField keys = new JBTextField();
  private final JBTextField cols = new JBTextField();
  private final ComboBox<String> group = editable();
  private final ComboBox<String> date = editable();
  private final ComboBox<String> value = editable();
  private final ComboBox<String> order = editable();
  private final ComboBox<String> parent = editable();
  private final ComboBox<String> status = editable();
  private final JBTextField statusValues = new JBTextField();
  private final JBIntSpinner topN = new JBIntSpinner(DEFAULT_TOP_N, 1, MAX_TOP_N);
  private final ComboBox<Patterns.Period> period = new ComboBox<>(Patterns.Period.values());
  private final Map<Patterns.Field, JComponent[]> rows = new EnumMap<>(Patterns.Field.class);
  private final List<String> columnNames = new ArrayList<>();

  PatternPanel(Bench bench, boolean dashboard) {
    this.bench = bench;
    for (Patterns.Kind k : Patterns.Kind.values()) if (k.dashboard == dashboard) kind.addItem(k);
    kind.setRenderer(new ColoredListCellRenderer<>() {
      @Override
      protected void customizeCellRenderer(@NotNull JList<? extends Patterns.Kind> list, Patterns.Kind k, int index, boolean selected, boolean focus) {
        if (k == null) return;
        append(k.label);
        if (index >= 0) append("   " + k.description, SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
    });
    description.setForeground(UIUtil.getContextHelpForeground());
    description.setAllowAutoWrapping(true);
    period.setSelectedItem(Patterns.Period.MONTH);

    FormBuilder form = FormBuilder.createFormBuilder()
        .addLabeledComponent(dashboard ? "보고 싶은 현황" : "하고 싶은 것", kind)
        .addComponentToRightColumn(description);
    row(form, Patterns.Field.TABLE, table);
    row(form, Patterns.Field.TABLE2, table2);
    row(form, Patterns.Field.KEYS, withPicker(keys));
    row(form, Patterns.Field.COLS, withPicker(cols));
    row(form, Patterns.Field.GROUP, group);
    row(form, Patterns.Field.DATE, date);
    row(form, Patterns.Field.VALUE, value);
    row(form, Patterns.Field.ORDER, order);
    row(form, Patterns.Field.PARENT, parent);
    row(form, Patterns.Field.STATUS, status);
    row(form, Patterns.Field.STATUS_VALUES, statusValues);
    row(form, Patterns.Field.TOP_N, topN);
    row(form, Patterns.Field.PERIOD, period);
    JPanel panel = form.addComponentFillVertically(new JPanel(), 0).getPanel();
    panel.setBorder(JBUI.Borders.empty(8));
    root.add(new JBScrollPane(panel), BorderLayout.CENTER);

    kind.addActionListener(e -> refresh());
    table.addActionListener(e -> loadColumns());
    for (ComboBox<String> c : List.of(table2, group, date, value, order, parent, status)) c.addActionListener(e -> refresh());
    for (JTextField f : List.of(keys, cols, statusValues, field(table), field(table2), field(group), field(date), field(value), field(order), field(parent), field(status))) {
      f.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) { refresh(); }
      });
    }
    topN.addChangeListener(e -> refresh());
    period.addActionListener(e -> refresh());
  }

  JComponent component() { return root; }

  /** 접속이 바뀌면 표 목록을 다시 넣는다. 친 글은 그대로 둔다. */
  void connectionChanged() {
    if (bench.config() == null) return;
    bench.tables(list -> {
      for (ComboBox<String> c : List.of(table, table2)) {
        Object keep = c.getEditor().getItem();
        c.removeAllItems();
        for (Meta.Table t : list) c.addItem(t.name());
        c.getEditor().setItem(keep);
      }
    });
  }

  private void loadColumns() {
    String name = text(table);
    if (name.isEmpty() || bench.config() == null) {
      refresh();
      return;
    }
    bench.columns(name, list -> {
      if (!name.equals(text(table))) return;
      columnNames.clear();
      for (Meta.Column c : list) columnNames.add(c.name());
      for (ComboBox<String> c : List.of(group, date, value, order, parent, status)) {
        Object keep = c.getEditor().getItem();
        c.removeAllItems();
        c.addItem("");
        for (String n : columnNames) c.addItem(n);
        c.getEditor().setItem(keep);
      }
      refresh();
    });
    refresh();
  }

  /** 지금 무늬에 맞게 칸을 보이고, 다 찼으면 문장을, 아니면 남은 칸을 미리 보기에 보낸다. */
  void refresh() {
    Patterns.Kind k = (Patterns.Kind) kind.getSelectedItem();
    if (k == null) return;
    description.setText(k.description);
    for (Map.Entry<Patterns.Field, JComponent[]> e : rows.entrySet()) {
      boolean on = k.fields.contains(e.getKey());
      for (JComponent c : e.getValue()) c.setVisible(on);
    }
    root.revalidate();
    Patterns.Fill f = new Patterns.Fill(bench.kind(), bench.style(), bench.bind(), text(table), text(table2), split(keys.getText()), split(cols.getText()),
        text(group), text(date), text(value), text(order), text(parent), text(status), split(statusValues.getText()), topN.getNumber(),
        (Patterns.Period) period.getSelectedItem());
    List<Patterns.Field> missing = Patterns.missing(k, f);
    if (!missing.isEmpty()) {
      List<String> names = new ArrayList<>();
      for (Patterns.Field m : missing) names.add("「" + m.label + "」");
      bench.show("", List.of("채워 주세요: " + String.join(", ", names)));
      return;
    }
    String sql = Patterns.render(k, f);
    List<String> notes = new ArrayList<>();
    if (sql.contains(":") || sql.contains("#{")) notes.add("값 자리(:이름 · #{이름})는 실행할 때 넣습니다.");
    bench.show(sql, notes);
  }

  /** 칸 하나: 왼쪽 이름표, 오른쪽 칸(그 줄 폭을 다 채움). 무늬에 없는 칸은 둘 다 숨긴다. */
  private void row(FormBuilder form, Patterns.Field field, JComponent c) {
    JBLabel label = new JBLabel(field.label);
    form.addLabeledComponent(label, c);
    rows.put(field, new JComponent[]{label, c});
  }

  /** 여러 칼럼 칸: 글 칸이 줄을 채우고 오른쪽에 「칼럼 고르기」. 고른 것은 쉼표로 이어 붙인다. */
  private JComponent withPicker(JBTextField f) {
    JButton pick = new JButton("칼럼 고르기…");
    pick.addActionListener(e -> {
      if (columnNames.isEmpty()) return;
      JBPopupFactory.getInstance().createPopupChooserBuilder(columnNames)
          .setTitle("칼럼 (Ctrl · Shift 로 여러 개)")
          .setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
          .setItemsChosenCallback(chosen -> {
            List<String> now = split(f.getText());
            for (String c : chosen) if (!now.contains(c)) now.add(c);
            f.setText(String.join(", ", now));
          })
          .createPopup()
          .showUnderneathOf(pick);
    });
    JPanel p = new JPanel(new BorderLayout(JBUI.scale(4), 0));
    p.add(f, BorderLayout.CENTER);
    p.add(pick, BorderLayout.EAST);
    return p;
  }

  private static ComboBox<String> editable() {
    ComboBox<String> c = new ComboBox<>();
    c.setEditable(true);
    return c;
  }

  private static JTextField field(JComboBox<String> c) {
    return (JTextField) c.getEditor().getEditorComponent();
  }

  private static String text(JComboBox<String> c) {
    Object v = c.getEditor().getItem();
    return v == null ? "" : v.toString().strip();
  }

  private static List<String> split(String text) {
    List<String> out = new ArrayList<>();
    for (String s : text.split(",")) if (!s.isBlank()) out.add(s.strip());
    return out;
  }
}
