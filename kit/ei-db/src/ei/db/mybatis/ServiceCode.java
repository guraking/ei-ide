package ei.db.mybatis;

import ei.db.gen.Names;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 매퍼 문장 하나에서 컨트롤러 · 서비스 · 매퍼 메서드 글을 만든다. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 * 형식 글은 정규 이름을 담을 수 있고(java.util.List&lt;com.x.OrderDto&gt;), 만든 코드에는 단순 이름으로 쓰며
 * 들여올 이름은 imports 로 따로 준다. 만든 코드는 4칸 들여쓰기, 줄 끝은 \n.
 */
public final class ServiceCode {
  private ServiceCode() {}

  /** 매개변수 하나. type 은 정규 이름일 수 있다. */
  public record Param(String type, String name) {}

  /** 메서드 모양. guessed 는 인터페이스 메서드 없이 XML 속성으로 짐작한 것. */
  public record Sig(String returnType, List<Param> params, boolean guessed) {}

  /** DTO 속성 하나(getter 이름에서 get · is 를 뗀 것)와 그 형식. */
  public record Prop(String name, String type) {}

  /** 부모-자식 서비스 메서드에 넣을 값. keyType 이 비면 Object. */
  public record Join(String parentMapperField, String parentId, Sig parent,
                     String childMapperField, String childId, Sig child,
                     String keyProperty, String keyType, String listName) {}

  public static final String SELECT = "select";
  public static final String GET = "GET";
  public static final String POST = "POST";
  public static final List<String> HTTP_METHODS = List.of(GET, POST, "PUT", "DELETE", "PATCH");

  private static final String INDENT = "    ";
  /** 메서드 체인 이어 쓰기 들여쓰기(본문 들여쓰기 + 8칸). */
  private static final String CONTINUATION = INDENT + INDENT + INDENT;
  private static final String LIST = "java.util.List";
  private static final String ARRAY_LIST = "java.util.ArrayList";
  private static final String MAP = "java.util.Map";
  private static final String COLLECTORS = "java.util.stream.Collectors";
  private static final String OBJECT = "java.lang.Object";
  private static final String VOID = "void";
  /** insert · update · delete 가 돌려주는 바뀐 행 수. */
  private static final String DML_RETURN = "int";
  private static final String MAP_TYPE = MAP + "<java.lang.String, " + OBJECT + ">";
  private static final String MAP_PARAM_NAME = "params";
  private static final String SCALAR_PARAM_NAME = "value";
  private static final String JAVA_LANG = "java.lang.";
  private static final String SPRING_ANNOTATIONS = "org.springframework.web.bind.annotation.";
  private static final String REQUEST_PARAM = "RequestParam";
  private static final String REQUEST_BODY = "RequestBody";
  private static final String MAPPING_SUFFIX = "Mapping";
  private static final String SERVICE_SUFFIX = "Service";
  private static final String FALLBACK_SERVICE_FIELD = "service";
  private static final String LIST_SUFFIX = "List";
  private static final String PLURAL_SUFFIX = "s";
  private static final String GETTER_PREFIX = "get";
  private static final String SETTER_PREFIX = "set";
  private static final String JOIN_METHOD_INFIX = "With";
  private static final String BY_INFIX = "By";

  /** 매퍼 · DAO 이름 끝말. 떼고 Service 를 붙여 서비스 필드 이름을 짐작한다. */
  private static final List<String> MAPPER_SUFFIXES = List.of("Mapper", "Dao", "DAO", "Repository");
  /** DTO 이름 끝말. 떼어 변수 · 목록 이름의 바탕으로 쓴다. */
  private static final List<String> DTO_SUFFIXES = List.of("Dto", "DTO", "Vo", "VO", "Entity");

  /** MyBatis 기본 별칭(소문자) · java.lang 이름 → 자바 형식. 여기 없는 이름은 클래스로 본다. */
  private static final Map<String, String> SCALARS = Map.ofEntries(
      Map.entry("_int", "int"), Map.entry("int", "int"), Map.entry("integer", "int"),
      Map.entry("_long", "long"), Map.entry("long", "long"),
      Map.entry("_short", "short"), Map.entry("short", "short"),
      Map.entry("_double", "double"), Map.entry("double", "double"),
      Map.entry("_float", "float"), Map.entry("float", "float"),
      Map.entry("_boolean", "boolean"), Map.entry("boolean", "boolean"),
      Map.entry("string", "java.lang.String"),
      Map.entry("date", "java.util.Date"),
      Map.entry("decimal", "java.math.BigDecimal"), Map.entry("bigdecimal", "java.math.BigDecimal"),
      Map.entry("object", OBJECT),
      Map.entry("map", MAP_TYPE), Map.entry("hashmap", MAP_TYPE),
      Map.entry("java.util.map", MAP_TYPE), Map.entry("java.util.hashmap", MAP_TYPE));

