package local.bootservers;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import local.bootservers.ui.LineChart;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;

/**
 * 서버 창 「대시보드」 탭: 고른 서버 하나의 CPU · 힙 · 스레드 · 요청 · 응답 · 오류율 · DB 풀, 최근 10분 그래프 셋, 느린 요청 상위 5.
 * 액추에이터 지표를 읽을 수 없으면 위쪽 안내 글로 이유를 보이고 JVM 값만 채운다. 서버가 실행 중이 아니면 빈 안내만 보인다.
 * 느린 요청 줄을 두 번 누르면 그 주소를 처리하는 컨트롤러 메서드로 간다.
 */
final class DashboardView {

    private static final String DASH = "—";
    private static final long MB = 1024L * 1024L;
    private static final double PERCENT = 100;
    private static final double MILLIS_PER_SECOND = 1000;
    private static final int CHART_HEIGHT = 80;
    private static final int STAT_COLUMNS = 7;
    private static final float VALUE_FONT_BIGGER = 3f;
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_BODY = "body";
    private static final String NOTIFICATION_GROUP = "서버 실행";

    /** 칸 하나: 이름 · 큰 값 · 흐린 보조 글. */
    private static final class Stat extends JPanel {
        final JBLabel value = new JBLabel();
        final JBLabel sub = new JBLabel();

