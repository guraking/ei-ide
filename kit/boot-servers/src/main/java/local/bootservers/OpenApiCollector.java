package local.bootservers;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import local.bootservers.OpenApiSpec.Op;
import local.bootservers.OpenApiSpec.Param;
import local.bootservers.OpenApiSpec.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로젝트의 스프링 요청 매핑(UrlMappingAction.collect)을 OpenAPI 문서로 옮긴다.
 * 요청 값은 메서드 매개변수의 스프링 주석(@PathVariable · @RequestParam · @RequestHeader · @RequestBody)으로 정하고,
 * 주석 없는 매개변수는 스프링 바인딩과 같게 단순 형식이면 쿼리 값, 객체면 그 필드들을 쿼리 값(한 단계)으로 본다.
 */
final class OpenApiCollector {

    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final String PATH_VARIABLE = WEB + "PathVariable";
    private static final String REQUEST_PARAM = WEB + "RequestParam";
    private static final String REQUEST_HEADER = WEB + "RequestHeader";
    private static final String REQUEST_BODY = WEB + "RequestBody";
    private static final String MODEL_ATTRIBUTE = WEB + "ModelAttribute";
    private static final String SPRING_PACKAGE = "org.springframework.";
    private static final String JDK_PACKAGE = "java.";
    /** 주석 없이 받아도 요청 값이 아닌 형식(서블릿 · 스프링 내부 객체, JDK 객체). */
    private static final String[] FRAMEWORK_PACKAGES = {"javax.servlet.", "jakarta.servlet.", SPRING_PACKAGE, JDK_PACKAGE};
    private static final String JAVA_TIME_PACKAGE = "java.time.";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_REQUIRED = "required";
    private static final String ATTR_DEFAULT_VALUE = "defaultValue";
    private static final String FORMAT_INT64 = "int64";
    private static final String FORMAT_DATE = "date";
    private static final String FORMAT_DATE_TIME = "date-time";

    /** 정규 형식 이름 → 단순 값 모양. 원시 형식은 getCanonicalText, 클래스는 정규 이름. */
    private static final Map<String, Schema> SIMPLE_TYPES = new LinkedHashMap<>();

    static {
        Schema string = Schema.of(OpenApiSpec.TYPE_STRING);
        Schema integer = Schema.of(OpenApiSpec.TYPE_INTEGER);
        Schema int64 = Schema.of(OpenApiSpec.TYPE_INTEGER, FORMAT_INT64);
        Schema number = Schema.of(OpenApiSpec.TYPE_NUMBER);
        Schema bool = Schema.of(OpenApiSpec.TYPE_BOOLEAN);
        Schema dateTime = Schema.of(OpenApiSpec.TYPE_STRING, FORMAT_DATE_TIME);
        for (String name : new String[]{"java.lang.String", "char", "java.lang.Character"}) {
            SIMPLE_TYPES.put(name, string);
        }
        for (String name : new String[]{"int", "short", "byte", "java.lang.Integer", "java.lang.Short", "java.lang.Byte",
                "java.math.BigInteger"}) {
            SIMPLE_TYPES.put(name, integer);
        }
        SIMPLE_TYPES.put("long", int64);
        SIMPLE_TYPES.put("java.lang.Long", int64);
        for (String name : new String[]{"double", "float", "java.lang.Double", "java.lang.Float", "java.math.BigDecimal"}) {
            SIMPLE_TYPES.put(name, number);
        }
        SIMPLE_TYPES.put("boolean", bool);
        SIMPLE_TYPES.put("java.lang.Boolean", bool);
        SIMPLE_TYPES.put("java.time.LocalDate", Schema.of(OpenApiSpec.TYPE_STRING, FORMAT_DATE));
        for (String name : new String[]{"java.time.LocalDateTime", "java.util.Date", "java.time.ZonedDateTime",
                "java.time.OffsetDateTime", "java.time.Instant"}) {
            SIMPLE_TYPES.put(name, dateTime);
        }
    }

