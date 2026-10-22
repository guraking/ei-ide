package ei.db.gen;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Component;
import java.lang.reflect.Field;

/**
 * 「기능 한 벌 만들기…」: 데이터베이스 탐색기에서 고른 표로 FeatureDialog 를 연다.
 * 포커스가 탐색기 나무에 있으면 그 나무를, 아니면 「EI Database」 도구 창의 나무를 본다.
 * shortcut: 탐색기 표 마디(DbExplorer$TableItem)가 비공개라 필드를 리플렉션으로 읽는다. 탐색기 우클릭 메뉴에 이 동작을 넣으면 걷어 낸다.
 */
public final class FeatureAction extends DumbAwareAction {

  private static final String TOOL_WINDOW_ID = "EI Database";
  private static final String TABLE_ITEM = "ei.db.ui.DbExplorer$TableItem";

  private record Picked(DbConfig c, String schema, String table) {}

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabled(e.getProject() != null);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    Object item = selectedItem(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT));
    if (item == null) item = selectedItem(explorerTree(project));
    if (item == null || !TABLE_ITEM.equals(item.getClass().getName())) {
      Notify.warning(project, EiDbBundle.message("feature.no.table"));
      return;
    }
    Picked p;
    try {
      p = new Picked((DbConfig) read(item, "c"), (String) read(item, "schema"), ((Meta.Table) read(item, "table")).name());
    } catch (ReflectiveOperationException | RuntimeException ex) {
      Notify.error(project, EiDbBundle.message("feature.read.table.failed", ex));
      return;
    }
    FeatureDialog.show(project, p.c(), p.schema(), p.table());
  }

  private static Object selectedItem(Component comp) {
    if (!(comp instanceof JTree tree)) return null;
    return tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
  }

  private static JTree explorerTree(Project project) {
    ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    Content content = tw == null ? null : tw.getContentManager().getSelectedContent();
    return content == null ? null : UIUtil.findComponentOfType(content.getComponent(), JTree.class);
  }

  private static Object read(Object target, String name) throws ReflectiveOperationException {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }
}
