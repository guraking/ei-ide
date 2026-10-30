package local.bootservers;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실행 중인 스프링 부트의 /actuator/metrics 를 읽어 요청 · 응답 시간 · 오류 · DB 풀 값을 잰다. 화면 스레드 밖에서 부른다.
 * 서버에 없는 지표는 묻지 않는다(묻는 요청이 404 로 4xx 오류에 세어지지 않게). 액추에이터 자신의 요청 수 · 시간은 뺀다.
 */
final class MetricsProbe {

    /** 지표를 읽을 수 있는가. NONE 은 액추에이터 주소가 없는 서버(WAS · 포트 모름). */
    enum Access { OK, NOT_EXPOSED, FORBIDDEN, UNREACHABLE, NONE }

    /** 요청 쪽 값. Access 가 OK 가 아니면 값은 모두 NaN. */
    record Requests(Access access, double count, double totalSeconds, double maxSeconds, double serverErrors, double clientErrors,
                    double poolActive, double poolMax, double poolPending) {
        static Requests of(Access access) {
            return new Requests(access, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final String METRICS = "/metrics";
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
    // 지표 하나를 묻는 요청의 uri 태그 끝(스프링 부트 3: /actuator/metrics/{requiredMetricName})
    private static final String METRIC_URI_END = "/metrics/{requiredMetricName}";
    // 어느 매핑에도 맞지 않은 요청(404)의 uri 태그. 느린 요청 목록에서 뺀다.
    private static final String UNMATCHED_URI = "/**";
    // 느린 요청을 고를 때 물어보는 주소 수 한도. 주소가 많은 서버에서 한 번에 수백 번 묻지 않게 한다.
    private static final int MAX_URIS = 100;
    private static final String METHOD_JOIN = ", ";
    private static final double MILLIS_PER_SECOND = 1000;

    private MetricsProbe() {
    }

    /** actuator 는 「http://localhost:포트[/컨텍스트]/actuator」. */
    static Requests requests(String actuator) {
        if (actuator == null) {
            return Requests.of(Access.NONE);
        }
        try {
            Answer list = get(actuator + METRICS);
            if (list.code() != HTTP_OK) {
                return Requests.of(accessOf(list.code()));
            }
            Set<String> names = Metrics.names(list.body());
            double count = 0;
            double total = 0;
            double max = 0;
            double serverErrors = 0;
            double clientErrors = 0;
            if (names.contains(REQUESTS)) {
                String all = get(metric(actuator, REQUESTS)).body();
                Map<String, Double> m = Metrics.measurements(all);
                count = m.getOrDefault(COUNT, 0d);
                total = m.getOrDefault(TOTAL_TIME, 0d);
                max = m.getOrDefault(MAX, 0d);
                // 전체 값을 읽은 요청과 그 뒤 주소별 요청은 끝날 때 세어지므로, 주소별 값에는 들고 전체 값에는 없다. 그만큼 덜 뺀다.
                int ownSinceTotal = 1;
                for (String uri : Metrics.tagValues(all, TAG_URI)) {
                    if (uri.startsWith(ACTUATOR_URI)) {
                        Map<String, Double> own = Metrics.measurements(get(metric(actuator, REQUESTS, TAG_URI, uri)).body());
                        double ownCount = own.getOrDefault(COUNT, 0d);
                        double ownTotal = own.getOrDefault(TOTAL_TIME, 0d);
                        if (uri.endsWith(METRIC_URI_END) && ownCount > 0) {
                            // 덜 빼는 요청들의 시간은 따로 알 수 없어 이 주소 평균 시간으로 친다.
                            double counted = Math.max(ownCount - ownSinceTotal, 0);
                            count -= counted;
                            total -= ownTotal * counted / ownCount;
                        }
                        else {
                            count -= ownCount;
                            total -= ownTotal;
                        }
                        ownSinceTotal++;
                    }
                }
                List<String> outcomes = Metrics.tagValues(all, TAG_OUTCOME);
                serverErrors = outcomes.contains(SERVER_ERROR) ? countOf(actuator, TAG_OUTCOME, SERVER_ERROR) : 0;
                clientErrors = outcomes.contains(CLIENT_ERROR) ? countOf(actuator, TAG_OUTCOME, CLIENT_ERROR) : 0;
            }
            return new Requests(Access.OK, Math.max(count, 0), Math.max(total, 0), max, serverErrors, clientErrors,
                    names.contains(POOL_ACTIVE) ? value(actuator, POOL_ACTIVE) : Double.NaN,
                    names.contains(POOL_MAX) ? value(actuator, POOL_MAX) : Double.NaN,
                    names.contains(POOL_PENDING) ? value(actuator, POOL_PENDING) : Double.NaN);
        }
        catch (IOException e) {
            return Requests.of(Access.UNREACHABLE);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Requests.of(Access.UNREACHABLE);
        }
    }

    /** 주소별 횟수 · 평균 · 최대 · 5xx(액추에이터 주소 제외). 읽을 수 없으면 빈 목록. */
    static List<Metrics.Slow> slow(String actuator) {
        List<Metrics.Slow> result = new ArrayList<>();
        if (actuator == null) {
            return result;
        }
        try {
            Answer all = get(metric(actuator, REQUESTS));
            if (all.code() != HTTP_OK) {
                return result;
            }
            List<String> uris = Metrics.tagValues(all.body(), TAG_URI);
            for (String uri : uris.subList(0, Math.min(uris.size(), MAX_URIS))) {
                if (uri.startsWith(ACTUATOR_URI) || uri.equals(UNMATCHED_URI)) {
                    continue;
                }
                Answer one = get(metric(actuator, REQUESTS, TAG_URI, uri));
                if (one.code() != HTTP_OK) {
                    continue;
                }
                Map<String, Double> m = Metrics.measurements(one.body());
                double count = m.getOrDefault(COUNT, 0d);
                if (count <= 0) {
                    continue;
                }
                long errors = 0;
                if (Metrics.tagValues(one.body(), TAG_OUTCOME).contains(SERVER_ERROR)) {
                    Answer failed = get(metric(actuator, REQUESTS, TAG_URI, uri, TAG_OUTCOME, SERVER_ERROR));
                    errors = Math.round(Metrics.measurements(failed.body()).getOrDefault(COUNT, 0d));
                }
                result.add(new Metrics.Slow(String.join(METHOD_JOIN, Metrics.tagValues(one.body(), TAG_METHOD)), uri, Math.round(count),
                        m.getOrDefault(TOTAL_TIME, 0d) / count * MILLIS_PER_SECOND, m.getOrDefault(MAX, 0d) * MILLIS_PER_SECOND, errors));
            }
        }
        catch (IOException e) {
            return result;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    private static Access accessOf(int code) {
        return code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ? Access.FORBIDDEN : Access.NOT_EXPOSED;
    }

    private static double countOf(String actuator, String tag, String value) throws IOException, InterruptedException {
        return Metrics.measurements(get(metric(actuator, REQUESTS, tag, value)).body()).getOrDefault(COUNT, 0d);
    }

    private static double value(String actuator, String name) throws IOException, InterruptedException {
        return Metrics.measurements(get(metric(actuator, name)).body()).getOrDefault(VALUE, Double.NaN);
    }

    /** …/metrics/{이름}?tag=키:값&tag=… (tags 는 키 · 값 차례). */
    private static String metric(String actuator, String name, String... tags) {
        StringBuilder url = new StringBuilder(actuator).append(METRICS).append('/').append(name);
        for (int i = 0; i + 1 < tags.length; i += 2) {
            url.append(i == 0 ? '?' : '&').append("tag=").append(URLEncoder.encode(tags[i] + ":" + tags[i + 1], StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private record Answer(int code, String body) {
    }

    private static Answer get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Answer(response.statusCode(), response.body());
    }
}
