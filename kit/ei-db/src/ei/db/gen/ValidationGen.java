package ei.db.gen;

import ei.db.Meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 칼럼 메타 → Bean Validation 주석. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다). */
public final class ValidationGen {
  private ValidationGen() {}

  public static final String NOT_BLANK = "NotBlank";
  public static final String NOT_NULL = "NotNull";
  public static final String SIZE = "Size";
  public static final String DIGITS = "Digits";

  /** 클래스 이름에서 떼는 꼬리. 긴 것부터 본다(VO 와 Vo 는 길이가 같아 순서 상관없음). */
  private static final List<String> SUFFIXES = List.of("Entity", "Dto", "Vo", "VO");
  private static final String STRING = "String";
  /** @Digits 가 받는 숫자 형식(Bean Validation 스펙). double · float 는 반올림 오차로 스펙에서 빠져 있다. */
  private static final Set<String> NUMBERS = Set.of(
      "BigDecimal", "BigInteger", "Long", "Integer", "Short", "Byte", "long", "int", "short", "byte");

  /** name 은 단순 이름, args 는 괄호 안(없으면 빈 글자). */
  public record Ann(String name, String args) {
    /** 패키지를 붙인 주석 글. 예: @jakarta.validation.constraints.Size(max = 100) */
    public String text(String pkg) {
      return "@" + pkg + "." + name + (args.isEmpty() ? "" : "(" + args + ")");
    }

    /** 미리보기용 짧은 글. 예: @Size(max = 100) */
    public String shortText() {
      return "@" + name + (args.isEmpty() ? "" : "(" + args + ")");
    }
  }

  /** OrderItemDto → ORDER_ITEM, HTTPLogVO → HTTP_LOG. 꼬리만 있는 이름(Dto)은 꼬리를 떼지 않는다. */
  public static String guessTable(String className) {
    String base = className;
    for (String s : SUFFIXES) {
      if (base.endsWith(s) && base.length() > s.length()) {
        base = base.substring(0, base.length() - s.length());
        break;
      }
    }
    return base.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").toUpperCase(Locale.ROOT);
  }

  /**
   * 필드 하나에 붙일 주석. javaType 은 필드 형식(단순 이름 또는 FQN, 제네릭 없음), existing 은 필드에 이미 있는 주석의 단순 이름.
   * 이미 있는 이름은 건너뛴다(값을 바꾸지 않는다).
   * @Size 는 칼럼이 문자 칼럼일 때만, @Digits 는 숫자 칼럼일 때만 붙인다(DATE 칼럼을 String 으로 받는 필드에 길이 7 을 걸지 않게).
   */
  public static List<Ann> annotations(Meta.Column col, String javaType, Set<String> existing) {
    String type = Names.simple(javaType);
    String colJava = TypeMap.forColumn(col.type(), col.scale());
    boolean string = STRING.equals(type);
    List<Ann> out = new ArrayList<>();
    if (!col.nullable()) out.add(new Ann(string ? NOT_BLANK : NOT_NULL, ""));
    if (string && col.size() > 0 && TypeMap.STRING.equals(colJava)) {
      out.add(new Ann(SIZE, "max = " + col.size()));
    }
    boolean numericColumn = TypeMap.LONG.equals(colJava) || TypeMap.INTEGER.equals(colJava) || TypeMap.DECIMAL.equals(colJava);
    if (NUMBERS.contains(type) && col.size() > 0 && numericColumn) {
      int fraction = Math.max(col.scale(), 0);
      int integer = Math.max(col.size() - fraction, 0);
      out.add(new Ann(DIGITS, "integer = " + integer + ", fraction = " + fraction));
    }
    out.removeIf(a -> existing.contains(a.name()));
    return out;
  }
}
