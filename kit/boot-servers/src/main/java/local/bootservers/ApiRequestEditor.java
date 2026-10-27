package local.bootservers;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.icons.AllIcons;
import com.intellij.ide.passwordSafe.PasswordSafe;
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
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLException;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
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
 * API 요청 화면(편집기 탭). 방식 · 주소 · 쿼리 · 헤더 · 본문 · 인증을 고쳐 보내고 상태 · 걸린 시간 · 응답 본문 · 헤더를 보인다.
 * 인증 방식 · 아이디는 프로젝트 설정에, 토큰 · 비밀번호 · 쿠키는 IDE 비밀번호 저장소에 둔다. 요청 · 응답 내용은 로그에 남기지 않는다.
 * 프로젝트마다 쿠키 저장소가 있어 로그인 응답의 Set-Cookie 를 다음 요청에 이어 보낸다(IDE 를 다시 켜면 비워짐).
 */
final class ApiRequestEditor extends UserDataHolderBase implements FileEditor {

    /** 화면을 열 때 채울 값. title 은 탭 이름(같은 이름의 탭이 열려 있으면 그 탭으로 간다). */
    record Request(String title, String method, String url, List<Map.Entry<String, String>> query,
                   List<Map.Entry<String, String>> headers, String body) {
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
    private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://.+");
    private static final String PSEUDO_HEADER_PREFIX = ":";
    private static final String CONTENT_TYPE = "content-type";
    private static final String[] RESPONSE_TYPES = {"json", "html", "xml"};
    private static final String PLAIN_TEXT = "txt";
    private static final int ENABLED_COLUMN = 0;
    private static final int NAME_COLUMN = 1;
    private static final int VALUE_COLUMN = 2;
    /** 헤더 표의 인증 줄에 보일 값 길이. 넘으면 「…」 로 줄인다. */
    private static final int AUTH_SHOWN_CHARS = 24;
    private static final String RESPONSE_EMPTY_CARD = "empty";
    private static final String RESPONSE_SHOWN_CARD = "shown";

    private final Project project;
    private final VirtualFile file;
    private final JPanel root = new JPanel(new BorderLayout());
    private final ComboBox<String> methodBox = new ComboBox<>(HttpText.METHODS);
    private final JBTextField urlField = new JBTextField();
    private final JButton sendButton = new JButton(BootServersBundle.message("api.send"), AllIcons.Actions.Execute);
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
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JPanel responseCards = new JPanel(new CardLayout());
    private final EditorTextField responseBody;
    private final EditorTextField responseHeaders;
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
        responseHeaders = textField(byExtension(PLAIN_TEXT), true);

        JPanel top = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        top.add(methodBox, BorderLayout.WEST);
        top.add(urlField, BorderLayout.CENTER);
        JButton reset = new JButton(BootServersBundle.message("api.reset"), AllIcons.Actions.Rollback);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        buttons.add(sendButton);
        buttons.add(reset);
        top.add(buttons, BorderLayout.EAST);
        top.setBorder(JBUI.Borders.empty(8));

        JBTabbedPane requestTabs = new JBTabbedPane();
        // 탭이 넣는 안쪽 여백은 바탕색 틀로 보이므로 뺀다. addTab 보다 먼저 정해야 한다.
        requestTabs.setTabComponentInsets(JBInsets.emptyInsets());
        requestTabs.addTab(BootServersBundle.message("api.tab.query"), rowsPanel(queryModel, row -> false));
        requestTabs.addTab(BootServersBundle.message("api.tab.headers"), rowsPanel(headerModel, this::isAuthRow));
        requestTabs.addTab(BootServersBundle.message("api.tab.body"), bodyField);
        requestTabs.addTab(BootServersBundle.message("api.tab.auth"), authPanel());

        JBTabbedPane responseTabs = new JBTabbedPane();
        responseTabs.setTabComponentInsets(JBInsets.emptyInsets());
        responseTabs.addTab(BootServersBundle.message("api.tab.body"), responseBody);
        responseTabs.addTab(BootServersBundle.message("api.tab.headers"), responseHeaders);
        JBLabel emptyResponse = new JBLabel(BootServersBundle.message("api.response.empty"), SwingConstants.CENTER);
        emptyResponse.setForeground(UIUtil.getContextHelpForeground());
        responseCards.add(emptyResponse, RESPONSE_EMPTY_CARD);
        responseCards.add(responseTabs, RESPONSE_SHOWN_CARD);
        JBLabel responseTitle = new JBLabel(BootServersBundle.message("api.response"));
        responseTitle.setFont(JBFont.label().asBold());
        JPanel responseHead = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        responseHead.add(responseTitle, BorderLayout.WEST);
        responseHead.add(statusLabel, BorderLayout.EAST);
        responseHead.setBorder(JBUI.Borders.empty(10, 10, 4, 10));
        JPanel response = new JPanel(new BorderLayout());
        response.add(responseHead, BorderLayout.NORTH);
        response.add(responseCards, BorderLayout.CENTER);

        OnePixelSplitter split = new OnePixelSplitter(true, REQUEST_RESPONSE_SPLIT);
        split.setFirstComponent(requestTabs);
        split.setSecondComponent(response);
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);

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
            editor.setBorder(JBUI.Borders.empty());
            editor.getScrollPane().setBorder(JBUI.Borders.empty());
            editor.getSettings().setLineNumbersShown(false);
        });
        return field;
    }

    private static FileType byExtension(String extension) {
        return FileTypeManager.getInstance().getFileTypeByExtension(extension);
    }

    /** locked 줄은 체크만 고칠 수 있다. */
    private static DefaultTableModel rowsModel(IntPredicate locked) {
        return new DefaultTableModel(new Object[]{"", BootServersBundle.message("api.column.name"),
                BootServersBundle.message("api.column.value")}, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                return column == ENABLED_COLUMN ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == ENABLED_COLUMN || !locked.test(row);
            }
        };
    }

    /** 체크 · 이름 · 값 표와 줄 더하기 · 빼기 단추. 체크를 끈 줄은 보내지 않는다. locked 줄은 흐린 글자로 보인다. */
    private static JComponent rowsPanel(DefaultTableModel model, IntPredicate locked) {
        JBTable table = new JBTable(model);
        table.getColumnModel().getColumn(ENABLED_COLUMN).setMaxWidth(JBUI.scale(32));
        table.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                if (!selected) {
                    c.setForeground(locked.test(row) ? UIUtil.getContextHelpForeground() : t.getForeground());
                }
                return c;
            }
        });
        // 탭 · 위아래 나눔 선이 이미 틀을 그리므로 표 바깥 틀과 스크롤 틀은 뺀다(겹치면 두 줄로 보인다).
        // 세로 칸 선은 머리글 칸 선과 1픽셀 어긋나므로 그리지 않는다.
        table.setShowVerticalLines(false);
        return ToolbarDecorator.createDecorator(table)
                .setPanelBorder(JBUI.Borders.empty())
                .setScrollPaneBorder(JBUI.Borders.empty())
                .setAddAction(b -> model.addRow(new Object[]{true, "", ""}))
                .setRemoveAction(b -> {
                    int[] rows = table.getSelectedRows();
                    for (int i = rows.length - 1; i >= 0; i--) {
                        if (!locked.test(rows[i])) {
                            model.removeRow(rows[i]);
                        }
                    }
                })
                .disableUpDownActions()
                .createPanel();
    }

    private JComponent authPanel() {
        ButtonGroup group = new ButtonGroup();
        JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0));
        for (JBRadioButton radio : new JBRadioButton[]{authNone, authBearer, authBasic, authCookie}) {
            group.add(radio);
            kinds.add(radio);
            radio.addActionListener(e -> applyAuthKind());
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
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("api.auth.kind"), kinds)
                .addLabeledComponent(BootServersBundle.message("api.auth.token"), tokenField)
                .addLabeledComponent(BootServersBundle.message("api.auth.user"), userField)
                .addLabeledComponent(BootServersBundle.message("api.auth.password"), passwordField)
                .addLabeledComponent(BootServersBundle.message("api.auth.cookie.value"), cookieField)
                .addComponent(new JBLabel(BootServersBundle.message("api.auth.note")))
                .getPanel();
        form.setBorder(JBUI.Borders.empty(8));
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

    /** 고른 방식의 칸만 켠다. */
    private void applyAuthKind() {
        String kind = authKind();
        tokenField.setEnabled(ApiText.AUTH_BEARER.equals(kind));
        userField.setEnabled(ApiText.AUTH_BASIC.equals(kind));
        passwordField.setEnabled(ApiText.AUTH_BASIC.equals(kind));
        cookieField.setEnabled(ApiText.AUTH_COOKIE.equals(kind));
        refreshAuthRow();
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
            headerModel.insertRow(0, new Object[]{checked, auth.getKey(), value});
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

    private static void setRows(DefaultTableModel model, List<Map.Entry<String, String>> rows) {
        model.setRowCount(0);
        for (Map.Entry<String, String> row : rows) {
            model.addRow(new Object[]{true, row.getKey(), row.getValue()});
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
            showError(BootServersBundle.message("api.error.url"));
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
            showError(buildErrorMessage(e));
            return;
        }
        saveAuth(kind, secrets);
        statusLabel.setText(BootServersBundle.message("api.sending", REQUEST_TIMEOUT.toSeconds()));
        statusLabel.setForeground(JBColor.foreground());
        long start = System.nanoTime();
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
        sendButton.setText(BootServersBundle.message("api.send"));
        if (error != null) {
            showError(responseErrorMessage(error) + " (" + elapsedMs + " ms)");
            return;
        }
        String body = response.body() == null ? "" : response.body();
        statusLabel.setText(BootServersBundle.message("api.status", response.statusCode(), elapsedMs,
                body.getBytes(StandardCharsets.UTF_8).length));
        statusLabel.setForeground(response.statusCode() >= HTTP_ERROR_STATUS ? JBColor.RED : JBColor.foreground());
        StringBuilder headerText = new StringBuilder();
        String contentType = "";
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            if (header.getKey().startsWith(PSEUDO_HEADER_PREFIX)) {
                continue;
            }
            String value = String.join(", ", header.getValue());
            headerText.append(header.getKey()).append(": ").append(value).append('\n');
            if (CONTENT_TYPE.equalsIgnoreCase(header.getKey())) {
                contentType = value.toLowerCase(Locale.ROOT);
            }
        }
        responseHeaders.setText(headerText.toString());
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
        ((CardLayout) responseCards.getLayout()).show(responseCards, RESPONSE_SHOWN_CARD);
    }

    private void showError(String message) {
        statusLabel.setText(message);
        statusLabel.setForeground(JBColor.RED);
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
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
    }
}
