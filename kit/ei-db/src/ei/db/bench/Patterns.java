package ei.db.bench;

import ei.db.DbKind;
import ei.db.gen.Names;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 목적으로 만드는 SQL 뼈대(중복 찾기 · 그룹별 최신 · 계층 …)와 현황 · 집계 쿼리(건수 · 기간별 · 증감 · 비율 …).
 * 표 · 칼럼 이름만 채우면 DB 종류에 맞는 문장이 나온다. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 * 나누기는 NULLIF 로 0 을 막고, 합계 · 평균은 NULL 행을 빼고 센다는 것을 문장 위 주석으로 알린다.
 */
public final class Patterns {
  private Patterns() {}

  /** 채울 칸. 무늬마다 필요한 칸만 화면에 보인다. */
  public enum Field {
    TABLE("표"), TABLE2("비교할 표 · 다른 표"), KEYS("기준 칼럼 (같은 값이면 같은 것)"), COLS("함께 볼 · 바꿀 칼럼"),
    GROUP("묶을 칼럼 (예: 상태, 부서)"), DATE("날짜 칼럼"), VALUE("값 칼럼 (금액 · 수량)"), ORDER("최신 · 순서를 가를 칼럼"),
    PARENT("부모를 가리키는 칼럼"), STATUS("상태 칼럼"), STATUS_VALUES("상태 값들 (쉼표로)"), TOP_N("몇 개"), PERIOD("기간 단위");

    public final String label;

    Field(String label) { this.label = label; }
  }

  public enum Period {
    DAY("일별", "DD", "day", "YYYY-MM-DD"), WEEK("주별", "IW", "week", "YYYY-MM-DD"), MONTH("월별", "MM", "month", "YYYY-MM"), YEAR("연도별", "YYYY", "year", "YYYY");

    public final String label;
    final String oracleTrunc;
    final String postgresTrunc;
    final String format;

    Period(String label, String oracleTrunc, String postgresTrunc, String format) {
      this.label = label;
      this.oracleTrunc = oracleTrunc;
      this.postgresTrunc = postgresTrunc;
      this.format = format;
    }

    @Override public String toString() { return label; }
  }

