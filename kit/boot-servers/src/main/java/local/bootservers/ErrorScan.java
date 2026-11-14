package local.bootservers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버 출력에서 오류 묶음을 찾아 같은 오류끼리 모은다. IntelliJ 에 기대지 않는다. 한 스레드에서만 부른다.
 * 묶음은 ERROR 줄 · 레벨 없는 예외 머리 줄 · 예외가 든 WARN 줄 · 스프링 부트 「APPLICATION FAILED TO START」 에서 시작해
 * 다음 레벨 줄이 오거나 flush 될 때 끝난다. 끝난 묶음은 {@link ErrorReport} 로 읽고 같은 열쇠(예외 · 숫자 뺀 글 · 우리 코드 줄)끼리 센다.
 * 메모리 한도: 앞 줄 CONTEXT_LINES 개, 묶음 BLOCK_LINES 줄 · BLOCK_CHARS 자, 줄 MAX_LINE_CHARS 자,
 * 오류 종류 MAX_GROUPS 개(넘으면 오래된 것부터 지움, 종류마다 마지막 것만 둔다).
 */
final class ErrorScan {

    static final int CONTEXT_LINES = 300;
    static final int BLOCK_LINES = 300;
    static final int MAX_LINE_CHARS = 4000;
    static final int BLOCK_CHARS = 50_000;
    static final int MAX_GROUPS = 200;

    private static final Pattern FAILED_TO_START = Pattern.compile("APPLICATION FAILED TO START");
    /** 색 바꾸기 등 ANSI 제어 글(콘솔이 풀지 않고 넘긴 경우). */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
    /** WARN 줄이 오류로 칠 만큼 예외를 담았는가(스프링 「Resolved [...Exception: ...]」 등). */
    private static final Pattern WARN_EXCEPTION = Pattern.compile("[\\w$]+(?:Exception|Error)\\b");

    /** 화면에 넘기는 오류 종류 한 줄(바뀌지 않는 사본). first · last 는 처음 · 마지막에 난 것, count 는 난 횟수. */
    record Item(String key, String firstTime, ErrorReport last, int count) {}

    private static final class Group {
        final String firstTime;
        ErrorReport last;
        int count;

        Group(ErrorReport report) {
            this.firstTime = report.time();
            this.last = report;
        }
    }

    private final ArrayDeque<String> context = new ArrayDeque<>();
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    private final StringBuilder partial = new StringBuilder();
    private List<String> block;
    private List<String> blockContext;
    private boolean failureReport;
    private boolean blockHasLevel;
    private boolean sawFrame;
    private int blockChars;
    private long changes;

    /** 지금까지 바뀐 횟수. 화면이 다시 그릴지 정할 때 쓴다. */
    long changes() {
        return changes;
    }

    /** 오래된 것부터의 오류 종류 사본. 다른 스레드에 넘겨도 된다. */
    List<Item> items() {
        List<Item> out = new ArrayList<>(groups.size());
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            Group g = e.getValue();
            out.add(new Item(e.getKey(), g.firstTime, g.last, g.count));
        }
        return out;
    }

    /** 모은 오류를 비운다(읽던 묶음 · 앞 줄은 그대로). */
    void clear() {
        groups.clear();
        changes++;
    }

    /** 출력 조각을 받는다. 줄바꿈 없는 끝은 다음 조각과 이어 붙인다. */
    void feed(String chunk) {
        int start = 0;
        for (int i = 0; i < chunk.length(); i++) {
            if (chunk.charAt(i) == '\n') {
                partial.append(chunk, start, i);
                line(partial.toString());
                partial.setLength(0);
                start = i + 1;
            }
        }
        if (partial.length() < MAX_LINE_CHARS) {
            partial.append(chunk, start, Math.min(chunk.length(), start + MAX_LINE_CHARS - partial.length()));
        }
    }

    /** 열린 묶음과 덜 쓴 끝 줄을 마무리한다. 출력이 한동안 멈췄거나 프로세스가 끝났을 때 부른다. */
    void flush() {
        if (partial.length() > 0) {
            line(partial.toString());
            partial.setLength(0);
        }
        close();
    }

    private void line(String raw) {
        String line = ANSI.matcher(raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw).replaceAll("");
        if (line.length() > MAX_LINE_CHARS) {
            line = line.substring(0, MAX_LINE_CHARS);
        }
        LogFilter.Level level = LogFilter.levelOf(line);
        boolean levelLine = level != null && !LogFields.isTraceLine(line);
        boolean trace = LogFields.isTraceLine(line);
        if (block != null && levelLine && !(failureReport && !LogFilter.LEADING_TIMESTAMP.matcher(line).lookingAt())) {
            close();
        }
        // printStackTrace 처럼 레벨 없이 시작한 묶음은 스택 줄이 나온 뒤의 보통 줄에서 끝난다.
        if (block != null && !blockHasLevel && sawFrame && !trace && !line.isBlank()) {
            close();
        }
        if (block == null && starts(line, level, levelLine)) {
            block = new ArrayList<>();
            blockContext = new ArrayList<>(context);
            failureReport = FAILED_TO_START.matcher(line).find();
            blockHasLevel = levelLine;
            blockChars = 0;
            sawFrame = false;
        }
        else if (block != null && !failureReport && FAILED_TO_START.matcher(line).find()) {
            failureReport = true;
        }
        if (block != null && block.size() < BLOCK_LINES && blockChars + line.length() <= BLOCK_CHARS) {
            block.add(line);
            blockChars += line.length();
        }
        if (block != null && line.startsWith("\tat ")) {
            sawFrame = true;
        }
        context.addLast(line);
        if (context.size() > CONTEXT_LINES) {
            context.removeFirst();
        }
    }

    private static boolean starts(String line, LogFilter.Level level, boolean levelLine) {
        if (FAILED_TO_START.matcher(line).find()) {
            return true;
        }
        if (levelLine) {
            return level == LogFilter.Level.ERROR || level == LogFilter.Level.WARN && WARN_EXCEPTION.matcher(line).find();
        }
        return level == null && LogFields.startsWithException(line);
    }

    private void close() {
        if (block == null) {
            return;
        }
        List<String> lines = block;
        List<String> before = blockContext;
        block = null;
        blockContext = null;
        failureReport = false;
        ErrorReport report = ErrorReport.of(lines, before);
        if (report == null) {
            return;
        }
        Group group = groups.remove(report.key());
        if (group == null) {
            group = new Group(report);
        }
        group.last = report;
        group.count++;
        groups.put(report.key(), group);
        while (groups.size() > MAX_GROUPS) {
            Iterator<Map.Entry<String, Group>> it = groups.entrySet().iterator();
            it.next();
            it.remove();
        }
        changes++;
    }

    /** 숫자 · 16진 · 따옴표 안 값을 지워 같은 오류가 값만 달라도 한 열쇠가 되게 한다. */
    static String normalize(String text) {
        Matcher m = VARIABLE.matcher(text);
        return m.replaceAll("#");
    }

    private static final Pattern VARIABLE = Pattern.compile("'[^']*'|\"[^\"]*\"|0x[0-9a-fA-F]+|@[0-9a-fA-F]{4,}|\\d+");
}
