package local.bootservers.icons;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.fileTypes.FileNameMatcher;
import com.intellij.openapi.fileTypes.FileNameMatcherFactory;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Iconable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 커뮤니티판에 아이콘이 없거나 TextMate 공용 아이콘으로 보이는 파일 종류에 아이콘을 단다(/icons/ei/file-icons.properties).
 * 키는 파일 이름 패턴(*.xlsx, logback*.xml)이고 파일에 적힌 순서대로 처음 맞는 것을 쓰므로 좁은 패턴을 위에 둔다.
 */
public final class FileIcons implements FileIconProvider, DumbAware {

    private record Rule(FileNameMatcher matcher, Icon icon) {
    }

    private static final List<Rule> RULES = rules();

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (Map.Entry<String, Icon> entry : IconRules.load("/icons/ei/file-icons.properties").entrySet()) {
            rules.add(new Rule(FileNameMatcherFactory.getInstance().createMatcher(entry.getKey()), entry.getValue()));
        }
        return rules;
    }

    @Override
    public @Nullable Icon getIcon(@NotNull VirtualFile file, @Iconable.IconFlags int flags, @Nullable Project project) {
        if (file.isDirectory()) {
            return null;
        }
        for (Rule rule : RULES) {
            if (rule.matcher().acceptsCharSequence(file.getNameSequence())) {
                return rule.icon();
            }
        }
        return null;
    }
}
