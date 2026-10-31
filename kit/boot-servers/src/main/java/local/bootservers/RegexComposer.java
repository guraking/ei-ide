package local.bootservers;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import local.bootservers.RegexBench.CharKind;
import local.bootservers.RegexBench.Need;
import local.bootservers.RegexBench.Repeat;
import local.bootservers.RegexBench.Source;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 정규식 작업대의 「조합」: 글 모양(전체 · 찾기) · 꼭 들어갈 것 · 조각 목록(왼쪽)과 고른 줄의 설정(오른쪽).
 * 고르기만으로 RegexBench.compose 에 넘길 조합을 만든다. 무엇이든 바뀌면 onChange 를 부른다.
 * 조합에 고칠 점이 있으면 pattern() 은 null 이고 아래 안내 줄에 그 점을 보인다.
 */
final class RegexComposer {

    private static final int DETAIL_WIDTH = 560;
    private static final int LIST_HEIGHT = 200;
    private static final int LIST_WIDTH = 300;
    private static final int CHILD_INDENT = 18;
    private static final int TEXT_COLUMNS = 10;
    private static final int NAME_COLUMNS = 8;
    private static final int MAX_REPEAT = 999;
    private static final int DEFAULT_MIN_LENGTH = 8;

    /** 반복 · 꺼내기 편집 값. */
    private static final class RepeatModel {
        Repeat repeat = Repeat.ONCE;
        int n = 1;
        int m = 2;
        boolean lazy;
        boolean capture;
        String name = "";

        RegexBench.Repeating toRecord() {
            return new RegexBench.Repeating(repeat, n, m, lazy, capture, name);
        }
    }

    private static final class PieceModel {
        Source source = Source.KINDS;
        final EnumSet<CharKind> kinds = EnumSet.noneOf(CharKind.class);
        String text = "";
        String before = "";
        String after = "";
        final RepeatModel repeating = new RepeatModel();

        RegexBench.Piece toRecord() {
            return new RegexBench.Piece(source, EnumSet.copyOf(kinds), text, repeating.toRecord(), before, after);
        }
    }

    private static final class BunchModel {
        final List<PieceModel> pieces = new ArrayList<>();
        boolean anyOf;
        final RepeatModel repeating = new RepeatModel();
    }

    /** 목록 한 줄. item 은 PieceModel 또는 BunchModel, owner 는 묶음 안 조각일 때 그 묶음. */
    private record Row(Object item, @Nullable BunchModel owner) {}

    private final Runnable onChange;
    private final List<Object> parts = new ArrayList<>();
    private final CollectionListModel<Row> rowModel = new CollectionListModel<>();
    private final JBList<Row> rowList = new JBList<>(rowModel);

    private final JBRadioButton wholeRadio = new JBRadioButton(BootServersBundle.message("regex.compose.whole"), true);
    private final JBRadioButton findRadio = new JBRadioButton(BootServersBundle.message("regex.compose.find"));
    private final JBCheckBox wordOnlyBox = new JBCheckBox(BootServersBundle.message("regex.compose.word"));
    private final JBLabel needsLabel = new JBLabel(BootServersBundle.message("regex.compose.needs"));
    private final Map<Need, JBCheckBox> needBoxes = new EnumMap<>(Need.class);
    private final JBCheckBox minLengthBox = new JBCheckBox(BootServersBundle.message("regex.compose.min.length"));
    private final JBIntSpinner minLengthSpinner = new JBIntSpinner(DEFAULT_MIN_LENGTH, 1, MAX_REPEAT);
    private final JBLabel minLengthTail = new JBLabel(BootServersBundle.message("regex.compose.min.length.tail"));
    private final JPanel needsRow = row();

