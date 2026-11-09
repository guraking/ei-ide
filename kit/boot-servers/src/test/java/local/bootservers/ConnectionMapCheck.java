package local.bootservers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** MapColumns 자체 점검. 실패하면 예외로 끝난다. */
public final class ConnectionMapCheck {

    public static void main(String[] args) {
        chain();
        cycle();
        selfAndUnknown();
        System.out.println("ConnectionMapCheck 통과");
    }

    private static void chain() {
        Map<String, Integer> d = MapColumns.depths(List.of("A", "B", "C"),
                List.of(new String[]{"A", "B"}, new String[]{"B", "C"}, new String[]{"A", "C"}));
        eq(0, d.get("A"), "사슬 A");
        eq(1, d.get("B"), "사슬 B");
        eq(2, d.get("C"), "사슬 C(가장 긴 길)");
    }

    /** A ↔ B 가 서로 부르고 C 가 A 를 부른다. */
    private static void cycle() {
        Map<String, Integer> d = MapColumns.depths(List.of("A", "B", "C"),
                List.of(new String[]{"A", "B"}, new String[]{"B", "A"}, new String[]{"C", "A"}));
        eq(0, d.get("C"), "순환 C");
        eq(1, d.get("A"), "순환 A");
        eq(2, d.get("B"), "순환 B");
        consecutive(d, "순환");
        Map<String, Integer> ring = MapColumns.depths(List.of("A", "B"),
                List.of(new String[]{"A", "B"}, new String[]{"B", "A"}));
        eq(0, ring.get("A"), "고리만 A");
        eq(1, ring.get("B"), "고리만 B");
    }

    private static void selfAndUnknown() {
        Map<String, Integer> d = MapColumns.depths(List.of("A"),
                List.of(new String[]{"A", "A"}, new String[]{"A", "X"}));
        eq(0, d.get("A"), "제 자신 · 모르는 이름");
        eq(1, d.size(), "모르는 이름은 넣지 않음");
    }

    private static void consecutive(Map<String, Integer> d, String what) {
        TreeSet<Integer> used = new TreeSet<>(d.values());
        eq(used.size() - 1, used.last(), what + " 빈 열 없음");
    }

    private static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
