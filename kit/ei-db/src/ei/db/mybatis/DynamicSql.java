package ei.db.mybatis;

import ei.db.DbKind;
import ei.db.mybatis.MapperDoc.Node;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 문장 하나의 동적 SQL 을 매개변수 값으로 풀어 실제 실행될 SQL 을 만든다.
 * 다루는 태그: if, choose/when/otherwise, where, set, trim, foreach, include, bind. selectKey 는 뺀다.
 * include 는 같은 파일 것을 먼저, 없으면 「네임스페이스.id」 의 네임스페이스 매퍼(mappers 가 주는 문서)에서 찾는다.
 * include 안의 &lt;property name value&gt; 는 그 조각 안에서 같은 이름의 값이 된다(${name} 에 쓰임).
 * #{name, jdbcType=…} → ? + 바인드 값, ${name} → 값의 글자 그대로. _parameter 는 매개변수 전체, _databaseId 는 받은 값.
 */
public final class DynamicSql {
  private static final List<String> WHERE_OVERRIDES =
      List.of("AND ", "OR ", "AND\n", "OR\n", "AND\r", "OR\r", "AND\t", "OR\t");
  /** include 가 자기를 다시 부르는 잘못된 매퍼에서 멈추기 위한 깊이. */
  private static final int MAX_INCLUDE_DEPTH = 16;
  private static final String INCLUDE = "include";
  private static final String PROPERTY = "property";
  /** MyBatis 가 스스로 넣는 이름. */
  private static final String PARAMETER = "_parameter";
  private static final String DATABASE_ID = "_databaseId";
  /** MyBatis VendorDatabaseIdProvider 의 흔한 설정(property 이름 → value)에 맞춘 _databaseId 값. */
  public static final String ORACLE_ID = "oracle";
  public static final String POSTGRESQL_ID = "postgresql";
  public static final String MYSQL_ID = "mysql";
  /** 다른 매퍼를 찾지 않을 때의 mappers. */
  public static final Function<String, MapperDoc> NO_MAPPERS = ns -> null;

  /** include 대상: sql 조각과 그 조각이 든 문서(조각 안 include 는 그 문서 기준으로 찾는다). */
  record Fragment(MapperDoc doc, Node node) {}

  public static final class Result {
    /** ? 가 든 SQL(줄 앞뒤 공백 정리, 빈 줄 제거). */
    public String sql;
    public final List<Object> binds = new ArrayList<>();
    public int ifTotal;
    public int ifIncluded;
    public int foreachItems;
    public boolean hasForeach;
    /** 찾지 못한 include refid(차례대로, 중복 없음). */
    public final Set<String> missingIncludes = new LinkedHashSet<>();
    /** 읽지 못한 test · bind 식 수(거짓 · null 로 셈). */
    public int badExpressions;

    /** ? 자리에 값을 넣은 미리보기 · 복사용 SQL. */
    public String inlined() { return SqlValues.inline(sql, binds); }

    /** 예: 「&lt;if&gt; 3개 중 2개 포함 · foreach 2개」. 해당 태그가 없으면 그 부분은 뺀다. */
    public String summary() {
      List<String> parts = new ArrayList<>();
      if (ifTotal > 0) parts.add("<if> " + ifTotal + "개 중 " + ifIncluded + "개 포함");
      if (hasForeach) parts.add("foreach " + foreachItems + "개");
      if (!missingIncludes.isEmpty()) parts.add("찾지 못한 include: " + String.join(", ", missingIncludes));
      if (badExpressions > 0) parts.add("읽지 못한 식 " + badExpressions + "개(거짓으로 셈)");
      return String.join(" · ", parts);
    }

    /** 찾지 못한 include · 읽지 못한 식이 있으면 true. */
    public boolean hasWarnings() { return !missingIncludes.isEmpty() || badExpressions > 0; }
  }

  /** 지금 풀고 있는 노드가 든 문서. 다른 매퍼의 조각을 푸는 동안만 바뀐다. */
  private MapperDoc doc;
  private final Function<String, MapperDoc> mappers;
  private final Result result = new Result();

  private DynamicSql(MapperDoc doc, Function<String, MapperDoc> mappers) {
    this.doc = doc;
    this.mappers = mappers;
  }

  public static Result resolve(MapperDoc doc, Node statement, Map<String, Object> params) {
    return resolve(doc, statement, params, null, NO_MAPPERS);
  }

