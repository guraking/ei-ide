package local.bootservers.web;

import local.bootservers.BootServersBundle;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;

/**
 * 「고르기로 만들기」 탭: 칸 · 조건을 고르면 JsPipeline 으로 배열 가공 코드를 만든다.
 * 칸 목록은 입력 데이터에서 읽고(setColumns), 칸 이름은 직접 칠 수도 있다. 맨 앞 NONE 을 고른 단계는 쓰지 않는다.
 */
final class JsPipelinePanel {

    private static final String NONE = BootServersBundle.message("js.pipe.none");
    private static final int LIMIT_MAX = 100_000;
    private static final int LIMIT_STEP = 10;
    /** 스크롤 한 칸(배율 전 px). */
    private static final int UNIT_SCROLL = 16;

    private final ComboBox<String> filterColumn = columnBox();
    private final ComboBox<JsPipeline.Op> filterOp = new ComboBox<>(JsPipeline.Op.values());
    private final JBTextField filterValue = new JBTextField();
    private final ComboBox<String> distinctColumn = columnBox();
    private final ComboBox<String> groupColumn = columnBox();
    private final ComboBox<Object> aggregate = new ComboBox<>();
    private final ComboBox<String> aggColumn = columnBox();
    private final ComboBox<String> sortColumn = columnBox();
    private final JBRadioButton ascending = new JBRadioButton(BootServersBundle.message("js.pipe.ascending"), true);
    private final JBRadioButton descending = new JBRadioButton(BootServersBundle.message("js.pipe.descending"));
    private final JSpinner limit = new JSpinner(new SpinnerNumberModel(0, 0, LIMIT_MAX, LIMIT_STEP));
    private final JBTextArea hint = new JBTextArea(BootServersBundle.message("js.pipe.hint"));
    private final JComponent root;

    JsPipelinePanel(Consumer<String> make) {
        aggregate.addItem(NONE);
        for (JsPipeline.Agg a : JsPipeline.Agg.values()) {
            aggregate.addItem(a);
        }
        ButtonGroup order = new ButtonGroup();
        order.add(ascending);
        order.add(descending);
        JPanel orderRow = new JPanel(new GridLayout(1, 2));
        orderRow.add(ascending);
        orderRow.add(descending);
        filterOp.addActionListener(e -> filterValue.setEnabled(((JsPipeline.Op) filterOp.getSelectedItem()).needsValue()));
        filterValue.getEmptyText().setText(BootServersBundle.message("js.pipe.value.empty"));
        hint.setEditable(false);
        hint.setFont(JBFont.label());
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        hint.setForeground(UIUtil.getContextHelpForeground());
        JButton makeButton = new JButton(BootServersBundle.message("js.pipe.make"));
        makeButton.putClientProperty(com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        makeButton.addActionListener(e -> make.accept(JsPipeline.generate(spec())));
        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(hint)
                .addSeparator()
                .addLabeledComponent(BootServersBundle.message("js.pipe.filter.column"), filterColumn)
                .addLabeledComponent(BootServersBundle.message("js.pipe.filter.op"), filterOp)
                .addLabeledComponent(BootServersBundle.message("js.pipe.filter.value"), filterValue)
                .addSeparator()
                .addLabeledComponent(BootServersBundle.message("js.pipe.distinct"), distinctColumn)
                .addSeparator()
                .addLabeledComponent(BootServersBundle.message("js.pipe.group"), groupColumn)
                .addLabeledComponent(BootServersBundle.message("js.pipe.aggregate"), aggregate)
                .addLabeledComponent(BootServersBundle.message("js.pipe.agg.column"), aggColumn)
                .addSeparator()
                .addLabeledComponent(BootServersBundle.message("js.pipe.sort"), sortColumn)
                .addLabeledComponent(BootServersBundle.message("js.pipe.order"), orderRow)
                .addLabeledComponent(BootServersBundle.message("js.pipe.limit"), limit)
                .addSeparator()
                .addComponent(makeButton)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        form.setBorder(JBUI.Borders.empty(8));
        root = new JBScrollPane(new WidthTracking(form));
        root.setBorder(JBUI.Borders.empty());
    }

    JComponent component() {
        return root;
    }

    /** 입력 데이터의 칸 이름으로 목록을 다시 채운다. 고르거나 쳐 둔 칸은 그대로 둔다. */
    void setColumns(List<String> columns) {
        for (ComboBox<String> box : List.of(filterColumn, distinctColumn, groupColumn, aggColumn, sortColumn)) {
            Object kept = box.getEditor().getItem();
            box.removeAllItems();
            box.addItem(NONE);
            for (String c : columns) {
                box.addItem(c);
            }
            box.setSelectedItem(kept == null || kept.toString().isBlank() ? NONE : kept.toString());
        }
    }

    private JsPipeline.Spec spec() {
        JsPipeline.Agg agg = aggregate.getSelectedItem() instanceof JsPipeline.Agg a ? a : null;
        String sort = picked(sortColumn);
        return new JsPipeline.Spec(picked(filterColumn), (JsPipeline.Op) filterOp.getSelectedItem(), filterValue.getText(),
                picked(distinctColumn), picked(groupColumn), agg, picked(aggColumn),
                sort, descending.isSelected(), (Integer) limit.getValue());
    }

    /** 고른(또는 친) 칸 이름. 비었거나 NONE 이면 null. */
    private static String picked(ComboBox<String> box) {
        Object item = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
        String text = item == null ? "" : item.toString().strip();
        return text.isEmpty() || text.equals(NONE) ? null : text;
    }

    /** 스크롤 칸 폭을 따라가는 판. 줄바꿈 글(hint)이 한 줄로 길게 늘어 가로 스크롤이 생기지 않고 칸 폭에서 접힌다. */
    private static final class WidthTracking extends JPanel implements Scrollable {
        WidthTracking(JComponent body) {
            super(new BorderLayout());
            add(body, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return JBUI.scale(UNIT_SCROLL);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static ComboBox<String> columnBox() {
        ComboBox<String> box = new ComboBox<>();
        box.setEditable(true);
        box.addItem(NONE);
        return box;
    }
}
