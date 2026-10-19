package local.bootservers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 고른 글 안의 식별자 표기법을 바꾼다. 문자열 · 문자 리터럴(큰 · 작은 따옴표, 텍스트 블록)과 줄 · 블록 주석은 그대로 둔다.
 * 글 전체가 식별자 하나면 무조건 바꾸고, 여러 토큰이면 두 낱말 이상인 식별자만 바꾼다.
 * 한 낱말(public · int · name)과 파스칼 표기(UserDto · String 같은 타입 이름)는 키워드 · 타입으로 보고 둔다.
 */
public final class CaseConvert {

    public enum Style { CAMEL, PASCAL, SNAKE, UPPER_SNAKE }

    private CaseConvert() {
    }

    public static String convert(String text, Style style) {
        String trimmed = text.strip();
        if (isIdentifier(trimmed)) {
            int start = text.indexOf(trimmed);
            return text.substring(0, start) + join(words(trimmed), style) + text.substring(start + trimmed.length());
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            int end;
            if (text.startsWith("\"\"\"", i)) {
                end = closeAt(text, i + 3, "\"\"\"");
            } else if (c == '"' || c == '\'') {
                end = quoteEnd(text, i);
            } else if (text.startsWith("//", i)) {
                int nl = text.indexOf('\n', i);
                end = nl < 0 ? n : nl;
            } else if (text.startsWith("/*", i)) {
                end = closeAt(text, i + 2, "*/");
            } else if (Character.isJavaIdentifierPart(c)) {
                end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String token = text.substring(i, end);
                out.append(Character.isDigit(c) ? token : convertToken(token, style));
                i = end;
                continue;
            } else {
                end = i + 1;
            }
            out.append(text, i, end);
            i = end;
        }
        return out.toString();
    }

    /** 여러 토큰 속 식별자 하나. 숫자 리터럴(1L · 0xFF)은 위에서 거른다. */
    private static String convertToken(String token, Style style) {
        List<String> words = words(token);
        boolean pascal = Character.isUpperCase(token.charAt(0)) && token.indexOf('_') < 0
                && !token.equals(token.toUpperCase(Locale.ROOT));
        return words.size() < 2 || pascal ? token : join(words, style);
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 밑줄 · 소문자→대문자 · 약어 끝(HTTPServer → HTTP, Server)에서 자른다. 숫자는 앞 낱말에 붙는다. */
    static List<String> words(String id) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_') {
                flush(words, cur);
                continue;
            }
            if (Character.isUpperCase(c) && cur.length() > 0) {
                char prev = id.charAt(i - 1);
                boolean nextLower = i + 1 < id.length() && Character.isLowerCase(id.charAt(i + 1));
                if (Character.isLowerCase(prev) || Character.isDigit(prev) || (Character.isUpperCase(prev) && nextLower)) {
                    flush(words, cur);
                }
            }
            cur.append(c);
        }
        flush(words, cur);
        return words;
    }

    private static void flush(List<String> words, StringBuilder cur) {
        if (cur.length() > 0) {
            words.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static String join(List<String> words, Style style) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).toLowerCase(Locale.ROOT);
            switch (style) {
                case CAMEL -> out.append(i == 0 ? w : capitalize(w));
                case PASCAL -> out.append(capitalize(w));
                case SNAKE -> out.append(i == 0 ? "" : "_").append(w);
                case UPPER_SNAKE -> out.append(i == 0 ? "" : "_").append(w.toUpperCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private static String capitalize(String w) {
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** 따옴표 리터럴의 끝(닫는 따옴표 다음). 백슬래시 이스케이프를 건너뛰고, 줄이 끝나면 거기서 멈춘다. */
    private static int quoteEnd(String text, int start) {
        char q = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == q) {
                return i + 1;
            }
            if (c == '\n') {
                return i;
            }
            i++;
        }
        return text.length();
    }

    /** from 부터 close 를 찾아 그 끝 위치. 없으면 글 끝(고른 글이 주석 · 문자열 중간에서 끝난 경우). */
    private static int closeAt(String text, int from, String close) {
        int at = text.indexOf(close, from);
        return at < 0 ? text.length() : at + close.length();
    }
}
