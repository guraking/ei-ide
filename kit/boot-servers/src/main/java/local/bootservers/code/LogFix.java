package local.bootservers.code;

import java.util.Objects;

/**
 * 빈 catch · printStackTrace · System.out/err 출력을 로거 호출 문장으로 바꿀 글.
 * 바꿀 수 없으면(로거 없음, 메서드 이름 · 인자 없음) null.
 */
public final class LogFix {

    public enum Kind { EMPTY_CATCH, PRINT_STACK_TRACE, OUT, ERR }

    private static final String ERROR = "error";
    private static final String INFO = "info";
    private static final String PLACEHOLDER = "{}";
    private static final String FAILED = " 실패";

    private LogFix() {
    }

    /**
     * @param kind    검사 종류
     * @param arg     EMPTY_CATCH 는 catch 매개변수 이름, PRINT_STACK_TRACE 는 예외 식 글, OUT · ERR 는 출력 인자 글
     * @param literal OUT · ERR 인자가 문자열 리터럴인지
     * @param logger  로거 필드 이름
     * @param method  둘러싼 메서드 이름(EMPTY_CATCH · PRINT_STACK_TRACE 에만 쓴다)
     */
    public static String statement(Kind kind, String arg, boolean literal, String logger, String method) {
        Objects.requireNonNull(kind, "kind");
        if (isBlank(logger) || isBlank(arg)) {
            return null;
        }
        switch (kind) {
            case EMPTY_CATCH, PRINT_STACK_TRACE -> {
                if (isBlank(method)) {
                    return null;
                }
                return logger + "." + ERROR + "(\"" + method + FAILED + "\", " + arg + ");";
            }
            default -> {
                String level = kind == Kind.ERR ? ERROR : INFO;
                // 리터럴 안의 {} 는 자리표시로 읽히므로 리터럴도 인자로 넘긴다.
                if (literal && !arg.contains(PLACEHOLDER)) {
                    return logger + "." + level + "(" + arg + ");";
                }
                return logger + "." + level + "(\"" + PLACEHOLDER + "\", " + arg + ");";
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
