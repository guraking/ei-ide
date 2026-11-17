package local.bootservers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장한 API 요청의 순수 논리(IntelliJ 클래스 없음): 저장할 헤더 거르기, 코드 시험 값과 저장 값 합치기, 응답 본문 줄이기 · 비교.
 * 응답 비교는 본문 전체의 지문(줄맞춤한 JSON 이면 줄맞춤 뒤 SHA-256)으로 하므로 저장 본문을 BODY_LIMIT 에서 잘라도 비교는 정확하다.
 */
final class ApiSaved {
    private ApiSaved() {
    }

    /** 표 한 줄: 이름 · 값 · 체크. */
    record Line(String name, String value, boolean on) {
    }

    /** 다시 보낸 결과 판정. */
    enum Verdict { SAME, STATUS, BODY, NO_BASELINE, FAILED }

    /** 저장 · 비교 창에 둘 응답 본문 최대 글자 수. 넘으면 앞부분만 두고 CUT_NOTE 를 붙인다(비교는 지문으로 한다). */
    static final int BODY_LIMIT = 256 * 1024;
    /** 저장할 요청 수. 넘으면 오래 쓰지 않은 것부터 버린다. */
    static final int MAX_SAVED = 300;
    /** 저장한 응답이 없을 때의 상태 코드. */
    static final int NO_CODE = -1;
    static final String CUT_NOTE = "\n… (앞 " + BODY_LIMIT + "자만 저장)";
    /** 비밀이 담기는 헤더. 저장하지 않는다(비밀 값은 인증 탭 · IDE 비밀번호 저장소에만 둔다). */
    private static final Set<String> SECRET_HEADERS = Set.of("authorization", "cookie", "proxy-authorization");
    /** 데이터를 바꾸지 않는 방식(RFC 9110 9.2.1). 다시 보내기에서 처음부터 체크한다. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** 비밀번호 · 토큰처럼 보이는 헤더 · 파라미터 · 본문 필드 이름. 그 값은 workspace.xml 에는 빈 글로, 실제 값은 IDE 비밀번호 저장소에 둔다. */
    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(password|passwd|pwd|secret|token|api[_-]?key|credential).*");
    /** JSON 의 "이름" : "값" 한 쌍(값은 글자열만, 이스케이프 포함). */
    private static final Pattern JSON_PAIR = Pattern.compile("(\"([^\"\\\\]*)\"\\s*:\\s*\")((?:[^\"\\\\]|\\\\.)*)(\")");
    /** 폼 본문 · 주소 쿼리(a=1&b=2)의 한 쌍. */
    private static final Pattern FORM_PAIR = Pattern.compile("(^|&)([^=&]+)=([^&#]*)");
    /** 비밀 값 맵의 키 앞머리: 쿼리 줄 · 헤더 줄은 이름(소문자), 본문 · 주소 쿼리는 비밀 쌍의 차례(0부터). */
    static final String QUERY = "q:";
    static final String HEADER = "h:";
    static final String BODY = "b:";
    static final String URL = "u:";

    /** 저장할 값(비밀은 비움)과 따로 둘 비밀 값(앞머리 + 이름 또는 차례 → 값). droppedHeader 는 Authorization 따위를 저장하지 않고 뺐는지. */
    record Scrubbed(List<Line> query, List<Line> headers, String url, String body, Map<String, String> secrets, boolean droppedHeader) {
    }

    static boolean isSecretName(String name) {
        return name != null && SECRET_NAME.matcher(name.strip()).matches();
    }

    /**
     * 저장 전에 비밀을 떼어 낸다. Authorization · Cookie · Proxy-Authorization 헤더는 저장하지 않고(인증 탭이 맡음),
     * 이름이 비밀처럼 보이는 헤더 · 쿼리 줄 · 주소 쿼리 값 · JSON 글자열 값 · 폼 값은 빈 글로 두고 secrets 에 옮긴다. 값이 빈 비밀은 옮기지 않는다.
     */
    static Scrubbed scrub(List<Line> query, List<Line> headers, String url, String body) {
        Map<String, String> secrets = new LinkedHashMap<>();
        List<Line> savedHeaders = new ArrayList<>();
        boolean dropped = false;
        for (Line h : headers) {
            if (h.name() == null || h.name().isBlank()) {
                continue;
            }
            if (isSecretHeader(h.name())) {
                dropped = true;
            } else {
                savedHeaders.add(scrubLine(h, HEADER, secrets));
            }
        }
        List<Line> savedQuery = new ArrayList<>();
        for (Line q : query) {
            savedQuery.add(scrubLine(q, QUERY, secrets));
        }
        return new Scrubbed(savedQuery, savedHeaders, pairs(url, URL, secrets, null), pairs(body, BODY, secrets, null), secrets, dropped);
    }

