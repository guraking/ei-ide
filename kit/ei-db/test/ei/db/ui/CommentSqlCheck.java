package ei.db.ui;

import ei.db.DbKind;
import ei.db.SqlGuard;

import java.util.List;
import java.util.Map;

/** COMMENT ON 문 만들기 · 붙여 넣기 읽기 자체 점검: java -cp out:test-out ei.db.ui.CommentSqlCheck */
public class CommentSqlCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    // 이름: 접히는 이름은 대문자, 대소문자가 섞였거나 예약어 · 특수 글자면 저장된 그대로 따옴표.
    eq(CommentSql.name(DbKind.ORACLE, "ORDER_ITEM"), "ORDER_ITEM");
    eq(CommentSql.name(DbKind.ORACLE, "order_item"), "\"order_item\"");
    eq(CommentSql.name(DbKind.ORACLE, "ORDER"), "\"ORDER\"");
    eq(CommentSql.name(DbKind.POSTGRES, "order_item"), "ORDER_ITEM");
    eq(CommentSql.name(DbKind.POSTGRES, "OrderItem"), "\"OrderItem\"");
    eq(CommentSql.name(DbKind.POSTGRES, "order"), "\"order\"");
    eq(CommentSql.name(DbKind.POSTGRES, "my col"), "\"my col\"");
    eq(CommentSql.name(DbKind.POSTGRES, "a\"b"), "\"a\"\"b\"");
    eq(CommentSql.name(DbKind.POSTGRES, "public", "orders"), "PUBLIC.ORDERS");

    // 표 주석: 스키마 지정, 작은따옴표는 두 번, 줄바꿈은 그대로.
    eq(CommentSql.table(DbKind.ORACLE, CommentSql.ObjectKind.TABLE, "SCOTT", "EMP", "사원 '정보'\n둘째 줄"),
       "COMMENT ON TABLE SCOTT.EMP IS '사원 ''정보''\n둘째 줄'");
    // 빈 주석 · null 은 '' (주석 지움).
    eq(CommentSql.table(DbKind.POSTGRES, CommentSql.ObjectKind.TABLE, "public", "orders", ""), "COMMENT ON TABLE PUBLIC.ORDERS IS ''");
    eq(CommentSql.column(DbKind.ORACLE, "SCOTT", "EMP", "ENAME", null), "COMMENT ON COLUMN SCOTT.EMP.ENAME IS ''");
    // 역슬래시는 Oracle · PostgreSQL 에서 그대로.
    eq(CommentSql.column(DbKind.POSTGRES, "public", "orders", "user", "a\\b"), "COMMENT ON COLUMN PUBLIC.ORDERS.\"user\" IS 'a\\b'");
    eq(CommentSql.column(DbKind.ORACLE, "app", "Emp Log", "Memo", "x"), "COMMENT ON COLUMN \"app\".\"Emp Log\".\"Memo\" IS 'x'");

    // 대상 낱말: Oracle 은 뷰도 TABLE, 구체화 뷰만 따로. PostgreSQL 은 종류마다.
    eq(CommentSql.word(DbKind.ORACLE, CommentSql.ObjectKind.VIEW), "TABLE");
    eq(CommentSql.word(DbKind.ORACLE, CommentSql.ObjectKind.MATERIALIZED_VIEW), "MATERIALIZED VIEW");
    eq(CommentSql.word(DbKind.POSTGRES, CommentSql.ObjectKind.VIEW), "VIEW");
    eq(CommentSql.word(DbKind.POSTGRES, CommentSql.ObjectKind.FOREIGN_TABLE), "FOREIGN TABLE");
    eq(CommentSql.ObjectKind.ofTableType("MATERIALIZED VIEW"), CommentSql.ObjectKind.MATERIALIZED_VIEW);
    eq(CommentSql.ObjectKind.ofTableType("PARTITIONED TABLE"), CommentSql.ObjectKind.TABLE);
    eq(CommentSql.ObjectKind.ofTableType("VIEW"), CommentSql.ObjectKind.VIEW);
    eq(CommentSql.ObjectKind.ofTableType(null), CommentSql.ObjectKind.TABLE);

    // 문장 묶음: 순서 그대로, 표 주석은 column 이 null.
    List<String> sqls = CommentSql.statements(DbKind.POSTGRES, CommentSql.ObjectKind.VIEW, "public", "v_orders",
        List.of(new CommentSql.Change(null, "주문 뷰"), new CommentSql.Change("id", "번호"), new CommentSql.Change("memo", "")));
    eq(sqls, List.of("COMMENT ON VIEW PUBLIC.V_ORDERS IS '주문 뷰'", "COMMENT ON COLUMN PUBLIC.V_ORDERS.ID IS '번호'",
                     "COMMENT ON COLUMN PUBLIC.V_ORDERS.MEMO IS ''"));
    eq(CommentSql.script(sqls), sqls.get(0) + ";\n" + sqls.get(1) + ";\n" + sqls.get(2) + ";\n");
    eq(CommentSql.script(List.of()), "");
    // 실행 입구의 판정: 변경이자 DDL(읽기 전용 거절 · 운영 확인 · Oracle 암묵 커밋 확인이 걸린다), 위험 문장은 아니다. 끝의 ; 를 떼도 글이 같다.
    for (String sql : sqls) {
      if (!SqlGuard.isWrite(sql) || !SqlGuard.isDdl(sql) || SqlGuard.danger(sql) != null) throw new AssertionError(sql);
      eq(SqlGuard.trimStatement(sql + ";"), sql);
    }
    // 주석 글 안의 ; · -- · WHERE 없는 DELETE 글이 판정을 바꾸지 않는다.
    String tricky = CommentSql.column(DbKind.ORACLE, "S", "T", "C", "a; DELETE FROM T -- x");
    if (!SqlGuard.isDdl(tricky) || SqlGuard.danger(tricky) != null) throw new AssertionError(tricky);

    // 붙여 넣기: 탭 구분, CRLF, 앞뒤 공백, 셋째 칸 버림, 탭 없는 줄 · 빈 이름 · 빈 주석 칸 건너뜀(건너뛴 빈 주석 줄은 센다), 같은 이름은 뒤의 것.
    String paste = "EMPNO\t사원 번호\r\n ENAME \t 이름 \t버림\r\nONLY_NAME\r\n\t주석만\r\nJOB\t\r\nSAL\t  \t셋째\r\nEMPNO\t사번\r\n";
    Map<String, String> pasted = CommentSql.parsePaste(paste);
    eq(pasted, Map.of("EMPNO", "사번", "ENAME", "이름"));
    eq(List.copyOf(pasted.keySet()), List.of("EMPNO", "ENAME"));
    eq(CommentSql.blankComments(paste), 2);
    eq(CommentSql.blankComments(null), 0);
    // 따옴표로 감싼 칸: 줄바꿈 · 탭을 품고 "" 는 " 하나.
    eq(CommentSql.parsePaste("MEMO\t\"첫 줄\n둘째\t줄 \"\"인용\"\"\"\nA\tb"), Map.of("MEMO", "첫 줄\n둘째\t줄 \"인용\"", "A", "b"));
    // 칸 중간의 따옴표는 글자다. 닫히지 않은 따옴표는 끝까지.
    eq(CommentSql.parsePaste("A\t5\" 화면"), Map.of("A", "5\" 화면"));
    eq(CommentSql.parsePaste("A\t\"열린 채"), Map.of("A", "열린 채"));
    eq(CommentSql.parsePaste(null), Map.of());
    eq(CommentSql.parsePaste(""), Map.of());
    eq(CommentSql.parsePaste("탭 없는 글"), Map.of());

    // 맞추기: 대소문자 무시, 표 이름은 표 주석으로, 나머지는 unknown.
    CommentSql.Matched m = CommentSql.match("EMP", List.of("EMPNO", "ENAME"),
        CommentSql.parsePaste("empno\t번호\nemp\t사원\n칼럼\t주석\nNOPE\tx"));
    eq(m.columns(), Map.of(0, "번호"));
    eq(m.tableComment(), "사원");
    eq(m.unknown(), List.of("칼럼", "NOPE"));
    // 표와 이름이 같은 칼럼이 있으면 칼럼이 먼저.
    eq(CommentSql.match("CODE", List.of("CODE"), Map.of("code", "코드")).tableComment(), null);

    System.out.println("CommentSqlCheck 통과");
  }
}
