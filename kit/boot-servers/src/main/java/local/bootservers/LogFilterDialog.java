package local.bootservers;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * 콘솔 한 개의 글을 레벨 · 검색어로 거른 줄 목록을 보이는 비모달 창.
 * 거르기는 풀 스레드에서 하고, 한 번에 하나만 돈다(도는 중에 조건이 바뀌면 끝난 뒤 한 번 더 돈다).
 * 「따라가기」 가 켜져 있으면 FOLLOW_INTERVAL_MS 마다 콘솔 글이 바뀌었는지 보고 다시 거른다.
 * 지난 거르기 뒤 콘솔 글이 끝에 붙기만 했으면 마지막 묶음부터만 거르고, 앞이 잘리거나 지워졌거나 조건이 바뀌면 전체를 거른다.
 */
final class LogFilterDialog extends DialogWrapper {

    private static final int FOLLOW_INTERVAL_MS = 1000;
    /** 한 줄에 보이는 최대 글자 수. 넘으면 줄임표. */
    private static final int MAX_SHOWN_CHARS = 1000;
    private static final String TAB_SHOWN = "    ";

    private static final Color ERROR_COLOR = new JBColor(new Color(0xC6, 0x28, 0x28), new Color(0xFF, 0x6B, 0x68));
    private static final Color WARN_COLOR = new JBColor(new Color(0xA0, 0x5A, 0x00), new Color(0xE5, 0xB5, 0x4A));
    private static final Color INFO_COLOR = new JBColor(new Color(0x1B, 0x5E, 0x20), new Color(0x8A, 0xC8, 0x7C));

    private final Editor editor;
    private final Map<LogFilter.Level, JBCheckBox> levelBoxes = new EnumMap<>(LogFilter.Level.class);
    private final JBTextField queryField = new JBTextField();
    private final JBCheckBox regexBox = new JBCheckBox(BootServersBundle.message("log.filter.regex"));
    private final JBCheckBox followBox = new JBCheckBox(BootServersBundle.message("log.filter.follow"), true);
    private final JBLabel status = new JBLabel();
    private final JBList<LogFilter.Line> list = new JBList<>();
    private final Timer timer;

    private long shownStamp = -1;
    private boolean running;
    private boolean pending;
    // 기다리는 거르기 가운데 전체 거르기(조건 변경)가 있었는지.
    private boolean pendingFull;
    // 지금 보이는 거른 줄과 그 다음 이어 거르기 지점. 이어 걸러도 되지 않는 상태면 resume 이 null.
    private List<LogFilter.Line> shownLines = List.of();
    private LogFilter.Resume resume;
    // 마지막으로 글을 떠 온 뒤 콘솔 글이 끝에 붙기만 했는지. 화면 스레드에서만 읽고 쓴다.
    private boolean appendedOnly;

