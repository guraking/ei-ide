package ei.db.mybatis;

import ei.db.mybatis.MapperDoc.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 매퍼 문장의 #{이름} 이 parameterType 클래스에 있는지 가리는 순수 논리(IntelliJ 클래스 없음).
 * 위치는 모두 문서 글자 안의 오프셋이다.
 */
final class MapperParams {
  private MapperParams() {}

  /** MyBatis 기본 별칭 중 필드 비교가 뜻 없는 것(원시형 · 문자열 · 날짜 · 숫자 · Map · 컬렉션). */
  private static final Set<String> BUILTIN = Set.of("string", "byte", "long", "short", "int", "integer", "double",
      "float", "boolean", "char", "character", "date", "decimal", "bigdecimal", "biginteger", "object", "map",
      "hashmap", "list", "arraylist", "collection", "iterator", "resultset");

  /** MyBatis 가 스스로 넣는 매개변수 이름. */
  private static final Set<String> RESERVED = Set.of("_parameter", "_databaseId");

  /** #{이름} 의 첫 마디와 그 글자 범위(end 는 끝 다음). */
  record Ref(String name, int start, int end) {}

  /** 경고 하나. */
  record Miss(int start, int end, String message) {}

  /** 기본 별칭 · "_" 별칭 · java.* 형식이면 true(클래스 필드와 비교하지 않는다). */
  static boolean isBuiltinType(String type) {
    String t = type.trim();
    return t.startsWith("_") || t.startsWith("java.") || BUILTIN.contains(t.toLowerCase(Locale.ROOT));
  }

  /** 검사할 parameterType. 없거나 기본형 · Map 따위면 null. */
  static String checkedType(Node statement) {
    String t = statement.attr("parameterType");
    if (t == null || t.isBlank() || isBuiltinType(t)) return null;
    return t.trim();
  }

  /** 문장 안 #{} 들. foreach 의 item · index 이름, bind 이름, MyBatis 예약 이름으로 시작하는 것은 뺀다. */
  static List<Ref> refs(CharSequence text, Node statement) {
    Set<String> skip = new HashSet<>(RESERVED);
    collectBinds(statement, skip);
    List<Ref> out = new ArrayList<>();
    walk(text, statement, skip, out);
    return out;
  }

  /**
   * 문서 전체를 검사한다. propsOfType 은 형식 이름 → 필드 · 속성 이름들(찾지 못하면 null, 그 문장은 건너뛴다).
   */
  static List<Miss> check(CharSequence text, Function<String, Collection<String>> propsOfType) {
    return check(text, MapperDoc.parse(text), propsOfType);
  }

  /** 이미 읽은 doc 으로 검사한다. doc 은 text 를 읽은 것이어야 한다. */
  static List<Miss> check(CharSequence text, MapperDoc doc, Function<String, Collection<String>> propsOfType) {
    List<Miss> out = new ArrayList<>();
    for (Node s : doc.statements) {
      String type = checkedType(s);
      if (type == null) continue;
      Collection<String> props = propsOfType.apply(type);
      if (props == null) continue;
      String simple = type.substring(type.lastIndexOf('.') + 1);
      for (Ref r : refs(text, s)) {
        if (!props.contains(r.name())) out.add(new Miss(r.start(), r.end(), simple + " 에 " + r.name() + " 필드가 없습니다"));
      }
    }
    return out;
  }

  private static void collectBinds(Node n, Set<String> skip) {
    for (Node c : n.children) {
      if (c.name == null) continue;
      if ("bind".equals(c.name) && c.attr("name") != null) skip.add(c.attr("name").trim());
      collectBinds(c, skip);
    }
  }

  private static void walk(CharSequence text, Node n, Set<String> skip, List<Ref> out) {
    for (Node c : n.children) {
      if (c.name == null) {
        scan(text, c.start, c.end, skip, out);
      } else if ("foreach".equals(c.name)) {
        Set<String> inner = new HashSet<>(skip);
        addAttr(c, "item", inner);
        addAttr(c, "index", inner);
        walk(text, c, inner, out);
      } else {
        walk(text, c, skip, out);
      }
    }
  }

  private static void addAttr(Node n, String key, Set<String> into) {
    String v = n.attr(key);
    if (v != null && !v.isBlank()) into.add(v.trim());
  }

  /** [from, to) 의 원래 글자에서 #{...} 를 찾는다. 닫히지 않은 #{ 는 무시한다. */
  private static void scan(CharSequence text, int from, int to, Set<String> skip, List<Ref> out) {
    String s = text.subSequence(from, Math.min(to, text.length())).toString();
    int i = s.indexOf("#{");
    while (i >= 0) {
      int close = s.indexOf('}', i + 2);
      if (close < 0) return;
      int a = i + 2;
      while (a < close && Character.isWhitespace(s.charAt(a))) a++;
      int b = a;
      while (b < close && Character.isJavaIdentifierPart(s.charAt(b))) b++;
      String name = s.substring(a, b);
      if (!name.isEmpty() && !skip.contains(name)) out.add(new Ref(name, from + a, from + b));
      i = s.indexOf("#{", close + 1);
    }
  }
}
