package local.bootservers;

import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import local.bootservers.ui.LineChart;
import local.bootservers.ui.Pill;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 서버 창 「모아 보기」: 실행 중인 서버를 한 표에 놓고 비교한다. 기준을 넘는 칸은 주황(평균 응답 · DB 풀) · 빨강(오류율).
 * 줄을 누르면 그 서버의 대시보드 탭으로 간다.
 */
final class AllServersView {

    private static final long MB = 1024L * 1024L;
    private static final double PERCENT = 100;
    private static final double MILLIS_PER_SECOND = 1000;
    private static final int SPARK_HEIGHT = 20;
    private static final int SPARK_COLUMN = 10;
    private static final int STATE_COLUMN = 1;
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABLE = "table";

    private record Row(String name, Metrics.History history, MetricsProbe.Access access) {
    }

    private final List<Row> rows = new ArrayList<>();
    private final String[] columns = {
            BootServersBundle.message("all.server"), BootServersBundle.message("all.state"), BootServersBundle.message("dashboard.cpu"),
            BootServersBundle.message("dashboard.heap"), BootServersBundle.message("dashboard.threads"), BootServersBundle.message("all.requests"),
            BootServersBundle.message("all.avg"), BootServersBundle.message("all.max"), BootServersBundle.message("dashboard.errors"),
            BootServersBundle.message("dashboard.pool"), BootServersBundle.message("all.cpu.chart")};
    private final AbstractTableModel model = new AbstractTableModel() {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row);
        }
    };
    private final JBTable table = new JBTable(model);
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JBLabel caption = new JBLabel();

    AllServersView(Consumer<String> onPick) {
        table.setShowGrid(false);
        table.setRowHeight(JBUI.scale(SPARK_HEIGHT + UIUtil.DEFAULT_VGAP * 2));
        TableCellRenderer cells = new Cells();
        for (int c = 0; c < columns.length; c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(cells);
        }
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && row < rows.size()) {
                    onPick.accept(rows.get(row).name());
                }
            }
        });
        caption.setForeground(NamedColorUtil.getInactiveTextColor());
        caption.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        JBLabel hint = new JBLabel(BootServersBundle.message("all.hint"));
        hint.setForeground(NamedColorUtil.getInactiveTextColor());
        hint.setFont(JBUI.Fonts.smallFont());
        hint.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        JPanel body = new JPanel(new BorderLayout());
        body.add(caption, BorderLayout.NORTH);
        body.add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER);
        body.add(hint, BorderLayout.SOUTH);
        JBPanelWithEmptyText empty = new JBPanelWithEmptyText();
        empty.getEmptyText().setText(BootServersBundle.message("all.empty"))
                .appendLine(BootServersBundle.message("all.empty.hint"), SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        root.add(empty, CARD_EMPTY);
        root.add(body, CARD_TABLE);
    }

    JComponent component() {
        return root;
    }

    /** names 는 실행 중인 서버(트리 차례). 값이 아직 없는 서버도 줄은 보인다. */
    void show(List<String> names, ServerMonitor monitor) {
        rows.clear();
        for (String name : names) {
            rows.add(new Row(name, monitor.history(name), monitor.access(name)));
        }
        model.fireTableDataChanged();
        caption.setText(BootServersBundle.message("all.caption", rows.size()));
        cards.show(root, rows.isEmpty() ? CARD_EMPTY : CARD_TABLE);
    }

    /** 칸마다 글 · 꼬리표 · 그래프를 그린다. 숫자 칸은 오른쪽 맞춤. */
    private static final class Cells implements TableCellRenderer {
        private final DefaultTableCellRenderer text = new DefaultTableCellRenderer();
        private final Pill pill = new Pill();
        private final JPanel pillBox = new JPanel(new BorderLayout());
        private final LineChart spark = new LineChart(false, SPARK_HEIGHT);
        private final JPanel sparkBox = new JPanel(new BorderLayout());

        Cells() {
            pillBox.add(pill, BorderLayout.WEST);
            pillBox.setBorder(JBUI.Borders.empty(0, UIUtil.DEFAULT_HGAP));
            sparkBox.add(spark, BorderLayout.CENTER);
            sparkBox.setBorder(JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            Row r = (Row) value;
            Metrics.History h = r.history();
            Metrics.Sample s = h == null ? null : h.latest();
            Color background = selected ? table.getSelectionBackground() : table.getBackground();
            if (column == SPARK_COLUMN) {
                spark.setValues(h == null ? new double[0] : h.series(x -> x.cpu() * PERCENT), "");
                sparkBox.setBackground(background);
                return sparkBox;
            }
            if (column == STATE_COLUMN) {
                state(r, h);
                pillBox.setBackground(background);
                return pillBox;
            }
            Color normal = selected ? table.getSelectionForeground() : table.getForeground();
            Color warn = JBUI.CurrentTheme.Label.warningForeground();
            Color error = JBUI.CurrentTheme.Label.errorForeground();
            String shown;
            Color color = normal;
            if (column == 0) {
                shown = r.name();
            }
            else if (s == null) {
                shown = "";
            }
            else {
                double avg = h.averageMillis();
                double rate = h.errorRate();
                boolean busy = s.poolMax() > 0 && s.poolActive() / s.poolMax() >= Metrics.POOL_BUSY_RATIO;
                switch (column) {
                    case 2 -> shown = DashboardView.percent(s.cpu());
                    case 3 -> shown = s.heapUsed() < 0 ? DashboardView.whole(Double.NaN) : BootServersBundle.message("dashboard.mb", s.heapUsed() / MB);
                    case 4 -> shown = s.threads() < 0 ? DashboardView.whole(Double.NaN) : String.valueOf(s.threads());
                    case 5 -> shown = DashboardView.whole(h.perMinute(Metrics.Sample::requests));
                    case 6 -> {
                        shown = DashboardView.millis(avg);
                        color = avg >= Metrics.SLOW_AVG_MILLIS ? warn : normal;
                    }
                    case 7 -> {
                        shown = DashboardView.millis(s.maxSeconds() * MILLIS_PER_SECOND);
                        color = s.maxSeconds() * MILLIS_PER_SECOND >= Metrics.SLOW_AVG_MILLIS ? warn : normal;
                    }
                    case 8 -> {
                        shown = DashboardView.percent(rate);
                        color = rate >= Metrics.ERROR_RATE ? error : normal;
                    }
                    default -> {
                        shown = Double.isNaN(s.poolActive()) ? BootServersBundle.message("all.pool.none") : DashboardView.poolText(s);
                        color = busy ? warn : normal;
                    }
                }
            }
            text.getTableCellRendererComponent(table, shown, selected, focus, row, column);
            text.setHorizontalAlignment(column == 0 ? SwingConstants.LEFT : SwingConstants.RIGHT);
            text.setForeground(color);
            return text;
        }

        /** 꼬리표: 오류 많음(빨강) > 응답 느림(주황) > 실행 중(초록), 지표를 못 읽으면 흐린 「JVM 만」. */
        private void state(Row r, Metrics.History h) {
            if (r.access() != MetricsProbe.Access.OK || h == null) {
                pill.show(BootServersBundle.message("all.state.jvm"), NamedColorUtil.getInactiveTextColor(), null);
            }
            else if (h.errorRate() >= Metrics.ERROR_RATE) {
                pill.show(BootServersBundle.message("all.state.errors"), JBUI.CurrentTheme.Label.errorForeground(), null);
            }
            else if (h.averageMillis() >= Metrics.SLOW_AVG_MILLIS) {
                pill.show(BootServersBundle.message("all.state.slow"), JBUI.CurrentTheme.Label.warningForeground(), null);
            }
            else {
                pill.show(BootServersBundle.message("all.state.up"), UIUtil.getLabelSuccessForeground(), null);
            }
        }
    }
}
