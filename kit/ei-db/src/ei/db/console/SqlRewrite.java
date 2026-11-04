package ei.db.console;

import ei.db.DbKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인덱스를 막는 조건 모양을 같은 뜻의 인덱스를 쓰는 모양으로 바꾼 쿼리를 만든다(느린 곳 탭의 「이렇게 바꾸세요」). IntelliJ 클래스 없이 돈다.
 * 바꾸는 것: TO_CHAR(날짜, '일 · 월 형식') = 값 → 범위, TRUNC(날짜) = 값 → 범위, NVL · COALESCE(칼럼, 기본) = 값 → OR IS NULL,
 * SUBSTR(칼럼, 1, n) = 값 → LIKE 값 || '%'. UPPER · LOWER 는 저장 값이 한 가지 대소문자일 때만 같은 뜻이라 rule "case" 로 따로 알린다.
 * 값은 '문자' · 숫자 · ? · :이름 · #{이름} 만 알아본다. 그 밖(식 · 서브쿼리)은 건드리지 않는다.
 * shortcut: 주석 · 문자열 안의 같은 모양도 바꾼다, 그런 오탐이 보이면 SqlText.code 처럼 위치를 지키는 훑기로 바꾼다.
 */
public final class SqlRewrite {
  private SqlRewrite() {}

  /** 바뀐 곳 하나. before* 는 원래 글, after* 는 바꾼 글 안의 위치. column 은 바꾼 칼럼(별칭 뺀 대문자). */
  public record Change(String rule, String column, int beforeStart, int beforeEnd, int afterStart, int afterEnd) {}

  public record Result(String sql, List<Change> changes) {}

  private static final String COL = "((?:\\w+\\.)?\\w+)";
  private static final String VALUE = "('(?:[^']|'')*'|\\?|:\\w+|#\\{[^}]+\\}|\\d+)";
  private static final Pattern TO_CHAR = Pattern.compile("(?i)\\bTO_CHAR\\s*\\(\\s*" + COL + "\\s*,\\s*'([^']+)'\\s*\\)\\s*=\\s*" + VALUE);
  private static final Pattern TRUNC = Pattern.compile("(?i)\\bTRUNC\\s*\\(\\s*" + COL + "\\s*\\)\\s*=\\s*" + VALUE);
  private static final Pattern NVL = Pattern.compile("(?i)\\b(?:NVL|COALESCE)\\s*\\(\\s*" + COL + "\\s*,\\s*" + VALUE + "\\s*\\)\\s*=\\s*" + VALUE);
  private static final Pattern SUBSTR = Pattern.compile("(?i)\\b(?:SUBSTR|SUBSTRING)\\s*\\(\\s*" + COL + "\\s*,\\s*1\\s*,\\s*(\\d+)\\s*\\)\\s*=\\s*" + VALUE);
  private static final Pattern CASE = Pattern.compile("(?i)\\b(UPPER|LOWER)\\s*\\(\\s*" + COL + "\\s*\\)\\s*=\\s*" + VALUE);
  /** 하루 단위 · 한 달 단위로 읽는 TO_CHAR 형식. 그 밖의 형식은 범위로 바꾸지 않는다. */
  private static final Set<String> DAY_FORMATS = Set.of("YYYYMMDD", "YYYY-MM-DD", "YYYY/MM/DD", "YYYY.MM.DD");
  private static final Set<String> MONTH_FORMATS = Set.of("YYYYMM", "YYYY-MM", "YYYY/MM", "YYYY.MM");

  /** 바꿀 곳이 없으면 changes 가 빈 목록이고 sql 은 원래 글 그대로. */
  public static Result rewrite(String sql, DbKind kind) {
    List<Change> changes = new ArrayList<>();
    StringBuilder out = new StringBuilder();
    int at = 0;
    for (Hit h : hits(sql, kind)) {
      if (h.start < at) continue; // 앞 바꿈과 겹침
      out.append(sql, at, h.start);
      int afterStart = out.length();
      out.append(h.replacement);
      changes.add(new Change(h.rule, bare(h.column), h.start, h.end, afterStart, out.length()));
      at = h.end;
    }
    out.append(sql.substring(at));
    return new Result(out.toString(), changes);
  }

  private record Hit(int start, int end, String rule, String column, String replacement) {}

  /** 원래 글 순서대로. */
  private static List<Hit> hits(String sql, DbKind kind) {
    List<Hit> out = new ArrayList<>();
    Matcher m = TO_CHAR.matcher(sql);
    while (m.find()) {
      String fmt = m.group(2).toUpperCase(Locale.ROOT);
      String next = DAY_FORMATS.contains(fmt) ? " + 1"
          : MONTH_FORMATS.contains(fmt) ? (kind == DbKind.POSTGRES ? " + INTERVAL '1 month'" : null) : null;
      String start = "TO_DATE(" + m.group(3) + ", '" + m.group(2) + "')";
      if (MONTH_FORMATS.contains(fmt) && kind != DbKind.POSTGRES) {
        out.add(new Hit(m.start(), m.end(), "date", m.group(1), range(m.group(1), start, "ADD_MONTHS(" + start + ", 1)")));
      } else if (next != null) {
        out.add(new Hit(m.start(), m.end(), "date", m.group(1), range(m.group(1), start, start + next)));
      }
    }
    m = TRUNC.matcher(sql);
    while (m.find()) out.add(new Hit(m.start(), m.end(), "date", m.group(1), range(m.group(1), m.group(2), m.group(2) + " + 1")));
    m = NVL.matcher(sql);
    while (m.find()) out.add(new Hit(m.start(), m.end(), "nvl", m.group(1), nvl(m.group(1), m.group(2), m.group(3))));
    m = SUBSTR.matcher(sql);
    while (m.find()) {
      String value = m.group(3);
      // 문자 값이 자른 길이와 다르면 원래 조건은 어떤 행과도 맞지 않는다. 뜻이 달라지므로 바꾸지 않는다.
      if (value.startsWith("'") && value.length() - 2 != Integer.parseInt(m.group(2))) continue;
      out.add(new Hit(m.start(), m.end(), "substr", m.group(1), m.group(1) + " LIKE " + value + " || '%'"));
    }
    m = CASE.matcher(sql);
    while (m.find()) out.add(new Hit(m.start(), m.end(), "case", m.group(2), m.group(2) + " = " + m.group(1) + "(" + m.group(3) + ")"));
    out.sort((a, b) -> Integer.compare(a.start, b.start));
    return out;
  }

  private static String range(String col, String from, String to) {
    return col + " >= " + from + " AND " + col + " < " + to;
  }

  /**
   * NVL(col, d) = v. d · v 가 둘 다 문자 · 숫자 글이면 미리 비교한다: 같으면 col = v OR col IS NULL, 다르면 col = v.
   * 하나라도 바인드면 (col = v OR (col IS NULL AND d = v)).
   */
  static String nvl(String col, String fallback, String value) {
    boolean literals = isLiteral(fallback) && isLiteral(value);
    if (literals && !fallback.equals(value)) return col + " = " + value;
    if (literals) return "(" + col + " = " + value + " OR " + col + " IS NULL)";
    return "(" + col + " = " + value + " OR (" + col + " IS NULL AND " + fallback + " = " + value + "))";
  }

  private static boolean isLiteral(String v) { return v.startsWith("'") || Character.isDigit(v.charAt(0)); }

  private static String bare(String col) { return col.substring(col.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT); }
}
