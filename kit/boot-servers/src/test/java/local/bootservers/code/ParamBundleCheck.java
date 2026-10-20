package local.bootservers.code;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** ParamBundle 자체 점검. 실패하면 예외로 끝난다. */
public final class ParamBundleCheck {

    private static final String STRING = "java.lang.String";
    private static final String INTEGER = "java.lang.Integer";
    private static final String LOCAL_DATE = "java.time.LocalDate";

    public static void main(String[] args) {
        names();
        initializers();
        dto();
        edges();
        System.out.println("ParamBundleCheck 통과");
    }

    private static ParamBundle.Param p(String name, String type, String requestName, String defaultValue) {
        return new ParamBundle.Param(name, type, requestName, defaultValue, List.of());
    }

    private static void names() {
        eq("SearchListRequest", ParamBundle.className("searchList"), "클래스 이름");
        eq("ListRequest", ParamBundle.className("list"), "한 낱말 메서드");
        eq("userId", ParamBundle.defaultFieldName(p("id", STRING, "user_id", null)), "스네이크 요청 이름");
        eq("userName", ParamBundle.defaultFieldName(p("n", STRING, "user-name", null)), "하이픈 요청 이름");
        eq("searchKeyword", ParamBundle.defaultFieldName(p("k", STRING, "search.keyword", null)), "점 요청 이름");
        eq("pageNo", ParamBundle.defaultFieldName(p("pageNo", INTEGER, "pageNo", null)), "카멜 그대로");
        eq("page", ParamBundle.defaultFieldName(p("page", INTEGER, null, null)), "요청 이름 없음");
        eq("p1", ParamBundle.defaultFieldName(p("p1", INTEGER, "1st", null)), "자바 이름 안 되면 매개변수 이름");
        eq("req", ParamBundle.variableName(Set.of("id")), "변수 이름");
        eq("req3", ParamBundle.variableName(Set.of("req", "req2")), "변수 이름 겹침");
        eq("getUserId", ParamBundle.getter("userId"), "getter");
        eq("req.getUserId()", ParamBundle.access("req", "userId"), "참조 식");
        eq("@org.springframework.web.bind.annotation.ModelAttribute a.b.ListRequest req",
                ParamBundle.parameter("a.b.ListRequest", "req"), "매개변수 글");
        List<ParamBundle.Field> fields = List.of(
                new ParamBundle.Field("userId", p("id", STRING, "user_id", null)),
                new ParamBundle.Field("page", p("page", INTEGER, "page", null)),
                new ParamBundle.Field("q", p("q", STRING, null, null)));
        eq(List.of("user_id → userId"), ParamBundle.nameWarnings(fields), "이름 다름 경고");
        eq("a", ParamBundle.duplicate(List.of("a", "b", "a")), "겹친 이름");
        eq(null, ParamBundle.duplicate(List.of("a", "b")), "안 겹침");
        check(ParamBundle.isSimpleType("int") && ParamBundle.isSimpleType(LOCAL_DATE)
                && !ParamBundle.isSimpleType("javax.servlet.http.HttpServletRequest"), "단순형");
        check(!ParamBundle.isValidName("class") && !ParamBundle.isValidName("") && !ParamBundle.isValidName("a.b")
                && ParamBundle.isValidName("userId"), "자바 이름");
    }

    private static void initializers() {
        eq("\"a\\\"b\"", ParamBundle.initializer(STRING, "a\"b"), "문자열 따옴표");
        eq("\"\"", ParamBundle.initializer(STRING, ""), "빈 문자열");
        eq("10", ParamBundle.initializer("int", " 10 "), "int");
        eq("10", ParamBundle.initializer(INTEGER, "10"), "Integer");
        eq(null, ParamBundle.initializer(INTEGER, "ten"), "Integer 아닌 글");
        eq("5L", ParamBundle.initializer("java.lang.Long", "5"), "Long");
        eq("1.5D", ParamBundle.initializer("double", "1.5"), "double");
        eq("true", ParamBundle.initializer("java.lang.Boolean", "TRUE"), "Boolean");
        eq(null, ParamBundle.initializer("boolean", "Y"), "boolean 아닌 글");
        eq("java.time.LocalDate.parse(\"2024-01-01\")", ParamBundle.initializer(LOCAL_DATE, "2024-01-01"), "LocalDate");
        eq("new java.math.BigDecimal(\"0.1\")", ParamBundle.initializer("java.math.BigDecimal", "0.1"), "BigDecimal");
        eq(null, ParamBundle.initializer("com.x.Kind", "A"), "모르는 형");
        eq(null, ParamBundle.initializer(STRING, null), "기본값 없음");
    }

    private static void dto() {
        List<ParamBundle.Field> fields = List.of(
                new ParamBundle.Field("from", new ParamBundle.Param("from", LOCAL_DATE, null, null,
                        List.of("@org.springframework.format.annotation.DateTimeFormat(pattern = \"yyyy-MM-dd\")"))),
                new ParamBundle.Field("page", p("page", "int", "page", "1")),
                new ParamBundle.Field("kind", p("kind", "com.x.Kind", null, "A")));
        String expected = """
                package com.x.web;

                public class ListRequest {

                    @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd")
                    private java.time.LocalDate from;
                    private int page = 1;
                    // 기본값 "A" 은 형에 맞게 직접 넣어야 합니다
                    private com.x.Kind kind;

                    public java.time.LocalDate getFrom() {
                        return from;
                    }

                    public void setFrom(java.time.LocalDate from) {
                        this.from = from;
                    }

                    public int getPage() {
                        return page;
                    }

                    public void setPage(int page) {
                        this.page = page;
                    }

                    public com.x.Kind getKind() {
                        return kind;
                    }

                    public void setKind(com.x.Kind kind) {
                        this.kind = kind;
                    }
                }
                """;
        eq(expected, ParamBundle.dto("com.x.web", "ListRequest", fields), "DTO 글");
        check(ParamBundle.dto("", "ListRequest", fields).startsWith("public class"), "기본 패키지");
    }

    private static void edges() {
        throwsIae(() -> ParamBundle.dto("a", "class", List.of(new ParamBundle.Field("a", p("a", STRING, null, null)))), "키워드 클래스 이름");
        throwsIae(() -> ParamBundle.dto("a", "A", List.of()), "필드 없음");
        throwsIae(() -> new ParamBundle.Field("1a", p("a", STRING, null, null)), "필드 이름");
        throwsIae(() -> p("", STRING, null, null), "빈 매개변수 이름");
        throwsIae(() -> ParamBundle.className(" "), "빈 메서드 이름");
    }

    private static void throwsIae(Runnable r, String what) {
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(what + ": IllegalArgumentException 이 나야 함");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + "\n기대: " + expected + "\n실제: " + actual);
        }
    }
}
