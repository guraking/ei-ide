package ei.db.ui;

import ei.db.DbConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 접속 목록 내보내기 · 가져오기 파일(JSON). 순수 자바.
 * 꼴: {"connections": [{"name": "...", "kind": "ORACLE", ...}]}. DbConfig 의 공개 필드(문자열 · 정수 · 참거짓 · 열거)를 그대로 쓰고,
 * id 는 쓰지 않는다(가져올 때 새로 만든다. 비밀번호가 id 에 묶여 있어 다른 접속의 비밀번호를 물려받지 않게).
 * driverJar 도 쓰지 않는다(이 PC 의 절대 경로라 다른 PC 에서는 없는 파일을 가리킨다. 비워 두면 드라이버를 자동으로 찾는다).
 * 비밀번호는 DbConfig 에 없으므로 파일에 들어가지 않는다. 가져올 때 모르는 키는 무시하고 빠진 키는 기본값으로 둔다.
 */
final class ConnectionsJson {
  private ConnectionsJson() {}

  static final String ROOT = "connections";
  private static final Set<String> SKIPPED = Set.of("id", "driverJar");
  private static final String IMPORTED = " (가져옴";
  private static final String BOM = "\uFEFF";

  static String write(List<DbConfig> list) {
    StringBuilder sb = new StringBuilder("{\n  \"").append(ROOT).append("\": [");
    for (int i = 0; i < list.size(); i++) {
      sb.append(i == 0 ? "\n    {" : ",\n    {");
      boolean first = true;
      for (Field f : fields()) {
        sb.append(first ? "\n" : ",\n").append("      ").append(quote(f.getName())).append(": ").append(literal(get(f, list.get(i))));
        first = false;
      }
      sb.append("\n    }");
    }
    return sb.append(list.isEmpty() ? "]\n}\n" : "\n  ]\n}\n").toString();
  }

