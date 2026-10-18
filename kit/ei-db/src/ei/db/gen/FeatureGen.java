package ei.db.gen;

import ei.db.Meta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 표 하나의 기능 한 벌: DTO · 매퍼 XML · 매퍼 인터페이스 · 서비스(클래스 하나 또는 인터페이스 + Impl) · 컨트롤러.
 * 화면과 떨어진 순수 논리라 자체 점검에서 바로 부른다.
 * 매퍼 문장은 MapperGen 이 만들고 목록 검색 조건은 고른 칼럼의 &lt;if&gt; 만 남긴다.
 * 서비스 · 컨트롤러 메서드 이름은 매퍼 문장 id 와 같고, 만들 수 없는 문장(MapperGen.possible 거짓)의 메서드는 빠진다.
 */
public final class FeatureGen {

  public enum Out { DTO, XML, MAPPER, SERVICE, SERVICE_IMPL, CONTROLLER }

  /**
   * 형식 이름은 모두 FQN(패키지가 없으면 단순 이름). serviceImplType 이 null 이면 서비스는 클래스 하나,
   * 아니면 serviceType 은 인터페이스이고 serviceImplType 이 구현이다.
   * rest 가 거짓이면 @Controller: 목록 · 상세는 Model 에 담아 화면 이름을 돌려주고 등록 · 수정 · 삭제는 @ResponseBody.
   * baseUrl 은 「/」 로 시작하고 「/」 로 끝나지 않는다. searchColumns 는 목록 검색에 쓸 DB 칼럼 이름.
   * kinds 는 만들 매퍼 문장(서비스 · 컨트롤러 메서드도 이것을 따른다), style 은 MapperGen 의 SQL 스타일 · DB 종류.
   */
  public record Spec(String table, List<Meta.Column> cols, MapperGen.Options style, Set<MapperGen.Kind> kinds,
                     String dtoType, String mapperType, String serviceType, String serviceImplType, String controllerType,
                     boolean rest, String baseUrl, Set<String> searchColumns) {}

  /** 서비스 · 컨트롤러가 갖는 문장(resultMap 은 메서드가 없다). */
  private static final List<MapperGen.Kind> METHOD_KINDS = List.of(
      MapperGen.Kind.LIST, MapperGen.Kind.ONE, MapperGen.Kind.INSERT, MapperGen.Kind.UPDATE, MapperGen.Kind.DELETE);
  private static final String IND = "    ";
  private static final String IND2 = IND + IND;
  private static final String PARAM = "param";
  private static final String LIST_PARAM = "Map<String, Object> " + PARAM;
  private static final String RANGE_FROM = "From";
  private static final String RANGE_TO = "To";
  private static final String IF_OPEN = "<if test=\"";
  /** MapperGen 의 검색 &lt;if&gt; 한 덩이: 여는 줄 · 조건 줄 · 닫는 줄. */
  private static final int IF_LINES = 3;
  private static final String VIEW_LIST = "list";
  private static final String VIEW_DETAIL = "detail";
  private static final String MODEL_LIST = "list";
  private static final String MODEL_ITEM = "item";
  private static final String FALLBACK_URL = "/item";

  private static final String LIST = "java.util.List";
  private static final String MAP = "java.util.Map";
  private static final String SERVICE = "org.springframework.stereotype.Service";
  private static final String CONTROLLER = "org.springframework.stereotype.Controller";
  private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
  private static final String MODEL = "org.springframework.ui.Model";
  private static final String WEB = "org.springframework.web.bind.annotation.";
  private static final String REST_CONTROLLER = WEB + "RestController";
  private static final String REQUEST_MAPPING = WEB + "RequestMapping";
  private static final String GET = WEB + "GetMapping";
  private static final String POST = WEB + "PostMapping";
  private static final String PUT = WEB + "PutMapping";
  private static final String DELETE = WEB + "DeleteMapping";
  private static final String PATH_VARIABLE = WEB + "PathVariable";
  private static final String REQUEST_PARAM = WEB + "RequestParam";
  private static final String REQUEST_BODY = WEB + "RequestBody";
  private static final String RESPONSE_BODY = WEB + "ResponseBody";

  private final Spec s;
  private final MapperGen mapper;
  private final String dto;

