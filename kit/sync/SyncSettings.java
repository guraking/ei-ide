package kinggura.sync;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.FlowLayout;

/**
 * 설정 → 버전 관리 → 동기화 창. 이클립스 Synchronize 의 Schedule 처럼 원격을 몇 분마다 저절로 확인할지(0 = 끔, 기본).
 * Merge Request 만들기(MergeRequest)의 받는 쪽 브랜치(기본 develop).
 * 값은 IDE 전체에 하나(PropertiesComponent)다.
 */
public final class SyncSettings implements Configurable {
  private static final String AUTO_MINUTES_KEY = "kinggura.sync.autoMinutes";
  // 하루. 그보다 길게 둘 일은 없다.
  private static final int MAX_MINUTES = 1440;
  private static final String MR_TARGET_KEY = "kinggura.sync.mrTarget";
  private static final String MR_TARGET_DEFAULT = "develop";
  private JBIntSpinner minutes;
  private JBTextField mrTarget;

  /** 원격 자동 확인 간격(분). 0 이면 끔. */
  static int autoMinutes() {
    return PropertiesComponent.getInstance().getInt(AUTO_MINUTES_KEY, 0);
  }

  /** Merge Request 받는 쪽 브랜치. 비워 두면 기본값. */
  static String mrTarget() {
    String value = PropertiesComponent.getInstance().getValue(MR_TARGET_KEY, "").trim();
    return value.isEmpty() ? MR_TARGET_DEFAULT : value;
  }

  @Override
  public @Nls String getDisplayName() {
    return SyncBundle.message("syncsettings.title");
  }

  @Override
  public @Nullable JComponent createComponent() {
    minutes = new JBIntSpinner(autoMinutes(), 0, MAX_MINUTES);
    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
    row.add(minutes);
    row.add(new JLabel(SyncBundle.message("syncsettings.minutes")));
    JComponent hint = ComponentPanelBuilder.createCommentComponent(SyncBundle.message("syncsettings.hint"), true);
    mrTarget = new JBTextField(mrTarget());
    JComponent mrHint = ComponentPanelBuilder.createCommentComponent(SyncBundle.message("syncsettings.mr.hint", MR_TARGET_DEFAULT), true);
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(SyncBundle.message("syncsettings.auto"), row)
      .addComponentToRightColumn(hint)
      .addLabeledComponent(SyncBundle.message("syncsettings.mr"), mrTarget)
      .addComponentToRightColumn(mrHint)
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
  }

  @Override
  public boolean isModified() {
    return minutes != null && (minutes.getNumber() != autoMinutes() || !mrTargetText().equals(mrTarget()));
  }

  @Override
  public void apply() {
    PropertiesComponent.getInstance().setValue(AUTO_MINUTES_KEY, minutes.getNumber(), 0);
    PropertiesComponent.getInstance().setValue(MR_TARGET_KEY, mrTargetText(), MR_TARGET_DEFAULT);
    mrTarget.setText(mrTarget());
  }

  @Override
  public void reset() {
    if (minutes != null) minutes.setNumber(autoMinutes());
    if (mrTarget != null) mrTarget.setText(mrTarget());
  }

  @Override
  public void disposeUIResources() {
    minutes = null;
    mrTarget = null;
  }

  /** 칸에 쓴 받는 쪽 브랜치. 비우면 기본값. */
  private String mrTargetText() {
    String text = mrTarget.getText().trim();
    return text.isEmpty() ? MR_TARGET_DEFAULT : text;
  }
}
