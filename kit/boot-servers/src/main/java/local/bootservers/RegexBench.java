package local.bootservers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 정규식 작업대의 일치 · 바꾸기 계산. IntelliJ 클래스를 쓰지 않는다(점검 RegexBenchCheck).
 * JavaScript 문법은 java.util.regex 로 옮겨 돌린다. 옮기는 것: 빈 묶음 [] · [^], 묶음 안 [ · &&,
 * 반복이 아닌 { , u 플래그의 코드 포인트 이스케이프(백슬래시 u{…}). 바꾸기 글은 String.prototype.replace 규칙($$ $& $` $' $n $nn $<이름>)을 따른다.
 * \s · \S 는 JS 공백 목록(JS_SPACES, ECMAScript WhiteSpace + LineTerminator)으로 옮긴다.
 * shortcut: u 없는 「.」 이 UTF-16 한 칸(이모지 반쪽)에 맞는 것은 흉내 내지 않는다. 틀린 결과가 보고되면 옮김에 더한다.
 */
final class RegexBench {

    enum Flavor { JAVASCRIPT, JAVA }

    /** label 은 화면에 보일 플래그 이름(JS 는 리터럴 뒤 글자, Java 는 Pattern 상수 이름)이다. */
    enum Flag {
        JS_GLOBAL(Flavor.JAVASCRIPT, "g", 0),
        // JS i 는 u 가 없어도 ASCII 밖 글자까지 대소문자를 가린다
        JS_IGNORE_CASE(Flavor.JAVASCRIPT, "i", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
        JS_MULTILINE(Flavor.JAVASCRIPT, "m", Pattern.MULTILINE),
        JS_DOT_ALL(Flavor.JAVASCRIPT, "s", Pattern.DOTALL),
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
        String source = js ? toJava(pattern, flags.contains(Flag.JS_UNICODE)) : pattern;
        int bits = 0;
        for (Flag flag : flags) {
            if (flag.flavor == flavor) {
                bits |= flag.bits;
            }
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(source, bits);
        } catch (PatternSyntaxException e) {
            // 옮기며 글자 수가 바뀌었으면 위치가 사용자가 친 패턴과 맞지 않는다
            int index = source.equals(pattern) ? e.getIndex() : -1;
            return new Result(List.of(), false, null, Problem.SYNTAX, e.getDescription(), index);
        }
        String[] names = groupNames(compiled);
        boolean all = !js || flags.contains(Flag.JS_GLOBAL);
        CharSequence timed = new TimedText(text, System.nanoTime() + timeoutMs * 1_000_000L);
        List<Match> matches = new ArrayList<>();
        boolean truncated = false;
        try {
            Matcher m = compiled.matcher(timed);
            while (m.find()) {
                if (matches.size() == MAX_MATCHES) {
                    truncated = true;
                    break;
                }
                matches.add(toMatch(m, names));
                if (!all) {
                    break;
                }
            }
            String replaced;
            try {
                replaced = replace(compiled.matcher(timed), js, all, text, replacement, names);
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

    private static Match toMatch(Matcher m, String[] names) {
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i <= m.groupCount(); i++) {
            groups.add(new Group(i, names[i], m.start(i), m.end(i), m.group(i)));
        }
        return new Match(m.start(), m.end(), groups);
    }

    /** 그룹 번호 → 이름. 이름 없는 번호는 null. */
    private static String[] groupNames(Pattern pattern) {
        String[] names = new String[pattern.matcher("").groupCount() + 1];
        for (Map.Entry<String, Integer> e : pattern.namedGroups().entrySet()) {
            names[e.getValue()] = e.getKey();
        }
        return names;
    }

    private static String replace(Matcher m, boolean js, boolean all, String text, String replacement, String[] names) {
        if (!js) {
            return all ? m.replaceAll(replacement) : m.replaceFirst(replacement);
        }
        boolean named = false;
        for (String name : names) {
            named |= name != null;
        }
        boolean hasNames = named;
        return all
                ? m.replaceAll(r -> Matcher.quoteReplacement(expandJs(replacement, r.start(), r.end(), m, text, hasNames)))
                : m.replaceFirst(r -> Matcher.quoteReplacement(expandJs(replacement, r.start(), r.end(), m, text, hasNames)));
    }

    /** String.prototype.replace 의 GetSubstitution. 뜻이 없는 $ 표기는 글자 그대로 둔다. */
    static String expandJs(String replacement, int start, int end, Matcher m, String text, boolean hasNames) {
        StringBuilder out = new StringBuilder();
        int groups = m.groupCount();
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
                    appendGroup(out, m, two);
                    i += 3;
                } else if (one >= 1 && one <= groups) {
                    appendGroup(out, m, one);
                    i += 2;
                } else {
                    out.append(c);
                    i++;
                }
            } else if (next == '<' && hasNames && replacement.indexOf('>', i + 2) > 0) {
                int close = replacement.indexOf('>', i + 2);
                String name = replacement.substring(i + 2, close);
                try {
                    appendGroup(out, m, m.pattern().namedGroups().getOrDefault(name, -1));
                } catch (IndexOutOfBoundsException ignored) {
                    // 없는 이름은 JS 에서 undefined → 빈 글
                }
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void appendGroup(StringBuilder out, Matcher m, int group) {
        if (group < 0) {
            throw new IndexOutOfBoundsException(group);
        }
        String value = m.group(group);
        if (value != null) {
            out.append(value);
        }
    }

    /** ECMAScript 의 WhiteSpace · LineTerminator(\s 가 맞는 글자). Java \s 는 이 가운데 ASCII 만 맞는다. */
    private static final String JS_SPACES =
            "\\t\\n\\x0B\\f\\r\\x20\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}";

    /** JavaScript 정규식을 java.util.regex 에서 같은 뜻이 되게 옮긴다. */
    static String toJava(String js, boolean unicode) {
        StringBuilder out = new StringBuilder();
        boolean inClass = false;
        int i = 0;
        while (i < js.length()) {
            char c = js.charAt(i);
            if (c == '\\' && i + 1 < js.length()) {
                int close = js.indexOf('}', i);
                if (unicode && js.startsWith("u{", i + 1) && close > 0) {
                    // Java 의 같은 표기는 \x{…} 다
                    out.append("\\x").append(js, i + 2, close + 1);
                    i = close + 1;
                } else if (js.charAt(i + 1) == 's') {
                    out.append(inClass ? JS_SPACES : "[" + JS_SPACES + "]");
                    i += 2;
                } else if (js.charAt(i + 1) == 'S') {
                    // 묶음 안에서는 Java 겹 묶음(합집합)으로 넣는다. [^…[^공백]] 도 Java 9 부터 바깥 ^ 가 전체에 걸린다.
                    out.append("[^").append(JS_SPACES).append(']');
                    i += 2;
                } else {
                    out.append(js, i, i + 2);
                    i += 2;
                }
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                    out.append(c);
                } else if (c == '[') {
                    // Java 는 묶음 안의 [ 를 겹 묶음으로 본다
                    out.append("\\[");
                } else if (c == '&' && i + 1 < js.length() && js.charAt(i + 1) == '&') {
                    // Java 는 묶음 안의 && 를 교집합으로 본다
                    out.append("&\\&");
                    i++;
                } else {
                    out.append(c);
                }
            } else if (js.startsWith("[]", i)) {
                out.append("(?!)");
                i++;
            } else if (js.startsWith("[^]", i)) {
                out.append("[\\s\\S]");
                i += 2;
            } else if (c == '[') {
                inClass = true;
                out.append(c);
                // 묶음 첫 자리의 ^ 다음 ] 는 JS 에서 묶음 끝이다. Java 는 그 ] 를 글자로 보므로 따로 막지 않아도 위 두 경우가 걸러 낸다.
            } else if (c == '{' && !isQuantifier(js, i)) {
                out.append("\\{");
            } else {
                out.append(c);
            }
            i++;
        }
        return out.toString();
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

    /** 조각 더하기의 글자 종류. 줄 처음 · 줄 끝은 반복 · 꺼내기를 받지 않는다. */
    enum Kind { TEXT, ONE_OF, NONE_OF, DIGIT, LETTER, ALNUM, KOREAN, SPACE, ANY, LINE_START, LINE_END }

    enum Repeat { ONCE, OPTIONAL, ONE_OR_MORE, ZERO_OR_MORE, EXACTLY, AT_LEAST, RANGE }

    /** 이름 붙은 그룹의 이름. java.util.regex 가 받는 꼴이며 JavaScript 도 받는다. */
    static final Pattern GROUP_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    // 묶음 밖 · 안에서 앞에 \ 를 붙일 글자. 모두 JS u 모드에서도 허용되는 이스케이프다.
    private static final String SPECIAL = "\\^$.|?*+()[]{}/";
    private static final String CLASS_SPECIAL = "\\]^-[";

    /**
     * 조각 하나를 패턴 글로 만든다. TEXT · ONE_OF · NONE_OF 는 text 가 비어 있지 않아야 하고,
     * RANGE 는 n <= m, capture 의 name 은 비었거나 GROUP_NAME 꼴이어야 한다(화면이 미리 확인한다).
     * 꺼내기는 반복까지 감싸서(「(\d+)」) 반복한 전체를 꺼낸다.
     */
    static String piece(Kind kind, String text, Repeat repeat, int n, int m, boolean capture, String name) {
        String atom = switch (kind) {
            case TEXT -> escape(text, SPECIAL);
            case ONE_OF -> "[" + classChars(text) + "]";
            case NONE_OF -> "[^" + classChars(text) + "]";
            case DIGIT -> "\\d";
            case LETTER -> "[A-Za-z]";
            case ALNUM -> "[A-Za-z0-9]";
            case KOREAN -> "[가-힣]";
            case SPACE -> "\\s";
            case ANY -> ".";
            case LINE_START -> "^";
            case LINE_END -> "$";
        };
        if (kind == Kind.LINE_START || kind == Kind.LINE_END) {
            return atom;
        }
        String quantifier = switch (repeat) {
            case ONCE -> "";
            case OPTIONAL -> "?";
            case ONE_OR_MORE -> "+";
            case ZERO_OR_MORE -> "*";
            case EXACTLY -> "{" + n + "}";
            case AT_LEAST -> "{" + n + ",}";
            case RANGE -> "{" + n + "," + m + "}";
        };
        boolean several = kind == Kind.TEXT && text.codePointCount(0, text.length()) > 1;
        String body = (several && !quantifier.isEmpty() ? "(?:" + atom + ")" : atom) + quantifier;
        if (!capture) {
            return body;
        }
        return (name == null || name.isEmpty() ? "(" : "(?<" + name + ">") + body + ")";
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

    /** 자주 쓰는 정규식 하나. flags 는 JS 플래그 글자(g i m s u)이고 Java 에서는 같은 뜻의 플래그로 바꿔 쓴다. */
    record Preset(String name, String pattern, String flags, String replacement, String sample) {}

    private static final String PRESET_COLUMN = "\t";
    private static final String PRESET_RESOURCE = "/regex/presets.tsv";
    private static final int PRESET_COLUMNS = 5;

    /**
     * 「이름 TAB 패턴 TAB 플래그 TAB 바꿀 글 TAB 예시 글」 줄들을 읽는다. 빈 줄과 # 로 시작하는 줄은 건너뛴다.
     * 예시 글의 「\n」 은 줄바꿈이다. 칸 수가 맞지 않으면 줄 번호와 함께 IllegalArgumentException.
     */
    static List<Preset> parsePresets(String tsv) {
        List<Preset> presets = new ArrayList<>();
        String[] lines = tsv.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split(PRESET_COLUMN, -1);
            if (cols.length != PRESET_COLUMNS) {
                throw new IllegalArgumentException((i + 1) + "번째 줄: 칸이 " + cols.length + "개(" + PRESET_COLUMNS + "개여야 함)");
            }
            presets.add(new Preset(cols[0], cols[1], cols[2], cols[3], cols[4].replace("\\n", "\n")));
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