  public FeatureGen(Spec s) {
    this.s = s;
    this.mapper = new MapperGen(s.table(), s.cols(), s.mapperType(), s.dtoType(), s.style());
    this.dto = Names.simple(s.dtoType());
  }

  public MapperGen mapper() {
    return mapper;
  }

  public boolean split() {
    return s.serviceImplType() != null;
  }

  /** ORDER_ITEM → /order-item. 글자 · 숫자가 없으면 /item. */
  public static String defaultBaseUrl(String table) {
    String t = table.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    return t.isEmpty() ? FALLBACK_URL : "/" + t;
  }

  /** PK 가 없거나 PK 아닌 칼럼이 없어 빠지는 문장을 알리는 글. 빠지는 것이 없으면 null. */
  public String missingNote() {
    if (!mapper.hasPk()) return "PK 가 없어 상세 · 수정 · 삭제는 만들지 않습니다.";
    if (!mapper.hasNonPk()) return "PK 아닌 칼럼이 없어 수정은 만들지 않습니다.";
    return null;
  }

  // ---- 새 파일 ----

  /** 새 파일 전체. 나누지 않을 때 SERVICE_IMPL 은 null. */
  public String file(Out o) {
    return switch (o) {
      case DTO -> mapper.dtoFile();
      case XML -> {
        StringBuilder b = new StringBuilder(Xml.HEADER);
        b.append("<mapper namespace=\"").append(Xml.attr(s.mapperType())).append("\">\n");
        b.append(MapperGen.joinBlocks(xmlBlocks()));
        yield b.append("</mapper>\n").toString();
      }
      case MAPPER -> mapper.interfaceFile(s.kinds());
      case SERVICE -> split()
          ? javaFile(s.serviceType(), imports(o), "public interface " + Names.simple(s.serviceType()) + " {\n", "", blocks(o))
          : javaFile(s.serviceType(), imports(o), "@Service\npublic class " + Names.simple(s.serviceType()) + " {\n",
                     injection(s.serviceType(), s.mapperType()), blocks(o));
      case SERVICE_IMPL -> split()
          ? javaFile(s.serviceImplType(), imports(o), "@Service\npublic class " + Names.simple(s.serviceImplType())
                     + " implements " + Names.simple(s.serviceType()) + " {\n", injection(s.serviceImplType(), s.mapperType()), blocks(o))
          : null;
      case CONTROLLER -> javaFile(s.controllerType(), imports(o),
          (s.rest() ? "@RestController\n" : "@Controller\n") + "@RequestMapping(\"" + s.baseUrl() + "\")\npublic class "
          + Names.simple(s.controllerType()) + " {\n", injection(s.controllerType(), s.serviceType()), blocks(o));
    };
  }

  private static String javaFile(String fqn, Set<String> imports, String decl, String members, List<MapperGen.Block> methods) {
    StringBuilder b = new StringBuilder();
    String pkg = Names.packageOf(fqn);
    if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
    for (String imp : imports) b.append("import ").append(imp).append(";\n");
    if (!imports.isEmpty()) b.append('\n');
    b.append(decl).append(members);
    for (MapperGen.Block m : methods) b.append(m.text());
    return b.append("}\n").toString();
  }

  /** 생성자 주입 필드와 생성자. */
  private static String injection(String owner, String depType) {
    String type = Names.simple(depType);
    String field = Names.lowerFirst(type);
    return "\n" + IND + "private final " + type + " " + field + ";\n\n"
        + IND + "public " + Names.simple(owner) + "(" + type + " " + field + ") {\n"
        + IND2 + "this." + field + " = " + field + ";\n" + IND + "}\n";
  }

  // ---- 덧붙이기 ----

  /** 덧붙일 수 없으면 그 까닭, 있으면 null. 서비스 · 컨트롤러는 부르는 필드가 이미 있어야 메서드가 컴파일된다. */
  public String cannotMerge(Out o, String existing) {
    if (o == Out.DTO) return "DTO 는 덧붙이지 않습니다";
    String need = requiredField(o);
    if (need != null && !Pattern.compile("\\b" + Pattern.quote(need) + "\\b").matcher(existing).find()) {
      return "필드 " + need + " 가 없어 메서드를 덧붙이지 않습니다";
    }
    return null;
  }

