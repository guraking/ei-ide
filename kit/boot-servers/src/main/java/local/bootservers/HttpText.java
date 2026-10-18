package local.bootservers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 요청 창의 글 처리(IntelliJ 클래스 없음): 헤더 글 읽기, 기본 주소 만들기, 스프링 설정 값 읽기, JSON 줄맞춤.
 */
final class HttpText {

    /** 요청 창이 고를 수 있는 HTTP 방식. 첫 값이 기본. */
    static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};
    static final String DEFAULT_HOST = "localhost";
    static final int DEFAULT_PORT = 8080;
    static final String PORT_KEY = "server.port";
    /** 스프링 부트 2 이상 · 1.x 의 컨텍스트 경로 키. 앞 키가 먼저. */
    static final String[] CONTEXT_PATH_KEYS = {"server.servlet.context-path", "server.context-path"};

    private static final String METHOD_SEPARATOR = "|";
    private static final String HEADER_SEPARATOR = ":";
    private static final String INDENT = "  ";
    private static final String YAML_DOCUMENT = "---";
    private static final String YAML_LIST_ITEM = "- ";
    // ${PORT:8080} 꼴은 기본값만 쓴다. 기본값 없는 ${PORT} 는 알 수 없는 값.
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^:}]*(?::([^}]*))?}");

    private HttpText() {
    }

    /** 매핑 목록의 방식 글(「GET」 · 「GET|POST」 · 「모두」)에서 요청 창의 방식 하나. 고를 수 없으면 GET. */
    static String firstMethod(String mappingMethod) {
        if (mappingMethod != null) {
            for (String part : mappingMethod.split(Pattern.quote(METHOD_SEPARATOR))) {
                for (String m : METHODS) {
                    if (m.equals(part.strip())) {
                        return m;
                    }
                }
            }
        }
        return METHODS[0];
    }

    /** 본문을 보내는 방식인가(기본 Content-Type 을 넣을지 정할 때). */
    static boolean hasBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    /**
     * 한 줄에 「이름: 값」 하나인 헤더 글을 읽는다. 빈 줄은 건너뛴다.
     * 「:」 가 없거나 이름이 비었거나 이름에 공백이 있으면 몇째 줄인지 담은 IllegalArgumentException.
     */
    static List<Map.Entry<String, String>> parseHeaders(String text) {
        List<Map.Entry<String, String>> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(HEADER_SEPARATOR);
            String name = colon < 0 ? "" : line.substring(0, colon).strip();
            if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("헤더 " + (i + 1) + "째 줄은 「이름: 값」 꼴이어야 합니다: " + line);
            }
            result.add(Map.entry(name, line.substring(colon + 1).strip()));
        }
        return result;
    }

    /** 인증 칸의 방식. 첫 값은 인증 헤더를 넣지 않음. */
    static final String AUTH_NONE = "없음";
    static final String AUTH_BEARER = "Bearer";
    static final String AUTH_BASIC = "Basic";
    static final String[] AUTH_SCHEMES = {AUTH_NONE, AUTH_BEARER, AUTH_BASIC};
    static final String AUTHORIZATION = "Authorization";

    /**
     * 인증 칸 값으로 만든 Authorization 헤더 값. Bearer 는 토큰 그대로, Basic 은 「아이디:비밀번호」 를 UTF-8 Base64 로.
     * 없음 · 빈 토큰이면 null. Basic 에 「:」 가 없으면 IllegalArgumentException.
     */
    static String authorization(String scheme, String token) {
        String t = token == null ? "" : token.strip();
        if (t.isEmpty() || AUTH_NONE.equals(scheme)) {
            return null;
        }
        if (AUTH_BASIC.equals(scheme)) {
            if (!t.contains(HEADER_SEPARATOR)) {
                throw new IllegalArgumentException("Basic 인증은 「아이디:비밀번호」 꼴로 적어야 합니다.");
            }
            return AUTH_BASIC + " " + java.util.Base64.getEncoder().encodeToString(t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return AUTH_BEARER + " " + t;
    }

    /** 헤더 목록에 그 이름(대소문자 무시)이 있는가. 헤더 칸에 직접 쓴 값이 인증 칸보다 앞선다. */
    static boolean hasHeader(List<Map.Entry<String, String>> headers, String name) {
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** http://localhost:포트 + 컨텍스트 경로(앞 / 붙임 · 끝 / 뗌, 「/」 하나면 없음). */
    static String baseUrl(int port, String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + DEFAULT_HOST + ":" + port + path;
    }

    /** 기본 주소 + 매핑 경로. 경로 앞 / 를 하나로 맞춘다. 경로 변수 {id} 는 그대로 둔다. */
    static String fullUrl(String base, String path) {
        String p = path == null ? "" : path.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return base + "/" + p;
    }

    /** 숫자로 읽히는 포트면 그 값, 아니면 null. */
    static Integer parsePort(String value) {
        if (value == null || !value.matches("\\d{1,5}")) {
            return null;
        }
        int port = Integer.parseInt(value);
        return port > 0 && port <= 65535 ? port : null;
    }

    /**
     * 스프링 설정 파일(application.properties · .yml · .yaml) 글에서 점으로 이은 키의 값. 없으면 null.
     * YAML 은 들여쓰기로 키를 잇고 첫 문서(--- 앞, 기본 프로필) 만 본다. ${X:기본값} 은 기본값.
     * shortcut: 따옴표 안의 「 #」, properties 줄 이음(\), YAML 여러 줄 값은 다루지 않는다. 그런 설정이 실제로 쓰이면 정식 파서로 바꾼다.
     */
    static String configValue(String fileName, String text, String key) {
        if (text == null) {
            return null;
        }
        String raw = fileName.endsWith(".properties") ? propertiesValue(text, key) : yamlValue(text, key);
        return raw == null ? null : resolvePlaceholder(unquote(raw));
    }

    private static String propertiesValue(String text, String key) {
        String found = null;
        for (String line : text.split("\\R")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) {
                continue;
            }
            int end = 0;
            while (end < s.length() && "=: \t".indexOf(s.charAt(end)) < 0) {
                end++;
            }
            if (!s.substring(0, end).equals(key)) {
                continue;
            }
            String rest = s.substring(end).strip();
            if (rest.startsWith("=") || rest.startsWith(":")) {
                rest = rest.substring(1).strip();
            }
            // 같은 키가 여러 번이면 뒤 값이 이긴다(java.util.Properties 와 같음).
            found = rest;
        }
        return found;
    }

    private static String yamlValue(String text, String key) {
        List<Integer> indents = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String content = stripYamlComment(line);
            String s = content.strip();
            if (s.equals(YAML_DOCUMENT)) {
                if (!names.isEmpty() || !indents.isEmpty()) {
                    return null;
                }
                continue;
            }
            if (s.isEmpty() || s.startsWith(YAML_LIST_ITEM)) {
                continue;
            }
            int colon = s.indexOf(": ");
            if (colon < 0 && s.endsWith(":")) {
                colon = s.length() - 1;
            }
            if (colon <= 0) {
                continue;
            }
            int indent = content.length() - content.stripLeading().length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                names.remove(names.size() - 1);
            }
            indents.add(indent);
            names.add(unquote(s.substring(0, colon).strip()));
            String value = s.substring(colon + 1).strip();
            if (!value.isEmpty() && String.join(".", names).equals(key)) {
                return value;
            }
        }
        return null;
    }

    private static String stripYamlComment(String line) {
        if (line.stripLeading().startsWith("#")) {
            return "";
        }
        int hash = line.indexOf(" #");
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String resolvePlaceholder(String value) {
        Matcher m = PLACEHOLDER.matcher(value);
        if (!m.matches()) {
            return value;
        }
        return m.group(1) == null ? null : m.group(1).strip();
    }

    /**
     * JSON 을 두 칸 들여쓰기로 줄맞춤한다. 다시 해석하지 않고 글자만 옮기므로 키 순서 · 숫자 표기가 그대로다.
     * 괄호 짝이 안 맞거나 문자열이 닫히지 않으면 받은 글을 그대로 돌려준다.
     */
    static String prettyJson(String json) {
        if (json == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(json.length() * 2);
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    out.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    out.append(c);
                    int next = nextNonSpace(json, i + 1);
                    char close = c == '{' ? '}' : ']';
                    if (next < json.length() && json.charAt(next) == close) {
                        out.append(close);
                        i = next;
                    } else {
                        depth++;
                        newLine(out, depth);
                    }
                }
                case '}', ']' -> {
                    depth--;
                    if (depth < 0) {
                        return json;
                    }
                    newLine(out, depth);
                    out.append(c);
                }
                case ',' -> {
                    out.append(c);
                    newLine(out, depth);
                }
                case ':' -> out.append(": ");
                default -> {
                    if (!Character.isWhitespace(c)) {
                        out.append(c);
                    }
                }
            }
        }
        return depth == 0 && !inString ? out.toString() : json;
    }

    private static int nextNonSpace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void newLine(StringBuilder out, int depth) {
        out.append('\n');
        for (int d = 0; d < depth; d++) {
            out.append(INDENT);
        }
    }
}
