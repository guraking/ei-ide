package ei.db.mybatis;

import ei.db.gen.Names;
import ei.db.mybatis.MapperDoc.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 매퍼 XML 글에서 칼럼 이름을 바꿀 위치를 찾는다(IntelliJ 클래스 없음). 위치는 모두 문서 글자 안의 오프셋이다.
 * 찾는 곳: 문장(select · insert · update · delete) · &lt;sql&gt; 조각의 SQL 글(CDATA 포함), resultMap 안 요소의 column 속성.
 * 낱말 단위(앞뒤가 식별자 글자가 아님)로 대소문자를 가리지 않고 찾으며 '...' 문자열 · XML 주석 · #{} · ${} · 엔티티 안은 뺀다.
 * DTO 속성도 바꾸면 #{camel(옛)} 의 첫 마디와, 칼럼을 바꾼 resultMap 요소의 property="camel(옛)" 도 바꾼다.
 */
final class ColumnRename {
  private ColumnRename() {}

  /** 칼럼 · 표 이름 꼴. */
  static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

  private static final String RESULT_MAP_ATTR = "resultMap";
  private static final String COLUMN_ATTR = "column";
  private static final String PROPERTY_ATTR = "property";
  private static final String SQL_TAG = "sql";
  private static final String INCLUDE_TAG = "include";
  private static final String CDATA_OPEN = "<![CDATA[";
  private static final String CDATA_CLOSE = "]]>";
  /** 요소의 DTO 형식을 주는 속성(가까운 것부터 본다). */
  private static final List<String> TYPE_ATTRS = List.of("ofType", "javaType", "type");
  /** &lt;include&gt; 를 따라가는 깊이 한도(서로 부르는 조각에서 멈추려고). */
  private static final int MAX_INCLUDE_DEPTH = 10;
  /** 엔티티(&amp;lt; 따위)로 보는 길이 한도. */
  private static final int MAX_ENTITY_LENGTH = 10;

  enum Kind { COLUMN, PROPERTY, PARAM }

  /** 바꿀 곳 하나. [start, end) 를 replacement 로. dtoType 은 PROPERTY · PARAM 의 DTO 형식 글(XML 그대로, 없으면 null). */
  record Hit(int start, int end, String replacement, Kind kind, String dtoType) {}

  /** 입력이 맞으면 null, 아니면 화면에 보일 까닭. table 은 비워도 된다. */
  static String validate(String oldName, String newName, String table) {
    String o = oldName == null ? "" : oldName.trim();
    String n = newName == null ? "" : newName.trim();
    if (!NAME.matcher(o).matches()) return "옛 칼럼 이름은 영문자 · 밑줄로 시작하고 영문자 · 숫자 · _ $ # 만 쓸 수 있습니다.";
    if (!NAME.matcher(n).matches()) return "새 칼럼 이름은 영문자 · 밑줄로 시작하고 영문자 · 숫자 · _ $ # 만 쓸 수 있습니다.";
    if (o.equalsIgnoreCase(n)) return "옛 이름과 새 이름이 같습니다.";
    String t = table == null ? "" : table.trim();
    if (!t.isEmpty() && !NAME.matcher(t).matches()) return "표 이름은 영문자 · 밑줄로 시작하고 영문자 · 숫자 · _ $ # 만 쓸 수 있습니다.";
    return null;
  }

  /** 원래가 모두 대문자면 새 이름도 대문자, 모두 소문자면 소문자, 섞였으면 적은 그대로. */
  static String caseLike(String original, String newName) {
    String upper = original.toUpperCase(Locale.ROOT);
    String lower = original.toLowerCase(Locale.ROOT);
    if (upper.equals(lower)) return newName;
    if (original.equals(upper)) return newName.toUpperCase(Locale.ROOT);
    if (original.equals(lower)) return newName.toLowerCase(Locale.ROOT);
    return newName;
  }

