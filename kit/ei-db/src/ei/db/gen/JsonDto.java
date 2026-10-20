package ei.db.gen;

import javax.lang.model.SourceVersion;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * JSON 글 → DTO 클래스 글. IntelliJ 에 기대지 않는다(자체 점검 JsonDtoCheck 에서 바로 부른다).
 * 객체는 Map(키 순서 유지), 배열은 List, 정수는 BigInteger, 소수 · 지수는 BigDecimal, 불린은 Boolean, null 은 null 로 읽는다.
 * 중첩 객체는 최상위 클래스 안의 public static class 로 한 단계에 나란히 둔다(이름이 한 파일 안에서 겹치지 않게 숫자를 붙인다).
 * DtoGen.dto 는 필드 주석(@JsonProperty) · 중첩 클래스를 받지 않아 글은 여기서 만들고 꼴(4칸 들여쓰기 · Lombok · getter/setter)은 DtoGen 과 맞춘다.
 */
public final class JsonDto {
  private JsonDto() {}

  /** 이보다 깊게 겹친 JSON 은 받지 않는다(재귀 하강이 스택을 넘기지 않게). */
  private static final int MAX_DEPTH = 512;
  private static final String INDENT = "    ";
  private static final String LIST = "List";
  private static final String BOOLEAN = "Boolean";
  private static final String JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty";
  private static final String LIST_IMPORT = "java.util.List";
  /** 중첩 클래스 이름으로 쓰면 필드 형식과 헷갈리는 이름. 이 이름이 나오면 숫자를 붙인다. */
  private static final Set<String> TYPE_NAMES = Set.of(TypeMap.STRING, TypeMap.LONG, TypeMap.DECIMAL, TypeMap.OBJECT, BOOLEAN, LIST);

  /** JSON 이 틀렸을 때. position 은 1부터 센 글자 위치(최상위 꼴이 맞지 않는 경우처럼 위치가 없으면 0). */
  public static final class JsonException extends Exception {
    public final int position;

    JsonException(String message, int position) {
      super(position > 0 ? position + "번째 글자: " + message : message);
      this.position = position;
    }
  }

  // ---- 파서 ----

  public static Object parse(String text) throws JsonException {
    Parser p = new Parser(text);
    p.skipSpace();
    Object v = p.value(0);
    p.skipSpace();
    if (p.i < text.length()) throw p.error("값 뒤에 남은 글자가 있습니다");
    return v;
  }

  private static final class Parser {
    final String s;
    int i;

    Parser(String s) { this.s = s; }

    JsonException error(String message) {
      return new JsonException(message, i + 1);
    }

    void skipSpace() {
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
        i++;
      }
    }

    Object value(int depth) throws JsonException {
      if (depth > MAX_DEPTH) throw error("너무 깊게 겹쳤습니다(" + MAX_DEPTH + "단계까지)");
      if (i >= s.length()) throw error("값이 와야 하는데 글이 끝났습니다");
      char c = s.charAt(i);
      if (c == '{') return object(depth);
      if (c == '[') return array(depth);
      if (c == '"') return string();
      if (c == '-' || (c >= '0' && c <= '9')) return number();
      if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
      if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
      if (s.startsWith("null", i)) { i += 4; return null; }
      throw error("알 수 없는 값입니다('" + c + "')");
    }

    Map<String, Object> object(int depth) throws JsonException {
      Map<String, Object> m = new LinkedHashMap<>();
      i++;
      skipSpace();
      if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
      while (true) {
        skipSpace();
        if (i >= s.length() || s.charAt(i) != '"') throw error("키(따옴표 글자)가 와야 합니다");
        String key = string();
        skipSpace();
        expect(':', "키 뒤에 ':' 가 와야 합니다");
        skipSpace();
        m.put(key, value(depth + 1));
        skipSpace();
        if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
        expect('}', "',' 또는 '}' 가 와야 합니다");
        return m;
      }
    }

    List<Object> array(int depth) throws JsonException {
      List<Object> list = new ArrayList<>();
      i++;
      skipSpace();
      if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
      while (true) {
        skipSpace();
        list.add(value(depth + 1));
        skipSpace();
        if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
        expect(']', "',' 또는 ']' 가 와야 합니다");
        return list;
      }
    }

    void expect(char c, String message) throws JsonException {
      if (i >= s.length() || s.charAt(i) != c) throw error(message);
      i++;
    }

