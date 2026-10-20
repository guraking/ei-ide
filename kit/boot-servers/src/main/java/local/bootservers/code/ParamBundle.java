package local.bootservers.code;

import local.bootservers.CaseConvert;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 컨트롤러 메서드의 요청 값 매개변수 여럿을 @ModelAttribute DTO 하나로 묶는 글 만들기.
 * 형 이름은 정규 이름(java.time.LocalDate) 그대로 쓰고, import 정리는 부르는 쪽(shortenClassReferences)이 한다.
 * DTO 는 Lombok 없이 필드 · getter · setter 를 쓴다. 스프링 바인딩은 setter 이름으로 하므로 필드 이름이 곧 요청 이름이다.
 */
public final class ParamBundle {

    /**
     * 묶을 매개변수.
     * @param name         매개변수 이름
     * @param type         정규 형 이름
     * @param requestName  @RequestParam value · name. 없으면 null
     * @param defaultValue @RequestParam defaultValue. 없으면 null
     * @param annotations  필드에 옮길 주석 글(@RequestParam 빼고, 정규 이름으로). 없으면 빈 목록
     */
    public record Param(String name, String type, String requestName, String defaultValue, List<String> annotations) {
        public Param {
            if (name == null || name.isBlank() || type == null || type.isBlank()) {
                throw new IllegalArgumentException("매개변수 이름 · 형이 비었습니다");
            }
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }
    }

    /** DTO 필드 하나. name 은 사용자가 고친 필드 이름. */
    public record Field(String name, Param param) {
        public Field {
            Objects.requireNonNull(param, "param");
            if (!isValidName(name)) {
                throw new IllegalArgumentException("필드 이름이 자바 이름이 아닙니다: " + name);
            }
        }
    }

    public static final String CLASS_SUFFIX = "Request";
    public static final String DEFAULT_VARIABLE = "req";
    private static final String MODEL_ATTRIBUTE = "org.springframework.web.bind.annotation.ModelAttribute";
    private static final String GET = "get";
    private static final String SET = "set";
    private static final String INDENT = "    ";
    private static final String NOT_IDENTIFIER_PART = "[^\\p{javaJavaIdentifierPart}]";
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
    private static final String TRUE = "true";
    private static final String FALSE = "false";

