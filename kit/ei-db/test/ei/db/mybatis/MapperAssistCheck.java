package ei.db.mybatis;

import java.util.List;
import java.util.Objects;

/** #{} · AS 자동완성 자리 찾기와 resultMap 만들기 순수 논리 자체 점검: java -cp out:test-out ei.db.mybatis.MapperAssistCheck */
public class MapperAssistCheck {
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

  /** 글의 | 자리를 커서로 보고 지운 글과 커서 위치를 돌려준다. */
  record At(String text, int offset) {}

  static At at(String s) { return new At(s.replace("|", ""), s.indexOf('|')); }

  static int param(String s) { At a = at(s); return MapperParams.paramPrefixStart(a.text(), a.offset()); }

  static int alias(String s) { At a = at(s); return ResultColumns.aliasPrefixStart(a.text(), a.offset()); }

  public static void main(String[] args) {
    eq(param("a = #{|"), 6);
    eq(param("a = #{us|"), 6);
    eq(param("a = #{ us|"), 7);
    eq(param("a = ${col|"), 6);
    eq(param("a = #{user.na|"), -1);
    eq(param("a = #{id, jdbc|"), -1);
    eq(param("a = {x|"), -1);
    eq(param("|"), -1);

    eq(alias("SELECT a.ID AS |"), 15);
    eq(alias("SELECT a.ID as ord|"), 15);
    eq(alias("SELECT a.ID AS\n       |"), 22);
    eq(alias("SELECT a.ID AS|"), -1);
    eq(alias("SELECT a.HAS |"), -1);
    eq(alias("SELECT a.ID |"), -1);

    String xml = """
        <mapper namespace="com.x.OrderMapper">
          <sql id="cols">o.ORDER_ID, o.QTY</sql>
          <select id="selectOrder" parameterType="orderDto" resultType="com.x.OrderDto">
            SELECT <include refid="cols"/>
                 , o.ORDER_NM AS orderName
                 , o.CUST_NO
              FROM TB_ORDER o
            <where>
              <foreach collection="ids" item="x" index="i">#{x}</foreach>
              <bind name="kw" value="'%' + orderName + '%'"/>
              AND o.ORDER_ID = #{orderId}
            </where>
          </select>
          <resultMap id="takenResultMap" type="X"/>
          <select id="taken" resultType="X">SELECT A FROM T</select>
          <select id="star" resultType="X">SELECT * FROM T</select>
          <select id="mapped" resultMap="m">SELECT A FROM T</select>
        </mapper>
        """;
    MapperDoc doc = MapperDoc.parse(xml);
    MapperDoc.Node s = doc.statement("selectOrder");
    eq(ResultColumns.of(doc.plainText(s)), List.of("ORDER_ID", "QTY", "ORDERNAME", "CUST_NO"));
    eq(MapperParams.localNames(s), List.of("x", "i", "kw"));
    int ref = xml.indexOf("#{orderId}") + 4;
    eq(MapperParams.refAt(xml, s, ref).name(), "orderId");
    eq(MapperParams.refAt(xml, s, xml.indexOf("#{x}") + 2), null);

    ResultMapGen.Result r = ResultMapGen.generate(xml, doc, s, List.of("orderId", "orderName", "quantity", "custNo"));
    eq(r.id(), "selectOrderResultMap");
    eq(r.paired(), 3);
    eq(r.unmatched(), List.of("QTY"));
    eq(r.insertText(), """
          <resultMap id="selectOrderResultMap" type="com.x.OrderDto">
            <result property="orderId" column="ORDER_ID"/>
            <result property="orderName" column="ORDERNAME"/>
            <result property="custNo" column="CUST_NO"/>
            <!-- 받을 필드 없음: QTY (비슷한 필드: quantity) -->
          </resultMap>

        """);
    eq(xml.substring(r.attrStart(), r.attrEnd()), "resultType=\"com.x.OrderDto\"");
    eq(r.attrText(), "resultMap=\"selectOrderResultMap\"");
    eq(r.insertAt(), xml.indexOf("  <select id=\"selectOrder\""));
    String out = xml.substring(0, r.attrStart()) + r.attrText() + xml.substring(r.attrEnd());
    out = out.substring(0, r.insertAt()) + r.insertText() + out.substring(r.insertAt());
    MapperDoc after = MapperDoc.parse(out);
    eq(after.statement("selectOrder").attr("resultMap"), "selectOrderResultMap");
    eq(after.statement("selectOrder").attr("resultType"), null);
    eq(MapperRefs.declOffset(after, MapperRefs.RESULT_MAP_TAG, "selectOrderResultMap") >= 0, true);

    fails(() -> ResultMapGen.generate(xml, doc, doc.statement("taken"), List.of("a")), "이미 takenResultMap");
    fails(() -> ResultMapGen.generate(xml, doc, doc.statement("star"), List.of("a")), "SELECT *");
    fails(() -> ResultMapGen.generate(xml, doc, doc.statement("mapped"), List.of("a")), "이미 resultMap");
    System.out.println("MapperAssistCheck 통과");
  }
}
