package ei.db.bench;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.Meta;
import ei.db.console.SqlParams;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「목적으로 만들기」 · 「현황 · 집계」 탭: 무늬를 고르면 그 무늬에 필요한 칸만 그 일에 맞는 이름으로 보이고, 칸을 채우는 대로 바로 아래 SQL 칸에 문장이 나온다.
 * 칸은 모두 줄 폭을 다 채우는 글 칸이고, 오른쪽 「고르기…」 로 표 · 칼럼 목록에서 찾아 넣는다(여러 칼럼 칸은 쉼표로 이어 붙인다).
 * 표 이름은 치다가 LOAD_DELAY_MS 동안 멈추면 그 표의 칼럼을 읽는다. 못 읽거나 칼럼이 없으면 표 칸 아래에 까닭을 보인다.
 */
final class PatternPanel {
  private static final int MAX_TOP_N = 10_000;
  private static final int DEFAULT_TOP_N = 10;
  private static final int LOAD_DELAY_MS = 500;

  private final Bench bench;
  private final JPanel form;
  private final ComboBox<Patterns.Kind> kind = new ComboBox<>();
  private final JBLabel description = new JBLabel();
  private final JBLabel tableStatus = new JBLabel();
  private final Map<Patterns.Field, JBTextField> texts = new EnumMap<>(Patterns.Field.class);
  private final JBIntSpinner topN = new JBIntSpinner(DEFAULT_TOP_N, 1, MAX_TOP_N);
  private final ComboBox<Patterns.Period> period = new ComboBox<>(Patterns.Period.values());
  private final Map<Patterns.Field, JComponent[]> rows = new EnumMap<>(Patterns.Field.class);
  private final Map<Patterns.Field, JBLabel> labels = new EnumMap<>(Patterns.Field.class);
  private final List<String> columnNames = new ArrayList<>();
  private final Timer loadTimer = new Timer(LOAD_DELAY_MS, e -> loadColumns());
  /** 칼럼을 읽어 둔 표 이름(대문자). 같은 표면 다시 읽지 않는다. */
  private String loadedTable = "";

