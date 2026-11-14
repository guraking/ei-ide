package ei.db.mybatis;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** 「Swagger 주석 만들기」 자체 점검: java -cp out:resources:test-out ei.db.mybatis.SwaggerDocCheck */
public class SwaggerDocCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) throws Exception {
    DocComment.Dict d;
    try (Reader r = new InputStreamReader(Objects.requireNonNull(SwaggerDocCheck.class.getResourceAsStream("/doc/DocWords.properties")), StandardCharsets.UTF_8)) {
      d = DocComment.dict(r, "word.item=품목\nword.rcpt=접수");
    }

    // 본문 + 응답
    eq(SwaggerDoc.operation(new SwaggerDoc.Op("품목 목록 조회", "품목 목록을 조회합니다.", List.of(),
        new SwaggerDoc.Body("품목 검색 dto", "ItemRegRcptSearch"), "조회 성공", "ItemRegRcptList")), """
        //@formatter:off
        @Operation(
            summary     = "품목 목록 조회",
            description = "품목 목록을 조회합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "품목 검색 dto",
                content     = @Content(schema = @Schema(implementation = ItemRegRcptSearch.class))
            ),
            responses = {
                @ApiResponse(
                    responseCode = "200",
                    description  = "조회 성공",
                    content      = @Content(schema = @Schema(implementation = ItemRegRcptList.class))
                )
            }
        )
        //@formatter:on""");

    // 매개변수 둘(예시 있음 · 없음), 응답 형식 없음
    eq(SwaggerDoc.operation(new SwaggerDoc.Op("상세 \"조회\"", "a\\b", List.of(
        new SwaggerDoc.Param("itemCd", "품목코드", true, "string", " 150001 "),
        new SwaggerDoc.Param("page", "페이지", false, "integer", "")), null, "성공", null)), """
        //@formatter:off
        @Operation(
            summary     = "상세 \\"조회\\"",
            description = "a\\\\b",
            parameters = {
                    @Parameter(name = "itemCd", description = "품목코드", required = true, schema = @Schema(type = "string", example = "150001")),
                    @Parameter(name = "page", description = "페이지", required = false, schema = @Schema(type = "integer"))
            },
            responses = {
                @ApiResponse(
                    responseCode = "200",
                    description  = "성공"
                )
            }
        )
        //@formatter:on""");

    // 본문 형식을 모르면 content 줄과 앞줄 쉼표가 빠진다
    String noType = SwaggerDoc.operation(new SwaggerDoc.Op("s", "d", List.of(), new SwaggerDoc.Body("본문", null), "성공", null));
    if (!noType.contains("        description = \"본문\"\n    ),")) throw new AssertionError(noType);

    eq(SwaggerDoc.tag("품목 접수", "품목 접수 API"), "@Tag(name = \"품목 접수\", description = \"품목 접수 API\")");
    eq(SwaggerDoc.quote("줄1\n줄2\t끝"), "\"줄1\\n줄2 끝\"");
    eq(SwaggerDoc.quote(null), "\"\"");

    eq(SwaggerDoc.schemaType("String"), "string");
    eq(SwaggerDoc.schemaType("int"), "integer");
    eq(SwaggerDoc.schemaType("java.math.BigDecimal"), "number");
    eq(SwaggerDoc.schemaType("Boolean"), "boolean");
    eq(SwaggerDoc.schemaType("List<String>"), "array");
    eq(SwaggerDoc.schemaType("String[]"), "array");
    eq(SwaggerDoc.schemaType("LocalDate"), "string");

    eq(SwaggerDoc.isSimple("String"), true);
    eq(SwaggerDoc.isSimple("long"), true);
    eq(SwaggerDoc.isSimple("LocalDate"), true);
    eq(SwaggerDoc.isSimple("List<String>"), false);
    eq(SwaggerDoc.isSimple("String[]"), false);
    eq(SwaggerDoc.isSimple("Model"), false);
    eq(SwaggerDoc.isSimple("ItemSearch"), false);

    // 미리 채울 글
    eq(SwaggerDoc.summary(null, "selectItemList", d), "품목 목록 조회");
    eq(SwaggerDoc.summary("  품목 목록을 조회한다. ", "x", d), "품목 목록 조회");
    eq(SwaggerDoc.summary("index을(를) 처리한다.", "x", d), "index 처리");
    eq(SwaggerDoc.summary("품목 접수 화면.", "x", d), "품목 접수 화면");
    eq(SwaggerDoc.summary("", "countUser", d), "사용자 건수 조회");
    eq(SwaggerDoc.summary(null, "index", d), "index");
    eq(SwaggerDoc.summary(null, "init", d), "초기화");
    eq(SwaggerDoc.description(null, "selectItemList", d), "품목 목록을 조회합니다.");
    eq(SwaggerDoc.description("품목을 등록한다.", "x", d), "품목을 등록합니다.");
    eq(SwaggerDoc.description("품목 등록", "x", d), "품목 등록");
    eq(SwaggerDoc.responseDescription("selectItemList", d), "조회 성공");
    eq(SwaggerDoc.responseDescription("countUser", d), "조회 성공");
    eq(SwaggerDoc.responseDescription("saveItem", d), "저장 성공");
    eq(SwaggerDoc.responseDescription("index", d), "성공");
    eq(SwaggerDoc.bodyDescription(new DocComment.Param("search", "ItemRcptSearch"), d), "품목 접수 검색 dto");
    eq(SwaggerDoc.bodyDescription(new DocComment.Param("itemCd", "String"), d), "품목 코드 dto");
    eq(SwaggerDoc.tagName(null, "ItemRcptController", d), "품목 접수");
    eq(SwaggerDoc.tagName("품목 접수 화면.", "X", d), "품목 접수 화면");
    eq(SwaggerDoc.tagName(null, "Controller", d), "Controller");
    eq(SwaggerDoc.tagDescription("품목 접수"), "품목 접수 API");

    // 이미 있는 @Operation 범위: 위아래 표시 줄이 둘 다 있을 때만 넓힌다
    String both = "  //@formatter:off\n  @Operation(x)\n  //@formatter:on\n  @GetMapping";
    int s = both.indexOf("@Operation");
    int e = s + "@Operation(x)".length();
    int[] r = SwaggerDoc.withMarkers(both, s, e);
    eq(both.substring(r[0], r[1]), "//@formatter:off\n  @Operation(x)\n  //@formatter:on");
    String one = "  //@formatter:off\n  @Operation(x)\n  @GetMapping";
    s = one.indexOf("@Operation");
    r = SwaggerDoc.withMarkers(one, s, s + "@Operation(x)".length());
    eq(one.substring(r[0], r[1]), "@Operation(x)");
    String edge = "@Operation(x)";
    r = SwaggerDoc.withMarkers(edge, 0, edge.length());
    eq(r[0], 0);
    eq(r[1], edge.length());

    System.out.println("SwaggerDocCheck 통과");
  }
}
