package local.bootservers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import local.bootservers.OpenApiSpec.Schema;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ApiText 자체 점검. 실패하면 예외로 끝난다. */
public final class ApiTextCheck {

    private static final String TODAY = "2026-10-27";

    public static void main(String[] args) {
        samples();
        body();
        urls();
        auth();
        pretty();
        decode();
        System.out.println("ApiTextCheck 통과");
    }

    private static void samples() {
        Schema text = Schema.of(OpenApiSpec.TYPE_STRING);
        eq("N", ApiText.sample("tempYn", text, "N", TODAY), "defaultValue 우선");
        eq("Y", ApiText.sample("useYn", text, null, TODAY), "Yn");
        eq("1", ApiText.sample("cadId", text, null, TODAY), "글 id");
        eq("1", ApiText.sample("partNo", text, null, TODAY), "글 no");
        eq("test@test.com", ApiText.sample("userEmail", text, null, TODAY), "email");
        eq("테스트", ApiText.sample("name", text, null, TODAY), "글");
        eq("1", ApiText.sample("pageIndex", Schema.of(OpenApiSpec.TYPE_INTEGER), null, TODAY), "숫자");
        eq("true", ApiText.sample("flag", Schema.of(OpenApiSpec.TYPE_BOOLEAN), null, TODAY), "참거짓");
        eq(TODAY, ApiText.sample("regDate", Schema.of(OpenApiSpec.TYPE_STRING, "date"), null, TODAY), "날짜");
        eq(TODAY + "T00:00:00", ApiText.sample("regDt", Schema.of(OpenApiSpec.TYPE_STRING, "date-time"), null, TODAY), "시각");
        eq("1", ApiText.sample("ids", Schema.array(Schema.of(OpenApiSpec.TYPE_INTEGER)), null, TODAY), "배열은 항목 하나");
        eq("테스트", ApiText.sample("x", null, null, TODAY), "모양 없음");
        eq("RED", ApiText.sample("color", Schema.of(OpenApiSpec.TYPE_STRING, ApiText.FORMAT_ENUM_PREFIX + "RED,BLUE"), null, TODAY), "열거형 첫 상수");
        eq("00:00:00", ApiText.sample("startTm", Schema.of(OpenApiSpec.TYPE_STRING, ApiText.FORMAT_TIME), null, TODAY), "LocalTime");
        eq("2026-10", ApiText.sample("ym", Schema.of(OpenApiSpec.TYPE_STRING, ApiText.FORMAT_YEAR_MONTH), null, TODAY), "YearMonth");
        eq("2026", ApiText.sample("yr", Schema.of(OpenApiSpec.TYPE_STRING, ApiText.FORMAT_YEAR), null, TODAY), "Year");
        eq(TODAY + "T00:00:00Z", ApiText.sample("at", Schema.of(OpenApiSpec.TYPE_STRING, ApiText.FORMAT_DATE_TIME_OFFSET), null, TODAY), "Instant");
        java.time.Instant.parse(ApiText.sample("at", Schema.of(OpenApiSpec.TYPE_STRING, ApiText.FORMAT_DATE_TIME_OFFSET), null, TODAY));
        java.time.ZonedDateTime.parse(TODAY + "T00:00:00Z");
    }

    private static void body() {
        Map<String, Map<String, Schema>> components = new LinkedHashMap<>();
        Map<String, Schema> cad = new LinkedHashMap<>();
        cad.put("cadId", Schema.of(OpenApiSpec.TYPE_INTEGER, "int64"));
        cad.put("cadNm", Schema.of(OpenApiSpec.TYPE_STRING));
        cad.put("parts", Schema.array(Schema.ref("Part")));
        cad.put("parent", Schema.ref("CadDto"));
        cad.put("extra", Schema.object());
        components.put("CadDto", cad);
        components.put("Part", Map.of("qty", Schema.of(OpenApiSpec.TYPE_INTEGER)));
        JsonObject json = ApiText.sampleJson("dto", Schema.ref("CadDto"), components, TODAY).getAsJsonObject();
        eq(1, json.get("cadId").getAsInt(), "숫자 필드");
        eq("테스트", json.get("cadNm").getAsString(), "글 필드");
        eq(1, json.getAsJsonArray("parts").get(0).getAsJsonObject().get("qty").getAsInt(), "목록 안 객체");
        eq(0, json.getAsJsonObject("parent").size(), "자기 참조는 빈 객체");
        eq(0, json.getAsJsonObject("extra").size(), "자유 객체");
        JsonElement missing = ApiText.sampleJson("x", Schema.ref("Nope"), components, TODAY);
        eq(0, missing.getAsJsonObject().size(), "모르는 참조");
        JsonElement list = ApiText.sampleJson("x", Schema.array(Schema.of(OpenApiSpec.TYPE_STRING)), components, TODAY);
        eq("[\n  \"테스트\"\n]", ApiText.toJson(list), "목록 본문");
    }