  private static final Map<String, String> BOXES = Map.of(
      "int", "Integer", "long", "Long", "short", "Short", "double", "Double", "float", "Float",
      "boolean", "Boolean", "char", "Character", "byte", "Byte");

  /** 패키지 마디(소문자로 시작하는 이름 + 점) 뒤에 대문자로 시작하는 클래스 이름이 오는 꼴. */
  private static final Pattern QUALIFIER = Pattern.compile("\\b(?:[a-z_][\\w$]*\\.)+(?=[A-Z])");
  private static final Pattern QUALIFIED = Pattern.compile("\\b(?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*");
  private static final Pattern LIST_OF = Pattern.compile("^(?:java\\.util\\.)?List<\\s*(.+?)\\s*>$");

  // ---- 모양 짐작 ----

  /**
   * XML 속성으로 모양을 짐작한다. resolve 는 별칭 · 단순 이름을 정규 이름으로 바꾼다(모르면 받은 값 그대로).
   * insert · update · delete → int. select → 기본 별칭이면 그 형식, 아니면 List&lt;resultType 또는 resultMap 의 type&gt;.
   * parameterType → 클래스면 (클래스, 낮춘 단순 이름), Map 이면 (Map&lt;String, Object&gt;, params), 기본 별칭이면 (형식, value), 없으면 없음.
   */
  public static Sig fromXml(MapperDoc doc, MapperDoc.Node statement, UnaryOperator<String> resolve) {
    String returnType;
    if (!SELECT.equals(statement.name)) {
      returnType = DML_RETURN;
    } else {
      String rt = blankToNull(statement.attr("resultType"));
      if (rt == null) rt = resultMapType(doc, statement.attr("resultMap"));
      String scalar = rt == null ? null : SCALARS.get(rt.toLowerCase(Locale.ROOT));
      // shortcut: map 별칭은 한 행으로 본다. 여러 행이면 사용자가 List 로 고친다.
      if (scalar != null) returnType = scalar;
      else if (rt == null) returnType = LIST + "<" + OBJECT + ">";
      else returnType = LIST + "<" + classType(rt, resolve) + ">";
    }
    List<Param> params = new ArrayList<>();
    String pt = blankToNull(statement.attr("parameterType"));
    if (pt != null) {
      String scalar = SCALARS.get(pt.toLowerCase(Locale.ROOT));
      if (MAP_TYPE.equals(scalar)) params.add(new Param(MAP_TYPE, MAP_PARAM_NAME));
      else if (scalar != null) params.add(new Param(scalar, SCALAR_PARAM_NAME));
      else {
        String type = classType(pt, resolve);
        params.add(new Param(type, Names.lowerFirst(simple(type))));
      }
    }
    return new Sig(returnType, params, true);
  }

  /** 같은 파일의 &lt;resultMap id&gt; 의 type. "네임스페이스.id" 꼴도 받는다. 찾지 못하면 null. */
  public static String resultMapType(MapperDoc doc, String resultMapId) {
    String id = blankToNull(resultMapId);
    if (id == null) return null;
    if (doc.namespace != null && id.startsWith(doc.namespace + ".")) id = id.substring(doc.namespace.length() + 1);
    for (MapperDoc.Node mapper : doc.root.children) {
      if (!"mapper".equals(mapper.name)) continue;
      for (MapperDoc.Node n : mapper.children) {
        if (MapperRefs.RESULT_MAP_TAG.equals(n.name) && id.equals(n.attr("id"))) return blankToNull(n.attr("type"));
      }
    }
    return null;
  }

