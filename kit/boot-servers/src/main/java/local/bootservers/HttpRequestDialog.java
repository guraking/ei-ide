package local.bootservers;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLException;
import javax.swing.Action;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 가벼운 HTTP 요청 창(비모달). 방식 · URL · 헤더(한 줄에 「이름: 값」) · 본문을 적어 보내고
 * 상태 코드 · 걸린 시간 · 응답 헤더 · 본문(JSON 이면 줄맞춤)을 보여 준다. 보낸 요청은 「최근」 에서 다시 고른다.
 * 요청 · 응답 내용은 IDE 로그에 남기지 않는다.
 */
final class HttpRequestDialog extends DialogWrapper {

    static final String TITLE = "HTTP 요청";

    private static final Logger LOG = Logger.getInstance(HttpRequestDialog.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** 화면에 넣을 응답 본문 최대 글자 수. 넘으면 앞부분만 보여 준다. */
    private static final int MAX_SHOWN_CHARS = 2_000_000;
    private static final int HTTP_ERROR_STATUS = 400;
    private static final String DEFAULT_HEADERS = "Content-Type: application/json";
    private static final String JSON_MARK = "json";
    private static final String CONTENT_TYPE = "content-type";
    private static final String PSEUDO_HEADER_PREFIX = ":";
    private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://.+");
    private static final String[] CONFIG_FILES = {"application.properties", "application.yml", "application.yaml"};
    private static final float REQUEST_RESPONSE_SPLIT = 0.4f;
    private static final float HEADERS_BODY_SPLIT = 0.35f;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            // 개발 서버가 h2c 올리기 요청을 잘못 받는 일이 있어 HTTP/1.1 로 고정한다.
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final Project project;
    private final ComboBox<HttpHistory.Request> recent = new ComboBox<>();
    private final ComboBox<String> methodBox = new ComboBox<>(HttpText.METHODS);
    private final JBTextField urlField = new JBTextField();
    private final JBTextArea headersArea = new JBTextArea();
    private final JBTextArea bodyArea = new JBTextArea();
    private final JBLabel statusLabel = new JBLabel(" ");
    private final JBTextArea responseHeadersArea = new JBTextArea();
    private final JBTextArea responseBodyArea = new JBTextArea();
    private boolean loadingRecent;
    private CompletableFuture<HttpResponse<String>> pending;

    /**
     * 기본 주소를 백그라운드에서 정한 뒤 창을 연다. anchor 는 컨트롤러 메서드처럼 어느 모듈의 요청인지 알려 주는 요소(없으면 null).
     * 기본 주소: 실행 중인 서버의 포트(anchor 모듈을 실행하는 서버 우선) → 설정 파일 server.port → 8080, 여기에 컨텍스트 경로.
     */
    static void open(Project project, @Nullable SmartPsiElementPointer<? extends PsiElement> anchor, String method, String path) {
        ReadAction.nonBlocking(() -> {
                    PsiElement element = anchor == null ? null : anchor.getElement();
                    Module module = element == null ? null : ModuleUtilCore.findModuleForPsiElement(element);
                    return HttpText.fullUrl(resolveBase(project, module), path);
                })
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), url -> new HttpRequestDialog(project, method, url).show())
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /** 읽기 동작 안에서 부른다. */
    private static String resolveBase(Project project, @Nullable Module controller) {
        Integer port = null;
        Module running = null;
        ServerRegistry registry = ServerRegistry.getInstance(project);
        for (RunnerAndConfigurationSettings s : RunManager.getInstance(project)
                .getConfigurationSettingsList(ApplicationConfigurationType.getInstance())) {
            ServerRegistry.Run run = registry.get(s.getName());
            if (run == null || run.port == null || ServerLauncher.runningDescriptors(s).isEmpty()) {
                continue;
            }
            Module m = ((ApplicationConfiguration) s.getConfiguration()).getConfigurationModule().getModule();
            boolean serves = controller != null && m != null
                    && (m.equals(controller) || ModuleUtilCore.getAllDependentModules(controller).contains(m));
            if (port == null || serves) {
                port = run.port;
                running = m;
            }
            if (serves) {
                break;
            }
        }
        List<VirtualFile> configs = configFiles(project, controller, running);
        if (port == null) {
            port = HttpText.parsePort(configValue(configs, HttpText.PORT_KEY));
        }
        String contextPath = null;
        for (String key : HttpText.CONTEXT_PATH_KEYS) {
            contextPath = configValue(configs, key);
            if (contextPath != null) {
                break;
            }
        }
        return HttpText.baseUrl(port != null ? port : HttpText.DEFAULT_PORT, contextPath);
    }

