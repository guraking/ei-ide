package ei.db.mybatis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 「DB 바뀐 것 코드에 맞추기」 순수 논리 자체 점검: java -cp out ei.db.mybatis.ColumnSyncCheck */
public class ColumnSyncCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static void ok(boolean c, String what) { if (!c) throw new AssertionError(what); }

  static final Set<String> DB = Set.of("PRODUCT_ID", "PRODUCT_NAME", "UNIT_PRICE", "USE_YN", "NEW_COL");
  static final List<ColumnSync.NewColumn> ADD = List.of(new ColumnSync.NewColumn("NEW_COL", "newCol", "새 칼럼", "VARCHAR"));
  static final Set<String> NEW = Set.of("NEW_COL");

  /** mapper 안의 문장 · resultMap 마다 forStatement · forResultMap 을 부르고 모든 바꿀 것을 반영한 글과 결과를 준다. */
  static List<ColumnSync.Change> run(String xml, Set<String> db, List<ColumnSync.NewColumn> adds, Set<String> missingOk, Set<String> dropped) {
    MapperDoc doc = MapperDoc.parse(xml);
    MapperSqlScan.Result scan = MapperSqlScan.scan(xml, doc);
    List<ColumnSync.Change> out = new ArrayList<>();
    for (MapperDoc.Node s : doc.statements) out.addAll(ColumnSync.forStatement(xml, s, scan, "PRODUCT", db, adds, missingOk, dropped));
    for (MapperDoc.Node c : doc.root.children.get(0).children) {
      if ("resultMap".equals(c.name)) out.addAll(ColumnSync.forResultMap(xml, c, db, adds, dropped));
    }
    return out;
  }

  static String applied(String xml, List<ColumnSync.Change> changes) {
    List<ColumnSync.Change> chosen = new ArrayList<>();
    for (ColumnSync.Change c : changes) if (!c.edits().isEmpty()) chosen.add(c);
    return ColumnSync.apply(xml, ColumnSync.edits(chosen));
  }

  static String mapper(String body) {
    return "<mapper namespace=\"m.ProductMapper\">\n" + body + "</mapper>\n";
  }

  public static void main(String[] args) {
    // 1 우리 MapperGen 스타일(앞 쉼표, 별칭 p., 주석, <set><if> 한 줄, resultMap)
    String gen = mapper("""
          <resultMap id="productMap" type="m.ProductDto">
            <id     property="productId" column="PRODUCT_ID"/>
            <result property="productName" column="PRODUCT_NAME"/>
            <result property="useYn" column="USE_YN"/>
          </resultMap>
          <select id="selectProduct" resultMap="productMap">
            SELECT
                   p.PRODUCT_ID   /* 상품 ID */
                 , p.PRODUCT_NAME /* 상품명 */
                 , p.UNIT_PRICE
                 , p.USE_YN       /* 사용 */
              FROM PRODUCT p
             WHERE p.PRODUCT_ID = #{productId}
          </select>
          <insert id="insertProduct">
            INSERT INTO PRODUCT (
                   PRODUCT_ID
                 , PRODUCT_NAME
                 , UNIT_PRICE
                 , USE_YN
            ) VALUES (
                   #{productId}
                 , #{productName}
                 , #{unitPrice, jdbcType=NUMERIC}
                 , #{useYn}
            )
          </insert>
          <update id="updateProduct">
            UPDATE PRODUCT
            <set>
              <if test="productName != null and productName != ''">PRODUCT_NAME = #{productName},</if>
              <if test="unitPrice != null">UNIT_PRICE = #{unitPrice},</if>
              <if test="useYn != null">USE_YN = #{useYn},</if>
            </set>
             WHERE PRODUCT_ID = #{productId}
          </update>
        """);
    List<ColumnSync.Change> ch = run(gen, DB, ADD, NEW, Set.of());
    String out = applied(gen, ch);
    if (args.length > 0) System.out.println(out);
    ok(out.contains("         , p.USE_YN       /* 사용 */\n         , p.NEW_COL       /* 새 칼럼 */\n      FROM"), "SELECT 앞 쉼표 복제\n" + out);
    ok(out.contains("         , USE_YN\n         , NEW_COL\n    ) VALUES"), "INSERT 칼럼\n" + out);
    ok(out.contains("         , #{useYn}\n         , #{newCol}\n    )"), "INSERT 값\n" + out);
    ok(out.contains("<if test=\"useYn != null\">USE_YN = #{useYn},</if>\n      <if test=\"newCol != null\">NEW_COL = #{newCol},</if>\n    </set>"),
        "UPDATE if 한 줄\n" + out);
    ok(out.contains("<result property=\"useYn\" column=\"USE_YN\"/>\n    <result property=\"newCol\" column=\"NEW_COL\"/>\n  </resultMap>"),
        "resultMap\n" + out);
    for (ColumnSync.Change c : ch) ok(c.checked(), "모두 가진 목록은 기본 체크: " + c);
    eq(ch.size(), 4);

    // 2 쉼표 뒤 스타일, 마지막 항목에 쉼표 없음 → 마지막 앞에 넣는다(값도 같은 자리)
    String trail = mapper("""
          <insert id="ins">
            INSERT INTO PRODUCT (
              PRODUCT_ID,
              PRODUCT_NAME,
              USE_YN
            ) VALUES (
              #{productId},
              #{productName},
              #{useYn}
            )
          </insert>
          <update id="upd">
            UPDATE PRODUCT SET
              product_name = #{productName},
              use_yn = #{useYn}
            WHERE product_id = #{productId}
          </update>
          <select id="sel">
            SELECT product_id,
                   product_name AS productName,
                   use_yn
              FROM product
          </select>
        """);
    out = applied(trail, run(trail, DB, ADD, NEW, Set.of()));
    if (args.length > 0) System.out.println(out);
    ok(out.contains("      PRODUCT_NAME,\n      NEW_COL,\n      USE_YN\n"), "INSERT 칼럼 마지막 앞\n" + out);
    ok(out.contains("      #{productName},\n      #{newCol},\n      #{useYn}\n"), "INSERT 값 같은 차례\n" + out);
    ok(out.contains("      product_name = #{productName},\n      new_col = #{newCol},\n      use_yn = #{useYn}\n"), "UPDATE 소문자 · 앞에 넣기\n" + out);
    ok(out.contains("           product_name AS productName,\n           new_col,\n           use_yn\n"), "SELECT 쉼표 뒤\n" + out);

    // 3 별칭 AS 필드 · 다른 칼럼 이름의 일부 · CDATA · 대소문자
    String alias = mapper("""
          <select id="a">
            SELECT p.PRODUCT_NAME AS productName
                 , p.USE_YN AS useYn
              FROM PRODUCT p
             WHERE p.UNIT_PRICE <![CDATA[ >= ]]> #{unitPrice}
          </select>
        """);
    List<ColumnSync.Change> ac = run(alias, DB, ADD, NEW, Set.of());
    out = applied(alias, ac);
    ok(out.contains("         , p.USE_YN AS useYn\n         , p.NEW_COL AS newCol\n"), "별칭 필드\n" + out);
    ok(!ac.get(0).checked(), "칼럼을 다 갖지 않은 SELECT 는 체크 끔");

    // 4 복제할 수 없는 모양: 한 줄 SELECT → 손으로 넣기
    String flat = mapper("""
          <select id="flat">SELECT PRODUCT_ID, USE_YN FROM PRODUCT</select>
        """);
    List<ColumnSync.Change> fc = run(flat, DB, ADD, NEW, Set.of());
    eq(fc.size(), 1);
    eq(fc.get(0).kind(), ColumnSync.Kind.ADD_BY_HAND);
    eq(fc.get(0).edits().size(), 0);

    // 5 다른 테이블 SELECT · 이미 있는 칼럼은 건너뜀
    String other = mapper("""
          <select id="o">
            SELECT x.A
                 , x.NEW_COL
              FROM OTHER x
          </select>
          <select id="has">
            SELECT PRODUCT_ID
                 , NEW_COL
              FROM PRODUCT
          </select>
        """);
    eq(run(other, DB, ADD, NEW, Set.of()).size(), 0);

    // 6 없어진 칼럼: 한 줄 항목은 지우기(체크 끔), WHERE 는 손으로, 쉼표 없는 마지막 항목은 손으로
    Set<String> db2 = Set.of("PRODUCT_ID", "PRODUCT_NAME", "UNIT_PRICE");
    List<ColumnSync.Change> rc = run(gen, db2, List.of(), Set.of(), Set.of("USE_YN"));
    out = applied(gen, rc);
    if (args.length > 0) System.out.println(out);
    ok(!out.contains("USE_YN") && !out.contains("useYn"), "USE_YN 모두 지움\n" + out);
    ok(out.contains("         , p.UNIT_PRICE\n      FROM"), "SELECT 줄 지움\n" + out);
    ok(out.contains("         , UNIT_PRICE\n    ) VALUES"), "INSERT 칼럼 지움\n" + out);
    ok(out.contains("         , #{unitPrice, jdbcType=NUMERIC}\n    )"), "INSERT 값 지움\n" + out);
    for (ColumnSync.Change c : rc) ok(!c.checked(), "지우기는 기본 체크 끔");
    String where = mapper("""
          <select id="w">
            SELECT PRODUCT_ID
                 , PRODUCT_NAME
              FROM PRODUCT
             WHERE USE_YN = 'Y'
          </select>
        """);
    List<ColumnSync.Change> wc = run(where, db2, List.of(), Set.of(), Set.of("USE_YN"));
    eq(wc.size(), 1);
    eq(wc.get(0).kind(), ColumnSync.Kind.FIX_BY_HAND);
    List<ColumnSync.Change> tc = run(trail, db2, List.of(), Set.of(), Set.of("USE_YN"));
    for (ColumnSync.Change c : tc) eq(c.kind(), ColumnSync.Kind.FIX_BY_HAND);
    ok(!tc.isEmpty(), "쉼표 없는 마지막 항목은 손으로");

    // 7 칼럼 이름이 다른 칼럼 이름의 일부: USE_YN 을 지워도 USE_YN_OLD 는 그대로
    String part = mapper("""
          <select id="p">
            SELECT PRODUCT_ID
                 , USE_YN_OLD
                 , USE_YN
              FROM PRODUCT
          </select>
        """);
    out = applied(part, run(part, Set.of("PRODUCT_ID", "USE_YN_OLD"), List.of(), Set.of(), Set.of("USE_YN")));
    ok(out.contains("USE_YN_OLD\n      FROM"), "일부 이름은 남김\n" + out);

    // 8 test 에 다른 필드가 있으면 복제하지 않는다 · 모르는 #{} 선택이면 복제하지 않는다
    String odd = mapper("""
          <update id="u">
            UPDATE PRODUCT
            <set>
              <if test="other != null">USE_YN = #{useYn},</if>
              <if test="x">UNIT_PRICE = #{unitPrice, typeHandler=Foo},</if>
            </set>
          </update>
        """);
    List<ColumnSync.Change> oc = run(odd, DB, ADD, NEW, Set.of());
    eq(oc.size(), 1);
    eq(oc.get(0).kind(), ColumnSync.Kind.ADD_BY_HAND);

    // 9 jdbcType 을 모르면 뗀다, 여러 새 칼럼은 DB 순서대로
    List<ColumnSync.NewColumn> two = List.of(new ColumnSync.NewColumn("NEW_COL", "newCol", "", null),
        new ColumnSync.NewColumn("NEW_TWO", "newTwo", "", null));
    Set<String> db3 = Set.of("PRODUCT_ID", "PRODUCT_NAME", "UNIT_PRICE", "USE_YN", "NEW_COL", "NEW_TWO");
    String jt = mapper("""
          <insert id="i">
            INSERT INTO PRODUCT (
                   PRODUCT_ID
                 , UNIT_PRICE   /* 단가 */
            ) VALUES (
                   #{productId}
                 , #{unitPrice, jdbcType=NUMERIC}
            )
          </insert>
        """);
    out = applied(jt, run(jt, db3, two, Set.of("NEW_COL", "NEW_TWO"), Set.of()));
    if (args.length > 0) System.out.println(out);
    ok(out.contains("         , UNIT_PRICE   /* 단가 */\n         , NEW_COL\n         , NEW_TWO\n    ) VALUES"), "주석 떼고 차례대로\n" + out);
    ok(out.contains("         , #{newCol}\n         , #{newTwo}\n    )"), "jdbcType 뗌\n" + out);

    // 10 CRLF 줄 끝은 그대로
    String crlf = gen.replace("\n", "\r\n");
    out = applied(crlf, run(crlf, DB, ADD, NEW, Set.of()));
    ok(out.contains("         , USE_YN\r\n         , NEW_COL\r\n    ) VALUES"), "CRLF\n" + out);
    ok(!out.replace("\r\n", "").contains("\n"), "외톨이 LF 없음");

    // 11 selectKey 안 SELECT 는 보지 않는다, foreach 의 item.필드
    String batch = mapper("""
          <insert id="b">
            <selectKey keyProperty="productId" resultType="long" order="BEFORE">SELECT SEQ.NEXTVAL FROM DUAL</selectKey>
            INSERT INTO PRODUCT (
                   PRODUCT_ID
                 , USE_YN
            ) VALUES (
                   #{item.productId}
                 , #{item.useYn}
            )
          </insert>
        """);
    out = applied(batch, run(batch, DB, ADD, NEW, Set.of()));
    ok(out.contains("         , #{item.useYn}\n         , #{item.newCol}\n"), "item.필드\n" + out);

    eq(ColumnSync.commentText("a */ <b> & c"), "a * / &lt;b&gt; &amp; c");
    System.out.println("ColumnSyncCheck 통과");
  }
}
