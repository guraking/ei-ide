package local.bootservers;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.icons.AllIcons;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.ide.ui.laf.darcula.ui.DarculaComboBoxUI;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.ide.ui.laf.darcula.ui.DarculaTextFieldUI;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.JBTabsFactory;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.util.IconUtil;
import local.bootservers.ui.Pill;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLException;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.FieldView;
import javax.swing.text.JTextComponent;
import javax.swing.text.Segment;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.AbstractAction;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.TableModelListener;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.ConnectException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * API 요청 화면(편집기 탭). 위 절반은 요청(왼쪽 쿼리 · 헤더 · 본문 · 인증 탭, 오른쪽 보낼 HTTP 요청 글 미리보기),
 * 아래 절반은 응답(상태 · 걸린 시간 · 본문 · 헤더 · 쿠키), 맨 아래 줄에 마지막 보낸 시각.
 * 인증 방식 · 아이디는 프로젝트 설정에, 토큰 · 비밀번호 · 쿠키는 IDE 비밀번호 저장소에 둔다. 요청 · 응답 내용은 로그에 남기지 않는다.
 * 프로젝트마다 쿠키 저장소가 있어 로그인 응답의 Set-Cookie 를 다음 요청에 이어 보낸다(IDE 를 다시 켜면 비워짐).
 */
final class ApiRequestEditor extends UserDataHolderBase implements FileEditor {

    /** 화면을 열 때 채울 값. title 은 탭 이름(같은 이름의 탭이 열려 있으면 그 탭으로 간다). */
    record Request(String title, String method, String url, List<Row> query, List<Row> headers, String body) {
    }

    /** 표 한 줄의 시험 값. source 는 값이 온 곳(VO 이름 · @RequestParam 기본값 · 자동). */
    record Row(String name, String value, String source) {
    }