  /** java.lang 이름은 그대로, 점 없는 별칭은 resolve 에 맡기고 바뀌지 않으면 첫 글자만 올린다. */
  private static String classType(String raw, UnaryOperator<String> resolve) {
    String t = raw.trim();
    if (t.contains(".")) return t;
    String resolved = resolve.apply(t);
    return resolved != null && !resolved.equals(t) ? resolved : Names.upperFirst(t);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  // ---- 이름 ----

  /** 정규 이름 · 제네릭 형식 글의 패키지 마디를 뗀다(java.util.List&lt;com.x.A&gt; → List&lt;A&gt;). */
  public static String simple(String typeText) {
    return QUALIFIER.matcher(typeText).replaceAll("");
  }

  /** 형식 글들에 나온 정규 이름(java.lang 제외), 이름 순. */
  public static Set<String> importsOf(Sig sig) {
    Set<String> out = new TreeSet<>();
    collectImports(sig.returnType(), out);
    for (Param p : sig.params()) collectImports(p.type(), out);
    return out;
  }

  private static void collectImports(String typeText, Set<String> out) {
    Matcher m = QUALIFIED.matcher(typeText);
    while (m.find()) {
      String q = m.group();
      if (!q.startsWith(JAVA_LANG) || q.indexOf('.', JAVA_LANG.length()) >= 0) out.add(q);
    }
  }

  /** 매퍼 필드 이름: 매퍼 단순 이름의 첫 글자를 낮춘다. */
  public static String mapperField(String mapperSimple) {
    return Names.lowerFirst(mapperSimple);
  }

  /** 서비스 필드 이름: OrderMapper → orderService. 끝말을 떼어 비면 service. */
  public static String serviceField(String mapperSimple) {
    String base = stripSuffix(mapperSimple, MAPPER_SUFFIXES);
    return base.isEmpty() ? FALLBACK_SERVICE_FIELD : Names.lowerFirst(base) + SERVICE_SUFFIX;
  }

  /** DTO 이름의 바탕: OrderItemDto → OrderItem. 끝말을 떼어 비면 받은 이름. */
  public static String baseName(String dtoSimple) {
    String base = stripSuffix(dtoSimple, DTO_SUFFIXES);
    return base.isEmpty() ? dtoSimple : base;
  }

  /** 자식 목록 이름 기본값: OrderItemDto → orderItemList. */
  public static String childListName(String childSimple) {
    return Names.lowerFirst(baseName(childSimple)) + LIST_SUFFIX;
  }

  /** order → orders. s 로 끝나면 List 를 붙인다(status → statusList). */
  public static String plural(String word) {
    return word.endsWith(PLURAL_SUFFIX) ? word + LIST_SUFFIX : word + PLURAL_SUFFIX;
  }

  public static String getter(String property) {
    return GETTER_PREFIX + Names.upperFirst(property);
  }

  public static String setter(String property) {
    return SETTER_PREFIX + Names.upperFirst(property);
  }

  /** List&lt;X&gt; 의 X(정규 이름 그대로). 목록이 아니면 null. */
  public static String elementType(String returnType) {
    Matcher m = LIST_OF.matcher(returnType.trim());
    return m.matches() ? m.group(1) : null;
  }

  /** 기본형은 감싼 형식으로, 비면 Object. */
  public static String boxed(String type) {
    if (type == null || type.isBlank()) return "Object";
    String t = simple(type.trim());
    return BOXES.getOrDefault(t, t);
  }

  public static String defaultUrl(String id) {
    return "/" + id;
  }

  public static String defaultHttpMethod(String tag) {
    return SELECT.equals(tag) ? GET : POST;
  }

  /** 두 DTO 에 함께 있는 속성(a 의 순서). 하나도 없으면 둘을 합친 것. */
  public static List<Prop> commonKeys(List<Prop> a, List<Prop> b) {
    List<Prop> common = new ArrayList<>();
    for (Prop p : a) {
      for (Prop q : b) {
        if (p.name().equals(q.name())) {
          common.add(p);
          break;
        }
      }
    }
    if (!common.isEmpty()) return common;
    List<Prop> all = new ArrayList<>(a);
    for (Prop q : b) {
      boolean seen = false;
      for (Prop p : all) seen |= p.name().equals(q.name());
      if (!seen) all.add(q);
    }
    return all;
  }

  private static String stripSuffix(String name, List<String> suffixes) {
    for (String s : suffixes) {
      if (name.endsWith(s)) return name.substring(0, name.length() - s.length());
    }
    return name;
  }

  // ---- 코드 글 ----

  /** 인터페이스 선언(매퍼 · 서비스 인터페이스 공통): List&lt;OrderDto&gt; selectOrderList(OrderDto orderDto); */
  public static String declaration(String id, Sig sig) {
    return simple(sig.returnType()) + " " + id + "(" + paramList(sig, null) + ");\n";
  }

  /** 서비스 구현 메서드: 매퍼를 그대로 부른다. */
  public static String serviceMethod(String mapperField, String id, Sig sig) {
    return "public " + simple(sig.returnType()) + " " + id + "(" + paramList(sig, null) + ") {\n"
        + INDENT + returnPrefix(sig) + mapperField + "." + id + "(" + argList(sig) + ");\n"
        + "}\n";
  }

  /**
   * 컨트롤러 메서드. GET: Map · 기본형 매개변수에 @RequestParam, DTO 는 그대로(값 묶기).
   * 그 밖의 방식: DTO · Map 에 @RequestBody, 기본형에 @RequestParam.
   */
  public static String controllerMethod(String serviceField, String id, Sig sig, String url, String httpMethod) {
    return "@" + mappingAnnotation(httpMethod) + "(\"" + url.replace("\"", "\\\"") + "\")\n"
        + "public " + simple(sig.returnType()) + " " + id + "(" + paramList(sig, httpMethod) + ") {\n"
        + INDENT + returnPrefix(sig) + serviceField + "." + id + "(" + argList(sig) + ");\n"
        + "}\n";
  }

  /** 컨트롤러 코드에 들여올 이름: 형식들 + 매핑 · 매개변수 주석. */
  public static Set<String> controllerImports(Sig sig, String httpMethod) {
    Set<String> out = importsOf(sig);
    out.add(SPRING_ANNOTATIONS + mappingAnnotation(httpMethod));
    for (Param p : sig.params()) {
      String ann = paramAnnotation(p, httpMethod);
      if (ann != null) out.add(SPRING_ANNOTATIONS + ann);
    }
    return out;
  }

  /** GET → GetMapping 따위. 모르는 방식이면 RequestMapping. */
  private static String mappingAnnotation(String httpMethod) {
    String m = httpMethod == null ? "" : httpMethod.trim().toUpperCase(Locale.ROOT);
    if (!HTTP_METHODS.contains(m)) return "Request" + MAPPING_SUFFIX;
    return m.charAt(0) + m.substring(1).toLowerCase(Locale.ROOT) + MAPPING_SUFFIX;
  }

  private static String paramAnnotation(Param p, String httpMethod) {
    boolean scalar = isScalar(p.type());
    if (GET.equalsIgnoreCase(httpMethod)) return scalar || p.type().startsWith(MAP) ? REQUEST_PARAM : null;
    return scalar ? REQUEST_PARAM : REQUEST_BODY;
  }

  private static boolean isScalar(String type) {
    return BOXES.containsKey(type) || BOXES.containsValue(simple(type)) || (type.startsWith(JAVA_LANG) && !type.equals(OBJECT))
        || type.startsWith("java.math.") || type.equals("java.util.Date");
  }

  /** httpMethod 가 null 이면 주석 없이. */
  private static String paramList(Sig sig, String httpMethod) {
    StringBuilder b = new StringBuilder();
    for (Param p : sig.params()) {
      if (b.length() > 0) b.append(", ");
      String ann = httpMethod == null ? null : paramAnnotation(p, httpMethod);
      if (ann != null) b.append('@').append(ann).append(' ');
      b.append(simple(p.type())).append(' ').append(p.name());
    }
    return b.toString();
  }

  private static String argList(Sig sig) {
    StringBuilder b = new StringBuilder();
    for (Param p : sig.params()) {
      if (b.length() > 0) b.append(", ");
      b.append(p.name());
    }
    return b.toString();
  }

  private static String returnPrefix(Sig sig) {
    return VOID.equals(sig.returnType()) ? "" : "return ";
  }

  /**
   * 부모 목록을 읽고 자식 목록을 키로 묶어 부모마다 채우는 서비스 메서드. 부모 · 자식 모두 List 를 돌려주어야 한다.
   * 자식 문장에는 부모 메서드의 매개변수를 그대로 넘긴다(자식이 매개변수를 받지 않으면 비운다).
   * @throws IllegalArgumentException 부모나 자식이 목록을 돌려주지 않거나 키 · 목록 이름이 비었을 때
   */
  public static String parentChild(Join j) {
    String parentElement = elementType(j.parent().returnType());
    String childElement = elementType(j.child().returnType());
    if (parentElement == null) throw new IllegalArgumentException("부모 문장 " + j.parentId() + " 이(가) 목록(List)을 돌려주지 않습니다.");
    if (childElement == null) throw new IllegalArgumentException("자식 문장 " + j.childId() + " 이(가) 목록(List)을 돌려주지 않습니다.");
    if (j.keyProperty() == null || j.keyProperty().isBlank()) throw new IllegalArgumentException("연결 키를 고르거나 적어 주세요.");
    if (j.listName() == null || j.listName().isBlank()) throw new IllegalArgumentException("자식 목록 이름을 적어 주세요.");
    String parentSimple = simple(parentElement);
    String childSimple = simple(childElement);
    String key = j.keyProperty().trim();
    String listName = j.listName().trim();
    String parentVar = Names.lowerFirst(baseName(parentSimple));
    String parentsVar = plural(parentVar);
    String mapVar = listName + BY_INFIX + Names.upperFirst(parentVar);
    // shortcut: 자식 문장의 매개변수 형식이 부모와 달라도 부모 매개변수를 넘긴다. 다르면 사용자가 창에서 고친다.
    String childArgs = j.child().params().isEmpty() ? "" : argList(j.parent());
    String returnType = simple(j.parent().returnType());
    return "public " + returnType + " " + j.parentId() + JOIN_METHOD_INFIX + Names.upperFirst(listName)
        + "(" + paramList(j.parent(), null) + ") {\n"
        + INDENT + returnType + " " + parentsVar + " = " + j.parentMapperField() + "." + j.parentId() + "(" + argList(j.parent()) + ");\n"
        + INDENT + "Map<" + boxed(j.keyType()) + ", List<" + childSimple + ">> " + mapVar + " = "
        + j.childMapperField() + "." + j.childId() + "(" + childArgs + ").stream()\n"
        + CONTINUATION + ".collect(Collectors.groupingBy(" + childSimple + "::" + getter(key) + "));\n"
        + INDENT + "for (" + parentSimple + " " + parentVar + " : " + parentsVar + ") {\n"
        + INDENT + INDENT + parentVar + "." + setter(listName) + "(" + mapVar + ".getOrDefault(" + parentVar + "." + getter(key) + "(), List.of()));\n"
        + INDENT + "}\n"
        + INDENT + "return " + parentsVar + ";\n"
        + "}\n";
  }

  /** 부모-자식 메서드에 들여올 이름. */
  public static Set<String> parentChildImports(Join j) {
    Set<String> out = new TreeSet<>(importsOf(j.parent()));
    out.addAll(importsOf(j.child()));
    out.add(LIST);
    out.add(MAP);
    out.add(COLLECTORS);
    return out;
  }

  /**
   * 부모 DTO 에 더할 자식 목록 필드. lombok 이면 필드만, 아니면 getter · setter 까지.
   * childElement 는 자식 DTO 형식(정규 이름이어도 된다).
   */
  public static String childField(String childElement, String listName, boolean lombok) {
    String type = "List<" + simple(childElement) + ">";
    String name = listName.trim();
    StringBuilder b = new StringBuilder();
    b.append("private ").append(type).append(' ').append(name).append(" = new ArrayList<>();\n");
    if (lombok) return b.toString();
    b.append('\n')
        .append("public ").append(type).append(' ').append(getter(name)).append("() {\n")
        .append(INDENT).append("return ").append(name).append(";\n")
        .append("}\n\n")
        .append("public void ").append(setter(name)).append('(').append(type).append(' ').append(name).append(") {\n")
        .append(INDENT).append("this.").append(name).append(" = ").append(name).append(";\n")
        .append("}\n");
    return b.toString();
  }

  /** 자식 목록 필드에 들여올 이름. */
  public static Set<String> childFieldImports(String childElement) {
    Set<String> out = new LinkedHashSet<>();
    out.add(LIST);
    out.add(ARRAY_LIST);
    collectImports(childElement, out);
    return out;
  }
}
