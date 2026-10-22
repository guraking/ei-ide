package ei.db.gen;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.EiDbBundle;
import ei.db.Meta;

import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ERD 그림판. 좌표는 배율 1 기준 논리 좌표로 두고 그릴 때만 zoom 을 곱한다.
 * 외래키 선은 자식 → 부모로 꺾인 선(가로 · 세로)과 부모 쪽 화살촉. 같은 FK 이름의 여러 칼럼(복합키)은 선 하나로 그린다.
 */
final class ErdCanvas extends JComponent {

  static final class Card {
    final Meta.Table table;
    final List<Meta.Column> columns;
    int x;
    int y;
    int w;
    int h;

    Card(Meta.Table table, List<Meta.Column> columns) {
      this.table = table;
      this.columns = columns;
    }

    Rectangle bounds() { return new Rectangle(x, y, w, h); }
  }

  private static final int ROW = 20;
  private static final int HEAD = 28;
  private static final int PAD = 10;
  private static final int TAG_W = 24;
  private static final int GAP = 12;
  private static final int MAX_CARD_W = 340;
  private static final int GAP_X = 90;
  private static final int GAP_Y = 30;
  private static final int MARGIN = 30;
  private static final int MAX_PER_COLUMN = 8;
  private static final int DETOUR = 24;
  private static final int ARROW = 7;
  private static final double ZOOM_STEP = 1.1;
  private static final double ZOOM_MIN = 0.3;
  private static final double ZOOM_MAX = 2.0;

  private final List<Card> cards = new ArrayList<>();
  private final Map<String, Card> byName = new HashMap<>();
  private final List<Meta.ForeignKey> fks;
  private final Consumer<String> onOpenTable;
  private boolean keysOnly;
  private double zoom = 1.0;
  private Card selected;
  private Point dragOffset;
  private Point panStart;

