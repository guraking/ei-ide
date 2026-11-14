package ei.db.mybatis;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import ei.db.EiDbBundle;
import ei.db.gen.Ui;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/**
 * 「Swagger 주석 만들기」 확인 창: 미리 채운 글을 줄마다 고친다. 글 칸은 창 폭을 다 채우고, 줄이 많으면 세로로 굴린다.
 * required 줄이 비면 넣기를 막는다.
 */
final class SwaggerDocDialog extends DialogWrapper {
  /** 한 줄. label 은 왼쪽 글, value 는 미리 채울 글, required 면 비울 수 없다. */
  record Row(String label, String value, boolean required) {}

  /** 열 때 화면 폭에 대한 창 폭 비율. 높이는 줄 수대로라(매개변수 수에 따라 다름) 크기를 기억하지 않는다. */
  private static final double FIRST_WIDTH_RATIO = 0.45;
  /** 줄이 많을 때 창 높이가 화면 높이에서 차지할 수 있는 최대 비율. 넘으면 굴린다. */
  private static final double MAX_HEIGHT_RATIO = 0.8;

  private final List<Row> rows;
  private final List<JBTextField> fields = new ArrayList<>();

  SwaggerDocDialog(Project project, String title, List<Row> rows) {
    super(project);
    this.rows = rows;
    for (Row r : rows) fields.add(new JBTextField(r.value()));
    setTitle(title);
    setOKButtonText(EiDbBundle.message("common.insert"));
    init();
  }

  /** 줄 차례대로 고친 글(앞뒤 빈칸 뺌). */
  List<String> values() {
    List<String> out = new ArrayList<>();
    for (JBTextField f : fields) out.add(f.getText().trim());
    return out;
  }

  @Override
  public Dimension getInitialSize() {
    Dimension max = Ui.firstSize(FIRST_WIDTH_RATIO, MAX_HEIGHT_RATIO);
    Dimension pref = getPreferredSize();
    return new Dimension(Math.max(max.width, pref.width), Math.min(max.height, pref.height));
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() { return fields.isEmpty() ? null : fields.get(0); }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    FormBuilder form = FormBuilder.createFormBuilder();
    for (int i = 0; i < rows.size(); i++) form.addLabeledComponent(rows.get(i).label(), fields.get(i));
    JPanel panel = form.addComponentFillVertically(new JPanel(), 0).getPanel();
    return ScrollPaneFactory.createScrollPane(panel, true);
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).required() && fields.get(i).getText().isBlank()) {
        return new ValidationInfo(EiDbBundle.message("swaggerdoc.required"), fields.get(i));
      }
    }
    return null;
  }
}
