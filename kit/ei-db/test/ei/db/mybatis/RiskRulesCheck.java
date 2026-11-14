package ei.db.mybatis;

import java.util.List;
import java.util.Objects;

/** 위험 구간 검사 글 판정 자체 점검: java -cp out:test-out ei.db.mybatis.RiskRulesCheck */
public class RiskRulesCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  static void ok(boolean v, String what) { if (!v) throw new AssertionError(what); }

  public static void main(String[] args) {
    // 값 자리
    eq(RiskRules.concatSpot("select * from TB_USER where USER_ID = '"), RiskRules.Spot.VALUE);
    eq(RiskRules.concatSpot("select * from TB_USER where USER_ID = "), RiskRules.Spot.VALUE);
    eq(RiskRules.concatSpot(" AND a.USER_NM LIKE '%"), RiskRules.Spot.VALUE);
    eq(RiskRules.concatSpot(" and DEPT_CD in ('"), RiskRules.Spot.VALUE);
    eq(RiskRules.concatSpot(" and DEPT_CD in ('x', '"), RiskRules.Spot.VALUE);
    eq(RiskRules.concatSpot("insert into T (A, B) values ('"), RiskRules.Spot.VALUE);
    eq(RiskRules.concatSpot("insert into T (A, B) values ('x', '"), RiskRules.Spot.VALUE);
    eq(RiskRules.concatSpot("update T set A = 'x', B = '"), RiskRules.Spot.VALUE);
    eq(RiskRules.concatSpot("UPDATE T SET A = "), RiskRules.Spot.VALUE);
    // 정렬 자리
    eq(RiskRules.concatSpot("select * from T order by "), RiskRules.Spot.ORDER);
    eq(RiskRules.concatSpot("select * from T ORDER BY x "), RiskRules.Spot.ORDER);
    eq(RiskRules.concatSpot("select * from T order by A desc, "), RiskRules.Spot.ORDER);
    // 위험 아님
    eq(RiskRules.concatSpot("User{id="), null);
    eq(RiskRules.concatSpot("User{id=x, name="), null);
    eq(RiskRules.concatSpot("select * from "), null);
    eq(RiskRules.concatSpot("select * from T where "), null);
    eq(RiskRules.concatSpot("사용자 "), null);
    eq(RiskRules.concatSpot("userId = "), null);
    eq(RiskRules.concatSpot("where A = 1"), null);

    // ${} 찾기
    String sql = "select * from ${schema}.TB_${suffix} where A = ${a} order by ${col} ${dir}";
    List<RiskRules.Dollar> ds = RiskRules.dollars(sql, 0, sql.length());
    eq(ds.size(), 5);
    eq(ds.get(0).name(), "schema");
    eq(sql.substring(ds.get(2).start(), ds.get(2).end()), "${a}");
    eq(RiskRules.dollars("a ${b", 0, 5).get(0).name(), "b");
    eq(RiskRules.dollars("a $ {b} $", 0, 9).size(), 0);
    eq(RiskRules.dollarSpot("select * from "), RiskRules.Spot.NAME);
    eq(RiskRules.dollarSpot("select * from x.TB_"), RiskRules.Spot.NAME);
    eq(RiskRules.dollarSpot("select * from T where A = "), RiskRules.Spot.VALUE);
    eq(RiskRules.dollarSpot("select * from T <where> A = "), RiskRules.Spot.VALUE);
    eq(RiskRules.dollarSpot("select * from T where A in ("), RiskRules.Spot.VALUE);
    eq(RiskRules.dollarSpot("select * from T\n ORDER BY "), RiskRules.Spot.ORDER);
    eq(RiskRules.dollarSpot("select * from T order by x "), RiskRules.Spot.ORDER);
    eq(RiskRules.dollarSpot("select * from T <if test=\"a\"> order by </if>"), RiskRules.Spot.ORDER);

    // 값만 보는 민감정보
    eq(RiskRules.secretLiteral("jdbc:oracle:thin:@10.1.2.3:1521:ORCL"), RiskRules.Secret.JDBC);
    eq(RiskRules.secretLiteral("jdbc:postgresql://db.local:5432/app"), RiskRules.Secret.JDBC);
    eq(RiskRules.secretLiteral("jdbc:oracle:thin:@"), null);
    eq(RiskRules.secretLiteral("jdbc:postgresql://"), null);
    eq(RiskRules.secretLiteral("jdbc:h2:mem:test"), null);
    eq(RiskRules.secretLiteral("https://user:p4ss@git.example.com/repo.git"), RiskRules.Secret.URL_LOGIN);
    eq(RiskRules.secretLiteral("https://git.example.com/repo.git"), null);
    eq(RiskRules.secretLiteral("-----BEGIN RSA PRIVATE KEY-----\nabc"), RiskRules.Secret.PRIVATE_KEY);
    eq(RiskRules.secretLiteral("AKIAABCDEFGHIJKLMNOP"), RiskRules.Secret.CLOUD_KEY);
    eq(RiskRules.secretLiteral("hello"), null);

    // 이름
    for (String n : List.of("password", "PASSWORD", "dbPassword", "DB_PWD", "userPw", "apiKey", "API_KEY", "secretKey", "clientSecret",
        "accessToken", "jwtSecret", "privateKey", "Password", "dbPass")) {
      ok(RiskRules.secretName(n), "비밀 이름: " + n);
    }
    for (String n : List.of("passwordParam", "PASSWORD_KEY", "passwordField", "tokenHeader", "TOKEN_PREFIX", "passwordPolicy",
        "pwdChangeDate", "userName", "url", "tokenizer", "bypass", "passYn", "PASSWORD_PATTERN", "tokenKey", "passwordRegExp", "PWD_REGEXP", "passport", "passengerName", "pwdErrCnt", "")) {
      ok(!RiskRules.secretName(n), "비밀 이름 아님: " + n);
    }
    // 값
    ok(RiskRules.secretValue("password", "tiger123"), "값 tiger123");
    ok(RiskRules.secretValue("apiKey", "a9F3kL0qZ7x"), "값 키");
    ok(RiskRules.secretValue("dbPwd", "1234"), "값 1234");
    for (String v : List.of("", "abc", "db.password", "spring.datasource.password", "DB_PASSWORD", "password", "PASSWORD",
        "userPassword", "user_password", "/password/reset", "login/password", "${db.password}", "#{pw}", "****",
        "비밀번호를 입력하세요", "enter password", " tiger1", "%s")) {
      ok(!RiskRules.secretValue("password", v), "값 아님: " + v);
    }
    eq(RiskRules.lastSegment("spring.datasource.password"), "password");
    eq(RiskRules.lastSegment("password"), "password");
    System.out.println("RiskRulesCheck 통과");
  }
}
