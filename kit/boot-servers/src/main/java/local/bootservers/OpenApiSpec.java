package local.bootservers;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 요청 매핑 목록으로 OpenAPI 3.0.3 문서(JSON)를 만든다(IntelliJ 클래스 없음).
 * Swagger UI 가 그대로 읽을 수 있도록 경로 변수 선언 · operationId 겹침 · 인증 방식을 맞춘다.
 */
final class OpenApiSpec {

    static final String OPENAPI_VERSION = "3.0.3";
    static final String DOC_VERSION = "1.0";
    static final String IN_PATH = "path";
    static final String IN_QUERY = "query";
    static final String IN_HEADER = "header";

    static final String TYPE_STRING = "string";
    static final String TYPE_INTEGER = "integer";
    static final String TYPE_NUMBER = "number";
    static final String TYPE_BOOLEAN = "boolean";
    static final String TYPE_ARRAY = "array";
    static final String TYPE_OBJECT = "object";

    static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    static final String BEARER_AUTH = "bearerAuth";
    static final String BASIC_AUTH = "basicAuth";

    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String OK_STATUS = "200";
    private static final String OK_DESCRIPTION = "성공";
    private static final String UNIQUE_SEPARATOR = "_";
    private static final int FIRST_DUPLICATE_NUMBER = 2;
    private static final String METHOD_SEPARATOR = "|";
    /** {이름} · {이름:정규식} 의 이름. OpenAPI 경로 틀에는 이름만 남긴다. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

    private OpenApiSpec() {
    }

    /**
     * 값 모양. ref 가 있으면 components 의 이름(앞에 SCHEMA_REF_PREFIX 를 붙여 낸다),
     * 아니면 type(+ format), 배열이면 items.
     */
    record Schema(String type, String format, Schema items, String ref) {

        static Schema of(String type) {
            return new Schema(type, null, null, null);
        }

        static Schema of(String type, String format) {
            return new Schema(type, format, null, null);
        }

        static Schema array(Schema items) {
            return new Schema(TYPE_ARRAY, null, Objects.requireNonNull(items, "items"), null);
        }

        static Schema ref(String componentName) {
            return new Schema(null, null, null, Objects.requireNonNull(componentName, "componentName"));
        }

        /** 속성을 정하지 않은 객체(Map 등). */
        static Schema object() {
            return of(TYPE_OBJECT);
        }
    }

    /** 요청 값 하나. in 은 IN_PATH · IN_QUERY · IN_HEADER. */
    record Param(String name, String in, Schema schema, boolean required) {
    }

    /** 요청 하나. method 는 소문자 HTTP 방식, body 는 JSON 본문 모양(없으면 null). */
    record Op(String method, String path, String operationId, String tag, List<Param> params, Schema body) {