    private static final Key<Request> REQUEST = Key.create("ei.api.request");
    private static final Key<HttpClient> CLIENT = Key.create("ei.api.client");
    private static final String AUTH_TYPE_KEY = "ei.api.auth.type";
    private static final String AUTH_USER_KEY = "ei.api.auth.user";
    /** 비밀번호 저장소의 서비스 이름 앞부분. 뒤에 프로젝트 위치 해시와 인증 방식이 붙는다. */
    private static final String AUTH_SERVICE = "EI IDE API 인증";
    private static final String[] SECRET_TYPES = {ApiText.AUTH_BEARER, ApiText.AUTH_BASIC, ApiText.AUTH_COOKIE};

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** 화면에 넣을 응답 본문 최대 글자 수. 넘으면 앞부분만 보인다. */
    private static final int MAX_SHOWN_CHARS = 2_000_000;
    private static final int HTTP_ERROR_STATUS = 400;
    private static final float REQUEST_RESPONSE_SPLIT = 0.5f;
    /** 요청 영역에서 탭(왼쪽)이 차지하는 비율. 오른쪽은 보낼 요청 미리보기. */
    private static final float TABS_PREVIEW_SPLIT = 0.55f;
    private static final DateTimeFormatter SENT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String SEND_SHORTCUT = "control ENTER";
    /** 보내는 중 지난 시간을 고쳐 쓰는 간격(ms). */
    private static final int ELAPSED_TICK_MS = 100;
    private static final double MS_PER_SECOND = 1000.0;
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final int TABLE_ROW_HEIGHT = 30;
    /** 체크 칸 너비. 표 끝 「줄 추가」 글을 이름 칸 첫 글자에 맞추는 데도 쓴다. */
    private static final int CHECK_COLUMN_WIDTH = 32;
    /** 표 칸 좌우 안쪽 여백. */
    private static final int CELL_PADDING = 8;
    private static final int VIEW_BODY = 0;
    private static final int VIEW_HEADERS = 1;
    private static final int VIEW_COOKIES = 2;
    private static final String[] VIEW_CARDS = {"body", "headers", "cookies"};
    private static final String VIEWS_PLACE = "EiApiResponseViews";
    private static final String SET_COOKIE = "set-cookie";
    private static final JBColor METHOD_GET = new JBColor(0x1F8A4C, 0x5FCF8F);
    private static final JBColor METHOD_POST = new JBColor(0xB8620E, 0xF0A060);
    private static final JBColor METHOD_PUT = new JBColor(0x1750B8, 0x6FA8FF);
    private static final JBColor METHOD_PATCH = new JBColor(0x7A5BB5, 0xB49AF0);
    private static final JBColor METHOD_DELETE = new JBColor(0xC23B3B, 0xF07070);
    private static final JBColor VALUE_NUMBER = new JBColor(0xB8620E, 0xF0A060);
    private static final JBColor VALUE_TEXT = new JBColor(0x1F7A3A, 0x7FBF73);
    private static final JBColor STATUS_OK = new JBColor(0x1F8A4C, 0x5FCF8F);
    private static final JBColor STATUS_OK_BG = new JBColor(0xE4F4EA, 0x1E3A28);
    private static final JBColor STATUS_ERROR = new JBColor(0xC23B3B, 0xF07070);
    private static final JBColor STATUS_ERROR_BG = new JBColor(0xFBE7E7, 0x4A2222);
    /** 흔한 상태 코드의 이름(RFC 9110 15절). 없는 코드는 숫자만 보인다. */
    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(200, "OK"), Map.entry(201, "Created"), Map.entry(204, "No Content"),
            Map.entry(301, "Moved Permanently"), Map.entry(302, "Found"), Map.entry(304, "Not Modified"),
            Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"), Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"), Map.entry(409, "Conflict"),
            Map.entry(415, "Unsupported Media Type"), Map.entry(500, "Internal Server Error"),
            Map.entry(502, "Bad Gateway"), Map.entry(503, "Service Unavailable"));
    private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://.+");
    private static final String PSEUDO_HEADER_PREFIX = ":";
    private static final String CONTENT_TYPE = "content-type";
    private static final String[] RESPONSE_TYPES = {"json", "html", "xml"};
    private static final String PLAIN_TEXT = "txt";
    private static final int ENABLED_COLUMN = 0;
    private static final int NAME_COLUMN = 1;
    private static final int VALUE_COLUMN = 2;
    private static final int SOURCE_COLUMN = 3;
    private static final String REMOVE_ROWS = "ei.api.removeRows";
    /** 헤더 표의 인증 줄에 보일 값 길이. 넘으면 「…」 로 줄인다. */
    private static final int AUTH_SHOWN_CHARS = 24;
    private static final String RESPONSE_EMPTY_CARD = "empty";
    private static final String RESPONSE_SENDING_CARD = "sending";
    private static final String RESPONSE_ERROR_CARD = "error";
    private static final String RESPONSE_SHOWN_CARD = "shown";

    private final Project project;
    private final VirtualFile file;
    private final JPanel root = new JPanel(new BorderLayout());
    private final ComboBox<String> methodBox = new ComboBox<>(HttpText.METHODS);
    private final JBTextField urlField = new UrlField();
    private final JButton sendButton = new JButton(sendLabel());
    private final DefaultTableModel queryModel = rowsModel(row -> false);
    /** 인증 탭 값으로 만든 헤더 줄이 헤더 표 맨 위(0번)에 있는가. 그 줄은 체크만 고칠 수 있다. */
    private boolean authRowShown;
    private final DefaultTableModel headerModel = rowsModel(this::isAuthRow);
    private final EditorTextField bodyField;
    private final JBRadioButton authNone = new JBRadioButton(BootServersBundle.message("api.auth.none"));
    private final JBRadioButton authBearer = new JBRadioButton(BootServersBundle.message("api.auth.bearer"));
    private final JBRadioButton authBasic = new JBRadioButton(BootServersBundle.message("api.auth.basic"));
    private final JBRadioButton authCookie = new JBRadioButton(BootServersBundle.message("api.auth.cookie"));
    private final JBPasswordField tokenField = new JBPasswordField();
    private final JBTextField userField = new JBTextField();
    private final JBPasswordField passwordField = new JBPasswordField();
    private final JBTextField cookieField = new JBTextField();
    private final JBLabel tokenLabel = new JBLabel(BootServersBundle.message("api.auth.token"));
    private final JBLabel userLabel = new JBLabel(BootServersBundle.message("api.auth.user"));
    private final JBLabel passwordLabel = new JBLabel(BootServersBundle.message("api.auth.password"));
    private final JBLabel cookieLabel = new JBLabel(BootServersBundle.message("api.auth.cookie.value"));
    private final JBTabs requestTabs;
    private final TabInfo queryTab;
    private final TabInfo headersTab;
    private final TabInfo bodyTab;
    private final TabInfo authTab;
    /** 지금 입력으로 보낼 HTTP 요청 글(읽기 전용). 입력이 바뀔 때마다 다시 만든다. */
    private final EditorTextField previewField;
    private final Pill statusPill = new Pill();
    private final JBLabel metaLabel = new JBLabel();
    private final ActionToolbar viewsToolbar;
    private final JBLabel errorTitle = new JBLabel();
    private final JBLabel errorText = new JBLabel();
    private final JPanel responseCards = new JPanel(new CardLayout());
    private final JPanel responseViews = new JPanel(new CardLayout());
    private final JBLabel footLabel = new JBLabel();
    private final Timer elapsedTimer = new Timer(ELAPSED_TICK_MS, null);
    private long sendStart;
    private final EditorTextField responseBody;
    private final EditorTextField responseHeaders;
    private final EditorTextField responseCookies;
    private int shownView = VIEW_BODY;
    private int headerCount;
    private int cookieCount;
    /** 저장소에서 읽은(또는 마지막으로 쓴) 값. 바뀐 것만 다시 쓴다. 읽기 전에는 null. */
    private volatile Map<String, String> savedSecrets;
    private CompletableFuture<HttpResponse<String>> pending;

    /** 같은 이름의 요청 탭이 열려 있으면 그 탭으로, 아니면 새 탭을 연다. */
    static void open(Project project, Request request) {
        FileEditorManager manager = FileEditorManager.getInstance(project);
        for (VirtualFile open : manager.getOpenFiles()) {
            if (open.getUserData(REQUEST) != null && open.getName().equals(request.title())) {
                manager.openFile(open, true);
                return;
            }
        }
        LightVirtualFile file = new LightVirtualFile(request.title());
        file.putUserData(REQUEST, request);
        manager.openFile(file, true);
    }

    /** REQUEST 가 붙은 가상 파일만 받는다. */
    public static final class Provider implements FileEditorProvider, DumbAware {
        @Override
        public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
            return file.getUserData(REQUEST) != null;
        }

        @Override
        public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
            return new ApiRequestEditor(project, file);
        }

        @Override
        public @NotNull String getEditorTypeId() {
            return "ei-api-request";
        }

        @Override
        public @NotNull FileEditorPolicy getPolicy() {
            return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
        }
    }

    private ApiRequestEditor(Project project, VirtualFile file) {
        this.project = project;
        this.file = file;
        bodyField = textField(byExtension(RESPONSE_TYPES[0]), false);
        bodyField.setPlaceholder(BootServersBundle.message("api.body.empty"));
        bodyField.setShowPlaceholderWhenFocused(true);
        responseBody = textField(byExtension(PLAIN_TEXT), true);
        responseBody.addSettingsProvider(editor -> editor.getSettings().setLineNumbersShown(true));
        responseHeaders = textField(byExtension(PLAIN_TEXT), true);
        responseCookies = textField(byExtension(PLAIN_TEXT), true);
        previewField = textField(byExtension(PLAIN_TEXT), true);

        methodBox.setRenderer(new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull JList<? extends String> list, String method, int index, boolean selected, boolean focused) {
                setText(method);
                setFont(monoFont().deriveFont(Font.BOLD));
                if (!selected) {
                    setForeground(methodColor(method));
                }
            }
        });
        // 상자에 보이는 고른 값(index -1)도 렌더러 색을 쓰게 한다. 기본값이면 Darcula 가 상자 글자 색으로 덮는다.
        methodBox.putClientProperty(DarculaComboBoxUI.DISABLE_SETTING_FOREGROUND, true);
        methodBox.setFont(monoFont().deriveFont(Font.BOLD));
        JPanel top = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        top.add(methodBox, BorderLayout.WEST);
        urlField.setFont(monoFont());
        top.add(urlField, BorderLayout.CENTER);
        JButton reset = new JButton(BootServersBundle.message("api.reset"), AllIcons.Actions.Rollback);
        sendButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        buttons.add(sendButton);
        buttons.add(reset);
        top.add(buttons, BorderLayout.EAST);
        top.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(10, 14)));

        // 요청 탭. 쿼리 · 헤더 탭은 탭 줄 오른쪽에 「줄 추가」 링크를 둔다.
        requestTabs = JBTabsFactory.createTabs(project, this);
        queryTab = requestTab(BootServersBundle.message("api.tab.query"), rowsPanel(queryModel, row -> false), addRowLink(queryModel));
        headersTab = requestTab(BootServersBundle.message("api.tab.headers"), rowsPanel(headerModel, this::isAuthRow), addRowLink(headerModel));
        bodyTab = requestTab(BootServersBundle.message("api.tab.body"), bodyField, null);
        authTab = requestTab(BootServersBundle.message("api.tab.auth"), authPanel(), null);
        requestTabs.select(queryTab, false);
        TableModelListener counts = e -> {
            refreshHints();
            refreshPreview();
        };
        queryModel.addTableModelListener(counts);
        headerModel.addTableModelListener(counts);
        bodyField.addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override
            public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent event) {
                refreshHints();
                // 본문 문서가 바뀌는 중에 미리보기 문서를 고치지 않도록 한 박자 뒤에 만든다.
                ApplicationManager.getApplication().invokeLater(ApiRequestEditor.this::refreshPreview, project.getDisposed());
            }
        });
        methodBox.addActionListener(e -> refreshPreview());
        urlField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                refreshPreview();
            }
        });

        JPanel previewHead = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        JBLabel previewTitle = new JBLabel(BootServersBundle.message("api.preview.title"));
        previewTitle.setFont(JBFont.label().asBold());
        previewHead.add(previewTitle);
        previewHead.add(dim(new JBLabel(BootServersBundle.message("api.preview.hint"))));
        previewHead.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 14)));
        JPanel preview = new JPanel(new BorderLayout());
        preview.add(previewHead, BorderLayout.NORTH);
        preview.add(previewField, BorderLayout.CENTER);
        OnePixelSplitter requestSplit = new OnePixelSplitter(false, TABS_PREVIEW_SPLIT);
        // 칸 안 글(긴 안내 · 표 머리)의 최소 너비에 나눔 선이 묶이지 않게 한다.
        requestSplit.setHonorComponentsMinimumSize(false);
        requestSplit.setFirstComponent(requestTabs.getComponent());
        requestSplit.setSecondComponent(preview);

        // 응답 머리줄: 「응답」 · 상태 알약 · 시간 · 크기, 오른쪽에 본문 · 헤더 · 쿠키 토글.
        statusPill.setFont(monoFont().deriveFont(Font.BOLD));
        metaLabel.setForeground(UIUtil.getContextHelpForeground());
        JBLabel responseTitle = new JBLabel(BootServersBundle.message("api.response.title"));
        responseTitle.setFont(JBFont.label().asBold());
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0));
        status.add(responseTitle);
        status.add(statusPill);
        status.add(metaLabel);
        DefaultActionGroup views = new DefaultActionGroup(new ViewAction(VIEW_BODY), new ViewAction(VIEW_HEADERS), new ViewAction(VIEW_COOKIES));
        viewsToolbar = ActionManager.getInstance().createActionToolbar(VIEWS_PLACE, views, true);
        viewsToolbar.setTargetComponent(root);
        viewsToolbar.setReservePlaceAutoPopupIcon(false);
        viewsToolbar.getComponent().setVisible(false);
        JPanel responseHead = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        responseHead.add(status, BorderLayout.WEST);
        responseHead.add(viewsToolbar.getComponent(), BorderLayout.EAST);
        responseHead.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 14)));

        String shortcut = KeymapUtil.getFirstKeyboardShortcutText(CustomShortcutSet.fromString(SEND_SHORTCUT));
        JBPanelWithEmptyText empty = new JBPanelWithEmptyText().withEmptyText(BootServersBundle.message("api.response.empty"));
        empty.getEmptyText().appendSecondaryText(shortcut, SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        responseCards.add(empty, RESPONSE_EMPTY_CARD);
        responseCards.add(new JBPanelWithEmptyText().withEmptyText(BootServersBundle.message("api.sending.wait", REQUEST_TIMEOUT.toSeconds())),
                RESPONSE_SENDING_CARD);
        errorTitle.setFont(JBFont.label().asBold());
        errorText.setAllowAutoWrapping(true);
        errorTitle.setForeground(STATUS_ERROR);
        JPanel errorBox = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        errorBox.setBackground(STATUS_ERROR_BG);
        errorBox.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(STATUS_ERROR), JBUI.Borders.empty(10, 12)));
        errorBox.add(errorTitle, BorderLayout.NORTH);
        errorBox.add(errorText, BorderLayout.CENTER);
        JPanel errorCard = new JPanel(new BorderLayout());
        errorCard.setBorder(JBUI.Borders.empty(12, 14));
        errorCard.add(errorBox, BorderLayout.NORTH);
        responseCards.add(errorCard, RESPONSE_ERROR_CARD);
        responseViews.add(responseBody, VIEW_CARDS[VIEW_BODY]);
        responseViews.add(responseHeaders, VIEW_CARDS[VIEW_HEADERS]);
        responseViews.add(responseCookies, VIEW_CARDS[VIEW_COOKIES]);
        responseCards.add(responseViews, RESPONSE_SHOWN_CARD);
        JPanel response = new JPanel(new BorderLayout());
        response.add(responseHead, BorderLayout.NORTH);
        response.add(responseCards, BorderLayout.CENTER);

        // 요청(위) · 응답(아래)으로 나눠 응답을 전체 폭으로 보인다.
        OnePixelSplitter split = new OnePixelSplitter(true, REQUEST_RESPONSE_SPLIT);
        split.setHonorComponentsMinimumSize(false);
        split.setFirstComponent(requestSplit);
        split.setSecondComponent(response);

        JPanel foot = new JPanel(new BorderLayout());
        foot.add(dim(footLabel), BorderLayout.WEST);
        foot.add(dim(new JBLabel(BootServersBundle.message("api.foot.shortcut", shortcut))), BorderLayout.EAST);
        foot.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(4, 14)));
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);

        // 파란 기본 단추 위에서는 아이콘 · 글자를 기본 단추 글자 색(테마)으로 맞춘다.
        sendButton.setIcon(IconUtil.colorize(AllIcons.Actions.Execute, JBUI.CurrentTheme.Button.defaultButtonForeground()));
        sendButton.setToolTipText(KeymapUtil.getFirstKeyboardShortcutText(CustomShortcutSet.fromString(SEND_SHORTCUT)));
        DumbAwareAction.create(e -> sendOrCancel()).registerCustomShortcutSet(CustomShortcutSet.fromString(SEND_SHORTCUT), root, this);
        elapsedTimer.addActionListener(e -> metaLabel.setText(BootServersBundle.message("api.sending.elapsed",
                String.format(Locale.ROOT, "%.1f", (System.nanoTime() - sendStart) / 1_000_000 / MS_PER_SECOND))));
        sendButton.addActionListener(e -> sendOrCancel());
        reset.addActionListener(e -> fill());
        urlField.addActionListener(e -> sendOrCancel());
        fill();
        loadAuth();
    }

    private EditorTextField textField(FileType type, boolean viewer) {
        EditorTextField field = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, type, viewer, false);
        // 탭 안에 넣으므로 편집기 틀 · 줄 번호 없이 평평하게 둔다.
        field.setBorder(JBUI.Borders.empty());
        field.addSettingsProvider(editor -> {
            editor.setVerticalScrollbarVisible(true);
            editor.setHorizontalScrollbarVisible(true);
            // 탭 줄 · 표와 같은 왼쪽 14px 줄에 글이 오도록 안쪽 여백을 둔다.
            // EditorImpl.setBorder 는 스크롤 칸 틀을 바꾸므로 스크롤 칸 틀을 따로 다시 정하지 않는다. 여백 바탕은 편집기 바탕과 같게.
            editor.setBorder(JBUI.Borders.empty(10, 14));
            editor.getScrollPane().setBackground(editor.getBackgroundColor());
            editor.getSettings().setLineNumbersShown(false);
        });
        return field;
    }

    private static FileType byExtension(String extension) {
        return FileTypeManager.getInstance().getFileTypeByExtension(extension);
    }

    /** 편집기 글꼴(코드 글꼴)을 화면 글자 크기로. */
    private static Font monoFont() {
        return new Font(EditorColorsManager.getInstance().getGlobalScheme().getEditorFontName(), Font.PLAIN, JBFont.label().getSize());
    }

    private static Color methodColor(String method) {
        return switch (method == null ? "" : method) {
            case "GET" -> METHOD_GET;
            case "POST" -> METHOD_POST;
            case "PUT" -> METHOD_PUT;
            case "PATCH" -> METHOD_PATCH;
            case "DELETE" -> METHOD_DELETE;
            default -> UIUtil.getLabelForeground();
        };
    }

    private static JBLabel dim(JBLabel label) {
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }

    private TabInfo requestTab(String title, JComponent content, @Nullable JComponent side) {
        TabInfo info = new TabInfo(content).setText(title);
        if (side != null) {
            info.setSideComponent(side);
        }
        requestTabs.addTab(info);
        return info;
    }

    /** 탭 줄 오른쪽 링크: 표에 빈 줄을 더한다. */
    private static JComponent addRowLink(DefaultTableModel model) {
        ActionLink link = new ActionLink(BootServersBundle.message("api.row.add"), e -> { model.addRow(new Object[]{true, "", "", ""}); });
        link.setBorder(JBUI.Borders.empty(0, 8));
        return link;
    }

    /** 탭 이름 뒤에 흐린 보조 글. */
    private static void setHint(TabInfo tab, String title, String hint) {
        tab.setText(title);
        tab.append("  " + hint, SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }

    /** 요청 탭 이름 옆 보조 글: 쿼리 · 헤더 줄 수, 본문 유무, 인증 방식. */
    private void refreshHints() {
        setHint(queryTab, BootServersBundle.message("api.tab.query"), String.valueOf(queryModel.getRowCount()));
        setHint(headersTab, BootServersBundle.message("api.tab.headers"), String.valueOf(headerModel.getRowCount()));
        setHint(bodyTab, BootServersBundle.message("api.tab.body"), bodyField.getText().isBlank()
                ? BootServersBundle.message("api.hint.none") : BootServersBundle.message("api.hint.json"));
        setHint(authTab, BootServersBundle.message("api.tab.auth"), switch (authKind()) {
            case ApiText.AUTH_BEARER -> "Bearer";
            case ApiText.AUTH_BASIC -> "Basic";
            case ApiText.AUTH_COOKIE -> BootServersBundle.message("api.auth.cookie");
            default -> BootServersBundle.message("api.hint.none");
        });
    }

    /** 지금 입력으로 보낼 HTTP 요청 글을 다시 만든다. 인증 줄 값은 헤더 표와 같이 앞부분만 보인다. */
    private void refreshPreview() {
        List<Map.Entry<String, String>> headers = checkedRows(headerModel, 0);
        // 헤더 칸에 직접 적은 같은 이름이 있으면 인증 줄은 보내지 않으므로(sendOrCancel) 미리보기에서도 뺀다.
        if (isAuthRow(0) && Boolean.TRUE.equals(headerModel.getValueAt(0, ENABLED_COLUMN)) && !headers.isEmpty()) {
            for (int i = 1; i < headers.size(); i++) {
                if (headers.get(i).getKey().equalsIgnoreCase(headers.get(0).getKey())) {
                    headers.remove(0);
                    break;
                }
            }
        }
        previewField.setText(ApiText.requestText(String.valueOf(methodBox.getSelectedItem()), urlField.getText(),
                checkedRows(queryModel, 0), headers, bodyField.getText()));
    }

    private void showCard(String card) {
        ((CardLayout) responseCards.getLayout()).show(responseCards, card);
    }

    private void showView(int view) {
        shownView = view;
        ((CardLayout) responseViews.getLayout()).show(responseViews, VIEW_CARDS[view]);
    }

    /** 응답 토글 이름. 헤더 · 쿠키는 개수를 붙인다. */
    private String viewTitle(int view) {
        return switch (view) {
            case VIEW_HEADERS -> BootServersBundle.message("api.tab.headers") + " " + headerCount;
            case VIEW_COOKIES -> BootServersBundle.message("api.tab.cookies") + " " + cookieCount;
            default -> BootServersBundle.message("api.tab.body");
        };
    }

    /** 응답 머리줄의 본문 · 헤더 · 쿠키 토글 하나. */
    private final class ViewAction extends ToggleAction implements DumbAware {
        private final int view;

        ViewAction(int view) {
            this.view = view;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public boolean displayTextInToolbar() {
            return true;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            super.update(e);
            e.getPresentation().setText(viewTitle(view));
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return shownView == view;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (state) {
                showView(view);
            }
        }
    }

    /** locked 줄은 체크만 고칠 수 있다. */
    private static DefaultTableModel rowsModel(IntPredicate locked) {
        return new DefaultTableModel(new Object[]{"", BootServersBundle.message("api.column.name"),
                BootServersBundle.message("api.column.value"), BootServersBundle.message("api.column.source")}, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                return column == ENABLED_COLUMN ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == ENABLED_COLUMN || column != SOURCE_COLUMN && !locked.test(row);
            }
        };
    }

    /**
     * 체크 · 이름 · 값 · 어디서 표. 체크를 끈 줄은 보내지 않고 흐리게 줄을 그어 보인다. 이름 · 값은 코드 글꼴, 값은 숫자 · 글을 색으로 가른다.
     * locked 줄은 흐린 글자. Delete 키로 고른 줄(locked 제외)을 뺀다. 줄 더하기는 표 끝 줄과 탭 줄 오른쪽 링크.
     */
    private static JComponent rowsPanel(DefaultTableModel model, IntPredicate locked) {
        JBTable table = new JBTable(model);
        table.setRowHeight(JBUI.scale(TABLE_ROW_HEIGHT));
        table.getColumnModel().getColumn(ENABLED_COLUMN).setMaxWidth(JBUI.scale(CHECK_COLUMN_WIDTH));
        table.getColumnModel().getColumn(NAME_COLUMN).setPreferredWidth(JBUI.scale(180));
        table.getColumnModel().getColumn(VALUE_COLUMN).setPreferredWidth(JBUI.scale(220));
        table.getColumnModel().getColumn(SOURCE_COLUMN).setPreferredWidth(JBUI.scale(120));
        Font mono = monoFont();
        // 값은 한글이 많아 화면 글꼴로 둔다(코드 글꼴은 한글 칸 너비가 벌어져 보인다).
        Font text = JBFont.label();
        Map<TextAttribute, Object> strike = Map.of(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        table.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                setBorder(JBUI.Borders.empty(0, CELL_PADDING));
                boolean on = Boolean.TRUE.equals(t.getValueAt(row, ENABLED_COLUMN));
                if (column == SOURCE_COLUMN) {
                    c.setFont(JBFont.small());
                    if (!selected) {
                        c.setForeground(UIUtil.getContextHelpForeground());
                    }
                    return c;
                }
                Font font = column == VALUE_COLUMN ? text : mono;
                c.setFont(on ? font : font.deriveFont(strike));
                if (selected) {
                    return c;
                }
                if (!on || locked.test(row)) {
                    c.setForeground(UIUtil.getContextHelpForeground());
                } else if (column == VALUE_COLUMN) {
                    c.setForeground(value != null && NUMBER.matcher(value.toString().strip()).matches() ? VALUE_NUMBER : VALUE_TEXT);
                } else {
                    c.setForeground(t.getForeground());
                }
                return c;
            }
        });
        // 탭 줄 · 좌우 나눔 선이 이미 틀을 그리므로 스크롤 틀은 뺀다(겹치면 두 줄로 보인다).
        // 세로 칸 선은 머리글 칸 선과 1픽셀 어긋나므로 그리지 않는다.
        table.setShowVerticalLines(false);
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), REMOVE_ROWS);
        table.getActionMap().put(REMOVE_ROWS, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (table.isEditing()) {
                    return;
                }
                int[] rows = table.getSelectedRows();
                for (int i = rows.length - 1; i >= 0; i--) {
                    if (!locked.test(rows[i])) {
                        model.removeRow(rows[i]);
                    }
                }
            }
        });
        // 표 끝 줄: 누르면 빈 줄을 더하고 이름 칸을 바로 고치게 한다.
        ActionLink addRow = new ActionLink(BootServersBundle.message("api.row.add.end"), e -> {
            model.addRow(new Object[]{true, "", "", ""});
            int row = model.getRowCount() - 1;
            table.changeSelection(row, NAME_COLUMN, false, false);
            if (table.editCellAt(row, NAME_COLUMN) && table.getEditorComponent() != null) {
                table.getEditorComponent().requestFocusInWindow();
            }
        });
        addRow.setForeground(UIUtil.getContextHelpForeground());
        addRow.setBorder(JBUI.Borders.empty(6, CELL_PADDING + CHECK_COLUMN_WIDTH, 6, CELL_PADDING));
        JPanel rows = new JPanel(new BorderLayout());
        rows.setBackground(table.getBackground());
        rows.add(table, BorderLayout.NORTH);
        JPanel rest = new JPanel(new BorderLayout());
        rest.setOpaque(false);
        rest.add(addRow, BorderLayout.NORTH);
        rows.add(rest, BorderLayout.CENTER);
        JBScrollPane scroll = new JBScrollPane(rows);
        // 표를 판 안에 넣었으므로 머리글은 스크롤 머리 자리에 따로 둔다.
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.getViewport().setBackground(table.getBackground());
        scroll.setBorder(JBUI.Borders.empty());
        return scroll;
    }

    /** 보내기 단추 글: 「보내기」 뒤에 단축키(Ctrl+Enter). 글 크기를 하나로 두어 단추 안 세로 가운데가 어긋나지 않게 한다. */
    private static String sendLabel() {
        return BootServersBundle.message("api.send") + "  " + KeymapUtil.getFirstKeyboardShortcutText(CustomShortcutSet.fromString(SEND_SHORTCUT));
    }

    /** 주소 칸. 서버 부분(scheme://host:port)과 쿼리(?…)는 흐리게, 경로만 보통 색으로 그린다. 고른 글은 기본 선택 색. */
    private static final class UrlField extends JBTextField {
        @Override
        public void updateUI() {
            // 테마가 바뀌어도 같은 그리기를 쓰도록 UI 를 다시 만들 때마다 우리 것으로 둔다.
            setUI(new DarculaTextFieldUI() {
                @Override
                public View create(Element element) {
                    return new UrlView(element);
                }
            });
        }
    }

    private static final class UrlView extends FieldView {
        UrlView(Element element) {
            super(element);
        }

        @Override
        protected float drawUnselectedText(Graphics2D g, float x, float y, int p0, int p1) throws BadLocationException {
            if (!getContainer().isEnabled()) {
                return super.drawUnselectedText(g, x, y, p0, p1);
            }
            String text = getDocument().getText(0, getDocument().getLength());
            int host = ApiText.hostEnd(text);
            int query = ApiText.queryStart(text);
            float at = part(g, x, y, p0, Math.min(p1, host), true);
            at = part(g, at, y, Math.max(p0, host), Math.min(p1, query), false);
            return part(g, at, y, Math.max(p0, query), p1, true);
        }

        private float part(Graphics2D g, float x, float y, int from, int to, boolean dim) throws BadLocationException {
            if (from >= to) {
                return x;
            }
            if (!dim) {
                return super.drawUnselectedText(g, x, y, from, to);
            }
            Segment segment = getLineBuffer();
            getDocument().getText(from, to - from, segment);
            g.setColor(UIUtil.getContextHelpForeground());
            // 직접 그리는 글에도 IDE 글자 다듬기(안티에일리어싱)를 맞춘다. 없으면 흐린 부분만 글자가 깨져 보인다.
            UISettings.setupAntialiasing(g);
            return Utilities.drawTabbedText(segment, x, y, g, this, from);
        }
    }

    private JComponent authPanel() {
        ButtonGroup group = new ButtonGroup();
        JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0));
        for (JBRadioButton radio : new JBRadioButton[]{authNone, authBearer, authBasic, authCookie}) {
            group.add(radio);
            kinds.add(radio);
            radio.addActionListener(e -> {
                applyAuthKind();
                // 방식은 고르는 즉시 남긴다. 아이디 · 비밀 값은 보낼 때 저장한다(saveAuth).
                PropertiesComponent.getInstance(project).setValue(AUTH_TYPE_KEY, authKind(), ApiText.AUTH_NONE);
            });
        }
        DocumentAdapter refresh = new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                refreshAuthRow();
            }
        };
        for (JTextComponent field : new JTextComponent[]{tokenField, userField, passwordField, cookieField}) {
            field.getDocument().addDocumentListener(refresh);
        }
        cookieField.getEmptyText().setText(BootServersBundle.message("api.auth.cookie.hint"));
        // 이름 칸 폭은 보이는 이름 중 가장 긴 것으로 정해지므로, 늘 보이는 「방식」을 가장 긴 이름 폭에 맞춰 방식을 바꿔도 칸이 움직이지 않게 한다.
        JBLabel kindLabel = new JBLabel(BootServersBundle.message("api.auth.kind"));
        int labelWidth = 0;
        for (JBLabel label : new JBLabel[]{kindLabel, tokenLabel, userLabel, passwordLabel, cookieLabel}) {
            labelWidth = Math.max(labelWidth, label.getPreferredSize().width);
        }
        kindLabel.setPreferredSize(new Dimension(labelWidth, kindLabel.getPreferredSize().height));
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent(kindLabel, kinds)
                .addLabeledComponent(tokenLabel, tokenField)
                .addLabeledComponent(userLabel, userField)
                .addLabeledComponent(passwordLabel, passwordField)
                .addLabeledComponent(cookieLabel, cookieField)
                .addComponentToRightColumn(ComponentPanelBuilder.createCommentComponent(BootServersBundle.message("api.auth.note"), true))
                .getPanel();
        form.setBorder(JBUI.Borders.empty(12, 14));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    private String authKind() {
        if (authBearer.isSelected()) {
            return ApiText.AUTH_BEARER;
        }
        if (authBasic.isSelected()) {
            return ApiText.AUTH_BASIC;
        }
        return authCookie.isSelected() ? ApiText.AUTH_COOKIE : ApiText.AUTH_NONE;
    }

    /** 고른 방식의 이름 · 칸만 보인다. 없음이면 방식 줄과 안내문만 남는다. */
    private void applyAuthKind() {
        String kind = authKind();
        boolean bearer = ApiText.AUTH_BEARER.equals(kind);
        boolean basic = ApiText.AUTH_BASIC.equals(kind);
        boolean cookie = ApiText.AUTH_COOKIE.equals(kind);
        tokenLabel.setVisible(bearer);
        tokenField.setVisible(bearer);
        userLabel.setVisible(basic);
        userField.setVisible(basic);
        passwordLabel.setVisible(basic);
        passwordField.setVisible(basic);
        cookieLabel.setVisible(cookie);
        cookieField.setVisible(cookie);
        tokenField.getParent().revalidate();
        tokenField.getParent().repaint();
        refreshAuthRow();
        refreshHints();
    }

    private boolean isAuthRow(int row) {
        return authRowShown && row == 0;
    }

    /** 고른 인증 방식의 비밀 값(토큰 · 비밀번호 · 쿠키). */
    private Map<String, String> secrets() {
        return Map.of(ApiText.AUTH_BEARER, new String(tokenField.getPassword()),
                ApiText.AUTH_BASIC, new String(passwordField.getPassword()),
                ApiText.AUTH_COOKIE, cookieField.getText());
    }

    private @Nullable Map.Entry<String, String> currentAuth() {
        return ApiText.authHeader(authKind(), userField.getText(), secrets().get(authKind()));
    }

    /**
     * 인증 탭 값을 헤더 표 맨 위 흐린 줄로 다시 보인다(값은 앞부분만). 인증이 없으면 그 줄을 뺀다.
     * 그 줄의 체크 상태는 값이 바뀌어도 유지한다.
     */
    private void refreshAuthRow() {
        boolean checked = true;
        if (authRowShown) {
            checked = Boolean.TRUE.equals(headerModel.getValueAt(0, ENABLED_COLUMN));
            authRowShown = false;
            headerModel.removeRow(0);
        }
        Map.Entry<String, String> auth = currentAuth();
        if (auth != null) {
            String value = auth.getValue().length() > AUTH_SHOWN_CHARS
                    ? auth.getValue().substring(0, AUTH_SHOWN_CHARS) + "…" : auth.getValue();
            headerModel.insertRow(0, new Object[]{checked, auth.getKey(), value, BootServersBundle.message("api.source.auth")});
            authRowShown = true;
        }
    }

    /** 처음 받은 값(시험 값)으로 방식 · 주소 · 쿼리 · 헤더 · 본문을 다시 채운다. 인증은 두고, 응답은 비운다. */
    private void fill() {
        Request request = file.getUserData(REQUEST);
        if (request == null) {
            return;
        }
        methodBox.setSelectedItem(request.method());
        urlField.setText(request.url());
        setRows(queryModel, request.query());
        authRowShown = false;
        setRows(headerModel, request.headers());
        refreshAuthRow();
        bodyField.setText(request.body());
    }

    private static void setRows(DefaultTableModel model, List<Row> rows) {
        model.setRowCount(0);
        for (Row row : rows) {
            model.addRow(new Object[]{true, row.name(), row.value(), row.source()});
        }
    }

    /** from 번째 줄부터 체크한 줄의 이름 · 값. 이름이 빈 줄은 건너뛴다. */
    private static List<Map.Entry<String, String>> checkedRows(DefaultTableModel model, int from) {
        List<Map.Entry<String, String>> result = new ArrayList<>();
        for (int i = from; i < model.getRowCount(); i++) {
            Object name = model.getValueAt(i, NAME_COLUMN);
            Object value = model.getValueAt(i, VALUE_COLUMN);
            if (Boolean.TRUE.equals(model.getValueAt(i, ENABLED_COLUMN)) && name != null && !name.toString().isBlank()) {
                result.add(Map.entry(name.toString().strip(), value == null ? "" : value.toString()));
            }
        }
        return result;
    }

    private static CredentialAttributes secretKey(Project project, String kind) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(AUTH_SERVICE, project.getLocationHash() + "/" + kind));
    }

    /** 방식 · 아이디는 바로, 비밀 값은 백그라운드에서 읽어 채운다(비밀번호 저장소는 화면 스레드에서 읽지 않는다). */
    private void loadAuth() {
        PropertiesComponent settings = PropertiesComponent.getInstance(project);
        String kind = settings.getValue(AUTH_TYPE_KEY, ApiText.AUTH_NONE);
        authNone.setSelected(true);
        authBearer.setSelected(ApiText.AUTH_BEARER.equals(kind));
        authBasic.setSelected(ApiText.AUTH_BASIC.equals(kind));
        authCookie.setSelected(ApiText.AUTH_COOKIE.equals(kind));
        userField.setText(settings.getValue(AUTH_USER_KEY, ""));
        applyAuthKind();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Map<String, String> secrets = new HashMap<>();
            for (String type : SECRET_TYPES) {
                String value = PasswordSafe.getInstance().getPassword(secretKey(project, type));
                secrets.put(type, value == null ? "" : value);
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                savedSecrets = secrets;
                tokenField.setText(secrets.get(ApiText.AUTH_BEARER));
                passwordField.setText(secrets.get(ApiText.AUTH_BASIC));
                cookieField.setText(secrets.get(ApiText.AUTH_COOKIE));
            }, ModalityState.defaultModalityState(), project.getDisposed());
        });
    }

    /** 방식 · 아이디를 설정에, 바뀐 비밀 값만 백그라운드에서 저장소에 쓴다. 빈 값이면 지운다. 읽기 전이면 비밀 값은 쓰지 않는다. */
    private void saveAuth(String kind, Map<String, String> secrets) {
        PropertiesComponent settings = PropertiesComponent.getInstance(project);
        settings.setValue(AUTH_TYPE_KEY, kind, ApiText.AUTH_NONE);
        settings.setValue(AUTH_USER_KEY, userField.getText().strip(), "");
        Map<String, String> saved = savedSecrets;
        if (saved == null) {
            return;
        }
        savedSecrets = secrets;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (String type : SECRET_TYPES) {
                String value = secrets.get(type);
                if (!value.equals(saved.get(type))) {
                    PasswordSafe.getInstance().setPassword(secretKey(project, type), value.isEmpty() ? null : value);
                }
            }
        });
    }

    /** 프로젝트마다 하나(화면 스레드에서만 부른다). 쿠키 저장소를 두어 로그인으로 받은 세션 쿠키를 이어 보낸다. */
    private static HttpClient client(Project project) {
        HttpClient client = project.getUserData(CLIENT);
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    // 개발 서버가 h2c 올리기 요청을 잘못 받는 일이 있어 HTTP/1.1 로 고정한다.
                    .version(HttpClient.Version.HTTP_1_1)
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .build();
            project.putUserData(CLIENT, client);
        }
        return client;
    }

    /** 「보내기」: 보내는 중이면 취소한다. */
    private void sendOrCancel() {
        if (pending != null) {
            pending.cancel(true);
            return;
        }
        String url = urlField.getText().strip();
        if (!HTTP_URL.matcher(url).matches()) {
            showError(BootServersBundle.message("api.error.title.request"), BootServersBundle.message("api.error.url"));
            return;
        }
        String method = String.valueOf(methodBox.getSelectedItem());
        List<Map.Entry<String, String>> headers = checkedRows(headerModel, authRowShown ? 1 : 0);
        String kind = authKind();
        Map<String, String> secrets = secrets();
        // 헤더 표 맨 위 인증 줄의 체크를 끄면 이번 요청에는 인증을 붙이지 않는다.
        Map.Entry<String, String> auth = isAuthRow(0) && Boolean.TRUE.equals(headerModel.getValueAt(0, ENABLED_COLUMN))
                ? currentAuth() : null;
        String body = bodyField.getText();
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ApiText.withQuery(url, checkedRows(queryModel, 0))))
                    .timeout(REQUEST_TIMEOUT);
            boolean authInHeaders = false;
            for (Map.Entry<String, String> header : headers) {
                builder.header(header.getKey(), header.getValue());
                authInHeaders |= auth != null && header.getKey().equalsIgnoreCase(auth.getKey());
            }
            // 헤더 칸에 직접 적은 값이 인증 칸보다 앞선다.
            if (auth != null && !authInHeaders) {
                builder.header(auth.getKey(), auth.getValue());
            }
            builder.method(method, body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            request = builder.build();
        } catch (IllegalArgumentException e) {
            showError(BootServersBundle.message("api.error.title.request"), buildErrorMessage(e));
            return;
        }
        saveAuth(kind, secrets);
        long start = System.nanoTime();
        sendStart = start;
        statusPill.clear();
        metaLabel.setIcon(AnimatedIcon.Default.INSTANCE);
        metaLabel.setText(BootServersBundle.message("api.sending.elapsed", "0.0"));
        viewsToolbar.getComponent().setVisible(false);
        showCard(RESPONSE_SENDING_CARD);
        elapsedTimer.start();
        CompletableFuture<HttpResponse<String>> future = client(project).sendAsync(request, HttpResponse.BodyHandlers.ofString());
        pending = future;
        sendButton.setText(BootServersBundle.message("api.cancel"));
        future.whenComplete((response, error) -> {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            ApplicationManager.getApplication().invokeLater(() -> finish(future, response, error, elapsedMs),
                    ModalityState.defaultModalityState(), project.getDisposed());
        });
    }

    private void finish(CompletableFuture<HttpResponse<String>> future, HttpResponse<String> response, Throwable error, long elapsedMs) {
        if (future != pending) {
            return;
        }
        pending = null;
        elapsedTimer.stop();
        metaLabel.setIcon(null);
        sendButton.setText(sendLabel());
        footLabel.setText(BootServersBundle.message("api.foot.last", LocalTime.now().format(SENT_TIME), elapsedMs));
        if (error != null) {
            metaLabel.setText(BootServersBundle.message("api.meta.time", elapsedMs));
            showError(BootServersBundle.message("api.error.title"), responseErrorMessage(error));
            return;
        }
        String body = response.body() == null ? "" : response.body();
        int code = response.statusCode();
        boolean failed = code >= HTTP_ERROR_STATUS;
        String reason = REASONS.get(code);
        statusPill.show(reason == null ? String.valueOf(code) : code + " " + reason,
                failed ? STATUS_ERROR : STATUS_OK, failed ? STATUS_ERROR_BG : STATUS_OK_BG);
        StringBuilder headerText = new StringBuilder();
        List<String> cookies = new ArrayList<>();
        String contentType = "";
        headerCount = 0;
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            if (header.getKey().startsWith(PSEUDO_HEADER_PREFIX)) {
                continue;
            }
            headerCount++;
            String value = String.join(", ", header.getValue());
            headerText.append(header.getKey()).append(": ").append(value).append('\n');
            if (CONTENT_TYPE.equalsIgnoreCase(header.getKey())) {
                contentType = value.toLowerCase(Locale.ROOT);
            }
            if (SET_COOKIE.equalsIgnoreCase(header.getKey())) {
                cookies.addAll(header.getValue());
            }
        }
        responseHeaders.setText(headerText.toString());
        responseCookies.setText(String.join("\n", cookies));
        cookieCount = cookies.size();
        String size = StringUtil.formatFileSize(body.getBytes(StandardCharsets.UTF_8).length);
        String mediaType = contentType.split(";")[0].strip();
        metaLabel.setText(mediaType.isEmpty() ? BootServersBundle.message("api.meta", elapsedMs, size)
                : BootServersBundle.message("api.meta.type", elapsedMs, size, mediaType));
        if (body.isBlank()) {
            showError(BootServersBundle.message("api.body.blank"),
                    BootServersBundle.message(failed ? "api.body.blank.error" : "api.body.blank.ok"));
            return;
        }
        String shownType = PLAIN_TEXT;
        for (String type : RESPONSE_TYPES) {
            if (contentType.contains(type)) {
                shownType = type;
                break;
            }
        }
        String shown = RESPONSE_TYPES[0].equals(shownType) ? ApiText.prettyJson(body) : body;
        if (shown.length() > MAX_SHOWN_CHARS) {
            shown = shown.substring(0, MAX_SHOWN_CHARS) + "\n" + BootServersBundle.message("api.truncated", MAX_SHOWN_CHARS);
        }
        responseBody.setFileType(byExtension(shownType));
        responseBody.setText(shown);
        viewsToolbar.getComponent().setVisible(true);
        viewsToolbar.updateActionsAsync();
        showCard(RESPONSE_SHOWN_CARD);
    }

    /** 응답 칸에 빨간 안내 상자. 머리 줄(상태 · 시간)은 그대로 둔다. */
    private void showError(String title, String message) {
        errorTitle.setText(title);
        errorText.setText(UIUtil.toHtml(StringUtil.escapeXmlEntities(message)));
        viewsToolbar.getComponent().setVisible(false);
        showCard(RESPONSE_ERROR_CARD);
    }

    private static String buildErrorMessage(IllegalArgumentException e) {
        String detail = String.valueOf(e.getMessage());
        if (detail.contains("Illegal character")) {
            return BootServersBundle.message("api.error.char");
        }
        if (detail.contains("restricted header")) {
            return BootServersBundle.message("api.error.header", detail);
        }
        return BootServersBundle.message("api.error.build", detail);
    }

    private static String responseErrorMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof CancellationException) {
            return BootServersBundle.message("api.error.cancelled");
        }
        if (cause instanceof HttpConnectTimeoutException) {
            return BootServersBundle.message("api.error.connect.timeout", CONNECT_TIMEOUT.toSeconds());
        }
        if (cause instanceof HttpTimeoutException) {
            return BootServersBundle.message("api.error.timeout", REQUEST_TIMEOUT.toSeconds());
        }
        if (cause instanceof ConnectException) {
            return BootServersBundle.message("api.error.connect");
        }
        if (cause instanceof SSLException) {
            return BootServersBundle.message("api.error.ssl", String.valueOf(cause.getMessage()));
        }
        if (cause instanceof IOException) {
            return BootServersBundle.message("api.error.io", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        }
        return BootServersBundle.message("api.error.io", String.valueOf(cause));
    }

    @Override
    public @NotNull JComponent getComponent() {
        return root;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return urlField;
    }

    @Override
    public @NotNull String getName() {
        return BootServersBundle.message("api.title");
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return file.isValid();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        elapsedTimer.stop();
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
    }
}
