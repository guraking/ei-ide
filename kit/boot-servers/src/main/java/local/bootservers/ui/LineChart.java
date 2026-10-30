package local.bootservers.ui;

import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * 작은 꺾은선 그래프(대시보드 10분 그래프 · 모아 보기 표의 CPU 칸). 값의 NaN 은 선을 끊는다.
 * 세로 눈금은 0 부터 값의 최대 × 1.15 까지, 가로는 값 차례대로 고르게 놓는다. 그릴 값이 둘 못 되면 empty 글을 가운데에 쓴다.
 */
public final class LineChart extends JComponent {

    // 위쪽 여유(최대값 위로 비우는 몫)
    private static final double HEADROOM = 1.15;
    private static final int GRID_LINES = 3;
    private static final float LINE_WIDTH = 1.6f;
    private static final double AREA_ALPHA = 0.14;
    private static final int PAD = 2;

    private final boolean grid;
    private final int height;
    private double[] values = new double[0];
    private String empty = "";

    /** @param grid 눈금선을 그리는가(작은 표 칸 그래프는 안 그린다), height 는 배율 전 높이 */
    public LineChart(boolean grid, int height) {
        this.grid = grid;
        this.height = height;
        setFont(JBUI.Fonts.smallFont());
    }

    public void setValues(double[] values, String empty) {
        this.values = values;
        this.empty = empty;
        repaint();
    }

    public static Color lineColor() {
        return JBUI.CurrentTheme.Link.Foreground.ENABLED;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(JBUI.scale(height * 3), JBUI.scale(height));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            double max = 0;
            int known = 0;
            for (double v : values) {
                if (!Double.isNaN(v)) {
                    max = Math.max(max, v);
                    known++;
                }
            }
            if (known < 2) {
                g2.setColor(NamedColorUtil.getInactiveTextColor());
                UIUtil.drawCenteredString(g2, new Rectangle(0, 0, w, h), empty);
                return;
            }
            if (grid) {
                g2.setColor(JBColor.border());
                for (int i = 1; i <= GRID_LINES; i++) {
                    int y = h * i / (GRID_LINES + 1);
                    g2.drawLine(0, y, w, y);
                }
            }
            double top = max <= 0 ? 1 : max * HEADROOM;
            int pad = JBUI.scale(PAD);
            double step = (w - 1.0) / (values.length - 1);
            Path2D line = new Path2D.Double();
            Path2D area = new Path2D.Double();
            boolean drawing = false;
            double lastX = 0;
            for (int i = 0; i < values.length; i++) {
                if (Double.isNaN(values[i])) {
                    if (drawing) {
                        area.lineTo(lastX, h);
                        area.closePath();
                    }
                    drawing = false;
                    continue;
                }
                double x = i * step;
                double y = h - pad - values[i] / top * (h - pad * 2);
                if (drawing) {
                    line.lineTo(x, y);
                    area.lineTo(x, y);
                }
                else {
                    line.moveTo(x, y);
                    area.moveTo(x, h);
                    area.lineTo(x, y);
                }
                drawing = true;
                lastX = x;
            }
            if (drawing) {
                area.lineTo(lastX, h);
                area.closePath();
            }
            Color color = lineColor();
            g2.setColor(ColorUtil.withAlpha(color, AREA_ALPHA));
            g2.fill(area);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(JBUI.scale(LINE_WIDTH), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(line);
        }
        finally {
            g2.dispose();
        }
    }
}
