package ei.db.console;

import java.util.ArrayList;
import java.util.List;

/** 문장 나누기 자체 점검: java -cp out:test-out ei.db.console.SqlSplitterCheck */
public class SqlSplitterCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  static List<String> texts(String sql) {
    List<String> out = new ArrayList<>();
    for (SqlSplitter.Stmt s : SqlSplitter.split(sql)) out.add(s.text());
    return out;
  }

  public static void main(String[] args) {
    eq(texts("SELECT 1 FROM dual;\nSELECT 2 FROM dual;\n"), List.of("SELECT 1 FROM dual;", "SELECT 2 FROM dual;"));
    // 빈 줄은 경계, 앞 주석은 빼고, 줄 끝 주석은 다음 문장에 붙지 않는다.
    eq(texts("-- 정리\nDELETE FROM t;   -- 1행\n\nSELECT *\n  FROM t\n\nSELECT 3"),
        List.of("DELETE FROM t;", "SELECT *\n  FROM t", "SELECT 3"));
    // 따옴표 · 주석 안의 ; 와 빈 줄은 무시.
    eq(texts("SELECT 'a;\n\nb' FROM t /* ; */;\nSELECT \"x;\" FROM t;"), List.of("SELECT 'a;\n\nb' FROM t /* ; */;", "SELECT \"x;\" FROM t;"));
    eq(texts("SELECT 'it''s;' FROM t;"), List.of("SELECT 'it''s;' FROM t;"));
    eq(texts("SELECT q'[a;b]' FROM t; SELECT 2;"), List.of("SELECT q'[a;b]' FROM t;", "SELECT 2;"));
    // PL/SQL 블록은 통째로.
    String block = "BEGIN\n  UPDATE t SET a = 1;\n\n  IF x THEN\n    NULL;\n  END IF;\n  FOR r IN c LOOP NULL; END LOOP;\nEND;";
    eq(texts(block + "\nSELECT 1 FROM dual;"), List.of(block, "SELECT 1 FROM dual;"));
    String declare = "DECLARE\n  v NUMBER;\nBEGIN\n  v := CASE WHEN 1 = 1 THEN 1 END;\n  BEGIN NULL; END;\nEND;";
    eq(texts(declare + "\n\nSELECT 2 FROM dual"), List.of(declare, "SELECT 2 FROM dual"));
    String proc = "CREATE OR REPLACE PROCEDURE p IS\n  x NUMBER;\nBEGIN\n  x := 1;\nEND p;";
    eq(texts(proc + "\n/\nSELECT 1 FROM dual;"), List.of(proc, "SELECT 1 FROM dual;"));
    String pkg = "CREATE PACKAGE BODY k AS\n  PROCEDURE a IS BEGIN NULL; END a;\n  PROCEDURE b IS BEGIN NULL; END b;\nEND k;";
    eq(texts(pkg + "\nSELECT 1 FROM dual;"), List.of(pkg, "SELECT 1 FROM dual;"));
    // / 줄은 문장에서 빠진다.
    eq(texts("BEGIN NULL; END\n/\n"), List.of("BEGIN NULL; END"));
    // PostgreSQL: BEGIN; 은 트랜잭션, $$ 본문 안 ; 무시.
    eq(texts("BEGIN;\nUPDATE t SET a = 1;\nCOMMIT;"), List.of("BEGIN;", "UPDATE t SET a = 1;", "COMMIT;"));
    String pg = "CREATE FUNCTION f() RETURNS int AS $$\nBEGIN\n  RETURN 1;\nEND;\n$$ LANGUAGE plpgsql;";
    eq(texts(pg + "\nSELECT f();"), List.of(pg, "SELECT f();"));
    // CREATE TABLE 은 블록이 아니다. V$SESSION 의 $ 는 따옴표가 아니다.
    eq(texts("CREATE TABLE t (a NUMBER);\nSELECT * FROM v$session;"), List.of("CREATE TABLE t (a NUMBER);", "SELECT * FROM v$session;"));
    eq(texts("  \n-- 주석만\n;\n"), List.of());

    // 커서 위치로 고르기.
    String text = "-- 최근\nSELECT a\n  FROM t;\n\nUPDATE t SET a = 1 WHERE b = 2;\n";
    eq(SqlSplitter.at(text, text.indexOf("FROM")).text(), "SELECT a\n  FROM t;");
    eq(SqlSplitter.at(text, text.indexOf(";") + 1).text(), "SELECT a\n  FROM t;");
    eq(SqlSplitter.at(text, 2).text(), "SELECT a\n  FROM t;");
    eq(SqlSplitter.at(text, text.indexOf("\n\n") + 1).text(), "UPDATE t SET a = 1 WHERE b = 2;");
    eq(SqlSplitter.at(text, text.length()).text(), "UPDATE t SET a = 1 WHERE b = 2;");
    eq(SqlSplitter.at("", 0), null);
    System.out.println("SqlSplitterCheck 통과");
  }
}
