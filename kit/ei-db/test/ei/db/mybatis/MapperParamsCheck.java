package ei.db.mybatis;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** #{} 필드 검사 순수 논리 자체 점검: java -cp out:test-out ei.db.mybatis.MapperParamsCheck */
public class MapperParamsCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static final String XML = """
      <mapper namespace="com.x.ProductMapper">
        <select id="byDto" parameterType="com.x.ProductDto">
          <bind name="kw" value="'%' + productName + '%'"/>
          SELECT * FROM product
           WHERE product_id = #{ productId }
             AND product_nm = #{productNm, jdbcType=VARCHAR}
             AND name LIKE #{kw}
             AND db = #{_databaseId}
             AND sub = #{owner.name}
          <foreach collection="ids" item="x" index="i" open="(" separator="," close=")">#{x.id} #{i} #{badInLoop}</foreach>
          <![CDATA[ AND qty < #{qtyy} ]]>
        </select>
        <select id="byMap" parameterType="map">SELECT #{anything}</select>
        <select id="byJavaMap" parameterType="java.util.Map">SELECT #{anything}</select>
        <select id="byInt" parameterType="int">SELECT #{anything}</select>
        <select id="none">SELECT #{anything}</select>
        <update id="unknown" parameterType="NotInProject">SET a = #{anything}</update>
      </mapper>
      """;

  public static void main(String[] args) {
    MapperDoc doc = MapperDoc.parse(XML);
    eq(MapperParams.checkedType(doc.statement("byDto")), "com.x.ProductDto");
    eq(MapperParams.checkedType(doc.statement("byMap")), null);
    eq(MapperParams.checkedType(doc.statement("byJavaMap")), null);
    eq(MapperParams.checkedType(doc.statement("byInt")), null);
    eq(MapperParams.checkedType(doc.statement("none")), null);

    List<MapperParams.Ref> refs = MapperParams.refs(XML, doc.statement("byDto"));
    eq(refs.stream().map(MapperParams.Ref::name).toList(), List.of("productId", "productNm", "owner", "badInLoop", "qtyy"));
    for (MapperParams.Ref r : refs) eq(XML.substring(r.start(), r.end()), r.name());

    Set<String> dto = Set.of("productId", "productName", "owner");
    List<MapperParams.Miss> misses = MapperParams.check(XML, t -> t.equals("com.x.ProductDto") ? dto : null);
    eq(misses.stream().map(MapperParams.Miss::message).toList(), List.of(
        "ProductDto 에 productNm 필드가 없습니다",
        "ProductDto 에 badInLoop 필드가 없습니다",
        "ProductDto 에 qtyy 필드가 없습니다"));
    eq(XML.substring(misses.get(0).start(), misses.get(0).end()), "productNm");

    eq(MapperParams.isBuiltinType("_int"), true);
    eq(MapperParams.isBuiltinType("java.util.HashMap"), true);
    eq(MapperParams.isBuiltinType("ProductDto"), false);
    System.out.println("MapperParamsCheck 통과");
  }
}
