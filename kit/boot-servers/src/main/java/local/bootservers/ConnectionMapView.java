package local.bootservers;

import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 서버 창 「연결 지도」 탭: 실행 중인 스프링 부트 서버와, 그 설정 파일에서 찾은 DB · Redis · 다른 서버 주소를 상자와 선으로 그린다.
 * 왼쪽에서 오른쪽으로: 다른 서버가 붙지 않는 서버 → 그 서버가 붙는 서버 → … → 바깥(점선 상자). 선은 붙으면 초록 실선, 끊기면 빨강 점선.
 * 선이나 아래 표의 줄을 두 번 누르면 그 주소가 적힌 설정 파일의 줄로 간다.
 */
final class ConnectionMapView {

    private static final int BOX_WIDTH = 200;
    private static final int BOX_HEIGHT = 54;
    private static final int COLUMN_GAP = 120;
    private static final int ROW_GAP = 24;
    private static final int MARGIN = 16;
    private static final int ARC = 8;
    private static final int DOT = 8;
    private static final int TEXT_PAD = 12;
    private static final float LINE_WIDTH = 2f;
    private static final float[] DASH = {6f, 4f};
    // 선 위 점까지의 거리가 이 안이면 그 선을 누른 것으로 친다(px, 배율 전)
    private static final int HIT_DISTANCE = 6;
    private static final int HIT_SAMPLES = 24;
    private static final long MILLIS_PER_MINUTE = 60_000;
    private static final Color UP = new JBColor(0x208A3C, 0x5FB865);

    /** 상자: 서버(server 가 서버 이름) 또는 바깥 주소(server 가 null). */
    private record Node(String id, String title, String subtitle, String server) {
    }

    private record Edge(ServerMonitor.Link link, Node from, Node to) {
    }

    private final Project project;
    private final ServerMonitor monitor;
    private final Function<String, Color> stateColor;
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final Canvas canvas = new Canvas();
    private final ComboBox<String> profile = new ComboBox<>();
    private final DefaultTableModel sourceModel = new DefaultTableModel(new Object[]{
            BootServersBundle.message("map.table.link"), BootServersBundle.message("map.table.key"),
            BootServersBundle.message("map.table.value"), BootServersBundle.message("map.table.state")}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JBTable sourceTable = new JBTable(sourceModel);
    private final JBPanelWithEmptyText empty = new JBPanelWithEmptyText();
    private List<Edge> edges = List.of();
    private final Map<String, Point> positions = new HashMap<>();
    private List<Node> nodes = List.of();
    private String selected;
    private boolean updatingProfiles;

    /** @param stateColor 서버 이름 → 상자 점 색(서버 창 상태 색) */
    ConnectionMapView(Project project, ServerMonitor monitor, Function<String, Color> stateColor) {
        this.project = project;
        this.monitor = monitor;
        this.stateColor = stateColor;
        profile.setRenderer(SimpleListCellRenderer.create(BootServersBundle.message("map.profile.server"), p -> p));
        profile.addActionListener(e -> {
            if (!updatingProfiles) {
                monitor.setProfileOverride((String) profile.getSelectedItem());
            }
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(UIUtil.DEFAULT_HGAP * 2), 0));
        bar.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, 0));
        JPanel profileBox = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        profileBox.add(new JBLabel(BootServersBundle.message("map.profile")));
        profileBox.add(profile);
        bar.add(profileBox);
        bar.add(legend(BootServersBundle.message("map.legend.up"), UP));
        bar.add(legend(BootServersBundle.message("map.legend.down"), JBUI.CurrentTheme.Label.errorForeground()));
        bar.add(grayLabel(BootServersBundle.message("map.legend.outside")));
        bar.add(grayLabel(BootServersBundle.message("map.legend.interval")));

