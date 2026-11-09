package local.bootservers;

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
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
import local.bootservers.ui.Gauge;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 서버 창 「연결 지도」 탭: 실행 중인 스프링 부트 서버와, 그 설정 파일에서 찾은 DB · Redis · 다른 서버 주소를 상자와 선으로 그린다.
 * 왼쪽에서 오른쪽으로: 다른 서버가 붙지 않는 서버 → 그 서버가 붙는 서버 → …, 바깥(점선 상자)은 그 주소를 쓰는 서버 바로 오른쪽. 선은 붙으면 초록 실선, 끊기면 빨강 점선.
 * 둘째 열부터는 상자를 데이터베이스 · 회사 서버 · 내 PC 구역으로 묶고 구역마다 제목을 단다.
 * 선은 직각으로 꺾인다: 출발 상자 오른쪽 변의 연결마다 다른 높이에서 나가, 연결마다 다른 세로 길로 내려가고, 대상 상자 왼쪽으로 들어간다.
 * ms · 「끊김」 글은 대상 상자 바로 왼쪽, 들어가는 선 위에 둔다. 상자 폭은 그 열에서 가장 긴 글에 맞춘다.
 * 선이나 아래 표의 줄을 두 번 누르면 그 주소가 적힌 설정 파일의 줄로 간다.
 */
final class ConnectionMapView {

    // 상자 폭의 아랫값. 글이 길면 그 열의 상자가 다 같이 넓어진다.
    private static final int MIN_BOX_WIDTH = 160;
    private static final int BOX_HEIGHT = 54;
    // 나란한 선 사이 간격(세로 길 사이 · 출발 높이 사이)
    private static final int LANE_GAP = 10;
    private static final int ROW_GAP = 24;
    private static final int SECTION_GAP = 20;
    private static final int TITLE_GAP = 6;
    private static final int MARGIN = 16;
    private static final int ARC = 8;
    private static final int DOT = 8;
    private static final int TEXT_PAD = 12;
    private static final int LABEL_PAD = 3;
    private static final float LINE_WIDTH = 2f;
    private static final float[] DASH = {6f, 4f};
    // 선까지의 거리가 이 안이면 그 선을 누른 것으로 친다(px, 배율 전)
    private static final int HIT_DISTANCE = 6;
    private static final long MILLIS_PER_MINUTE = 60_000;
    private static final Color UP = new JBColor(0x208A3C, 0x5FB865);
    // 표에서 같은 줄인지 가릴 때 보는 앞쪽 칸 수(연결 · 설정 · 값)
    private static final int ROW_KEY_COLUMNS = 3;
    private static final DateTimeFormatter CHECKED_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 상자 구역. 나열 순서가 위에서 아래 순서다. */
    private enum Group {
        DATABASE("map.group.database"), REMOTE("map.group.remote"), LOCAL("map.group.local");

        final String key;

        Group(String key) {
            this.key = key;
        }
    }

    /** 상자: 서버(server 가 서버 이름) 또는 바깥 주소(server 가 null). */
    private record Node(String id, String title, String subtitle, String server, Group group) {
    }

    private record Edge(ServerMonitor.Link link, Node from, Node to) {
    }

    /** 구역 제목. bounds 는 제목 글 자리(배율 곱한 px), 밑줄은 그 아래. */
    private record Section(String title, Rectangle bounds) {
    }

    /** 선 하나의 꺾인 점들(배율 곱한 px). 마지막 점이 대상 상자 변, back 이면 대상 상자 오른쪽 변으로 들어간다. */
    private record Route(List<Point> points, boolean back) {
        Point end() {
            return points.get(points.size() - 1);
        }
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
    private final JButton query = new JButton(BootServersBundle.message("map.query"));
    private final JBLabel checkedAt = grayLabel(BootServersBundle.message("map.query.none"));
    private final Gauge gauge = Gauge.create(BootServersBundle.message("map.query.running"));
    private List<Edge> edges = List.of();
    private List<Route> routes = List.of();
    private final Map<String, Rectangle> boxes = new HashMap<>();
    private final List<Section> sections = new ArrayList<>();
    private List<Node> nodes = List.of();
    private String selected;
    // 지금 실행 중인 스프링 부트 서버 이름. 멈춘 서버로 가는 선은 끊긴 것으로 그린다.
    private Set<String> running = Set.of();
    private boolean updatingProfiles;
    // 프로필을 바꾼 뒤 아직 「조회」를 누르지 않았으면 true
    private boolean profileChanged;

