package local.bootservers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 콘솔 글을 줄 단위로 레벨 판정 · 묶기 · 거르기 한다. IntelliJ 에 기대지 않는다.
 * 레벨이 붙은 줄이 한 묶음을 시작하고, 레벨 없는 뒤 줄(여러 줄 메시지 · 스택 줄)은 그 묶음에 붙는다.
 * 거르기는 묶음 단위다: 묶음의 레벨이 골라져 있고 묶음 안 한 줄이라도 검색어에 맞으면 묶음 전체를 낸다.
 */
public final class LogFilter {

    public enum Level { ERROR, WARN, INFO, DEBUG }

    /** number 는 문서 줄 번호(0부터), level 은 줄이 속한 묶음의 레벨(첫 레벨 줄 앞의 글은 null). */
    public record Line(int number, String text, Level level) {}

    /** 대문자 레벨 낱말. 앞뒤가 글자 · 숫자 · 밑줄이 아니어야 한다(「 ERROR 」, 「[ERROR]」, 「ERROR:」). */
    private static final Pattern LEVEL = Pattern.compile(
            "(?<![A-Za-z0-9_])(FATAL|SEVERE|ERROR|WARNING|WARN|INFO|DEBUG|TRACE)(?![A-Za-z0-9_])");
    /** 들여쓰기 없는 예외 머리 줄(「java.lang.OutOfMemoryError」 처럼 Exception 이 없는 것까지). */
    private static final Pattern EXCEPTION_HEAD = Pattern.compile("[\\w$]+(\\.[\\w$]+)+(Error|Exception)\\b");

    private LogFilter() {}

    /** 줄에서 처음 나온 레벨 낱말. 없으면 null. */
    public static Level levelOf(String line) {
        Matcher m = LEVEL.matcher(line);
        if (!m.find()) {
            return null;
        }
        return switch (m.group(1)) {
            case "FATAL", "SEVERE", "ERROR" -> Level.ERROR;
            case "WARNING", "WARN" -> Level.WARN;
            case "INFO" -> Level.INFO;
            default -> Level.DEBUG;
        };
    }

    /** 예외 머리 줄: 레벨 없이 들여쓰기 없이 시작하는 예외 이름 줄. */
    static boolean isExceptionHead(String line) {
        return line.contains("Exception") && !line.startsWith("\t") && !line.startsWith(" ")
                && !line.startsWith("Caused by:")
                || EXCEPTION_HEAD.matcher(line).lookingAt();
    }

    /** 스택 줄: 「\tat 」 · 「\t... n more」 · 「Caused by:」 · 「Suppressed:」 · 예외 머리 줄. */
    public static boolean isStackLine(String line) {
        return line.startsWith("\tat ") || line.startsWith("\t... ") || line.startsWith("Caused by:")
                || line.trim().startsWith("Suppressed:") || isExceptionHead(line);
    }

    /**
     * 글 전체를 거른다. query 가 비면 검색어 조건은 없다. 대소문자는 무시한다.
     * 레벨이 없는 첫 묶음(시작 그림 등)은 네 레벨이 모두 골라졌을 때만 낸다.
     *
     * @throws java.util.regex.PatternSyntaxException regex 가 참이고 query 가 잘못된 정규식일 때
     */
    public static List<Line> filter(CharSequence text, Set<Level> levels, String query, boolean regex) {
        Pattern pattern = query.isEmpty() || !regex ? null : Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        String needle = query.toLowerCase(Locale.ROOT);
        List<Line> out = new ArrayList<>();
        List<Line> group = new ArrayList<>();
        Level groupLevel = null;
        String[] lines = text.toString().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Level own = levelOf(line);
            Level next = own != null ? own : continuationLevel(line, groupLevel);
            if (own != null || next != groupLevel) {
                emit(group, groupLevel, levels, needle, pattern, out);
                group.clear();
                groupLevel = next;
            }
            group.add(new Line(i, line, groupLevel));
        }
        emit(group, groupLevel, levels, needle, pattern, out);
        return out;
    }

    /**
     * 레벨 없는 줄이 붙을 묶음 레벨. 지금 묶음 레벨을 그대로 돌려주면 이어 붙는다.
     * 예외 머리 줄은 ERROR · WARN 묶음 뒤면 그 묶음에 붙고, 그 밖이면 새 ERROR 묶음을 연다(printStackTrace 출력).
     */
    private static Level continuationLevel(String line, Level groupLevel) {
        boolean stack = isStackLine(line);
        if (!stack) {
            return groupLevel;
        }
        if (groupLevel == null) {
            return Level.ERROR;
        }
        if (isExceptionHead(line) && groupLevel != Level.ERROR && groupLevel != Level.WARN) {
            return Level.ERROR;
        }
        return groupLevel;
    }

    private static void emit(List<Line> group, Level level, Set<Level> levels, String needle, Pattern pattern, List<Line> out) {
        if (group.isEmpty()) {
            return;
        }
        boolean levelOk = level == null ? levels.size() == Level.values().length : levels.contains(level);
        if (levelOk && anyMatch(group, needle, pattern)) {
            out.addAll(group);
        }
    }

    private static boolean anyMatch(List<Line> group, String needle, Pattern pattern) {
        if (needle.isEmpty()) {
            return true;
        }
        for (Line l : group) {
            boolean hit = pattern != null ? pattern.matcher(l.text()).find() : l.text().toLowerCase(Locale.ROOT).contains(needle);
            if (hit) {
                return true;
            }
        }
        return false;
    }
}