    /** 컨트롤러 모듈 → 실행 중인 서버 모듈 → 프로젝트 전체 순으로, 설정 파일이 처음 나오는 범위의 파일들(시험 자원 제외). 색인 중이면 빈 목록. */
    private static List<VirtualFile> configFiles(Project project, @Nullable Module controller, @Nullable Module running) {
        List<VirtualFile> result = new ArrayList<>();
        if (DumbService.isDumb(project)) {
            return result;
        }
        Set<GlobalSearchScope> scopes = new LinkedHashSet<>();
        for (Module m : new Module[]{controller, running}) {
            if (m != null) {
                scopes.add(GlobalSearchScope.moduleScope(m));
            }
        }
        scopes.add(GlobalSearchScope.projectScope(project));
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        for (GlobalSearchScope scope : scopes) {
            for (String name : CONFIG_FILES) {
                for (VirtualFile file : FilenameIndex.getVirtualFilesByName(name, scope)) {
                    if (!index.isInTestSourceContent(file)) {
                        result.add(file);
                    }
                }
            }
            if (!result.isEmpty()) {
                break;
            }
        }
        return result;
    }

    private static String configValue(List<VirtualFile> files, String key) {
        for (VirtualFile file : files) {
            try {
                String value = HttpText.configValue(file.getName(), VfsUtilCore.loadText(file), key);
                if (value != null) {
                    return value;
                }
            } catch (IOException e) {
                LOG.warn("설정 파일을 읽지 못했습니다: " + file.getPath(), e);
            }
        }
        return null;
    }

    private HttpRequestDialog(Project project, String method, String url) {
        super(project, false);
        this.project = project;
        setModal(false);
        setTitle(TITLE);
        setOKButtonText("보내기");
        getCancelAction().putValue(Action.NAME, "닫기");
        methodBox.setSelectedItem(method);
        urlField.setText(url);
        init();
        applyDefaultHeaders();
        reloadRecent();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        Font mono = EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
        for (JBTextArea area : new JBTextArea[]{headersArea, bodyArea, responseHeadersArea, responseBodyArea}) {
            area.setFont(mono);
        }
        urlField.setFont(mono);
        responseHeadersArea.setEditable(false);
        responseBodyArea.setEditable(false);
        headersArea.getEmptyText().setText("한 줄에 하나씩 「이름: 값」");
        bodyArea.getEmptyText().setText("본문(JSON 등)");
        urlField.getEmptyText().setText("http://localhost:8080/api/...");
        methodBox.addActionListener(e -> applyDefaultHeaders());
        recent.addActionListener(e -> fillFromRecent());

        JPanel recentRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        recentRow.add(new JBLabel("최근"), BorderLayout.WEST);
        recentRow.add(recent, BorderLayout.CENTER);
        JPanel requestRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        requestRow.add(methodBox, BorderLayout.WEST);
        requestRow.add(urlField, BorderLayout.CENTER);
        JPanel north = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        north.add(recentRow, BorderLayout.NORTH);
        north.add(requestRow, BorderLayout.SOUTH);
        north.setBorder(JBUI.Borders.emptyBottom(6));

        JBSplitter request = new JBSplitter(false, HEADERS_BODY_SPLIT);
        request.setFirstComponent(section("헤더", headersArea));
        request.setSecondComponent(section("본문", bodyArea));
        JBSplitter response = new JBSplitter(false, HEADERS_BODY_SPLIT);
        response.setFirstComponent(section("응답 헤더", responseHeadersArea));
        response.setSecondComponent(section("응답 본문", responseBodyArea));
        JPanel responsePanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        responsePanel.add(statusLabel, BorderLayout.NORTH);
        responsePanel.add(response, BorderLayout.CENTER);
        responsePanel.setBorder(JBUI.Borders.emptyTop(6));
        JBSplitter center = new JBSplitter(true, REQUEST_RESPONSE_SPLIT);
        center.setFirstComponent(request);
        center.setSecondComponent(responsePanel);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        panel.setPreferredSize(JBUI.size(900, 640));
        return panel;
    }

    private static JComponent section(String title, JComponent area) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        panel.add(new JBLabel(title), BorderLayout.NORTH);
        panel.add(new JBScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "KingGura.HttpRequest";
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return urlField;
    }

    /** 본문 방식으로 바꿨는데 헤더가 비어 있으면 기본 Content-Type 을 넣는다. */
    private void applyDefaultHeaders() {
        if (HttpText.hasBody(selectedMethod()) && headersArea.getText().isBlank()) {
            headersArea.setText(DEFAULT_HEADERS);
        }
    }

    private String selectedMethod() {
        Object m = methodBox.getSelectedItem();
        return m == null ? HttpText.METHODS[0] : m.toString();
    }

