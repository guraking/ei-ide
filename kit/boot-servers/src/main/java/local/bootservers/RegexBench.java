package local.bootservers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 정규식 작업대의 일치 · 바꾸기 계산. IntelliJ 클래스를 쓰지 않는다(점검 RegexBenchCheck).
 * JavaScript 문법은 java.util.regex 로 옮겨 돌린다(toJava). 옮기는 것: 빈 묶음 [] · [^], 묶음 안 글자 · 범위, 반복이 아닌 { ,
 * ^ · $ · . 의 JS 줄 끝(LineTerminator) 기준, \s · \S(JS_SPACES), \v · \0 · \cX · \xHH · 백슬래시 u 이스케이프, u 없을 때 모르는 이스케이프(글자 그대로),
 * 그룹 이름(Java 에는 번호 그룹으로 넣고 이름은 따로 든다), 아직 잡히지 않은 그룹 되참조(빈 글에 맞음).
 * u 가 없으면 UTF-16 한 칸 단위로 맞춘다: 서로게이트(D800-DFFF)와 사용자 영역 E000-E7FF 를 맞바꾼 글로 돌려 Java 가 이모지를 한 글자로 묶지 않게 한다.
 * 바꾸기 글은 String.prototype.replace 규칙($$ $& $` $' $n $nn $<이름>)을 따른다.
 * shortcut: u 없을 때 U+E000-E7FF 글자가 이어 나오면 서로게이트 짝으로 읽혀 틀릴 수 있고, 반복 안 그룹 값을 회차마다 비우는 JS 규칙 ·
 * 뒤보기 안 되참조 방향은 흉내 내지 않는다. 틀린 결과가 보고되면 옮김에 더한다.
 */
final class RegexBench {

    enum Flavor { JAVASCRIPT, JAVA }

    /** label 은 화면에 보일 플래그 이름(JS 는 리터럴 뒤 글자, Java 는 Pattern 상수 이름)이다. */
    enum Flag {
        JS_GLOBAL(Flavor.JAVASCRIPT, "g", 0),
        // JS i 는 u 가 없어도 ASCII 밖 글자까지 대소문자를 가린다
        JS_IGNORE_CASE(Flavor.JAVASCRIPT, "i", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
        // m · s 는 toJava 가 ^ $ . 를 직접 옮겨 Java 플래그가 필요 없다
        JS_MULTILINE(Flavor.JAVASCRIPT, "m", 0),
        JS_DOT_ALL(Flavor.JAVASCRIPT, "s", 0),
        JS_UNICODE(Flavor.JAVASCRIPT, "u", 0),
        CASE_INSENSITIVE(Flavor.JAVA, "CASE_INSENSITIVE", Pattern.CASE_INSENSITIVE),
        MULTILINE(Flavor.JAVA, "MULTILINE", Pattern.MULTILINE),
        DOTALL(Flavor.JAVA, "DOTALL", Pattern.DOTALL),
        COMMENTS(Flavor.JAVA, "COMMENTS", Pattern.COMMENTS),
        UNICODE_CASE(Flavor.JAVA, "UNICODE_CASE", Pattern.UNICODE_CASE);

        final Flavor flavor;
        final String label;
        final int bits;

        Flag(Flavor flavor, String label, int bits) {
            this.flavor = flavor;
            this.label = label;
            this.bits = bits;
        }
    }

    enum Problem { NONE, SYNTAX, TIMEOUT, TOO_DEEP, REPLACEMENT }

    /** 그룹 하나. 일치에 끼지 않은 그룹은 start · end 가 -1, value 가 null. 이름 없는 그룹은 name 이 null. */
    record Group(int index, String name, int start, int end, String value) {}

    /** 일치 하나. groups 의 0 번은 일치 전체다. */
    record Match(int start, int end, List<Group> groups) {}

    /**
     * matches 는 처음 MAX_MATCHES 개까지, 더 있으면 truncated.
     * problem 이 SYNTAX 면 detail 은 JDK 의 오류 설명, errorIndex 는 패턴 안 위치(알 수 없으면 -1).
     * REPLACEMENT 면 일치는 있고 replaced 가 null 이다.
     */
    record Result(List<Match> matches, boolean truncated, String replaced, Problem problem, String detail, int errorIndex) {}

    static final int MAX_MATCHES = 1000;

    private RegexBench() {
    }

    /** 시간이 timeoutMs 를 넘으면 TIMEOUT, 되돌이가 너무 깊어 스택이 넘치면 TOO_DEEP 으로 끝낸다. */
    static Result run(Flavor flavor, String pattern, Set<Flag> flags, String text, String replacement, long timeoutMs) {
        boolean js = flavor == Flavor.JAVASCRIPT;
        Compiled compiled;
        try {
            compiled = compile(flavor, pattern, flags);
        } catch (PatternSyntaxException e) {
            return new Result(List.of(), false, null, Problem.SYNTAX, e.getDescription(), e.getIndex());
        }
        CharSequence timed = new TimedText(compiled.subject(text), System.nanoTime() + timeoutMs * 1_000_000L);
        try {
            List<MatchResult> found = js ? findJs(compiled, timed, text, flags.contains(Flag.JS_GLOBAL)) : findJava(compiled, timed);
            List<Match> matches = new ArrayList<>();
            for (MatchResult r : found.subList(0, Math.min(found.size(), MAX_MATCHES))) {
                matches.add(toMatch(r, text, compiled));
            }
            boolean truncated = found.size() > MAX_MATCHES;
            String replaced;
            try {
                replaced = js ? replaceJs(found, text, replacement, compiled) : compiled.pattern().matcher(timed).replaceAll(replacement);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                return new Result(matches, truncated, null, Problem.REPLACEMENT, e.getMessage(), -1);
            }
            return new Result(matches, truncated, replaced, Problem.NONE, null, -1);
        } catch (Timeout e) {
            return new Result(List.of(), false, null, Problem.TIMEOUT, null, -1);
        } catch (StackOverflowError e) {
            return new Result(List.of(), false, null, Problem.TOO_DEEP, null, -1);
        }
    }

    /**
     * 컴파일한 패턴. names[k] · groups[k] 는 사용자 패턴의 그룹 k 의 이름(없으면 null)과 Java 그룹 번호(0 은 일치 전체).
     * units 면 JS u 없는 모드라 글을 swapUnits 로 바꿔 맞춘다. codePoints 면 JS u 모드라 이모지 가운데서는 일치를 시작하지 않는다.
     */
    private record Compiled(Pattern pattern, String[] names, int[] groups, boolean units, boolean codePoints) {
        String subject(String text) {
            return units ? swapUnits(text) : text;
        }
    }

    /** 패턴 오류는 PatternSyntaxException 이고, 그 위치는 사용자가 친 패턴 기준이다. */
    private static Compiled compile(Flavor flavor, String pattern, Set<Flag> flags) {
        int bits = 0;
        for (Flag flag : flags) {
            if (flag.flavor == flavor) {
                bits |= flag.bits;
            }
        }
        if (flavor == Flavor.JAVA) {
            Pattern compiled = Pattern.compile(pattern, bits);
            String[] names = groupNames(compiled);
            int[] groups = new int[names.length];
            for (int i = 0; i < groups.length; i++) {
                groups[i] = i;
            }
            return new Compiled(compiled, names, groups, false, false);
        }
        boolean unicode = flags.contains(Flag.JS_UNICODE);
        JsPattern converted = toJava(pattern, flags);
        try {
            return new Compiled(Pattern.compile(converted.java(), bits), converted.names(), converted.groups(), !unicode, unicode);
        } catch (PatternSyntaxException e) {
            throw new PatternSyntaxException(e.getDescription(), pattern, converted.originOf(e.getIndex()));
        }
    }

    private static List<MatchResult> findJava(Compiled compiled, CharSequence timed) {
        List<MatchResult> found = new ArrayList<>();
        Matcher m = compiled.pattern().matcher(timed);
        // Java 는 늘 전부 바꾸므로 목록만 상한 하나 위에서 끊는다(넘은 것은 truncated 로 안다)
        while (found.size() <= MAX_MATCHES && m.find()) {
            found.add(m.toMatchResult());
        }
        return found;
    }

    /** RegExp exec 를 g 대로 되풀이한다. 빈 일치 뒤에는 u 면 코드 포인트, 아니면 UTF-16 한 칸 건너간다. 바꾸기에 쓰므로 상한 없이 모은다. */
    private static List<MatchResult> findJs(Compiled compiled, CharSequence timed, String text, boolean global) {
        List<MatchResult> found = new ArrayList<>();
        Matcher m = compiled.pattern().matcher(timed);
        int from = 0;
        while (from <= text.length() && m.find(from)) {
            int start = m.start();
            if (compiled.codePoints() && insidePair(text, start)) {
                from = start + 1;
                continue;
            }
            found.add(m.toMatchResult());
            if (!global) {
                break;
            }
            from = m.end() > start ? m.end() : m.end() + (compiled.codePoints() && pairAt(text, m.end()) ? 2 : 1);
        }
        return found;
    }

    private static boolean insidePair(String text, int index) {
        return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1)) && Character.isLowSurrogate(text.charAt(index));
    }

    private static boolean pairAt(String text, int index) {
        return index + 1 < text.length() && Character.isHighSurrogate(text.charAt(index)) && Character.isLowSurrogate(text.charAt(index + 1));
    }

    /** 일치 · 그룹 값은 원래 글에서 자른다(units 모드의 바꾼 글이 아니라). */
    private static Match toMatch(MatchResult r, String text, Compiled compiled) {
        List<Group> groups = new ArrayList<>();
        for (int k = 0; k < compiled.groups().length; k++) {
            int start = r.start(compiled.groups()[k]);
            int end = r.end(compiled.groups()[k]);
            groups.add(new Group(k, compiled.names()[k], start, end, start < 0 ? null : text.substring(start, end)));
        }
        return new Match(r.start(), r.end(), groups);
    }

    /** 그룹 번호 → 이름. 이름 없는 번호는 null. */
    private static String[] groupNames(Pattern pattern) {
        String[] names = new String[pattern.matcher("").groupCount() + 1];
        for (Map.Entry<String, Integer> e : pattern.namedGroups().entrySet()) {
            names[e.getValue()] = e.getKey();
        }
        return names;
    }

    private static String replaceJs(List<MatchResult> found, String text, String replacement, Compiled compiled) {
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (MatchResult r : found) {
            out.append(text, last, r.start()).append(expandJs(replacement, r, text, compiled));
            last = r.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    /** String.prototype.replace 의 GetSubstitution. 뜻이 없는 $ 표기는 글자 그대로 둔다. */
    private static String expandJs(String replacement, MatchResult r, String text, Compiled compiled) {
        StringBuilder out = new StringBuilder();
        String[] names = compiled.names();
        int groups = names.length - 1;
        boolean hasNames = false;
        for (String name : names) {
            hasNames |= name != null;
        }
        int start = r.start();
        int end = r.end();
        int i = 0;
        while (i < replacement.length()) {
            char c = replacement.charAt(i);
            if (c != '$' || i + 1 == replacement.length()) {
                out.append(c);
                i++;
                continue;
            }
            char next = replacement.charAt(i + 1);
            if (next == '$') {
                out.append('$');
                i += 2;
            } else if (next == '&') {
                out.append(text, start, end);
                i += 2;
            } else if (next == '`') {
                out.append(text, 0, start);
                i += 2;
            } else if (next == '\'') {
                out.append(text, end, text.length());
                i += 2;
            } else if (Character.isDigit(next)) {
                int two = i + 2 < replacement.length() && Character.isDigit(replacement.charAt(i + 2))
                        ? (next - '0') * 10 + (replacement.charAt(i + 2) - '0') : -1;
                int one = next - '0';
                if (two >= 1 && two <= groups) {
                    appendGroup(out, r, text, compiled.groups()[two]);
                    i += 3;
                } else if (one >= 1 && one <= groups) {
                    appendGroup(out, r, text, compiled.groups()[one]);
                    i += 2;
                } else {
                    out.append(c);
                    i++;
                }
            } else if (next == '<' && hasNames && replacement.indexOf('>', i + 2) > 0) {
                int close = replacement.indexOf('>', i + 2);
                String name = replacement.substring(i + 2, close);
                // 없는 이름은 JS 에서 undefined → 빈 글
                for (int k = 1; k <= groups; k++) {
                    if (name.equals(names[k])) {
                        appendGroup(out, r, text, compiled.groups()[k]);
                    }
                }
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void appendGroup(StringBuilder out, MatchResult r, String text, int javaGroup) {
        if (r.start(javaGroup) >= 0) {
            out.append(text, r.start(javaGroup), r.end(javaGroup));
        }
    }

    /** ECMAScript 의 WhiteSpace · LineTerminator(\s 가 맞는 글자). Java \s 는 이 가운데 ASCII 만 맞는다. */
    private static final String JS_SPACES =
            "\\t\\n\\x0B\\f\\r\\x20\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}";
    /** ECMAScript LineTerminator. m 의 ^ · $ 와 s 없는 . 이 이것만 줄 끝으로 본다(Java 는 \u0085 와 \r\n 한 덩이도 줄 끝으로 봄). */
    private static final String JS_LINE_TERMINATORS = "\\n\\r\\x{2028}\\x{2029}";
    private static final String ANY_CHAR = "[\\s\\S]";
    private static final String NOTHING = "(?:)";
    private static final int INITIAL_ORIGIN = 64;

    // u 없는 모드에서 맞바꾸는 두 구역. 둘 다 0x800 칸이라 서로 옮기면 겹치지 않는다.
    private static final int SURROGATE_FIRST = 0xD800;
    private static final int SURROGATE_LAST = 0xDFFF;
    private static final int PRIVATE_FIRST = 0xE000;
    private static final int PRIVATE_LAST = 0xE7FF;
    private static final int SWAP_DISTANCE = PRIVATE_FIRST - SURROGATE_FIRST;
    // \cX 는 X 를 32 로 나눈 나머지 글자다
    private static final int CONTROL_MODULO = 32;
    private static final int MAX_LEGACY_OCTAL = 0377;
    private static final int MAX_OCTAL_DIGITS = 3;
    private static final int HEX_RADIX = 16;
    private static final int OCTAL_RADIX = 8;
    private static final int DECIMAL_RADIX = 10;
    private static final int HEX2 = 2;
    private static final int HEX4 = 4;
    private static final int BACKSPACE = 0x08;
    private static final int LINE_TABULATION = 0x0B;
    // JS u 모드가 이스케이프로 받는 문법 글자. 이 밖의 글자 이스케이프는 u 모드에서 오류다.
    private static final String JS_SYNTAX_CHARS = "^$\\.*+?()[]{}|/";
    private static final String BAD_ESCAPE = "잘못된 이스케이프";
    private static final String BAD_RANGE = "묶음 범위의 앞 글자가 뒤 글자보다 큼";
    private static final String BAD_GROUP_NAME = "잘못된 그룹 이름";
    private static final String DUPLICATE_GROUP_NAME = "겹친 그룹 이름";
    private static final String UNKNOWN_GROUP_NAME = "없는 그룹 이름";

    /** UTF-16 값 하나를 맞바꾼다. 서로게이트 ↔ U+E000-E7FF, 나머지는 그대로. */
    private static int swapUnit(int unit) {
        if (unit >= SURROGATE_FIRST && unit <= SURROGATE_LAST) {
            return unit + SWAP_DISTANCE;
        }
        if (unit >= PRIVATE_FIRST && unit <= PRIVATE_LAST) {
            return unit - SWAP_DISTANCE;
        }
        return unit;
    }

    /** 글자마다 swapUnit. 길이 · 위치는 그대로다. */
    static String swapUnits(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) swapUnit(chars[i]);
        }
        return new String(chars);
    }

    /**
     * toJava 결과. origin[i] 는 java 의 i 번째 글자를 만든 원래 패턴 위치이고, 끝 다음 칸(origin[java 길이])은 원래 길이다.
     * names[k] 는 JS 그룹 k 의 이름(없으면 null, 0 은 일치 전체), groups[k] 는 그 그룹의 Java 그룹 번호.
     */
    record JsPattern(String java, int[] origin, String[] names, int[] groups) {
        int originOf(int javaIndex) {
            return javaIndex < 0 ? -1 : origin[Math.min(javaIndex, origin.length - 1)];
        }
    }

    /** JavaScript 정규식을 java.util.regex 에서 같은 뜻이 되게 옮긴다. JS 가 받지 않는 꼴이면 원래 패턴 위치와 함께 PatternSyntaxException. */
    static JsPattern toJava(String js, Set<Flag> flags) {
        return new JsConverter(js, flags).convert();
    }

    /** toJava 의 한 번 옮기기. 그룹 이름 · 수는 먼저 훑어(scanGroups) 앞쪽 되참조 · \k 를 가린다. */
    private static final class JsConverter {
        private final String js;
        private final boolean unicode;
        private final boolean multiline;
        private final boolean dotAll;
        private final List<String> names;
        private final int[] javaOf;
        // 그룹 k 가 끝까지 맞았는지 알리는 빈 그룹의 Java 번호. 0 이면 아직 닫히지 않았다.
        private final int[] markerOf;
        private final StringBuilder out = new StringBuilder();
        private int[] origin = new int[INITIAL_ORIGIN];
        private final java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
        private int javaCount;
        private int jsCount;

        JsConverter(String js, Set<Flag> flags) {
            this.js = js;
            unicode = flags.contains(Flag.JS_UNICODE);
            multiline = flags.contains(Flag.JS_MULTILINE);
            dotAll = flags.contains(Flag.JS_DOT_ALL);
            names = scanGroups(js);
            javaOf = new int[names.size() + 1];
            markerOf = new int[names.size() + 1];
        }

        JsPattern convert() {
            int i = 0;
            while (i < js.length()) {
                char c = js.charAt(i);
                if (c == '\\') {
                    Atom atom = escape(i, false);
                    emit(atom.java(), i);
                    i = atom.end();
                } else if (c == '[') {
                    i = charClass(i);
                } else if (c == '(') {
                    i = openGroup(i);
                } else if (c == ')') {
                    Integer k = open.poll();
                    if (k != null && k > 0) {
                        // 그룹 끝에 빈 그룹을 두어 그 그룹이 맞았는지 되참조로 알 수 있게 한다
                        markerOf[k] = ++javaCount;
                        emit("()", i);
                    }
                    emit(")", i);
                    i++;
                } else if (c == '^') {
                    emit(multiline ? "(?<![^" + JS_LINE_TERMINATORS + "])" : "^", i);
                    i++;
                } else if (c == '$') {
                    emit(multiline ? "(?![^" + JS_LINE_TERMINATORS + "])" : "(?!" + ANY_CHAR + ")", i);
                    i++;
                } else if (c == '.') {
                    emit(dotAll ? ANY_CHAR : "[^" + JS_LINE_TERMINATORS + "]", i);
                    i++;
                } else if (c == '{' && !isQuantifier(js, i)) {
                    emit("\\{", i);
                    i++;
                } else {
                    emit(unicode || swapUnit(c) == c ? String.valueOf(c) : hex(swapUnit(c)), i);
                    i++;
                }
            }
            int[] exact = java.util.Arrays.copyOf(origin, out.length() + 1);
            exact[out.length()] = js.length();
            String[] groupNames = new String[names.size() + 1];
            for (int k = 1; k <= names.size(); k++) {
                groupNames[k] = names.get(k - 1);
            }
            return new JsPattern(out.toString(), exact, groupNames, javaOf);
        }

        /** ( 하나. 잡는 그룹은 Java 에서 번호 그룹이다(이름은 groupNames 가 든다). 그 밖의 (? 꼴은 그대로 둔다. */
        private int openGroup(int i) {
            boolean named = js.startsWith("(?<", i) && i + 3 < js.length() && js.charAt(i + 3) != '=' && js.charAt(i + 3) != '!';
            if (js.startsWith("(?", i) && !named) {
                open.push(0);
                emit("(?", i);
                return i + 2;
            }
            jsCount++;
            javaOf[jsCount] = ++javaCount;
            open.push(jsCount);
            emit("(", i);
            return named ? js.indexOf('>', i) + 1 : i + 1;
        }

        /** [ … ] 하나. 글자 하나는 \x{…} 로, 범위는 양 끝 값으로 넣는다. 범위가 될 수 없는 - 는 글자다. */
        private int charClass(int i) {
            if (js.startsWith("[]", i)) {
                emit("(?!)", i);
                return i + 2;
            }
            if (js.startsWith("[^]", i)) {
                emit(ANY_CHAR, i);
                return i + 3;
            }
            boolean negated = js.startsWith("[^", i);
            emit(negated ? "[^" : "[", i);
            int j = i + (negated ? 2 : 1);
            while (j < js.length() && js.charAt(j) != ']') {
                Atom first = classAtom(j);
                int dash = first.end();
                boolean range = dash + 1 < js.length() && js.charAt(dash) == '-' && js.charAt(dash + 1) != ']';
                if (!range) {
                    emit(first.java(), j);
                    j = dash;
                    continue;
                }
                Atom second = classAtom(dash + 1);
                if (first.value() < 0 || second.value() < 0) {
                    // [\d-x] 의 - 는 u 없으면 글자, u 면 오류
                    if (unicode) {
                        throw new PatternSyntaxException(BAD_ESCAPE, js, dash);
                    }
                    emit(first.java(), j);
                    emit("\\-", dash);
                    j = dash + 1;
                    continue;
                }
                if (first.value() > second.value()) {
                    throw new PatternSyntaxException(BAD_RANGE, js, j);
                }
                emitRange(first.value(), second.value(), j);
                j = second.end();
            }
            if (j < js.length()) {
                emit("]", j);
                j++;
            }
            return j;
        }

        private Atom classAtom(int j) {
            if (js.charAt(j) == '\\') {
                return escape(j, true);
            }
            int value = unicode ? js.codePointAt(j) : js.charAt(j);
            return new Atom(literal(value), value, j + (unicode ? Character.charCount(value) : 1));
        }

        /** u 없으면 맞바꾼 구역을 따로 떼어 넣는다. */
        private void emitRange(int low, int high, int from) {
            if (unicode) {
                emit(hex(low) + "-" + hex(high), from);
                return;
            }
            int[][] parts = {{low, Math.min(high, SURROGATE_FIRST - 1)}, {Math.max(low, SURROGATE_FIRST), Math.min(high, SURROGATE_LAST)},
                    {Math.max(low, PRIVATE_FIRST), Math.min(high, PRIVATE_LAST)}, {Math.max(low, PRIVATE_LAST + 1), high}};
            for (int[] part : parts) {
                if (part[0] <= part[1]) {
                    emit(hex(swapUnit(part[0])) + "-" + hex(swapUnit(part[1])), from);
                }
            }
        }

        private String literal(int value) {
            return hex(unicode ? value : swapUnit(value));
        }

        /** i 자리 백슬래시에서 시작하는 이스케이프 하나. value 는 글자 하나면 그 값, 아니면 -1. */
        private Atom escape(int i, boolean inClass) {
            if (i + 1 >= js.length()) {
                // 끝의 \ 는 JS · Java 모두 오류다. Java 가 그 위치로 알린다.
                return new Atom("\\", -1, i + 1);
            }
            char d = js.charAt(i + 1);
            switch (d) {
                case 'd', 'D', 'w', 'W' -> {
                    return new Atom("\\" + d, -1, i + 2);
                }
                case 's' -> {
                    return new Atom(inClass ? JS_SPACES : "[" + JS_SPACES + "]", -1, i + 2);
                }
                case 'S' -> {
                    // 묶음 안에서는 Java 겹 묶음(합집합)으로 넣는다. [^…[^공백]] 도 Java 9 부터 바깥 ^ 가 전체에 걸린다.
                    return new Atom("[^" + JS_SPACES + "]", -1, i + 2);
                }
                case 'b' -> {
                    // 묶음 밖 \b 는 JS · Java(19 부터) 모두 ASCII 낱말 글자 기준이다
                    return inClass ? value(BACKSPACE, i + 2) : new Atom("\\b", -1, i + 2);
                }
                case 'B' -> {
                    return inClass ? identity(i, d) : new Atom("\\B", -1, i + 2);
                }
                case 'f' -> {
                    return value('\f', i + 2);
                }
                case 'n' -> {
                    return value('\n', i + 2);
                }
                case 'r' -> {
                    return value('\r', i + 2);
                }
                case 't' -> {
                    return value('\t', i + 2);
                }
                case 'v' -> {
                    return value(LINE_TABULATION, i + 2);
                }
                case 'c' -> {
                    return control(i, inClass);
                }
                case 'x' -> {
                    int hex = hexDigits(i + 2, HEX2);
                    return hex >= 0 ? value(hex, i + 2 + HEX2) : identity(i, d);
                }
                case 'u' -> {
                    return unicodeEscape(i);
                }
                case 'p', 'P' -> {
                    int close = js.indexOf('}', i);
                    if (unicode && js.startsWith("{", i + 2) && close > 0) {
                        return new Atom(js.substring(i, close + 1), -1, close + 1);
                    }
                    return identity(i, d);
                }
                case 'k' -> {
                    return named(i, inClass);
                }
                default -> {
                    if (d >= '0' && d <= '9') {
                        return digits(i, inClass);
                    }
                    if (JS_SYNTAX_CHARS.indexOf(d) >= 0 || (inClass && d == '-')) {
                        return value(d, i + 2);
                    }
                    return identity(i, d);
                }
            }
        }

        private Atom value(int value, int end) {
            return new Atom(literal(value), value, end);
        }

        /** u 없으면 모르는 이스케이프는 그 글자 자체, u 면 오류. */
        private Atom identity(int i, char d) {
            if (unicode) {
                throw new PatternSyntaxException(BAD_ESCAPE, js, i);
            }
            return value(d, i + 2);
        }

        /** \cX. X 가 영문(묶음 안이면 숫자 · _ 도)이 아니면 u 없을 때 \ 와 c 를 글자로 본다. */
        private Atom control(int i, boolean inClass) {
            char x = i + 2 < js.length() ? js.charAt(i + 2) : 0;
            boolean letter = (x >= 'A' && x <= 'Z') || (x >= 'a' && x <= 'z');
            if (letter || (!unicode && inClass && ((x >= '0' && x <= '9') || x == '_'))) {
                return value(x % CONTROL_MODULO, i + 3);
            }
            if (unicode) {
                throw new PatternSyntaxException(BAD_ESCAPE, js, i);
            }
            // 백슬래시만 먹고 c 는 다음 글자로 읽힌다
            return value('\\', i + 1);
        }

        /** 백슬래시 u 넷 · u 모드의 u{…}(짝 서로게이트 두 개는 한 글자). u 없으면 꼴이 틀린 것은 글자 u. */
        private Atom unicodeEscape(int i) {
            int close = js.indexOf('}', i);
            if (unicode && js.startsWith("{", i + 2) && close > i + 3) {
                int cp = -1;
                try {
                    cp = Integer.parseInt(js.substring(i + 3, close), HEX_RADIX);
                } catch (NumberFormatException e) {
                    // 아래에서 오류로 알린다
                }
                if (cp < 0 || cp > Character.MAX_CODE_POINT) {
                    throw new PatternSyntaxException(BAD_ESCAPE, js, i);
                }
                return value(cp, close + 1);
            }
            int unit = hexDigits(i + 2, HEX4);
            if (unit < 0) {
                return identity(i, 'u');
            }
            int end = i + 2 + HEX4;
            int low = js.startsWith("\\u", end) ? hexDigits(end + 2, HEX4) : -1;
            if (unicode && Character.isHighSurrogate((char) unit) && low >= 0 && Character.isLowSurrogate((char) low)) {
                return value(Character.toCodePoint((char) unit, (char) low), end + 2 + HEX4);
            }
            return value(unit, end);
        }

        /** \k<이름>. 이름 있는 그룹이 하나도 없고 u 가 없으면 글자 k. */
        private Atom named(int i, boolean inClass) {
            boolean anyName = false;
            for (String name : names) {
                anyName |= name != null;
            }
            if (!anyName && !unicode) {
                return value('k', i + 2);
            }
            int close = js.indexOf('>', i);
            if (inClass || !js.startsWith("<", i + 2) || close < 0) {
                throw new PatternSyntaxException(BAD_ESCAPE, js, i);
            }
            int k = names.indexOf(js.substring(i + 3, close)) + 1;
            if (k == 0) {
                throw new PatternSyntaxException(UNKNOWN_GROUP_NAME, js, i);
            }
            return backReference(k, close + 1);
        }

        /** \숫자. 그룹 수 안이면 되참조, 아니면 u 없을 때 옛 8진수(8 · 9 는 글자), u 면 오류. \0 은 뒤에 숫자가 없으면 NUL. */
        private Atom digits(int i, boolean inClass) {
            int j = i + 1;
            while (j < js.length() && Character.isDigit(js.charAt(j))) {
                j++;
            }
            if (js.charAt(i + 1) == '0' && j == i + 2) {
                return value(0, i + 2);
            }
            if (!inClass && js.charAt(i + 1) != '0') {
                long number = Long.parseLong(js.substring(i + 1, Math.min(j, i + 1 + String.valueOf(Integer.MAX_VALUE).length())), DECIMAL_RADIX);
                if (number <= names.size()) {
                    return backReference((int) number, j);
                }
            }
            if (unicode) {
                throw new PatternSyntaxException(BAD_ESCAPE, js, i);
            }
            char first = js.charAt(i + 1);
            if (first == '8' || first == '9') {
                return value(first, i + 2);
            }
            int value = 0;
            int end = i + 1;
            while (end < js.length() && end < i + 1 + MAX_OCTAL_DIGITS && js.charAt(end) >= '0' && js.charAt(end) <= '7'
                    && value * OCTAL_RADIX + (js.charAt(end) - '0') <= MAX_LEGACY_OCTAL) {
                value = value * OCTAL_RADIX + (js.charAt(end) - '0');
                end++;
            }
            return value(value, end);
        }

        /**
         * 되참조. JS 는 맞지 않았거나 아직 닫히지 않은 그룹을 빈 글로 본다. 닫힌 그룹은 끝 표시 그룹(markerOf)이 잡혔으면 그 값을,
         * 아니면 빈 글을 맞춘다. 아직 닫히지 않은 그룹(앞쪽 참조 · 자기 안)은 늘 빈 글이다.
         */
        private Atom backReference(int k, int end) {
            if (markerOf[k] == 0) {
                return new Atom(NOTHING, -1, end);
            }
            String marker = "\\" + markerOf[k];
            return new Atom("(?:" + marker + "\\" + javaOf[k] + "|(?!" + marker + "))", -1, end);
        }

        /** from 부터 count 자리 16진수 값. 모자라면 -1. */
        private int hexDigits(int from, int count) {
            if (from + count > js.length()) {
                return -1;
            }
            int value = 0;
            for (int j = from; j < from + count; j++) {
                int digit = Character.digit(js.charAt(j), HEX_RADIX);
                if (digit < 0) {
                    return -1;
                }
                value = value * HEX_RADIX + digit;
            }
            return value;
        }

        private void emit(String text, int from) {
            if (out.length() + text.length() >= origin.length) {
                origin = java.util.Arrays.copyOf(origin, (out.length() + text.length()) * 2 + 1);
            }
            for (int k = 0; k < text.length(); k++) {
                origin[out.length() + k] = from;
            }
            out.append(text);
        }
    }

    /** 옮긴 글 한 토막. value 는 글자 하나를 뜻하면 그 값(u 면 코드 포인트, 아니면 UTF-16 값), 아니면 -1. end 는 원래 패턴에서 다음 위치. */
    private record Atom(String java, int value, int end) {}

    private static String hex(int value) {
        return "\\x{" + Integer.toHexString(value) + "}";
    }

    /** 잡는 그룹의 이름을 차례로(이름 없으면 null). 이름이 JS 식별자 꼴이 아니거나 겹치면 PatternSyntaxException. */
    private static List<String> scanGroups(String js) {
        List<String> names = new ArrayList<>();
        boolean inClass = false;
        int i = 0;
        while (i < js.length()) {
            char c = js.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
                // [] · [^] 의 ] 는 묶음 끝이다
                i += js.startsWith("[^", i) ? 1 : 0;
            } else if (c == '(' && !js.startsWith("(?", i)) {
                names.add(null);
            } else if (js.startsWith("(?<", i) && i + 3 < js.length() && js.charAt(i + 3) != '=' && js.charAt(i + 3) != '!') {
                int close = js.indexOf('>', i);
                String name = close < 0 ? "" : js.substring(i + 3, close);
                if (!isJsName(name)) {
                    throw new PatternSyntaxException(BAD_GROUP_NAME, js, i);
                }
                if (names.contains(name)) {
                    throw new PatternSyntaxException(DUPLICATE_GROUP_NAME, js, i);
                }
                names.add(name);
                i = close;
            }
            i++;
        }
        return names;
    }

    /** JS 그룹 이름: 식별자 첫 글자(또는 $ _) 다음 식별자 글자(또는 $). */
    private static boolean isJsName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        int first = name.codePointAt(0);
        if (!Character.isUnicodeIdentifierStart(first) && first != '$' && first != '_') {
            return false;
        }
        for (int cp : name.codePoints().toArray()) {
            if (!Character.isUnicodeIdentifierPart(cp) && cp != '$') {
                return false;
            }
        }
        return true;
    }

    /** i 자리의 { 가 {n} · {n,} · {n,m} 반복인지. */
    private static boolean isQuantifier(String s, int i) {
        int j = i + 1;
        int digits = j;
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }
        if (j == digits) {
            return false;
        }
        if (j < s.length() && s.charAt(j) == ',') {
            j++;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
        }
        return j < s.length() && s.charAt(j) == '}';
    }

    enum Repeat { ONCE, OPTIONAL, ONE_OR_MORE, ZERO_OR_MORE, EXACTLY, AT_LEAST, RANGE }

    /** 이름 붙은 그룹의 이름. java.util.regex 가 받는 꼴이며 JavaScript 도 받는다. */
    static final Pattern GROUP_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    // 묶음 밖 · 안에서 앞에 \ 를 붙일 글자. 모두 JS u 모드에서도 허용되는 이스케이프다.
    private static final String SPECIAL = "\\^$.|?*+()[]{}/";
    private static final String CLASS_SPECIAL = "\\]^-[";
    private static final String WORD_SEPARATOR = ",";

    /** 조각이 맞출 글자를 정하는 방식: 종류에서 고르기 · 정해진 낱말(쉼표로 여럿이면 그중 하나) · 아무 글자 · 이 글자 빼고. */
    enum Source { KINDS, WORDS, ANY, EXCEPT }

    /** 「종류에서 고르기」 의 글자 종류. range 는 [ ] 안에 넣을 꼴이다. SYMBOL 은 ASCII 문장 부호 전부. */
    enum CharKind {
        KOREAN("가-힣"), UPPER("A-Z"), LOWER("a-z"), DIGIT("0-9"), SPACE("\\s"), SYMBOL("!-/:-@\\[-`{-~");

        final String range;

        CharKind(String range) {
            this.range = range;
        }
    }

    /** 글 전체 모양일 때 「꼭 들어가야 할 것」. 각각 글 어딘가에 하나 이상 있어야 한다. */
    enum Need {
        LETTER("[A-Za-z]"), UPPER("[A-Z]"), LOWER("[a-z]"), DIGIT("\\d"), SYMBOL("[" + CharKind.SYMBOL.range + "]"), KOREAN("[가-힣]");

        final String atom;

        Need(String atom) {
            this.atom = atom;
        }
    }

    /** 반복 · 꺼내기 설정. lazy 는 「가능한 짧게」, name 은 비면 번호 그룹. */
    record Repeating(Repeat repeat, int n, int m, boolean lazy, boolean capture, String name) {
        static final Repeating ONCE = new Repeating(Repeat.ONCE, 0, 0, false, false, "");
    }

    /** 조합의 한 줄. 조각 하나이거나, 여러 조각을 함께 반복 · 꺼내는 묶음이다. */
    sealed interface Part permits Piece, Bunch {}

    /**
     * text 는 KINDS 에서 더할 글자, WORDS 에서 쉼표로 가른 낱말, EXCEPT 에서 뺄 글자.
     * before · after 는 이 조각 바로 앞에 있어야 / 바로 뒤에 와야 하는 글자(값에는 들어가지 않음). 비면 조건 없음.
     */
    record Piece(Source source, Set<CharKind> kinds, String text, Repeating repeating, String before, String after)
            implements Part {}

    /** anyOf 면 안의 조각 가운데 하나(또는), 아니면 차례로. */
    record Bunch(List<Piece> pieces, boolean anyOf, Repeating repeating) implements Part {}

    /** 글 모양 전체 설정. whole 이면 글 전체(^…$), wordOnly 면 낱말 단위(앞뒤가 WORD_CHAR 가 아님), needs · minLength 는 whole 일 때만 쓴다(0 이면 조건 없음). */
    record Shape(boolean whole, boolean wordOnly, Set<Need> needs, int minLength) {}

    enum PartProblem { NO_KINDS, NO_WORDS, NO_EXCEPT, RANGE, NAME, EMPTY_GROUP, DUPLICATE_NAME }

    /** 조합 전체의 고칠 점. 줄마다 problem 을 보고, 없으면 두 줄 이상이 같은 이름으로 꺼내는지 본다. 없으면 null. */
    static PartProblem problem(List<Part> parts) {
        List<String> names = new ArrayList<>();
        for (Part part : parts) {
            PartProblem problem = problem(part);
            if (problem != null) {
                return problem;
            }
            List<Repeating> repeatings = new ArrayList<>();
            if (part instanceof Bunch group) {
                repeatings.add(group.repeating());
                for (Piece piece : group.pieces()) {
                    repeatings.add(piece.repeating());
                }
            } else {
                repeatings.add(((Piece) part).repeating());
            }
            for (Repeating r : repeatings) {
                if (r.capture() && !r.name().isEmpty()) {
                    if (names.contains(r.name())) {
                        return PartProblem.DUPLICATE_NAME;
                    }
                    names.add(r.name());
                }
            }
        }
        return null;
    }

    /** 패턴을 만들 수 없게 고른 점. 없으면 null. 묶음은 안의 조각 문제를 먼저 돌려준다. */
    static PartProblem problem(Part part) {
        if (part instanceof Bunch group) {
            if (group.pieces().isEmpty()) {
                return PartProblem.EMPTY_GROUP;
            }
            for (Piece piece : group.pieces()) {
                PartProblem inner = problem(piece);
                if (inner != null) {
                    return inner;
                }
            }
            return repeatProblem(group.repeating());
        }
        Piece piece = (Piece) part;
        if (piece.source() == Source.KINDS && piece.kinds().isEmpty() && piece.text().isEmpty()) {
            return PartProblem.NO_KINDS;
        }
        if (piece.source() == Source.WORDS && words(piece.text()).isEmpty()) {
            return PartProblem.NO_WORDS;
        }
        if (piece.source() == Source.EXCEPT && piece.text().isEmpty()) {
            return PartProblem.NO_EXCEPT;
        }
        return repeatProblem(piece.repeating());
    }

    private static PartProblem repeatProblem(Repeating r) {
        if (r.repeat() == Repeat.RANGE && r.n() > r.m()) {
            return PartProblem.RANGE;
        }
        if (r.capture() && !r.name().isEmpty() && !GROUP_NAME.matcher(r.name()).matches()) {
            return PartProblem.NAME;
        }
        return null;
    }

    /**
     * 낱말 단위의 낱말 글자. \b 는 JS · Java 모두 ASCII 기준이라 한글 옆에서 맞지 않으므로 앞뒤 보기로 직접 건다.
     * \p{L} 은 JS 에서 u 플래그가 있어야 해서 두 문법에 다 통하는 묶음을 쓴다.
     */
    private static final String WORD_CHAR = "[가-힣A-Za-z0-9_]";
    private static final String WORD_START = "(?<!" + WORD_CHAR + ")";
    private static final String WORD_END = "(?!" + WORD_CHAR + ")";

    /** parts 를 차례로 이은 패턴. 모든 줄이 problem 이 없어야 한다. 조각이 없고 조건만 있으면 본문은 「아무 글자 여럿」. */
    static String compose(List<Part> parts, Shape shape) {
        StringBuilder body = new StringBuilder();
        for (Part part : parts) {
            body.append(part instanceof Bunch group ? group(group) : piece((Piece) part));
        }
        if (!shape.whole()) {
            return shape.wordOnly() && !body.isEmpty() ? WORD_START + body + WORD_END : body.toString();
        }
        StringBuilder out = new StringBuilder("^");
        for (Need need : Need.values()) {
            if (shape.needs().contains(need)) {
                out.append("(?=.*").append(need.atom).append(')');
            }
        }
        if (shape.minLength() > 0) {
            out.append("(?=.{").append(shape.minLength()).append(",}$)");
        }
        if (body.isEmpty() && out.length() == 1) {
            return "";
        }
        return out.append(body.isEmpty() ? ".*" : body).append('$').toString();
    }

    private static String group(Bunch group) {
        StringJoiner inner = new StringJoiner(group.anyOf() ? "|" : "");
        for (Piece piece : group.pieces()) {
            inner.add(piece(piece));
        }
        // 「또는」 은 반복 · 꺼내기가 없어도 앞뒤 조각과 섞이지 않게 늘 감싼다
        boolean several = group.pieces().size() > 1 || needsWrap(group.pieces().get(0));
        String atom = group.anyOf() && group.pieces().size() > 1 && !group.repeating().capture() && group.repeating().repeat() == Repeat.ONCE
                ? "(?:" + inner + ")" : inner.toString();
        return wrap(atom, several, group.repeating());
    }

    /** 꺼내기는 반복까지 감싸서(「(\d+)」) 반복한 전체를 꺼낸다. 앞 · 뒤 조건은 꺼내기 밖에 둔다. */
    static String piece(Piece piece) {
        String atom = switch (piece.source()) {
            case KINDS -> kindsAtom(piece.kinds(), piece.text());
            case WORDS -> wordsAtom(words(piece.text()));
            case ANY -> ".";
            case EXCEPT -> exceptAtom(piece.text());
        };
        String body = wrap(atom, needsWrap(piece), piece.repeating());
        String before = piece.before().isEmpty() ? "" : "(?<=" + escape(piece.before(), SPECIAL) + ")";
        String after = piece.after().isEmpty() ? "" : "(?=" + escape(piece.after(), SPECIAL) + ")";
        return before + body + after;
    }

    /**
     * 반복을 붙이려면 (?:…) 로 감싸야 하는 조각인지: UTF-16 두 칸 이상인 낱말 하나. 여러 낱말은 이미 (?:a|b) 다.
     * 이모지 한 글자도 감싼다. JS u 없는 모드는 😀+ 의 + 를 뒤 반쪽에만 건다.
     */
    private static boolean needsWrap(Piece piece) {
        if (piece.source() != Source.WORDS) {
            return false;
        }
        List<String> words = words(piece.text());
        return words.size() == 1 && words.get(0).length() > 1;
    }

    private static String wrap(String atom, boolean several, Repeating r) {
        String quantifier = switch (r.repeat()) {
            case ONCE -> "";
            case OPTIONAL -> "?";
            case ONE_OR_MORE -> "+";
            case ZERO_OR_MORE -> "*";
            case EXACTLY -> "{" + r.n() + "}";
            case AT_LEAST -> "{" + r.n() + ",}";
            case RANGE -> "{" + r.n() + "," + r.m() + "}";
        };
        // 정확히 N번은 짧게 · 길게가 같다
        if (r.lazy() && !quantifier.isEmpty() && r.repeat() != Repeat.EXACTLY) {
            quantifier += "?";
        }
        String body = (several && !quantifier.isEmpty() ? "(?:" + atom + ")" : atom) + quantifier;
        if (!r.capture()) {
            return body;
        }
        return (r.name().isEmpty() ? "(" : "(?<" + r.name() + ">") + body + ")";
    }

    /**
     * 고른 종류와 더할 글자를 한 [ ] 로. 숫자만 · 공백만이면 \d · \s 로 줄인다.
     * BMP 밖 글자(이모지)는 JS u 없는 모드의 [ ] 가 반쪽씩 맞추므로 (?:[…]|😀) 처럼 또는으로 붙인다.
     */
    private static String kindsAtom(Set<CharKind> kinds, String extra) {
        if (extra.isEmpty() && kinds.size() == 1) {
            if (kinds.contains(CharKind.DIGIT)) {
                return "\\d";
            }
            if (kinds.contains(CharKind.SPACE)) {
                return "\\s";
            }
        }
        StringBuilder content = new StringBuilder();
        for (CharKind kind : CharKind.values()) {
            if (kinds.contains(kind)) {
                content.append(kind.range);
            }
        }
        StringBuilder bmp = new StringBuilder();
        List<String> astral = astral(extra, bmp);
        String set = content.isEmpty() && bmp.isEmpty() ? "" : "[" + content + classChars(bmp.toString()) + "]";
        if (astral.isEmpty()) {
            return set;
        }
        StringJoiner alternatives = new StringJoiner("|", "(?:", ")");
        if (!set.isEmpty()) {
            alternatives.add(set);
        }
        for (String symbol : astral) {
            alternatives.add(symbol);
        }
        return alternatives.toString();
    }

    /**
     * 「이 글자 빼고」. BMP 밖 글자는 [^ ] 대신 앞보기로 뺀다: (?:(?!😀)(?:짝|[^…])).
     * 짝(SURROGATE_PAIR)을 먼저 두어 JS u 없는 모드에서도 다른 이모지를 반쪽으로 자르지 않는다. Java 는 짝을 한 글자로 읽어 [^…] 로 간다.
     */
    private static String exceptAtom(String text) {
        StringBuilder bmp = new StringBuilder();
        List<String> astral = astral(text, bmp);
        if (astral.isEmpty()) {
            return "[^" + classChars(text) + "]";
        }
        String rest = bmp.isEmpty() ? ANY_CHAR : "[^" + classChars(bmp.toString()) + "]";
        return "(?:(?!" + String.join("|", astral) + ")(?:" + SURROGATE_PAIR + "|" + rest + "))";
    }

    private static final String SURROGATE_PAIR = "[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]";

    /** text 의 BMP 밖 글자를 겹치지 않게 돌려주고, 나머지 글자는 bmp 에 더한다. */
    private static List<String> astral(String text, StringBuilder bmp) {
        List<String> astral = new ArrayList<>();
        for (int cp : text.codePoints().toArray()) {
            String symbol = Character.toString(cp);
            if (Character.isBmpCodePoint(cp)) {
                bmp.append(symbol);
            } else if (!astral.contains(symbol)) {
                astral.add(symbol);
            }
        }
        return astral;
    }

    private static String wordsAtom(List<String> words) {
        if (words.size() == 1) {
            return escape(words.get(0), SPECIAL);
        }
        StringJoiner alternatives = new StringJoiner("|", "(?:", ")");
        for (String word : words) {
            alternatives.add(escape(word, SPECIAL));
        }
        return alternatives.toString();
    }

    /** 쉼표로 가른 낱말. 앞뒤 공백은 떼고 빈 것 · 겹친 것은 뺀다. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.split(WORD_SEPARATOR)) {
            String trimmed = word.strip();
            if (!trimmed.isEmpty() && !words.contains(trimmed)) {
                words.add(trimmed);
            }
        }
        return words;
    }

    private static final Repeating ONE_OR_MORE = new Repeating(Repeat.ONE_OR_MORE, 0, 0, false, false, "");
    private static final Repeating ZERO_OR_MORE = new Repeating(Repeat.ZERO_OR_MORE, 0, 0, false, false, "");
    private static final Piece ONE_SPACE = new Piece(Source.KINDS, EnumSet.of(CharKind.SPACE), "", Repeating.ONCE, "", "");

    /**
     * 「질문 따라가기」 답으로 만든 패턴. 한 낱말은 kinds · extra 글자로 length 만큼, spaced 면 그 낱말이 띄어쓰기 한 칸을 사이에 두고
     * 여럿 올 수 있다(예: 김 철수). whole 이면 글 전체(^…$)이고 needs 를 앞에 건다. 고른 글자가 없으면 null.
     * 찾기에서 낱말 경계(\b)는 걸지 않는다. \b 는 ASCII 기준이라 한글 앞뒤에서는 맞지 않는다.
     */
    static String ask(boolean whole, Set<CharKind> kinds, String extra, Repeating length, boolean spaced, Set<Need> needs) {
        Piece word = new Piece(Source.KINDS, kinds, extra, length, "", "");
        if (problem(word) != null) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        parts.add(word);
        if (spaced) {
            parts.add(new Bunch(List.of(ONE_SPACE, word), false, ZERO_OR_MORE));
        }
        return compose(parts, new Shape(whole, false, whole ? needs : Set.of(), 0));
    }

    /** 「예시로 만들기」 결과. rejectsAll 이면 틀려야 할 글이 모두 맞지 않는다. */
    record Guess(String pattern, boolean rejectsAll) {}

    // 예시 하나를 맞춰 볼 때 시간 한도. 만든 패턴은 되돌이가 없어 넉넉하다.
    private static final long GUESS_TIMEOUT_MS = 200;

    /** 같은 종류 글자가 이어진 한 토막. kind 가 null 이면 literal 글자 하나가 count 번. */
    private record Run(CharKind kind, String literal, int count) {
        boolean sameAs(Run other) {
            return kind != null ? kind == other.kind : other.kind == null && literal.equals(other.literal);
        }
    }

    /**
     * 맞아야 할 글(good)에 모두 맞는 글 전체 패턴(^…$)을 만든다. 빈 줄은 뺀다. good 이 비면 null.
     * 후보를 좁은 것부터 차례로 보고, good 에 모두 맞으면서 bad 에 하나도 맞지 않는 첫 후보를 고른다. 그런 후보가 없으면 첫 후보.
     * 후보: 1 글자 종류 차례와 길이가 같은 꼴(010-1234-5678), 2 차례만 같은 꼴, 3 띄어쓰기로 이은 낱말(김 철수),
     * 4 나온 글자 종류만으로 최소~최대 길이, 5 나온 글자 종류만으로 아무 길이.
     */
    static Guess guess(List<String> good, List<String> bad) {
        List<List<Run>> shapes = new ArrayList<>();
        for (String text : good) {
            if (!text.isEmpty()) {
                shapes.add(runs(text));
            }
        }
        if (shapes.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        if (sameOrder(shapes)) {
            candidates.add(sequence(shapes, false));
            candidates.add(sequence(shapes, true));
        }
        Set<CharKind> kinds = EnumSet.noneOf(CharKind.class);
        StringBuilder literals = new StringBuilder();
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (List<Run> shape : shapes) {
            int length = 0;
            for (Run run : shape) {
                length += run.count();
                if (run.kind() != null) {
                    kinds.add(run.kind());
                } else if (literals.indexOf(run.literal()) < 0) {
                    literals.append(run.literal());
                }
            }
            min = Math.min(min, length);
            max = Math.max(max, length);
        }
        if (kinds.remove(CharKind.SPACE)) {
            String words = ask(true, kinds, literals.toString(), ONE_OR_MORE, true, Set.of());
            if (words != null) {
                candidates.add(words);
            }
            kinds.add(CharKind.SPACE);
        }
        Piece bag = new Piece(Source.KINDS, kinds, literals.toString(), count(min, max), "", "");
        candidates.add(compose(List.of(bag), new Shape(true, false, Set.of(), 0)));
        candidates.add(compose(List.of(new Piece(Source.KINDS, kinds, literals.toString(), ONE_OR_MORE, "", "")), new Shape(true, false, Set.of(), 0)));

        String first = null;
        for (String candidate : candidates) {
            if (!fitsAll(candidate, good)) {
                continue;
            }
            if (first == null) {
                first = candidate;
            }
            if (missesAll(candidate, bad)) {
                return new Guess(candidate, true);
            }
        }
        // 마지막 후보는 나온 글자를 모두 담은 + 라 good 에 늘 맞는다
        return new Guess(first, false);
    }

    private static Repeating count(int min, int max) {
        if (min == max) {
            return min == 1 ? Repeating.ONCE : new Repeating(Repeat.EXACTLY, min, 0, false, false, "");
        }
        return new Repeating(Repeat.RANGE, min, max, false, false, "");
    }

    private static List<Run> runs(String text) {
        List<Run> runs = new ArrayList<>();
        for (int cp : text.codePoints().toArray()) {
            CharKind kind = kindOf(cp);
            Run run = new Run(kind, kind == null ? Character.toString(cp) : "", 1);
            Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last.sameAs(run)) {
                runs.set(runs.size() - 1, new Run(last.kind(), last.literal(), last.count() + 1));
            } else {
                runs.add(run);
            }
        }
        return runs;
    }

    /** CharKind 범위에 드는 글자의 종류. 특수문자는 종류로 넓히지 않고 그 글자 그대로 쓰도록 null. */
    private static CharKind kindOf(int cp) {
        if (cp >= '가' && cp <= '힣') {
            return CharKind.KOREAN;
        }
        if (cp >= 'A' && cp <= 'Z') {
            return CharKind.UPPER;
        }
        if (cp >= 'a' && cp <= 'z') {
            return CharKind.LOWER;
        }
        if (cp >= '0' && cp <= '9') {
            return CharKind.DIGIT;
        }
        return cp == ' ' || cp == '\t' ? CharKind.SPACE : null;
    }

    private static boolean sameOrder(List<List<Run>> shapes) {
        List<Run> first = shapes.get(0);
        for (List<Run> shape : shapes) {
            if (shape.size() != first.size()) {
                return false;
            }
            for (int i = 0; i < shape.size(); i++) {
                if (!shape.get(i).sameAs(first.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 토막마다 한 조각. anyLength 면 글자 종류 토막은 「하나 이상」, 아니면 예시의 최소~최대 길이. 특수문자 토막은 늘 길이 그대로. */
    private static String sequence(List<List<Run>> shapes, boolean anyLength) {
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < shapes.get(0).size(); i++) {
            int min = Integer.MAX_VALUE;
            int max = 0;
            for (List<Run> shape : shapes) {
                min = Math.min(min, shape.get(i).count());
                max = Math.max(max, shape.get(i).count());
            }
            Run run = shapes.get(0).get(i);
            Repeating repeating = anyLength && run.kind() != null ? ONE_OR_MORE : count(min, max);
            parts.add(run.kind() != null
                    ? new Piece(Source.KINDS, EnumSet.of(run.kind()), "", repeating, "", "")
                    : new Piece(Source.KINDS, EnumSet.noneOf(CharKind.class), run.literal(), repeating, "", ""));
        }
        return compose(parts, new Shape(true, false, Set.of(), 0));
    }

    private static boolean fitsAll(String pattern, List<String> texts) {
        for (String text : texts) {
            if (!text.isEmpty() && check(Flavor.JAVA, pattern, Set.of(), text, GUESS_TIMEOUT_MS).verdict() != Verdict.FULL) {
                return false;
            }
        }
        return true;
    }

    private static boolean missesAll(String pattern, List<String> texts) {
        for (String text : texts) {
            if (!text.isEmpty() && check(Flavor.JAVA, pattern, Set.of(), text, GUESS_TIMEOUT_MS).verdict() == Verdict.FULL) {
                return false;
            }
        }
        return true;
    }

    enum Verdict { FULL, PARTIAL, NONE }

    /** 「확인할 글」 결과. PARTIAL 이면 part 가 처음 맞은 부분. problem 이 NONE 이 아니면 verdict 는 null. */
    record Check(Verdict verdict, String part, Problem problem) {}

    /** input 전체가 패턴에 맞는지(입력값 검사), 아니면 일부만 맞는지. 플래그는 run 과 같다(g 는 상관없음). */
    static Check check(Flavor flavor, String pattern, Set<Flag> flags, String input, long timeoutMs) {
        Compiled compiled;
        try {
            compiled = compile(flavor, pattern, flags);
        } catch (PatternSyntaxException e) {
            return new Check(null, null, Problem.SYNTAX);
        }
        try {
            Matcher m = compiled.pattern().matcher(new TimedText(compiled.subject(input), System.nanoTime() + timeoutMs * 1_000_000L));
            if (m.matches()) {
                return new Check(Verdict.FULL, input, Problem.NONE);
            }
            return m.find() ? new Check(Verdict.PARTIAL, input.substring(m.start(), m.end()), Problem.NONE) : new Check(Verdict.NONE, null, Problem.NONE);
        } catch (Timeout e) {
            return new Check(null, null, Problem.TIMEOUT);
        } catch (StackOverflowError e) {
            return new Check(null, null, Problem.TOO_DEEP);
        }
    }

    /** 같은 글자는 한 번만 넣는다. 그래서 Java 의 && 교집합이 생기지 않는다. */
    private static String classChars(String text) {
        StringBuilder unique = new StringBuilder();
        for (int cp : text.codePoints().toArray()) {
            if (unique.indexOf(Character.toString(cp)) < 0) {
                unique.appendCodePoint(cp);
            }
        }
        return escape(unique.toString(), CLASS_SPECIAL);
    }

    private static String escape(String text, String special) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (special.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 코드에 붙여 넣을 글. JavaScript 는 /패턴/플래그 리터럴, Java 는 Pattern.compile("…", 플래그). */
    static String toCode(Flavor flavor, String pattern, Set<Flag> flags) {
        StringBuilder out = new StringBuilder();
        if (flavor == Flavor.JAVASCRIPT) {
            // 빈 리터럴 // 은 JS 에서 주석이 된다
            out.append('/').append(pattern.isEmpty() ? "(?:)" : escapeSlash(pattern)).append('/');
            for (Flag flag : Flag.values()) {
                if (flag.flavor == flavor && flags.contains(flag)) {
                    out.append(flag.label);
                }
            }
            return out.toString();
        }
        out.append("Pattern.compile(\"");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        out.append('"');
        String separator = ", ";
        for (Flag flag : Flag.values()) {
            if (flag.flavor == flavor && flags.contains(flag)) {
                out.append(separator).append("Pattern.").append(flag.label);
                separator = " | ";
            }
        }
        return out.append(')').toString();
    }

    /** 이스케이프되지 않은 / 앞에 \ 를 붙인다. */
    private static String escapeSlash(String pattern) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                out.append(c).append(pattern.charAt(++i));
            } else {
                out.append(c == '/' ? "\\/" : String.valueOf(c));
            }
        }
        return out.toString();
    }

    /** 자주 쓰는 정규식 하나. category 는 화면에서 묶는 분류, flags 는 JS 플래그 글자(g i m s u)이고 Java 에서는 같은 뜻의 플래그로 바꿔 쓴다. */
    record Preset(String category, String name, String pattern, String flags, String replacement, String sample) {}

    private static final String PRESET_COLUMN = "\t";
    private static final String PRESET_RESOURCE = "/regex/presets.tsv";
    private static final int PRESET_COLUMNS = 5;
    private static final String CATEGORY_PREFIX = "## ";

    /**
     * 「이름 TAB 패턴 TAB 플래그 TAB 바꿀 글 TAB 예시 글」 줄들을 읽는다. 「## 분류」 줄은 그 아래 줄들의 분류다(앞에 없으면 빈 분류).
     * 빈 줄과 그 밖의 # 로 시작하는 줄은 건너뛴다. 예시 글의 「\n」 은 줄바꿈이다. 칸 수가 맞지 않으면 줄 번호와 함께 IllegalArgumentException.
     */
    static List<Preset> parsePresets(String tsv) {
        List<Preset> presets = new ArrayList<>();
        String category = "";
        String[] lines = tsv.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith(CATEGORY_PREFIX)) {
                category = line.substring(CATEGORY_PREFIX.length()).strip();
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split(PRESET_COLUMN, -1);
            if (cols.length != PRESET_COLUMNS) {
                throw new IllegalArgumentException((i + 1) + "번째 줄: 칸이 " + cols.length + "개(" + PRESET_COLUMNS + "개여야 함)");
            }
            presets.add(new Preset(category, cols[0], cols[1], cols[2], cols[3], cols[4].replace("\\n", "\n")));
        }
        return presets;
    }

    private static final Pattern JS_NAMED_REFERENCE = Pattern.compile("\\$<(\\w+)>");
    private static final Pattern JAVA_NAMED_REFERENCE = Pattern.compile("\\$\\{(\\w+)}");

    /** 바꿀 글의 이름 그룹 표기를 to 문법으로 옮긴다. JavaScript 는 $<이름>, Java 는 ${이름}. 다른 글자는 그대로. */
    static String convertReplacement(Flavor to, String replacement) {
        return to == Flavor.JAVA
                ? JS_NAMED_REFERENCE.matcher(replacement).replaceAll("\\${$1}")
                : JAVA_NAMED_REFERENCE.matcher(replacement).replaceAll("\\$<$1>");
    }

    /** 플러그인에 든 PRESET_RESOURCE 를 읽는다. 없거나 읽지 못하면 IllegalStateException. */
    static List<Preset> loadPresets() {
        try (InputStream in = RegexBench.class.getResourceAsStream(PRESET_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(PRESET_RESOURCE + " 가 없습니다");
            }
            return parsePresets(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(PRESET_RESOURCE + " 를 읽지 못했습니다", e);
        }
    }

    /** JS 플래그 글자를 두 문법의 플래그로. g · u 는 Java 에 짝이 없다. */
    static Set<Flag> flagsOf(String letters) {
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        for (Flag flag : Flag.values()) {
            if (flag.flavor == Flavor.JAVASCRIPT && letters.contains(flag.label)) {
                flags.add(flag);
                Flag java = switch (flag) {
                    case JS_IGNORE_CASE -> Flag.CASE_INSENSITIVE;
                    case JS_MULTILINE -> Flag.MULTILINE;
                    case JS_DOT_ALL -> Flag.DOTALL;
                    default -> null;
                };
                if (java != null) {
                    flags.add(java);
                }
            }
        }
        return flags;
    }

    private static final class Timeout extends RuntimeException {
        Timeout() {
            super(null, null, false, false);
        }
    }

    /** 글자를 읽을 때마다 마감 시각을 넘었는지 보고, 넘었으면 Timeout 을 던진다. 되돌이 폭주를 끊는 용도다. */
    private record TimedText(String text, long deadline) implements CharSequence {
        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new Timeout();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
