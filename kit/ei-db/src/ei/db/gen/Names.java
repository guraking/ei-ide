package ei.db.gen;

import ei.db.DbKind;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** DB 이름 → 자바 이름 · SQL 표기. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다). */
public final class Names {
  private Names() {}

  /** 두 글자 별칭이 SQL 예약어와 겹치면 별칭으로 못 쓴다. */
  private static final Set<String> RESERVED = Set.of("as", "at", "by", "do", "go", "if", "in", "is", "of", "on", "or", "to");
  private static final String FALLBACK_ALIAS = "t";
  /** 따옴표 없이 써도 그 이름 그대로 잡히는 꼴: Oracle 은 대문자로, PostgreSQL 은 소문자로 접힌다. */
  private static final Pattern ORACLE_PLAIN = Pattern.compile("[A-Z][A-Z0-9_$#]*");
  private static final Pattern POSTGRES_PLAIN = Pattern.compile("[a-z_][a-z0-9_$]*");

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

  /**
   * SQL 본문의 스키마 · 표 · 칼럼 표기(메타에서 읽은 이름을 받는다. 별칭은 alias 가 소문자로 만든다).
   * Oracle: 대문자 이름이면 그대로(대문자), 아니면 "그대로". PostgreSQL: 소문자 이름이면 대문자로(접혀서 같은 이름), 아니면 "그대로".
   * MySQL: 리눅스에서 표 이름 대소문자를 가리므로 그대로.
   * shortcut: 예약어와 같은 이름(USER · ORDER 등)은 따옴표를 붙이지 않는다. 그런 표가 생기면 예약어 목록을 더한다.
   */
  public static String sql(DbKind kind, String name) {
    return switch (kind) {
      case MYSQL -> name;
      case ORACLE -> ORACLE_PLAIN.matcher(name).matches() ? name : quote(name);
      case POSTGRES -> POSTGRES_PLAIN.matcher(name).matches() ? name.toUpperCase(Locale.ROOT) : quote(name);
    };
  }

  /** 스키마.표 표기. */
  public static String sql(DbKind kind, String schema, String table) {
    return sql(kind, schema) + "." + sql(kind, table);
  }

  private static String quote(String name) {
    return '"' + name.replace("\"", "\"\"") + '"';
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
