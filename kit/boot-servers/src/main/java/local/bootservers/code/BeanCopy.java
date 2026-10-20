package local.bootservers.code;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 객체 옮겨 담기 코드 줄 만들기. 담을 쪽 setter 마다 같은 속성 이름의 꺼낼 쪽 getter(getX, boolean 은 isX 도)를 짝짓는다.
 * 형은 정규 이름 글로 비교하고 같을 때만 실제 문장, 다르면 「형 다름」 주석 줄, getter 가 없으면 「짝 없음」 주석 줄.
 * 줄 순서는 필드 선언 순서이고, 같은 이름 필드가 없는 setter 는 그 뒤에 받은 순서대로 둔다.
 */
public final class BeanCopy {

    /** 접근 메서드. type 은 setter 면 매개변수 형, getter 면 돌려주는 형의 정규 이름. */
    public record Accessor(String method, String type) {
    }

    private static final String SET = "set";
    private static final String GET = "get";
    private static final String IS = "is";
    private static final String BOOLEAN = "boolean";
    private static final String TYPE_DIFFERS = "// 형 다름: ";
    private static final String NO_PAIR = "// 짝 없음: ";
    private static final String TYPE_ARROW = " ← ";
    /** 소문자로 시작하는 꾸러미 이름 마디(java. util. 등). 중첩 클래스 이름(Outer.Inner)은 남긴다. */
    private static final Pattern PACKAGE_PART = Pattern.compile("\\b[a-z_][\\w$]*\\.");

    private BeanCopy() {
    }

    /**
     * @param from       꺼낼 객체 식 이름
     * @param to         담을 객체 식 이름
     * @param fieldOrder 담을 클래스 필드 이름(상위 클래스 먼저, 선언 순서)
     * @param setters    담을 클래스의 매개변수 하나인 메서드들. set 으로 시작하지 않는 것은 무시한다.
     * @param getters    꺼낼 클래스의 매개변수 없는 메서드들. get · is 로 시작하지 않는 것은 무시한다.
     */
    public static List<String> lines(String from, String to, List<String> fieldOrder,
                                     List<Accessor> setters, List<Accessor> getters) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalArgumentException("꺼낼 객체 · 담을 객체 이름이 비었습니다");
        }
        Objects.requireNonNull(fieldOrder, "fieldOrder");
        Map<String, List<Accessor>> setterByProperty = new LinkedHashMap<>();
        for (Accessor s : setters) {
            String property = property(s.method(), SET);
            if (property == null) {
                continue;
            }
            List<Accessor> overloads = setterByProperty.computeIfAbsent(property, k -> new ArrayList<>());
            if (!overloads.contains(s)) {
                overloads.add(s);
            }
        }
        Map<String, Accessor> getterByProperty = getterMap(getters);

        Set<String> order = new LinkedHashSet<>();
        for (String field : fieldOrder) {
            String property = capitalize(field);
            if (setterByProperty.containsKey(property)) {
                order.add(property);
            }
        }
        order.addAll(setterByProperty.keySet());

        List<String> out = new ArrayList<>();
        for (String property : order) {
            Accessor getter = getterByProperty.get(property);
            Accessor setter = pick(setterByProperty.get(property), getter);
            String call = to + "." + setter.method() + "(";
            if (getter == null) {
                out.add(NO_PAIR + call + "...);");
                continue;
            }
            String statement = call + from + "." + getter.method() + "());";
            if (setter.type().equals(getter.type())) {
                out.add(statement);
            } else {
                out.add(TYPE_DIFFERS + statement + " // " + shortType(setter.type()) + TYPE_ARROW + shortType(getter.type()));
            }
        }
        return out;
    }

    /** getX 가 isX 보다 먼저. 같은 이름이 여럿이면 처음 것. */
    private static Map<String, Accessor> getterMap(List<Accessor> getters) {
        Map<String, Accessor> map = new LinkedHashMap<>();
        Set<String> fromGet = new LinkedHashSet<>();
        for (Accessor g : getters) {
            String property = property(g.method(), GET);
            if (property != null) {
                if (fromGet.add(property)) {
                    map.put(property, g);
                }
                continue;
            }
            property = property(g.method(), IS);
            if (property != null && BOOLEAN.equals(g.type())) {
                map.putIfAbsent(property, g);
            }
        }
        return map;
    }

    /** 겹쳐 정의된 setter 중 getter 와 형이 같은 것, 없으면 처음 것. */
    private static Accessor pick(List<Accessor> overloads, Accessor getter) {
        if (getter != null) {
            for (Accessor s : overloads) {
                if (s.type().equals(getter.type())) {
                    return s;
                }
            }
        }
        return overloads.get(0);
    }

    /** prefix 뒤가 대문자로 시작하면 그 뒤 글(속성 이름 대문자 꼴), 아니면 null. */
    private static String property(String method, String prefix) {
        if (method == null || method.length() <= prefix.length() || !method.startsWith(prefix)
                || !Character.isUpperCase(method.charAt(prefix.length()))) {
            return null;
        }
        return method.substring(prefix.length());
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** 정규 이름에서 꾸러미 이름을 뺀 글. java.util.List&lt;java.lang.String&gt; → List&lt;String&gt; */
    static String shortType(String type) {
        return PACKAGE_PART.matcher(type).replaceAll("");
    }
}
