package ei.db.bench;

import ei.db.DbKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle ↔ PostgreSQL 문법 바꿔 쓰기. 자주 쓰는 함수 · 꼴만 글자로 바꾸고, 바꾸지 못한 것은 notes 에 「직접 확인」 으로 남긴다.
 * 문자열 · 주석 안은 건드리지 않는다(가린 글에서 찾고 원문 위치를 바꾼다). IntelliJ 에 기대지 않는다.
 */
public final class DialectConvert {
  private DialectConvert() {}

  /** notes 는 바꾼 것(「NVL → COALESCE 2곳」)과 직접 볼 것. */
  public record Result(String sql, List<String> notes) {}

  private static final int MAX_PASSES = 500;
  private static final Pattern INT = Pattern.compile("-?\\d+");

  public static Result convert(String sql, DbKind from, DbKind to) {
    if (from == to) throw new IllegalArgumentException("바꿀 DB 가 같습니다.");
    if (from == DbKind.ORACLE) return toPostgres(sql);
    if (to == DbKind.ORACLE) return toOracle(sql);
    throw new IllegalArgumentException("Oracle ↔ PostgreSQL 만 바꿉니다.");
  }

  /** Oracle → PostgreSQL. */
  public static Result toPostgres(String sql) {
    List<String> notes = new ArrayList<>();
    String s = sql;
    if (SqlParts.mask(s).contains("(+)")) {
      try {
        JoinStyleConvert.Result r = JoinStyleConvert.toAnsi(s);
        s = r.sql();
        notes.add("(+) 연결 → LEFT OUTER JOIN … ON");
        notes.addAll(r.notes());
      } catch (IllegalArgumentException e) {
        notes.add("직접 확인: (+) 를 JOIN 으로 바꾸지 못했습니다(" + e.getMessage() + ")");
      }
    }
    s = words(s, "TRUNC\\s*\\(\\s*SYSDATE\\s*\\)", "CURRENT_DATE", "TRUNC(SYSDATE) → CURRENT_DATE", notes);
    s = words(s, "SYSDATE|SYSTIMESTAMP", "NOW()", "SYSDATE → NOW()", notes);
    s = trunc(s, notes);
    s = calls(s, "NVL", a -> a.size() == 2 ? "COALESCE(" + a.get(0) + ", " + a.get(1) + ")" : null, "NVL → COALESCE", notes);
    s = calls(s, "NVL2", a -> a.size() == 3 ? "CASE WHEN " + a.get(0) + " IS NOT NULL THEN " + a.get(1) + " ELSE " + a.get(2) + " END" : null, "NVL2 → CASE", notes);
    int before = notes.size();
    s = calls(s, "DECODE", DialectConvert::decode, "DECODE → CASE", notes);
    if (notes.size() > before) notes.add("직접 확인: DECODE 는 NULL 끼리 같다고 보지만 CASE 는 아닙니다. NULL 을 비교하던 곳은 WHEN … IS NULL 로 고치세요.");
    s = calls(s, "INSTR", a -> a.size() == 2 ? "STRPOS(" + a.get(0) + ", " + a.get(1) + ")" : null, "INSTR → STRPOS", notes);
    s = calls(s, "ADD_MONTHS", a -> a.size() != 2 ? null
        : INT.matcher(a.get(1)).matches() ? "(" + a.get(0) + " + INTERVAL '" + a.get(1) + " month')" : "(" + a.get(0) + " + " + a.get(1) + " * INTERVAL '1 month')",
        "ADD_MONTHS → + INTERVAL", notes);
    s = listagg(s, notes);
    s = words(s, "\\s+FROM\\s+DUAL", "", "FROM DUAL 뺌", notes);
    s = words(s, "MINUS", "EXCEPT", "MINUS → EXCEPT", notes);
    s = regex(s, "([A-Za-z_][\\w$#]*)\\s*\\.\\s*(NEXTVAL|CURRVAL)(?![\\w$#])", m -> m.group(2).equalsIgnoreCase("NEXTVAL") ? "nextval('" + m.group(1).toLowerCase(Locale.ROOT) + "')" : "currval('" + m.group(1).toLowerCase(Locale.ROOT) + "')", "시퀀스.NEXTVAL → nextval('시퀀스')", notes);
    s = words(s, "VARCHAR2", "VARCHAR", "VARCHAR2 → VARCHAR", notes);
    s = words(s, "NUMBER(?=\\s*\\()", "NUMERIC", "NUMBER(n) → NUMERIC(n)", notes);
    s = pagedRownum(s, notes);
    s = rownum(s, notes);
    s = updateSetAlias(s, notes);
    String up = SqlParts.mask(s).toUpperCase(Locale.ROOT);
    if (DICTIONARY.matcher(up).find()) notes.add("직접 확인: Oracle 데이터 사전(USER_ · ALL_ · DBA_ · V$)은 PostgreSQL 에 없습니다. information_schema · pg_catalog 로 바꿔 쓰세요(예제 모음 → 데이터 사전).");
    if (up.contains("CONNECT BY")) notes.add("직접 확인: CONNECT BY 계층 조회는 WITH RECURSIVE 로 바꿔야 합니다(목적으로 만들기 → 계층 참고).");
    if (up.matches("(?s).*\\bMONTHS_BETWEEN\\b.*")) notes.add("직접 확인: MONTHS_BETWEEN 은 PostgreSQL 에 없습니다(AGE · EXTRACT 로).");
    if (up.matches("(?s).*\\bROWNUM\\b.*")) notes.add("직접 확인: 남은 ROWNUM 은 ROW_NUMBER() OVER () 나 LIMIT 으로 바꾸세요.");
    return new Result(s, notes);
  }

