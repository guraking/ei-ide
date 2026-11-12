package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher;
import com.intellij.openapi.fileTypes.FileNameMatcher;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.impl.AbstractFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.TextMateService;

import java.util.ArrayList;
import java.util.List;

/**
 * 커뮤니티판이 defaultFileTypes.xml 로 미리 잡아 두는 「… (syntax highlighting only)」 자리표시 파일 종류(JSP · SQL · JavaScript · CSS 등)의
 * 확장자 연결을 푼다. 자리표시 종류는 문자열 · 숫자 · 주석 · 키워드 자리만 있어 태그 · 속성 · 함수 색이 나오지 않고,
 * TextMate 문법은 아무 종류도 잡지 않은 확장자만 맡으므로 연결을 풀어야 TextMate 가 그 파일을 색칠한다.
 * TextMate 번들이 없는 확장자는 두는데, 풀면 색이 아예 없는 일반 글이 되기 때문이다.
 * 푼 연결은 사용자 설정(filetypes.xml)에 남으므로 IDE 설정마다 한 번만 적용하고, 그 뒤 사용자가 File Types 에서 고친 것은 그대로 둔다.
 */
public final class PlaceholderFileTypes implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(PlaceholderFileTypes.class);
    /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. */
    private static final String APPLIED_KEY = "eiide.fileTypes.placeholderReleased.applied";

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (properties.getBoolean(APPLIED_KEY)) return;
        // 파일 종류 변경은 EDT 의 쓰기 동작 안에서만 허용된다. 풀 연결 고르기(TextMate 문법 찾기)는 쓰기 잠금 밖에서 하고,
        // 쓰기 동작은 푸는 것만 한 번에 한다. 표시는 끝까지 마친 뒤에 남겨, 도중에 실패하면 다음 실행에 다시 한다.
        ApplicationManager.getApplication().invokeLater(() -> {
            List<Release> releases = ReadAction.compute(PlaceholderFileTypes::findReleases);
            if (!releases.isEmpty()) {
                WriteAction.run(() -> release(releases));
            }
            properties.setValue(APPLIED_KEY, true);
        });
    }

    /** 풀 연결 하나: 자리표시 파일 종류와 그 확장자. */
    private record Release(FileType type, ExtensionFileNameMatcher matcher) {
    }

    /** TextMate 문법이 있는 확장자에 걸린 자리표시 파일 종류 연결. */
    private static List<Release> findReleases() {
        FileTypeManager manager = FileTypeManager.getInstance();
        TextMateService textMate = TextMateService.getInstance();
        List<Release> releases = new ArrayList<>();
        for (FileType type : manager.getRegisteredFileTypes()) {
            if (!(type instanceof AbstractFileType)) continue;
            for (FileNameMatcher matcher : manager.getAssociations(type)) {
                if (!(matcher instanceof ExtensionFileNameMatcher extension)) continue;
                if (textMate.getLanguageDescriptorByFileName("a." + extension.getExtension()) == null) continue;
                releases.add(new Release(type, extension));
            }
        }
        return releases;
    }

    private static void release(List<Release> releases) {
        FileTypeManager manager = FileTypeManager.getInstance();
        for (Release release : releases) {
            manager.removeAssociation(release.type(), release.matcher());
            LOG.info("자리표시 파일 종류 연결 해제: " + release.type().getName() + " *." + release.matcher().getExtension());
        }
    }
}
