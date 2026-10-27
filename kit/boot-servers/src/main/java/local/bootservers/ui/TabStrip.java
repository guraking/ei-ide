package local.bootservers.ui;

import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.RoundedLineBorder;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 글자 탭 줄과 그 내용 칸(cards()). 테마의 둥근 상자 탭 대신 쓴다.
 * 밑줄형: 고른 탭 아래 2px 강조 선. 단추형(segmented): 테두리 안에 붙은 작은 단추, 고른 것만 옅은 강조 바탕.
 * 탭 이름 옆에 흐린 보조 글(개수 · 상태)을 붙일 수 있다. 마우스 · 스페이스 · 엔터로 고른다.
 */
public final class TabStrip extends JPanel {

    private static final int UNDERLINE = 2;
    private static final int GAP = 18;
    private static final int SEGMENT_ARC = 8;
    /** 단추형에서 고른 단추 바탕: 강조 색을 이만큼 옅게 깐다. */
    private static final double SEGMENT_ALPHA = 0.14;

    private final boolean segmented;
    private final JPanel cards = new JPanel(new CardLayout());
    private final List<JBLabel> tabs = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<String> hints = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private int selected = -1;

    public TabStrip(boolean segmented) {
        // 첫 탭이 칸 왼쪽 여백에 바로 붙도록 간격은 FlowLayout 이 아니라 탭 오른쪽 여백으로 둔다.
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.segmented = segmented;
        setOpaque(false);
        if (segmented) {
            setBorder(new RoundedLineBorder(JBColor.border(), JBUI.scale(SEGMENT_ARC)));
        }
    }

    public JComponent cards() {
        return cards;
    }

    public void addTab(String title, JComponent content) {
        int index = tabs.size();
        JBLabel tab = new JBLabel();
        tab.setFocusable(true);
        tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tab.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                select(index);
            }
        });
        tab.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER) {
                    select(index);
                }
            }
        });
        tabs.add(tab);
        titles.add(title);
        hints.add("");
        cards.add(content, String.valueOf(index));
        super.add(tab);
        if (selected < 0) {
            select(index);
        } else {
            paintTab(index);
        }
    }

    /** 탭 이름 옆 흐린 글. 빈 글이면 이름만. */
    public void setHint(int index, String hint) {
        hints.set(index, hint);
        paintTab(index);
    }

    public int selected() {
        return selected;
    }

    /** 다른 탭으로 옮길 때마다 부른다. */
    public void onSelect(Runnable listener) {
        listeners.add(listener);
    }

    public void select(int index) {
        int old = selected;
        selected = index;
        ((CardLayout) cards.getLayout()).show(cards, String.valueOf(index));
        if (old >= 0) {
            paintTab(old);
        }
        paintTab(index);
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private void paintTab(int index) {
        JBLabel tab = tabs.get(index);
        boolean on = index == selected;
        String name = escape(titles.get(index));
        StringBuilder html = new StringBuilder("<html>").append(on && !segmented ? "<b>" + name + "</b>" : name);
        String hint = hints.get(index);
        if (!hint.isEmpty()) {
            html.append("&nbsp;&nbsp;<font color='").append(ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()))
                    .append("'>").append(escape(hint)).append("</font>");
        }
        String title = html.append("</html>").toString();
        tab.setText(title);
        Color accent = JBUI.CurrentTheme.Link.Foreground.ENABLED;
        if (segmented) {
            tab.setOpaque(on);
            tab.setBackground(ColorUtil.withAlpha(accent, SEGMENT_ALPHA));
            tab.setForeground(on ? accent : UIUtil.getContextHelpForeground());
            tab.setBorder(JBUI.Borders.empty(2, 10));
        } else {
            tab.setForeground(on ? UIUtil.getLabelForeground() : UIUtil.getContextHelpForeground());
            tab.setBorder(JBUI.Borders.compound(JBUI.Borders.emptyRight(GAP), JBUI.Borders.compound(on
                            ? JBUI.Borders.customLine(accent, 0, 0, UNDERLINE, 0)
                            : JBUI.Borders.empty(0, 0, UNDERLINE, 0),
                    JBUI.Borders.empty(10, 0, 6, 0))));
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