    private final JBLabel emptyDetail = new JBLabel(BootServersBundle.message("regex.compose.detail.empty"));
    private final JBLabel bunchLabel = new JBLabel(BootServersBundle.message("regex.compose.bunch"));
    private final JBRadioButton sequenceRadio = new JBRadioButton(BootServersBundle.message("regex.compose.bunch.sequence"), true);
    private final JBRadioButton anyOfRadio = new JBRadioButton(BootServersBundle.message("regex.compose.bunch.anyof"));
    private final JPanel bunchRow = row(sequenceRadio, anyOfRadio);
    private final JBLabel sourceLabel = new JBLabel(BootServersBundle.message("regex.compose.source"));
    private final Map<Source, JBRadioButton> sourceRadios = new EnumMap<>(Source.class);
    private final JPanel sourceRow = row();
    private final Map<CharKind, JBCheckBox> kindBoxes = new EnumMap<>(CharKind.class);
    private final JBLabel extraLabel = new JBLabel(BootServersBundle.message("regex.compose.extra"));
    private final JBTextField extraField = new JBTextField(TEXT_COLUMNS);
    private final JPanel kindsRow = row();
    private final JBLabel wordsLabel = new JBLabel(BootServersBundle.message("regex.compose.words"));
    private final JBTextField wordsField = new JBTextField();
    private final JBLabel exceptLabel = new JBLabel(BootServersBundle.message("regex.compose.except"));
    private final JBTextField exceptField = new JBTextField();
    private final JBLabel repeatLabel = new JBLabel(BootServersBundle.message("regex.compose.repeat"));
    private final ComboBox<Repeat> repeatBox = new ComboBox<>(Repeat.values());
    private final JBLabel nLabel = new JBLabel(BootServersBundle.message("regex.compose.n"));
    private final JBIntSpinner nSpinner = new JBIntSpinner(1, 0, MAX_REPEAT);
    private final JBLabel mLabel = new JBLabel(BootServersBundle.message("regex.compose.m"));
    private final JBIntSpinner mSpinner = new JBIntSpinner(2, 0, MAX_REPEAT);
    private final JBCheckBox lazyBox = new JBCheckBox(BootServersBundle.message("regex.compose.lazy"));
    private final JPanel repeatRow = row(repeatBox, nLabel, nSpinner, mLabel, mSpinner, lazyBox);
    private final JBLabel captureLabel = new JBLabel(BootServersBundle.message("regex.compose.capture"));
    private final JBCheckBox captureBox = new JBCheckBox(BootServersBundle.message("regex.compose.capture.box"));
    private final JBLabel nameLabel = new JBLabel(BootServersBundle.message("regex.compose.name"));
    private final JBTextField nameField = new JBTextField(NAME_COLUMNS);
    private final JPanel captureRow = row(captureBox, nameLabel, nameField);
    private final JBLabel aroundLabel = new JBLabel(BootServersBundle.message("regex.compose.around"));
    private final JBTextField beforeField = new JBTextField(TEXT_COLUMNS / 2);
    private final JBTextField afterField = new JBTextField(TEXT_COLUMNS / 2);
    private final JPanel aroundRow = row(new JBLabel(BootServersBundle.message("regex.compose.before")), beforeField,
            new JBLabel(BootServersBundle.message("regex.compose.before.tail")),
            new JBLabel(BootServersBundle.message("regex.compose.after")), afterField,
            new JBLabel(BootServersBundle.message("regex.compose.after.tail")));
    private final JBLabel problemLabel = new JBLabel();
    private final JComponent component;

    // 고른 줄의 값을 칸에 채우는 동안 칸 리스너가 모델을 덮지 않게 한다
    private boolean loading;

    RegexComposer(Runnable onChange) {
        this.onChange = onChange;
        component = build();
        showDetail();
        showShape();
        problemLabel.setText(BootServersBundle.message("regex.compose.start"));
    }

    JComponent component() {
        return component;
    }

    /** 조각이나 조건이 하나라도 있는지. 없으면 패턴 칸을 건드리지 않는다. */
    boolean isEmpty() {
        return parts.isEmpty() && needs().isEmpty() && !minLengthBox.isSelected();
    }

    /** 지금 조합의 패턴. 고칠 점이 있으면 null. */
    @Nullable String pattern() {
        return problem() == null ? RegexBench.compose(records(), shape()) : null;
    }

    private @Nullable RegexBench.PartProblem problem() {
        for (RegexBench.Part part : records()) {
            RegexBench.PartProblem problem = RegexBench.problem(part);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private List<RegexBench.Part> records() {
        List<RegexBench.Part> records = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof BunchModel bunch) {
                List<RegexBench.Piece> pieces = new ArrayList<>();
                for (PieceModel piece : bunch.pieces) {
                    pieces.add(piece.toRecord());
                }
                records.add(new RegexBench.Bunch(pieces, bunch.anyOf, bunch.repeating.toRecord()));
            } else {
                records.add(((PieceModel) part).toRecord());
            }
        }
        return records;
    }

