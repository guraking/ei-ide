package local.bootservers;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.ui.JBUI;

import javax.swing.JTable;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.TableColumnModel;
import java.util.StringJoiner;

/**
 * 표 칸 폭. 칸 폭은 선호 폭의 비율로 넣으므로 자동 맞춤 표에서 창 폭을 따라 늘고 준다.
 * 처음에는 weights 비율, 사용자가 머리글 경계를 끌면 그 폭을 IDE 설정(key)에 남기고 다음에 연 표에서 같은 비율로 쓴다.
 * 남긴 폭은 합이 처음 비율의 합과 같게 맞춰 넣어, 넓은 화면에서 끈 폭이 좁은 창에서 가로 스크롤을 만들지 않게 한다.
 * parse 는 IntelliJ 클래스 없이 점검(ConnectionMapCheck)한다.
 */
final class ColumnWidths {

    private static final Logger LOG = Logger.getInstance(ColumnWidths.class);
    private static final String SEPARATOR = ",";
    // 비율 1 의 선호 폭(px, 배율 전). 자동 맞춤 표라 칸끼리의 비율로만 쓰인다.
    private static final int WEIGHT_UNIT = 40;

    private ColumnWidths() {
    }

    /** weights 는 칸마다 처음 비율(표 칸 수와 같은 길이). */
    static void remember(JTable table, String key, int[] weights) {
        TableColumnModel columns = table.getColumnModel();
        if (columns.getColumnCount() != weights.length) {
            throw new IllegalArgumentException("칸 수와 비율 수가 다릅니다: " + columns.getColumnCount() + " ≠ " + weights.length);
        }
        PropertiesComponent props = PropertiesComponent.getInstance();
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        String saved = props.getValue(key);
        int[] widths = parse(saved, weights.length);
        if (saved != null && widths == null) {
            LOG.warn("표 칸 폭 기억이 깨져 처음 비율로 엽니다: " + key + "=" + saved);
        }
        int[] applied = scale(widths != null ? widths : weights, JBUI.scale(total * WEIGHT_UNIT));
        for (int i = 0; i < applied.length; i++) {
            columns.getColumn(i).setPreferredWidth(applied[i]);
        }
        columns.addColumnModelListener(new TableColumnModelListener() {
            @Override
            public void columnMarginChanged(ChangeEvent e) {
                // 표 폭이 바뀔 때도 불리므로 머리글을 끄는 중일 때만 남긴다
                if (table.getTableHeader() == null || table.getTableHeader().getResizingColumn() == null) {
                    return;
                }
                StringJoiner joined = new StringJoiner(SEPARATOR);
                for (int i = 0; i < columns.getColumnCount(); i++) {
                    joined.add(String.valueOf(columns.getColumn(i).getWidth()));
                }
                props.setValue(key, joined.toString());
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

    /** 남긴 글(「120,80,300」) → 칸 폭. 없거나 칸 수가 다르거나 숫자가 아니거나 1 보다 작으면 null. */
    static int[] parse(String saved, int count) {
        if (saved == null) {
            return null;
        }
        String[] parts = saved.split(SEPARATOR);
        if (parts.length != count) {
            return null;
        }
        int[] widths = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                widths[i] = Integer.parseInt(parts[i].trim());
                if (widths[i] < 1) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return widths;
    }

    /** 비율을 지킨 채 합이 total 쯤이 되게 줄이거나 늘린다. 칸마다 1 이상. */
    static int[] scale(int[] widths, int total) {
        long sum = 0;
        for (int width : widths) {
            sum += width;
        }
        int[] scaled = new int[widths.length];
        for (int i = 0; i < widths.length; i++) {
            scaled[i] = (int) Math.max(1, Math.round((double) widths[i] * total / sum));
        }
        return scaled;
    }
}
