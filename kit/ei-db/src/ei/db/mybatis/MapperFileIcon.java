package ei.db.mybatis;

import com.intellij.ide.IconProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/** MyBatis 매퍼 XML(글에 &lt;mapper 가 있는 XML) 은 프로젝트 창 · 탭에서 매퍼 아이콘으로 보인다. */
public final class MapperFileIcon extends IconProvider implements DumbAware {

  private static final Icon MAPPER = IconLoader.getIcon("/icons/ei/fileMapper.svg", MapperFileIcon.class);

  @Override
  public Icon getIcon(@NotNull PsiElement element, int flags) {
    return element instanceof XmlFile file && MapperDocs.isMapper(file) ? MAPPER : null;
  }
}
