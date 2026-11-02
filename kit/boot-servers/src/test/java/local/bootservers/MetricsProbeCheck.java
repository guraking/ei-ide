package local.bootservers;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MetricsProbe 를 가짜 JMX metrics 끝점(스프링 부트 EndpointMBean 과 같은 이름 · 연산)에 붙여 점검한다. 실패하면 예외로 끝난다. */
public final class MetricsProbeCheck {

    private static final String REQUESTS = "http.server.requests";
    private static final Map<String, Map<String, Object>> ANSWERS = Map.of(
            REQUESTS, m(110, 2.0, 0.9, List.of(tag("outcome", "SUCCESS", "SERVER_ERROR"), tag("uri", "/actuator/prometheus", "/api/{id}", "/slow"))),
            REQUESTS + "[uri:/actuator/prometheus]", m(10, 0.1, 0.01, List.of()),
            REQUESTS + "[outcome:SERVER_ERROR]", m(3, 0.3, 0.2, List.of()),
            REQUESTS + "[uri:/api/{id}]", m(90, 0.9, 0.05, List.of(tag("method", "GET"), tag("outcome", "SUCCESS"))),
            REQUESTS + "[uri:/slow]", m(10, 1.0, 0.9, List.of(tag("method", "POST"), tag("outcome", "SERVER_ERROR"))),
            REQUESTS + "[uri:/slow, outcome:SERVER_ERROR]", m(3, 0.3, 0.2, List.of()),
            "hikaricp.connections.active", Map.of("measurements", List.of(Map.of("statistic", "VALUE", "value", 4.0))),
            "hikaricp.connections.max", Map.of("measurements", List.of(Map.of("statistic", "VALUE", "value", 10.0))));

    public static void main(String[] args) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        check(MetricsProbe.requests(server).access() == MetricsProbe.Access.NOT_EXPOSED, "끝점 없음");
        check(MetricsProbe.requests(null).access() == MetricsProbe.Access.UNREACHABLE, "연결 없음");
        List<String> asked = new ArrayList<>();
        // unique-names 를 켠 앱처럼 context 꼬리가 붙은 이름
        ObjectName name = new ObjectName("org.springframework.boot:type=Endpoint,name=Metrics,context=1a2b");
        server.registerMBean(new FakeEndpoint(asked), name);
        try {
            MetricsProbe.Requests r = MetricsProbe.requests(server);
            check(r.access() == MetricsProbe.Access.OK, "OK");
            check(r.count() == 100 && Math.abs(r.totalSeconds() - 1.9) < 1e-9, "액추에이터 뺌: " + r);
            check(r.serverErrors() == 3 && r.clientErrors() == 0, "오류 수");
            check(r.poolActive() == 4 && r.poolMax() == 10 && Double.isNaN(r.poolPending()), "DB 풀");
            for (String a : asked) {
                check(a.equals("listNames") || ANSWERS.containsKey(a), "없는 지표를 물음: " + a);
            }
            List<Metrics.Slow> slow = MetricsProbe.slow(server);
            check(slow.size() == 2, "두 주소: " + slow);
            Metrics.Slow s = Metrics.slowest(slow, 1).get(0);
            check(s.uri().equals("/slow") && s.method().equals("POST") && Math.abs(s.avgMillis() - 100) < 1e-9 && s.errors() == 3, "느린 주소: " + s);
        }
        finally {
            server.unregisterMBean(name);
        }
        System.out.println("MetricsProbeCheck 통과");
    }

    /** 스프링 부트 EndpointMBean 처럼 연산 이름으로만 고르고, 응답은 JSON 과 같은 모양의 Map 으로 돌려준다. */
    private record FakeEndpoint(List<String> asked) implements DynamicMBean {
        @Override
        public Object invoke(String action, Object[] params, String[] signature) {
            if (action.equals("listNames")) {
                asked.add(action);
                return Map.of("names", List.of(REQUESTS, "hikaricp.connections.active", "hikaricp.connections.max"));
            }
            String key = (String) params[0] + (params[1] == null ? "" : params[1].toString());
            asked.add(key);
            return ANSWERS.get(key);
        }

        @Override
        public Object getAttribute(String attribute) {
            return null;
        }

        @Override
        public void setAttribute(Attribute attribute) {
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            return new AttributeList();
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(FakeEndpoint.class.getName(), null, null, null, null, null);
        }
    }

    private static Map<String, Object> m(double count, double total, double max, List<Map<String, Object>> tags) {
        return Map.of("measurements", List.of(Map.of("statistic", "COUNT", "value", count), Map.of("statistic", "TOTAL_TIME", "value", total),
                Map.of("statistic", "MAX", "value", max)), "availableTags", tags);
    }

    private static Map<String, Object> tag(String tag, String... values) {
        return Map.of("tag", tag, "values", List.of(values));
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
