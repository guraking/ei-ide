package ei.db.mybatis;

import java.util.List;
import java.util.Objects;

/** 「바꾼 것 다시 시험」 판정 규칙 자체 점검: java -cp out:test-out ei.db.mybatis.RetestRulesCheck */
public class RetestRulesCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    String before = "<select id=\"a\">\n  SELECT A\n    FROM T <!-- 메모 -->\n</select>";
    String now = "<select id=\"a\">  SELECT A FROM T\n</select>";
    eq(RetestRules.statementText(before, List.of(new int[]{0, before.length()})),
       RetestRules.statementText(now, List.of(new int[]{0, now.length()})));
    String changed = "<select id=\"a\">SELECT A, B FROM T</select>";
    eq(RetestRules.statementText(changed, List.of(new int[]{0, changed.length()})).equals(
       RetestRules.statementText(now, List.of(new int[]{0, now.length()}))), false);
    // include 조각 범위는 이어 붙이고, 글 밖 범위는 건너뛴다.
    String doc = "<a>X</a><sql>Y</sql>";
    eq(RetestRules.statementText(doc, List.of(new int[]{0, 8}, new int[]{8, doc.length()}, new int[]{5, 99})), "<a>X</a> <sql>Y</sql>");

    eq(RetestRules.methodKey("p.A", "get", List.of("String", "int")), "p.A#get(String,int)");
    eq(RetestRules.methodKey(null, "run", List.of()), "#run()");

    eq(RetestRules.apiVerdict("끊김", 200, 0, false), RetestRules.ApiVerdict.FAILED);
    eq(RetestRules.apiVerdict(null, -1, 200, false), RetestRules.ApiVerdict.NO_BASE);
    eq(RetestRules.apiVerdict(null, 200, 500, false), RetestRules.ApiVerdict.STATUS);
    eq(RetestRules.apiVerdict(null, 200, 200, false), RetestRules.ApiVerdict.BODY);
    eq(RetestRules.apiVerdict(null, 200, 200, true), RetestRules.ApiVerdict.SAME);

    // URL 이 둘인 메서드에서 /a 만 저장했으면 /b 는 저장한 요청 없음.
    String keyA = "GET p.C#m /a";
    eq(RetestRules.keysByUrl(List.of("/a", "/b"), List.of(keyA)), java.util.Map.of("/a", List.of(keyA), "/b", List.of()));
    // URL 모양이 달라 하나도 맞지 않으면 모든 URL 에 모든 key.
    eq(RetestRules.keysByUrl(List.of("/x/{id}"), List.of(keyA)), java.util.Map.of("/x/{id}", List.of(keyA)));
    eq(RetestRules.keysByUrl(List.of("/a"), List.of()), java.util.Map.of("/a", List.of()));

    // 다른 파일 조각을 거쳐 include 해도 바뀐 조각을 쓰는 것으로 본다. 같은 파일 조각 안의 include 도 따라간다.
    MapperDoc common = MapperDoc.parse("<mapper namespace=\"p.Common\"><sql id=\"cols\">A, B</sql></mapper>");
    MapperDoc mid = MapperDoc.parse("<mapper namespace=\"p.Mid\"><sql id=\"wrap\"><include refid=\"p.Common.cols\"/></sql></mapper>");
    MapperDoc user = MapperDoc.parse("<mapper namespace=\"p.User\"><sql id=\"own\"><include refid=\"p.Mid.wrap\"/></sql>"
        + "<select id=\"list\">SELECT <include refid=\"own\"/> FROM T</select><select id=\"other\">SELECT 1</select></mapper>");
    java.util.Set<String> reach = RetestRules.reach(List.of(common, mid, user), java.util.Set.of("p.Common.cols"));
    eq(reach.contains("p.Mid.wrap") && reach.contains("p.User.own"), true);
    eq(RetestRules.includedFragment(user, user.statement("list"), reach), "p.User.own");
    eq(RetestRules.includedFragment(user, user.statement("other"), reach), null);
    eq(RetestRules.reach(List.of(common, mid, user), java.util.Set.of("p.Other.x")), java.util.Set.of("p.Other.x"));
    // 자기 자신을 부르는 조각에서 멈춘다.
    MapperDoc loop = MapperDoc.parse("<mapper namespace=\"p.L\"><sql id=\"a\"><include refid=\"a\"/></sql>"
        + "<select id=\"s\"><include refid=\"a\"/></select></mapper>");
    eq(RetestRules.includedFragment(loop, loop.statement("s"), java.util.Set.of("p.X.y")), null);

    System.out.println("RetestRulesCheck 통과");
  }
}
