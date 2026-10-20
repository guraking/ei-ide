package ei.db.mybatis;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** 서비스 호출 코드 순수 논리 자체 점검: java -cp out ei.db.mybatis.ServiceCallCheck */
public class ServiceCallCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static final String XML = """
      <mapper namespace="com.x.UserMapper">
        <select id="selectUserList" parameterType="com.x.UserDto" resultType="com.x.UserDto">
          SELECT * FROM TB_USER WHERE USER_ID = #{userId} AND DEPT = #{dept.code} AND USER_ID2 = #{userId}
          <if test="userNm != null">AND USER_NM = #{userNm}</if>
        </select>
        <select id="countUser" parameterType="map" resultType="int">SELECT COUNT(*) FROM TB_USER WHERE A = #{a} AND B = #{b.c}</select>
        <select id="selectUser" parameterType="string" resultType="com.x.UserDto">SELECT * FROM TB_USER WHERE USER_ID = #{userId}</select>
        <delete id="deleteUser" parameterType="int">DELETE FROM TB_USER WHERE SEQ = #{seq}</delete>
        <update id="touchAll">UPDATE TB_USER SET D = SYSDATE</update>
      </mapper>
      """;

  static String code(MapperDoc doc, String id) {
    MapperDoc.Node s = doc.statement(id);
    ServiceCode.Sig sig = ServiceCode.fromXml(doc, s, UnaryOperator.identity());
    return ServiceCall.code(s.name, id, sig, DynamicSql.paramNames(doc, s), ServiceCode.mapperField(doc.shortNamespace()));
  }

  public static void main(String[] args) {
    MapperDoc doc = MapperDoc.parse(XML);
    eq(code(doc, "selectUserList"), """
        UserDto param = new UserDto();
        param.setUserId(null);
        param.setDept(null);
        param.setUserNm(null);
        List<UserDto> list = userMapper.selectUserList(param);
        """);
    eq(code(doc, "countUser"), """
        Map<String, Object> param = new HashMap<>();
        param.put("a", null);
        param.put("b", null);
        int result = userMapper.countUser(param);
        """);
    eq(code(doc, "selectUser"), """
        String userId = null;
        List<UserDto> list = userMapper.selectUser(userId);
        """);
    eq(code(doc, "deleteUser"), """
        int seq = 0;
        int count = userMapper.deleteUser(seq);
        """);
    eq(code(doc, "touchAll"), "int count = userMapper.touchAll();\n");

    // 인터페이스 모양: 단건 반환 · 여러 매개변수 · void.
    ServiceCode.Sig one = new ServiceCode.Sig("com.x.UserDto",
        List.of(new ServiceCode.Param("java.lang.String", "userId"), new ServiceCode.Param("com.x.DeptDto", "dept")), false);
    eq(ServiceCall.code("select", "find", one, List.of("userId", "dept.code"), "userMapper"), """
        String userId = null;
        DeptDto dept = new DeptDto();
        UserDto result = userMapper.find(userId, dept);
        """);
    ServiceCode.Sig none = new ServiceCode.Sig("void", List.of(new ServiceCode.Param("java.util.HashMap<java.lang.String,java.lang.Object>", "m")), false);
    eq(ServiceCall.code("insert", "add", none, List.of(), "m2"), """
        Map<String, Object> param = new HashMap<>();
        m2.add(param);
        """);
    System.out.println("ServiceCallCheck 통과");
  }
}
