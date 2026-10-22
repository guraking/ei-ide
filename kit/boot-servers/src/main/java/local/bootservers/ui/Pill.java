package local.bootservers.ui;

import com.intellij.ui.ColorUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * 둥근 바탕의 작은 꼬리표(상태 · 프로필 · WAS 종류 · 로그 레벨).
 * 바탕은 제 선호 크기만큼 왼쪽 · 세로 가운데에 그리므로 칸이 더 넓거나 높아도 꼬리표 크기는 그대로다.
 * 글이 비면 아무것도 그리지 않는다(표 · 목록 렌더러에서 자리만 둘 때).
 */
public final class Pill extends JBLabel {

    // 둥근 모서리 지름
    private static final int ARC = 8;
    // 바탕을 따로 주지 않을 때 글 색에 씌우는 불투명도
    private static final double TAG_ALPHA = 0.2;
    private static final int PAD_VERTICAL = 1;
    private static final int PAD_HORIZONTAL = 6;

    private Color background;

    public Pill() {
        setFont(JBUI.Fonts.smallFont());
        setBorder(JBUI.Borders.empty(PAD_VERTICAL, PAD_HORIZONTAL));
    }

    /** @param bg 바탕 색. null 이면 글 색을 옅게 깐다. */
    public void show(String text, Color fg, @Nullable Color bg) {
        setText(text);
        setForeground(fg);
        background = bg;
    }

    public void clear() {
        setText("");
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        String text = getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        Dimension size = getPreferredSize();
        int w = Math.min(size.width, getWidth());
        int h = Math.min(size.height, getHeight());
        int arc = JBUI.scale(ARC);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(background != null ? background : ColorUtil.withAlpha(getForeground(), TAG_ALPHA));
            g2.fill(new RoundRectangle2D.Float(0, (getHeight() - h) / 2f, w, h, arc, arc));
        }
        finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