        Op {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(operationId, "operationId");
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    /** 매핑 목록의 방식 글(「GET」 · 「GET|POST」 · 「모두」)을 소문자 방식들로. 모르는 방식은 건너뛰고, 하나도 없으면 get. */
    static List<String> methods(String httpMethod) {
        Set<String> result = new LinkedHashSet<>();
        if (httpMethod != null) {
            for (String part : httpMethod.split(Pattern.quote(METHOD_SEPARATOR))) {
                for (String known : HttpText.METHODS) {
                    if (known.equals(part.strip())) {
                        result.add(known.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        if (result.isEmpty()) {
            result.add(HttpText.METHODS[0].toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(result);
    }

    /** Swagger UI 바로가기 주소(「#/」 뒤)의 꼴: 태그/operationId. unique 를 거친 Op 여야 문서와 맞는다. */
    static String anchor(Op op) {
        return op.tag() + "/" + op.operationId();
    }

    /** operationId 가 겹치면 뒤의 것에 _2 · _3 … 을 붙인다. 이미 겹치지 않는 목록은 그대로. */
    static List<Op> unique(List<Op> ops) {
        Set<String> used = new HashSet<>();
        List<Op> result = new ArrayList<>();
        for (Op op : ops) {
            String id = op.operationId();
            int n = FIRST_DUPLICATE_NUMBER;
            while (!used.add(id)) {
                id = op.operationId() + UNIQUE_SEPARATOR + n++;
            }
            result.add(id.equals(op.operationId()) ? op
                    : new Op(op.method(), op.path(), id, op.tag(), op.params(), op.body()));
        }
        return result;
    }

    /**
     * OpenAPI 3.0.3 문서를 줄맞춘 JSON 으로. serverUrl 이 비면 servers 를 넣지 않는다.
     * components 는 「이름 → (속성 이름 → 모양)」. 같은 경로 · 방식이 둘 이상이면 앞의 것만 남는다.
     */
    static String toJson(String title, String serverUrl, List<Op> ops, Map<String, Map<String, Schema>> components) {
        JsonObject doc = new JsonObject();
        doc.addProperty("openapi", OPENAPI_VERSION);
        JsonObject info = new JsonObject();
        info.addProperty("title", title == null ? "" : title);
        info.addProperty("version", DOC_VERSION);
        doc.add("info", info);
        if (serverUrl != null && !serverUrl.isBlank()) {
            JsonObject server = new JsonObject();
            server.addProperty("url", serverUrl.strip());
            JsonArray servers = new JsonArray();
            servers.add(server);
            doc.add("servers", servers);
        }

        JsonObject paths = new JsonObject();
        for (Op op : unique(ops)) {
            String path = PATH_VARIABLE.matcher(op.path()).replaceAll("{$1}");
            JsonObject item = paths.getAsJsonObject(path);
            if (item == null) {
                item = new JsonObject();
                paths.add(path, item);
            }
            if (!item.has(op.method())) {
                item.add(op.method(), operation(op, path));
            }
        }
        doc.add("paths", paths);

        JsonObject schemas = new JsonObject();
        if (components != null) {
            for (Map.Entry<String, Map<String, Schema>> component : components.entrySet()) {
                JsonObject properties = new JsonObject();
                for (Map.Entry<String, Schema> property : component.getValue().entrySet()) {
                    properties.add(property.getKey(), schema(property.getValue()));
                }
                JsonObject model = new JsonObject();
                model.addProperty("type", TYPE_OBJECT);
                model.add("properties", properties);
                schemas.add(component.getKey(), model);
            }
        }
        JsonObject securitySchemes = new JsonObject();
        securitySchemes.add(BEARER_AUTH, httpScheme("bearer"));
        securitySchemes.add(BASIC_AUTH, httpScheme("basic"));
        JsonObject componentsJson = new JsonObject();
        componentsJson.add("schemas", schemas);
        componentsJson.add("securitySchemes", securitySchemes);
        doc.add("components", componentsJson);

        // 둘 중 하나만 맞으면 되는 OR 관계: Authorize 창에서 고른 방식이 요청에 실린다.
        JsonArray security = new JsonArray();
        for (String name : new String[]{BEARER_AUTH, BASIC_AUTH}) {
            JsonObject requirement = new JsonObject();
            requirement.add(name, new JsonArray());
            security.add(requirement);
        }
        doc.add("security", security);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(doc);
    }

    /** 경로 틀의 {변수} 가 params 에 없으면 필수 문자열 경로 값으로 채운다(OpenAPI 는 모두 선언해야 한다). */
    private static JsonObject operation(Op op, String path) {
        JsonObject operation = new JsonObject();
        operation.addProperty("operationId", op.operationId());
        if (op.tag() != null) {
            JsonArray tags = new JsonArray();
            tags.add(op.tag());
            operation.add("tags", tags);
        }
        List<Param> params = new ArrayList<>(op.params());
        Set<String> declaredPath = new HashSet<>();
        for (Param p : params) {
            if (IN_PATH.equals(p.in())) {
                declaredPath.add(p.name());
            }
        }
        Matcher m = PATH_VARIABLE.matcher(path);
        while (m.find()) {
            if (declaredPath.add(m.group(1))) {
                params.add(new Param(m.group(1), IN_PATH, Schema.of(TYPE_STRING), true));
            }
        }
        if (!params.isEmpty()) {
            JsonArray parameters = new JsonArray();
            for (Param p : params) {
                JsonObject parameter = new JsonObject();
                parameter.addProperty("name", p.name());
                parameter.addProperty("in", p.in());
                // OpenAPI 는 경로 값을 반드시 required: true 로 요구한다.
                parameter.addProperty("required", p.required() || IN_PATH.equals(p.in()));
                parameter.add("schema", schema(p.schema() == null ? Schema.of(TYPE_STRING) : p.schema()));
                parameters.add(parameter);
            }
            operation.add("parameters", parameters);
        }
        if (op.body() != null) {
            JsonObject media = new JsonObject();
            media.add("schema", schema(op.body()));
            JsonObject content = new JsonObject();
            content.add(JSON_MEDIA_TYPE, media);
            JsonObject requestBody = new JsonObject();
            requestBody.add("content", content);
            operation.add("requestBody", requestBody);
        }
        JsonObject ok = new JsonObject();
        ok.addProperty("description", OK_DESCRIPTION);
        JsonObject responses = new JsonObject();
        responses.add(OK_STATUS, ok);
        operation.add("responses", responses);
        return operation;
    }

    private static JsonObject schema(Schema s) {
        JsonObject json = new JsonObject();
        if (s.ref() != null) {
            json.addProperty("$ref", SCHEMA_REF_PREFIX + s.ref());
            return json;
        }
        json.addProperty("type", s.type());
        if (s.format() != null) {
            json.addProperty("format", s.format());
        }
        if (s.items() != null) {
            json.add("items", schema(s.items()));
        }
        return json;
    }

    private static JsonObject httpScheme(String scheme) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "http");
        json.addProperty("scheme", scheme);
        return json;
    }
}
