package ei.db.gen;

import java.util.Locale;
import java.util.Set;

/** DB 이름 → 자바 이름 · SQL 표기. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다). */
public final class Names {
  private Names() {}

  /** 두 글자 별칭이 SQL 예약어와 겹치면 별칭으로 못 쓴다. */
  private static final Set<String> RESERVED = Set.of("as", "at", "by", "do", "go", "if", "in", "is", "of", "on", "or", "to");
  private static final String FALLBACK_ALIAS = "t";

  /** PRODUCT_ID → productId. 밑줄이 없고 대소문자가 섞인 이름(productId)은 첫 글자만 낮춘다. 식별자에 못 쓰는 글자는 밑줄로 본다. */
  public static String camel(String raw) {
    String s = raw.replaceAll("[^A-Za-z0-9_]", "_");
    boolean mixed = !s.contains("_") && !s.equals(s.toUpperCase(Locale.ROOT)) && !s.equals(s.toLowerCase(Locale.ROOT));
    StringBuilder b = new StringBuilder();
    if (mixed) {
      b.append(Character.toLowerCase(s.charAt(0))).append(s.substring(1));
    } else {
      for (String part : s.toLowerCase(Locale.ROOT).split("_")) {
        if (part.isEmpty()) continue;
        b.append(b.length() == 0 ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
      }
    }
    if (b.length() == 0) return "field";
    return Character.isDigit(b.charAt(0)) ? "f" + b : b.toString();
  }

  /** ORDER_ITEM → OrderItem. */
  public static String pascal(String raw) {
    return upperFirst(camel(raw));
  }

  public static String upperFirst(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  public static String lowerFirst(String s) {
    return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  /** 밑줄로 나눈 머리글자(ORDER_ITEM → oi). 예약어와 겹치거나 비면 t. */
  public static String alias(String table) {
    StringBuilder b = new StringBuilder();
    for (String part : table.split("_")) {
      if (!part.isEmpty() && Character.isLetter(part.charAt(0))) b.append(Character.toLowerCase(part.charAt(0)));
    }
    String a = b.toString();
    return a.isEmpty() || RESERVED.contains(a) ? FALLBACK_ALIAS : a;
  }

  /** SQL 본문 표기. 모두 대문자인 이름(Oracle 기본)만 소문자로 쓰고, 대소문자가 섞인 이름은 따옴표 없이 쓰면 달라지므로 그대로 둔다. */
  public static String sql(String name) {
    return name.equals(name.toUpperCase(Locale.ROOT)) ? name.toLowerCase(Locale.ROOT) : name;
  }

  /** 마지막 점 뒤(단순 이름). */
  public static String simple(String fqn) {
    return fqn.substring(fqn.lastIndexOf('.') + 1);
  }

  /** 마지막 점 앞(패키지). 점이 없으면 빈 글자. */
  public static String packageOf(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i < 0 ? "" : fqn.substring(0, i);
  }
}
