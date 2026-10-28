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
    /** {이름} · {이름:정규식}. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

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
                if (FORMAT_DATE.equals(schema.format())) {
                    return new JsonPrimitive(today);
                }
                if (FORMAT_DATE_TIME.equals(schema.format())) {
                    return new JsonPrimitive(today + MIDNIGHT);
                }
                return new JsonPrimitive(sampleText(name));
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

    /** 경로 틀의 {변수} 를 values 의 값(주소용으로 인코딩)으로 바꾼다. 값이 없는 변수는 시험 숫자. */
    static String fillPath(String path, Map<String, String> values) {
        Matcher m = PATH_VARIABLE.matcher(path == null ? "" : path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.getOrDefault(m.group(1), String.valueOf(SAMPLE_NUMBER));
            m.appendReplacement(out, Matcher.quoteReplacement(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 기본 주소 + 경로. 경로 앞 / 를 하나로 맞춘다. */
    static String join(String base, String path) {
        String p = path == null ? "" : path.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return base + "/" + p;
    }

    /** 주소 앞 흐리게 보일 부분(scheme://host[:port])의 끝 자리. 「://」 가 없으면 0, 경로가 없으면 주소 길이. */
    static int hostEnd(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return 0;
        }
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url.length() : slash;
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
