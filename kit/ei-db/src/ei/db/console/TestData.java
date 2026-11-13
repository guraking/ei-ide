package ei.db.console;

import ei.db.DbKind;
import ei.db.Meta;
import ei.db.console.InsertRows.Val;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 「테스트 데이터 만들기」의 논리: 칼럼마다 만드는 방식(Plan)을 받아 행 값을 만든다. 같은 씨앗 · 같은 방식이면 늘 같은 결과이고,
 * 줄 수만 줄이면 앞쪽 행이 그대로 나온다(미리보기). 값은 칼럼 길이 · 자릿수를 넘지 않게 만들고, 넘는 설정은 오류로 돌려준다(행을 만들지 않는다).
 * IntelliJ 클래스를 쓰지 않는다(TestDataCheck 로 점검).
 */
public final class TestData {
  private TestData() {}

  public static final int DEFAULT_ROWS = 100;
  /** 한 번에 만드는 줄 수 한도. 만든 값은 모두 메모리에 두므로 넓은 표에서도 부담이 없을 만큼으로 둔다. */
  public static final int MAX_ROWS = 10_000;
  public static final long DEFAULT_SEED = 20240101L;
  /** 미리보기에 보이는 행 수. */
  public static final int PREVIEW_ROWS = 5;
  /** 「참조 표에서 실제 값 가져오기」가 읽는 값 수 한도. */
  public static final int FK_SAMPLE_MAX = 200;
  /** 범위 값의 구분자(1~100, 2024-01-01~2024-12-31). */
  public static final String RANGE_SEPARATOR = "~";
  /** 목록 값의 구분자. */
  public static final String LIST_SEPARATOR = ",";

