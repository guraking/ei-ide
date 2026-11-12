package local.bootservers;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import javax.swing.JComponent;
import javax.swing.SwingConstants;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 편집기 안의 Swagger UI 화면(JCEF). Swagger UI 파일은 jar 에 넣어 두어 인터넷 없이 돈다.
 *
 * <p>화면은 대상 서버와 같은 출처(http://호스트:포트)의 가짜 경로 {@link #PREFIX} 아래에서 연다.
 * 그 경로 요청은 이 브라우저가 가로채 jar 의 파일 · 지금 사양으로 답하고 서버로 보내지 않는다.
 * 그래서 「Try it out」 요청은 같은 출처 요청이 되어 서버에 CORS 설정이 없어도 된다.
 * shortcut: 사양의 servers 가 다른 호스트 · 포트면 그쪽 서버의 CORS 설정이 있어야 한다. 그런 사양이 흔해지면 요청을 자바에서 대신 보내는 방식으로 바꾼다.
 */
final class SwaggerView implements Disposable {

    /** 서버에 실제로 없을 경로. 이 아래 요청만 가로챈다. */
    static final String PREFIX = "/__ei-swagger__/";
    private static final String INDEX = "index.html";
    private static final String SPEC = "spec.json";
    private static final String RESOURCE_DIR = "/swagger-ui/";
    private static final String BUNDLE = "swagger-ui-bundle.js";
    private static final String CSS = "swagger-ui.css";
    private static final Map<String, String> FILES = Map.of(
            BUNDLE, "application/javascript",
            CSS, "text/css");
    private static final String HTTPS = "https";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final Logger LOG = Logger.getInstance(SwaggerView.class);
    // jar 의 Swagger UI 파일은 플러그인이 바뀌기 전에는 그대로라 브라우저가 오래 두고 쓴다. 주소에 내용 지문을 붙여 바뀌면 새로 받게 한다.
    private static final String CACHE_STATIC = "max-age=31536000, immutable";
    // 사양 · 첫 화면은 고칠 때마다 바뀌므로 두지 않는다.
    private static final String CACHE_NONE = "no-store";
    private static final int RADIX_HEX = 16;
    // jar 에서 한 번 읽은 Swagger UI 파일(이름 → 내용). 글을 고칠 때마다 다시 읽지 않는다.
    private static final Map<String, byte[]> STATIC_FILES = new ConcurrentHashMap<>();

    // persistAuthorization: 「Authorize」 에 넣은 토큰을 이 출처의 브라우저 저장소에 두어 다시 열어도 남는다.
    private static final String PAGE = """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <link rel="stylesheet" href="swagger-ui.css?v=%s">
            <style>body{margin:0;background:#fff}</style>
            </head><body><div id="ui"></div>
            <script src="swagger-ui-bundle.js?v=%s"></script>
            <script>
            window.ui = SwaggerUIBundle({url: 'spec.json', dom_id: '#ui', deepLinking: true, persistAuthorization: true,
              tryItOutEnabled: true, displayRequestDuration: true, filter: true});
            </script></body></html>
            """;

    private final JBCefBrowser browser;
    private final JComponent component;
    private volatile String origin;
    private volatile String specJson = "{}";
    private volatile String lastUrl;
    private int loads;

    SwaggerView(Disposable parent) {
        Disposer.register(parent, this);
        if (!JBCefApp.isSupported()) {
            browser = null;
            component = new JBLabel(BootServersBundle.message("swagger.no.jcef"), SwingConstants.CENTER);
            return;
        }
        browser = new JBCefBrowser();
        Disposer.register(this, browser);
        browser.getJBCefClient().addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public CefResourceRequestHandler getResourceRequestHandler(CefBrowser b, CefFrame frame, CefRequest request,
                                                                       boolean navigation, boolean download, String initiator,
                                                                       BoolRef disableDefault) {
                String name = ownName(request.getURL());
                return name == null ? null : new CefResourceRequestHandlerAdapter() {
                    @Override
                    public CefResourceHandler getResourceHandler(CefBrowser b2, CefFrame f2, CefRequest r2) {
                        return answer(name);
                    }
                };
            }
        }, browser.getCefBrowser());
        // 내장 브라우저는 창이 처음 보일 때 만들어진다. 그 전에 연 주소는 가로채기 전에 빈 화면으로 끝날 수 있어, 만들어진 뒤 마지막 주소를 다시 연다.
        browser.getJBCefClient().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public void onAfterCreated(CefBrowser b) {
                String url = lastUrl;
                if (url != null) {
                    b.loadURL(url);
                }
            }
        }, browser.getCefBrowser());
        component = browser.getComponent();
    }

    JComponent component() {
        return component;
    }

    /**
     * 사양을 바꿔 화면을 다시 연다. base 는 대상 서버 주소(http://localhost:8080/ctx), anchor 는 펼칠 API(「태그/operationId」, 없으면 null).
     * base 가 http(s) 주소가 아니면 IllegalArgumentException.
     */
    void show(String base, String specJson, String anchor) {
        URI uri = URI.create(base);
        if (uri.getScheme() == null || !uri.getScheme().startsWith("http") || uri.getHost() == null) {
            throw new IllegalArgumentException("서버 주소가 아닙니다: " + base);
        }
        this.specJson = specJson;
        this.origin = origin(uri);
        if (browser == null) {
            return;
        }
        // 매번 다른 주소로 열어 브라우저가 앞 사양을 다시 쓰지 않게 한다.
        String url = origin + PREFIX + INDEX + "?v=" + (++loads) + (anchor == null ? "" : "#/" + anchor);
        lastUrl = url;
        browser.loadURL(url);
    }

    /**
     * scheme://host[:port]. 브라우저와 같게 scheme · host 는 소문자로, 기본 포트(http 80 · https 443)는 뺀다.
     * 브라우저가 넘기는 요청 주소에는 기본 포트가 없으므로 양쪽을 이것으로 맞춰 견준다.
     */
    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == (HTTPS.equals(scheme) ? HTTPS_PORT : HTTP_PORT);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port < 0 || defaultPort ? "" : ":" + port);
    }

    /** 가로챌 요청이면 PREFIX 뒤 파일 이름, 아니면 null. */
    private String ownName(String url) {
        String own = origin;
        if (own == null || url == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        String path = uri.getRawPath();
        if (uri.getScheme() == null || uri.getHost() == null || path == null || !path.startsWith(PREFIX)
                || !own.equals(origin(uri))) {
            return null;
        }
        return path.substring(PREFIX.length());
    }

    private CefResourceHandler answer(String name) {
        if (INDEX.equals(name)) {
            String page = PAGE.formatted(fingerprint(CSS), fingerprint(BUNDLE));
            return new Bytes(HTTP_OK, "text/html", page.getBytes(StandardCharsets.UTF_8), CACHE_NONE);
        }
        if (SPEC.equals(name)) {
            return new Bytes(HTTP_OK, "application/json", specJson.getBytes(StandardCharsets.UTF_8), CACHE_NONE);
        }
        String mime = FILES.get(name);
        byte[] data = mime == null ? null : staticFile(name);
        return data != null ? new Bytes(HTTP_OK, mime, data, CACHE_STATIC) : new Bytes(HTTP_NOT_FOUND, "text/plain", new byte[0], CACHE_NONE);
    }

    /** jar 의 Swagger UI 파일. 처음 한 번만 읽는다. 없거나 못 읽으면 null(다음에 다시 읽어 본다). */
    private static byte[] staticFile(String name) {
        byte[] cached = STATIC_FILES.get(name);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = SwaggerView.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                LOG.warn("Swagger UI 파일이 jar 에 없습니다: " + name);
                return null;
            }
            byte[] data = in.readAllBytes();
            STATIC_FILES.put(name, data);
            return data;
        } catch (IOException e) {
            LOG.warn("Swagger UI 파일을 읽지 못했습니다: " + name, e);
            return null;
        }
    }

    /** 파일 내용 지문(주소 꼬리). 파일이 없으면 0. */
    private static String fingerprint(String name) {
        byte[] data = staticFile(name);
        return data == null ? "0" : Integer.toString(Arrays.hashCode(data), RADIX_HEX);
    }

    @Override
    public void dispose() {
    }

    /** 메모리의 바이트로 답하는 응답. CEF 의 새 방식(open · read)과 옛 방식(processRequest · readResponse)을 함께 받는다. */
    private static final class Bytes extends CefResourceHandlerAdapter {
        private final int status;
        private final String mime;
        private final byte[] data;
        private final String cacheControl;
        private int offset;

        Bytes(int status, String mime, byte[] data, String cacheControl) {
            this.status = status;
            this.mime = mime;
            this.data = data;
            this.cacheControl = cacheControl;
        }

        @Override
        public boolean open(CefRequest request, BoolRef handleRequest, CefCallback callback) {
            handleRequest.set(true);
            return true;
        }

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            callback.Continue();
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef length, StringRef redirect) {
            response.setStatus(status);
            response.setMimeType(mime);
            response.setHeaderByName("Cache-Control", cacheControl, true);
            length.set(data.length);
        }

        @Override
        public boolean read(byte[] out, int bytesToRead, IntRef bytesRead, CefResourceReadCallback callback) {
            return copy(out, bytesToRead, bytesRead);
        }

        @Override
        public boolean readResponse(byte[] out, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            return copy(out, bytesToRead, bytesRead);
        }

        private boolean copy(byte[] out, int bytesToRead, IntRef bytesRead) {
            int n = Math.min(bytesToRead, data.length - offset);
            if (n <= 0) {
                bytesRead.set(0);
                return false;
            }
            System.arraycopy(data, offset, out, 0, n);
            offset += n;
            bytesRead.set(n);
            return true;
        }
    }
}
