package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 없어진 테마를 고른 설정을 새 테마로 옮긴다. EI Glass · EI Graphite → Moa Graphite, EI Gray · EI White → Moa White.
 * 없어진 테마 id 는 LafManager 가 읽을 때 기본 테마로 대신하므로, 저장된 laf.xml 글을 직접 읽어 판단한다
 * (laf.xml 은 설정을 저장할 때 다시 쓰이므로 첫 창이 뜰 때까지는 옛 id 가 남아 있다).
 * 「OS 와 맞추기」에서 쓰는 밝은 · 어두운 테마({@code <preferred-light-laf>} · {@code <preferred-dark-laf>})도 같은 표로 옮긴다.
 * 현재 테마와 OS 맞춤 테마는 표시 키를 따로 두어 각각 IDE 설정마다 한 번만 적용한다. 그 뒤 사용자가 고른 테마는 그대로 둔다.
 * 옛 테마의 편집기 색 구성표를 고친 사용자 사본(colors/_@user_EI ….icls)도 같은 표로 새 이름 사본으로 옮긴다.
 */
public final class ThemeMigration implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ThemeMigration.class);
    /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. EI 이름 때 옮긴 설정도 Moa 이름으로 한 번 더 옮기도록 새 키를 쓴다. */
    private static final String APPLIED_KEY = "moaide.theme.moaRename.applied";
    /** OS 맞춤 테마를 옮겼다는 표시. APPLIED_KEY 가 이미 남은 설정에서도 한 번 돌도록 따로 둔다. */
    private static final String PREFERRED_APPLIED_KEY = "moaide.theme.moaRename.preferred.applied";
    /** 편집기 색 구성표 사본을 옮겼다는 표시. */
    private static final String SCHEMES_APPLIED_KEY = "moaide.theme.moaRename.schemes.applied";
    /** IntelliJ 가 기본 구성표를 고친 사본에 붙이는 이름 앞부분과 파일 확장자(설정 폴더 colors). */
    private static final String USER_SCHEME_PREFIX = "_@user_";
    private static final String SCHEME_EXTENSION = ".icls";
    /** 옮기고 남은 옛 사본을 지우지 않고 두는 colors 아래 폴더. IntelliJ 는 colors 바로 아래 파일만 읽는다. */
    private static final String OLD_SCHEMES_DIR = "_old-EI";
    /** 옮길 순서. 같은 새 이름으로 가는 옛 구성표가 둘이면(EI Graphite · EI Glass) 앞의 것을 옮긴다. */
    private static final List<String> SCHEME_ORDER = List.of("EI Graphite", "EI White", "EI Glass", "EI Gray");
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
        if (!properties.getBoolean(SCHEMES_APPLIED_KEY)) {
            properties.setValue(SCHEMES_APPLIED_KEY, true);
            migrateEditorSchemes();
        }
    }

    /**
     * colors/_@user_EI ….icls 를 새 이름 사본(_@user_Moa ….icls)으로 옮긴다. 새 이름 사본이 이미 있으면 그것을 둔다.
     * 옛 사본은 colors/_old-EI 로 옮겨 둔다(부모 구성표가 없어 IntelliJ 가 매번 건너뛰던 파일). 옮긴 것이 있으면 구성표를 다시 읽는다.
     */
    private static void migrateEditorSchemes() {
        Path dir = Path.of(PathManager.getConfigPath(), "colors");
        boolean moved = false;
        for (String oldName : SCHEME_ORDER) {
            Path oldFile = dir.resolve(USER_SCHEME_PREFIX + oldName + SCHEME_EXTENSION);
            if (!Files.isRegularFile(oldFile)) continue;
            String newName = RENAMED.get(oldName);
            Path newFile = dir.resolve(USER_SCHEME_PREFIX + newName + SCHEME_EXTENSION);
            try {
                if (!Files.exists(newFile)) {
                    Files.writeString(newFile, renameScheme(Files.readString(oldFile, StandardCharsets.UTF_8), oldName, newName), StandardCharsets.UTF_8);
                }
                Path aside = Files.createDirectories(dir.resolve(OLD_SCHEMES_DIR));
                Files.move(oldFile, aside.resolve(oldFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (Exception e) {
                LOG.warn("편집기 색 구성표 옮기기 실패: " + oldFile, e);
            }
        }
        if (moved) {
            ApplicationManager.getApplication().invokeLater(() -> EditorColorsManager.getInstance().reloadKeepingActiveScheme());
        }
    }

    /** 구성표 글의 이름 · 원래 구성표 이름 · 옛 글꼴 이름을 새 이름으로 바꾼다. 부모 구성표(Darcula · Default)는 그대로 둔다. */
    static String renameScheme(String xml, String oldName, String newName) {
        return xml.replace("\"" + USER_SCHEME_PREFIX + oldName + "\"", "\"" + USER_SCHEME_PREFIX + newName + "\"")
                .replace(">" + oldName + "<", ">" + newName + "<")
                .replace("\"" + BundledFonts.OLD_FAMILY + "\"", "\"" + BundledFonts.FAMILY + "\"");
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
