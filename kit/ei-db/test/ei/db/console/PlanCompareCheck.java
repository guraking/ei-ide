package ei.db.console;

import ei.db.console.PlanAdvisor.Step;
import ei.db.console.PlanCompare.Access;
import ei.db.console.PlanCompare.Mark;
import ei.db.console.PlanCompare.Note;
import ei.db.console.PlanCompare.NoteKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 실행 계획 전후 비교 자체 점검. */
public class PlanCompareCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static final List<String> ORACLE_COLS = List.of("ID", "PARENT_ID", "DEPTH", "OPERATION", "OPTIONS", "OBJECT_OWNER", "OBJECT_NAME",
      "COST", "CARDINALITY", "ACCESS_PREDICATES", "FILTER_PREDICATES", "LAST_STARTS", "LAST_OUTPUT_ROWS", "LAST_CR_BUFFER_GETS", "LAST_ELAPSED_TIME");

  static BigDecimal n(long v) { return BigDecimal.valueOf(v); }

  /** id, 부모, 깊이, 단계, 옵션, 대상, 비용, 건수. */
  static List<Object> row(int id, Integer parent, int depth, String op, String options, String object, long cost, long rows) {
    return Arrays.asList(n(id), parent == null ? null : n(parent), n(depth), op, options, object == null ? null : "APP", object, n(cost), n(rows),
        null, null, null, null, null, null);
  }

  @SafeVarargs
  static List<Step> oracle(List<Object>... rows) { return PlanParse.oracle(ORACLE_COLS, Arrays.asList(rows)); }

  /** 「종류 대상 앞방법/앞인덱스 → 뒤방법/뒤인덱스」 꼴로 줄인 바뀐 점. */
  static List<String> notes(PlanCompare.Result r) {
    List<String> out = new ArrayList<>();
    for (Note x : r.notes()) {
      out.add(x.kind() + " " + x.object() + " " + how(x.before()) + " -> " + how(x.after()));
    }
    return out;
  }

  static String how(PlanCompare.How h) {
    if (h == null) return "-";
    return h.access() == Access.OTHER ? h.raw() : h.access() + (h.index().isEmpty() ? "" : "/" + h.index());
  }

  public static void main(String[] args) {
    // ---- Oracle: 전체 읽기 → 인덱스 범위 읽기, 조인 방식 바뀜
    List<Step> before = oracle(
        row(0, null, 0, "SELECT STATEMENT", null, null, 1000, 50),
        row(1, 0, 1, "HASH JOIN", null, null, 1000, 50),
        row(2, 1, 2, "TABLE ACCESS", "FULL", "TB_ORDER", 900, 100000),
        row(3, 1, 2, "TABLE ACCESS", "FULL", "TB_USER", 90, 1000));
    List<Step> after = oracle(
        row(0, null, 0, "SELECT STATEMENT", null, null, 630, 50),
        row(1, 0, 1, "NESTED LOOPS", null, null, 630, 50),
        row(2, 1, 2, "TABLE ACCESS", "BY INDEX ROWID BATCHED", "TB_ORDER", 500, 50),
        row(3, 2, 3, "INDEX", "RANGE SCAN", "IDX_ORDER_01", 3, 50),
        row(4, 1, 2, "TABLE ACCESS", "FULL", "TB_USER", 90, 1000));
    PlanCompare.Result r = PlanCompare.compare(before, after);
    eq(notes(r), List.of("JOIN  HASH JOIN -> NESTED LOOPS", "ACCESS TB_ORDER FULL -> INDEX_RANGE/IDX_ORDER_01"));
    eq(r.before().get(0), Mark.SAME);
    eq(r.before().get(1), Mark.CHANGED);
    eq(r.before().get(2), Mark.CHANGED);
    eq(r.before().get(3), Mark.SAME);
    eq(r.after().get(1), Mark.CHANGED);
    eq(r.after().get(2), Mark.CHANGED);
    // 표 단계의 읽는 방법에 적힌 인덱스 단계는 표에는 「추가」, 바뀐 점에는 따로 적지 않는다.
    eq(r.after().get(3), Mark.ADDED);
    eq(r.after().get(4), Mark.SAME);
    eq(PlanCompare.totalCost(before), 1000L);
    eq(PlanCompare.totalCost(after), 630L);
    eq(PlanCompare.percent(1000L, 630L), -37L);
    eq(PlanCompare.percent(100L, 150L), 50L);
    eq(PlanCompare.percent(100L, 100L), 0L);
    eq(PlanCompare.percent(0L, 10L), null);
    eq(PlanCompare.percent(null, 10L), null);
    eq(PlanCompare.totalRows(after), 50L);
    eq(PlanCompare.buffers(after), null);

    // 거꾸로 견주면 인덱스 단계는 「삭제」.
    r = PlanCompare.compare(after, before);
    eq(notes(r), List.of("JOIN  NESTED LOOPS -> HASH JOIN", "ACCESS TB_ORDER INDEX_RANGE/IDX_ORDER_01 -> FULL"));
    eq(r.before().get(3), Mark.REMOVED);

    // 같은 계획: 달라진 것 없음.
    r = PlanCompare.compare(before, before);
    eq(r.notes(), List.of());
    for (Mark m : r.before().values()) eq(m, Mark.SAME);
    for (Mark m : r.after().values()) eq(m, Mark.SAME);

    // 쓰는 인덱스만 바뀜(표 단계 이름은 같다).
    List<Step> idx1 = oracle(
        row(0, null, 0, "SELECT STATEMENT", null, null, 10, 5),
        row(1, 0, 1, "TABLE ACCESS", "BY INDEX ROWID", "TB_ORDER", 10, 5),
        row(2, 1, 2, "INDEX", "RANGE SCAN", "IDX_ORDER_01", 2, 5));
    List<Step> idx2 = oracle(
        row(0, null, 0, "SELECT STATEMENT", null, null, 8, 5),
        row(1, 0, 1, "TABLE ACCESS", "BY INDEX ROWID", "TB_ORDER", 8, 5),
        row(2, 1, 2, "INDEX", "UNIQUE SCAN", "PK_ORDER", 1, 1));
    r = PlanCompare.compare(idx1, idx2);
    eq(notes(r), List.of("ACCESS TB_ORDER INDEX_RANGE/IDX_ORDER_01 -> INDEX_UNIQUE/PK_ORDER"));
    eq(r.before().get(2), Mark.REMOVED);
    eq(r.after().get(2), Mark.ADDED);

    // 같은 인덱스를 읽는 방법이 바뀜 · 새 정렬 단계 · 없어진 표.
    List<Step> a1 = oracle(
        row(0, null, 0, "SELECT STATEMENT", null, null, 10, 5),
        row(1, 0, 1, "INDEX", "RANGE SCAN", "IDX_A", 10, 5),
        row(2, 0, 1, "TABLE ACCESS", "FULL", "TB_GONE", 10, 5));
    List<Step> a2 = oracle(
        row(0, null, 0, "SELECT STATEMENT", null, null, 10, 5),
        row(1, 0, 1, "SORT", "ORDER BY", null, 10, 5),
        row(2, 1, 2, "INDEX", "FULL SCAN", "IDX_A", 10, 5));
    r = PlanCompare.compare(a1, a2);
    eq(notes(r), List.of("ACCESS IDX_A INDEX_RANGE/IDX_A -> INDEX_FULL/IDX_A", "ADDED  - -> SORT ORDER BY", "REMOVED TB_GONE FULL -> -"));

    // 같은 표가 두 번 나오면 깊이가 가까운 것끼리.
    List<Step> s1 = oracle(
        row(0, null, 0, "SELECT STATEMENT", null, null, 10, 5),
        row(1, 0, 1, "TABLE ACCESS", "FULL", "T", 10, 5),
        row(2, 0, 1, "FILTER", null, null, 10, 5),
        row(3, 2, 2, "TABLE ACCESS", "BY INDEX ROWID", "T", 10, 5),
        row(4, 3, 3, "INDEX", "UNIQUE SCAN", "PK_T", 10, 5));
    List<Step> s2 = oracle(
        row(0, null, 0, "SELECT STATEMENT", null, null, 10, 5),
        row(1, 0, 1, "FILTER", null, null, 10, 5),
        row(2, 1, 2, "TABLE ACCESS", "BY INDEX ROWID", "T", 10, 5),
        row(3, 2, 3, "INDEX", "UNIQUE SCAN", "PK_T", 10, 5),
        row(4, 0, 1, "TABLE ACCESS", "FULL", "T", 10, 5));
    r = PlanCompare.compare(s1, s2);
    eq(r.notes(), List.of());

    // ---- PostgreSQL: Seq Scan → Index Scan, 별칭만 다른 것은 같은 것, 비트맵
    List<Step> pg1 = PlanParse.postgres(List.of(
        "Hash Join  (cost=1.00..500.00 rows=10 width=8)",
        "  Hash Cond: (o.user_id = u.id)",
        "  ->  Seq Scan on tb_order o  (cost=0.00..400.00 rows=10000 width=4)",
        "        Filter: (status = 'A'::text)",
        "  ->  Hash  (cost=1.00..1.00 rows=10 width=4)",
        "        ->  Seq Scan on tb_user u  (cost=0.00..1.00 rows=10 width=4)"));
    List<Step> pg2 = PlanParse.postgres(List.of(
        "Nested Loop  (cost=0.50..120.00 rows=10 width=8)",
        "  ->  Seq Scan on tb_user usr  (cost=0.00..1.00 rows=10 width=4)",
        "  ->  Index Scan using idx_order_user on tb_order o  (cost=0.50..11.00 rows=1 width=4)",
        "        Index Cond: (user_id = usr.id)"));
    r = PlanCompare.compare(pg1, pg2);
    eq(notes(r), List.of("JOIN  Hash Join -> Nested Loop", "ACCESS TB_ORDER FULL -> INDEX/IDX_ORDER_USER", "REMOVED  Hash -> -"));
    eq(PlanCompare.totalCost(pg1), 500L);
    eq(PlanCompare.percent(PlanCompare.totalCost(pg1), PlanCompare.totalCost(pg2)), -76L);
    List<Step> pg3 = PlanParse.postgres(List.of(
        "Bitmap Heap Scan on tb_order o  (cost=4.00..20.00 rows=5 width=4)",
        "  Recheck Cond: (user_id = 1)",
        "  ->  Bitmap Index Scan on idx_order_user  (cost=0.00..4.00 rows=5 width=0)",
        "        Index Cond: (user_id = 1)"));
    List<Step> pg4 = PlanParse.postgres(List.of("Index Only Scan using idx_order_cover on tb_order o  (cost=0.30..8.00 rows=5 width=4)"));
    r = PlanCompare.compare(pg3, pg4);
    eq(notes(r), List.of("ACCESS TB_ORDER BITMAP/IDX_ORDER_USER -> INDEX_ONLY/IDX_ORDER_COVER"));
    eq(r.before().get(1), Mark.REMOVED);

    // ---- MySQL: 비용을 모른다 · 단계는 평평하다
    List<String> myCols = List.of("id", "select_type", "table", "type", "key", "rows", "Extra");
    List<Step> my1 = PlanParse.mysql(myCols, List.of(Arrays.asList(1, "SIMPLE", "t", "ALL", null, 1000, "Using where")));
    List<Step> my2 = PlanParse.mysql(myCols, List.of(Arrays.asList(1, "SIMPLE", "t", "ref", "idx_t", 10, "")));
    r = PlanCompare.compare(my1, my2);
    eq(notes(r), List.of("ACCESS T FULL -> type=ref · idx_t"));
    eq(PlanCompare.totalCost(my1), null);
    eq(PlanCompare.totalRows(my1), 1000L);
    eq(PlanCompare.percent(PlanCompare.totalRows(my1), PlanCompare.totalRows(my2)), -99L);

    // ---- 빈 계획 · 실제 값
    r = PlanCompare.compare(List.of(), before);
    eq(r.notes().size(), 4);
    eq(r.notes().get(0).kind(), NoteKind.ADDED);
    r = PlanCompare.compare(before, List.of());
    eq(r.notes().get(0).kind(), NoteKind.REMOVED);
    eq(PlanCompare.compare(List.of(), List.of()).notes(), List.of());
    eq(PlanCompare.totalCost(List.of()), null);
    List<Object> measured = Arrays.asList(n(0), null, n(0), "SELECT STATEMENT", null, null, null, n(5), n(1), null, null, n(1), n(1), n(120), n(3400));
    List<Object> child = Arrays.asList(n(1), n(0), n(1), "TABLE ACCESS", "FULL", "APP", "T", n(5), n(1), null, null, n(1), n(1), n(100), n(3000));
    eq(PlanCompare.buffers(oracle(measured, child)), 120L);

    System.out.println("PlanCompareCheck 통과");
  }
}
