package ei.db.mybatis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「Swagger 주석 만들기」의 글 만들기(springdoc · swagger v3 어노테이션). IntelliJ 클래스를 쓰지 않는다.
 * 모양은 「//@formatter:off」 ~ 「//@formatter:on」 사이에 @Operation 을 여러 줄로 쓰고 묶음마다 「=」 자리를 맞춘다.
 * 글자 값은 자바 문자열로 바꿔 넣는다(따옴표 · 역슬래시 · 줄바꿈).
 */
public final class SwaggerDoc {
  private SwaggerDoc() {}

  public static final String FORMATTER_OFF = "//@formatter:off";
  public static final String FORMATTER_ON = "//@formatter:on";
  /** 스프링의 @RequestBody 와 이름이 같아 정규 이름으로 쓴다. */
  private static final String REQUEST_BODY = "@io.swagger.v3.oas.annotations.parameters.RequestBody";
  private static final String OK_CODE = "200";
  private static final String IN = "    ";
  private static final String SUCCESS = "성공";
  private static final String DTO_SUFFIX = " dto";
  private static final String TAG_SUFFIX = " API";
  private static final String PLAIN_END = "한다.";
  private static final String POLITE_END = "합니다.";
  /** 「~을(를) ~한다.」 꼴 한 줄. 1: 목적어, 2: 동사 낱말. */
  private static final Pattern OBJECT_VERB = Pattern.compile("(.+?)(?:을\\(를\\)|을|를) (\\S+)한다\\.?");

  /** 자바 형식 단순 이름 → OpenAPI 기본 형식. 없는 형식은 string. */
  private static final Map<String, String> SCHEMA_TYPES = Map.ofEntries(
      Map.entry("int", "integer"), Map.entry("Integer", "integer"), Map.entry("long", "integer"), Map.entry("Long", "integer"),
      Map.entry("short", "integer"), Map.entry("Short", "integer"), Map.entry("byte", "integer"), Map.entry("Byte", "integer"),
      Map.entry("BigInteger", "integer"),
      Map.entry("double", "number"), Map.entry("Double", "number"), Map.entry("float", "number"), Map.entry("Float", "number"),
      Map.entry("BigDecimal", "number"),
      Map.entry("boolean", "boolean"), Map.entry("Boolean", "boolean"),
      Map.entry("List", "array"), Map.entry("Set", "array"), Map.entry("Collection", "array"));
  private static final String ARRAY = "array";
  /** SCHEMA_TYPES 에 없지만 요청 값 하나로 받는 형식(OpenAPI 로는 string). */
  private static final Set<String> SIMPLE_EXTRA = Set.of("String", "char", "Character", "Date", "LocalDate", "LocalDateTime", "LocalTime");
  private static final String STRING = "string";

  /** 주소 · 쿼리 · 헤더로 받는 값 하나. example 이 비면 example 을 쓰지 않는다. */
  public record Param(String name, String description, boolean required, String type, String example) {}

  /** 요청 본문. implementation(형식 단순 이름)이 null 이면 content 줄을 뺀다. */
  public record Body(String description, String implementation) {}

  /** @Operation 하나. body 가 null 이면 requestBody 를, params 가 비면 parameters 를, responseType 이 null 이면 응답 content 를 뺀다. */
  public record Op(String summary, String description, List<Param> params, Body body, String responseDescription, String responseType) {}

  /** 들여쓰기 없는 여러 줄 글(「//@formatter:off」 로 시작해 「//@formatter:on」 으로 끝난다, 끝에 줄바꿈 없음). */
  public static String operation(Op op) {
    List<String> l = new ArrayList<>();
    l.add(FORMATTER_OFF);
    l.add("@Operation(");
    l.add(IN + "summary     = " + quote(op.summary()) + ",");
    l.add(IN + "description = " + quote(op.description()) + ",");
    if (!op.params().isEmpty()) {
      l.add(IN + "parameters = {");
      for (int i = 0; i < op.params().size(); i++) {
        l.add(IN + IN + IN + parameter(op.params().get(i)) + (i + 1 < op.params().size() ? "," : ""));
      }
      l.add(IN + "},");
    }
    if (op.body() != null) {
      boolean content = op.body().implementation() != null;
      l.add(IN + "requestBody = " + REQUEST_BODY + "(");
      l.add(IN + IN + "description = " + quote(op.body().description()) + (content ? "," : ""));
      if (content) l.add(IN + IN + "content     = " + content(op.body().implementation()));
      l.add(IN + "),");
    }
    boolean content = op.responseType() != null;
    l.add(IN + "responses = {");
    l.add(IN + IN + "@ApiResponse(");
    l.add(IN + IN + IN + "responseCode = " + quote(OK_CODE) + ",");
    l.add(IN + IN + IN + "description  = " + quote(op.responseDescription()) + (content ? "," : ""));
    if (content) l.add(IN + IN + IN + "content      = " + content(op.responseType()));
    l.add(IN + IN + ")");
    l.add(IN + "}");
    l.add(")");
    l.add(FORMATTER_ON);
    return String.join("\n", l);
  }

  private static String parameter(Param p) {
    String example = p.example() == null || p.example().isBlank() ? "" : ", example = " + quote(p.example().trim());
    return "@Parameter(name = " + quote(p.name()) + ", description = " + quote(p.description()) + ", required = " + p.required()
        + ", schema = @Schema(type = " + quote(p.type()) + example + "))";
  }

