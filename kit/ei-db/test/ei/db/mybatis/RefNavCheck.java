package ei.db.mybatis;

import java.util.Objects;

/** 매퍼 참조 이동 순수 논리 자체 점검: java -cp out:test-out ei.db.mybatis.RefNavCheck */
public class RefNavCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static final String XML = """
      <mapper namespace="com.x.OrderMapper">
        <sql id="cols">a, b</sql>
        <resultMap id="base" type="Order"><id column="ID" property="id"/></resultMap>
        <resultMap id="detail" type="Order" extends="base">
          <association property="user" resultMap="com.x.UserMapper.user"/>
          <collection property="items" resultMap='item'/>
        </resultMap>
        <resultMap id="item" type="Item"/>
        <select id="find" resultMap="base, detail" resultType="Order">
          SELECT <include refid="cols"/>, <include refid="com.x.UserMapper.userCols"/> FROM ORDERS
        </select>
        <select id="plain" parameterType="base"/>
      </mapper>
      """;

  /** marker 의 첫 글자 뒤 shift 글자 위치. */
  static MapperRefs.Ref at(String marker, int shift) {
    int i = XML.indexOf(marker);
    if (i < 0) throw new AssertionError("표시 없음: " + marker);
    return MapperRefs.refAt(XML, i + shift);
  }

  public static void main(String[] args) {
    MapperDoc doc = MapperDoc.parse(XML);

    eq(at("refid=\"cols\"", 8), new MapperRefs.Ref("sql", null, "cols"));
    eq(at("refid=\"cols\"", 7), new MapperRefs.Ref("sql", null, "cols"));
    eq(at("refid=\"cols\"", 11), new MapperRefs.Ref("sql", null, "cols"));
    eq(at("refid=\"cols\"", 2), null);
    eq(at("refid=\"com.x.UserMapper.userCols", 20), new MapperRefs.Ref("sql", "com.x.UserMapper", "userCols"));

    eq(at("resultMap=\"base, detail\"", 12), new MapperRefs.Ref("resultMap", null, "base"));
    eq(at("resultMap=\"base, detail\"", 18), new MapperRefs.Ref("resultMap", null, "detail"));
    eq(at("resultMap=\"base, detail\"", 16), new MapperRefs.Ref("resultMap", null, "detail"));
    eq(at("extends=\"base\"", 10), new MapperRefs.Ref("resultMap", null, "base"));
    eq(at("resultMap=\"com.x.UserMapper.user\"", 14), new MapperRefs.Ref("resultMap", "com.x.UserMapper", "user"));
    eq(at("resultMap='item'", 12), new MapperRefs.Ref("resultMap", null, "item"));
    eq(at("resultType=\"Order\">", 13), null);
    eq(at("parameterType=\"base\"", 16), null);
    eq(at("type=\"Item\"", 7), null);
    eq(at("SELECT", 2), null);
    eq(MapperRefs.refAt(XML, 0), null);
    eq(MapperRefs.refAt(XML, XML.length()), null);
    eq(MapperRefs.refAt("<include refid=\"cut", 17), new MapperRefs.Ref("sql", null, "cut"));

    eq(MapperRefs.declOffset(doc, "sql", "cols"), XML.indexOf("<sql id=\"cols\""));
    eq(MapperRefs.declOffset(doc, "resultMap", "base"), XML.indexOf("<resultMap id=\"base\""));
    eq(MapperRefs.declOffset(doc, "resultMap", "item"), XML.indexOf("<resultMap id=\"item\""));
    eq(MapperRefs.declOffset(doc, "resultMap", "cols"), -1);
    eq(MapperRefs.declOffset(doc, "sql", "none"), -1);

    System.out.println("RefNavCheck 통과");
  }
}