  private static final long DEFAULT_START = 1;
  private static final int DEFAULT_RANGE_MAX = 9999;
  /** 자릿수를 알 수 없는 숫자 형식(정수 형식 등)의 기본 범위 끝. */
  private static final int SMALL_RANGE_MAX = 100;
  /** 난수에 쓰는 소수 자릿수 한도. */
  private static final int MAX_RANDOM_SCALE = 6;
  private static final int DEFAULT_DATE_YEARS = 1;
  private static final long LAST_SECOND_OF_DAY = 24 * 60 * 60 - 1;
  private static final String CODE_LIST = "Y,N";
  private static final String NAME_SEPARATOR = "_";
  /** 칼럼 이름에 쓸 ASCII 글자가 없을 때의 「칼럼이름_순번」 머리. */
  private static final String FALLBACK_PREFIX = "V";
  private static final Pattern NON_ASCII_WORD = Pattern.compile("[^A-Za-z0-9_]");
  /** 문장에 그대로 끼우는 시퀀스 이름: 이름 또는 스키마.이름. */
  private static final Pattern SEQUENCE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*)?");
  private static final Pattern INTEGER = Pattern.compile("[-+]?\\d{1,18}");
  private static final List<String> SURNAMES = List.of("김", "이", "박", "최", "정", "강", "조", "윤", "장", "임");
  private static final List<String> GIVEN_NAMES = List.of("민준", "서연", "도윤", "지우", "하준", "서윤", "지호", "수아", "예준", "하은");
  private static final List<String> ENGLISH_WORDS = List.of(
      "alpha", "bravo", "cedar", "delta", "ember", "frost", "grove", "harbor", "ivory", "jade",
      "kite", "lumen", "maple", "north", "olive", "pixel", "quartz", "river", "stone", "tulip");

  /**
   * 만드는 방식. SEQUENCE 순번(값 = 시작값), DB_SEQUENCE DB 시퀀스(값 = 시퀀스 이름), NAME_SEQ 칼럼이름_순번, FIXED 고정값,
   * LIST 목록 돌려 쓰기(쉼표로 나눈 값), KOREAN_NAME · ENGLISH_WORD 내장 표본, RANDOM_NUMBER 범위 난수(최소~최대),
   * RANDOM_DATE 날짜 범위 난수(시작~끝), NULL, DEFAULT 기본값 쓰기(칼럼 빼기), SKIP 넣지 않음(칼럼 빼기).
   */
  public enum Method { SEQUENCE, DB_SEQUENCE, NAME_SEQ, FIXED, LIST, KOREAN_NAME, ENGLISH_WORD, RANDOM_NUMBER, RANDOM_DATE, NULL, DEFAULT, SKIP }

  public record Plan(Meta.Column column, Method method, String arg) {}

  /** errors 가 있으면 rows 는 비어 있다. columns 는 넣는 칼럼(DEFAULT · SKIP 은 빠진다). */
  public record Output(List<String> columns, List<List<Val>> rows, List<String> errors, List<String> warnings) {}

  /** row 번째 행(0 부터)의 값. 난수는 rnd 에서만 뽑는다. */
  private interface Gen { Val at(int row, Random rnd); }

  /** 그 칼럼에서 고를 수 있는 방식. NOT NULL 칼럼은 NULL 을, 기본값 없는 칼럼은 DEFAULT 를 고를 수 없다. LOB · 이진 칼럼은 값을 만들지 않는다. */
  public static List<Method> methods(Meta.Column col, boolean hasDefault) {
    List<Method> out = new ArrayList<>(switch (InsertRows.typeOf(col.type())) {
      case NUMBER -> List.of(Method.SEQUENCE, Method.DB_SEQUENCE, Method.RANDOM_NUMBER, Method.FIXED, Method.LIST);
      case TEXT -> List.of(Method.NAME_SEQ, Method.SEQUENCE, Method.FIXED, Method.LIST, Method.KOREAN_NAME, Method.ENGLISH_WORD);
      case DATE -> List.of(Method.RANDOM_DATE, Method.FIXED, Method.LIST);
      case LOB -> List.<Method>of();
      case OTHER -> List.of(Method.FIXED, Method.LIST);
    });
    if (col.nullable()) out.add(Method.NULL);
    if (hasDefault) out.add(Method.DEFAULT);
    out.add(Method.SKIP);
    return out;
  }

  /**
   * 칼럼 형식 · 제약으로 고른 기본 방식: 기본값이 있으면 DEFAULT, LOB · 이진은 SKIP, PK 는 순번(글 PK 는 칼럼이름_순번),
   * 숫자는 범위 난수, 1글자 글은 Y,N 목록, 글은 칼럼이름_순번, 날짜는 today 까지 최근 1년 난수, 그 밖은 NULL(NOT NULL 이면 고정값).
   */
  public static Plan defaultPlan(Meta.Column col, boolean hasDefault, LocalDate today) {
    InsertRows.Type type = InsertRows.typeOf(col.type());
    Method m;
    if (hasDefault) m = Method.DEFAULT;
    else if (type == InsertRows.Type.LOB) m = Method.SKIP;
    else if (type == InsertRows.Type.NUMBER) m = col.pk() ? Method.SEQUENCE : Method.RANDOM_NUMBER;
    else if (type == InsertRows.Type.TEXT) m = !col.pk() && col.size() == 1 ? Method.LIST : Method.NAME_SEQ;
    else if (type == InsertRows.Type.DATE) m = Method.RANDOM_DATE;
    else m = col.nullable() ? Method.NULL : Method.FIXED;
    return new Plan(col, m, defaultArg(col, m, today));
  }

  /** 방식을 골랐을 때 값 칸에 채우는 기본값. 값이 필요 없는 방식 · 사용자가 채울 방식은 빈 글자. */
  public static String defaultArg(Meta.Column col, Method m, LocalDate today) {
    return switch (m) {
      case SEQUENCE -> String.valueOf(DEFAULT_START);
      case LIST -> col.size() == 1 ? CODE_LIST : "";
      case RANDOM_NUMBER -> defaultRange(col);
      case RANDOM_DATE -> today.minusYears(DEFAULT_DATE_YEARS) + RANGE_SEPARATOR + today;
      default -> "";
    };
  }

  private static String defaultRange(Meta.Column col) {
    int room = PasteRows.digitRoom(col);
    if (room < 0) return DEFAULT_START + RANGE_SEPARATOR + SMALL_RANGE_MAX;
    if (room == 0) {
      BigDecimal max = BigDecimal.ONE.subtract(BigDecimal.ONE.movePointLeft(scaleOf(col)));
      return 0 + RANGE_SEPARATOR + max.toPlainString();
    }
    BigDecimal max = BigDecimal.TEN.pow(room).subtract(BigDecimal.ONE).min(BigDecimal.valueOf(DEFAULT_RANGE_MAX));
    return DEFAULT_START + RANGE_SEPARATOR + max.toPlainString();
  }

  private static int scaleOf(Meta.Column col) {
    return PasteRows.digitRoom(col) < 0 ? 0 : Math.min(col.scale(), MAX_RANDOM_SCALE);
  }

  /**
   * plans(표 칼럼 순서)대로 rowCount 행을 만든다. withDefault 는 기본값이 있는 칼럼 이름.
   * 설정이 틀리면(값 형식 · 길이 · 자릿수 · NOT NULL · 줄 수 한도) errors 에 칼럼별 이유를 담고 행은 만들지 않는다.
   * 넣지 않는 NOT NULL 칼럼 · 중복이 날 수 있는 PK 는 warnings.
   */
  public static Output generate(DbKind kind, List<Plan> plans, Set<String> withDefault, int rowCount, long seed) {
    return generate(kind, plans, withDefault, rowCount, rowCount, seed);
  }

  /** generate 와 같은 검증(rowCount 행 기준)을 하고 앞의 PREVIEW_ROWS 행만 만든다. 그 행들은 generate 의 앞쪽 행과 같다. */
  public static Output preview(DbKind kind, List<Plan> plans, Set<String> withDefault, int rowCount, long seed) {
    return generate(kind, plans, withDefault, rowCount, Math.min(rowCount, PREVIEW_ROWS), seed);
  }

  private static Output generate(DbKind kind, List<Plan> plans, Set<String> withDefault, int rowCount, int produce, long seed) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    List<Gen> gens = new ArrayList<>();
    if (rowCount < 1 || rowCount > MAX_ROWS) errors.add("줄 수는 1 ~ " + MAX_ROWS + " 사이여야 합니다.");
    int rows = Math.max(1, Math.min(rowCount, MAX_ROWS));
    boolean anyPk = false;
    boolean uniquePk = false;
    for (Plan p : plans) {
      Meta.Column col = p.column();
      boolean hasDefault = withDefault.contains(col.name());
      Method m = p.method();
      if (col.pk()) {
        anyPk = true;
        uniquePk |= m == Method.SEQUENCE || m == Method.DB_SEQUENCE || m == Method.NAME_SEQ || m == Method.DEFAULT;
      }
      if (!methods(col, hasDefault).contains(m)) {
        errors.add(col.name() + ": 이 칼럼에 쓸 수 없는 방식입니다.");
        continue;
      }
      if (m == Method.DEFAULT || m == Method.SKIP) {
        if (m == Method.SKIP && !col.nullable() && !hasDefault) warnings.add(col.name() + ": NOT NULL 칼럼인데 넣지 않습니다(기본값 없음).");
        continue;
      }
      List<String> mine = new ArrayList<>();
      Gen gen = gen(kind, p, rows, mine, warnings);
      for (String e : mine) errors.add(col.name() + ": " + e);
      columns.add(col.name());
      gens.add(gen);
    }
    if (columns.isEmpty() && errors.isEmpty()) errors.add("넣을 칼럼이 없습니다.");
    if (anyPk && !uniquePk) warnings.add("PK 칼럼에 순번 · 시퀀스 방식이 없어 중복 오류가 날 수 있습니다.");
    if (!errors.isEmpty()) return new Output(columns, List.of(), errors, warnings);
    Random rnd = new Random(seed);
    List<List<Val>> out = new ArrayList<>(produce);
    for (int r = 0; r < produce; r++) {
      List<Val> row = new ArrayList<>(gens.size());
      for (Gen g : gens) row.add(g.at(r, rnd));
      out.add(row);
    }
    return new Output(columns, out, errors, warnings);
  }

  /** 방식 하나의 값 만들기. 설정 오류는 errors 에 담고(이때 돌려준 Gen 은 쓰이지 않는다) 주의는 warnings 에 담는다. */
  private static Gen gen(DbKind kind, Plan p, int rows, List<String> errors, List<String> warnings) {
    Meta.Column col = p.column();
    String arg = p.arg() == null ? "" : p.arg().strip();
    boolean textColumn = InsertRows.typeOf(col.type()) == InsertRows.Type.TEXT;
    int maxChars = PasteRows.bounded(col) ? col.size() : Integer.MAX_VALUE;
    switch (p.method()) {
      case SEQUENCE -> {
        String startText = arg.isEmpty() ? String.valueOf(DEFAULT_START) : arg;
        if (!INTEGER.matcher(startText).matches()) {
          errors.add("시작값은 정수여야 합니다: " + arg);
          return null;
        }
        long start = Long.parseLong(startText);
        check(kind, col, String.valueOf(start + rows - 1), errors, null);
        if (errors.isEmpty()) check(kind, col, String.valueOf(start), errors, null);
        return (row, rnd) -> textColumn ? InsertRows.text(String.valueOf(start + row), kind) : InsertRows.number(BigDecimal.valueOf(start + row));
      }
      case DB_SEQUENCE -> {
        if (kind == DbKind.MYSQL) errors.add("MySQL 은 시퀀스를 쓰지 않습니다. 다른 방식을 고르세요.");
        else if (!SEQUENCE_NAME.matcher(arg).matches()) errors.add("시퀀스 이름(이름 또는 스키마.이름)을 넣으세요.");
        Val v = InsertRows.raw(kind == DbKind.ORACLE ? arg + ".NEXTVAL" : "nextval('" + arg + "')");
        return (row, rnd) -> v;
      }
      case NAME_SEQ -> {
        String ascii = NON_ASCII_WORD.matcher(col.name()).replaceAll("");
        String prefix = ascii.isEmpty() ? FALLBACK_PREFIX : ascii;
        if (String.valueOf(rows).length() > maxChars) errors.add("칼럼 크기(" + col.size() + ")가 순번 자릿수보다 작습니다.");
        return (row, rnd) -> InsertRows.text(nameSeq(prefix, row + 1, maxChars), kind);
      }
      case FIXED -> {
        if (arg.isEmpty()) {
          errors.add("고정값을 넣으세요.");
          return null;
        }
        Val v = check(kind, col, p.arg(), errors, warnings);
        return (row, rnd) -> v;
      }
      case LIST -> {
        List<Val> values = new ArrayList<>();
        for (String item : arg.split(LIST_SEPARATOR)) {
          if (!item.isBlank()) values.add(check(kind, col, item.strip(), errors, warnings));
        }
        if (values.isEmpty()) {
          errors.add("목록 값을 쉼표로 나눠 넣으세요.");
          return null;
        }
        return (row, rnd) -> values.get(row % values.size());
      }
      case KOREAN_NAME -> {
        int room = kind == DbKind.ORACLE && PasteRows.bounded(col) ? col.size() / PasteRows.ORACLE_BYTES_PER_HANGUL : maxChars;
        if (room < 1) errors.add("칼럼 크기(" + col.size() + ")가 한글 한 글자보다 작습니다.");
        return (row, rnd) -> InsertRows.text(cut(pick(SURNAMES, rnd) + pick(GIVEN_NAMES, rnd), room), kind);
      }
      case ENGLISH_WORD -> {
        return (row, rnd) -> InsertRows.text(cut(pick(ENGLISH_WORDS, rnd), maxChars), kind);
      }
      case RANDOM_NUMBER -> {
        return randomNumber(kind, col, arg, errors);
      }
      case RANDOM_DATE -> {
        return randomDate(kind, col, arg, errors);
      }
      default -> {
        return (row, rnd) -> InsertRows.NULL;
      }
    }
  }

  /** text 를 칼럼 값으로 바꿔 본다. 막는 문제는 errors 로, 주의는 warnings(null 이면 버림)로. */
  private static Val check(DbKind kind, Meta.Column col, String text, List<String> errors, List<String> warnings) {
    PasteRows.Cell cell = PasteRows.value(kind, col, text, false);
    if (cell.reason() != null) {
      if (cell.blocking()) errors.add(cell.reason());
      else if (warnings != null) warnings.add(col.name() + ": " + cell.reason());
    }
    return cell.value();
  }

  /** 「머리_순번」을 maxChars 안으로: 넘치면 머리를 자르고, 머리 자리가 없으면 순번만. */
  static String nameSeq(String prefix, int no, int maxChars) {
    String n = String.valueOf(no);
    int room = maxChars - n.length() - NAME_SEPARATOR.length();
    if (room < 1) return n;
    return (prefix.length() > room ? prefix.substring(0, room) : prefix) + NAME_SEPARATOR + n;
  }

  private static String pick(List<String> from, Random rnd) {
    return from.get(rnd.nextInt(from.size()));
  }

  private static String cut(String s, int maxChars) {
    return s.length() > maxChars ? s.substring(0, Math.max(maxChars, 0)) : s;
  }

  /** 「최소~최대」 범위에서 칼럼 소수 자릿수(MAX_RANDOM_SCALE 까지)에 맞춘 난수. 두 끝이 칼럼 자릿수 안이어야 한다. */
  private static Gen randomNumber(DbKind kind, Meta.Column col, String arg, List<String> errors) {
    String[] ends = arg.split(RANGE_SEPARATOR, -1);
    if (ends.length != 2) {
      errors.add("범위를 최소" + RANGE_SEPARATOR + "최대 로 넣으세요: " + arg);
      return null;
    }
    int before = errors.size();
    Val min = check(kind, col, ends[0], errors, null);
    Val max = check(kind, col, ends[1], errors, null);
    if (errors.size() > before) return null;
    if (!(min.bind() instanceof BigDecimal lo) || !(max.bind() instanceof BigDecimal hi) || lo.compareTo(hi) > 0) {
      errors.add("범위를 최소" + RANGE_SEPARATOR + "최대 로 넣으세요: " + arg);
      return null;
    }
    int scale = scaleOf(col);
    long from;
    long to;
    try {
      from = lo.setScale(scale, RoundingMode.CEILING).unscaledValue().longValueExact();
      to = hi.setScale(scale, RoundingMode.FLOOR).unscaledValue().longValueExact();
    } catch (ArithmeticException e) {
      errors.add("범위가 너무 큽니다: " + arg);
      return null;
    }
    if (from > to || to == Long.MAX_VALUE) {
      errors.add("범위가 칼럼 소수 자릿수(" + scale + ")로 나타낼 수 없습니다: " + arg);
      return null;
    }
    return (row, rnd) -> InsertRows.number(BigDecimal.valueOf(rnd.nextLong(from, to + 1), scale));
  }

  /** 「시작~끝」(PasteRows.parseDate 가 읽는 꼴) 사이의 초 단위 난수. 끝에 시각이 없으면 그날 끝까지. */
  private static Gen randomDate(DbKind kind, Meta.Column col, String arg, List<String> errors) {
    String[] ends = arg.split(RANGE_SEPARATOR, -1);
    LocalDateTime from = ends.length == 2 ? PasteRows.parseDate(ends[0]) : null;
    LocalDateTime to = ends.length == 2 ? PasteRows.parseDate(ends[1]) : null;
    if (from == null || to == null) {
      errors.add("범위를 시작" + RANGE_SEPARATOR + "끝(yyyy-MM-dd) 으로 넣으세요: " + arg);
      return null;
    }
    LocalDateTime end = to.toLocalTime().toSecondOfDay() == 0 ? to.plusSeconds(LAST_SECOND_OF_DAY) : to;
    long seconds = ChronoUnit.SECONDS.between(from, end);
    if (seconds < 0) {
      errors.add("시작이 끝보다 늦습니다: " + arg);
      return null;
    }
    return (row, rnd) -> InsertRows.date(kind, col.type(), from.plusSeconds(rnd.nextLong(seconds + 1)));
  }
}
