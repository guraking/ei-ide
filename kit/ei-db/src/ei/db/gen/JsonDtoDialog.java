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
  private static final String COMMAND = "JSON 으로 DTO 만들기";
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
    setTitle(COMMAND);
    setOKButtonText("만들기");
    setCancelButtonText("취소");
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
    locationField.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("DTO 위치"));
    Ui.onChange(classField, this::refresh);
    Ui.onChange(packageField, () -> {
      locationField.setText(location());
      refresh();
    });
    Ui.onChange(jsonArea, this::refresh);
    lombok.addActionListener(e -> refresh());

    JPanel top = new JPanel(new GridLayout(1, 3, JBUI.scale(16), 0));
    top.add(labeled("클래스 이름", classField));
    top.add(labeled("패키지", packageField));
    top.add(labeled("위치", locationField));

    jsonArea.setFont(Ui.mono());
    JPanel left = new JPanel(new BorderLayout(0, JBUI.scale(8)));
    left.add(new JBLabel("JSON 붙여 넣기 (최상위가 배열이면 첫 원소 기준)"), BorderLayout.NORTH);
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
      preview.setText("// 왼쪽에 JSON 을 붙여 넣으세요.");
      return;
    }
    try {
      preview.setText(dtoText());
    } catch (JsonDto.JsonException e) {
      preview.setText("// JSON 오류 — " + e.getMessage());
    }
  }

  @Override
  protected ValidationInfo doValidate() {
    if (!IDENT.matcher(cls()).matches()) return new ValidationInfo("클래스 이름이 자바 이름 꼴이 아닙니다.", classField);
    if (!PACKAGE.matcher(pkg()).matches()) return new ValidationInfo("패키지 이름이 자바 이름 꼴이 아닙니다.", packageField);
    if (locationField.getText().isBlank()) return new ValidationInfo("위치를 고르세요.", locationField);
    if (jsonArea.getText().isBlank()) return new ValidationInfo("JSON 을 붙여 넣으세요.", jsonArea);
    try {
      dtoText();
    } catch (JsonDto.JsonException e) {
      return new ValidationInfo("JSON 오류 — " + e.getMessage(), jsonArea);
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
      setErrorText("JSON 오류 — " + e.getMessage(), jsonArea);
      return;
    }
    boolean exists = Targets.exists(path);
    if (exists && Messages.showYesNoDialog(project, roots.rel(path) + " 이 이미 있습니다. 덮어쓸까요?", COMMAND,
                                           "덮어쓰기", "취소", Messages.getWarningIcon()) != Messages.YES) {
      return;
    }
    try {
      Targets.write(project, COMMAND, List.of(new Targets.Job(path, text, true)));
    } catch (IOException e) {
      setErrorText("파일을 쓰지 못했습니다: " + e.getMessage());
      return;
    }
    super.doOKAction();
    Targets.open(project, path);
    Notify.info(project, roots.rel(path) + (exists ? " 덮어씀" : " 새로 만듦"));
  }
}
