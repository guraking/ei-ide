package ei.db.ui;

import ei.db.DbKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 탐색기 「시노님 · 권한 주기」의 GRANT · REVOKE · 시노님 문 만들기와 받는 사용자 글 읽기. IntelliJ 클래스를 쓰지 않는다(GrantSqlCheck 로 점검).
 * Oracle · PostgreSQL 만 다룬다(MySQL 은 사용자 이름에 호스트가 붙어 부르는 쪽이 거절한다). 시노님은 Oracle 에만 있다.
 * 객체 이름은 CommentSql.name 표기(필요할 때만 따옴표), 받는 사용자는 readGrantees 가 검사한 표기를 그대로 쓴다.
 */
final class GrantSql {
  private GrantSql() {}

  static final String SELECT = "SELECT";
  static final String INSERT = "INSERT";
  static final String UPDATE = "UPDATE";
  static final String DELETE = "DELETE";
  static final String EXECUTE = "EXECUTE";
  /** 모든 사용자를 뜻하는 낱말. 따옴표를 붙이면 그 이름의 사용자가 되므로 늘 맨 낱말로 쓴다. */
  static final String PUBLIC = "PUBLIC";

  /** 시노님을 어디에 만들지(Oracle). GRANTEE 는 받는 사용자마다 그 사용자 스키마에, PUBLIC_SYNONYM 은 공용 하나. */
  enum Synonym { NONE, GRANTEE, PUBLIC_SYNONYM }

  /**
   * 만들 문장 묶음. objects 는 schema 안 객체의 메타 이름(표 · 뷰 · 프로그램), privileges 는 위 상수, grantees 는 readGrantees 의 names.
   * revoke 면 grantOption · schemaUsage 는 무시한다(REVOKE USAGE ON SCHEMA 는 그 스키마의 다른 권한까지 못 쓰게 하므로 만들지 않는다).
   */
  record Request(DbKind kind, boolean revoke, String schema, List<String> objects, List<String> privileges, List<String> grantees,
                 boolean grantOption, Synonym synonym, boolean schemaUsage) {}

  /** 받는 사용자 글을 읽은 결과: 쓸 이름(나온 순서, 같은 것은 한 번) · 이름 꼴이 아닌 조각. */
  record Grantees(List<String> names, List<String> invalid) {}

  /** 따옴표 없는 이름: 첫 글자 영문 · _, 나머지 영문 · 숫자 · _ $ #. 따옴표 이름은 " 안에 " 가 없는 한 줄. */
  private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
  private static final Pattern QUOTED = Pattern.compile("\"[^\"\\r\\n]+\"");
  /** 받는 사용자 사이 구분: 쉼표 · 공백 · 줄바꿈(따옴표 안 공백은 PLAIN · QUOTED 검사에서 걸러진다). */
  private static final Pattern SEPARATOR = Pattern.compile("[,\\s]+");
  private static final String STATEMENT_END = ";";

  /**
   * 사용자가 쓴 받는 사용자 글을 이름 목록으로. 따옴표 없는 이름은 대문자로 쓴다(DB 가 대문자 · 소문자로 접으므로 뜻은 같다).
   * 따옴표 이름은 쓴 그대로 둔다. 그 밖의 조각(; · 괄호 · 따옴표 안 공백 등)은 invalid 로 돌려 SQL 에 들어가지 않게 한다. null · 빈 글이면 빈 목록.
   */
  static Grantees readGrantees(String text) {
    List<String> names = new ArrayList<>();
    List<String> invalid = new ArrayList<>();
    if (text == null) return new Grantees(names, invalid);
    for (String part : SEPARATOR.split(text.strip())) {
      if (part.isEmpty()) continue;
      String name;
      if (PLAIN.matcher(part).matches()) name = part.toUpperCase(Locale.ROOT);
      else if (QUOTED.matcher(part).matches()) name = part;
      else {
        invalid.add(part);
        continue;
      }
      if (!names.contains(name)) names.add(name);
    }
    return new Grantees(names, invalid);
  }

