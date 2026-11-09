package local.bootservers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import local.bootservers.OpenApiSpec.Schema;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 요청 화면의 글 처리(IntelliJ 클래스 없음): 시험 값 채우기, 주소 만들기, 인증 헤더, JSON 줄맞춤.
 */
final class ApiText {

    static final String SAMPLE_TEXT = "테스트";
    static final String SAMPLE_EMAIL = "test@test.com";
    static final String SAMPLE_YES = "Y";
    static final int SAMPLE_NUMBER = 1;
    /** 이름이 이것으로 끝나면(대소문자 무시) 글 칸이라도 숫자 시험 값을 넣는다. */
    static final String[] NUMBER_NAME_SUFFIXES = {"id", "no", "seq", "cnt"};
    static final String EMAIL_NAME_SUFFIX = "email";
    static final String YN_NAME_SUFFIX = "yn";
    /** LocalDateTime 등 날짜 · 시각 형식에 붙이는 시각. */
    static final String MIDNIGHT = "T00:00:00";

    static final String AUTH_NONE = "NONE";
    static final String AUTH_BEARER = "BEARER";
    static final String AUTH_BASIC = "BASIC";
    static final String AUTH_COOKIE = "COOKIE";
    static final String AUTHORIZATION = "Authorization";
    static final String COOKIE = "Cookie";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    private static final String FORMAT_DATE = "date";
    private static final String FORMAT_DATE_TIME = "date-time";
    /** LocalTime(시각만). 시험 값은 MIDNIGHT 의 시각 부분. */
    static final String FORMAT_TIME = "time";
    /** YearMonth. 시험 값은 오늘의 yyyy-MM. */
    static final String FORMAT_YEAR_MONTH = "year-month";
    /** Year. 시험 값은 오늘의 yyyy. */
    static final String FORMAT_YEAR = "year";
    /** Instant · OffsetDateTime · ZonedDateTime(시간대 필요). 시험 값은 오늘 자정 UTC(「Z」). */
    static final String FORMAT_DATE_TIME_OFFSET = "date-time-offset";
    /**
     * 열거형은 format 에 「enum:상수1,상수2」 로 상수 이름을 싣는다. 시험 값은 첫 상수.
     * shortcut: Schema 에 enum 칸이 없어 format 에 싣는다, Schema 를 OpenAPI 글로 내보내게 되면 enum 칸을 따로 둔다.
     */
    static final String FORMAT_ENUM_PREFIX = "enum:";
    static final String ENUM_SEPARATOR = ",";
    private static final String UTC_MARK = "Z";
    private static final int YEAR_MONTH_LENGTH = "yyyy-MM".length();
    private static final int YEAR_LENGTH = "yyyy".length();
    /** 경로 변수 이름과 정규식을 가르는 글자({이름:정규식}). */
    private static final char VARIABLE_REGEX_MARK = ':';
    /** 스프링 설정 자리표시({@code ${키}}) 앞 글자. 이 뒤의 { } 는 경로 변수가 아니다. */
    private static final char PLACEHOLDER_MARK = '$';
    /** 응답 헤더 · HTML meta 의 charset 값. */
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w.:-]+)");
    /** HTML 앞부분에서 meta charset 을 찾는 바이트 수(HTML 표준은 앞 1024 바이트 안에 두라고 한다). */
    private static final int META_SCAN_BYTES = 1024;
    private static final Pattern META_CHARSET = Pattern.compile("(?i)<meta[^>]*charset\\s*=\\s*[\"']?([\\w.:-]+)");
    /** 헤더 · meta 에 charset 이 없고 UTF-8 로도 읽히지 않을 때 쓰는 글자 모음(한글 윈도우 · EUC-KR 의 상위 집합). */
    static final Charset FALLBACK_CHARSET = Charset.forName("MS949");

    private ApiText() {
    }

    /**
     * 요청 값 하나의 시험 값(글). defaultValue 가 있으면 그 값.
     * 숫자 1 · 참거짓 true · 날짜 오늘 · 이름이 id/no/seq/cnt 로 끝나면 1 · email 이면 test@test.com · Yn 으로 끝나면 Y · 그 밖의 글은 「테스트」.
     * 배열은 항목 하나의 값. today 는 yyyy-MM-dd.
     */
    static String sample(String name, Schema schema, String defaultValue, String today) {
        if (defaultValue != null) {
            return defaultValue;
        }
        JsonElement value = sampleJson(name, schema, Map.of(), today);
        while (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
            value = value.getAsJsonArray().get(0);
        }
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** JSON 본문의 시험 값. 참조는 components 의 필드로 펼치고, 자기를 다시 참조하면 빈 객체로 끊는다. */
    static JsonElement sampleJson(String name, Schema schema, Map<String, Map<String, Schema>> components, String today) {
        return sampleJson(name, schema, components, today, new HashSet<>());
    }

    private static JsonElement sampleJson(String name, Schema schema, Map<String, Map<String, Schema>> components,
                                          String today, Set<String> visiting) {
        if (schema == null) {
            return new JsonPrimitive(SAMPLE_TEXT);
        }
        if (schema.ref() != null) {
            JsonObject object = new JsonObject();
            Map<String, Schema> fields = components.get(schema.ref());
            if (fields == null || !visiting.add(schema.ref())) {
                return object;
            }
            for (Map.Entry<String, Schema> field : fields.entrySet()) {
                object.add(field.getKey(), sampleJson(field.getKey(), field.getValue(), components, today, visiting));
            }
            visiting.remove(schema.ref());
            return object;
        }
        String type = schema.type() == null ? OpenApiSpec.TYPE_STRING : schema.type();
        switch (type) {
            case OpenApiSpec.TYPE_ARRAY -> {
                JsonArray array = new JsonArray();
                array.add(sampleJson(name, schema.items(), components, today, visiting));
                return array;
            }
            case OpenApiSpec.TYPE_OBJECT -> {
                return new JsonObject();
            }
            case OpenApiSpec.TYPE_INTEGER, OpenApiSpec.TYPE_NUMBER -> {
                return new JsonPrimitive(SAMPLE_NUMBER);
            }
            case OpenApiSpec.TYPE_BOOLEAN -> {
                return new JsonPrimitive(true);
            }
            default -> {
                String format = schema.format() == null ? "" : schema.format();
                if (format.startsWith(FORMAT_ENUM_PREFIX) && format.length() > FORMAT_ENUM_PREFIX.length()) {
                    return new JsonPrimitive(format.substring(FORMAT_ENUM_PREFIX.length()).split(ENUM_SEPARATOR)[0]);
                }
                return new JsonPrimitive(switch (format) {
                    case FORMAT_DATE -> today;
                    case FORMAT_DATE_TIME -> today + MIDNIGHT;
                    case FORMAT_DATE_TIME_OFFSET -> today + MIDNIGHT + UTC_MARK;
                    case FORMAT_TIME -> MIDNIGHT.substring(1);
                    case FORMAT_YEAR_MONTH -> today.substring(0, YEAR_MONTH_LENGTH);
                    case FORMAT_YEAR -> today.substring(0, YEAR_LENGTH);
                    default -> sampleText(name);
                });
            }
        }
    }

    private static String sampleText(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(EMAIL_NAME_SUFFIX)) {
            return SAMPLE_EMAIL;
        }
        if (lower.endsWith(YN_NAME_SUFFIX)) {
            return SAMPLE_YES;
        }
        for (String suffix : NUMBER_NAME_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return String.valueOf(SAMPLE_NUMBER);
            }
        }
        return SAMPLE_TEXT;
    }

    /**
     * 경로 틀의 {변수} · {변수:정규식} 을 values 의 값(주소용으로 인코딩)으로 바꾼다. 값이 없는 변수는 시험 숫자.
     * 정규식 안의 { } 는 짝을 세어 넘기고, {@code ${키}} 자리표시와 짝이 없는 { 는 그대로 둔다.
     */
    static String fillPath(String path, Map<String, String> values) {
        String p = path == null ? "" : path;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            int end = c == '{' ? closingBrace(p, i) : -1;
            if (end < 0) {
                out.append(c);
                i++;
                continue;
            }
            if (i > 0 && p.charAt(i - 1) == PLACEHOLDER_MARK) {
                out.append(p, i, end + 1);
            } else {
                String inner = p.substring(i + 1, end);
                int mark = inner.indexOf(VARIABLE_REGEX_MARK);
                String name = (mark < 0 ? inner : inner.substring(0, mark)).strip();
                String value = values.getOrDefault(name, String.valueOf(SAMPLE_NUMBER));
                out.append(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** open 자리의 { 와 짝인 } 자리. 안쪽 { } 짝을 센다. 짝이 없으면 -1. */
    private static int closingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 응답 본문 바이트를 글로. Content-Type 의 charset → HTML meta charset → 엄격한 UTF-8 → MS949 순.
     * cut 이면 끝이 잘린 것이므로 UTF-8 판단에서 마지막 덜 받은 글자는 틀린 것으로 보지 않는다.
     */
    static String decode(byte[] bytes, String contentType, boolean cut) {
        Charset declared = charsetOf(CHARSET, contentType);
        if (declared == null) {
            int scan = Math.min(bytes.length, META_SCAN_BYTES);
            declared = charsetOf(META_CHARSET, new String(bytes, 0, scan, StandardCharsets.ISO_8859_1));
        }
        if (declared != null) {
            return new String(bytes, declared);
        }
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        CoderResult result = utf8.decode(ByteBuffer.wrap(bytes), out, !cut);
        if (!result.isError() && (cut || utf8.flush(out).isUnderflow())) {
            return out.flip().toString();
        }
        return new String(bytes, FALLBACK_CHARSET);
    }

    /** text 에서 pattern 의 charset 이름을 찾아 이 JVM 이 아는 Charset 으로. 없거나 모르는 이름이면 null. */
    private static Charset charsetOf(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return Charset.forName(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 기본 주소 + 경로. 경로 앞 / 를 하나로 맞춘다. */
    static String join(String base, String path) {
        String p = path == null ? "" : path.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return base + "/" + p;
    }

    /**
     * 주소 앞 흐리게 보일 부분(scheme://host[:port])의 끝 자리: 「://」 뒤 처음 나오는 「/」 · 「?」 · 「#」 자리(RFC 3986 authority 끝).
     * 「://」 가 없으면 0, 셋 다 없으면 주소 길이.
     */
    static int hostEnd(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return 0;
        }
        for (int i = scheme + 3; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                return i;
            }
        }
        return url.length();
    }

    /** 쿼리(「?」 부터)의 시작 자리. 없으면 주소 길이. 경로 앞의 「?」 는 쿼리로 치지 않는다. */
    static int queryStart(String url) {
        int q = url.indexOf('?', hostEnd(url));
        return q < 0 ? url.length() : q;
    }

    /** 주소 뒤에 쿼리(이름 · 값 쌍)를 인코딩해 붙인다. 주소에 이미 「?」 가 있으면 「&」 로 잇는다. 빈 이름은 건너뛴다. */
    static String withQuery(String url, List<Map.Entry<String, String>> query) {
        StringBuilder out = new StringBuilder(url);
        char separator = url.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> q : query) {
            if (q.getKey() == null || q.getKey().isBlank()) {
                continue;
            }
            out.append(separator)
                    .append(URLEncoder.encode(q.getKey().strip(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(q.getValue() == null ? "" : q.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return out.toString();
    }

    /**
     * 보낼 HTTP 요청 글: 요청줄(방식 · 경로+쿼리 · HTTP/1.1) · Host · 헤더, 본문이 있으면 빈 줄 뒤에 본문.
     * 주소의 서버 부분은 Host 줄로 가고, scheme 이 없으면 Host 줄을 뺀다. 경로가 없으면 「/」.
     */
    static String requestText(String method, String url, List<Map.Entry<String, String>> query,
                              List<Map.Entry<String, String>> headers, String body) {
        String u = url == null ? "" : url.strip();
        int scheme = u.indexOf("://");
        int hostEnd = hostEnd(u);
        String host = scheme < 0 ? "" : u.substring(scheme + 3, hostEnd);
        String path = u.substring(hostEnd);
        StringBuilder out = new StringBuilder();
        // 경로 없이 쿼리만 있으면(https://h?x=1) 요청줄은 「/?x=1」 이다.
        out.append(method).append(' ').append(withQuery(path.startsWith("/") ? path : "/" + path, query)).append(" HTTP/1.1\n");
        if (!host.isEmpty()) {
            out.append("Host: ").append(host).append('\n');
        }
        for (Map.Entry<String, String> header : headers) {
            out.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
        }
        if (body != null && !body.isBlank()) {
            out.append('\n').append(body);
        }
        return out.toString();
    }

    /**
     * 인증 방식과 값으로 붙일 헤더(이름, 값). 없음 · 빈 값이면 null.
     * Bearer 는 「Authorization: Bearer 토큰」, Basic 은 「아이디:비밀번호」 UTF-8 Base64, 쿠키는 「Cookie: 값」 그대로.
     */
    static Map.Entry<String, String> authHeader(String type, String user, String secret) {
        String s = secret == null ? "" : secret.strip();
        if (AUTH_BASIC.equals(type)) {
            String u = user == null ? "" : user.strip();
            if (u.isEmpty() && s.isEmpty()) {
                return null;
            }
            String token = Base64.getEncoder().encodeToString((u + ":" + s).getBytes(StandardCharsets.UTF_8));
            return Map.entry(AUTHORIZATION, "Basic " + token);
        }
        if (s.isEmpty()) {
            return null;
        }
        if (AUTH_BEARER.equals(type)) {
            return Map.entry(AUTHORIZATION, s.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length()) ? s : "Bearer " + s);
        }
        if (AUTH_COOKIE.equals(type)) {
            return Map.entry(COOKIE, s);
        }
        return null;
    }

    /** { 또는 [ 로 시작하는 JSON 이면 줄맞춤, 아니거나 깨진 JSON 이면 그대로. */
    static String prettyJson(String text) {
        String t = text == null ? "" : text.strip();
        if (!t.startsWith("{") && !t.startsWith("[")) {
            return text;
        }
        try {
            return GSON.toJson(JsonParser.parseString(t));
        } catch (JsonParseException e) {
            return text;
        }
    }

    static String toJson(JsonElement element) {
        return GSON.toJson(element);
    }
}
