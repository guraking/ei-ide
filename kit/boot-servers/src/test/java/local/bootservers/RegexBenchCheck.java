package local.bootservers;

import local.bootservers.RegexBench.Flag;
import local.bootservers.RegexBench.Flavor;
import local.bootservers.RegexBench.Kind;
import local.bootservers.RegexBench.Preset;
import local.bootservers.RegexBench.Problem;
import local.bootservers.RegexBench.Repeat;
import local.bootservers.RegexBench.Result;

import java.util.EnumSet;
import java.util.Set;

/** RegexBench 자체 점검. 실패하면 예외로 끝난다. */
public final class RegexBenchCheck {

    private static final long TIMEOUT_MS = 1000;
    private static final long SHORT_TIMEOUT_MS = 200;

    public static void main(String[] args) {
        javascript();
        java();
        problems();
        pieces();
        code();
        presets();
        System.out.println("RegexBenchCheck 통과");
    }

    private static void javascript() {
        Set<Flag> none = EnumSet.noneOf(Flag.class);
        Set<Flag> global = EnumSet.of(Flag.JS_GLOBAL);
        eq(1, js("a", none, "aaa", "").matches().size(), "g 없으면 첫 일치만");
        eq(3, js("a", global, "aaa", "").matches().size(), "g 면 전부");
        eq("baa", js("a", none, "aaa", "b").replaced(), "g 없으면 첫 일치만 바꿈");
        eq("a\nb", js("a[^]b", none, "a\nb", "").matches().get(0).groups().get(0).value(), "[^] 는 줄바꿈도");
        eq(0, js("a[]", global, "a[]", "").matches().size(), "[] 는 아무것도 아님");
        eq("{name}", js("\\{?{(\\w+)}", none, "x {name}", "").matches().get(0).groups().get(0).value(), "반복 아닌 { 는 글자");
        eq(1, js("[[]", global, "a[b", "").matches().size(), "묶음 안 [ 는 글자");
        eq(3, js("[a&&b]", global, "a&b", "").matches().size(), "묶음 안 && 는 글자");
        eq(1, js("\\u{1F600}", EnumSet.of(Flag.JS_UNICODE), "x\uD83D\uDE00", "").matches().size(), "u 의 \\u{…}");
        String spaces = "a\u00A0b\uFEFFc\u3000d";
        eq(3, js("\\s", global, spaces, "").matches().size(), "JS \\s 는 유니코드 공백도");
        eq(0, run(Flavor.JAVA, "\\s", none, spaces, "").matches().size(), "Java \\s 는 ASCII 공백만");
        eq(4, js("\\S", global, spaces, "").matches().size(), "JS \\S 는 유니코드 공백 빼고");
        eq(2, js("[^\\S\n]", global, " \u00A0\n", "").matches().size(), "JS 줄바꿈 뺀 공백 [^\\S\\n]");
        eq(3, js("[\\s,]", global, ",\u00A0 x", "").matches().size(), "JS 묶음 안 \\s");
        eq(1, js("[x\\S]", global, "\u00A0y", "").matches().size(), "JS 묶음 안 \\S");
        eq(1, js("é", EnumSet.of(Flag.JS_IGNORE_CASE), "É", "").matches().size(), "i 는 ASCII 밖도");
        eq("2026.10.31", js("(?<y>\\d{4})-(\\d\\d)-(\\d\\d)", none, "2026-10-31", "$<y>.$2.$3").replaced(), "$<이름> · $n");
        eq("[x]$-", js("x", none, "x", "[$&]$$-").replaced(), "$& · $$");
        eq("aa|c|c", js("b", none, "abc", "$`|$'|").replaced(), "$` · $'");
        eq("$9", js("x", none, "x", "$9").replaced(), "없는 그룹 번호는 글자 그대로");
        eq("ab", js("(a)(b)?", none, "a", "$1$2b").replaced(), "끼지 않은 그룹은 빈 글");
        eq("", js("(?<n>a)", none, "a", "$<m>").replaced(), "없는 이름은 빈 글");
        Result named = js("(?<year>\\d{4})", none, "2026", "");
        eq("year", named.matches().get(0).groups().get(1).name(), "그룹 이름");
        eq(-1, js("(a)|b", none, "b", "").matches().get(0).groups().get(1).start(), "끼지 않은 그룹 위치");
    }

