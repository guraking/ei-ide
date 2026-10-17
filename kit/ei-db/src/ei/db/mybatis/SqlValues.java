package ei.db.mybatis;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import ei.db.DbKind;

/** 값 ↔ 입력 글 · SQL 리터럴 변환과, ? 자리에 리터럴을 끼워 넣는 일. */
public final class SqlValues {
  private SqlValues() {}

  private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?");
  private static final Pattern INTEGER = Pattern.compile("-?\\d+");
  private static final Pattern DECIMAL = Pattern.compile("-?\\d*\\.\\d+");
  private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";
  private static final int LONG_DIGITS = 18;
  private static final String QUOTE = "'";

  /**
   * 입력 글을 값으로. 비면 null. 'abc' 처럼 따옴표로 감싸면 늘 문자열.
   * yyyy-MM-dd → java.sql.Date, yyyy-MM-dd HH:mm(:ss) → Timestamp, 정수 → Long, 소수 → BigDecimal, 나머지 문자열.
   * 날짜 꼴이지만 없는 날짜(2026-13-01)면 문자열.
   * 0 으로 시작하는 여러 자리 숫자(코드 값)는 앞 0 이 사라지지 않게 문자열로 둔다.
   * list 면 쉼표로 나눠 각각 바꾼 List 를 준다.
   */
  public static Object parse(String text, boolean list) { return parse(text, list, false); }

  /** asText 가 참이면(받는 쪽이 문자열 필드) 따옴표만 벗기고 늘 문자열로 둔다. 문자 칼럼을 숫자 · 날짜로 비교하지 않게. */
  public static Object parse(String text, boolean list, boolean asText) {
    if (text == null || text.isBlank()) return null;
    if (!list) return scalar(text.trim(), asText);
    List<Object> out = new ArrayList<>();
    for (String part : text.split(",")) {
      if (!part.isBlank()) out.add(scalar(part.trim(), asText));
    }
    return out;
  }

  private static Object scalar(String t) { return scalar(t, false); }

  private static Object scalar(String t, boolean asText) {
    if (t.length() >= 2 && t.startsWith(QUOTE) && t.endsWith(QUOTE)) return t.substring(1, t.length() - 1).replace("''", QUOTE);
    if (asText) return t;
    try {
      if (DATE.matcher(t).matches()) return Date.valueOf(LocalDate.parse(t));
      if (DATE_TIME.matcher(t).matches()) return Timestamp.valueOf(LocalDateTime.parse(t.replace(' ', 'T')));
    } catch (DateTimeParseException e) {
      return t; // 없는 날짜 · 시각은 사용자가 넣은 글 그대로 문자열로 넘긴다.
    }
    boolean leadingZero = t.length() > 1 && (t.startsWith("0") || t.startsWith("-0")) && !t.contains(".");
    if (INTEGER.matcher(t).matches() && !leadingZero) {
      return t.replace("-", "").length() <= LONG_DIGITS ? (Object) Long.valueOf(t) : new BigDecimal(t);
    }
    if (DECIMAL.matcher(t).matches()) return new BigDecimal(t);
    return t;
  }

  /** parse 의 반대. 숫자처럼 보이는 문자열은 따옴표로 감싸 다시 읽어도 문자열이 되게 한다. */
  public static String format(Object v) {
    if (v == null) return "";
    if (v instanceof Collection<?> c) {
      List<String> parts = new ArrayList<>();
      for (Object o : c) parts.add(format(o));
      return String.join(", ", parts);
    }
    if (v instanceof Timestamp t) return new SimpleDateFormat(TIMESTAMP_FORMAT).format(t);
    if (v instanceof LocalDateTime t) return t.format(DateTimeFormatter.ofPattern(TIMESTAMP_FORMAT));
    if (v instanceof BigDecimal d) return d.toPlainString();
    if (v instanceof String s && !(scalar(s) instanceof String)) return "'" + s.replace("'", "''") + "'";
    return String.valueOf(v);
  }

  /** 대화창 「형식」 칸 표시. */
  public static String typeLabel(Object v) {
    if (v == null) return "String";
    if (v instanceof Date) return "Date";
    return v.getClass().getSimpleName();
  }

  /**
   * SQL 리터럴. 문자열은 ' 를 '' 로, java.sql.Date · LocalDate → DATE 'yyyy-mm-dd',
   * Timestamp · java.util.Date · LocalDateTime → TIMESTAMP 'yyyy-mm-dd hh:mi:ss', 숫자는 그대로, null → NULL.
   */
  public static String literal(Object v) { return literal(v, null); }

  /** kind 가 MySQL 이면 문자열 안 \ 도 \\ 로(MySQL 은 \ 를 이스케이프로 읽는다). null 이면 표준 SQL. */
  public static String literal(Object v, DbKind kind) {
    if (v == null) return "NULL";
    if (v instanceof Date || v instanceof LocalDate) return "DATE '" + v + "'";
    if (v instanceof java.util.Date d) return "TIMESTAMP '" + new SimpleDateFormat(TIMESTAMP_FORMAT).format(d) + "'";
    if (v instanceof LocalDateTime t) return "TIMESTAMP '" + t.format(DateTimeFormatter.ofPattern(TIMESTAMP_FORMAT)) + "'";
    if (v instanceof BigDecimal d) return d.toPlainString();
    if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
    if (v instanceof Collection<?> c) {
      List<String> parts = new ArrayList<>();
      for (Object o : c) parts.add(literal(o, kind));
      return String.join(", ", parts);
    }
    String s = String.valueOf(v);
    if (kind == DbKind.MYSQL) s = s.replace("\\", "\\\\");
    return QUOTE + s.replace(QUOTE, "''") + QUOTE;
  }

  /** 문자열 · 따옴표 이름 · 주석 안의 ? 는 건너뛰고 차례로 리터럴을 넣는다. 값이 모자라면 남은 ? 는 그대로 둔다. */
  public static String inline(String sql, List<Object> binds) { return inline(sql, binds, null); }

  /** kind 에 맞춘 리터럴로 넣는다(literal(Object, DbKind)). */
  public static String inline(String sql, List<Object> binds, DbKind kind) {
    StringBuilder b = new StringBuilder(sql.length() + binds.size() * 8);
    int n = 0;
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      int skip = skipQuotedOrComment(sql, i);
      if (skip > i) {
        b.append(sql, i, skip);
        i = skip;
      } else if (c == '?' && n < binds.size()) {
        b.append(literal(binds.get(n++), kind));
        i++;
      } else {
        b.append(c);
        i++;
      }
    }
    return b.toString();
  }

  /** i 에서 문자열 · 따옴표 이름 · 주석이 시작하면 그 끝 다음 위치, 아니면 i. */
  static int skipQuotedOrComment(String s, int i) {
    char c = s.charAt(i);
    if (c == '\'' || c == '"') {
      int k = i + 1;
      while (k < s.length()) {
        if (s.charAt(k) == c) {
          if (k + 1 < s.length() && s.charAt(k + 1) == c) {
            k += 2;
            continue;
          }
          return k + 1;
        }
        k++;
      }
      return s.length();
    }
    if (s.startsWith("--", i)) {
      int k = s.indexOf('\n', i);
      return k < 0 ? s.length() : k;
    }
    if (s.startsWith("/*", i)) {
      int k = s.indexOf("*/", i + 2);
      return k < 0 ? s.length() : k + 2;
    }
    return i;
  }
}
