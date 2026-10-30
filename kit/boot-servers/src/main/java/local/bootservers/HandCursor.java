package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 우리 플러그인 셋(서버 · 동기화 · DB)의 창과 대화 상자 안에서 마우스 커서를 정한다.
 * 단추 · 도구줄 단추 · 링크 위는 손가락, 그 밖에 커서를 따로 정하지 않은 부품은 화살표.
 * 글 상자 · 칸막이처럼 커서를 이미 정한 부품은 건드리지 않는다.
 * 커서를 정하지 않은 부품은 부모를 따르는데, 우리 창에서는 입력 커서(I)가 남아 보였다.
 */
public final class HandCursor implements AppLifecycleListener {

    /** 우리 플러그인 클래스의 패키지 앞부분. boot-servers · kinggura-sync · ei-db. */
    private static final List<String> OUR_PACKAGES = List.of("local.bootservers.", "kinggura.sync.", "ei.db.");

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        Toolkit.getDefaultToolkit().addAWTEventListener(HandCursor::onMouse, AWTEvent.MOUSE_EVENT_MASK);
    }

    private static void onMouse(AWTEvent event) {
        if (event.getID() != MouseEvent.MOUSE_ENTERED || !(event.getSource() instanceof JComponent c) || c.isCursorSet() || !isOurs(c)) return;
        boolean clickable = c instanceof AbstractButton || c instanceof ActionButton;
        c.setCursor(Cursor.getPredefinedCursor(clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** 우리 대화 상자 안이거나, 부모를 따라 올라가다 우리 클래스를 만나면 우리 창이다. */
    private static boolean isOurs(Component c) {
        DialogWrapper dialog = DialogWrapper.findInstance(c);
        if (dialog != null) return isOurClass(dialog);
        for (Component p = c; p != null; p = p.getParent()) {
            if (isOurClass(p)) return true;
        }
        return false;
    }

    private static boolean isOurClass(Object o) {
        String name = o.getClass().getName();
        for (String prefix : OUR_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
