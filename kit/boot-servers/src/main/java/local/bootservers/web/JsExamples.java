package local.bootservers.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 자바스크립트 작업대 예제 모음(jar 안 EXAMPLES, IntelliJ 클래스 없음). 인터넷 없이 쓰도록 플러그인 안에 둔다.
 * 파일 모양(UTF-8):
 * <pre>
 * ## 묶음 이름
 * ### 예제 제목
 * &gt; 한 줄 설명(여러 줄이면 이어 붙인다)
 * &#64;보기만            ← 있으면 브라우저 · Vue 화면에서만 도는 코드(작업대에서 실행하지 않고 복사해 쓴다)
 * 코드 …
 * --- 데이터           ← 있으면 그 아래가 입력 데이터
 * 데이터 …
 * </pre>
 * 예제는 「## 」 · 「### 」 로 시작하는 줄에서 끝난다. 코드 · 데이터 앞뒤 빈 줄은 뗀다.
 */
public final class JsExamples {

    static final String EXAMPLES = "/jsbench/examples.txt";
    static final String GROUP = "## ";
    static final String TITLE = "### ";
    static final String DESCRIPTION = "> ";
    static final String VIEW_ONLY = "@보기만";
    static final String DATA = "--- 데이터";

    public record Example(String group, String title, String description, boolean viewOnly, String code, String data) {
        @Override
        public String toString() {
            return title;
        }
    }

    private JsExamples() {
    }

    /** jar 안 예제를 읽는다. 파일이 없거나 모양이 틀리면 IOException · IllegalArgumentException. */
    public static List<Example> load() throws IOException {
        try (InputStream in = JsExamples.class.getResourceAsStream(EXAMPLES)) {
            if (in == null) {
                throw new IOException("jar 안에 " + EXAMPLES + " 가 없습니다.");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** 예제 글을 읽는다. 묶음 없는 예제 · 제목 없는 코드 · 코드 없는 예제는 줄 번호와 함께 IllegalArgumentException. */
    static List<Example> parse(String text) {
        List<Example> out = new ArrayList<>();
        String group = null;
        String title = null;
        int titleLine = 0;
        StringBuilder description = new StringBuilder();
        boolean viewOnly = false;
        StringBuilder code = new StringBuilder();
        StringBuilder data = null;
        boolean head = false;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean newGroup = line.startsWith(GROUP);
            boolean newTitle = line.startsWith(TITLE);
            if (newGroup || newTitle) {
                if (title != null) {
                    out.add(example(group, title, titleLine, description, viewOnly, code, data));
                }
                title = null;
                if (newGroup) {
                    group = line.substring(GROUP.length()).strip();
                    continue;
                }
                if (group == null) {
                    throw new IllegalArgumentException((i + 1) + "줄: 묶음(## ) 없이 예제가 나왔습니다.");
                }
                title = line.substring(TITLE.length()).strip();
                titleLine = i + 1;
                description = new StringBuilder();
                viewOnly = false;
                code = new StringBuilder();
                data = null;
                head = true;
                continue;
            }
            if (title == null) {
                if (!line.isBlank()) {
                    throw new IllegalArgumentException((i + 1) + "줄: 제목(### ) 없는 글입니다.");
                }
                continue;
            }
            if (head && line.startsWith(DESCRIPTION)) {
                if (description.length() > 0) {
                    description.append(' ');
                }
                description.append(line.substring(DESCRIPTION.length()).strip());
                continue;
            }
            if (head && line.strip().equals(VIEW_ONLY)) {
                viewOnly = true;
                continue;
            }
            head = false;
            if (data == null && line.strip().equals(DATA)) {
                data = new StringBuilder();
                continue;
            }
            (data != null ? data : code).append(line).append('\n');
        }
        if (title != null) {
            out.add(example(group, title, titleLine, description, viewOnly, code, data));
        }
        return out;
    }

    private static Example example(String group, String title, int line, StringBuilder description, boolean viewOnly,
                                   StringBuilder code, StringBuilder data) {
        String body = trimBlankLines(code.toString());
        if (body.isEmpty()) {
            throw new IllegalArgumentException(line + "줄: 「" + title + "」 에 코드가 없습니다.");
        }
        return new Example(group, title, description.toString(), viewOnly, body, data == null ? "" : trimBlankLines(data.toString()));
    }

    /** 앞뒤 빈 줄만 뗀다(줄 안 들여쓰기는 둔다). */
    static String trimBlankLines(String text) {
        String[] lines = text.split("\n", -1);
        int start = 0;
        int end = lines.length;
        while (start < end && lines[start].isBlank()) {
            start++;
        }
        while (end > start && lines[end - 1].isBlank()) {
            end--;
        }
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                out.append('\n');
            }
            out.append(lines[i].stripTrailing());
        }
        return out.toString();
    }
}
