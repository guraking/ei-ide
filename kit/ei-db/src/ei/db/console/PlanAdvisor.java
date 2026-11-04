package ei.db.console;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실행 계획 단계에서 확연히 느린 곳을 찾아 원인 · 고칠 법을 붙인다. IntelliJ 클래스 없이 돈다(PlanAdvisorCheck).
 * 문구는 묶음 키(plan.&lt;rule&gt;.title · cause · fix)로만 내고 화면이 EiDbMessages 로 바꾼다.
 * cost · elapsed 는 Oracle · PostgreSQL 처럼 자식까지 더한 누적 값이어야 한다. 자기 몫 = 누적 − 자식 누적(음수면 0).
 */
public final class PlanAdvisor {
  private PlanAdvisor() {}

  /** 한 단계가 이 비율 이상을 차지하면 「심각」. */
  static final double SHARE_DANGER = 0.5;
  /** 이 비율 이상이면 「주의」. 그 아래 전체 읽기 · 정렬은 작은 표로 보고 넘긴다. */
  static final double SHARE_WARNING = 0.2;
  /** 예상 건수가 이 이상이면 큰 표 · 큰 정렬로 본다. */
  static final long BIG_ROWS = 10_000;
  /** 중첩 반복(NESTED LOOPS) 바깥 건수가 이 이상인데 안쪽이 전체 읽기면 「심각」. */
  static final long LOOP_OUTER_ROWS = 100;
  /** 예상 · 실제 건수가 이 배수 이상 다르고 큰 쪽이 MISESTIMATE_MIN_ROWS 이상이면 통계가 낡았다고 본다. */
  static final double MISESTIMATE_RATIO = 10;
  static final long MISESTIMATE_MIN_ROWS = 100;

  public enum Kind { FULL_SCAN, INDEX_FULL, INDEX_SKIP, CARTESIAN, NESTED_LOOP, SORT, SUBQUERY_FILTER, OTHER }

  public enum Level { DANGER, WARNING }

  /**
   * 계획 한 단계. id 는 0부터, parentId 는 뿌리면 -1. 모르는 숫자는 null.
   * starts · actualRows · buffers · elapsedMicros 는 실제 시간을 잰 때만 있다(actualRows 는 반복 전체의 합).
   * extra 는 MySQL 의 Extra 칸(Using filesort 등), 없으면 빈 글.
   */
  public record Step(int id, int parentId, int depth, String operation, Kind kind, String owner, String object,
                     Long cost, Long rows, String access, String filter, String extra,
                     Long starts, Long actualRows, Long buffers, Long elapsedMicros) {
    String predicates() { return (access == null ? "" : access) + " " + (filter == null ? "" : filter); }
  }

  /** 한 가지 지적. rule 이 문구 키, args 는 문구 자리값, example 은 고친 SQL · 명령 예시(없으면 null). */
  public record Finding(int stepId, Level level, String rule, List<Object> args, String example) {}

  /** 표 이름(대문자) → 그 표 인덱스들, 인덱스는 칼럼 이름(대문자) 순서 목록. */
  public record Index(String name, List<String> columns) {}

  // Oracle "A"."COL" · "COL", PostgreSQL a.col · col. 함수 안 첫 인자가 칼럼이면 인덱스를 못 탄다.
  private static final String COLUMN = "(?:\"?\\w+\"?\\.)?\"?(\\w+)\"?";
  private static final Pattern WRAPPED = Pattern.compile(
      "(?i)\\b(TO_CHAR|TO_NUMBER|TO_DATE|NVL|NVL2|COALESCE|UPPER|LOWER|SUBSTR|SUBSTRING|TRUNC|DATE_TRUNC|TRIM|LTRIM|RTRIM|REPLACE|DECODE|INTERNAL_FUNCTION)\\(\\s*" + COLUMN);
  /** PostgreSQL 형 변환 (col)::text. 칼럼과 값의 형이 달라 붙는다. */
  private static final Pattern PG_CAST = Pattern.compile("\\(" + COLUMN + "\\)::\\w+");
  /** TO_CHAR(날짜칼럼, '형식') = '값' 은 범위 조건으로 바꿔 보일 수 있다. */
  private static final Pattern TO_CHAR_EQ = Pattern.compile("(?i)TO_CHAR\\(\\s*" + COLUMN + "\\s*,\\s*'([^']+)'\\s*\\)\\s*=\\s*'([^']+)'");
  private static final Pattern LEADING_WILDCARD = Pattern.compile("(?i)(\\bLIKE|~~\\*?)\\s*'%");
  /** 비교 연산 앞의 칼럼. 함수 안 칼럼은 WRAPPED 가 따로 잡는다. */
  private static final Pattern COMPARED = Pattern.compile("(?i)" + COLUMN + "\\s*(=|>=|<=|<>|!=|>|<|\\bLIKE\\b|~~|\\bBETWEEN\\b|\\bIN\\b)");
  private static final Set<String> NOT_COLUMNS = Set.of("AND", "OR", "NOT", "NULL", "IS", "TEXT", "NUMERIC", "INTEGER", "BIGINT");