    private static void java() {
        Set<Flag> none = EnumSet.noneOf(Flag.class);
        eq(3, run(Flavor.JAVA, "a", none, "aaa", "").matches().size(), "Java 는 늘 전부");
        eq("2026.10", run(Flavor.JAVA, "(?<y>\\d{4})-(\\d\\d)", none, "2026-10", "${y}.$2").replaced(), "${이름} · $n");
        eq(1, run(Flavor.JAVA, "^b", EnumSet.of(Flag.MULTILINE), "a\nb", "").matches().size(), "MULTILINE");
        eq(0, run(Flavor.JAVA, "^b", EnumSet.of(Flag.JS_MULTILINE), "a\nb", "").matches().size(), "다른 문법 플래그는 무시");
        eq(1, run(Flavor.JAVA, "a b # 주석", EnumSet.of(Flag.COMMENTS), "ab", "").matches().size(), "COMMENTS");
    }

    private static void problems() {
        Set<Flag> none = EnumSet.noneOf(Flag.class);
        Result syntax = run(Flavor.JAVA, "a(b", none, "ab", "");
        eq(Problem.SYNTAX, syntax.problem(), "닫지 않은 괄호");
        eq(3, syntax.errorIndex(), "오류 위치");
        eq(Problem.SYNTAX, run(Flavor.JAVA, "{x}", none, "", "").problem(), "Java 는 { 글자를 막음");
        Result moved = js("[^](", none, "", "");
        eq(Problem.SYNTAX, moved.problem(), "JS 닫지 않은 괄호");
        eq(-1, moved.errorIndex(), "옮긴 패턴은 위치를 주지 않음");
        Result replacement = run(Flavor.JAVA, "a", none, "a", "$9");
        eq(Problem.REPLACEMENT, replacement.problem(), "Java 없는 그룹");
        eq(1, replacement.matches().size(), "바꾸기 오류여도 일치는 보임");
        Result slow = RegexBench.run(Flavor.JAVA, "(.*a){20}$", none, "a".repeat(28) + "!", "", SHORT_TIMEOUT_MS);
        eq(Problem.TIMEOUT, slow.problem(), "되돌이 폭주는 끊음");
        Result many = run(Flavor.JAVA, "", none, "x".repeat(RegexBench.MAX_MATCHES + 5), "");
        eq(RegexBench.MAX_MATCHES, many.matches().size(), "일치 수 상한");
        eq(true, many.truncated(), "상한을 넘음");
    }

    private static void pieces() {
        eq("(?:a\\.b)+", RegexBench.piece(Kind.TEXT, "a.b", Repeat.ONE_OR_MORE, 0, 0, false, ""), "여러 글자 반복은 묶음");
        eq("a?", RegexBench.piece(Kind.TEXT, "a", Repeat.OPTIONAL, 0, 0, false, ""), "한 글자는 그대로");
        eq("(?<last>\\d{4})", RegexBench.piece(Kind.DIGIT, "", Repeat.EXACTLY, 4, 0, true, "last"), "이름 붙은 꺼내기");
        eq("(\\d{2,4})", RegexBench.piece(Kind.DIGIT, "", Repeat.RANGE, 2, 4, true, ""), "번호 꺼내기");
        eq("[가-힣]{2,}", RegexBench.piece(Kind.KOREAN, "", Repeat.AT_LEAST, 2, 0, false, ""), "N번 이상");
        eq("[\\-_]", RegexBench.piece(Kind.ONE_OF, "-_-", Repeat.ONCE, 0, 0, false, ""), "같은 글자 한 번 · - 이스케이프");
        eq("[^,\\]]*", RegexBench.piece(Kind.NONE_OF, ",]", Repeat.ZERO_OR_MORE, 0, 0, false, ""), "이 글자 빼고");
        eq("^", RegexBench.piece(Kind.LINE_START, "", Repeat.ONE_OR_MORE, 0, 0, true, "x"), "줄 처음은 반복 · 꺼내기 없음");
        String built = RegexBench.piece(Kind.LINE_START, "", Repeat.ONCE, 0, 0, false, "")
                + RegexBench.piece(Kind.TEXT, "a/b+", Repeat.ONCE, 0, 0, false, "")
                + RegexBench.piece(Kind.ONE_OF, "&&", Repeat.ONCE, 0, 0, false, "");
        for (Flavor flavor : Flavor.values()) {
            eq(1, run(flavor, built, EnumSet.noneOf(Flag.class), "a/b+&", "").matches().size(), flavor + " 에서 만든 조각이 맞음");
        }
        eq(false, RegexBench.GROUP_NAME.matcher("1abc").matches(), "숫자로 시작하는 이름");
    }