  /** 있는 파일에 없는 문장 · 메서드만 덧붙인 결과. cannotMerge 가 null 일 때만 부른다. */
  public Merge.Result merge(Out o, String existing) {
    return o == Out.XML ? Merge.xml(existing, xmlBlocks()) : Merge.java(existing, blocks(o), new ArrayList<>(imports(o)));
  }

  private String requiredField(Out o) {
    return switch (o) {
      case SERVICE -> split() ? null : Names.lowerFirst(Names.simple(s.mapperType()));
      case SERVICE_IMPL -> Names.lowerFirst(Names.simple(s.mapperType()));
      case CONTROLLER -> Names.lowerFirst(Names.simple(s.serviceType()));
      default -> null;
    };
  }

  // ---- 블록 ----

  /** 매퍼 XML 문장. 목록은 고른 칼럼의 검색 조건만 남긴다. */
  public List<MapperGen.Block> xmlBlocks() {
    List<MapperGen.Block> out = new ArrayList<>();
    String listId = mapper.id(MapperGen.Kind.LIST);
    for (MapperGen.Block b : mapper.xmlBlocks(s.kinds())) {
      out.add(b.id().equals(listId) ? new MapperGen.Block(b.id(), keepConditions(b.text(), searchFields())) : b);
    }
    return out;
  }

  /** 목록 검색에 남길 필드: 날짜 칼럼은 필드From · 필드To, 나머지는 필드 이름. */
  Set<String> searchFields() {
    Set<String> out = new HashSet<>();
    for (Meta.Column c : s.cols()) {
      if (!s.searchColumns().contains(c.name())) continue;
      String f = Names.camel(c.name());
      if (MapperGen.isDate(c.type())) {
        out.add(f + RANGE_FROM);
        out.add(f + RANGE_TO);
      } else {
        out.add(f);
      }
    }
    return out;
  }