    /** 저장한 줄의 비어 있는 비밀 값을 secrets 로 되살린다. */
    static List<Line> restore(List<Line> lines, String prefix, Map<String, String> secrets) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            String value = l.value().isEmpty() && isSecretName(l.name()) ? secrets.get(prefix + l.name().strip().toLowerCase(Locale.ROOT)) : null;
            out.add(value == null ? l : new Line(l.name(), value, l.on()));
        }
        return out;
    }

    /** 저장한 주소 · 본문(prefix 는 URL · BODY)의 비어 있는 비밀 값을 차례대로 되살린다. */
    static String restore(String text, String prefix, Map<String, String> secrets) {
        return secrets.isEmpty() ? text : pairs(text, prefix, null, secrets);
    }

    private static Line scrubLine(Line l, String prefix, Map<String, String> secrets) {
        if (!isSecretName(l.name()) || l.value() == null || l.value().isEmpty()) {
            return l;
        }
        secrets.put(prefix + l.name().strip().toLowerCase(Locale.ROOT), l.value());
        return new Line(l.name(), "", l.on());
    }

    /**
     * text 의 비밀 쌍을 차례대로 돈다. take 가 있으면 값을 비우고 take 에 옮기고, give 가 있으면 빈 값을 give 의 값으로 채운다.
     * 본문은 { · [ 로 시작하면 JSON, 아니면 폼. 주소는 ? 뒤만 폼으로 본다. 둘 다 아니면 그대로.
     */
    private static String pairs(String text, String prefix, Map<String, String> take,
                                Map<String, String> give) {
        if (text == null || text.isBlank()) {
            return text;
        }
        int from = 0;
        Pattern pattern;
        if (URL.equals(prefix)) {
            from = text.indexOf('?') + 1;
            if (from == 0) {
                return text;
            }
            pattern = FORM_PAIR;
        } else {
            String trimmed = text.strip();
            pattern = trimmed.startsWith("{") || trimmed.startsWith("[") ? JSON_PAIR : FORM_PAIR;
        }
        boolean json = pattern == JSON_PAIR;
        Matcher m = pattern.matcher(text.substring(from));
        StringBuilder out = new StringBuilder(text.substring(0, from));
        int index = 0;
        while (m.find()) {
            String replaced = m.group();
            if (isSecretName(m.group(2))) {
                String key = prefix + index++;
                String value = m.group(3);
                if (take != null && !value.isEmpty()) {
                    take.put(key, value);
                    value = "";
                } else if (give != null && value.isEmpty() && give.containsKey(key)) {
                    value = give.get(key);
                }
                replaced = json ? m.group(1) + value + m.group(4) : m.group(1) + m.group(2) + "=" + value;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        m.appendTail(out);
        return out.toString();
    }

    static boolean isSecretHeader(String name) {
        return name != null && SECRET_HEADERS.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    /** 주소에서 쿼리(? 뒤)를 뗀다. 요청 key 에 비밀 쿼리 값이 남지 않게 한다. */
    static String withoutQuery(String url) {
        int at = url == null ? -1 : url.indexOf('?');
        return at < 0 ? url : url.substring(0, at);
    }

    static boolean changesData(String method) {
        return !SAFE_METHODS.contains(method == null ? "" : method.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * 코드에서 온 줄(fresh)을 바탕으로 같은 이름(대소문자 무시) 줄은 저장한 값 · 체크로 바꾸고, 저장에만 있는 줄(사용자가 더한 줄)은 뒤에 붙인다.
     * 같은 이름이 여럿이면(ids=1&ids=2) 나온 차례대로 짝짓는다. 저장한 비밀 값이 비었으면(되살리지 못함) 코드의 값을 둔다.
     * 코드에서 빠진 줄을 지우지 않는 것은 사용자가 더한 줄과 가릴 수 없어서다. 반환은 fresh 의 순서를 지킨다.
     */
    static List<Line> merge(List<Line> fresh, List<Line> saved) {
        Map<String, ArrayDeque<Line>> byName = new LinkedHashMap<>();
        for (Line s : saved) {
            byName.computeIfAbsent(s.name().toLowerCase(Locale.ROOT), k -> new ArrayDeque<>()).add(s);
        }
        List<Line> out = new ArrayList<>();
        for (Line f : fresh) {
            ArrayDeque<Line> same = byName.get(f.name().toLowerCase(Locale.ROOT));
            Line s = same == null ? null : same.poll();
            if (s == null) {
                out.add(f);
            } else {
                boolean lostSecret = s.value().isEmpty() && isSecretName(s.name());
                out.add(new Line(f.name(), lostSecret ? f.value() : s.value(), s.on()));
            }
        }
        for (ArrayDeque<Line> rest : byName.values()) {
            out.addAll(rest);
        }
        return out;
    }

    /** 저장 · 비교 창에 넣을 본문: BODY_LIMIT 를 넘으면 앞부분과 CUT_NOTE. */
    static String cap(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > BODY_LIMIT ? body.substring(0, BODY_LIMIT) + CUT_NOTE : body;
    }

    /** 비교에 쓰는 본문 지문. JSON 이면 줄맞춤(값 · 순서는 그대로)한 뒤, 줄 끝 \r\n 은 \n 으로 맞춘다. */
    static String fingerprint(String body) {
        String normal = ApiText.prettyJson(body == null ? "" : body).replace("\r\n", "\n");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normal.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 자바 실행 환경이 갖춰야 하는 알고리즘(MessageDigest 문서)이라 여기 오지 않는다.
            throw new IllegalStateException(e);
        }
    }

    /** 판정. error 가 있으면 실패, 기준이 없으면 NO_BASELINE, 코드가 다르면 STATUS, 지문이 다르면 BODY. */
    static Verdict verdict(String error, int beforeCode, int nowCode, String beforePrint, String nowPrint) {
        if (error != null) {
            return Verdict.FAILED;
        }
        if (beforeCode == NO_CODE) {
            return Verdict.NO_BASELINE;
        }
        if (beforeCode != nowCode) {
            return Verdict.STATUS;
        }
        return beforePrint != null && beforePrint.equals(nowPrint) ? Verdict.SAME : Verdict.BODY;
    }
}
