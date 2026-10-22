package ei.db.console;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;

/** 둥근 바탕에 작은 굵은 글을 그리는 꼬리표 아이콘(PK · FK · 운영 · 미반영 건수 등). 크기는 배율을 따른다. */
public final class Badge implements Icon {

  /** 칼럼 키 꼬리표 색. 테마가 EiDatabase.* 키로 바꿀 수 있다. */
  public static final Color PK = JBColor.namedColor("EiDatabase.pk", new JBColor(0xB86E00, 0xE0A64F));
  public static final Color PK_BACKGROUND = JBColor.namedColor("EiDatabase.pkBackground", new JBColor(0xFCEBCF, 0x4A3B22));
  public static final Color FK = JBColor.namedColor("EiDatabase.fk", new JBColor(0x2F63D3, 0x6F9DF7));
  public static final Color FK_BACKGROUND = JBColor.namedColor("EiDatabase.fkBackground", new JBColor(0xDCE7FC, 0x22334D));

  /** 높이 · 모서리 반지름 · 글 좌우 여백(px, 배율 전). */
  private static final int HEIGHT = 16;
  private static final int RADIUS = 4;
  private static final int PAD = 4;
  private static final FontRenderContext MEASURE = new FontRenderContext(null, true, true);

  private final String text;
  private final Color background;
  private final Color foreground;
  private final int minWidth;

  public Badge(String text, Color background, Color foreground) {
    this(text, background, foreground, 0);
  }

  /** minWidth(px, 배율 전)는 PK · FK 처럼 같은 칸에 줄 세울 꼬리표의 최소 폭. */
  public Badge(String text, Color background, Color foreground, int minWidth) {
    this.text = text;
    this.background = background;
    this.foreground = foreground;
    this.minWidth = minWidth;
  }

  public static Badge pk(int minWidth) {
    return new Badge("PK", PK_BACKGROUND, PK, minWidth);
  }

  public static Badge fk(int minWidth) {
    return new Badge("FK", FK_BACKGROUND, FK, minWidth);
  }

  private static Font font() {
    return JBUI.Fonts.smallFont().asBold();
  }

  @Override
  public void paintIcon(Component c, Graphics g, int x, int y) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      int w = getIconWidth();
      int h = getIconHeight();
      int arc = JBUI.scale(RADIUS) * 2;
      g2.setColor(background);
      g2.fillRoundRect(x, y, w, h, arc, arc);
      g2.setFont(font());
      FontMetrics fm = g2.getFontMetrics();
      g2.setColor(foreground);
      g2.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2);
    } finally {
      g2.dispose();
    }
  }

  @Override
  public int getIconWidth() {
    int textWidth = (int) Math.ceil(font().getStringBounds(text, MEASURE).getWidth());
    return Math.max(JBUI.scale(minWidth), textWidth + JBUI.scale(PAD) * 2);
  }

  @Override
  public int getIconHeight() {
    return JBUI.scale(HEIGHT);
  }
}
