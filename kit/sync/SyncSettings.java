package kinggura.sync;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.ui.JBIntSpinner;
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
 * 값은 IDE 전체에 하나(PropertiesComponent)다.
 */
public final class SyncSettings implements Configurable {
  private static final String AUTO_MINUTES_KEY = "kinggura.sync.autoMinutes";
  // 하루. 그보다 길게 둘 일은 없다.
  private static final int MAX_MINUTES = 1440;
  private JBIntSpinner minutes;

  /** 원격 자동 확인 간격(분). 0 이면 끔. */
  static int autoMinutes() {
    return PropertiesComponent.getInstance().getInt(AUTO_MINUTES_KEY, 0);
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
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(SyncBundle.message("syncsettings.auto"), row)
      .addComponentToRightColumn(hint)
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel();
  }

  @Override
  public boolean isModified() {
    return minutes != null && minutes.getNumber() != autoMinutes();
  }

  @Override
  public void apply() {
    PropertiesComponent.getInstance().setValue(AUTO_MINUTES_KEY, minutes.getNumber(), 0);
  }

  @Override
  public void reset() {
    if (minutes != null) minutes.setNumber(autoMinutes());
  }

  @Override
  public void disposeUIResources() {
    minutes = null;
  }
}
