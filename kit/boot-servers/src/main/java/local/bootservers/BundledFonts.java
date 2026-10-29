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
 * IDE 화면 글꼴(메뉴 · 설정 창 · 도구 창)은 JetBrains Mono 로 한다. 설정마다 한 번만 바꾸고, 그 뒤 사용자가 고른 글꼴은 그대로 둔다.
 */
public final class BundledFonts implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(BundledFonts.class);
    // 편집기 기본 글꼴이 쓰는 보통 굵기. 첫 창 전에 등록한다.
    private static final String REGULAR = "JetBrainsMonoHangul-Regular.ttf";
    // 화면 글꼴(UISettings.fontFace). IntelliJ 가 함께 싣는 글꼴이라 화면이 깔리기 전에 이미 있다.
    // 한글이 없어 한글은 OS 기본 한글 글꼴로 그려진다. JetBrainsMonoHangul 을 화면 글꼴로 쓰면 한글이 두 칸 폭이라 음절마다 벌어지고 작은 크기에서 획이 얇다.
    private static final String UI_FAMILY = "JetBrains Mono";
    /** 화면 글꼴을 바꿨다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. 이전 키(monoHangul)로 이미 바꾼 PC 도 한 번 다시 바꾼다. */
    private static final String UI_FONT_APPLIED_KEY = "eiide.uiFont.jetbrainsMono.applied";
    // 굵게 · 기울임. 편집기에서 굵은 글(키워드 등)이 첫 화면부터 나오므로 함께 등록한다.
    // 늦게 등록하면 그 사이 자바가 보통 굵기를 번지게 그려 굵기를 흉내 내어 한글 획이 뭉개진다.
    private static final List<String> OTHER_FILES = List.of(
            "JetBrainsMonoHangul-Bold.ttf", "JetBrainsMonoHangul-Italic.ttf", "JetBrainsMonoHangul-BoldItalic.ttf");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        register(REGULAR);
        for (String file : OTHER_FILES) {
            register(file);
        }
        applyUiFont();
    }

    /** 화면 글꼴을 UI_FAMILY 로 한다(Appearance → Use custom font). 처음 한 번만 바꾸고 화면을 다시 깐다. 글꼴이 없으면 손대지 않는다. */
    private static void applyUiFont() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (properties.getBoolean(UI_FONT_APPLIED_KEY)) return;
        if (!UI_FAMILY.equals(new Font(UI_FAMILY, Font.PLAIN, 1).getFamily())) return;
        properties.setValue(UI_FONT_APPLIED_KEY, true);
        UISettings ui = UISettings.getInstance();
        ui.setOverrideLafFonts(true);
        ui.setFontFace(UI_FAMILY);
        ApplicationManager.getApplication().invokeLater(() -> LafManager.getInstance().updateUI());
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
