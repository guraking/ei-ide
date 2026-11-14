package ei.db.console;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import ei.db.DbConfig;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 도구 메뉴 「고치기 전 백업 보기…」. 콘솔 편집기에 초점이 있을 때 부르면 그 콘솔의 접속, 접속이 하나뿐이면 그 접속의 백업 목록을 바로 연다.
 * 접속이 여럿이면 고르게 하고, 하나도 없으면 알린다.
 */
public class BackupViewAction extends DumbAwareAction {
  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(e.getProject() != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
    DbConfig console = ConsoleOpener.isConsole(file) ? ConsoleOpener.config(project, file) : null;
    List<DbConfig> all = DbSettings.getInstance().connections();
    if (console != null || all.size() == 1) {
      BackupDialog.open(project, console != null ? console : all.get(0));
    } else if (all.isEmpty()) {
      Notify.warning(project, EiDbBundle.message("backup.no.connection"));
    } else {
      JBPopupFactory.getInstance().createPopupChooserBuilder(all)
          .setTitle(EiDbBundle.message("backup.choose.connection"))
          .setItemChosenCallback(c -> BackupDialog.open(project, c))
          .createPopup().showCenteredInCurrentWindow(project);
    }
  }
}