        Stat(String name) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1),
                    JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP)));
            JBLabel label = new JBLabel(name);
            label.setFont(JBUI.Fonts.smallFont());
            label.setForeground(NamedColorUtil.getInactiveTextColor());
            value.setFont(JBUI.Fonts.label().biggerOn(VALUE_FONT_BIGGER));
            sub.setFont(JBUI.Fonts.smallFont());
            add(label);
            add(value);
            add(sub);
        }

        void show(String v, Color vColor, String s, Color sColor) {
            value.setText(v);
            value.setForeground(vColor);
            sub.setText(s);
            sub.setForeground(sColor);
        }
    }

    private final Project project;
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JBLabel notice = new JBLabel();
    private final Stat cpu = new Stat(BootServersBundle.message("dashboard.cpu"));
    private final Stat heap = new Stat(BootServersBundle.message("dashboard.heap"));
    private final Stat threads = new Stat(BootServersBundle.message("dashboard.threads"));
    private final Stat requests = new Stat(BootServersBundle.message("dashboard.requests"));
    private final Stat response = new Stat(BootServersBundle.message("dashboard.response"));
    private final Stat errors = new Stat(BootServersBundle.message("dashboard.errors"));
    private final Stat pool = new Stat(BootServersBundle.message("dashboard.pool"));
    private final LineChart cpuChart = new LineChart(true, CHART_HEIGHT);
    private final LineChart heapChart = new LineChart(true, CHART_HEIGHT);
    private final LineChart responseChart = new LineChart(true, CHART_HEIGHT);
    private final DefaultTableModel slowModel = new DefaultTableModel(new Object[]{
            BootServersBundle.message("dashboard.slow.method"), BootServersBundle.message("dashboard.slow.uri"),
            BootServersBundle.message("dashboard.slow.count"), BootServersBundle.message("dashboard.slow.avg"),
            BootServersBundle.message("dashboard.slow.max"), BootServersBundle.message("dashboard.slow.errors")}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JBTable slowTable = new JBTable(slowModel);
    private List<Metrics.Slow> shownSlow = List.of();

    DashboardView(Project project) {
        this.project = project;
        JBPanelWithEmptyText empty = new JBPanelWithEmptyText();
        empty.getEmptyText().setText(BootServersBundle.message("dashboard.empty"))
                .appendLine(BootServersBundle.message("dashboard.empty.hint"), SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        root.add(empty, CARD_EMPTY);
        root.add(ScrollPaneFactory.createScrollPane(body(), true), CARD_BODY);
    }

    JComponent component() {
        return root;
    }

    private JComponent body() {
        notice.setForeground(NamedColorUtil.getInactiveTextColor());
        notice.setAllowAutoWrapping(true);
        JPanel stats = new JPanel(new GridLayout(1, STAT_COLUMNS));
        stats.setBorder(JBUI.Borders.customLine(JBColor.border()));
        for (Stat s : new Stat[]{cpu, heap, threads, requests, response, errors, pool}) {
            stats.add(s);
        }
        JPanel charts = new JPanel(new GridLayout(1, 3, JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        charts.add(chartBox(BootServersBundle.message("dashboard.chart.cpu"), cpuChart));
        charts.add(chartBox(BootServersBundle.message("dashboard.chart.heap"), heapChart));
        charts.add(chartBox(BootServersBundle.message("dashboard.chart.response"), responseChart));

        slowTable.setShowGrid(false);
        slowTable.getEmptyText().setText(BootServersBundle.message("dashboard.slow.empty"));
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 2; c < slowModel.getColumnCount(); c++) {
            slowTable.getColumnModel().getColumn(c).setCellRenderer(right);
        }
        slowTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = slowTable.getSelectedRow();
                if (e.getClickCount() == 2 && row >= 0 && row < shownSlow.size()) {
                    goToController(shownSlow.get(row));
                }
            }
        });
        JPanel tableBox = new JPanel(new BorderLayout());
        tableBox.setBorder(JBUI.Borders.customLine(JBColor.border()));
        tableBox.add(slowTable.getTableHeader(), BorderLayout.NORTH);
        tableBox.add(slowTable, BorderLayout.CENTER);
        JBLabel slowCaption = grayLabel(BootServersBundle.message("dashboard.slow.caption"));
        JBLabel slowHint = grayLabel(BootServersBundle.message("dashboard.slow.hint"));

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        int gap = JBUI.scale(UIUtil.DEFAULT_VGAP * 2);
        for (JComponent c : new JComponent[]{notice, stats, charts, slowCaption, tableBox, slowHint}) {
            c.setAlignmentX(0);
            column.add(c);
            column.add(javax.swing.Box.createVerticalStrut(gap));
        }
        JPanel top = new JPanel(new BorderLayout());
        top.add(column, BorderLayout.NORTH);
        return top;
    }

    private static JComponent chartBox(String title, LineChart chart) {
        JPanel box = new JPanel(new BorderLayout(0, JBUI.scale(UIUtil.DEFAULT_VGAP)));
        box.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP)));
        JPanel head = new JPanel(new BorderLayout());
        head.add(grayLabel(title), BorderLayout.WEST);
        head.add(grayLabel(BootServersBundle.message("dashboard.chart.window")), BorderLayout.EAST);
        box.add(head, BorderLayout.NORTH);
        box.add(chart, BorderLayout.CENTER);
        return box;
    }

    private static JBLabel grayLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(JBUI.Fonts.smallFont());
        label.setForeground(NamedColorUtil.getInactiveTextColor());
        return label;
    }

    /** history 가 null 이면(실행 중이 아님 · 아직 첫 값 전) 빈 안내. */
    void show(Metrics.History history, MetricsProbe.Access access, List<Metrics.Slow> slow) {
        Metrics.Sample s = history == null ? null : history.latest();
        cards.show(root, s == null ? CARD_EMPTY : CARD_BODY);
        if (s == null) {
            return;
        }
        Color normal = UIUtil.getLabelForeground();
        Color gray = NamedColorUtil.getInactiveTextColor();
        Color warn = JBUI.CurrentTheme.Label.warningForeground();
        Color error = JBUI.CurrentTheme.Label.errorForeground();
        String noticeText = noticeOf(access);
        notice.setText(noticeText);
        notice.setVisible(noticeText != null);

        cpu.show(Double.isNaN(s.cpu()) ? DASH : percent(s.cpu()), normal,
                s.cores() < 0 ? "" : BootServersBundle.message("dashboard.cpu.cores", s.cores()), gray);
        double gc = history.perMinute(Metrics.Sample::gcCount);
        heap.show(s.heapUsed() < 0 ? DASH : BootServersBundle.message("dashboard.mb", s.heapUsed() / MB), normal,
                s.heapMax() < 0 ? "" : BootServersBundle.message("dashboard.heap.sub", s.heapMax() / MB, Double.isNaN(gc) ? DASH : whole(gc)), gray);
        threads.show(s.threads() < 0 ? DASH : String.valueOf(s.threads()), normal,
                s.peakThreads() < 0 ? "" : BootServersBundle.message("dashboard.threads.peak", s.peakThreads()), gray);

        double perMinute = history.perMinute(Metrics.Sample::requests);
        requests.show(Double.isNaN(perMinute) ? DASH : BootServersBundle.message("dashboard.per.minute", whole(perMinute)), normal,
                Double.isNaN(s.requests()) ? "" : BootServersBundle.message("dashboard.requests.total", Math.round(s.requests())), gray);
        double avg = history.averageMillis();
        response.show(millis(avg), avg >= Metrics.SLOW_AVG_MILLIS ? warn : normal,
                Double.isNaN(s.maxSeconds()) ? "" : BootServersBundle.message("dashboard.response.max", millis(s.maxSeconds() * MILLIS_PER_SECOND)),
                s.maxSeconds() * MILLIS_PER_SECOND >= Metrics.SLOW_AVG_MILLIS ? warn : gray);
        double rate = history.errorRate();
        double server = history.perMinute(Metrics.Sample::serverErrors);
        double client = history.perMinute(Metrics.Sample::clientErrors);
        errors.show(Double.isNaN(rate) ? DASH : percent(rate), rate >= Metrics.ERROR_RATE ? error : normal,
                Double.isNaN(server) ? "" : BootServersBundle.message("dashboard.errors.sub", whole(server), whole(client)), gray);
        boolean busy = s.poolMax() > 0 && s.poolActive() / s.poolMax() >= Metrics.POOL_BUSY_RATIO;
        pool.show(Double.isNaN(s.poolActive()) ? DASH : poolText(s), busy ? warn : normal,
                Double.isNaN(s.poolPending()) ? "" : BootServersBundle.message("dashboard.pool.pending", whole(s.poolPending())), gray);

        String collecting = BootServersBundle.message("dashboard.chart.collecting");
        cpuChart.setValues(history.series(x -> x.cpu() * PERCENT), collecting);
        heapChart.setValues(history.series(x -> x.heapUsed() < 0 ? Double.NaN : (double) x.heapUsed() / MB), collecting);
        responseChart.setValues(history.averageSeries(), access == MetricsProbe.Access.OK ? collecting : DASH);
        showSlow(slow);
    }

    private void showSlow(List<Metrics.Slow> slow) {
        if (slow.equals(shownSlow)) {
            return;
        }
        shownSlow = slow;
        slowModel.setRowCount(0);
        for (Metrics.Slow s : slow) {
            slowModel.addRow(new Object[]{s.method(), s.uri(), String.format(Locale.ROOT, "%,d", s.count()), millis(s.avgMillis()), millis(s.maxMillis()), s.errors()});
        }
    }

    private static String noticeOf(MetricsProbe.Access access) {
        return switch (access) {
            case OK -> null;
            case NOT_EXPOSED -> BootServersBundle.message("dashboard.notice.not.exposed");
            case FORBIDDEN -> BootServersBundle.message("dashboard.notice.forbidden");
            case UNREACHABLE -> BootServersBundle.message("dashboard.notice.unreachable");
            case NONE -> BootServersBundle.message("dashboard.notice.none");
        };
    }

    static String poolText(Metrics.Sample s) {
        return whole(s.poolActive()) + " / " + (Double.isNaN(s.poolMax()) ? DASH : whole(s.poolMax()));
    }

    static String percent(double ratio) {
        return Double.isNaN(ratio) ? DASH : String.format(Locale.ROOT, ratio * PERCENT < 10 ? "%.1f %%" : "%.0f %%", ratio * PERCENT);
    }

    /** 1초 아래는 「48 ms」, 넘으면 「1.24 s」. */
    static String millis(double ms) {
        if (Double.isNaN(ms)) {
            return DASH;
        }
        return ms < MILLIS_PER_SECOND ? String.format(Locale.ROOT, "%.0f ms", ms) : String.format(Locale.ROOT, "%.2f s", ms / MILLIS_PER_SECOND);
    }

    static String whole(double v) {
        return Double.isNaN(v) ? DASH : String.format(Locale.ROOT, "%,.0f", v);
    }

    /** uri 패턴 · 방식이 맞는 컨트롤러 메서드로 간다. 찾지 못하면 알림. */
    private void goToController(Metrics.Slow slow) {
        String wanted = UrlPattern.normalize(slow.uri());
        ReadAction.nonBlocking(() -> {
                    List<UrlMappingAction.Endpoint> endpoints = UrlMappingAction.collect(project);
                    if (endpoints == null) {
                        return null;
                    }
                    for (UrlMappingAction.Endpoint e : endpoints) {
                        boolean method = e.httpMethod().equals(UrlMappingAction.ANY_METHOD) || slow.method().contains(e.httpMethod());
                        if (method && UrlPattern.normalize(e.url()).equals(wanted)) {
                            return e;
                        }
                    }
                    return null;
                })
                .inSmartMode(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), found -> {
                    PsiMethod target = found == null ? null : found.target().getElement();
                    if (target == null) {
                        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                                .createNotification(BootServersBundle.message("dashboard.slow.not.found", slow.uri()), NotificationType.INFORMATION)
                                .notify(project);
                        return;
                    }
                    WriteIntentReadAction.run(() -> target.navigate(true));
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }
}
