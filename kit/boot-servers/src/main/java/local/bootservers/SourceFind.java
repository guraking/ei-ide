package local.bootservers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 오류 글에서 소스 자리를 찾는다. IntelliJ 에 기대지 않는다.
 * logLine: 스택 없는 로그 글을 찍은 줄(그 글의 조각을 가장 많이 담은 문자열이 있는 줄).
 * mapperId: MyBatis 오류 글의 매퍼 문장 id. statementOffset: 매퍼 XML 에서 그 문장 태그의 자리.
 */
final class SourceFind {

    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    /**
     * 문자열 안의 값 자리(slf4j 「{}」, String.format 「%s」 · 「%d」 …, MessageFormat 「{0}」)와 역슬래시 글자(「\"」 · 「\n」).
     * 이것으로 문자열을 조각낸다(역슬래시 글자는 소스와 찍힌 글의 모양이 달라 조각에 넣지 않는다).
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d*}|%[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]|\\\\.");
    /** 글과 맞아야 하는 조각 글자 수의 최소 합. 「: 」 같은 짧은 문자열이 우연히 맞는 것을 막는다. */
    private static final int MIN_MATCHED_CHARS = 6;
    private static final int MIN_PIECE_CHARS = 2;

    private static final Pattern[] MAPPER_ID = {
            Pattern.compile("### The error may involve ([\\w$.]+?)(?:-Inline)?\\s*$", Pattern.MULTILINE),
            Pattern.compile("Invalid bound statement \\(not found\\): ([\\w$.]+)"),
            Pattern.compile("Mapped Statements collection (?:does not contain|already contains) value for ([\\w$.]+)"),
    };
    private static final String STATEMENT_TAGS = "select|insert|update|delete";

    private SourceFind() {}

    /**
     * source 에서 message 를 찍었을 줄(0부터). 문자열마다 값 자리로 나눈 조각 가운데 message 에 든 것의 글자 수를 더해 가장 큰 문자열의 줄.
     * 합이 MIN_MATCHED_CHARS 보다 작으면 -1.
     */
    static int logLine(String source, String message) {
        int best = -1;
        int bestScore = MIN_MATCHED_CHARS - 1;
        Matcher m = STRING.matcher(source);
        while (m.find()) {
            int score = 0;
            for (String piece : PLACEHOLDER.split(m.group(1))) {
                String p = piece.strip();
                if (p.length() >= MIN_PIECE_CHARS && message.contains(p)) {
                    score += p.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = m.start();
            }
        }
        if (best < 0) {
            return -1;
        }
        int line = 0;
        for (int i = 0; i < best; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 오류 묶음 글의 매퍼 문장 id(「com.x.UserMapper.selectUser」). 없으면 null. */
    static String mapperId(String all) {
        for (Pattern p : MAPPER_ID) {
            Matcher m = p.matcher(all);
            if (m.find() && m.group(1).indexOf('.') > 0) {
                return m.group(1);
            }
        }
        return null;
    }

    /** xml 이 namespace 의 매퍼인가. */
    static boolean isMapperOf(String xml, String namespace) {
        return Pattern.compile("<mapper\\s[^>]*namespace\\s*=\\s*[\"']" + Pattern.quote(namespace) + "[\"']").matcher(xml).find();
    }

    /** xml 에서 id 가 statement 인 select · insert · update · delete 태그의 자리. 없으면 -1. */
    static int statementOffset(String xml, String statement) {
        Matcher m = Pattern.compile("<(?:" + STATEMENT_TAGS + ")\\s[^>]*?\\bid\\s*=\\s*[\"']" + Pattern.quote(statement) + "[\"']").matcher(xml);
        return m.find() ? m.start() : -1;
    }
}
