package local.bootservers.icons;

import com.intellij.ide.IconProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import javax.swing.Icon;
import java.util.Locale;
import java.util.Map;

/**
 * 프로젝트 창 폴더 · 패키지 아이콘을 이름으로 고른다(/icons/ei/folder-icons.properties).
 * 자바 소스 · 테스트 루트 아래 폴더는 「package.이름」, 그 밖(리소스 · webapp 등)은 「folder.이름」 규칙을 쓴다. 이름은 소문자로 맞춘다.
 * 소스 · 리소스 · 내용 루트와 제외 폴더는 IntelliJ 아이콘을 그대로 둔다.
 */
public final class FolderIcons extends IconProvider implements DumbAware {

    private static final Map<String, Icon> RULES = IconRules.load("/icons/ei/folder-icons.properties");
    private static final String FOLDER = "folder.";
    private static final String PACKAGE = "package.";

    @Override
    public Icon getIcon(@NotNull PsiElement element, int flags) {
        if (!(element instanceof PsiDirectory directory)) {
            return null;
        }
        VirtualFile file = directory.getVirtualFile();
        ProjectFileIndex index = ProjectFileIndex.getInstance(directory.getProject());
        if (ProjectRootsUtil.isModuleContentRoot(file, directory.getProject())
                || ProjectRootsUtil.isModuleSourceRoot(file, directory.getProject())
                || index.isExcluded(file)) {
            return null;
        }
        String prefix = index.getContainingSourceRootType(file) instanceof JavaSourceRootType ? PACKAGE : FOLDER;
        return RULES.get(prefix + file.getName().toLowerCase(Locale.ROOT));
    }
}