    private static void urls() {
        eq("/api/cad/1/item/a%20b", ApiText.fillPath("/api/cad/{cadId}/item/{no:\\d+}", Map.of("no", "a b")), "경로 변수");
        eq("/list", ApiText.fillPath("/list", Map.of()), "변수 없음");
        eq("/x/7/${api.v}", ApiText.fillPath("/x/{id:\\d{3}}/${api.v}", Map.of("id", "7")), "정규식 안 { } · 자리표시");
        eq("/x/1/y", ApiText.fillPath("/x/{id:[a-z]{2,}}/y", Map.of()), "정규식 반복 횟수");
        eq("/x/{open", ApiText.fillPath("/x/{open", Map.of()), "짝 없는 {");
        eq("http://localhost:9090/api/x", ApiText.join("http://localhost:9090", "/api/x"), "잇기");
        eq("http://localhost:9090/", ApiText.join("http://localhost:9090", ""), "빈 경로");
        eq(21, ApiText.hostEnd("http://localhost:8090/basic/list?a=1"), "서버 끝");
        eq(32, ApiText.queryStart("http://localhost:8090/basic/list?a=1"), "쿼리 시작");
        eq(9, ApiText.hostEnd("https://h?"), "경로 없음");
        eq(9, ApiText.queryStart("https://h?"), "경로 없이 서버 뒤 ? 는 쿼리 시작");
        eq(9, ApiText.hostEnd("https://h?x=1"), "경로 없는 쿼리는 서버 부분이 아님");
        eq(9, ApiText.hostEnd("https://h#a"), "# 앞에서 서버 끝");
        eq(9, ApiText.hostEnd("https://h"), "주소 전체가 서버");
        eq(0, ApiText.hostEnd("/basic/list"), "scheme 없음");
        eq(11, ApiText.queryStart("/basic/list"), "쿼리 없음");
        eq("http://h/list?a=1&b=%ED%85%8C+%26", ApiText.withQuery("http://h/list",
                List.of(Map.entry("a", "1"), Map.entry(" ", "x"), Map.entry("b", "테 &"))), "쿼리");
        eq("http://h/l?x=1&a=2", ApiText.withQuery("http://h/l?x=1", List.of(Map.entry("a", "2"))), "이미 ? 있음");
        eq("GET /basic/list?a=1 HTTP/1.1\nHost: localhost:8090\nAccept: */*\n",
                ApiText.requestText("GET", "http://localhost:8090/basic/list", List.of(Map.entry("a", "1")),
                        List.of(Map.entry("Accept", "*/*")), ""), "보낼 요청 글");
        eq("POST / HTTP/1.1\nHost: h\n\n{}",
                ApiText.requestText("POST", "http://h", List.of(), List.of(), "{}"), "경로 없음 · 본문");
        eq("GET /x HTTP/1.1\n", ApiText.requestText("GET", "/x", List.of(), List.of(), null), "scheme 없음");
    }

    private static void auth() {
        eq(null, ApiText.authHeader(ApiText.AUTH_NONE, "u", "t"), "없음");
        eq(null, ApiText.authHeader(ApiText.AUTH_BEARER, null, " "), "빈 토큰");
        eq(Map.entry("Authorization", "Bearer abc"), ApiText.authHeader(ApiText.AUTH_BEARER, null, "abc"), "Bearer");
        eq(Map.entry("Authorization", "bearer abc"), ApiText.authHeader(ApiText.AUTH_BEARER, null, "bearer abc"), "Bearer 이미 붙음");
        eq(Map.entry("Authorization", "Basic dTpw"), ApiText.authHeader(ApiText.AUTH_BASIC, "u", "p"), "Basic");
        eq(null, ApiText.authHeader(ApiText.AUTH_BASIC, "", ""), "빈 Basic");
        eq(Map.entry("Cookie", "JSESSIONID=1"), ApiText.authHeader(ApiText.AUTH_COOKIE, null, "JSESSIONID=1"), "쿠키");
    }

    private static void pretty() {
        eq("{\n  \"a\": 1\n}", ApiText.prettyJson("{\"a\":1}"), "줄맞춤");
        eq("<html>", ApiText.prettyJson("<html>"), "HTML 그대로");
        eq("{broken", ApiText.prettyJson("{broken"), "깨진 JSON 그대로");
        eq("list", ApiText.prettyJson("list"), "글 그대로");
    }

    private static void decode() {
        String korean = "<p>한글 확인</p>";
        byte[] euc = korean.getBytes(Charset.forName("EUC-KR"));
        byte[] utf8 = korean.getBytes(StandardCharsets.UTF_8);
        eq(korean, ApiText.decode(euc, "text/html;charset=EUC-KR", false), "헤더 charset");
        eq(korean, ApiText.decode(euc, "text/html", false), "charset 없음 · UTF-8 아님 → MS949");
        eq(korean, ApiText.decode(utf8, "application/json", false), "charset 없음 · UTF-8");
        eq(korean, ApiText.decode(utf8, null, false), "Content-Type 없음");
        String meta = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=euc-kr\"></head>한글</html>";
        eq(meta, ApiText.decode(meta.getBytes(Charset.forName("EUC-KR")), "text/html", false), "meta charset");
        eq("<meta charset=\"utf-8\">é", ApiText.decode("<meta charset=\"utf-8\">é".getBytes(StandardCharsets.UTF_8), "", false), "meta utf-8");
        eq("a가", ApiText.decode(Arrays.copyOf("a가나".getBytes(StandardCharsets.UTF_8), 5), "", true), "잘린 UTF-8 끝 글자");
        eq(korean, ApiText.decode(euc, "text/html; charset=nope-x", false), "모르는 charset 은 건너뜀");
    }

    private static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": 기대 <" + expected + "> 실제 <" + actual + ">");
        }
    }
}
