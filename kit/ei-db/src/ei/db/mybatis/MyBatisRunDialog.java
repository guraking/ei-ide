package ei.db.mybatis;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.PsiClass;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.SqlRunner;
import ei.db.console.ConsoleOpener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 매퍼 문장 하나를 매개변수 값과 함께 실행하는 창.
 * 값이 비었거나 「null」 을 고르면 null 이라 그 값에 걸린 &lt;if&gt; 가 빠진다. 미리보기는 값이 바뀔 때마다 다시 푼다.
 * 접속은 프로젝트마다, 값은 문장 · 매개변수마다 마지막 것을 기억한다.
 */
public final class MyBatisRunDialog extends DialogWrapper {
  /** 마지막에 고른 접속 id(프로젝트 단위). 로그 실행도 같은 값을 기본으로 쓴다. */
  public static final String CONNECTION_KEY = "ei.db.mybatis.connection";
  private static final String VALUE_KEY = "ei.db.mybatis.value.";
  private static final int PREVIEW_ROWS = 12;
  private static final int PREVIEW_COLUMNS = 80;
  private static final JBColor WARNING = new JBColor(new Color(0xC27D0E), new Color(0xE0A64F));
  private static final JBColor WARNING_BACKGROUND = new JBColor(new Color(0xFFF6E5), new Color(0x3A3121));

  private final Project project;
  private final MapperDoc doc;
  private final MapperDoc.Node statement;
  private final Set<String> listNames;
  private final Map<String, Row> rows = new LinkedHashMap<>();
  private final ComboBox<DbConfig> connection;
  private final JBTextArea preview = new JBTextArea(PREVIEW_ROWS, PREVIEW_COLUMNS);
  private final JBLabel summary = new JBLabel();
  private final JPanel warning = new JPanel(new BorderLayout());
  private final PsiClass dto;
  private final List<String> dtoFields;
  private final Set<String> textParams;
  private DynamicSql.Result result;

  private static final class Row {
    final JBTextField value = new JBTextField();
    final JBCheckBox isNull = new JBCheckBox("null");
    final JBLabel type = new JBLabel();
  }

