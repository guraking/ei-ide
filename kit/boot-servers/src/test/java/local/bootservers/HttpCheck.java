package local.bootservers;

import java.util.List;
import java.util.Set;

/** HttpText · 서버 기본 주소 설정 읽기 자체 점검. 실패하면 예외로 끝난다. */
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

    /** 서버 기본 주소가 설정 파일을 읽는 길(ServerBase): ConfigKeys.entries + Links.value. 프로필 파일 · 프로필 문서가 이긴다. */
    private static void config() {
        String props = "# 주석\nserver.port = 9090\nserver.servlet.context-path:/app\nserver.port=9091\n";
        List<Links.Source> base = List.of(source("application.properties", props));
        Set<String> none = Links.profiles("", base);
        eq("9091", Links.value(base, none, HttpText.PORT_KEY), "properties 뒤 값");
        eq("/app", Links.value(base, none, HttpText.CONTEXT_PATH_KEYS[0]), "properties : 구분");
        eq(null, Links.value(base, none, "server"), "앞부분만 맞는 키");

        String yml = "spring:\n  application:\n    name: demo\n  profiles:\n    active: local\nserver:\n  # 주석\n  port: ${PORT:8082}  # 끝 주석\n"
                + "  servlet:\n    context-path: \"/shop\"\n---\nspring:\n  config:\n    activate:\n      on-profile: dev\nserver:\n  port: 7000\n";
        List<Links.Source> withProfile = List.of(source("application.yml", yml), source("application-local.yml", "server:\n  port: 8181\n"));
        Set<String> active = Links.profiles("", withProfile);
        eq(Set.of("local"), active, "설정 파일의 spring.profiles.active");
        eq(8181, HttpText.parsePort(Links.value(withProfile, active, HttpText.PORT_KEY)), "꺼진 서버도 프로필 파일 포트");
        eq("/shop", Links.value(withProfile, active, HttpText.CONTEXT_PATH_KEYS[0]), "yml 따옴표");
        eq("demo", Links.value(withProfile, active, "spring.application.name"), "yml 형제 키 뒤");
        eq(8082, HttpText.parsePort(Links.value(withProfile.subList(0, 1), active, HttpText.PORT_KEY)), "yml 자리표시 기본값 · 끝 주석 · 다른 프로필 문서 안 봄");
        eq(7000, HttpText.parsePort(Links.value(withProfile, Links.profiles("dev", withProfile), HttpText.PORT_KEY)), "VM 옵션 프로필이 이김");
        eq("8083", Links.value(List.of(source("application.yaml", "server.port: 8083\n")), none, HttpText.PORT_KEY), "yml 점 키");
        eq(null, Links.value(List.of(source("application.yml", "server:\n  port: ${PORT}\n")), none, HttpText.PORT_KEY), "기본값 없는 자리표시");
        eq(null, Links.value(List.of(source("application.yml", "server:\n  ports:\n    port: 1\n")), none, HttpText.PORT_KEY), "다른 갈래");
    }

    private static Links.Source source(String name, String text) {
        boolean yaml = !name.endsWith(".properties");
        return new Links.Source(name, yaml, ConfigKeys.entries(text, yaml));
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
