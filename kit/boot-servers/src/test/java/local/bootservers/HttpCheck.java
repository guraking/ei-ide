package local.bootservers;

import java.util.List;
import java.util.Map;

/** HttpText 자체 점검. 실패하면 예외로 끝난다. */
public final class HttpCheck {

    public static void main(String[] args) {
        methods();
        headers();
        auth();
        urls();
        config();
        json();
        System.out.println("HttpCheck 통과");
    }

    private static void methods() {
        eq("GET", HttpText.firstMethod("모두"), "모두는 GET");
        eq("POST", HttpText.firstMethod("POST"), "하나");
        eq("PUT", HttpText.firstMethod("HEAD|PUT|GET"), "모르는 방식 건너뜀");
        eq("GET", HttpText.firstMethod(null), "null");
        yes(HttpText.hasBody("PATCH") && !HttpText.hasBody("GET") && !HttpText.hasBody("DELETE"), "본문 방식");
    }

    private static void headers() {
        List<Map.Entry<String, String>> h = HttpText.parseHeaders("Content-Type: application/json\n\n  Authorization:Bearer a:b  \r\nX-Empty:");
        eq(3, h.size(), "개수 · 빈 줄");
        eq("Content-Type", h.get(0).getKey(), "이름");
        eq("Bearer a:b", h.get(1).getValue(), "값 안의 : · 공백 뗌");
        eq("", h.get(2).getValue(), "빈 값");
        eq(0, HttpText.parseHeaders(null).size(), "null");
        eq(0, HttpText.parseHeaders("  \n").size(), "빈 글");
        fails("A: 1\nno colon", "2째 줄", "콜론 없음");
        fails(": v", "1째 줄", "이름 없음");
        fails("Bad Name: v", "1째 줄", "이름에 공백");
    }

    private static void auth() {
        eq(null, HttpText.authorization(HttpText.AUTH_NONE, "abc"), "없음");
        eq(null, HttpText.authorization(HttpText.AUTH_BEARER, "  "), "빈 토큰");
        eq(null, HttpText.authorization(HttpText.AUTH_BEARER, null), "null 토큰");
        eq("Bearer abc.def", HttpText.authorization(HttpText.AUTH_BEARER, " abc.def "), "Bearer · 공백 뗌");
        // RFC 7617 예: Aladdin:open sesame
        eq("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", HttpText.authorization(HttpText.AUTH_BASIC, "Aladdin:open sesame"), "Basic");
        eq("Basic 7ZWcOmI=", HttpText.authorization(HttpText.AUTH_BASIC, "한:b"), "Basic UTF-8");
        try {
            HttpText.authorization(HttpText.AUTH_BASIC, "nocolon");
            throw new AssertionError("Basic 콜론 없음이 통과함");
        } catch (IllegalArgumentException expected) {
            // 기대한 실패
        }
        yes(HttpText.hasHeader(HttpText.parseHeaders("authorization: x"), HttpText.AUTHORIZATION), "대소문자 무시");
        yes(!HttpText.hasHeader(HttpText.parseHeaders("Content-Type: a"), HttpText.AUTHORIZATION), "없음");
    }

    private static void urls() {
        eq("http://localhost:8080", HttpText.baseUrl(8080, null), "경로 없음");
        eq("http://localhost:8080", HttpText.baseUrl(8080, "/"), "뿌리 경로");
        eq("http://localhost:9000/app", HttpText.baseUrl(9000, "app/"), "앞 / 붙임 · 끝 / 뗌");
        eq("http://localhost:9000/app/api/order/{id}", HttpText.fullUrl("http://localhost:9000/app", "/api/order/{id}"), "경로 변수 그대로");
        eq("http://localhost:8080/", HttpText.fullUrl("http://localhost:8080", "/"), "뿌리 매핑");
        eq("http://localhost:8080/a", HttpText.fullUrl("http://localhost:8080", "//a"), "겹친 /");
        eq(8081, HttpText.parsePort("8081"), "포트");
        eq(null, HttpText.parsePort("0"), "0 은 아님");
        eq(null, HttpText.parsePort("70000"), "범위 밖");
        eq(null, HttpText.parsePort("${PORT}"), "글자");
    }

    private static void config() {
        String props = "# 주석\nserver.port = 9090\nserver.servlet.context-path:/app\nserver.port=9091\n";
        eq("9091", HttpText.configValue("application.properties", props, "server.port"), "properties 뒤 값");
        eq("/app", HttpText.configValue("application.properties", props, "server.servlet.context-path"), "properties : 구분");
        eq(null, HttpText.configValue("application.properties", props, "server"), "앞부분만 맞는 키");

        String yml = "spring:\n  application:\n    name: demo\nserver:\n  # 주석\n  port: ${PORT:8082}  # 끝 주석\n"
                + "  servlet:\n    context-path: \"/shop\"\n---\nserver:\n  port: 7000\n";
        eq("8082", HttpText.configValue("application.yml", yml, "server.port"), "yml 자리표시 기본값 · 끝 주석");
        eq("/shop", HttpText.configValue("application.yml", yml, "server.servlet.context-path"), "yml 따옴표");
        eq("demo", HttpText.configValue("application.yml", yml, "spring.application.name"), "yml 형제 키 뒤");
        eq(null, HttpText.configValue("application.yml", "server:\n  port: 1\n---\nserver:\n  servlet:\n    context-path: /x\n",
                "server.servlet.context-path"), "둘째 문서는 안 봄");
        eq("8083", HttpText.configValue("application.yaml", "server.port: 8083\n", "server.port"), "yml 점 키");
        eq(null, HttpText.configValue("application.yml", "server:\n  port: ${PORT}\n", "server.port"), "기본값 없는 자리표시");
        eq(null, HttpText.configValue("application.yml", "server:\n  ports:\n    port: 1\n", "server.port"), "다른 갈래");
    }

    private static void json() {
        eq("{\n  \"a\": 1,\n  \"b\": [\n    1.50,\n    2e10\n  ],\n  \"c\": {}\n}",
                HttpText.prettyJson("{\"a\":1,\"b\":[1.50, 2e10],\"c\":{ }}"), "순서 · 숫자 표기 그대로 · 빈 객체");
        eq("{\n  \"s\": \"x, {y}: \\\"z\\\" \"\n}", HttpText.prettyJson("{\"s\":\"x, {y}: \\\"z\\\" \"}"), "문자열 안 글자");
        eq("[]", HttpText.prettyJson(" [ ] "), "빈 배열");
        eq("{\"a\":1", HttpText.prettyJson("{\"a\":1"), "닫히지 않음은 그대로");
        eq("]x[", HttpText.prettyJson("]x["), "짝 틀림은 그대로");
        eq("{\"a\":\"b", HttpText.prettyJson("{\"a\":\"b"), "문자열 안 닫힘은 그대로");
        eq("\"한글\"", HttpText.prettyJson("\"한글\""), "문자열 하나");
    }

    private static void fails(String text, String expectedPart, String name) {
        try {
            HttpText.parseHeaders(text);
        } catch (IllegalArgumentException e) {
            yes(e.getMessage().contains(expectedPart), name + ": " + e.getMessage());
            return;
        }
        throw new AssertionError(name + ": 예외가 나야 함");
    }

    private static void eq(Object expected, Object actual, String name) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }

    private static void yes(boolean ok, String name) {
        if (!ok) {
            throw new AssertionError(name);
        }
    }
}
