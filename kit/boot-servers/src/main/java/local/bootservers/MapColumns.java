package local.bootservers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 연결 지도의 서버 열 번호. IntelliJ 클래스 없이 점검(ConnectionMapCheck)한다. */
final class MapColumns {

    private MapColumns() {
    }

    /**
     * 서버 이름 → 열 번호(0부터). 선 from→to 를 따라 가장 긴 길의 길이가 열 번호다.
     * 순환을 이루는 선(깊이 우선으로 돌다 지금 길 위의 서버로 돌아가는 선)은 세지 않는다. 들어오는 선이 없는 서버부터 돌고, 남은 서버는 목록 순서로 돈다.
     * 열 번호는 0 부터 빈 열 없이 이어진다.
     *
     * @param servers 서버 이름(순서대로)
     * @param edges   {from, to} 서버 이름 쌍. servers 에 없는 이름 · 제 자신으로 가는 선은 무시한다
     */
    static Map<String, Integer> depths(List<String> servers, List<String[]> edges) {
        Map<String, List<String>> next = new LinkedHashMap<>();
        for (String s : servers) {
            next.put(s, new ArrayList<>());
        }
        Set<String> hasIn = new HashSet<>();
        for (String[] e : edges) {
            if (next.containsKey(e[0]) && next.containsKey(e[1]) && !e[0].equals(e[1])) {
                next.get(e[0]).add(e[1]);
                hasIn.add(e[1]);
            }
        }
        List<String> order = new ArrayList<>();
        for (String s : next.keySet()) {
            if (!hasIn.contains(s)) {
                order.add(s);
            }
        }
        for (String s : next.keySet()) {
            if (hasIn.contains(s)) {
                order.add(s);
            }
        }
        Set<String> seen = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        List<String[]> kept = new ArrayList<>();
        for (String s : order) {
            visit(s, next, seen, onPath, kept);
        }
        Map<String, Integer> depth = new HashMap<>();
        for (String s : next.keySet()) {
            depth.put(s, 0);
        }
        // 남은 선은 순환이 없으므로 서버 수만큼 돌면 가장 긴 길에 닿는다.
        for (int round = 0; round < next.size(); round++) {
            boolean changed = false;
            for (String[] e : kept) {
                int d = depth.get(e[0]) + 1;
                if (d > depth.get(e[1])) {
                    depth.put(e[1], d);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return depth;
    }

    private static void visit(String n, Map<String, List<String>> next, Set<String> seen, Set<String> onPath, List<String[]> kept) {
        if (!seen.add(n)) {
            return;
        }
        onPath.add(n);
        for (String m : next.get(n)) {
            if (!onPath.contains(m)) {
                kept.add(new String[]{n, m});
                visit(m, next, seen, onPath, kept);
            }
        }
        onPath.remove(n);
    }
}