  /** 잘못된 꼴이면 IllegalArgumentException(화면에 보일 문구). 앞의 BOM 은 건너뛴다. */
  static List<DbConfig> read(String json) {
    Object root = new Parser(json.startsWith(BOM) ? json.substring(1) : json).document();
    if (!(root instanceof Map<?, ?> m) || !(m.get(ROOT) instanceof List<?> items)) {
      throw new IllegalArgumentException("\"" + ROOT + "\" 목록이 없는 파일입니다.");
    }
    List<DbConfig> out = new ArrayList<>();
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> obj)) throw new IllegalArgumentException("접속 항목이 객체가 아닙니다: " + item);
      out.add(toConfig(obj));
    }
    return out;
  }

  /** 겹치지 않으면 그대로, 겹치면 「이름 (가져옴)」, 그것도 있으면 「이름 (가져옴 2)」 … */
  static String uniqueName(String name, Set<String> taken) {
    if (!taken.contains(name)) return name;
    String candidate = name + IMPORTED + ")";
    for (int n = 2; taken.contains(candidate); n++) candidate = name + IMPORTED + " " + n + ")";
    return candidate;
  }

  private static List<Field> fields() {
    List<Field> out = new ArrayList<>();
    for (Field f : DbConfig.class.getFields()) {
      int mod = f.getModifiers();
      if (!Modifier.isStatic(mod) && !Modifier.isFinal(mod) && !SKIPPED.contains(f.getName())) out.add(f);
    }
    return out;
  }

  private static Object get(Field f, DbConfig c) {
    try {
      return f.get(c);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e); // 공개 필드만 고르므로 일어나지 않는다.
    }
  }

  private static DbConfig toConfig(Map<?, ?> obj) {
    DbConfig c = new DbConfig();
    for (Field f : fields()) {
      if (!obj.containsKey(f.getName())) continue;
      try {
        f.set(c, convert(f, obj.get(f.getName())));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    if (c.name == null || c.name.isBlank()) throw new IllegalArgumentException("이름이 없는 접속이 있습니다.");
    return c;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object convert(Field f, Object v) {
    Class<?> t = f.getType();
    String bad = f.getName() + " 값이 올바르지 않습니다: " + v;
    if (t == String.class) {
      if (v == null) return "";
      if (v instanceof String s) return s;
    } else if (t == int.class) {
      if (v instanceof BigDecimal d) {
        try { return d.intValueExact(); } catch (ArithmeticException e) { throw new IllegalArgumentException(bad, e); }
      }
    } else if (t == boolean.class) {
      if (v instanceof Boolean b) return b;
    } else if (t.isEnum()) {
      if (v instanceof String s) {
        try { return Enum.valueOf((Class<? extends Enum>) t, s); } catch (IllegalArgumentException e) { throw new IllegalArgumentException(bad, e); }
      }
    } else {
      throw new IllegalStateException("직렬화하지 못하는 필드 형식입니다: " + f.getName() + " " + t.getSimpleName());
    }
    throw new IllegalArgumentException(bad);
  }

  private static String literal(Object v) {
    if (v == null) return "null";
    if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
    if (v instanceof Enum<?> e) return quote(e.name());
    return quote(String.valueOf(v));
  }

  static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
          else sb.append(ch);
        }
      }
    }
    return sb.append('"').toString();
  }

  /** 최소 JSON 읽개: 객체 · 배열 · 문자열 · 숫자(BigDecimal) · true · false · null. */
  private static final class Parser {
    private final String s;
    private int i;

    Parser(String s) { this.s = s; }

    Object document() {
      Object v = value();
      ws();
      if (i < s.length()) throw error("끝에 남은 글자가 있습니다");
      return v;
    }

    private Object value() {
      ws();
      if (i >= s.length()) throw error("값이 없습니다");
      char ch = s.charAt(i);
      if (ch == '{') return object();
      if (ch == '[') return array();
      if (ch == '"') return string();
      if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
      if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
      if (s.startsWith("null", i)) { i += 4; return null; }
      return number();
    }

    private Map<String, Object> object() {
      Map<String, Object> m = new LinkedHashMap<>();
      i++;
      ws();
      if (peek('}')) return m;
      do {
        ws();
        if (i >= s.length() || s.charAt(i) != '"') throw error("키가 와야 합니다");
        String key = string();
        ws();
        expect(':');
        m.put(key, value());
        ws();
      } while (peek(','));
      expect('}');
      return m;
    }

    private List<Object> array() {
      List<Object> out = new ArrayList<>();
      i++;
      ws();
      if (peek(']')) return out;
      do {
        out.add(value());
        ws();
      } while (peek(','));
      expect(']');
      return out;
    }

    private String string() {
      StringBuilder sb = new StringBuilder();
      i++;
      while (i < s.length()) {
        char ch = s.charAt(i++);
        if (ch == '"') return sb.toString();
        if (ch != '\\') { sb.append(ch); continue; }
        if (i >= s.length()) break;
        char e = s.charAt(i++);
        switch (e) {
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'u' -> {
            if (i + 4 > s.length()) throw error("\\u 뒤 네 자리가 없습니다");
            try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (NumberFormatException ex) { throw error("\\u 값이 잘못되었습니다"); }
            i += 4;
          }
          default -> sb.append(e);
        }
      }
      throw error("문자열이 닫히지 않았습니다");
    }

    private BigDecimal number() {
      int start = i;
      while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
      try {
        return new BigDecimal(s.substring(start, i));
      } catch (NumberFormatException e) {
        i = start;
        throw error("알 수 없는 값입니다");
      }
    }

    private void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char ch) {
      if (i < s.length() && s.charAt(i) == ch) { i++; return true; }
      return false;
    }

    private void expect(char ch) {
      if (!peek(ch)) throw error("'" + ch + "' 가 와야 합니다");
    }

    private IllegalArgumentException error(String what) {
      return new IllegalArgumentException("JSON 꼴이 아닙니다(" + (i + 1) + "번째 글자): " + what);
    }
  }
}
