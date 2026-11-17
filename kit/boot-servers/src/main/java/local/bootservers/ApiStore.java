package local.bootservers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Send API Request 로 보낸 요청(마지막 값)과 그 응답(기준)을 요청 key 마다 하나씩 기억한다.
 * 요청 값 · 상태 코드 · 본문 지문은 workspace.xml(리포지토리에 안 올라감)에, 응답 본문(ApiSaved.cap 으로 줄인 것)은 IDE 시스템 폴더
 * moa-api/&lt;프로젝트 위치 해시&gt;/&lt;key 지문&gt;.txt 에 둔다(workspace.xml 이 커지지 않게). Authorization 따위 헤더는 저장하지 않고,
 * 비밀처럼 보이는 값(ApiSaved.scrub)은 workspace.xml 에 빈 글로 두고 실제 값은 key 마다 IDE 비밀번호 저장소 한 항목(이름 → 값 JSON)에 둔다.
 * 목록은 화면 스레드에서만 바꾼다. 다른 스레드는 get · keys 로 읽기만 하며, 바꿀 때는 Entry 를 통째로 새로 넣는다.
 */
@Service(Service.Level.PROJECT)
@State(name = "MoaApiRequests", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ApiStore implements PersistentStateComponent<ApiStore.StateBean> {
    private static final Logger LOG = Logger.getInstance(ApiStore.class);
    private static final String FOLDER = "moa-api";
    private static final String BODY_EXTENSION = ".txt";
    private static final String TEMP_SUFFIX = ".tmp";
    /** 비밀번호 저장소의 서비스 이름 앞부분. 뒤에 프로젝트 위치 해시와 key 지문이 붙는다. */
    private static final String SECRET_SERVICE = "Moa API 저장 요청";

    /** 직렬화를 위해 공개 필드와 기본 생성자를 쓴다. */
    public static final class LineBean {
        public String name = "";
        public String value = "";
        public boolean on = true;
    }

    public static final class Entry {
        public String key = "";
        public String title = "";
        public String method = "";
        public String url = "";
        public List<LineBean> query = new ArrayList<>();
        public List<LineBean> headers = new ArrayList<>();
        /** 설정의 인증(헤더 표 맨 위 줄)을 붙였는지. */
        public boolean auth = true;
        public String body = "";
        /** 기준 응답의 상태 코드. 없으면 ApiSaved.NO_CODE. */
        public int code = ApiSaved.NO_CODE;
        /** 기준 응답 본문 지문(ApiSaved.fingerprint). */
        public String print = "";
        /** 기준을 저장한 때(에포크 ms). */
        public long savedAt;
        /** 헤더 칸에 직접 적은 Authorization · Cookie 따위를 저장하지 않고 뺐는지(다시 보내면 설정의 인증으로 나간다). */
        public boolean droppedHeader;
    }

    public static final class StateBean {
        public List<Entry> entries = new ArrayList<>();
    }

    private final Project project;
    /** 본문 파일 쓰기 · 지우기를 부른 차례대로 하나씩 한다(같은 key 를 잇달아 저장해도 마지막 것이 남게). */
    private final ExecutorService io = AppExecutorUtil.createBoundedApplicationPoolExecutor("Moa API 요청 저장", 1);
    /** 화면 스레드에서 지운 key. 그 전에 시작한 저장이 늦게 끝나도 목록에 되살리지 않는다. 다시 보내면(saveSent) 풀린다. */
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    private volatile StateBean state = new StateBean();

    public ApiStore(Project project) {
        this.project = project;
    }

    public static ApiStore getInstance(@NotNull Project project) {
        return project.getService(ApiStore.class);
    }

    @Override
    public synchronized StateBean getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull StateBean loaded) {
        state = loaded;
    }

    synchronized @Nullable Entry get(String key) {
        for (Entry e : state.entries) {
            if (e.key.equals(key)) {
                return e;
            }
        }
        return null;
    }

    /** 저장한 요청 key, 마지막 저장이 늦은 것부터. */
    synchronized List<String> keys() {
        List<Entry> sorted = new ArrayList<>(state.entries);
        sorted.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
        List<String> out = new ArrayList<>();
        for (Entry e : sorted) {
            out.add(e.key);
        }
        return out;
    }

    /** 보낸 요청 값과 받은 응답을 기준으로 저장한다. 본문 파일 쓰기 · 지문 계산은 백그라운드에서 하고 끝나면 목록에 넣는다. */
    void saveSent(Entry sent, Map<String, String> secrets, int code, String responseBody) {
        // 화면 스레드에서 불린다: 지운 뒤 다시 보낸 key 는 다시 저장한다.
        removed.remove(sent.key);
        io.execute(() -> {
            writeSecrets(sent.key, secrets);
            sent.code = code;
            sent.print = ApiSaved.fingerprint(responseBody);
            sent.savedAt = System.currentTimeMillis();
            // 비교 창 왼쪽(기준)도 오른쪽(이번 응답, ApiReplay.resend)처럼 줄맞춤한 뒤 줄인다.
            writeBody(sent.key, ApiSaved.cap(ApiText.prettyJson(responseBody)));
            ApplicationManager.getApplication().invokeLater(() -> {
                // 저장하는 사이 지웠으면 되살리지 않는다(지우기가 본문 · 비밀을 뒤에서 지운다).
                if (!removed.contains(sent.key)) {
                    put(sent);
                }
            }, project.getDisposed());
        });
    }

    /** 다시 보낸 응답을 기준으로 삼는다(요청 값은 그대로). print 는 받은 본문 전체의 지문, shownBody 는 줄인 본문. 그 사이 지운 요청이면 아무것도 안 한다. */
    void accept(String key, int code, String print, String shownBody) {
        io.execute(() -> {
            if (get(key) == null) {
                return;
            }
            writeBody(key, ApiSaved.cap(shownBody));
            ApplicationManager.getApplication().invokeLater(() -> {
                // 본문을 쓰는 사이 지웠으면 되살리지 않는다.
                Entry old = get(key);
                if (old == null) {
                    return;
                }
                Entry e = copy(old);
                e.code = code;
                e.print = print;
                e.savedAt = System.currentTimeMillis();
                put(e);
            }, project.getDisposed());
        });
    }

    /** 화면 스레드에서. 목록과 본문 파일을 지운다. */
    void remove(String key) {
        removed.add(key);
        synchronized (this) {
            List<Entry> next = new ArrayList<>(state.entries);
            next.removeIf(e -> e.key.equals(key));
            state.entries = next;
        }
        io.execute(() -> {
            deleteBody(key);
            writeSecrets(key, Map.of());
        });
    }

    /** 화면 스레드에서. 같은 key 는 바꾸고, MAX_SAVED 를 넘으면 저장이 가장 이른 것부터 버린다. */
    private void put(Entry entry) {
        List<String> dropped = new ArrayList<>();
        synchronized (this) {
            List<Entry> next = new ArrayList<>(state.entries);
            next.removeIf(e -> e.key.equals(entry.key));
            next.add(entry);
            while (next.size() > ApiSaved.MAX_SAVED) {
                Entry oldest = next.get(0);
                for (Entry e : next) {
                    if (e.savedAt < oldest.savedAt) {
                        oldest = e;
                    }
                }
                next.remove(oldest);
                dropped.add(oldest.key);
            }
            state.entries = next;
        }
        if (!dropped.isEmpty()) {
            io.execute(() -> {
                for (String key : dropped) {
                    deleteBody(key);
                    writeSecrets(key, Map.of());
                }
            });
        }
    }

    /** 기준 응답 본문(줄인 것). 없거나 읽지 못하면 null. 디스크를 읽으므로 화면 스레드에서 부르지 않는다. */
    @Nullable String body(String key) {
        try {
            return Files.readString(bodyPath(key), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            LOG.warn("API 기준 응답을 읽지 못함", e);
            return null;
        }
    }

    /** 임시 파일에 쓴 뒤 옮겨, 읽는 쪽이 반쯤 쓴 본문을 보지 않게 한다. */
    private void writeBody(String key, String text) {
        try {
            Path path = bodyPath(key);
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), TEMP_SUFFIX);
            try {
                Files.writeString(temp, text, StandardCharsets.UTF_8);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            // 본문 파일이 없어도 비교(지문)는 되고 비교 창의 왼쪽만 빈다.
            LOG.warn("API 기준 응답을 저장하지 못함", e);
        }
    }

    /** 저장해 둔 비밀 값(ApiSaved.scrub 의 secrets). 없거나 읽지 못하면 빈 맵. 비밀번호 저장소를 읽으므로 화면 스레드에서 부르지 않는다. */
    Map<String, String> secrets(String key) {
        String text = PasswordSafe.getInstance().getPassword(secretKey(key));
        if (text == null || text.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, String> out = new HashMap<>();
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
            return out;
        } catch (RuntimeException e) {
            // 값은 로그에 남기지 않는다.
            LOG.warn("API 요청의 저장한 비밀 값을 읽지 못함: " + e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** 비밀 값을 저장한다. 비었으면 지운다. 백그라운드에서. */
    private void writeSecrets(String key, Map<String, String> secrets) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            json.addProperty(e.getKey(), e.getValue());
        }
        PasswordSafe.getInstance().setPassword(secretKey(key), secrets.isEmpty() ? null : json.toString());
    }

    private CredentialAttributes secretKey(String key) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SECRET_SERVICE,
                project.getLocationHash() + "/" + ApiSaved.fingerprint(key)));
    }

    private void deleteBody(String key) {
        try {
            Files.deleteIfExists(bodyPath(key));
        } catch (IOException e) {
            LOG.warn("API 기준 응답 파일을 지우지 못함", e);
        }
    }

    private Path bodyPath(String key) {
        return Path.of(PathManager.getSystemPath(), FOLDER, project.getLocationHash(), ApiSaved.fingerprint(key) + BODY_EXTENSION);
    }

    static Entry copy(Entry e) {
        Entry c = new Entry();
        c.key = e.key;
        c.title = e.title;
        c.method = e.method;
        c.url = e.url;
        c.query = e.query;
        c.headers = e.headers;
        c.auth = e.auth;
        c.body = e.body;
        c.code = e.code;
        c.print = e.print;
        c.savedAt = e.savedAt;
        c.droppedHeader = e.droppedHeader;
        return c;
    }

    static List<ApiSaved.Line> lines(List<LineBean> beans) {
        List<ApiSaved.Line> out = new ArrayList<>();
        for (LineBean b : beans) {
            // 직렬화로 읽은 빈 글은 null 일 수 있다.
            out.add(new ApiSaved.Line(b.name == null ? "" : b.name, b.value == null ? "" : b.value, b.on));
        }
        return out;
    }

    static List<LineBean> beans(List<ApiSaved.Line> lines) {
        List<LineBean> out = new ArrayList<>();
        for (ApiSaved.Line l : lines) {
            LineBean b = new LineBean();
            b.name = l.name();
            b.value = l.value();
            b.on = l.on();
            out.add(b);
        }
        return out;
    }
}
