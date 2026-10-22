package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import ei.db.DbKind;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** 접속 아이콘: DB 종류 아이콘 오른쪽 아래에 접속 상태 점. 점 둘레는 바탕색으로 비워 아이콘과 떨어져 보이게 한다. */
public final class KindIcon implements Icon {

  /** 점 지름 · 점 둘레 띠 두께(px, 배율 전). */
  private static final int DOT_SIZE = 6;
  private static final int DOT_RING = 1;

  private final Icon base;
  private final Color dot;
  private final Color ring;

  /** ring 은 점 둘레 띠 색(보통 줄 바탕색). null 이면 띠 없이 점만. */
  public KindIcon(DbKind kind, Color dot, Color ring) {
    this.base = iconOf(kind);
    this.dot = dot;
    this.ring = ring;
  }

  /** 종류별 아이콘. 종류가 없으면 스키마 아이콘. */
  public static Icon iconOf(DbKind kind) {
    if (kind == null) return AllIcons.Nodes.DataSchema;
    return switch (kind) {
      case ORACLE -> AllIcons.Providers.Oracle;
      case MYSQL -> AllIcons.Providers.Mysql;
      case POSTGRES -> AllIcons.Providers.Postgresql;
    };
  }

  @Override
  public void paintIcon(Component c, Graphics g, int x, int y) {
    base.paintIcon(c, g, x, y);
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int size = JBUI.scale(DOT_SIZE);
      int ringWidth = JBUI.scale(DOT_RING);
      int dx = x + getIconWidth() - size - ringWidth;
      int dy = y + getIconHeight() - size - ringWidth;
      if (ring != null) {
        g2.setColor(ring);
        g2.fillOval(dx - ringWidth, dy - ringWidth, size + ringWidth * 2, size + ringWidth * 2);
      }
      g2.setColor(dot);
      g2.fillOval(dx, dy, size, size);
    } finally {
      g2.dispose();
    }
  }

  @Override public int getIconWidth() { return base.getIconWidth(); }
  @Override public int getIconHeight() { return base.getIconHeight(); }
}
