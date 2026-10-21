package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
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
 */
public final class BundledFonts implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(BundledFonts.class);
    // 편집기 기본 글꼴이 쓰는 보통 굵기. 첫 창 전에 등록한다.
    private static final String REGULAR = "JetBrainsMonoHangul-Regular.ttf";
    // 굵게 · 기울임. 쓰일 때까지 시간이 있으므로 풀 스레드에서 등록한다.
    private static final List<String> OTHER_FILES = List.of(
            "JetBrainsMonoHangul-Bold.ttf", "JetBrainsMonoHangul-Italic.ttf", "JetBrainsMonoHangul-BoldItalic.ttf");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        register(REGULAR);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (String file : OTHER_FILES) {
                register(file);
            }
        });
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
