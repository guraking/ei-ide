package ei.db.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 연결 모양 바꾸기: 옛 방식(FROM 쉼표 · WHERE 조건 · (+)) ↔ 표준 방식(JOIN … ON). 맨 바깥 SELECT 의 첫 묶음만 바꾸고 나머지 글은 그대로 둔다.
 * IntelliJ 에 기대지 않는다. 바꿀 수 없는 꼴(FULL JOIN · USING · NATURAL)은 IllegalArgumentException(한국어 이유).
 */
public final class JoinStyleConvert {
  private JoinStyleConvert() {}

  public record Result(String sql, List<String> notes) {}

  private static final Pattern PLUS = Pattern.compile("\\s*\\(\\s*\\+\\s*\\)");
  private static final Pattern OR_IN = Pattern.compile("(?<![\\w$#])(OR|IN)(?![\\w$#])");
  private static final String AND = "\n   AND ";

  /** 옛 방식 → 표준 방식. 두 표를 잇는 조건은 뒤에 나온 표의 ON 으로, 한 표에만 (+) 를 단 조건(b.TYPE(+) = 'A')은 그 표의 ON 으로 옮긴다. */
  public static Result toAnsi(String sql) {
    List<SqlParts.Part> parts = SqlParts.mainBlock(SqlParts.split(sql));
    SqlParts.Part from = SqlParts.find(parts, "FROM");
    if (from == null) throw new IllegalArgumentException("FROM 이 없습니다.");
    List<SqlParts.Source> sources = SqlParts.sources(from.body());
    for (SqlParts.Source s : sources) if (s.join() != null) throw new IllegalArgumentException("이미 표준 방식(JOIN)입니다.");
    if (sources.size() < 2) throw new IllegalArgumentException("표가 하나라 바꿀 연결이 없습니다.");
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < sources.size(); i++) index.put(sources.get(i).ref().replace("\"", "").toUpperCase(Locale.ROOT), i);

