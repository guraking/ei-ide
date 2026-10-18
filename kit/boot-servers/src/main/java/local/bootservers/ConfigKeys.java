package local.bootservers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 스프링 설정 키 찾기: ${키:기본값} 에서 키 꺼내기, yml(yaml) · properties 글에서 키의 위치 · 값 찾기.
 * 키 비교는 느슨한 바인딩(소문자로 바꾸고 「-」 · 「_」 를 뺀 뒤 비교). 인텔리제이 클래스를 쓰지 않는다(NavCheck 로 점검).
 */
public final class ConfigKeys {

    /** 찾은 키. offset 은 글 안에서 키가 시작하는 위치, value 는 따옴표를 뗀 값(부모 키는 빈 글). */
    public record Hit(int offset, String value) {
    }

    private static final String PLACEHOLDER_OPEN = "${";
    private static final char PLACEHOLDER_CLOSE = '}';
    private static final char BRACE_OPEN = '{';
    private static final char DEFAULT_SEPARATOR = ':';
    private static final char KEY_SEPARATOR = '.';
    private static final char COMMENT = '#';
    private static final char PROPERTIES_BANG = '!';
    private static final char ESCAPE = '\\';
    private static final String LIST_ITEM = "- ";
    private static final String LIST_ITEM_BARE = "-";
    private static final String DOCUMENT_START = "---";
    private static final String DOCUMENT_END = "...";
    private static final String BLOCK_LITERAL = "|";
    private static final String BLOCK_FOLDED = ">";

    private ConfigKeys() {
    }

    /** 느슨한 바인딩 비교용 키. */
    public static String normalize(String key) {
        return key.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /**
     * 글(text) 의 caret 위치를 감싸는 가장 안쪽 ${...} 의 키(「:」 앞). caret 이 어떤 ${...} 안에도 없으면 null.
     * caret 은 ${ 의 $ 부터 닫는 } 까지를 안으로 친다.
     */
    public static String placeholderKeyAt(String text, int caret) {
        String best = null;
        int bestLength = Integer.MAX_VALUE;
        int start = text.indexOf(PLACEHOLDER_OPEN);
        while (start >= 0) {
            int end = closing(text, start + PLACEHOLDER_OPEN.length());
            if (end >= 0 && caret >= start && caret <= end && end - start < bestLength) {
                best = keyOf(text.substring(start + PLACEHOLDER_OPEN.length(), end));
                bestLength = end - start;
            }
            start = text.indexOf(PLACEHOLDER_OPEN, start + 1);
        }
        return best == null || best.isBlank() ? null : best.trim();
    }

    /** from 부터 짝이 맞는 닫는 } 위치. 없으면 -1. */
    private static int closing(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == BRACE_OPEN) {
                depth++;
            } else if (c == PLACEHOLDER_CLOSE) {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /** ${ 안쪽 글에서 중괄호 밖 첫 「:」 앞까지. */
    private static String keyOf(String inner) {
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == BRACE_OPEN) {
                depth++;
            } else if (c == PLACEHOLDER_CLOSE) {
                depth--;
            } else if (c == DEFAULT_SEPARATOR && depth == 0) {
                return inner.substring(0, i);
            }
        }
        return inner;
    }

    /** 설정 글에서 키를 찾는다. yml 은 문서(---)마다 하나씩 나올 수 있어 모두 돌려준다. */
    public static List<Hit> find(String text, String key, boolean yaml) {
        return yaml ? findYaml(text, normalize(key)) : findProperties(text, normalize(key));
    }

    /** yml 한 단계. indent 는 키가 시작하는 칸. */
    private record Level(int indent, String key) {
    }

