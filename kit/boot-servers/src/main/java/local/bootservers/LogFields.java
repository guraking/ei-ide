package local.bootservers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그 한 줄의 머리(시각 · 레벨 · 스레드 · 로거)와 요청 ID 를 읽는다. IntelliJ 에 기대지 않는다.
 * 스프링 부트 2 · 3(「ERROR 1234 --- [app] [nio-8080-exec-1] c.e.Foo : 글」), logback 기본(「[main] ERROR c.e.Foo - 글」),
 * log4j(「[ERROR] 10:00:00 [main] c.e.Foo - 글」) 꼴을 맞춘다. 못 읽은 칸은 null.
 */
public record LogFields(String thread, String logger, String requestId) {

    public static final LogFields NONE = new LogFields(null, null, null);

    /** 머리와 글을 가르는 표시. 스프링 부트는 「 : 」, logback · log4j 는 「 - 」. */
    private static final Pattern SEPARATOR = Pattern.compile(" (?::|-) ");
    /** 앞뒤가 공백 · 줄 끝인 대괄호 칸. 로거 이름에 붙은 「o.a.c.c.C.[.[.[/].[dispatcherServlet]」 의 대괄호는 뺀다. */
    private static final Pattern BRACKET = Pattern.compile("(?<=^|\\s)\\[([^\\[\\]]+)](?=\\s|$)");
    /** 점이 하나 이상 든 로거 이름(「c.e.d.Foo」, 「o.s.web.servlet.DispatcherServlet」). 줄여진 로거의 「…」 은 스프링 부트 3 꼴. */
    private static final Pattern LOGGER = Pattern.compile("(?<![\\w$.])(?:\\.\\.\\.)?[A-Za-z_$][\\w$]*(?:\\.[\\w$]+)+(?![\\w$.])");
    /** MDC 에 흔히 넣는 요청 ID 이름과 값. */
    private static final Pattern REQUEST_ID_NAMED = Pattern.compile(
            "(?i)\\b(?:traceId|trace_id|trace-id|requestId|request_id|request-id|reqId|txId|tid|guid|x-request-id)\\s*[=:]\\s*\\[?([\\w-]{4,})");
    /** 머리 대괄호 안의 16 · 32 자리 16진수(마이크로미터 추적 ID). 「[app,6553d1d5c0a5a7e1,6553d1d5c0a5a7e1]」 도 맞춘다. */
    private static final Pattern REQUEST_ID_HEX = Pattern.compile("(?<![0-9a-f])([0-9a-f]{32}|[0-9a-f]{16})(?![0-9a-f])");
    /** 머리로 볼 최대 글자 수. 넘는 곳의 구분 표시는 글 안의 것으로 본다. */
    private static final int MAX_HEAD = 300;

    /** 들여쓴 줄 · 「Caused by:」 · 「Suppressed:」 · 예외 이름으로 시작하는 줄. 글 안에 「Exception」 이 든 로그 줄은 아니다. */
    static boolean isTraceLine(String line) {
        return !line.isEmpty() && (line.charAt(0) == '\t' || line.charAt(0) == ' ' || line.startsWith("Caused by:")
                || EXCEPTION_NAME.matcher(line).lookingAt());
    }

    /** 예외 이름(「java.lang.IllegalStateException」)으로 시작하는 줄. */
    static boolean startsWithException(String line) {
        return EXCEPTION_NAME.matcher(line).lookingAt();
    }

    private static final Pattern EXCEPTION_NAME = Pattern.compile("[\\w$]+(?:\\.[\\w$]+)+(?:Exception|Error|Throwable)\\b");

    public static LogFields of(String line) {
        if (line == null || line.isEmpty() || isTraceLine(line)) {
            return NONE;
        }
        Matcher stamp = LogFilter.LEADING_TIMESTAMP.matcher(line);
        int from = stamp.lookingAt() ? stamp.end() : 0;
        Matcher sep = SEPARATOR.matcher(line);
        int headEnd = sep.find(from) && sep.start() <= MAX_HEAD ? sep.start() : -1;
        String head = headEnd < 0 ? line.substring(from, Math.min(line.length(), MAX_HEAD)) : line.substring(from, headEnd);
        String thread = null;
        String hexId = null;
        Matcher b = BRACKET.matcher(head);
        while (b.find()) {
            String inside = b.group(1).trim();
            if (inside.isEmpty() || LogFilter.levelOf(inside) != null && inside.equals(inside.toUpperCase())) {
                continue;
            }
            Matcher hex = REQUEST_ID_HEX.matcher(inside);
            if (hex.find()) {
                hexId = hex.group(1);
                continue;
            }
            thread = inside;
        }
        String logger = null;
        if (headEnd >= 0) {
            Matcher m = LOGGER.matcher(head);
            while (m.find()) {
                logger = m.group();
            }
        }
        String requestId = hexId;
        Matcher named = REQUEST_ID_NAMED.matcher(line);
        if (named.find()) {
            requestId = named.group(1);
        }
        return new LogFields(thread, logger, requestId);
    }
}
