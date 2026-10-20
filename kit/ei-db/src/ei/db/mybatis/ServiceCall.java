package ei.db.mybatis;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 매퍼 문장 하나를 부르는 서비스 쪽 코드 글(매개변수 준비 + 호출 줄)을 만든다. IntelliJ 클래스 없음.
 * 형식은 단순 이름으로 쓰고 들여올 이름은 넣지 않는다. 들여쓰기 없이 줄마다 \n 으로 끝난다.
 */
final class ServiceCall {
  private ServiceCall() {}

  private static final String VOID = "void";
  private static final String PARAM_VAR = "param";
  private static final String LIST_VAR = "list";
  private static final String RESULT_VAR = "result";
  private static final String COUNT_VAR = "count";
  private static final Pattern MAP_TYPE = Pattern.compile("(?:Hash|LinkedHash|Tree)?Map(?:<.*)?");
  /** 기본형은 null 을 담지 못해 기본값으로 둔다. */
  private static final Map<String, String> PRIMITIVE_DEFAULTS = Map.of(
      "int", "0", "long", "0L", "short", "0", "byte", "0", "double", "0.0", "float", "0f",
      "boolean", "false", "char", "'\\0'");

  /** 정규 이름을 떼고 나면 DTO 와 구별되지 않는 흔한 값 형식. */
  private static final Set<String> JAVA_VALUE_TYPES = Set.of("String", "Integer", "Long", "Short", "Byte", "Double",
      "Float", "Boolean", "Character", "Object", "BigDecimal", "BigInteger", "Date", "LocalDate", "LocalDateTime",
      "LocalTime", "Timestamp");

  /**
   * tag 는 select · insert · update · delete, names 는 문장에 쓰인 #{} 이름(점 이름은 첫 마디만 쓴다).
   * 매개변수가 하나일 때만 DTO setter · Map put 줄을 넣는다(여럿이면 #{} 이름이 어느 매개변수 것인지 모른다).
   */
  static String code(String tag, String id, ServiceCode.Sig sig, List<String> names, String mapperField) {
    StringBuilder b = new StringBuilder();
    List<ServiceCode.Param> params = sig.params();
    boolean single = params.size() == 1;
    Set<String> roots = roots(names);
    StringBuilder args = new StringBuilder();
    for (ServiceCode.Param p : params) {
      String type = ServiceCode.simple(p.type().trim());
      String var;
      if (isMap(type)) {
        var = single ? PARAM_VAR : p.name();
        b.append("Map<String, Object> ").append(var).append(" = new HashMap<>();\n");
        if (single) {
          for (String r : roots) b.append(var).append(".put(\"").append(r).append("\", null);\n");
        }
      } else if (isDto(type)) {
        var = single ? PARAM_VAR : p.name();
        b.append(type).append(' ').append(var).append(" = new ").append(type).append("();\n");
        if (single) {
          for (String r : roots) b.append(var).append('.').append(ServiceCode.setter(r)).append("(null);\n");
        }
      } else {
        var = single && !roots.isEmpty() ? roots.iterator().next() : p.name();
        b.append(type).append(' ').append(var).append(" = ").append(PRIMITIVE_DEFAULTS.getOrDefault(type, "null")).append(";\n");
      }
      if (args.length() > 0) args.append(", ");
      args.append(var);
    }
    String call = mapperField + "." + id + "(" + args + ");\n";
    String rt = sig.returnType().trim();
    if (VOID.equals(rt)) return b.append(call).toString();
    String simple = ServiceCode.simple(rt);
    String var;
    if (!ServiceCode.SELECT.equals(tag)) var = COUNT_VAR;
    else if (ServiceCode.elementType(rt) != null) var = LIST_VAR;
    else var = RESULT_VAR;
    return b.append(simple).append(' ').append(var).append(" = ").append(call).toString();
  }

  /** 첫 마디만, 처음 나온 순서로, 겹침 없이. */
  private static Set<String> roots(List<String> names) {
    Set<String> out = new LinkedHashSet<>();
    for (String n : names) {
      String t = n.trim();
      int dot = t.indexOf('.');
      String r = dot < 0 ? t : t.substring(0, dot);
      if (!r.isEmpty()) out.add(r);
    }
    return out;
  }

  private static boolean isMap(String simpleType) {
    return MAP_TYPE.matcher(simpleType).matches();
  }

  /** 단순 이름이 된 형식에서: 기본형 · 배열 · 제네릭 · 패키지가 남은 것(java.*) · java.lang 형식이 아니면 DTO. */
  private static boolean isDto(String simpleType) {
    if (PRIMITIVE_DEFAULTS.containsKey(simpleType) || simpleType.contains("<") || simpleType.contains("[")
        || simpleType.contains(".")) return false;
    return !JAVA_VALUE_TYPES.contains(simpleType);
  }
}
