package ei.db.console;

import ei.db.console.PlanAdvisor.Step;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 두 실행 계획(앞 · 뒤)의 단계를 맞춰 달라진 곳을 찾는다. IntelliJ 클래스 없이 돈다(PlanCompareCheck).
 * 맞추는 규칙(트리 전체를 견주지 않는다):
 * <ol>
 * <li>대상(표 · 인덱스 이름, 대소문자 무시)이 같은 단계끼리 짝. 같은 대상이 여럿이면 깊이가 가장 가까운 것, 같으면 앞의 것.</li>
 * <li>대상 없는 단계는 단계 이름이 똑같은 것끼리 같은 방법으로 짝.</li>
 * <li>남은 조인 단계끼리 깊이가 가까운 것을 짝지어 「조인 방식 바뀜」.</li>
 * <li>그래도 남으면 앞에만 있는 것은 없어짐, 뒤에만 있는 것은 새로 생김.</li>
 * </ol>
 * 짝의 단계 이름이나 읽는 방법(쓰는 인덱스 포함)이 다르면 「바뀜」.
 */
final class PlanCompare {
  private PlanCompare() {}

  enum Mark { SAME, CHANGED, ADDED, REMOVED }

  /** 표 · 인덱스를 읽는 방법. OTHER 는 읽는 단계가 아니거나 모르는 꼴(raw 를 그대로 보인다). */
  enum Access { FULL, INDEX_RANGE, INDEX_UNIQUE, INDEX_FULL, INDEX_FAST_FULL, INDEX_SKIP, INDEX, INDEX_ONLY, BITMAP, OTHER }

  /** 단계가 대상을 읽는 방법. index 는 쓰는 인덱스 이름(대문자, 없으면 빈 글), raw 는 단계 이름(PostgreSQL 의 「on 표」 꼬리는 뗀 것). */
  record How(Access access, String index, String raw) {
    boolean same(How o) { return access == o.access && index.equalsIgnoreCase(o.index) && (access != Access.OTHER || raw.equalsIgnoreCase(o.raw)); }
  }

  enum NoteKind { ACCESS, JOIN, ADDED, REMOVED }

  /** 「바뀐 점」 한 줄. object 는 대상 이름(대문자, 없으면 빈 글). ADDED 는 after 만, REMOVED 는 before 만 있다. */
  record Note(NoteKind kind, String object, How before, How after) {}

  /** 단계 id → 표시(앞 계획 · 뒤 계획 따로)와 바뀐 점 목록(바뀐 것 → 새로 생긴 것 → 없어진 것, 저마다 계획의 단계 차례). */
  record Result(Map<Integer, Mark> before, Map<Integer, Mark> after, List<Note> notes) {}

  private static final Pattern PG_USING = Pattern.compile("\\busing\\s++(\\S++)");
  private static final Pattern PG_ON_TAIL = Pattern.compile("\\s+(?:using\\s++\\S++\\s++)?on\\s.*+$");
  private static final String ORACLE_TABLE_ACCESS = "TABLE ACCESS";
  private static final String ORACLE_INDEX = "INDEX";

