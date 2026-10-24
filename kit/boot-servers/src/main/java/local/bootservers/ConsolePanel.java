package local.bootservers;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;

import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * 실행 창 탭 내용: 가운데 콘솔, 왼쪽 세로 도구줄.
 * 도구줄은 actions 를 그대로 보이므로 탭을 만든 뒤 넣은 동작도 나온다. 마지막에 콘솔 기본 동작(맨 아래로 · 줄 바꿈 · 지우기 · 인쇄 · 스택 위아래)을 붙인다.
 */
final class ConsolePanel {

    // 도구줄 위치 이름(동작 사용 기록용). IntelliJ RunContentExecutor 와 같은 값.
    private static final String PLACE = "RunContentExecutor";

    private ConsolePanel() {
    }

    /** 화면 스레드에서 부른다. 콘솔 기본 동작은 콘솔 편집기가 생긴 뒤(getComponent 뒤)에 만든다. */
    static JPanel create(ConsoleView console, DefaultActionGroup actions) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(console.getComponent(), BorderLayout.CENTER);
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(PLACE, actions, false);
        toolbar.setTargetComponent(panel);
        panel.add(toolbar.getComponent(), BorderLayout.WEST);
        return panel;
    }

    /** 앞 동작 뒤에 구분선과 콘솔 기본 동작을 붙인다. */
    static void addConsoleActions(ConsoleView console, DefaultActionGroup actions) {
        if (actions.getChildrenCount() > 0) {
            actions.addSeparator();
        }
        actions.addAll(console.createConsoleActions());
    }
}