  /** PostgreSQL → Oracle. */
  public static Result toOracle(String sql) {
    List<String> notes = new ArrayList<>();
    String s = sql;
    s = casts(s, notes);
    s = words(s, "NOW\\s*\\(\\s*\\)", "SYSDATE", "NOW() → SYSDATE", notes);
    s = words(s, "CURRENT_DATE", "TRUNC(SYSDATE)", "CURRENT_DATE → TRUNC(SYSDATE)", notes);
    s = regex(s, "([A-Za-z_][\\w$#.]*|'[^']*')\\s+ILIKE\\s+([A-Za-z_][\\w$#.]*|'[^']*'|:[A-Za-z_]\\w*)", m -> "UPPER(" + m.group(1) + ") LIKE UPPER(" + m.group(2) + ")", "ILIKE → UPPER LIKE UPPER", notes);
    s = calls(s, "STRING_AGG", DialectConvert::stringAgg, "STRING_AGG → LISTAGG", notes);
    s = calls(s, "NEXTVAL", a -> a.size() == 1 && a.get(0).startsWith("'") ? a.get(0).replace("'", "").toUpperCase(Locale.ROOT) + ".NEXTVAL" : null, "nextval('시퀀스') → 시퀀스.NEXTVAL", notes);
    s = words(s, "EXCEPT", "MINUS", "EXCEPT → MINUS", notes);
    s = regex(s, "INTERVAL\\s+'(\\d+)\\s+DAYS?'", m -> m.group(1), "INTERVAL 'n day' → n", notes);
    s = limit(s, notes);
    s = dual(s, notes);
    String up = SqlParts.mask(s).toUpperCase(Locale.ROOT);
    if (up.matches("(?s).*\\bINTERVAL\\b.*")) notes.add("직접 확인: 남은 INTERVAL(달 · 해)은 ADD_MONTHS 로 바꾸세요.");
    if (up.matches("(?s).*\\b(TRUE|FALSE)\\b.*")) notes.add("직접 확인: Oracle 칼럼에는 TRUE · FALSE 가 없어 보통 1 · 0 이나 'Y' · 'N' 을 씁니다.");
    if (up.matches("(?s).*\\bON\\s+CONFLICT\\b.*")) notes.add("직접 확인: ON CONFLICT 는 MERGE 로 바꿔야 합니다(목적으로 만들기 → 있으면 수정, 없으면 넣기).");
    if (up.matches("(?s).*\\bGENERATE_SERIES\\b.*")) notes.add("직접 확인: generate_series 는 CONNECT BY LEVEL 로 바꿔야 합니다.");
    return new Result(s, notes);
  }

  private static String decode(List<String> a) {
    if (a.size() < 3) return null;
    StringBuilder sb = new StringBuilder("CASE ").append(a.get(0));
    int i = 1;
    for (; i + 1 < a.size(); i += 2) sb.append(" WHEN ").append(a.get(i)).append(" THEN ").append(a.get(i + 1));
    if (i < a.size()) sb.append(" ELSE ").append(a.get(i));
    return sb.append(" END").toString();
  }

