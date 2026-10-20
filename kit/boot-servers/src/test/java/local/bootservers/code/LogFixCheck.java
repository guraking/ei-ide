package local.bootservers.code;

import static local.bootservers.code.LogFix.Kind.EMPTY_CATCH;
import static local.bootservers.code.LogFix.Kind.ERR;
import static local.bootservers.code.LogFix.Kind.OUT;
import static local.bootservers.code.LogFix.Kind.PRINT_STACK_TRACE;

/** LogFix 자체 점검. 실패하면 예외로 끝난다. */
public final class LogFixCheck {

    public static void main(String[] args) {
        eq("log.error(\"save 실패\", e);", LogFix.statement(EMPTY_CATCH, "e", false, "log", "save"), "빈 catch");
        eq("LOGGER.error(\"load 실패\", ex);", LogFix.statement(PRINT_STACK_TRACE, "ex", false, "LOGGER", "load"), "printStackTrace");
        eq("log.info(\"시작\");", LogFix.statement(OUT, "\"시작\"", true, "log", "run"), "리터럴");
        eq("log.info(\"{}\", user);", LogFix.statement(OUT, "user", false, "log", "run"), "식");
        eq("log.info(\"{}\", \"a{}b\");", LogFix.statement(OUT, "\"a{}b\"", true, "log", "run"), "리터럴 안 {}");
        eq("log.error(\"{}\", \"x=\" + x);", LogFix.statement(ERR, "\"x=\" + x", false, "log", "run"), "System.err");
        eq("log.error(\"오류\");", LogFix.statement(ERR, "\"오류\"", true, "log", null), "err 리터럴, 메서드 없어도 됨");
        eq(null, LogFix.statement(OUT, "", false, "log", "run"), "인자 없음");
        eq(null, LogFix.statement(OUT, "x", false, null, "run"), "로거 없음");
        eq(null, LogFix.statement(EMPTY_CATCH, "e", false, "log", null), "메서드 이름 없음");
        System.out.println("LogFixCheck 통과");
    }

    private static void eq(Object expected, Object actual, String name) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
