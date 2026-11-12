package local.bootservers.web;

import java.util.regex.Pattern;

/**
 * 「고르기로 만들기」: 고른 단계로 배열 가공 코드를 만든다(IntelliJ 클래스 없음).
 * 순서는 거르기 → 중복 빼기 → 묶기 · 집계 → 정렬 → 앞에서 N개로 고정이고, 쓰지 않는 단계는 null(또는 0)로 둔다.
 * 같음 · 다름 · 포함 · 시작 · 끝은 글로 비교해 숫자 1 과 글 "1" 을 같게 보고, 큼 · 작음은 숫자로 비교한다.
 * 만든 코드는 입력 데이터(data)를 rows 로 받아 console.table(rows) 로 끝난다.
 */
public final class JsPipeline {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

    public enum Op {
        EQ("같음"), NE("다름"), CONTAINS("포함"), STARTS("~로 시작"), ENDS("~로 끝남"),
        GT("보다 큼"), GE("크거나 같음"), LT("보다 작음"), LE("작거나 같음"), EMPTY("비었음"), NOT_EMPTY("비지 않음");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        public boolean needsValue() {
            return this != EMPTY && this != NOT_EMPTY;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Agg {
        COUNT("개수", "COUNT"), SUM("합계", "SUM"), AVG("평균", "AVG"), MAX("최대", "MAX"), MIN("최소", "MIN");

        private final String label;
        private final String prefix;

        Agg(String label, String prefix) {
            this.label = label;
            this.prefix = prefix;
        }

        /** 결과 칸 이름. 개수는 칸과 상관없이 COUNT. */
        public String column(String of) {
            return this == COUNT ? prefix : prefix + "_" + of;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 고른 단계. filterColumn · distinctColumn · groupColumn · aggregate · sortColumn 이 null 이면 그 단계를 쓰지 않는다.
     * aggregate 가 COUNT 가 아니면 aggColumn 이 있어야 한다. limit 이 0 이하면 자르지 않는다.
     */
    public record Spec(String filterColumn, Op filterOp, String filterValue, String distinctColumn,
                       String groupColumn, Agg aggregate, String aggColumn,
                       String sortColumn, boolean sortDescending, int limit) {
    }

    private JsPipeline() {
    }

    public static String generate(Spec s) {
        StringBuilder out = new StringBuilder("let rows = Array.isArray(data) ? data : [];\n");
        if (s.filterColumn() != null && s.filterOp() != null) {
            out.append("\n// 거르기: ").append(s.filterColumn()).append(' ').append(s.filterOp())
                    .append(s.filterOp().needsValue() ? " " + quote(nullToEmpty(s.filterValue())) : "").append('\n');
            out.append("rows = rows.filter(r => ").append(condition(s.filterColumn(), s.filterOp(), nullToEmpty(s.filterValue()))).append(");\n");
        }
        if (s.distinctColumn() != null) {
            out.append("\n// 중복 빼기: ").append(s.distinctColumn()).append(" 가 같은 줄은 처음 것만 남긴다\n");
            out.append("const seen = new Set();\n");
            out.append("rows = rows.filter(r => !seen.has(").append(field("r", s.distinctColumn()))
                    .append(") && seen.add(").append(field("r", s.distinctColumn())).append("));\n");
        }
        if (s.aggregate() != null && (s.aggregate() == Agg.COUNT || s.aggColumn() != null)) {
            appendGroup(out, s);
        }
        if (s.sortColumn() != null) {
            out.append("\n// 정렬: ").append(s.sortColumn()).append(s.sortDescending() ? " 큰 것부터" : " 작은 것부터")
                    .append(" (숫자는 숫자로, 글은 가나다 · 숫자 순으로)\n");
            out.append("const compare = (x, y) => typeof x === 'number' && typeof y === 'number'\n")
                    .append("  ? x - y\n")
                    .append("  : String(x ?? '').localeCompare(String(y ?? ''), 'ko', { numeric: true });\n");
            String a = field("a", s.sortColumn());
            String b = field("b", s.sortColumn());
            out.append("rows = [...rows].sort((a, b) => ")
                    .append(s.sortDescending() ? "compare(" + b + ", " + a + ")" : "compare(" + a + ", " + b + ")").append(");\n");
        }
        if (s.limit() > 0) {
            out.append("\n// 앞에서 ").append(s.limit()).append("개만\n");
            out.append("rows = rows.slice(0, ").append(s.limit()).append(");\n");
        }
        out.append("\nconsole.table(rows);\n");
        return out.toString();
    }

    private static void appendGroup(StringBuilder out, Spec s) {
        String result = s.aggregate().column(s.aggColumn());
        String value = s.aggregate() == Agg.COUNT ? "" : "Number(" + field("r", s.aggColumn()) + " ?? 0)";
        String reduce = switch (s.aggregate()) {
            case COUNT -> "list.length";
            case SUM -> "list.reduce((sum, r) => sum + " + value + ", 0)";
            case AVG -> "list.length ? list.reduce((sum, r) => sum + " + value + ", 0) / list.length : 0";
            // 펼침(...)은 줄이 수십만이면 호출 스택을 넘으므로 reduce 로 센다. 빈 목록은 null.
            case MAX -> "list.length ? list.reduce((max, r) => Math.max(max, " + value + "), -Infinity) : null";
            case MIN -> "list.length ? list.reduce((min, r) => Math.min(min, " + value + "), Infinity) : null";
        };
        if (s.groupColumn() == null) {
            out.append("\n// 전체 ").append(s.aggregate()).append(s.aggregate() == Agg.COUNT ? "" : ": " + s.aggColumn()).append('\n');
            out.append("const list = rows;\n");
            out.append("rows = [{ ").append(key(result)).append(": ").append(reduce).append(" }];\n");
            return;
        }
        out.append("\n// 묶기: ").append(s.groupColumn()).append(" 가 같은 줄끼리 ").append(s.aggregate())
                .append(s.aggregate() == Agg.COUNT ? "" : "(" + s.aggColumn() + ")").append('\n');
        out.append("const groups = new Map();\n");
        out.append("for (const r of rows) {\n");
        out.append("  const key = ").append(field("r", s.groupColumn())).append(";\n");
        out.append("  if (!groups.has(key)) groups.set(key, []);\n");
        out.append("  groups.get(key).push(r);\n");
        out.append("}\n");
        out.append("rows = [...groups].map(([key, list]) => ({ ").append(key(s.groupColumn())).append(": key, ")
                .append(key(result)).append(": ").append(reduce).append(" }));\n");
    }

    private static String condition(String column, Op op, String value) {
        String f = field("r", column);
        String text = "String(" + f + " ?? '')";
        return switch (op) {
            case EQ -> text + " === " + quote(value);
            case NE -> text + " !== " + quote(value);
            case CONTAINS -> text + ".includes(" + quote(value) + ")";
            case STARTS -> text + ".startsWith(" + quote(value) + ")";
            case ENDS -> text + ".endsWith(" + quote(value) + ")";
            case GT -> "Number(" + f + ") > " + number(value);
            case GE -> "Number(" + f + ") >= " + number(value);
            case LT -> "Number(" + f + ") < " + number(value);
            case LE -> "Number(" + f + ") <= " + number(value);
            case EMPTY -> f + " == null || " + f + " === ''";
            case NOT_EMPTY -> f + " != null && " + f + " !== ''";
        };
    }

    /** 숫자로 읽히면 그 글 그대로(쉼표는 뗀다), 아니면 Number('…') 로 감싸 실행 때 NaN 이 되게 한다. */
    private static String number(String value) {
        String plain = value.strip().replace(",", "");
        return plain.matches("-?\\d+(\\.\\d+)?") ? plain : "Number(" + quote(value) + ")";
    }

    static String field(String row, String column) {
        return IDENTIFIER.matcher(column).matches() ? row + "." + column : row + "[" + quote(column) + "]";
    }

    private static String key(String column) {
        return IDENTIFIER.matcher(column).matches() ? column : quote(column);
    }

    /** 작은따옴표 JS 글. 역슬래시 · 따옴표 · 줄바꿈 · 줄 구분 문자(U+2028 · U+2029)를 이스케이프한다. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
