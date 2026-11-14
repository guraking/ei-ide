package local.bootservers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis 로그의 「==> Parameters: 1(Integer), abc(String), null」 값으로 SQL 의 ? 를 채운다. IntelliJ 에 기대지 않는다.
 * 숫자 형은 그대로, 그 밖은 작은따옴표로 감싸고 안의 ' 는 '' 로 바꾼다. null 은 NULL.
 * 문자열 · 주석 안의 ? 는 건드리지 않는다. 값 수가 ? 수와 다르면 null(채우지 않는다).
 */
final class SqlFill {

    /** 값 끝의 「(형)」. 값 안에 「, 」 가 있어도 형 꼬리로 값을 가른다. */
    private static final Pattern TYPE_END = Pattern.compile("\\(([A-Za-z_$][\\w.$]*)\\)(?=, |$)");
    private static final String NULL = "null";
    private static final Set<String> NUMBER_TYPES = Set.of("Integer", "Long", "Short", "Byte", "Double", "Float",
            "BigDecimal", "BigInteger", "int", "long", "short", "double", "float");

    private SqlFill() {}

    /** params 가 null 이면 null. */
    static String fill(String sql, String params) {
        if (sql == null || params == null) {
            return null;
        }
        List<String> values = values(params);
        List<Integer> marks = marks(sql);
        if (values.size() != marks.size()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (int i = 0; i < marks.size(); i++) {
            out.append(sql, from, marks.get(i)).append(values.get(i));
            from = marks.get(i) + 1;
        }
        return out.append(sql.substring(from)).toString();
    }

    /** SQL 글자로 바꾼 값 목록. */
    static List<String> values(String params) {
        List<String> out = new ArrayList<>();
        String rest = params.trim();
        while (!rest.isEmpty()) {
            if (rest.equals(NULL) || rest.startsWith(NULL + ", ")) {
                out.add("NULL");
                rest = rest.length() == NULL.length() ? "" : rest.substring(NULL.length() + 2);
                continue;
            }
            Matcher m = TYPE_END.matcher(rest);
            if (!m.find()) {
                out.add(quote(rest));
                break;
            }
            String value = rest.substring(0, m.start());
            String type = m.group(1);
            out.add(NUMBER_TYPES.contains(type.substring(type.lastIndexOf('.') + 1)) ? value : quote(value));
            rest = m.end() >= rest.length() ? "" : rest.substring(m.end() + 2);
        }
        return out;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** 문자열('...' · "...") · 주석(-- 줄 끝 · 블록 주석) 밖의 ? 자리. */
    static List<Integer> marks(String sql) {
        List<Integer> out = new ArrayList<>();
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = sql.indexOf(c, i + 1);
                while (end >= 0 && end + 1 < n && sql.charAt(end + 1) == c) {
                    end = sql.indexOf(c, end + 2);
                }
                if (end < 0) {
                    break;
                }
                i = end;
            }
            else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    break;
                }
                i = end;
            }
            else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                i = end + 1;
            }
            else if (c == '?') {
                out.add(i);
            }
        }
        return out;
    }
}
