package local.bootservers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버 설정(application*.yml · properties)에서 서버가 붙는 곳(DB · Redis · 다른 HTTP 서버)을 찾는다.
 * 프로필 규칙: application.* 은 늘, application-{프로필}.* 은 그 프로필이 켜졌을 때, yml 문서의 spring.config.activate.on-profile
 * (옛 spring.profiles) 은 값이 켜진 프로필과 맞을 때 읽는다. 같은 키는 뒤에 읽은 것이 이긴다(프로필 파일이 기본 파일을 덮는다).
 * 값의 ${키:기본값} 은 같은 설정의 키, 없으면 기본값으로 바꾸고, 둘 다 없으면 그 값은 건너뛴다.
 * 인텔리제이 클래스를 쓰지 않는다(LinksCheck 로 점검).
 */
final class Links {

    enum Kind { DATABASE, REDIS, HTTP }

    /** 설정 파일 하나. name 은 파일 이름(application-local.yml), source 는 부르는 쪽이 파일을 다시 찾을 때 쓰는 번호. */
    record Source(String name, boolean yaml, List<ConfigKeys.Entry> entries) {
    }

    /** 붙는 곳 하나. title 은 상자 제목, source · offset 은 그 값이 적힌 파일 번호와 글 위치. */
    record Target(Kind kind, String title, String host, int port, String key, String value, int source, int offset) {
        String address() {
            return host + ":" + port;
        }
    }

    private record Found(ConfigKeys.Entry entry, int source) {
    }

    private static final String DEFAULT_PROFILE = "default";
    private static final String BASE_NAME = "application";
    private static final String PROFILE_SEPARATOR = "-";
    private static final String NOT = "!";
    private static final String[] PROFILE_KEYS = {"spring.config.activate.on-profile", "spring.profiles"};
    private static final String ACTIVE_KEY = "spring.profiles.active";
    // 켜진 프로필이 없을 때 쓰는 프로필 이름. 이 키가 없으면 스프링 기본값 default.
    private static final String DEFAULT_KEY = "spring.profiles.default";
    private static final String PORT_KEY = "server.port";
    private static final String JDBC = "jdbc:";
    // 다른 드라이버를 감싸 로그만 남기는 드라이버. 감싼 뒤의 주소로 읽는다.
    private static final String[] JDBC_WRAPPERS = {"log4jdbc:", "p6spy:"};
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final Pattern HTTP_URL = Pattern.compile("(?i)^(https?)://([^/:?#\\s,]+)(?::(\\d+))?");
    private static final Pattern REDIS_URL = Pattern.compile("(?i)^rediss?://(?:[^@/]*@)?([^/:?#\\s]+)(?::(\\d+))?");
    // jdbc:하위규약://호스트[:포트][/;?]... (MySQL · MariaDB · PostgreSQL · SQL Server · H2 tcp · DB2)
    private static final Pattern JDBC_SLASHES = Pattern.compile("^([a-z0-9]+)(?::tcp)?://([^/:;?,\\s]+)(?::(\\d+))?([/;?].*)?$", Pattern.CASE_INSENSITIVE);
    // Oracle · Tibero thin: @호스트:포트:SID, @호스트:포트/서비스, @//호스트:포트/서비스
    private static final Pattern JDBC_THIN = Pattern.compile("^([a-z]+):thin:@(?://)?([^/:\\s]+)(?::(\\d+))?(?:[:/]([^?\\s]+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TNS_HOST = Pattern.compile("(?i)\\(HOST\\s*=\\s*([^)\\s]+)\\s*\\)");
    private static final Pattern TNS_PORT = Pattern.compile("(?i)\\(PORT\\s*=\\s*(\\d+)\\s*\\)");
    private static final Pattern TNS_SERVICE = Pattern.compile("(?i)\\((?:SERVICE_NAME|SID)\\s*=\\s*([^)\\s]+)\\s*\\)");
    private static final Pattern SQLSERVER_DB = Pattern.compile("(?i);database(?:name)?=([^;]+)");
    // ${} 안에 ${} 가 이어질 때 따라가는 최대 횟수. 서로를 가리키는 키에서 멈추게 한다.
    private static final int MAX_ROUNDS = 5;
    private static final String TITLE_JOIN = " · ";
    private static final String REDIS_TITLE = "Redis";
    private static final String HTTP_TITLE = "HTTP";
    private static final int REDIS_PORT = 6379;
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private static final String HTTPS = "https";
    // 드라이버 이름 → { 보이는 이름, 기본 포트 }
    private static final Map<String, Object[]> DATABASES = new HashMap<>();

