package local.bootservers;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

/**
 * 저장한 API 요청(ApiStore) 다시 보내기의 공개 입구. 서버 창의 「저장한 API 요청 다시 보내기」와 ei-db 의 「바꾼 것 다시 시험」이 부른다.
 * 요청 key 는 ApiRequestAction 이 만드는 「방식 패키지.클래스#메서드 URL」 이다. 다시 보낼 때는 저장한 값 · 지금 설정의 인증을 쓰고
 * 요청 탭과 같은 코드(ApiHttp)로 보낸다. 기준 응답은 accept 로만 바뀐다.
 */
public final class ApiReplay {
    private ApiReplay() {
    }

    /** 응답을 기다리며 취소를 보는 간격(ms). */
    private static final long POLL_MS = 100;

    /** 다시 보낸 결과. error 가 null 이 아니면 보내지 못함(화면 글). beforeCode 는 저장한 응답이 없으면 -1. */
    public record Result(String key, String title, String method, int beforeCode, int nowCode,
                         @Nullable String beforeBody, @Nullable String nowBody, @Nullable String error,
                         String beforePrint, String nowPrint, long savedAt) {
        /** error 없고 코드 같고 본문(JSON 이면 줄맞춤 후) 같음. */
        public boolean same() {
            return verdict() == ApiSaved.Verdict.SAME;
        }

        ApiSaved.Verdict verdict() {
            return ApiSaved.verdict(error, beforeCode, nowCode, beforePrint, nowPrint);
        }

        /** 결과 칸 글: 같음 · 상태 바뀜 200 → 500 · 본문 바뀜 · 기준 없음 · 실패(까닭). */
        public String verdictText() {
            return switch (verdict()) {
                case SAME -> BootServersBundle.message("api.resend.same");
                case STATUS -> BootServersBundle.message("api.resend.status", beforeCode, nowCode);
                case BODY -> BootServersBundle.message("api.resend.body");
                case NO_BASELINE -> BootServersBundle.message("api.resend.no.baseline");
                case FAILED -> BootServersBundle.message("api.resend.failed", error);
            };
        }
    }

    public static List<String> savedKeys(Project project, String classAndMethod) {
        // key 가운데 「 패키지.클래스#메서드 」 마디가 정확히 같은 것만(접두어가 같은 다른 메서드는 뺀다).
        String part = " " + classAndMethod + " ";
        List<String> out = new ArrayList<>();
        for (String key : ApiStore.getInstance(project).keys()) {
            if (key.contains(part)) {
                out.add(key);
            }
        }
        return out;
    }

    public static List<String> allSavedKeys(Project project) {
        return ApiStore.getInstance(project).keys();
    }

    public static String title(Project project, String key) {
        ApiStore.Entry e = ApiStore.getInstance(project).get(key);
        return e == null ? key : e.title;
    }

    public static boolean changesData(Project project, String key) {
        ApiStore.Entry e = ApiStore.getInstance(project).get(key);
        return e == null || ApiSaved.changesData(e.method);
    }

    /** 백그라운드에서만. 저장한 값으로 다시 보내고 결과를 돌려준다(막힘, 시간 제한은 에디터와 같음). 기준 응답은 바꾸지 않는다. */
    public static Result resend(Project project, String key) {
        ApiStore store = ApiStore.getInstance(project);
        ApiStore.Entry e = store.get(key);
        if (e == null) {
            return new Result(key, key, "", ApiSaved.NO_CODE, ApiSaved.NO_CODE, null, null,
                    BootServersBundle.message("api.resend.gone"), "", "", 0);
        }
        String before = e.code == ApiSaved.NO_CODE ? null : store.body(key);
        String error = null;
        int code = ApiSaved.NO_CODE;
        String now = null;
        try {
            HttpRequest request = ApiHttp.build(e.method, e.url.strip(), checked(e.query), checked(e.headers),
                    e.auth ? ApiHttp.savedAuth(project) : null, e.body);
            HttpResponse<ApiHttp.Received> response = await(ApiHttp.send(project, request));
            code = response.statusCode();
            now = ApiHttp.text(response);
        } catch (IllegalArgumentException ex) {
            error = ApiHttp.buildErrorMessage(ex);
        } catch (ExecutionException | CancellationException ex) {
            error = ApiHttp.responseErrorMessage(ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            error = ApiHttp.responseErrorMessage(new CancellationException());
        }
        return new Result(key, e.title, e.method, e.code, code, before, now, error, e.print,
                now == null ? "" : ApiSaved.fingerprint(now), e.savedAt);
    }

    /** 화면 스레드에서. 저장한 응답 · 이번 응답 본문을 IntelliJ 비교 창으로 보인다. */
    public static void showDiff(Project project, Result result) {
        DiffContentFactory contents = DiffContentFactory.getInstance();
        String left = ApiSaved.cap(ApiText.prettyJson(result.beforeBody() == null ? "" : result.beforeBody()));
        String right = ApiSaved.cap(ApiText.prettyJson(result.nowBody() == null ? "" : result.nowBody()));
        // 둘 중 하나라도 JSON 으로 보이면 JSON 색칠, 아니면 일반 글.
        boolean json = left.stripLeading().startsWith("{") || left.stripLeading().startsWith("[")
                || right.stripLeading().startsWith("{") || right.stripLeading().startsWith("[");
        FileType type = json ? FileTypeManager.getInstance().getFileTypeByExtension("json") : PlainTextFileType.INSTANCE;
        String savedAt = result.savedAt() == 0 ? "" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(result.savedAt()));
        SimpleDiffRequest request = new SimpleDiffRequest(result.title(),
                contents.create(project, left, type), contents.create(project, right, type),
                BootServersBundle.message("api.resend.diff.before", result.beforeCode(), savedAt),
                BootServersBundle.message("api.resend.diff.now", result.nowCode()));
        DiffManager.getInstance().showDiff(project, request);
    }

    /** 이번 응답을 기준으로 저장한다. 보내지 못한 결과면 아무것도 안 한다. */
    public static void accept(Project project, Result result) {
        if (result.error() == null && result.nowBody() != null) {
            ApiStore.getInstance(project).accept(result.key(), result.nowCode(), result.nowBody());
        }
    }

    /** 응답을 기다리며 진행 표시의 취소를 본다. 취소되면 요청도 끊고 ProcessCanceledException 을 그대로 던진다. */
    private static <T> T await(CompletableFuture<T> future) throws ExecutionException, InterruptedException {
        while (true) {
            try {
                return future.get(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                try {
                    ProgressManager.checkCanceled();
                } catch (ProcessCanceledException cancelled) {
                    future.cancel(true);
                    throw cancelled;
                }
            }
        }
    }

    private static List<Map.Entry<String, String>> checked(List<ApiStore.LineBean> lines) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (ApiStore.LineBean l : lines) {
            if (l.on && l.name != null && !l.name.isBlank()) {
                out.add(Map.entry(l.name.strip(), l.value == null ? "" : l.value));
            }
        }
        return out;
    }
}