    /**
     * 들여쓰기로 경로를 따라가는 간단한 yml 읽기.
     * 주석 · 따옴표 키 · 목록 항목(「- 키: 값」, 목록 번호는 경로에 넣지 않음) · 블록 글(| · >) · 여러 문서(---)를 다룬다.
     */
    private static List<Hit> findYaml(String text, String wanted) {
        List<Hit> result = new ArrayList<>();
        Deque<Level> path = new ArrayDeque<>();
        int blockIndent = -1;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = stripComment(text.substring(lineStart, lineEnd).replace("\r", ""));
            int indent = leadingSpaces(line);
            String content = line.trim();
            if (content.isEmpty()) {
                lineStart = lineEnd + 1;
                continue;
            }
            if (blockIndent >= 0 && indent > blockIndent) {
                lineStart = lineEnd + 1;
                continue;
            }
            blockIndent = -1;
            if (indent == 0 && (content.startsWith(DOCUMENT_START) || content.equals(DOCUMENT_END))) {
                path.clear();
                lineStart = lineEnd + 1;
                continue;
            }
            while (content.startsWith(LIST_ITEM) || content.equals(LIST_ITEM_BARE)) {
                int shift = content.equals(LIST_ITEM_BARE) ? 1 : LIST_ITEM.length();
                String rest = content.substring(shift);
                indent += shift + leadingSpaces(rest);
                content = rest.trim();
            }
            int keyEnd = keyEnd(content);
            if (keyEnd < 0) {
                lineStart = lineEnd + 1;
                continue;
            }
            String key = unquote(content.substring(0, keyEnd).trim());
            String value = unquote(content.substring(keyEnd + 1).trim());
            while (!path.isEmpty() && path.peekLast().indent() >= indent) {
                path.removeLast();
            }
            path.addLast(new Level(indent, key));
            if (normalize(join(path)).equals(wanted)) {
                result.add(new Hit(lineStart + indent, value));
            }
            if (value.startsWith(BLOCK_LITERAL) || value.startsWith(BLOCK_FOLDED)) {
                blockIndent = indent;
            }
            lineStart = lineEnd + 1;
        }
        return result;
    }

    /** properties: 「키 = 값」 · 「키: 값」 · 「키 값」 줄. 주석(# · !) 과 \ 로 이어진 줄은 건너뛴다. */
    private static List<Hit> findProperties(String text, String wanted) {
        List<Hit> result = new ArrayList<>();
        boolean continued = false;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(lineStart, lineEnd).replace("\r", "");
            boolean wasContinued = continued;
            continued = endsWithEscape(line);
            int indent = leadingWhitespace(line);
            String content = line.substring(indent);
            if (!wasContinued && !content.isEmpty()
                    && content.charAt(0) != COMMENT && content.charAt(0) != PROPERTIES_BANG) {
                int end = propertyKeyEnd(content);
                String key = content.substring(0, end).replace("\\", "");
                if (normalize(key).equals(wanted)) {
                    result.add(new Hit(lineStart + indent, propertyValue(content.substring(end))));
                }
            }
            lineStart = lineEnd + 1;
        }
        return result;
    }

    private static int propertyKeyEnd(String content) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == ESCAPE) {
                i++;
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                return i;
            }
        }
        return content.length();
    }

    private static String propertyValue(String afterKey) {
        String rest = afterKey.stripLeading();
        if (!rest.isEmpty() && (rest.charAt(0) == '=' || rest.charAt(0) == ':')) {
            rest = rest.substring(1).stripLeading();
        }
        return rest.endsWith(String.valueOf(ESCAPE)) ? rest.substring(0, rest.length() - 1) : rest.stripTrailing();
    }

    private static boolean endsWithEscape(String line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == ESCAPE; i--) {
            count++;
        }
        return count % 2 == 1;
    }

    /** 따옴표 밖, 줄 처음이거나 공백 뒤의 # 부터 지운다. */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == COMMENT && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** 키 끝의 「:」 위치(뒤가 공백이거나 줄 끝). 따옴표 키면 닫는 따옴표 뒤의 「:」. 키 줄이 아니면 -1. */
    private static int keyEnd(String content) {
        int from = 0;
        if (!content.isEmpty() && (content.charAt(0) == '"' || content.charAt(0) == '\'')) {
            int close = content.indexOf(content.charAt(0), 1);
            if (close < 0) {
                return -1;
            }
            from = close + 1;
        }
        for (int i = from; i < content.length(); i++) {
            if (content.charAt(i) == ':' && (i + 1 == content.length() || Character.isWhitespace(content.charAt(i + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String join(Deque<Level> path) {
        StringBuilder result = new StringBuilder();
        for (Level level : path) {
            if (!result.isEmpty()) {
                result.append(KEY_SEPARATOR);
            }
            result.append(level.key());
        }
        return result.toString();
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static int leadingWhitespace(String line) {
        int count = 0;
        while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }
}
