package ei.db.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * 끝을 모르는 일 동안 보이는 진행 표시: IntelliJ 상태줄처럼 얇은 진행 막대와 그 오른쪽의 회색 글.
 * 글을 막대 안에 그리면 막대가 글 높이만큼 두꺼워지므로 글은 막대 밖에 둔다.
 * 처음에는 숨겨 두고, 부르는 쪽이 일 시작에 보이고 끝에 숨긴다.
 */
final class Gauge extends JPanel {
  private static final int BAR_W = 120;
  private static final int GAP = 6;

  private Gauge(String text) {
    super(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0));
    setOpaque(false);
    JProgressBar bar = new JProgressBar();
    bar.setIndeterminate(true);
    bar.setPreferredSize(new Dimension(JBUI.scale(BAR_W), bar.getPreferredSize().height));
    JBLabel label = new JBLabel(text);
    label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    add(bar);
    add(label);
    setVisible(false);
  }

  static Gauge create(String text) {
    return new Gauge(text);
  }
}