  /** offset 에 걸친 식별자 낱말. 없으면 빈 글. */
  static String wordAt(CharSequence text, int offset) {
    int a = Math.max(0, Math.min(offset, text.length()));
    int b = a;
    while (a > 0 && isIdent(text.charAt(a - 1))) a--;
    while (b < text.length() && isIdent(text.charAt(b))) b++;
    return text.subSequence(a, b).toString();
  }

  /**
   * 바꿀 곳들(위치 순, 겹치지 않음). table 이 비지 않으면 그 표 이름이 SQL 글(포함한 조각 포함)에 나오는 문장,
   * 그 문장이 포함하는 조각, 그 문장이 resultMap 속성으로 쓰는 resultMap 만 본다.
   */
  static List<Hit> find(CharSequence text, String oldName, String newName, String table, boolean renameProperty) {
    String o = oldName.trim();
    String n = newName.trim();
    String t = table == null || table.isBlank() ? null : table.trim();
    String oldProp = renameProperty ? Names.camel(o) : null;
    String newProp = Names.camel(n);
    MapperDoc doc = MapperDoc.parse(text);
    Node mapper = mapperOf(doc);
    List<Hit> out = new ArrayList<>();
    if (mapper == null) return out;

    Set<Node> units = new HashSet<>();
    Set<String> usedMaps = new HashSet<>();
    for (Node s : doc.statements) {
      if (t != null && !mentions(text, doc, s, t, 0)) continue;
      units.add(s);
      addIncluded(doc, s, units, 0);
      String rm = s.attr(RESULT_MAP_ATTR);
      if (rm != null) {
        for (String id : rm.split(",")) usedMaps.add(localId(doc, id.trim()));
      }
    }
    for (Node c : mapper.children) {
      if (c.name == null) continue;
      if (SQL_TAG.equals(c.name) && (t == null || mentions(text, doc, c, t, 0))) {
        units.add(c);
        addIncluded(doc, c, units, 0);
      }
    }
    for (Node c : mapper.children) {
      if (c.name == null) continue;
      if (units.contains(c)) {
        String dto = MapperDoc.STATEMENT_TAGS.contains(c.name) ? c.attr("parameterType") : null;
        scanNode(text, c, o, n, oldProp, newProp, dto, out);
      } else if (MapperRefs.RESULT_MAP_TAG.equals(c.name) && (t == null || usedMaps.contains(c.attr("id")))) {
        scanResultMap(text, c, o, n, oldProp, newProp, c.attr("type"), out);
      }
    }
    out.sort(Comparator.comparingInt(Hit::start));
    return out;
  }

  /** hits 를 반영한 글. hits 는 겹치지 않아야 한다. */
  static String apply(CharSequence text, List<Hit> hits) {
    List<Hit> sorted = new ArrayList<>(hits);
    sorted.sort(Comparator.comparingInt(Hit::start));
    StringBuilder b = new StringBuilder(text.length());
    int at = 0;
    for (Hit h : sorted) {
      if (h.start() < at) throw new IllegalArgumentException("겹치는 바꿀 곳이 있습니다: " + h.start());
      b.append(text, at, h.start()).append(h.replacement());
      at = h.end();
    }
    return b.append(text, at, text.length()).toString();
  }

  // ---- 찾기 ----

  private static Node mapperOf(MapperDoc doc) {
    for (Node c : doc.root.children) {
      if ("mapper".equals(c.name)) return c;
    }
    return null;
  }

  private static String localId(MapperDoc doc, String id) {
    return doc.namespace != null && id.startsWith(doc.namespace + ".") ? id.substring(doc.namespace.length() + 1) : id;
  }

