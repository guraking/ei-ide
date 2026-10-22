package ei.db.gen;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import ei.db.EiDbBundle;
import ei.db.Notify;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/** 붙여 넣은 JSON 으로 DTO 파일 하나(중첩 객체는 안쪽 static 클래스)를 만든다. 글은 JsonDto 가 만든다. */
final class JsonDtoDialog extends DialogWrapper {

  /** 열 때의 기본값: 생성 루트, 자바 소스 루트(절대 경로), 패키지. */
  record Start(Targets.Roots roots, String javaRoot, String pkg) {}

  private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
  private static final Pattern PACKAGE = Pattern.compile("([A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*)?");
  private static final String DEFAULT_CLASS = "ResponseDto";
  private static final int INPUT_WIDTH = 520;
  private static final int PREVIEW_WIDTH = 720;
  private static final int HEIGHT = 640;

  private final Project project;
  private final Targets.Roots roots;
  private final String javaRoot;

  private final JBTextField classField = new JBTextField(DEFAULT_CLASS);
  private final JBTextField packageField = new JBTextField();
  private final TextFieldWithBrowseButton locationField = new TextFieldWithBrowseButton();
  private final JBTextArea jsonArea = new JBTextArea();
  private final JBCheckBox lombok = new JBCheckBox("Lombok @Getter @Setter", true);
  private Preview preview;

  JsonDtoDialog(Project project, Start start) {
    super(project, true);
    this.project = project;
    this.roots = start.roots();
    this.javaRoot = start.javaRoot();
    packageField.setText(start.pkg());
    locationField.setText(location());
    setTitle(EiDbBundle.message("jsondto.title"));
    setOKButtonText(EiDbBundle.message("common.generate"));
    setCancelButtonText(EiDbBundle.message("common.cancel"));
    init();
    refresh();
  }

  private String location() {
    String p = pkg();
    return roots.rel(p.isEmpty() ? javaRoot : javaRoot + "/" + p.replace('.', '/'));
  }

  @Override
  protected JComponent createCenterPanel() {
    preview = new Preview(project, "java", getDisposable());
    locationField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(EiDbBundle.message("jsondto.location.title")));
    Ui.onChange(classField, this::refresh);
    Ui.onChange(packageField, () -> {
      locationField.setText(location());
      refresh();
    });
    Ui.onChange(jsonArea, this::refresh);
    lombok.addActionListener(e -> refresh());

    JPanel top = new JPanel(new GridLayout(1, 3, JBUI.scale(16), 0));
    top.add(labeled(EiDbBundle.message("common.class.name"), classField));
    top.add(labeled(EiDbBundle.message("common.package"), packageField));
    top.add(labeled(EiDbBundle.message("common.location"), locationField));

    jsonArea.setFont(Ui.mono());
    JPanel left = new JPanel(new BorderLayout(0, JBUI.scale(8)));
    left.add(new JBLabel(EiDbBundle.message("jsondto.paste.label")), BorderLayout.NORTH);
    left.add(new JBScrollPane(jsonArea), BorderLayout.CENTER);
    left.add(lombok, BorderLayout.SOUTH);
    left.setPreferredSize(new Dimension(JBUI.scale(INPUT_WIDTH), JBUI.scale(HEIGHT)));

    JComponent right = preview.component();
    right.setPreferredSize(new Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(HEIGHT)));

    JPanel body = new JPanel(new BorderLayout(JBUI.scale(20), 0));
    body.add(left, BorderLayout.WEST);
    body.add(right, BorderLayout.CENTER);

    JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(12)));
    root.add(top, BorderLayout.NORTH);
    root.add(body, BorderLayout.CENTER);
    return root;
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return jsonArea;
  }

  private static JComponent labeled(String label, JComponent field) {
    return FormBuilder.createFormBuilder().addLabeledComponent(label, field, true).getPanel();
  }

  private String cls() { return classField.getText().strip(); }

  private String pkg() { return packageField.getText().strip(); }

  private String dtoText() throws JsonDto.JsonException {
    return JsonDto.dto(jsonArea.getText(), pkg(), cls(), lombok.isSelected());
  }

  private void refresh() {
    if (preview == null) return;
    if (jsonArea.getText().isBlank()) {
      preview.setText(EiDbBundle.message("jsondto.preview.empty"));
      return;
    }
    try {
      preview.setText(dtoText());
    } catch (JsonDto.JsonException e) {
      preview.setText(EiDbBundle.message("jsondto.preview.error", e.getMessage()));
    }
  }

  @Override
  protected ValidationInfo doValidate() {
    if (!IDENT.matcher(cls()).matches()) return new ValidationInfo(EiDbBundle.message("common.class.name.invalid"), classField);
    if (!PACKAGE.matcher(pkg()).matches()) return new ValidationInfo(EiDbBundle.message("common.package.invalid"), packageField);
    if (locationField.getText().isBlank()) return new ValidationInfo(EiDbBundle.message("common.location.empty"), locationField);
    if (jsonArea.getText().isBlank()) return new ValidationInfo(EiDbBundle.message("jsondto.json.empty"), jsonArea);
    try {
      dtoText();
    } catch (JsonDto.JsonException e) {
      return new ValidationInfo(EiDbBundle.message("jsondto.error", e.getMessage()), jsonArea);
    }
    return null;
  }

  @Override
  protected void doOKAction() {
    String path = roots.abs(locationField.getText()) + "/" + cls() + ".java";
    String text;
    try {
      text = dtoText();
    } catch (JsonDto.JsonException e) {
      setErrorText(EiDbBundle.message("jsondto.error", e.getMessage()), jsonArea);
      return;
    }
    boolean exists = Targets.exists(path);
    if (exists && Messages.showYesNoDialog(project, EiDbBundle.message("common.overwrite.confirm", roots.rel(path)), EiDbBundle.message("jsondto.title"),
                                           EiDbBundle.message("common.overwrite"), EiDbBundle.message("common.cancel"), Messages.getWarningIcon()) != Messages.YES) {
      return;
    }
    try {
      Targets.write(project, EiDbBundle.message("jsondto.title"), List.of(new Targets.Job(path, text, true)));
    } catch (IOException e) {
      setErrorText(EiDbBundle.message("common.write.failed", e.getMessage()));
      return;
    }
    super.doOKAction();
    Targets.open(project, path);
    Notify.info(project, EiDbBundle.message(exists ? "common.file.overwritten" : "common.file.created", roots.rel(path)));
  }
}