    private void reloadRecent() {
        loadingRecent = true;
        try {
            recent.setModel(new DefaultComboBoxModel<>(HttpHistory.getInstance(project).list().toArray(new HttpHistory.Request[0])));
            recent.setSelectedItem(null);
        } finally {
            loadingRecent = false;
        }
    }

    private void fillFromRecent() {
        if (loadingRecent || !(recent.getSelectedItem() instanceof HttpHistory.Request r)) {
            return;
        }
        methodBox.setSelectedItem(r.method());
        urlField.setText(r.url());
        headersArea.setText(r.headers());
        bodyArea.setText(r.body());
    }

    /** 「보내기」: 보내는 중이면 취소한다. 창은 닫지 않는다. */
    @Override
    protected void doOKAction() {
        if (pending != null) {
            pending.cancel(true);
            return;
        }
        send();
    }

    private void send() {
        String method = selectedMethod();
        String url = urlField.getText().strip();
        String headers = headersArea.getText();
        String body = bodyArea.getText();
        if (!HTTP_URL.matcher(url).matches()) {
            showError("URL 은 http:// 또는 https:// 로 시작해야 합니다.");
            return;
        }
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
            for (Map.Entry<String, String> header : HttpText.parseHeaders(headers)) {
                builder.header(header.getKey(), header.getValue());
            }
            builder.method(method, body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            request = builder.build();
        } catch (IllegalArgumentException e) {
            showError(buildErrorMessage(e));
            return;
        }
        HttpHistory.getInstance(project).add(new HttpHistory.Request(method, url, headers, body));
        reloadRecent();
        statusLabel.setText("보내는 중… (" + REQUEST_TIMEOUT.toSeconds() + "초 뒤 시간 초과)");
        statusLabel.setForeground(JBColor.foreground());
        responseHeadersArea.setText("");
        responseBodyArea.setText("");
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<String>> future = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        pending = future;
        setOKButtonText("취소");
        future.whenComplete((response, error) -> {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            ApplicationManager.getApplication().invokeLater(() -> finish(future, response, error, elapsedMs), ModalityState.any());
        });
    }

    private void finish(CompletableFuture<HttpResponse<String>> future, HttpResponse<String> response, Throwable error, long elapsedMs) {
        if (future != pending || isDisposed()) {
            return;
        }
        pending = null;
        setOKButtonText("보내기");
        if (error != null) {
            showError(responseErrorMessage(error) + " (" + elapsedMs + " ms)");
            return;
        }
        statusLabel.setText("상태 " + response.statusCode() + " · " + elapsedMs + " ms");
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
                contentType = value;
            }
        }
        responseHeadersArea.setText(headerText.toString());
        responseHeadersArea.setCaretPosition(0);
        String body = response.body() == null ? "" : response.body();
        if (contentType.toLowerCase().contains(JSON_MARK)) {
            body = HttpText.prettyJson(body);
        }
        if (body.length() > MAX_SHOWN_CHARS) {
            body = body.substring(0, MAX_SHOWN_CHARS) + "\n… (앞 " + MAX_SHOWN_CHARS + "자만 보여 줍니다)";
        }
        responseBodyArea.setText(body);
        responseBodyArea.setCaretPosition(0);
    }

    private void showError(String message) {
        statusLabel.setText(message);
        statusLabel.setForeground(JBColor.RED);
    }

    private static String buildErrorMessage(IllegalArgumentException e) {
        String detail = String.valueOf(e.getMessage());
        if (detail.contains("Illegal character")) {
            return "URL 에 쓸 수 없는 글자가 있습니다. {id} 같은 경로 변수는 값으로 바꾸세요.";
        }
        if (detail.contains("restricted header")) {
            return "직접 정할 수 없는 헤더입니다(Host · Content-Length · Connection 등): " + detail;
        }
        return "요청을 만들 수 없습니다: " + detail;
    }

    private static String responseErrorMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof CancellationException) {
            return "요청을 취소했습니다.";
        }
        if (cause instanceof HttpConnectTimeoutException) {
            return "연결 시간 초과(" + CONNECT_TIMEOUT.toSeconds() + "초). 주소 · 포트를 확인하세요.";
        }
        if (cause instanceof HttpTimeoutException) {
            return "응답 시간 초과(" + REQUEST_TIMEOUT.toSeconds() + "초).";
        }
        if (cause instanceof ConnectException) {
            return "연결할 수 없습니다. 서버가 떠 있는지, 주소 · 포트가 맞는지 확인하세요.";
        }
        if (cause instanceof SSLException) {
            return "보안 연결(SSL) 실패: " + cause.getMessage();
        }
        if (cause instanceof IOException) {
            return "요청 실패: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        }
        return "요청 실패: " + cause;
    }

    @Override
    protected void dispose() {
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        super.dispose();
    }
}
