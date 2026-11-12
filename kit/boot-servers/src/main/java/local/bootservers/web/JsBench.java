package local.bootservers.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 자바스크립트 작업대의 실행(IntelliJ 클래스 없음). 코드 · 입력 데이터를 새 임시 폴더에 쓰고 jar 안 실행기(HARNESS)를 Node 로 돌린다.
 * 실행기는 한 줄에 사건 하나를 MARK + JSON 으로 내보내고, MARK 없는 줄은 코드가 직접 쓴 글로 본다.
 * 한 번 실행은 TIMEOUT_MS 안에 끝나야 하고, 출력이 OUTPUT_LIMIT 글자를 넘으면 그 자리에서 멈춘다. 임시 폴더는 끝나면 지운다.
 */
public final class JsBench {

    static final String HARNESS = "/jsbench/harness.js";
    static final String MARK = "\u001eJSB ";
    static final long TIMEOUT_MS = 5000;
    /** 출력 한도(글자). 실행기(harness.js)도 넘기면 스스로 끝내고, 여기서도 넘기면 끊는다. */
    static final int OUTPUT_LIMIT = 2_000_000;
    static final String CODE_FILE = "code.txt";
    static final String DATA_FILE = "data.txt";
    private static final String HARNESS_FILE = "harness.js";
    private static final String TEMP_PREFIX = "moa-jsbench";
    /** 끊은 뒤 남은 출력을 다 읽기까지 기다리는 시간. */
    private static final long DRAIN_MS = 1000;
    /** 브라우저 화면에만 있는 이름. 이것이 없다는 오류면 「화면 전용 코드」 안내를 붙인다. */
    private static final List<String> BROWSER_NAMES = List.of("document", "window", "localStorage", "sessionStorage",
            "navigator", "location", "alert", "confirm", "HTMLElement", "FileReader", "XMLHttpRequest", "axios", "Vue",
            "ref", "reactive", "computed", "watch", "onMounted", "defineProps", "defineEmits");
    private static final String NOT_DEFINED = " is not defined";

    /** 실행기 동작. 앞 둘은 코드를 돌리고, 나머지는 입력 데이터를 바꾼다. */
    public enum Mode {
        RUN_JS("run-js"), RUN_TS("run-ts"), TSV("tsv"), CAMEL("camel"), PRETTY("pretty"), TS("ts"), PROPS("props");

        final String arg;

        Mode(String arg) {
            this.arg = arg;
        }

        boolean runs() {
            return this == RUN_JS || this == RUN_TS;
        }
    }

    /** TRUNCATED 는 실행기가 출력 한도를 넘어 스스로 끝냈다는 표시. */
    public enum Kind { LOG, WARN, ERROR, TABLE, VALUE, OUT, TEXT, TRUNCATED }

    /** 사건 하나. TABLE 은 columns · rows · more(보이지 않은 줄 수), 나머지는 text. */
    public record Event(Kind kind, String text, List<String> columns, List<List<String>> rows, int more) {
        static Event text(Kind kind, String text) {
            return new Event(kind, text, List.of(), List.of(), 0);
        }
    }

