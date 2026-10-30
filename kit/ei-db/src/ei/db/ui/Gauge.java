package ei.db.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * 진행 표시: 창 너비 전체를 가로지르는 2px 진행 선({@link #line()})과, 원래 자리에 남는 회색 글(이 패널).
 * 부르는 쪽이 선을 도구 막대 바로 아래에 붙인다. 선 칸은 숨겨도 높이를 지켜 아래 내용이 들썩이지 않는다.
 * 처음에는 숨겨 두고, 부르는 쪽이 일 시작에 보이고 끝에 숨긴다. 글과 선은 함께 보이고 숨는다.
 */
final class Gauge extends JPanel {
  /** IntelliJ 편집기 · 탐색줄의 가는 진행 선과 같은 두께. */
  private static final int LINE_H = 2;

  private final JProgressBar bar = new JProgressBar();
  private final JPanel line = new JPanel(new BorderLayout());
  private final JBLabel label = new JBLabel();

  private Gauge(String text) {
    super(new FlowLayout(FlowLayout.LEFT, 0, 0));
    setOpaque(false);
    bar.setIndeterminate(true);
    // DarculaProgressBarUI 가 읽는 값: 선 두께와 둥글지 않은 끝.
    bar.putClientProperty("ProgressBar.stripeWidth", LINE_H);
    bar.putClientProperty("ProgressBar.flatEnds", Boolean.TRUE);
    bar.setBorder(JBUI.Borders.empty());
    bar.setVisible(false);
    line.setOpaque(false);
    line.setPreferredSize(new Dimension(0, JBUI.scale(LINE_H)));
    line.add(bar, BorderLayout.CENTER);
    label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
    label.setText(text);
    add(label);
    setVisible(false);
  }

  static Gauge create(String text) {
    return new Gauge(text);
  }

  /** 도구 막대 아래에 창 너비로 붙일 선 칸. */
  JComponent line() {
    return line;
  }

  @Override
  public void setVisible(boolean on) {
    super.setVisible(on);
    bar.setVisible(on);
  }
}
