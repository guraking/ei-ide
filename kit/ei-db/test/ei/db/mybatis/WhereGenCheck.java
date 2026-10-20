package ei.db.mybatis;

import java.util.List;
import java.util.Objects;

/** 동적 조건 만들기 순수 논리 자체 점검: java -cp out:test-out ei.db.mybatis.WhereGenCheck */
public class WhereGenCheck {
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

  static String apply(String text, WhereGen.Insert in) {
    return text.substring(0, in.offset()) + in.text() + text.substring(in.offset());
  }

  public static void main(String[] args) {
    // 칼럼 이름
    eq(WhereGen.column("userName"), "USER_NAME");
    eq(WhereGen.column("userID"), "USER_ID");
    eq(WhereGen.column("htmlURLPath"), "HTML_URL_PATH");
    eq(WhereGen.column("addr1"), "ADDR1");
    eq(WhereGen.column("addr1Detail"), "ADDR1_DETAIL");
    eq(WhereGen.column("id"), "ID");
    eq(WhereGen.validName("USER_NAME"), true);
    eq(WhereGen.validName("u"), true);
    eq(WhereGen.validName(""), false);
    eq(WhereGen.validName("1A"), false);
    eq(WhereGen.validName("A B"), false);

    // 별칭 짐작
    eq(WhereGen.guessAlias("SELECT A FROM TB_USER u, TB_DEPT d WHERE 1=1"), "u");
    eq(WhereGen.guessAlias("select a from tb_user AS usr"), "usr");
    eq(WhereGen.guessAlias("SELECT A FROM TB_USER WHERE 1=1"), "");
    eq(WhereGen.guessAlias("SELECT A FROM TB_USER"), "");
    eq(WhereGen.guessAlias("SELECT A FROM SCH.TB_USER x"), "x");
    eq(WhereGen.guessAlias("UPDATE TB_USER u SET A = 1"), "u");
    eq(WhereGen.guessAlias("UPDATE TB_USER SET A = 1"), "");
    eq(WhereGen.guessAlias("DELETE FROM TB_USER t"), "t");
    eq(WhereGen.guessAlias("SELECT 1"), "");

    // if 하나
    WhereGen.Cond name = new WhereGen.Cond("userName", "USER_NAME", "java.lang.String", WhereGen.EQ);
    eq(WhereGen.ifBlock(name, "u", "      "),
        "      <if test=\"userName != null and userName != ''\">\n"
      + "        AND u.USER_NAME = #{userName}\n"
      + "      </if>\n");
    WhereGen.Cond like = new WhereGen.Cond("userName", "USER_NAME", "String", WhereGen.LIKE);
    eq(WhereGen.ifBlock(like, "", ""),
        "<if test=\"userName != null and userName != ''\">\n"
      + "  AND USER_NAME LIKE '%' || #{userName} || '%'\n"
      + "</if>\n");
    WhereGen.Cond ge = new WhereGen.Cond("amt", "AMT", "java.math.BigDecimal", WhereGen.GE);
    eq(WhereGen.ifBlock(ge, "o", ""),
        "<if test=\"amt != null\">\n"
      + "  AND o.AMT <![CDATA[ >= ]]> #{amt}\n"
      + "</if>\n");
    WhereGen.Cond le = new WhereGen.Cond("age", "AGE", "int", WhereGen.LE);
    eq(WhereGen.ifBlock(le, "o", "").contains("AND o.AGE <![CDATA[ <= ]]> #{age}"), true);
    fails(() -> WhereGen.ifBlock(new WhereGen.Cond("a", "A", "int", "<>"), "", ""), "모르는 비교 방식");

    // where 없는 문장: 커서 줄 뒤에 넣는다
    String xml = """
        <mapper namespace="com.x.UserMapper">
          <select id="selectUserList" parameterType="userDto" resultType="userDto">
            SELECT A
              FROM TB_USER u
          </select>
          <update id="updateUser">
            UPDATE TB_USER u SET A = 1
            <where>
              <if test="id != null">
                AND u.ID = #{id}
              </if>
            </where>
          </update>
          <select id="oneLine">SELECT 1 FROM DUAL</select>
        </mapper>
        """;
    MapperDoc doc = MapperDoc.parse(xml);
    MapperDoc.Node sel = doc.statement("selectUserList");
    int caret = xml.indexOf("TB_USER u") + 3;
    String out = apply(xml, WhereGen.insertion(xml, sel, caret, List.of(name), "u"));
    eq(out.substring(out.indexOf("      FROM TB_USER u\n"), out.indexOf("  </select>") + "  </select>".length()),
        "      FROM TB_USER u\n"
      + "    <where>\n"
      + "      <if test=\"userName != null and userName != ''\">\n"
      + "        AND u.USER_NAME = #{userName}\n"
      + "      </if>\n"
      + "    </where>\n"
      + "  </select>");
    eq(MapperDoc.parse(out).statement("selectUserList") != null, true);

    // 빈 줄 위 커서: 그 줄 앞에 넣는다
    String blank = xml.replace("      FROM TB_USER u\n", "      FROM TB_USER u\n\n");
    int bc = blank.indexOf("TB_USER u\n\n") + "TB_USER u\n".length();
    WhereGen.Insert bi = WhereGen.insertion(blank, MapperDoc.parse(blank).statement("selectUserList"), bc, List.of(name), "u");
    eq(bi.offset(), bc);
    eq(bi.text().startsWith("    <where>\n"), true);

    // 커서가 여는 태그 안이면 본문 첫 줄로 좁힌다
    WhereGen.Insert top = WhereGen.insertion(xml, sel, sel.start + 3, List.of(name), "u");
    eq(top.offset(), xml.indexOf("    SELECT A"));

    // 이미 where 가 있으면 그 끝에 if 만
    MapperDoc.Node upd = doc.statement("updateUser");
    String out2 = apply(xml, WhereGen.insertion(xml, upd, upd.start + 30, List.of(ge), "u"));
    eq(out2.contains(
        "        AND u.ID = #{id}\n"
      + "      </if>\n"
      + "      <if test=\"amt != null\">\n"
      + "        AND u.AMT <![CDATA[ >= ]]> #{amt}\n"
      + "      </if>\n"
      + "    </where>\n"), true);
    eq(out2.indexOf("<where>"), out2.lastIndexOf("<where>"));

    // 한 줄 문장: 닫는 태그 앞에서 줄을 바꿔 넣는다
    MapperDoc.Node one = doc.statement("oneLine");
    String out3 = apply(xml, WhereGen.insertion(xml, one, one.end - 2, List.of(name), ""));
    eq(out3.contains("<select id=\"oneLine\">SELECT 1 FROM DUAL\n    <where>\n"), true);
    eq(out3.contains("    </where>\n  </select>"), true);

    fails(() -> WhereGen.insertion(xml, sel, caret, List.of(), "u"), "고른 조건이 없습니다");
    String open = "<mapper namespace=\"a\"><select id=\"x\">SELECT 1";
    MapperDoc od = MapperDoc.parse(open);
    fails(() -> WhereGen.insertion(open, od.statement("x"), open.length(), List.of(name), ""), "닫는 태그");

    // 위치 도움
    eq(WhereGen.openEnd("<a b=\"x>y\">z", 0), 11);
    eq(WhereGen.openEnd("<a/>", 0), -1);
    eq(WhereGen.indentAt("x\n   <a>", 6), "   ");
    System.out.println("WhereGenCheck 통과");
  }
}
