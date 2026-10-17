package ei.db.mybatis;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * MyBatis test · bind 식에 쓰이는 OGNL 의 작은 부분집합.
 * 이름(점 경로), null · true · false, 숫자, 문자열, 비교(== != &gt; &lt; &gt;= &lt;= 와 eq neq gt lt gte lte),
 * and · or(&amp;&amp; ||), !(not), +, 괄호, 메서드 size() isEmpty() length() toString() trim().
 * 읽지 못하는 식은 예외 없이 null 로 끝나므로 test 에서는 거짓이 된다.
 */
public final class Ognl {
  private static final Set<String> WORDS = Set.of("and", "or", "not", "eq", "neq", "gt", "lt", "gte", "lte",
                                                  "null", "true", "false");

  private final List<String> tokens;
  private final Function<String, Object> lookup;
  private int p;

  private Ognl(List<String> tokens, Function<String, Object> lookup) {
    this.tokens = tokens;
    this.lookup = lookup;
  }

  /** lookup 은 점 경로(예: "user.name")를 받아 값을 준다. 없으면 null. */
  public static Object eval(String expr, Function<String, Object> lookup) {
    try {
      Ognl o = new Ognl(lex(expr), lookup);
      Object v = o.or();
      return o.p == o.tokens.size() ? v : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** MyBatis ExpressionEvaluator.evaluateBoolean 과 같은 판정: Boolean 은 그대로, 숫자는 0 이 아니면, 나머지는 null 이 아니면 참. */
  public static boolean test(String expr, Function<String, Object> lookup) {
    Object v = eval(expr, lookup);
    if (v instanceof Boolean b) return b;
    if (v instanceof Number n) return new BigDecimal(n.toString()).signum() != 0;
    return v != null;
  }

  /** 식에 나오는 매개변수 점 경로(메서드 호출 앞까지). 예약어와 _ 로 시작하는 이름은 뺀다. */
  public static List<String> names(String expr) {
    List<String> out = new ArrayList<>();
    List<String> t;
    try {
      t = lex(expr);
    } catch (RuntimeException e) {
      return out;
    }
    for (int k = 0; k < t.size(); k++) {
      String tok = t.get(k);
      if (!isIdent(tok) || WORDS.contains(tok) || tok.startsWith("_") || (k > 0 && t.get(k - 1).equals("."))) continue;
      StringBuilder path = new StringBuilder(tok);
      int j = k + 1;
      while (j + 1 < t.size() && t.get(j).equals(".") && isIdent(t.get(j + 1))
             && !(j + 2 < t.size() && t.get(j + 2).equals("("))) {
        path.append('.').append(t.get(j + 1));
        j += 2;
      }
      out.add(path.toString());
    }
    return out;
  }

  private Object or() {
    Object v = and();
    while (peekAny("or", "||")) {
      p++;
      boolean left = truthy(v);
      Object r = and();
      v = left || truthy(r);
    }
    return v;
  }

  private Object and() {
    Object v = equality();
    while (peekAny("and", "&&")) {
      p++;
      boolean left = truthy(v);
      Object r = equality();
      v = left && truthy(r);
    }
    return v;
  }

  private Object equality() {
    Object v = relational();
    while (peekAny("==", "!=", "eq", "neq")) {
      String op = tokens.get(p++);
      Object r = relational();
      boolean same = equal(v, r);
      v = op.equals("==") || op.equals("eq") ? same : !same;
    }
    return v;
  }

  private Object relational() {
    Object v = additive();
    while (peekAny("<", ">", "<=", ">=", "lt", "gt", "lte", "gte")) {
      String op = tokens.get(p++);
      Object r = additive();
      Integer c = compare(v, r);
      if (c == null) return false;
      v = switch (op) {
        case "<", "lt" -> c < 0;
        case ">", "gt" -> c > 0;
        case "<=", "lte" -> c <= 0;
        default -> c >= 0;
      };
    }
    return v;
  }

  private Object additive() {
    Object v = unary();
    while (peekAny("+", "-")) {
      String op = tokens.get(p++);
      Object r = unary();
      BigDecimal a = number(v), b = number(r);
      if (op.equals("+") && (a == null || b == null || v instanceof String || r instanceof String)) {
        v = String.valueOf(v == null ? "" : v) + (r == null ? "" : r);
      } else if (a != null && b != null) {
        v = op.equals("+") ? a.add(b) : a.subtract(b);
      } else {
        v = null;
      }
    }
    return v;
  }

  private Object unary() {
    if (peekAny("!", "not")) {
      p++;
      return !truthy(unary());
    }
    if (peekAny("-")) {
      p++;
      BigDecimal n = number(unary());
      return n == null ? null : n.negate();
    }
    return postfix();
  }

  private Object postfix() {
    String tok = next();
    Object v;
    if (tok.equals("(")) {
      v = or();
      expect(")");
    } else if (tok.startsWith("'") || tok.startsWith("\"")) {
      v = tok.substring(1);
    } else if (Character.isDigit(tok.charAt(0))) {
      v = new BigDecimal(tok.replaceAll("[LlDdFf]$", ""));
    } else if (tok.equals("null")) {
      v = null;
    } else if (tok.equals("true") || tok.equals("false")) {
      v = Boolean.valueOf(tok);
    } else if (isIdent(tok)) {
      StringBuilder path = new StringBuilder(tok);
      while (peekAny(".") && p + 1 < tokens.size() && isIdent(tokens.get(p + 1))
             && !(p + 2 < tokens.size() && tokens.get(p + 2).equals("("))) {
        path.append('.').append(tokens.get(p + 1));
        p += 2;
      }
      v = lookup.apply(path.toString());
    } else {
      throw new IllegalArgumentException(tok);
    }
    while (peekAny(".")) {
      p++;
      String member = next();
      if (peekAny("(")) {
        p++;
        expect(")");
        v = call(v, member);
      } else {
        v = v instanceof Map<?, ?> m ? m.get(member) : null;
      }
    }
    return v;
  }

  private static Object call(Object v, String method) {
    if (v == null) return null;
    return switch (method) {
      case "size", "length" -> size(v);
      case "isEmpty" -> {
        Integer n = size(v);
        yield n == null ? null : n == 0;
      }
      case "toString" -> String.valueOf(v);
      case "trim" -> String.valueOf(v).trim();
      default -> null;
    };
  }

  private static Integer size(Object v) {
    if (v instanceof Collection<?> c) return c.size();
    if (v instanceof Map<?, ?> m) return m.size();
    if (v instanceof CharSequence s) return s.length();
    if (v.getClass().isArray()) return Array.getLength(v);
    return null;
  }

  static boolean truthy(Object v) {
    if (v instanceof Boolean b) return b;
    if (v instanceof Number n) return new BigDecimal(n.toString()).signum() != 0;
    return v != null;
  }

  private static boolean equal(Object a, Object b) {
    if (a == null || b == null) return a == b;
    BigDecimal x = number(a), y = number(b);
    if (x != null && y != null && (a instanceof Number || b instanceof Number)) return x.compareTo(y) == 0;
    return String.valueOf(a).equals(String.valueOf(b));
  }

  private static Integer compare(Object a, Object b) {
    if (a == null || b == null) return null;
    BigDecimal x = number(a), y = number(b);
    if (x != null && y != null) return x.compareTo(y);
    return String.valueOf(a).compareTo(String.valueOf(b));
  }

  private static BigDecimal number(Object v) {
    if (v instanceof Number n) return new BigDecimal(n.toString());
    if (v instanceof String s) {
      try {
        return new BigDecimal(s.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private boolean peekAny(String... options) {
    if (p >= tokens.size()) return false;
    for (String o : options) {
      if (tokens.get(p).equals(o)) return true;
    }
    return false;
  }

  private String next() {
    if (p >= tokens.size()) throw new IllegalArgumentException("식이 끝남");
    return tokens.get(p++);
  }

  private void expect(String t) {
    if (!next().equals(t)) throw new IllegalArgumentException(t);
  }

  private static boolean isIdent(String t) {
    return !t.isEmpty() && (Character.isLetter(t.charAt(0)) || t.charAt(0) == '_' || t.charAt(0) == '$');
  }

  /** 문자열 토큰은 여는 따옴표 하나 + 풀린 내용으로 담아 이름 · 숫자와 구별한다. */
  private static List<String> lex(String s) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '\'' || c == '"') {
        StringBuilder b = new StringBuilder().append(c);
        i++;
        while (i < s.length() && s.charAt(i) != c) {
          if (s.charAt(i) == '\\' && i + 1 < s.length()) i++;
          b.append(s.charAt(i++));
        }
        if (i >= s.length()) throw new IllegalArgumentException("닫히지 않은 문자열");
        i++;
        out.add(b.toString());
      } else if (Character.isDigit(c)) {
        int from = i;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i < s.length() && "LlDdFf".indexOf(s.charAt(i)) >= 0) i++;
        out.add(s.substring(from, i));
      } else if (Character.isLetter(c) || c == '_' || c == '$') {
        int from = i;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) i++;
        out.add(s.substring(from, i));
      } else {
        String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
        if (List.of("==", "!=", "<=", ">=", "&&", "||").contains(two)) {
          out.add(two);
          i += 2;
        } else if ("<>!().+-".indexOf(c) >= 0) {
          out.add(String.valueOf(c));
          i++;
        } else {
          throw new IllegalArgumentException(String.valueOf(c));
        }
      }
    }
    return out;
  }
}
