package ei.db.console;

import ei.db.DbKind;
import ei.db.QueryResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** 고치기 전 백업(문장 읽기 · 되돌리는 SQL · 파일 머리) 자체 점검: java -cp out:test-out ei.db.console.BackupCheck */
public class BackupCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static BackupSql.Target ok(String sql) { return ok(sql, 0, DbKind.ORACLE); }

  static BackupSql.Target ok(String sql, int binds, DbKind kind) {
    BackupSql.Parsed p = BackupSql.parse(sql, binds, kind);
    if (p.target() == null) throw new AssertionError("백업 대상이어야 합니다: " + sql + " → " + p.unsupported());
    return p.target();
  }

  static void unsupported(String sql, String reason) { unsupported(sql, 0, DbKind.ORACLE, reason); }

  static void unsupported(String sql, int binds, DbKind kind, String reason) {
    BackupSql.Parsed p = BackupSql.parse(sql, binds, kind);
    if (p.target() != null) throw new AssertionError("지원 안 함이어야 합니다: " + sql);
    eq(p.unsupported(), reason);
  }

  static void none(String sql) {
    if (!BackupSql.parse(sql, 0, DbKind.ORACLE).none()) throw new AssertionError("백업 대상 문장이 아니어야 합니다: " + sql);
  }

  public static void main(String[] args) {
    parse();
    binds();
    restore();
    names();
    header();
    System.out.println("BackupCheck 통과");
  }

  static void parse() {
    // 기본 꼴: 표 · 별칭 · WHERE.
    BackupSql.Target t = ok("UPDATE EMP SET SAL = 1 WHERE EMPNO = 7 AND NAME = 'A'");
    eq(t.delete(), false);
    eq(t.table(), "EMP");
    eq(t.schema(), null);
    eq(t.name(), "EMP");
    eq(t.alias(), null);
    eq(t.where(), "EMPNO = 7 AND NAME = 'A'");
    eq(BackupSql.select(t), "SELECT\n       *\n  FROM EMP\n WHERE EMPNO = 7 AND NAME = 'A'");

    t = ok("delete from emp e\n where e.deptno = 10;");
    eq(t.delete(), true);
    eq(t.alias(), "e");
    eq(t.where(), "e.deptno = 10");
    eq(BackupSql.select(t), "SELECT\n       *\n  FROM emp e\n WHERE e.deptno = 10");

    // Oracle 은 FROM 없이 DELETE 표. AS 별칭은 SELECT 에서 AS 없이 쓴다.
    eq(BackupSql.select(ok("DELETE EMP WHERE X = 1")), "SELECT\n       *\n  FROM EMP\n WHERE X = 1");
    eq(BackupSql.select(ok("UPDATE emp AS e SET sal = 1 WHERE e.id = 1", 0, DbKind.POSTGRES)), "SELECT\n       *\n  FROM emp e\n WHERE e.id = 1");

    // WHERE 없음: 모든 행.
    t = ok("UPDATE EMP SET SAL = 0");
    eq(t.where(), null);
    eq(BackupSql.select(t), "SELECT\n       *\n  FROM EMP");
    eq(ok("DELETE FROM EMP").where(), null);
    eq(ok("DELETE FROM EMP e").alias(), "e");

    // 스키마.표 · 따옴표 이름 · 한글 이름.
    t = ok("UPDATE HR.EMP SET A = 1 WHERE B = 2");
    eq(t.table(), "HR.EMP");
    eq(t.schema(), "HR");
    eq(t.name(), "EMP");
    t = ok("UPDATE \"My Schema\".\"Order Table\" \"o\" SET A = 1 WHERE \"o\".\"WHERE\" = 2");
    eq(t.table(), "\"My Schema\".\"Order Table\"");
    eq(t.schema(), "\"My Schema\"");
    eq(t.name(), "\"Order Table\"");
    eq(t.alias(), "\"o\"");
    eq(t.where(), "\"o\".\"WHERE\" = 2");
    eq(ok("DELETE FROM 사원 WHERE 사번 = 1").name(), "사원");
    t = ok("UPDATE `my db`.`t``x` SET a = 1 WHERE b = 2", 0, DbKind.MYSQL);
    eq(t.table(), "`my db`.`t``x`");
    eq(t.schema(), "my db");
    eq(t.name(), "t`x");

    // 문자열 · 주석 · 괄호 안의 WHERE 는 조건절이 아니다. 힌트 · 주석은 건너뛴다.
    eq(ok("UPDATE T SET MEMO = 'no WHERE here' WHERE ID = 1").where(), "ID = 1");
    eq(ok("UPDATE T SET A = (SELECT MAX(B) FROM U WHERE U.X = T.X) WHERE ID = 1").where(), "ID = 1");
    eq(ok("UPDATE T SET A = (SELECT MAX(B) FROM U WHERE U.X = T.X)").where(), null);
    eq(ok("UPDATE /*+ INDEX(T PK_T) */ T -- 표\n SET A = 1 /* WHERE X */ WHERE ID = 1").where(), "ID = 1");
    eq(ok("UPDATE T SET A = 'it''s' WHERE B = 'x;y'").where(), "B = 'x;y'");
    eq(ok("UPDATE T SET A = q'[it's WHERE]' WHERE ID = 1").where(), "ID = 1");
    eq(ok("UPDATE t SET a = E'it\\'s WHERE' WHERE id = 1", 0, DbKind.POSTGRES).where(), "id = 1");
    eq(ok("UPDATE t SET a = $q$ it's WHERE $q$ WHERE id = 1", 0, DbKind.POSTGRES).where(), "id = 1");
    eq(ok("UPDATE t SET a = 'x\\' WHERE ' WHERE id = 1", 0, DbKind.MYSQL).where(), "id = 1");
    eq(ok("UPDATE T SET A = CASE WHEN B = 1 THEN 2 ELSE 3 END WHERE ID IN (SELECT ID FROM U WHERE Z = 1)").where(),
        "ID IN (SELECT ID FROM U WHERE Z = 1)");
    // WHERE 안의 FROM(IS DISTINCT FROM · EXTRACT)은 조건의 일부.
    eq(ok("DELETE FROM t WHERE a IS DISTINCT FROM b AND EXTRACT(YEAR FROM d) = 2026", 0, DbKind.POSTGRES).where(),
        "a IS DISTINCT FROM b AND EXTRACT(YEAR FROM d) = 2026");

    // 지원 안 함.
    unsupported("MERGE INTO T USING U ON (T.ID = U.ID) WHEN MATCHED THEN UPDATE SET T.A = U.A", BackupSql.R_MERGE);
    unsupported("WITH X AS (SELECT 1 FROM DUAL) DELETE FROM T WHERE ID IN (SELECT * FROM X)", BackupSql.R_WRAPPED);
    unsupported("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", BackupSql.R_WRAPPED);
    unsupported("EXPLAIN ANALYZE UPDATE t SET a = 1", BackupSql.R_WRAPPED);
    unsupported("UPDATE T, U SET T.A = U.A WHERE T.ID = U.ID", BackupSql.R_SHAPE);
    unsupported("UPDATE T JOIN U ON T.ID = U.ID SET T.A = 1", BackupSql.R_SHAPE);
    unsupported("UPDATE (SELECT A FROM T WHERE ID = 1) SET A = 2", BackupSql.R_SHAPE);
    unsupported("DELETE FROM (SELECT * FROM T) WHERE ID = 1", BackupSql.R_SHAPE);
    unsupported("UPDATE ONLY t SET a = 1 WHERE id = 1", BackupSql.R_SHAPE);
    unsupported("DELETE FROM ONLY t WHERE id = 1", BackupSql.R_SHAPE);
    unsupported("UPDATE T@REMOTE SET A = 1 WHERE ID = 1", BackupSql.R_SHAPE);
    unsupported("UPDATE T PARTITION (P1) SET A = 1", BackupSql.R_SHAPE);
    unsupported("UPDATE A.B.C SET X = 1", BackupSql.R_SHAPE);
    unsupported("DELETE FROM t USING u WHERE t.id = u.id", BackupSql.R_SHAPE);
    unsupported("DELETE t FROM t JOIN u ON t.id = u.id", BackupSql.R_SHAPE);
    unsupported("DELETE FROM t RETURNING *", BackupSql.R_SHAPE);
    unsupported("DELETE FROM T WHERE", BackupSql.R_SHAPE);
    unsupported("UPDATE t SET a = u.a FROM u WHERE t.id = u.id", BackupSql.R_CLAUSE);
    unsupported("UPDATE t SET a = 1 WHERE id = 1 RETURNING a", BackupSql.R_CLAUSE);
    unsupported("DELETE FROM t WHERE id = 1 RETURNING *", BackupSql.R_CLAUSE);
    unsupported("UPDATE T SET A = 1 WHERE ID = 1 RETURNING A INTO :V", BackupSql.R_CLAUSE);
    unsupported("DELETE FROM t WHERE a = 1 ORDER BY b LIMIT 3", BackupSql.R_CLAUSE);
    unsupported("UPDATE T SET A = 1 WHERE CURRENT OF CUR", BackupSql.R_CLAUSE);
    unsupported("UPDATE T SET A = 1 WHERE ID = 1 LOG ERRORS INTO ERR$_T", BackupSql.R_CLAUSE);
    unsupported("UPDATE T SET A = 1 WHERE ID = 1; DELETE FROM T", BackupSql.R_PARSE);
    unsupported("UPDATE T SET A = 'x WHERE ID = 1", BackupSql.R_PARSE);
    unsupported("UPDATE T SET A = (1 WHERE ID = 1", BackupSql.R_PARSE);
    unsupported("DELETE FROM T /* 닫히지 않음 WHERE ID = 1", BackupSql.R_PARSE);

    // 백업 대상 문장이 아님.
    none("SELECT * FROM T WHERE ID = 1 FOR UPDATE");
    none("WITH X AS (SELECT 1 FROM DUAL) SELECT * FROM X FOR UPDATE");
    none("INSERT INTO T (A) VALUES (1)");
    none("EXPLAIN PLAN FOR DELETE FROM T");
    none("BEGIN UPDATE T SET A = 1; END;");
    none("DROP TABLE T");
    none("UPDATES");
    none("");
  }

  static void binds() {
    // WHERE 쪽 ? 만 쓴다: SET 의 ? 는 앞에서 센다.
    BackupSql.Target t = ok("UPDATE T SET A = ?, B = ? WHERE ID = ? AND C IN (?, ?)", 5, DbKind.ORACLE);
    eq(t.bindsBefore(), 2);
    eq(t.whereBinds(), 3);
    t = ok("DELETE FROM T WHERE ID = ?", 1, DbKind.ORACLE);
    eq(t.bindsBefore(), 0);
    eq(t.whereBinds(), 1);
    t = ok("UPDATE T SET A = ?", 1, DbKind.ORACLE);
    eq(t.bindsBefore(), 1);
    eq(t.whereBinds(), 0);
    // 문자열 · 주석 안의 ? 는 자리가 아니다.
    t = ok("UPDATE T SET A = '?' /* ? */ WHERE ID = ? -- ?", 1, DbKind.ORACLE);
    eq(t.bindsBefore(), 0);
    eq(t.whereBinds(), 1);
    // 수가 다르면 가를 수 없다.
    unsupported("UPDATE T SET A = ? WHERE ID = ?", 3, DbKind.ORACLE, BackupSql.R_BINDS);
    unsupported("UPDATE t SET a = ? WHERE j ? 'k' AND id = ?", 2, DbKind.POSTGRES, BackupSql.R_BINDS);
    // 값 없이 실행하면 ? 는 자리가 아니다(PostgreSQL jsonb 연산자).
    t = ok("UPDATE t SET a = 1 WHERE j ? 'k'", 0, DbKind.POSTGRES);
    eq(t.whereBinds(), 0);
    eq(t.where(), "j ? 'k'");
  }

  static void restore() {
    List<String> columns = List.of("ID", "NAME", "SAL", "PHOTO", "REG_DT");
    List<String> types = List.of("NUMBER", "VARCHAR2", "NUMBER", "BLOB", "DATE");
    List<List<Object>> rows = new ArrayList<>();
    rows.add(Arrays.asList(new BigDecimal("1"), "O'Neil", new BigDecimal("1200.50"), new QueryResult.BinaryValue(10), Timestamp.valueOf("2026-01-02 03:04:05")));
    rows.add(Arrays.asList(new BigDecimal("2"), null, null, null, null));

    // DELETE → INSERT, LOB 칼럼은 빼고 적는다.
    BackupSql.Restore r = BackupSql.restore(true, "EMP", columns, types, rows, Set.of(), DbKind.ORACLE);
    eq(r.mode(), BackupSql.Mode.INSERT);
    eq(r.excluded(), List.of("PHOTO"));
    eq(count(r.body(), "INSERT INTO EMP ("), 2);
    if (!r.body().contains("'O''Neil'") || !r.body().contains("TIMESTAMP '2026-01-02 03:04:05.0'") || r.body().contains("이진")) {
      throw new AssertionError(r.body());
    }

    // UPDATE + PK → PK 로 찾는 UPDATE, SET 은 PK 아닌 모든 칼럼.
    r = BackupSql.restore(false, "HR.EMP", columns, types, rows, Set.of("ID"), DbKind.ORACLE);
    eq(r.mode(), BackupSql.Mode.UPDATE);
    eq(r.excluded(), List.of("PHOTO"));
    eq(count(r.body(), "UPDATE HR.EMP\n"), 2);
    if (!r.body().contains("   SET NAME = 'O''Neil'\n     , SAL = 1200.50\n     , REG_DT = TIMESTAMP '2026-01-02 03:04:05.0'\n WHERE 1 = 1\n   AND ID = 1;")
        || !r.body().contains("   SET NAME = NULL\n     , SAL = NULL\n     , REG_DT = NULL\n WHERE 1 = 1\n   AND ID = 2;")) {
      throw new AssertionError(r.body());
    }

    // PK 없음 · PK 뿐인 표 · PK 가 이진 → INSERT 로 남기고 표시.
    eq(BackupSql.restore(false, "EMP", columns, types, rows, Set.of(), DbKind.ORACLE).mode(), BackupSql.Mode.INSERT_NO_PK);
    r = BackupSql.restore(false, "T", List.of("A", "B"), List.of("NUMBER", "NUMBER"), List.of(List.of(1, 2)), Set.of("A", "B"), DbKind.POSTGRES);
    eq(r.mode(), BackupSql.Mode.INSERT_NO_PK);
    eq(count(r.body(), "INSERT INTO T ("), 1);
    eq(r.excluded(), List.of());
    eq(BackupSql.restore(false, "EMP", columns, types, rows, Set.of("PHOTO"), DbKind.ORACLE).mode(), BackupSql.Mode.INSERT_NO_PK);

    // 남는 칼럼이 없으면 만들지 못한다.
    try {
      BackupSql.restore(true, "T", List.of("B"), List.of("BLOB"), List.of(List.of(new QueryResult.BinaryValue(1))), Set.of(), DbKind.ORACLE);
      throw new AssertionError("LOB 뿐인 표는 예외여야 합니다");
    } catch (IllegalArgumentException expected) {
      // 호출부가 「백업 실패」로 묻는다.
    }

    // 글로 옮길 수 없는 값(toString 이 없는 드라이버 객체)이 있으면 만들지 않는다.
    try {
      BackupSql.restore(true, "T", List.of("ID", "GEO"), List.of("NUMBER", "SDO_GEOMETRY"), List.of(Arrays.asList(1, new Object())), Set.of(), DbKind.ORACLE);
      throw new AssertionError("옮길 수 없는 값은 예외여야 합니다");
    } catch (IllegalArgumentException expected) {
      if (!expected.getMessage().contains("GEO")) throw new AssertionError(expected.getMessage());
    }

    // PostgreSQL 은 소문자 이름 그대로, 대문자가 섞이면 따옴표.
    r = BackupSql.restore(true, "t", List.of("id", "Name"), List.of("int4", "text"), List.of(List.of(1, "a\\b")), Set.of(), DbKind.POSTGRES);
    if (!r.body().contains("       id\n     , \"Name\"\n") || !r.body().contains("'a\\b'")) throw new AssertionError(r.body());

    // 결과 표의 행 지우기 문장.
    SqlText.Update d = BackupSql.delete("EMP", List.of("ID", "\"Seq\""), Arrays.asList(new BigDecimal("7"), "a'b"), DbKind.ORACLE);
    eq(d.preview(), "DELETE FROM EMP\n WHERE 1 = 1\n   AND ID = 7\n   AND \"Seq\" = 'a''b';\n");
    eq(d.sql(), "DELETE FROM EMP\n WHERE 1 = 1\n   AND ID = ?\n   AND \"Seq\" = ?\n");
    eq(d.binds(), Arrays.asList(new BigDecimal("7"), "a'b"));
    d = BackupSql.delete("EMP", List.of("ID", "SUB"), Arrays.asList(1, null), DbKind.ORACLE);
    eq(d.sql(), "DELETE FROM EMP\n WHERE 1 = 1\n   AND ID = ?\n   AND SUB IS NULL\n");
    eq(d.binds(), List.of(1));
  }

  static int count(String text, String part) {
    int n = 0;
    for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) n++;
    return n;
  }

  static void names() {
    eq(BackupSql.safeName("운영 DB (본사)"), "운영_DB__본사_");
    eq(BackupSql.safeName("a/b\\c:d*e?f\"g<h>i|j"), "a_b_c_d_e_f_g_h_i_j");
    eq(BackupSql.safeName("dev-1_x.y"), "dev-1_x.y");
    eq(BackupSql.safeName(".."), "__");
    eq(BackupSql.safeName("name."), "name_");
    eq(BackupSql.safeName(""), "_");
    eq(BackupSql.safeName(null), "_");
    eq(BackupSql.safeName("con"), "_con");
    eq(BackupSql.safeName("NUL.txt"), "_NUL.txt");
    eq(BackupSql.safeName("x".repeat(200)).length(), 80);
    LocalDateTime at = LocalDateTime.of(2026, 11, 13, 9, 5, 7);
    eq(BackupSql.fileName(at, "EMP", 1), "20261113-090507_EMP.sql");
    eq(BackupSql.fileName(at, "HR.\"Order Table\"", 2), "20261113-090507_Order_Table-2.sql");
    eq(BackupSql.fileName(at, "`db`.`t`", 1), "20261113-090507_t.sql");
  }

  static void header() {
    LocalDateTime at = LocalDateTime.of(2026, 11, 13, 9, 5, 7);
    String sql = "UPDATE EMP\n   SET SAL = ?\n\n WHERE ID = ? -- 끝";
    BackupSql.Header h = new BackupSql.Header(at, "운영 DB", BackupSql.Origin.MAPPER, "HR.EMP", 12, List.of("PHOTO", "DOC"), BackupSql.Mode.UPDATE, sql);
    String text = BackupSql.format(h);
    for (String line : text.split("\n")) {
      if (!line.startsWith("--")) throw new AssertionError("머리는 모두 주석이어야 합니다: " + line);
    }
    if (!text.contains("-- 종류: 매퍼 실행\n") || !text.contains("-- 행 수: 12\n") || !text.contains("PHOTO, DOC") || !text.contains("트리거")) {
      throw new AssertionError(text);
    }
    eq(BackupSql.parseHeader(List.of((text + "UPDATE EMP\n   SET A = 1;\n").split("\n"))), h);

    // 뺀 칼럼 없음 · PK 없음 · 접속 이름의 줄바꿈은 한 줄로.
    BackupSql.Header none = new BackupSql.Header(at, "a\nb", BackupSql.Origin.GRID_DELETE, "T", 0, List.of(), BackupSql.Mode.INSERT_NO_PK, "DELETE FROM T");
    BackupSql.Header read = BackupSql.parseHeader(List.of(BackupSql.format(none).split("\n")));
    eq(read.connection(), "a b");
    eq(read.excluded(), List.of());
    eq(read.mode(), BackupSql.Mode.INSERT_NO_PK);
    eq(read.origin(), BackupSql.Origin.GRID_DELETE);
    if (!BackupSql.format(none).contains("PK 없음, 직접 확인")) throw new AssertionError(BackupSql.format(none));
    for (BackupSql.Origin o : BackupSql.Origin.values()) {
      eq(BackupSql.parseHeader(List.of(BackupSql.format(new BackupSql.Header(at, "c", o, "T", 1, List.of(), BackupSql.Mode.INSERT, "X")).split("\n"))).origin(), o);
    }

    // 우리 파일이 아니거나 머리가 끊긴 파일.
    eq(BackupSql.parseHeader(List.of()), null);
    eq(BackupSql.parseHeader(List.of("SELECT 1 FROM DUAL")), null);
    List<String> cut = new ArrayList<>(List.of(text.split("\n")));
    cut.remove(cut.size() - 1);
    eq(BackupSql.parseHeader(cut), null);
    eq(BackupSql.parseHeader(List.of(text.replace("행 수: 12", "행 수: 열둘").split("\n"))), null);
  }
}
