package ei.db.mybatis;

import java.util.Objects;

/** 페이징 쿼리 만들기 순수 논리 자체 점검: java -cp out:test-out ei.db.mybatis.PagingGenCheck */
public class PagingGenCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static void fails(Runnable r, String part) {
    try {
      r.run();
    } catch (IllegalArgumentException ex) {
      if (!ex.getMessage().contains(part)) throw new AssertionError("메시지가 다름: " + ex.getMessage());
      return;
    }
    throw new AssertionError("예외가 나야 함: " + part);
  }

  public static void main(String[] args) {
    String xml = """
        <mapper namespace="com.x.UserMapper">
          <select id="selectUserList" parameterType="userDto" resultMap="userMap">
            SELECT
                   u.USER_ID
                 , u.USER_NAME
              FROM TB_USER u
            <where>
              <if test="userName != null">
                AND u.USER_NAME = #{userName}
              </if>
            </where>
             ORDER BY u.USER_ID
          </select>
          <select id="selectDone">SELECT 1 FROM DUAL</select>
          <select id="selectDonePage">SELECT 1 FROM DUAL</select>
          <insert id="insertUser">INSERT</insert>
        </mapper>
        """;
    MapperDoc doc = MapperDoc.parse(xml);
    MapperDoc.Node sel = doc.statement("selectUserList");
    PagingGen.Result r = PagingGen.generate(xml, doc, sel);
    eq(r.pageId(), "selectUserListPage");
    eq(r.countId(), "selectUserListCount");
    eq(r.offset(), sel.end);
    String expected = "\n"
        + "  <select id=\"selectUserListPage\" parameterType=\"userDto\" resultMap=\"userMap\">\n"
        + "    SELECT\n"
        + "           *\n"
        + "      FROM (\n"
        + "            SELECT\n"
        + "                   ROWNUM AS RNUM\n"
        + "                 , a.*\n"
        + "              FROM (\n"
        + "                    SELECT\n"
        + "                           u.USER_ID\n"
        + "                         , u.USER_NAME\n"
        + "                      FROM TB_USER u\n"
        + "                    <where>\n"
        + "                      <if test=\"userName != null\">\n"
        + "                        AND u.USER_NAME = #{userName}\n"
        + "                      </if>\n"
        + "                    </where>\n"
        + "                     ORDER BY u.USER_ID\n"
        + "                   ) a\n"
        + "             WHERE ROWNUM <![CDATA[<=]]> #{endRow}\n"
        + "           )\n"
        + "     WHERE RNUM <![CDATA[>]]> #{startRow}\n"
        + "  </select>\n"
        + "  <select id=\"selectUserListCount\" parameterType=\"userDto\" resultType=\"int\">\n"
        + "    SELECT\n"
        + "           COUNT(*)\n"
        + "      FROM (\n"
        + "            SELECT\n"
        + "                   u.USER_ID\n"
        + "                 , u.USER_NAME\n"
        + "              FROM TB_USER u\n"
        + "            <where>\n"
        + "              <if test=\"userName != null\">\n"
        + "                AND u.USER_NAME = #{userName}\n"
        + "              </if>\n"
        + "            </where>\n"
        + "           )\n"
        + "  </select>";
    eq(r.text(), expected);
    String merged = xml.substring(0, r.offset()) + r.text() + xml.substring(r.offset());
    MapperDoc after = MapperDoc.parse(merged);
    eq(after.statement("selectUserListPage") != null && after.statement("selectUserListCount") != null, true);
    eq(after.statements.size(), doc.statements.size() + 2);

    // 같은 id 가 있으면 만들지 않는다 · select 만
    fails(() -> PagingGen.generate(xml, doc, doc.statement("selectDone")), "이미 selectDonePage");
    fails(() -> PagingGen.generate(xml, doc, doc.statement("insertUser")), "select 문장에서만");
    PagingGen.Result one = PagingGen.generate(
        "<mapper namespace=\"a\">\n  <select id='q' resultType=\"int\">SELECT 1 FROM DUAL</select>\n</mapper>",
        MapperDoc.parse("<mapper namespace=\"a\">\n  <select id='q' resultType=\"int\">SELECT 1 FROM DUAL</select>\n</mapper>"),
        MapperDoc.parse("<mapper namespace=\"a\">\n  <select id='q' resultType=\"int\">SELECT 1 FROM DUAL</select>\n</mapper>").statement("q"));
    eq(one.text().contains("  <select id='qPage' resultType=\"int\">\n"), true);
    eq(one.text().contains("  <select id=\"qCount\" resultType=\"int\">\n"), true);

    // 맨 바깥 ORDER BY 만 뺀다
    eq(PagingGen.withoutOuterOrderBy("SELECT A FROM T ORDER BY A"), "SELECT A FROM T");
    eq(PagingGen.withoutOuterOrderBy("SELECT A FROM (SELECT A FROM T ORDER BY A) x"), "SELECT A FROM (SELECT A FROM T ORDER BY A) x");
    eq(PagingGen.withoutOuterOrderBy("SELECT 'ORDER BY' FROM T"), "SELECT 'ORDER BY' FROM T");
    eq(PagingGen.withoutOuterOrderBy("SELECT A FROM T -- ORDER BY A\n"), "SELECT A FROM T -- ORDER BY A\n");
    eq(PagingGen.withoutOuterOrderBy("SELECT A FROM T <if test=\"s\">ORDER BY A</if>"), "SELECT A FROM T <if test=\"s\">ORDER BY A</if>");
    eq(PagingGen.withoutOuterOrderBy("SELECT A FROM T\n ORDER BY <if test=\"s\">${s}</if>"), "SELECT A FROM T");
    eq(PagingGen.withoutOuterOrderBy("<![CDATA[SELECT A FROM T WHERE A < 1 ORDER BY A]]>"), "<![CDATA[SELECT A FROM T WHERE A < 1]]>");
    eq(PagingGen.withoutOuterOrderBy("SELECT A FROM T <include refid=\"w\"/> ORDER SIBLINGS BY A"), "SELECT A FROM T <include refid=\"w\"/>");
    eq(PagingGen.withoutOuterOrderBy("SELECT REORDER BY FROM T"), "SELECT REORDER BY FROM T");
    eq(PagingGen.withoutOuterOrderBy("SELECT A FROM T <!-- ORDER BY A -->"), "SELECT A FROM T <!-- ORDER BY A -->");

    // 인터페이스 메서드
    eq(PagingGen.pageMethod("com.x.UserDto", "selectUserListPage", "(UserDto userDto)"),
        "java.util.List<com.x.UserDto> selectUserListPage(UserDto userDto);");
    eq(PagingGen.countMethod("selectUserListCount", "(UserDto userDto)"), "int selectUserListCount(UserDto userDto);");
    eq(PagingGen.reindent("\n    a\n\n      b\n  ", "  "), "  a\n\n    b\n");
    System.out.println("PagingGenCheck 통과");
  }
}
