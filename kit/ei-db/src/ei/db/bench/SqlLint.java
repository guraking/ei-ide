package ei.db.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실행 전 쿼리 점검: 결과를 크게 바꿀 수 있는 실수만 찾는다(문법 오류는 DB 가 알려 준다). IntelliJ 에 기대지 않는다.
 * 맨 바깥 SELECT 의 첫 묶음과 WITH 단계 본문을 본다. 틀리게 짚는 일을 줄이려고 확실한 꼴만 알린다.
 * 1) 다른 표와 이어지지 않은 표(모든 조합) 2) 집계와 일반 칼럼이 섞였는데 GROUP BY 가 없음 · GROUP BY 에 없는 칼럼
 * 3) = NULL 비교 4) NOT IN (SELECT …) 5) 옛 방식 바깥 조인 표에 (+) 없이 건 조건.
 */
public final class SqlLint {
  private SqlLint() {}

  public record Issue(String title, String detail) {}

  /** 쿼리 안 표와 이은 조건(조인 그림에 쓴다). links 의 값은 조건 글. */
  public record Graph(List<SqlParts.Source> sources, List<Link> links) {}

  public record Link(String leftRef, String leftColumn, String rightRef, String rightColumn, String condition) {}

  private static final Pattern AGG = Pattern.compile("(?<![\\w$#])(COUNT|SUM|AVG|MIN|MAX|LISTAGG|STRING_AGG|STDDEV|VARIANCE|MEDIAN|ARRAY_AGG)\\s*\\(");
  private static final Pattern OVER = Pattern.compile("(?<![\\w$#])OVER\\s*\\(");
  private static final Pattern EQ_NULL = Pattern.compile("(=|<>|!=)\\s*NULL(?![\\w$#])");
  private static final Pattern NOT_IN_SELECT = Pattern.compile("(?<![\\w$#])NOT\\s+IN\\s*\\(\\s*SELECT\\b");
  private static final Pattern SIMPLE_COLUMN = Pattern.compile("((?:[A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\.\\s*)?([A-Za-z_][\\w$#]*|\"[^\"]+\")");
  private static final Pattern AS_ALIAS = Pattern.compile("(?is)^(.*?)(?:\\s+AS)?\\s+([A-Za-z_][\\w$#]*|\"[^\"]+\")$");
  private static final Pattern PLUS_OWNER = Pattern.compile("([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\.\\s*[A-Za-z_\"][\\w$#\"]*\\s*\\(\\s*\\+\\s*\\)");
  private static final Pattern EQUALS = Pattern.compile("^\\s*([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\.\\s*([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*(?:\\(\\s*\\+\\s*\\))?\\s*=\\s*([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\.\\s*([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*(?:\\(\\s*\\+\\s*\\))?\\s*$");
  private static final Set<String> CONSTANTS = Set.of("NULL", "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP", "ROWNUM", "LEVEL", "USER");

  public static List<Issue> check(String sql) {
    List<Issue> out = new ArrayList<>();
    List<SqlParts.Part> parts = SqlParts.split(sql);
    if (parts.isEmpty()) return out;
    String first = parts.get(0).key();
    if (!first.equals("SELECT") && !first.equals("WITH")) return out;
    if (first.equals("WITH")) {
      for (SqlTools.Cte c : SqlTools.ctes(sql)) block(sql.substring(c.bodyStart(), c.bodyEnd()), c.name() + " 단계", out);
    }
    block(sql, null, out);
    String masked = SqlParts.mask(sql).toUpperCase(Locale.ROOT);
    if (EQ_NULL.matcher(masked).find()) {
      out.add(new Issue("= NULL 비교", "NULL 은 = · <> 로 비교하면 늘 거짓이라 결과가 비게 됩니다. IS NULL · IS NOT NULL 로 쓰세요."));
    }
    if (NOT_IN_SELECT.matcher(masked).find()) {
      out.add(new Issue("NOT IN (SELECT …)", "안쪽 결과에 NULL 이 하나라도 있으면 아무 행도 나오지 않습니다. NOT EXISTS 로 쓰는 편이 안전합니다."));
    }
    return out;
  }