        sourceTable.setShowGrid(false);
        sourceTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                ServerMonitor.LinkState s = row < edges.size() ? monitor.state(edges.get(row).link()) : ServerMonitor.LinkState.UNKNOWN;
                setForeground(!s.checked() ? NamedColorUtil.getInactiveTextColor() : s.up() ? UP : JBUI.CurrentTheme.Label.errorForeground());
                return this;
            }
        });
        sourceTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = sourceTable.getSelectedRow();
                if (e.getClickCount() == 2 && row >= 0 && row < edges.size()) {
                    open(edges.get(row).link());
                }
            }
        });
        JPanel tableBox = new JPanel(new BorderLayout());
        tableBox.setBorder(JBUI.Borders.customLine(JBColor.border()));
        tableBox.add(sourceTable.getTableHeader(), BorderLayout.NORTH);
        tableBox.add(sourceTable, BorderLayout.CENTER);

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(JBUI.Borders.empty(0, UIUtil.DEFAULT_HGAP, UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        JComponent canvasBox = ScrollPaneFactory.createScrollPane(canvas);
        for (JComponent c : new JComponent[]{bar, canvasBox, grayLabel(BootServersBundle.message("map.table.caption")), tableBox}) {
            c.setAlignmentX(0);
            column.add(c);
            column.add(Box.createVerticalStrut(JBUI.scale(UIUtil.DEFAULT_VGAP)));
        }
        JPanel top = new JPanel(new BorderLayout());
        top.add(column, BorderLayout.NORTH);
        root.add(ScrollPaneFactory.createScrollPane(top, true), "map");
        root.add(empty, "empty");
    }

    JComponent component() {
        return root;
    }

    private static JComponent legend(String text, Color color) {
        JBLabel label = new JBLabel(text);
        label.setIcon(new com.intellij.util.ui.ColorIcon(JBUI.scale(DOT), color));
        return label;
    }

    private static JBLabel grayLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(NamedColorUtil.getInactiveTextColor());
        return label;
    }

    /** monitor 의 지금 값으로 다시 그린다. selectedServer 는 굵은 테두리로 보일 서버(없으면 null). */
    void show(String selectedServer) {
        selected = selectedServer;
        updateProfiles();
        List<ServerMonitor.Watched> live = new ArrayList<>();
        for (ServerMonitor.Watched w : monitor.servers()) {
            if (w.live() && w.module() != null) {
                live.add(w);
            }
        }
        if (live.isEmpty()) {
            empty.getEmptyText().setText(BootServersBundle.message("map.empty"))
                    .appendLine(BootServersBundle.message("map.empty.hint"), SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
            cards.show(root, "empty");
            return;
        }
        cards.show(root, "map");
        build(live);
        canvas.revalidate();
        canvas.repaint();
        fillTable();
    }

    private void updateProfiles() {
        updatingProfiles = true;
        try {
            DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
            m.addElement(null);
            for (String p : monitor.profileNames()) {
                m.addElement(p);
            }
            if (!Objects.equals(listOf(m), listOf((DefaultComboBoxModel<String>) profile.getModel()))) {
                profile.setModel(m);
            }
            profile.setSelectedItem(monitor.profileOverride());
        }
        finally {
            updatingProfiles = false;
        }
    }

    private static List<String> listOf(DefaultComboBoxModel<String> m) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < m.getSize(); i++) {
            result.add(m.getElementAt(i));
        }
        return result;
    }

    /** 상자 · 선을 만들고 자리를 잡는다. 서버 열은 들어오는 선을 따라 깊이를 매기고(순환은 서버 수에서 멈춤), 바깥은 그것을 쓰는 서버의 바로 오른쪽 열. */
    private void build(List<ServerMonitor.Watched> live) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (ServerMonitor.Watched w : live) {
            byId.put(w.name(), new Node(w.name(), w.name(), w.port() == null ? "" : "localhost:" + w.port(), w.name()));
        }
        List<Edge> result = new ArrayList<>();
        for (ServerMonitor.Link link : monitor.links()) {
            Node from = byId.get(link.from());
            if (from == null) {
                continue;
            }
            Links.Target t = link.target();
            String id = link.to() != null ? link.to() : t.kind() + "|" + t.address().toLowerCase(Locale.ROOT);
            Node to = byId.computeIfAbsent(id, k -> link.to() != null
                    ? new Node(link.to(), link.to(), t.address(), link.to())
                    : new Node(k, t.title(), t.address(), null));
            result.add(new Edge(link, from, to));
        }
        edges = result;
        nodes = new ArrayList<>(byId.values());

        Map<String, Integer> depth = new HashMap<>();
        for (Node n : nodes) {
            if (n.server() != null) {
                depth.put(n.id(), 0);
            }
        }
        for (int round = 0; round < nodes.size(); round++) {
            for (Edge e : edges) {
                if (e.to().server() != null && depth.get(e.from().id()) + 1 > depth.get(e.to().id())) {
                    depth.put(e.to().id(), Math.min(depth.get(e.from().id()) + 1, nodes.size()));
                }
            }
        }
        Map<String, Integer> outsideColumn = new HashMap<>();
        for (Edge e : edges) {
            if (e.to().server() == null) {
                outsideColumn.merge(e.to().id(), depth.get(e.from().id()) + 1, Math::max);
            }
        }
        Map<Integer, Integer> rowsInColumn = new HashMap<>();
        positions.clear();
        for (Node n : nodes) {
            int col = n.server() != null ? depth.get(n.id()) : outsideColumn.get(n.id());
            int row = rowsInColumn.merge(col, 1, Integer::sum) - 1;
            positions.put(n.id(), new Point(MARGIN + col * (BOX_WIDTH + COLUMN_GAP), MARGIN + row * (BOX_HEIGHT + ROW_GAP)));
        }
    }

    private void fillTable() {
        sourceModel.setRowCount(0);
        for (Edge e : edges) {
            sourceModel.addRow(new Object[]{e.from().title() + " → " + e.to().title(), e.link().target().key(), e.link().target().value(), stateText(monitor.state(e.link()))});
        }
    }

    private static String stateText(ServerMonitor.LinkState s) {
        if (!s.checked()) {
            return BootServersBundle.message("map.state.checking");
        }
        if (s.up()) {
            return BootServersBundle.message("map.state.up", s.millis());
        }
        long minutes = (System.currentTimeMillis() - s.downSince()) / MILLIS_PER_MINUTE;
        return minutes < 1 ? BootServersBundle.message("map.state.down.now") : BootServersBundle.message("map.state.down", minutes);
    }

    private void open(ServerMonitor.Link link) {
        if (link.file() == null || !link.file().isValid()) {
            return;
        }
        WriteIntentReadAction.run(() -> new OpenFileDescriptor(project, link.file(), link.target().offset()).navigate(true));
    }

    /** 상자 · 선 그림. 크기는 배율을 곱한 자리에서 정한다. */
    private final class Canvas extends JComponent {

        Canvas() {
            setFont(JBUI.Fonts.label());
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Edge edge = edgeAt(e.getPoint());
                    if (edge != null && e.getClickCount() == 2) {
                        open(edge.link());
                    }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    Edge edge = edgeAt(e.getPoint());
                    setCursor(edge == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    setToolTipText(edge == null ? null : edge.link().target().key() + ": " + edge.link().target().value());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        public Dimension getPreferredSize() {
            int w = 0;
            int h = 0;
            for (Point p : positions.values()) {
                w = Math.max(w, p.x + BOX_WIDTH + MARGIN);
                h = Math.max(h, p.y + BOX_HEIGHT + MARGIN);
            }
            return new Dimension(JBUI.scale(w), JBUI.scale(h));
        }

        private CubicCurve2D curve(Edge e) {
            Point a = positions.get(e.from().id());
            Point b = positions.get(e.to().id());
            double x1 = JBUI.scale(a.x + BOX_WIDTH);
            double y1 = JBUI.scale(a.y + BOX_HEIGHT / 2);
            double x2 = JBUI.scale(b.x);
            double y2 = JBUI.scale(b.y + BOX_HEIGHT / 2);
            // 오른쪽에서 왼쪽으로 가는 선(같은 열 · 뒤 열의 서버)도 상자를 가로지르지 않게 손잡이를 바깥으로 뺀다.
            double handle = Math.max(Math.abs(x2 - x1) / 2, JBUI.scale(COLUMN_GAP) / 2.0);
            return new CubicCurve2D.Double(x1, y1, x1 + handle, y1, x2 - handle, y2, x2, y2);
        }

        private Edge edgeAt(Point p) {
            double limit = JBUI.scale(HIT_DISTANCE);
            for (Edge e : edges) {
                CubicCurve2D c = curve(e);
                for (int i = 0; i <= HIT_SAMPLES; i++) {
                    if (pointAt(c, (double) i / HIT_SAMPLES).distance(p) <= limit) {
                        return e;
                    }
                }
            }
            return null;
        }

        private static Point2D pointAt(CubicCurve2D c, double t) {
            double u = 1 - t;
            double x = u * u * u * c.getX1() + 3 * u * u * t * c.getCtrlX1() + 3 * u * t * t * c.getCtrlX2() + t * t * t * c.getX2();
            double y = u * u * u * c.getY1() + 3 * u * u * t * c.getCtrlY1() + 3 * u * t * t * c.getCtrlY2() + t * t * t * c.getY2();
            return new Point2D.Double(x, y);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIUtil.getPanelBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                Color error = JBUI.CurrentTheme.Label.errorForeground();
                Color gray = NamedColorUtil.getInactiveTextColor();
                g2.setFont(JBUI.Fonts.smallFont());
                for (Edge e : edges) {
                    ServerMonitor.LinkState s = monitor.state(e.link());
                    Color color = !s.checked() ? gray : s.up() ? UP : error;
                    g2.setColor(color);
                    g2.setStroke(s.checked() && !s.up()
                            ? new BasicStroke(JBUI.scale(LINE_WIDTH), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, scaled(DASH), 0f)
                            : new BasicStroke(JBUI.scale(LINE_WIDTH)));
                    CubicCurve2D c = curve(e);
                    g2.draw(c);
                    Point2D mid = pointAt(c, 0.5);
                    String label = stateText(s);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(label, (float) (mid.getX() - fm.stringWidth(label) / 2.0), (float) (mid.getY() - JBUI.scale(4)));
                }
                for (Node n : nodes) {
                    paintNode(g2, n);
                }
            }
            finally {
                g2.dispose();
            }
        }

        private void paintNode(Graphics2D g2, Node n) {
            Point p = positions.get(n.id());
            float x = JBUI.scale(p.x);
            float y = JBUI.scale(p.y);
            float w = JBUI.scale(BOX_WIDTH);
            float h = JBUI.scale(BOX_HEIGHT);
            RoundRectangle2D box = new RoundRectangle2D.Float(x, y, w, h, JBUI.scale(ARC), JBUI.scale(ARC));
            g2.setColor(UIUtil.getListBackground());
            g2.fill(box);
            boolean broken = false;
            for (Edge e : edges) {
                ServerMonitor.LinkState s = monitor.state(e.link());
                broken |= e.to() == n && s.checked() && !s.up();
            }
            Color border = broken ? JBUI.CurrentTheme.Label.errorForeground()
                    : n.id().equals(selected) ? JBUI.CurrentTheme.Focus.focusColor() : JBColor.border();
            g2.setColor(border);
            g2.setStroke(n.server() == null
                    ? new BasicStroke(JBUI.scale(1f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, scaled(DASH), 0f)
                    : new BasicStroke(JBUI.scale(n.id().equals(selected) ? LINE_WIDTH : 1f)));
            g2.draw(box);
            int pad = JBUI.scale(TEXT_PAD);
            float textX = x + pad;
            if (n.server() != null) {
                int dot = JBUI.scale(DOT);
                g2.setColor(stateColor.apply(n.server()));
                g2.fillOval((int) x + pad, (int) (y + h / 3 - dot / 2f), dot, dot);
                textX += dot + JBUI.scale(UIUtil.DEFAULT_HGAP / 2);
            }
            g2.setFont(JBUI.Fonts.label());
            g2.setColor(UIUtil.getLabelForeground());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(clip(n.title(), fm, (int) (x + w - pad - textX)), textX, y + h / 3 + fm.getAscent() / 2f - 1);
            g2.setFont(JBUI.Fonts.smallFont());
            g2.setColor(NamedColorUtil.getInactiveTextColor());
            fm = g2.getFontMetrics();
            g2.drawString(clip(n.subtitle(), fm, (int) (w - pad * 2)), x + pad, y + h * 2 / 3 + fm.getAscent() / 2f);
        }

        private static String clip(String text, FontMetrics fm, int width) {
            if (fm.stringWidth(text) <= width) {
                return text;
            }
            String more = "…";
            int end = text.length();
            while (end > 0 && fm.stringWidth(text.substring(0, end) + more) > width) {
                end--;
            }
            return text.substring(0, end) + more;
        }

        private static float[] scaled(float[] dash) {
            float[] result = new float[dash.length];
            for (int i = 0; i < dash.length; i++) {
                result[i] = JBUI.scale(dash[i]);
            }
            return result;
        }
    }
}