    SqlParts.Part where = SqlParts.find(parts, "WHERE");
    List<List<String>> on = new ArrayList<>();
    String[] kind = new String[sources.size()];
    for (int i = 0; i < sources.size(); i++) on.add(new ArrayList<>());
    List<String> rest = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    if (where != null) {
      for (String cond : SqlParts.splitAnd(where.body())) {
        List<Integer> refs = new ArrayList<>();
        for (String r : SqlParts.refs(cond)) if (index.containsKey(r)) refs.add(index.get(r));
        boolean plus = SqlParts.mask(cond).contains("(+)") || PLUS.matcher(SqlParts.mask(cond)).find();
        if (refs.size() == 2) {
          int later = Math.max(refs.get(0), refs.get(1));
          on.get(later).add(clean(cond));
          if (plus) {
            String side = plusOwner(cond, index);
            String k = side == null || index.get(side) == later ? "LEFT" : "RIGHT";
            kind[later] = kind[later] == null || kind[later].equals(k) || kind[later].equals("INNER") ? k : "FULL";
          } else if (kind[later] == null) {
            kind[later] = "INNER";
          }
        } else if (refs.size() == 1 && plus && refs.get(0) > 0) {
          on.get(refs.get(0)).add(clean(cond));
          kind[refs.get(0)] = "LEFT";
        } else {
          rest.add(SqlParts.oneLine(cond).equals("1 = 1") ? "1 = 1" : cond.strip());
          if (refs.size() > 2) notes.add("세 표 이상을 한꺼번에 잇는 조건은 WHERE 에 남겼습니다: " + SqlParts.oneLine(cond));
        }
      }
    }
    StringBuilder sb = new StringBuilder(sql.substring(0, from.start()).stripTrailing()).append("\n  FROM ").append(sources.get(0).text()).append('\n');
    for (int i = 1; i < sources.size(); i++) {
      String k = kind[i];
      if ("FULL".equals(k)) throw new IllegalArgumentException(sources.get(i).ref() + " 에 (+) 가 양쪽에 있어 바꿀 수 없습니다.");
      if (on.get(i).isEmpty()) {
        sb.append("  CROSS JOIN ").append(sources.get(i).text()).append('\n');
        notes.add(sources.get(i).ref() + " 는 잇는 조건이 없어 CROSS JOIN(모든 조합)으로 두었습니다.");
        continue;
      }
      sb.append("LEFT".equals(k) ? "  LEFT OUTER JOIN " : "RIGHT".equals(k) ? "  RIGHT OUTER JOIN " : "  INNER JOIN ").append(sources.get(i).text()).append('\n');
      sb.append("    ON ").append(String.join(AND, on.get(i))).append('\n');
    }
    rest.removeIf("1 = 1"::equals);
    if (!rest.isEmpty() || where != null) sb.append(" WHERE 1 = 1").append(rest.isEmpty() ? "" : AND + String.join(AND, rest)).append('\n');
    return new Result(tail(sb, sql, where != null ? where.end() : from.end()), notes);
  }

  /** 표준 방식 → 옛 방식. LEFT JOIN 표의 ON 조건은 그 표 칼럼마다 (+) 를 붙여 WHERE 로, RIGHT JOIN 은 상대 표 칼럼에 붙인다. */
  public static Result toOld(String sql) {
    List<SqlParts.Part> parts = SqlParts.mainBlock(SqlParts.split(sql));
    SqlParts.Part from = SqlParts.find(parts, "FROM");
    if (from == null) throw new IllegalArgumentException("FROM 이 없습니다.");
    List<SqlParts.Source> sources = SqlParts.sources(from.body());
    boolean any = false;
    for (SqlParts.Source s : sources) any |= s.join() != null;
    if (!any) throw new IllegalArgumentException("이미 옛 방식(쉼표)입니다.");
    List<String> items = new ArrayList<>();
    List<String> conds = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    for (SqlParts.Source s : sources) {
      String upper = SqlParts.mask(s.text()).toUpperCase(Locale.ROOT);
      if (upper.matches("(?s).*\\bUSING\\s*\\(.*") && s.on() == null) throw new IllegalArgumentException("JOIN … USING 은 바꿀 수 없습니다. ON 으로 바꾼 뒤 다시 하세요.");
      if ("FULL".equals(s.join())) throw new IllegalArgumentException("FULL OUTER JOIN 은 옛 방식으로 쓸 수 없습니다.");
      items.add(s.text().substring(0, s.on() == null ? s.text().length() : itemEnd(s)).strip());
      if (s.on() == null) continue;
      for (String c : SqlParts.splitAnd(s.on())) {
        String cond = c.strip();
        if ("LEFT".equals(s.join())) cond = plus(cond, Set.of(norm(s.ref())), true);
        if ("RIGHT".equals(s.join())) cond = plus(cond, Set.of(norm(s.ref())), false);
        if (("LEFT".equals(s.join()) || "RIGHT".equals(s.join())) && OR_IN.matcher(SqlParts.mask(cond).toUpperCase(Locale.ROOT)).find()) {
          notes.add("OR · IN 이 든 바깥 조인 조건은 (+) 로 쓸 수 없을 수 있습니다(ORA-01719): " + SqlParts.oneLine(cond));
        }
        conds.add(cond);
      }
    }
    SqlParts.Part where = SqlParts.find(parts, "WHERE");
    if (where != null) {
      for (String c : SqlParts.splitAnd(where.body())) if (!SqlParts.oneLine(c).equals("1 = 1")) conds.add(c.strip());
    }
    StringBuilder sb = new StringBuilder(sql.substring(0, from.start()).stripTrailing()).append("\n  FROM ").append(String.join("\n     , ", items)).append('\n');
    sb.append(" WHERE 1 = 1");
    for (String c : conds) sb.append(AND).append(c);
    sb.append('\n');
    return new Result(tail(sb, sql, where != null ? where.end() : from.end()), notes);
  }

  /** 조건에서 (+) 를 단 칼럼의 별칭(대문자). 못 찾으면 null. */
  private static String plusOwner(String cond, Map<String, Integer> index) {
    String masked = SqlParts.mask(cond);
    Matcher m = Pattern.compile("([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\.\\s*([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\(\\s*\\+\\s*\\)").matcher(masked);
    while (m.find()) {
      String a = norm(m.group(1));
      if (index.containsKey(a)) return a;
    }
    return null;
  }

  /** (+) 를 뺀 조건(문자열 안은 건드리지 않는다). */
  private static String clean(String cond) {
    String masked = SqlParts.mask(cond);
    Matcher m = PLUS.matcher(masked);
    StringBuilder out = new StringBuilder();
    int last = 0;
    while (m.find()) {
      out.append(cond, last, m.start());
      last = m.end();
    }
    out.append(cond.substring(last));
    return out.toString().strip();
  }

  /** inside 면 aliases 의 칼럼에, 아니면 그 밖 별칭의 칼럼에 (+) 를 붙인다. */
  private static String plus(String cond, Set<String> aliases, boolean inside) {
    String masked = SqlParts.mask(cond);
    Matcher m = Pattern.compile("(?<![\\w$#.\"])([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\.\\s*([A-Za-z_][\\w$#]*|\"[^\"]+\")(?!\\s*\\()").matcher(masked);
    StringBuilder out = new StringBuilder();
    int last = 0;
    while (m.find()) {
      boolean mine = aliases.contains(norm(m.group(1)));
      if (mine != inside) continue;
      out.append(cond, last, m.end()).append("(+)");
      last = m.end();
    }
    out.append(cond.substring(last));
    return out.toString();
  }

  /** 표 글에서 ON 앞까지. */
  private static int itemEnd(SqlParts.Source s) {
    String upper = SqlParts.mask(s.text()).toUpperCase(Locale.ROOT);
    Matcher m = Pattern.compile("(?<![\\w$#])ON(?![\\w$#])").matcher(upper);
    int[] depth = SqlParts.depth(SqlParts.mask(s.text()));
    while (m.find()) if (depth[m.start()] == 0) return m.start();
    return s.text().length();
  }

  private static String tail(StringBuilder sb, String sql, int end) {
    String rest = sql.substring(Math.min(end, sql.length())).strip();
    if (rest.equals(";")) rest = "";
    String head = sb.toString().stripTrailing();
    return rest.isEmpty() ? head : head + "\n " + rest;
  }

  private static String norm(String alias) {
    return alias.replace("\"", "").toUpperCase(Locale.ROOT);
  }
}
