package ei.db.console;

import ei.db.DbKind;
import ei.db.Meta;
import ei.db.gen.TypeMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「엑셀 붙여 넣어 행 넣기」의 논리: 엑셀 클립보드 글(탭으로 나눈 칸) 읽기 · 첫 줄을 칼럼에 맞추기 · 칸 값을 칼럼 형식에 맞는 값으로 바꾸며 문제 찾기.
 * 읽지 못한 값은 추측하지 않고 문제로 남긴다. IntelliJ 클래스를 쓰지 않는다(PasteRowsCheck 로 점검).
 */
public final class PasteRows {
  private PasteRows() {}

  /** 한 번에 붙여 넣는 행 수 한도. */
  public static final int MAX_ROWS = 5000;
  /** 맞춘 칼럼이 없는 칸(넣지 않음). */
  public static final int SKIP = -1;
  /** 문제의 줄 · 칸 자리에 쓰는 「전체」. */
  public static final int WHOLE = -1;

  private static final char QUOTE = '"';
  private static final char TAB = '\t';
  /** 천 단위 쉼표가 제자리에 있는 숫자만 쉼표를 뗀다(1,5 같은 꼴은 숫자로 읽지 않는다). */
  private static final Pattern GROUPED_NUMBER = Pattern.compile("[-+]?\\d{1,3}(,\\d{3})+(\\.\\d+)?");
  private static final Pattern PLAIN_NUMBER = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)");
  private static final String TIME_PART = "(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.0+)?)?)?";
  /** yyyy-MM-dd · yyyy/MM/dd · yyyy.MM.dd(구분자는 앞뒤가 같아야 한다, 「2024. 1. 2.」 꼴도) + HH:mm[:ss]. */
  private static final Pattern SEPARATED_DATE = Pattern.compile("(\\d{4})([-/.]) ?(\\d{1,2})\\2 ?(\\d{1,2})\\.?" + TIME_PART);
  /** yyyyMMdd + HH:mm[:ss]. */
  private static final Pattern COMPACT_DATE = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})" + TIME_PART);
  /** 자릿수 · 소수 자릿수를 검사하는 숫자 형식 머리(FLOAT · 정수 형식의 COLUMN_SIZE 는 십진 자릿수가 아니거나 범위가 따로 있다). */
  private static final Pattern DECIMAL_TYPE = Pattern.compile("(?i)(NUMBER|NUMERIC|DECIMAL|DEC)");
  /** Oracle 의 글 칼럼 크기가 바이트일 때 한글 한 글자가 차지하는 바이트(AL32UTF8). */
  public static final int ORACLE_BYTES_PER_HANGUL = 3;

  static final String NOT_NUMBER = "숫자 칼럼에 글";
  static final String BAD_DATE = "날짜 형식을 읽지 못함";
  static final String TOO_LONG = "길이 초과";
  static final String MAYBE_TOO_LONG = "칼럼 크기가 바이트 기준이면 길이 초과";
  static final String TOO_MANY_DIGITS = "자릿수 초과";
  static final String EMPTY_NOT_NULL = "NOT NULL 인데 빔";
  static final String WIDTH_DIFFERS = "칸 수가 다름";
  static final String NO_COLUMN = "맞는 칼럼이 없어 넣지 않음";
  static final String EXTRA_CELLS = "표 칼럼보다 칸이 많아 넣지 않음";
  static final String SAME_COLUMN = "같은 칼럼을 두 번 고름";
  static final String MISSING_NOT_NULL = "NOT NULL 칼럼이 빠짐(기본값 없음)";
  static final String NOTHING_MAPPED = "넣을 칼럼이 없음";

  /**
   * 엑셀 클립보드 글을 행 · 칸으로. 칸은 탭, 행은 줄바꿈(CRLF · LF · CR)으로 나눈다. 칸이 큰따옴표로 시작하면 닫는 큰따옴표까지가 한 칸이고
   * 그 안의 줄바꿈 · 탭은 값이며 "" 는 큰따옴표 하나다. 닫는 큰따옴표가 없거나 그 뒤에 글이 이어지면 따옴표를 글자로 본다.
   * 모든 칸이 빈 행(빈 줄 · 끝의 빈 줄)은 뺀다.
   */
  public static List<List<String>> parse(String text) {
    List<List<String>> out = new ArrayList<>();
    List<String> row = new ArrayList<>();
    int n = text.length();
    int i = 0;
    while (i <= n) {
      StringBuilder cell = new StringBuilder();
      if (i < n && text.charAt(i) == QUOTE) {
        int end = closingQuote(text, i + 1);
        boolean closed = end >= 0 && (end + 1 == n || text.charAt(end + 1) == TAB || text.charAt(end + 1) == '\n' || text.charAt(end + 1) == '\r');
        if (closed) {
          cell.append(text.substring(i + 1, end).replace("\"\"", "\""));
          i = end + 1;
        }
      }
      while (i < n && text.charAt(i) != TAB && text.charAt(i) != '\n' && text.charAt(i) != '\r') cell.append(text.charAt(i++));
      row.add(cell.toString());
      if (i < n && text.charAt(i) == TAB) {
        i++;
        continue;
      }
      if (!blank(row)) out.add(row);
      row = new ArrayList<>();
      if (i >= n) break;
      i += text.charAt(i) == '\r' && i + 1 < n && text.charAt(i + 1) == '\n' ? 2 : 1;
      if (i == n) break;
    }
    return out;
  }

  /** from 부터 닫는 큰따옴표("" 는 건너뜀)의 자리. 없으면 -1. */
  private static int closingQuote(String text, int from) {
    int i = from;
    while (i < text.length()) {
      if (text.charAt(i) == QUOTE) {
        if (i + 1 < text.length() && text.charAt(i + 1) == QUOTE) i += 2;
        else return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  private static boolean blank(List<String> row) {
    for (String cell : row) {
      if (!cell.isBlank()) return false;
    }
    return true;
  }

  /** 가장 칸이 많은 행의 칸 수. 행이 없으면 0. */
  public static int width(List<List<String>> rows) {
    int w = 0;
    for (List<String> row : rows) w = Math.max(w, row.size());
    return w;
  }

  /**
   * 머리 칸마다 칼럼 번호(못 맞추면 SKIP). 칼럼 이름(대소문자 무시)을 먼저, 없으면 칼럼 주석(한글 이름)과 같은 것을 찾는다.
   * 이미 맞춘 칼럼은 다시 맞추지 않는다.
   */
  public static int[] match(List<String> header, List<Meta.Column> columns) {
    Map<String, Integer> byName = new HashMap<>();
    Map<String, Integer> byComment = new HashMap<>();
    for (int i = 0; i < columns.size(); i++) {
      byName.putIfAbsent(key(columns.get(i).name()), i);
      if (!columns.get(i).comment().isBlank()) byComment.putIfAbsent(key(columns.get(i).comment()), i);
    }
    int[] out = new int[header.size()];
    boolean[] used = new boolean[columns.size()];
    for (int i = 0; i < out.length; i++) {
      String k = key(header.get(i));
      Integer found = byName.containsKey(k) ? byName.get(k) : byComment.get(k);
      out[i] = found == null || used[found] ? SKIP : found;
      if (out[i] != SKIP) used[found] = true;
    }
    return out;
  }

  private static String key(String s) {
    return s.strip().toLowerCase(Locale.ROOT);
  }

  /** 첫 줄의 칸 가운데 절반 이상이 칼럼 이름 · 주석과 맞으면 머리 줄로 본다. */
  public static boolean looksLikeHeader(List<String> first, List<Meta.Column> columns) {
    int hit = 0;
    for (int m : match(first, columns)) {
      if (m != SKIP) hit++;
    }
    return hit > 0 && hit * 2 >= first.size();
  }

  /** 머리 줄이 없을 때: 칸 순서대로 표 칼럼 순서. 칼럼 수를 넘는 칸은 SKIP. */
  public static int[] byOrder(int width, int columnCount) {
    int[] out = new int[width];
    for (int i = 0; i < width; i++) out[i] = i < columnCount ? i : SKIP;
    return out;
  }

  /**
   * 문제 하나. row 는 데이터 행(0 부터, 전체면 WHOLE), cell 은 붙여 넣은 칸(0 부터, 전체면 WHOLE), column 은 칼럼 이름(없으면 빈 글자).
   * blocking 이 아니면 주의(바로 넣기를 막지 않는다).
   */
  public record Problem(int row, int cell, String column, String reason, boolean blocking) {}

  /** 칸 하나를 바꾼 결과. reason 이 null 이면 문제 없음. 문제가 있어도 value 는 원래 글의 글 값으로 채운다(콘솔에서 고칠 수 있게). */
  public record Cell(InsertRows.Val value, String reason, boolean blocking) {}

  /** columns 는 넣는 칼럼 이름(칸 순서), rows 는 그 칼럼 순서의 값. */
  public record Result(List<String> columns, List<List<InsertRows.Val>> rows, List<Problem> problems) {
    public boolean blocked() {
      for (Problem p : problems) {
        if (p.blocking()) return true;
      }
      return false;
    }
  }

  /**
   * 행들을 값으로 바꾼다. mapping 은 칸 → 칼럼 번호(SKIP 은 넣지 않음), withDefault 는 기본값이 있는 칼럼 이름,
   * header 는 머리 줄(없으면 null, 못 맞춘 머리를 주의로 알리는 데만 쓴다), trim 이면 글 칼럼 값의 앞뒤 공백을 뗀다.
   */
  public static Result convert(DbKind kind, List<Meta.Column> columns, Set<String> withDefault, int[] mapping, List<String> header,
                               List<List<String>> rows, boolean trim) {
    List<Problem> problems = new ArrayList<>();
    List<String> names = new ArrayList<>();
    boolean[] mapped = new boolean[columns.size()];
    for (int i = 0; i < mapping.length; i++) {
      int m = mapping[i];
      if (m == SKIP) {
        if (header != null && i < header.size() && !header.get(i).isBlank()) {
          problems.add(new Problem(WHOLE, i, header.get(i).strip(), NO_COLUMN, false));
        } else if (header == null && i >= columns.size()) {
          problems.add(new Problem(WHOLE, i, "", EXTRA_CELLS, false));
        }
        continue;
      }
      if (mapped[m]) problems.add(new Problem(WHOLE, i, columns.get(m).name(), SAME_COLUMN, true));
      mapped[m] = true;
      names.add(columns.get(m).name());
    }
    if (names.isEmpty()) problems.add(new Problem(WHOLE, WHOLE, "", NOTHING_MAPPED, true));
    for (int m = 0; m < columns.size(); m++) {
      Meta.Column col = columns.get(m);
      if (!mapped[m] && !col.nullable() && !withDefault.contains(col.name())) {
        problems.add(new Problem(WHOLE, WHOLE, col.name(), MISSING_NOT_NULL, false));
      }
    }
    List<List<InsertRows.Val>> out = new ArrayList<>(rows.size());
    for (int r = 0; r < rows.size(); r++) {
      List<String> row = rows.get(r);
      if (row.size() != mapping.length) {
        problems.add(new Problem(r, WHOLE, "", WIDTH_DIFFERS + "(" + row.size() + "칸, 맞춘 칸 " + mapping.length + "칸)", true));
      }
      List<InsertRows.Val> values = new ArrayList<>(names.size());
      for (int i = 0; i < mapping.length; i++) {
        if (mapping[i] == SKIP) continue;
        Meta.Column col = columns.get(mapping[i]);
        Cell cell = value(kind, col, i < row.size() ? row.get(i) : "", trim);
        if (cell.reason() != null) problems.add(new Problem(r, i, col.name(), cell.reason(), cell.blocking()));
        values.add(cell.value());
      }
      out.add(values);
    }
    return new Result(names, out, problems);
  }

  /**
   * 칸 글 하나를 칼럼 형식에 맞는 값으로. 빈 칸은 NULL(NOT NULL 칼럼이면 문제), 숫자 칼럼은 천 단위 쉼표를 떼고 자릿수를 보며,
   * 날짜 · 시각 칼럼은 parseDate 가 읽는 꼴만 받고, 글 칼럼은 길이를 본다(글자 수가 크기를 넘으면 문제,
   * Oracle 에서 글자 수는 맞지만 UTF-8 바이트가 넘으면 주의). 그 밖의 형식은 글 그대로 넘긴다.
   */
  public static Cell value(DbKind kind, Meta.Column col, String raw, boolean trim) {
    InsertRows.Type type = InsertRows.typeOf(col.type());
    boolean strip = trim || type == InsertRows.Type.NUMBER || type == InsertRows.Type.DATE;
    String s = strip ? raw.strip() : raw;
    if (s.isEmpty()) return new Cell(InsertRows.NULL, col.nullable() ? null : EMPTY_NOT_NULL, true);
    InsertRows.Val asText = InsertRows.text(s, kind);
    switch (type) {
      case NUMBER -> {
        String plain = GROUPED_NUMBER.matcher(s).matches() ? s.replace(",", "") : s;
        if (!PLAIN_NUMBER.matcher(plain).matches()) return new Cell(asText, NOT_NUMBER + ": " + s, true);
        BigDecimal n = new BigDecimal(plain);
        int room = digitRoom(col);
        if (room >= 0 && n.signum() != 0 && n.precision() - n.scale() > room) {
          return new Cell(asText, TOO_MANY_DIGITS + "(정수 " + room + "자리까지): " + s, true);
        }
        return new Cell(InsertRows.number(n), null, false);
      }
      case DATE -> {
        LocalDateTime t = parseDate(s);
        if (t == null) return new Cell(asText, BAD_DATE + ": " + s, true);
        return new Cell(InsertRows.date(kind, col.type(), t), null, false);
      }
      case TEXT -> {
        int chars = s.codePointCount(0, s.length());
        if (bounded(col) && chars > col.size()) {
          return new Cell(asText, TOO_LONG + "(" + chars + "자, 칼럼 크기 " + col.size() + ")", true);
        }
        int bytes = s.getBytes(StandardCharsets.UTF_8).length;
        if (kind == DbKind.ORACLE && bounded(col) && bytes > col.size()) {
          return new Cell(asText, MAYBE_TOO_LONG + "(" + bytes + "바이트, 칼럼 크기 " + col.size() + ")", false);
        }
        return new Cell(asText, null, false);
      }
      default -> {
        return new Cell(asText, null, false);
      }
    }
  }

  /** 크기가 정해진 칼럼인지(PostgreSQL 의 text · 길이 없는 varchar 는 크기가 매우 크게 온다). */
  static boolean bounded(Meta.Column col) {
    return col.size() > 0 && col.size() < Integer.MAX_VALUE / 2;
  }

  /** 정수 부분에 쓸 수 있는 자릿수. 자릿수를 정하지 않은 숫자 · 정밀도가 십진 자릿수가 아닌 형식이면 -1(검사하지 않음). */
  static int digitRoom(Meta.Column col) {
    if (!DECIMAL_TYPE.matcher(col.type().strip()).matches()) return -1;
    if (col.size() <= 0 || col.size() >= TypeMap.UNBOUNDED_PRECISION || col.scale() < 0) return -1;
    return Math.max(col.size() - col.scale(), 0);
  }

  /**
   * yyyy-MM-dd · yyyy/MM/dd · yyyy.MM.dd · yyyyMMdd 와 그 뒤의 HH:mm[:ss](초 뒤의 .0 은 받는다). 그 밖의 꼴 · 없는 날짜는 null.
   */
  public static LocalDateTime parseDate(String text) {
    String s = text.strip();
    Matcher m = SEPARATED_DATE.matcher(s);
    int base;
    if (m.matches()) {
      base = 3;
    } else {
      m = COMPACT_DATE.matcher(s);
      if (!m.matches()) return null;
      base = 2;
    }
    try {
      int hour = m.group(base + 2) == null ? 0 : Integer.parseInt(m.group(base + 2));
      int minute = m.group(base + 3) == null ? 0 : Integer.parseInt(m.group(base + 3));
      int second = m.group(base + 4) == null ? 0 : Integer.parseInt(m.group(base + 4));
      return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(base)), Integer.parseInt(m.group(base + 1)), hour, minute, second);
    } catch (DateTimeException e) {
      return null; // 13월 · 25시처럼 없는 날짜 · 시각
    }
  }
}
