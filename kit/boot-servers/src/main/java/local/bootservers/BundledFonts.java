package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.List;

/**
 * 플러그인 jar 에 든 글꼴을 JVM 에 등록해 OS 에 설치하지 않아도 편집기 글꼴 목록에 나오게 한다.
 * JetBrainsMonoHangul (JetBrains Mono + D2Coding 한글, OFL 1.1, https://github.com/Jhyub/JetBrainsMonoHangul).
 * 편집기 기본 글꼴 이름(FontPreferences)이 이 가족 이름을 가리키므로 첫 창이 뜨기 전에 등록해야 한다.
 * IDE 화면 글꼴(메뉴 · 설정 창 · 도구 창)도 이 글꼴로 한다. 설정마다 한 번만 바꾸고, 그 뒤 사용자가 고른 글꼴은 그대로 둔다.
 */
public final class BundledFonts implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(BundledFonts.class);
    // 편집기 기본 글꼴이 쓰는 보통 굵기. 첫 창 전에 등록한다.
    private static final String REGULAR = "JetBrainsMonoHangul-Regular.ttf";
    // 글꼴 파일 안의 가족 이름. 화면 글꼴 설정(UISettings.fontFace)에 이 이름을 넣는다.
    private static final String FAMILY = "JetBrainsMonoHangul";
    /** 화면 글꼴을 바꿨다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. */
    private static final String UI_FONT_APPLIED_KEY = "eiide.uiFont.monoHangul.applied";
    // 굵게 · 기울임. 쓰일 때까지 시간이 있으므로 풀 스레드에서 등록한다.
    private static final List<String> OTHER_FILES = List.of(
            "JetBrainsMonoHangul-Bold.ttf", "JetBrainsMonoHangul-Italic.ttf", "JetBrainsMonoHangul-BoldItalic.ttf");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        register(REGULAR);
        applyUiFont();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (String file : OTHER_FILES) {
                register(file);
            }
        });
    }

    /**
     * 화면 글꼴을 FAMILY 로 한다(Appearance → Use custom font). 처음 한 번만 설정을 바꾼다.
     * 화면 글꼴은 이 글꼴을 등록하기 전에 깔리므로, 설정이 FAMILY 를 가리키면 등록 뒤 화면을 다시 깐다.
     * 등록에 실패했으면 다시 깔지 않는다(설정은 남아도 IntelliJ 가 없는 글꼴을 기본 글꼴로 대신한다).
     */
    private static void applyUiFont() {
        if (!FAMILY.equals(new Font(FAMILY, Font.PLAIN, 1).getFamily())) return;
        UISettings ui = UISettings.getInstance();
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (!properties.getBoolean(UI_FONT_APPLIED_KEY)) {
            properties.setValue(UI_FONT_APPLIED_KEY, true);
            ui.setOverrideLafFonts(true);
            ui.setFontFace(FAMILY);
        }
        if (ui.getOverrideLafFonts() && FAMILY.equals(ui.getFontFace())) {
            ApplicationManager.getApplication().invokeLater(() -> LafManager.getInstance().updateUI());
        }
    }

    /** jar 의 /fonts/file 을 등록한다. 실패하면 기록만 남긴다. */
    private static void register(String file) {
        try (InputStream in = BundledFonts.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) {
                LOG.warn("글꼴 자원이 없음: " + file);
                return;
            }
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
        } catch (Exception e) {
            LOG.warn("글꼴 등록 실패: " + file, e);
        }
    }
}