    LogFilterDialog(Project project, Editor editor) {
        super(project, false);
        this.editor = editor;
        setModal(false);
        setTitle(BootServersBundle.message("log.filter.title"));
        getCancelAction().putValue(Action.NAME, BootServersBundle.message("common.close"));
        timer = new Timer(FOLLOW_INTERVAL_MS, e -> followTick());
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent event) {
                if (event.getOldLength() != 0 || event.getOffset() + event.getNewLength() != event.getDocument().getTextLength()) {
                    appendedOnly = false;
                }
            }
        }, getDisposable());
        init();
        refresh();
        timer.start();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        for (LogFilter.Level level : LogFilter.Level.values()) {
            JBCheckBox box = new JBCheckBox(level.name(), true);
            box.addActionListener(e -> refresh());
            levelBoxes.put(level, box);
            top.add(box);
        }
        queryField.setColumns(24);
        queryField.getEmptyText().setText(BootServersBundle.message("log.filter.query.hint"));
        queryField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                refresh();
            }
        });
        regexBox.addActionListener(e -> refresh());
        top.add(queryField);
        top.add(regexBox);
        top.add(followBox);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(top);
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)));
        statusRow.add(status);
        north.add(statusRow);

        list.setFont(editor.getColorsScheme().getFont(EditorFontType.PLAIN));
        list.setCellRenderer(new LineRenderer());
        list.getEmptyText().setText(BootServersBundle.message("log.filter.empty"));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    jumpTo(list.getSelectedValue());
                }
            }
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JBScrollPane(list), BorderLayout.CENTER);
        panel.setPreferredSize(JBUI.size(900, 500));
        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction()};
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "KingGura.LogFilter";
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return queryField;
    }

    @Override
    protected void dispose() {
        timer.stop();
        super.dispose();
    }

    private void followTick() {
        if (followBox.isSelected() && !editor.isDisposed() && editor.getDocument().getModificationStamp() != shownStamp) {
            refresh(true);
        }
    }

    /** 지금 조건으로 전체를 다시 거른다. 화면 스레드에서 부른다. */
    private void refresh() {
        refresh(false);
    }

    /** tail 이 참이면 콘솔 글이 끝에 붙기만 했을 때 마지막 묶음부터만 거른다. 화면 스레드에서 부른다. */
    private void refresh(boolean tail) {
        if (editor.isDisposed()) {
            status.setText(BootServersBundle.message("log.filter.console.closed"));
            timer.stop();
            return;
        }
        if (running) {
            pending = true;
            pendingFull |= !tail;
            return;
        }
        running = true;
        boolean full = !tail || pendingFull;
        pending = false;
        pendingFull = false;
        Document doc = editor.getDocument();
        long stamp = doc.getModificationStamp();
        CharSequence text = doc.getImmutableCharSequence();
        LogFilter.Resume from = !full && appendedOnly && resume != null ? resume : LogFilter.Resume.START;
        List<LogFilter.Line> head = new ArrayList<>();
        for (LogFilter.Line line : shownLines) {
            if (line.number() < from.line()) {
                head.add(line);
            }
        }
        appendedOnly = true;
        Set<LogFilter.Level> levels = selectedLevels();
        String query = queryField.getText();
        boolean regex = regexBox.isSelected();
        ApplicationManager.getApplication().executeOnPooledThread(() -> filterInBackground(text, from, head, levels, query, regex, stamp));
    }

    /** head(from 앞의 거른 줄) 뒤에 from 부터 거른 줄을 잇는다. */
    private void filterInBackground(CharSequence text, LogFilter.Resume from, List<LogFilter.Line> head,
                                    Set<LogFilter.Level> levels, String query, boolean regex, long stamp) {
        LogFilter.Result result = null;
        String error = null;
        try {
            result = LogFilter.filterFrom(text, from, levels, query, regex);
            head.addAll(result.lines());
        } catch (PatternSyntaxException ex) {
            error = BootServersBundle.message("log.filter.regex.invalid", ex.getDescription());
        }
        LogFilter.Resume next = result == null ? null : result.resume();
        String message = error;
        ApplicationManager.getApplication().invokeLater(() -> applyResult(head, next, message, stamp), ModalityState.any());
    }

    private void applyResult(List<LogFilter.Line> lines, LogFilter.Resume next, String error, long stamp) {
        running = false;
        if (isDisposed()) {
            return;
        }
        shownStamp = stamp;
        resume = next;
        if (error != null) {
            status.setText(error);
            status.setForeground(ERROR_COLOR);
        } else {
            shownLines = lines;
            status.setText(BootServersBundle.message("log.filter.count", lines.size()));
            status.setForeground(JBColor.foreground());
            list.setModel(new CollectionListModel<>(lines));
            if (followBox.isSelected() && !lines.isEmpty()) {
                list.ensureIndexIsVisible(lines.size() - 1);
            }
        }
        if (pending) {
            refresh(true);
        }
    }

    private Set<LogFilter.Level> selectedLevels() {
        Set<LogFilter.Level> levels = EnumSet.noneOf(LogFilter.Level.class);
        for (Map.Entry<LogFilter.Level, JBCheckBox> e : levelBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                levels.add(e.getKey());
            }
        }
        return levels;
    }

    /** 원래 콘솔에서 그 줄을 골라 가운데로 보인다. 콘솔 앞부분이 잘려 줄이 밀렸으면 알리기만 한다. */
    private void jumpTo(LogFilter.Line line) {
        if (line == null || editor.isDisposed()) {
            return;
        }
        Document doc = editor.getDocument();
        int n = line.number();
        if (n >= doc.getLineCount()) {
            status.setText(BootServersBundle.message("log.filter.line.gone"));
            return;
        }
        int start = doc.getLineStartOffset(n);
        int end = doc.getLineEndOffset(n);
        if (!doc.getImmutableCharSequence().subSequence(start, end).toString().equals(line.text())) {
            status.setText(BootServersBundle.message("log.filter.line.gone"));
            return;
        }
        editor.getCaretModel().moveToOffset(start);
        editor.getSelectionModel().setSelection(start, end);
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    }

    private static Color colorOf(LogFilter.Level level) {
        if (level == null) {
            return JBColor.foreground();
        }
        return switch (level) {
            case ERROR -> ERROR_COLOR;
            case WARN -> WARN_COLOR;
            case INFO -> INFO_COLOR;
            case DEBUG -> JBColor.GRAY;
        };
    }

    private static final class LineRenderer extends ColoredListCellRenderer<LogFilter.Line> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends LogFilter.Line> list, LogFilter.Line value,
                                             int index, boolean selected, boolean hasFocus) {
            setFont(list.getFont());
            append(String.format("%6d  ", value.number() + 1), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            String text = value.text().replace("\t", TAB_SHOWN);
            if (text.length() > MAX_SHOWN_CHARS) {
                text = text.substring(0, MAX_SHOWN_CHARS) + "…";
            }
            append(text, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, colorOf(value.level())));
        }
    }
}
