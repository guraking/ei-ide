package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 없어진 테마를 고른 설정을 새 테마로 옮긴다. EI Glass → EI Graphite, EI Gray → EI White.
 * 없어진 테마 id 는 LafManager 가 읽을 때 기본 테마로 대신하므로, 저장된 laf.xml 글을 직접 읽어 판단한다
 * (laf.xml 은 설정을 저장할 때 다시 쓰이므로 첫 창이 뜰 때까지는 옛 id 가 남아 있다).
 * IDE 설정마다 한 번만 적용한다. 그 뒤 사용자가 고른 테마는 그대로 둔다.
 */
public final class ThemeMigration implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ThemeMigration.class);
    /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. */
    private static final String APPLIED_KEY = "eiide.theme.graphiteWhite.applied";
    /** 옛 테마 id → 새 테마 id (plugin.xml themeProvider id). */
    private static final Map<String, String> RENAMED = Map.of("EI Glass", "EI Graphite", "EI Gray", "EI White");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (properties.getBoolean(APPLIED_KEY)) return;
        properties.setValue(APPLIED_KEY, true);

        String newId = RENAMED.get(savedThemeId());
        if (newId == null) return;
        UIThemeLookAndFeelInfo theme = LafManager.getInstance().findLaf(newId);
        if (theme == null) {
            LOG.warn("옮길 테마가 없음: " + newId);
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            // lockEditorScheme=false 라야 테마에 딸린 편집기 색(editorScheme)까지 같이 바뀐다.
            LafManager.getInstance().setCurrentLookAndFeel(theme, false);
            LafManager.getInstance().updateUI();
        });
    }

    /** laf.xml 의 {@code <laf themeId="…"/>} 값. 파일이 없거나 못 읽으면 빈 문자열. */
    private static String savedThemeId() {
        Path file = Path.of(PathManager.getOptionsPath(), "laf.xml");
        if (!Files.isRegularFile(file)) return "";
        try {
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            int laf = xml.indexOf("<laf ");
            if (laf < 0) return "";
            int start = xml.indexOf("themeId=\"", laf);
            if (start < 0) return "";
            start += "themeId=\"".length();
            int end = xml.indexOf('"', start);
            return end < 0 ? "" : xml.substring(start, end);
        } catch (Exception e) {
            LOG.warn("laf.xml 읽기 실패", e);
            return "";
        }
    }
}
