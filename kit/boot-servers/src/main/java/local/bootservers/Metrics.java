package local.bootservers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버 대시보드의 계산: 액추에이터 /actuator/metrics 응답 읽기, 5초마다 잰 값의 10분 기록, 분당 · 평균 값, 노출 설정 합치기.
 * 값을 모르면 NaN(소수) · -1(개수). 인텔리제이 클래스를 쓰지 않는다(MetricsCheck 로 점검).
 */
final class Metrics {

    /** 한 번 잰 값. 요청 값은 서버를 켠 뒤 누적(액추에이터 요청은 뺀 값), CPU 는 0~1. */
    record Sample(long time, double cpu, int cores, long heapUsed, long heapMax, int threads, int peakThreads, long gcCount,
                  double requests, double totalSeconds, double maxSeconds, double serverErrors, double clientErrors,
                  double poolActive, double poolMax, double poolPending) {
    }

    /** 느린 요청 한 줄. 시간은 밀리초. */
    record Slow(String method, String uri, long count, double avgMillis, double maxMillis, long errors) {
    }

    // 기록을 남기는 길이(그래프 가로 폭)와 분당 값을 재는 구간
    static final long HISTORY_MILLIS = 10 * 60 * 1000L;
    static final long WINDOW_MILLIS = 60 * 1000L;
    // 대시보드 · 모아 보기에서 주황 · 빨강으로 보이는 기준
    static final double SLOW_AVG_MILLIS = 300;
    static final double POOL_BUSY_RATIO = 0.8;
    static final double ERROR_RATE = 0.005;
    static final String EXPOSURE_KEY = "management.endpoints.web.exposure.include";
    static final List<String> NEEDED_ENDPOINTS = List.of("health", "metrics");
    private static final String EXPOSE_ALL = "*";
    private static final double MILLIS_PER_SECOND = 1000;
    private static final double MILLIS_PER_MINUTE = 60_000;

    private static final Pattern MEASUREMENT = Pattern.compile(
            "\\{\\s*\"statistic\"\\s*:\\s*\"(\\w+)\"\\s*,\\s*\"value\"\\s*:\\s*(-?[0-9.]+(?:[eE][-+]?\\d+)?|NaN)\\s*}");
    private static final String TAGS_START = "\"tag\"\\s*:\\s*\"";
    private static final String TAGS_VALUES = "\"\\s*,\\s*\"values\"\\s*:\\s*\\[";
    private static final Pattern NAMES = Pattern.compile("\"names\"\\s*:\\s*\\[");

    private Metrics() {
    }

    /** /actuator/metrics/{이름} 응답의 measurements: statistic(COUNT · TOTAL_TIME · MAX · VALUE …) → 값. */
    static Map<String, Double> measurements(String json) {
        Map<String, Double> result = new LinkedHashMap<>();
        Matcher m = MEASUREMENT.matcher(json);
        while (m.find()) {
            result.put(m.group(1), m.group(2).equals("NaN") ? Double.NaN : Double.parseDouble(m.group(2)));
        }
        return result;
    }

    /** availableTags 에서 한 태그(uri · outcome · method …)의 값들. 없으면 빈 목록. */
    static List<String> tagValues(String json, String tag) {
        Matcher m = Pattern.compile(TAGS_START + Pattern.quote(tag) + TAGS_VALUES).matcher(json);
        return m.find() ? strings(json, m.end()) : List.of();
    }

    /** /actuator/metrics 응답의 names(지표 이름들). */
    static Set<String> names(String json) {
        Matcher m = NAMES.matcher(json);
        return m.find() ? new LinkedHashSet<>(strings(json, m.end())) : Set.of();
    }

