package local.bootservers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 개발 서버 주소 글 처리(IntelliJ 클래스 없음): 기본 주소 만들기, 스프링 설정 값 읽기.
 */
final class HttpText {

    /** OpenAPI 사양에 넣는 HTTP 방식. */
    static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};
    static final String DEFAULT_HOST = "localhost";
    static final int DEFAULT_PORT = 8080;
    static final String PORT_KEY = "server.port";
    /** 스프링 부트 2 이상 · 1.x 의 컨텍스트 경로 키. 앞 키가 먼저. */
    static final String[] CONTEXT_PATH_KEYS = {"server.servlet.context-path", "server.context-path"};

    private static final String YAML_DOCUMENT = "---";
    private static final String YAML_LIST_ITEM = "- ";
    // ${PORT:8080} 꼴은 기본값만 쓴다. 기본값 없는 ${PORT} 는 알 수 없는 값.
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^:}]*(?::([^}]*))?}");

    private HttpText() {
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
}
