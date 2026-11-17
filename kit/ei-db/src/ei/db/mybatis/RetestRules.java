package ei.db.mybatis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 「바꾼 것 다시 시험」의 판정 규칙(IntelliJ 클래스 없음).
 * 문장 · 메서드 글은 공백 차이와 XML 주석을 무시하고 견준다(줄 맞춤만 바꾼 것은 바뀐 것으로 보지 않는다).
 */
final class RetestRules {
  private RetestRules() {}

  private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final String ID = "id";
  private static final String INCLUDE = "include";
  private static final String REFID = "refid";

  /** API 다시 보내기 결과의 갈래. */
  enum ApiVerdict { FAILED, NO_BASE, STATUS, BODY, SAME }

  /** text 의 ranges 글을 이어 XML 주석을 빼고 공백 덩이를 한 칸으로 줄인 것. 범위가 글 밖이면 그 범위는 건너뛴다. */
  static String statementText(CharSequence text, List<int[]> ranges) {
    StringBuilder out = new StringBuilder();
    for (int[] r : ranges) {
      if (r[0] < 0 || r[0] > r[1] || r[1] > text.length()) continue;
      out.append(text, r[0], r[1]).append('\n');
    }
    return code(XML_COMMENT.matcher(out).replaceAll(" "));
  }

  /** 공백 덩이를 한 칸으로 줄이고 앞뒤 공백을 뗀 글. */
  static String code(CharSequence text) {
    return SPACES.matcher(text).replaceAll(" ").strip();
  }

  /** 메서드 짝짓기 키: 「클래스#이름(파라미터 형식,…)」. 클래스 이름이 없으면(익명 클래스) 빈 글. */
  static String methodKey(String owner, String name, List<String> paramTypes) {
    return (owner == null ? "" : owner) + "#" + name + "(" + String.join(",", paramTypes) + ")";
  }

  /**
   * 한 메서드의 URL 마다 그 URL 로 저장한 요청 key(key 는 「METHOD 패키지.클래스#메서드 URL」). key 하나라도 끝이 「 URL」 로 맞으면
   * 맞는 것만 주고(나머지 URL 은 빈 목록 = 저장한 요청 없음), 하나도 맞지 않으면 URL 적는 모양이 달라 가릴 수 없으므로 모든 URL 에 모든 key 를 준다.
   */
  static Map<String, List<String>> keysByUrl(List<String> urls, List<String> keys) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    boolean exact = false;
    for (String url : urls) {
      List<String> mine = new ArrayList<>();
      for (String key : keys) {
        if (key.endsWith(" " + url)) mine.add(key);
      }
      exact |= !mine.isEmpty();
      out.put(url, mine);
    }
    if (!exact) {
      for (String url : urls) out.put(url, keys);
    }
    return out;
  }

  /** beforeCode 는 저장한 응답이 없으면 음수. */
  static ApiVerdict apiVerdict(String error, int beforeCode, int nowCode, boolean same) {
    if (error != null) return ApiVerdict.FAILED;
    if (beforeCode < 0) return ApiVerdict.NO_BASE;
    if (beforeCode != nowCode) return ApiVerdict.STATUS;
    return same ? ApiVerdict.SAME : ApiVerdict.BODY;
  }

  /**
   * changed(바뀐 조각 「네임스페이스.id」)에 docs 의 조각 가운데 바뀐 조각을 (같은 파일 · 다른 파일 조각을 거쳐) include 하는 것을
   * 더 늘지 않을 때까지 더한 것. 네임스페이스가 없는 문서는 docs 에 넣지 않는다.
   */
  static Set<String> reach(Collection<MapperDoc> docs, Set<String> changed) {
    Set<String> reach = new LinkedHashSet<>(changed);
    boolean grew = true;
    while (grew) {
      grew = false;
      for (MapperDoc d : docs) {
        for (Map.Entry<String, MapperDoc.Node> f : d.fragments.entrySet()) {
          String name = d.namespace + "." + f.getKey();
          if (!reach.contains(name) && includedFragment(d, f.getValue(), reach) != null) {
            reach.add(name);
            grew = true;
          }
        }
      }
    }
    return reach;
  }

  /** n 이 (같은 파일 조각을 따라가며) include 하는 조각 가운데 reach 에 있는 첫 이름(「네임스페이스.id」). 없으면 null. */
  static String includedFragment(MapperDoc doc, MapperDoc.Node n, Set<String> reach) {
    return includedFragment(doc, n, reach, new HashSet<>());
  }

  private static String includedFragment(MapperDoc doc, MapperDoc.Node n, Set<String> reach, Set<MapperDoc.Node> seen) {
    for (MapperDoc.Node c : n.children) {
      if (c.name == null) continue;
      if (!INCLUDE.equals(c.name)) {
        String found = includedFragment(doc, c, reach, seen);
        if (found != null) return found;
        continue;
      }
      String refid = c.attr(REFID);
      if (refid == null) continue;
      MapperDoc.Node own = doc.fragment(refid);
      if (own == null) {
        if (reach.contains(refid)) return refid;
        continue;
      }
      String name = doc.namespace + "." + own.attr(ID);
      if (reach.contains(name)) return name;
      if (seen.add(own)) {
        String found = includedFragment(doc, own, reach, seen);
        if (found != null) return found;
      }
    }
    return null;
  }
}
