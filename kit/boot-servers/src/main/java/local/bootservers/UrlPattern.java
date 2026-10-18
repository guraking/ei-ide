package local.bootservers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 스프링 요청 매핑 경로 다루기(IntelliJ 클래스 없음).
 * 클래스 경로와 메서드 경로를 잇고, 실제 URL 이 {변수} · * · ** 가 든 매핑 경로에 맞는지 본다.
 * 경로 끝의 「/」 는 없는 것으로 본다. 대소문자는 가린다(스프링 기본과 같음).
 */
final class UrlPattern {

    private static final String SLASH = "/";
    private static final String ANY_SEGMENTS = "**";
    private static final String VAR_REGEX = "[^/]+";
    private static final String STAR_REGEX = "[^/]*";
    private static final char QUERY_START = '?';
    private static final char FRAGMENT_START = '#';

    private UrlPattern() {
    }

    /** 클래스 경로와 메서드 경로를 이어 「/」 로 시작하고 「/」 가 겹치지 않는 경로를 만든다. 둘 다 비면 「/」. */
    static String join(String classPath, String methodPath) {
        return normalize(nullToEmpty(classPath).trim() + SLASH + nullToEmpty(methodPath).trim());
    }

    /** 앞뒤 공백을 떼고, 겹친 「/」 를 하나로, 앞에 「/」 를 붙이고, 끝의 「/」 는 뗀다(「/」 하나만 남으면 그대로). */
    static String normalize(String path) {
        String p = nullToEmpty(path).trim().replaceAll("/{2,}", SLASH);
        if (!p.startsWith(SLASH)) {
            p = SLASH + p;
        }
        if (p.length() > 1 && p.endsWith(SLASH)) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 실제 URL 이 매핑 경로에 맞는지 본다. URL 의 「?」 · 「#」 뒤는 뗀다.
     * {이름} · {이름:정규식} 은 「/」 없는 한 칸 이상, * 은 한 칸 안의 아무 글자, ** 은 칸 0개 이상.
     */
    static boolean matches(String pattern, String url) {
        List<String> patternSegments = segments(normalize(pattern));
        List<String> urlSegments = segments(normalize(stripQuery(url)));
        return matchFrom(patternSegments, 0, urlSegments, 0);
    }

    /** 검색 창 거르기: 비었으면 모두, 아니면 보이는 글에 (대소문자 없이) 들었거나 실제 URL 로서 매핑 경로에 맞으면 통과. */
    static boolean accepts(String pattern, String shownText, String query) {
        String q = nullToEmpty(query).trim();
        if (q.isEmpty()) {
            return true;
        }
        if (nullToEmpty(shownText).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return q.startsWith(SLASH) && matches(pattern, q);
    }

    private static boolean matchFrom(List<String> pattern, int pi, List<String> url, int ui) {
        if (pi == pattern.size()) {
            return ui == url.size();
        }
        String seg = pattern.get(pi);
        if (seg.equals(ANY_SEGMENTS)) {
            for (int skip = ui; skip <= url.size(); skip++) {
                if (matchFrom(pattern, pi + 1, url, skip)) {
                    return true;
                }
            }
            return false;
        }
        if (ui == url.size() || !segmentRegex(seg).matcher(url.get(ui)).matches()) {
            return false;
        }
        return matchFrom(pattern, pi + 1, url, ui + 1);
    }

    /** 매핑 경로 한 칸을 정규식으로 바꾼다. {…} 안의 중괄호(정규식 반복 횟수 등)는 짝을 세어 건너뛴다. */
    private static Pattern segmentRegex(String seg) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < seg.length()) {
            char c = seg.charAt(i);
            if (c == '{') {
                int end = closingBrace(seg, i);
                if (end < 0) {
                    literal.append(seg.substring(i));
                    break;
                }
                flush(regex, literal);
                regex.append(VAR_REGEX);
                i = end + 1;
            } else if (c == '*') {
                flush(regex, literal);
                regex.append(STAR_REGEX);
                i++;
            } else {
                literal.append(c);
                i++;
            }
        }
        flush(regex, literal);
        return Pattern.compile(regex.toString());
    }

    private static int closingBrace(String seg, int open) {
        int depth = 0;
        for (int i = open; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void flush(StringBuilder regex, StringBuilder literal) {
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }

    private static List<String> segments(String normalized) {
        List<String> result = new ArrayList<>();
        for (String s : normalized.split(SLASH)) {
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }

    private static String stripQuery(String url) {
        String u = nullToEmpty(url);
        int cut = u.length();
        int q = u.indexOf(QUERY_START);
        int f = u.indexOf(FRAGMENT_START);
        if (q >= 0) {
            cut = q;
        }
        if (f >= 0 && f < cut) {
            cut = f;
        }
        return u.substring(0, cut);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