  /** 문서 글자 text 에서 offset 을 감싸는 문장을 찾아 창을 연다. 화면 스레드에서 부른다. */
  public static void openAt(Project project, CharSequence text, int offset) {
    MapperDoc doc = MapperDoc.parse(text);
    MapperDoc.Node s = doc.statementAt(offset);
    if (s == null) {
      Notify.error(project, EiDbBundle.message("mybatisrun.no.statement"));
      return;
    }
    new Task.Backgroundable(project, EiDbBundle.message("mybatisrun.progress"), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        DtoFields.ForRun types = ReadAction.nonBlocking(() -> DtoFields.forRun(project, doc, s)).wrapProgress(indicator).executeSynchronously();
        ApplicationManager.getApplication().invokeLater(() -> new MyBatisRunDialog(project, doc, s, null, types).show(), project.getDisposed());
      }
    }.queue();
  }

  /**
   * prefill 이 null 이면 기억한 값, 아니면 그 값(없는 이름은 빈 값)으로 채운다.
   * types 는 색인 · PSI 를 보는 일이라 화면 스레드 밖에서 DtoFields.forRun 으로 구해 넘긴다.
   */
  public MyBatisRunDialog(Project project, MapperDoc doc, MapperDoc.Node statement, @Nullable Map<String, String> prefill,
                          DtoFields.ForRun types) {
    super(project);
    this.project = project;
    this.doc = doc;
    this.statement = statement;
    this.listNames = DynamicSql.listNames(doc, statement);
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    for (String name : DynamicSql.paramNames(doc, statement)) {
      Row row = new Row();
      String text = prefill != null ? prefill.getOrDefault(name, "") : props.getValue(valueKey(name), "");
      row.value.setText(text);
      row.isNull.setSelected(text.isBlank());
      rows.put(name, row);
    }
    connection = new ComboBox<>(DbSettings.getInstance().connections().toArray(new DbConfig[0]));
    connection.setRenderer(SimpleListCellRenderer.create("", MyBatisRunDialog::connectionLabel));
    DbConfig last = DbSettings.getInstance().find(props.getValue(CONNECTION_KEY, ""));
    if (last != null) connection.setSelectedItem(last);
    dto = types.result();
    dtoFields = types.resultFields();
    textParams = types.textParams();
    setTitle(EiDbBundle.message("action.EiDb.MyBatis.Run.text"));
    setOKButtonText(EiDbBundle.message("common.run"));
    getOKAction().putValue(Action.SMALL_ICON, AllIcons.Actions.Execute);
    setCancelButtonText(EiDbBundle.message("common.cancel"));
    init();
    refresh();
  }

  static String connectionLabel(DbConfig c) {
    if (c == null) return "";
    String schema = c.effectiveSchema();
    return c.name + (schema.isEmpty() ? "" : " · " + schema) + (c.readOnly ? EiDbBundle.message("common.read.only.suffix") : "");
  }

  private String valueKey(String name) {
    return VALUE_KEY + doc.namespace + "." + statement.attr("id") + "." + name;
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBLabel id = new JBLabel(doc.shortNamespace() + "." + statement.attr("id"));
    id.setFont(monospace());
    id.setForeground(UIUtil.getContextHelpForeground());
    JBLabel note = new JBLabel(EiDbBundle.message("mybatisrun.note"));
    note.setForeground(UIUtil.getContextHelpForeground());
    return FormBuilder.createFormBuilder()
        .addComponent(id)
        .addLabeledComponent(EiDbBundle.message("common.connection"), connection, JBUI.scale(8))
        .addComponent(parameters(), JBUI.scale(10))
        .addComponent(note)
        .addComponent(previewBox(), JBUI.scale(8))
        .addComponent(warning, JBUI.scale(8))
        .getPanel();
  }

  private JComponent parameters() {
    JPanel p = new JPanel(new GridBagLayout());
    if (rows.isEmpty()) {
      p.add(new JBLabel(EiDbBundle.message("mybatisrun.no.params")));
      return p;
    }
    GridBagConstraints g = new GridBagConstraints();
    g.insets = JBUI.insets(2, 0, 2, 10);
    g.anchor = GridBagConstraints.WEST;
    g.gridy = 0;
    String[] heads = {EiDbBundle.message("mybatisrun.col.param"), EiDbBundle.message("common.col.type"), EiDbBundle.message("bind.col.value"), ""};
    for (int k = 0; k < heads.length; k++) {
      JBLabel h = new JBLabel(heads[k]);
      h.setForeground(UIUtil.getContextHelpForeground());
      g.gridx = k;
      g.weightx = k == 2 ? 1 : 0;
      g.fill = k == 2 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
      p.add(h, g);
    }
    for (Map.Entry<String, Row> e : rows.entrySet()) {
      Row row = e.getValue();
      g.gridy++;
      JBLabel name = new JBLabel(e.getKey());
      name.setFont(monospace());
      row.value.getEmptyText().setText(EiDbBundle.message("mybatisrun.empty.hint"));
      row.value.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent ev) {
          row.isNull.setSelected(row.value.getText().isBlank());
          refresh();
        }
      });
      row.isNull.addActionListener(ev -> refresh());
      JComponent[] cells = {name, row.type, row.value, row.isNull};
      for (int k = 0; k < cells.length; k++) {
        g.gridx = k;
        g.weightx = k == 2 ? 1 : 0;
        g.fill = k == 2 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        p.add(cells[k], g);
      }
    }
    return p;
  }

  private JComponent previewBox() {
    JBLabel title = new JBLabel(EiDbBundle.message("mybatisrun.sql.label"));
    title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
    summary.setForeground(UIUtil.getContextHelpForeground());
    ActionLink copy = new ActionLink(EiDbBundle.message("common.copy"), e -> {
      CopyPasteManager.getInstance().setContents(new StringSelection(preview.getText()));
    });
    JPanel head = new JPanel();
    head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
    head.setBorder(JBUI.Borders.empty(6, 10));
    head.setOpaque(false);
    head.add(title);
    head.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)));
    head.add(summary);
    head.add(javax.swing.Box.createHorizontalGlue());
    head.add(copy);
    preview.setEditable(false);
    preview.setFont(monospace());
    preview.setBorder(JBUI.Borders.empty(6, 10));
    JPanel box = new JPanel(new BorderLayout());
    box.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
    box.add(head, BorderLayout.NORTH);
    JBScrollPane scroll = new JBScrollPane(preview);
    scroll.setBorder(JBUI.Borders.customLineTop(JBColor.border()));
    box.add(scroll, BorderLayout.CENTER);
    return box;
  }

  private static java.awt.Font monospace() {
    return EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
  }

  private Map<String, Object> values() {
    Map<String, Object> out = new HashMap<>();
    for (Map.Entry<String, Row> e : rows.entrySet()) {
      Row row = e.getValue();
      String name = e.getKey();
      out.put(name, row.isNull.isSelected() ? null : SqlValues.parse(row.value.getText(), listNames.contains(name), textParams.contains(name)));
    }
    return out;
  }

  private void refresh() {
    Map<String, Object> values = values();
    for (Map.Entry<String, Row> e : rows.entrySet()) {
      e.getValue().type.setText(listNames.contains(e.getKey()) ? "List · foreach" : SqlValues.typeLabel(values.get(e.getKey())));
    }
    result = DynamicSql.resolve(doc, statement, values);
    preview.setText(result.inlined());
    preview.setCaretPosition(0);
    summary.setText(result.summary());
    showWarnings();
  }

  /** resultType 클래스를 찾았고 SELECT 칼럼을 알 수 있을 때만 맞지 않는 곳을 보인다. */
  private void showWarnings() {
    warning.removeAll();
    List<String> columns = dto == null ? null : ResultColumns.of(result.sql);
    List<ResultColumns.Issue> issues = columns == null ? List.of() : ResultColumns.compare(columns, dtoFields);
    warning.setVisible(!issues.isEmpty());
    if (issues.isEmpty()) return;
    JBLabel title = new JBLabel(EiDbBundle.message("mybatisrun.mismatch", dto.getName(), issues.size()), AllIcons.General.Warning, JBLabel.LEFT);
    title.setForeground(WARNING);
    title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
    ActionLink open = new ActionLink(EiDbBundle.message("mybatisrun.open.dto"), e -> {
      close(CANCEL_EXIT_CODE);
      dto.navigate(true);
    });
    JPanel head = new JPanel(new BorderLayout());
    head.setOpaque(false);
    head.add(title, BorderLayout.CENTER);
    head.add(open, BorderLayout.EAST);
    JPanel lines = new JPanel();
    lines.setOpaque(false);
    lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
    lines.setBorder(JBUI.Borders.emptyLeft(22));
    for (ResultColumns.Issue i : issues) lines.add(new JBLabel(issueText(i)));
    warning.add(head, BorderLayout.NORTH);
    warning.add(lines, BorderLayout.CENTER);
    warning.setBackground(WARNING_BACKGROUND);
    warning.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(WARNING, 1), JBUI.Borders.empty(8, 12)));
    warning.revalidate();
    warning.repaint();
  }

  private static String issueText(ResultColumns.Issue i) {
    if (!i.columnWithoutField) return EiDbBundle.message("mybatisrun.issue.field", i.field);
    String hint = i.similar == null ? "" : EiDbBundle.message("mybatisrun.issue.similar", i.similar);
    return EiDbBundle.message("mybatisrun.issue.column", i.column, i.field, hint);
  }

  @Override
  protected JPanel createSouthAdditionalPanel() {
    JPanel p = new JPanel(new BorderLayout());
    JBLabel hint = new JBLabel(EiDbBundle.message("mybatisrun.hint"));
    hint.setForeground(UIUtil.getContextHelpForeground());
    p.add(hint, BorderLayout.CENTER);
    return p;
  }

  @Override
  protected Action @NotNull [] createActions() {
    Action toConsole = new AbstractAction(EiDbBundle.message("querypick.send")) {
      @Override
      public void actionPerformed(ActionEvent e) {
        ValidationInfo invalid = doValidate();
        if (invalid != null) {
          setErrorText(invalid.message, invalid.component);
          return;
        }
        DbConfig c = selected();
        String sql = SqlValues.inline(result.sql, result.binds, c.kind);
        close(NEXT_USER_EXIT_CODE);
        ConsoleOpener.open(project, c, sql);
      }
    };
    return new Action[] {getCancelAction(), toConsole, getOKAction()};
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    return selected() == null ? new ValidationInfo(EiDbBundle.message("common.no.connection"), connection) : null;
  }

  private DbConfig selected() { return (DbConfig) connection.getSelectedItem(); }

  @Override
  protected void doOKAction() {
    DbConfig c = selected();
    DynamicSql.Result r = result;
    super.doOKAction();
    SqlRunner.run(project, c, r.sql, new ArrayList<>(r.binds));
  }

  /** 어떤 단추로 닫든 넣은 값과 고른 접속을 기억한다. */
  @Override
  protected void dispose() {
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    for (Map.Entry<String, Row> e : rows.entrySet()) {
      Row row = e.getValue();
      props.setValue(valueKey(e.getKey()), row.isNull.isSelected() ? "" : row.value.getText(), "");
    }
    if (selected() != null) props.setValue(CONNECTION_KEY, selected().id);
    super.dispose();
  }
}
