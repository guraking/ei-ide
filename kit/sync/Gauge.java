package kinggura.sync;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * 진행 표시: IntelliJ 상태줄처럼 얇은 진행 막대와 그 오른쪽의 회색 글.
 * 글을 막대 안에 그리면 막대가 글 높이만큼 두꺼워지므로 글은 막대 밖에 둔다.
 * 처음에는 숨겨 두고, 부르는 쪽이 일 시작에 보이고 끝에 숨긴다.
 */
final class Gauge extends JPanel {
  private static final int BAR_W = 120;
  private static final int GAP = 6;

  private final JProgressBar bar = new JProgressBar();
  private final JBLabel label = new JBLabel();

  Gauge(String text) {
    super(new FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0));
    setOpaque(false);
    bar.setIndeterminate(true);
    bar.setPreferredSize(new Dimension(JBUI.scale(BAR_W), bar.getPreferredSize().height));
    label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    label.setText(text);
    add(bar);
    add(label);
    setVisible(false);
  }

  void setString(String text) {
    label.setText(text);
  }

  /** total 이 0 이하면 끝을 모르는 움직임, 아니면 done/total 만큼 찬다. */
  void setProgress(int done, int total) {
    bar.setIndeterminate(total <= 0);
    bar.setMaximum(Math.max(total, 1));
    bar.setValue(done);
  }
}
