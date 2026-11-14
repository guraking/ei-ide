package ei.db.mybatis;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 코드 흐름 판정 규칙 자체 점검: java -cp out:test-out ei.db.mybatis.FlowRulesCheck */
public class FlowRulesCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static boolean found(String url, String text) {
    Pattern p = FlowRules.frontPattern(url);
    return p != null && FlowRules.find(p, text) >= 0;
  }

  public static void main(String[] args) {
    eq(FlowRules.http("GetMapping", List.of()), "GET");
    eq(FlowRules.http("RequestMapping", List.of("RequestMethod.POST", "GET", "POST")), "POST · GET");
    eq(FlowRules.http("RequestMapping", List.of()), "");

    eq(FlowRules.join("", ""), "/");
    eq(FlowRules.join("/user", "list"), "/user/list");
    eq(FlowRules.join("user/", "/list/"), "/user/list");
    eq(FlowRules.join("/api//user", ""), "/api/user");

    // 그대로 · 기본 주소가 앞을 채움 · 템플릿 문자열
    eq(found("/user/list", "axios.get('/user/list')"), true);
    eq(found("/user/list", "http.post(\"user/list\", body)"), true);
    eq(found("/user/list", "api.get(`${BASE}/user/list?page=1`)"), true);
    eq(found("/user/list", "fetch('/api/user/list')"), true);
    // 이름이 더 이어지면 다른 URL
    eq(found("/user/list", "axios.get('/user/listAll')"), false);
    eq(found("/user/list", "axios.get('/user/list-all')"), false);
    eq(found("/user/list", "axios.get('/user/list.json')"), false);
    // 더 긴 URL 은 다른 API
    eq(found("/api/user", "axios.get('/api/user/list')"), false);
    eq(found("/api/user", "axios.get('/api/user?id=1')"), true);
    // 와일드카드 · 정규식 변수
    eq(found("/files/**", "get('/files/a/b.png')"), true);
    eq(found("/user/*/detail", "get(`/user/${id}/detail`)"), true);
    eq(found("/item/{id:[0-9]{3}}", "get('/item/' + id)"), true);
    eq(found("/item/{id:[0-9]{3}}/x", "get('/item/123/x')"), true);
    eq(FlowRules.literalTokens("/files/**"), List.of("files"));
    // 따옴표 · 빗금 앞이 아니면(다른 낱말의 끝) 아님
    eq(found("/user/list", "const xuser = 'abc'; 'user/list'.length"), true);
    eq(found("/user/list", "superuser/list"), false);
    // 변수 마디
    eq(found("/user/{id}/detail", "axios.get(`/user/${id}/detail`)"), true);
    eq(found("/user/{id}/detail", "axios.get('/user/' + id + '/detail')"), false);
    eq(found("/user/{id}", "axios.get('/user/' + id)"), true);
    eq(found("/user/{id}", "axios.get('/users/' + id)"), false);
    eq(found("/file/{name}.json", "get(`/file/${n}.json`)"), true);
    // 글자 마디가 없으면 찾지 않는다
    eq(FlowRules.frontPattern("/"), null);
    eq(FlowRules.frontPattern("/{id}"), null);
    // 앞의 설정값 · 판 번호 자리는 빼고 찾는다
    eq(found("/${api.prefix}/user/list", "axios.get('/user/list')"), true);
    eq(found("/{version}/user/list", "get(`/v1/user/list`)"), true);
    eq(FlowRules.frontPattern("/${api.prefix}/{id}"), null);
    // 정규식 글자는 그대로 본다
    eq(found("/a.b/c", "'/a.b/c'"), true);
    eq(found("/a.b/c", "'/aXb/c'"), false);

    // 낱말 조각 거르기: 정규식이 맞는 글이면 조각도 모두 들어 있다
    String[][] cases = {
        {"/user/list", "axios.get('/api/user/list')"}, {"/user/{id}/detail", "get(`/user/${id}/detail`)"},
        {"/file/{name}.json", "get(`/file/${n}.json`)"}, {"/a.b/c", "'/a.b/c'"}, {"/user/list.do", "\"/user/list.do?x=1\""},
        {"/${api.prefix}/user/list", "axios.get('/user/list')"}};
    for (String[] c : cases) {
      eq(found(c[0], c[1]), true);
      eq(FlowRules.tokens(c[1]).containsAll(FlowRules.literalTokens(c[0])), true);
    }
    eq(FlowRules.literalTokens("/user/{id}/detail"), List.of("user", "detail"));
    eq(FlowRules.literalTokens("/file/{name}.json"), List.of("file"));
    eq(FlowRules.literalTokens("/a~b/c"), List.of("c"));

    // 태그 빼기: 문장 자신의 id 속성 · if test 는 SQL 낱말이 아니다
    String xml = "<select id=\"ID\" resultType=\"X\"><!-- ID --><![CDATA[SELECT A FROM T WHERE a < b]]><if test=\"id != null\">AND B = 1</if></select>";
    eq(FlowRules.hasWord(FlowRules.withoutTags(xml), "id"), false);
    eq(FlowRules.hasWord(FlowRules.withoutTags(xml), "b"), true);
    eq(FlowRules.withoutTags(xml).contains("a < b"), true);

    // 스키마를 준 표 찾기
    eq(FlowRules.hasTable("SELECT * FROM APP.TB_USER", "APP", "TB_USER"), true);
    eq(FlowRules.hasTable("SELECT * FROM OTHER.TB_USER", "APP", "TB_USER"), false);
    eq(FlowRules.hasTable("SELECT * FROM TB_USER u", "APP", "TB_USER"), true);
    eq(FlowRules.hasTable("SELECT * FROM OTHER.TB_USER", null, "TB_USER"), true);
    eq(FlowRules.hasTable("SELECT * FROM TB_USER_LOG", null, "TB_USER"), false);
    eq(FlowRules.hasTable("SELECT * FROM OTHER.TB_USER, TB_USER", "APP", "TB_USER"), true);

    eq(FlowRules.skippedFront("src/views/User.vue"), false);
    eq(FlowRules.skippedFront("node_modules/a/index.js"), true);
    eq(FlowRules.skippedFront("web/dist/app.js"), true);
    eq(FlowRules.skippedFront("bin/static/js/app.js"), true);
    eq(FlowRules.skippedFront("src/dist.js"), false);

    eq(FlowRules.isDtoName("UserDto"), true);
    eq(FlowRules.isDtoName("UserVO"), true);
    eq(FlowRules.isDtoName("Dto"), false);
    eq(FlowRules.isDtoName("UserService"), false);
    eq(FlowRules.isDtoName("Video"), false);

    eq(FlowRules.snake("userName"), "user_name");
    eq(FlowRules.snake("id"), "id");
    eq(FlowRules.snake("regDt2"), "reg_dt2");
    eq(FlowRules.snake("userID"), "user_id");
    eq(FlowRules.snake("XMLParser"), "xml_parser");

    // 매퍼 XML 의 필드 쓰임: 태그 이름 · SQL 낱말 · 다른 속성은 세지 않는다
    eq(FlowRules.usesField("<id property=\"userId\" column=\"USER_ID\"/>", "id"), false);
    eq(FlowRules.usesField("<id property=\"id\" column=\"ID\"/>", "id"), true);
    eq(FlowRules.usesField("SELECT * FROM T ORDER BY A <bind name=\"x\" value=\"1\"/>", "order"), false);
    eq(FlowRules.usesField("<bind name=\"x\"/>", "name"), false);
    eq(FlowRules.usesField("WHERE A = #{ dto.userName , jdbcType=VARCHAR}", "userName"), true);
    eq(FlowRules.usesField("WHERE A = #{userNameX}", "userName"), false);
    eq(FlowRules.usesField("<if test=\"userName != null and userName != ''\">", "userName"), true);
    eq(FlowRules.usesField("<insert id=\"ins\" useGeneratedKeys=\"true\" keyProperty=\"seq\">", "seq"), true);
    eq(FlowRules.usesField("<insert id=\"seq\">", "seq"), false);

    eq(FlowRules.hasWord("SELECT USER_NAME FROM T", "user_name"), true);
    eq(FlowRules.hasWord("SELECT USER_NAME_X FROM T", "user_name"), false);
    eq(FlowRules.hasWord("#{userName}", "userName"), true);
    eq(FlowRules.hasWord("a.userName2", "userName"), false);
    eq(FlowRules.hasWord("abc", ""), false);
    eq(FlowRules.hasWord("x tb_user", "TB_USER"), true);

    eq(Arrays.asList(FlowRules.splitTable(" tb_user ")), Arrays.asList(null, "tb_user"));
    eq(Arrays.asList(FlowRules.splitTable("APP.\"TB_USER\"")), Arrays.asList("APP", "TB_USER"));

    System.out.println("FlowRulesCheck 통과");
  }
}