    static {
        DATABASES.put("oracle", new Object[]{"Oracle", 1521});
        DATABASES.put("tibero", new Object[]{"Tibero", 8629});
        DATABASES.put("mysql", new Object[]{"MySQL", 3306});
        DATABASES.put("mariadb", new Object[]{"MariaDB", 3306});
        DATABASES.put("postgresql", new Object[]{"PostgreSQL", 5432});
        DATABASES.put("sqlserver", new Object[]{"SQL Server", 1433});
        DATABASES.put("h2", new Object[]{"H2", 9092});
        DATABASES.put("db2", new Object[]{"DB2", 50000});
    }

    private Links() {
    }

    /** VM 옵션에도 프로필이 없으면 설정의 spring.profiles.active, 그것도 없으면 설정의 spring.profiles.default, 그것도 없으면 default. 쉼표로 여럿. */
    static Set<String> profiles(String vmProfile, List<Source> sources) {
        String value = vmProfile == null || vmProfile.isBlank() ? baseValue(sources, ACTIVE_KEY) : vmProfile;
        Set<String> result = names(value);
        if (result.isEmpty()) {
            result = names(baseValue(sources, DEFAULT_KEY));
        }
        if (result.isEmpty()) {
            result.add(DEFAULT_PROFILE);
        }
        return result;
    }

