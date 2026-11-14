package local.bootservers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 오류 묶음 하나를 읽은 결과. IntelliJ 에 기대지 않는다.
 * 가장 안쪽 원인(마지막 「Caused by:」), 우리 코드 첫 줄(프레임워크 패키지가 아닌 첫 스택 줄), 한국어 설명({@link ErrorRules}),
 * 실패한 SQL(MyBatis 「### SQL:」 · 「bad SQL grammar [..]」 · 앞 로그의 「==>  Preparing:」 와 「==> Parameters:」),
 * 오류 앞 로그의 요청(메서드 · 경로 · 파라미터), 스프링 부트 시작 실패 보고(Description · Action),
 * 첫 줄을 찍은 로거(스택이 없을 때 찍은 곳을 찾는 데 쓴다), MyBatis 매퍼 문장 id 를 담는다. 못 찾은 칸은 null.
 */
record ErrorReport(String key, List<String> lines, String time, String thread, String rootClass, String rootMessage,
                   Frame frame, ErrorRules.Explain explain, String sql, String sqlParams,
                   String method, String path, String params, boolean startup, String description, String action,
                   String logger, String mapperId) {

    /** 스택 줄 하나. className 은 패키지까지, line 은 없으면 -1. */
    record Frame(String className, String method, String file, int line) {
        String text() {
            return className + "." + method + "(" + file + (line >= 0 ? ":" + line : "") + ")";
        }
    }

    // 「\tat java.base/java.lang.Thread.run(Thread.java:840)」 · 「\tat app//com.x.Foo.bar(Foo.java:1)」 · 「\tat com.x.Foo.bar(Unknown Source)」
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(?:[^\\s/(]*/)*([\\w$.]+)\\.([\\w$<>-]+)\\(([^):]*)(?::(\\d+))?\\)");
    private static final String CAUSED_BY = "Caused by: ";
    private static final Pattern EXCEPTION_START = Pattern.compile("^([\\w$]+(?:\\.[\\w$]+)+(?:Exception|Error|Throwable)[\\w$]*)(?::\\s?(.*))?$");
    /** 레벨 줄에서 글이 시작하는 곳(스프링 부트 「 : 」, logback 「 - 」). */
    private static final Pattern MESSAGE_START = Pattern.compile(" (?::|-) ");
    private static final Pattern MYBATIS_SQL = Pattern.compile("### SQL:\\s*(.+)");
    private static final Pattern BAD_GRAMMAR = Pattern.compile("(?:bad SQL grammar|SQL) \\[(.+?)]\\s*;");
    private static final Pattern PREPARING = Pattern.compile("==>\\s+Preparing:\\s*(.+)");
    private static final Pattern PARAMETERS = Pattern.compile("==>\\s*Parameters:\\s?(.*)");
    // 스프링 DispatcherServlet DEBUG 「POST "/api/a?b=1", parameters={masked}」
    private static final Pattern REQUEST_SPRING = Pattern.compile(
            "\\b(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS) \"([^\"\\s]+)\"(?:, parameters=\\{(.*)})?");
    // 「GET /api/a」 처럼 메서드 뒤 경로
    private static final Pattern REQUEST_PLAIN = Pattern.compile("\\b(GET|POST|PUT|DELETE|PATCH) +(/[^\\s\"',]*)");
    // 「requestURI=/api/a」 · 「URI : /api/a」 · 「url=http://host/api」(메서드 모름)
    private static final Pattern REQUEST_URI = Pattern.compile(
            "(?i)\\b(?:request ?URI|uri|url)\\s*[=:]\\s*\\[?\"?((?:https?://[^\\s/\"\\]]+)?/[^\\s\"\\],]*)");
    private static final String MASKED = "masked";
    private static final String DESCRIPTION = "Description:";
    private static final String ACTION = "Action:";
    private static final String FAILED_TO_START = "APPLICATION FAILED TO START";

    /** 묶음 줄과 그 앞 로그로 읽는다. 읽을 것이 없으면(빈 묶음) null. */
    static ErrorReport of(List<String> lines, List<String> before) {
        if (lines.isEmpty()) {
            return null;
        }
        String head = lines.get(0);
        Matcher stamp = LogFilter.LEADING_TIMESTAMP.matcher(head);
        String time = stamp.lookingAt() ? stamp.group("time") : null;
        String thread = LogFields.of(head).thread();

        String root = null;
        int rootLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith(CAUSED_BY)) {
                root = l.substring(CAUSED_BY.length()).trim();
                rootLine = i;
            }
        }
        if (root == null) {
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (EXCEPTION_START.matcher(l).matches()) {
                    root = l;
                    rootLine = i;
                    break;
                }
            }
        }
        if (root == null) {
            Matcher msg = MESSAGE_START.matcher(head);
            root = msg.find() ? head.substring(msg.end()).trim() : head.trim();
            rootLine = 0;
        }
        String rootClass = null;
        String rootMessage = root;
        Matcher ex = EXCEPTION_START.matcher(root);
        if (ex.matches()) {
            rootClass = ex.group(1);
            rootMessage = ex.group(2) == null ? "" : ex.group(2).trim();
        }

        Frame frame = ourFrame(lines, rootLine);
        String all = String.join("\n", lines);

        boolean startup = all.contains(FAILED_TO_START);
        String description = startup ? paragraph(lines, DESCRIPTION, ACTION) : null;
        String action = startup ? paragraph(lines, ACTION, null) : null;
        if (startup && description != null) {
            rootMessage = description;
        }

        ErrorRules.Explain explain = ErrorRules.match(startup && description != null ? description : root, all);

        String sql = null;
        String sqlParams = null;
        for (String l : lines) {
            Matcher m = MYBATIS_SQL.matcher(l);
            if (m.find()) {
                sql = m.group(1).trim();
                break;
            }
        }
        if (sql == null) {
            Matcher m = BAD_GRAMMAR.matcher(all);
            if (m.find()) {
                sql = m.group(1).trim();
            }
        }
        String[] prepared = prepared(before, thread);
        if (prepared != null && (sql == null || sameSql(sql, prepared[0]))) {
            sql = prepared[0];
            sqlParams = prepared[1];
        }

        String[] request = request(before, lines, thread);

        String key = startup
                ? "startup|" + ErrorScan.normalize(description == null ? head : description)
                : rootClass + "|" + ErrorScan.normalize(rootMessage) + "|" + (frame == null ? "" : frame.className() + "." + frame.method());
        return new ErrorReport(key, List.copyOf(lines), time, thread, rootClass, rootMessage, frame, explain, sql, sqlParams,
                request == null ? null : request[0], request == null ? null : request[1], request == null ? null : request[2],
                startup, description, action, LogFields.of(head).logger(), SourceFind.mapperId(all));
    }

    /** 원인 줄 아래 스택에서 먼저, 없으면 묶음 전체에서 프레임워크 아닌 첫 줄. */
    private static Frame ourFrame(List<String> lines, int rootLine) {
        Frame found = ourFrame(lines, Math.max(0, rootLine), true);
        return found != null ? found : ourFrame(lines, 0, false);
    }

    private static Frame ourFrame(List<String> lines, int from, boolean stopAtNextCause) {
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i);
            if (stopAtNextCause && i > from && l.startsWith(CAUSED_BY)) {
                return null;
            }
            Frame f = frame(l);
            if (f != null && !ErrorRules.isFramework(f.className())) {
                return f;
            }
        }
        return null;
    }

    static Frame frame(String line) {
        Matcher m = FRAME.matcher(line);
        if (!m.find()) {
            return null;
        }
        return new Frame(m.group(1), m.group(2), m.group(3), m.group(4) == null ? -1 : Integer.parseInt(m.group(4)));
    }

    /** start 줄 뒤부터 end 줄 앞까지(end 가 null 이면 묶음 끝까지)의 글. 빈 줄 · 별표 줄은 뺀다. 없으면 null. */
    private static String paragraph(List<String> lines, String start, String end) {
        int i = 0;
        while (i < lines.size() && !lines.get(i).trim().equals(start)) {
            i++;
        }
        if (i == lines.size()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (i++; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (end != null && l.equals(end)) {
                break;
            }
            if (!l.isEmpty() && !l.startsWith("***")) {
                out.add(l);
            }
        }
        return out.isEmpty() ? null : String.join("\n", out);
    }

    /** 앞 로그 끝에서 가까운 MyBatis Preparing 과 그 뒤 Parameters. 스레드를 아는 줄끼리는 같은 스레드만. {sql, params 또는 null}. */
    private static String[] prepared(List<String> before, String thread) {
        for (int i = before.size() - 1; i >= 0; i--) {
            Matcher m = PREPARING.matcher(before.get(i));
            if (m.find() && sameThread(before.get(i), thread)) {
                String params = null;
                for (int j = i + 1; j < before.size(); j++) {
                    Matcher p = PARAMETERS.matcher(before.get(j));
                    if (p.find() && sameThread(before.get(j), thread)) {
                        params = p.group(1);
                        break;
                    }
                    if (PREPARING.matcher(before.get(j)).find() && sameThread(before.get(j), thread)) {
                        break;
                    }
                }
                return new String[] {m.group(1).trim(), params};
            }
        }
        return null;
    }

    private static boolean sameThread(String line, String thread) {
        String other = LogFields.of(line).thread();
        return thread == null || other == null || thread.equals(other);
    }

    private static boolean sameSql(String a, String b) {
        return a.replaceAll("\\s+", " ").trim().equalsIgnoreCase(b.replaceAll("\\s+", " ").trim());
    }

    /** 오류 묶음 안, 없으면 앞 로그 끝에서 가까운 요청. {메서드 또는 null, 경로, 파라미터 또는 null}. */
    private static String[] request(List<String> before, List<String> lines, String thread) {
        for (String l : lines) {
            String[] found = request(l);
            if (found != null) {
                return found;
            }
        }
        for (int i = before.size() - 1; i >= 0; i--) {
            String l = before.get(i);
            if (sameThread(l, thread)) {
                String[] found = request(l);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static String[] request(String line) {
        Matcher m = REQUEST_SPRING.matcher(line);
        if (m.find()) {
            String params = m.group(3);
            return new String[] {m.group(1), m.group(2), params == null || params.isBlank() || params.equals(MASKED) ? null : params};
        }
        m = REQUEST_PLAIN.matcher(line);
        if (m.find()) {
            return new String[] {m.group(1), m.group(2), null};
        }
        m = REQUEST_URI.matcher(line);
        if (m.find() && m.group(1).length() > 1) {
            return new String[] {null, m.group(1), null};
        }
        return null;
    }

    /** 첫 줄에서 시각을 뺀 글(목록에 보일 제목). */
    String headline() {
        String head = lines.get(0);
        Matcher stamp = LogFilter.LEADING_TIMESTAMP.matcher(head);
        return (stamp.lookingAt() ? head.substring(stamp.end()) : head).trim();
    }

    /** 원인 한 줄(「예외 이름: 글」, 예외 이름이 없으면 글). */
    String cause() {
        if (rootClass == null) {
            return rootMessage;
        }
        String simple = rootClass.substring(rootClass.lastIndexOf('.') + 1);
        return rootMessage.isEmpty() ? simple : simple + ": " + rootMessage;
    }

    /** 메신저에 붙일 글: 설명 · 원인 · 우리 코드 줄 · 요청 · SQL · 스택 앞부분. filledSql 이 있으면 SQL 대신 그것. */
    String copyText(String filledSql, int stackLines) {
        StringBuilder out = new StringBuilder();
        if (explain != null) {
            out.append("[").append(explain.title()).append("] ").append(explain.text()).append('\n');
        }
        out.append("원인: ").append(cause()).append('\n');
        if (startup && action != null) {
            out.append("할 일: ").append(action.replace('\n', ' ')).append('\n');
        }
        if (frame != null) {
            out.append("우리 코드: ").append(frame.text()).append('\n');
        }
        if (path != null) {
            out.append("요청: ").append(method == null ? "" : method + " ").append(path);
            if (params != null) {
                out.append("  ").append(params);
            }
            out.append('\n');
        }
        String shownSql = filledSql != null ? filledSql : sql;
        if (shownSql != null) {
            out.append("SQL:\n").append(shownSql).append('\n');
        }
        out.append("---\n");
        int n = Math.min(stackLines, lines.size());
        for (int i = 0; i < n; i++) {
            out.append(lines.get(i)).append('\n');
        }
        if (n < lines.size()) {
            out.append("... ").append(lines.size() - n).append("줄 더 있음\n");
        }
        return out.toString();
    }
}
