package local.bootservers.code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JUnit 5 테스트 뼈대 글 만들기. IntelliJ 클래스에 기대지 않는다.
 * 매퍼는 @MybatisTest(실제 DB 그대로), 그 밖은 Mockito(@Mock 의존 필드, @InjectMocks 대상).
 * 형 글은 받은 그대로 쓴다(FQN 이면 FQN). import 줄이기는 부르는 쪽이 한다.
 */
public final class TestSkeleton {

    /** 테스트 종류. */
    public enum Kind { MAPPER, MOCK }

    /** 주입받는 필드. type 은 형 글. */
    public record Field(String type, String name) {
    }

    /**
     * 테스트할 메서드. paramTypes 는 지워진 형 글(제네릭 없음, 가변 인자는 배열).
     * throwsChecked 이면 테스트 메서드에 throws Exception 을 붙인다.
     */
    public record Method(String name, List<String> paramTypes, boolean throwsChecked) {
    }

    public static final String TEST_SUFFIX = "Test";
    public static final String TARGET_FIELD = "target";
    private static final String OVERLOAD_SEPARATOR = "_";
    private static final String INDENT = "    ";
    private static final String MAPPER_ANNOTATION = "org.apache.ibatis.annotations.Mapper";
    private static final String[] MAPPER_NAME_SUFFIXES = {"Mapper", "Dao", "DAO"};

    private static final String TEST = "org.junit.jupiter.api.Test";
    private static final String EXTEND_WITH = "org.junit.jupiter.api.extension.ExtendWith";
    private static final String MOCK = "org.mockito.Mock";
    private static final String INJECT_MOCKS = "org.mockito.InjectMocks";
    private static final String MOCKITO_EXTENSION = "org.mockito.junit.jupiter.MockitoExtension";
    private static final String MYBATIS_TEST = "org.mybatis.spring.boot.test.autoconfigure.MybatisTest";
    private static final String AUTO_CONFIGURE_TEST_DATABASE =
            "org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase";
    private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";

    private static final String[] MAPPER_IMPORTS = {TEST, MYBATIS_TEST, AUTO_CONFIGURE_TEST_DATABASE, AUTOWIRED};
    private static final String[] MOCK_IMPORTS = {TEST, EXTEND_WITH, INJECT_MOCKS, MOCK, MOCKITO_EXTENSION};

    private static final Map<String, String> PRIMITIVE_DEFAULTS = Map.of(
            "int", "0", "long", "0L", "double", "0.0", "float", "0f",
            "short", "(short) 0", "byte", "(byte) 0", "char", "'\\0'", "boolean", "false");
    private static final String NULL = "null";

    private TestSkeleton() {
    }

    /** 이름이 Mapper · Dao · DAO 로 끝나거나 @Mapper 가 붙은 인터페이스. */
    public static boolean isMapper(boolean isInterface, Set<String> annotations, String simpleName) {
        if (!isInterface) {
            return false;
        }
        if (annotations.contains(MAPPER_ANNOTATION)) {
            return true;
        }
        for (String suffix : MAPPER_NAME_SUFFIXES) {
            if (simpleName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static String testClassName(String className) {
        return className + TEST_SUFFIX;
    }

    /**
     * 새 테스트 파일 글. pkg 가 비면 package 줄을 쓰지 않는다.
     * MAPPER 에서는 fields 를 쓰지 않고 대상 매퍼 하나를 @Autowired 로 둔다.
     */
    public static String file(String pkg, String className, Kind kind, List<Field> fields, List<Method> methods) {
        StringBuilder sb = new StringBuilder();
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }
        for (String fqn : kind == Kind.MAPPER ? MAPPER_IMPORTS : MOCK_IMPORTS) {
            sb.append("import ").append(fqn).append(";\n");
        }
        sb.append('\n');
        if (kind == Kind.MAPPER) {
            sb.append("@MybatisTest\n");
            sb.append("@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)\n");
        } else {
            sb.append("@ExtendWith(MockitoExtension.class)\n");
        }
        sb.append("class ").append(testClassName(className)).append(" {\n");
        if (kind == Kind.MAPPER) {
            field(sb, "@Autowired", className, mapperField(className));
        } else {
            for (Field f : fields) {
                field(sb, "@Mock", f.type(), f.name());
            }
            field(sb, "@InjectMocks", className, TARGET_FIELD);
        }
        List<String> names = testNames(methods);
        for (int i = 0; i < methods.size(); i++) {
            sb.append('\n');
            method(sb, "@Test", kind, names.get(i), methods.get(i), isOverloaded(methods, i));
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 기존 테스트 클래스에 없는 테스트 메서드 글들. existing 은 그 클래스의 메서드 이름들.
     * import 가 없는 파일에도 붙도록 @Test 는 FQN 으로 쓴다.
     */
    public static List<String> missingMethods(Kind kind, List<Method> methods, Set<String> existing) {
        List<String> result = new ArrayList<>();
        List<String> names = testNames(methods);
        for (int i = 0; i < methods.size(); i++) {
            if (existing.contains(names.get(i))) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            method(sb, "@" + TEST, kind, names.get(i), methods.get(i), isOverloaded(methods, i));
            result.add(sb.toString());
        }
        return result;
    }

    /** 선언 순서대로 이름, 같은 이름의 두 번째부터 _2, _3. */
    static List<String> testNames(List<Method> methods) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (Method m : methods) {
            int count = seen.merge(m.name(), 1, Integer::sum);
            names.add(count == 1 ? m.name() : m.name() + OVERLOAD_SEPARATOR + count);
        }
        return names;
    }

    static String mapperField(String className) {
        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    private static boolean isOverloaded(List<Method> methods, int index) {
        String name = methods.get(index).name();
        for (int i = 0; i < methods.size(); i++) {
            if (i != index && methods.get(i).name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void field(StringBuilder sb, String annotation, String type, String name) {
        sb.append('\n');
        sb.append(INDENT).append(annotation).append('\n');
        sb.append(INDENT).append("private ").append(type).append(' ').append(name).append(";\n");
    }

    /** 오버로드된 메서드의 null 인자는 어느 메서드인지 정해지도록 형을 붙인다. */
    private static void method(StringBuilder sb, String annotation, Kind kind, String testName,
                               Method m, boolean overloaded) {
        sb.append(INDENT).append(annotation).append('\n');
        sb.append(INDENT).append("void ").append(testName).append("()");
        if (kind == Kind.MOCK && m.throwsChecked()) {
            sb.append(" throws Exception");
        }
        sb.append(" {\n");
        sb.append(INDENT).append(INDENT).append("// given\n\n");
        sb.append(INDENT).append(INDENT).append("// when\n");
        if (kind == Kind.MOCK) {
            sb.append(INDENT).append(INDENT).append(TARGET_FIELD).append('.').append(m.name()).append('(');
            for (int i = 0; i < m.paramTypes().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(defaultValue(m.paramTypes().get(i), overloaded));
            }
            sb.append(");\n");
        }
        sb.append('\n');
        sb.append(INDENT).append(INDENT).append("// then\n");
        sb.append(INDENT).append("}\n");
    }

    static String defaultValue(String type, boolean typed) {
        String primitive = PRIMITIVE_DEFAULTS.get(type);
        if (primitive != null) {
            return primitive;
        }
        return typed ? "(" + type + ") " + NULL : NULL;
    }
}
