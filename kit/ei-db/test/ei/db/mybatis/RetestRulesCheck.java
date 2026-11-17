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

    System.out.println("RetestRulesCheck 통과");
  }
}
