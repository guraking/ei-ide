package ei.db.gen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ERD 첫 배치의 줄 나누기. 부모(참조되는 표)가 왼쪽, 자식이 오른쪽: 깊이 = 부모 깊이 최댓값 + 1.
 * 순환 참조는 도는 지점을 0 으로 끊는다. 한 줄이 maxPerColumn 을 넘으면 같은 깊이를 여러 줄로 나눈다.
 */
public final class ErdLayout {
  private ErdLayout() {}

  /** edges 는 {자식 표, 부모 표}. tables 밖의 표와 자기 참조는 무시한다. 반환: 왼쪽부터 줄마다 표 이름. */
  public static List<List<String>> columns(List<String> tables, List<String[]> edges, int maxPerColumn) {
    Map<String, Set<String>> parents = new HashMap<>();
    Set<String> known = new HashSet<>(tables);
    for (String t : tables) parents.put(t, new HashSet<>());
    for (String[] e : edges) {
      if (known.contains(e[0]) && known.contains(e[1]) && !e[0].equals(e[1])) parents.get(e[0]).add(e[1]);
    }
    Map<String, Integer> depth = new HashMap<>();
    for (String t : tables) depth(t, parents, depth, new HashSet<>());
    Map<Integer, List<String>> byDepth = new LinkedHashMap<>();
    int max = 0;
    for (String t : tables) {
      int d = depth.get(t);
      max = Math.max(max, d);
      byDepth.computeIfAbsent(d, k -> new ArrayList<>()).add(t);
    }
    List<List<String>> out = new ArrayList<>();
    for (int d = 0; d <= max; d++) {
      List<String> col = byDepth.get(d);
      if (col == null) continue;
      for (int i = 0; i < col.size(); i += maxPerColumn) out.add(new ArrayList<>(col.subList(i, Math.min(col.size(), i + maxPerColumn))));
    }
    return out;
  }

  private static int depth(String t, Map<String, Set<String>> parents, Map<String, Integer> memo, Set<String> path) {
    Integer known = memo.get(t);
    if (known != null) return known;
    if (!path.add(t)) return 0;
    int d = 0;
    for (String p : parents.get(t)) d = Math.max(d, depth(p, parents, memo, path) + 1);
    path.remove(t);
    memo.put(t, d);
    return d;
  }
}
