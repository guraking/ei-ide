package ei.db.bench;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.Meta;
import ei.db.console.SqlParams;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「목적으로 만들기」 탭: 무늬를 고르면 그 무늬에 필요한 칸만 그 일에 맞는 이름으로 보이고, 칸을 채우는 대로 바로 아래 SQL 칸에 문장이 나온다.
 * 칸은 모두 줄 폭을 다 채우는 고르는 글 칸(PickField)이다. 표 칸은 표 목록, 칼럼 칸은 그 표의 칼럼 목록을 펼친다.
 * 표 이름은 치다가 LOAD_DELAY_MS 동안 멈추면 그 표의 칼럼을 읽는다. 못 읽거나 칼럼이 없으면 표 칸 아래에 까닭을 보인다.
 */
final class PatternPanel {
  private static final int LOAD_DELAY_MS = 500;
  private static final String NO_TABLES = "테이블 목록을 아직 읽지 못했습니다. 위에서 접속을 확인하세요. 이름을 직접 쳐도 됩니다.";
  private static final String NO_COLUMNS = "칼럼 목록이 없습니다. 테이블을 먼저 고르세요. 이름을 직접 쳐도 됩니다.";

  private final Bench bench;
  private final JPanel form;
  private final ComboBox<Patterns.Kind> kind = new ComboBox<>();
  private final JBLabel description = new JBLabel();
  private final JBLabel tableStatus = new JBLabel();
  private final Map<Patterns.Field, JTextField> texts = new EnumMap<>(Patterns.Field.class);
  private final Map<Patterns.Field, JComponent[]> rows = new EnumMap<>(Patterns.Field.class);
  private final Map<Patterns.Field, JBLabel> labels = new EnumMap<>(Patterns.Field.class);
  private final List<String> columnNames = new ArrayList<>();
  /** 표 목록 상자 항목(「이름  — 설명」). 접속이 바뀌면 다시 읽는다. */
  private final List<String> tableItems = new ArrayList<>();
  private final Timer loadTimer = new Timer(LOAD_DELAY_MS, e -> loadColumns());
  /** 칼럼을 읽어 둔 표 이름(대문자). 같은 표면 다시 읽지 않는다. */
  private String loadedTable = "";
  /** 칼럼 칸에 든 이름이 속한 표(대문자). 다른 표의 칼럼을 읽으면 칼럼 칸을 비운다. */
  private String columnsOf = "";

  PatternPanel(Bench bench) {
    this.bench = bench;
    loadTimer.setRepeats(false);
    for (Patterns.Kind k : Patterns.Kind.values()) kind.addItem(k);
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

    FormBuilder fb = FormBuilder.createFormBuilder()
        .addLabeledComponent("하고 싶은 것", full(kind))
        .addComponentToRightColumn(description);
    for (Patterns.Field f : Patterns.Field.values()) {
      JComponent c;
      if (f == Patterns.Field.STATUS_VALUES) {
        JBTextField t = new JBTextField();
        t.getEmptyText().setText(Patterns.hint(f));
        texts.put(f, t);
        c = t;
      } else {
        boolean table = f == Patterns.Field.TABLE || f == Patterns.Field.TABLE2;
        PickField pick = new PickField(f == Patterns.Field.KEYS || f == Patterns.Field.COLS || f == Patterns.Field.GROUP, table ? () -> tableItems : () -> columnNames,
            table ? NO_TABLES : NO_COLUMNS);
        pick.field.getEmptyText().setText(Patterns.hint(f));
        texts.put(f, pick.field);
        c = full(pick.field);
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
    for (Map.Entry<Patterns.Field, JTextField> e : texts.entrySet()) {
      boolean table = e.getKey() == Patterns.Field.TABLE;
      e.getValue().getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent ev) {
          if (table) loadTimer.restart();
          refresh();
        }
      });
    }
  }

  /** 위에 놓을 입력 칸들(아래 SQL 칸은 작업대가 붙인다). */
  JComponent component() { return form; }

  /** 접속이 바뀌면 칼럼을 다시 읽는다. 친 글은 그대로 둔다. */
  void connectionChanged() {
    loadedTable = "";
    columnNames.clear();
    tableItems.clear();
    if (bench.config() != null) {
      bench.tables(all -> {
        tableItems.clear();
        for (Meta.Table t : all) tableItems.add(t.comment().isEmpty() ? t.name() : t.name() + PickText.COMMENT_GAP + t.comment());
      });
    }
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
        warn("테이블 " + name + " 를 이 접속의 기본 스키마에서 찾지 못했습니다. 이름을 확인하거나 목록에서 고르세요. SQL 은 친 이름 그대로 만듭니다.");
        return;
      }
      String found = meta;
      bench.columns(found, list -> {
        if (!name.equals(text(Patterns.Field.TABLE))) return;
        columnNames.clear();
        for (Meta.Column c : list) columnNames.add(c.name());
        loadedTable = key;
        if (!columnsOf.isEmpty() && !columnsOf.equals(key)) clearColumns();
        columnsOf = key;
        if (list.isEmpty()) {
          warn("테이블 " + found + " 의 칼럼을 읽지 못했습니다(권한이 없거나 빈 테이블 정의). 칼럼 이름을 직접 치세요.");
        } else {
          tableStatus.setText("칼럼 " + list.size() + "개를 읽었습니다. 칼럼 칸의 목록에서 고르세요.");
          tableStatus.setForeground(UIUtil.getContextHelpForeground());
        }
      });
    });
  }

  /** 표 칸 둘을 뺀 칸(칼럼 이름 · 그 칼럼의 값)을 비운다. */
  private void clearColumns() {
    for (Map.Entry<Patterns.Field, JTextField> e : texts.entrySet()) {
      if (e.getKey() != Patterns.Field.TABLE && e.getKey() != Patterns.Field.TABLE2) e.getValue().setText("");
    }
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
        split(text(Patterns.Field.KEYS)), split(text(Patterns.Field.COLS)), text(Patterns.Field.GROUP),
        text(Patterns.Field.VALUE), text(Patterns.Field.ORDER), text(Patterns.Field.PARENT), text(Patterns.Field.STATUS),
        split(text(Patterns.Field.STATUS_VALUES)));
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

  /** 상자를 그 줄 폭에 맞게 늘린다(FormBuilder 는 상자를 원래 폭으로 둔다). */
  static JComponent full(JComponent c) {
    JPanel p = new JPanel(new BorderLayout());
    p.add(c, BorderLayout.CENTER);
    return p;
  }

  private String text(Patterns.Field f) {
    JTextField t = texts.get(f);
    return t == null ? "" : t.getText().strip();
  }

  private static List<String> split(String text) {
    List<String> out = new ArrayList<>();
    for (String s : text.split(",")) if (!s.isBlank()) out.add(s.strip());
    return out;
  }
}