  /** 무늬 하나. dashboard 는 「현황 · 집계」 탭에 보인다. */
  public enum Kind {
    DUPLICATES(false, "중복 찾기", "기준 칼럼 값이 같은 행이 2건 이상인 값과 건수를 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS)),
    DUPLICATE_ROWS(false, "중복 행 전체 보기", "중복된 행을 지우기 전에 한 줄씩 다 봅니다. 같은 값끼리 순번(RN)을 매겨 2 이상이 지울 대상입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.ORDER)),
    LATEST(false, "그룹별 최신 1건", "기준 칼럼마다 순서 칼럼이 가장 큰(최신) 행 하나만 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.ORDER)),
    EXISTS(false, "있는지 확인 (EXISTS)", "다른 표에 짝이 있는 행만 봅니다. 짝이 여러 개여도 한 번만 나옵니다(JOIN 과 달리 늘어나지 않음).", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    NOT_EXISTS(false, "없는 것 찾기 (NOT EXISTS)", "다른 표에 짝이 없는 행만 봅니다. NOT IN 과 달리 NULL 이 있어도 맞게 나옵니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    CTE(false, "WITH 로 나눠 쓰기", "긴 쿼리를 이름 붙인 단계(base → summary)로 나눕니다. 단계마다 따로 실행해 볼 수 있습니다(도구 → 조각 실행).", EnumSet.of(Field.TABLE, Field.GROUP)),
    HIERARCHY(false, "계층 (부모-자식) 조회", "메뉴 · 조직 · 부품 구성처럼 부모를 가리키는 칼럼으로 위에서 아래로 펼칩니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.PARENT)),
    RANK(false, "누적 합 · 순위", "줄마다 앞에서부터 쌓인 합과 값 순위를 붙입니다(행 수는 그대로).", EnumSet.of(Field.TABLE, Field.VALUE, Field.ORDER)),
    DIFF(false, "두 표 차이 비교", "두 표의 기준 칼럼 값 중 한쪽에만 있는 것을 봅니다(이관 · 백업 비교).", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    PIVOT(false, "세로 → 가로 바꾸기", "상태 값마다 칸을 하나씩 만들어 한 줄에 놓습니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.STATUS, Field.STATUS_VALUES, Field.VALUE)),
    UPSERT(false, "있으면 수정, 없으면 넣기", "기준 칼럼으로 찾아 있으면 UPDATE, 없으면 INSERT 합니다(Oracle MERGE, PostgreSQL ON CONFLICT).", EnumSet.of(Field.TABLE, Field.KEYS, Field.COLS)),

    COUNT_BY(true, "건수 (전체 · 그룹별)", "묶을 칼럼을 비우면 전체 건수, 넣으면 값마다 건수를 많은 순으로 봅니다.", EnumSet.of(Field.TABLE, Field.GROUP)),
    STATS(true, "합계 · 평균 · 최대 · 최소", "값 칼럼의 건수 · 합계 · 평균 · 최대 · 최소를 한 번에 봅니다. 묶을 칼럼을 넣으면 그룹마다.", EnumSet.of(Field.TABLE, Field.VALUE, Field.GROUP)),
    BY_PERIOD(true, "기간별 현황", "일 · 주 · 월 · 연도마다 건수(값 칼럼을 넣으면 합계도)를 봅니다. 시작일 · 끝일은 실행할 때 넣습니다.", EnumSet.of(Field.TABLE, Field.DATE, Field.PERIOD, Field.VALUE)),
    CHANGE(true, "전 기간 대비 증감", "기간마다 건수와 바로 앞 기간 대비 증감 · 증감률(%)을 봅니다(전월 · 전년 대비).", EnumSet.of(Field.TABLE, Field.DATE, Field.PERIOD)),
    RATIO(true, "비율 (전체 중 몇 %)", "묶을 칼럼 값마다 건수와 전체 중 차지하는 비율(%)을 봅니다.", EnumSet.of(Field.TABLE, Field.GROUP)),
    CUMULATIVE(true, "기간별 누적", "기간마다 건수와 처음부터 쌓인 누적 건수를 봅니다.", EnumSet.of(Field.TABLE, Field.DATE, Field.PERIOD)),
    TOP_N(true, "순위 · 상위 N 개", "묶을 칼럼 값마다 값 칼럼 합계(값 칼럼이 비면 건수)로 순위를 매겨 상위 N 개만 봅니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.VALUE, Field.TOP_N)),
    STATUS_ROW(true, "상태별 건수 한 줄 (대시보드 카드)", "전체 · 상태 값마다 건수를 한 줄에 칸으로 놓습니다. 대시보드 카드 숫자에 바로 씁니다.", EnumSet.of(Field.TABLE, Field.STATUS, Field.STATUS_VALUES)),
    ROLLUP(true, "합계 줄 붙이기 (ROLLUP)", "그룹별 건수 · 합계 아래에 「합계」 줄을 하나 더 붙입니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.VALUE)),
    DATE_FILL(true, "0 건인 날짜도 빠짐없이", "기간 안의 모든 날짜를 만들고 건수를 붙입니다. 행이 없는 날은 0 으로 나옵니다(그래프용).", EnumSet.of(Field.TABLE, Field.DATE));

    public final boolean dashboard;
    public final String label;
    public final String description;
    public final Set<Field> fields;

    Kind(boolean dashboard, String label, String description, Set<Field> fields) {
      this.dashboard = dashboard;
      this.label = label;
      this.description = description;
      this.fields = fields;
    }

    @Override public String toString() { return label; }
  }

  /** 채운 값. 칼럼 목록은 쉼표로 나눈 이름, 빈 칸은 빈 글. */
  public record Fill(DbKind kind, QueryBuilder.JoinStyle style, QueryBuilder.Bind bind, String table, String table2, List<String> keys,
                     List<String> cols, String group, String date, String value, String order, String parent, String status,
                     List<String> statusValues, int topN, Period period) {}

  private static final Pattern PLAIN_ALIAS = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
  private static final String FROM_DATE = "fromDt";
  private static final String TO_DATE = "toDt";
  private static final String TOTAL_LABEL = "합계";

  /** 비어서 만들 수 없는 칸(화면에 「채워 주세요」 로 보인다). 다 채웠으면 빈 목록. 값 칼럼 · 묶을 칼럼 등 비어도 되는 칸은 넣지 않는다. */
  public static List<Field> missing(Kind k, Fill f) {
    List<Field> out = new ArrayList<>();
    if (blank(f.table())) out.add(Field.TABLE);
    if (k.fields.contains(Field.TABLE2) && blank(f.table2())) out.add(Field.TABLE2);
    if (k.fields.contains(Field.KEYS) && f.keys().isEmpty()) out.add(Field.KEYS);
    if (k.fields.contains(Field.COLS) && f.cols().isEmpty()) out.add(Field.COLS);
    if (k.fields.contains(Field.DATE) && blank(f.date())) out.add(Field.DATE);
    if (k.fields.contains(Field.ORDER) && blank(f.order())) out.add(Field.ORDER);
    if (k.fields.contains(Field.PARENT) && blank(f.parent())) out.add(Field.PARENT);
    if (k.fields.contains(Field.STATUS) && blank(f.status())) out.add(Field.STATUS);
    if (k.fields.contains(Field.STATUS_VALUES) && f.statusValues().isEmpty()) out.add(Field.STATUS_VALUES);
    if ((k == Kind.RANK) && blank(f.value())) out.add(Field.VALUE);
    if ((k == Kind.CTE || k == Kind.RATIO || k == Kind.TOP_N || k == Kind.ROLLUP || k == Kind.PIVOT) && blank(f.group())) out.add(Field.GROUP);
    return out;
  }

  /** 문장. missing 이 비지 않으면 IllegalArgumentException. */
  public static String render(Kind k, Fill f) {
    if (!missing(k, f).isEmpty()) throw new IllegalArgumentException("채울 칸이 남았습니다.");
    DbKind db = f.kind();
    String t = name(db, f.table());
    String a = Names.alias(SqlParts.simpleName(f.table()));
    String t2 = blank(f.table2()) ? "" : name(db, f.table2());
    String b = blank(f.table2()) ? "" : other(a, Names.alias(SqlParts.simpleName(f.table2())));
    List<String> keys = cols(db, f.keys(), "");
    List<String> ak = cols(db, f.keys(), a + ".");
    String group = blank(f.group()) ? null : name(db, f.group());
    String date = blank(f.date()) ? null : a + "." + name(db, f.date());
    String value = blank(f.value()) ? null : name(db, f.value());
    String order = blank(f.order()) ? null : name(db, f.order());
    boolean oracle = db == DbKind.ORACLE;
    return switch (k) {
      case DUPLICATES -> "-- 중복 찾기: " + String.join(", ", keys) + " 가 같은 행이 2건 이상인 값\n"
          + select(keys, "COUNT(*) AS CNT") + "  FROM " + t + "\n GROUP BY\n" + list(keys) + " HAVING COUNT(*) > 1\n ORDER BY\n       CNT DESC";
      case DUPLICATE_ROWS -> "-- 중복 행 전체: 같은 값끼리 " + order + " 큰 순으로 순번(RN). RN >= 2 가 지울 대상입니다\n"
          + "WITH ranked AS (\n    SELECT\n           " + a + ".*\n         , ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", ak) + " ORDER BY " + a + "." + order + " DESC) AS RN\n"
          + "         , COUNT(*) OVER (PARTITION BY " + String.join(", ", ak) + ") AS CNT\n      FROM " + t + " " + a + "\n)\n"
          + "SELECT\n       r.*\n  FROM ranked r\n WHERE r.CNT > 1\n ORDER BY\n" + list(prefix(keys, "r.")) + "     , r.RN";
      case LATEST -> "-- 그룹별 최신 1건: " + String.join(", ", keys) + " 마다 " + order + " 가 가장 큰 행\n"
          + "WITH ranked AS (\n    SELECT\n           " + a + ".*\n         , ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", ak) + " ORDER BY " + a + "." + order + " DESC) AS RN\n"
          + "      FROM " + t + " " + a + "\n)\nSELECT\n       r.*\n  FROM ranked r\n WHERE r.RN = 1";
      case EXISTS, NOT_EXISTS -> "-- " + (k == Kind.EXISTS ? "짝이 있는 행: " : "짝이 없는 행: ") + f.table2() + " 에 같은 " + String.join(", ", keys) + " 가 " + (k == Kind.EXISTS ? "있는" : "없는") + " " + f.table() + "\n"
          + "SELECT\n       " + a + ".*\n  FROM " + t + " " + a + "\n WHERE " + (k == Kind.EXISTS ? "EXISTS" : "NOT EXISTS") + " (\n        SELECT 1\n          FROM " + t2 + " " + b + "\n"
          + matchLines(keys, b, a, "         WHERE ", "           AND ") + "       )";
      case CTE -> "-- WITH 로 나눠 쓰기: base(거를 행) → summary(묶기) → 마지막 SELECT\n"
          + "WITH base AS (\n    SELECT\n           " + a + ".*\n      FROM " + t + " " + a + "\n     WHERE 1 = 1\n)\n"
          + ", summary AS (\n    SELECT\n           " + group + "\n         , COUNT(*) AS CNT\n      FROM base\n     GROUP BY\n           " + group + "\n)\n"
          + "SELECT\n       s.*\n  FROM summary s\n ORDER BY\n       s.CNT DESC";
      case HIERARCHY -> hierarchy(f, t, a, keys.get(0), name(db, f.parent()), oracle);
      case RANK -> "-- 누적 합 · 순위: " + order + " 순서로 쌓인 " + value + " 합계와 " + value + " 큰 순위(같은 값은 같은 순위)\n"
          + "SELECT\n       " + a + ".*\n     , SUM(" + a + "." + value + ") OVER (ORDER BY " + a + "." + order + ") AS CUM_" + bare(value) + "\n"
          + "     , RANK() OVER (ORDER BY " + a + "." + value + " DESC) AS RNK\n  FROM " + t + " " + a + "\n ORDER BY\n       " + a + "." + order;
      case DIFF -> {
        String minus = oracle ? "MINUS" : "EXCEPT";
        yield "-- 두 표 차이: 한쪽에만 있는 " + String.join(", ", keys) + " (WHERE 칸 = 어느 표에만 있는지)\n"
            + "SELECT\n       '" + bare(f.table()) + " 에만' AS WHERE_ONLY\n" + list(keys).replaceFirst("^       ", "     , ")
            + "  FROM (\n        SELECT " + String.join(", ", keys) + " FROM " + t + "\n        " + minus + "\n        SELECT " + String.join(", ", keys) + " FROM " + t2 + "\n       ) x\n"
            + "UNION ALL\nSELECT\n       '" + bare(f.table2()) + " 에만' AS WHERE_ONLY\n" + list(keys).replaceFirst("^       ", "     , ")
            + "  FROM (\n        SELECT " + String.join(", ", keys) + " FROM " + t2 + "\n        " + minus + "\n        SELECT " + String.join(", ", keys) + " FROM " + t + "\n       ) y";
      }
      case PIVOT -> {
        List<String> items = new ArrayList<>(List.of(a + "." + group));
        for (String sv : f.statusValues()) {
          String then = value == null ? "1" : a + "." + value;
          items.add("SUM(CASE WHEN " + a + "." + name(db, f.status()) + " = " + QueryBuilder.quote(sv) + " THEN " + then + " ELSE 0 END) AS " + label(sv));
        }
        yield "-- 세로 → 가로: " + group + " 마다 " + f.status() + " 값별 " + (value == null ? "건수" : value + " 합계") + "를 칸으로\n"
            + "SELECT\n" + list(items) + "  FROM " + t + " " + a + "\n GROUP BY\n       " + a + "." + group + "\n ORDER BY\n       " + a + "." + group;
      }
      case UPSERT -> upsert(f, t, keys, cols(db, f.cols(), ""), oracle);
      case COUNT_BY -> group == null
          ? "-- 전체 건수\nSELECT\n       COUNT(*) AS CNT\n  FROM " + t + " " + a + "\n WHERE 1 = 1"
          : "-- " + group + " 값마다 건수 (많은 순)\nSELECT\n       " + a + "." + group + "\n     , COUNT(*) AS CNT\n  FROM " + t + " " + a
            + "\n WHERE 1 = 1\n GROUP BY\n       " + a + "." + group + "\n ORDER BY\n       CNT DESC";
      case STATS -> {
        List<String> items = new ArrayList<>();
        if (group != null) items.add(a + "." + group);
        String v = a + "." + value;
        items.addAll(List.of("COUNT(*) AS CNT", "SUM(" + v + ") AS SUM_" + bare(value), "ROUND(AVG(" + v + "), 2) AS AVG_" + bare(value),
            "MAX(" + v + ") AS MAX_" + bare(value), "MIN(" + v + ") AS MIN_" + bare(value)));
        yield "-- " + value + " 의 건수 · 합계 · 평균 · 최대 · 최소" + (group == null ? "" : " (" + group + " 마다)") + ". 합계 · 평균은 NULL 행을 빼고 셉니다\n"
            + "SELECT\n" + list(items) + "  FROM " + t + " " + a + "\n WHERE 1 = 1" + (group == null ? "" : "\n GROUP BY\n       " + a + "." + group + "\n ORDER BY\n       " + a + "." + group);
      }
      case BY_PERIOD -> {
        String p = period(f, date);
        List<String> items = new ArrayList<>(List.of(p + " AS PERIOD", "COUNT(*) AS CNT"));
        if (value != null) items.add("SUM(" + a + "." + value + ") AS SUM_" + bare(value));
        yield "-- " + f.period().label + " 현황: 시작일 ~ 끝일(끝일 하루 전체 포함)\n"
            + "SELECT\n" + list(items) + "  FROM " + t + " " + a + "\n" + range(f, date) + " GROUP BY\n       " + p + "\n ORDER BY\n       PERIOD";
      }
      case CHANGE -> "-- " + f.period().label + " 건수와 앞 기간 대비 증감 · 증감률(%). 앞 기간이 0 건이면 증감률은 비웁니다\n"
          + "WITH counted AS (\n    SELECT\n           " + period(f, date) + " AS PERIOD\n         , COUNT(*) AS CNT\n      FROM " + t + " " + a + "\n"
          + QueryBuilder.indent(range(f, date).stripTrailing(), "    ") + "\n     GROUP BY\n           " + period(f, date) + "\n)\n"
          + "SELECT\n       c.PERIOD\n     , c.CNT\n     , LAG(c.CNT) OVER (ORDER BY c.PERIOD) AS PREV_CNT\n     , c.CNT - LAG(c.CNT) OVER (ORDER BY c.PERIOD) AS DIFF\n"
          + "     , ROUND((c.CNT - LAG(c.CNT) OVER (ORDER BY c.PERIOD)) * 100.0 / NULLIF(LAG(c.CNT) OVER (ORDER BY c.PERIOD), 0), 1) AS RATE\n"
          + "  FROM counted c\n ORDER BY\n       c.PERIOD";
      case RATIO -> "-- " + group + " 값마다 건수와 전체 중 비율(%)\n"
          + "SELECT\n       " + a + "." + group + "\n     , COUNT(*) AS CNT\n     , ROUND(COUNT(*) * 100.0 / NULLIF(SUM(COUNT(*)) OVER (), 0), 1) AS PCT\n"
          + "  FROM " + t + " " + a + "\n WHERE 1 = 1\n GROUP BY\n       " + a + "." + group + "\n ORDER BY\n       CNT DESC";
      case CUMULATIVE -> "-- " + f.period().label + " 건수와 누적 건수\n"
          + "SELECT\n       " + period(f, date) + " AS PERIOD\n     , COUNT(*) AS CNT\n     , SUM(COUNT(*)) OVER (ORDER BY " + period(f, date) + ") AS CUM_CNT\n"
          + "  FROM " + t + " " + a + "\n" + range(f, date) + " GROUP BY\n       " + period(f, date) + "\n ORDER BY\n       PERIOD";
      case TOP_N -> {
        String measure = value == null ? "COUNT(*)" : "SUM(" + a + "." + value + ")";
        String measureName = value == null ? "CNT" : "SUM_" + bare(value);
        yield "-- " + group + " 마다 " + (value == null ? "건수" : value + " 합계") + " 순위, 상위 " + f.topN() + " 개 (같은 값은 같은 순위)\n"
            + "WITH ranked AS (\n    SELECT\n           " + a + "." + group + "\n         , " + measure + " AS " + measureName + "\n"
            + "         , RANK() OVER (ORDER BY " + measure + " DESC) AS RNK\n      FROM " + t + " " + a + "\n     GROUP BY\n           " + a + "." + group + "\n)\n"
            + "SELECT\n       r.*\n  FROM ranked r\n WHERE r.RNK <= " + Math.max(1, f.topN()) + "\n ORDER BY\n       r.RNK";
      }
      case STATUS_ROW -> {
        List<String> items = new ArrayList<>(List.of("COUNT(*) AS TOTAL"));
        for (String sv : f.statusValues()) {
          items.add("SUM(CASE WHEN " + a + "." + name(db, f.status()) + " = " + QueryBuilder.quote(sv) + " THEN 1 ELSE 0 END) AS " + label(sv));
        }
        yield "-- 대시보드 카드: 전체 건수와 " + f.status() + " 값마다 건수를 한 줄에\nSELECT\n" + list(items) + "  FROM " + t + " " + a + "\n WHERE 1 = 1";
      }
      case ROLLUP -> {
        String g = a + "." + group;
        List<String> items = new ArrayList<>(List.of("CASE WHEN GROUPING(" + g + ") = 1 THEN '" + TOTAL_LABEL + "' ELSE CAST(" + g + " AS VARCHAR(100)) END AS " + bare(group),
            "COUNT(*) AS CNT"));
        if (value != null) items.add("SUM(" + a + "." + value + ") AS SUM_" + bare(value));
        yield "-- " + group + " 별 건수" + (value == null ? "" : " · 합계") + " 아래에 「" + TOTAL_LABEL + "」 줄\n"
            + "SELECT\n" + list(items) + "  FROM " + t + " " + a + "\n WHERE 1 = 1\n GROUP BY ROLLUP(" + g + ")\n ORDER BY\n       GROUPING(" + g + ")\n     , " + g;
      }
      case DATE_FILL -> dateFill(f, t, a, date, oracle);
    };
  }

  private static String hierarchy(Fill f, String t, String a, String id, String parent, boolean oracle) {
    String head = "-- 계층: " + parent + " 가 비어 있는 행(맨 위)부터 아래로. LVL 은 깊이, PATH 는 위에서부터의 경로\n";
    if (oracle) {
      return head + "SELECT\n       LEVEL AS LVL\n     , SYS_CONNECT_BY_PATH(" + a + "." + id + ", ' > ') AS PATH\n     , " + a + ".*\n  FROM " + t + " " + a + "\n"
          + " START WITH " + a + "." + parent + " IS NULL\n CONNECT BY PRIOR " + a + "." + id + " = " + a + "." + parent + "\n ORDER SIBLINGS BY\n       " + a + "." + id;
    }
    return head + "WITH RECURSIVE tree AS (\n    SELECT\n           1 AS LVL\n         , CAST(" + a + "." + id + " AS VARCHAR(4000)) AS PATH\n         , " + a + ".*\n"
        + "      FROM " + t + " " + a + "\n     WHERE " + a + "." + parent + " IS NULL\n    UNION ALL\n    SELECT\n           p.LVL + 1\n"
        + "         , p.PATH || ' > ' || " + a + "." + id + "\n         , " + a + ".*\n      FROM " + t + " " + a + "\n     INNER JOIN tree p\n        ON " + a + "." + parent + " = p." + id + "\n)\n"
        + "SELECT\n       tr.*\n  FROM tree tr\n ORDER BY\n       tr.PATH";
  }

  private static String upsert(Fill f, String t, List<String> keys, List<String> cols, boolean oracle) {
    List<String> all = new ArrayList<>(keys);
    all.addAll(cols);
    if (oracle) {
      List<String> src = new ArrayList<>();
      for (String c : all) src.add(place(f, c) + " AS " + c);
      List<String> set = new ArrayList<>();
      for (String c : cols) set.add("t." + c + " = s." + c);
      return "-- 있으면 수정, 없으면 넣기: " + String.join(", ", keys) + " 로 찾습니다\n"
          + "MERGE INTO " + t + " t\nUSING (\n        SELECT\n" + QueryBuilder.indent(list(src).stripTrailing(), "        ") + "\n          FROM DUAL\n      ) s\n"
          + "   ON (" + match(keys, "t", "s") + ")\n WHEN MATCHED THEN\n      UPDATE\n         SET " + String.join("\n           , ", set) + "\n"
          + " WHEN NOT MATCHED THEN\n      INSERT (" + String.join(", ", all) + ")\n      VALUES (" + String.join(", ", prefix(all, "s.")) + ")";
    }
    List<String> vals = new ArrayList<>();
    for (String c : all) vals.add(place(f, c));
    List<String> set = new ArrayList<>();
    for (String c : cols) set.add(c + " = EXCLUDED." + c);
    return "-- 있으면 수정, 없으면 넣기: " + String.join(", ", keys) + " 로 찾습니다. 기준 칼럼에 PK 나 UNIQUE 제약이 있어야 합니다\n"
        + "INSERT INTO " + t + " (" + String.join(", ", all) + ")\nVALUES (" + String.join(", ", vals) + ")\n"
        + "    ON CONFLICT (" + String.join(", ", keys) + ")\n    DO UPDATE\n          SET " + String.join("\n            , ", set);
  }

  private static String dateFill(Fill f, String t, String a, String date, boolean oracle) {
    String from = dateParam(f, FROM_DATE);
    String to = dateParam(f, TO_DATE);
    String head = "-- 시작일 ~ 끝일의 모든 날짜와 건수. 행이 없는 날은 0\n";
    String counted = "cnt AS (\n    SELECT\n           " + (oracle ? "TRUNC(" + date + ")" : "CAST(" + date + " AS DATE)") + " AS DT\n         , COUNT(*) AS CNT\n"
        + "      FROM " + t + " " + a + "\n     WHERE " + date + " >= " + from + "\n       AND " + date + " < " + to + " + 1\n     GROUP BY\n           "
        + (oracle ? "TRUNC(" + date + ")" : "CAST(" + date + " AS DATE)") + "\n)\n";
    String days = oracle
        ? "WITH days AS (\n    SELECT " + from + " + LEVEL - 1 AS DT\n      FROM DUAL\n   CONNECT BY LEVEL <= " + to + " - " + from + " + 1\n)\n"
        : "WITH days AS (\n    SELECT CAST(g AS DATE) AS DT\n      FROM generate_series(" + from + ", " + to + ", INTERVAL '1 day') g\n)\n";
    String nvl = oracle ? "NVL" : "COALESCE";
    if (oracle && f.style() == QueryBuilder.JoinStyle.OLD) {
      return head + days + ", " + counted + "SELECT\n       d.DT\n     , NVL(c.CNT, 0) AS CNT\n  FROM days d\n     , cnt c\n WHERE d.DT = c.DT(+)\n ORDER BY\n       d.DT";
    }
    return head + days + ", " + counted + "SELECT\n       d.DT\n     , " + nvl + "(c.CNT, 0) AS CNT\n  FROM days d\n  LEFT OUTER JOIN cnt c\n    ON d.DT = c.DT\n ORDER BY\n       d.DT";
  }

  /** 기간 묶음 식. Oracle 은 TRUNC 뒤 TO_CHAR, PostgreSQL 은 DATE_TRUNC 뒤 TO_CHAR(정렬되는 글자). */
  private static String period(Fill f, String date) {
    Period p = f.period() == null ? Period.MONTH : f.period();
    if (f.kind() == DbKind.ORACLE) return "TO_CHAR(TRUNC(" + date + ", '" + p.oracleTrunc + "'), '" + p.format + "')";
    return "TO_CHAR(DATE_TRUNC('" + p.postgresTrunc + "', " + date + "), '" + p.format + "')";
  }

  /** 시작일 이상 · 끝일 다음 날 미만(끝일 하루 전체). */
  private static String range(Fill f, String date) {
    return " WHERE " + date + " >= " + dateParam(f, FROM_DATE) + "\n   AND " + date + " < " + dateParam(f, TO_DATE) + " + 1\n";
  }

  private static String dateParam(Fill f, String name) {
    String place = f.bind() == QueryBuilder.Bind.MYBATIS ? "#{" + name + "}" : ":" + name;
    return f.kind() == DbKind.ORACLE ? "TO_DATE(" + place + ", '" + QueryBuilder.DATE_FORMAT + "')" : "CAST(" + place + " AS DATE)";
  }

  private static String place(Fill f, String column) {
    String name = Names.camel(column.replace("\"", ""));
    return f.bind() == QueryBuilder.Bind.MYBATIS ? "#{" + name + "}" : ":" + name;
  }

  private static String select(List<String> items, String extra) {
    List<String> all = new ArrayList<>(items);
    all.add(extra);
    return "SELECT\n" + list(all);
  }

  /** 앞 쉼표 목록(끝에 줄바꿈). */
  private static String list(List<String> items) {
    StringBuilder sb = new StringBuilder();
    QueryBuilder.leading(sb, items, blanks(items.size()));
    return sb.toString();
  }

  private static String matchLines(List<String> keys, String inner, String outer, String first, String next) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < keys.size(); i++) {
      sb.append(i == 0 ? first : next).append(inner).append('.').append(keys.get(i)).append(" = ").append(outer).append('.').append(keys.get(i)).append('\n');
    }
    return sb.toString();
  }

  private static String match(List<String> keys, String left, String right) {
    List<String> out = new ArrayList<>();
    for (String k : keys) out.add(left + "." + k + " = " + right + "." + k);
    return String.join(" AND ", out);
  }

  private static List<String> cols(DbKind kind, List<String> names, String prefix) {
    List<String> out = new ArrayList<>();
    for (String n : names) out.add(prefix + name(kind, n));
    return out;
  }

  private static List<String> prefix(List<String> items, String prefix) {
    List<String> out = new ArrayList<>();
    for (String i : items) out.add(prefix + i);
    return out;
  }

  private static List<String> blanks(int n) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) out.add("");
    return out;
  }

  /** 화면에서 넣은 이름(스키마.표 가능)을 SQL 표기로. 따옴표로 감싼 이름은 그대로. */
  private static String name(DbKind kind, String raw) {
    String s = raw.strip();
    if (s.startsWith("\"")) return s;
    StringBuilder out = new StringBuilder();
    for (String part : s.split("\\.")) {
      if (out.length() > 0) out.append('.');
      out.append(Names.sql(kind, kind == DbKind.ORACLE ? part.toUpperCase(Locale.ROOT) : part.toLowerCase(Locale.ROOT)));
    }
    return out.toString();
  }

  /** 따옴표 · 스키마를 뺀 대문자 이름(별칭 만들 때). */
  private static String bare(String name) {
    return SqlParts.simpleName(name).toUpperCase(Locale.ROOT);
  }

  /** 칸 이름: 영문 이름 꼴이면 대문자 그대로, 아니면(한글 · 공백) 큰따옴표로 감싼다. */
  private static String label(String value) {
    String v = value.strip();
    return PLAIN_ALIAS.matcher(v).matches() ? v.toUpperCase(Locale.ROOT) : '"' + v.replace("\"", "\"\"") + '"';
  }

  private static String other(String a, String b) {
    return a.equals(b) ? b + "2" : b;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
