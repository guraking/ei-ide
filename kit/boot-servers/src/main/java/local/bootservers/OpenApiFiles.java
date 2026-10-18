package local.bootservers;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAPI 사양 파일 글 처리(IntelliJ 클래스 없음): 판 알아보기, YAML · JSON 을 Swagger UI 가 읽을 JSON 으로 바꾸기.
 */
final class OpenApiFiles {

    static final String SWAGGER_2 = "2.0";
    static final String OPENAPI_30 = "3.0";
    static final String OPENAPI_31 = "3.1";
    /** 판을 알아볼 때 읽는 파일 앞부분 글자 수. 최상위 openapi · swagger 키는 보통 첫 줄에 있다. */
    static final int HEAD_CHARS = 4096;

    // 최상위(들여쓰기 없는) YAML 키 또는 JSON 의 첫 키. 값은 따옴표가 있어도 된다.
    private static final Pattern VERSION = Pattern.compile(
            "(?m)^(?:\\s*\\{\\s*)?[\"']?(openapi|swagger)[\"']?\\s*:\\s*[\"']?(\\d+\\.\\d+)");

    private OpenApiFiles() {
    }

    /** 파일 앞부분 글로 판을 알아본다. Swagger 2.0 · OpenAPI 3.0 · 3.1 이면 그 판, 아니면 null. */
    static String version(String head) {
        if (head == null) {
            return null;
        }
        Matcher m = VERSION.matcher(head);
        if (!m.find()) {
            return null;
        }
        String v = m.group(2);
        if ("swagger".equals(m.group(1))) {
            return SWAGGER_2.equals(v) ? SWAGGER_2 : null;
        }
        return OPENAPI_30.equals(v) || OPENAPI_31.equals(v) ? v : null;
    }

    /**
     * 사양 글(JSON 또는 YAML)을 JSON 으로 바꾼다. servers 가 없고 defaultServer 가 있으면 그 주소를 넣는다
     * (Swagger 2.0 은 servers 가 없으므로 그대로). 읽을 수 없으면 IllegalArgumentException(위치를 담은 메시지).
     */
    static String toJson(String text, String defaultServer) {
        JsonElement root;
        try {
            String trimmed = text == null ? "" : text.strip();
            if (trimmed.startsWith("{")) {
                root = JsonParser.parseString(trimmed);
            } else {
                Object yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(trimmed);
                if (!(yaml instanceof Map)) {
                    throw new IllegalArgumentException("최상위가 키 · 값 묶음이 아닙니다.");
                }
                root = new GsonBuilder().create().toJsonTree(yaml);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("사양 파일을 읽을 수 없습니다: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("최상위가 키 · 값 묶음이 아닙니다.");
        }
        JsonObject spec = root.getAsJsonObject();
        if (spec.has("openapi") && !spec.has("servers") && defaultServer != null && !defaultServer.isBlank()) {
            JsonObject server = new JsonObject();
            server.addProperty("url", defaultServer);
            JsonArray servers = new JsonArray();
            servers.add(server);
            spec.add("servers", servers);
        }
        return spec.toString();
    }

    /** JSON 사양을 블록 꼴 YAML 로 바꾼다(파일로 저장할 때). 키 순서를 지킨다. */
    static String toYaml(String json) {
        Object tree = new GsonBuilder().create().fromJson(json, Object.class);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        return new Yaml(options).dump(tree);
    }
}