    String string() throws JsonException {
      i++;
      StringBuilder b = new StringBuilder();
      while (true) {
        if (i >= s.length()) throw error("문자열이 닫히지 않았습니다");
        char c = s.charAt(i);
        if (c == '"') { i++; return b.toString(); }
        if (c < ' ') throw error("문자열 안에 제어 글자가 있습니다(\\n 처럼 써야 합니다)");
        if (c != '\\') { b.append(c); i++; continue; }
        if (i + 1 >= s.length()) throw error("문자열이 닫히지 않았습니다");
        char e = s.charAt(i + 1);
        switch (e) {
          case '"', '\\', '/' -> b.append(e);
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case 'n' -> b.append('\n');
          case 'r' -> b.append('\r');
          case 't' -> b.append('\t');
          case 'u' -> {
            if (i + 6 > s.length()) throw error("\\u 뒤에 16진수 네 자리가 와야 합니다");
            int code = 0;
            for (int k = i + 2; k < i + 6; k++) {
              int d = Character.digit(s.charAt(k), 16);
              if (d < 0) throw error("\\u 뒤에 16진수 네 자리가 와야 합니다");
              code = code * 16 + d;
            }
            b.append((char) code);
            i += 4;
          }
          default -> throw error("알 수 없는 이스케이프입니다(\\" + e + ")");
        }
        i += 2;
      }
    }