    /** from 부터 「]」 까지의 JSON 글 값들(따옴표 · 역사선 · 유니코드 이스케이프를 푼다). */
    private static List<String> strings(String json, int from) {
        List<String> result = new ArrayList<>();
        StringBuilder current = null;
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (current == null) {
                if (c == ']') {
                    break;
                }
                if (c == '"') {
                    current = new StringBuilder();
                }
                continue;
            }
            if (c == '"') {
                result.add(current.toString());
                current = null;
            }
            else if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                if (next == 'u' && i + 4 < json.length()) {
                    current.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                else {
                    current.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
                }
            }
            else {
                current.append(c);
            }
        }
        return result;
    }

    /**
     * 설정에 이미 있는 노출 목록(쉼표, 없으면 null)에 health · metrics 를 더한 값. 이미 다 있거나 「*」 이면 null(바꿀 것 없음).
     */
    static String exposure(String existing) {
        Set<String> items = new LinkedHashSet<>();
        if (existing != null) {
            for (String part : existing.split(",")) {
                if (part.strip().equals(EXPOSE_ALL)) {
                    return null;
                }
                if (!part.isBlank()) {
                    items.add(part.strip());
                }
            }
        }
        if (items.containsAll(NEEDED_ENDPOINTS)) {
            return null;
        }
        items.addAll(NEEDED_ENDPOINTS);
        return String.join(",", items);
    }

    /** 평균 응답이 느린 순으로 n 개. */
    static List<Slow> slowest(List<Slow> all, int n) {
        List<Slow> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(Slow::avgMillis).reversed());
        return sorted.subList(0, Math.min(n, sorted.size()));
    }

    /** 한 서버의 최근 10분 기록. 화면 스레드에서만 쓴다. */
    static final class History {
        private final Deque<Sample> samples = new ArrayDeque<>();

        void add(Sample s) {
            Sample last = samples.peekLast();
            // 누적 값이 줄면 서버가 다시 켜진 것이다. 이전 기록과 이으면 분당 값이 음수가 되므로 버린다.
            if (last != null && s.requests() >= 0 && last.requests() > s.requests()) {
                samples.clear();
            }
            samples.addLast(s);
            while (samples.size() > 1 && s.time() - samples.peekFirst().time() > HISTORY_MILLIS) {
                samples.removeFirst();
            }
        }

        Sample latest() {
            return samples.peekLast();
        }

        List<Sample> all() {
            return new ArrayList<>(samples);
        }

        /** 마지막 값에서 1분(없으면 가장 오래된 것) 전 기록. 기록이 하나뿐이면 null. */
        private Sample minuteAgo() {
            Sample last = samples.peekLast();
            if (last == null || samples.size() < 2) {
                return null;
            }
            Sample result = samples.peekFirst();
            for (Sample s : samples) {
                if (last.time() - s.time() >= WINDOW_MILLIS) {
                    result = s;
                }
            }
            return result;
        }

        /** 최근 1분의 분당 증가량. 모르면 NaN. */
        double perMinute(ToDoubleFunction<Sample> counter) {
            Sample then = minuteAgo();
            Sample now = latest();
            if (then == null || now.time() == then.time()) {
                return Double.NaN;
            }
            double delta = counter.applyAsDouble(now) - counter.applyAsDouble(then);
            return delta * MILLIS_PER_MINUTE / (now.time() - then.time());
        }

        /** 최근 1분 요청의 평균 응답(ms). 요청이 없었으면 NaN. */
        double averageMillis() {
            return averageMillis(minuteAgo(), latest());
        }

        /** 최근 1분 요청 중 5xx 비율(0~1). 요청이 없었으면 NaN. */
        double errorRate() {
            Sample then = minuteAgo();
            Sample now = latest();
            if (then == null) {
                return Double.NaN;
            }
            double requests = now.requests() - then.requests();
            return requests > 0 ? (now.serverErrors() - then.serverErrors()) / requests : Double.NaN;
        }

        /** 그래프 값: 기록마다 value. */
        double[] series(ToDoubleFunction<Sample> value) {
            double[] result = new double[samples.size()];
            int i = 0;
            for (Sample s : samples) {
                result[i++] = value.applyAsDouble(s);
            }
            return result;
        }

        /** 그래프 값: 앞 기록과 사이 구간의 평균 응답(ms). 첫 값과 요청이 없던 구간은 NaN. */
        double[] averageSeries() {
            double[] result = new double[samples.size()];
            Sample previous = null;
            int i = 0;
            for (Sample s : samples) {
                result[i++] = averageMillis(previous, s);
                previous = s;
            }
            return result;
        }

        private static double averageMillis(Sample then, Sample now) {
            if (then == null || now == null) {
                return Double.NaN;
            }
            double requests = now.requests() - then.requests();
            // shortcut: 액추에이터 자신의 시간을 평균으로 빼므로 누적 시간에 몇 ms 흔들림이 남는다. 요청이 아주 적은 구간에서만 보이고 음수는 0 으로 막는다.
            return requests > 0 ? Math.max(0, (now.totalSeconds() - then.totalSeconds()) / requests * MILLIS_PER_SECOND) : Double.NaN;
        }
    }
}