  private static final Pattern ORDER_IN_ARG = Pattern.compile("(?is)^(.*?)\\s+ORDER\\s+BY\\s+(.+)$");

  private static String stringAgg(List<String> a) {
    if (a.size() != 2) return null;
    Matcher m = ORDER_IN_ARG.matcher(a.get(1));
    String sep = m.matches() ? m.group(1) : a.get(1);
    String order = m.matches() ? m.group(2) : a.get(0);
    return "LISTAGG(" + a.get(0) + ", " + sep + ") WITHIN GROUP (ORDER BY " + order + ")";
  }

  /** LISTAGG(x, sep) WITHIN GROUP (ORDER BY y) → STRING_AGG(CAST(x AS VARCHAR), sep ORDER BY y). */
  private static String listagg(String sql, List<String> notes) {
    Pattern within = Pattern.compile("(?is)^\\s*WITHIN\\s+GROUP\\s*\\(\\s*ORDER\\s+BY\\s+");
    int count = 0;
    int limit = Integer.MAX_VALUE;
    String s = sql;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      Call c = lastCall(s, "LISTAGG", limit);
      if (c == null) break;
      List<String> a = c.args;
      String after = s.substring(c.end);
      Matcher m = within.matcher(SqlParts.mask(after));
      String order = null;
      int consumed = 0;
      if (m.find()) {
        int open = after.indexOf('(');
        int close = SqlParts.matching(SqlParts.mask(after), open);
        if (close > 0) {
          order = after.substring(m.end(), close).strip();
          consumed = close + 1;
        }
      }
      String sep = a.size() > 1 ? a.get(1) : "','";
      String text = "STRING_AGG(CAST(" + a.get(0) + " AS VARCHAR), " + sep + (order == null ? "" : " ORDER BY " + order) + ")";
      s = s.substring(0, c.start) + text + after.substring(consumed);
      count++;
      limit = c.start;
    }
    if (count > 0) notes.add("LISTAGG → STRING_AGG " + count + "곳");
    return s;
  }

  private static final Pattern DICTIONARY = Pattern.compile("(?<![\\w$#.])(USER|ALL|DBA)_(TABLES|TAB_COLUMNS|TAB_COMMENTS|COL_COMMENTS|CONSTRAINTS|CONS_COLUMNS|INDEXES|IND_COLUMNS|OBJECTS|SOURCE|VIEWS|SEQUENCES)(?![\\w$#])|(?<![\\w$#.])V\\$\\w+");
  /** TRUNC 의 Oracle 날짜 단위 → DATE_TRUNC 단위. */
  private static final Map<String, String> TRUNC_UNITS = Map.ofEntries(
      Map.entry("YYYY", "year"), Map.entry("YEAR", "year"), Map.entry("YY", "year"), Map.entry("Y", "year"), Map.entry("SYYYY", "year"),
      Map.entry("Q", "quarter"), Map.entry("MM", "month"), Map.entry("MON", "month"), Map.entry("MONTH", "month"),
      Map.entry("IW", "week"), Map.entry("WW", "week"), Map.entry("W", "week"), Map.entry("DD", "day"), Map.entry("DDD", "day"), Map.entry("J", "day"),
      Map.entry("HH", "hour"), Map.entry("HH24", "hour"), Map.entry("HH12", "hour"), Map.entry("MI", "minute"));
  /** 인자 하나인 TRUNC 가 날짜를 자르는 것으로 볼 꼴(날짜 함수 · _DT · DATE 가 든 이름). 숫자 TRUNC 는 PostgreSQL 에도 있어 두지 않는다. */
  private static final Pattern DATE_LIKE = Pattern.compile("(?i).*(NOW\\s*\\(|CURRENT_DATE|CURRENT_TIMESTAMP|TO_DATE\\s*\\(|SYSDATE|DATE|_DT\\b|DTM\\b|TIME).*", Pattern.DOTALL);

  /** TRUNC(날짜[, '단위']) → DATE_TRUNC('단위', 날짜). 숫자 TRUNC 는 그대로, 모르는 단위는 직접 확인으로 알린다. */
  private static String trunc(String sql, List<String> notes) {
    int[] count = {0};
    List<String> unknown = new ArrayList<>();
    String out = calls(sql, "TRUNC", a -> {
      if (a.size() == 2) {
        String fmt = a.get(1).strip();
        String unit = fmt.length() > 1 && fmt.startsWith("'") && fmt.endsWith("'") ? TRUNC_UNITS.get(fmt.substring(1, fmt.length() - 1).toUpperCase(Locale.ROOT)) : null;
        if (unit == null) {
          unknown.add(fmt);
          return "TRUNC(" + a.get(0) + ", " + a.get(1) + ")";
        }
        count[0]++;
        return "DATE_TRUNC('" + unit + "', " + a.get(0).strip() + ")";
      }
      if (a.size() == 1 && DATE_LIKE.matcher(a.get(0)).matches()) {
        count[0]++;
        return "DATE_TRUNC('day', " + a.get(0).strip() + ")";
      }
      return "TRUNC(" + String.join(", ", a) + ")";
    }, "", notes);
    if (count[0] > 0) notes.add("TRUNC(날짜) → DATE_TRUNC " + count[0] + "곳 (결과가 timestamp 라 날짜만 보려면 ::date)");
    if (!unknown.isEmpty()) notes.add("직접 확인: TRUNC 단위 " + String.join(", ", unknown) + " 는 바꾸지 못했습니다.");
    return out;
  }

  /** 작업대 · 흔한 꼴의 ROWNUM 페이징(SELECT * FROM (SELECT q.*, ROWNUM RN FROM (안쪽) q WHERE ROWNUM <= 끝) WHERE RN > 시작) → 안쪽 + LIMIT · OFFSET. */
  private static final Pattern PAGED = Pattern.compile(
      "(?is)^\\s*SELECT\\s+\\*\\s+FROM\\s*\\(\\s*SELECT\\s+(\\w+)\\s*\\.\\s*\\*\\s*,\\s*ROWNUM\\s+(?:AS\\s+)?(\\w+)\\s+FROM\\s*\\((.*)\\)\\s*\\1\\s+WHERE\\s+ROWNUM\\s*(<=|<)\\s*(\\d+)\\s*\\)\\s*(?:\\w+\\s+)?WHERE\\s+\\2\\s*(>=|>)\\s*(\\d+)\\s*;?\\s*$");

  private static String pagedRownum(String sql, List<String> notes) {
    Matcher m = PAGED.matcher(sql);
    if (!m.matches()) return sql;
    int end = Integer.parseInt(m.group(5)) - (m.group(4).equals("<") ? 1 : 0);
    int start = Integer.parseInt(m.group(7)) - (m.group(6).equals(">=") ? 1 : 0);
    if (end <= start) return sql;
    notes.add("ROWNUM 페이징 → LIMIT " + (end - start) + " OFFSET " + start);
    return m.group(3).strip() + "\n LIMIT " + (end - start) + " OFFSET " + start;
  }

  /** UPDATE 표 별칭 SET 별칭.칼럼 = … → SET 칼럼 = …(PostgreSQL 은 SET 왼쪽에 별칭을 못 쓴다). SET 맨 바깥 쉼표 뒤 왼쪽 이름만 바꾼다. */
  private static String updateSetAlias(String sql, List<String> notes) {
    List<SqlParts.Part> parts = SqlParts.split(sql);
    if (parts.isEmpty() || !parts.get(0).key().equals("UPDATE")) return sql;
    String[] head = parts.get(0).body().strip().split("\\s+");
    SqlParts.Part set = SqlParts.find(parts, "SET");
    if (head.length < 2 || set == null) return sql;
    String alias = head[head.length - 1];
    String masked = SqlParts.mask(sql);
    int[] depth = SqlParts.depth(masked);
    Pattern p = Pattern.compile("(?i)(^|,)(\\s*)" + Pattern.quote(alias) + "\\s*\\.\\s*");
    Matcher m = p.matcher(masked);
    m.region(set.bodyStart(), set.end());
    m.useAnchoringBounds(true);
    StringBuilder out = new StringBuilder();
    int last = 0;
    int count = 0;
    while (m.find()) {
      if (depth[m.start()] != depth[set.bodyStart()]) continue;
      out.append(sql, last, m.end(2));
      last = m.end();
      count++;
    }
    if (count == 0) return sql;
    out.append(sql.substring(last));
    notes.add("UPDATE SET 왼쪽 별칭(" + alias + ".) 뺌 " + count + "곳");
    return out.toString();
  }

  /** 맨 바깥 WHERE 의 「ROWNUM <= n」 · 「ROWNUM < n」 을 빼고 끝에 LIMIT n. */
  private static String rownum(String sql, List<String> notes) {
    List<SqlParts.Part> parts = SqlParts.split(sql);
    SqlParts.Part where = SqlParts.find(SqlParts.mainBlock(parts), "WHERE");
    if (where == null) return sql;
    List<String> conds = SqlParts.splitAnd(where.body());
    Integer limit = null;
    List<String> keep = new ArrayList<>();
    Pattern p = Pattern.compile("(?i)ROWNUM\\s*(<=|<)\\s*(\\d+)");
    for (String c : conds) {
      Matcher m = p.matcher(c.strip());
      if (limit == null && m.matches()) {
        int n = Integer.parseInt(m.group(2));
        limit = m.group(1).equals("<") ? n - 1 : n;
      } else {
        keep.add(c.strip());
      }
    }
    if (limit == null) return sql;
    String head = sql.substring(0, where.start()).stripTrailing();
    String whereText = keep.isEmpty() ? "" : "\n WHERE " + String.join("\n   AND ", keep);
    String rest = sql.substring(where.end()).strip();
    if (rest.equals(";")) rest = "";
    notes.add("ROWNUM <= " + limit + " → LIMIT " + limit + " (정렬이 있으면 ROWNUM 은 정렬 전에 잘랐으니 결과가 다를 수 있습니다)");
    return head + whereText + (rest.isEmpty() ? "" : "\n " + rest) + "\n LIMIT " + limit;
  }

  /** 맨 바깥의 LIMIT n [OFFSET m] → ROWNUM 감싸기. */
  private static String limit(String sql, List<String> notes) {
    List<SqlParts.Part> parts = SqlParts.split(sql);
    SqlParts.Part lim = SqlParts.find(parts, "LIMIT");
    if (lim == null) return sql;
    Matcher n = INT.matcher(lim.body());
    if (!n.lookingAt()) {
      notes.add("직접 확인: LIMIT 값이 숫자가 아니라 바꾸지 않았습니다.");
      return sql;
    }
    SqlParts.Part off = SqlParts.find(parts, "OFFSET");
    int offset = 0;
    if (off != null) {
      Matcher o = INT.matcher(off.body());
      if (o.lookingAt()) offset = Integer.parseInt(o.group());
    }
    int cut = Math.min(lim.start(), off == null ? lim.start() : off.start());
    String inner = sql.substring(0, cut).strip();
    notes.add("LIMIT → ROWNUM 감싸기");
    return QueryBuilder.page(DbKind.ORACLE, inner, offset, Integer.parseInt(n.group()));
  }

  /** FROM 없는 맨 바깥 SELECT 에 FROM DUAL. */
  private static String dual(String sql, List<String> notes) {
    List<SqlParts.Part> parts = SqlParts.mainBlock(SqlParts.split(sql));
    if (parts.isEmpty() || !parts.get(0).key().equals("SELECT") || SqlParts.find(parts, "FROM") != null) return sql;
    SqlParts.Part sel = parts.get(0);
    notes.add("FROM 없는 SELECT → FROM DUAL");
    return sql.substring(0, sel.end()).stripTrailing() + "\n  FROM DUAL" + (sel.end() < sql.length() ? "\n " + sql.substring(sel.end()).strip() : "");
  }

  private static final Pattern CAST = Pattern.compile("(?i)([A-Za-z_][\\w$#.]*|'[^']*'|\\))\\s*::\\s*([A-Za-z]+(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?)");

  /** x::type → CAST(x AS 오라클형). 괄호 식 뒤의 :: 는 괄호까지 감싼다. */
  private static String casts(String sql, List<String> notes) {
    int count = 0;
    String s = sql;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String masked = SqlParts.mask(s);
      Matcher m = CAST.matcher(masked);
      if (!m.find()) break;
      int start = m.start(1);
      if (m.group(1).equals(")")) {
        start = openOf(masked, m.start(1));
        if (start < 0) break;
        while (start > 0 && (Character.isLetterOrDigit(masked.charAt(start - 1)) || masked.charAt(start - 1) == '_')) start--;
      }
      String expr = s.substring(start, m.end(1));
      s = s.substring(0, start) + "CAST(" + expr + " AS " + oracleType(s.substring(m.start(2), m.end(2))) + ")" + s.substring(m.end());
      count++;
    }
    if (count > 0) notes.add(":: 형 바꾸기 → CAST " + count + "곳");
    return s;
  }

  private static int openOf(String masked, int close) {
    int depth = 0;
    for (int i = close; i >= 0; i--) {
      if (masked.charAt(i) == ')') depth++;
      if (masked.charAt(i) == '(' && --depth == 0) return i;
    }
    return -1;
  }

  private static String oracleType(String pg) {
    String t = pg.strip().toUpperCase(Locale.ROOT);
    if (t.startsWith("VARCHAR") || t.startsWith("CHARACTER VARYING")) return t.contains("(") ? t.replace("VARCHAR", "VARCHAR2") : "VARCHAR2(4000)";
    if (t.equals("TEXT")) return "VARCHAR2(4000)";
    if (t.equals("INT") || t.equals("INTEGER") || t.equals("BIGINT") || t.equals("SMALLINT") || t.startsWith("NUMERIC") || t.startsWith("DECIMAL")) {
      return t.startsWith("NUMERIC") || t.startsWith("DECIMAL") ? t.replaceFirst("NUMERIC|DECIMAL", "NUMBER") : "NUMBER";
    }
    if (t.startsWith("TIMESTAMP")) return "TIMESTAMP";
    return t;
  }

  /** 낱말(가린 글에서 대소문자 무시) 바꾸기. 바뀐 곳이 있으면 「what n곳」. */
  private static String words(String sql, String regex, String replacement, String what, List<String> notes) {
    return regex(sql, "(?<![\\w$#.])(?:" + regex + ")(?![\\w$#])", m -> replacement, what, notes);
  }

  /** 가린 글에서 찾아 원문을 바꾼다. f 가 null 을 주면 그곳은 두고, what 이 비면 알리지 않는다. 그룹 값은 원문에서 읽는다. */
  private static String regex(String sql, String regex, Function<Matcher, String> f, String what, List<String> notes) {
    String masked = SqlParts.mask(sql);
    Matcher m = Pattern.compile("(?i)" + regex).matcher(masked);
    Matcher original = Pattern.compile("(?i)" + regex).matcher(sql);
    StringBuilder out = new StringBuilder();
    int last = 0;
    int count = 0;
    while (m.find()) {
      original.region(m.start(), sql.length());
      String text = original.lookingAt() ? f.apply(original) : null;
      if (text == null) continue;
      out.append(sql, last, m.start()).append(text);
      last = m.end();
      count++;
    }
    if (count == 0) return sql;
    out.append(sql.substring(last));
    if (!what.isEmpty()) notes.add(what + " " + count + "곳");
    return out.toString();
  }

  private record Call(int start, int end, List<String> args) {}

  /** limit 앞에서 가장 뒤에 있는 name( … ) 호출. */
  private static Call lastCall(String sql, String name, int limit) {
    String masked = SqlParts.mask(sql);
    Matcher m = Pattern.compile("(?i)(?<![\\w$#.])" + name + "\\s*\\(").matcher(masked);
    Call last = null;
    while (m.find() && m.start() < limit) {
      int open = m.end() - 1;
      int close = SqlParts.matching(masked, open);
      if (close < 0) continue;
      last = new Call(m.start(), close + 1, SqlParts.splitCommas(sql.substring(open + 1, close)));
    }
    return last;
  }

  /** name( … ) 호출을 뒤에서부터 f 로 바꾼다(안쪽 같은 이름 호출도 바뀐다). f 가 null 이면 그 호출은 두고 직접 확인으로 알린다. */
  private static String calls(String sql, String name, Function<List<String>, String> f, String what, List<String> notes) {
    String s = sql;
    int limit = Integer.MAX_VALUE;
    int count = 0;
    int skipped = 0;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      Call c = lastCall(s, name, limit);
      if (c == null) break;
      String text = f.apply(c.args);
      if (text == null) {
        skipped++;
      } else {
        s = s.substring(0, c.start) + text + s.substring(c.end);
        count++;
      }
      limit = c.start;
    }
    if (count > 0 && !what.isEmpty()) notes.add(what + " " + count + "곳");
    if (skipped > 0) notes.add("직접 확인: " + name + " " + skipped + "곳은 인자 꼴이 달라 그대로 두었습니다.");
    return s;
  }
}
