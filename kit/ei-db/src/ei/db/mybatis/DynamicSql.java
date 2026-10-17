package ei.db.mybatis;

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

/**
 * 문장 하나의 동적 SQL 을 매개변수 값으로 풀어 실제 실행될 SQL 을 만든다.
 * 다루는 태그: if, choose/when/otherwise, where, set, trim, foreach, include(같은 파일), bind. selectKey 는 뺀다.
 * #{name, jdbcType=…} → ? + 바인드 값, ${name} → 값의 글자 그대로.
 */
public final class DynamicSql {
  private static final List<String> WHERE_OVERRIDES =
      List.of("AND ", "OR ", "AND\n", "OR\n", "AND\r", "OR\r", "AND\t", "OR\t");
  /** include 가 자기를 다시 부르는 잘못된 매퍼에서 멈추기 위한 깊이. */
  private static final int MAX_INCLUDE_DEPTH = 16;

  public static final class Result {
    /** ? 가 든 SQL(줄 앞뒤 공백 정리, 빈 줄 제거). */
    public String sql;
    public final List<Object> binds = new ArrayList<>();
    public int ifTotal;
    public int ifIncluded;
    public int foreachItems;
    public boolean hasForeach;

    /** ? 자리에 값을 넣은 미리보기 · 복사용 SQL. */
    public String inlined() { return SqlValues.inline(sql, binds); }

    /** 예: 「&lt;if&gt; 3개 중 2개 포함 · foreach 2개」. 해당 태그가 없으면 그 부분은 뺀다. */
    public String summary() {
      List<String> parts = new ArrayList<>();
      if (ifTotal > 0) parts.add("<if> " + ifTotal + "개 중 " + ifIncluded + "개 포함");
      if (hasForeach) parts.add("foreach " + foreachItems + "개");
      return String.join(" · ", parts);
    }
  }

  private final MapperDoc doc;
  private final Result result = new Result();

  private DynamicSql(MapperDoc doc) { this.doc = doc; }

  public static Result resolve(MapperDoc doc, Node statement, Map<String, Object> params) {
    DynamicSql d = new DynamicSql(doc);
    StringBuilder out = new StringBuilder();
    d.children(statement, new HashMap<>(params), out, 0);
    d.result.sql = tidy(out.toString());
    return d.result;
  }

  /**
   * 문장이 쓰는 매개변수 이름을 처음 나온 차례로. #{} · ${} · test 식 · foreach collection 에서 모으고,
   * 둘러싼 foreach 의 item · index 이름과 bind 이름은 뺀다.
   */
  public static List<String> paramNames(MapperDoc doc, Node statement) {
    Set<String> out = new LinkedHashSet<>();
    collectNames(doc, statement, new HashSet<>(), out, 0);
    return new ArrayList<>(out);
  }

  /** foreach collection 으로 쓰이는 이름(대화창에서 쉼표로 나눈 목록으로 받는다). */
  public static Set<String> listNames(MapperDoc doc, Node statement) {
    Set<String> out = new HashSet<>();
    collectLists(doc, statement, out, 0);
    return out;
  }

  private static void collectLists(MapperDoc doc, Node n, Set<String> out, int depth) {
    for (Node c : n.children) {
      if (c.name == null) continue;
      if ("foreach".equals(c.name) && c.attr("collection") != null) out.add(c.attr("collection").trim());
      Node target = "include".equals(c.name) ? doc.fragment(c.attr("refid")) : c;
      if (target != null && depth < MAX_INCLUDE_DEPTH) collectLists(doc, target, out, depth + 1);
    }
  }

  private static void collectNames(MapperDoc doc, Node n, Set<String> local, Set<String> out, int depth) {
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
          collectNames(doc, c, inner, out, depth);
        }
        case "bind" -> {
          for (String name : Ognl.names(c.attr("value") == null ? "" : c.attr("value"))) addName(name, local, out);
          if (c.attr("name") != null) local.add(c.attr("name"));
        }
        case "include" -> {
          Node f = doc.fragment(c.attr("refid"));
          if (f != null && depth < MAX_INCLUDE_DEPTH) collectNames(doc, f, local, out, depth + 1);
        }
        case "selectKey" -> { }
        default -> collectNames(doc, c, local, out, depth);
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
        if (Ognl.test(attr(n, "test"), k -> lookup(scope, k))) {
          result.ifIncluded++;
          children(n, scope, out, depth);
        }
      }
      case "choose" -> {
        for (Node c : n.children) {
          if ("when".equals(c.name) && Ognl.test(attr(c, "test"), k -> lookup(scope, k))) {
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
        if (n.attr("name") != null) scope.put(n.attr("name"), Ognl.eval(attr(n, "value"), k -> lookup(scope, k)));
      }
      case "include" -> {
        Node f = doc.fragment(n.attr("refid"));
        if (f != null && depth < MAX_INCLUDE_DEPTH) children(f, scope, out, depth + 1);
      }
      case "selectKey" -> { }
      default -> children(n, scope, out, depth);
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
