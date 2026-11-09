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
 * 없어진 테마를 고른 설정을 새 테마로 옮긴다. EI Glass · EI Graphite → Moa Graphite, EI Gray · EI White → Moa White.
 * 없어진 테마 id 는 LafManager 가 읽을 때 기본 테마로 대신하므로, 저장된 laf.xml 글을 직접 읽어 판단한다
 * (laf.xml 은 설정을 저장할 때 다시 쓰이므로 첫 창이 뜰 때까지는 옛 id 가 남아 있다).
 * 「OS 와 맞추기」에서 쓰는 밝은 · 어두운 테마({@code <preferred-light-laf>} · {@code <preferred-dark-laf>})도 같은 표로 옮긴다.
 * 현재 테마와 OS 맞춤 테마는 표시 키를 따로 두어 각각 IDE 설정마다 한 번만 적용한다. 그 뒤 사용자가 고른 테마는 그대로 둔다.
 */
public final class ThemeMigration implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ThemeMigration.class);
    /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. EI 이름 때 옮긴 설정도 Moa 이름으로 한 번 더 옮기도록 새 키를 쓴다. */
    private static final String APPLIED_KEY = "moaide.theme.moaRename.applied";
    /** OS 맞춤 테마를 옮겼다는 표시. APPLIED_KEY 가 이미 남은 설정에서도 한 번 돌도록 따로 둔다. */
    private static final String PREFERRED_APPLIED_KEY = "moaide.theme.moaRename.preferred.applied";
    /** laf.xml 의 현재 테마 · OS 맞춤 밝은 테마 · OS 맞춤 어두운 테마 요소 이름. */
    private static final String CURRENT_TAG = "laf";
    private static final String PREFERRED_LIGHT_TAG = "preferred-light-laf";
    private static final String PREFERRED_DARK_TAG = "preferred-dark-laf";
    private static final String THEME_ID_ATTRIBUTE = "themeId=\"";
    /** 옛 테마 id → 새 테마 id (plugin.xml themeProvider id). */
    private static final Map<String, String> RENAMED = Map.of(
            "EI Glass", "Moa Graphite", "EI Gray", "Moa White",
            "EI Graphite", "Moa Graphite", "EI White", "Moa White");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (!properties.getBoolean(APPLIED_KEY)) {
            properties.setValue(APPLIED_KEY, true);
            migrateCurrentTheme();
        }
        if (!properties.getBoolean(PREFERRED_APPLIED_KEY)) {
            properties.setValue(PREFERRED_APPLIED_KEY, true);
            migratePreferredThemes();
        }
    }

    private static void migrateCurrentTheme() {
        UIThemeLookAndFeelInfo theme = renamedTheme(savedThemeId(CURRENT_TAG));
        if (theme == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            // lockEditorScheme=false 라야 테마에 딸린 편집기 색(editorScheme)까지 같이 바뀐다.
            LafManager.getInstance().setCurrentLookAndFeel(theme, false);
            LafManager.getInstance().updateUI();
        });
    }

    /**
     * OS 맞춤 테마가 옛 id 이면 새 테마로 바꾼다. 옛 id 가 아니면(사용자가 고른 다른 테마 포함) 그대로 둔다.
     * OS 맞춤이 켜져 있으면 지금 밝기 쪽의 옮긴 테마를 바로 쓴다(옛 id 를 못 찾아 기본 테마로 떠 있던 것).
     */
    private static void migratePreferredThemes() {
        UIThemeLookAndFeelInfo light = renamedTheme(savedThemeId(PREFERRED_LIGHT_TAG));
        UIThemeLookAndFeelInfo dark = renamedTheme(savedThemeId(PREFERRED_DARK_TAG));
        if (light == null && dark == null) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            LafManager lafManager = LafManager.getInstance();
            if (light != null) lafManager.setPreferredLightLaf(light);
            if (dark != null) lafManager.setPreferredDarkLaf(dark);
            if (!lafManager.getAutodetect()) return;
            UIThemeLookAndFeelInfo current = lafManager.getCurrentUIThemeLookAndFeel();
            if (current == null) return;
            UIThemeLookAndFeelInfo target = current.isDark() ? dark : light;
            if (target == null || target == current) return;
            lafManager.setCurrentLookAndFeel(target, false);
            lafManager.updateUI();
        });
    }

    /** 옛 테마 id 에 맞는 새 테마. 옛 id 가 아니거나 새 테마가 없으면 null. */
    private static UIThemeLookAndFeelInfo renamedTheme(String oldId) {
        String newId = RENAMED.get(oldId);
        if (newId == null) return null;
        UIThemeLookAndFeelInfo theme = LafManager.getInstance().findLaf(newId);
        if (theme == null) LOG.warn("옮길 테마가 없음: " + newId);
        return theme;
    }

    /** laf.xml 의 {@code <tag themeId="…"/>} 값. 요소나 파일이 없거나 못 읽으면 빈 문자열. */
    private static String savedThemeId(String tag) {
        Path file = Path.of(PathManager.getOptionsPath(), "laf.xml");
        if (!Files.isRegularFile(file)) return "";
        try {
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            int element = xml.indexOf("<" + tag + " ");
            if (element < 0) return "";
            int start = xml.indexOf(THEME_ID_ATTRIBUTE, element);
            if (start < 0) return "";
            start += THEME_ID_ATTRIBUTE.length();
            int end = xml.indexOf('"', start);
            return end < 0 ? "" : xml.substring(start, end);
        } catch (Exception e) {
            LOG.warn("laf.xml 읽기 실패", e);
            return "";
        }
    }
}
