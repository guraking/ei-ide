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
import ei.db.Notify;
import ei.db.QueryResult;
import org.jetbrains.annotations.NotNull;

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
  private static final int FORM_WIDTH = 420;
  private static final int PREVIEW_WIDTH = 760;
  private static final int HEIGHT = 640;

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
      Notify.error(project, "결과의 접속을 알 수 없어 enum 을 만들 수 없습니다.");
      return;
    }
    if (result == null || !result.isQuery() || result.sql() == null || result.sql().isBlank()) {
      Notify.error(project, "enum 을 만들 조회 결과가 없습니다.");
      return;
    }
    if (result.columns().size() < MIN_COLUMNS) {
      Notify.warning(project, "enum 을 만들려면 칼럼이 두 개(코드 · 이름) 이상이어야 합니다.");
      return;
    }
    if (result.rows().isEmpty()) {
      Notify.warning(project, "결과에 행이 없어 enum 을 만들 수 없습니다.");
      return;
    }
    new Task.Backgroundable(project, "enum 위치 찾는 중", true) {
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
    setTitle("결과 → enum 만들기 — " + c.name + " · 행 " + result.rows().size() + "개");
    setOKButtonText("만들기");
    setCancelButtonText("취소");
    init();
    refresh();
  }

  @Override
  protected JComponent createCenterPanel() {
    preview = new Preview(project, "java", getDisposable());
    locationField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("enum 위치"));
    Ui.onChange(classField, this::refresh);
    Ui.onChange(packageField, () -> {
      locationField.setText(roots.rel(roots.java() + "/" + packageField.getText().strip().replace('.', '/')));
      refresh();
    });
    codeBox.addActionListener(e -> refresh());
    labelBox.addActionListener(e -> refresh());

    JPanel form = FormBuilder.createFormBuilder()
        .addLabeledComponent("코드 칼럼", codeBox, true)
        .addLabeledComponent("이름 칼럼", labelBox, true)
        .addLabeledComponent("클래스 이름", classField, true)
        .addLabeledComponent("패키지", packageField, true)
        .addLabeledComponent("위치", locationField, true)
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
    form.setPreferredSize(new Dimension(JBUI.scale(FORM_WIDTH), JBUI.scale(HEIGHT)));
    JComponent right = preview.component();
    right.setPreferredSize(new Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(HEIGHT)));

    JPanel root = new JPanel(new BorderLayout(JBUI.scale(20), 0));
    root.add(form, BorderLayout.WEST);
    root.add(right, BorderLayout.CENTER);
    return root;
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
    countLabel.setText("상수 " + items().size() + "개"
                       + (skipped > 0 ? " · 코드가 빈 행 " + skipped + "개 건너뜀" : "")
                       + (result.truncated() ? " · 결과가 잘려 있어 일부 행만 들어갑니다(다시 맞추기는 끝까지 읽습니다)" : ""));
  }

  @Override
  protected ValidationInfo doValidate() {
    if (!IDENT.matcher(cls()).matches()) return new ValidationInfo("클래스 이름이 자바 이름 꼴이 아닙니다.", classField);
    if (!PACKAGE.matcher(pkg()).matches()) return new ValidationInfo("패키지 이름이 자바 이름 꼴이 아닙니다.", packageField);
    if (locationField.getText().isBlank()) return new ValidationInfo("위치를 고르세요.", locationField);
    if (codeBox.getSelectedIndex() < 0) return new ValidationInfo("코드 칼럼을 고르세요.", codeBox);
    if (labelBox.getSelectedIndex() < 0) return new ValidationInfo("이름 칼럼을 고르세요.", labelBox);
    if (items().isEmpty()) return new ValidationInfo("코드 칼럼 값이 모두 비어 있습니다.", codeBox);
    return null;
  }

  @Override
  protected void doOKAction() {
    String path = path();
    if (Targets.exists(path)) {
      setErrorText(roots.rel(path) + " 이 이미 있습니다. 덮어쓰지 않으니 이름이나 위치를 바꾸세요.", classField);
      return;
    }
    try {
      Targets.write(project, "enum 만들기", List.of(new Targets.Job(path, text(), false)));
    } catch (IOException e) {
      setErrorText("파일을 쓰지 못했습니다: " + e.getMessage());
      return;
    }
    super.doOKAction();
    Targets.open(project, path);
    Notify.info(project, roots.rel(path) + " 새로 만듦 · 상수 " + items().size() + "개");
  }
}
