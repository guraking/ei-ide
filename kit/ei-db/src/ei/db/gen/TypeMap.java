package ei.db.gen;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** DB 형식 이름 → 자바 형식(단순 이름)과 그 import. */
public final class TypeMap {
  private TypeMap() {}

  public static final String STRING = "String";
  public static final String LONG = "Long";
  public static final String INTEGER = "Integer";
  public static final String DECIMAL = "BigDecimal";
  public static final String DATE_TIME = "LocalDateTime";
  public static final String OBJECT = "Object";
  public static final String BOOLEAN = "Boolean";
  public static final String OFFSET_DATE_TIME = "OffsetDateTime";
  public static final String TIME = "LocalTime";
  public static final String UUID = "UUID";
  public static final String BYTES = "byte[]";

  /** 이 자릿수 이상은 자릿수를 정하지 않은 숫자로 본다(PG 의 정밀도 없는 numeric 은 131089 로 온다. PG 가 선언받는 최대 정밀도가 1000). */
  public static final int UNBOUNDED_PRECISION = 1000;

  /** 콤보에 보이는 고를 거리. 직접 쳐 넣은 형식도 받는다. */
  public static final List<String> CHOICES = List.of(STRING, LONG, INTEGER, DECIMAL, DATE_TIME, "LocalDate", "Double", BOOLEAN, OFFSET_DATE_TIME, TIME, UUID, BYTES, OBJECT);

  public static final Map<String, String> IMPORTS = Map.of(
      DECIMAL, "java.math.BigDecimal",
      DATE_TIME, "java.time.LocalDateTime",
      "LocalDate", "java.time.LocalDate",
      OFFSET_DATE_TIME, "java.time.OffsetDateTime",
      TIME, "java.time.LocalTime",
      UUID, "java.util.UUID");

  /**
   * fractional 은 소수가 있을 수 있는 숫자인지(칼럼 scale 이나 결과 표본으로 판정). 정수 숫자는 Long, 소수는 BigDecimal.
   * FLOAT · DOUBLE 처럼 늘 소수인 형식은 fractional 과 상관없이 BigDecimal.
   */
  public static String javaType(String dbType, boolean fractional) {
    String t = dbType == null ? "" : dbType.toUpperCase(Locale.ROOT);
    if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) return STRING;
    if (t.equals("TIMESTAMPTZ") || t.startsWith("TIMESTAMP") && t.contains("WITH TIME ZONE")) return OFFSET_DATE_TIME;
    if (t.contains("DATE") || t.contains("TIMESTAMP")) return DATE_TIME;
    if (t.equals("TIME") || t.startsWith("TIME(") || t.startsWith("TIME WITHOUT")) return TIME;
    if (t.equals("INTEGER") || t.equals("INT") || t.equals("SMALLINT") || t.equals("TINYINT")
        || t.equals("INT4") || t.equals("INT2") || t.equals("SERIAL") || t.equals("SERIAL4")
        || t.equals("SMALLSERIAL") || t.equals("SERIAL2")) {
      return INTEGER;
    }
    if (t.equals("BIGSERIAL") || t.equals("SERIAL8")) return LONG;
    if (t.equals("BOOL") || t.equals("BOOLEAN") || t.equals("BIT(1)")) return BOOLEAN;
    if (t.equals("UUID")) return UUID;
    if (t.equals("BYTEA") || t.equals("BLOB") || t.equals("RAW") || t.startsWith("RAW(") || t.equals("LONG RAW")) return BYTES;
    if (t.contains("FLOAT") || t.contains("DOUBLE") || t.equals("REAL")) return DECIMAL;
    if (t.startsWith("NUMBER") || t.startsWith("NUMERIC") || t.startsWith("DECIMAL") || t.equals("BIGINT") || t.equals("INT8")) {
      return fractional ? DECIMAL : LONG;
    }
    return OBJECT;
  }

  /** 메타 칼럼: scale 이 0 이 아니면 소수로 본다(Oracle 의 정밀도 없는 NUMBER 는 scale 이 음수로 온다). */
  public static String forColumn(String dbType, int scale) {
    return javaType(dbType, scale != 0);
  }

  /** 메타 칼럼: 위에 더해 자릿수가 UNBOUNDED_PRECISION 이상인 숫자(자릿수 없는 PG numeric)도 소수로 본다. */
  public static String forColumn(String dbType, int size, int scale) {
    return javaType(dbType, scale != 0 || size >= UNBOUNDED_PRECISION);
  }

  /**
   * 실행 결과 칼럼. precision · scale 은 ResultSetMetaData 값(모르면 unknown). 정밀도가 있으면 칼럼 메타처럼 정하고,
   * 정밀도가 0 이하이거나 scale 을 모르면(PG 의 자릿수 없는 numeric 등) scale 이 0 아닌지 또는 결과 표본으로 정한다.
   */
  public static String forResult(String dbType, int precision, int scale, int unknown, List<List<Object>> rows, int col) {
    if (scale != unknown && precision != unknown && precision > 0) return forColumn(dbType, precision, scale);
    boolean fractional = scale != unknown && scale != 0 || anyFraction(rows, col);
    return javaType(dbType, fractional);
  }

  /** 결과 표본: 값 하나라도 정수가 아니면 소수로 본다. 숫자가 아닌 값 · null 은 건너뛴다. */
  public static boolean anyFraction(List<List<Object>> rows, int col) {
    for (List<Object> row : rows) {
      Object v = col < row.size() ? row.get(col) : null;
      if (v instanceof BigDecimal d) {
        if (d.stripTrailingZeros().scale() > 0) return true;
      } else if (v instanceof Double || v instanceof Float) {
        double x = ((Number) v).doubleValue();
        if (x != Math.rint(x)) return true;
      }
    }
    return false;
  }
}