  /** 한 묶음(맨 바깥 또는 WITH 단계 본문) 점검. where 는 알림 앞에 붙일 단계 이름(맨 바깥이면 null). */
  private static void block(String sql, String where, List<Issue> out) {
    List<SqlParts.Part> parts = SqlParts.mainBlock(SqlParts.split(sql));
    SqlParts.Part sel = SqlParts.find(parts, "SELECT");
    SqlParts.Part from = SqlParts.find(parts, "FROM");
    if (sel == null || from == null) return;
    String at = where == null ? "" : where + ": ";
    Graph g = graph(parts);
    List<String> loose = unlinked(g);
    for (String ref : loose) {
      out.add(new Issue(at + "이어지지 않은 표 " + ref, ref + " 가 다른 표와 잇는 조건이 없어 두 표의 모든 조합(곱하기)이 나옵니다. 행 수가 크게 늘어납니다."));
    }
    groupCheck(sel.body(), SqlParts.find(parts, "GROUP BY"), at, out);
    outerCheck(g, SqlParts.find(parts, "WHERE"), at, out);
  }

  /** 맨 바깥 첫 묶음의 표와 잇는 조건. */
  public static Graph graph(String sql) {
    return graph(SqlParts.mainBlock(SqlParts.split(sql)));
  }

  private static Graph graph(List<SqlParts.Part> parts) {
    SqlParts.Part from = SqlParts.find(parts, "FROM");
    if (from == null) return new Graph(List.of(), List.of());
    List<SqlParts.Source> sources = SqlParts.sources(from.body());
    List<String> conds = new ArrayList<>();
    for (SqlParts.Source s : sources) if (s.on() != null) conds.addAll(SqlParts.splitAnd(s.on()));
    SqlParts.Part where = SqlParts.find(parts, "WHERE");
    if (where != null) conds.addAll(SqlParts.splitAnd(where.body()));
    Map<String, String> refs = new HashMap<>();
    for (SqlParts.Source s : sources) refs.put(norm(s.ref()), s.ref());
    List<Link> links = new ArrayList<>();
    for (String c : conds) {
      List<String> mine = new ArrayList<>();
      for (String r : SqlParts.refs(c)) if (refs.containsKey(r)) mine.add(r);
      if (mine.size() < 2) continue;
      Matcher m = EQUALS.matcher(SqlParts.mask(c));
      if (m.matches() && refs.containsKey(norm(m.group(1))) && refs.containsKey(norm(m.group(3)))) {
        links.add(new Link(refs.get(norm(m.group(1))), unquote(c, m, 2), refs.get(norm(m.group(3))), unquote(c, m, 4), SqlParts.oneLine(c)));
      } else {
        links.add(new Link(refs.get(mine.get(0)), "", refs.get(mine.get(1)), "", SqlParts.oneLine(c)));
      }
    }
    return new Graph(sources, links);
  }

  private static String unquote(String c, Matcher m, int group) {
    return c.substring(m.start(group), m.end(group)).replace("\"", "");
  }

  /** 첫 표에서 잇는 조건을 따라 닿지 않는 표의 이름(별칭). CROSS JOIN 은 일부러 쓴 것이라, LATERAL · 표 함수는 잇는 조건이 안쪽(인자)에 있어 뺀다. 표가 하나면 빈 목록. */
  public static List<String> unlinked(Graph g) {
    List<String> out = new ArrayList<>();
    if (g.sources().size() < 2) return out;
    Map<String, Set<String>> adj = new LinkedHashMap<>();
    for (SqlParts.Source s : g.sources()) adj.put(norm(s.ref()), new HashSet<>());
    for (Link l : g.links()) {
      adj.get(norm(l.leftRef())).add(norm(l.rightRef()));
      adj.get(norm(l.rightRef())).add(norm(l.leftRef()));
    }
    Set<String> seen = new HashSet<>();
    List<String> todo = new ArrayList<>(List.of(norm(g.sources().get(0).ref())));
    while (!todo.isEmpty()) {
      String a = todo.remove(todo.size() - 1);
      if (seen.add(a)) todo.addAll(adj.get(a));
    }
    for (SqlParts.Source s : g.sources()) {
      // 표 함수(UNNEST(p.TAGS) 등)는 인자로 앞 표를 부를 수 있어 LATERAL 과 같이 본다.
      boolean lateral = s.text().regionMatches(true, 0, "LATERAL", 0, "LATERAL".length()) || (!s.subquery() && s.name().endsWith(")"));
      if (!"CROSS".equals(s.join()) && !lateral && !seen.contains(norm(s.ref()))) out.add(s.ref());
    }
    return out;
  }

