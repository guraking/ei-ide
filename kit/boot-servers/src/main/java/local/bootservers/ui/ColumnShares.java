package local.bootservers.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTable;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.util.Arrays;
import java.util.Locale;

/**
 * 표 칸 너비: 글 칸은 창 폭에 따라 비율로 늘고 줄며, 사용자가 머리글을 끌어 바꾼 비율을 IDE 설정에 남겨 다음에 연 표에 쓴다.
 * 체크 칸은 체크 상자 · 머리글 글 가운데 넓은 쪽에 맞춘다(화면 배율 · 글꼴을 따른다).
 */
public final class ColumnShares {

    /** 비율을 선호 너비로 넣을 때 곱하는 수. 표는 선호 너비를 비율로만 쓰므로 실제 픽셀이 아니다. */
    private static final int SHARE_SCALE = 1000;
    private static final String SHARE_SEPARATOR = ",";
    private static final String SHARE_FORMAT = "%.4f";
    /** 체크 칸 좌우 여백(배율 전). */
    private static final int CHECK_PADDING = 4;

    private ColumnShares() {
    }

    /**
     * columns 칸의 너비 비율을 key 로 기억한다. defaults 는 처음 비율(columns 와 같은 길이), null 이면 똑같이 나눈다.
     * 표 구조가 바뀌면(칸을 다시 만들면) 다시 불러야 한다.
     */
    public static void remember(JTable table, String key, int[] columns, double @Nullable [] defaults) {
        double[] fallback = defaults != null ? defaults : equalShares(columns.length);
        double[] shares = parse(PropertiesComponent.getInstance().getValue(key), fallback);
        for (int i = 0; i < columns.length; i++) {
            table.getColumnModel().getColumn(columns[i]).setPreferredWidth((int) Math.round(shares[i] * SHARE_SCALE));
        }
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override
            public void columnMarginChanged(ChangeEvent e) {
                // 표 폭이 바뀔 때도 불리므로 머리글을 끄는 중일 때만 남긴다.
                JTableHeader header = table.getTableHeader();
                if (header == null || header.getResizingColumn() == null) {
                    return;
                }
                int total = 0;
                for (int column : columns) {
                    total += table.getColumnModel().getColumn(column).getWidth();
                }
                if (total <= 0) {
                    return;
                }
                StringBuilder value = new StringBuilder();
                for (int column : columns) {
                    if (value.length() > 0) {
                        value.append(SHARE_SEPARATOR);
                    }
                    value.append(String.format(Locale.ROOT, SHARE_FORMAT, (double) table.getColumnModel().getColumn(column).getWidth() / total));
                }
                PropertiesComponent.getInstance().setValue(key, value.toString());
            }

            @Override
            public void columnAdded(TableColumnModelEvent e) {
            }

            @Override
            public void columnRemoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnSelectionChanged(ListSelectionEvent e) {
            }
        });
    }

    /** 모든 칸의 너비 비율을 key 로 기억한다(처음에는 똑같이 나눈다). */
    public static void remember(JTable table, String key) {
        int[] columns = new int[table.getColumnModel().getColumnCount()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = i;
        }
        remember(table, key, columns, null);
    }

    /** 체크 칸을 체크 상자 · 머리글 글 가운데 넓은 쪽 + 여백으로 고정하고 그 너비(px)를 돌려준다. */
    public static int fitCheck(JTable table, int column) {
        TableColumn c = table.getColumnModel().getColumn(column);
        int box = new JBCheckBox().getPreferredSize().width;
        Object title = c.getHeaderValue();
        JTableHeader header = table.getTableHeader();
        int text = title == null || header == null ? 0 : header.getFontMetrics(header.getFont()).stringWidth(title.toString());
        int width = Math.max(box, text) + JBUI.scale(CHECK_PADDING) * 2;
        c.setMinWidth(width);
        c.setMaxWidth(width);
        c.setPreferredWidth(width);
        return width;
    }

    /** 남긴 비율 글(「0.35,0.45,0.20」). 없거나 깨졌거나 칸 수가 다르면 fallback. */
    static double[] parse(@Nullable String value, double[] fallback) {
        if (value == null) {
            return fallback;
        }
        String[] parts = value.split(SHARE_SEPARATOR);
        if (parts.length != fallback.length) {
            return fallback;
        }
        double[] shares = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                shares[i] = Double.parseDouble(parts[i]);
                if (!(shares[i] > 0 && shares[i] < 1)) {
                    return fallback;
                }
            }
        }
        catch (NumberFormatException e) {
            return fallback;
        }
        return shares;
    }

    private static double[] equalShares(int count) {
        double[] shares = new double[count];
        Arrays.fill(shares, 1.0 / count);
        return shares;
    }
}
