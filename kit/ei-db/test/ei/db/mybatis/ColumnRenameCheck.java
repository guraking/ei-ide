package ei.db.mybatis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 칼럼 이름 바꾸기 순수 논리 자체 점검: java -cp out ei.db.mybatis.ColumnRenameCheck */
public class ColumnRenameCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static final String XML = """
      <mapper namespace="com.x.UserMapper">
        <resultMap id="userMap" type="com.x.UserDto">
          <id column="USER_ID" property="userId"/>
          <result column="USER_NM" property="userNm"/>
          <association property="dept" javaType="com.x.DeptDto">
            <result column="user_nm" property="userNm"/>
          </association>
        </resultMap>
        <sql id="cols">u.USER_NM, u.USER_NM_OLD</sql>
        <!-- USER_NM 은 주석이라 그대로 -->
        <select id="selectUser" parameterType="com.x.UserDto" resultMap="userMap">
          SELECT <include refid="cols"/>
               , 'USER_NM' AS lit
               , ${USER_NM} AS dollar
            FROM TB_USER u
           WHERE u.user_nm = #{userNm, jdbcType=VARCHAR}
             AND X_USER_NM = #{owner.userNm}
             <if test="userNm != null"><![CDATA[ AND u.User_Nm <> #{ userNm } ]]></if>
             AND a &lt; b
        </select>
        <update id="updateOther" parameterType="com.x.OtherDto">
          UPDATE TB_OTHER SET USER_NM = #{userNm}
        </update>
      </mapper>
      """;

  public static void main(String[] args) {
    eq(ColumnRename.validate("USER_NM", "USER_NAME", ""), null);
    eq(ColumnRename.validate("1A", "B", null) != null, true);
    eq(ColumnRename.validate("A", "a", null), "옛 이름과 새 이름이 같습니다.");
    eq(ColumnRename.validate("A", "B-C", null) != null, true);
    eq(ColumnRename.validate("A", "B", "TB.X") != null, true);
    eq(ColumnRename.validate("A$#", "B_1", "TB_USER"), null);

    eq(ColumnRename.caseLike("USER_NM", "user_name"), "USER_NAME");
    eq(ColumnRename.caseLike("user_nm", "USER_NAME"), "user_name");
    eq(ColumnRename.caseLike("User_Nm", "USER_NAME"), "USER_NAME");
    eq(ColumnRename.wordAt("a = USER_NM)", 6), "USER_NM");
    eq(ColumnRename.wordAt("a = USER_NM)", 11), "USER_NM");
    eq(ColumnRename.wordAt("  ", 1), "");

    // 표 이름 없음, DTO 도: 모든 문장 · resultMap.
    List<ColumnRename.Hit> all = ColumnRename.find(XML, "USER_NM", "USER_NAME", "", true);
    List<String> got = new ArrayList<>();
    for (ColumnRename.Hit h : all) got.add(h.kind() + ":" + XML.substring(h.start(), h.end()) + ">" + h.replacement() + ":" + h.dtoType());
    eq(got, List.of(
        "COLUMN:USER_NM>USER_NAME:null",
        "PROPERTY:userNm>userName:com.x.UserDto",
        "COLUMN:user_nm>user_name:null",
        "PROPERTY:userNm>userName:com.x.DeptDto",
        "COLUMN:USER_NM>USER_NAME:null",
        "COLUMN:user_nm>user_name:null",
        "PARAM:userNm>userName:com.x.UserDto",
        "COLUMN:User_Nm>USER_NAME:null",
        "PARAM:userNm>userName:com.x.UserDto",
        "COLUMN:USER_NM>USER_NAME:null",
        "PARAM:userNm>userName:com.x.OtherDto"));

    String changed = ColumnRename.apply(XML, all);
    eq(changed.contains("<result column=\"USER_NAME\" property=\"userName\"/>"), true);
    eq(changed.contains("u.USER_NAME, u.USER_NM_OLD"), true);
    eq(changed.contains("<!-- USER_NM 은 주석이라 그대로 -->"), true);
    eq(changed.contains("'USER_NM' AS lit"), true);
    eq(changed.contains("${USER_NM}"), true);
    eq(changed.contains("WHERE u.user_name = #{userName, jdbcType=VARCHAR}"), true);
    eq(changed.contains("AND X_USER_NM = #{owner.userNm}"), true);
    eq(changed.contains("<if test=\"userNm != null\"><![CDATA[ AND u.USER_NAME <> #{ userName } ]]></if>"), true);
    eq(changed.contains("a &lt; b"), true);

    // 표 이름: TB_USER 문장, 그것이 포함하는 조각, 쓰는 resultMap 만. TB_OTHER 문장은 빠진다.
    List<ColumnRename.Hit> user = ColumnRename.find(XML, "user_nm", "user_name", "tb_user", true);
    eq(user.size(), all.size() - 2);
    for (ColumnRename.Hit h : user) eq(h.dtoType() == null || !h.dtoType().equals("com.x.OtherDto"), true);

    // DTO 끔: 칼럼만.
    List<ColumnRename.Hit> cols = ColumnRename.find(XML, "USER_NM", "USER_NAME", null, false);
    for (ColumnRename.Hit h : cols) eq(h.kind(), ColumnRename.Kind.COLUMN);
    eq(cols.size(), 6);

    // 없는 표, 매퍼 아님, 닫히지 않은 #{.
    eq(ColumnRename.find(XML, "USER_NM", "X", "TB_NONE", true).isEmpty(), true);
    eq(ColumnRename.find("<beans/>", "USER_NM", "X", null, true).isEmpty(), true);
    String open = "<mapper namespace=\"a\"><select id=\"s\">SELECT USER_NM, #{x USER_NM</select></mapper>";
    eq(ColumnRename.find(open, "USER_NM", "X", null, true).size(), 1);
    System.out.println("ColumnRenameCheck 통과");
  }
}
