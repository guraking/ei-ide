package ei.db.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import ei.db.EiDbBundle;

/** 오른쪽 「데이터베이스」 도구 창. */
public final class DbToolWindowFactory implements ToolWindowFactory, DumbAware {

  @Override
  public void init(@NotNull ToolWindow toolWindow) {
    toolWindow.setStripeTitle(EiDbBundle.message("toolwindow.stripe.EI_Database"));
  }

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    DbExplorer explorer = new DbExplorer(project, toolWindow.getDisposable());
    Content content = ContentFactory.getInstance().createContent(explorer, "", false);
    toolWindow.getContentManager().addContent(content);
  }
}
