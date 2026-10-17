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

  /** 콤보에 보이는 고를 거리. 직접 쳐 넣은 형식도 받는다. */
  public static final List<String> CHOICES = List.of(STRING, LONG, INTEGER, DECIMAL, DATE_TIME, "LocalDate", "Double", "Boolean", OBJECT);

  public static final Map<String, String> IMPORTS = Map.of(
      DECIMAL, "java.math.BigDecimal",
      DATE_TIME, "java.time.LocalDateTime",
      "LocalDate", "java.time.LocalDate");

  /**
   * fractional 은 소수가 있을 수 있는 숫자인지(칼럼 scale 이나 결과 표본으로 판정). 정수 숫자는 Long, 소수는 BigDecimal.
   * FLOAT · DOUBLE 처럼 늘 소수인 형식은 fractional 과 상관없이 BigDecimal.
   */
  public static String javaType(String dbType, boolean fractional) {
    String t = dbType == null ? "" : dbType.toUpperCase(Locale.ROOT);
    if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) return STRING;
    if (t.contains("DATE") || t.contains("TIMESTAMP")) return DATE_TIME;
    if (t.equals("INTEGER") || t.equals("INT") || t.equals("SMALLINT") || t.equals("TINYINT")
        || t.equals("INT4") || t.equals("INT2")) {
      return INTEGER;
    }
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
