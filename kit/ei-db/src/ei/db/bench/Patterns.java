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
 * 목적으로 만드는 SQL 뼈대(중복 찾기 · 그룹별 최신 · 계층 …).
 * 표 · 칼럼 이름만 채우면 DB 종류에 맞는 문장이 나온다. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 */
public final class Patterns {
  private Patterns() {}

  /** 채울 칸. 무늬마다 필요한 칸만 화면에 보인다. */
  public enum Field {
    TABLE("표"), TABLE2("비교할 표 · 다른 표"), KEYS("기준 칼럼 (같은 값이면 같은 것)"), COLS("함께 볼 · 바꿀 칼럼"),
    GROUP("묶을 칼럼 (예: 상태, 부서)"), VALUE("값 칼럼 (금액 · 수량)"), ORDER("최신 · 순서를 가를 칼럼"),
    PARENT("부모를 가리키는 칼럼"), STATUS("상태 칼럼"), STATUS_VALUES("상태 값들 (쉼표로)");

    public final String label;

    Field(String label) { this.label = label; }
  }

  /** 무늬 하나. */
  public enum Kind {
    DUPLICATES("중복 찾기", "기준 칼럼 값이 같은 행이 2건 이상인 값과 건수를 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS)),
    DUPLICATE_ROWS("중복 행 전체 보기", "중복된 행을 지우기 전에 한 줄씩 다 봅니다. 같은 값끼리 순번(RN)을 매겨 2 이상이 지울 대상입니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.ORDER)),
    LATEST("그룹별 최신 1건", "기준 칼럼마다 순서 칼럼이 가장 큰(최신) 행 하나만 봅니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.ORDER)),
    EXISTS("있는지 확인 (EXISTS)", "다른 표에 짝이 있는 행만 봅니다. 짝이 여러 개여도 한 번만 나옵니다(JOIN 과 달리 늘어나지 않음).", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    NOT_EXISTS("없는 것 찾기 (NOT EXISTS)", "다른 표에 짝이 없는 행만 봅니다. NOT IN 과 달리 NULL 이 있어도 맞게 나옵니다.", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    CTE("WITH 로 나눠 쓰기", "긴 쿼리를 이름 붙인 단계(base → summary)로 나눕니다. 단계마다 따로 실행해 볼 수 있습니다(도구 → 조각 실행).", EnumSet.of(Field.TABLE, Field.GROUP)),
    HIERARCHY("계층 (부모-자식) 조회", "메뉴 · 조직 · 부품 구성처럼 부모를 가리키는 칼럼으로 위에서 아래로 펼칩니다.", EnumSet.of(Field.TABLE, Field.KEYS, Field.PARENT)),
    RANK("누적 합 · 순위", "줄마다 앞에서부터 쌓인 합과 값 순위를 붙입니다(행 수는 그대로).", EnumSet.of(Field.TABLE, Field.VALUE, Field.ORDER)),
    DIFF("두 표 차이 비교", "두 표의 기준 칼럼 값 중 한쪽에만 있는 것을 봅니다(이관 · 백업 비교).", EnumSet.of(Field.TABLE, Field.TABLE2, Field.KEYS)),
    PIVOT("세로 → 가로 바꾸기", "상태 값마다 칸을 하나씩 만들어 한 줄에 놓습니다.", EnumSet.of(Field.TABLE, Field.GROUP, Field.STATUS, Field.STATUS_VALUES, Field.VALUE)),
    UPSERT("있으면 수정, 없으면 넣기", "기준 칼럼으로 찾아 있으면 UPDATE, 없으면 INSERT 합니다(Oracle MERGE, PostgreSQL ON CONFLICT).", EnumSet.of(Field.TABLE, Field.KEYS, Field.COLS));

    public final String label;
    public final String description;
    public final Set<Field> fields;

    Kind(String label, String description, Set<Field> fields) {
      this.label = label;
      this.description = description;
      this.fields = fields;
    }

    @Override public String toString() { return label; }
  }

  /** 칸 이름: 무늬마다 그 일에 맞는 이름, 없으면 Field 기본 이름. */
  public static String label(Kind k, Field f) {
    String own = switch (k) {
      case HIERARCHY -> f == Field.KEYS ? "자기 ID 칼럼 (예: MENU_ID)" : f == Field.PARENT ? "부모 ID 칼럼 (예: PARENT_MENU_ID)" : null;
      case LATEST -> f == Field.KEYS ? "이 값마다 1건 (예: CUST_ID)" : f == Field.ORDER ? "최신을 가를 칼럼 (예: REG_DT)" : null;
      case DUPLICATE_ROWS -> f == Field.ORDER ? "남길 행을 가를 칼럼 (큰 것 = RN 1)" : null;
      case EXISTS, NOT_EXISTS -> f == Field.TABLE2 ? "짝을 찾을 표" : f == Field.KEYS ? "두 표에 같은 이름으로 있는 칼럼" : null;
      case DIFF -> f == Field.TABLE2 ? "비교할 표 (예: 백업 표)" : f == Field.KEYS ? "비교할 칼럼 (두 표에 같은 이름)" : null;
      case UPSERT -> f == Field.KEYS ? "찾을 칼럼 (PK)" : f == Field.COLS ? "넣을 · 고칠 칼럼" : null;
      case PIVOT -> f == Field.GROUP ? "줄로 둘 칼럼" : f == Field.STATUS ? "칸으로 펼칠 칼럼" : f == Field.STATUS_VALUES ? "칸이 될 값들 (쉼표로)" : f == Field.VALUE ? "더할 값 칼럼" : null;
      case RANK -> f == Field.VALUE ? "순위 · 누적할 값 칼럼" : f == Field.ORDER ? "누적 순서 칼럼 (예: 날짜)" : null;
      case CTE -> f == Field.GROUP ? "묶을 칼럼" : null;
      default -> null;
    };
    return own != null ? own : f.label;
  }

  /** 칸이 비었을 때 회색으로 보일 예시. */
  public static String hint(Field f) {
    return switch (f) {
      case TABLE, TABLE2 -> "예: TB_ORDER  (치거나 목록에서 고르기)";
      case KEYS, COLS -> "예: CUST_ID, ORDER_NO  (쉼표로 여러 개)";
      case STATUS_VALUES -> "예: 접수, 진행, 완료";
      default -> "칼럼 이름  (치거나 목록에서 고르기)";
    };
  }

  /** 채운 값. 칼럼 목록은 쉼표로 나눈 이름, 빈 칸은 빈 글. */
  public record Fill(DbKind kind, QueryBuilder.JoinStyle style, QueryBuilder.Bind bind, String table, String table2, List<String> keys,
                     List<String> cols, String group, String value, String order, String parent, String status,
                     List<String> statusValues) {}

  private static final Pattern PLAIN_ALIAS = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  /** 비어서 만들 수 없는 칸(화면에 「채워 주세요」 로 보인다). 다 채웠으면 빈 목록. 값 칼럼 · 묶을 칼럼 등 비어도 되는 칸은 넣지 않는다. */
  public static List<Field> missing(Kind k, Fill f) {
    List<Field> out = new ArrayList<>();
    if (blank(f.table())) out.add(Field.TABLE);
    if (k.fields.contains(Field.TABLE2) && blank(f.table2())) out.add(Field.TABLE2);
    if (k.fields.contains(Field.KEYS) && f.keys().isEmpty()) out.add(Field.KEYS);
    if (k.fields.contains(Field.COLS) && f.cols().isEmpty()) out.add(Field.COLS);
    if (k.fields.contains(Field.ORDER) && blank(f.order())) out.add(Field.ORDER);
    if (k.fields.contains(Field.PARENT) && blank(f.parent())) out.add(Field.PARENT);
    if (k.fields.contains(Field.STATUS) && blank(f.status())) out.add(Field.STATUS);
    if (k.fields.contains(Field.STATUS_VALUES) && f.statusValues().isEmpty()) out.add(Field.STATUS_VALUES);
    if (k == Kind.RANK && blank(f.value())) out.add(Field.VALUE);
    if ((k == Kind.CTE || k == Kind.PIVOT) && blank(f.group())) out.add(Field.GROUP);
    return out;
  }

  /** 문장. missing 이 비지 않으면 IllegalArgumentException. 값 자리가 MyBatis 면 부등호 줄을 CDATA 로 감싼다. */
  public static String render(Kind k, Fill f) {
    String sql = body(k, f);
    return f.bind() == QueryBuilder.Bind.MYBATIS ? QueryBuilder.cdata(sql) : sql;
  }

  private static String body(Kind k, Fill f) {
    if (!missing(k, f).isEmpty()) throw new IllegalArgumentException("채울 칸이 남았습니다.");
    DbKind db = f.kind();
    String t = name(db, f.table());
    String a = Names.alias(SqlParts.simpleName(f.table()));
    String t2 = blank(f.table2()) ? "" : name(db, f.table2());
    String b = blank(f.table2()) ? "" : other(a, Names.alias(SqlParts.simpleName(f.table2())));
    List<String> keys = cols(db, f.keys(), "");
    List<String> ak = cols(db, f.keys(), a + ".");
    String group = blank(f.group()) ? null : name(db, f.group());
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
    };
  }

  private static String hierarchy(Fill f, String t, String a, String id, String parent, boolean oracle) {
    String head = "-- 계층: " + parent + " 가 비어 있는 행(맨 위)부터 아래로. LVL 은 깊이, PATH 는 위에서부터의 경로\n";
    if (oracle) {
      return head + "SELECT\n       LEVEL AS LVL\n     , SYS_CONNECT_BY_PATH(" + a + "." + id + ", ' > ') AS PATH\n     , " + a + ".*\n  FROM " + t + " " + a + "\n"
          + " START WITH " + a + "." + parent + " IS NULL\n CONNECT BY PRIOR " + a + "." + id + " = " + a + "." + parent + "\n ORDER SIBLINGS BY\n       " + a + "." + id;
    }
    // 고리(자기 자신 · 서로를 부모로 가리키는 행)가 있으면 WITH RECURSIVE 는 끝없이 돈다. 경로에 이미 있는 ID 는 다시 펼치지 않는다.
    // Oracle CONNECT BY 는 고리에서 ORA-01436 으로 멈추므로 그대로 둔다.
    String p = other(a, "p");
    return head.replace("\n", "(고리는 한 번만 펼침)\n") + "WITH RECURSIVE tree AS (\n    SELECT\n           1 AS LVL\n         , CAST(" + a + "." + id + " AS VARCHAR(4000)) AS PATH\n         , " + a + ".*\n"
        + "      FROM " + t + " " + a + "\n     WHERE " + a + "." + parent + " IS NULL\n    UNION ALL\n    SELECT\n           " + p + ".LVL + 1\n"
        + "         , CAST(" + p + ".PATH || ' > ' || " + a + "." + id + " AS VARCHAR(4000))\n         , " + a + ".*\n      FROM " + t + " " + a + "\n     INNER JOIN tree " + p + "\n        ON " + a + "." + parent + " = " + p + "." + id + "\n"
        + "     WHERE (' > ' || " + p + ".PATH || ' > ') NOT LIKE ('% > ' || " + a + "." + id + " || ' > %')\n)\n"
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