    /** 정수 리터럴 그대로 쓸 수 있는 형. */
    private static final Set<String> INT_TYPES = Set.of("int", "java.lang.Integer", "short", "java.lang.Short",
            "byte", "java.lang.Byte");
    private static final Set<String> LONG_TYPES = Set.of("long", "java.lang.Long");
    private static final Set<String> DOUBLE_TYPES = Set.of("double", "java.lang.Double");
    private static final Set<String> FLOAT_TYPES = Set.of("float", "java.lang.Float");
    private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "java.lang.Boolean");
    private static final Set<String> STRING_TYPES = Set.of("java.lang.String", "java.lang.CharSequence");
    /** 문자열 인자 하나인 static parse 로 기본값을 만드는 형. */
    private static final Set<String> PARSE_TYPES = Set.of("java.time.LocalDate", "java.time.LocalDateTime",
            "java.time.LocalTime");
    /** 문자열 인자 하나인 생성자로 기본값을 만드는 형. */
    private static final Set<String> NEW_FROM_STRING_TYPES = Set.of("java.math.BigDecimal", "java.math.BigInteger");

    /** 주석 없이도 요청 값으로 묶는 단순형(정규 이름). 열거형은 부르는 쪽이 따로 본다. */
    private static final Set<String> SIMPLE_TYPES = Set.of(
            "boolean", "byte", "short", "int", "long", "float", "double", "char",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.lang.Float", "java.lang.Double", "java.lang.Character", "java.lang.String",
            "java.math.BigDecimal", "java.math.BigInteger", "java.util.Date",
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime");

    private ParamBundle() {
    }

    public static boolean isSimpleType(String canonicalType) {
        return canonicalType != null && SIMPLE_TYPES.contains(canonicalType);
    }

    /** 자바 이름(키워드 · 빈 글 아님)인지. */
    public static boolean isValidName(String name) {
        return name != null && SourceVersion.isName(name) && name.indexOf('.') < 0;
    }

    /** 메서드 이름 Pascal + Request. */
    public static String className(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("메서드 이름이 비었습니다");
        }
        return CaseConvert.convert(methodName, CaseConvert.Style.PASCAL) + CLASS_SUFFIX;
    }

    /** 요청 이름이 있으면 그것을 camel 로(- . 등은 낱말 경계), 없거나 camel 로 자바 이름이 안 되면 매개변수 이름. */
    public static String defaultFieldName(Param p) {
        if (p.requestName() == null || p.requestName().isBlank()) {
            return p.name();
        }
        String camel = CaseConvert.convert(p.requestName().strip().replaceAll(NOT_IDENTIFIER_PART, "_"), CaseConvert.Style.CAMEL);
        return isValidName(camel) ? camel : p.name();
    }

    /** 요청 이름이 따로 있고 필드 이름과 다른 것. 이대로면 그 요청 값이 바인딩되지 않는다. 「요청 이름 → 필드 이름」 글. */
    public static List<String> nameWarnings(List<Field> fields) {
        List<String> out = new ArrayList<>();
        for (Field f : fields) {
            String requestName = f.param().requestName();
            if (requestName != null && !requestName.isBlank() && !requestName.equals(f.name())) {
                out.add(requestName + " → " + f.name());
            }
        }
        return out;
    }

    /** 이미 쓰는 이름과 겹치지 않는 DTO 변수 이름. req, req2, req3 … */
    public static String variableName(Set<String> taken) {
        String name = DEFAULT_VARIABLE;
        for (int n = 2; taken.contains(name); n++) {
            name = DEFAULT_VARIABLE + n;
        }
        return name;
    }

    /**
     * shortcut: 둘째 글자가 대문자인 이름(xValue)은 getXValue 가 되어 JavaBeans 속성 이름이 XValue 로 읽힌다. 문제 되면 Introspector 규칙으로 바꾼다.
     */
    public static String getter(String field) {
        return GET + capitalize(field);
    }

    public static String setter(String field) {
        return SET + capitalize(field);
    }

    /** 본문의 매개변수 참조를 바꿀 식. */
    public static String access(String variable, String field) {
        return variable + "." + getter(field) + "()";
    }

    /** 묶은 매개변수 자리에 넣을 매개변수 글. */
    public static String parameter(String classQualifiedName, String variable) {
        return "@" + MODEL_ATTRIBUTE + " " + classQualifiedName + " " + variable;
    }

    /**
     * defaultValue 를 그 형의 필드 초기값 식으로. 옮길 수 없으면(형이 맞지 않거나 값이 그 형 글이 아님) null.
     * 스프링 defaultValue 는 빈 글 요청에도 쓰이지만 필드 초기값은 값이 없을 때만 남는다.
     */
    public static String initializer(String type, String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (STRING_TYPES.contains(type)) {
            return quote(value);
        }
        if (INT_TYPES.contains(type)) {
            return INTEGER.matcher(v).matches() ? v : null;
        }
        if (LONG_TYPES.contains(type)) {
            return INTEGER.matcher(v).matches() ? v + "L" : null;
        }
        if (DOUBLE_TYPES.contains(type)) {
            return DECIMAL.matcher(v).matches() ? v + "D" : null;
        }
        if (FLOAT_TYPES.contains(type)) {
            return DECIMAL.matcher(v).matches() ? v + "F" : null;
        }
        if (BOOLEAN_TYPES.contains(type)) {
            return TRUE.equalsIgnoreCase(v) ? TRUE : FALSE.equalsIgnoreCase(v) ? FALSE : null;
        }
        if (NEW_FROM_STRING_TYPES.contains(type)) {
            return DECIMAL.matcher(v).matches() ? "new " + type + "(" + quote(v) + ")" : null;
        }
        if (PARSE_TYPES.contains(type)) {
            return v.isEmpty() ? null : type + ".parse(" + quote(v) + ")";
        }
        return null;
    }

    /** DTO 파일 글. pkg 가 빈 글이면 package 줄 없음. */
    public static String dto(String pkg, String className, List<Field> fields) {
        if (!isValidName(className)) {
            throw new IllegalArgumentException("클래스 이름이 자바 이름이 아닙니다: " + className);
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("필드가 없습니다");
        }
        StringBuilder out = new StringBuilder();
        if (pkg != null && !pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        out.append("public class ").append(className).append(" {\n\n");
        for (Field f : fields) {
            Param p = f.param();
            String init = initializer(p.type(), p.defaultValue());
            if (p.defaultValue() != null && init == null) {
                out.append(INDENT).append("// 기본값 ").append(quote(p.defaultValue())).append(" 은 형에 맞게 직접 넣어야 합니다\n");
            }
            for (String annotation : p.annotations()) {
                out.append(INDENT).append(annotation).append('\n');
            }
            out.append(INDENT).append("private ").append(p.type()).append(' ').append(f.name());
            if (init != null) {
                out.append(" = ").append(init);
            }
            out.append(";\n");
        }
        for (Field f : fields) {
            String type = f.param().type();
            out.append('\n')
                    .append(INDENT).append("public ").append(type).append(' ').append(getter(f.name())).append("() {\n")
                    .append(INDENT).append(INDENT).append("return ").append(f.name()).append(";\n")
                    .append(INDENT).append("}\n\n")
                    .append(INDENT).append("public void ").append(setter(f.name())).append('(').append(type).append(' ')
                    .append(f.name()).append(") {\n")
                    .append(INDENT).append(INDENT).append("this.").append(f.name()).append(" = ").append(f.name()).append(";\n")
                    .append(INDENT).append("}\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /** 필드 이름이 겹치면 그 이름, 없으면 null. */
    public static String duplicate(List<String> names) {
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (!seen.add(n)) {
                return n;
            }
        }
        return null;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