  /** n 의 SQL 글(포함하는 조각 포함)에 word 가 낱말로 나오면 true. */
  private static boolean mentions(CharSequence text, MapperDoc doc, Node n, String word, int depth) {
    for (Node c : n.children) {
      if (c.name == null) {
        List<Hit> found = new ArrayList<>();
        scanText(text, c, word, word, null, null, null, found);
        if (!found.isEmpty()) return true;
      } else if (INCLUDE_TAG.equals(c.name)) {
        Node f = doc.fragment(c.attr("refid"));
        if (f != null && depth < MAX_INCLUDE_DEPTH && mentions(text, doc, f, word, depth + 1)) return true;
      } else if (mentions(text, doc, c, word, depth)) {
        return true;
      }
    }
    return false;
  }

  private static void addIncluded(MapperDoc doc, Node n, Set<Node> units, int depth) {
    for (Node c : n.children) {
      if (c.name == null) continue;
      if (INCLUDE_TAG.equals(c.name)) {
        Node f = doc.fragment(c.attr("refid"));
        if (f != null && depth < MAX_INCLUDE_DEPTH && units.add(f)) addIncluded(doc, f, units, depth + 1);
      } else {
        addIncluded(doc, c, units, depth);
      }
    }
  }

  private static void scanNode(CharSequence text, Node n, String o, String nw, String oldProp, String newProp,
                               String dto, List<Hit> out) {
    for (Node c : n.children) {
      if (c.name == null) scanText(text, c, o, nw, oldProp, newProp, dto, out);
      else scanNode(text, c, o, nw, oldProp, newProp, dto, out);
    }
  }

  private static void scanResultMap(CharSequence text, Node n, String o, String nw, String oldProp, String newProp,
                                    String dto, List<Hit> out) {
    for (Node c : n.children) {
      if (c.name == null) continue;
      int[] col = attrRange(text, c, COLUMN_ATTR);
      int before = out.size();
      if (col != null) scan(text, col[0], col[1], o, nw, null, null, null, out);
      if (oldProp != null && out.size() > before && oldProp.equals(c.attr(PROPERTY_ATTR))) {
        int[] prop = attrRange(text, c, PROPERTY_ATTR);
        if (prop != null) out.add(new Hit(prop[0], prop[1], newProp, Kind.PROPERTY, dto));
      }
      scanResultMap(text, c, o, nw, oldProp, newProp, typeOf(c, dto), out);
    }
  }

  /** association · collection 따위는 자기 형식 속성, 없으면 바깥 형식. */
  private static String typeOf(Node n, String outer) {
    for (String a : TYPE_ATTRS) {
      String v = n.attr(a);
      if (v != null && !v.isBlank()) return v.trim();
    }
    return outer;
  }

  /** 글 조각의 원래 글자 범위. CDATA 는 여닫는 표시를 뺀다. */
  private static void scanText(CharSequence text, Node c, String o, String nw, String oldProp, String newProp,
                               String dto, List<Hit> out) {
    int from = c.start;
    int to = Math.min(c.end, text.length());
    if (startsWith(text, from, CDATA_OPEN)) {
      from += CDATA_OPEN.length();
      if (to - from >= CDATA_CLOSE.length() && startsWith(text, to - CDATA_CLOSE.length(), CDATA_CLOSE)) to -= CDATA_CLOSE.length();
    }
    scan(text, from, to, o, nw, oldProp, newProp, dto, out);
  }

  /**
   * [from, to) 에서 word 낱말(대소문자 무시)을 찾는다. 문자열 · #{} · ${} · 엔티티 안은 건너뛰고,
   * oldProp 이 있으면 #{} 의 첫 마디가 oldProp 과 같을 때(대소문자 구분) 그 마디도 바꾼다. 닫히지 않은 #{ 뒤는 보지 않는다.
   */
  private static void scan(CharSequence text, int from, int to, String word, String nw, String oldProp, String newProp,
                           String dto, List<Hit> out) {
    int i = from;
    while (i < to) {
      char c = text.charAt(i);
      if (c == '\'') {
        i = skipQuoted(text, i + 1, to);
      } else if ((c == '#' || c == '$') && i + 1 < to && text.charAt(i + 1) == '{') {
        int close = indexOf(text, '}', i + 2, to);
        if (close < 0) return;
        if (c == '#' && oldProp != null) paramHit(text, i + 2, close, oldProp, newProp, dto, out);
        i = close + 1;
      } else if (c == '&') {
        i = skipEntity(text, i, to);
      } else if (isIdent(c)) {
        int b = i;
        while (b < to && isIdent(text.charAt(b)) && !placeholderAt(text, b, to)) b++;
        String run = text.subSequence(i, b).toString();
        if (run.equalsIgnoreCase(word)) out.add(new Hit(i, b, caseLike(run, nw), Kind.COLUMN, null));
        i = b;
      } else {
        i++;
      }
    }
  }

