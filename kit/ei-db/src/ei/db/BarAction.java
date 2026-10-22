package ei.db;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.util.function.BooleanSupplier;

/**
 * 도구 창 · 편집기 띠의 도구줄 단추 하나. text 는 툴팁 제목 · 접근성 이름, description 은 툴팁 풀이(없으면 null).
 * 켜짐 · 보임 조건은 화면 상태만 보므로 화면 스레드(EDT)에서 판정한다.
 */
public class BarAction extends DumbAwareAction {
  private final Runnable run;
  private BooleanSupplier enabled = () -> true;
  private BooleanSupplier visible = () -> true;

  public BarAction(String text, String description, Icon icon, Runnable run) {
    super(text, description, icon);
    this.run = run;
  }

  public BarAction enabledWhen(BooleanSupplier condition) {
    enabled = condition;
    return this;
  }

  public BarAction visibleWhen(BooleanSupplier condition) {
    visible = condition;
    return this;
  }

  /** 아이콘이 없거나 글이 뜻을 전하는 단추(INSERT · 커밋 등)는 아이콘 옆에 글도 보인다. */
  public BarAction withText() {
    getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
    return this;
  }

  /** 등록된 동작(plugin.xml)의 단축키를 툴팁에 보인다. 단축키 처리는 그 동작이 맡으므로 여기서는 표시만 한다. */
  public BarAction shortcutOf(String actionId) {
    AnAction registered = ActionManager.getInstance().getAction(actionId);
    if (registered != null) copyShortcutFrom(registered);
    return this;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    run.run();
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setVisible(visible.getAsBoolean());
    e.getPresentation().setEnabled(enabled.getAsBoolean());
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  /** 가로 도구줄. target 은 동작 문맥을 줄 부품이다. 구분선은 actions 사이에 Separator 를 넣는다. */
  public static ActionToolbar toolbar(String place, JComponent target, AnAction... actions) {
    ActionToolbar bar = ActionManager.getInstance().createActionToolbar(place, new DefaultActionGroup(actions), true);
    bar.setTargetComponent(target);
    bar.setReservePlaceAutoPopupIcon(false);
    return bar;
  }
}
