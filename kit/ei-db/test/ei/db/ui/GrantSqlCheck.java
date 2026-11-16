package ei.db.ui;

import ei.db.DbKind;
import ei.db.SqlGuard;

import java.util.List;

/** GRANT · REVOKE · 시노님 문 만들기 · 받는 사용자 읽기 자체 점검: java -cp out:test-out ei.db.ui.GrantSqlCheck */
public class GrantSqlCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static GrantSql.Request req(DbKind kind, boolean revoke, List<String> objects, List<String> privileges, List<String> grantees,
                              boolean grantOption, GrantSql.Synonym synonym, boolean usage) {
    return new GrantSql.Request(kind, revoke, kind == DbKind.ORACLE ? "OWNER" : "app", objects, privileges, grantees, grantOption, synonym, usage);
  }

  public static void main(String[] args) {
    // 받는 사용자: 쉼표 · 공백 구분, 맨 이름은 대문자, 같은 것은 한 번, 따옴표 이름은 그대로, 이상한 조각은 따로.
    GrantSql.Grantees g = GrantSql.readGrantees(" app_user, Batch\nAPP_USER , \"Mixed\" public ");
    eq(g.names(), List.of("APP_USER", "BATCH", "\"Mixed\"", "PUBLIC"));
    eq(g.invalid(), List.of());
    g = GrantSql.readGrantees("a;DROP TABLE x, b'c, \"x y\", (r), 1abc, \"\"");
    eq(g.names(), List.of("TABLE", "X"));
    eq(g.invalid(), List.of("a;DROP", "b'c", "\"x", "y\"", "(r)", "1abc", "\"\""));
    eq(GrantSql.readGrantees(null).names(), List.of());
    eq(GrantSql.readGrantees("   ").names(), List.of());

    // Oracle 주기: 표마다 GRANT 하나(받는 사용자 여럿), 다시 줄 수 있게, 받는 사용자 시노님은 PUBLIC 빼고.
    List<String> s = GrantSql.statements(req(DbKind.ORACLE, false, List.of("TB_PART", "order"), List.of("SELECT", "UPDATE"),
        List.of("APP", "PUBLIC"), true, GrantSql.Synonym.GRANTEE, true));
    eq(s, List.of(
        "GRANT SELECT, UPDATE ON OWNER.TB_PART TO APP, PUBLIC WITH GRANT OPTION",
        "GRANT SELECT, UPDATE ON OWNER.\"order\" TO APP, PUBLIC WITH GRANT OPTION",
        "CREATE OR REPLACE SYNONYM APP.TB_PART FOR OWNER.TB_PART",
        "CREATE OR REPLACE SYNONYM APP.\"order\" FOR OWNER.\"order\""));
    // Oracle 빼기: REVOKE 먼저, 다시 줄 수 있게는 무시, 시노님 DROP.
    s = GrantSql.statements(req(DbKind.ORACLE, true, List.of("TB_PART"), List.of("SELECT"), List.of("APP"), true, GrantSql.Synonym.GRANTEE, false));
    eq(s, List.of("REVOKE SELECT ON OWNER.TB_PART FROM APP", "DROP SYNONYM APP.TB_PART"));
    // 공용 시노님: 객체마다 하나.
    s = GrantSql.statements(req(DbKind.ORACLE, false, List.of("PKG_PART"), List.of("EXECUTE"), List.of("APP", "BATCH"), false, GrantSql.Synonym.PUBLIC_SYNONYM, false));
    eq(s, List.of("GRANT EXECUTE ON OWNER.PKG_PART TO APP, BATCH", "CREATE OR REPLACE PUBLIC SYNONYM PKG_PART FOR OWNER.PKG_PART"));
    s = GrantSql.statements(req(DbKind.ORACLE, true, List.of("PKG_PART"), List.of(), List.of("APP"), false, GrantSql.Synonym.PUBLIC_SYNONYM, false));
    eq(s, List.of("DROP PUBLIC SYNONYM PKG_PART"));
    // 시노님만(권한 없음)도 된다.
    s = GrantSql.statements(req(DbKind.ORACLE, false, List.of("TB_PART"), List.of(), List.of("APP"), false, GrantSql.Synonym.GRANTEE, false));
    eq(s, List.of("CREATE OR REPLACE SYNONYM APP.TB_PART FOR OWNER.TB_PART"));

    // PostgreSQL: 스키마 사용 권한 먼저, 시노님은 무시, 메타 소문자 이름은 대문자 표기.
    s = GrantSql.statements(req(DbKind.POSTGRES, false, List.of("tb_part", "OrderItem"), List.of("SELECT"), List.of("APP"), false, GrantSql.Synonym.GRANTEE, true));
    eq(s, List.of("GRANT USAGE ON SCHEMA APP TO APP", "GRANT SELECT ON APP.TB_PART TO APP", "GRANT SELECT ON APP.\"OrderItem\" TO APP"));
    // PostgreSQL 빼기는 스키마 사용 권한을 건드리지 않는다.
    s = GrantSql.statements(req(DbKind.POSTGRES, true, List.of("tb_part"), List.of("SELECT", "DELETE"), List.of("APP"), false, GrantSql.Synonym.NONE, true));
    eq(s, List.of("REVOKE SELECT, DELETE ON APP.TB_PART FROM APP"));
    // 권한이 없으면 스키마 사용 권한도 만들지 않는다.
    eq(GrantSql.statements(req(DbKind.POSTGRES, false, List.of("t"), List.of(), List.of("APP"), false, GrantSql.Synonym.GRANTEE, true)), List.of());

    // 빈 것: 받는 사용자 · 객체가 없으면 문장 없음.
    eq(GrantSql.statements(req(DbKind.ORACLE, false, List.of("T"), List.of("SELECT"), List.of(), false, GrantSql.Synonym.GRANTEE, false)), List.of());
    eq(GrantSql.statements(req(DbKind.ORACLE, false, List.of(), List.of("SELECT"), List.of("A"), false, GrantSql.Synonym.GRANTEE, false)), List.of());

    // 목록에서 고른 이름: PUBLIC 은 맨 낱말, 접히지 않는 이름은 따옴표.
    eq(GrantSql.granteeText(DbKind.ORACLE, "APP_USER"), "APP_USER");
    eq(GrantSql.granteeText(DbKind.POSTGRES, "app_user"), "APP_USER");
    eq(GrantSql.granteeText(DbKind.POSTGRES, "AppUser"), "\"AppUser\"");
    eq(GrantSql.granteeText(DbKind.POSTGRES, "public"), "PUBLIC");

    // 실행 확인: 모두 변경 · DDL(Oracle 암묵 커밋 확인), DROP SYNONYM 은 위험 문장으로 확인을 받는다.
    for (String sql : List.of("GRANT SELECT ON A.B TO C", "REVOKE SELECT ON A.B FROM C", "CREATE OR REPLACE SYNONYM C.B FOR A.B", "DROP SYNONYM C.B")) {
      eq(SqlGuard.isWrite(sql), true);
      eq(SqlGuard.isDdl(sql), true);
    }
    eq(SqlGuard.danger("DROP SYNONYM C.B") != null, true);
    eq(SqlGuard.danger("DROP PUBLIC SYNONYM B") != null, true);

    eq(GrantSql.script(List.of("A", "B")), "A;\nB;\n");
    eq(GrantSql.script(List.of()), "");
    System.out.println("GrantSqlCheck OK");
  }
}