  public static List<Finding> analyze(List<Step> steps, Map<String, List<Index>> indexes) {
    List<Finding> out = new ArrayList<>();
    if (steps.isEmpty()) return out;
    Map<Integer, List<Step>> children = children(steps);
    Map<Integer, Double> costShare = shares(steps, children, false);
    Map<Integer, Double> timeShare = shares(steps, children, true);
    for (Step s : steps) {
      double share = costShare.getOrDefault(s.id(), 0.0);
      int before = out.size();
      switch (s.kind()) {
        case FULL_SCAN -> fullScan(s, share, steps, children, indexes, out);
        case CARTESIAN -> out.add(new Finding(s.id(), Level.DANGER, "cartesian", List.of(), null));
        case INDEX_FULL, INDEX_SKIP -> {
          if (share >= SHARE_WARNING) out.add(new Finding(s.id(), Level.WARNING, s.kind() == Kind.INDEX_SKIP ? "skip" : "indexfull", List.of(name(s)), null));
        }
        case SORT -> {
          if (share >= SHARE_WARNING || rowsOf(s) >= BIG_ROWS) out.add(new Finding(s.id(), level(share), "sort", List.of(rowsOf(s)), null));
        }
        case SUBQUERY_FILTER -> {
          if (children.getOrDefault(s.id(), List.of()).size() > 1) out.add(new Finding(s.id(), Level.WARNING, "subquery", List.of(), null));
        }
        default -> { }
      }
      extra(s, out);
      predicates(s, out);
      Double time = timeShare.get(s.id());
      if (time != null && time >= SHARE_DANGER) out.add(new Finding(s.id(), Level.DANGER, "time", List.of(percent(time)), null));
      misestimate(s, out);
      if (out.size() == before && share >= SHARE_DANGER && s.parentId() >= 0) {
        out.add(new Finding(s.id(), Level.WARNING, "cost", List.of(percent(share)), null));
      }
    }
    return out;
  }

  /** 단계 id → 가장 무거운 지적 수준. 지적 없는 단계는 없음. */
  public static Map<Integer, Level> levels(List<Finding> findings) {
    Map<Integer, Level> out = new HashMap<>();
    for (Finding f : findings) {
      Level now = out.get(f.stepId());
      if (now == null || f.level() == Level.DANGER) out.put(f.stepId(), f.level());
    }
    return out;
  }

  /** 단계마다 자기 몫(누적 − 자식 누적)이 뿌리 누적에서 차지하는 비율. 값이 없거나 뿌리가 0 이면 그 단계는 빠진다. */
  static Map<Integer, Double> shares(List<Step> steps, Map<Integer, List<Step>> children, boolean time) {
    Map<Integer, Double> out = new HashMap<>();
    double total = 0;
    for (Step s : steps) {
      if (s.parentId() < 0) total += value(s, time);
    }
    if (total <= 0) return out;
    for (Step s : steps) {
      Long v = time ? s.elapsedMicros() : weight(s);
      if (v == null) continue;
      double own = v;
      for (Step c : children.getOrDefault(s.id(), List.of())) own -= value(c, time);
      out.put(s.id(), Math.max(0, own) / total);
    }
    return out;
  }

  /** 비용이 없으면(MySQL) 읽는 건수를 무게로 쓴다. MySQL 단계는 서로 자식이 아니라 뿌리 합이 전체가 된다. */
  private static Long weight(Step s) { return s.cost() != null ? s.cost() : s.rows(); }

  private static double value(Step s, boolean time) {
    Long v = time ? s.elapsedMicros() : weight(s);
    return v == null ? 0 : v;
  }

  static Map<Integer, List<Step>> children(List<Step> steps) {
    Map<Integer, List<Step>> out = new HashMap<>();
    for (Step s : steps) {
      if (s.parentId() >= 0) out.computeIfAbsent(s.parentId(), k -> new ArrayList<>()).add(s);
    }
    return out;
  }

