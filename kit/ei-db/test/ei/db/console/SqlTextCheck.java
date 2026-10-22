package ei.db.console;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class SqlTextCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  static void fails(Runnable r) {
    try { r.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("예외가 나야 함");
  }
  public static void main(String[] args) {
    List<String> cols = List.of("ORDER_ID", "LINE_NO", "QTY", "MEMO");
    List<List<Object>> rows = List.of(Arrays.asList(100231, 1, 2, "it's"), Arrays.asList(100231, 2, null, null));
    eq(SqlText.updates("order_item", cols, List.of(), rows, Set.of("ORDER_ID", "LINE_NO"), null),
       "UPDATE order_item\n"
     + "   SET QTY = 2\n"
     + "     , MEMO = 'it''s'\n"
     + " WHERE 1 = 1\n"
     + "   AND ORDER_ID = 100231\n"
     + "   AND LINE_NO = 1;\n"
     + "\n"
     + "UPDATE order_item\n"
     + "   SET QTY = NULL\n"
     + "     , MEMO = NULL\n"
     + " WHERE 1 = 1\n"
     + "   AND ORDER_ID = 100231\n"
     + "   AND LINE_NO = 2;\n");
    String noPk = SqlText.updates("t", List.of("a", "b"), List.of(), List.of(Arrays.asList(1, "x")), Set.of(), null);
    eq(noPk.startsWith("-- PK 를 찾지 못해 첫 칼럼(a)"), true);
    eq(noPk.endsWith("UPDATE t\n   SET b = 'x'\n WHERE 1 = 1\n   AND a = 1;\n"), true);
    eq(SqlText.updates("t", List.of("id"), List.of(), List.of(Arrays.asList(1)), Set.of("ID"), null), "");
    eq(SqlText.update("t", List.of("ID", "V"), Arrays.asList(null, 3), new boolean[]{true, false}),
       "UPDATE t\n   SET V = 3\n WHERE 1 = 1\n   AND ID IS NULL;\n");

    eq(SqlText.inserts("T", List.of("A", "B"), List.of(), List.of(Arrays.asList("x", 1), Arrays.asList(null, 22)), null),
       "INSERT INTO T (\n"
     + "       A\n"
     + "     , B\n"
     + ") VALUES (\n"
     + "       'x'  -- A\n"
     + "     , 1    -- B\n"
     + ");\n"
     + "\n"
     + "INSERT INTO T (\n"
     + "       A\n"
     + "     , B\n"
     + ") VALUES (\n"
     + "       NULL  -- A\n"
     + "     , 22    -- B\n"
     + ");\n");
    eq(SqlText.inserts("T", List.of("A"), List.of(), List.of(), null), "");

    eq(SqlText.isNumericType("NUMBER"), true);
    eq(SqlText.isNumericType("int unsigned"), true);
    eq(SqlText.isNumericType("BIGINT"), true);
    eq(SqlText.isNumericType("int8"), true);
    eq(SqlText.isNumericType("INTERVAL DAY TO SECOND"), false);
    eq(SqlText.isNumericType("VARCHAR2"), false);
    eq(SqlText.isNumericType(null), false);

    eq(SqlText.isUneditableType("CLOB"), true);
    eq(SqlText.isUneditableType("LONGBLOB"), true);
    eq(SqlText.isUneditableType("LONG RAW"), true);
    eq(SqlText.isUneditableType("BINARY_DOUBLE"), false);
    eq(SqlText.isUneditableType("VARCHAR2"), false);

    eq(SqlText.parseEdit("", "VARCHAR2"), null);
    eq(SqlText.parseEdit("  ", "NUMBER"), null);
    eq(SqlText.parseEdit(" 1,200.50 ", "NUMBER"), new BigDecimal("1200.50"));
    eq(SqlText.parseEdit(" a b ", "VARCHAR2"), " a b ");
    eq(SqlText.parseEdit("2024-03-01 10:20:30", "DATE"), Timestamp.valueOf("2024-03-01 10:20:30"));
    eq(SqlText.parseEdit("2024-03-01", "DATE"), java.sql.Date.valueOf("2024-03-01"));
    fails(() -> SqlText.parseEdit("12a", "NUMBER"));
    fails(() -> SqlText.parseEdit("3월 1일", "TIMESTAMP"));

    eq(SqlText.notEditable("SELECT * FROM TB_PART WHERE A IN (1, 2)"), null);
    eq(SqlText.notEditable("SELECT * FROM TB_PART p WHERE p.A = ','"), null);
    eq(SqlText.notEditable("select p.id, name\n  from tb_part p\n where p.a = 'UNION SELECT' order by id;"), null);
    eq(SqlText.notEditable("SELECT * FROM TB_PART -- , TB_ITEM\nWHERE 1 = 1"), null);
    eq(SqlText.notEditable("SELECT * FROM GPM.TB_PART") != null, true);
    eq(SqlText.notEditable("SELECT * FROM TB_PART a, TB_ITEM b WHERE a.X = b.X") != null, true);
    eq(SqlText.notEditable("SELECT * FROM TB_PART a JOIN TB_ITEM b ON a.X = b.X") != null, true);
    eq(SqlText.notEditable("SELECT id,name FROM emp_2023 UNION ALL SELECT id,name FROM emp_2024") != null, true);
    eq(SqlText.notEditable("SELECT mgr_id AS id, name FROM emp") != null, true);
    eq(SqlText.notEditable("SELECT mgr_id id, name FROM emp") != null, true);
    eq(SqlText.notEditable("SELECT id, name FROM emp WHERE dept IN (SELECT id FROM dept)") != null, true);
    eq(SqlText.notEditable("SELECT * FROM (SELECT * FROM emp)") != null, true);
    eq(SqlText.notEditable("SELECT DISTINCT id FROM emp") != null, true);
    eq(SqlText.notEditable("SELECT id FROM emp GROUP BY id") != null, true);
    eq(SqlText.notEditable("SELECT id FROM emp START WITH mgr IS NULL CONNECT BY PRIOR id = mgr") != null, true);
    eq(SqlText.notEditable("SELECT id, upper(name) FROM emp") != null, true);
    eq(SqlText.notEditable("WITH x AS (SELECT * FROM emp) SELECT * FROM x") != null, true);

    // 반영 실행은 ? + binds, 미리보기만 리터럴. MySQL 에서 \ 로 끝나는 값이 문장을 깨지 않게.
    SqlText.Update u = SqlText.updateWithBinds("emp", List.of("NAME", "MEMO", "ID"), Arrays.asList("a\\", null, 7), new boolean[]{false, false, true});
    eq(u.preview(), "UPDATE emp\n   SET NAME = 'a\\'\n     , MEMO = NULL\n WHERE 1 = 1\n   AND ID = 7;\n");
    eq(u.sql(), "UPDATE emp\n   SET NAME = ?\n     , MEMO = NULL\n WHERE 1 = 1\n   AND ID = ?;\n");
    eq(u.binds(), Arrays.asList("a\\", 7));
    eq(SqlText.updateWithBinds("emp", List.of("ID"), Arrays.asList(1), new boolean[]{true}), null);
    // 복합 PK 일부만 결과에 있으면 만들지 않는다(첫 칼럼 대체는 PK 를 못 읽었을 때만).
    fails(() -> SqlText.updates("order_item", List.of("ORDER_ID", "QTY"), List.of(), List.of(Arrays.asList(1, 2)), Set.of("ORDER_ID", "LINE_NO"), null));
    fails(() -> SqlText.updates("t", List.of("A", "B"), List.of(), List.of(Arrays.asList(1, 2)), Set.of("ID"), null));
    // LOB · 이진 칼럼은 SET · VALUES 에서 빠지고 주석이 붙는다: 형식 이름 · byte[] · 잘린 CLOB 글.
    String cut = "x".repeat(4000) + "…";
    List<String> lobCols = List.of("ID", "DOC", "IMG", "RAWV", "NAME");
    List<String> lobTypes = List.of("NUMBER", "VARCHAR2", "BLOB", "VARCHAR2", "VARCHAR2");
    List<List<Object>> lobRows = List.of(Arrays.asList(1, cut, "(BLOB 3 바이트)", new byte[]{1}, "a"));
    eq(SqlText.updates("t", lobCols, lobTypes, lobRows, Set.of("ID"), null),
       "-- LOB 칼럼 제외: DOC, IMG, RAWV\nUPDATE t\n   SET NAME = 'a'\n WHERE 1 = 1\n   AND ID = 1;\n");
    eq(SqlText.inserts("t", lobCols, lobTypes, lobRows, null),
       "-- LOB 칼럼 제외: DOC, IMG, RAWV\nINSERT INTO t (\n       ID\n     , NAME\n) VALUES (\n       1    -- ID\n     , 'a'  -- NAME\n);\n");
    eq(SqlText.isLobValue(new Object() { @Override public String toString() { return "x"; } }), false);
    eq(SqlText.isLobValue("x".repeat(3999) + "…"), false);
    fails(() -> SqlText.updates("t", List.of("ID", "V"), List.of("RAW", "NUMBER"), List.of(Arrays.asList(new byte[]{1}, 2)), Set.of("ID"), null));
    // MySQL 은 \ 도 이스케이프.
    eq(SqlText.literal("a\\b'c"), "'a\\b''c'");
    eq(SqlText.literal("a\\b'c", ei.db.DbKind.MYSQL), "'a\\\\b''c'");
    eq(SqlText.inserts("T", List.of("A"), List.of(), List.of(Arrays.asList("C:\\")), ei.db.DbKind.MYSQL).contains("'C:\\\\'"), true);

    // 주석 · 문자열 지우기와 다시 읽기 판정.
    eq(SqlText.code("SELECT '--x' a -- FROM T_BAK\n/* FROM Z */ FROM t"), "SELECT '' a  \n  FROM t");
    eq(SqlText.isRereadable("-- 조회\nselect * from t"), true);
    eq(SqlText.isRereadable("WITH x AS (SELECT 1 FROM dual) SELECT * FROM x"), true);
    eq(SqlText.isRereadable("(SELECT 1 FROM dual) UNION (SELECT 2 FROM dual)"), true);
    eq(SqlText.isRereadable("SELECT * FROM t FOR UPDATE"), true);
    eq(SqlText.isRereadable("SELECT 'DELETE' FROM t"), true);
    eq(SqlText.isRereadable("INSERT INTO t VALUES (1) RETURNING id"), false);
    eq(SqlText.isRereadable("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d"), false);
    eq(SqlText.isRereadable("UPDATE t SET a = 1 RETURNING a"), false);
    // 결과 표 표시용 쉼표: 화면 글만 바뀌고 text(복사 · CSV · INSERT 가 쓰는 값)는 그대로.
    eq(SqlText.display(128000), "128,000");
    eq(SqlText.display(999), "999");
    eq(SqlText.display(-1234567L), "-1,234,567");
    eq(SqlText.display(new BigDecimal("1234567.8900")), "1,234,567.8900");
    eq(SqlText.display(new BigDecimal("-100")), "-100");
    eq(SqlText.display(new BigDecimal("1E+3")), "1,000");
    eq(SqlText.display(1.0E10), "1.0E10");
    eq(SqlText.display("1234567"), "1234567");
    eq(SqlText.display(0), "0");
    eq(SqlText.text(new BigDecimal("1234567.89")), "1234567.89");
    eq(SqlText.literal(128000), "128000");
    System.out.println("SqlTextCheck 통과");
  }
}
