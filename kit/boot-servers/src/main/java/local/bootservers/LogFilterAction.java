package local.bootservers;

import com.intellij.execution.impl.ConsoleViewUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

/** 콘솔 우클릭 메뉴의 「로그 거르기」. 지금 콘솔 글을 대상으로 거르기 창을 연다. 콘솔 편집기에서만 보인다. */
public final class LogFilterAction extends DumbAwareAction {

    @Override
    public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(consoleEditor(e) != null && e.getProject() != null);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Editor editor = consoleEditor(e);
        Project project = e.getProject();
        if (editor != null && project != null) {
            new LogFilterDialog(project, editor).show();
        }
    }

    private static Editor consoleEditor(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        return editor != null && ConsoleViewUtil.isConsoleViewEditor(editor) ? editor : null;
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
