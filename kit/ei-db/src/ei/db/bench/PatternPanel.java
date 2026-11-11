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

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.event.ItemEvent;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 「목적으로 만들기」 탭: 무늬를 고르면 그 무늬에 필요한 칸만 그 일에 맞는 이름으로 보이고, 칸을 채우는 대로 바로 아래 SQL 칸에 문장이 나온다.
 * 칸은 모두 줄 폭을 다 채우는 고칠 수 있는 목록 상자다. 표 칸은 표 목록, 칼럼 칸은 그 표의 칼럼 목록을 펼치고,
 * 치는 글을 담은 항목만 남긴다. 여러 칼럼 칸(KEYS · COLS)은 마지막 쉼표 뒤 글로 거르고, 고르면 쉼표로 이어 붙인다.
 * 표 이름은 치다가 LOAD_DELAY_MS 동안 멈추면 그 표의 칼럼을 읽는다. 못 읽거나 칼럼이 없으면 표 칸 아래에 까닭을 보인다.
 */
final class PatternPanel {
  private static final int LOAD_DELAY_MS = 500;
  /** 표 목록 항목에서 이름과 설명 사이. 고르면 앞 이름만 넣는다. */
  private static final String COMMENT_GAP = "  — ";

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
        PickBox box = new PickBox(f == Patterns.Field.KEYS || f == Patterns.Field.COLS, table ? () -> tableItems : () -> columnNames);
        if (box.editor instanceof JBTextField jb) jb.getEmptyText().setText(Patterns.hint(f));
        texts.put(f, box.editor);
        c = full(box.box);
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
        for (Meta.Table t : all) tableItems.add(t.comment().isEmpty() ? t.name() : t.name() + COMMENT_GAP + t.comment());
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
        warn("표 " + name + " 를 이 접속의 기본 스키마에서 찾지 못했습니다. 이름을 확인하거나 목록에서 고르세요. SQL 은 친 이름 그대로 만듭니다.");
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
          tableStatus.setText("칼럼 " + list.size() + "개를 읽었습니다. 칼럼 칸의 목록에서 고르세요.");
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

  /**
   * 고칠 수 있는 목록 상자. 펼칠 때와 글을 칠 때 source 항목 중 치는 글(여러 값이면 마지막 쉼표 뒤)을 담은 것만 남긴다.
   * 방향키로 움직여도 글은 바뀌지 않고(isTableCellEditor), Enter · 클릭으로 고를 때만 넣는다.
   * 「이름  — 설명」 항목은 이름만 넣는다. 모델을 다시 채우는 동안(adjusting)에는 친 글 · 커서를 지킨다.
   */
  private static final class PickBox {
    final ComboBox<String> box = new ComboBox<>();
    final JTextField editor;
    private final boolean many;
    private final Supplier<List<String>> source;
    private boolean adjusting;
    /** 사용자가 마지막으로 친 글. 여러 값 칸에서 고른 항목을 이 글의 마지막 쉼표 뒤에 붙인다. */
    private String typed = "";

    PickBox(boolean many, Supplier<List<String>> source) {
      this.many = many;
      this.source = source;
      box.setEditable(true);
      box.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);
      // shortcut: 테마를 바꾸면 상자가 글 칸을 새로 만들어 이 참조 · 감시가 끊긴다. 작업대를 다시 열면 된다.
      editor = (JTextField) box.getEditor().getEditorComponent();
      editor.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) {
          if (adjusting) return;
          typed = editor.getText();
          // 문서 알림 안에서는 글을 못 바꾸므로 거르기는 알림이 끝난 뒤에 한다.
          SwingUtilities.invokeLater(() -> {
            if (!editor.isFocusOwner()) return;
            rebuild();
            if (box.getItemCount() > 0) box.showPopup();
            else box.hidePopup();
          });
        }
      });
      box.addPopupMenuListener(new PopupMenuListener() {
        @Override
        public void popupMenuWillBecomeVisible(PopupMenuEvent e) { if (!adjusting) rebuild(); }
        @Override
        public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
        @Override
        public void popupMenuCanceled(PopupMenuEvent e) { }
      });
      box.addItemListener(e -> {
        if (adjusting || e.getStateChange() != ItemEvent.SELECTED || e.getItem() == null) return;
        String name = e.getItem().toString().split(COMMENT_GAP)[0].strip();
        // 상자가 고른 항목으로 글을 덮은 뒤에 넣는다.
        SwingUtilities.invokeLater(() -> put(name));
      });
    }

    private void put(String name) {
      String text = many ? head(typed) + name : name;
      adjusting = true;
      try {
        editor.setText(text);
        editor.setCaretPosition(text.length());
      } finally {
        adjusting = false;
      }
      typed = text;
      box.hidePopup();
    }

    private void rebuild() {
      String keep = editor.getText();
      int caret = editor.getCaretPosition();
      String needle = (many ? tail(typed) : typed).strip().toLowerCase(Locale.ROOT);
      adjusting = true;
      try {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) box.getModel();
        model.removeAllElements();
        for (String item : source.get()) if (item.toLowerCase(Locale.ROOT).contains(needle)) model.addElement(item);
        model.setSelectedItem(null);
        editor.setText(keep);
        editor.setCaretPosition(Math.min(caret, keep.length()));
      } finally {
        adjusting = false;
      }
    }

    /** 마지막 쉼표까지(쉼표 뒤 한 칸 포함). 쉼표가 없으면 빈 글. */
    private static String head(String text) {
      int comma = text.lastIndexOf(',');
      return comma < 0 ? "" : text.substring(0, comma + 1) + " ";
    }

    private static String tail(String text) {
      return text.substring(text.lastIndexOf(',') + 1);
    }
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