  PatternPanel(Bench bench, boolean dashboard) {
    this.bench = bench;
    loadTimer.setRepeats(false);
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
    tableStatus.setForeground(UIUtil.getContextHelpForeground());
    tableStatus.setVisible(false);
    period.setSelectedItem(Patterns.Period.MONTH);

    FormBuilder fb = FormBuilder.createFormBuilder()
        .addLabeledComponent(dashboard ? "보고 싶은 현황" : "하고 싶은 것", full(kind))
        .addComponentToRightColumn(description);
    for (Patterns.Field f : Patterns.Field.values()) {
      JComponent c;
      if (f == Patterns.Field.TOP_N) {
        c = topN;
      } else if (f == Patterns.Field.PERIOD) {
        c = full(period);
      } else {
        JBTextField t = new JBTextField();
        t.getEmptyText().setText(Patterns.hint(f));
        texts.put(f, t);
        c = f == Patterns.Field.STATUS_VALUES ? t : withPicker(f, t);
      }
      JBLabel label = new JBLabel(f.label);
      labels.put(f, label);
      fb.addLabeledComponent(label, c);
      if (f == Patterns.Field.TABLE) fb.addComponentToRightColumn(tableStatus);
      rows.put(f, new JComponent[]{label, c});
    }
    form = fb.getPanel();
    form.setBorder(JBUI.Borders.empty(8, 8, 4, 8));

    kind.addActionListener(e -> refresh());
    for (Map.Entry<Patterns.Field, JBTextField> e : texts.entrySet()) {
      boolean table = e.getKey() == Patterns.Field.TABLE;
      e.getValue().getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent ev) {
          if (table) loadTimer.restart();
          refresh();
        }
      });
    }
    topN.addChangeListener(e -> refresh());
    period.addActionListener(e -> refresh());
  }

  /** 위에 놓을 입력 칸들(아래 SQL 칸은 작업대가 붙인다). */
  JComponent component() { return form; }

  /** 시작 화면에서 무늬 하나를 골라 연다. */
  void select(Patterns.Kind k) { kind.setSelectedItem(k); }

  /** 접속이 바뀌면 칼럼을 다시 읽는다. 친 글은 그대로 둔다. */
  void connectionChanged() {
    loadedTable = "";
    columnNames.clear();
    if (bench.config() != null) bench.tables(list -> { });
    loadColumns();
  }

  /** 표 칸의 이름으로 칼럼을 읽는다. 표 목록에 없으면 까닭을 보인다. */
  private void loadColumns() {
    String name = text(Patterns.Field.TABLE);
    String key = name.toUpperCase(Locale.ROOT);
    if (name.isEmpty() || bench.config() == null) {
      tableStatus.setVisible(false);
      return;
    }
    if (key.equals(loadedTable)) return;
    tableStatus.setText(name + " 칼럼 읽는 중…");
    tableStatus.setForeground(UIUtil.getContextHelpForeground());
    tableStatus.setVisible(true);
    String plain = SqlParts.simpleName(name).replace("\"", "");
    bench.tables(all -> {
      String meta = null;
      for (Meta.Table t : all) if (t.name().equalsIgnoreCase(plain)) meta = t.name();
      if (!name.equals(text(Patterns.Field.TABLE))) return;
      if (meta == null) {
        columnNames.clear();
        loadedTable = "";
        warn("표 " + name + " 를 이 접속의 기본 스키마에서 찾지 못했습니다. 이름을 확인하거나 「고르기…」 로 고르세요. SQL 은 친 이름 그대로 만듭니다.");
        return;
      }
      String found = meta;
      bench.columns(found, list -> {
        if (!name.equals(text(Patterns.Field.TABLE))) return;
        columnNames.clear();
        for (Meta.Column c : list) columnNames.add(c.name());
        loadedTable = key;
        if (list.isEmpty()) {
          warn("표 " + found + " 의 칼럼을 읽지 못했습니다(권한이 없거나 빈 표 정의). 칼럼 이름을 직접 치세요.");
        } else {
          tableStatus.setText("칼럼 " + list.size() + "개를 읽었습니다. 칼럼 칸의 「고르기…」 로 고르세요.");
          tableStatus.setForeground(UIUtil.getContextHelpForeground());
        }
      });
    });
  }

  private void warn(String text) {
    tableStatus.setText(text);
    tableStatus.setForeground(UIUtil.getErrorForeground());
    tableStatus.setVisible(true);
  }

  /** 지금 무늬에 맞게 칸 이름 · 보이기를 바꾸고, 다 찼으면 문장을, 아니면 남은 칸을 아래에 보낸다. */
  void refresh() {
    Patterns.Kind k = (Patterns.Kind) kind.getSelectedItem();
    if (k == null) return;
    description.setText(k.description);
    for (Map.Entry<Patterns.Field, JComponent[]> e : rows.entrySet()) {
      boolean on = k.fields.contains(e.getKey());
      for (JComponent c : e.getValue()) c.setVisible(on);
      labels.get(e.getKey()).setText(Patterns.label(k, e.getKey()));
    }
    tableStatus.setVisible(tableStatus.isVisible() && !tableStatus.getText().isEmpty());
    form.revalidate();
    form.repaint();
    Patterns.Fill f = new Patterns.Fill(bench.kind(), bench.style(), bench.bind(), text(Patterns.Field.TABLE), text(Patterns.Field.TABLE2),
        split(text(Patterns.Field.KEYS)), split(text(Patterns.Field.COLS)), text(Patterns.Field.GROUP), text(Patterns.Field.DATE),
        text(Patterns.Field.VALUE), text(Patterns.Field.ORDER), text(Patterns.Field.PARENT), text(Patterns.Field.STATUS),
        split(text(Patterns.Field.STATUS_VALUES)), topN.getNumber(), (Patterns.Period) period.getSelectedItem());
    List<Patterns.Field> missing = Patterns.missing(k, f);
    if (!missing.isEmpty()) {
      List<String> names = new ArrayList<>();
      for (Patterns.Field m : missing) names.add("「" + Patterns.label(k, m) + "」");
      bench.show("", List.of("채워 주세요: " + String.join(", ", names)));
      return;
    }
    String sql;
    try {
      sql = Patterns.render(k, f);
    } catch (RuntimeException e) {
      bench.show("", List.of("문장을 만들지 못했습니다: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
      return;
    }
    List<String> notes = new ArrayList<>();
    if (!SqlParams.find(sql).isEmpty()) notes.add("값 자리(:이름 · #{이름})는 실행할 때 넣습니다.");
    bench.show(sql, notes);
  }

  /** 글 칸이 줄을 채우고 오른쪽에 「고르기…」. 표 칸은 표 목록, 나머지는 칼럼 목록. 여러 값 칸(KEYS · COLS)은 쉼표로 이어 붙인다. */
  private JComponent withPicker(Patterns.Field field, JBTextField f) {
    boolean table = field == Patterns.Field.TABLE || field == Patterns.Field.TABLE2;
    boolean many = field == Patterns.Field.KEYS || field == Patterns.Field.COLS;
    JButton pick = new JButton("고르기…");
    pick.addActionListener(e -> {
      if (table) {
        if (bench.config() == null) return;
        bench.tables(all -> {
          List<String> names = new ArrayList<>();
          for (Meta.Table t : all) names.add(t.comment().isEmpty() ? t.name() : t.name() + "  — " + t.comment());
          choose(pick, names, false, picked -> f.setText(picked.get(0).split("  — ")[0]));
        });
        return;
      }
      if (columnNames.isEmpty()) {
        warn("표 칸에 표를 먼저 넣으세요. 칼럼 목록은 그 표에서 읽습니다.");
        return;
      }
      choose(pick, columnNames, many, picked -> {
        if (!many) {
          f.setText(picked.get(0));
          return;
        }
        List<String> now = split(f.getText());
        for (String c : picked) if (!now.contains(c)) now.add(c);
        f.setText(String.join(", ", now));
      });
    });
    JPanel p = new JPanel(new BorderLayout(JBUI.scale(4), 0));
    p.add(f, BorderLayout.CENTER);
    p.add(pick, BorderLayout.EAST);
    return p;
  }

  /** 글자를 치면 거르는 목록 팝업. many 면 Ctrl · Shift 로 여러 개. */
  private static void choose(JComponent anchor, List<String> items, boolean many, java.util.function.Consumer<List<String>> done) {
    if (items.isEmpty()) return;
    JBPopupFactory.getInstance().createPopupChooserBuilder(items)
        .setTitle(many ? "고르기 (Ctrl · Shift 로 여러 개, 글자를 치면 거름)" : "고르기 (글자를 치면 거름)")
        .setSelectionMode(many ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION)
        .setNamerForFiltering(s -> s)
        .setItemsChosenCallback(chosen -> { if (!chosen.isEmpty()) done.accept(new ArrayList<>(chosen)); })
        .createPopup()
        .showUnderneathOf(anchor);
  }

  /** 상자를 그 줄 폭에 맞게 늘린다(FormBuilder 는 상자를 원래 폭으로 둔다). */
  static JComponent full(JComponent c) {
    JPanel p = new JPanel(new BorderLayout());
    p.add(c, BorderLayout.CENTER);
    return p;
  }

  private String text(Patterns.Field f) {
    JBTextField t = texts.get(f);
    return t == null ? "" : t.getText().strip();
  }

  private static List<String> split(String text) {
    List<String> out = new ArrayList<>();
    for (String s : text.split(",")) if (!s.isBlank()) out.add(s.strip());
    return out;
  }
}
