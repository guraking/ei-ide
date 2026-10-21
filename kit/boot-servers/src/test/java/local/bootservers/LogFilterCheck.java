package local.bootservers;

import local.bootservers.LogFilter.Level;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/** LogFilter 자체 점검. 실패하면 예외로 끝난다. */
public final class LogFilterCheck {

    private static final Set<Level> ALL = EnumSet.allOf(Level.class);

    private static final String LOG = String.join("\n",
            "  .   ____          _",                                                                      // 0 시작 그림
            "2026-10-17T10:00:00.000+09:00  INFO 1234 --- [main] o.s.b.App : Started",                    // 1
            "2026-10-17 10:00:01.000 DEBUG [main] o.h.SQL - select *",                                    // 2
            "    from TB_A",                                                                               // 3 이어지는 줄
            "[WARN] 느린 응답",                                                                             // 4
            "10:00:02 ERROR: 처리 실패",                                                                   // 5
            "java.lang.IllegalStateException: 상태 오류",                                                  // 6
            "\tat a.b.C.run(C.java:10)",                                                                   // 7
            "Caused by: java.io.IOException: 끊김",                                                        // 8
            "\t... 3 more",                                                                                // 9
            "2026-10-17 10:00:03 INFO  [main] 다시 시작",                                                  // 10
            "java.lang.OutOfMemoryError: Java heap space",                                                 // 11 INFO 뒤 예외 → 새 ERROR
            "\tat x.Y.z(Y.java:1)",                                                                         // 12
            "SEVERE: 톰캣 오류",                                                                             // 13
            "WARNING: 톰캣 경고",                                                                            // 14
            "2026-10-17 10:00:04 INFO  [main] ERROR_CODE 값 없음");                                         // 15

    public static void main(String[] args) {
        levelOf();
        stackLine();
        grouping();
        query();
        resume();
        System.out.println("LogFilterCheck 통과");
    }

    private static void levelOf() {
        eq(Level.ERROR, LogFilter.levelOf("12:00 ERROR 1 --- x"), "공백 ERROR");
        eq(Level.ERROR, LogFilter.levelOf("[ERROR] x"), "괄호 ERROR");
        eq(Level.ERROR, LogFilter.levelOf("ERROR: x"), "쌍점 ERROR");
        eq(Level.WARN, LogFilter.levelOf("12:00  WARN 1 --- x"), "WARN");
        eq(Level.DEBUG, LogFilter.levelOf("12:00 TRACE x"), "TRACE 는 DEBUG");
        eq(null, LogFilter.levelOf("ERRORS 없음 error 소문자"), "낱말 아님");
        eq(null, LogFilter.levelOf("MY_ERROR_CODE"), "밑줄 낀 낱말");
        eq(Level.INFO, LogFilter.levelOf("INFO x ERROR y"), "처음 것");
    }

    private static void stackLine() {
        yes(LogFilter.isStackLine("\tat a.B.c(B.java:1)"), "at 줄");
        yes(LogFilter.isStackLine("Caused by: x.Y"), "Caused by");
        yes(LogFilter.isStackLine("\t... 5 more"), "more 줄");
        yes(LogFilter.isStackLine("org.foo.BarException"), "Exception 머리");
        yes(LogFilter.isStackLine("java.lang.StackOverflowError"), "Error 머리");
        yes(!LogFilter.isStackLine("    from TB_A"), "일반 이어진 줄");
    }

    private static void grouping() {
        List<LogFilter.Line> all = LogFilter.filter(LOG, ALL, "", false);
        eq(16, all.size(), "모두 고르면 전체 줄");
        eq(null, all.get(0).level(), "시작 그림은 레벨 없음");
        eq(Level.DEBUG, all.get(3).level(), "이어진 줄은 앞 묶음 레벨");
        eq(Level.ERROR, all.get(9).level(), "스택 줄은 앞 ERROR 에");
        eq(Level.ERROR, all.get(11).level(), "INFO 뒤 예외 머리는 새 ERROR");
        eq(Level.INFO, all.get(15).level(), "ERROR_CODE 는 레벨 아님");

        eq(List.of(5, 6, 7, 8, 9, 11, 12, 13), numbers(LogFilter.filter(LOG, EnumSet.of(Level.ERROR), "", false)), "ERROR 만");
        eq(List.of(4, 14), numbers(LogFilter.filter(LOG, EnumSet.of(Level.WARN), "", false)), "WARN 만");
        eq(List.of(), numbers(LogFilter.filter(LOG, EnumSet.noneOf(Level.class), "", false)), "아무것도 안 고름");
        eq(List.of(1, 10, 15), numbers(LogFilter.filter(LOG, EnumSet.of(Level.INFO), "", false)), "INFO 만, 시작 그림 빠짐");
    }

    private static void query() {
        eq(List.of(5, 6, 7, 8, 9), numbers(LogFilter.filter(LOG, ALL, "ioexception", false)), "검색어는 대소문자 무시, 묶음 통째");
        eq(List.of(2, 3), numbers(LogFilter.filter(LOG, ALL, "tb_a", false)), "이어진 줄로 맞아도 묶음 통째");
        eq(List.of(), numbers(LogFilter.filter(LOG, ALL, "c\\.run", false)), "정규식 끄면 글자 그대로");
        eq(List.of(5, 6, 7, 8, 9), numbers(LogFilter.filter(LOG, ALL, "c\\.run\\(", true)), "정규식");
        eq(List.of(), numbers(LogFilter.filter("", ALL, "x", false)), "빈 글");
        try {
            LogFilter.filter(LOG, ALL, "(", true);
            throw new AssertionError("잘못된 정규식이 통과함");
        } catch (PatternSyntaxException expected) {
            // 창이 이 예외를 받아 상태 줄에 알린다.
        }
    }

    /** 글을 어디서 잘라 앞부분을 거른 뒤 이어 거르든, 전체를 한 번에 거른 것과 같아야 한다. */
    private static void resume() {
        List<Set<Level>> levelSets = List.of(ALL, EnumSet.of(Level.ERROR), EnumSet.of(Level.INFO));
        List<String> queries = List.of("", "tb_a", "ioexception");
        for (Set<Level> levels : levelSets) {
            for (String query : queries) {
                List<LogFilter.Line> whole = LogFilter.filter(LOG, levels, query, false);
                for (int cut = 0; cut <= LOG.length(); cut++) {
                    LogFilter.Result first = LogFilter.filterFrom(LOG.substring(0, cut), LogFilter.Resume.START, levels, query, false);
                    List<LogFilter.Line> joined = new ArrayList<>();
                    for (LogFilter.Line l : first.lines()) {
                        if (l.number() < first.resume().line()) {
                            joined.add(l);
                        }
                    }
                    joined.addAll(LogFilter.filterFrom(LOG, first.resume(), levels, query, false).lines());
                    eq(whole, joined, "이어 거르기 levels=" + levels + " query=" + query + " cut=" + cut);
                }
            }
        }
    }

    private static List<Integer> numbers(List<LogFilter.Line> lines) {
        List<Integer> out = new ArrayList<>();
        for (LogFilter.Line l : lines) {
            out.add(l.number());
        }
        return out;
    }

    private static void yes(boolean b, String what) {
        if (!b) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