  /**
   * 문장 목록(끝의 ; 없음). 권한도 시노님도 없으면 빈 목록. 받는 사용자 · 객체가 없으면 빈 목록.
   * 주기: (PostgreSQL 스키마 사용 권한) → 객체마다 GRANT → 시노님 CREATE OR REPLACE.
   * 빼기: 객체마다 REVOKE → 시노님 DROP. 시노님이 없어 DROP 이 실패해도 권한은 이미 빠지도록 REVOKE 를 먼저 둔다.
   * 받는 사용자 시노님은 PUBLIC 에는 만들지 않는다(공용 시노님은 PUBLIC_SYNONYM 으로 고른다).
   */
  static List<String> statements(Request r) {
    List<String> out = new ArrayList<>();
    if (r.objects().isEmpty() || r.grantees().isEmpty()) return out;
    boolean oracle = r.kind() == DbKind.ORACLE;
    String to = String.join(", ", r.grantees());
    if (!r.revoke() && r.schemaUsage() && r.kind() == DbKind.POSTGRES && !r.privileges().isEmpty()) {
      out.add("GRANT USAGE ON SCHEMA " + CommentSql.name(r.kind(), r.schema()) + " TO " + to);
    }
    if (!r.privileges().isEmpty()) {
      String privileges = String.join(", ", r.privileges());
      for (String object : r.objects()) {
        String target = CommentSql.name(r.kind(), r.schema(), object);
        out.add(r.revoke() ? "REVOKE " + privileges + " ON " + target + " FROM " + to
                           : "GRANT " + privileges + " ON " + target + " TO " + to + (r.grantOption() ? " WITH GRANT OPTION" : ""));
      }
    }
    if (!oracle || r.synonym() == Synonym.NONE) return out;
    for (String object : r.objects()) {
      String target = CommentSql.name(r.kind(), r.schema(), object);
      String name = CommentSql.name(r.kind(), object);
      if (r.synonym() == Synonym.PUBLIC_SYNONYM) {
        out.add(r.revoke() ? "DROP PUBLIC SYNONYM " + name : "CREATE OR REPLACE PUBLIC SYNONYM " + name + " FOR " + target);
        continue;
      }
      for (String grantee : r.grantees()) {
        if (grantee.equals(PUBLIC)) continue;
        out.add(r.revoke() ? "DROP SYNONYM " + grantee + "." + name : "CREATE OR REPLACE SYNONYM " + grantee + "." + name + " FOR " + target);
      }
    }
    return out;
  }

  /** 받는 사용자 목록에서 고른 DB 이름을 칸에 넣을 표기로: PUBLIC 은 그대로, 나머지는 따옴표가 필요할 때만. */
  static String granteeText(DbKind kind, String dbName) {
    return dbName.equalsIgnoreCase(PUBLIC) ? PUBLIC : CommentSql.name(kind, dbName);
  }

  /** 콘솔 · 미리보기용 글: 문장마다 ; 로 끝내고 한 줄씩. 문장이 없으면 빈 글. */
  static String script(List<String> statements) {
    StringBuilder sb = new StringBuilder();
    for (String s : statements) sb.append(s).append(STATEMENT_END).append('\n');
    return sb.toString();
  }

  /** 받는 사용자 고르기 목록. Oracle 은 ALL_USERS(누구나 읽음), PostgreSQL 은 pg_roles 에서 내부 역할(pg_ 로 시작)을 뺀 것. */
  static String usersSql(DbKind kind) {
    return kind == DbKind.ORACLE ? ORACLE_USERS : POSTGRES_USERS;
  }

  private static final String ORACLE_USERS = """
      SELECT
             u.USERNAME
        FROM ALL_USERS u
       ORDER BY u.USERNAME""";

  private static final String POSTGRES_USERS = """
      SELECT
             r.ROLNAME
        FROM PG_ROLES r
       WHERE r.ROLNAME NOT LIKE 'pg\\_%'
       ORDER BY r.ROLNAME""";
}
