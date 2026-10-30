package local.bootservers;

import java.util.List;
import java.util.Map;

/** Metrics 자체 점검. 실패하면 예외로 끝난다. */
public final class MetricsCheck {

    public static void main(String[] args) {
        parse();
        exposure();
        history();
        System.out.println("MetricsCheck 통과");
    }

    private static void parse() {
        String json = "{\"name\":\"http.server.requests\",\"measurements\":[{\"statistic\":\"COUNT\",\"value\":12.0},"
                + "{\"statistic\":\"TOTAL_TIME\",\"value\":0.53},{\"statistic\":\"MAX\",\"value\":1.2E-2}],"
                + "\"availableTags\":[{\"tag\":\"method\",\"values\":[\"GET\"]},{\"tag\":\"uri\",\"values\":[\"/api/{id}\",\"/a\\\"b\",\"\\u002Fx\"]}]}";
        Map<String, Double> m = Metrics.measurements(json);
        check(m.get("COUNT") == 12.0 && m.get("TOTAL_TIME") == 0.53 && m.get("MAX") == 0.012, "measurements " + m);
        check(Metrics.tagValues(json, "uri").equals(List.of("/api/{id}", "/a\"b", "/x")), "uri " + Metrics.tagValues(json, "uri"));
        check(Metrics.tagValues(json, "outcome").isEmpty(), "없는 태그");
        check(Metrics.names("{\"names\":[\"jvm.threads.live\",\"hikaricp.connections.active\"]}").contains("hikaricp.connections.active"), "names");
    }

    private static void exposure() {
        check(Metrics.exposure(null).equals("health,metrics"), "없음");
        check(Metrics.exposure("health,info").equals("health,info,metrics"), "합침");
        check(Metrics.exposure("*") == null, "전부");
        check(Metrics.exposure("metrics, health") == null, "이미 있음");
    }

    private static void history() {
        Metrics.History h = new Metrics.History();
        h.add(sample(0, 100, 10, 1));
        check(Double.isNaN(h.perMinute(Metrics.Sample::requests)), "하나면 모름");
        h.add(sample(30_000, 160, 13, 1));
        h.add(sample(60_000, 220, 16, 3));
        check(near(h.perMinute(Metrics.Sample::requests), 120), "분당 " + h.perMinute(Metrics.Sample::requests));
        check(near(h.averageMillis(), 50), "평균 " + h.averageMillis());
        check(near(h.errorRate(), 2.0 / 120), "오류율");
        double[] avg = h.averageSeries();
        check(Double.isNaN(avg[0]) && near(avg[1], 50), "구간 평균");
        h.add(sample(61_000, 220, 16, 3));
        check(Double.isNaN(h.averageSeries()[3]), "요청 없는 구간");
        h.add(sample(70_000, 5, 1, 0));
        check(h.all().size() == 1, "다시 켜지면 비움");
        for (long t = 0; t <= Metrics.HISTORY_MILLIS + 20_000; t += 5_000) {
            h.add(sample(70_000 + t, 10 + t, 1, 0));
        }
        Metrics.Sample first = h.all().get(0);
        check(h.latest().time() - first.time() <= Metrics.HISTORY_MILLIS, "10분만");
        List<Metrics.Slow> slow = Metrics.slowest(List.of(new Metrics.Slow("GET", "/a", 1, 10, 10, 0),
                new Metrics.Slow("GET", "/b", 1, 30, 30, 0), new Metrics.Slow("GET", "/c", 1, 20, 20, 0)), 2);
        check(slow.size() == 2 && slow.get(0).uri().equals("/b") && slow.get(1).uri().equals("/c"), "느린 순");
    }

    private static Metrics.Sample sample(long time, double requests, double totalSeconds, double errors) {
        return new Metrics.Sample(time, 0.1, 8, 1, 2, 3, 4, 5, requests, totalSeconds, 0.2, errors, 0, 1, 10, 0);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
