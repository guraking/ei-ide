package local.bootservers.web;

import local.bootservers.BootServersBundle;
import local.bootservers.ui.DialogSize;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 자바스크립트 작업대. 모달이 아니라 편집기를 오가며 쓴다. 처음에는 화면의 FIRST_SIZE 비율로 열고, 바꾼 크기는 IntelliJ 가 기억한다.
 * 왼쪽: 「예제」(찾기 · 목록 · 설명) · 「고르기로 만들기」 탭. 가운데: 코드 칸 위, 입력 데이터(data) 칸 아래. 오른쪽: 실행 결과(출력 · 표).
 * 칸 사이 분할선은 끌어 바꾸고 비율을 기억한다. 코드는 설치본 Node 로 돌린다(JsBench, 시간 제한 JsBench.TIMEOUT_MS).
 * 편집기에서 열었으면 「편집기에 넣기」 가 열 때 고른 자리(고른 글이 없으면 커서)를 코드로 바꾼다.
 */
public final class JsBenchDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(JsBenchDialog.class);
    private static final double FIRST_SIZE = 0.7;
    private static final String KEY = "KingGura.JsBench.";
    private static final String DIMENSION_KEY = KEY + "size";
    private static final String TS_KEY = KEY + "typescript";
    private static final float LEFT_SPLIT = 0.25f;
    private static final float CENTER_SPLIT = 0.55f;
    private static final float CODE_SPLIT = 0.65f;
    private static final float RESULT_SPLIT = 0.5f;
    private static final int UNDO_LIMIT = 30;
    private static final int COLUMNS_DEBOUNCE_MS = 400;
    private static final int PADDING = 6;
    private static final String JS_NAME = "작업대.js";
    private static final String TS_NAME = "작업대.ts";
    private static final String DATA_NAME = "data.json";
    private static final String RUN_SHORTCUT = "control ENTER";
    private static final int OUTPUT_TAB = 0;
    private static final int TABLE_TAB = 1;
    private static final Color ERROR = JBColor.namedColor("Label.errorForeground", new JBColor(0xC7222D, 0xFF5261));
    private static final Color SUCCESS = JBColor.namedColor("Label.successForeground", new JBColor(0x208A3C, 0x5FB865));

    private final Project project;
    private final @Nullable Editor source;
    private final @Nullable RangeMarker target;
    private final List<JsExamples.Example> examples;
    private final JBRadioButton jsButton = new JBRadioButton("JavaScript");
    private final JBRadioButton tsButton = new JBRadioButton("TypeScript");
    private final EditorTextField code;
    private final EditorTextField data;
    private final SearchTextField search = new SearchTextField(false);
    private final JBList<JsExamples.Example> list = new JBList<>();
    private final JBTextArea description = new JBTextArea();
    private final JsPipelinePanel pipeline = new JsPipelinePanel(this::useGenerated);
    private final JBLabel status = new JBLabel();
    private final JButton nodeButton = new JButton(BootServersBundle.message("js.node.download"));
    private final JBTextArea output = new JBTextArea();
    private final ComboBox<String> tablePick = new ComboBox<>();
    private final DefaultTableModel tableModel = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JBLabel tableNote = new JBLabel();
    private final JBTabbedPane resultTabs = new JBTabbedPane();
    private final Deque<String[]> undo = new ArrayDeque<>();
    private final Action runAction = action(BootServersBundle.message("js.run"), this::run);
    private final Action stopAction = action(BootServersBundle.message("js.stop"), this::stop);
    private final Action undoAction = action(BootServersBundle.message("js.undo"), this::undo);
    private final Action insertAction = action(BootServersBundle.message("js.insert"), this::insert);
    private final Timer columnsTimer = new Timer(COLUMNS_DEBOUNCE_MS, e -> pipeline.setColumns(JsBench.columns(dataText())));
    private final List<JsBench.Event> tables = new ArrayList<>();
    /** 코드 칸 파일 이름(언어에 따라 JS_NAME · TS_NAME). 색칠 문법을 고른다. */
    private String codeName;
    private @Nullable JsBench.Run running;

    /** 작업대를 연다. editor 는 열 때의 편집기(없으면 null), initialCode 는 고른 글, typescript 는 처음 언어(null 이면 지난번). */
    public static void open(Project project, @Nullable Editor editor, @Nullable String initialCode, @Nullable Boolean typescript) {
        List<JsExamples.Example> examples;
        try {
            examples = JsExamples.load();
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn(e);
            examples = List.of();
        }
        new JsBenchDialog(project, editor, initialCode, typescript, examples).show();
    }

    private JsBenchDialog(Project project, @Nullable Editor editor, @Nullable String initialCode, @Nullable Boolean typescript,
                          List<JsExamples.Example> examples) {
        super(project, null, true, IdeModalityType.MODELESS);
        this.project = project;
        this.examples = examples;
        this.source = editor;
        this.target = editor == null ? null : marker(editor);
        boolean ts = typescript != null ? typescript : PropertiesComponent.getInstance().getBoolean(TS_KEY, false);
        codeName = ts ? TS_NAME : JS_NAME;
        code = editorField(() -> codeName, initialCode == null ? "" : initialCode, true);
        data = editorField(() -> DATA_NAME, "", false);
        (ts ? tsButton : jsButton).setSelected(true);
        setTitle(BootServersBundle.message("js.title"));
        setModal(false);
        getCancelAction().putValue(Action.NAME, BootServersBundle.message("js.close"));
        stopAction.setEnabled(false);
        undoAction.setEnabled(false);
        insertAction.setEnabled(target != null);
        init();
        if (examples.isEmpty()) {
            setStatus(BootServersBundle.message("js.examples.failed"), true);
        }
    }

    /** 열 때 고른 자리. 바꾼 글이 다시 그 자리에 들어가도록 양쪽으로 늘어나게 둔다. */
    private static RangeMarker marker(Editor editor) {
        int start = editor.getSelectionModel().getSelectionStart();
        int end = editor.getSelectionModel().getSelectionEnd();
        RangeMarker m = editor.getDocument().createRangeMarker(start, end);
        m.setGreedyToLeft(true);
        m.setGreedyToRight(true);
        return m;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return DIMENSION_KEY;
    }

    @Override
    public Dimension getInitialSize() {
        return DialogSize.ofScreen(FIRST_SIZE, FIRST_SIZE);
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{runAction, stopAction,
                action(BootServersBundle.message("js.copy.code"), () -> copy(code.getText(), "js.copied.code")),
                action(BootServersBundle.message("js.copy.output"), () -> copy(output.getText(), "js.copied.output")),
                undoAction};
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{insertAction, getCancelAction()};
    }

    /** 「편집기에 넣기」 는 파란 단추로 보이되 Enter 기본 단추로 두지 않는다(코드 칸에서 Enter 가 줄바꿈이 되게). */
    @Override
    protected JButton createJButtonForAction(Action action) {
        JButton b = super.createJButtonForAction(action);
        if (action == insertAction || action == runAction) {
            b.putClientProperty(com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        }
        return b;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        ButtonGroup languages = new ButtonGroup();
        languages.add(jsButton);
        languages.add(tsButton);
        jsButton.addActionListener(e -> languageChanged());
        tsButton.addActionListener(e -> languageChanged());
        JPanel languageRow = new JPanel(new GridLayout(1, 2));
        languageRow.add(jsButton);
        languageRow.add(tsButton);
        JPanel top = FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("js.language"), languageRow)
                .getPanel();
        top.setBorder(JBUI.Borders.emptyBottom(PADDING));

        JBTabbedPane left = new JBTabbedPane();
        left.setTabComponentInsets(JBUI.emptyInsets());
        left.addTab(BootServersBundle.message("js.tab.examples"), examplePanel());
        left.addTab(BootServersBundle.message("js.tab.pipeline"), pipeline.component());

        OnePixelSplitter codeAndData = splitter(true, CODE_SPLIT, "codeSplit",
                titled(BootServersBundle.message("js.code"), code, null),
                titled(BootServersBundle.message("js.data"), data, convertButton()));
        OnePixelSplitter center = splitter(false, CENTER_SPLIT, "centerSplit", codeAndData, resultPanel());
        OnePixelSplitter main = splitter(false, LEFT_SPLIT, "leftSplit", left, center);

        // 줄바꿈 글 칸이 좁을 때 높이가 커져 창이 스스로 늘어나지 않게 최소 크기를 0 으로 둔다.
        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, 0);
            }
        };
        root.add(top, BorderLayout.NORTH);
        root.add(main, BorderLayout.CENTER);

        DumbAwareAction.create(e -> run()).registerCustomShortcutSet(CustomShortcutSet.fromString(RUN_SHORTCUT), root, getDisposable());
        data.addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override
            public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent e) {
                columnsTimer.restart();
            }
        });
        columnsTimer.setRepeats(false);
        Disposable stopOnClose = () -> {
            columnsTimer.stop();
            stop();
        };
        com.intellij.openapi.util.Disposer.register(getDisposable(), stopOnClose);
        return root;
    }

    private JComponent examplePanel() {
        List<String> haystacks = new ArrayList<>(examples.size());
        for (JsExamples.Example e : examples) {
            haystacks.add((e.group() + " " + e.title() + " " + e.description() + " " + e.code()).toLowerCase(Locale.ROOT));
        }
        list.setListData(examples.toArray(new JsExamples.Example[0]));
        list.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends JsExamples.Example> l, JsExamples.Example e, int index,
                                                 boolean selected, boolean focus) {
                append(e.group() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append(e.title());
                if (e.viewOnly()) {
                    append("  " + BootServersBundle.message("js.example.view.only"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            }
        });
        list.getEmptyText().setText(BootServersBundle.message("js.examples.none"));
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                useExample(list.getSelectedValue());
            }
        });
        search.getTextEditor().getEmptyText().setText(BootServersBundle.message("js.examples.search"));
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                filter(haystacks);
            }
        });
        description.setEditable(false);
        description.setFont(JBFont.label());
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setBorder(JBUI.Borders.empty(PADDING));
        description.setText(BootServersBundle.message("js.examples.intro", examples.size()));
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        panel.add(search, BorderLayout.NORTH);
        panel.add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER);
        panel.add(description, BorderLayout.SOUTH);
        panel.setBorder(JBUI.Borders.empty(PADDING));
        return panel;
    }

    /** 찾기: 묶음 · 제목 · 설명 · 코드에 띄어 쓴 낱말을 모두 담은 예제만 남긴다(대소문자 무시). */
    private void filter(List<String> haystacks) {
        String[] words = search.getText().strip().toLowerCase(Locale.ROOT).split("\\s+");
        JsExamples.Example keep = list.getSelectedValue();
        List<JsExamples.Example> shown = new ArrayList<>();
        for (int i = 0; i < examples.size(); i++) {
            boolean all = true;
            for (String w : words) {
                if (!w.isEmpty() && !haystacks.get(i).contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                shown.add(examples.get(i));
            }
        }
        list.setListData(shown.toArray(new JsExamples.Example[0]));
        if (keep != null && shown.contains(keep)) {
            list.setSelectedValue(keep, true);
        }
    }

    private JComponent resultPanel() {
        output.setEditable(false);
        output.setFont(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        output.setBorder(JBUI.Borders.empty(PADDING));
        output.getEmptyText().setText(BootServersBundle.message("js.output.empty"));
        JBTable table = new JBTable(tableModel);
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getEmptyText().setText(BootServersBundle.message("js.table.empty"));
        tablePick.addActionListener(e -> showTable(tablePick.getSelectedIndex()));
        tableNote.setForeground(UIUtil.getContextHelpForeground());
        JPanel tablePanel = new JPanel(new BorderLayout());
        JPanel tableTop = FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("js.table.pick"), tablePick)
                .addComponent(tableNote)
                .getPanel();
        tableTop.setBorder(JBUI.Borders.empty(PADDING));
        tablePanel.add(tableTop, BorderLayout.NORTH);
        tablePanel.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
        resultTabs.setTabComponentInsets(JBUI.emptyInsets());
        resultTabs.addTab(BootServersBundle.message("js.tab.output"), ScrollPaneFactory.createScrollPane(output));
        resultTabs.addTab(BootServersBundle.message("js.tab.table"), tablePanel);
        nodeButton.setVisible(false);
        nodeButton.addActionListener(e -> WebInstaller.installNode(project));
        JPanel head = new JPanel(new BorderLayout(JBUI.scale(PADDING), 0));
        head.add(status, BorderLayout.CENTER);
        head.add(nodeButton, BorderLayout.EAST);
        head.setBorder(JBUI.Borders.empty(0, PADDING, PADDING, PADDING));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(head, BorderLayout.NORTH);
        panel.add(resultTabs, BorderLayout.CENTER);
        return titled(BootServersBundle.message("js.result"), panel, null);
    }

    /** 입력 데이터 「바꾸기」: 앞 셋은 데이터 칸을 바꾸고, 뒤 둘은 만든 코드를 출력에 보인다. */
    private JButton convertButton() {
        JButton button = new JButton(BootServersBundle.message("js.convert"));
        button.addActionListener(e -> {
            List<JsBench.Mode> modes = List.of(JsBench.Mode.TSV, JsBench.Mode.CAMEL, JsBench.Mode.PRETTY, JsBench.Mode.TS, JsBench.Mode.PROPS);
            JBPopupFactory.getInstance().createPopupChooserBuilder(modes)
                    .setRenderer(new ColoredListCellRenderer<JsBench.Mode>() {
                        @Override
                        protected void customizeCellRenderer(@NotNull JList<? extends JsBench.Mode> l, JsBench.Mode m, int index,
                                                             boolean selected, boolean focus) {
                            append(BootServersBundle.message("js.convert." + m.arg));
                        }
                    })
                    .setItemChosenCallback(this::convert)
                    .createPopup()
                    .show(new RelativePoint(button, new java.awt.Point(0, button.getHeight())));
        });
        return button;
    }

    private static JComponent titled(String title, JComponent body, @Nullable JComponent extra) {
        JBLabel label = new JBLabel(title);
        label.setForeground(UIUtil.getContextHelpForeground());
        JPanel head = new JPanel(new BorderLayout(JBUI.scale(PADDING), 0));
        head.add(label, BorderLayout.CENTER);
        if (extra != null) {
            head.add(extra, BorderLayout.EAST);
        }
        head.setBorder(JBUI.Borders.empty(PADDING, PADDING, PADDING / 2, PADDING));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(head, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private static OnePixelSplitter splitter(boolean vertical, float proportion, String key, JComponent first, JComponent second) {
        OnePixelSplitter s = new OnePixelSplitter(vertical, proportion);
        s.setSplitterProportionKey(KEY + key);
        s.setHonorComponentsMinimumSize(false);
        s.setFirstComponent(first);
        s.setSecondComponent(second);
        return s;
    }

    /**
     * 코드 · 데이터 칸. 이름(.js · .ts · .json)으로 TextMate 가 문법을 골라 색칠한다.
     * 편집기는 문서를 바꿀 때마다 새로 만들어지므로 이름은 그때그때 name 에서 읽는다.
     */
    private EditorTextField editorField(java.util.function.Supplier<String> name, String text, boolean lineNumbers) {
        Document document = EditorFactory.getInstance().createDocument(text);
        EditorTextField field = new EditorTextField(document, project, fileType(name.get()), false, false);
        field.addSettingsProvider(e -> {
            e.setVerticalScrollbarVisible(true);
            e.setHorizontalScrollbarVisible(true);
            e.getSettings().setLineNumbersShown(lineNumbers);
            e.getSettings().setFoldingOutlineShown(false);
            e.getSettings().setAdditionalLinesCount(0);
            e.getContentComponent().setBorder(JBUI.Borders.empty(PADDING));
            e.setHighlighter(com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(project, new LightVirtualFile(name.get(), fileType(name.get()), "")));
        });
        field.setPlaceholder(BootServersBundle.message(lineNumbers ? "js.code.empty" : "js.data.empty"));
        field.setShowPlaceholderWhenFocused(true);
        return field;
    }

    /** 확장자 표에 없으면(TextMate 가 맡는 .js · .ts) 메모리 파일을 감지시켜 TextMate 종류를 받는다. 감지도 안 되면 색 없는 글. */
    private static FileType fileType(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1);
        FileType type = FileTypeManager.getInstance().getFileTypeByExtension(extension);
        if (type != UnknownFileType.INSTANCE) {
            return type;
        }
        return FileTypeRegistry.getInstance().getFileTypeByFile(new LightVirtualFile(name, ""));
    }

    private void languageChanged() {
        boolean ts = tsButton.isSelected();
        PropertiesComponent.getInstance().setValue(TS_KEY, ts, false);
        codeName = ts ? TS_NAME : JS_NAME;
        Document document = EditorFactory.getInstance().createDocument(code.getText());
        code.setNewDocumentAndFileType(fileType(codeName), document);
        if (code.getEditor() instanceof com.intellij.openapi.editor.ex.EditorEx ex) {
            ex.setHighlighter(com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(project, new LightVirtualFile(codeName, fileType(codeName), "")));
        }
    }

    private void useExample(@Nullable JsExamples.Example e) {
        if (e == null) {
            description.setText(BootServersBundle.message("js.examples.intro", examples.size()));
            return;
        }
        description.setText(e.title() + "\n" + e.description());
        fill(e.code(), e.data());
        if (e.viewOnly()) {
            setStatus(BootServersBundle.message("js.example.view.only.note"), false);
        } else {
            setStatus(BootServersBundle.message("js.example.ready"), false);
        }
    }

    private void useGenerated(String generated) {
        fill(generated, dataText());
        run();
    }

    /** 코드 · 데이터를 바꾼다. 바꾸기 전 글은 되돌리기에 남긴다(같은 글이면 남기지 않는다). */
    private void fill(String newCode, String newData) {
        String oldCode = code.getText();
        String oldData = dataText();
        if (oldCode.equals(newCode) && oldData.equals(newData)) {
            return;
        }
        if (!oldCode.isBlank() || !oldData.isBlank()) {
            undo.push(new String[]{oldCode, oldData});
            while (undo.size() > UNDO_LIMIT) {
                undo.removeLast();
            }
            undoAction.setEnabled(true);
        }
        code.setText(newCode);
        data.setText(newData);
    }

    private void undo() {
        String[] last = undo.poll();
        if (last == null) {
            return;
        }
        code.setText(last[0]);
        data.setText(last[1]);
        undoAction.setEnabled(!undo.isEmpty());
    }

    private String dataText() {
        return data.getText();
    }

    private void run() {
        if (running != null) {
            return;
        }
        execute(tsButton.isSelected() ? JsBench.Mode.RUN_TS : JsBench.Mode.RUN_JS);
    }

    private void convert(JsBench.Mode mode) {
        if (running != null) {
            return;
        }
        if (dataText().isBlank()) {
            setStatus(BootServersBundle.message("js.convert.empty"), true);
            return;
        }
        execute(mode);
    }

    /** 풀 스레드에서 Node 로 돌리고 결과를 창에 놓는다. 창을 닫으면 stop() 이 끊는다. */
    private void execute(JsBench.Mode mode) {
        Path base = WebLspSupport.baseDir();
        Path node = WebTools.findNode(base, System.getenv("PATH"), SystemInfo.isWindows);
        nodeButton.setVisible(false);
        if (node == null) {
            setStatus(BootServersBundle.message("js.node.missing"), true);
            nodeButton.setVisible(SystemInfo.isWindows && NodeDist.arch(System.getProperty("os.arch")) != null);
            return;
        }
        Path tsLib = null;
        if (mode == JsBench.Mode.RUN_TS) {
            tsLib = WebTools.tsdk(null, base);
            if (!Files.isRegularFile(tsLib.resolve("typescript.js"))) {
                setStatus(BootServersBundle.message("js.ts.missing"), true);
                return;
            }
        }
        String codeText = code.getText();
        String dataValue = dataText();
        JsBench.Run run = new JsBench.Run();
        running = run;
        runAction.setEnabled(false);
        stopAction.setEnabled(true);
        setStatus(BootServersBundle.message(mode.runs() ? "js.running" : "js.converting"), false);
        Path tsPath = tsLib;
        ModalityState modality = ModalityState.stateForComponent(getContentPanel());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            JsBench.Result result = null;
            String failure = null;
            try {
                result = run.execute(node, mode, codeText, dataValue, tsPath);
            } catch (IOException | RuntimeException e) {
                LOG.warn(e);
                failure = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            JsBench.Result done = result;
            String error = failure;
            ApplicationManager.getApplication().invokeLater(() -> finished(run, mode, done, error), modality);
        });
    }

    private void stop() {
        JsBench.Run run = running;
        if (run != null) {
            run.stop();
        }
    }

    private void finished(JsBench.Run run, JsBench.Mode mode, @Nullable JsBench.Result result, @Nullable String failure) {
        if (running != run) {
            return;
        }
        running = null;
        runAction.setEnabled(true);
        stopAction.setEnabled(false);
        if (isDisposed()) {
            return;
        }
        if (result == null) {
            setStatus(BootServersBundle.message("js.start.failed", failure), true);
            return;
        }
        if (!mode.runs()) {
            convertDone(mode, result);
            return;
        }
        show(result);
    }

    /** 바꾸기 결과: 데이터를 바꾸는 것은 데이터 칸에, 코드를 만드는 것은 출력에 놓는다. */
    private void convertDone(JsBench.Mode mode, JsBench.Result result) {
        String out = null;
        StringBuilder errors = new StringBuilder();
        for (JsBench.Event e : result.events()) {
            if (e.kind() == JsBench.Kind.OUT) {
                out = e.text();
            } else if (e.kind() == JsBench.Kind.ERROR || e.kind() == JsBench.Kind.TEXT) {
                errors.append(firstLine(e.text())).append(' ');
            }
        }
        if (out == null || result.failed()) {
            setStatus(BootServersBundle.message("js.convert.failed", errors.toString().strip()), true);
            return;
        }
        if (mode == JsBench.Mode.TS || mode == JsBench.Mode.PROPS) {
            output.setText(out);
            output.setCaretPosition(0);
            resultTabs.setSelectedIndex(OUTPUT_TAB);
            setStatus(BootServersBundle.message("js.convert.output"), false);
            return;
        }
        fill(code.getText(), out);
        setStatus(BootServersBundle.message("js.convert.done"), false);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private void show(JsBench.Result result) {
        StringBuilder text = new StringBuilder();
        tables.clear();
        String browserName = null;
        for (JsBench.Event e : result.events()) {
            switch (e.kind()) {
                case TABLE -> {
                    tables.add(e);
                    text.append(BootServersBundle.message("js.output.table", tables.size(), e.rows().size() + e.more())).append('\n');
                }
                case VALUE -> text.append(BootServersBundle.message("js.output.value")).append(' ').append(e.text()).append('\n');
                case WARN -> text.append(BootServersBundle.message("js.output.warn")).append(' ').append(e.text()).append('\n');
                case ERROR -> {
                    text.append(BootServersBundle.message("js.output.error")).append(' ').append(e.text()).append('\n');
                    if (browserName == null) {
                        browserName = JsBench.browserOnlyName(e.text());
                    }
                }
                default -> text.append(e.text()).append('\n');
            }
        }
        if (browserName != null) {
            text.append('\n').append(BootServersBundle.message("js.output.browser", browserName)).append('\n');
        }
        output.setText(text.toString());
        output.setCaretPosition(0);
        tablePick.removeAllItems();
        for (int i = 0; i < tables.size(); i++) {
            tablePick.addItem(BootServersBundle.message("js.table.name", i + 1, tables.get(i).rows().size() + tables.get(i).more()));
        }
        tablePick.setEnabled(tables.size() > 1);
        if (tables.isEmpty()) {
            showTable(-1);
        } else {
            tablePick.setSelectedIndex(tables.size() - 1);
        }
        boolean errors = result.failed();
        resultTabs.setSelectedIndex(!tables.isEmpty() && !errors ? TABLE_TAB : OUTPUT_TAB);
        String seconds = String.format(Locale.ROOT, "%.2f", result.elapsedMs() / 1000.0);
        if (result.timedOut()) {
            setStatus(BootServersBundle.message("js.status.timeout", JsBench.TIMEOUT_MS / 1000), true);
        } else if (result.truncated()) {
            setStatus(BootServersBundle.message("js.status.truncated"), true);
        } else if (result.cancelled()) {
            setStatus(BootServersBundle.message("js.status.stopped"), true);
        } else if (errors) {
            setStatus(BootServersBundle.message("js.status.error", seconds), true);
        } else {
            status.setForeground(SUCCESS);
            status.setText(BootServersBundle.message("js.status.ok", seconds));
        }
    }

    private void showTable(int index) {
        tableModel.setRowCount(0);
        tableModel.setColumnCount(0);
        tableNote.setText("");
        if (index < 0 || index >= tables.size()) {
            return;
        }
        JsBench.Event t = tables.get(index);
        tableModel.setColumnIdentifiers(t.columns().toArray());
        for (List<String> row : t.rows()) {
            tableModel.addRow(row.toArray());
        }
        if (t.more() > 0) {
            tableNote.setText(BootServersBundle.message("js.table.more", t.rows().size(), t.more()));
        }
    }

    private void insert() {
        if (target == null || source == null || source.isDisposed() || !target.isValid()) {
            setStatus(BootServersBundle.message("js.insert.gone"), true);
            return;
        }
        Document document = target.getDocument();
        if (!document.isWritable()) {
            setStatus(BootServersBundle.message("js.insert.readonly"), true);
            return;
        }
        String text = code.getText();
        WriteCommandAction.runWriteCommandAction(project, BootServersBundle.message("js.insert"), null,
                () -> document.replaceString(target.getStartOffset(), target.getEndOffset(), text));
        setStatus(BootServersBundle.message("js.inserted"), false);
    }

    private void copy(String text, String doneKey) {
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
        setStatus(BootServersBundle.message(doneKey), false);
    }

    private void setStatus(String text, boolean error) {
        status.setForeground(error ? ERROR : UIUtil.getContextHelpForeground());
        status.setText(text);
    }

    private static Action action(String name, Runnable body) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                body.run();
            }
        };
    }
}
