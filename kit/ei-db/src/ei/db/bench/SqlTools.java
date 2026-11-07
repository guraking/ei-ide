package ei.db.bench;

import ei.db.DbKind;
import ei.db.gen.Names;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 작업대 「도구」: IN 목록 만들기 · SELECT → 건수 / UPDATE / DELETE 바꾸기 · WITH 조각 나누기. IntelliJ 에 기대지 않는다.
 * 바꿀 수 없으면 IllegalArgumentException(화면에 보일 한국어 이유).
 */
public final class SqlTools {
  private SqlTools() {}

  /** Oracle 은 IN 목록 하나에 값을 1000 개까지만 받는다(ORA-01795). 여러 칼럼 짝 목록에는 이 한도가 없다. */
  public static final int ORACLE_IN_LIMIT = 1000;
  /** IN 목록 한 줄에 놓는 값 수. */
  private static final int PER_LINE = 10;
  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

  /** IN 목록 결과: 글, 값 개수, 뺀 중복 수. */
  public record InResult(String sql, int count, int duplicates) {}

  /**
   * 붙여 넣은 값(엑셀에서 복사한 한 칸 또는 여러 칸)으로 IN 조건. 칸은 탭으로 나누고, 탭이 없고 한 줄이면 쉼표로 나눈다.
   * 칸이 둘 이상이면 튜플 IN. 모든 값이 숫자인 칸은 따옴표 없이 쓴다. 같은 값(짝)은 한 번만 넣는다.
   * columns 가 비면 COL(여러 칸이면 COL1, COL2 …). Oracle 의 한 칼럼 목록이 한도를 넘으면 OR 로 나눈다.
   */
  public static InResult inList(String pasted, List<String> columns, DbKind kind) {
    List<String> lines = new ArrayList<>();
    for (String line : pasted.split("\\R")) if (!line.isBlank()) lines.add(line);
    if (lines.isEmpty()) throw new IllegalArgumentException("붙여 넣은 값이 없습니다.");
    boolean tabs = false;
    for (String line : lines) tabs |= line.contains("\t");
    List<List<String>> rows = new ArrayList<>();
    if (!tabs && lines.size() == 1 && lines.get(0).contains(",")) {
      for (String v : lines.get(0).split(",")) if (!v.isBlank()) rows.add(List.of(v.strip()));
    } else {
      for (String line : lines) {
        List<String> cells = new ArrayList<>();
        for (String v : line.split("\t", -1)) cells.add(v.strip());
        rows.add(cells);
      }
    }
    int width = 0;
    for (List<String> r : rows) width = Math.max(width, r.size());
    boolean[] numeric = new boolean[width];
    for (int c = 0; c < width; c++) {
      numeric[c] = true;
      for (List<String> r : rows) numeric[c] &= c < r.size() && NUMBER.matcher(r.get(c)).matches();
    }
    Set<String> seen = new LinkedHashSet<>();
    int dup = 0;
    for (List<String> r : rows) {
      List<String> lits = new ArrayList<>();
      for (int c = 0; c < width; c++) {
        String v = c < r.size() ? r.get(c) : "";
        lits.add(numeric[c] ? v : v.isEmpty() ? "NULL" : QueryBuilder.quote(v));
      }
      String item = width == 1 ? lits.get(0) : "(" + String.join(", ", lits) + ")";
      if (!seen.add(item)) dup++;
    }
    List<String> names = new ArrayList<>();
    for (int c = 0; c < width; c++) {
      String n = c < columns.size() && !columns.get(c).isBlank() ? columns.get(c).strip() : width == 1 ? "COL" : "COL" + (c + 1);
      names.add(n);
    }
    List<String> items = new ArrayList<>(seen);
    String head = width == 1 ? names.get(0) : "(" + String.join(", ", names) + ")";
    String sql;
    if (width == 1 && kind == DbKind.ORACLE && items.size() > ORACLE_IN_LIMIT) {
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < items.size(); i += ORACLE_IN_LIMIT) {
        parts.add(head + " IN (" + wrap(items.subList(i, Math.min(items.size(), i + ORACLE_IN_LIMIT))) + ")");
      }
      sql = "(" + String.join("\n    OR ", parts) + ")";
    } else {
      sql = head + " IN (" + wrap(items) + ")";
    }
    String note = "-- 값 " + items.size() + "개" + (dup > 0 ? " (같은 값 " + dup + "개 뺌)" : "")
        + (width == 1 && kind == DbKind.ORACLE && items.size() > ORACLE_IN_LIMIT ? ", Oracle 한도 " + ORACLE_IN_LIMIT + "개씩 나눔" : "") + "\n";
    return new InResult(note + sql, items.size(), dup);
  }

  private static String wrap(List<String> items) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(i % PER_LINE == 0 ? ",\n        " : ", ");
      sb.append(items.get(i));
    }
    return sb.toString();
  }

  /** 정렬을 뺀 SELECT 를 감싸 건수를 센다. SELECT · WITH 가 아니면 예외. */
  public static String countOf(String select) {
    List<SqlParts.Part> parts = SqlParts.split(select);
    if (parts.isEmpty() || !(parts.get(0).key().equals("SELECT") || parts.get(0).key().equals("WITH"))) {
      throw new IllegalArgumentException("SELECT 문에서만 건수를 셀 수 있습니다.");
    }
    String body = select.substring(parts.get(0).start(), parts.get(parts.size() - 1).end());
    SqlParts.Part order = null;
    for (SqlParts.Part p : parts) if (p.key().startsWith("ORDER")) order = p;
    if (order != null && order == parts.get(parts.size() - 1)) body = select.substring(parts.get(0).start(), order.start());
    return "SELECT COUNT(*) AS CNT\n  FROM (\n" + QueryBuilder.indent(body.strip(), "        ") + "\n       ) q";
  }

  /** SELECT → 바꾸기 결과: 문장, 대상 표, 알림(없으면 빈 글). */
  public record Dml(String sql, String table, String note) {}

  /**
   * 조건을 확인한 SELECT 를 같은 조건의 DELETE 로. 대상은 FROM 의 첫 표. 다른 표가 있으면 그 표들과 조건을 EXISTS 안에 넣는다.
   * 표준 JOIN 은 옛 방식으로 바꾼 뒤 다룬다. 바깥 조인 · 묶기 · 집합 연산 · 계층 · 서브쿼리 FROM 은 뜻이 바뀌어 바꾸지 않는다.
   */
  public static Dml toDelete(String select, DbKind kind) {
    Target t = target(select);
    if (t.others.isEmpty()) {
      return new Dml("DELETE\n  FROM " + t.source.text() + (t.where.isEmpty() ? "" : "\n WHERE " + String.join("\n   AND ", t.where)), t.name(), t.note);
    }
    return new Dml("DELETE\n  FROM " + t.source.text() + "\n WHERE EXISTS (\n" + exists(t) + "       )", t.name(), t.note);
  }

  /** 같은 조건의 UPDATE. SET 은 SELECT 에 고른 대상 표 칼럼마다 이름 자리(:칼럼 · #{칼럼}). 칼럼을 안 골랐으면 고칠 칼럼 자리를 주석으로 둔다. */
  public static Dml toUpdate(String select, DbKind kind, QueryBuilder.Bind bind) {
    Target t = target(select);
    List<String> set = new ArrayList<>();
    String ref = t.source.ref().toUpperCase(Locale.ROOT);
    for (String item : SqlParts.splitCommas(t.selectList)) {
      Matcher m = COLUMN_ITEM.matcher(item.strip());
      if (!m.matches()) continue;
      String owner = m.group(1) == null ? null : m.group(1).replace("\"", "").toUpperCase(Locale.ROOT);
      if (owner != null && !owner.equals(ref)) continue;
      if (owner == null && !t.others.isEmpty()) continue;
      String col = m.group(2);
      String name = Names.camel(col.replace("\"", ""));
      set.add(col + " = " + (bind == QueryBuilder.Bind.MYBATIS ? "#{" + name + "}" : ":" + name));
    }
    String setText = set.isEmpty() ? "칼럼 = 값  -- 고칠 칼럼을 쓰세요" : String.join("\n     , ", set);
    String where = t.others.isEmpty()
        ? (t.where.isEmpty() ? "" : "\n WHERE " + String.join("\n   AND ", t.where))
        : "\n WHERE EXISTS (\n" + exists(t) + "       )";
    String note = t.note + (set.isEmpty() ? (t.note.isEmpty() ? "" : " ") + "SELECT 에 " + t.source.ref() + " 의 칼럼을 고르면 SET 칸을 채워 줍니다." : "");
    return new Dml("UPDATE " + t.source.text() + "\n   SET " + setText + where, t.name(), note);
  }

  private static final Pattern COLUMN_ITEM = Pattern.compile("(?:([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*\\.\\s*)?([A-Za-z_][\\w$#]*|\"[^\"]+\")");

  private record Target(SqlParts.Source source, List<SqlParts.Source> others, List<String> where, String selectList, String note) {
    String name() { return SqlParts.simpleName(source.name()); }
  }

  private static Target target(String select) {
    String sql = select;
    List<SqlParts.Part> parts = SqlParts.split(sql);
    if (parts.isEmpty() || !parts.get(0).key().equals("SELECT")) throw new IllegalArgumentException("WITH 없는 SELECT 문에서만 바꿀 수 있습니다.");
    for (SqlParts.Part p : parts) {
      switch (p.key()) {
        case "SELECT", "FROM", "WHERE", "ORDER BY", "ORDER SIBLINGS BY" -> { }
        default -> throw new IllegalArgumentException(p.key() + " 가 있는 문장은 행 단위로 바꿀 수 없어 바꾸지 않았습니다.");
      }
    }
    SqlParts.Part from = SqlParts.find(parts, "FROM");
    if (from == null) throw new IllegalArgumentException("FROM 이 없습니다.");
    List<SqlParts.Source> sources = SqlParts.sources(from.body());
    String note = "";
    boolean ansi = false;
    for (SqlParts.Source s : sources) ansi |= s.join() != null;
    if (ansi) {
      sql = JoinStyleConvert.toOld(sql).sql();
      parts = SqlParts.split(sql);
      from = SqlParts.find(parts, "FROM");
      sources = SqlParts.sources(from.body());
    }
    if (SqlParts.mask(sql).contains("(+)")) throw new IllegalArgumentException("바깥 조인((+) · LEFT JOIN)이 있으면 뜻이 바뀌어 바꾸지 않았습니다.");
    if (sources.get(0).subquery()) throw new IllegalArgumentException("첫 표가 서브쿼리라 바꿀 수 없습니다.");
    SqlParts.Part where = SqlParts.find(parts, "WHERE");
    List<String> conds = new ArrayList<>();
    if (where != null) {
      for (String c : SqlParts.splitAnd(where.body())) if (!c.replaceAll("\\s+", "").equals("1=1")) conds.add(SqlParts.oneLine(c));
    }
    List<SqlParts.Source> others = sources.subList(1, sources.size());
    if (!others.isEmpty()) note = "대상은 첫 표 " + SqlParts.simpleName(sources.get(0).name()) + " 입니다. 다른 표는 EXISTS 안의 조건으로 옮겼습니다.";
    if (conds.isEmpty()) note = (note.isEmpty() ? "" : note + " ") + "조건이 없어 표 전체가 바뀝니다.";
    return new Target(sources.get(0), others, conds, SqlParts.find(parts, "SELECT").body(), note);
  }

  private static String exists(Target t) {
    List<String> from = new ArrayList<>();
    for (SqlParts.Source s : t.others) from.add(s.text());
    StringBuilder sb = new StringBuilder("        SELECT 1\n          FROM ").append(String.join("\n             , ", from)).append('\n');
    for (int i = 0; i < t.where.size(); i++) sb.append(i == 0 ? "         WHERE " : "           AND ").append(t.where.get(i)).append('\n');
    return sb.toString();
  }

  /** WITH 의 단계 하나: 이름과 원문 안 본문 범위(괄호 안). */
  public record Cte(String name, int bodyStart, int bodyEnd) {}

  private static final Pattern CTE_HEAD = Pattern.compile("\\s*(?:RECURSIVE\\s+)?([A-Za-z_][\\w$#]*|\"[^\"]+\")\\s*(\\([^)]*\\))?\\s*AS\\s*(?:(?:NOT\\s+)?MATERIALIZED\\s*)?\\(", Pattern.CASE_INSENSITIVE);

  /** WITH 단계들(앞에서부터). WITH 로 시작하지 않으면 빈 목록. */
  public static List<Cte> ctes(String sql) {
    List<SqlParts.Part> parts = SqlParts.split(sql);
    List<Cte> out = new ArrayList<>();
    if (parts.isEmpty() || !parts.get(0).key().equals("WITH")) return out;
    String masked = SqlParts.mask(sql);
    int pos = parts.get(0).bodyStart();
    int end = parts.get(0).end();
    while (pos < end) {
      Matcher m = CTE_HEAD.matcher(masked);
      m.region(pos, end);
      if (!m.lookingAt()) break;
      int open = m.end() - 1;
      int close = SqlParts.matching(masked, open);
      if (close < 0) break;
      out.add(new Cte(sql.substring(m.start(1), m.end(1)), open + 1, close));
      pos = close + 1;
      while (pos < end && (Character.isWhitespace(masked.charAt(pos)) || masked.charAt(pos) == ',')) pos++;
    }
    return out;
  }

  /** index 번째 단계까지만 남기고 그 단계 결과를 보는 문장. 뒤 단계 · 마지막 SELECT 는 뺀다. */
  public static String upTo(String sql, int index) {
    List<Cte> ctes = ctes(sql);
    if (index < 0 || index >= ctes.size()) throw new IllegalArgumentException("WITH 단계가 아닙니다.");
    String head = sql.substring(0, ctes.get(index).bodyEnd() + 1);
    return head.strip() + "\nSELECT\n       *\n  FROM " + ctes.get(index).name();
  }
}