  private static void paramHit(CharSequence text, int from, int close, String oldProp, String newProp, String dto, List<Hit> out) {
    int a = from;
    while (a < close && Character.isWhitespace(text.charAt(a))) a++;
    int b = a;
    while (b < close && Character.isJavaIdentifierPart(text.charAt(b))) b++;
    if (text.subSequence(a, b).toString().equals(oldProp)) out.add(new Hit(a, b, newProp, Kind.PARAM, dto));
  }

  /** 여는 따옴표 다음에서 시작해 닫는 따옴표 다음을 준다. '' 는 글 안의 따옴표. 닫히지 않으면 to. */
  private static int skipQuoted(CharSequence text, int i, int to) {
    while (i < to) {
      if (text.charAt(i) == '\'') {
        if (i + 1 < to && text.charAt(i + 1) == '\'') i += 2;
        else return i + 1;
      } else {
        i++;
      }
    }
    return to;
  }

  /** &amp;이름; · &amp;#숫자; 꼴이면 그 뒤, 아니면 다음 글자. */
  private static int skipEntity(CharSequence text, int i, int to) {
    int k = i + 1;
    while (k < to && k - i <= MAX_ENTITY_LENGTH && (Character.isLetterOrDigit(text.charAt(k)) || text.charAt(k) == '#')) k++;
    return k < to && k > i + 1 && text.charAt(k) == ';' ? k + 1 : i + 1;
  }

  private static boolean placeholderAt(CharSequence text, int i, int to) {
    char c = text.charAt(i);
    return (c == '#' || c == '$') && i + 1 < to && text.charAt(i + 1) == '{';
  }

  /** 요소 여는 태그 안 key 속성 값의 원래 글자 범위 [시작, 끝). 없으면 null. */
  private static int[] attrRange(CharSequence text, Node el, String key) {
    int i = el.start + 1 + el.name.length();
    int limit = text.length();
    while (i < limit) {
      while (i < limit && Character.isWhitespace(text.charAt(i))) i++;
      if (i >= limit || text.charAt(i) == '>' || text.charAt(i) == '/' || text.charAt(i) == '<') return null;
      int nameStart = i;
      while (i < limit && !Character.isWhitespace(text.charAt(i)) && "=/><".indexOf(text.charAt(i)) < 0) i++;
      String name = text.subSequence(nameStart, i).toString();
      while (i < limit && Character.isWhitespace(text.charAt(i))) i++;
      if (i >= limit || text.charAt(i) != '=') continue;
      i++;
      while (i < limit && Character.isWhitespace(text.charAt(i))) i++;
      if (i >= limit || (text.charAt(i) != '"' && text.charAt(i) != '\'')) continue;
      char q = text.charAt(i);
      int close = indexOf(text, q, i + 1, limit);
      if (close < 0) return null;
      if (name.equals(key)) return new int[] {i + 1, close};
      i = close + 1;
    }
    return null;
  }

  private static int indexOf(CharSequence text, char c, int from, int to) {
    for (int k = from; k < to; k++) {
      if (text.charAt(k) == c) return k;
    }
    return -1;
  }

  private static boolean startsWith(CharSequence text, int at, String s) {
    return at >= 0 && at + s.length() <= text.length() && text.subSequence(at, at + s.length()).toString().equals(s);
  }

  static boolean isIdent(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
  }
}