  private static void fullScan(Step s, double share, List<Step> steps, Map<Integer, List<Step>> children,
                               Map<String, List<Index>> indexes, List<Finding> out) {
    Loop loop = loopParent(s, steps, children);
    if (loop != null) {
      out.add(new Finding(s.id(), Level.DANGER, "loopfull", List.of(name(s), loop.outerRows()), indexExample(s, candidates(s))));
      return;
    }
    boolean big = rowsOf(s) >= BIG_ROWS;
    if (share < SHARE_WARNING && !big) return;
    Level level = share >= SHARE_DANGER || (big && s.cost() == null) ? Level.DANGER : Level.WARNING;
    List<String> cols = candidates(s);
    if (cols.isEmpty()) {
      // 조건 글을 주지 않는 DB(MySQL)는 조건이 없다고 단정할 수 없다.
      out.add(new Finding(s.id(), level, s.cost() == null ? "fullunknown" : "fullnowhere", List.of(name(s)), null));
      return;
    }
    Index existing = leading(indexes.get(upper(s.object())), cols);
    if (existing != null) {
      out.add(new Finding(s.id(), level, "fullunused", List.of(name(s), existing.name(), String.join(", ", existing.columns())), null));
    } else {
      out.add(new Finding(s.id(), level, "fullnoindex", List.of(name(s), String.join(", ", cols)), indexExample(s, cols)));
    }
  }

  private record Loop(long outerRows) {}

  /**
   * s 가 중첩 반복의 안쪽(두 번째 자식)이고 바깥이 LOOP_OUTER_ROWS 건 이상이면 그 바깥 건수. 실제 반복 횟수가 있으면 그것을 쓴다.
   * MySQL 처럼 단계가 평평하면 해당 없음.
   */
  private static Loop loopParent(Step s, List<Step> steps, Map<Integer, List<Step>> children) {
    if (s.starts() != null) return s.starts() >= LOOP_OUTER_ROWS ? new Loop(s.starts()) : null;
    Step parent = byId(steps, s.parentId());
    if (parent == null || parent.kind() != Kind.NESTED_LOOP) return null;
    List<Step> kids = children.getOrDefault(parent.id(), List.of());
    if (kids.size() < 2 || kids.get(1).id() != s.id()) return null;
    long outer = rowsOf(kids.get(0));
    return outer >= LOOP_OUTER_ROWS ? new Loop(outer) : null;
  }

  private static Step byId(List<Step> steps, int id) {
    for (Step s : steps) {
      if (s.id() == id) return s;
    }
    return null;
  }

  /** MySQL Extra 칸. 정렬 · 임시 표 · 조인 버퍼(조인 칼럼에 인덱스 없음). */
  private static void extra(Step s, List<Finding> out) {
    String e = s.extra() == null ? "" : s.extra();
    if (e.contains("Using filesort") || e.contains("Using temporary")) out.add(new Finding(s.id(), level(0), "sort", List.of(rowsOf(s)), null));
    if (e.contains("Using join buffer")) out.add(new Finding(s.id(), Level.WARNING, "joinbuffer", List.of(name(s)), null));
  }

  /** 조건 글에서 인덱스를 막는 모양: 칼럼에 씌운 함수 · 형 변환, 앞에 % 가 붙은 LIKE. 같은 칼럼 · 함수는 한 번만. */
  private static void predicates(Step s, List<Finding> out) {
    String text = s.predicates();
    Set<String> seen = new LinkedHashSet<>();
    Matcher m = WRAPPED.matcher(text);
    while (m.find()) {
      String fn = m.group(1).toUpperCase(Locale.ROOT);
      String col = m.group(2);
      if (!seen.add(fn + "." + col)) continue;
      out.add(new Finding(s.id(), wrapLevel(s), "fn." + family(fn), List.of(col, fn), fnExample(fn, col, text, s)));
    }
    Matcher cast = PG_CAST.matcher(text);
    while (cast.find()) {
      if (seen.add("CAST." + cast.group(1))) out.add(new Finding(s.id(), wrapLevel(s), "fn.convert", List.of(cast.group(1), "::"), null));
    }
    if (LEADING_WILDCARD.matcher(text).find()) out.add(new Finding(s.id(), wrapLevel(s), "likeprefix", List.of(), null));
  }

  /** 전체 읽기 단계에서 걸린 함수 조건은 그 읽기의 원인이라 「심각」, 그 밖은 「주의」. */
  private static Level wrapLevel(Step s) { return s.kind() == Kind.FULL_SCAN ? Level.DANGER : Level.WARNING; }

  /** 함수 이름 → 문구 묶음. 형 변환(INTERNAL_FUNCTION · TO_NUMBER)은 칼럼과 값의 형이 달라 DB 가 몰래 씌운 것이다. */
  static String family(String fn) {
    return switch (fn) {
      case "TO_CHAR", "TRUNC", "DATE_TRUNC" -> "date";
      case "TO_NUMBER", "INTERNAL_FUNCTION", "TO_DATE" -> "convert";
      case "NVL", "NVL2", "COALESCE" -> "nvl";
      case "UPPER", "LOWER" -> "case";
      case "SUBSTR", "SUBSTRING" -> "substr";
      default -> "other";
    };
  }

