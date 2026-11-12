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
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.QueryResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/** 조회 결과의 두 칼럼(코드 · 이름)으로 enum 을 만든다. 원래 SQL 과 접속 이름을 Javadoc 에 남겨 「enum 다시 맞추기」가 쓴다. */
public final class EnumDialog extends DialogWrapper {

  private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
  private static final Pattern PACKAGE = Pattern.compile("([A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*)?");
  private static final String DEFAULT_CLASS = "CodeType";
  private static final String CODE_PACKAGE = ".code";
  private static final int MIN_COLUMNS = 2;
  /** 대화창 첫 폭과 왼쪽 칸 비율(칸 폭은 분할선으로 바꾼다). */
  /** 왼쪽 칸 처음 비율(끌어 바꾼 비율은 기억한다). */
  private static final float SPLIT = 0.36f;
  /** 처음 열 때 화면 폭 · 높이에 대한 창 크기 비율. 사용자가 바꾼 크기는 IntelliJ 가 기억한다. */
  private static final double FIRST_WIDTH_RATIO = 0.61;
  private static final double FIRST_HEIGHT_RATIO = 0.6;
  private static final String DIMENSION_KEY = "ei.db.gen.EnumDialog";

  private final Project project;
  private final DbConfig config;
  private final QueryResult result;
  private final Targets.Roots roots;

  private final ComboBox<String> codeBox;
  private final ComboBox<String> labelBox;
  private final JBTextField classField = new JBTextField();
  private final JBTextField packageField = new JBTextField();
  private final TextFieldWithBrowseButton locationField = new TextFieldWithBrowseButton();
  private final JBLabel countLabel = new JBLabel();
  private Preview preview;

