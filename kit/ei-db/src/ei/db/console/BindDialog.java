package ei.db.console;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ei.db.EiDbBundle;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 콘솔 실행 전 이름 자리(:name · #{name} · ${name}) 값 입력. 값은 이름마다 마지막 것을 프로젝트에 기억한다.
 * 비밀번호 이름(RunHistory.isSecretName)의 값은 기억하지 않고, 실행 기록에서 가리도록 RunHistory 에 알린다.
 * 값으로 바꿀 수 없는 글(잘못된 날짜 꼴)이 있으면 실행하지 않고 그 칸에 알린다. 화면 스레드에서 부른다.
 */
final class BindDialog extends DialogWrapper {
  private static final java.util.regex.Pattern DATE_LIKE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?");
  private static final String VALUE_KEY = "ei.db.console.bind.";
  private static final int VALUE_COLUMNS = 28;
  private static final int MAX_VISIBLE_ROWS = 12;
  private static final int ROW_HEIGHT = 30;

  private final Project project;
  private final Map<String, JBTextField> fields = new LinkedHashMap<>();
  private final List<SqlParams.Param> params;

  private BindDialog(Project project, List<SqlParams.Param> params) {
    super(project);
    this.project = project;
    this.params = params;
    setTitle(EiDbBundle.message("bind.title"));
    setOKButtonText(EiDbBundle.message("common.run"));
    setCancelButtonText(EiDbBundle.message("common.cancel"));
    init();
  }

  /**
   * 문장들을 자리 값으로 바꾼다. 자리가 없으면 창 없이 그대로(binds null). 취소하면 null.
   * 같은 이름은 여러 문장에 걸쳐 한 번만 묻는다.
   */
  static @Nullable List<SqlParams.Bound> ask(Project project, List<String> sqls) {
    List<SqlParams.Param> params = SqlParams.distinct(sqls);
    Map<String, String> values = new HashMap<>();
    if (!params.isEmpty()) {
      BindDialog d = new BindDialog(project, params);
      if (!d.showAndGet()) return null;
      values = d.values();
    }
    List<SqlParams.Bound> out = new ArrayList<>();
    for (SqlParams.Param p : params) {
      if (p.kind() != SqlParams.Kind.RAW && RunHistory.isSecretName(p.name())) RunHistory.markSecret(SqlParams.value(values.get(p.name())));
    }
    for (String sql : sqls) out.add(SqlParams.apply(sql, values));
    return out;
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    JPanel grid = new JPanel(new GridBagLayout());
    GridBagConstraints g = new GridBagConstraints();
    g.insets = JBUI.insets(2, 0, 2, 10);
    g.anchor = GridBagConstraints.WEST;
    g.gridy = 0;
    addRow(grid, g, header(EiDbBundle.message("common.col.name")), header(EiDbBundle.message("bind.col.value")), header(EiDbBundle.message("common.col.type")));
    for (SqlParams.Param p : params) {
      JBTextField value = new JBTextField(props.getValue(VALUE_KEY + p.name(), ""), VALUE_COLUMNS);
      JBLabel type = new JBLabel();
      type.setForeground(UIUtil.getContextHelpForeground());
      updateType(type, p, value.getText());
      value.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override protected void textChanged(@NotNull DocumentEvent e) { updateType(type, p, value.getText()); }
      });
      fields.put(p.name(), value);
      JBLabel name = new JBLabel(p.label());
      name.setToolTipText(p.label());
      g.gridy++;
      addRow(grid, g, name, value, type);
    }
    JPanel top = new JPanel(new BorderLayout());
    top.add(grid, BorderLayout.NORTH);
    JBScrollPane scroll = new JBScrollPane(top);
    scroll.setBorder(JBUI.Borders.empty());
    int rows = Math.min(params.size() + 1, MAX_VISIBLE_ROWS);
    scroll.setPreferredSize(new Dimension(grid.getPreferredSize().width + JBUI.scale(20), JBUI.scale(ROW_HEIGHT) * rows));

    JBLabel note = new JBLabel(EiDbBundle.message("bind.note"),
                               UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
    note.setBorder(JBUI.Borders.emptyTop(8));
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(scroll, BorderLayout.CENTER);
    panel.add(note, BorderLayout.SOUTH);
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return fields.isEmpty() ? null : fields.values().iterator().next();
  }

  /** 「실행」 전에 DialogWrapper 가 부른다. 값으로 바꿀 수 없는 칸이 있으면 그 칸에 문구를 띄우고 닫지 않는다. */
  @Override
  protected @Nullable ValidationInfo doValidate() {
    for (SqlParams.Param p : params) {
      JBTextField field = fields.get(p.name());
      if (p.kind() != SqlParams.Kind.RAW && badDate(field.getText())) {
        return new ValidationInfo(EiDbBundle.message("bind.bad.date", p.label(), field.getText().strip()), field);
      }
    }
    return null;
  }

  /** 비밀번호 이름의 값은 기억하지 않는다(지난 값을 지운다). */
  @Override
  protected void doOKAction() {
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    for (Map.Entry<String, JBTextField> e : fields.entrySet()) {
      if (RunHistory.isSecretName(e.getKey())) props.unsetValue(VALUE_KEY + e.getKey());
      else props.setValue(VALUE_KEY + e.getKey(), e.getValue().getText());
    }
    super.doOKAction();
  }

  /** 값으로 바꾸다 난 예외. 바뀌면 null. SqlValues.parse 는 2026-13-01 처럼 날짜 꼴인데 없는 날짜면 예외를 낸다. */
  /** 따옴표 없이 날짜 꼴(yyyy-mm-dd[ hh:mm[:ss]])인데 날짜로 읽히지 않는 값(2026-13-01 등)인지. 이런 값은 SqlValues 가 글자로 넘긴다. */
  private static boolean badDate(String text) {
    if (!DATE_LIKE.matcher(text.strip()).matches()) return false;
    try {
      return !(SqlParams.value(text) instanceof java.util.Date);
    } catch (RuntimeException e) {
      return true;
    }
  }

  private Map<String, String> values() {
    Map<String, String> out = new HashMap<>();
    for (Map.Entry<String, JBTextField> e : fields.entrySet()) out.put(e.getKey(), e.getValue().getText());
    return out;
  }

  private static JBLabel header(String text) {
    JBLabel h = new JBLabel(text);
    h.setForeground(UIUtil.getContextHelpForeground());
    return h;
  }

  /** 가운데(값) 칸만 늘린다. */
  private static void addRow(JPanel grid, GridBagConstraints g, JComponent name, JComponent value, JComponent type) {
    JComponent[] cells = {name, value, type};
    for (int k = 0; k < cells.length; k++) {
      g.gridx = k;
      g.weightx = k == 1 ? 1 : 0;
      g.fill = k == 1 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
      grid.add(cells[k], g);
    }
  }

  private static void updateType(JBLabel type, SqlParams.Param p, String text) {
    type.setText(typeLabel(p, text));
  }

  static String typeLabel(SqlParams.Param p, String text) {
    if (p.kind() == SqlParams.Kind.RAW) return EiDbBundle.message("bind.type.raw");
    if (badDate(text)) return EiDbBundle.message("bind.type.bad.date");
    Object v = SqlParams.value(text);
    if (v == null) return "NULL";
    if (v instanceof Long || v instanceof BigDecimal) return EiDbBundle.message("bind.type.number");
    if (v instanceof java.util.Date) return EiDbBundle.message("bind.type.date");
    return EiDbBundle.message("bind.type.string");
  }
}
