package local.bootservers;

/** HttpText 자체 점검. 실패하면 예외로 끝난다. */
public final class HttpCheck {

    public static void main(String[] args) {
        urls();
        config();
        System.out.println("HttpCheck 통과");
    }

    private static void urls() {
        eq("http://localhost:8080", HttpText.baseUrl(8080, null), "경로 없음");
        eq("http://localhost:8080", HttpText.baseUrl(8080, "/"), "뿌리 경로");
        eq("http://localhost:9000/app", HttpText.baseUrl(9000, "app/"), "앞 / 붙임 · 끝 / 뗌");
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