  static Result compare(List<Step> before, List<Step> after) {
    Map<Integer, Mark> marksBefore = new HashMap<>();
    Map<Integer, Mark> marksAfter = new HashMap<>();
    // 뒤 계획 단계 id → 짝인 앞 계획 단계.
    Map<Integer, Step> pairOf = new HashMap<>();
    Set<Integer> joinPairs = new HashSet<>();
    Set<Integer> used = new HashSet<>();
    for (int pass = 0; pass < 3; pass++) {
      for (Step b : before) {
        if (marksBefore.containsKey(b.id())) continue;
        String key = key(b, pass);
        if (key == null) continue;
        Step best = null;
        for (Step a : after) {
          if (used.contains(a.id()) || !key.equals(key(a, pass))) continue;
          if (best == null || Math.abs(a.depth() - b.depth()) < Math.abs(best.depth() - b.depth())) best = a;
        }
        if (best == null) continue;
        used.add(best.id());
        pairOf.put(best.id(), b);
        if (pass == 2) joinPairs.add(best.id());
        // 표시는 아래에서 읽는 방법까지 견주어 정한다.
        marksBefore.put(b.id(), Mark.SAME);
      }
    }
    Set<Integer> childIndexBefore = new HashSet<>();
    Set<Integer> childIndexAfter = new HashSet<>();
    List<Note> notes = new ArrayList<>();
    for (Step a : after) {
      Step b = pairOf.get(a.id());
      if (b == null) {
        marksAfter.put(a.id(), Mark.ADDED);
        continue;
      }
      How hb = how(b, before, childIndexBefore);
      How ha = how(a, after, childIndexAfter);
      boolean changed = !hb.raw().equalsIgnoreCase(ha.raw()) || !hb.same(ha);
      marksBefore.put(b.id(), changed ? Mark.CHANGED : Mark.SAME);
      marksAfter.put(a.id(), changed ? Mark.CHANGED : Mark.SAME);
      if (changed) notes.add(new Note(joinPairs.contains(a.id()) ? NoteKind.JOIN : NoteKind.ACCESS, name(a), hb, ha));
    }
    // 표 단계의 「읽는 방법」에 이미 적힌 인덱스 단계는 따로 적지 않는다(표에는 추가 · 삭제로 보인다).
    for (Step a : after) {
      if (marksAfter.get(a.id()) == Mark.ADDED && !childIndexAfter.contains(a.id())) notes.add(new Note(NoteKind.ADDED, name(a), null, how(a, after, new HashSet<>())));
    }
    for (Step b : before) {
      if (marksBefore.containsKey(b.id())) continue;
      marksBefore.put(b.id(), Mark.REMOVED);
      if (!childIndexBefore.contains(b.id())) notes.add(new Note(NoteKind.REMOVED, name(b), how(b, before, new HashSet<>()), null));
    }
    return new Result(marksBefore, marksAfter, notes);
  }

  /** 맞출 때 쓰는 값. 0: 대상 이름, 1: 대상 없는 단계의 이름, 2: 대상 없는 조인 단계. 그 차례에 해당하지 않으면 null. */
  private static String key(Step s, int pass) {
    boolean hasObject = s.object() != null && !s.object().isEmpty();
    if (pass == 0) return hasObject ? s.object().toUpperCase(Locale.ROOT) : null;
    if (hasObject) return null;
    if (pass == 1) return s.operation().toUpperCase(Locale.ROOT);
    return isJoin(s) ? "JOIN" : null;
  }

  static boolean isJoin(Step s) {
    String op = s.operation().toUpperCase(Locale.ROOT);
    return op.contains("JOIN") || op.startsWith("NESTED LOOP");
  }

  private static String name(Step s) { return s.object() == null ? "" : s.object().toUpperCase(Locale.ROOT); }

