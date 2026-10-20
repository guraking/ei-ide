package local.bootservers.code;

import java.util.List;
import java.util.Set;

import local.bootservers.code.TestSkeleton.Field;
import local.bootservers.code.TestSkeleton.Kind;
import local.bootservers.code.TestSkeleton.Method;

/** TestSkeleton 자체 점검. 실패하면 예외로 끝난다. */
public final class TestSkeletonCheck {

    public static void main(String[] args) {
        mapperJudge();
        names();
        defaults();
        mockFile();
        mapperFile();
        missing();
        System.out.println("TestSkeletonCheck 통과");
    }

    private static void mapperJudge() {
        eq(true, TestSkeleton.isMapper(true, Set.of("org.apache.ibatis.annotations.Mapper"), "UserRepo"), "@Mapper");
        eq(true, TestSkeleton.isMapper(true, Set.of(), "UserMapper"), "이름 Mapper");
        eq(true, TestSkeleton.isMapper(true, Set.of(), "UserDao"), "이름 Dao");
        eq(false, TestSkeleton.isMapper(false, Set.of(), "UserMapper"), "클래스는 매퍼 아님");
        eq(false, TestSkeleton.isMapper(true, Set.of(), "UserService"), "그 밖의 인터페이스");
    }

    private static void names() {
        List<Method> methods = List.of(m("find"), m("save"), m("find", "int"), m("find", "java.lang.String"));
        eq(List.of("find", "save", "find_2", "find_3"), TestSkeleton.testNames(methods), "오버로드 이름");
        eq("userMapper", TestSkeleton.mapperField("UserMapper"), "매퍼 필드 이름");
    }

    private static void defaults() {
        eq("0", TestSkeleton.defaultValue("int", false), "int");
        eq("false", TestSkeleton.defaultValue("boolean", true), "boolean");
        eq("(byte) 0", TestSkeleton.defaultValue("byte", false), "byte");
        eq("null", TestSkeleton.defaultValue("java.lang.String", false), "참조형");
        eq("(java.lang.String) null", TestSkeleton.defaultValue("java.lang.String", true), "오버로드 참조형");
    }

    private static void mockFile() {
        String expected = """
                package com.acme.user;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;

                @ExtendWith(MockitoExtension.class)
                class UserServiceTest {

                    @Mock
                    private com.acme.user.UserMapper userMapper;

                    @InjectMocks
                    private UserService target;

                    @Test
                    void find() {
                        // given

                        // when
                        target.find((java.lang.String) null);

                        // then
                    }

                    @Test
                    void find_2() throws Exception {
                        // given

                        // when
                        target.find((java.lang.String) null, 0L);

                        // then
                    }

                    @Test
                    void clear() {
                        // given

                        // when
                        target.clear();

                        // then
                    }
                }
                """;
        List<Method> methods = List.of(m("find", "java.lang.String"),
                new Method("find", List.of("java.lang.String", "long"), true), m("clear"));
        String actual = TestSkeleton.file("com.acme.user", "UserService", Kind.MOCK,
                List.of(new Field("com.acme.user.UserMapper", "userMapper")), methods);
        eq(expected, actual, "Mockito 파일");
    }

    private static void mapperFile() {
        String expected = """
                import org.junit.jupiter.api.Test;
                import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
                import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
                import org.springframework.beans.factory.annotation.Autowired;

                @MybatisTest
                @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
                class UserMapperTest {

                    @Autowired
                    private UserMapper userMapper;

                    @Test
                    void selectUser() {
                        // given

                        // when

                        // then
                    }
                }
                """;
        String actual = TestSkeleton.file("", "UserMapper", Kind.MAPPER, List.of(),
                List.of(new Method("selectUser", List.of("java.lang.String"), true)));
        eq(expected, actual, "매퍼 파일(기본 패키지)");
    }

    private static void missing() {
        List<Method> methods = List.of(m("find"), m("find", "int"), m("save", "boolean"));
        List<String> added = TestSkeleton.missingMethods(Kind.MOCK, methods, Set.of("find", "other"));
        eq(2, added.size(), "없는 것만");
        eq(true, added.get(0).startsWith("    @org.junit.jupiter.api.Test\n    void find_2() {"), "FQN @Test");
        eq(true, added.get(0).contains("target.find(0);"), "오버로드 원시형은 형 없음");
        eq(true, added.get(1).contains("target.save(false);"), "boolean 기본값");
        eq(0, TestSkeleton.missingMethods(Kind.MOCK, methods, Set.of("find", "find_2", "save")).size(), "더할 것 없음");
    }

    private static Method m(String name, String... params) {
        return new Method(name, List.of(params), false);
    }

    private static void eq(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
