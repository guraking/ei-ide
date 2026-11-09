package local.bootservers;

import local.bootservers.RegexBench.Flag;
import local.bootservers.RegexBench.Flavor;
import local.bootservers.RegexBench.CharKind;
import local.bootservers.RegexBench.Bunch;
import local.bootservers.RegexBench.Part;
import local.bootservers.RegexBench.Need;
import local.bootservers.RegexBench.Piece;
import local.bootservers.RegexBench.Repeating;
import local.bootservers.RegexBench.Shape;
import local.bootservers.RegexBench.Source;
import local.bootservers.RegexBench.Verdict;
import local.bootservers.RegexBench.Preset;
import local.bootservers.RegexBench.Problem;
import local.bootservers.RegexBench.Repeat;
import local.bootservers.RegexBench.Result;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** RegexBench 자체 점검. 실패하면 예외로 끝난다. */
public final class RegexBenchCheck {

    private static final long TIMEOUT_MS = 1000;
    private static final long SHORT_TIMEOUT_MS = 200;

    public static void main(String[] args) {
        javascript();
        jsLikeNode();
        java();
        problems();
        pieces();
        checks();
        code();
        presets();
        asks();
        guesses();
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

    /** node 22 에서 같은 패턴 · 글로 얻은 값과 맞춘다. 줄 끝 · 이스케이프 · 되참조 · 그룹 이름 · UTF-16 단위. */
    private static void jsLikeNode() {
        Set<Flag> none = EnumSet.noneOf(Flag.class);
        Set<Flag> g = EnumSet.of(Flag.JS_GLOBAL);
        Set<Flag> gm = EnumSet.of(Flag.JS_GLOBAL, Flag.JS_MULTILINE);
        Set<Flag> gu = EnumSet.of(Flag.JS_GLOBAL, Flag.JS_UNICODE);
        eq(0, js("^\\d+$", none, "123\n", "").matches().size(), "m 없는 $ 는 맨 끝만");
        eq(List.of(2), starts(js("$", g, "a\n", "")), "m 없는 $ 위치");
        eq(List.of(0, 2, 3), starts(js("^", gm, "a\r\nb", "")), "m 의 ^ 는 \\r · \\n 을 따로 줄 끝으로");
        eq(List.of(1, 2, 4), starts(js("$", gm, "a\r\nb", "")), "m 의 $ 는 \\r · \\n 을 따로 줄 끝으로");
        eq(List.of(0, 2), starts(js("^", gm, "a\u2028b\u0085c", "")), "m 의 줄 끝은 \\u2028 · \\u0085 아님");
        eq(List.of(0, 1), starts(js(".", g, "a\u0085\u2028\r", "")), ". 는 \\u0085 에 맞고 \\u2028 · \\r 에 안 맞음");
        eq(List.of(0, 1), starts(js(".", EnumSet.of(Flag.JS_GLOBAL, Flag.JS_DOT_ALL), "a\u2028", "")), "s 의 . 는 줄 끝에도");
        eq(List.of(1), starts(js("\\v", g, "\n\u000B", "")), "\\v 는 \\x0B 하나");
        eq(List.of(1), starts(js("[\\v]", g, "\n\u000B", "")), "묶음 안 \\v");
        eq(List.of(1), starts(js("\\0", g, "x\0", "")), "\\0 은 NUL");
        eq("RZQE", js("\\R\\Z\\Q\\E", none, "RZQE", "").matches().get(0).groups().get(0).value(), "u 없으면 모르는 이스케이프는 글자");
        eq("p{L}", js("\\p{L}", none, "p{L}", "").matches().get(0).groups().get(0).value(), "u 없으면 \\p 는 글자 p");
        eq("\u0001\u0001", js("\\cA\\ca", none, "\u0001\u0001", "").matches().get(0).groups().get(0).value(), "\\cX 는 32 로 나눈 나머지");
        eq("A8", js("\\101\\8", none, "A8", "").matches().get(0).groups().get(0).value(), "옛 8진수 · \\8 은 글자");
        eq(List.of(0, 1, 2), starts(js("[\\d-x]", g, "-x5", "")), "u 없으면 [\\d-x] 의 - 는 글자");
        eq("b", js("(a)?\\1b", none, "b", "").matches().get(0).groups().get(0).value(), "맞지 않은 그룹 되참조는 빈 글");
        eq("ab", js("\\1(a)b", none, "ab", "").matches().get(0).groups().get(0).value(), "앞쪽 되참조는 빈 글");
        eq("aa", js("(a)\\1", none, "aa", "").matches().get(0).groups().get(0).value(), "닫힌 그룹 되참조");
        Result named = js("(?<first_name>a)(?<$x>b)(?<한글>c)\\k<$x>", none, "abcb", "$<first_name>$<한글>$2");
        eq(Problem.NONE, named.problem(), "JS 그룹 이름 _ · $ · 한글");
        eq("first_name", named.matches().get(0).groups().get(1).name(), "원래 그룹 이름을 보임");
        eq("$x", named.matches().get(0).groups().get(2).name(), "$ 든 그룹 이름");
        eq("acb", named.replaced(), "$<이름> 바꾸기");
        eq("[a|b|b|]", js("(a)(?<n>b)", none, "ab", "[$1|$<n>|$2|$<m>]").replaced(), "그룹 번호 · 이름 바꾸기");
        eq(List.of(0, 2), starts(js("\uD83D\uDE00+", g, "\uD83D\uDE00\uD83D\uDE00", "")), "u 없으면 + 는 이모지 뒤 반쪽에만");
        eq(List.of(0, 1), starts(js("[\uD83D\uDE00]", g, "\uD83D\uDE00", "")), "u 없으면 묶음 안 이모지는 반쪽씩");
        eq(List.of(0, 1), starts(js(".", g, "\uD83D\uDE00", "")), "u 없으면 . 는 반쪽");
        eq(List.of(0), starts(js(".", gu, "\uD83D\uDE00", "")), "u 면 . 는 한 글자");
        eq(List.of(1), starts(js("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]", g, "a\uD83D\uDE00b", "")), "서로게이트 범위");
        eq(List.of(0, 2), starts(js("(?:)", gu, "\uD83D\uDE00", "")), "u 의 빈 일치는 코드 포인트로 건너감");
        eq(List.of(0, 1, 2), starts(js("(?:)", g, "\uD83D\uDE00", "")), "u 없으면 빈 일치는 한 칸씩");
        eq(List.of(1), starts(js("[\uE000]", g, "\uD800\uE000", "")), "사용자 영역 글자는 그대로");
        eq(Verdict.FULL, RegexBench.check(Flavor.JAVASCRIPT, "^.$", Set.of(), "\u0085", TIMEOUT_MS).verdict(), "확인할 글도 JS 줄 끝 기준");
        eq("\uD83D", RegexBench.check(Flavor.JAVASCRIPT, "\\uD83D", Set.of(), "\uD83D\uDE00", TIMEOUT_MS).part(), "확인할 글도 UTF-16 단위");
    }

    private static List<Integer> starts(Result result) {
        List<Integer> starts = new java.util.ArrayList<>();
        for (RegexBench.Match match : result.matches()) {
            starts.add(match.start());
        }
        return starts;
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
        eq(4, moved.errorIndex(), "옮긴 패턴도 사용자가 친 패턴 기준 위치");
        eq(6, js("\\s{3,1}", none, "", "").errorIndex(), "\\s 를 옮겨도 오류 위치는 원래 패턴 기준");
        eq(Problem.SYNTAX, js("(?<1a>x)", none, "", "").problem(), "JS 가 받지 않는 그룹 이름");
        eq(Problem.SYNTAX, js("(?<a>x)(?<a>y)", none, "", "").problem(), "겹친 그룹 이름");
        eq(Problem.SYNTAX, js("(?<n>a)\\k<m>", none, "", "").problem(), "없는 이름 되참조");
        eq(1, js("[z-a]", none, "", "").errorIndex(), "범위 순서 오류는 범위 앞 글자 위치");
        eq(Problem.SYNTAX, js("\\h", EnumSet.of(Flag.JS_UNICODE), "h", "").problem(), "u 의 모르는 이스케이프는 오류");
        eq(Problem.SYNTAX, js("[\\d-x]", EnumSet.of(Flag.JS_UNICODE), "", "").problem(), "u 의 [\\d-x] 는 오류");
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
        Set<CharKind> korean = EnumSet.of(CharKind.KOREAN);
        Piece koreanMany = piece(Source.KINDS, korean, "", new Repeating(Repeat.ONE_OR_MORE, 0, 0, false, false, ""));
        Piece space = piece(Source.KINDS, EnumSet.of(CharKind.SPACE), "", Repeating.ONCE);
        Bunch more = new Bunch(List.of(space, koreanMany), false, new Repeating(Repeat.ZERO_OR_MORE, 0, 0, false, false, ""));
        String name = RegexBench.compose(List.<Part>of(koreanMany, more), shape(true));
        eq("^[가-힣]+(?:\\s[가-힣]+)*$", name, "김 철수 조합");
        for (Flavor flavor : Flavor.values()) {
            eq(2, run(flavor, name, EnumSet.of(Flag.MULTILINE, Flag.JS_MULTILINE, Flag.JS_GLOBAL), "홍길동\n홍길동1\nHong\n김 철수", "").matches().size(),
                    flavor + " 김 철수 일치");
        }
        eq("\\d", RegexBench.piece(piece(Source.KINDS, EnumSet.of(CharKind.DIGIT), "", Repeating.ONCE)), "숫자만은 \\d");
        eq("[A-Za-z0-9_\\-]", RegexBench.piece(piece(Source.KINDS, EnumSet.of(CharKind.DIGIT, CharKind.UPPER, CharKind.LOWER), "_-_", Repeating.ONCE)),
                "종류 + 이 글자도(겹친 글자 한 번, - 이스케이프)");
        eq("(?:010|011)", RegexBench.piece(piece(Source.WORDS, Set.of(), " 010, 011 ,010,", Repeating.ONCE)), "낱말 여럿은 또는");
        eq("(?:a\\.b)+", RegexBench.piece(piece(Source.WORDS, Set.of(), "a.b", new Repeating(Repeat.ONE_OR_MORE, 0, 0, false, false, ""))),
                "두 글자 낱말 반복은 묶음");
        eq("[^,\\]]*?", RegexBench.piece(piece(Source.EXCEPT, Set.of(), ",]", new Repeating(Repeat.ZERO_OR_MORE, 0, 0, true, false, ""))),
                "이 글자 빼고 · 가능한 짧게");
        eq("(?<last>\\d{4})", RegexBench.piece(piece(Source.KINDS, EnumSet.of(CharKind.DIGIT), "", new Repeating(Repeat.EXACTLY, 4, 0, true, true, "last"))),
                "이름 꺼내기 · 정확히 N번은 짧게 없음");
        eq("(\\d{2,4})", RegexBench.piece(piece(Source.KINDS, EnumSet.of(CharKind.DIGIT), "", new Repeating(Repeat.RANGE, 2, 4, false, true, ""))),
                "번호 꺼내기");
        Piece won = new Piece(Source.KINDS, EnumSet.of(CharKind.DIGIT), "", new Repeating(Repeat.ONE_OR_MORE, 0, 0, false, false, ""), "", "원");
        Piece dollar = new Piece(Source.KINDS, EnumSet.of(CharKind.DIGIT), "", new Repeating(Repeat.ONE_OR_MORE, 0, 0, false, false, ""), "$", "");
        eq("\\d+(?=원)", RegexBench.piece(won), "뒤에 올 때만");
        for (Flavor flavor : Flavor.values()) {
            eq("3000", run(flavor, RegexBench.piece(won), Set.of(), "3000원 20개", "").matches().get(0).groups().get(0).value(), flavor + " 원 앞 숫자");
            eq("5", run(flavor, RegexBench.piece(dollar), Set.of(), "1 $5", "").matches().get(0).groups().get(0).value(), flavor + " $ 뒤 숫자");
        }
        Bunch phone = new Bunch(List.of(piece(Source.WORDS, Set.of(), "010-", Repeating.ONCE), piece(Source.WORDS, Set.of(), "02-", Repeating.ONCE)),
                true, Repeating.ONCE);
        eq("(?:010-|02-)\\d", RegexBench.compose(List.<Part>of(phone, piece(Source.KINDS, EnumSet.of(CharKind.DIGIT), "", Repeating.ONCE)), shape(false)),
                "묶음 또는");
        Shape password = new Shape(true, false, EnumSet.of(Need.LETTER, Need.DIGIT, Need.SYMBOL), 8);
        String pw = RegexBench.compose(List.<Part>of(), password);
        for (Flavor flavor : Flavor.values()) {
            eq(1, run(flavor, pw, EnumSet.of(Flag.MULTILINE, Flag.JS_MULTILINE, Flag.JS_GLOBAL), "abc123!@\npassword\n12345678\nAb1!", "").matches().size(),
                    flavor + " 비밀번호 조건");
        }
        eq("", RegexBench.compose(List.<Part>of(), shape(true)), "아무것도 없으면 빈 패턴");
        String word = RegexBench.compose(List.<Part>of(piece(Source.WORDS, Set.of(), "cat", Repeating.ONCE)), new Shape(false, true, Set.of(), 0));
        eq("(?<![가-힣A-Za-z0-9_])cat(?![가-힣A-Za-z0-9_])", word, "낱말 단위");
        String honorific = RegexBench.compose(List.<Part>of(piece(Source.WORDS, Set.of(), "홍길동", Repeating.ONCE)), new Shape(false, true, Set.of(), 0));
        for (Flavor flavor : Flavor.values()) {
            eq(1, run(flavor, word, EnumSet.of(Flag.JS_GLOBAL), "cat category", "").matches().size(), flavor + " 낱말 단위 일치");
            eq(1, run(flavor, honorific, EnumSet.of(Flag.JS_GLOBAL), "홍길동 님, 홍길동님", "").matches().size(), flavor + " 한글 낱말 단위 일치");
        }
        eq(0, js("\\b홍\\b", Set.of(), "홍", "").matches().size(), "JS \\b 는 한글을 낱말 글자로 보지 않음");
        eq(0, run(Flavor.JAVA, "\\b홍\\b", Set.of(), "홍", "").matches().size(), "Java \\b 도 ASCII 기준");
        eq(1, js("[\\b]", Set.of(), "\b", "").matches().size(), "JS 묶음 안 \\b 는 백스페이스");
        eq(1, js("[" + CharKind.SYMBOL.range + "]+", Set.of(), "!/[\\]`~", "").matches().size(), "특수문자 범위 JS");
        eq(7, run(Flavor.JAVA, "[" + CharKind.SYMBOL.range + "]", Set.of(), "!/[\\]`~a", "").matches().size(), "특수문자 범위 Java");

        eq(RegexBench.PartProblem.NO_KINDS, RegexBench.problem(piece(Source.KINDS, Set.of(), "", Repeating.ONCE)), "종류 없음");
        eq(RegexBench.PartProblem.NO_WORDS, RegexBench.problem(piece(Source.WORDS, Set.of(), " , ", Repeating.ONCE)), "낱말 없음");
        eq(RegexBench.PartProblem.NO_EXCEPT, RegexBench.problem(piece(Source.EXCEPT, Set.of(), "", Repeating.ONCE)), "뺄 글자 없음");
        eq(RegexBench.PartProblem.RANGE, RegexBench.problem(piece(Source.ANY, Set.of(), "", new Repeating(Repeat.RANGE, 5, 2, false, false, ""))), "N > M");
        eq(RegexBench.PartProblem.NAME, RegexBench.problem(piece(Source.ANY, Set.of(), "", new Repeating(Repeat.ONCE, 0, 0, false, true, "1a"))), "이름 꼴");
        eq(RegexBench.PartProblem.EMPTY_GROUP, RegexBench.problem(new Bunch(List.of(), false, Repeating.ONCE)), "빈 묶음");
        Piece year = piece(Source.KINDS, EnumSet.of(CharKind.DIGIT), "", new Repeating(Repeat.EXACTLY, 4, 0, false, true, "y"));
        Bunch yearInside = new Bunch(List.of(year), false, Repeating.ONCE);
        eq(RegexBench.PartProblem.DUPLICATE_NAME, RegexBench.problem(List.<Part>of(year, yearInside)), "두 줄이 같은 이름");
        eq(null, RegexBench.problem(List.<Part>of(year, more)), "이름이 하나뿐");

        Repeating many = new Repeating(Repeat.ONE_OR_MORE, 0, 0, false, false, "");
        String smiles = RegexBench.piece(piece(Source.WORDS, Set.of(), "\uD83D\uDE00", many));
        eq("(?:\uD83D\uDE00)+", smiles, "이모지 하나도 반복이면 묶음");
        String smileOrDigit = RegexBench.piece(piece(Source.KINDS, EnumSet.of(CharKind.DIGIT), "\uD83D\uDE00", many));
        eq("(?:[0-9]|\uD83D\uDE00)+", smileOrDigit, "이모지는 [ ] 밖 또는");
        String notSmile = RegexBench.piece(piece(Source.EXCEPT, Set.of(), ",\uD83D\uDE00", many));
        eq("(?:(?!\uD83D\uDE00)(?:[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]|[^,]))+", notSmile, "이모지 빼고는 앞보기");
        String text = "1\uD83D\uDE00\uD83D\uDE00,\uD83D\uDE01";
        for (Flavor flavor : Flavor.values()) {
            eq(1, run(flavor, smiles, EnumSet.of(Flag.JS_GLOBAL), text, "").matches().size(), flavor + " 이모지 반복");
            eq("1\uD83D\uDE00\uD83D\uDE00", run(flavor, smileOrDigit, Set.of(), text, "").matches().get(0).groups().get(0).value(), flavor + " 이모지 또는 숫자");
            List<RegexBench.Match> others = run(flavor, notSmile, EnumSet.of(Flag.JS_GLOBAL), text, "").matches();
            eq("\uD83D\uDE01", others.get(others.size() - 1).groups().get(0).value(), flavor + " 이모지 빼고 다른 이모지는 통째로");
        }
        eq(null, RegexBench.problem(more), "문제 없는 묶음");
    }

    private static void checks() {
        Set<Flag> none = EnumSet.noneOf(Flag.class);
        eq(Verdict.FULL, RegexBench.check(Flavor.JAVA, "[가-힣 ]+", none, "김 철수", TIMEOUT_MS).verdict(), "전체 맞음");
        RegexBench.Check partial = RegexBench.check(Flavor.JAVASCRIPT, "[가-힣]+", none, "홍길동1", TIMEOUT_MS);
        eq(Verdict.PARTIAL, partial.verdict(), "일부만");
        eq("홍길동", partial.part(), "맞은 부분");
        eq(Verdict.NONE, RegexBench.check(Flavor.JAVA, "\\d", none, "abc", TIMEOUT_MS).verdict(), "안 맞음");
        eq(Problem.SYNTAX, RegexBench.check(Flavor.JAVA, "(", none, "a", TIMEOUT_MS).problem(), "확인할 글 패턴 오류");
        eq(Problem.TIMEOUT, RegexBench.check(Flavor.JAVA, "(.*a){20}$", none, "a".repeat(28) + "!", SHORT_TIMEOUT_MS).problem(), "확인할 글 시간 넘김");
    }

    private static Piece piece(Source source, Set<CharKind> kinds, String text, Repeating repeating) {
        return new Piece(source, kinds, text, repeating, "", "");
    }

    private static Shape shape(boolean whole) {
        return new Shape(whole, false, Set.of(), 0);
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
        var grouped = RegexBench.parsePresets("a\tb\tg\t\tx\n## 분류 1\nc\td\tg\t\ty\n");
        eq("", grouped.get(0).category(), "분류 줄 앞은 빈 분류");
        eq("분류 1", grouped.get(1).category(), "분류 줄 아래");
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
            if (preset.category().isEmpty()) {
                throw new AssertionError(preset.name() + ": 분류가 없음");
            }
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

    private static void asks() {
        Repeating any = new Repeating(Repeat.ONE_OR_MORE, 0, 0, false, false, "");
        eq("^[가-힣]+(?:\\s[가-힣]+)*$", RegexBench.ask(true, EnumSet.of(CharKind.KOREAN), "", any, true, Set.of()), "한글 이름");
        eq("\\d{4}", RegexBench.ask(false, EnumSet.of(CharKind.DIGIT), "", new Repeating(Repeat.EXACTLY, 4, 0, false, false, ""), false, Set.of(Need.DIGIT)),
                "찾기는 꼭 들어갈 것 · 낱말 경계 없음");
        eq("^(?=.*\\d)[a-z0-9_]{4,20}$", RegexBench.ask(true, EnumSet.of(CharKind.LOWER, CharKind.DIGIT), "_",
                new Repeating(Repeat.RANGE, 4, 20, false, false, ""), false, Set.of(Need.DIGIT)), "아이디");
        eq(null, RegexBench.ask(true, EnumSet.noneOf(CharKind.class), "", any, false, Set.of()), "고른 글자가 없으면 null");
    }

    private static void guesses() {
        eq(null, RegexBench.guess(List.of("", ""), List.of()), "맞아야 할 글이 없으면 null");
        RegexBench.Guess phone = RegexBench.guess(List.of("010-1234-5678", "011-123-4567"), List.of("02-123-4567"));
        eq("^\\d{3}[\\-]\\d{3,4}[\\-]\\d{4}$", phone.pattern(), "같은 차례 · 길이");
        eq(true, phone.rejectsAll(), "틀려야 할 글 걸러냄");
        RegexBench.Guess name = RegexBench.guess(List.of("홍길동", "김 철수", "남궁 민 수"), List.of("홍길동1", " 김철수"));
        eq("^[가-힣]+(?:\\s[가-힣]+)*$", name.pattern(), "띄어쓰기로 이은 낱말");
        eq(true, name.rejectsAll(), "이름 아닌 것 걸러냄");
        RegexBench.Guess id = RegexBench.guess(List.of("kinggura", "user_01"), List.of("1user_00"));
        eq("^[a-z0-9_]{7,8}$", id.pattern(), "글자 종류 모아 길이");
        eq(false, id.rejectsAll(), "걸러내지 못하면 알림");
        RegexBench.Guess code = RegexBench.guess(List.of("AB-12", "CD-345"), List.of("AB-1234"));
        eq("^[A-Z]{2}[\\-]\\d{2,3}$", code.pattern(), "대문자 · 숫자 꼴");
        for (String text : List.of("010-1234-5678", "011-123-4567")) {
            eq(Verdict.FULL, RegexBench.check(Flavor.JAVASCRIPT, phone.pattern(), Set.of(), text, TIMEOUT_MS).verdict(), "JS 에서도 맞음 " + text);
        }
    }

    private static Result js(String pattern, Set<Flag> flags, String text, String replacement) {
        return run(Flavor.JAVASCRIPT, pattern, flags, text, replacement);
    }

    private static Result run(Flavor flavor, String pattern, Set<Flag> flags, String text, String replacement) {
        return RegexBench.run(flavor, pattern, flags, text, replacement, TIMEOUT_MS);
    }

    private static void eq(Object expected, Object actual, String name) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
