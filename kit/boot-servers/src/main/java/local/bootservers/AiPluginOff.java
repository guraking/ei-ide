package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.plugins.PluginEnabler;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * 옛 설정 폴더에서 따라 들어온 JetBrains AI 플러그인을 IDE 설정마다 한 번 끈다(다음 실행부터 꺼짐).
 * 라이선스 없이 켜져 있으면 30초마다 JetBrains 서버에 권한을 묻고 실패한다. 그 뒤 사용자가 다시 켜면 그대로 둔다.
 */
public final class AiPluginOff implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(AiPluginOff.class);
    /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. */
    private static final String APPLIED_KEY = "moaide.aiPlugin.off.applied";
    private static final PluginId AI_PLUGIN = PluginId.getId("com.intellij.ml.llm");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (properties.getBoolean(APPLIED_KEY)) return;
        properties.setValue(APPLIED_KEY, true);
        if (PluginManagerCore.getPlugin(AI_PLUGIN) == null || PluginManagerCore.isDisabled(AI_PLUGIN)) return;
        if (PluginEnabler.HEADLESS.disableById(Set.of(AI_PLUGIN))) {
            LOG.info("JetBrains AI 플러그인을 껐습니다. 다음 실행부터 적용됩니다.");
        } else {
            LOG.warn("JetBrains AI 플러그인 끄기 실패");
        }
    }
}
