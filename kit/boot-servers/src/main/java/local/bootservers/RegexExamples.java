package local.bootservers;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * 정규식 작업대의 「예시로 만들기」: 맞아야 할 글 · 틀려야 할 글을 한 줄에 하나씩 받아 RegexBench.guess 로 글 전체 패턴을 만든다.
 * 「패턴 만들기」 를 누르면 패턴과, 두 칸의 글을 이은 시험 글을 use 에 넘긴다.
 */
final class RegexExamples {

    private static final int ROWS = 6;

    private final RegexBenchDialog.Use use;
    private final JBTextArea goodArea = new JBTextArea(ROWS, 0);
    private final JBTextArea badArea = new JBTextArea(ROWS, 0);
    private final JBLabel resultLabel = new JBLabel();
    private final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(UIUtil.DEFAULT_VGAP)));

    RegexExamples(RegexBenchDialog.Use use) {
        this.use = use;
        goodArea.getEmptyText().setText(BootServersBundle.message("regex.examples.good.empty"));
        badArea.getEmptyText().setText(BootServersBundle.message("regex.examples.bad.empty"));
        JPanel areas = new JPanel(new GridLayout(1, 2, JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        areas.add(labeled(BootServersBundle.message("regex.examples.good"), goodArea));
        areas.add(labeled(BootServersBundle.message("regex.examples.bad"), badArea));
        JButton make = new JButton(BootServersBundle.message("regex.examples.make"));
        make.addActionListener(e -> make());
        panel.add(areas, BorderLayout.CENTER);
        panel.add(RegexComposer.row(make, resultLabel), BorderLayout.SOUTH);
    }

    JComponent component() {
        return panel;
    }

    private static JPanel labeled(String label, JBTextArea area) {
        JPanel box = new JPanel(new BorderLayout(0, JBUI.scale(UIUtil.DEFAULT_VGAP)));
        box.add(new JBLabel(label), BorderLayout.NORTH);
        box.add(new JBScrollPane(area), BorderLayout.CENTER);
        return box;
    }

    /** 줄마다 앞뒤 공백을 떼고 빈 줄은 뺀다. */
    private static List<String> lines(JBTextArea area) {
        List<String> lines = new ArrayList<>();
        for (String line : area.getText().split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        return lines;
    }

    private void make() {
        List<String> good = lines(goodArea);
        List<String> bad = lines(badArea);
        RegexBench.Guess guess = RegexBench.guess(good, bad);
        if (guess == null) {
            resultLabel.setForeground(NamedColorUtil.getErrorForeground());
            resultLabel.setText(BootServersBundle.message("regex.examples.none"));
            return;
        }
        resultLabel.setForeground(guess.rejectsAll() ? UIUtil.getLabelForeground() : NamedColorUtil.getErrorForeground());
        resultLabel.setText(BootServersBundle.message(guess.rejectsAll() ? "regex.examples.ok" : "regex.examples.loose"));
        List<String> sample = new ArrayList<>(good);
        sample.addAll(bad);
        use.use(guess.pattern(), true, String.join("\n", sample));
    }
}