    /** -?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)? */
    Number number() throws JsonException {
      int start = i;
      boolean fraction = false;
      if (s.charAt(i) == '-') i++;
      if (i < s.length() && s.charAt(i) == '0') i++;
      else if (digits() == 0) throw error("숫자가 와야 합니다");
      if (i < s.length() && s.charAt(i) == '.') {
        i++;
        fraction = true;
        if (digits() == 0) throw error("소수점 뒤에 숫자가 와야 합니다");
      }
      if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        i++;
        fraction = true;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        if (digits() == 0) throw error("지수 뒤에 숫자가 와야 합니다");
      }
      String t = s.substring(start, i);
      return fraction ? new BigDecimal(t) : new BigInteger(t);
    }

    int digits() {
      int from = i;
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
      return i - from;
    }
  }

  // ---- 꼴 합치기 ----

  /** 값의 꼴. NULL 은 아직 모르는 꼴(다른 원소 · 다른 값과 합치면 그쪽을 따른다). elem 이 null 인 배열은 빈 배열. */
  private sealed interface Shape permits Scalar, Obj, Arr {}

  private record Scalar(String type) implements Shape {}

  private record Obj(Map<String, Shape> fields) implements Shape {}

  private record Arr(Shape elem) implements Shape {}

  private static final Scalar NULL = new Scalar(null);

  private static Shape shape(Object v) {
    if (v == null) return NULL;
    if (v instanceof String) return new Scalar(TypeMap.STRING);
    if (v instanceof Boolean) return new Scalar(BOOLEAN);
    if (v instanceof BigInteger) return new Scalar(TypeMap.LONG);
    if (v instanceof BigDecimal) return new Scalar(TypeMap.DECIMAL);
    if (v instanceof Map<?, ?> m) {
      Map<String, Shape> fields = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) fields.put((String) e.getKey(), shape(e.getValue()));
      return new Obj(fields);
    }
    Shape elem = null;
    for (Object o : (List<?>) v) {
      Shape s = shape(o);
      elem = elem == null ? s : merge(elem, s);
    }
    return new Arr(elem);
  }

  /** 정수 + 소수 → 소수, 객체끼리는 키를 합치고, 배열끼리는 원소 꼴을 합친다. 서로 다른 꼴은 Object. */
  private static Shape merge(Shape a, Shape b) {
    if (a == NULL) return b;
    if (b == NULL) return a;
    if (a instanceof Scalar x && b instanceof Scalar y) {
      if (x.type().equals(y.type())) return a;
      boolean numbers = isNumber(x.type()) && isNumber(y.type());
      return new Scalar(numbers ? TypeMap.DECIMAL : TypeMap.OBJECT);
    }
    if (a instanceof Obj x && b instanceof Obj y) {
      Map<String, Shape> fields = new LinkedHashMap<>(x.fields());
      for (Map.Entry<String, Shape> e : y.fields().entrySet()) {
        Shape old = fields.get(e.getKey());
        fields.put(e.getKey(), old == null ? e.getValue() : merge(old, e.getValue()));
      }
      return new Obj(fields);
    }
    if (a instanceof Arr x && b instanceof Arr y) {
      if (x.elem() == null) return b;
      if (y.elem() == null) return a;
      return new Arr(merge(x.elem(), y.elem()));
    }
    return new Scalar(TypeMap.OBJECT);
  }

  private static boolean isNumber(String type) {
    return TypeMap.LONG.equals(type) || TypeMap.DECIMAL.equals(type);
  }

  // ---- 글 만들기 ----

  private record Field(String key, String name, String type) {}

  private record Cls(String name, List<Field> fields) {}

  /** 만드는 중인 파일 하나의 상태: 쓴 클래스 이름, 클래스 목록(첫째가 최상위), import. */
  private static final class Build {
    final Set<String> names = new HashSet<>(TYPE_NAMES);
    final List<Cls> classes = new ArrayList<>();
    final Set<String> imports = new TreeSet<>();

    String uniqueName(String base) {
      String name = base;
      for (int n = 2; !names.add(name); n++) name = base + n;
      return name;
    }

    /** 이 꼴의 필드 형식. 객체면 클래스를 하나 더 만든다. */
    String type(Shape s, String key) {
      if (s instanceof Obj o) return cls(Names.pascal(key), o);
      if (s instanceof Arr a) {
        imports.add(LIST_IMPORT);
        return LIST + "<" + (a.elem() == null ? TypeMap.OBJECT : type(a.elem(), key)) + ">";
      }
      String t = ((Scalar) s).type();
      if (t == null) return TypeMap.OBJECT;
      String imp = TypeMap.IMPORTS.get(t);
      if (imp != null) imports.add(imp);
      return t;
    }

    String cls(String base, Obj o) {
      Cls c = new Cls(uniqueName(base), new ArrayList<>());
      classes.add(c);
      Set<String> used = new HashSet<>();
      for (Map.Entry<String, Shape> e : o.fields().entrySet()) {
        String name = fieldName(e.getKey());
        String unique = name;
        for (int n = 2; !used.add(unique); n++) unique = name + n;
        if (!unique.equals(e.getKey())) imports.add(JSON_PROPERTY);
        c.fields().add(new Field(e.getKey(), unique, type(e.getValue(), e.getKey())));
      }
      return c.name();
    }
  }

  /** Names.camel 꼴. 자바 예약어(class · default 등)면 뒤에 밑줄을 붙인다. */
  private static String fieldName(String key) {
    String name = Names.camel(key);
    return SourceVersion.isKeyword(name) ? name + "_" : name;
  }

  /**
   * JSON 글 → 자바 파일 글. 최상위가 배열이면 첫 원소를 본다. 최상위(또는 첫 원소)가 객체가 아니면 JsonException(위치 0).
   * pkg 가 비면 package 줄을 쓰지 않는다.
   */
  public static String dto(String json, String pkg, String cls, boolean lombok) throws JsonException {
    Object v = parse(json);
    if (v instanceof List<?> list) {
      if (list.isEmpty()) throw new JsonException("최상위 배열이 비어 있어 클래스를 만들 수 없습니다", 0);
      v = list.get(0);
    }
    if (!(v instanceof Map<?, ?>)) throw new JsonException("최상위는 객체({…}) 또는 객체 배열이어야 합니다", 0);
    Build b = new Build();
    b.names.remove(cls);
    b.cls(cls, (Obj) shape(v));
    if (lombok) {
      b.imports.add("lombok.Getter");
      b.imports.add("lombok.Setter");
    }

    StringBuilder out = new StringBuilder();
    if (!pkg.isEmpty()) out.append("package ").append(pkg).append(";\n\n");
    for (String imp : b.imports) out.append("import ").append(imp).append(";\n");
    if (!b.imports.isEmpty()) out.append('\n');
    List<Cls> nested = b.classes.subList(1, b.classes.size());
    body(out, b.classes.get(0), "", false, lombok, nested);
    return out.toString();
  }

  private static void body(StringBuilder b, Cls c, String indent, boolean nested, boolean lombok, List<Cls> inner) {
    String in = indent + INDENT;
    if (lombok) b.append(indent).append("@Getter\n").append(indent).append("@Setter\n");
    b.append(indent).append(nested ? "public static class " : "public class ").append(c.name()).append(" {\n");
    for (Field f : c.fields()) {
      if (!f.name().equals(f.key())) b.append(in).append("@JsonProperty(\"").append(javaString(f.key())).append("\")\n");
      b.append(in).append("private ").append(f.type()).append(' ').append(f.name()).append(";\n");
    }
    if (!lombok) {
      for (Field f : c.fields()) {
        String up = Names.upperFirst(f.name());
        b.append('\n');
        b.append(in).append("public ").append(f.type()).append(" get").append(up).append("() {\n");
        b.append(in).append(INDENT).append("return ").append(f.name()).append(";\n").append(in).append("}\n\n");
        b.append(in).append("public void set").append(up).append('(').append(f.type()).append(' ').append(f.name()).append(") {\n");
        b.append(in).append(INDENT).append("this.").append(f.name()).append(" = ").append(f.name()).append(";\n").append(in).append("}\n");
      }
    }
    for (Cls n : inner) {
      b.append('\n');
      body(b, n, in, true, lombok, List.of());
    }
    b.append(indent).append("}\n");
  }

  /** 자바 문자열 리터럴 안에 넣을 글. */
  private static String javaString(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < ' ') b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.toString();
  }
}