    private OpenApiCollector() {
    }

    /**
     * 읽기 동작 안에서 부른다. 스프링 웹 의존성이 없으면 null.
     * 코드가 바뀌어 메서드를 잃은 매핑은 건너뛴다.
     */
    static String specJson(Project project, String title, String serverUrl) {
        List<UrlMappingAction.Endpoint> endpoints = UrlMappingAction.collect(project);
        if (endpoints == null) {
            return null;
        }
        Map<String, Map<String, Schema>> components = new LinkedHashMap<>();
        List<Op> ops = new ArrayList<>();
        for (UrlMappingAction.Endpoint endpoint : endpoints) {
            ProgressManager.checkCanceled();
            PsiMethod method = endpoint.target().getElement();
            PsiClass owner = method == null ? null : method.getContainingClass();
            if (owner == null) {
                continue;
            }
            List<Param> params = new ArrayList<>();
            Schema body = null;
            for (PsiParameter parameter : method.getParameterList().getParameters()) {
                ProgressManager.checkCanceled();
                if (AnnotationUtil.findAnnotation(parameter, REQUEST_BODY) != null) {
                    body = schema(parameter.getType(), components);
                } else {
                    addParams(parameter, params, components);
                }
            }
            for (String httpMethod : OpenApiSpec.methods(endpoint.httpMethod())) {
                ops.add(new Op(httpMethod, endpoint.url(), method.getName(), owner.getName(), params, body));
            }
        }
        return OpenApiSpec.toJson(title, serverUrl, ops, components);
    }

    /** @RequestBody 가 아닌 매개변수 하나를 요청 값으로 옮긴다. 요청 값이 아닌 매개변수는 넣지 않는다. */
    private static void addParams(PsiParameter parameter, List<Param> params, Map<String, Map<String, Schema>> components) {
        PsiType type = parameter.getType();
        String[][] annotated = {{PATH_VARIABLE, OpenApiSpec.IN_PATH}, {REQUEST_PARAM, OpenApiSpec.IN_QUERY},
                {REQUEST_HEADER, OpenApiSpec.IN_HEADER}};
        for (String[] kind : annotated) {
            PsiAnnotation annotation = AnnotationUtil.findAnnotation(parameter, kind[0]);
            if (annotation != null) {
                params.add(new Param(paramName(annotation, parameter), kind[1], schema(type, components), required(annotation)));
                return;
            }
        }
        // @CookieValue · @RequestPart · @AuthenticationPrincipal 등 다른 스프링 주석은 요청 값으로 옮기지 않는다.
        for (PsiAnnotation annotation : parameter.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if (name != null && name.startsWith(SPRING_PACKAGE) && !MODEL_ATTRIBUTE.equals(name)) {
                return;
            }
        }
        Schema simple = simple(type);
        if (simple != null) {
            params.add(new Param(parameter.getName(), OpenApiSpec.IN_QUERY, simple, false));
            return;
        }
        PsiClass model = modelClass(type);
        if (model == null) {
            return;
        }
        for (PsiField field : model.getAllFields()) {
            ProgressManager.checkCanceled();
            Schema fieldSchema = field.hasModifierProperty(PsiModifier.STATIC) ? null : simple(field.getType());
            if (fieldSchema != null) {
                params.add(new Param(field.getName(), OpenApiSpec.IN_QUERY, fieldSchema, false));
            }
        }
    }

