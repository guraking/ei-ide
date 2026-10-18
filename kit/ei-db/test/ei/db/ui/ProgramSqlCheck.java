package ei.db.ui;

import ei.db.DbKind;
import ei.db.SqlGuard;

import java.util.List;

/** 프로그램 소스 · 세션 · 잠금 쿼리 자체 점검: java -cp out:test-out ei.db.ui.ProgramSqlCheck */
public class ProgramSqlCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    // 패키지 본문이 INVALID 면 패키지 줄도 INVALID, 본문 줄은 따로 생기지 않는다.
    List<ProgramSql.Item> items = ProgramSql.merge(List.of(
        new String[] {"PKG_ORDER", "PACKAGE", "VALID"},
        new String[] {"PKG_ORDER", "PACKAGE BODY", "INVALID"},
        new String[] {"PKG_OK", "PACKAGE", "VALID"},
        new String[] {"PKG_OK", "PACKAGE BODY", "VALID"},
        new String[] {"P_SAVE", "PROCEDURE", "VALID"}));
    eq(items.size(), 3);
    eq(items.get(0), new ProgramSql.Item("PKG_ORDER", "PACKAGE", "INVALID"));
    eq(items.get(1).invalid(), false);
    eq(items.get(2).type(), "PROCEDURE");
    // 명세보다 본문이 먼저 와도 INVALID 를 잃지 않는다.
    eq(ProgramSql.merge(List.<String[]>of(new String[] {"A", "PACKAGE BODY", "INVALID"}, new String[] {"A", "PACKAGE", "VALID"})).get(0).invalid(), true);

    eq(ProgramSql.sourceTypes("PACKAGE"), List.of("PACKAGE", "PACKAGE BODY"));
    eq(ProgramSql.sourceTypes("FUNCTION"), List.of("FUNCTION", "FUNCTION"));

    // 소유자를 넣고 형식마다 / 로 끝낸다.
    String ddl = ProgramSql.ddl("SCOTT", List.of(
        new String[] {"PACKAGE", "package pkg_order as\n"},
        new String[] {"PACKAGE", "  procedure save;\n"},
        new String[] {"PACKAGE", "end;\n"},
        new String[] {"PACKAGE BODY", "PACKAGE BODY pkg_order AS\n"},
        new String[] {"PACKAGE BODY", "  PROCEDURE save IS BEGIN NULL; END;\n"},
        new String[] {"PACKAGE BODY", "END;\n"}));
    eq(ddl, "CREATE OR REPLACE PACKAGE SCOTT.pkg_order as\n  procedure save;\nend;\n/\n"
            + "\nCREATE OR REPLACE PACKAGE BODY SCOTT.pkg_order AS\n  PROCEDURE save IS BEGIN NULL; END;\nEND;\n/\n");
    // 이미 소유자가 있거나 따옴표 이름이면 그대로, 소문자 소유자는 따옴표.
    eq(ProgramSql.ddl("SCOTT", List.<String[]>of(new String[] {"PROCEDURE", "PROCEDURE hr.p_save IS BEGIN NULL; END;"})),
       "CREATE OR REPLACE PROCEDURE hr.p_save IS BEGIN NULL; END;\n/\n");
    eq(ProgramSql.ddl("app_user", List.<String[]>of(new String[] {"FUNCTION", "FUNCTION \"F x\" RETURN NUMBER IS BEGIN RETURN 1; END;"})),
       "CREATE OR REPLACE FUNCTION \"app_user\".\"F x\" RETURN NUMBER IS BEGIN RETURN 1; END;\n/\n");
    eq(ProgramSql.ddl("SCOTT", List.of()), "");

    // 세션 · 잠금 쿼리는 변경 문장으로 보지 않고 바인드가 없다.
    for (DbKind k : DbKind.values()) {
      for (String sql : List.of(SessionSql.sessions(k), SessionSql.locks(k))) {
        if (SqlGuard.isWrite(sql) || SqlGuard.danger(sql) != null) throw new AssertionError("변경 문장으로 봄: " + sql);
        if (sql.indexOf('?') >= 0) throw new AssertionError("바인드 있음: " + sql);
      }
    }
    for (String sql : List.of(ProgramSql.LIST, ProgramSql.SOURCE, ProgramSql.ERRORS)) {
      if (SqlGuard.isWrite(sql)) throw new AssertionError(sql);
    }
    System.out.println("ProgramSqlCheck 통과");
  }
}
