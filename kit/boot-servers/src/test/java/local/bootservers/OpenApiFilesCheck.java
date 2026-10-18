package local.bootservers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** OpenApiFiles 자체 점검. 실패하면 예외로 끝난다. */
public final class OpenApiFilesCheck {

    public static void main(String[] args) {
        version();
        toJson();
        toYaml();
        System.out.println("OpenApiFilesCheck 통과");
    }

    private static void version() {
        eq("3.0", OpenApiFiles.version("openapi: 3.0.3\ninfo:\n  title: a"), "YAML 3.0");
        eq("3.1", OpenApiFiles.version("# 주석\nopenapi: \"3.1.0\""), "주석 뒤 · 따옴표");
        eq("2.0", OpenApiFiles.version("swagger: '2.0'"), "Swagger 2.0");
        eq("3.0", OpenApiFiles.version("{\n  \"openapi\": \"3.0.1\", \"info\": {}}"), "JSON");
        eq("3.0", OpenApiFiles.version("{\"openapi\":\"3.0.0\"}"), "JSON 한 줄");
        eq(null, OpenApiFiles.version("info:\n  openapi: 3.0.0"), "들여쓴 키는 아님");
        eq(null, OpenApiFiles.version("openapi: 4.0.0"), "모르는 판");
        eq(null, OpenApiFiles.version("swagger: '1.2'"), "옛 판");
        eq(null, OpenApiFiles.version("spring:\n  port: 1"), "다른 YAML");
        eq(null, OpenApiFiles.version(null), "null");
    }

    private static void toJson() {
        JsonObject a = parse(OpenApiFiles.toJson("openapi: 3.0.3\ninfo:\n  title: 주문\npaths: {}", "http://localhost:8080/app"));
        eq("주문", a.getAsJsonObject("info").get("title").getAsString(), "YAML → JSON · 한글");
        eq("http://localhost:8080/app", a.getAsJsonArray("servers").get(0).getAsJsonObject().get("url").getAsString(), "servers 채움");
        JsonObject b = parse(OpenApiFiles.toJson("{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"/v1\"}]}", "http://x"));
        eq("/v1", b.getAsJsonArray("servers").get(0).getAsJsonObject().get("url").getAsString(), "있는 servers 는 그대로");
        JsonObject c = parse(OpenApiFiles.toJson("swagger: '2.0'\npaths: {}", "http://x"));
        yes(!c.has("servers"), "2.0 은 servers 넣지 않음");
        fails("openapi: [", "YAML 오류");
        fails("- a\n- b", "목록 최상위");
        fails("{\"openapi\": ", "JSON 오류");
    }

    private static void toYaml() {
        String json = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"주문\",\"version\":\"1.0\"},\"paths\":{\"/a/{id}\":{\"get\":{\"responses\":{\"200\":{\"description\":\"성공\"}}}}}}";
        String yaml = OpenApiFiles.toYaml(json);
        yes(yaml.startsWith("openapi: 3.0.3"), "키 순서 · 블록 꼴: " + yaml);
        yes(yaml.contains("title: 주문"), "한글 그대로");
        eq(OpenApiFiles.toJson(json, null), OpenApiFiles.toJson(yaml, null), "YAML 로 갔다 와도 같음");
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void fails(String text, String name) {
        try {
            OpenApiFiles.toJson(text, null);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(name + ": 실패해야 함");
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