    /** @param stateColor 서버 이름 → 상자 점 색(서버 창 상태 색) */
    ConnectionMapView(Project project, ServerMonitor monitor, Function<String, Color> stateColor) {
        this.project = project;
        this.monitor = monitor;
        this.stateColor = stateColor;
        profile.setRenderer(SimpleListCellRenderer.create(BootServersBundle.message("map.profile.server"), p -> p));
        profile.addActionListener(e -> {
            if (!updatingProfiles) {
                monitor.setProfileOverride((String) profile.getSelectedItem());
                profileChanged = true;
                updateQuery();
            }
        });
        query.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        query.addActionListener(e -> {
            profileChanged = false;
            monitor.refreshLinks();
            updateQuery();
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(UIUtil.DEFAULT_HGAP * 2), 0));
        bar.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, 0));
        bar.add(query);
        bar.add(gauge);
        bar.add(checkedAt);
        // 프로필 목록 상자는 제 줄 폭을 다 채운다.
        JPanel profileBox = new JPanel(new BorderLayout(JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        profileBox.add(new JBLabel(BootServersBundle.message("map.profile")), BorderLayout.WEST);
        profileBox.add(profile, BorderLayout.CENTER);
        bar.add(legend(BootServersBundle.message("map.legend.up"), UP));
        bar.add(legend(BootServersBundle.message("map.legend.down"), JBUI.CurrentTheme.Label.errorForeground()));
        bar.add(grayLabel(BootServersBundle.message("map.legend.outside")));

        sourceTable.setShowGrid(false);
        sourceTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                ServerMonitor.LinkState s = row < edges.size() ? state(edges.get(row)) : ServerMonitor.LinkState.UNKNOWN;
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
        for (JComponent c : new JComponent[]{bar, profileBox, canvasBox, grayLabel(BootServersBundle.message("map.table.caption")), tableBox}) {
            c.setAlignmentX(0);
            column.add(c);
            column.add(Box.createVerticalStrut(JBUI.scale(UIUtil.DEFAULT_VGAP)));
        }
        JPanel top = new JPanel(new BorderLayout());
        top.add(column, BorderLayout.NORTH);
        JPanel mapCard = new JPanel(new BorderLayout());
        mapCard.add(gauge.line(), BorderLayout.NORTH);
        mapCard.add(ScrollPaneFactory.createScrollPane(top, true), BorderLayout.CENTER);
        root.add(mapCard, "map");
        root.add(empty, "empty");
    }

    /** 「조회」 단추 · 진행 표시 · 마지막 조회 시각을 monitor 의 지금 상태에 맞춘다. 조회 중에는 단추를 잠근다. 프로필을 바꾼 뒤 조회 전이면 조회 안내를 보인다. */
    private void updateQuery() {
        boolean pending = monitor.linksPending();
        query.setEnabled(!pending);
        gauge.setVisible(pending);
        long at = monitor.linksCheckedAt();
        checkedAt.setVisible(!pending);
        checkedAt.setText(at == 0 || profileChanged ? BootServersBundle.message("map.query.none")
                : BootServersBundle.message("map.query.last", CHECKED_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))));
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
        updateQuery();
        List<ServerMonitor.Watched> live = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ServerMonitor.Watched w : monitor.servers()) {
            if (w.live() && w.module() != null) {
                live.add(w);
                names.add(w.name());
            }
        }
        running = names;
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
            // 고른 프로필이 설정에서 사라져도 연결은 그 프로필로 찾으므로 목록에 남겨 보인다.
            String override = monitor.profileOverride();
            if (override != null && !monitor.profileNames().contains(override)) {
                m.addElement(override);
            }
            if (!Objects.equals(listOf(m), listOf((DefaultComboBoxModel<String>) profile.getModel()))) {
                profile.setModel(m);
            }
            profile.setSelectedItem(override);
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

    /** 상자 · 선을 만들고 자리를 잡는다. 서버 열은 들어오는 선을 따라 깊이를 매기고(순환을 이루는 선은 세지 않음, MapColumns), 바깥은 쓰는 서버 다음 열. */
    private void build(List<ServerMonitor.Watched> live) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (ServerMonitor.Watched w : live) {
            byId.put(w.name(), new Node(w.name(), w.name(), w.port() == null ? "" : "localhost:" + w.port(), w.name(), Group.LOCAL));
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
                    ? new Node(link.to(), link.to(), t.address(), link.to(), Group.LOCAL)
                    : new Node(k, t.title(), t.address(), null, groupOf(t)));
            result.add(new Edge(link, from, to));
        }
        edges = result;
        nodes = new ArrayList<>(byId.values());

        List<String> servers = new ArrayList<>();
        for (Node n : nodes) {
            if (n.server() != null) {
                servers.add(n.id());
            }
        }
        List<String[]> serverEdges = new ArrayList<>();
        for (Edge e : edges) {
            if (e.to().server() != null) {
                serverEdges.add(new String[]{e.from().id(), e.to().id()});
            }
        }
        Map<String, Integer> depth = MapColumns.depths(servers, serverEdges);
        // 바깥 상자는 그 주소를 쓰는 서버 바로 오른쪽 열(여럿이 쓰면 가장 오른쪽 서버 다음). 선이 다른 상자 뒤를 지나지 않는다.
        for (Edge e : edges) {
            if (e.to().server() == null) {
                depth.merge(e.to().id(), depth.get(e.from().id()) + 1, Math::max);
            }
        }
        layout(depth);
    }

    private static Group groupOf(Links.Target t) {
        if (t.kind() != Links.Kind.HTTP) {
            return Group.DATABASE;
        }
        return ServerMonitor.LOCAL_HOSTS.contains(t.host().toLowerCase(Locale.ROOT)) ? Group.LOCAL : Group.REMOTE;
    }

    /**
     * 상자 · 구역 제목 · 선 자리를 배율 곱한 px 로 정한다.
     * 열 폭은 그 열에서 가장 긴 글, 열 사이 폭은 그 사이를 지나는 세로 길 수와 대상 상자 왼쪽에 둘 상태 글의 가장 긴 폭으로 정한다.
     * 한 상자의 선은 대상이 위에 있는 것부터 위에서 출발하고, 아래로 가는 선은 먼 대상일수록 왼쪽 세로 길을, 위로 가는 선도 먼 대상일수록 왼쪽 세로 길을 쓴다.
     * 이 순서면 같은 상자에서 나간 선끼리는 엇갈리지 않는다.
     * 열을 건너뛰는 선(가운데 열에 상자가 있는 선)은 모든 상자 아래 통로로 돌아, 대상 바로 앞 열 사이의 세로 길로 올라간다.
     */
    private void layout(Map<String, Integer> depth) {
        FontMetrics titleFm = canvas.getFontMetrics(JBUI.Fonts.label());
        FontMetrics smallFm = canvas.getFontMetrics(JBUI.Fonts.smallFont());
        TreeMap<Integer, List<Node>> columns = new TreeMap<>();
        for (Node n : nodes) {
            columns.computeIfAbsent(depth.get(n.id()), k -> new ArrayList<>()).add(n);
        }
        Map<String, List<Integer>> out = new HashMap<>();
        Map<String, List<Integer>> in = new HashMap<>();
        Map<Integer, Integer> lanes = new HashMap<>();
        Map<Integer, Integer> labels = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            out.computeIfAbsent(e.from().id(), k -> new ArrayList<>()).add(i);
            in.computeIfAbsent(e.to().id(), k -> new ArrayList<>()).add(i);
            lanes.merge(depth.get(e.from().id()), 1, Integer::sum);
            if (depth.get(e.to().id()) > depth.get(e.from().id()) + 1) {
                lanes.merge(columns.floorKey(depth.get(e.to().id()) - 1), 1, Integer::sum);
            }
            labels.merge(depth.get(e.to().id()), smallFm.stringWidth(stateText(state(e))), Math::max);
        }
        int labelLine = smallFm.getHeight() + JBUI.scale(LABEL_PAD);
        int pad = JBUI.scale(TEXT_PAD);
        int dotSpace = JBUI.scale(DOT) + JBUI.scale(UIUtil.DEFAULT_HGAP / 2);

        boxes.clear();
        sections.clear();
        int x = JBUI.scale(MARGIN);
        TreeMap<Integer, Integer> right = new TreeMap<>();
        for (Map.Entry<Integer, List<Node>> column : columns.entrySet()) {
            int col = column.getKey();
            List<Node> list = column.getValue();
            boolean grouped = col > 0;
            if (grouped) {
                list.sort(Comparator.comparing(Node::group));
            }
            Map<Group, Integer> counts = new HashMap<>();
            int width = JBUI.scale(MIN_BOX_WIDTH);
            for (Node n : list) {
                counts.merge(n.group(), 1, Integer::sum);
                int title = titleFm.stringWidth(n.title()) + (n.server() != null ? dotSpace : 0);
                width = Math.max(width, Math.max(title, smallFm.stringWidth(n.subtitle())) + pad * 2);
            }
            int y = JBUI.scale(MARGIN);
            Group current = null;
            for (Node n : list) {
                if (grouped && n.group() != current) {
                    if (current != null) {
                        y += JBUI.scale(SECTION_GAP);
                    }
                    current = n.group();
                    String title = BootServersBundle.message(current.key, counts.get(current));
                    width = Math.max(width, titleFm.stringWidth(title));
                    sections.add(new Section(title, new Rectangle(x, y, 0, titleFm.getHeight())));
                    y += titleFm.getHeight() + JBUI.scale(TITLE_GAP) * 2;
                }
                int lines = Math.max(out.getOrDefault(n.id(), List.of()).size() * JBUI.scale(LANE_GAP),
                        in.getOrDefault(n.id(), List.of()).size() * labelLine);
                int h = Math.max(JBUI.scale(BOX_HEIGHT), lines + labelLine);
                boxes.put(n.id(), new Rectangle(x, y, 0, h));
                y += h + JBUI.scale(ROW_GAP);
            }
            for (Node n : list) {
                boxes.get(n.id()).width = width;
            }
            for (Section s : sections) {
                if (s.bounds().x == x) {
                    s.bounds().width = width;
                }
            }
            right.put(col, x + width);
            int nextLabels = labels.getOrDefault(col + 1, 0);
            x += width + (lanes.getOrDefault(col, 0) + 1) * JBUI.scale(LANE_GAP)
                    + (nextLabels > 0 ? nextLabels + JBUI.scale(LABEL_PAD) * 2 : 0);
        }

        // 아래 통로로 도는 선은 출발 상자 맨 아래에서 나가 맨 왼쪽 세로 길을 쓰고, 대상 상자에는 맨 아래로 들어간다.
        boolean[] skip = new boolean[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            skip[i] = depth.get(edges.get(i).to().id()) > depth.get(edges.get(i).from().id()) + 1;
        }
        int[] y1 = new int[edges.size()];
        int[] y2 = new int[edges.size()];
        for (Map.Entry<String, List<Integer>> e : out.entrySet()) {
            List<Integer> list = e.getValue();
            list.sort(Comparator.comparingInt(i -> skip[i] ? Integer.MAX_VALUE : boxes.get(edges.get(i).to().id()).y));
            spread(boxes.get(e.getKey()), list, y1);
        }
        for (Map.Entry<String, List<Integer>> e : in.entrySet()) {
            List<Integer> list = e.getValue();
            list.sort(Comparator.comparingInt(i -> skip[i] ? Integer.MAX_VALUE : y1[i]));
            spread(boxes.get(e.getKey()), list, y2);
        }
        int[] lane = new int[edges.size()];
        Map<Integer, Integer> used = new HashMap<>();
        for (List<Node> list : columns.values()) {
            for (Node n : list) {
                List<Integer> mine = new ArrayList<>(out.getOrDefault(n.id(), List.of()));
                // 아래 통로로 도는 선 → 아래로 가는 선(먼 것부터) → 위로 가는 선(먼 것부터)
                mine.sort(Comparator.comparingInt((Integer i) -> skip[i] ? 0 : y2[i] >= y1[i] ? 1 : 2)
                        .thenComparingInt(i -> -Math.abs(y2[i] - y1[i])));
                for (int i : mine) {
                    lane[i] = used.merge(depth.get(n.id()), 1, Integer::sum);
                }
            }
        }
        int bottom = 0;
        for (Rectangle r : boxes.values()) {
            bottom = Math.max(bottom, r.y + r.height);
        }
        int corridor = 0;
        List<Route> result = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            int fromCol = depth.get(edges.get(i).from().id());
            int toCol = depth.get(edges.get(i).to().id());
            Rectangle a = boxes.get(edges.get(i).from().id());
            Rectangle b = boxes.get(edges.get(i).to().id());
            int laneX = right.get(fromCol) + lane[i] * JBUI.scale(LANE_GAP);
            boolean back = b.x <= a.x;
            int endX = back ? b.x + b.width : b.x;
            List<Point> points = new ArrayList<>();
            points.add(new Point(a.x + a.width, y1[i]));
            points.add(new Point(laneX, y1[i]));
            if (skip[i]) {
                corridor++;
                int corridorY = bottom + JBUI.scale(ROW_GAP) / 2 + corridor * JBUI.scale(LANE_GAP);
                // 대상 바로 앞 열이 비어 있으면 그 왼쪽에서 가장 가까운 열 사이로 올라간다.
                int laneCol = right.floorKey(toCol - 1);
                int lastX = right.get(laneCol) + used.merge(laneCol, 1, Integer::sum) * JBUI.scale(LANE_GAP);
                points.add(new Point(laneX, corridorY));
                points.add(new Point(lastX, corridorY));
                laneX = lastX;
            }
            points.add(new Point(laneX, y2[i]));
            points.add(new Point(endX, y2[i]));
            result.add(new Route(points, back));
        }
        routes = result;
    }

    /** 상자 오른쪽(또는 왼쪽) 변에 선들을 고르게 나눠 놓는다. 위에서부터 list 순서. */
    private static void spread(Rectangle box, List<Integer> list, int[] ys) {
        for (int k = 0; k < list.size(); k++) {
            ys[list.get(k)] = box.y + (int) ((long) box.height * (k + 1) / (list.size() + 1));
        }
    }

    /** 표를 선 목록에 맞춘다. 줄 수가 같으면 바뀐 칸만 고쳐 고른 줄 · 스크롤을 그대로 두고, 줄 수가 바뀌면 다시 채운 뒤 같은 연결 · 설정 줄을 다시 고른다. */
    private void fillTable() {
        List<Object[]> rows = new ArrayList<>();
        for (Edge e : edges) {
            rows.add(new Object[]{e.from().title() + " → " + e.to().title(), e.link().target().key(), e.link().target().value(), stateText(state(e))});
        }
        if (rows.size() == sourceModel.getRowCount()) {
            for (int r = 0; r < rows.size(); r++) {
                for (int c = 0; c < rows.get(r).length; c++) {
                    if (!Objects.equals(rows.get(r)[c], sourceModel.getValueAt(r, c))) {
                        sourceModel.setValueAt(rows.get(r)[c], r, c);
                    }
                }
            }
            return;
        }
        int before = sourceTable.getSelectedRow();
        List<Object> picked = new ArrayList<>();
        for (int c = 0; before >= 0 && c < ROW_KEY_COLUMNS; c++) {
            picked.add(sourceModel.getValueAt(before, c));
        }
        sourceModel.setRowCount(0);
        for (Object[] row : rows) {
            sourceModel.addRow(row);
        }
        if (picked.isEmpty()) {
            return;
        }
        for (int r = 0; r < rows.size(); r++) {
            if (picked.equals(Arrays.asList(rows.get(r)).subList(0, ROW_KEY_COLUMNS))) {
                sourceTable.setRowSelectionInterval(r, r);
                return;
            }
        }
    }

    /** 선의 상태. 대상이 우리 서버인데 지금 실행 중이 아니면 마지막 조회 값과 상관없이 끊긴 것으로 본다. */
    private ServerMonitor.LinkState state(Edge e) {
        ServerMonitor.LinkState s = monitor.state(e.link());
        if (e.to().server() == null || running.contains(e.to().server()) || s.checked() && !s.up()) {
            return s;
        }
        return new ServerMonitor.LinkState(true, false, 0, System.currentTimeMillis());
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
            int margin = JBUI.scale(MARGIN);
            for (Rectangle r : boxes.values()) {
                w = Math.max(w, r.x + r.width + margin);
                h = Math.max(h, r.y + r.height + margin);
            }
            for (Route r : routes) {
                for (Point p : r.points()) {
                    h = Math.max(h, p.y + margin);
                }
            }
            return new Dimension(w, h);
        }

        private Edge edgeAt(Point p) {
            double limit = JBUI.scale(HIT_DISTANCE);
            for (int i = 0; i < routes.size(); i++) {
                List<Point> pts = routes.get(i).points();
                for (int k = 1; k < pts.size(); k++) {
                    if (Line2D.ptSegDist(pts.get(k - 1).x, pts.get(k - 1).y, pts.get(k).x, pts.get(k).y, p.x, p.y) <= limit) {
                        return edges.get(i);
                    }
                }
            }
            return null;
        }

        private static Path2D path(Route r) {
            Path2D.Double path = new Path2D.Double();
            path.moveTo(r.points().get(0).x, r.points().get(0).y);
            for (int k = 1; k < r.points().size(); k++) {
                path.lineTo(r.points().get(k).x, r.points().get(k).y);
            }
            return path;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIUtil.getPanelBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                for (Section s : sections) {
                    paintSection(g2, s);
                }
                for (int i = 0; i < edges.size(); i++) {
                    ServerMonitor.LinkState s = state(edges.get(i));
                    g2.setColor(lineColor(s));
                    g2.setStroke(s.checked() && !s.up()
                            ? new BasicStroke(JBUI.scale(LINE_WIDTH), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, scaled(DASH), 0f)
                            : new BasicStroke(JBUI.scale(LINE_WIDTH), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                    g2.draw(path(routes.get(i)));
                }
                // 글자는 선을 다 그린 뒤 그린다. 대상 상자 바로 왼쪽(뒤로 가는 선은 오른쪽), 들어가는 선 바로 위.
                g2.setFont(JBUI.Fonts.smallFont());
                FontMetrics fm = g2.getFontMetrics();
                int labelPad = JBUI.scale(LABEL_PAD);
                for (int i = 0; i < edges.size(); i++) {
                    ServerMonitor.LinkState s = state(edges.get(i));
                    Route r = routes.get(i);
                    String label = stateText(s);
                    int labelWidth = fm.stringWidth(label);
                    int labelX = r.back() ? r.end().x + labelPad * 2 : r.end().x - labelPad * 2 - labelWidth;
                    int labelTop = r.end().y - labelPad - fm.getHeight();
                    g2.setColor(UIUtil.getPanelBackground());
                    g2.fillRect(labelX - labelPad, labelTop, labelWidth + labelPad * 2, fm.getHeight());
                    g2.setColor(lineColor(s));
                    g2.drawString(label, labelX, labelTop + fm.getAscent());
                }
                for (Node n : nodes) {
                    paintNode(g2, n);
                }
            }
            finally {
                g2.dispose();
            }
        }

        /** 구역 제목(흐린 글) 과 그 아래 열 폭만큼의 가는 선. */
        private void paintSection(Graphics2D g2, Section s) {
            Rectangle b = s.bounds();
            g2.setFont(JBUI.Fonts.label());
            g2.setColor(NamedColorUtil.getInactiveTextColor());
            g2.drawString(s.title(), b.x, b.y + g2.getFontMetrics().getAscent());
            g2.setColor(JBColor.border());
            g2.setStroke(new BasicStroke(JBUI.scale(1f)));
            int lineY = b.y + b.height + JBUI.scale(TITLE_GAP);
            g2.drawLine(b.x, lineY, b.x + b.width, lineY);
        }

        private static Color lineColor(ServerMonitor.LinkState s) {
            return !s.checked() ? NamedColorUtil.getInactiveTextColor() : s.up() ? UP : JBUI.CurrentTheme.Label.errorForeground();
        }

        private void paintNode(Graphics2D g2, Node n) {
            Rectangle r = boxes.get(n.id());
            float x = r.x;
            float y = r.y;
            float w = r.width;
            float h = r.height;
            RoundRectangle2D box = new RoundRectangle2D.Float(x, y, w, h, JBUI.scale(ARC), JBUI.scale(ARC));
            g2.setColor(UIUtil.getListBackground());
            g2.fill(box);
            boolean broken = false;
            for (Edge e : edges) {
                ServerMonitor.LinkState s = state(e);
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
            // 선이 많아 상자가 길어져도 글은 위쪽 기본 높이 안에 둔다.
            float th = JBUI.scale(BOX_HEIGHT);
            float textX = x + pad;
            if (n.server() != null) {
                int dot = JBUI.scale(DOT);
                g2.setColor(stateColor.apply(n.server()));
                g2.fillOval((int) x + pad, (int) (y + th / 3 - dot / 2f), dot, dot);
                textX += dot + JBUI.scale(UIUtil.DEFAULT_HGAP / 2);
            }
            g2.setFont(JBUI.Fonts.label());
            g2.setColor(UIUtil.getLabelForeground());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(clip(n.title(), fm, (int) (x + w - pad - textX)), textX, y + th / 3 + fm.getAscent() / 2f - 1);
            g2.setFont(JBUI.Fonts.smallFont());
            g2.setColor(NamedColorUtil.getInactiveTextColor());
            fm = g2.getFontMetrics();
            g2.drawString(clip(n.subtitle(), fm, (int) (w - pad * 2)), x + pad, y + th * 2 / 3 + fm.getAscent() / 2f);
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
