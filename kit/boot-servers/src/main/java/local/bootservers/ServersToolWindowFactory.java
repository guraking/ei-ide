package local.bootservers;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** 아래쪽 「서버」 창. 색인 중에도 열리고, 서버 목록은 색인이 끝난 뒤 채운다. */
public final class ServersToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        toolWindow.setStripeTitle("서버");
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ServersPanel panel = new ServersPanel(project, toolWindow);
        toolWindow.getContentManager().addContent(ContentFactory.getInstance().createContent(panel, "", false));
    }
}