  private static void groupCheck(String select, SqlParts.Part group, String at, List<Issue> out) {
    List<String> items = SqlParts.splitCommas(select.replaceFirst("(?is)^\\s*(DISTINCT|UNIQUE|ALL)\\s+", ""));
    boolean agg = false;
    List<String> plain = new ArrayList<>();
    for (String item : items) {
      String expr = stripAlias(item);
      String masked = SqlParts.blankNested(SqlParts.mask(expr)).toUpperCase(Locale.ROOT);
      boolean windowed = OVER.matcher(masked).find();
      if (AGG.matcher(masked).find() && !windowed) {
        agg = true;
      } else if (!windowed && SIMPLE_COLUMN.matcher(expr.strip()).matches() && !CONSTANTS.contains(expr.strip().toUpperCase(Locale.ROOT))) {
        plain.add(expr.strip());
      }
    }
    if (group == null) {
      if (agg && !plain.isEmpty()) {
        out.add(new Issue(at + "GROUP BY 빠짐", "집계(COUNT · SUM 등)와 일반 칼럼 " + String.join(", ", plain)
            + " 가 함께 있는데 GROUP BY 가 없습니다. 이 칼럼들로 GROUP BY 를 넣으세요(ORA-00937 · PostgreSQL 42803)."));
      }
      return;
    }
    Set<String> grouped = new HashSet<>();
    String body = group.body().replaceFirst("(?is)^\\s*(ROLLUP|CUBE|GROUPING\\s+SETS)\\s*", "");
    for (String g : SqlParts.splitCommas(body.replaceAll("[()]", " "))) {
      grouped.add(key(g));
      grouped.add(last(key(g)));
    }
    List<String> missing = new ArrayList<>();
    for (String p : plain) {
      if (!grouped.contains(key(p)) && !grouped.contains(last(key(p)))) missing.add(p);
    }
    if (!missing.isEmpty()) {
      out.add(new Issue(at + "GROUP BY 에 없는 칼럼", String.join(", ", missing) + " 는 집계도 아니고 GROUP BY 에도 없습니다. GROUP BY 에 넣거나 집계로 감싸세요(ORA-00979)."));
    }
  }

  /** 옛 방식에서 (+) 로 바깥 조인한 표에, 다른 조건은 (+) 없이 걸면 바깥 조인이 풀린다. IS NULL 검사는 일부러 쓰는 꼴이라 뺀다. */
  private static void outerCheck(Graph g, SqlParts.Part where, String at, List<Issue> out) {
    if (where == null) return;
    Set<String> outer = new HashSet<>();
    List<String> conds = SqlParts.splitAnd(where.body());
    for (String c : conds) {
      Matcher m = PLUS_OWNER.matcher(SqlParts.mask(c));
      while (m.find()) outer.add(norm(m.group(1)));
    }
    if (outer.isEmpty()) return;
    for (String c : conds) {
      String masked = SqlParts.mask(c).toUpperCase(Locale.ROOT);
      Set<String> refs = SqlParts.refs(c);
      if (refs.size() != 1 || masked.contains("(+)") || masked.matches("(?s).*\\bIS\\s+(NOT\\s+)?NULL\\b.*")) continue;
      String r = refs.iterator().next();
      if (outer.contains(r)) {
        out.add(new Issue(at + "바깥 조인이 풀림", SqlParts.oneLine(c) + " 에 (+) 가 없어 " + r.toLowerCase(Locale.ROOT)
            + " 표에 맞는 행이 없는 줄이 빠집니다(안쪽 조인처럼 됨). 빠지면 안 되면 칼럼 뒤에 (+) 를 붙이세요."));
      }
    }
  }

  private static String stripAlias(String item) {
    String masked = SqlParts.mask(item);
    Matcher m = AS_ALIAS.matcher(masked.strip());
    if (!m.matches()) return item;
    String expr = m.group(1).strip();
    if (expr.isEmpty() || expr.endsWith(".")) return item;
    int start = masked.indexOf(masked.strip());
    return item.substring(start, start + m.end(1));
  }

  private static String key(String s) {
    return s.replaceAll("\\s+", "").replace("\"", "").toUpperCase(Locale.ROOT);
  }

  private static String last(String s) {
    return s.substring(s.lastIndexOf('.') + 1);
  }

  private static String norm(String ref) {
    return ref.replace("\"", "").toUpperCase(Locale.ROOT);
  }
}