  public static void show(Project project, DbConfig c, QueryResult result) {
    if (c == null) {
      Notify.error(project, EiDbBundle.message("enum.no.connection"));
      return;
    }
    if (result == null || !result.isQuery() || result.sql() == null || result.sql().isBlank()) {
      Notify.error(project, EiDbBundle.message("enum.no.result"));
      return;
    }
    if (result.columns().size() < MIN_COLUMNS) {
      Notify.warning(project, EiDbBundle.message("enum.need.two.columns"));
      return;
    }
    if (result.rows().isEmpty()) {
      Notify.warning(project, EiDbBundle.message("enum.no.rows"));
      return;
    }
    new Task.Backgroundable(project, EiDbBundle.message("enum.progress.location"), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        Targets.Roots roots = ReadAction.compute(() -> Targets.find(project));
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!project.isDisposed()) new EnumDialog(project, c, result, roots).show();
        });
      }
    }.queue();
  }

  private EnumDialog(Project project, DbConfig c, QueryResult result, Targets.Roots roots) {
    super(project, true);
    this.project = project;
    this.config = c;
    this.result = result;
    this.roots = roots;
    String[] cols = result.columns().toArray(new String[0]);
    codeBox = new ComboBox<>(cols);
    labelBox = new ComboBox<>(cols);
    codeBox.setSelectedIndex(0);
    labelBox.setSelectedIndex(1);
    String table = DtoDialog.sourceTable(result.sql());
    classField.setText(table == null ? DEFAULT_CLASS : Names.pascal(table));
    packageField.setText(roots.basePackage() + CODE_PACKAGE);
    locationField.setText(roots.rel(roots.java() + "/" + packageField.getText().replace('.', '/')));
    setTitle(EiDbBundle.message("enum.title", c.name, result.rows().size()));
    setOKButtonText(EiDbBundle.message("common.generate"));
    setCancelButtonText(EiDbBundle.message("common.cancel"));
    init();
    refresh();
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() { return Ui.firstSize(FIRST_WIDTH_RATIO, FIRST_HEIGHT_RATIO); }

  @Override
  protected JComponent createCenterPanel() {
    preview = new Preview(project, "java", getDisposable());
    locationField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(EiDbBundle.message("enum.location.title")));
    Ui.onChange(classField, this::refresh);
    Ui.onChange(packageField, () -> {
      locationField.setText(roots.rel(roots.java() + "/" + packageField.getText().strip().replace('.', '/')));
      refresh();
    });
    codeBox.addActionListener(e -> refresh());
    labelBox.addActionListener(e -> refresh());

    JPanel form = FormBuilder.createFormBuilder()
        .addLabeledComponent(EiDbBundle.message("enum.code.column"), codeBox, true)
        .addLabeledComponent(EiDbBundle.message("enum.label.column"), labelBox, true)
        .addLabeledComponent(EiDbBundle.message("common.class.name"), classField, true)
        .addLabeledComponent(EiDbBundle.message("common.package"), packageField, true)
        .addLabeledComponent(EiDbBundle.message("common.location"), locationField, true)
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
    form.setBorder(JBUI.Borders.emptyRight(10));
    JComponent right = preview.component();
    right.setBorder(JBUI.Borders.merge(right.getBorder(), JBUI.Borders.emptyLeft(10), false));
    return Ui.split("enum", SPLIT, form, right);
  }

  @Override
  protected JPanel createSouthAdditionalPanel() {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    countLabel.setForeground(Ui.muted());
    p.add(countLabel);
    return p;
  }

  private String cls() { return classField.getText().strip(); }

  private String pkg() { return packageField.getText().strip(); }

  private List<EnumGen.Item> items() {
    return EnumGen.items(result.rows(), codeBox.getSelectedIndex(), labelBox.getSelectedIndex());
  }

  private String text() {
    String code = result.columns().get(codeBox.getSelectedIndex());
    String label = result.columns().get(labelBox.getSelectedIndex());
    return EnumGen.text(pkg(), cls(), items(), new EnumGen.Source(result.sql(), config.name, List.of(code, label)));
  }

  private String path() {
    return roots.abs(locationField.getText()) + "/" + cls() + ".java";
  }

  private void refresh() {
    if (preview == null) return;
    preview.setText(text());
    int skipped = result.rows().size() - items().size();
    countLabel.setText(EiDbBundle.message("enum.count", items().size())
                       + (skipped > 0 ? EiDbBundle.message("enum.count.skipped", skipped) : "")
                       + (result.truncated() ? EiDbBundle.message("enum.count.truncated") : ""));
  }

  @Override
  protected ValidationInfo doValidate() {
    if (!IDENT.matcher(cls()).matches()) return new ValidationInfo(EiDbBundle.message("common.class.name.invalid"), classField);
    if (!PACKAGE.matcher(pkg()).matches()) return new ValidationInfo(EiDbBundle.message("common.package.invalid"), packageField);
    if (locationField.getText().isBlank()) return new ValidationInfo(EiDbBundle.message("common.location.empty"), locationField);
    if (codeBox.getSelectedIndex() < 0) return new ValidationInfo(EiDbBundle.message("enum.code.column.empty"), codeBox);
    if (labelBox.getSelectedIndex() < 0) return new ValidationInfo(EiDbBundle.message("enum.label.column.empty"), labelBox);
    if (items().isEmpty()) return new ValidationInfo(EiDbBundle.message("enum.codes.empty"), codeBox);
    return null;
  }

  @Override
  protected void doOKAction() {
    String path = path();
    if (Targets.exists(path)) {
      setErrorText(EiDbBundle.message("common.exists.no.overwrite", roots.rel(path)), classField);
      return;
    }
    try {
      Targets.write(project, EiDbBundle.message("enum.command"), List.of(new Targets.Job(path, text(), false)));
    } catch (IOException e) {
      setErrorText(EiDbBundle.message("common.write.failed", e.getMessage()));
      return;
    }
    super.doOKAction();
    Targets.open(project, path);
    Notify.info(project, EiDbBundle.message("enum.created", roots.rel(path), items().size()));
  }
}