    private RegexBench.Shape shape() {
        return new RegexBench.Shape(wholeRadio.isSelected(), wordOnlyBox.isSelected(), needs(),
                minLengthBox.isSelected() ? minLengthSpinner.getNumber() : 0);
    }

    private EnumSet<Need> needs() {
        EnumSet<Need> needs = EnumSet.noneOf(Need.class);
        for (Map.Entry<Need, JBCheckBox> e : needBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                needs.add(e.getKey());
            }
        }
        return needs;
    }

    private JComponent build() {
        ButtonGroup shapeGroup = new ButtonGroup();
        shapeGroup.add(wholeRadio);
        shapeGroup.add(findRadio);
        wholeRadio.addActionListener(e -> shapeChanged());
        findRadio.addActionListener(e -> shapeChanged());
        wordOnlyBox.addActionListener(e -> shapeChanged());
        JPanel shapeRow = row(wholeRadio, findRadio, wordOnlyBox);

        needsRow.add(needsLabel);
        for (Need need : Need.values()) {
            JBCheckBox box = new JBCheckBox(BootServersBundle.message("regex.need." + need.name()));
            box.addActionListener(e -> changed());
            needBoxes.put(need, box);
            needsRow.add(box);
        }
        minLengthBox.addActionListener(e -> shapeChanged());
        minLengthSpinner.addChangeListener(e -> changed());
        needsRow.add(minLengthBox);
        needsRow.add(minLengthSpinner);
        needsRow.add(minLengthTail);

        rowList.setCellRenderer(new RowRenderer());
        rowList.getEmptyText().setText(BootServersBundle.message("regex.compose.list.empty"));
        rowList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        JPanel list = ToolbarDecorator.createDecorator(rowList)
                .setAddAction(button -> JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(List.of(BootServersBundle.message("regex.compose.add.piece"), BootServersBundle.message("regex.compose.add.bunch")))
                        .setItemChosenCallback(choice -> add(choice.equals(BootServersBundle.message("regex.compose.add.bunch"))))
                        .createPopup()
                        .show(button.getPreferredPopupPoint()))
                .setRemoveAction(button -> remove())
                .setMoveUpAction(button -> move(-1))
                .setMoveDownAction(button -> move(1))
                .createPanel();
        list.setPreferredSize(JBUI.size(LIST_WIDTH, LIST_HEIGHT));

        ButtonGroup bunchGroup = new ButtonGroup();
        bunchGroup.add(sequenceRadio);
        bunchGroup.add(anyOfRadio);
        sequenceRadio.addActionListener(e -> edit());
        anyOfRadio.addActionListener(e -> edit());
        ButtonGroup sourceGroup = new ButtonGroup();
        for (Source source : Source.values()) {
            JBRadioButton radio = new JBRadioButton(BootServersBundle.message("regex.source." + source.name()));
            radio.addActionListener(e -> edit());
            sourceGroup.add(radio);
            sourceRadios.put(source, radio);
            sourceRow.add(radio);
        }
        for (CharKind kind : CharKind.values()) {
            JBCheckBox box = new JBCheckBox(BootServersBundle.message("regex.kind." + kind.name()));
            box.addActionListener(e -> edit());
            kindBoxes.put(kind, box);
            kindsRow.add(box);
        }
        kindsRow.add(extraLabel);
        kindsRow.add(extraField);
        extraField.getEmptyText().setText(BootServersBundle.message("regex.compose.extra.empty"));
        wordsField.getEmptyText().setText(BootServersBundle.message("regex.compose.words.empty"));
        exceptField.getEmptyText().setText(BootServersBundle.message("regex.compose.except.empty"));
        nameField.getEmptyText().setText(BootServersBundle.message("regex.compose.name.empty"));
        repeatBox.setRenderer(SimpleListCellRenderer.create("", r -> BootServersBundle.message("regex.repeat." + r.name())));
        repeatBox.addActionListener(e -> edit());
        nSpinner.addChangeListener(e -> edit());
        mSpinner.addChangeListener(e -> edit());
        lazyBox.setToolTipText(BootServersBundle.message("regex.compose.lazy.tip"));
        lazyBox.addActionListener(e -> edit());
        captureBox.addActionListener(e -> edit());
        for (JBTextField field : List.of(extraField, wordsField, exceptField, nameField, beforeField, afterField)) {
            field.getDocument().addDocumentListener(new DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull DocumentEvent e) {
                    edit();
                }
            });
        }

        JPanel detail = FormBuilder.createFormBuilder()
                .addComponent(emptyDetail)
                .addLabeledComponent(bunchLabel, bunchRow)
                .addLabeledComponent(sourceLabel, sourceRow)
                .addComponentToRightColumn(kindsRow)
                .addLabeledComponent(wordsLabel, wordsField)
                .addLabeledComponent(exceptLabel, exceptField)
                .addLabeledComponent(repeatLabel, repeatRow)
                .addLabeledComponent(captureLabel, captureRow)
                .addLabeledComponent(aroundLabel, aroundRow)
                .getPanel();
        detail.setBorder(JBUI.Borders.emptyLeft(UIUtil.DEFAULT_HGAP));
        detail.setPreferredSize(JBUI.size(DETAIL_WIDTH, LIST_HEIGHT));

        JPanel master = new JPanel(new BorderLayout());
        master.add(list, BorderLayout.WEST);
        master.add(detail, BorderLayout.CENTER);

        problemLabel.setAllowAutoWrapping(true);
        return FormBuilder.createFormBuilder()
                .addComponent(shapeRow)
                .addComponent(needsRow)
                .addComponent(master)
                .addComponent(problemLabel)
                .getPanel();
    }

    private static JPanel row(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(UIUtil.DEFAULT_HGAP), 0));
        for (JComponent c : components) {
            row.add(c);
        }
        return row;
    }

    private void shapeChanged() {
        showShape();
        changed();
    }

    /** 「꼭 들어갈 것」 은 글 전체 모양일 때만, 「낱말 단위로만」 은 찾기일 때만 보인다. */
    private void showShape() {
        boolean whole = wholeRadio.isSelected();
        needsRow.setVisible(whole);
        wordOnlyBox.setVisible(!whole);
        minLengthSpinner.setVisible(minLengthBox.isSelected());
        minLengthTail.setVisible(minLengthBox.isSelected());
        component.revalidate();
    }

    private @Nullable Row selectedRow() {
        return rowList.getSelectedValue();
    }

    /** 고른 줄 뒤에 조각 또는 묶음(조각 하나 든)을 더하고 그 조각을 고른다. 묶음 안에서 조각을 더하면 그 묶음에 들어간다. */
    private void add(boolean bunch) {
        PieceModel piece = new PieceModel();
        Row selected = selectedRow();
        if (!bunch && selected != null && (selected.owner() != null || selected.item() instanceof BunchModel)) {
            BunchModel owner = selected.owner() != null ? selected.owner() : (BunchModel) selected.item();
            int at = selected.owner() != null ? owner.pieces.indexOf(selected.item()) + 1 : owner.pieces.size();
            owner.pieces.add(at, piece);
        } else {
            Object top = selected == null ? null : selected.owner() != null ? selected.owner() : selected.item();
            int at = top == null ? parts.size() : parts.indexOf(top) + 1;
            if (bunch) {
                BunchModel model = new BunchModel();
                model.pieces.add(piece);
                parts.add(at, model);
            } else {
                parts.add(at, piece);
            }
        }
        refreshRows(piece);
        changed();
    }

    private void remove() {
        Row selected = selectedRow();
        if (selected == null) {
            return;
        }
        int index = rowList.getSelectedIndex();
        if (selected.owner() != null) {
            selected.owner().pieces.remove(selected.item());
        } else {
            parts.remove(selected.item());
        }
        refreshRows(null);
        if (!rowModel.isEmpty()) {
            rowList.setSelectedIndex(Math.min(index, rowModel.getSize() - 1));
        }
        changed();
    }

    /** 같은 단(맨 위 또는 같은 묶음 안)에서 한 칸 옮긴다. */
    private void move(int step) {
        Row selected = selectedRow();
        if (selected == null) {
            return;
        }
        List<?> siblings = selected.owner() != null ? selected.owner().pieces : parts;
        int from = siblings.indexOf(selected.item());
        int to = from + step;
        if (to < 0 || to >= siblings.size()) {
            return;
        }
        Collections.swap(siblings, from, to);
        refreshRows(selected.item());
        changed();
    }

    /** parts 를 목록 줄로 다시 펼치고 select 줄을 고른다. */
    private void refreshRows(@Nullable Object select) {
        List<Row> rows = new ArrayList<>();
        for (Object part : parts) {
            rows.add(new Row(part, null));
            if (part instanceof BunchModel bunch) {
                for (PieceModel piece : bunch.pieces) {
                    rows.add(new Row(piece, bunch));
                }
            }
        }
        rowModel.replaceAll(rows);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item() == select) {
                rowList.setSelectedIndex(i);
                return;
            }
        }
        showDetail();
    }

    /** 고른 줄의 값을 오른쪽 칸에 채우고, 그 줄 종류 · 고른 값에 쓰는 칸만 보인다. */
    private void showDetail() {
        Row selected = selectedRow();
        loading = true;
        try {
            boolean bunch = selected != null && selected.item() instanceof BunchModel;
            boolean piece = selected != null && selected.item() instanceof PieceModel;
            emptyDetail.setVisible(selected == null);
            bunchLabel.setVisible(bunch);
            bunchRow.setVisible(bunch);
            RepeatModel repeating = null;
            if (bunch) {
                BunchModel model = (BunchModel) selected.item();
                anyOfRadio.setSelected(model.anyOf);
                sequenceRadio.setSelected(!model.anyOf);
                repeating = model.repeating;
            }
            PieceModel model = piece ? (PieceModel) selected.item() : null;
            if (model != null) {
                sourceRadios.get(model.source).setSelected(true);
                for (Map.Entry<CharKind, JBCheckBox> e : kindBoxes.entrySet()) {
                    e.getValue().setSelected(model.kinds.contains(e.getKey()));
                }
                setText(extraField, model.source == Source.KINDS ? model.text : "");
                setText(wordsField, model.source == Source.WORDS ? model.text : "");
                setText(exceptField, model.source == Source.EXCEPT ? model.text : "");
                setText(beforeField, model.before);
                setText(afterField, model.after);
                repeating = model.repeating;
            }
            sourceLabel.setVisible(piece);
            sourceRow.setVisible(piece);
            kindsRow.setVisible(piece && model.source == Source.KINDS);
            wordsLabel.setVisible(piece && model.source == Source.WORDS);
            wordsField.setVisible(piece && model.source == Source.WORDS);
            exceptLabel.setVisible(piece && model.source == Source.EXCEPT);
            exceptField.setVisible(piece && model.source == Source.EXCEPT);
            aroundLabel.setVisible(piece);
            aroundRow.setVisible(piece);
            boolean any = repeating != null;
            repeatLabel.setVisible(any);
            repeatRow.setVisible(any);
            captureLabel.setVisible(any);
            captureRow.setVisible(any);
            if (any) {
                repeatBox.setSelectedItem(repeating.repeat);
                nSpinner.setNumber(repeating.n);
                mSpinner.setNumber(repeating.m);
                lazyBox.setSelected(repeating.lazy);
                captureBox.setSelected(repeating.capture);
                setText(nameField, repeating.name);
                showRepeatFields(repeating);
            }
        } finally {
            loading = false;
        }
        component.revalidate();
        component.repaint();
    }

    private static void setText(JBTextField field, String text) {
        if (!field.getText().equals(text)) {
            field.setText(text);
        }
    }

    private void showRepeatFields(RepeatModel repeating) {
        boolean n = repeating.repeat == Repeat.EXACTLY || repeating.repeat == Repeat.AT_LEAST || repeating.repeat == Repeat.RANGE;
        nLabel.setVisible(n);
        nSpinner.setVisible(n);
        mLabel.setVisible(repeating.repeat == Repeat.RANGE);
        mSpinner.setVisible(repeating.repeat == Repeat.RANGE);
        // 한 번 · 정확히 N번은 짧게 · 길게가 같다
        lazyBox.setVisible(repeating.repeat != Repeat.ONCE && repeating.repeat != Repeat.EXACTLY);
        nameLabel.setVisible(repeating.capture);
        nameField.setVisible(repeating.capture);
    }

    /** 오른쪽 칸의 값을 고른 줄의 모델에 적는다. 글자 방식이 바뀌면 그 방식의 칸만 보이게 다시 그린다. */
    private void edit() {
        Row selected = selectedRow();
        if (loading || selected == null) {
            return;
        }
        RepeatModel repeating;
        boolean redraw = false;
        if (selected.item() instanceof BunchModel bunch) {
            bunch.anyOf = anyOfRadio.isSelected();
            repeating = bunch.repeating;
        } else {
            PieceModel piece = (PieceModel) selected.item();
            Source source = piece.source;
            for (Map.Entry<Source, JBRadioButton> e : sourceRadios.entrySet()) {
                if (e.getValue().isSelected()) {
                    source = e.getKey();
                }
            }
            redraw = source != piece.source;
            piece.source = source;
            piece.kinds.clear();
            for (Map.Entry<CharKind, JBCheckBox> e : kindBoxes.entrySet()) {
                if (e.getValue().isSelected()) {
                    piece.kinds.add(e.getKey());
                }
            }
            piece.text = switch (source) {
                case KINDS -> extraField.getText();
                case WORDS -> wordsField.getText();
                case EXCEPT -> exceptField.getText();
                case ANY -> "";
            };
            piece.before = beforeField.getText();
            piece.after = afterField.getText();
            repeating = piece.repeating;
        }
        Repeat repeat = (Repeat) repeatBox.getSelectedItem();
        repeating.repeat = repeat != null ? repeat : Repeat.ONCE;
        repeating.n = nSpinner.getNumber();
        repeating.m = mSpinner.getNumber();
        repeating.lazy = lazyBox.isSelected();
        repeating.capture = captureBox.isSelected();
        repeating.name = nameField.getText().strip();
        showRepeatFields(repeating);
        if (redraw) {
            showDetail();
        }
        rowList.repaint();
        changed();
    }

    /** 안내 줄을 고치고 onChange 를 부른다. */
    private void changed() {
        RegexBench.PartProblem problem = problem();
        if (problem != null) {
            problemLabel.setForeground(NamedColorUtil.getErrorForeground());
            problemLabel.setText(BootServersBundle.message("regex.problem." + problem.name()));
        } else {
            problemLabel.setForeground(NamedColorUtil.getInactiveTextColor());
            problemLabel.setText(isEmpty() ? BootServersBundle.message("regex.compose.start") : BootServersBundle.message("regex.compose.ok"));
        }
        onChange.run();
    }

    /** 줄 글: 「1. 한글 · 하나 이상 · 꺼냄」, 묶음은 「묶음(또는) · 없거나 여러 번」. 묶음 안 조각은 들여 쓴다. */
    private final class RowRenderer extends ColoredListCellRenderer<Row> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends Row> list, Row row, int index, boolean selected, boolean hasFocus) {
            setIpad(JBUI.insetsLeft(row.owner() != null ? CHILD_INDENT : 0));
            if (row.item() instanceof BunchModel bunch) {
                append(BootServersBundle.message(bunch.anyOf ? "regex.row.bunch.anyof" : "regex.row.bunch.sequence"));
                appendRepeat(bunch.repeating);
                return;
            }
            PieceModel piece = (PieceModel) row.item();
            append(describe(piece));
            appendRepeat(piece.repeating);
            if (!piece.before.isEmpty() || !piece.after.isEmpty()) {
                append(" · " + BootServersBundle.message("regex.row.around"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        private void appendRepeat(RepeatModel repeating) {
            append(" · " + BootServersBundle.message("regex.repeat." + repeating.repeat.name()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            if (repeating.capture) {
                append(" · " + BootServersBundle.message("regex.row.capture"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        private String describe(PieceModel piece) {
            return switch (piece.source) {
                case KINDS -> {
                    StringJoiner names = new StringJoiner(" · ");
                    for (CharKind kind : piece.kinds) {
                        names.add(BootServersBundle.message("regex.kind." + kind.name()));
                    }
                    if (!piece.text.isEmpty()) {
                        names.add("「" + piece.text + "」");
                    }
                    yield names.length() == 0 ? BootServersBundle.message("regex.row.kinds.empty") : names.toString();
                }
                case WORDS -> "「" + piece.text + "」";
                case ANY -> BootServersBundle.message("regex.source.ANY");
                case EXCEPT -> BootServersBundle.message("regex.row.except", piece.text);
            };
        }
    }
}
