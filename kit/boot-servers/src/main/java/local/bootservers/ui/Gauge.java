package local.bootservers.ui;

import com.intellij.util.ui.JBUI;

import javax.swing.JProgressBar;
import java.awt.Dimension;

/**
 * 끝을 모르는 일 동안 보이는 IntelliJ 식 진행 막대. 글은 막대 안에 그린다.
 * 처음에는 숨겨 두고, 부르는 쪽이 일 시작에 보이고 끝에 숨긴다. 폭은 동기화 창 막대와 같다.
 */
public final class Gauge {

    private static final int WIDTH = 260;

    private Gauge() {
    }

    public static JProgressBar create(String text) {
        JProgressBar gauge = new JProgressBar();
        gauge.setIndeterminate(true);
        gauge.setStringPainted(true);
        gauge.setString(text);
        gauge.setPreferredSize(new Dimension(JBUI.scale(WIDTH), gauge.getPreferredSize().height));
        gauge.setVisible(false);
        return gauge;
    }
}
