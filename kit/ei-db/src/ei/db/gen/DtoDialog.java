package ei.db.gen;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import ei.db.DbConfig;
import ei.db.Notify;
import ei.db.QueryResult;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 조회 결과의 칼럼으로 DTO 를 만든다. 형식은 칼럼 형식과 결과 표본으로 짐작하고 사용자가 고친다. */
public final class DtoDialog extends DialogWrapper {

  private static final Pattern FROM_TABLE = Pattern.compile("\\bFROM\\s+([\\w.$#\"]+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
  private static final Pattern PACKAGE = Pattern.compile("([A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*)?");
  private static final String DEFAULT_CLASS = "ResultDto";
  private static final String DTO_SUFFIX = "Dto";
  private static final int COL_COLUMN = 0;
  private static final int COL_FIELD = 1;
  private static final int COL_TYPE = 2;
  private static final int COL_COMMENT = 3;
  private static final int TABLE_WIDTH = 560;
  private static final int PREVIEW_WIDTH = 520;
  private static final int HEIGHT = 560;

  private final Project project;
  private final QueryResult result;
  private final Targets.Roots roots;

  private final JBTextField classField = new JBTextField();
  private final JBTextField packageField = new JBTextField();
  private final TextFieldWithBrowseButton locationField = new TextFieldWithBrowseButton();
  private final DefaultTableModel model;
  private final JBCheckBox lombok = new JBCheckBox("Lombok @Getter @Setter", true);
  private final JBCheckBox javadoc = new JBCheckBox("칼럼 주석을 Javadoc 으로", true);
  private final JBCheckBox makeResultMap = new JBCheckBox("resultMap 도 만들기", false);
  private final TextFieldWithBrowseButton xmlField = new TextFieldWithBrowseButton();
  private final JBCheckBox camel = new JBCheckBox("밑줄 이름을 낙타 이름으로", true);
  private final JBTabbedPane tabs = new JBTabbedPane();
  private final JBLabel fileLabel = new JBLabel();
  private final JBLabel countLabel = new JBLabel();
  private Preview dtoPreview;
  private Preview mapPreview;

  public static void show(Project project, DbConfig c, QueryResult result) {
    if (result == null || !result.isQuery()) {
      Notify.error(project, "DTO 를 만들 조회 결과가 없습니다.");
      return;
    }
    new Task.Backgroundable(project, "DTO 위치 찾는 중", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        Targets.Roots roots = ReadAction.compute(() -> Targets.find(project));
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!project.isDisposed()) new DtoDialog(project, result, roots).show();
        });
      }
    }.queue();
  }

  private DtoDialog(Project project, QueryResult result, Targets.Roots roots) {
    super(project, true);
    this.project = project;
    this.result = result;
    this.roots = roots;
    String table = sourceTable(result.sql());
    classField.setText(table == null ? DEFAULT_CLASS : Names.pascal(table) + DTO_SUFFIX);
    packageField.setText(roots.basePackage() + ".dto");
    locationField.setText(roots.rel(roots.java() + "/" + packageField.getText().replace('.', '/')));
    if (table != null) {
      String xml = roots.resources() + "/mapper/" + table.toLowerCase(Locale.ROOT) + "/" + Names.pascal(table) + "Mapper.xml";
      if (Targets.exists(xml)) {
        xmlField.setText(roots.rel(xml));
        makeResultMap.setSelected(true);
      }
    }
    model = new DefaultTableModel(new Object[] {"칼럼", "필드", "자바 형식", "주석"}, 0) {
      @Override
      public boolean isCellEditable(int row, int column) { return column == COL_FIELD || column == COL_TYPE; }
    };
    for (int i = 0; i < result.columns().size(); i++) {
      String col = result.columns().get(i);
      String dbType = i < result.columnTypes().size() ? result.columnTypes().get(i) : "";
      String comment = result.comments() == null ? "" : result.comments().getOrDefault(col, "");
      model.addRow(new Object[] {col, fieldName(col), TypeMap.javaType(dbType, TypeMap.anyFraction(result.rows(), i)), comment});
    }
    setTitle("결과 → DTO 만들기 — " + (table == null ? "결과" : table.toLowerCase(Locale.ROOT)) + " · 칼럼 " + result.columns().size() + "개");
    setOKButtonText("만들기");
    setCancelButtonText("취소");
    init();
    refresh();
  }

  /** FROM 뒤 첫 표 이름(스키마 · 따옴표 뗌). 서브쿼리 등으로 못 찾으면 null. */
  static String sourceTable(String sql) {
    Matcher m = FROM_TABLE.matcher(sql == null ? "" : sql);
    if (!m.find()) return null;
    String t = Names.simple(m.group(1).replace("\"", ""));
    return t.isEmpty() ? null : t;
  }

  private String fieldName(String column) {
    return camel.isSelected() ? Names.camel(column) : column.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_$]", "_");
  }

  @Override
  protected JComponent createCenterPanel() {
    dtoPreview = new Preview(project, "java", getDisposable());
    mapPreview = new Preview(project, "xml", getDisposable());
    tabs.addTab("DTO", dtoPreview.component());
    tabs.addTab("resultMap", mapPreview.component());
    tabs.addChangeListener(e -> refresh());

    locationField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("DTO 위치"));
    xmlField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor("xml").withTitle("resultMap 을 덧붙일 매퍼 XML"));
    Ui.onChange(classField, this::refresh);
    Ui.onChange(packageField, () -> {
      locationField.setText(roots.rel(roots.java() + "/" + packageField.getText().strip().replace('.', '/')));
      refresh();
    });
    Ui.onChange(xmlField.getTextField(), this::refresh);
    for (JBCheckBox b : List.of(lombok, javadoc, makeResultMap)) b.addActionListener(e -> refresh());
    camel.addActionListener(e -> {
      for (int r = 0; r < model.getRowCount(); r++) model.setValueAt(fieldName((String) model.getValueAt(r, COL_COLUMN)), r, COL_FIELD);
    });
    model.addTableModelListener(e -> refresh());

    JPanel top = new JPanel(new GridLayout(1, 3, JBUI.scale(16), 0));
    top.add(labeled("클래스 이름", classField));
    top.add(labeled("패키지", packageField));
    top.add(labeled("위치", locationField));

    JBTable table = new JBTable(model);
    table.setShowGrid(false);
    ComboBox<String> typeBox = new ComboBox<>(TypeMap.CHOICES.toArray(new String[0]));
    typeBox.setEditable(true);
    table.getColumnModel().getColumn(COL_TYPE).setCellEditor(new DefaultCellEditor(typeBox));
    JBScrollPane tableScroll = new JBScrollPane(table);

    JPanel resultMapRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    resultMapRow.add(makeResultMap, BorderLayout.WEST);
    resultMapRow.add(xmlField, BorderLayout.CENTER);
    JPanel options = new JPanel();
    options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
    for (JComponent c : List.of(lombok, javadoc, resultMapRow, camel)) {
      c.setAlignmentX(0f);
      options.add(c);
    }

    JPanel left = new JPanel(new BorderLayout(0, JBUI.scale(12)));
    left.add(tableScroll, BorderLayout.CENTER);
    left.add(options, BorderLayout.SOUTH);
    left.setPreferredSize(new Dimension(JBUI.scale(TABLE_WIDTH), JBUI.scale(HEIGHT)));

    JPanel head = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    fileLabel.setFont(Ui.mono());
    fileLabel.setForeground(Ui.muted());
    head.add(fileLabel);
    JPanel right = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    right.add(head, BorderLayout.NORTH);
    right.add(tabs, BorderLayout.CENTER);
    right.setPreferredSize(new Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(HEIGHT)));

    JPanel body = new JPanel(new BorderLayout(JBUI.scale(20), 0));
    body.add(left, BorderLayout.WEST);
    body.add(right, BorderLayout.CENTER);

    JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(12)));
    root.add(top, BorderLayout.NORTH);
    root.add(body, BorderLayout.CENTER);
    return root;
  }

  private static JComponent labeled(String label, JComponent field) {
    return FormBuilder.createFormBuilder().addLabeledComponent(label, field, true).getPanel();
  }

  @Override
  protected JPanel createSouthAdditionalPanel() {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    countLabel.setForeground(Ui.muted());
    p.add(countLabel);
    return p;
  }

  // ---- 생성 ----

  private List<DtoGen.Field> fields() {
    List<DtoGen.Field> out = new ArrayList<>();
    for (int r = 0; r < model.getRowCount(); r++) {
      out.add(new DtoGen.Field(str(r, COL_COLUMN), str(r, COL_FIELD).strip(), str(r, COL_TYPE).strip(), str(r, COL_COMMENT)));
    }
    return out;
  }

  private String str(int row, int col) {
    Object v = model.getValueAt(row, col);
    return v == null ? "" : v.toString();
  }

  private String cls() { return classField.getText().strip(); }

  private String pkg() { return packageField.getText().strip(); }

  private String typeFqn() { return pkg().isEmpty() ? cls() : pkg() + "." + cls(); }

  /** OrderItemDto → orderItemMap. */
  private String resultMapId() {
    String c = cls();
    String base = c.endsWith(DTO_SUFFIX) && c.length() > DTO_SUFFIX.length() ? c.substring(0, c.length() - DTO_SUFFIX.length()) : c;
    return Names.lowerFirst(base) + "Map";
  }

  private String dtoText() {
    return DtoGen.dto(pkg(), cls(), fields(), lombok.isSelected(), javadoc.isSelected());
  }

  private String mapText() {
    return DtoGen.resultMap(resultMapId(), typeFqn(), fields(), Set.of());
  }

  private String dtoPath() {
    return roots.abs(locationField.getText()) + "/" + cls() + ".java";
  }

  private void refresh() {
    if (dtoPreview == null) return;
    dtoPreview.setText(dtoText());
    mapPreview.setText(makeResultMap.isSelected() ? mapText() : "<!-- resultMap 은 만들지 않습니다 -->");
    xmlField.setEnabled(makeResultMap.isSelected());
    String xml = xmlField.getText().strip();
    makeResultMap.setText(makeResultMap.isSelected() && !xml.isEmpty()
                          ? "resultMap 도 만들기 → " + xml.substring(xml.lastIndexOf('/') + 1) + " 끝에 추가"
                          : "resultMap 도 만들기");
    fileLabel.setText(tabs.getSelectedIndex() == 0 ? cls() + ".java" : xml.isEmpty() ? "" : xml.substring(xml.lastIndexOf('/') + 1));
    countLabel.setText("필드 " + model.getRowCount() + "개 · resultMap " + (makeResultMap.isSelected() ? 1 : 0) + "개");
  }

  @Override
  protected ValidationInfo doValidate() {
    if (!IDENT.matcher(cls()).matches()) return new ValidationInfo("클래스 이름이 자바 이름 꼴이 아닙니다.", classField);
    if (!PACKAGE.matcher(pkg()).matches()) return new ValidationInfo("패키지 이름이 자바 이름 꼴이 아닙니다.", packageField);
    if (locationField.getText().isBlank()) return new ValidationInfo("위치를 고르세요.", locationField);
    Set<String> seen = new HashSet<>();
    for (DtoGen.Field f : fields()) {
      if (!IDENT.matcher(f.name()).matches()) return new ValidationInfo(f.column() + " 의 필드 이름이 자바 이름 꼴이 아닙니다.");
      if (!seen.add(f.name())) return new ValidationInfo("필드 이름 " + f.name() + " 이 겹칩니다.");
      if (f.type().isEmpty()) return new ValidationInfo(f.column() + " 의 자바 형식이 비었습니다.");
    }
    if (makeResultMap.isSelected() && !xmlField.getText().strip().endsWith(".xml")) {
      return new ValidationInfo("resultMap 을 덧붙일 매퍼 XML 을 고르세요.", xmlField);
    }
    return null;
  }

  @Override
  protected void doOKAction() {
    String dtoPath = dtoPath();
    if (Targets.exists(dtoPath)) {
      setErrorText(roots.rel(dtoPath) + " 이 이미 있습니다. 덮어쓰지 않으니 이름이나 위치를 바꾸세요.", classField);
      return;
    }
    List<Targets.Job> jobs = new ArrayList<>();
    jobs.add(new Targets.Job(dtoPath, dtoText(), false));
    String note = roots.rel(dtoPath) + " 새로 만듦";
    try {
      if (makeResultMap.isSelected()) {
        String xmlPath = roots.abs(xmlField.getText());
        String xml = Targets.read(xmlPath);
        if (xml == null) {
          setErrorText(roots.rel(xmlPath) + " 이 없습니다.", xmlField);
          return;
        }
        Merge.Result r = Merge.xml(xml, List.of(new MapperGen.Block(resultMapId(), mapText())));
        if (r.text() == null) {
          setErrorText(roots.rel(xmlPath) + " 에서 </mapper> 를 찾지 못했습니다.", xmlField);
          return;
        }
        jobs.add(new Targets.Job(xmlPath, r.text(), !r.added().isEmpty()));
        note += r.added().isEmpty()
                ? "\nresultMap " + resultMapId() + " 은 이미 있어 건너뜀"
                : "\n" + roots.rel(xmlPath) + " 에 resultMap " + resultMapId() + " 덧붙임";
      }
      Targets.write(project, "DTO 만들기", jobs);
      super.doOKAction();
      Targets.open(project, dtoPath);
      Notify.info(project, note);
    } catch (IOException e) {
      setErrorText("파일을 쓰지 못했습니다: " + e.getMessage());
    }
  }
}
