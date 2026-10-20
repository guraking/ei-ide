package local.bootservers.code;

import java.util.List;

/** BeanCopy 자체 점검. 실패하면 예외로 끝난다. */
public final class BeanCopyCheck {

    private static final String STRING = "java.lang.String";
    private static final String LONG = "java.lang.Long";

    public static void main(String[] args) {
        pairs();
        order();
        edges();
        System.out.println("BeanCopyCheck 통과");
    }

    private static BeanCopy.Accessor a(String method, String type) {
        return new BeanCopy.Accessor(method, type);
    }

    private static void pairs() {
        List<String> lines = BeanCopy.lines("src", "dst", List.of("name", "id", "active", "memo"),
                List.of(a("setName", STRING), a("setId", LONG), a("setActive", "boolean"), a("setMemo", STRING)),
                List.of(a("getName", STRING), a("getId", STRING), a("isActive", "boolean"), a("getClass", "java.lang.Class<?>")));
        eq(List.of(
                "dst.setName(src.getName());",
                "// 형 다름: dst.setId(src.getId()); // Long ← String",
                "dst.setActive(src.isActive());",
                "// 짝 없음: dst.setMemo(...);"), lines, "같음 · 형 다름 · is · 짝 없음");
    }

    private static void order() {
        List<String> lines = BeanCopy.lines("a", "b", List.of("id", "name"),
                List.of(a("setExtra", STRING), a("setName", STRING), a("setId", STRING), a("setName", STRING)),
                List.of(a("getId", STRING), a("getName", STRING), a("getExtra", STRING)));
        eq(List.of("b.setId(a.getId());", "b.setName(a.getName());", "b.setExtra(a.getExtra());"), lines,
                "필드 순서 먼저, 필드 없는 setter 뒤, 같은 setter 한 번");
    }

    private static void edges() {
        eq(List.of("b.setFlag(a.getFlag());"),
                BeanCopy.lines("a", "b", List.of(), List.of(a("setFlag", "boolean")),
                        List.of(a("isFlag", "boolean"), a("getFlag", "boolean"))), "getX 가 isX 보다 먼저");
        eq(List.of("// 짝 없음: b.setOk(...);"),
                BeanCopy.lines("a", "b", List.of(), List.of(a("setOk", "java.lang.Boolean")),
                        List.of(a("isOk", "java.lang.Boolean"))), "Boolean 의 isX 는 getter 아님");
        eq(List.of("b.setTags(a.getTags());"),
                BeanCopy.lines("a", "b", List.of(), List.of(a("setTags", STRING), a("setTags", "java.util.List<java.lang.String>")),
                        List.of(a("getTags", "java.util.List<java.lang.String>"))), "겹친 setter 는 형 맞는 것");
        eq(List.of("b.setURL(a.getURL());"),
                BeanCopy.lines("a", "b", List.of("URL"), List.of(a("setURL", STRING), a("settle", STRING), a("set", STRING)),
                        List.of(a("getURL", STRING))), "대문자 속성 · set 뒤 소문자 무시");
        eq("Map<String, Outer.Inner>", BeanCopy.shortType("java.util.Map<java.lang.String, com.x.Outer.Inner>"), "짧은 형");
        eq(List.of(), BeanCopy.lines("a", "b", List.of("x"), List.of(), List.of()), "setter 없음");
        try {
            BeanCopy.lines(" ", "b", List.of(), List.of(), List.of());
            throw new AssertionError("빈 이름이 통과");
        } catch (IllegalArgumentException expected) {
            // 기대한 예외
        }
    }

    private static void eq(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
