package local.bootservers;

import java.util.List;
import java.util.Map;

/** Metrics 자체 점검. 실패하면 예외로 끝난다. */
public final class MetricsCheck {

    public static void main(String[] args) {
        parse();
        exposure();
        history();
        restartAfterUnknown();
        unknownBaseline();
        System.out.println("MetricsCheck 통과");
    }

    private static void parse() {
        Map<String, Object> answer = Map.of("name", "http.server.requests",
                "measurements", List.of(Map.of("statistic", "COUNT", "value", 12.0), Map.of("statistic", "TOTAL_TIME", "value", 0.53),
                        Map.of("statistic", "MAX", "value", 0.012), Map.of("statistic", "BROKEN")),
                "availableTags", List.of(Map.of("tag", "method", "values", List.of("GET")), Map.of("tag", "uri", "values", List.of("/api/{id}", "/x"))));
        Map<String, Double> m = Metrics.measurements(answer);
        check(m.size() == 3 && m.get("COUNT") == 12.0 && m.get("TOTAL_TIME") == 0.53 && m.get("MAX") == 0.012, "measurements " + m);
        check(Metrics.tagValues(answer, "uri").equals(List.of("/api/{id}", "/x")), "uri " + Metrics.tagValues(answer, "uri"));
        check(Metrics.tagValues(answer, "outcome").isEmpty(), "없는 태그");
        check(Metrics.measurements(Map.of()).isEmpty() && Metrics.names(Map.of("names", "x")).isEmpty(), "모양이 다름");
        check(Metrics.names(Map.of("names", List.of("jvm.threads.live", "hikaricp.connections.active"))).contains("hikaricp.connections.active"), "names");
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

    /** 읽기 실패(NaN) 기록 뒤에 누적 값이 줄어도 다시 켜진 것으로 본다. */
    private static void restartAfterUnknown() {
        Metrics.History h = new Metrics.History();
        h.add(sample(0, 100, 10, 0));
        h.add(sample(5_000, 200, 20, 0));
        h.add(sample(10_000, Double.NaN, Double.NaN, Double.NaN));
        h.add(sample(15_000, 3, 1, 0));
        check(h.all().size() == 1, "NaN 뒤 다시 켜지면 비움 " + h.all().size());
        h.add(sample(20_000, 9, 2, 0));
        check(h.perMinute(Metrics.Sample::requests) >= 0, "음수 분당 " + h.perMinute(Metrics.Sample::requests));
    }

    /** attach 실패(-1) 기록은 분당 값의 기준으로 쓰지 않는다. */
    private static void unknownBaseline() {
        Metrics.History h = new Metrics.History();
        h.add(sample(0, 0, 0, 0, -1));
        h.add(sample(30_000, 0, 0, 0, 4));
        h.add(sample(60_000, 0, 0, 0, 10));
        check(near(h.perMinute(Metrics.Sample::gcCount), 12), "GC 분당 " + h.perMinute(Metrics.Sample::gcCount));
        h.add(sample(65_000, 0, 0, 0, -1));
        check(Double.isNaN(h.perMinute(Metrics.Sample::gcCount)), "지금 값 모름");
        Metrics.History unknown = new Metrics.History();
        unknown.add(sample(0, 0, 0, 0, -1));
        unknown.add(sample(5_000, 0, 0, 0, 3));
        check(Double.isNaN(unknown.perMinute(Metrics.Sample::gcCount)), "기준 없음");
    }

    private static Metrics.Sample sample(long time, double requests, double totalSeconds, double errors) {
        return sample(time, requests, totalSeconds, errors, 5);
    }

    private static Metrics.Sample sample(long time, double requests, double totalSeconds, double errors, long gcCount) {
        return new Metrics.Sample(time, 0.1, 8, 1, 2, 3, 4, gcCount, requests, totalSeconds, 0.2, errors, 0, 1, 10, 0);
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
