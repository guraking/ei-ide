package local.bootservers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** MapColumns · ColumnWidths 자체 점검. 실패하면 예외로 끝난다. */
public final class ConnectionMapCheck {

    public static void main(String[] args) {
        chain();
        cycle();
        selfAndUnknown();
        widths();
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

    private static void widths() {
        eq("[120, 80, 300]", Arrays.toString(ColumnWidths.parse("120,80, 300", 3)), "칸 폭 읽기");
        eq(null, ColumnWidths.parse(null, 3), "기억 없음");
        eq(null, ColumnWidths.parse("120,80", 3), "칸 수 다름");
        eq(null, ColumnWidths.parse("120,x,300", 3), "숫자 아님");
        eq(null, ColumnWidths.parse("120,0,300", 3), "0 폭");
        eq("[100, 300]", Arrays.toString(ColumnWidths.scale(new int[]{500, 1500}, 400)), "넓은 화면에서 끈 폭도 처음 합으로 맞춤");
        eq("[1, 400]", Arrays.toString(ColumnWidths.scale(new int[]{1, 100_000}, 400)), "작은 칸도 1 이상");
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