  ErdCanvas(List<Card> cards, List<Meta.ForeignKey> fks, Consumer<String> onOpenTable) {
    this.cards.addAll(cards);
    for (Card c : cards) byName.put(c.table.name(), c);
    this.fks = fks;
    this.onOpenTable = onOpenTable;
    setToolTipText("");
    setOpaque(true);
    MouseAdapter mouse = new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) { press(e); }
      @Override public void mouseDragged(MouseEvent e) { drag(e); }
      @Override public void mouseReleased(MouseEvent e) { dragOffset = null; panStart = null; }
      @Override public void mouseClicked(MouseEvent e) {
        Card c = cardAt(logical(e.getPoint()));
        if (c != null && e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) onOpenTable.accept(c.table.name());
      }
      @Override public void mouseWheelMoved(MouseWheelEvent e) { zoomBy(e.getWheelRotation() < 0 ? ZOOM_STEP : 1 / ZOOM_STEP); }
    };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
    addMouseWheelListener(mouse);
    autoLayout();
  }

  // ---- 배치 ----

  List<Meta.Column> visible(Card c) {
    if (!keysOnly) return c.columns;
    List<Meta.Column> out = new ArrayList<>();
    for (Meta.Column col : c.columns) if (col.pk() || col.fk()) out.add(col);
    return out;
  }

  void setKeysOnly(boolean v) {
    keysOnly = v;
    autoLayout();
  }

  /** FK 깊이로 줄을 나눠 왼쪽(부모)부터 놓는다. 같은 줄은 위에서 아래로 쌓아 겹치지 않는다. */
  void autoLayout() {
    measure();
    List<String> names = new ArrayList<>();
    for (Card c : cards) names.add(c.table.name());
    List<String[]> edges = new ArrayList<>();
    for (Meta.ForeignKey fk : fks) edges.add(new String[] {fk.fkTable(), fk.pkTable()});
    int x = MARGIN;
    for (List<String> column : ErdLayout.columns(names, edges, MAX_PER_COLUMN)) {
      int y = MARGIN;
      int width = 0;
      for (String n : column) {
        Card c = byName.get(n);
        c.x = x;
        c.y = y;
        y += c.h + GAP_Y;
        width = Math.max(width, c.w);
      }
      x += width + GAP_X;
    }
    resize();
  }

  private void measure() {
    FontMetrics bold = getFontMetrics(boldFont());
    FontMetrics plain = getFontMetrics(plainFont());
    FontMetrics mono = getFontMetrics(monoFont());
    for (Card c : cards) {
      int head = PAD + bold.stringWidth(c.table.name()) + GAP + plain.stringWidth(c.table.comment()) + PAD;
      int rows = 0;
      for (Meta.Column col : visible(c)) {
        rows = Math.max(rows, PAD + TAG_W + mono.stringWidth(col.name()) + GAP + plain.stringWidth(col.comment()) + PAD);
      }
      c.w = Math.min(MAX_CARD_W, Math.max(head, rows));
      c.h = HEAD + Math.max(1, visible(c).size()) * ROW + PAD / 2;
    }
  }

  private void resize() {
    int w = 0;
    int h = 0;
    for (Card c : cards) {
      w = Math.max(w, c.x + c.w);
      h = Math.max(h, c.y + c.h);
    }
    setPreferredSize(new Dimension((int) ((w + MARGIN + DETOUR) * zoom), (int) ((h + MARGIN) * zoom)));
    revalidate();
    repaint();
  }

  void zoomBy(double factor) {
    zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * factor));
    resize();
  }

  // ---- 마우스 ----

  private Point logical(Point p) {
    return new Point((int) (p.x / zoom), (int) (p.y / zoom));
  }

  /** 맨 위(나중에 그린) 카드부터 찾는다. */
  private Card cardAt(Point p) {
    for (int i = cards.size() - 1; i >= 0; i--) if (cards.get(i).bounds().contains(p)) return cards.get(i);
    return null;
  }

  private void press(MouseEvent e) {
    Point p = logical(e.getPoint());
    selected = cardAt(p);
    if (selected != null) {
      cards.remove(selected);
      cards.add(selected);
      dragOffset = new Point(p.x - selected.x, p.y - selected.y);
    } else {
      panStart = e.getLocationOnScreen();
    }
    repaint();
  }

  private void drag(MouseEvent e) {
    if (selected != null && dragOffset != null) {
      Point p = logical(e.getPoint());
      selected.x = Math.max(0, p.x - dragOffset.x);
      selected.y = Math.max(0, p.y - dragOffset.y);
      resize();
    } else if (panStart != null && getParent() instanceof JViewport vp) {
      Point now = e.getLocationOnScreen();
      Point view = vp.getViewPosition();
      Rectangle r = new Rectangle(view.x - (now.x - panStart.x), view.y - (now.y - panStart.y), vp.getWidth(), vp.getHeight());
      scrollRectToVisible(r);
      panStart = now;
    }
  }

  @Override
  public String getToolTipText(MouseEvent e) {
    Point p = logical(e.getPoint());
    Card c = cardAt(p);
    if (c == null) return null;
    int row = (p.y - c.y - HEAD) / ROW;
    List<Meta.Column> cols = visible(c);
    if (p.y < c.y + HEAD || row < 0 || row >= cols.size()) return c.table.comment().isEmpty() ? c.table.name() : c.table.name() + " · " + c.table.comment();
    Meta.Column col = cols.get(row);
    return col.typeLabel() + (col.comment().isEmpty() ? "" : " · " + col.comment());
  }

  // ---- 그리기 ----

  private Font plainFont() { return UIUtil.getLabelFont(); }

  private Font boldFont() { return plainFont().deriveFont(Font.BOLD); }

  private Font monoFont() { return Ui.mono(); }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setColor(UIUtil.getPanelBackground());
      g2.fillRect(0, 0, getWidth(), getHeight());
      g2.scale(zoom, zoom);
      paintDiagram(g2);
    } finally {
      g2.dispose();
    }
  }

  private void paintDiagram(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    for (Map.Entry<String, List<Meta.ForeignKey>> e : groupedFks().entrySet()) paintFk(g, e.getValue());
    for (Card c : cards) paintCard(g, c);
  }

  /** FK 이름(없으면 자식 · 부모 · 칼럼)으로 묶는다. */
  private Map<String, List<Meta.ForeignKey>> groupedFks() {
    Map<String, List<Meta.ForeignKey>> out = new LinkedHashMap<>();
    for (Meta.ForeignKey fk : fks) {
      if (!byName.containsKey(fk.fkTable()) || !byName.containsKey(fk.pkTable())) continue;
      String key = fk.fkTable() + "|" + fk.pkTable() + "|" + (fk.name().isEmpty() ? fk.fkColumn() : fk.name());
      out.computeIfAbsent(key, k -> new ArrayList<>()).add(fk);
    }
    return out;
  }

  /** 칼럼 줄의 가운데 y. 키만 보기 등으로 안 보이면 머리 가운데. */
  private int rowY(Card c, String column) {
    List<Meta.Column> cols = visible(c);
    for (int i = 0; i < cols.size(); i++) if (cols.get(i).name().equals(column)) return c.y + HEAD + i * ROW + ROW / 2;
    return c.y + HEAD / 2;
  }

  private void paintFk(Graphics2D g, List<Meta.ForeignKey> group) {
    Meta.ForeignKey fk = group.get(0);
    Card child = byName.get(fk.fkTable());
    Card parent = byName.get(fk.pkTable());
    boolean hot = selected != null && (selected == child || selected == parent);
    g.setColor(hot ? JBUI.CurrentTheme.Focus.focusColor() : Ui.muted());
    g.setStroke(new BasicStroke(hot ? 2f : 1.2f));
    int sy = rowY(child, fk.fkColumn());
    int ey = rowY(parent, fk.pkColumn());
    int sx;
    int ex;
    int midX;
    if (parent.x + parent.w + DETOUR <= child.x) {
      sx = child.x;
      ex = parent.x + parent.w;
      midX = (sx + ex) / 2;
    } else if (child.x + child.w + DETOUR <= parent.x) {
      sx = child.x + child.w;
      ex = parent.x;
      midX = (sx + ex) / 2;
    } else {
      sx = child.x + child.w;
      ex = parent.x + parent.w;
      midX = Math.max(sx, ex) + DETOUR;
    }
    g.drawLine(sx, sy, midX, sy);
    g.drawLine(midX, sy, midX, ey);
    g.drawLine(midX, ey, ex, ey);
    int dir = ex < midX ? -1 : 1;
    Polygon head = new Polygon();
    head.addPoint(ex, ey);
    head.addPoint(ex - dir * ARROW, ey - ARROW / 2 - 1);
    head.addPoint(ex - dir * ARROW, ey + ARROW / 2 + 1);
    g.fillPolygon(head);
    g.setFont(plainFont().deriveFont(plainFont().getSize2D() - 2));
    String label = fk.fkColumn();
    int lw = g.getFontMetrics().stringWidth(label);
    int lx = sx < midX ? sx + 4 : sx - 4 - lw;
    g.drawString(label, lx, sy - 4);
  }

  private void paintCard(Graphics2D g, Card c) {
    Color border = c == selected ? JBUI.CurrentTheme.Focus.focusColor() : JBColor.border();
    g.setColor(UIUtil.getListBackground());
    g.fillRoundRect(c.x, c.y, c.w, c.h, 8, 8);
    g.setColor(UIUtil.getPanelBackground());
    g.fillRoundRect(c.x, c.y, c.w, HEAD, 8, 8);
    g.fillRect(c.x, c.y + HEAD - 8, c.w, 8);
    g.setColor(border);
    g.setStroke(new BasicStroke(c == selected ? 2f : 1f));
    g.drawRoundRect(c.x, c.y, c.w, c.h, 8, 8);
    g.setStroke(new BasicStroke(1f));
    g.setColor(JBColor.border());
    g.drawLine(c.x, c.y + HEAD, c.x + c.w, c.y + HEAD);

    java.awt.Shape oldClip = g.getClip();
    g.clipRect(c.x + 1, c.y + 1, c.w - 2, c.h - 2); // 폭은 MAX_CARD_W 까지만 늘어나므로 넘치는 글은 자른다
    g.setFont(boldFont());
    FontMetrics fm = g.getFontMetrics();
    int base = c.y + (HEAD + fm.getAscent() - fm.getDescent()) / 2;
    g.setColor(UIUtil.getLabelForeground());
    g.drawString(c.table.name(), c.x + PAD, base);
    int after = c.x + PAD + fm.stringWidth(c.table.name()) + GAP;
    g.setFont(plainFont());
    g.setColor(Ui.muted());
    g.drawString(c.table.comment(), after, base);

    int y = c.y + HEAD;
    List<Meta.Column> cols = visible(c);
    if (cols.isEmpty()) {
      g.drawString(EiDbBundle.message("erd.no.key.columns"), c.x + PAD, y + ROW - 6);
    }
    for (Meta.Column col : cols) {
      int b = y + ROW - 6;
      if (col.pk() || col.fk()) {
        g.setFont(plainFont().deriveFont(Font.BOLD, plainFont().getSize2D() - 3));
        g.setColor(col.pk() ? Ui.PK : Ui.FK);
        g.drawString(col.pk() ? "PK" : "FK", c.x + PAD, b);
      }
      g.setFont(monoFont());
      g.setColor(UIUtil.getLabelForeground());
      g.drawString(col.name(), c.x + PAD + TAG_W, b);
      int cx = c.x + PAD + TAG_W + g.getFontMetrics().stringWidth(col.name()) + GAP;
      g.setFont(plainFont());
      g.setColor(Ui.muted());
      g.drawString(col.comment(), cx, b);
      y += ROW;
    }
    g.setClip(oldClip);
  }

  /** 배율 1 로 전체를 그린 그림(PNG 저장용). */
  BufferedImage snapshot() {
    int w = 1;
    int h = 1;
    for (Card c : cards) {
      w = Math.max(w, c.x + c.w + MARGIN + DETOUR);
      h = Math.max(h, c.y + c.h + MARGIN);
    }
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setColor(UIUtil.getPanelBackground());
      g.fillRect(0, 0, w, h);
      paintDiagram(g);
    } finally {
      g.dispose();
    }
    return img;
  }
}
