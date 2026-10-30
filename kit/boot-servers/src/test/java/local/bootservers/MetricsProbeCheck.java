package local.bootservers;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MetricsProbe 를 가짜 액추에이터에 붙여 점검한다. 실패하면 예외로 끝난다. */
public final class MetricsProbeCheck {

    private static final String BASE = "/ctx/actuator/metrics";
    private static final Map<String, String> ANSWERS = Map.of(
            BASE, "{\"names\":[\"http.server.requests\",\"hikaricp.connections.active\",\"hikaricp.connections.max\"]}",
            BASE + "/http.server.requests", m(110, 2.0, 0.9) + ",\"availableTags\":[{\"tag\":\"outcome\",\"values\":[\"SUCCESS\",\"SERVER_ERROR\"]},"
                    + "{\"tag\":\"uri\",\"values\":[\"/actuator/metrics\",\"/api/{id}\",\"/slow\"]}]}",
            BASE + "/http.server.requests?tag=uri:/actuator/metrics", m(10, 0.1, 0.01) + "}",
            BASE + "/http.server.requests?tag=outcome:SERVER_ERROR", m(3, 0.3, 0.2) + "}",
            BASE + "/http.server.requests?tag=uri:/api/{id}", m(90, 0.9, 0.05) + ",\"availableTags\":[{\"tag\":\"method\",\"values\":[\"GET\"]},{\"tag\":\"outcome\",\"values\":[\"SUCCESS\"]}]}",
            BASE + "/http.server.requests?tag=uri:/slow", m(10, 1.0, 0.9) + ",\"availableTags\":[{\"tag\":\"method\",\"values\":[\"POST\"]},{\"tag\":\"outcome\",\"values\":[\"SERVER_ERROR\"]}]}",
            BASE + "/http.server.requests?tag=uri:/slow&tag=outcome:SERVER_ERROR", m(3, 0.3, 0.2) + "}",
            BASE + "/hikaricp.connections.active", "{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":4.0}]}",
            BASE + "/hikaricp.connections.max", "{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":10.0}]}");

    public static void main(String[] args) throws IOException {
        List<String> asked = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
            String query = exchange.getRequestURI().getRawQuery();
            String key = query == null ? path : path + "?" + URLDecoder.decode(query, StandardCharsets.UTF_8);
            asked.add(key);
            String body = key.startsWith("/locked") ? null : ANSWERS.get(key);
            int code = key.startsWith("/locked") ? 401 : body == null ? 404 : 200;
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            MetricsProbe.Requests r = MetricsProbe.requests(root + "/ctx/actuator");
            check(r.access() == MetricsProbe.Access.OK, "OK");
            check(r.count() == 100 && Math.abs(r.totalSeconds() - 1.9) < 1e-9, "액추에이터 뺌: " + r);
            check(r.serverErrors() == 3 && r.clientErrors() == 0, "오류 수");
            check(r.poolActive() == 4 && r.poolMax() == 10 && Double.isNaN(r.poolPending()), "DB 풀");
            for (String a : asked) {
                check(ANSWERS.containsKey(a), "없는 지표를 물음: " + a);
            }
            List<Metrics.Slow> slow = MetricsProbe.slow(root + "/ctx/actuator");
            check(slow.size() == 2, "두 주소: " + slow);
            Metrics.Slow s = Metrics.slowest(slow, 1).get(0);
            check(s.uri().equals("/slow") && s.method().equals("POST") && Math.abs(s.avgMillis() - 100) < 1e-9 && s.errors() == 3, "느린 주소: " + s);
            check(MetricsProbe.requests(root + "/locked").access() == MetricsProbe.Access.FORBIDDEN, "로그인 필요");
            check(MetricsProbe.requests(root + "/none").access() == MetricsProbe.Access.NOT_EXPOSED, "열리지 않음");
            check(MetricsProbe.requests(null).access() == MetricsProbe.Access.NONE, "주소 없음");
        }
        finally {
            server.stop(0);
        }
        check(MetricsProbe.requests("http://127.0.0.1:1/actuator").access() == MetricsProbe.Access.UNREACHABLE, "연결 안 됨");
        System.out.println("MetricsProbeCheck 통과");
    }

    private static String m(double count, double total, double max) {
        return "{\"measurements\":[{\"statistic\":\"COUNT\",\"value\":" + count + "},{\"statistic\":\"TOTAL_TIME\",\"value\":" + total
                + "},{\"statistic\":\"MAX\",\"value\":" + max + "}]";
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
