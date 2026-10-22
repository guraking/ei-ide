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
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
    private static final Map<String, String> FILES = Map.of(
            "swagger-ui-bundle.js", "application/javascript",
            "swagger-ui.css", "text/css");
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final Logger LOG = Logger.getInstance(SwaggerView.class);

    // persistAuthorization: 「Authorize」 에 넣은 토큰을 이 출처의 브라우저 저장소에 두어 다시 열어도 남는다.
    private static final String PAGE = """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <link rel="stylesheet" href="swagger-ui.css">
            <style>body{margin:0;background:#fff}</style>
            </head><body><div id="ui"></div>
            <script src="swagger-ui-bundle.js"></script>
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
        this.origin = uri.getScheme() + "://" + uri.getAuthority();
        if (browser == null) {
            return;
        }
        // 매번 다른 주소로 열어 브라우저가 앞 사양을 다시 쓰지 않게 한다.
        String url = origin + PREFIX + INDEX + "?v=" + (++loads) + (anchor == null ? "" : "#/" + anchor);
        lastUrl = url;
        browser.loadURL(url);
    }

    /** 가로챌 요청이면 PREFIX 뒤 파일 이름, 아니면 null. */
    private String ownName(String url) {
        String own = origin == null ? null : origin + PREFIX;
        if (own == null || url == null || !url.startsWith(own)) {
            return null;
        }
        String name = url.substring(own.length());
        int cut = name.indexOf('?');
        if (cut < 0) {
            cut = name.indexOf('#');
        }
        return cut < 0 ? name : name.substring(0, cut);
    }

    private CefResourceHandler answer(String name) {
        if (INDEX.equals(name)) {
            return new Bytes(HTTP_OK, "text/html", PAGE.getBytes(StandardCharsets.UTF_8));
        }
        if (SPEC.equals(name)) {
            return new Bytes(HTTP_OK, "application/json", specJson.getBytes(StandardCharsets.UTF_8));
        }
        String mime = FILES.get(name);
        if (mime != null) {
            try (InputStream in = SwaggerView.class.getResourceAsStream(RESOURCE_DIR + name)) {
                if (in != null) {
                    return new Bytes(HTTP_OK, mime, in.readAllBytes());
                }
            } catch (IOException e) {
                LOG.warn("Swagger UI 파일을 읽지 못했습니다: " + name, e);
            }
        }
        return new Bytes(HTTP_NOT_FOUND, "text/plain", new byte[0]);
    }

    @Override
    public void dispose() {
    }

    /** 메모리의 바이트로 답하는 응답. CEF 의 새 방식(open · read)과 옛 방식(processRequest · readResponse)을 함께 받는다. */
    private static final class Bytes extends CefResourceHandlerAdapter {
        private final int status;
        private final String mime;
        private final byte[] data;
        private int offset;

        Bytes(int status, String mime, byte[] data) {
            this.status = status;
            this.mime = mime;
            this.data = data;
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
            response.setHeaderByName("Cache-Control", "no-store", true);
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
