package local.bootservers;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * API 요청 보내기 공통: 요청 탭(ApiRequestEditor)과 저장한 요청 다시 보내기(ApiReplay)가 같은 코드로 요청을 만들고 받는다.
 * 프로젝트마다 HttpClient 하나(쿠키 저장소로 로그인 세션 쿠키를 이어 보냄, IDE 를 다시 켜면 비워짐).
 * 인증 방식 · 아이디는 프로젝트 설정에, 토큰 · 비밀번호 · 쿠키는 IDE 비밀번호 저장소에 둔다. 요청 · 응답 내용은 로그에 남기지 않는다.
 */
final class ApiHttp {
    private ApiHttp() {
    }

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** 응답 본문을 받는 최대 바이트 수. 넘으면 나머지는 받지 않고 끊었다고 알린다. */
    static final int MAX_RECEIVED_BYTES = 16 * 1024 * 1024;
    static final String AUTH_TYPE_KEY = "ei.api.auth.type";
    static final String AUTH_USER_KEY = "ei.api.auth.user";
    /** 비밀번호 저장소의 서비스 이름 앞부분. 뒤에 프로젝트 위치 해시와 인증 방식이 붙는다. */
    private static final String AUTH_SERVICE = "EI IDE API 인증";
    static final String[] SECRET_TYPES = {ApiText.AUTH_BEARER, ApiText.AUTH_BASIC, ApiText.AUTH_COOKIE};
    private static final String CONTENT_TYPE = "content-type";
    private static final Key<HttpClient> CLIENT = Key.create("ei.api.client");

    /** 받은 응답 본문 바이트와 MAX_RECEIVED_BYTES 에서 끊었는지. */
    record Received(byte[] bytes, boolean cut) {
    }

    static CredentialAttributes secretKey(Project project, String kind) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(AUTH_SERVICE, project.getLocationHash() + "/" + kind));
    }

    /** 설정의 인증 방식 · 아이디와 비밀번호 저장소의 비밀로 만든 인증 헤더. 없으면 null. 비밀번호 저장소를 읽으므로 화면 스레드에서 부르지 않는다. */
    static @Nullable Map.Entry<String, String> savedAuth(Project project) {
        PropertiesComponent settings = PropertiesComponent.getInstance(project);
        String kind = settings.getValue(AUTH_TYPE_KEY, ApiText.AUTH_NONE);
        if (ApiText.AUTH_NONE.equals(kind)) {
            return null;
        }
        return ApiText.authHeader(kind, settings.getValue(AUTH_USER_KEY, ""), PasswordSafe.getInstance().getPassword(secretKey(project, kind)));
    }

    /** 프로젝트마다 하나. 어느 스레드에서 불러도 같은 것을 준다. */
    static HttpClient client(Project project) {
        synchronized (CLIENT) {
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
    }

    /**
     * 보낼 요청. 헤더 칸에 직접 적은 같은 이름이 인증 헤더(auth, 없으면 null)보다 앞선다. 본문이 비면 본문 없이 보낸다.
     * 주소 · 헤더가 잘못이면 IllegalArgumentException(buildErrorMessage 로 화면 글).
     */
    static HttpRequest build(String method, String url, List<Map.Entry<String, String>> query, List<Map.Entry<String, String>> headers,
                             @Nullable Map.Entry<String, String> auth, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ApiText.withQuery(url, query))).timeout(REQUEST_TIMEOUT);
        boolean authInHeaders = false;
        for (Map.Entry<String, String> header : headers) {
            builder.header(header.getKey(), header.getValue());
            authInHeaders |= auth != null && header.getKey().equalsIgnoreCase(auth.getKey());
        }
        if (auth != null && !authInHeaders) {
            builder.header(auth.getKey(), auth.getValue());
        }
        builder.method(method, body == null || body.isBlank()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return builder.build();
    }

    static CompletableFuture<HttpResponse<Received>> send(Project project, HttpRequest request) {
        return client(project).sendAsync(request, info -> new CappedBytes(MAX_RECEIVED_BYTES));
    }

    /** 응답의 Content-Type(소문자, 없으면 빈 글). */
    static String contentType(HttpResponse<?> response) {
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            if (CONTENT_TYPE.equalsIgnoreCase(header.getKey())) {
                return String.join(", ", header.getValue()).toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    /** 응답 본문 글(Content-Type 의 문자 집합, 없으면 ApiText.decode 규칙). */
    static String text(HttpResponse<Received> response) {
        Received received = received(response);
        return ApiText.decode(received.bytes(), contentType(response), received.cut());
    }

    static Received received(HttpResponse<Received> response) {
        return response.body() == null ? new Received(new byte[0], false) : response.body();
    }

    static String buildErrorMessage(IllegalArgumentException e) {
        String detail = String.valueOf(e.getMessage());
        if (detail.contains("Illegal character")) {
            return BootServersBundle.message("api.error.char");
        }
        if (detail.contains("restricted header")) {
            return BootServersBundle.message("api.error.header", detail);
        }
        return BootServersBundle.message("api.error.build", detail);
    }

    static String responseErrorMessage(Throwable error) {
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

    /** 응답 본문을 limit 바이트까지만 모은다. 넘으면 나머지는 받지 않고 연결을 끊는다. */
    private static final class CappedBytes implements HttpResponse.BodySubscriber<Received> {
        private final int limit;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<Received> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBytes(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<Received> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int take = Math.min(item.remaining(), limit - out.size());
                byte[] chunk = new byte[take];
                item.get(chunk);
                out.write(chunk, 0, take);
                if (item.hasRemaining()) {
                    result.complete(new Received(out.toByteArray(), true));
                    subscription.cancel();
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(new Received(out.toByteArray(), false));
        }
    }
}