    /** value · name 속성, 둘 다 없으면 자바 매개변수 이름. */
    private static String paramName(PsiAnnotation annotation, PsiParameter parameter) {
        for (String attribute : new String[]{ATTR_VALUE, ATTR_NAME}) {
            String value = AnnotationUtil.getDeclaredStringAttributeValue(annotation, attribute);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return parameter.getName();
    }

    /** 스프링과 같게 defaultValue 가 있으면 선택, 아니면 required 속성(기본 true). */
    private static boolean required(PsiAnnotation annotation) {
        if (annotation.findDeclaredAttributeValue(ATTR_DEFAULT_VALUE) != null) {
            return false;
        }
        return !Boolean.FALSE.equals(AnnotationUtil.getBooleanAttributeValue(annotation, ATTR_REQUIRED));
    }

    /** 문자열 · 숫자 · 참거짓 · 날짜 · 열거형이면 그 모양, 아니면 null. */
    private static Schema simple(PsiType type) {
        if (type instanceof PsiPrimitiveType) {
            return SIMPLE_TYPES.get(type.getCanonicalText());
        }
        PsiClass c = PsiUtil.resolveClassInClassTypeOnly(type);
        if (c == null || c instanceof PsiTypeParameter) {
            return null;
        }
        if (c.isEnum()) {
            return Schema.of(OpenApiSpec.TYPE_STRING);
        }
        String name = c.getQualifiedName();
        if (name == null) {
            return null;
        }
        Schema known = SIMPLE_TYPES.get(name);
        if (known == null && name.startsWith(JAVA_TIME_PACKAGE)) {
            return Schema.of(OpenApiSpec.TYPE_STRING);
        }
        return known;
    }

    /** 주석 없는 객체 매개변수로 받을 사용자 클래스. 프레임워크 · JDK 형식 · 배열이면 null. */
    private static PsiClass modelClass(PsiType type) {
        PsiClass c = PsiUtil.resolveClassInClassTypeOnly(type);
        if (c == null || c instanceof PsiTypeParameter || c.getQualifiedName() == null) {
            return null;
        }
        for (String prefix : FRAMEWORK_PACKAGES) {
            if (c.getQualifiedName().startsWith(prefix)) {
                return null;
            }
        }
        return c;
    }

    /**
     * 형식의 값 모양. 배열 · Collection 은 항목 모양의 배열, Map · JDK 객체 · 형식 변수는 자유 객체,
     * 그 밖의 클래스는 components 에 필드(상위 클래스 포함, static 제외)를 올리고 참조로 낸다.
     * 먼저 이름을 올려 두므로 서로 참조하는 클래스도 끝난다.
     * shortcut: 패키지가 달라도 단순 이름이 같으면 먼저 본 클래스 하나로 합친다, 겹치는 프로젝트가 나오면 정규 이름으로 가린다.
     */
    private static Schema schema(PsiType type, Map<String, Map<String, Schema>> components) {
        ProgressManager.checkCanceled();
        if (type instanceof PsiArrayType array) {
            return Schema.array(schema(array.getComponentType(), components));
        }
        Schema simple = simple(type);
        if (simple != null) {
            return simple;
        }
        PsiClass c = PsiUtil.resolveClassInClassTypeOnly(type);
        if (c == null || c instanceof PsiTypeParameter || c.getQualifiedName() == null) {
            return Schema.object();
        }
        if (InheritanceUtil.isInheritor(c, CommonClassNames.JAVA_UTIL_COLLECTION)) {
            PsiType item = type instanceof PsiClassType
                    ? PsiUtil.substituteTypeParameter(type, CommonClassNames.JAVA_UTIL_COLLECTION, 0, false) : null;
            return Schema.array(item == null ? Schema.object() : schema(item, components));
        }
        if (c.getQualifiedName().startsWith(JDK_PACKAGE)) {
            return Schema.object();
        }
        String name = c.getName();
        if (!components.containsKey(name)) {
            Map<String, Schema> properties = new LinkedHashMap<>();
            components.put(name, properties);
            for (PsiField field : c.getAllFields()) {
                if (!field.hasModifierProperty(PsiModifier.STATIC)) {
                    properties.put(field.getName(), schema(field.getType(), components));
                }
            }
        }
        return Schema.ref(name);
    }
}