    private static void code() {
        eq("${y}.$1", RegexBench.convertReplacement(Flavor.JAVA, "$<y>.$1"), "JS → Java 이름 표기");
        eq("$<y>.$1", RegexBench.convertReplacement(Flavor.JAVASCRIPT, "${y}.$1"), "Java → JS 이름 표기");
        eq("/a\\/b\\//gm", RegexBench.toCode(Flavor.JAVASCRIPT, "a/b\\/", EnumSet.of(Flag.JS_GLOBAL, Flag.JS_MULTILINE)), "JS / 이스케이프");
        eq("/(?:)/", RegexBench.toCode(Flavor.JAVASCRIPT, "", EnumSet.noneOf(Flag.class)), "빈 JS 패턴");
        eq("Pattern.compile(\"\\\\d\\\"\", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)",
                RegexBench.toCode(Flavor.JAVA, "\\d\"", EnumSet.of(Flag.MULTILINE, Flag.CASE_INSENSITIVE, Flag.JS_GLOBAL)), "Java 문자열");
        eq("Pattern.compile(\"a\")", RegexBench.toCode(Flavor.JAVA, "a", EnumSet.noneOf(Flag.class)), "Java 플래그 없음");
    }

    private static void presets() {
        eq(1, RegexBench.parsePresets("# 주석\n\na\tb\tg\t\tx\\ny\n").size(), "주석 · 빈 줄 건너뜀");
        eq("x\ny", RegexBench.parsePresets("a\tb\tg\t\tx\\ny").get(0).sample(), "예시 줄바꿈");
        try {
            RegexBench.parsePresets("a\tb");
            throw new AssertionError("칸 수가 틀린 줄을 받음");
        } catch (IllegalArgumentException expected) {
            // 칸 수 오류
        }
        var list = RegexBench.loadPresets();
        if (list.isEmpty()) {
            throw new AssertionError("자주 쓰는 정규식이 비었음");
        }
        for (Preset preset : list) {
            for (Flavor flavor : Flavor.values()) {
                Result result = run(flavor, preset.pattern(), RegexBench.flagsOf(preset.flags()), preset.sample(), RegexBench.convertReplacement(flavor, preset.replacement()));
                eq(Problem.NONE, result.problem(), preset.name() + " · " + flavor + " 오류 " + result.detail());
                if (result.matches().isEmpty()) {
                    throw new AssertionError(preset.name() + " · " + flavor + ": 예시 글에 일치가 없음");
                }
            }
            eq(run(Flavor.JAVASCRIPT, preset.pattern(), RegexBench.flagsOf(preset.flags()), preset.sample(), preset.replacement()).replaced(),
                    run(Flavor.JAVA, preset.pattern(), RegexBench.flagsOf(preset.flags()), preset.sample(),
                            RegexBench.convertReplacement(Flavor.JAVA, preset.replacement())).replaced(), preset.name() + ": 두 문법 바꾸기 결과가 같음");
            Result jsResult = run(Flavor.JAVASCRIPT, preset.pattern(), RegexBench.flagsOf(preset.flags()), preset.sample(), "");
            Result javaResult = run(Flavor.JAVA, preset.pattern(), RegexBench.flagsOf(preset.flags()), preset.sample(), "");
            eq(jsResult.matches().size(), javaResult.matches().size(), preset.name() + ": 두 문법 일치 수가 같음");
        }
    }

    private static Result js(String pattern, Set<Flag> flags, String text, String replacement) {
        return run(Flavor.JAVASCRIPT, pattern, flags, text, replacement);
    }

    private static Result run(Flavor flavor, String pattern, Set<Flag> flags, String text, String replacement) {
        return RegexBench.run(flavor, pattern, flags, text, replacement, TIMEOUT_MS);
    }

    private static void eq(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
