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
  /** 전체 비용(비용이 없으면 읽는 건수)이 이 아래면 작은 쿼리로 보고 곱하기 조인 말고는 짚지 않는다. 그 정도는 전체 읽기가 가장 빠르다. */
  static final long SMALL_PLAN_COST = 1000;
  /** 값이 몇 가지뿐인 칼럼(Y/N · 플래그). 인덱스를 만들어도 DB 가 거의 쓰지 않아 후보에서 뺀다. */
  private static final Pattern FEW_VALUES = Pattern.compile("(?i)(.*_)?(YN|FLAG|FLG|YN_CD|AT)$");
  /** IN 목록에도 INTERNAL_FUNCTION 이 찍힌다. 그때는 형 변환이 아니다. */
  private static final Pattern IN_LIST = Pattern.compile("(?i)^\\s*\"?\\)?\\s*(NOT\\s+)?IN\\b");

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
  /** PostgreSQL 형 변환 (col)::numeric 등. 칼럼과 값의 형이 달라 붙는다. */
  private static final Pattern PG_CAST = Pattern.compile("\\(" + COLUMN + "\\)::(\\w+)");
  /** varchar · char 칼럼을 글자와 비교하면 계획에 ::text 가 찍히지만 인덱스는 그대로 쓴다(같은 연산자족). 지적하지 않는다. */
  private static final Set<String> HARMLESS_CASTS = Set.of("text", "varchar", "bpchar", "character", "name");
  /** TO_CHAR(날짜칼럼, '형식') = '값' 은 범위 조건으로 바꿔 보일 수 있다. */
  private static final Pattern TO_CHAR_EQ = Pattern.compile("(?i)TO_CHAR\\(\\s*" + COLUMN + "\\s*,\\s*'([^']+)'\\s*\\)\\s*=\\s*'([^']+)'");
  private static final Pattern LEADING_WILDCARD = Pattern.compile("(?i)(\\bLIKE|~~\\*?)\\s*'%");
  /** 비교 연산 앞의 칼럼. 함수 안 칼럼은 WRAPPED 가 따로 잡는다. */
  private static final Pattern COMPARED = Pattern.compile("(?i)" + COLUMN + "\\s*(=|>=|<=|<>|!=|>|<|\\bLIKE\\b|~~|\\bBETWEEN\\b|\\bIN\\b)");
  private static final Set<String> NOT_COLUMNS = Set.of("AND", "OR", "NOT", "NULL", "IS", "TEXT", "NUMERIC", "INTEGER", "BIGINT");

  /**
   * 지적 목록. 헛경고를 줄이려고: 작은 쿼리(isSmall)는 곱하기 조인만, 그 밖은 그 단계가 무거울 때(비율 SHARE_WARNING 이상 또는 BIG_ROWS 건 이상)만 짚는다.
   * 실제 시간을 잰 계획(actual)은 비율을 비용 대신 실제 시간으로 잰다.
   * indexes 에 그 표가 있으면(읽은 표) 칼럼에 인덱스가 없을 때 「함수 때문에 인덱스를 못 씀」을 말하지 않는다. 표가 없으면(못 읽음) 말한다.
   */
  public static List<Finding> analyze(List<Step> steps, Map<String, List<Index>> indexes) {
    List<Finding> out = new ArrayList<>();
    if (steps.isEmpty()) return out;
    Map<Integer, List<Step>> children = children(steps);
    boolean actual = isActual(steps);
    boolean small = !actual && isSmall(steps);
    Map<Integer, Double> shares = shares(steps, children, actual);
    int leaves = 0;
    for (Step s : steps) if (!children.containsKey(s.id())) leaves++;
    for (Step s : steps) {
      if (s.kind() == Kind.CARTESIAN) out.add(new Finding(s.id(), Level.DANGER, "cartesian", List.of(), null));
      if (small) continue;
      double share = shares.getOrDefault(s.id(), 0.0);
      boolean heavy = share >= SHARE_WARNING || rowsOf(s) >= BIG_ROWS;
      int before = out.size();
      switch (s.kind()) {
        case FULL_SCAN -> fullScan(s, share, steps, children, indexes, actual, out);
        case INDEX_FULL, INDEX_SKIP -> {
          if (share >= SHARE_WARNING && !underAggregate(s, steps)) out.add(new Finding(s.id(), Level.WARNING, s.kind() == Kind.INDEX_SKIP ? "skip" : "indexfull", List.of(name(s)), null));
        }
        case SORT -> {
          if (share >= SHARE_WARNING || rowsOf(s) >= BIG_ROWS) out.add(new Finding(s.id(), level(share), "sort", List.of(rowsOf(s)), null));
        }
        case SUBQUERY_FILTER -> {
          if (heavy && children.getOrDefault(s.id(), List.of()).size() > 1) out.add(new Finding(s.id(), Level.WARNING, "subquery", List.of(), null));
        }
        default -> { }
      }
      if (heavy) {
        extra(s, out);
        predicates(s, share, indexes, out);
      }
      if (actual && share >= SHARE_DANGER) out.add(new Finding(s.id(), Level.DANGER, "time", List.of(percent(share)), null));
      misestimate(s, out);
      // 표를 하나만 읽는 쿼리는 비용이 그 한 곳에 몰리는 것이 당연하다. 읽는 곳이 둘 이상일 때만 짚는다.
      if (!actual && leaves > 1 && out.size() == before && share >= SHARE_DANGER && s.parentId() >= 0) {
        out.add(new Finding(s.id(), Level.WARNING, "cost", List.of(percent(share)), null));
      }
    }
    return out;
  }

  /** 실제 시간을 잰 계획인지(어느 단계든 실제 시간이 있음). */
  static boolean isActual(List<Step> steps) {
    for (Step s : steps) {
      if (s.elapsedMicros() != null) return true;
    }
    return false;
  }

  /** 뿌리 단계 비용(비용이 없으면 읽는 건수) 합이 SMALL_PLAN_COST 아래인 계획. 값을 모르면 작다고 보지 않는다. */
  public static boolean isSmall(List<Step> steps) {
    long total = 0;
    for (Step s : steps) {
      if (s.parentId() >= 0) continue;
      Long w = weight(s);
      if (w == null) return false;
      total += w;
    }
    return total < SMALL_PLAN_COST;
  }

  /** COUNT · 집계 바로 아래의 인덱스 전체 읽기는 인덱스만 훑는 빠른 방법이라 짚지 않는다. */
  private static boolean underAggregate(Step s, List<Step> steps) {
    Step parent = byId(steps, s.parentId());
    return parent != null && parent.operation().toUpperCase(Locale.ROOT).contains("AGGREGATE");
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
                               Map<String, List<Index>> indexes, boolean actual, List<Finding> out) {
    Loop loop = loopParent(s, steps, children);
    // 반복 수 × 안쪽 한 번 비용이 전체의 SHARE_WARNING 이상일 때만. 코드표처럼 작은 안쪽은 수백 번 읽어도 빠르다.
    if (loop != null && (actual ? share >= SHARE_WARNING : loopWork(s, loop) >= SHARE_WARNING * total(steps))) {
      out.add(new Finding(s.id(), Level.DANGER, "loopfull", List.of(name(s), loop.outerRows()), indexExample(s, candidates(s))));
      return;
    }
    boolean big = rowsOf(s) >= BIG_ROWS;
    if (share < SHARE_WARNING && !big) return;
    Level level = share >= SHARE_DANGER || (big && s.cost() == null) ? Level.DANGER : Level.WARNING;
    List<String> all = candidates(s);
    List<String> cols = new ArrayList<>();
    for (String col : all) if (!FEW_VALUES.matcher(col).matches()) cols.add(col);
    if (cols.isEmpty() && !all.isEmpty()) {
      out.add(new Finding(s.id(), level, "fullnarrow", List.of(name(s), String.join(", ", all)), null));
      return;
    }
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

  private static double loopWork(Step s, Loop loop) {
    Long once = weight(s);
    return once == null ? 0 : (double) once * loop.outerRows();
  }

  private static double total(List<Step> steps) {
    double t = 0;
    for (Step s : steps) {
      if (s.parentId() < 0) t += value(s, false);
    }
    return t;
  }

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
  private static void predicates(Step s, double share, Map<String, List<Index>> indexes, List<Finding> out) {
    String text = s.predicates();
    Set<String> seen = new LinkedHashSet<>();
    List<Index> known = indexes.get(upper(s.object()));
    Matcher m = WRAPPED.matcher(text);
    while (m.find()) {
      String fn = m.group(1).toUpperCase(Locale.ROOT);
      String col = upper(m.group(2));
      if (fn.equals("INTERNAL_FUNCTION") && IN_LIST.matcher(text.substring(m.end())).find()) continue;
      if (known != null && !indexedFirst(known, col)) continue; // 인덱스가 없는 칼럼이라 함수 탓이 아니다
      if (!seen.add(fn + "." + col)) continue;
      out.add(new Finding(s.id(), wrapLevel(s, share), "fn." + family(fn), List.of(col, fn), fnExample(fn, col, text, s)));
    }
    Matcher cast = PG_CAST.matcher(text);
    while (cast.find()) {
      if (HARMLESS_CASTS.contains(cast.group(2).toLowerCase(Locale.ROOT))) continue;
      if (known != null && !indexedFirst(known, upper(cast.group(1)))) continue;
      if (seen.add("CAST." + cast.group(1))) out.add(new Finding(s.id(), wrapLevel(s, share), "fn.convert", List.of(upper(cast.group(1)), "::"), null));
    }
    if (LEADING_WILDCARD.matcher(text).find()) out.add(new Finding(s.id(), wrapLevel(s, share), "likeprefix", List.of(), null));
  }

  /** 큰 전체 읽기(비용 SHARE_WARNING 이상 또는 BIG_ROWS 건 이상)에 걸린 함수 조건은 그 읽기의 원인이라 「심각」, 그 밖은 「주의」. */
  private static Level wrapLevel(Step s, double share) {
    return s.kind() == Kind.FULL_SCAN && (share >= SHARE_WARNING || rowsOf(s) >= BIG_ROWS) ? Level.DANGER : Level.WARNING;
  }

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
          if (upper(m.group(1)).equals(col)) {
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
            : "CREATE INDEX IX_" + upper(s.object()) + "_" + col + "_" + fn + " ON " + upper(s.object()) + " (" + fn + "(" + col + "))";
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
        : "EXEC DBMS_STATS.GATHER_TABLE_STATS('" + upper(s.owner()) + "', '" + upper(s.object()) + "')";
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

  private static boolean indexedFirst(List<Index> list, String col) {
    for (Index i : list) {
      if (!i.columns().isEmpty() && i.columns().get(0).equals(col)) return true;
    }
    return false;
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
    String table = (s.owner() == null || s.owner().isEmpty() ? "" : upper(s.owner()) + ".") + upper(s.object());
    return "CREATE INDEX IX_" + upper(s.object()) + "_" + cols.get(0) + " ON " + table + " (" + String.join(", ", cols) + ")";
  }

  private static Level level(double share) { return share >= SHARE_DANGER ? Level.DANGER : Level.WARNING; }

  private static long rowsOf(Step s) {
    if (s.actualRows() != null) return s.actualRows();
    return s.rows() == null ? 0 : s.rows();
  }

  /** 화면에 보일 대상 이름. 표 · 칼럼은 SQL 쓰는 규칙대로 대문자. */
  private static String name(Step s) { return s.object() == null || s.object().isEmpty() ? s.operation() : upper(s.object()); }

  private static String upper(String s) { return s == null ? "" : s.toUpperCase(Locale.ROOT); }

  private static final Pattern NOOP_CAST = Pattern.compile("(?i)::(text|character varying|varchar|bpchar|name)\\b");
  /** 함수 호출이 아닌 괄호 하나로 감싼 이름: (use_yn) · ("E"."ID"). */
  private static final Pattern WRAPPED_NAME = Pattern.compile("(?<![\\w$#])\\((\\w+(?:\\.\\w+)?)\\)");
  private static final Pattern QUOTED_STRING = Pattern.compile("'(?:[^']|'')*'");

  /**
   * 계획의 조건 글을 사람이 쓴 SQL 처럼 보이게 한다: 뜻 없는 글자 변환(::text 등)과 이름 따옴표 · 이름만 감싼 괄호를 빼고,
   * 따옴표 문자열 밖은 대문자로. 뜻이 있는 변환(::numeric 등)은 남긴다. 화면에만 쓰고 분석에는 원래 글을 쓴다.
   */
  public static String readable(String predicate) {
    if (predicate == null || predicate.isBlank()) return predicate;
    String t = NOOP_CAST.matcher(predicate).replaceAll("").replace("\"", "");
    String prev;
    do {
      prev = t;
      t = WRAPPED_NAME.matcher(t).replaceAll("$1");
    } while (!t.equals(prev));
    StringBuilder out = new StringBuilder();
    Matcher q = QUOTED_STRING.matcher(t);
    int at = 0;
    while (q.find()) {
      out.append(t.substring(at, q.start()).toUpperCase(Locale.ROOT)).append(q.group());
      at = q.end();
    }
    return out.append(t.substring(at).toUpperCase(Locale.ROOT)).toString();
  }

  static long percent(double share) { return Math.round(share * 100); }
}