  /** databaseId 는 _databaseId 값(모르면 null), mappers 는 네임스페이스 → 그 매퍼 문서(없으면 null). */
  public static Result resolve(MapperDoc doc, Node statement, Map<String, Object> params, String databaseId,
                               Function<String, MapperDoc> mappers) {
    DynamicSql d = new DynamicSql(doc, mappers);
    StringBuilder out = new StringBuilder();
    Map<String, Object> scope = new HashMap<>(params);
    scope.put(PARAMETER, new HashMap<>(params));
    scope.put(DATABASE_ID, databaseId);
    d.children(statement, scope, out, 0);
    d.result.sql = tidy(out.toString());
    return d.result;
  }

  /**
   * 문장이 쓰는 매개변수 이름을 처음 나온 차례로. #{} · ${} · test 식 · foreach collection 에서 모으고,
   * 둘러싼 foreach 의 item · index 이름과 bind 이름은 뺀다.
   */
  public static List<String> paramNames(MapperDoc doc, Node statement) {
    return paramNames(doc, statement, NO_MAPPERS);
  }

  /** include property 이름은 그 조각 안에서 매개변수로 치지 않는다. */
  public static List<String> paramNames(MapperDoc doc, Node statement, Function<String, MapperDoc> mappers) {
    Set<String> out = new LinkedHashSet<>();
    collectNames(doc, statement, new HashSet<>(), out, 0, mappers);
    return new ArrayList<>(out);
  }

  /** foreach collection 으로 쓰이는 이름(대화창에서 쉼표로 나눈 목록으로 받는다). */
  public static Set<String> listNames(MapperDoc doc, Node statement) {
    return listNames(doc, statement, NO_MAPPERS);
  }

  public static Set<String> listNames(MapperDoc doc, Node statement, Function<String, MapperDoc> mappers) {
    Set<String> out = new HashSet<>();
    collectLists(doc, statement, out, 0, mappers);
    return out;
  }

  /**
   * refid 의 sql 조각. 같은 문서 것이 먼저, 없고 refid 가 「네임스페이스.id」 꼴이면 mappers 가 준 그 네임스페이스 문서의 것.
   * 찾지 못하면 null.
   */
  static Fragment fragment(MapperDoc doc, String refid, Function<String, MapperDoc> mappers) {
    Node own = doc.fragment(refid);
    if (own != null) return new Fragment(doc, own);
    int dot = refid == null ? -1 : refid.lastIndexOf('.');
    if (dot <= 0) return null;
    MapperDoc other = mappers.apply(refid.substring(0, dot));
    Node node = other == null ? null : other.fragments.get(refid.substring(dot + 1));
    return node == null ? null : new Fragment(other, node);
  }

  /** include 안 &lt;property&gt; 의 이름 → 값. */
  private static Map<String, String> includeProperties(Node include) {
    Map<String, String> out = new HashMap<>();
    for (Node c : include.children) {
      if (PROPERTY.equals(c.name) && c.attr("name") != null) out.put(c.attr("name").trim(), c.attr("value") == null ? "" : c.attr("value"));
    }
    return out;
  }

  private static void collectLists(MapperDoc doc, Node n, Set<String> out, int depth, Function<String, MapperDoc> mappers) {
    for (Node c : n.children) {
      if (c.name == null) continue;
      if ("foreach".equals(c.name) && c.attr("collection") != null) out.add(c.attr("collection").trim());
      if (depth >= MAX_INCLUDE_DEPTH) continue;
      if (INCLUDE.equals(c.name)) {
        Fragment f = fragment(doc, c.attr("refid"), mappers);
        if (f != null) collectLists(f.doc(), f.node(), out, depth + 1, mappers);
      } else {
        collectLists(doc, c, out, depth + 1, mappers);
      }
    }
  }

  private static void collectNames(MapperDoc doc, Node n, Set<String> local, Set<String> out, int depth,
                                   Function<String, MapperDoc> mappers) {
    for (Node c : n.children) {
      if (c.name == null) {
        for (String name : placeholderNames(c.text)) addName(name, local, out);
        continue;
      }
      String test = c.attr("test");
      if (test != null && ("if".equals(c.name) || "when".equals(c.name))) {
        for (String name : Ognl.names(test)) addName(name, local, out);
      }
      switch (c.name) {
        case "foreach" -> {
          if (c.attr("collection") != null) addName(c.attr("collection").trim(), local, out);
          Set<String> inner = new HashSet<>(local);
          if (c.attr("item") != null) inner.add(c.attr("item"));
          if (c.attr("index") != null) inner.add(c.attr("index"));
          collectNames(doc, c, inner, out, depth, mappers);
        }
        case "bind" -> {
          for (String name : Ognl.names(c.attr("value") == null ? "" : c.attr("value"))) addName(name, local, out);
          if (c.attr("name") != null) local.add(c.attr("name"));
        }
        case INCLUDE -> {
          Fragment f = depth < MAX_INCLUDE_DEPTH ? fragment(doc, c.attr("refid"), mappers) : null;
          if (f != null) {
            Set<String> inner = new HashSet<>(local);
            inner.addAll(includeProperties(c).keySet());
            collectNames(f.doc(), f.node(), inner, out, depth + 1, mappers);
          }
        }
        case "selectKey" -> { }
        default -> collectNames(doc, c, local, out, depth, mappers);
      }
    }
  }

