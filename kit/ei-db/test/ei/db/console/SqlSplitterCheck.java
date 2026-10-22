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
    // 괄호 안의 빈 줄은 경계가 아니다(인라인 뷰 · EXISTS).
    String inline = "SELECT a\n  FROM (\n\n    SELECT 1 AS a FROM dual\n\n  ) v\n WHERE EXISTS (\n\n  SELECT 1 FROM dual)";
    eq(texts(inline + "\n\nSELECT 2 FROM dual"), List.of(inline, "SELECT 2 FROM dual"));
    // 빈 줄 뒤가 이어지는 절(ORDER BY · OFFSET · , 등)이면 끊지 않는다.
    String paging = "SELECT a\n  FROM t\n WHERE 1 = 1\n\n ORDER BY a DESC\n\n  -- 쪽\n  OFFSET 0 ROWS FETCH NEXT 15 ROWS ONLY";
    eq(texts(paging + "\n\nSELECT 2 FROM dual"), List.of(paging, "SELECT 2 FROM dual"));
    eq(texts("SELECT a\n\n     , b\n  FROM t"), List.of("SELECT a\n\n     , b\n  FROM t"));
    String myProc = "CREATE PROCEDURE p() BEGIN DECLARE x INT; DECLARE CONTINUE HANDLER FOR NOT FOUND SET x = 0; SET x = 1; END;";
    eq(texts(myProc + " SELECT 1; SELECT 2;"), List.of(myProc, "SELECT 1;", "SELECT 2;"));
    String nested = "BEGIN DECLARE v NUMBER; BEGIN v := 1; END; END;";
    eq(texts(nested + " SELECT 1 FROM dual;"), List.of(nested, "SELECT 1 FROM dual;"));
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
    // 프로시저 안 지역 함수 · END CASE · END IF · END LOOP 에서 끊기지 않고, 객체 이름의 END 에서 끝난다.
    String local = "CREATE OR REPLACE PROCEDURE gpm.p IS\n  FUNCTION f(a NUMBER) RETURN NUMBER IS\n  BEGIN\n    RETURN a;\n  END f;\n\n"
        + "  PROCEDURE g;\nBEGIN\n  CASE x WHEN 1 THEN NULL; ELSE NULL; END CASE;\n  IF f(1) = 1 THEN NULL; END IF;\n"
        + "  LOOP EXIT; END LOOP;\n  x := CASE WHEN 1 = 1 THEN 1 END;\nEND p;";
    eq(texts(local + "\nSELECT 1 FROM dual;"), List.of(local, "SELECT 1 FROM dual;"));
    // 익명 블록의 지역 함수.
    String anon = "DECLARE\n  FUNCTION f RETURN NUMBER IS\n  BEGIN\n    RETURN 1;\n  END f;\nBEGIN\n  NULL;\nEND;";
    eq(texts(anon + "\nSELECT 2 FROM dual;"), List.of(anon, "SELECT 2 FROM dual;"));
    // 타입 몸통: 메서드마다 END; (생성자는 SELF AS RESULT 의 AS 가 머리를 연다).
    String typeBody = "CREATE OR REPLACE TYPE BODY tp AS\n  CONSTRUCTOR FUNCTION tp RETURN SELF AS RESULT IS\n  BEGIN\n    RETURN;\n  END;\n"
        + "  MEMBER FUNCTION f RETURN NUMBER IS\n  BEGIN\n    RETURN 1;\n  END;\n  MEMBER PROCEDURE p IS\n  BEGIN\n    NULL;\n  END p;\nEND;";
    eq(texts(typeBody + "\nSELECT 3 FROM dual;"), List.of(typeBody, "SELECT 3 FROM dual;"));
    // 패키지 명세(앞선 선언)와 초기화 부분이 있는 몸통.
    String spec = "CREATE PACKAGE k AS\n  PROCEDURE a;\n  FUNCTION b RETURN NUMBER;\nEND k;";
    String body = "CREATE OR REPLACE PACKAGE BODY k IS\n  FUNCTION b RETURN NUMBER IS BEGIN RETURN 1; END b;\n  PROCEDURE a IS BEGIN NULL; END a;\nBEGIN\n  NULL;\nEND k;";
    eq(texts(spec + "\n" + body + "\nSELECT 4 FROM dual;"), List.of(spec, body, "SELECT 4 FROM dual;"));
    // 트리거 · 복합 트리거(END BEFORE STATEMENT 에서 끝나지 않음).
    String trg = "CREATE TRIGGER t1 BEFORE INSERT ON t REFERENCING NEW AS n FOR EACH ROW\nBEGIN\n  :n.a := 1;\nEND;";
    eq(texts(trg + "\nSELECT 5 FROM dual;"), List.of(trg, "SELECT 5 FROM dual;"));
    String compound = "CREATE TRIGGER t2 FOR INSERT ON t COMPOUND TRIGGER\n  BEFORE STATEMENT IS\n  BEGIN\n    NULL;\n  END BEFORE STATEMENT;\nEND t2;";
    eq(texts(compound + "\n/\nSELECT 6 FROM dual;"), List.of(compound, "SELECT 6 FROM dual;"));
    // MySQL 의 END WHILE · END REPEAT.
    String my = "BEGIN\n  WHILE i < 3 DO SET i = i + 1; END WHILE;\n  REPEAT SET i = i - 1; UNTIL i = 0 END REPEAT;\nEND;";
    eq(texts(my + "\nSELECT 7;"), List.of(my, "SELECT 7;"));
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
