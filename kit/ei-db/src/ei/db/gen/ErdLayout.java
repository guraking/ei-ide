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

  /** category 가 접두어로 보는 첫 토막의 최대 길이(TB_ · T_ · TBL_ 등). */
  private static final int PREFIX_MAX = 3;

  /**
   * 표 머리 색 띠 번호(0 ~ count-1). 이름을 _ 로 나눠 짧은 접두어(PREFIX_MAX 글자 이하) 첫 토막은 빼고 다음 토막으로 고른다.
   * 그래서 TB_ORDER · TB_ORDER_ITEM 은 같은 번호(ORDER)다. 대소문자는 가리지 않는다.
   */
  public static int category(String table, int count) {
    String[] parts = table.toUpperCase(java.util.Locale.ROOT).split("_");
    if (parts.length == 0) return 0;
    String key = parts.length > 1 && parts[0].length() <= PREFIX_MAX && !parts[1].isEmpty() ? parts[1] : parts[0];
    return Math.floorMod(key.hashCode(), count);
  }
}
