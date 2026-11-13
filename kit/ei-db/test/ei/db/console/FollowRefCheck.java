package ei.db.console;

import ei.db.DbKind;
import ei.db.Meta;
import ei.db.QueryResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 「참조 따라가기」의 대상 판정 · SELECT 만들기와 「전체 읽기」 문장(FollowRef) 점검. */
public class FollowRefCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  static Meta.Column col(String name, boolean pk) { return new Meta.Column(name, "NUMBER", 10, 0, pk, false, !pk, ""); }

  public static void main(String[] args) {
    // 표가 꼭 하나일 때만 원본 표를 안다.
    eq(FollowRef.singleTable("SELECT * FROM APP.EMP e WHERE e.ID = 1").name(), "EMP");
    eq(FollowRef.singleTable("SELECT * FROM APP.EMP e WHERE e.ID = 1").schema(), "APP");
    eq(FollowRef.singleTable("SELECT * FROM (SELECT ROWNUM rn, a.* FROM (SELECT * FROM emp ORDER BY 1) a) WHERE rn <= 10").name(), "emp");
    eq(FollowRef.singleTable("SELECT * FROM EMP e, DEPT d WHERE d.ID = e.DEPT_ID"), null);
    eq(FollowRef.singleTable("SELECT * FROM EMP WHERE DEPT_ID IN (SELECT ID FROM DEPT)"), null);
    eq(FollowRef.singleTable("SELECT 1"), null);

    // 조건 값으로 쓸 수 있는 칸.
    eq(FollowRef.followable(10, "NUMBER"), true);
    eq(FollowRef.followable(null, "NUMBER"), false);
    eq(FollowRef.followable(new QueryResult.BinaryValue(3), "RAW"), false);
    eq(FollowRef.followable("x", "CLOB"), false);
    eq(FollowRef.followable("a".repeat(4000) + "…", "VARCHAR2"), false);

    List<String> columns = List.of("EMP_ID", "DEPT_ID", "ORG_CD", "TEAM_NO", "NAME");
    List<Object> row = Arrays.asList(7, 10, "A", 3, "kim");
    List<Meta.ForeignKey> imported = List.of(
        new Meta.ForeignKey("FK_EMP_DEPT", "EMP", "DEPT_ID", "DEPT", "DEPT_ID"),
        new Meta.ForeignKey("FK_EMP_TEAM", "EMP", "ORG_CD", "TEAM", "ORG_CD"),
        new Meta.ForeignKey("FK_EMP_TEAM", "EMP", "TEAM_NO", "TEAM", "TEAM_NO"),
        new Meta.ForeignKey("FK_EMP_TEAM", "EMP", "TEAM_NO", "TEAM", "TEAM_NO"));
    List<Meta.ForeignKey> exported = List.of(
        new Meta.ForeignKey("FK_PAY_EMP", "PAY", "EMP_ID", "EMP", "EMP_ID"),
        new Meta.ForeignKey("FK_EMP_MGR", "EMP", "MGR_ID", "EMP", "EMP_ID"));
    // 1) 외래 키 칼럼 → 가리키는 표.
    eq(FollowRef.declared(columns, 1, row, imported, exported),
       List.of(new FollowRef.Target(FollowRef.Kind.REFERENCE, "DEPT", List.of("DEPT_ID"), List.of(1))));
    // 여러 칼럼 외래 키는 같은 행의 다른 칼럼도 조건에 넣는다(거듭 온 줄은 한 번만).
    eq(FollowRef.declared(columns, 3, row, imported, exported),
       List.of(new FollowRef.Target(FollowRef.Kind.REFERENCE, "TEAM", List.of("ORG_CD", "TEAM_NO"), List.of(2, 3))));
    // 다른 칼럼의 값이 null 이면 그 칼럼은 조건에서 뺀다.
    eq(FollowRef.declared(columns, 3, Arrays.asList(7, 10, null, 3, "kim"), imported, exported),
       List.of(new FollowRef.Target(FollowRef.Kind.REFERENCE, "TEAM", List.of("TEAM_NO"), List.of(3))));
    // 2) PK 칼럼 → 이 값을 가리키는 표마다 하나(자기 표를 가리키는 것도).
    eq(FollowRef.declared(columns, 0, row, imported, exported),
       List.of(new FollowRef.Target(FollowRef.Kind.REFERRED, "PAY", List.of("EMP_ID"), List.of(0)),
               new FollowRef.Target(FollowRef.Kind.REFERRED, "EMP", List.of("MGR_ID"), List.of(0))));
    // 대소문자만 다른 결과 칼럼 이름(PostgreSQL) · 외래 키가 아닌 칼럼.
    eq(FollowRef.declared(List.of("emp_id", "dept_id"), 1, Arrays.asList(7, 10), imported, List.of()).get(0).table(), "DEPT");
    eq(FollowRef.declared(columns, 4, row, imported, exported), List.of());
    // 이름 없는 외래 키는 칼럼마다 따로.
    List<Meta.ForeignKey> unnamed = List.of(new Meta.ForeignKey("", "EMP", "DEPT_ID", "DEPT", "ID"), new Meta.ForeignKey("", "EMP", "ORG_CD", "DEPT", "CD"));
    eq(FollowRef.declared(columns, 1, row, unnamed, List.of()),
       List.of(new FollowRef.Target(FollowRef.Kind.REFERENCE, "DEPT", List.of("ID"), List.of(1))));

    // 3) 이름으로 짐작: PK 가 칼럼 하나이고 이름이 같은 표, PK 가 ID 이고 칼럼이 「표 이름 끝 토막_ID」 인 표. 조회한 표 · 여러 칼럼 PK 는 뺀다.
    Map<String, List<String>> pks = new LinkedHashMap<>();
    pks.put("DEPT", List.of("DEPT_ID"));
    pks.put("DEPT_HIST", List.of("DEPT_ID", "SEQ"));
    pks.put("EMP", List.of("EMP_ID"));
    pks.put("TB_GPM_NPP_PART", List.of("ID"));
    pks.put("dept_old", List.of("dept_id"));
    eq(FollowRef.guessed("DEPT_ID", 1, "EMP", pks),
       List.of(new FollowRef.Target(FollowRef.Kind.GUESS, "DEPT", List.of("DEPT_ID"), List.of(1)),
               new FollowRef.Target(FollowRef.Kind.GUESS, "dept_old", List.of("dept_id"), List.of(1))));
    eq(FollowRef.guessed("NPP_PART_ID", 2, null, pks),
       List.of(new FollowRef.Target(FollowRef.Kind.GUESS, "TB_GPM_NPP_PART", List.of("ID"), List.of(2))));
    eq(FollowRef.guessed("EMP_ID", 0, "emp", pks), List.of());
    eq(FollowRef.guessed("ID", 0, null, pks), List.of());
    eq(FollowRef.guessed("NAME", 4, "EMP", pks), List.of());

    // SELECT: 키워드만 한 줄, 값은 ?, 스키마는 줄 때만. PostgreSQL 소문자 이름은 대문자로, 접히지 않는 이름은 따옴표.
    eq(FollowRef.select(DbKind.ORACLE, null, "TEAM", null, List.of("ORG_CD", "TEAM_NO")),
       "SELECT\n       *\n  FROM TEAM\n WHERE ORG_CD = ?\n   AND TEAM_NO = ?");
    eq(FollowRef.select(DbKind.POSTGRES, "app", "dept", null, List.of("dept_id")),
       "SELECT\n       *\n  FROM APP.DEPT\n WHERE DEPT_ID = ?");
    eq(FollowRef.select(DbKind.POSTGRES, null, "Dept", "Memo", List.of("id")),
       "SELECT\n       \"Memo\"\n  FROM \"Dept\"\n WHERE ID = ?");
    eq(FollowRef.select(DbKind.MYSQL, null, "dept", null, List.of("dept_id")),
       "SELECT\n       *\n  FROM dept\n WHERE dept_id = ?");
    try {
      FollowRef.select(DbKind.ORACLE, null, "T", null, List.of());
      throw new AssertionError("예외가 나야 함");
    } catch (IllegalArgumentException expected) { }
    // 만든 SELECT 는 표 하나 조회라 결과 칸 고치기 조건에도 맞는다.
    eq(SqlText.notEditable(FollowRef.select(DbKind.ORACLE, null, "TEAM", null, List.of("ORG_CD", "TEAM_NO"))), null);

    // 전체 읽기: PK 가 모두 결과에 있고 값이 있을 때만.
    List<Meta.Column> defs = List.of(col("DOC_ID", true), col("SEQ", true), col("BODY", false));
    List<String> docColumns = List.of("doc_id", "seq", "body");
    List<String> types = List.of("NUMBER", "NUMBER", "CLOB");
    FollowRef.CellQuery q = FollowRef.cellQuery(DbKind.ORACLE, "DOC", defs, docColumns, types, Arrays.asList(5, 2, "x"), 2);
    eq(q.sql(), "SELECT\n       BODY\n  FROM DOC\n WHERE DOC_ID = ?\n   AND SEQ = ?");
    eq(q.binds(), List.of(5, 2));
    // 문장에 스키마를 붙여 썼으면 다시 읽는 문장에도 붙인다.
    eq(FollowRef.cellQuery(DbKind.ORACLE, "HR", "DOC", defs, docColumns, types, Arrays.asList(5, 2, "x"), 2).sql(),
       "SELECT\n       BODY\n  FROM HR.DOC\n WHERE DOC_ID = ?\n   AND SEQ = ?");
    eq(FollowRef.cellQuery(DbKind.ORACLE, "DOC", defs, docColumns, types, Arrays.asList(5, null, "x"), 2), null);
    eq(FollowRef.cellQuery(DbKind.ORACLE, "DOC", defs, List.of("doc_id", "body"), List.of("NUMBER", "CLOB"), Arrays.asList(5, "x"), 1), null);
    eq(FollowRef.cellQuery(DbKind.ORACLE, "DOC", List.of(col("BODY", false)), List.of("body"), List.of("CLOB"), Arrays.asList("x"), 0), null);
    eq(FollowRef.cellQuery(DbKind.ORACLE, "DOC", defs, List.of("doc_id", "seq", "other"), types, Arrays.asList(5, 2, "x"), 2), null);

    // PK 사전 쿼리: 바인드 하나, 프로젝트 SQL 모양(쉼표 줄 앞 · 구식 조인).
    for (DbKind kind : DbKind.values()) {
      String sql = Meta.primaryKeySql(kind);
      eq(sql.chars().filter(ch -> ch == '?').count(), 1L);
      eq(sql.toUpperCase(java.util.Locale.ROOT).contains(" JOIN "), false);
      eq(sql.contains("\n     , "), true);
    }
    System.out.println("FollowRefCheck 통과");
  }
}
