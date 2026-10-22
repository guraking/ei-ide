package ei.db.gen;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;
import ei.db.EiDbBundle;
import ei.db.Meta;

import javax.swing.Icon;
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
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
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
  private static final int TRACK = 8;       // 같은 틈을 지나는 세로 선끼리의 간격
  private static final int CORNER = 6;      // 꺾이는 곳 둥근 반지름
  private static final int CLEARANCE = 4;   // 선과 남의 카드 사이 최소 여백
  private static final int LABEL_PAD = 4;   // 관계 이름표 상자 안 좌우 여백
  private static final int LABEL_ARC = 6;   // 관계 이름표 상자 모서리
  private static final int ARC = 8;         // 카드 모서리
  private static final int STRIPE = 3;      // 표 머리 위 색 띠 두께
  private static final int ICON_GAP = 6;    // 표 아이콘과 이름 사이
  private static final int TAG_PAD = 3;     // PK · FK 꼬리표 안 좌우 여백
  private static final int TAG_ARC = 6;     // PK · FK 꼬리표 모서리
  private static final int SMALL_STEP = 2;  // 작은 글(이름표 · 칼럼 수)이 기본 글보다 작은 크기
  private static final int TAG_STEP = 3;    // 꼬리표 글이 기본 글보다 작은 크기
  private static final int GRID = 20;       // 바탕 점 격자 간격
  private static final int GRID_DOT = 2;    // 바탕 점 크기
  private static final double GRID_ALPHA = 0.5;
  static final double ZOOM_STEP = 1.1;
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
    FontMetrics small = getFontMetrics(smallFont());
    for (Card c : cards) {
      int head = PAD + tableIcon().getIconWidth() + ICON_GAP + bold.stringWidth(c.table.name()) + GAP + plain.stringWidth(c.table.comment())
                 + GAP + small.stringWidth(String.valueOf(c.columns.size())) + PAD;
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
    setZoom(zoom * factor);
  }

  /** 배율(1 = 100%). ZOOM_MIN ~ ZOOM_MAX 로 자른다. */
  void setZoom(double value) {
    zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, value));
    resize();
  }

  double zoom() { return zoom; }

  int tableCount() { return cards.size(); }

  /** 그려지는 관계(선) 수. 복합키는 하나로 센다. */
  int relationCount() { return groupedFks().size(); }

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

  private Font smallFont() { return plainFont().deriveFont(plainFont().getSize2D() - SMALL_STEP); }

  private static Icon tableIcon() { return AllIcons.Nodes.DataTables; }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setColor(UIUtil.getPanelBackground());
      g2.fillRect(0, 0, getWidth(), getHeight());
      g2.scale(zoom, zoom);
      paintGrid(g2);
      paintDiagram(g2);
    } finally {
      g2.dispose();
    }
  }

  /** 화면에만 그리는 바탕 점 격자(PNG 저장에는 넣지 않는다). 보이는 곳만 그린다. */
  private void paintGrid(Graphics2D g) {
    Rectangle area = g.getClipBounds();
    if (area == null) area = new Rectangle(0, 0, (int) (getWidth() / zoom), (int) (getHeight() / zoom));
    g.setColor(ColorUtil.withAlpha(JBColor.border(), GRID_ALPHA));
    int startX = Math.floorDiv(area.x, GRID) * GRID;
    int startY = Math.floorDiv(area.y, GRID) * GRID;
    for (int y = startY; y <= area.y + area.height; y += GRID) {
      for (int x = startX; x <= area.x + area.width; x += GRID) g.fillRect(x, y, GRID_DOT, GRID_DOT);
    }
  }

  private void paintDiagram(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    Map<Integer, Integer> tracks = new HashMap<>();
    for (List<Meta.ForeignKey> group : groupedFks().values()) paintFk(g, group, tracks);
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

  private void paintFk(Graphics2D g, List<Meta.ForeignKey> group, Map<Integer, Integer> tracks) {
    Meta.ForeignKey fk = group.get(0);
    Card child = byName.get(fk.fkTable());
    Card parent = byName.get(fk.pkTable());
    boolean hot = selected != null && (selected == child || selected == parent);
    g.setColor(hot ? Ui.SELECTION : Ui.muted());
    g.setStroke(new BasicStroke(hot ? 2f : 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    List<Point> path = route(child, parent, rowY(child, fk.fkColumn()), rowY(parent, fk.pkColumn()), tracks);
    g.draw(rounded(path));

    Point end = path.get(path.size() - 1);
    Point before = path.get(path.size() - 2);
    int dir = end.x < before.x ? -1 : 1;
    Polygon head = new Polygon();
    head.addPoint(end.x, end.y);
    head.addPoint(end.x - dir * ARROW, end.y - ARROW / 2 - 1);
    head.addPoint(end.x - dir * ARROW, end.y + ARROW / 2 + 1);
    g.fillPolygon(head);

    List<String> fkCols = new ArrayList<>();
    List<String> pkCols = new ArrayList<>();
    for (Meta.ForeignKey f : group) {
      fkCols.add(f.fkColumn());
      pkCols.add(f.pkColumn());
    }
    // 칼럼 이름이 같으면 하나만, 다르면 「FK:PK」. 가운데 토막의 가운데에 작은 상자로 둔다.
    String label = fkCols.equals(pkCols) ? String.join(",", fkCols) : String.join(",", fkCols) + ":" + String.join(",", pkCols);
    g.setFont(smallFont());
    FontMetrics fm = g.getFontMetrics();
    int lw = fm.stringWidth(label);
    int k = (path.size() - 2) / 2;
    Point a = path.get(k);
    Point b = path.get(k + 1);
    int bw = lw + LABEL_PAD * 2;
    int bh = fm.getHeight();
    int bx = (a.x + b.x) / 2 - bw / 2;
    int by = (a.y + b.y) / 2 - bh / 2;
    g.setColor(UIUtil.getListBackground());
    g.fillRoundRect(bx, by, bw, bh, LABEL_ARC, LABEL_ARC);
    g.setStroke(new BasicStroke(1f));
    g.setColor(hot ? Ui.SELECTION : JBColor.border());
    g.drawRoundRect(bx, by, bw, bh, LABEL_ARC, LABEL_ARC);
    g.setColor(hot ? Ui.SELECTION : Ui.muted());
    g.drawString(label, bx + LABEL_PAD, by + fm.getAscent());
  }

  /**
   * 자식 FK 칼럼 줄에서 나가 부모 키 칼럼 줄로 들어가는 가로 · 세로 꺾은선(점 목록, 첫 점 자식 · 끝 점 부모).
   * 다른 카드를 지나지 않는 길을 고른다: 세 토막(가로 → 세로 → 가로)을 두 카드 사이 가운데 · 자식 쪽 틈 · 부모 쪽 틈 순서로 해 보고,
   * 모두 막히면 자식 쪽 틈 → 카드 사이 빈 가로 줄 → 부모 쪽 틈으로 도는 다섯 토막. 그래도 막히면 첫 세 토막을 그대로 쓴다.
   */
  private List<Point> route(Card child, Card parent, int sy, int ey, Map<Integer, Integer> tracks) {
    int sx;
    int ex;
    int sOut;
    int eOut;
    if (parent.x + parent.w + DETOUR <= child.x) {
      sx = child.x;
      ex = parent.x + parent.w;
      sOut = -1;
      eOut = 1;
    } else if (child.x + child.w + DETOUR <= parent.x) {
      sx = child.x + child.w;
      ex = parent.x;
      sOut = 1;
      eOut = -1;
    } else {
      sx = child.x + child.w;
      ex = parent.x + parent.w;
      sOut = 1;
      eOut = 1;
    }
    int nearChild = sx + sOut * GAP_X / 2;
    int nearParent = ex + eOut * GAP_X / 2;
    List<Integer> xs = sOut == eOut ? List.of(Math.max(sx, ex) + DETOUR) : List.of((sx + ex) / 2, nearChild, nearParent);
    for (int x : xs) {
      List<Point> p = List.of(new Point(sx, sy), new Point(x, sy), new Point(x, ey), new Point(ex, ey));
      if (clear(p, child, parent)) return shifted(p, tracks);
    }
    for (int y : channels(sy, ey)) {
      List<Point> p = List.of(new Point(sx, sy), new Point(nearChild, sy), new Point(nearChild, y),
                              new Point(nearParent, y), new Point(nearParent, ey), new Point(ex, ey));
      if (clear(p, child, parent)) return shifted(p, tracks);
    }
    int x = xs.get(0);
    return shifted(List.of(new Point(sx, sy), new Point(x, sy), new Point(x, ey), new Point(ex, ey)), tracks);
  }

  /** 꺾은선의 어느 토막도 다른 카드(첫 토막은 자식, 끝 토막은 부모 제외)와 CLEARANCE 안으로 겹치지 않으면 true. */
  private boolean clear(List<Point> path, Card child, Card parent) {
    int last = path.size() - 2;
    for (int i = 0; i <= last; i++) {
      Point a = path.get(i);
      Point b = path.get(i + 1);
      Rectangle seg = new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(a.x - b.x) + 1, Math.abs(a.y - b.y) + 1);
      seg.grow(CLEARANCE, CLEARANCE);
      for (Card c : cards) {
        if (i == 0 && c == child || i == last && c == parent) continue;
        if (seg.intersects(c.bounds())) return false;
      }
    }
    return true;
  }

  /** 다섯 토막 길의 가로 줄 후보: 카드 위 · 아래 틈을 sy · ey 가운데에 가까운 순으로, 맨 끝은 모든 카드 아래(늘 비어 있음). */
  private List<Integer> channels(int sy, int ey) {
    List<Integer> ys = new ArrayList<>();
    int bottom = 0;
    for (Card c : cards) {
      if (c.y >= GAP_Y) ys.add(c.y - GAP_Y / 2);
      ys.add(c.y + c.h + GAP_Y / 2);
      bottom = Math.max(bottom, c.y + c.h);
    }
    int mid = (sy + ey) / 2;
    ys.sort(Comparator.comparingInt(y -> Math.abs(y - mid)));
    ys.add(bottom + GAP_Y / 2);
    return ys;
  }

  /**
   * 가운데 세로 토막(점 1-2, 3-4)을 같은 x 를 먼저 쓴 선이 있으면 0, -TRACK, +TRACK, -2·TRACK … 순서로 비켜 겹치지 않게 한다.
   * 비키는 폭은 틈 절반을 넘지 않는다. tracks 는 한 번 그릴 때마다 새로 만든다(x → 지금까지 쓴 선 수).
   */
  private static List<Point> shifted(List<Point> path, Map<Integer, Integer> tracks) {
    List<Point> out = new ArrayList<>();
    for (Point p : path) out.add(new Point(p));
    int limit = GAP_X / 2 - TRACK;
    for (int i = 1; i + 2 < out.size(); i += 2) {
      int n = tracks.merge(out.get(i).x, 1, Integer::sum) - 1;
      int off = (n % 2 == 0 ? 1 : -1) * ((n + 1) / 2) * TRACK;
      off = Math.max(-limit, Math.min(limit, off));
      out.get(i).x += off;
      out.get(i + 1).x += off;
    }
    return out;
  }

  /** 꺾이는 곳을 CORNER 반지름으로 둥글린 선. 토막이 짧으면 반지름을 토막 절반까지 줄인다. */
  private static Path2D rounded(List<Point> pts) {
    Path2D path = new Path2D.Double();
    path.moveTo(pts.get(0).x, pts.get(0).y);
    for (int i = 1; i + 1 < pts.size(); i++) {
      Point a = pts.get(i - 1);
      Point p = pts.get(i);
      Point b = pts.get(i + 1);
      double d1 = a.distance(p);
      double d2 = p.distance(b);
      if (d1 == 0 || d2 == 0) {
        path.lineTo(p.x, p.y);
        continue;
      }
      double r = Math.min(CORNER, Math.min(d1, d2) / 2);
      path.lineTo(p.x + (a.x - p.x) * r / d1, p.y + (a.y - p.y) * r / d1);
      path.quadTo(p.x, p.y, p.x + (b.x - p.x) * r / d2, p.y + (b.y - p.y) * r / d2);
    }
    Point last = pts.get(pts.size() - 1);
    path.lineTo(last.x, last.y);
    return path;
  }

  private void paintCard(Graphics2D g, Card c) {
    boolean chosen = c == selected;
    Color border = chosen ? Ui.SELECTION : JBColor.border();
    g.setColor(UIUtil.getListBackground());
    g.fillRoundRect(c.x, c.y, c.w, c.h, ARC, ARC);
    g.setColor(chosen ? Ui.SELECTION_BG : UIUtil.getPanelBackground());
    g.fillRoundRect(c.x, c.y, c.w, HEAD, ARC, ARC);
    g.fillRect(c.x, c.y + HEAD - ARC, c.w, ARC);
    // 머리 위 색 띠: 이름 토막이 같은 표(TB_ORDER · TB_ORDER_ITEM)는 같은 색. 카드 둥근 모서리 안에서만 칠한다.
    Shape oldClip = g.getClip();
    g.clip(new RoundRectangle2D.Float(c.x, c.y, c.w, c.h, ARC, ARC));
    g.setColor(Ui.CATEGORY[ErdLayout.category(c.table.name(), Ui.CATEGORY.length)]);
    g.fillRect(c.x, c.y, c.w, STRIPE);
    g.setClip(oldClip);
    g.setColor(border);
    g.setStroke(new BasicStroke(chosen ? 2f : 1f));
    g.drawRoundRect(c.x, c.y, c.w, c.h, ARC, ARC);
    g.setStroke(new BasicStroke(1f));
    g.setColor(JBColor.border());
    g.drawLine(c.x, c.y + HEAD, c.x + c.w, c.y + HEAD);

    g.clipRect(c.x + 1, c.y + 1, c.w - 2, c.h - 2); // 폭은 MAX_CARD_W 까지만 늘어나므로 넘치는 글은 자른다
    Icon icon = tableIcon();
    icon.paintIcon(this, g, c.x + PAD, c.y + (HEAD - icon.getIconHeight()) / 2);
    int nameX = c.x + PAD + icon.getIconWidth() + ICON_GAP;
    g.setFont(boldFont());
    FontMetrics fm = g.getFontMetrics();
    int base = c.y + (HEAD + fm.getAscent() - fm.getDescent()) / 2;
    g.setColor(UIUtil.getLabelForeground());
    g.drawString(c.table.name(), nameX, base);
    int after = nameX + fm.stringWidth(c.table.name()) + GAP;
    g.setFont(plainFont());
    g.setColor(Ui.muted());
    g.drawString(c.table.comment(), after, base);
    String count = String.valueOf(c.columns.size());
    g.setFont(smallFont());
    g.drawString(count, c.x + c.w - PAD - g.getFontMetrics().stringWidth(count), base);

    int y = c.y + HEAD;
    List<Meta.Column> cols = visible(c);
    if (cols.isEmpty()) {
      g.drawString(EiDbBundle.message("erd.no.key.columns"), c.x + PAD, y + ROW - 6);
    }
    for (Meta.Column col : cols) {
      int b = y + ROW - 6;
      if (col.pk() || col.fk()) {
        g.setFont(plainFont().deriveFont(Font.BOLD, plainFont().getSize2D() - TAG_STEP));
        FontMetrics tm = g.getFontMetrics();
        String tag = col.pk() ? "PK" : "FK";
        int tw = tm.stringWidth(tag) + TAG_PAD * 2;
        int th = tm.getHeight();
        int ty = y + (ROW - th) / 2;
        g.setColor(col.pk() ? Ui.PK_BG : Ui.FK_BG);
        g.fillRoundRect(c.x + PAD, ty, tw, th, TAG_ARC, TAG_ARC);
        g.setColor(col.pk() ? Ui.PK : Ui.FK);
        g.drawString(tag, c.x + PAD + TAG_PAD, ty + tm.getAscent());
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
