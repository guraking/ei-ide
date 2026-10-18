package ei.db.mybatis;

import java.util.List;

/**
 * 매퍼 XML 안 참조 속성(include refid, resultMap, extends)을 글자에서 바로 읽고 그 선언 위치를 찾는다.
 * 점이 든 id 는 "네임스페이스.id" 로 본다(MyBatis 규칙: 마지막 점 앞이 네임스페이스).
 */
public final class MapperRefs {
  private MapperRefs() {}

  public static final String SQL_TAG = "sql";
  public static final String RESULT_MAP_TAG = "resultMap";
  private static final String INCLUDE_TAG = "include";
  private static final String REFID_ATTR = "refid";
  private static final String RESULT_MAP_ATTR = "resultMap";
  private static final String EXTENDS_ATTR = "extends";
  private static final String ID_ATTR = "id";
  private static final List<String> RESULT_MAP_USERS = List.of("select", "association", "collection", "case");
  private static final char LIST_SEPARATOR = ',';
  private static final char NAMESPACE_SEPARATOR = '.';

  /**
   * 커서 밑 참조. tag 는 선언 태그 이름(sql · resultMap), namespace 는 id 에 점이 없으면 null, id 는 마지막 마디.
   */
  public record Ref(String tag, String namespace, String id) {}

  /** offset 이 참조 속성 값 안이면 그 참조, 아니면 null. resultMap 목록("a, b")은 커서 밑 항목만. */
  public static Ref refAt(CharSequence text, int offset) {
    String s = text.toString();
    if (offset < 0 || offset > s.length()) return null;
    int lt = s.lastIndexOf('<', offset - 1);
    if (lt < 0 || lt + 1 >= s.length() || !Character.isLetter(s.charAt(lt + 1))) return null;
    int i = lt + 1;
    int nameEnd = nameEnd(s, i);
    String tag = s.substring(i, nameEnd);
    i = nameEnd;
    while (i < s.length()) {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
      if (i >= s.length() || i > offset) return null;
      char c = s.charAt(i);
      if (c == '>' || c == '<' || c == '/') return null;
      int keyEnd = nameEnd(s, i);
      if (keyEnd == i) return null;
      String key = s.substring(i, keyEnd);
      i = keyEnd;
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
      if (i >= s.length() || s.charAt(i) != '=') continue;
      i++;
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
      if (i >= s.length() || (s.charAt(i) != '"' && s.charAt(i) != '\'')) return null;
      int valueStart = i + 1;
      int close = s.indexOf(s.charAt(i), valueStart);
      int valueEnd = close < 0 ? s.length() : close;
      if (offset >= valueStart && offset <= valueEnd) return ref(tag, key, s.substring(valueStart, valueEnd), offset - valueStart);
      if (close < 0) return null;
      i = close + 1;
    }
    return null;
  }

  private static Ref ref(String tag, String key, String value, int caret) {
    String declTag;
    if (INCLUDE_TAG.equals(tag) && REFID_ATTR.equals(key)) declTag = SQL_TAG;
    else if (RESULT_MAP_USERS.contains(tag) && RESULT_MAP_ATTR.equals(key)) declTag = RESULT_MAP_TAG;
    else if (RESULT_MAP_TAG.equals(tag) && EXTENDS_ATTR.equals(key)) declTag = RESULT_MAP_TAG;
    else return null;
    int from = value.lastIndexOf(LIST_SEPARATOR, caret - 1) + 1;
    int to = value.indexOf(LIST_SEPARATOR, caret);
    String full = MapperDoc.decodeEntities(value.substring(from, to < 0 ? value.length() : to)).trim();
    if (full.isEmpty()) return null;
    int dot = full.lastIndexOf(NAMESPACE_SEPARATOR);
    return dot < 0 ? new Ref(declTag, null, full) : new Ref(declTag, full.substring(0, dot), full.substring(dot + 1));
  }

  /** 매퍼 바로 밑 tag 요소 중 id 가 같은 것의 여는 태그 위치. 없으면 -1. */
  public static int declOffset(MapperDoc doc, String tag, String id) {
    for (MapperDoc.Node m : doc.root.children) {
      if (!"mapper".equals(m.name)) continue;
      for (MapperDoc.Node n : m.children) {
        if (tag.equals(n.name) && id.equals(n.attr(ID_ATTR))) return n.start;
      }
    }
    return -1;
  }

  private static int nameEnd(String s, int i) {
    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || "_-:.".indexOf(s.charAt(i)) >= 0)) i++;
    return i;
  }
}