  /**
   * 단계가 대상을 읽는 방법. Oracle 의 「TABLE ACCESS BY … ROWID」 와 PostgreSQL 의 「Bitmap Heap Scan」 은 바로 아래 인덱스 단계에서
   * 인덱스 이름 · 방법을 가져오고 그 단계 id 를 usedChildren 에 넣는다.
   */
  static How how(Step s, List<Step> steps, Set<Integer> usedChildren) {
    // PostgreSQL 단계 이름의 「on 표 별칭」 꼬리는 뗀다(별칭만 바뀐 것은 바뀐 것이 아니다).
    String op = PG_ON_TAIL.matcher(s.operation()).replaceFirst("");
    String upper = s.operation().toUpperCase(Locale.ROOT);
    if (upper.startsWith(ORACLE_TABLE_ACCESS)) {
      if (upper.contains("FULL")) return new How(Access.FULL, "", op);
      for (Step child : steps) {
        if (child.parentId() != s.id() || !child.operation().toUpperCase(Locale.ROOT).startsWith(ORACLE_INDEX)) continue;
        usedChildren.add(child.id());
        return new How(oracleIndex(child.operation().toUpperCase(Locale.ROOT)), name(child), op);
      }
      return new How(Access.OTHER, "", op);
    }
    if (upper.startsWith("SEQ SCAN") || upper.startsWith("PARALLEL SEQ SCAN")) return new How(Access.FULL, "", op);
    if (upper.startsWith("INDEX ONLY SCAN") || upper.startsWith("PARALLEL INDEX ONLY SCAN")) return new How(Access.INDEX_ONLY, pgIndex(s.operation()), op);
    if (upper.startsWith("INDEX SCAN") || upper.startsWith("PARALLEL INDEX SCAN")) return new How(Access.INDEX, pgIndex(s.operation()), op);
    if (upper.startsWith("BITMAP HEAP SCAN") || upper.startsWith("PARALLEL BITMAP HEAP SCAN")) {
      // 비트맵 인덱스 단계는 BitmapAnd · BitmapOr 아래에 있을 수도 있어 자손에서 처음 것을 쓴다.
      Set<Integer> under = new HashSet<>();
      under.add(s.id());
      for (Step child : steps) {
        if (!under.contains(child.parentId())) continue;
        under.add(child.id());
        if (!child.operation().toUpperCase(Locale.ROOT).startsWith("BITMAP INDEX SCAN")) continue;
        usedChildren.add(child.id());
        return new How(Access.BITMAP, name(child), op);
      }
      return new How(Access.BITMAP, "", op);
    }
    // MySQL: 「type=ALL」 · 「type=ref · 인덱스」.
    if (upper.startsWith("TYPE=ALL")) return new How(Access.FULL, "", op);
    // Oracle 인덱스 단계(INDEX RANGE SCAN 등). PostgreSQL 의 「Index Scan」 은 위에서 걸렀다.
    if (upper.startsWith(ORACLE_INDEX + " ")) return new How(oracleIndex(upper), name(s), op);
    return new How(Access.OTHER, "", op);
  }

  private static Access oracleIndex(String upper) {
    if (upper.contains("FAST FULL")) return Access.INDEX_FAST_FULL;
    if (upper.contains("SKIP SCAN")) return Access.INDEX_SKIP;
    if (upper.contains("FULL SCAN")) return Access.INDEX_FULL;
    if (upper.contains("UNIQUE SCAN")) return Access.INDEX_UNIQUE;
    if (upper.contains("RANGE SCAN")) return Access.INDEX_RANGE;
    return Access.INDEX;
  }

  private static String pgIndex(String op) {
    Matcher m = PG_USING.matcher(op);
    return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "";
  }

  // ---------------------------------------------------------------- 요약 숫자

  /** 뿌리 단계 비용의 합. 비용을 모르는 뿌리가 있으면(MySQL) null. */
  static Long totalCost(List<Step> steps) {
    Long sum = null;
    for (Step s : steps) {
      if (s.parentId() >= 0) continue;
      if (s.cost() == null) return null;
      sum = (sum == null ? 0 : sum) + s.cost();
    }
    return sum;
  }

  /** 뿌리 단계 예상 건수의 합(MySQL 은 단계가 모두 뿌리라 읽는 건수 합). 모르는 뿌리가 있으면 null. */
  static Long totalRows(List<Step> steps) {
    Long sum = null;
    for (Step s : steps) {
      if (s.parentId() >= 0) continue;
      if (s.rows() == null) return null;
      sum = (sum == null ? 0 : sum) + s.rows();
    }
    return sum;
  }

  /** 읽은 블록(누적 값이라 가장 큰 것이 전체). 잰 값이 없으면 null. */
  static Long buffers(List<Step> steps) {
    Long max = null;
    for (Step s : steps) {
      if (s.buffers() != null && (max == null || s.buffers() > max)) max = s.buffers();
    }
    return max;
  }

  /** 앞 → 뒤 변화율(%). 음수면 줄었다. 한쪽을 모르거나 앞이 0 이하면 null. */
  static Long percent(Long before, Long after) {
    if (before == null || after == null || before <= 0) return null;
    return Math.round((after - before) * 100.0 / before);
  }
}