  /** &lt;if test="필드 ..."&gt; 로 여는 세 줄 덩이 가운데 필드가 fields 에 없는 것을 뺀다. */
  static String keepConditions(String block, Set<String> fields) {
    String[] lines = block.split("\n", -1);
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].strip();
      if (t.startsWith(IF_OPEN) && !fields.contains(leadingIdent(t.substring(IF_OPEN.length())))) {
        i += IF_LINES - 1;
        continue;
      }
      kept.add(lines[i]);
    }
    return String.join("\n", kept);
  }

  private static String leadingIdent(String s) {
    int i = 0;
    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) i++;
    return s.substring(0, i);
  }

  /** 자바 파일의 메서드 블록. XML · DTO 는 빈 목록(XML 은 xmlBlocks). */
  public List<MapperGen.Block> blocks(Out o) {
    List<MapperGen.Block> out = new ArrayList<>();
    if (o == Out.DTO || o == Out.XML) return out;
    if (o == Out.MAPPER) return mapper.methods(s.kinds());
    if (o == Out.SERVICE_IMPL && !split()) return out;
    String mapperField = Names.lowerFirst(Names.simple(s.mapperType()));
    for (MapperGen.Kind k : kinds()) {
      String id = mapper.id(k);
      String text = switch (o) {
        case SERVICE -> split() ? IND + signature(k) + ";\n" : serviceMethod(k, mapperField, false);
        case SERVICE_IMPL -> serviceMethod(k, mapperField, true);
        default -> controllerMethod(k);
      };
      out.add(new MapperGen.Block(id, text));
    }
    return out;
  }

  private List<MapperGen.Kind> kinds() {
    List<MapperGen.Kind> out = new ArrayList<>();
    for (MapperGen.Kind k : METHOD_KINDS) if (s.kinds().contains(k) && mapper.possible(k)) out.add(k);
    return out;
  }

  private String signature(MapperGen.Kind k) {
    String id = mapper.id(k);
    return switch (k) {
      case LIST -> "List<" + dto + "> " + id + "(" + LIST_PARAM + ")";
      case ONE -> dto + " " + id + "(" + dto + " " + PARAM + ")";
      default -> "int " + id + "(" + dto + " " + PARAM + ")";
    };
  }

  private static boolean writes(MapperGen.Kind k) {
    return k == MapperGen.Kind.INSERT || k == MapperGen.Kind.UPDATE || k == MapperGen.Kind.DELETE;
  }

  private String serviceMethod(MapperGen.Kind k, String mapperField, boolean override) {
    StringBuilder b = new StringBuilder("\n");
    if (override) b.append(IND).append("@Override\n");
    if (writes(k)) b.append(IND).append("@Transactional\n");
    b.append(IND).append("public ").append(signature(k)).append(" {\n");
    b.append(IND2).append("return ").append(mapperField).append('.').append(mapper.id(k)).append('(').append(PARAM).append(");\n");
    return b.append(IND).append("}\n").toString();
  }

  private List<Meta.Column> pkCols() {
    List<Meta.Column> out = new ArrayList<>();
    for (Meta.Column c : s.cols()) if (c.pk()) out.add(c);
    return out;
  }

  private static String javaType(Meta.Column c) {
    return TypeMap.forColumn(c.type(), c.scale());
  }

  /** 복합 PK 는 /{a}/{b}. */
  private String pkPath() {
    StringBuilder b = new StringBuilder();
    for (Meta.Column c : pkCols()) b.append("/{").append(Names.camel(c.name())).append('}');
    return b.toString();
  }

  /** 이름을 밝힌 @PathVariable 목록(-parameters 없이 컴파일해도 맞게). */
  private String pkParams() {
    List<String> out = new ArrayList<>();
    for (Meta.Column c : pkCols()) {
      String f = Names.camel(c.name());
      out.add("@PathVariable(\"" + f + "\") " + javaType(c) + " " + f);
    }
    return String.join(", ", out);
  }

  private String setPk(boolean newParam) {
    StringBuilder b = new StringBuilder();
    if (newParam) b.append(IND2).append(dto).append(' ').append(PARAM).append(" = new ").append(dto).append("();\n");
    for (Meta.Column c : pkCols()) {
      String f = Names.camel(c.name());
      b.append(IND2).append(PARAM).append(".set").append(Names.upperFirst(f)).append('(').append(f).append(");\n");
    }
    return b.toString();
  }

  private String viewName(String page) {
    return s.baseUrl().substring(1) + "/" + page;
  }

  private String controllerMethod(MapperGen.Kind k) {
    String svc = Names.lowerFirst(Names.simple(s.serviceType()));
    String id = mapper.id(k);
    String call = svc + "." + id + "(" + PARAM + ")";
    StringBuilder b = new StringBuilder("\n");
    switch (k) {
      case LIST -> {
        b.append(IND).append("@GetMapping\n");
        if (s.rest()) {
          b.append(IND).append("public List<").append(dto).append("> ").append(id).append("(@RequestParam ").append(LIST_PARAM).append(") {\n");
          b.append(IND2).append("return ").append(call).append(";\n");
        } else {
          b.append(IND).append("public String ").append(id).append("(@RequestParam ").append(LIST_PARAM).append(", Model model) {\n");
          b.append(IND2).append("model.addAttribute(\"").append(MODEL_LIST).append("\", ").append(call).append(");\n");
          b.append(IND2).append("return \"").append(viewName(VIEW_LIST)).append("\";\n");
        }
      }
      case ONE -> {
        b.append(IND).append("@GetMapping(\"").append(pkPath()).append("\")\n");
        if (s.rest()) {
          b.append(IND).append("public ").append(dto).append(' ').append(id).append('(').append(pkParams()).append(") {\n");
          b.append(setPk(true)).append(IND2).append("return ").append(call).append(";\n");
        } else {
          b.append(IND).append("public String ").append(id).append('(').append(pkParams()).append(", Model model) {\n");
          b.append(setPk(true));
          b.append(IND2).append("model.addAttribute(\"").append(MODEL_ITEM).append("\", ").append(call).append(");\n");
          b.append(IND2).append("return \"").append(viewName(VIEW_DETAIL)).append("\";\n");
        }
      }
      case INSERT -> {
        b.append(IND).append("@PostMapping\n").append(responseBody());
        b.append(IND).append("public int ").append(id).append("(@RequestBody ").append(dto).append(' ').append(PARAM).append(") {\n");
        b.append(IND2).append("return ").append(call).append(";\n");
      }
      case UPDATE -> {
        b.append(IND).append("@PutMapping(\"").append(pkPath()).append("\")\n").append(responseBody());
        b.append(IND).append("public int ").append(id).append('(').append(pkParams()).append(", @RequestBody ").append(dto)
         .append(' ').append(PARAM).append(") {\n");
        b.append(setPk(false)).append(IND2).append("return ").append(call).append(";\n");
      }
      default -> {
        b.append(IND).append("@DeleteMapping(\"").append(pkPath()).append("\")\n").append(responseBody());
        b.append(IND).append("public int ").append(id).append('(').append(pkParams()).append(") {\n");
        b.append(setPk(true)).append(IND2).append("return ").append(call).append(";\n");
      }
    }
    return b.append(IND).append("}\n").toString();
  }

  private String responseBody() {
    return s.rest() ? "" : IND + "@ResponseBody\n";
  }

  // ---- import ----

  /** 쓰는 형식의 import. 같은 패키지 · 기본 패키지 형식은 넣지 않는다. */
  public Set<String> imports(Out o) {
    if (o == Out.SERVICE_IMPL && !split()) return new TreeSet<>();
    Set<String> types = new TreeSet<>();
    String self;
    List<MapperGen.Kind> kinds = kinds();
    boolean writes = false;
    for (MapperGen.Kind k : kinds) writes |= writes(k);
    boolean hasList = kinds.contains(MapperGen.Kind.LIST);
    switch (o) {
      case SERVICE -> {
        self = s.serviceType();
        types.add(s.dtoType());
        if (hasList) {
          types.add(LIST);
          types.add(MAP);
        }
        if (!split()) {
          types.add(s.mapperType());
          types.add(SERVICE);
          if (writes) types.add(TRANSACTIONAL);
        }
      }
      case SERVICE_IMPL -> {
        self = s.serviceImplType();
        types.add(s.dtoType());
        types.add(s.mapperType());
        types.add(s.serviceType());
        if (hasList) {
          types.add(LIST);
          types.add(MAP);
        }
        types.add(SERVICE);
        if (writes) types.add(TRANSACTIONAL);
      }
      case CONTROLLER -> {
        self = s.controllerType();
        types.add(s.dtoType());
        types.add(s.serviceType());
        types.add(REQUEST_MAPPING);
        types.add(s.rest() ? REST_CONTROLLER : CONTROLLER);
        if (hasList) {
          types.add(MAP);
          types.add(REQUEST_PARAM);
          if (s.rest()) types.add(LIST);
        }
        if (hasList || kinds.contains(MapperGen.Kind.ONE)) types.add(GET);
        if (!s.rest() && (hasList || kinds.contains(MapperGen.Kind.ONE))) types.add(MODEL);
        if (!s.rest() && writes) types.add(RESPONSE_BODY);
        if (kinds.contains(MapperGen.Kind.INSERT) || kinds.contains(MapperGen.Kind.UPDATE)) types.add(REQUEST_BODY);
        if (kinds.contains(MapperGen.Kind.INSERT)) types.add(POST);
        if (kinds.contains(MapperGen.Kind.UPDATE)) types.add(PUT);
        if (kinds.contains(MapperGen.Kind.DELETE)) types.add(DELETE);
        if (kinds.contains(MapperGen.Kind.ONE) || kinds.contains(MapperGen.Kind.UPDATE) || kinds.contains(MapperGen.Kind.DELETE)) {
          types.add(PATH_VARIABLE);
          for (Meta.Column c : pkCols()) {
            String imp = TypeMap.IMPORTS.get(javaType(c));
            if (imp != null) types.add(imp);
          }
        }
      }
      case MAPPER -> {
        return new TreeSet<>(mapper.interfaceImports(s.kinds()));
      }
      default -> {
        return types;
      }
    }
    Set<String> out = new TreeSet<>();
    String pkg = Names.packageOf(self);
    for (String t : types) {
      String tp = Names.packageOf(t);
      if (!tp.isEmpty() && !tp.equals(pkg)) out.add(t);
    }
    return out;
  }
}