  private static void addName(String name, Set<String> local, Set<String> out) {
    String root = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
    if (name.isEmpty() || local.contains(root) || name.startsWith("_")) return;
    out.add(name);
  }

  private static List<String> placeholderNames(String text) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (true) {
      int a = nextPlaceholder(text, i);
      if (a < 0) return out;
      int close = text.indexOf('}', a + 2);
      if (close < 0) return out;
      out.add(placeholderName(text.substring(a + 2, close)));
      i = close + 1;
    }
  }

  private static int nextPlaceholder(String text, int from) {
    int h = text.indexOf("#{", from), d = text.indexOf("${", from);
    if (h < 0) return d;
    if (d < 0) return h;
    return Math.min(h, d);
  }

  private static String placeholderName(String inside) {
    int comma = inside.indexOf(',');
    return (comma < 0 ? inside : inside.substring(0, comma)).trim();
  }

  private void children(Node n, Map<String, Object> scope, StringBuilder out, int depth) {
    for (Node c : n.children) node(c, scope, out, depth);
  }

  private void node(Node n, Map<String, Object> scope, StringBuilder out, int depth) {
    if (n.name == null) {
      text(n.text, scope, out);
      return;
    }
    switch (n.name) {
      case "if" -> {
        result.ifTotal++;
        if (test(attr(n, "test"), scope)) {
          result.ifIncluded++;
          children(n, scope, out, depth);
        }
      }
      case "choose" -> {
        for (Node c : n.children) {
          if ("when".equals(c.name) && test(attr(c, "test"), scope)) {
            children(c, scope, out, depth);
            return;
          }
        }
        for (Node c : n.children) {
          if ("otherwise".equals(c.name)) children(c, scope, out, depth);
        }
      }
      case "where" -> trim(n, scope, out, depth, "WHERE", null, WHERE_OVERRIDES, List.of());
      case "set" -> trim(n, scope, out, depth, "SET", null, List.of(","), List.of(","));
      case "trim" -> trim(n, scope, out, depth, n.attr("prefix"), n.attr("suffix"),
                          split(n.attr("prefixOverrides")), split(n.attr("suffixOverrides")));
      case "foreach" -> foreach(n, scope, out, depth);
      case "bind" -> {
        if (n.attr("name") != null) scope.put(n.attr("name"), evaluate(attr(n, "value"), scope));
      }
      case INCLUDE -> include(n, scope, out, depth);
      case "selectKey" -> { }
      default -> children(n, scope, out, depth);
    }
  }

  /** 접속 종류의 _databaseId. kind 가 null 이면 null. */
  public static String databaseId(DbKind kind) {
    if (kind == null) return null;
    return switch (kind) {
      case ORACLE -> ORACLE_ID;
      case POSTGRES -> POSTGRESQL_ID;
      case MYSQL -> MYSQL_ID;
    };
  }

  /** 찾지 못한 조각은 result.missingIncludes 에 담는다. property 가 있으면 그 값을 더한 복사본 범위에서 푼다. */
  private void include(Node n, Map<String, Object> scope, StringBuilder out, int depth) {
    if (depth >= MAX_INCLUDE_DEPTH) return;
    Fragment f = fragment(doc, n.attr("refid"), mappers);
    if (f == null) {
      result.missingIncludes.add(attr(n, "refid"));
      return;
    }
    Map<String, String> props = includeProperties(n);
    Map<String, Object> inner = scope;
    if (!props.isEmpty()) {
      inner = new HashMap<>(scope);
      inner.putAll(props);
    }
    MapperDoc outer = doc;
    doc = f.doc();
    try {
      children(f.node(), inner, out, depth + 1);
    } finally {
      doc = outer;
    }
  }

  /** 읽지 못한 식은 세고 거짓으로 본다. */
  private boolean test(String expr, Map<String, Object> scope) {
    return Ognl.truthy(evaluate(expr, scope));
  }

  private Object evaluate(String expr, Map<String, Object> scope) {
    try {
      return Ognl.evaluate(expr, k -> lookup(scope, k));
    } catch (IllegalArgumentException e) {
      result.badExpressions++;
      return null;
    }
  }

  private void trim(Node n, Map<String, Object> scope, StringBuilder out, int depth, String prefix, String suffix,
                    List<String> prefixOverrides, List<String> suffixOverrides) {
    StringBuilder inner = new StringBuilder();
    children(n, scope, inner, depth);
    String body = inner.toString().trim();
    if (body.isEmpty()) return;
    String upper = body.toUpperCase();
    for (String o : prefixOverrides) {
      if (upper.startsWith(o.toUpperCase())) {
        body = body.substring(o.length()).trim();
        break;
      }
    }
    upper = body.toUpperCase();
    for (String o : suffixOverrides) {
      if (upper.endsWith(o.toUpperCase())) {
        body = body.substring(0, body.length() - o.length()).trim();
        break;
      }
    }
    out.append(' ');
    if (prefix != null && !prefix.isEmpty()) out.append(prefix).append(' ');
    out.append(body);
    if (suffix != null && !suffix.isEmpty()) out.append(' ').append(suffix);
    out.append(' ');
  }

  private void foreach(Node n, Map<String, Object> scope, StringBuilder out, int depth) {
    result.hasForeach = true;
    Object coll = lookup(scope, attr(n, "collection").trim());
    List<Object[]> entries = entries(coll);
    if (entries.isEmpty()) return;
    String item = n.attr("item"), index = n.attr("index");
    String sep = n.attr("separator") == null ? "" : n.attr("separator");
    StringBuilder body = new StringBuilder();
    boolean first = true;
    for (Object[] e : entries) {
      Map<String, Object> inner = new HashMap<>(scope);
      if (item != null) inner.put(item, e[1]);
      if (index != null) inner.put(index, e[0]);
      StringBuilder one = new StringBuilder();
      children(n, inner, one, depth);
      if (one.toString().isBlank()) continue;
      if (!first) body.append(sep).append(sep.equals(",") ? " " : "");
      body.append(one.toString().trim());
      first = false;
      result.foreachItems++;
    }
    out.append(n.attr("open") == null ? "" : n.attr("open")).append(body).append(n.attr("close") == null ? "" : n.attr("close"));
  }

  /** {index, item} 쌍. Map 은 키가 index, 값이 item. null 은 빈 목록. */
  private static List<Object[]> entries(Object coll) {
    List<Object[]> out = new ArrayList<>();
    if (coll instanceof Map<?, ?> m) {
      for (Map.Entry<?, ?> e : m.entrySet()) out.add(new Object[] {e.getKey(), e.getValue()});
    } else if (coll instanceof Collection<?> c) {
      int i = 0;
      for (Object o : c) out.add(new Object[] {i++, o});
    } else if (coll != null && coll.getClass().isArray()) {
      for (int i = 0; i < Array.getLength(coll); i++) out.add(new Object[] {i, Array.get(coll, i)});
    } else if (coll != null) {
      out.add(new Object[] {0, coll});
    }
    return out;
  }

  private void text(String text, Map<String, Object> scope, StringBuilder out) {
    int i = 0;
    while (true) {
      int a = nextPlaceholder(text, i);
      int close = a < 0 ? -1 : text.indexOf('}', a + 2);
      if (close < 0) {
        out.append(text, i, text.length());
        return;
      }
      out.append(text, i, a);
      Object v = lookup(scope, placeholderName(text.substring(a + 2, close)));
      if (text.charAt(a) == '#') {
        out.append('?');
        result.binds.add(v);
      } else if (v != null) {
        out.append(v);
      }
      i = close + 1;
    }
  }

  /** 점 경로 그대로의 키가 먼저, 없으면 첫 마디부터 Map 을 따라 내려간다. */
  static Object lookup(Map<String, Object> scope, String path) {
    if (scope.containsKey(path)) return scope.get(path);
    String[] parts = path.split("\\.");
    Object v = scope.get(parts[0]);
    for (int k = 1; k < parts.length && v != null; k++) {
      v = v instanceof Map<?, ?> m ? m.get(parts[k]) : null;
    }
    return v;
  }

  private static String attr(Node n, String key) { return n.attr(key) == null ? "" : n.attr(key); }

  private static List<String> split(String overrides) {
    if (overrides == null || overrides.isEmpty()) return List.of();
    return Arrays.asList(overrides.split("\\|"));
  }

  /** 줄마다 끝 공백을 지우고 빈 줄을 빼며, 공통 들여쓰기를 걷어낸다. */
  static String tidy(String sql) {
    List<String> lines = new ArrayList<>();
    int indent = Integer.MAX_VALUE;
    for (String line : sql.split("\r?\n")) {
      String l = line.stripTrailing();
      if (l.isBlank()) continue;
      lines.add(l);
      indent = Math.min(indent, l.length() - l.stripLeading().length());
    }
    StringBuilder b = new StringBuilder();
    for (String l : lines) {
      if (b.length() > 0) b.append('\n');
      b.append(l.substring(indent));
    }
    return b.toString();
  }
}