  private static String content(String type) {
    return "@Content(schema = @Schema(implementation = " + type + ".class))";
  }

  /** 클래스 위 @Tag 한 줄. */
  public static String tag(String name, String description) {
    return "@Tag(name = " + quote(name) + ", description = " + quote(description) + ")";
  }

  /** 자바 문자열 상수(따옴표 포함). null 은 빈 글. */
  static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : (s == null ? "" : s.trim()).toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> { }
        case '\t' -> b.append(' ');
        default -> b.append(c);
      }
    }
    return b.append('"').toString();
  }

  /** 자바 형식(제네릭 · 배열 포함 가능)의 OpenAPI 기본 형식. */
  public static String schemaType(String javaType) {
    if (javaType.endsWith("[]")) return ARRAY;
    return SCHEMA_TYPES.getOrDefault(DocComment.rawType(javaType), STRING);
  }

  /** 어노테이션 없이도 스프링이 요청 값으로 받는 단순 형식(글자 · 숫자 · 참거짓 · 날짜)이면 true. */
  public static boolean isSimple(String javaType) {
    String raw = DocComment.rawType(javaType);
    return SIMPLE_EXTRA.contains(raw) || (SCHEMA_TYPES.containsKey(raw) && !ARRAY.equals(SCHEMA_TYPES.get(raw)));
  }

  // ---------------------------------------------------------------- 미리 채울 글

  /**
   * summary: 문서 주석 첫 줄이 있으면 그것(끝 마침표 뺌, 「사용자 목록을 조회한다.」 꼴은 「사용자 목록 조회」로),
   * 없으면 메서드 이름을 「명사 동사」로.
   */
  public static String summary(String docFirstLine, String methodName, DocComment.Dict d) {
    if (docFirstLine == null || docFirstLine.isBlank()) return DocComment.title(methodName, d);
    String line = docFirstLine.trim();
    Matcher m = OBJECT_VERB.matcher(line);
    return m.matches() ? m.group(1) + " " + m.group(2) : stripPeriod(line);
  }

  /** description: 문서 주석 첫 줄이 있으면 그 글(「~한다.」는 「~합니다.」로), 없으면 메서드 이름 설명을 「~합니다.」로. */
  public static String description(String docFirstLine, String methodName, DocComment.Dict d) {
    String s = docFirstLine != null && !docFirstLine.isBlank() ? docFirstLine.trim() : DocComment.sentence(methodName, d);
    return s.endsWith(PLAIN_END) ? s.substring(0, s.length() - PLAIN_END.length()) + POLITE_END : s;
  }

  /** 응답 설명: 「조회 성공」 · 「등록 성공」. 동사를 모르면 「성공」. */
  public static String responseDescription(String methodName, DocComment.Dict d) {
    String verb = DocComment.verbWord(methodName, d);
    return verb == null ? SUCCESS : verb + " " + SUCCESS;
  }

  /** 요청 본문 설명: 「품목 검색 dto」. */
  public static String bodyDescription(DocComment.Param p, DocComment.Dict d) {
    return DocComment.paramText(p, Map.of(), Map.of(), d) + DTO_SUFFIX;
  }

  /** @Tag 이름: 클래스 문서 주석 첫 줄, 없으면 클래스 이름에서 Controller 를 뺀 명사. */
  public static String tagName(String docFirstLine, String className, DocComment.Dict d) {
    if (docFirstLine != null && !docFirstLine.isBlank()) return stripPeriod(docFirstLine.trim());
    List<String> words = new ArrayList<>(DocComment.words(className));
    words.remove("controller");
    String n = DocComment.noun(words, d);
    return n.isEmpty() ? className : n;
  }

  public static String tagDescription(String tagName) {
    return tagName + TAG_SUFFIX;
  }

  private static String stripPeriod(String s) {
    return s.endsWith(".") ? s.substring(0, s.length() - 1).trim() : s;
  }

  // ---------------------------------------------------------------- 이미 있는 것 바꾸기

  /**
   * 이미 있는 @Operation(문서의 start ~ end)을 바꿀 때 함께 지울 범위. 바로 윗줄이 「//@formatter:off」, 바로 아랫줄이
   * 「//@formatter:on」 이면(사이에 빈칸 · 줄바꿈만) 그 줄까지 넓힌다. 둘 중 하나만 있으면 넓히지 않는다.
   * 돌려주는 값은 {시작, 끝}.
   */
  public static int[] withMarkers(CharSequence text, int start, int end) {
    int s = start;
    while (s > 0 && Character.isWhitespace(text.charAt(s - 1))) s--;
    int offStart = s - FORMATTER_OFF.length();
    int e = end;
    while (e < text.length() && Character.isWhitespace(text.charAt(e))) e++;
    int onEnd = e + FORMATTER_ON.length();
    boolean off = offStart >= 0 && text.subSequence(offStart, s).toString().equals(FORMATTER_OFF);
    boolean on = onEnd <= text.length() && text.subSequence(e, onEnd).toString().equals(FORMATTER_ON);
    return off && on ? new int[]{offStart, onEnd} : new int[]{start, end};
  }
}
