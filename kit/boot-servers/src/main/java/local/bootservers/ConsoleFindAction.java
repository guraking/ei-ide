package local.bootservers;

import com.intellij.execution.impl.ConsoleViewUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;

/**
 * 실행 콘솔에서 Ctrl+F 로 찾기 띠를 연다.
 * 이클립스 키맵은 Ctrl+F 를 「바꾸기」 에 주고 「찾기」 의 단축키를 비우므로 콘솔에서 Ctrl+F 가 아무 일도 하지 않는다.
 * 콘솔 편집기에서만 켜지므로 코드 편집기의 Ctrl+F(바꾸기)는 그대로다.
 */
public final class ConsoleFindAction extends DumbAwareAction {

    @Override
    public void update(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabled(editor != null && ConsoleViewUtil.isConsoleViewEditor(editor));
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        AnAction find = ActionManager.getInstance().getAction(IdeActions.ACTION_FIND);
        if (find != null) {
            ActionUtil.invokeAction(find, e.getDataContext(), e.getPlace(), e.getInputEvent(), null);
        }
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
