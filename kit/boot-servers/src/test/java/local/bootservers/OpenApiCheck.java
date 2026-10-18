package local.bootservers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import local.bootservers.OpenApiSpec.Op;
import local.bootservers.OpenApiSpec.Param;
import local.bootservers.OpenApiSpec.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenApiSpec 자체 점검. 실패하면 예외로 끝난다. */
public final class OpenApiCheck {

    public static void main(String[] args) {
        methods();
        document();
        unique();
        System.out.println("OpenApiCheck 통과");
    }

    private static void methods() {
        eq(List.of("get"), OpenApiSpec.methods("GET"), "하나");
        eq(List.of("get", "post"), OpenApiSpec.methods("GET|POST"), "여럿");
        eq(List.of("get"), OpenApiSpec.methods("모두"), "모두는 get");
        eq(List.of("get"), OpenApiSpec.methods(" "), "빈 글");
        eq(List.of("get"), OpenApiSpec.methods(null), "null");
        eq(List.of("put"), OpenApiSpec.methods("HEAD|PUT|PUT"), "모르는 방식 건너뜀 · 겹침 하나");
    }

    private static void document() {
        Schema id = Schema.of(OpenApiSpec.TYPE_INTEGER, "int64");
        List<Op> ops = List.of(
                new Op("get", "/api/order/{id}/item/{no:\\d+}", "find", "OrderController",
                        List.of(new Param("id", OpenApiSpec.IN_PATH, id, true),
                                new Param("q", OpenApiSpec.IN_QUERY, Schema.of(OpenApiSpec.TYPE_STRING), false)), null),
                new Op("post", "/api/order", "save", "OrderController", null, Schema.ref("OrderDto")),
                new Op("get", "/api/user", "find", "UserController", List.of(), null));
        Map<String, Map<String, Schema>> components = new LinkedHashMap<>();
        Map<String, Schema> order = new LinkedHashMap<>();
        order.put("id", id);
        order.put("tags", Schema.array(Schema.of(OpenApiSpec.TYPE_STRING)));
        order.put("extra", Schema.object());
        components.put("OrderDto", order);

        JsonObject doc = JsonParser.parseString(
                OpenApiSpec.toJson("주문 API", "http://localhost:8080", ops, components)).getAsJsonObject();
        eq("3.0.3", doc.get("openapi").getAsString(), "판");
        eq("주문 API", doc.getAsJsonObject("info").get("title").getAsString(), "제목");
        eq("1.0", doc.getAsJsonObject("info").get("version").getAsString(), "문서 판");
        eq("http://localhost:8080", doc.getAsJsonArray("servers").get(0).getAsJsonObject().get("url").getAsString(), "서버");
        yes(!JsonParser.parseString(OpenApiSpec.toJson("t", " ", ops, Map.of())).getAsJsonObject().has("servers"), "빈 서버는 뺌");

        JsonObject paths = doc.getAsJsonObject("paths");
        JsonObject find = paths.getAsJsonObject("/api/order/{id}/item/{no}").getAsJsonObject("get");
        yes(find != null, "경로 변수 정규식 뗌");
        JsonArray params = find.getAsJsonArray("parameters");
        eq(3, params.size(), "경로 변수 채움");
        eq("int64", params.get(0).getAsJsonObject().getAsJsonObject("schema").get("format").getAsString(), "선언한 경로 값 그대로");
        eq(false, params.get(1).getAsJsonObject().get("required").getAsBoolean(), "선택 쿼리");
        JsonObject added = params.get(2).getAsJsonObject();
        eq("no", added.get("name").getAsString(), "채운 이름");
        eq("path", added.get("in").getAsString(), "채운 위치");
        eq(true, added.get("required").getAsBoolean(), "채운 값은 필수");
        eq("string", added.getAsJsonObject("schema").get("type").getAsString(), "채운 모양");
        eq("OrderController", find.getAsJsonArray("tags").get(0).getAsString(), "태그");
        eq("성공", find.getAsJsonObject("responses").getAsJsonObject("200").get("description").getAsString(), "응답");
        yes(!find.has("requestBody"), "본문 없음");

        JsonObject save = paths.getAsJsonObject("/api/order").getAsJsonObject("post");
        yes(!save.has("parameters"), "값 없으면 parameters 뺌");
        eq("#/components/schemas/OrderDto", save.getAsJsonObject("requestBody").getAsJsonObject("content")
                .getAsJsonObject("application/json").getAsJsonObject("schema").get("$ref").getAsString(), "본문 참조");
        eq("find_2", paths.getAsJsonObject("/api/user").getAsJsonObject("get").get("operationId").getAsString(), "겹친 operationId");

        JsonObject comps = doc.getAsJsonObject("components");
        JsonObject dto = comps.getAsJsonObject("schemas").getAsJsonObject("OrderDto");
        eq("object", dto.get("type").getAsString(), "모델 형식");
        eq("string", dto.getAsJsonObject("properties").getAsJsonObject("tags").getAsJsonObject("items").get("type").getAsString(), "배열 항목");
        eq("object", dto.getAsJsonObject("properties").getAsJsonObject("extra").get("type").getAsString(), "자유 객체");
        JsonObject schemes = comps.getAsJsonObject("securitySchemes");
        eq("bearer", schemes.getAsJsonObject("bearerAuth").get("scheme").getAsString(), "Bearer");
        eq("basic", schemes.getAsJsonObject("basicAuth").get("scheme").getAsString(), "Basic");
        eq("http", schemes.getAsJsonObject("basicAuth").get("type").getAsString(), "http 방식");
        JsonArray security = doc.getAsJsonArray("security");
        eq(2, security.size(), "인증 둘");
        yes(security.get(0).getAsJsonObject().has("bearerAuth") && security.get(1).getAsJsonObject().has("basicAuth"), "인증 순서");
    }

    private static void unique() {
        List<Op> ops = OpenApiSpec.unique(List.of(op("a"), op("a"), op("a_2"), op("b"), op("a")));
        eq("a", ops.get(0).operationId(), "첫째 그대로");
        eq("a_2", ops.get(1).operationId(), "둘째");
        eq("a_2_2", ops.get(2).operationId(), "원래 a_2 와 겹침");
        eq("b", ops.get(3).operationId(), "안 겹침");
        eq("a_3", ops.get(4).operationId(), "셋째");
        eq(ops, OpenApiSpec.unique(ops), "두 번 거쳐도 같음");
        eq("T/a_3", OpenApiSpec.anchor(ops.get(4)), "바로가기");
    }

    private static Op op(String id) {
        return new Op("get", "/" + id, id, "T", List.of(), null);
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
