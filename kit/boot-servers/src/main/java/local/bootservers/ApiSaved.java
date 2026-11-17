package local.bootservers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /** 비밀번호 · 토큰처럼 보이는 파라미터 · 본문 필드 이름. 그 값은 workspace.xml 에 남기지 않고 빈 글로 저장한다. */
    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(password|passwd|pwd|secret|token|api[_-]?key|credential).*");
    /** JSON 의 "이름" : "값" 한 쌍(값은 글자열만, 이스케이프 포함). */
    private static final Pattern JSON_PAIR = Pattern.compile("(\"([^\"\\\\]*)\"\\s*:\\s*\")((?:[^\"\\\\]|\\\\.)*)(\")");
    /** 폼 본문(a=1&b=2)의 한 쌍. */
    private static final Pattern FORM_PAIR = Pattern.compile("(^|&)([^=&]+)=([^&]*)");

    static boolean isSecretName(String name) {
        return name != null && SECRET_NAME.matcher(name.strip()).matches();
    }

    /** 이름이 비밀처럼 보이는 줄은 값을 비운다(줄 · 체크는 남긴다). */
    static List<Line> savableQuery(List<Line> query) {
        List<Line> out = new ArrayList<>();
        for (Line q : query) {
            out.add(isSecretName(q.name()) ? new Line(q.name(), "", q.on()) : q);
        }
        return out;
    }

    /** 본문에서 이름이 비밀처럼 보이는 JSON 글자열 값 · 폼 값을 비운다. JSON 도 폼도 아니면 그대로. */
    static String savableBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String trimmed = body.strip();
        Matcher m = (trimmed.startsWith("{") || trimmed.startsWith("[") ? JSON_PAIR : FORM_PAIR).matcher(body);
        boolean json = m.pattern() == JSON_PAIR;
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replaced = !isSecretName(m.group(2)) ? m.group() : json ? m.group(1) + m.group(4) : m.group(1) + m.group(2) + "=";
            m.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        m.appendTail(out);
        return out.toString();
    }

    static boolean isSecretHeader(String name) {
        return name != null && SECRET_HEADERS.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    /** 비밀 헤더와 이름이 빈 줄을 뺀 헤더 줄. */
    static List<Line> savableHeaders(List<Line> headers) {
        List<Line> out = new ArrayList<>();
        for (Line h : headers) {
            if (h.name() != null && !h.name().isBlank() && !isSecretHeader(h.name())) {
                out.add(h);
            }
        }
        return out;
    }

    static boolean changesData(String method) {
        return !SAFE_METHODS.contains(method == null ? "" : method.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * 코드에서 온 줄(fresh)을 바탕으로 같은 이름(대소문자 무시) 줄은 저장한 값 · 체크로 바꾸고, 저장에만 있는 줄(사용자가 더한 줄)은 뒤에 붙인다.
     * 코드에서 빠진 줄을 지우지 않는 것은 사용자가 더한 줄과 가릴 수 없어서다. 반환은 fresh 의 순서를 지킨다.
     */
    static List<Line> merge(List<Line> fresh, List<Line> saved) {
        Map<String, Line> byName = new LinkedHashMap<>();
        for (Line s : saved) {
            byName.putIfAbsent(s.name().toLowerCase(Locale.ROOT), s);
        }
        List<Line> out = new ArrayList<>();
        for (Line f : fresh) {
            Line s = byName.remove(f.name().toLowerCase(Locale.ROOT));
            out.add(s == null ? f : new Line(f.name(), s.value(), s.on()));
        }
        out.addAll(byName.values());
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