  private static String fnExample(String fn, String col, String text, Step s) {
    switch (family(fn)) {
      case "date" -> {
        Matcher m = TO_CHAR_EQ.matcher(text);
        while (m.find()) {
          if (m.group(1).equals(col)) {
            String start = "TO_DATE('" + m.group(3) + "', '" + m.group(2) + "')";
            return col + " >= " + start + "\n   AND " + col + " < " + start + " + 1";
          }
        }
        return col + " >= TO_DATE(:fromDate, 'YYYYMMDD')\n   AND " + col + " < TO_DATE(:toDate, 'YYYYMMDD') + 1";
      }
      case "nvl" -> {
        return "(" + col + " = :value OR " + col + " IS NULL)";
      }
      case "substr" -> {
        return col + " LIKE :prefix || '%'";
      }
      case "case" -> {
        return s.object() == null || s.object().isEmpty() ? null
            : "CREATE INDEX IX_" + s.object() + "_" + col + "_" + fn + " ON " + s.object() + " (" + fn + "(" + col + "))";
      }
      default -> {
        return null;
      }
    }
  }

  /**
   * 실제 건수(반복 합)와 예상 건수 × 반복이 MISESTIMATE_RATIO 배 넘게 다르면 통계가 낡은 것으로 본다.
   * 통계는 표 · 인덱스 단위로 모으므로 대상이 있는 단계만 본다(조인 단계의 어긋남은 그 아래 표 단계에서 드러난다).
   */
  private static void misestimate(Step s, List<Finding> out) {
    if (s.actualRows() == null || s.rows() == null || s.object() == null || s.object().isEmpty()) return;
    long starts = s.starts() == null || s.starts() < 1 ? 1 : s.starts();
    double expected = Math.max(1, s.rows() * (double) starts);
    double actual = Math.max(1, s.actualRows());
    double ratio = Math.max(expected / actual, actual / expected);
    if (ratio < MISESTIMATE_RATIO || Math.max(expected, actual) < MISESTIMATE_MIN_ROWS) return;
    String example = s.operation().startsWith("INDEX") ? null
        : "EXEC DBMS_STATS.GATHER_TABLE_STATS('" + (s.owner() == null ? "" : s.owner()) + "', '" + s.object() + "')";
    out.add(new Finding(s.id(), Level.WARNING, "estimate", List.of((long) expected, (long) actual), example));
  }

  /** 조건에 나온 칼럼, = 조건 칼럼을 앞에. 함수 · 형 변환 안의 칼럼도 넣는다(고치고 나면 인덱스가 필요하다). */
  static List<String> candidates(Step s) {
    Set<String> eq = new LinkedHashSet<>();
    Set<String> other = new LinkedHashSet<>();
    Matcher m = COMPARED.matcher(s.predicates());
    while (m.find()) {
      String col = m.group(1).toUpperCase(Locale.ROOT);
      if (NOT_COLUMNS.contains(col) || Character.isDigit(col.charAt(0))) continue;
      (m.group(2).equals("=") ? eq : other).add(col);
    }
    Matcher w = WRAPPED.matcher(s.predicates());
    while (w.find()) other.add(w.group(2).toUpperCase(Locale.ROOT));
    Matcher cast = PG_CAST.matcher(s.predicates());
    while (cast.find()) other.add(cast.group(1).toUpperCase(Locale.ROOT));
    other.removeAll(eq);
    List<String> out = new ArrayList<>(eq);
    out.addAll(other);
    return out;
  }

  /** 후보 칼럼 중 하나로 시작하는 기존 인덱스. 없으면 null. */
  private static Index leading(List<Index> list, List<String> cols) {
    if (list == null) return null;
    for (Index i : list) {
      if (!i.columns().isEmpty() && cols.contains(i.columns().get(0))) return i;
    }
    return null;
  }

  private static String indexExample(Step s, List<String> cols) {
    if (cols.isEmpty() || s.object() == null) return null;
    String table = (s.owner() == null || s.owner().isEmpty() ? "" : s.owner() + ".") + s.object();
    return "CREATE INDEX IX_" + s.object() + "_" + cols.get(0) + " ON " + table + " (" + String.join(", ", cols) + ")";
  }

  private static Level level(double share) { return share >= SHARE_DANGER ? Level.DANGER : Level.WARNING; }

  private static long rowsOf(Step s) {
    if (s.actualRows() != null) return s.actualRows();
    return s.rows() == null ? 0 : s.rows();
  }

  private static String name(Step s) { return s.object() == null || s.object().isEmpty() ? s.operation() : s.object(); }

  private static String upper(String s) { return s == null ? "" : s.toUpperCase(Locale.ROOT); }

  static long percent(double share) { return Math.round(share * 100); }
}
