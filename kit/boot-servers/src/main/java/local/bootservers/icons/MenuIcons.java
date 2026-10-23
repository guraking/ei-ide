package local.bootservers.icons;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Presentation;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.List;
import java.util.Map;

/**
 * 주 메뉴 · 우클릭 메뉴에서 아이콘이 없는 기본 동작에 아이콘을 단다(/icons/ei/menu-icons.properties, 「동작 id=아이콘」).
 * 이미 아이콘이 있는 동작은 건드리지 않으므로 IntelliJ 기본 아이콘은 그대로 남는다.
 * 없는 id(플러그인을 끈 경우 등)는 건너뛴다. 첫 창이 뜰 때 한 번 한다.
 */
public final class MenuIcons implements AppLifecycleListener {

    private static final String RULES = "/icons/ei/menu-icons.properties";

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        ActionManager actionManager = ActionManager.getInstance();
        for (Map.Entry<String, Icon> rule : IconRules.load(RULES).entrySet()) {
            AnAction action = actionManager.getAction(rule.getKey());
            if (action == null) {
                continue;
            }
            Presentation template = action.getTemplatePresentation();
            if (template.getIcon() == null) {
                template.setIcon(rule.getValue());
            }
        }
    }
}
