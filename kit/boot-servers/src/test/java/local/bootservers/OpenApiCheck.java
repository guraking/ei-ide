package local.bootservers;

import java.util.List;

/** OpenApiSpec 자체 점검. 실패하면 예외로 끝난다. */
public final class OpenApiCheck {

    public static void main(String[] args) {
        methods();
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

    private static void eq(Object expected, Object actual, String name) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
