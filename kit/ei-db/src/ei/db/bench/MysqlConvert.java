package ei.db.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 글 → MySQL 8 글. 예제 모음이 MySQL 접속에 보일 글을 PostgreSQL 판에서 만든다. IntelliJ 에 기대지 않는다.
 * 문자열 · 주석 안은 건드리지 않는다(가린 글에서 찾고 원문을 바꾼다).
 * 바꾸는 것: "이름" → `이름`, :: · CAST 의 타입, EXTRACT(EPOCH · ISODOW · DOW · DOY), DATE_TRUNC, TO_CHAR · TO_DATE 서식,
 * INTERVAL 'n 단위', 날짜 ± 정수 · 날짜 - 날짜, ||(CONCAT), ILIKE · ~ 정규식, STRING_AGG, 집계 FILTER (WHERE), NULLS FIRST · LAST,
 * LENGTH(글자 수) · STRPOS · CHR · RANDOM · TRUNC · SPLIT_PART · CURRENT_SCHEMA, IS [NOT] DISTINCT FROM, FETCH FIRST.
 * 이 밖의 꼴(GENERATE_SERIES · DISTINCT ON · FULL OUTER JOIN · 배열 · pg_ 카탈로그 등)은 Examples 가 MySQL 글을 따로 둔다.
 * 날짜인지는 이름으로 가린다: CURRENT_DATE, 날짜를 돌려주는 식(CAST … AS DATE · DATE( · STR_TO_DATE( 등), 끝이 _DT · _DATE 인 칼럼.
 */
final class MysqlConvert {
  private MysqlConvert() {}

  private static final int MAX_PASSES = 2000;
  /** 예제 별칭에 나오는 MySQL 8 예약어(별칭으로 쓰면 백틱이 필요하다). */
  private static final String RESERVED = "CHANGE|RANK|ROWS|GROUPS|KEY|KEYS|LEAD|LAG|CUME_DIST|DENSE_RANK|ROW_NUMBER|NTILE|PERCENT_RANK|FIRST_VALUE|LAST_VALUE"
      + "|NTH_VALUE|SYSTEM|WINDOW|OVER|USAGE|READS|LOAD|RANGE|CONDITION|DIV|INTERVAL|LEAVE|LOOP|REPEAT|RETURN|SIGNAL|SPATIAL|STORED|VIRTUAL|LINES|OPTION|ROW|OF";
  /** PostgreSQL 날짜 서식 낱말 → MySQL DATE_FORMAT 서식. 긴 것부터 맞춘다. */
  private static final String[][] DATE_TOKENS = {
      {"HH24", "%H"}, {"IYYY", "%x"}, {"YYYY", "%Y"}, {"HH12", "%h"}, {"YY", "%y"}, {"MM", "%m"}, {"DD", "%d"}, {"HH", "%h"},
      {"MI", "%i"}, {"SS", "%s"}, {"MS", "%f"}, {"IW", "%v"}, {"Dy", "%a"}, {"AM", "%p"}, {"PM", "%p"}};
  /** FM(앞 0 빼기)일 때 달 · 날. */
  private static final Map<String, String> FM_TOKENS = Map.of("%m", "%c", "%d", "%e", "%H", "%k", "%h", "%l");
  private static final Pattern NUMBER_FORMAT = Pattern.compile("(?i)^(FM)?[9,]*0*(?:\\.(0+))?$");
  private static final Pattern ZERO_FORMAT = Pattern.compile("(?i)^(FM)?(0+)$");
  private static final Pattern UNIT = Pattern.compile("(?i)^(MICROSECOND|SECOND|MINUTE|HOUR|DAY|WEEK|MONTH|QUARTER|YEAR)S?$");
  private static final Pattern NUMBER = Pattern.compile("^[0-9.]+$");
  /** 지금 시각. 시각 - 날짜 칼럼은 날 수가 아니라 걸린 시간이라 DATEDIFF 로 바꾸지 않는다. */
  private static final Pattern NOW = Pattern.compile("(?i)^(?:NOW\\s*\\(\\s*\\)|CURRENT_TIMESTAMP|LOCALTIMESTAMP|SYSDATE\\s*\\(\\s*\\))$");
  private static final Pattern DATE_NAME = Pattern.compile("(?i)^(?:[\\w$#`]+\\.)?`?(?:\\w*_(?:DT|DATE|START|END)|DT|DATE)`?$");
  private static final Pattern DATE_CALL = Pattern.compile("(?is)^(?:DATE|STR_TO_DATE|MAKEDATE|CURDATE|LAST_DAY|DATE_ADD|DATE_SUB)\\s*\\(.*|^CAST\\s*\\(.*\\sAS\\s+DATE\\s*\\)$");
  /** 첫 인자가 날짜면 날짜인 펑션(집계 · 창). */
  private static final Pattern FIRST_ARG_DATE = Pattern.compile("(?i)^(?:MAX|MIN|LAG|LEAD|FIRST_VALUE|LAST_VALUE)$");
  /** 인자 중 하나라도 날짜면 날짜인 펑션. */
  private static final Pattern ANY_ARG_DATE = Pattern.compile("(?i)^(?:COALESCE|GREATEST|LEAST|NVL)$");
  private static final Pattern KEYWORD_BEFORE = Pattern.compile("(?i)^(SELECT|WHERE|AND|OR|NOT|THEN|ELSE|WHEN|ON|BY|AS|IN|IS|LIKE|BETWEEN|RETURN|SET|VALUES|HAVING|CASE|DISTINCT|INTERVAL)$");

  static String convert(String pg) {
    return comments(backslashes(statement(pg, false)));
  }

  /** 줄 주석 안의 PostgreSQL 글도 같은 규칙으로 바꾼다(예제 주석에 「지난달: … DATE_TRUNC …」 같은 글이 있다). */
  private static String comments(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        int k = i + 1;
        while (k < sql.length() && !(sql.charAt(k) == c && (k + 1 >= sql.length() || sql.charAt(k + 1) != c))) k += sql.charAt(k) == c ? 2 : 1;
        int end = Math.min(sql.length(), k + 1);
        out.append(sql, i, end);
        i = end;
      } else if (sql.startsWith("/*", i)) {
        int k = sql.indexOf("*/", i + 2);
        int end = k < 0 ? sql.length() : k + 2;
        out.append(sql, i, end);
        i = end;
      } else if (sql.startsWith("--", i)) {
        int k = sql.indexOf('\n', i);
        int end = k < 0 ? sql.length() : k;
        out.append("--").append(statement(sql.substring(i + 2, end), true));
        i = end;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /** MySQL 은 기본 설정에서 '…' 안의 \ 를 이스케이프로 읽는다. 글자 그대로 쓰려면 \\ 로 겹친다. */
  private static String backslashes(String sql) {
    String masked = SqlParts.mask(sql);
    StringBuilder out = new StringBuilder(sql.length());
    boolean in = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (masked.charAt(i) == '\'' && c == '\'') in = !in;
      out.append(c);
      if (in && c == '\\' && masked.charAt(i) == ' ') out.append('\\');
    }
    return out.toString();
  }

  /** comment 면 주석 글: ~ 는 「부터 ~ 까지」 뜻이라 정규식으로 바꾸지 않는다. */
  private static String statement(String pg, boolean comment) {
    List<String> notes = new ArrayList<>();
    String s = pg;
    s = DialectConvert.regex(s, "\"[^\"\\n]*\"", m -> "`" + m.group().substring(1, m.group().length() - 1) + "`", "", notes);
    s = DialectConvert.casts(s, MysqlConvert::type, notes);
    s = DialectConvert.calls(s, "CAST", MysqlConvert::cast, "", notes);
    s = DialectConvert.calls(s, "EXTRACT", MysqlConvert::extract, "", notes);
    s = DialectConvert.calls(s, "DATE_TRUNC", MysqlConvert::dateTrunc, "", notes);
    s = DialectConvert.calls(s, "TO_CHAR", MysqlConvert::toChar, "", notes);
    s = DialectConvert.calls(s, "TO_DATE", a -> a.size() == 2 ? "STR_TO_DATE(" + a.get(0) + ", " + dateFormat(a.get(1)) + ")" : null, "", notes);
    s = DialectConvert.calls(s, "TO_TIMESTAMP", a -> a.size() == 2 ? "STR_TO_DATE(" + a.get(0) + ", " + dateFormat(a.get(1)) + ")" : null, "", notes);
    s = intervals(s, notes);
    s = timesInterval(s);
    s = dateArithmetic(s);
    s = concat(s);
    s = DialectConvert.words(s, "NOT\\s+ILIKE", "NOT LIKE", "", notes);
    s = DialectConvert.words(s, "ILIKE", "LIKE", "", notes);
    if (!comment) {
      s = DialectConvert.regex(s, "!~\\*?", m -> "NOT REGEXP", "", notes);
      s = DialectConvert.regex(s, "(?<![!<>=])~\\*?", m -> "REGEXP", "", notes);
    }
    s = filters(s);
    s = DialectConvert.calls(s, "STRING_AGG", MysqlConvert::stringAgg, "", notes);
    s = DialectConvert.calls(s, "ROLLUP", a -> String.join(", ", a) + " WITH ROLLUP", "", notes);
    s = nulls(s, notes);
    s = DialectConvert.regex(s, "(?<![\\w$#.])LENGTH\\s*\\(", m -> "CHAR_LENGTH(", "", notes);
    s = DialectConvert.calls(s, "STRPOS", a -> a.size() == 2 ? "LOCATE(" + a.get(1) + ", " + a.get(0) + ")" : null, "", notes);
    s = DialectConvert.calls(s, "LPAD", a -> a.size() == 2 ? "LPAD(" + a.get(0) + ", " + a.get(1) + ", ' ')" : null, "", notes);
    s = DialectConvert.calls(s, "RPAD", a -> a.size() == 2 ? "RPAD(" + a.get(0) + ", " + a.get(1) + ", ' ')" : null, "", notes);
    s = DialectConvert.calls(s, "LTRIM", a -> a.size() == 2 && oneChar(a.get(1)) ? "TRIM(LEADING " + a.get(1) + " FROM " + a.get(0) + ")" : null, "", notes);
    s = DialectConvert.calls(s, "RTRIM", a -> a.size() == 2 && oneChar(a.get(1)) ? "TRIM(TRAILING " + a.get(1) + " FROM " + a.get(0) + ")" : null, "", notes);
    s = DialectConvert.calls(s, "BTRIM", a -> a.size() == 1 ? "TRIM(" + a.get(0) + ")" : a.size() == 2 && oneChar(a.get(1)) ? "TRIM(BOTH " + a.get(1) + " FROM " + a.get(0) + ")" : null, "", notes);
    s = DialectConvert.regex(s, "(?<![\\w$#.])AS\\s+(" + RESERVED + ")(?![\\w$#])", m -> "AS `" + m.group(1) + "`", "", notes);
    s = DialectConvert.calls(s, "CHR", a -> a.size() == 1 ? "CHAR(" + a.get(0) + ")" : null, "", notes);
    s = DialectConvert.regex(s, "(?<![\\w$#.])RANDOM\\s*\\(\\s*\\)", m -> "RAND()", "", notes);
    s = DialectConvert.calls(s, "TRUNC", a -> a.size() == 1 ? "TRUNCATE(" + a.get(0) + ", 0)" : a.size() == 2 ? "TRUNCATE(" + a.get(0) + ", " + a.get(1) + ")" : null, "", notes);
    s = DialectConvert.calls(s, "SPLIT_PART", a -> a.size() == 3
        ? "SUBSTRING_INDEX(SUBSTRING_INDEX(" + a.get(0) + ", " + a.get(1) + ", " + a.get(2) + "), " + a.get(1) + ", -1)" : null, "", notes);
    s = DialectConvert.words(s, "CURRENT_SCHEMA(?:\\s*\\(\\s*\\))?", "DATABASE()", "", notes);
    s = DialectConvert.regex(s, "IS\\s+NOT\\s+DISTINCT\\s+FROM", m -> "<=>", "", notes);
    s = distinctFrom(s);
    s = DialectConvert.regex(s, "OFFSET\\s+(\\d+)\\s+ROWS?\\s+FETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+ONLY", m -> "LIMIT " + m.group(2) + " OFFSET " + m.group(1), "", notes);
    s = DialectConvert.regex(s, "FETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+ONLY", m -> "LIMIT " + m.group(1), "", notes);
    return s;
  }

  /** PostgreSQL 타입 → MySQL CAST 타입. */
  static String type(String pg) {
    String t = pg.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    if (t.startsWith("VARCHAR") || t.startsWith("CHARACTER VARYING") || t.equals("TEXT") || t.startsWith("CHAR")) {
      int open = t.indexOf('(');
      return open < 0 ? "CHAR" : "CHAR" + t.substring(open);
    }
    if (t.equals("INT") || t.equals("INTEGER") || t.equals("BIGINT") || t.equals("SMALLINT") || t.matches("INT[248]")) return "SIGNED";
    if (t.startsWith("NUMERIC") || t.startsWith("DECIMAL")) {
      int open = t.indexOf('(');
      return open < 0 ? "DECIMAL(38, 10)" : "DECIMAL" + t.substring(open);
    }
    if (t.equals("DOUBLE PRECISION") || t.equals("FLOAT8") || t.equals("REAL") || t.equals("FLOAT4")) return "DOUBLE";
    if (t.startsWith("TIMESTAMP")) return "DATETIME";
    if (t.equals("BOOLEAN") || t.equals("BOOL")) return "UNSIGNED";
    return t;
  }

  /** CAST(x AS 타입) 의 타입만 바꾼다. 꼴이 다르면 null(그대로 둠). */
  private static String cast(List<String> a) {
    if (a.size() != 1) return null;
    Matcher m = Pattern.compile("(?is)^(.*)\\s+AS\\s+([A-Za-z][\\w ]*(?:\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?)\\s*$").matcher(a.get(0));
    if (!m.matches()) return null;
    return "CAST(" + m.group(1) + " AS " + type(m.group(2)) + ")";
  }

  private static String extract(List<String> a) {
    if (a.size() != 1) return null;
    Matcher m = Pattern.compile("(?is)^\\s*(\\w+)\\s+FROM\\s+(.*)$").matcher(a.get(0));
    if (!m.matches()) return null;
    String field = m.group(1).toUpperCase(Locale.ROOT);
    String x = m.group(2).strip();
    return switch (field) {
      case "EPOCH" -> epoch(x);
      case "ISODOW" -> "(WEEKDAY(" + x + ") + 1)";
      case "DOW" -> "(DAYOFWEEK(" + x + ") - 1)";
      case "DOY" -> "DAYOFYEAR(" + x + ")";
      case "WEEK" -> "WEEK(" + x + ", 3)";
      default -> null;
    };
  }

  /** EXTRACT(EPOCH FROM a - b) 은 두 시각 사이 초, EXTRACT(EPOCH FROM x) 는 1970 부터의 초. */
  private static String epoch(String expr) {
    String x = expr.strip();
    while (x.startsWith("(") && SqlParts.matching(SqlParts.mask(x), 0) == x.length() - 1) x = x.substring(1, x.length() - 1).strip();
    String masked = SqlParts.mask(x);
    int[] depth = SqlParts.depth(masked);
    for (int i = masked.length() - 1; i > 0; i--) {
      if (masked.charAt(i) == '-' && depth[i] == 0) {
        return "TIMESTAMPDIFF(SECOND, " + x.substring(i + 1).strip() + ", " + x.substring(0, i).strip() + ")";
      }
    }
    return "UNIX_TIMESTAMP(" + x + ")";
  }

  private static String dateTrunc(List<String> a) {
    if (a.size() != 2) return null;
    String x = a.get(1).strip();
    return switch (a.get(0).strip().replace("'", "").toUpperCase(Locale.ROOT)) {
      case "DAY" -> "DATE(" + x + ")";
      case "WEEK" -> "(DATE(" + x + ") - INTERVAL WEEKDAY(" + x + ") DAY)";
      case "MONTH" -> "DATE(DATE_FORMAT(" + x + ", '%Y-%m-01'))";
      case "QUARTER" -> "(MAKEDATE(YEAR(" + x + "), 1) + INTERVAL (QUARTER(" + x + ") - 1) QUARTER)";
      case "YEAR" -> "MAKEDATE(YEAR(" + x + "), 1)";
      case "HOUR" -> "CAST(DATE_FORMAT(" + x + ", '%Y-%m-%d %H:00:00') AS DATETIME)";
      case "MINUTE" -> "CAST(DATE_FORMAT(" + x + ", '%Y-%m-%d %H:%i:00') AS DATETIME)";
      default -> null;
    };
  }

  private static String toChar(List<String> a) {
    if (a.size() == 1) return "CAST(" + a.get(0) + " AS CHAR)";
    if (a.size() != 2) return null;
    String x = a.get(0).strip();
    String f = a.get(1).strip();
    if (!f.startsWith("'") || !f.endsWith("'")) return null;
    String body = f.substring(1, f.length() - 1);
    if (body.equalsIgnoreCase("Q")) return "CAST(QUARTER(" + x + ") AS CHAR)";
    if (body.equalsIgnoreCase("W")) return "CAST(FLOOR((DAY(" + x + ") - 1) / 7) + 1 AS CHAR)";
    if (body.equalsIgnoreCase("D")) return "CAST(DAYOFWEEK(" + x + ") AS CHAR)";
    Matcher zero = ZERO_FORMAT.matcher(body);
    if (zero.matches()) return "LPAD(" + x + ", " + zero.group(2).length() + ", '0')";
    Matcher num = NUMBER_FORMAT.matcher(body);
    if (num.matches() && body.indexOf('9') >= 0 || num.matches() && body.indexOf('.') >= 0) {
      int scale = num.group(2) == null ? 0 : num.group(2).length();
      return body.indexOf(',') >= 0 ? "FORMAT(" + x + ", " + scale + ")" : "CAST(ROUND(" + x + ", " + scale + ") AS CHAR)";
    }
    return "DATE_FORMAT(" + x + ", " + dateFormat(f) + ")";
  }

  /** '서식'(작은따옴표 포함) → MySQL 서식 글. 큰따옴표로 감싼 글은 그대로 둔다. */
  static String dateFormat(String quoted) {
    String f = quoted.strip();
    if (!f.startsWith("'") || !f.endsWith("'")) return f;
    String body = f.substring(1, f.length() - 1);
    StringBuilder out = new StringBuilder();
    boolean fm = false;
    int i = 0;
    while (i < body.length()) {
      if (body.startsWith("FM", i)) {
        fm = true;
        i += 2;
        continue;
      }
      if (body.charAt(i) == '"') {
        int close = body.indexOf('"', i + 1);
        String text = close < 0 ? body.substring(i + 1) : body.substring(i + 1, close);
        out.append(text.replace("%", "%%"));
        i = close < 0 ? body.length() : close + 1;
        continue;
      }
      String hit = null;
      for (String[] t : DATE_TOKENS) {
        if (body.startsWith(t[0], i)) {
          hit = t[1];
          i += t[0].length();
          break;
        }
      }
      if (hit == null) {
        char c = body.charAt(i++);
        out.append(c == '%' ? "%%" : String.valueOf(c));
      } else {
        out.append(fm ? FM_TOKENS.getOrDefault(hit, hit) : hit);
      }
    }
    return "'" + out + "'";
  }

  private static final Pattern INTERVAL = Pattern.compile("(?i)(?<![\\w$#.])INTERVAL\\s+'");
  private static final Pattern ONE_UNIT = Pattern.compile("(?i)^\\s*(\\d+)\\s+([A-Za-z]+)\\s*$");
  private static final Pattern TWO_UNITS = Pattern.compile("(?i)^\\s*(\\d+)\\s+([A-Za-z]+)\\s*-\\s*(\\d+)\\s+([A-Za-z]+)\\s*$");

  /**
   * INTERVAL 'n 단위' → INTERVAL n 단위. 'n 단위 - m 단위' 는 앞의 + · - 와 함께 둘로 나눈다(앞이 + 면 + … - …, - 면 - … + …).
   * 글 안이 가린 글에서는 보이지 않으므로 따옴표 자리만 가린 글에서 찾고 글은 원문에서 읽는다.
   */
  private static String intervals(String sql, List<String> notes) {
    String s = sql;
    int from = 0;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String masked = SqlParts.mask(s);
      Matcher m = INTERVAL.matcher(masked);
      if (!m.find(from)) break;
      int open = m.end() - 1;
      int close = masked.indexOf('\'', open + 1);
      if (close < 0) break;
      from = m.end();
      String text = s.substring(open + 1, close);
      Matcher one = ONE_UNIT.matcher(text);
      Matcher two = TWO_UNITS.matcher(text);
      String rep;
      int start = m.start();
      if (one.matches() && unit(one.group(2)) != null) {
        rep = "INTERVAL " + one.group(1) + " " + unit(one.group(2));
      } else if (two.matches() && unit(two.group(2)) != null && unit(two.group(4)) != null) {
        int k = start - 1;
        while (k >= 0 && Character.isWhitespace(masked.charAt(k))) k--;
        if (k < 0 || (masked.charAt(k) != '+' && masked.charAt(k) != '-')) continue;
        String back = masked.charAt(k) == '+' ? "-" : "+";
        rep = "INTERVAL " + two.group(1) + " " + unit(two.group(2)) + " " + back + " INTERVAL " + two.group(3) + " " + unit(two.group(4));
      } else {
        continue;
      }
      s = s.substring(0, start) + rep + s.substring(close + 1);
      from = start + rep.length();
    }
    return s;
  }

  private static String unit(String word) {
    Matcher m = UNIT.matcher(word);
    return m.matches() ? m.group(1).toUpperCase(Locale.ROOT) : null;
  }

  /** x * INTERVAL n 단위 → INTERVAL (x) * n 단위. */
  private static String timesInterval(String sql) {
    String s = sql;
    Pattern p = Pattern.compile("(?i)\\*\\s*INTERVAL\\s+(\\d+)\\s+([A-Z]+)(?![\\w$#])");
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String masked = SqlParts.mask(s);
      Matcher m = p.matcher(masked);
      if (!m.find()) break;
      int start = operandStart(masked, m.start());
      if (start < 0) break;
      String x = s.substring(start, m.start()).strip();
      String n = m.group(1).equals("1") ? "" : " * " + m.group(1);
      s = s.substring(0, start) + "INTERVAL (" + x + ")" + n + " " + m.group(2).toUpperCase(Locale.ROOT) + s.substring(m.end());
    }
    return s;
  }

  /**
   * 날짜 - 날짜 → DATEDIFF(앞, 뒤)(날 수), 날짜 ± 날짜 아닌 값 → 날짜 ± INTERVAL 값 DAY.
   * MySQL 은 DATE ± 정수를 숫자(20261130 + 1)로 셈해 값이 틀린다. 뒤가 INTERVAL 이면 그대로 둔다.
   */
  private static String dateArithmetic(String sql) {
    String s = sql;
    int from = 0;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String masked = SqlParts.mask(s);
      int at = nextOperator(masked, from);
      if (at < 0) break;
      from = at + 1;
      int ls = operandStart(masked, at);
      if (ls < 0) continue;
      String left = s.substring(ls, at).strip();
      if (KEYWORD_BEFORE.matcher(left).matches()) continue;
      int rs = skipBlank(masked, at + 1);
      int re = operandEnd(masked, rs);
      if (re <= rs) continue;
      // 곱셈 · 나눗셈은 + · - 보다 먼저 묶인다(CURRENT_DATE - n * 7).
      boolean compound = false;
      for (int k = skipBlank(masked, re); k < masked.length() && (masked.charAt(k) == '*' || masked.charAt(k) == '/'); k = skipBlank(masked, re)) {
        int ne = operandEnd(masked, k + 1);
        if (ne <= skipBlank(masked, k + 1)) break;
        re = ne;
        compound = true;
      }
      String right = s.substring(rs, re);
      if (right.regionMatches(true, 0, "INTERVAL", 0, "INTERVAL".length())) continue;
      // 앞이 날짜로 안 보여도 뒤가 날짜인 뺄셈은 날 수다(r.FIRST_RCV - p.PO_DT). 앞이 INTERVAL 의 단위면 날짜 식의 꼬리라 건너뛴다.
      boolean dateMinusDate = masked.charAt(at) == '-' && isDate(right) && !UNIT.matcher(left).matches() && !NUMBER.matcher(left).matches()
          && !NOW.matcher(left).matches();
      if (!isDate(left) && !dateMinusDate) continue;
      String rep;
      int start;
      if (isDate(right)) {
        if (masked.charAt(at) != '-') continue;
        rep = "DATEDIFF(" + left + ", " + right + ")";
        start = ls;
      } else {
        rep = s.substring(ls, rs) + "INTERVAL " + (compound ? "(" + right + ")" : right) + " DAY";
        start = ls;
      }
      s = s.substring(0, start) + rep + s.substring(re);
      from = start + rep.length();
    }
    return s;
  }

  /** from 뒤 첫 이항 + · -(앞에 피연산자가 있는 것). 없으면 -1. */
  private static int nextOperator(String masked, int from) {
    for (int i = from; i < masked.length(); i++) {
      char c = masked.charAt(i);
      if (c != '+' && c != '-') continue;
      int k = i - 1;
      while (k >= 0 && Character.isWhitespace(masked.charAt(k))) k--;
      if (k < 0) continue;
      char p = masked.charAt(k);
      if (Character.isLetterOrDigit(p) || p == '_' || p == ')' || p == '\'' || p == '`' || p == '$' || p == '#') return i;
    }
    return -1;
  }

  /** 날짜(시각 아님)로 보이는 식. */
  static boolean isDate(String expr) {
    String e = expr.strip();
    while (e.startsWith("(") && SqlParts.matching(SqlParts.mask(e), 0) == e.length() - 1) e = e.substring(1, e.length() - 1).strip();
    String up = e.toUpperCase(Locale.ROOT);
    if (up.equals("CURRENT_DATE") || up.startsWith("DATE '")) return true;
    if (DATE_NAME.matcher(e).matches()) return true;
    if (DATE_CALL.matcher(e).matches() && SqlParts.matching(SqlParts.mask(e), e.indexOf('(')) == e.length() - 1) return true;
    String masked = SqlParts.mask(e);
    int open = masked.indexOf('(');
    if (open <= 0) return false;
    int close = SqlParts.matching(masked, open);
    String rest = close < 0 ? "x" : masked.substring(close + 1).strip();
    if (close < 0 || !(rest.isEmpty() || rest.regionMatches(true, 0, "OVER", 0, 4))) return false;
    String fn = e.substring(0, open).strip();
    List<String> args = SqlParts.splitCommas(e.substring(open + 1, close));
    if (FIRST_ARG_DATE.matcher(fn).matches()) return !args.isEmpty() && isDate(args.get(0));
    if (ANY_ARG_DATE.matcher(fn).matches()) {
      for (String a : args) if (isDate(a)) return true;
    }
    return false;
  }

  /** a || b || c → CONCAT(a, b, c). MySQL 기본 설정에서 || 는 OR 다. */
  private static String concat(String sql) {
    String s = sql;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String masked = SqlParts.mask(s);
      int at = masked.indexOf("||");
      if (at < 0) break;
      int start = operandStart(masked, at);
      if (start < 0) break;
      List<String> parts = new ArrayList<>();
      parts.add(s.substring(start, at).strip());
      int end;
      int op = at;
      while (true) {
        int rs = skipBlank(masked, op + 2);
        end = operandEnd(masked, rs);
        if (end <= rs) return s;
        parts.add(s.substring(rs, end).strip());
        int next = skipBlank(masked, end);
        if (!masked.startsWith("||", next)) break;
        op = next;
      }
      s = s.substring(0, start) + "CONCAT(" + String.join(", ", parts) + ")" + s.substring(end);
    }
    return s;
  }

  private static String stringAgg(List<String> a) {
    if (a.size() != 2) return null;
    Matcher m = Pattern.compile("(?is)^(.*?)\\s+ORDER\\s+BY\\s+(.+)$").matcher(a.get(1));
    String sep = m.matches() ? m.group(1).strip() : a.get(1).strip();
    String order = m.matches() ? " ORDER BY " + m.group(2).strip() : "";
    return "GROUP_CONCAT(" + a.get(0).strip() + order + " SEPARATOR " + sep + ")";
  }

  /** 집계(x) FILTER (WHERE c) → 집계(CASE WHEN c THEN x END). COUNT(*) 은 SUM(CASE WHEN c THEN 1 ELSE 0 END). */
  private static String filters(String sql) {
    String s = sql;
    Pattern p = Pattern.compile("(?i)\\)\\s*FILTER\\s*\\(\\s*WHERE\\s");
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String masked = SqlParts.mask(s);
      Matcher m = p.matcher(masked);
      if (!m.find()) break;
      int close = m.start();
      int open = DialectConvert.openOf(masked, close);
      int fOpen = masked.indexOf('(', m.start() + 1);
      int fClose = SqlParts.matching(masked, fOpen);
      if (open < 0 || fClose < 0) break;
      int nameStart = open;
      while (nameStart > 0 && Character.isLetter(masked.charAt(nameStart - 1))) nameStart--;
      String fn = s.substring(nameStart, open).strip().toUpperCase(Locale.ROOT);
      String arg = s.substring(open + 1, close).strip();
      String cond = s.substring(m.end(), fClose).strip();
      // 첫 인자만 조건으로 감싼다(STRING_AGG(x, ',' ORDER BY y) 의 구분자 · 순서는 그대로).
      List<String> args = SqlParts.splitCommas(arg);
      String first = args.isEmpty() ? arg : args.get(0);
      String rest = args.size() < 2 ? "" : ", " + String.join(", ", args.subList(1, args.size()));
      String rep = fn.equals("COUNT") && arg.equals("*") ? "SUM(CASE WHEN " + cond + " THEN 1 ELSE 0 END)"
          : fn + "(CASE WHEN " + cond + " THEN " + first + " END" + rest + ")";
      s = s.substring(0, nameStart) + rep + s.substring(fClose + 1);
    }
    return s;
  }

  /** 식 [ASC|DESC] NULLS FIRST|LAST → 식 IS NULL [DESC], 식 [ASC|DESC]. MySQL 은 오름차순에서 NULL 이 앞이다. */
  private static String nulls(String sql, List<String> notes) {
    String s = sql;
    Pattern p = Pattern.compile("(?i)(\\s+(ASC|DESC))?\\s+NULLS\\s+(FIRST|LAST)(?![\\w$#])");
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String masked = SqlParts.mask(s);
      Matcher m = p.matcher(masked);
      if (!m.find()) break;
      int start = operandStart(masked, m.start());
      if (start < 0) break;
      String x = s.substring(start, m.start()).strip();
      String dir = m.group(2) == null ? "" : " " + m.group(2).toUpperCase(Locale.ROOT);
      boolean first = m.group(3).equalsIgnoreCase("FIRST");
      s = s.substring(0, start) + x + " IS NULL" + (first ? " DESC" : "") + ", " + x + dir + s.substring(m.end());
    }
    return s;
  }

  /** a IS DISTINCT FROM b → NOT (a <=> b). */
  private static String distinctFrom(String sql) {
    String s = sql;
    Pattern p = Pattern.compile("(?i)\\s+IS\\s+DISTINCT\\s+FROM\\s+");
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String masked = SqlParts.mask(s);
      Matcher m = p.matcher(masked);
      if (!m.find()) break;
      int start = operandStart(masked, m.start());
      int end = operandEnd(masked, m.end());
      if (start < 0 || end <= m.end()) break;
      s = s.substring(0, start) + "NOT (" + s.substring(start, m.start()).strip() + " <=> " + s.substring(m.end(), end) + ")" + s.substring(end);
    }
    return s;
  }

  /** '한 글자'(작은따옴표 포함)인지. MySQL TRIM 은 글 전체를 한 덩어리로 지우므로 한 글자일 때만 LTRIM(x, 글자) 과 같다. */
  private static boolean oneChar(String quoted) {
    String q = quoted.strip();
    return q.length() == 3 && q.charAt(0) == '\'' && q.charAt(2) == '\'';
  }

  private static int skipBlank(String masked, int i) {
    while (i < masked.length() && Character.isWhitespace(masked.charAt(i))) i++;
    return i;
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '.' || c == ':';
  }

  /** pos 앞(공백 건너뜀) 피연산자의 시작: 이름 · 숫자 · 바인드 · '글' · `이름` · (괄호) · 함수( ) · CASE … END. 없으면 -1. */
  static int operandStart(String masked, int pos) {
    int i = pos - 1;
    while (i >= 0 && Character.isWhitespace(masked.charAt(i))) i--;
    if (i < 0) return -1;
    char c = masked.charAt(i);
    if (c == ')') {
      int open = DialectConvert.openOf(masked, i);
      if (open < 0) return -1;
      i = open - 1;
    } else if (c == '\'' || c == '`') {
      int q = masked.lastIndexOf(c, i - 1);
      if (q < 0) return -1;
      i = q - 1;
    } else if (isNameChar(c)) {
      int end = i;
      while (i >= 0 && isNameChar(masked.charAt(i))) i--;
      if (masked.substring(i + 1, end + 1).equalsIgnoreCase("END")) return caseStart(masked, i + 1);
      return i + 1;
    } else {
      return -1;
    }
    while (i >= 0 && isNameChar(masked.charAt(i))) i--;
    return i + 1;
  }

  /** pos 부터(공백 뒤) 피연산자의 끝(바로 뒤 위치). 없으면 pos. */
  static int operandEnd(String masked, int pos) {
    int i = skipBlank(masked, pos);
    if (i >= masked.length()) return pos;
    char c = masked.charAt(i);
    if (c == '(') {
      int close = SqlParts.matching(masked, i);
      return close < 0 ? pos : close + 1;
    }
    if (c == '\'' || c == '`') {
      int q = masked.indexOf(c, i + 1);
      return q < 0 ? pos : q + 1;
    }
    if (!isNameChar(c) && c != '-') return pos;
    int start = i;
    if (c == '-') i++;
    while (i < masked.length() && isNameChar(masked.charAt(i))) i++;
    String word = masked.substring(start, i);
    if (word.equalsIgnoreCase("CASE")) return caseEnd(masked, start);
    if (word.equalsIgnoreCase("DATE") && i < masked.length() && skipBlank(masked, i) < masked.length() && masked.charAt(skipBlank(masked, i)) == '\'') {
      int q = masked.indexOf('\'', skipBlank(masked, i) + 1);
      return q < 0 ? pos : q + 1;
    }
    if (i < masked.length() && masked.charAt(i) == '(') {
      int close = SqlParts.matching(masked, i);
      if (close < 0) return pos;
      i = close + 1;
      int over = skipBlank(masked, i);
      if (masked.regionMatches(true, over, "OVER", 0, 4)) {
        int open = skipBlank(masked, over + 4);
        if (open < masked.length() && masked.charAt(open) == '(') {
          int oc = SqlParts.matching(masked, open);
          if (oc > 0) i = oc + 1;
        }
      }
    }
    return i;
  }

  private static final Pattern CASE_END = Pattern.compile("(?i)(?<![\\w$#.])(CASE|END)(?![\\w$#])");

  /** end 위치의 END 와 짝인 CASE 시작. 없으면 -1. */
  private static int caseStart(String masked, int end) {
    List<int[]> marks = new ArrayList<>();
    Matcher m = CASE_END.matcher(masked.substring(0, end));
    while (m.find()) marks.add(new int[]{m.start(), m.group(1).equalsIgnoreCase("CASE") ? 1 : -1});
    int need = 1;
    for (int k = marks.size() - 1; k >= 0; k--) {
      need -= marks.get(k)[1];
      if (need == 0) return marks.get(k)[0];
    }
    return -1;
  }

  /** start 의 CASE 와 짝인 END 뒤 위치. 없으면 start. */
  private static int caseEnd(String masked, int start) {
    Matcher m = CASE_END.matcher(masked);
    m.region(start, masked.length());
    int depth = 0;
    while (m.find()) {
      depth += m.group(1).equalsIgnoreCase("CASE") ? 1 : -1;
      if (depth == 0) return m.end();
    }
    return start;
  }
}