    private static Set<String> names(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value != null) {
            for (String p : value.split(",")) {
                if (!p.isBlank()) {
                    result.add(p.strip());
                }
            }
        }
        return result;
    }

    /** 켜진 프로필로 합친 설정의 key 값(${} 를 바꾼 값). 없거나 바꿀 수 없으면 null. */
    static String value(List<Source> sources, Set<String> profiles, String key) {
        Map<String, Found> merged = merge(sources, profiles);
        Found found = merged.get(ConfigKeys.normalize(key));
        return found == null ? null : resolve(found.entry().value(), merged);
    }

    /** 켜진 프로필로 합친 설정의 server.port. 없거나 숫자가 아니면 null. */
    static Integer port(List<Source> sources, Set<String> profiles) {
        String value = value(sources, profiles, PORT_KEY);
        try {
            return value == null ? null : Integer.valueOf(value.strip());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** 켜진 프로필로 합친 설정에서 붙는 곳들. 같은 종류 · 주소는 처음 것 하나만. 글 순서(파일 순 · 줄 순). */
    static List<Target> targets(List<Source> sources, Set<String> profiles) {
        Map<String, Found> merged = merge(sources, profiles);
        Map<String, Target> result = new LinkedHashMap<>();
        for (Found found : merged.values()) {
            String value = resolve(found.entry().value(), merged);
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                Target t = target(found, part.strip(), merged);
                if (t != null) {
                    result.putIfAbsent(t.kind() + "|" + t.address().toLowerCase(Locale.ROOT), t);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    /** 파일 이름 application-{p}.* 에서 p 들(파일 이름 순서). 대시보드의 프로필 고르기 목록에 쓴다. */
    static Set<String> profileNames(List<Source> sources) {
        Set<String> result = new LinkedHashSet<>();
        for (Source s : sources) {
            String p = profileOfFile(s.name());
            if (p != null) {
                result.add(p);
            }
        }
        return result;
    }

    private static Target target(Found found, String value, Map<String, Found> merged) {
        ConfigKeys.Entry e = found.entry();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith(JDBC)) {
            return jdbc(found, value);
        }
        Matcher redis = REDIS_URL.matcher(value);
        if (redis.find()) {
            return new Target(Kind.REDIS, REDIS_TITLE, redis.group(1), portOr(redis.group(2), REDIS_PORT), e.key(), value, found.source(), e.offset());
        }
        Matcher http = HTTP_URL.matcher(value);
        if (http.find()) {
            int port = portOr(http.group(3), HTTPS.equalsIgnoreCase(http.group(1)) ? HTTPS_PORT : HTTP_PORT);
            return new Target(Kind.HTTP, HTTP_TITLE + TITLE_JOIN + parentKey(e.key()), http.group(2), port, e.key(), value, found.source(), e.offset());
        }
        String key = ConfigKeys.normalize(e.key());
        if (key.endsWith(".redis.host")) {
            String portKey = key.substring(0, key.length() - "host".length()) + "port";
            Found portEntry = merged.get(portKey);
            String portValue = portEntry == null ? null : resolve(portEntry.entry().value(), merged);
            return new Target(Kind.REDIS, REDIS_TITLE, value, portOr(portValue, REDIS_PORT), e.key(), value, found.source(), e.offset());
        }
        return null;
    }

    private static Target jdbc(Found found, String value) {
        String rest = value.substring(JDBC.length());
        for (String wrapper : JDBC_WRAPPERS) {
            if (rest.toLowerCase(Locale.ROOT).startsWith(wrapper)) {
                rest = rest.substring(wrapper.length());
            }
        }
        String driver;
        String host;
        String port;
        String name;
        Matcher thin = JDBC_THIN.matcher(rest);
        Matcher slashes = JDBC_SLASHES.matcher(rest);
        int at = rest.indexOf('@');
        if (at > 0 && rest.indexOf('(', at) > 0) {
            Matcher h = TNS_HOST.matcher(rest);
            Matcher p = TNS_PORT.matcher(rest);
            Matcher s = TNS_SERVICE.matcher(rest);
            if (!h.find()) {
                return null;
            }
            driver = rest.substring(0, rest.indexOf(':'));
            host = h.group(1);
            port = p.find() ? p.group(1) : null;
            name = s.find() ? s.group(1) : null;
        }
        else if (thin.find()) {
            driver = thin.group(1);
            host = thin.group(2);
            port = thin.group(3);
            name = thin.group(4);
        }
        else if (slashes.find()) {
            driver = slashes.group(1);
            host = slashes.group(2);
            port = slashes.group(3);
            name = databaseName(slashes.group(4));
        }
        else {
            return null;
        }
        Object[] db = DATABASES.get(driver.toLowerCase(Locale.ROOT));
        if (db == null) {
            return null;
        }
        String title = name == null || name.isEmpty() ? (String) db[0] : db[0] + TITLE_JOIN + name;
        ConfigKeys.Entry e = found.entry();
        return new Target(Kind.DATABASE, title, host, portOr(port, (Integer) db[1]), e.key(), value, found.source(), e.offset());
    }

    /** 주소 뒤쪽(/이름?.. · ;databaseName=이름)에서 DB 이름. 없으면 null. */
    private static String databaseName(String tail) {
        if (tail == null || tail.isEmpty()) {
            return null;
        }
        Matcher m = SQLSERVER_DB.matcher(tail);
        if (m.find()) {
            return m.group(1);
        }
        if (tail.charAt(0) != '/') {
            return null;
        }
        String name = tail.substring(1);
        int end = name.length();
        for (char c : new char[]{'?', ';', '/'}) {
            int i = name.indexOf(c);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return name.substring(0, end);
    }

    private static int portOr(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.strip());
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** a.b.base-url → a.b. 한 단계 키면 그대로. */
    private static String parentKey(String key) {
        int dot = key.lastIndexOf('.');
        return dot <= 0 ? key : key.substring(0, dot);
    }

    /** 켜진 프로필로 읽을 값들을 합친다. 키는 느슨한 바인딩 키, 값은 이긴 Entry. 순서는 처음 나온 순서. */
    private static Map<String, Found> merge(List<Source> sources, Set<String> profiles) {
        Map<String, Found> merged = new LinkedHashMap<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (profileOfFile(sources.get(i).name()) == null) {
                order.add(i);
            }
        }
        for (String profile : profiles) {
            for (int i = 0; i < sources.size(); i++) {
                if (profile.equals(profileOfFile(sources.get(i).name()))) {
                    order.add(i);
                }
            }
        }
        for (int i : order) {
            Source s = sources.get(i);
            Set<Integer> skipped = skippedDocuments(s, profiles);
            for (ConfigKeys.Entry e : s.entries()) {
                if (!skipped.contains(e.document())) {
                    merged.put(ConfigKeys.normalize(e.key()), new Found(e, i));
                }
            }
        }
        return merged;
    }

    /** yml 문서 중 on-profile 이 켜진 프로필과 맞지 않는 문서 번호들. */
    private static Set<Integer> skippedDocuments(Source s, Set<String> profiles) {
        Set<Integer> result = new LinkedHashSet<>();
        for (ConfigKeys.Entry e : s.entries()) {
            for (String key : PROFILE_KEYS) {
                if (ConfigKeys.normalize(e.key()).equals(ConfigKeys.normalize(key)) && !matches(e.value(), profiles)) {
                    result.add(e.document());
                }
            }
        }
        return result;
    }

    /**
     * on-profile 값이 켜진 프로필과 맞는가. 스프링 Profiles.of 와 같은 식: 쉼표로 여럿(하나라도 맞으면),
     * 각 식은 ! (아님) · &amp; (그리고) · | (또는) · 괄호. &amp; 가 | 보다 먼저 묶인다. 식이 깨졌으면 맞지 않는 것으로 본다.
     */
    static boolean matches(String expression, Set<String> profiles) {
        for (String part : expression.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            ProfileExpression p = new ProfileExpression(part, profiles);
            if (p.parse()) {
                return true;
            }
        }
        return false;
    }

    /** 한 프로필 식을 앞에서부터 읽어 맞는지 계산한다. */
    private static final class ProfileExpression {
        private final String text;
        private final Set<String> profiles;
        private int pos;
        private boolean broken;

        ProfileExpression(String text, Set<String> profiles) {
            this.text = text;
            this.profiles = profiles;
        }

        boolean parse() {
            boolean result = or();
            skipSpaces();
            return result && !broken && pos == text.length();
        }

        private boolean or() {
            boolean result = and();
            while (take('|')) {
                result |= and();
            }
            return result;
        }

        private boolean and() {
            boolean result = not();
            while (take('&')) {
                result &= not();
            }
            return result;
        }

        private boolean not() {
            if (take(NOT.charAt(0))) {
                return !not();
            }
            if (take('(')) {
                boolean result = or();
                if (!take(')')) {
                    broken = true;
                }
                return result;
            }
            skipSpaces();
            int start = pos;
            while (pos < text.length() && "()&|!".indexOf(text.charAt(pos)) < 0 && !Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                broken = true;
                return false;
            }
            return profiles.contains(text.substring(start, pos));
        }

        private boolean take(char ch) {
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == ch) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }

    /** application-{p}.yml → p, application.yml · 다른 이름 → null. */
    private static String profileOfFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        String prefix = BASE_NAME + PROFILE_SEPARATOR;
        return stem.startsWith(prefix) && stem.length() > prefix.length() ? stem.substring(prefix.length()) : null;
    }

    /** 프로필 파일 · 프로필 문서가 아닌 곳의 값. 없으면 null. */
    private static String baseValue(List<Source> sources, String key) {
        String wanted = ConfigKeys.normalize(key);
        String result = null;
        for (Source s : sources) {
            if (profileOfFile(s.name()) != null) {
                continue;
            }
            Set<Integer> profiled = new LinkedHashSet<>();
            for (ConfigKeys.Entry e : s.entries()) {
                for (String k : PROFILE_KEYS) {
                    if (ConfigKeys.normalize(e.key()).equals(ConfigKeys.normalize(k))) {
                        profiled.add(e.document());
                    }
                }
            }
            for (ConfigKeys.Entry e : s.entries()) {
                if (!profiled.contains(e.document()) && ConfigKeys.normalize(e.key()).equals(wanted)) {
                    result = e.value();
                }
            }
        }
        return result;
    }

    /** ${키:기본값} 을 바꾼다. 바꿀 수 없는 자리가 있으면 null. 키가 다시 ${} 를 품으면 한 번 더(순환은 한도에서 멈춤). */
    private static String resolve(String value, Map<String, Found> merged) {
        String current = value;
        for (int round = 0; round < MAX_ROUNDS && current.contains("${"); round++) {
            Matcher m = PLACEHOLDER.matcher(current);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                Found found = merged.get(ConfigKeys.normalize(m.group(1)));
                String replacement = found != null ? found.entry().value() : m.group(2);
                if (replacement == null) {
                    return null;
                }
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            current = out.toString();
        }
        return current.contains("${") ? null : current;
    }
}
