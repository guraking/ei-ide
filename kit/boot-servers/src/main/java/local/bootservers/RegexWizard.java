package local.bootservers;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.NamedColorUtil;
import local.bootservers.RegexBench.CharKind;
import local.bootservers.RegexBench.Need;
import local.bootservers.RegexBench.Repeat;
import local.bootservers.RegexBench.Repeating;
import org.jetbrains.annotations.NotNull;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 정규식 작업대의 「질문 따라가기」: 어디에 쓰는지 · 어떤 글자인지 · 몇 자인지 · 띄어쓰기 · 꼭 들어갈 것을 차례로 묻고
 * 답이 바뀔 때마다 RegexBench.ask 로 만든 패턴을 use 에 넘긴다. 글자를 하나도 고르지 않으면 넘기지 않고 안내만 보인다.
 */
final class RegexWizard {

    private static final int MAX_LENGTH = 999;
    // 띄어쓰기는 5번 질문이 맡는다
    private static final EnumSet<CharKind> KINDS = EnumSet.complementOf(EnumSet.of(CharKind.SPACE));
    private static final Repeat[] LENGTHS = {Repeat.ONE_OR_MORE, Repeat.EXACTLY, Repeat.RANGE, Repeat.AT_LEAST};

    private final RegexBenchDialog.Use use;
    private final JBRadioButton wholeRadio = new JBRadioButton(BootServersBundle.message("regex.ask.whole"), true);
    private final JBRadioButton findRadio = new JBRadioButton(BootServersBundle.message("regex.ask.find"));
    private final Map<CharKind, JBCheckBox> kindBoxes = new EnumMap<>(CharKind.class);
    private final JBTextField extraField = new JBTextField();
    private final ComboBox<Repeat> lengthBox = new ComboBox<>(LENGTHS);
    private final JBIntSpinner nSpinner = new JBIntSpinner(4, 1, MAX_LENGTH);
    private final JBLabel mLabel = new JBLabel(BootServersBundle.message("regex.compose.m"));
    private final JBIntSpinner mSpinner = new JBIntSpinner(20, 1, MAX_LENGTH);
    private final JBLabel lengthTail = new JBLabel(BootServersBundle.message("regex.ask.length.tail"));
    private final JBCheckBox spacedBox = new JBCheckBox(BootServersBundle.message("regex.ask.spaced.box"));
    private final JBLabel needsLabel = new JBLabel(BootServersBundle.message("regex.ask.needs"));
    private final Map<Need, JBCheckBox> needBoxes = new EnumMap<>(Need.class);
    private final JPanel needsRow = RegexComposer.row();
    private final JBLabel problemLabel = new JBLabel();
    private final JPanel panel;

    RegexWizard(RegexBenchDialog.Use use) {
        this.use = use;
        ButtonGroup whereGroup = new ButtonGroup();
        whereGroup.add(wholeRadio);
        whereGroup.add(findRadio);
        wholeRadio.addActionListener(e -> changed());
        findRadio.addActionListener(e -> changed());

        JPanel kindsRow = RegexComposer.row();
        for (CharKind kind : KINDS) {
            JBCheckBox box = new JBCheckBox(BootServersBundle.message("regex.kind." + kind.name()));
            box.addActionListener(e -> changed());
            kindBoxes.put(kind, box);
            kindsRow.add(box);
        }
        kindBoxes.get(CharKind.KOREAN).setSelected(true);
        extraField.getEmptyText().setText(BootServersBundle.message("regex.compose.extra.empty"));
        extraField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                changed();
            }
        });

        lengthBox.setRenderer(SimpleListCellRenderer.create("", r -> BootServersBundle.message("regex.ask.length." + r.name())));
        lengthBox.addActionListener(e -> changed());
        nSpinner.addChangeListener(e -> changed());
        mSpinner.addChangeListener(e -> changed());
        spacedBox.addActionListener(e -> changed());

        for (Need need : Need.values()) {
            JBCheckBox box = new JBCheckBox(BootServersBundle.message("regex.need." + need.name()));
            box.addActionListener(e -> changed());
            needBoxes.put(need, box);
            needsRow.add(box);
        }
        problemLabel.setForeground(NamedColorUtil.getErrorForeground());

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("regex.ask.where"), RegexComposer.row(wholeRadio, findRadio))
                .addLabeledComponent(BootServersBundle.message("regex.ask.kinds"), kindsRow)
                .addLabeledComponent(BootServersBundle.message("regex.ask.extra"), extraField)
                .addLabeledComponent(BootServersBundle.message("regex.ask.length"), RegexComposer.row(lengthBox, nSpinner, mLabel, mSpinner, lengthTail))
                .addLabeledComponent(BootServersBundle.message("regex.ask.spaced"), spacedBox)
                .addLabeledComponent(needsLabel, needsRow)
                .addComponent(problemLabel)
                .getPanel();
        showFields();
    }

    JComponent component() {
        return panel;
    }

    private void showFields() {
        Repeat length = (Repeat) lengthBox.getSelectedItem();
        boolean n = length != Repeat.ONE_OR_MORE;
        nSpinner.setVisible(n);
        lengthTail.setVisible(n);
        mLabel.setVisible(length == Repeat.RANGE);
        mSpinner.setVisible(length == Repeat.RANGE);
        // 꼭 들어갈 것은 글 전체를 볼 때만 뜻이 있다
        needsLabel.setVisible(wholeRadio.isSelected());
        needsRow.setVisible(wholeRadio.isSelected());
    }

    /** 답이 바뀌었다. 패턴을 만들 수 있으면 use 에 넘기고, 아니면 무엇이 모자란지 보인다. */
    private void changed() {
        showFields();
        Repeat length = (Repeat) lengthBox.getSelectedItem();
        if (length == Repeat.RANGE && nSpinner.getNumber() > mSpinner.getNumber()) {
            problemLabel.setText(BootServersBundle.message("regex.problem.RANGE"));
            return;
        }
        EnumSet<CharKind> kinds = EnumSet.noneOf(CharKind.class);
        for (Map.Entry<CharKind, JBCheckBox> e : kindBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                kinds.add(e.getKey());
            }
        }
        EnumSet<Need> needs = EnumSet.noneOf(Need.class);
        for (Map.Entry<Need, JBCheckBox> e : needBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                needs.add(e.getKey());
            }
        }
        Repeating repeating = new Repeating(length, nSpinner.getNumber(), mSpinner.getNumber(), false, false, "");
        String pattern = RegexBench.ask(wholeRadio.isSelected(), kinds, extraField.getText(), repeating, spacedBox.isSelected(), needs);
        if (pattern == null) {
            problemLabel.setText(BootServersBundle.message("regex.problem.NO_KINDS"));
            return;
        }
        problemLabel.setText("");
        use.use(pattern, wholeRadio.isSelected(), null);
    }
}
