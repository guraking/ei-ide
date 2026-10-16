package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
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
 */
public final class BundledFonts implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(BundledFonts.class);
    private static final List<String> FILES = List.of(
            "JetBrainsMonoHangul-Regular.ttf", "JetBrainsMonoHangul-Bold.ttf",
            "JetBrainsMonoHangul-Italic.ttf", "JetBrainsMonoHangul-BoldItalic.ttf");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String file : FILES) {
            try (InputStream in = BundledFonts.class.getResourceAsStream("/fonts/" + file)) {
                if (in == null) {
                    LOG.warn("글꼴 자원이 없음: " + file);
                    continue;
                }
                ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
            } catch (Exception e) {
                LOG.warn("글꼴 등록 실패: " + file, e);
            }
        }
    }
}
