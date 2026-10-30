package local.bootservers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 요청 값과 값 모양의 표현, 매핑 목록의 방식 글 풀기(IntelliJ 클래스 없음). 값 이름은 OpenAPI 와 같다.
 */
final class OpenApiSpec {

    static final String IN_PATH = "path";
    static final String IN_QUERY = "query";
    static final String IN_HEADER = "header";

    static final String TYPE_STRING = "string";
    static final String TYPE_INTEGER = "integer";
    static final String TYPE_NUMBER = "number";
    static final String TYPE_BOOLEAN = "boolean";
    static final String TYPE_ARRAY = "array";
    static final String TYPE_OBJECT = "object";

    private static final String METHOD_SEPARATOR = "|";

    private OpenApiSpec() {
    }

    /**
     * 값 모양. ref 가 있으면 components 의 이름,
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
}
