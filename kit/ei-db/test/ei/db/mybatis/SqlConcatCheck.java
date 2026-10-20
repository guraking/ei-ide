package ei.db.mybatis;

import java.io.StringReader;
import java.util.List;
import java.util.Objects;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** 문자열 SQL 정리 자체 점검(SqlFormatter 와 함께 컴파일): java -cp out:test-out ei.db.mybatis.SqlConcatCheck */
public class SqlConcatCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }
  static void ok(boolean v, String what) { if (!v) throw new AssertionError(what); }

  static Document parse(String xml) throws Exception {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
  }

  public static void main(String[] args) throws Exception {
    eq(SqlConcat.bindName("userId"), "userId");
    eq(SqlConcat.bindName("this.userId"), "userId");
    eq(SqlConcat.bindName("param.getUserId()"), "userId");
    eq(SqlConcat.bindName("getUserId()"), "userId");
    eq(SqlConcat.bindName("dto.getUser().getDeptCd()"), "deptCd");
    eq(SqlConcat.bindName("form.isUse()"), "use");
    eq(SqlConcat.bindName("map.get(\"user_id\")"), "mapGetUser_id");
    eq(SqlConcat.bindName("ids[0]"), "ids");
    eq(SqlConcat.bindName("1 + 2"), "param");

    String sql = SqlConcat.sql(List.of(
        SqlConcat.Piece.literal("select a.USER_ID, a.USER_NM from TB_USER a, TB_DEPT b where a.DEPT_CD = b.DEPT_CD(+) and a.USER_ID = "),
        SqlConcat.Piece.expr("dto.getUserId()"),
        SqlConcat.Piece.literal(" and a.AGE >= "),
        SqlConcat.Piece.expr("age"),
        SqlConcat.Piece.literal(" and a.USE_YN = 'Y'")));
    eq(sql, "select a.USER_ID, a.USER_NM from TB_USER a, TB_DEPT b where a.DEPT_CD = b.DEPT_CD(+) and a.USER_ID = #{userId} and a.AGE >= #{age} and a.USE_YN = 'Y'");

    String formatted = SqlConcat.format(sql);
    if (args.length > 0) System.out.println(formatted);
    eq(formatted, """
        SELECT
               a.USER_ID
             , a.USER_NM
          FROM TB_USER a, TB_DEPT b
         WHERE a.DEPT_CD = b.DEPT_CD(+)
           AND a.USER_ID = #{userId}
           AND a.AGE >= #{age}
           AND a.USE_YN = 'Y'""");

    eq(SqlConcat.statementKind("-- 머리\n/* x */ SELECT 1"), "select");
    eq(SqlConcat.statementKind("with t as (select 1 from dual) select * from t"), "select");
    eq(SqlConcat.statementKind(" insert into T values (1)"), "insert");
    eq(SqlConcat.statementKind("UPDATE T SET A = 1"), "update");
    eq(SqlConcat.statementKind("delete from T"), "delete");
    eq(SqlConcat.statementKind("merge into T using S on (1 = 1)"), "update");
    eq(SqlConcat.statementKind("{call P(#{x})}"), "select");

    String xml = SqlConcat.xml(formatted, "selectUser");
    if (args.length > 0) System.out.println(xml);
    eq(xml, """
        <select id="selectUser">
            <![CDATA[
            SELECT
                   a.USER_ID
                 , a.USER_NM
              FROM TB_USER a, TB_DEPT b
             WHERE a.DEPT_CD = b.DEPT_CD(+)
               AND a.USER_ID = #{userId}
               AND a.AGE >= #{age}
               AND a.USE_YN = 'Y'
            ]]>
        </select>
        """);
    ok(parse(xml).getDocumentElement().getTextContent().contains("a.AGE >= #{age}"), "CDATA 본문");

    String upd = SqlConcat.xml("UPDATE TB_USER\n   SET NM = #{nm} || '&'\n WHERE ID = #{id}", "");
    eq(upd, """
        <update id="statement">
            UPDATE TB_USER
               SET NM = #{nm} || '&amp;'
             WHERE ID = #{id}
        </update>
        """);
    eq(parse(upd).getDocumentElement().getTextContent().strip(), "UPDATE TB_USER\n       SET NM = #{nm} || '&'\n     WHERE ID = #{id}");

    String tricky = SqlConcat.xml("SELECT ']]>' FROM DUAL WHERE 1 < 2", "x");
    ok(parse(tricky).getDocumentElement().getTextContent().contains("SELECT ']]>' FROM DUAL WHERE 1 < 2"), "]]> 나눠 쓰기");
    System.out.println("SqlConcatCheck 통과");
  }
}
