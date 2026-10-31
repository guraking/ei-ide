package local.bootservers;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 정규식 작업대. 맨 위 「조합」 에서 고르기만으로 패턴을 만들고, JavaScript(프론트) · Java(백엔드) 문법으로 시험한다.
 * 입력이 바뀌면 DEBOUNCE_MS 뒤 풀 스레드에서 RegexBench 로 계산하고, 가장 마지막 입력의 결과만 화면에 놓는다.
 * 「확인할 글」 은 글 하나가 패턴에 통째로 맞는지 본다. 아래 「코드」 칸은 지금 패턴을 코드에 붙일 글로 보이고 「복사」 로 클립보드에 넣는다.
 * 창 글은 IDE 언어와 상관없이 한국어다(BootServersMessages).
 */
final class RegexBenchDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(RegexBenchDialog.class);
    private static final int DEBOUNCE_MS = 150;
    private static final long TIMEOUT_MS = 1000;
    private static final int DIALOG_WIDTH = 1000;
    private static final int DIALOG_HEIGHT = 900;
    private static final float TEXT_TABS_SPLIT = 0.45f;
    private static final int SAMPLE_ROWS = 5;
    private static final int RESULT_ROWS = 4;
    private static final int COPIED_FADE_MS = 1500;
    private static final String FLAVOR_KEY = "KingGura.RegexBench.flavor";
    // 그룹 표 칸 수
    private static final int COLUMN_COUNT = 5;
    private static final Color SUCCESS = JBColor.namedColor("Label.successForeground", new JBColor(0x208A3C, 0x5FB865));
    private static final Color WARNING = JBColor.namedColor("Label.warningForeground", new JBColor(0xA36200, 0xE0A040));

    private final ComboBox<RegexBench.Flavor> flavorBox = new ComboBox<>(RegexBench.Flavor.values());
    private final ComboBox<RegexBench.Preset> presetBox = new ComboBox<>();
    private final RegexComposer composer = new RegexComposer(this::composed);
    private final JBTextField patternField = new JBTextField();
    private final Map<RegexBench.Flag, JBCheckBox> flagBoxes = new EnumMap<>(RegexBench.Flag.class);
    private final JBTextField checkField = new JBTextField();
    private final JBLabel checkResult = new JBLabel();
    private final JBLabel status = new JBLabel();
    private final JBTextArea sampleArea = new JBTextArea(SAMPLE_ROWS, 0);
    private final DefaultTableModel groupModel = new DefaultTableModel(new Object[]{
            BootServersBundle.message("regex.column.match"), BootServersBundle.message("regex.column.group"),
            BootServersBundle.message("regex.column.name"), BootServersBundle.message("regex.column.value"),
            BootServersBundle.message("regex.column.range")}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JBTable groupTable = new JBTable(groupModel);
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JBTextField replacementField = new JBTextField();
    private final JBLabel replacementHint = new JBLabel();
    private final JBLabel replacementError = new JBLabel();
    private final JBTextArea replacedArea = new JBTextArea(RESULT_ROWS, 0);
    private final JBTextField codeField = new JBTextField();
    private final JButton copyButton = new JButton(BootServersBundle.message("regex.code.copy"));
    private final Timer debounce = new Timer(DEBOUNCE_MS, e -> run());
    private final Highlighter.HighlightPainter matchPainter;
    private final Highlighter.HighlightPainter selectedPainter;

    // 표의 줄마다 [시작, 끝]. 끼지 않은 그룹은 null.
    private final List<int[]> rowRanges = new ArrayList<>();
    private Object selectedHighlight;
    // 입력이 바뀔 때마다 오른다. 계산이 끝났을 때 값이 다르면 그 결과는 버린다.
    private int generation;
    // 자주 쓰는 정규식이 마지막으로 채운 예시 글. 사용자가 고친 글은 다른 것을 골라도 덮지 않는다.
    private String presetSample = "";
    // 문법 상자가 바뀌기 전 값. 바꿀 글의 이름 그룹 표기를 옮길 때 쓴다.
    private RegexBench.Flavor previousFlavor;

    /** sample 은 시험 글 처음 내용, flavor 가 null 이면 지난번 문법. */
    RegexBenchDialog(Project project, @Nullable RegexBench.Flavor flavor, String sample) {
        super(project, false);
        setModal(false);
        setTitle(BootServersBundle.message("regex.title"));
        getCancelAction().putValue(Action.NAME, BootServersBundle.message("regex.close"));
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        matchPainter = new DefaultHighlighter.DefaultHighlightPainter(matchColor(scheme));
        Color selection = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR);
        selectedPainter = new DefaultHighlighter.DefaultHighlightPainter(selection != null ? selection : sampleArea.getSelectionColor());
        debounce.setRepeats(false);
        flavorBox.setSelectedItem(flavor != null ? flavor : savedFlavor());
        sampleArea.setText(sample);
        init();
        flavorChanged(null);
    }

    private static Color matchColor(EditorColorsScheme scheme) {
        TextAttributes attributes = scheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES);
        Color color = attributes != null ? attributes.getBackgroundColor() : null;
        return color != null ? color : JBColor.YELLOW;
    }

    private static RegexBench.Flavor savedFlavor() {
        String saved = PropertiesComponent.getInstance().getValue(FLAVOR_KEY, RegexBench.Flavor.JAVA.name());
        for (RegexBench.Flavor flavor : RegexBench.Flavor.values()) {
            if (flavor.name().equals(saved)) {
                return flavor;
            }
        }
        return RegexBench.Flavor.JAVA;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        Font mono = EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
        flavorBox.setRenderer(SimpleListCellRenderer.create("", f -> BootServersBundle.message("regex.flavor." + f.name())));
        presetBox.setRenderer(SimpleListCellRenderer.create(BootServersBundle.message("regex.preset.pick"), RegexBench.Preset::name));
        presetBox.addItem(null);
        for (RegexBench.Preset preset : presets()) {
            presetBox.addItem(preset);
        }
        JPanel flavorRow = row(flavorBox, new JBLabel(BootServersBundle.message("regex.preset")), presetBox);

        patternField.setFont(mono);
        patternField.getEmptyText().setText(BootServersBundle.message("regex.pattern.empty"));
        JBLabel patternHint = new JBLabel(BootServersBundle.message("regex.pattern.hint"));
        patternHint.setForeground(NamedColorUtil.getInactiveTextColor());

        JPanel flagRow = row();
        for (RegexBench.Flag flag : RegexBench.Flag.values()) {
            JBCheckBox box = new JBCheckBox(BootServersBundle.message("regex.flag." + flag.name()));
            box.setToolTipText(flag.label);
            box.addActionListener(e -> changed());
            flagBoxes.put(flag, box);
            flagRow.add(box);
        }

        checkField.getEmptyText().setText(BootServersBundle.message("regex.check.empty"));
        // 확인할 글 칸은 줄 폭을 다 쓰고 결과는 오른쪽에 붙인다
        JPanel checkRow = new JPanel(new BorderLayout(JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        checkRow.add(checkField, BorderLayout.CENTER);
        checkRow.add(checkResult, BorderLayout.EAST);

        JPanel top = FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("regex.compose"), composer.component(), true)
                .addLabeledComponent(BootServersBundle.message("regex.flavor"), flavorRow)
                .addLabeledComponent(BootServersBundle.message("regex.pattern"), patternField)
                .addComponentToRightColumn(patternHint)
                .addLabeledComponent(BootServersBundle.message("regex.flags"), flagRow)
                .addLabeledComponent(BootServersBundle.message("regex.check"), checkRow)
                .addComponent(status)
                .getPanel();

        sampleArea.setFont(mono);
        JPanel samplePanel = new JPanel(new BorderLayout(0, JBUI.scale(UIUtil.DEFAULT_VGAP)));
        samplePanel.add(new JBLabel(BootServersBundle.message("regex.sample")), BorderLayout.NORTH);
        samplePanel.add(new JBScrollPane(sampleArea), BorderLayout.CENTER);

        groupTable.getEmptyText().setText(BootServersBundle.message("regex.groups.empty"));
        groupTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedRow();
            }
        });
        replacementField.setFont(mono);
        replacementHint.setForeground(NamedColorUtil.getInactiveTextColor());
        replacementHint.setAllowAutoWrapping(true);
        replacementError.setForeground(NamedColorUtil.getErrorForeground());
        replacedArea.setFont(mono);
        replacedArea.setEditable(false);
        JPanel replacePanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("regex.replacement"), replacementField)
                .addComponentToRightColumn(replacementHint)
                .addComponentToRightColumn(replacementError)
                .addLabeledComponentFillVertically(BootServersBundle.message("regex.replaced"), new JBScrollPane(replacedArea))
                .getPanel();
        tabs.addTab(BootServersBundle.message("regex.tab.groups"), new JBScrollPane(groupTable));
        tabs.addTab(BootServersBundle.message("regex.tab.replace"), replacePanel);

        OnePixelSplitter split = new OnePixelSplitter(true, TEXT_TABS_SPLIT);
        split.setFirstComponent(samplePanel);
        split.setSecondComponent(tabs);

        codeField.setFont(mono);
        codeField.setEditable(false);
        copyButton.addActionListener(e -> copyCode());
        JPanel codeRow = new JPanel(new BorderLayout(JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        codeRow.add(new JBLabel(BootServersBundle.message("regex.code")), BorderLayout.WEST);
        codeRow.add(codeField, BorderLayout.CENTER);
        codeRow.add(copyButton, BorderLayout.EAST);

        flavorBox.addActionListener(e -> flavorChanged(previousFlavor));
        presetBox.addActionListener(e -> applyPreset());
        patternField.getDocument().addDocumentListener(listener(this::changed));
        checkField.getDocument().addDocumentListener(listener(this::changed));
        sampleArea.getDocument().addDocumentListener(listener(this::changed));
        replacementField.getDocument().addDocumentListener(listener(this::changed));

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(UIUtil.DEFAULT_VGAP)));
        panel.add(top, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(codeRow, BorderLayout.SOUTH);
        panel.setPreferredSize(JBUI.size(DIALOG_WIDTH, DIALOG_HEIGHT));
        return panel;
    }

    private static List<RegexBench.Preset> presets() {
        try {
            return RegexBench.loadPresets();
        } catch (RuntimeException e) {
            LOG.warn("자주 쓰는 정규식을 읽지 못했습니다", e);
            return List.of();
        }
    }

    private static JPanel row(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        for (JComponent c : components) {
            row.add(c);
        }
        return row;
    }

    private static DocumentAdapter listener(Runnable onChange) {
        return new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                onChange.run();
            }
        };
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction()};
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "KingGura.RegexBench";
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return patternField;
    }

    @Override
    protected void dispose() {
        debounce.stop();
        super.dispose();
    }

    /** 조합이 바뀌었다. 고칠 점이 없고 비어 있지 않으면 패턴 칸을 조합 결과로 바꾼다. */
    private void composed() {
        String pattern = composer.pattern();
        if (pattern != null && !composer.isEmpty() && !pattern.equals(patternField.getText())) {
            patternField.setText(pattern);
        }
    }

    private RegexBench.Flavor flavor() {
        RegexBench.Flavor flavor = (RegexBench.Flavor) flavorBox.getSelectedItem();
        return flavor != null ? flavor : RegexBench.Flavor.JAVA;
    }

    /** 문법에 맞는 플래그만 보이고, 바꿀 글의 이름 그룹 표기를 새 문법으로 옮긴다. */
    private void flavorChanged(@Nullable RegexBench.Flavor previous) {
        RegexBench.Flavor flavor = flavor();
        for (Map.Entry<RegexBench.Flag, JBCheckBox> e : flagBoxes.entrySet()) {
            e.getValue().setVisible(e.getKey().flavor == flavor);
        }
        if (previous != null && previous != flavor) {
            replacementField.setText(RegexBench.convertReplacement(flavor, replacementField.getText()));
        }
        previousFlavor = flavor;
        replacementHint.setText(BootServersBundle.message("regex.replacement.hint." + flavor.name()));
        PropertiesComponent.getInstance().setValue(FLAVOR_KEY, flavor.name());
        changed();
    }

    private void applyPreset() {
        RegexBench.Preset preset = (RegexBench.Preset) presetBox.getSelectedItem();
        if (preset == null) {
            return;
        }
        Set<RegexBench.Flag> flags = RegexBench.flagsOf(preset.flags());
        for (Map.Entry<RegexBench.Flag, JBCheckBox> e : flagBoxes.entrySet()) {
            e.getValue().setSelected(flags.contains(e.getKey()));
        }
        patternField.setText(preset.pattern());
        replacementField.setText(RegexBench.convertReplacement(flavor(), preset.replacement()));
        String current = sampleArea.getText();
        if (current.isEmpty() || current.equals(presetSample)) {
            sampleArea.setText(preset.sample());
            presetSample = preset.sample();
        }
        sampleArea.setCaretPosition(0);
    }

    private Set<RegexBench.Flag> flags() {
        Set<RegexBench.Flag> flags = EnumSet.noneOf(RegexBench.Flag.class);
        RegexBench.Flavor flavor = flavor();
        for (Map.Entry<RegexBench.Flag, JBCheckBox> e : flagBoxes.entrySet()) {
            if (e.getKey().flavor == flavor && e.getValue().isSelected()) {
                flags.add(e.getKey());
            }
        }
        return flags;
    }

    /** 입력이 바뀌었다. 코드 칸은 바로, 일치 계산은 DEBOUNCE_MS 뒤. */
    private void changed() {
        generation++;
        codeField.setText(RegexBench.toCode(flavor(), patternField.getText(), flags()));
        debounce.restart();
    }

    private void run() {
        String pattern = patternField.getText();
        if (pattern.isEmpty()) {
            clearResult();
            checkResult.setText("");
            status.setForeground(NamedColorUtil.getInactiveTextColor());
            status.setText(BootServersBundle.message("regex.status.empty"));
            return;
        }
        int mine = generation;
        RegexBench.Flavor flavor = flavor();
        Set<RegexBench.Flag> flags = flags();
        String text = sampleArea.getText();
        String replacement = replacementField.getText();
        String input = checkField.getText();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            RegexBench.Result result = RegexBench.run(flavor, pattern, flags, text, replacement, TIMEOUT_MS);
            RegexBench.Check check = input.isEmpty() ? null : RegexBench.check(flavor, pattern, flags, input, TIMEOUT_MS);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (mine == generation && !isDisposed()) {
                    show(result);
                    showCheck(check);
                }
            }, ModalityState.any());
        });
    }

    /** 확인할 글 결과. 칸이 비었으면 null. 패턴 오류 · 시간 넘김은 위 상태 줄이 알리므로 여기서는 비운다. */
    private void showCheck(@Nullable RegexBench.Check check) {
        if (check == null || check.verdict() == null) {
            checkResult.setText("");
            return;
        }
        switch (check.verdict()) {
            case FULL -> {
                checkResult.setForeground(SUCCESS);
                checkResult.setText(BootServersBundle.message("regex.check.full"));
            }
            case PARTIAL -> {
                checkResult.setForeground(WARNING);
                checkResult.setText(BootServersBundle.message("regex.check.partial", check.part()));
            }
            case NONE -> {
                checkResult.setForeground(NamedColorUtil.getErrorForeground());
                checkResult.setText(BootServersBundle.message("regex.check.none"));
            }
        }
    }

    private void clearResult() {
        sampleArea.getHighlighter().removeAllHighlights();
        selectedHighlight = null;
        groupModel.setRowCount(0);
        rowRanges.clear();
        replacedArea.setText("");
        replacementError.setText("");
        tabs.setTitleAt(0, BootServersBundle.message("regex.tab.groups"));
    }

    private void show(RegexBench.Result result) {
        clearResult();
        status.setForeground(UIUtil.getLabelForeground());
        switch (result.problem()) {
            case SYNTAX -> {
                status.setForeground(NamedColorUtil.getErrorForeground());
                status.setText(result.errorIndex() >= 0
                        ? BootServersBundle.message("regex.status.syntax.at", result.detail(), result.errorIndex() + 1)
                        : BootServersBundle.message("regex.status.syntax", result.detail()));
                return;
            }
            case TIMEOUT -> {
                status.setForeground(NamedColorUtil.getErrorForeground());
                status.setText(BootServersBundle.message("regex.status.timeout", TIMEOUT_MS / 1000.0));
                return;
            }
            case TOO_DEEP -> {
                status.setForeground(NamedColorUtil.getErrorForeground());
                status.setText(BootServersBundle.message("regex.status.deep"));
                return;
            }
            case REPLACEMENT -> replacementError.setText(BootServersBundle.message("regex.replacement.error", result.detail()));
            case NONE -> replacedArea.setText(result.replaced());
        }
        List<RegexBench.Match> matches = result.matches();
        status.setText(matches.isEmpty() ? BootServersBundle.message("regex.status.none")
                : result.truncated() ? BootServersBundle.message("regex.status.truncated", matches.size())
                : BootServersBundle.message("regex.status.count", matches.size()));
        Highlighter highlighter = sampleArea.getHighlighter();
        for (int i = 0; i < matches.size(); i++) {
            RegexBench.Match match = matches.get(i);
            try {
                highlighter.addHighlight(match.start(), match.end(), matchPainter);
            } catch (BadLocationException e) {
                // 계산 뒤 글이 바뀌면 generation 이 달라 여기 오지 않는다
                LOG.warn(e);
            }
            for (RegexBench.Group group : match.groups()) {
                Object[] row = new Object[COLUMN_COUNT];
                row[0] = i + 1;
                row[1] = group.index();
                row[2] = group.index() == 0 ? BootServersBundle.message("regex.group.whole") : group.name() != null ? group.name() : "";
                row[3] = group.value() != null ? group.value() : BootServersBundle.message("regex.group.unmatched");
                row[4] = group.start() >= 0 ? BootServersBundle.message("regex.group.range", group.start(), group.end()) : "";
                groupModel.addRow(row);
                rowRanges.add(group.start() >= 0 ? new int[]{group.start(), group.end()} : null);
            }
        }
        tabs.setTitleAt(0, BootServersBundle.message("regex.tab.groups.count", groupModel.getRowCount()));
    }

    /** 표에서 고른 줄의 범위를 시험 글에 다른 색으로 칠하고 그 자리로 굴린다. */
    private void showSelectedRow() {
        Highlighter highlighter = sampleArea.getHighlighter();
        if (selectedHighlight != null) {
            highlighter.removeHighlight(selectedHighlight);
            selectedHighlight = null;
        }
        int row = groupTable.getSelectedRow();
        if (row < 0 || row >= rowRanges.size() || rowRanges.get(row) == null) {
            return;
        }
        int[] range = rowRanges.get(row);
        try {
            selectedHighlight = highlighter.addHighlight(range[0], range[1], selectedPainter);
            Rectangle at = sampleArea.modelToView2D(range[0]).getBounds();
            sampleArea.scrollRectToVisible(at);
        } catch (BadLocationException e) {
            LOG.warn(e);
        }
    }

    private void copyCode() {
        CopyPasteManager.getInstance().setContents(new StringSelection(codeField.getText()));
        JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(BootServersBundle.message("regex.code.copied"), MessageType.INFO, null)
                .setFadeoutTime(COPIED_FADE_MS)
                .createBalloon()
                .show(new RelativePoint(copyButton, new Point(copyButton.getWidth() / 2, 0)), Balloon.Position.above);
    }
}
