package local.bootservers;

import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실행 중인 스프링 부트의 액추에이터 metrics 끝점을 JMX 로 읽어 요청 · 응답 시간 · 오류 · DB 풀 값을 잰다. 화면 스레드 밖에서 부른다.
 * HTTP 를 거치지 않으므로 앱의 보안 필터 · 세션 · 접근 로그를 건드리지 않고, 요청 수에도 세어지지 않는다.
 * 다른 도구가 부른 /actuator 주소의 요청 수 · 시간은 뺀다.
 */
final class MetricsProbe {

    /** 지표를 읽을 수 있는가. NOT_EXPOSED 는 JMX 에 metrics 끝점이 없음, UNREACHABLE 은 attach · 읽기 실패, NONE 은 WAS. */
    enum Access { OK, NOT_EXPOSED, UNREACHABLE, NONE }

    /** 요청 쪽 값. Access 가 OK 가 아니면 값은 모두 NaN. */
    record Requests(Access access, double count, double totalSeconds, double maxSeconds, double serverErrors, double clientErrors,
                    double poolActive, double poolMax, double poolPending) {
        static Requests of(Access access) {
            return new Requests(access, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private static final String METRICS_ENDPOINT = "Metrics";
    private static final String LIST_OPERATION = "listNames";
    private static final String METRIC_OPERATION = "metric";
    private static final String[] METRIC_SIGNATURE = {String.class.getName(), List.class.getName()};
    private static final String REQUESTS = "http.server.requests";
    private static final String POOL_ACTIVE = "hikaricp.connections.active";
    private static final String POOL_MAX = "hikaricp.connections.max";
    private static final String POOL_PENDING = "hikaricp.connections.pending";
    private static final String COUNT = "COUNT";
    private static final String TOTAL_TIME = "TOTAL_TIME";
    private static final String MAX = "MAX";
    private static final String VALUE = "VALUE";
    private static final String TAG_URI = "uri";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_METHOD = "method";
    private static final String SERVER_ERROR = "SERVER_ERROR";
    private static final String CLIENT_ERROR = "CLIENT_ERROR";
    // 액추에이터 요청의 uri 태그 앞부분. 서버 요청 수에서 뺀다.
    private static final String ACTUATOR_URI = "/actuator";
    // 어느 매핑에도 맞지 않은 요청(404)의 uri 태그. 느린 요청 목록에서 뺀다.
    private static final String UNMATCHED_URI = "/**";
    // 느린 요청을 고를 때 물어보는 주소 수 한도. 주소가 많은 서버에서 한 번에 수백 번 묻지 않게 한다.
    private static final int MAX_URIS = 100;
    private static final String METHOD_JOIN = ", ";
    private static final double MILLIS_PER_SECOND = 1000;

    private MetricsProbe() {
    }

    /** server 는 실행 중인 스프링 부트에 attach 로 연 JMX 연결(못 열었으면 null). */
    static Requests requests(MBeanServerConnection server) {
        if (server == null) {
            return Requests.of(Access.UNREACHABLE);
        }
        try {
            ObjectName endpoint = ServerProbe.endpoint(server, METRICS_ENDPOINT);
            if (endpoint == null) {
                return Requests.of(Access.NOT_EXPOSED);
            }
            Set<String> names = Metrics.names(names(server, endpoint));
            double count = 0;
            double total = 0;
            double max = 0;
            double serverErrors = 0;
            double clientErrors = 0;
            if (names.contains(REQUESTS)) {
                Map<?, ?> all = metric(server, endpoint, REQUESTS);
                Map<String, Double> m = Metrics.measurements(all);
                count = m.getOrDefault(COUNT, 0d);
                total = m.getOrDefault(TOTAL_TIME, 0d);
                max = m.getOrDefault(MAX, 0d);
                for (String uri : Metrics.tagValues(all, TAG_URI)) {
                    if (uri.startsWith(ACTUATOR_URI)) {
                        Map<String, Double> own = Metrics.measurements(metric(server, endpoint, REQUESTS, TAG_URI, uri));
                        count -= own.getOrDefault(COUNT, 0d);
                        total -= own.getOrDefault(TOTAL_TIME, 0d);
                    }
                }
                List<String> outcomes = Metrics.tagValues(all, TAG_OUTCOME);
                serverErrors = outcomes.contains(SERVER_ERROR) ? countOf(server, endpoint, TAG_OUTCOME, SERVER_ERROR) : 0;
                clientErrors = outcomes.contains(CLIENT_ERROR) ? countOf(server, endpoint, TAG_OUTCOME, CLIENT_ERROR) : 0;
            }
            return new Requests(Access.OK, Math.max(count, 0), Math.max(total, 0), max, serverErrors, clientErrors,
                    names.contains(POOL_ACTIVE) ? value(server, endpoint, POOL_ACTIVE) : Double.NaN,
                    names.contains(POOL_MAX) ? value(server, endpoint, POOL_MAX) : Double.NaN,
                    names.contains(POOL_PENDING) ? value(server, endpoint, POOL_PENDING) : Double.NaN);
        }
        catch (IOException | JMException | RuntimeException e) {
            return Requests.of(Access.UNREACHABLE);
        }
    }

    /** 주소별 횟수 · 평균 · 최대 · 5xx(액추에이터 주소 제외). 읽을 수 없으면 빈 목록. */
    static List<Metrics.Slow> slow(MBeanServerConnection server) {
        List<Metrics.Slow> result = new ArrayList<>();
        if (server == null) {
            return result;
        }
        try {
            ObjectName endpoint = ServerProbe.endpoint(server, METRICS_ENDPOINT);
            if (endpoint == null) {
                return result;
            }
            List<String> uris = Metrics.tagValues(metric(server, endpoint, REQUESTS), TAG_URI);
            for (String uri : uris.subList(0, Math.min(uris.size(), MAX_URIS))) {
                if (uri.startsWith(ACTUATOR_URI) || uri.equals(UNMATCHED_URI)) {
                    continue;
                }
                Map<?, ?> one = metric(server, endpoint, REQUESTS, TAG_URI, uri);
                Map<String, Double> m = Metrics.measurements(one);
                double count = m.getOrDefault(COUNT, 0d);
                if (count <= 0) {
                    continue;
                }
                long errors = 0;
                if (Metrics.tagValues(one, TAG_OUTCOME).contains(SERVER_ERROR)) {
                    Map<?, ?> failed = metric(server, endpoint, REQUESTS, TAG_URI, uri, TAG_OUTCOME, SERVER_ERROR);
                    errors = Math.round(Metrics.measurements(failed).getOrDefault(COUNT, 0d));
                }
                result.add(new Metrics.Slow(String.join(METHOD_JOIN, Metrics.tagValues(one, TAG_METHOD)), uri, Math.round(count),
                        m.getOrDefault(TOTAL_TIME, 0d) / count * MILLIS_PER_SECOND, m.getOrDefault(MAX, 0d) * MILLIS_PER_SECOND, errors));
            }
        }
        catch (IOException | JMException | RuntimeException e) {
            return result;
        }
        return result;
    }

    private static double countOf(MBeanServerConnection server, ObjectName endpoint, String tag, String value) throws IOException, JMException {
        return Metrics.measurements(metric(server, endpoint, REQUESTS, tag, value)).getOrDefault(COUNT, 0d);
    }

    private static double value(MBeanServerConnection server, ObjectName endpoint, String name) throws IOException, JMException {
        return Metrics.measurements(metric(server, endpoint, name)).getOrDefault(VALUE, Double.NaN);
    }

    /** 끝점의 listNames: { names: [...] }. */
    private static Map<?, ?> names(MBeanServerConnection server, ObjectName endpoint) throws IOException, JMException {
        return asMap(server.invoke(endpoint, LIST_OPERATION, new Object[0], new String[0]));
    }

    /**
     * 끝점의 metric(이름, 태그들): /actuator/metrics/{이름}?tag=키:값 과 같은 응답. tags 는 키 · 값 차례.
     * 끝점의 tag 매개변수는 List<String>(「키:값」들)이고, 없으면 null 을 넘긴다.
     */
    private static Map<?, ?> metric(MBeanServerConnection server, ObjectName endpoint, String name, String... tags) throws IOException, JMException {
        String[] pairs = new String[tags.length / 2];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = tags[i * 2] + ":" + tags[i * 2 + 1];
        }
        Object[] params = {name, pairs.length == 0 ? null : List.of(pairs)};
        return asMap(server.invoke(endpoint, METRIC_OPERATION, params, METRIC_SIGNATURE));
    }

    /** 끝점 응답은 Map 이다(스프링 부트가 JSON 과 같은 모양으로 바꿔 준다). 다르면 빈 Map. */
    private static Map<?, ?> asMap(Object answer) {
        return answer instanceof Map<?, ?> map ? map : Map.of();
    }
}
