package local.bootservers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 흔한 오류의 한국어 설명 표(resources/errors/explain.tsv)와 프레임워크 패키지 목록(resources/errors/framework.txt).
 * explain.tsv 한 줄 = 「정규식 \t 제목 \t 설명 \t 짧은 예」. 위 줄이 먼저다. 설명 · 예의 $1~$9 는 정규식 묶음 값으로 바뀐다.
 * 「#」 으로 시작하는 줄과 빈 줄은 건너뛴다. 표는 처음 쓸 때 한 번 읽는다.
 */
final class ErrorRules {

    record Explain(String title, String text, String example) {}

    private record Rule(Pattern pattern, String title, String text, String example) {}

    static final String EXPLAIN_FILE = "/errors/explain.tsv";
    static final String FRAMEWORK_FILE = "/errors/framework.txt";
    private static final int COLUMNS = 4;
    private static final Pattern GROUP_REF = Pattern.compile("\\$(\\d)");

    private static final class Holder {
        static final List<Rule> RULES = rules();
        static final List<String> FRAMEWORK = lines(FRAMEWORK_FILE);
    }

    private ErrorRules() {}

    /** 먼저 primary(가장 안쪽 원인 · 시작 실패 설명)에, 없으면 묶음 전체 글에 맞는 첫 규칙. 없으면 null. */
    static Explain match(String primary, String all) {
        for (String text : new String[] {primary, all}) {
            if (text == null) {
                continue;
            }
            for (Rule rule : Holder.RULES) {
                Matcher m = rule.pattern().matcher(text);
                if (m.find()) {
                    return new Explain(rule.title(), fill(rule.text(), m), fill(rule.example(), m));
                }
            }
        }
        return null;
    }

    /** 프레임워크 · JDK · 라이브러리 클래스인가(우리 코드 줄로 보이지 않을 것). */
    static boolean isFramework(String className) {
        for (String prefix : Holder.FRAMEWORK) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 표의 규칙 수(점검용). */
    static int size() {
        return Holder.RULES.size();
    }

    private static String fill(String template, Matcher m) {
        Matcher ref = GROUP_REF.matcher(template);
        StringBuilder out = new StringBuilder();
        while (ref.find()) {
            int group = Integer.parseInt(ref.group(1));
            String value = group <= m.groupCount() && m.group(group) != null ? m.group(group) : "";
            ref.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        ref.appendTail(out);
        return out.toString();
    }

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (String line : lines(EXPLAIN_FILE)) {
            String[] cols = line.split("\t", -1);
            if (cols.length != COLUMNS) {
                throw new IllegalStateException(EXPLAIN_FILE + " 칸 수가 " + COLUMNS + " 이 아닙니다: " + line);
            }
            rules.add(new Rule(Pattern.compile(cols[0]), cols[1], cols[2], cols[3]));
        }
        return List.copyOf(rules);
    }

    /** 리소스의 줄(앞뒤 공백 그대로, 빈 줄 · # 줄 뺌). 리소스가 없으면 예외. */
    private static List<String> lines(String resource) {
        InputStream in = ErrorRules.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("리소스가 없습니다: " + resource);
        }
        List<String> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.add(line);
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }
}
