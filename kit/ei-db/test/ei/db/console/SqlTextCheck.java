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
    eq(SqlText.updates("order_item", cols, rows, Set.of("ORDER_ID", "LINE_NO")),
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
    String noPk = SqlText.updates("t", List.of("a", "b"), List.of(Arrays.asList(1, "x")), Set.of());
    eq(noPk.startsWith("-- PK 를 찾지 못해 첫 칼럼(a)"), true);
    eq(noPk.endsWith("UPDATE t\n   SET b = 'x'\n WHERE 1 = 1\n   AND a = 1;\n"), true);
    eq(SqlText.updates("t", List.of("id"), List.of(Arrays.asList(1)), Set.of("ID")), "");
    eq(SqlText.update("t", List.of("ID", "V"), Arrays.asList(null, 3), new boolean[]{true, false}),
       "UPDATE t\n   SET V = 3\n WHERE 1 = 1\n   AND ID IS NULL;\n");

    eq(SqlText.inserts("T", List.of("A", "B"), List.of(Arrays.asList("x", 1), Arrays.asList(null, 22))),
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
    eq(SqlText.inserts("T", List.of("A"), List.of()), "");

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
    System.out.println("SqlTextCheck 통과");
  }
}