    /** 실행 결과. exitCode 는 끊었으면 -1. */
    public record Result(List<Event> events, int exitCode, boolean timedOut, boolean truncated, boolean cancelled, long elapsedMs) {
        public boolean failed() {
            if (exitCode != 0 || timedOut || truncated) {
                return true;
            }
            for (Event e : events) {
                if (e.kind() == Kind.ERROR) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 실행 하나. 다른 스레드에서 stop() 으로 끊을 수 있다. */
    public static final class Run {
        private volatile Process process;
        /** 사용자가 「멈추기」 를 눌렀으면 true. */
        private volatile boolean stopped;

        /** 사용자가 끊는다. 실행 전이면 실행하지 않는다. */
        public void stop() {
            stopped = true;
            kill();
        }

        private void kill() {
            Process p = process;
            if (p != null) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        }

        /**
         * node 로 mode 를 돌린다. tsLib 은 RUN_TS 일 때 typescript/lib 폴더(없으면 null).
         * Node 를 못 띄우거나 임시 파일을 못 쓰면 IOException.
         */
        public Result execute(Path node, Mode mode, String code, String data, Path tsLib) throws IOException {
            long start = System.nanoTime();
            List<Event> events = new ArrayList<>();
            boolean[] truncated = {false};
            Path dir = Files.createTempDirectory(TEMP_PREFIX);
            try {
                writeHarness(dir.resolve(HARNESS_FILE));
                Files.writeString(dir.resolve(CODE_FILE), code, StandardCharsets.UTF_8);
                Files.writeString(dir.resolve(DATA_FILE), data, StandardCharsets.UTF_8);
                ProcessBuilder builder = new ProcessBuilder(command(node, dir.resolve(HARNESS_FILE), mode, dir, tsLib))
                        .directory(dir.toFile()).redirectErrorStream(true);
                // 사용자 환경의 NODE_OPTIONS(디버거 · 메모리 옵션 등)가 작업대 실행에 섞이지 않게 뺀다.
                builder.environment().remove("NODE_OPTIONS");
                if (stopped) {
                    return new Result(List.of(), -1, false, false, true, 0);
                }
                Process p = builder.start();
                process = p;
                if (stopped) {
                    kill();
                }
                p.getOutputStream().close();
                Thread reader = new Thread(() -> read(p.getInputStream(), events, truncated, this::kill), "Moa JS 작업대 출력");
                reader.setDaemon(true);
                reader.start();
                boolean finished;
                try {
                    finished = p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    finished = false;
                }
                if (!finished) {
                    kill();
                }
                try {
                    reader.join(DRAIN_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                boolean ended = finished && !stopped && !truncated[0];
                List<Event> copy;
                synchronized (events) {
                    copy = List.copyOf(events);
                }
                return new Result(copy, ended ? p.exitValue() : -1, !finished && !stopped && !truncated[0], truncated[0], stopped,
                        (System.nanoTime() - start) / 1_000_000);
            } finally {
                deleteQuietly(dir);
            }
        }
    }

    private JsBench() {
    }

    static List<String> command(Path node, Path harness, Mode mode, Path dir, Path tsLib) {
        List<String> command = new ArrayList<>(List.of(node.toString(), harness.toString(), mode.arg, dir.toString(),
                String.valueOf(OUTPUT_LIMIT)));
        if (tsLib != null) {
            command.add(tsLib.toString());
        }
        return command;
    }

    private static void writeHarness(Path target) throws IOException {
        try (InputStream in = JsBench.class.getResourceAsStream(HARNESS)) {
            if (in == null) {
                throw new IOException("jar 안에 " + HARNESS + " 가 없습니다.");
            }
            Files.copy(in, target);
        }
    }

    private static void read(InputStream in, List<Event> events, boolean[] truncated, Runnable kill) {
        long total = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                total += line.length() + 1;
                if (total > OUTPUT_LIMIT) {
                    truncated[0] = true;
                    kill.run();
                    return;
                }
                Event e = parse(line);
                if (e.kind() == Kind.TRUNCATED) {
                    truncated[0] = true;
                    continue;
                }
                synchronized (events) {
                    events.add(e);
                }
            }
        } catch (IOException ignored) {
            // 끊은 프로세스의 출력은 중간에 닫힌다. 그때까지 읽은 사건만 보인다.
        }
    }

    /** 실행기 한 줄을 사건으로. MARK 가 없거나 JSON 이 깨졌으면 TEXT. */
    static Event parse(String line) {
        if (!line.startsWith(MARK)) {
            return Event.text(Kind.TEXT, line);
        }
        try {
            JsonObject o = JsonParser.parseString(line.substring(MARK.length())).getAsJsonObject();
            Kind kind = Kind.valueOf(o.get("kind").getAsString().toUpperCase(java.util.Locale.ROOT));
            if (kind != Kind.TABLE) {
                return Event.text(kind, o.has("text") ? o.get("text").getAsString() : "");
            }
            List<String> columns = strings(o.getAsJsonArray("columns"));
            List<List<String>> rows = new ArrayList<>();
            for (JsonElement row : o.getAsJsonArray("rows")) {
                rows.add(strings(row.getAsJsonArray()));
            }
            return new Event(kind, "", columns, rows, o.has("more") ? o.get("more").getAsInt() : 0);
        } catch (RuntimeException e) {
            return Event.text(Kind.TEXT, line.substring(MARK.length()));
        }
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>(array.size());
        for (JsonElement e : array) {
            out.add(e.isJsonNull() ? "" : e.getAsString());
        }
        return out;
    }

    /** 오류 글이 브라우저 · Vue 화면에만 있는 이름을 찾지 못한 것이면 그 이름, 아니면 null. */
    static String browserOnlyName(String errorText) {
        for (String name : BROWSER_NAMES) {
            if (errorText.contains(name + NOT_DEFINED)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 입력 데이터의 칸 이름(고르기로 만들기용). JSON 객체 배열이면 객체들의 키를 나온 순서대로, JSON 객체 하나면 그 키,
     * 엑셀에서 복사한 표(탭)면 첫 줄. 그 밖에는 빈 목록.
     */
    static List<String> columns(String data) {
        String text = data.strip();
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(text);
            if (root.isJsonArray()) {
                for (JsonElement row : root.getAsJsonArray()) {
                    if (row.isJsonObject()) {
                        addKeys(row.getAsJsonObject(), out);
                    }
                }
            } else if (root.isJsonObject()) {
                addKeys(root.getAsJsonObject(), out);
            }
            return out;
        } catch (JsonParseException | IllegalStateException e) {
            if (!text.contains("\t")) {
                return out;
            }
            String first = text.lines().findFirst().orElse("");
            String[] cells = first.split("\t", -1);
            for (int i = 0; i < cells.length; i++) {
                String c = cells[i].strip();
                out.add(c.isEmpty() ? "COL" + (i + 1) : c);
            }
            return out;
        }
    }

    private static void addKeys(JsonObject o, List<String> out) {
        for (String k : o.keySet()) {
            if (!out.contains(k)) {
                out.add(k);
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // 임시 폴더 지우기 실패는 결과에 영향이 없다. OS 가 나중에 치운다.
        }
    }
}
