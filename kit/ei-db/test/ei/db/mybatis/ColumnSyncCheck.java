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

  /** mapper 안의 문장 · 조각 · resultMap 마다 forStatement · forFragment · forResultMap 을 부른다. adds 는 견줄 칼럼, fresh 는 새 칼럼. */
  static List<ColumnSync.Change> run(String xml, Set<String> db, List<ColumnSync.NewColumn> adds, Set<String> fresh, Set<String> dropped) {
    MapperDoc doc = MapperDoc.parse(xml);
    MapperSqlScan.Result scan = MapperSqlScan.scan(xml, doc);
    List<ColumnSync.Change> out = new ArrayList<>();
    for (MapperDoc.Node s : doc.statements) out.addAll(ColumnSync.forStatement(xml, doc, s, scan, "PRODUCT", db, adds, fresh, dropped));
    // resultMap 에는 새 칼럼만 넣는다(ColumnSyncAction 과 같음).
    List<ColumnSync.NewColumn> freshOnly = new ArrayList<>();
    for (ColumnSync.NewColumn nc : adds) if (fresh.contains(nc.name())) freshOnly.add(nc);
    for (MapperDoc.Node f : doc.fragments.values()) out.addAll(ColumnSync.forFragment(xml, doc, f, scan, "PRODUCT", db, adds, fresh, dropped));
    for (MapperDoc.Node c : doc.root.children.get(0).children) {
      if ("resultMap".equals(c.name)) out.addAll(ColumnSync.forResultMap(xml, c, db, freshOnly, dropped));
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

    // 9 jdbcType 은 새 칼럼 것으로, 여러 새 칼럼은 DB 순서대로
    List<ColumnSync.NewColumn> two = List.of(new ColumnSync.NewColumn("NEW_COL", "newCol", "", "VARCHAR"),
        new ColumnSync.NewColumn("NEW_TWO", "newTwo", "", "NUMERIC"));
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
    ok(out.contains("         , #{newCol, jdbcType=VARCHAR}\n         , #{newTwo, jdbcType=NUMERIC}\n    )"), "jdbcType 새 칼럼 것\n" + out);
    // jdbcType 을 모르면 jdbcType 있는 줄을 복제하지 않고 손으로 넣기
    String jt2 = jt.replace("#{productId}", "#{productId, jdbcType=NUMERIC}");
    List<ColumnSync.Change> unknown = run(jt2, db3, List.of(new ColumnSync.NewColumn("NEW_COL", "newCol", "", null)), Set.of("NEW_COL"), Set.of());
    eq(unknown.size(), 1);
    eq(unknown.get(0).kind(), ColumnSync.Kind.ADD_BY_HAND);

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

    // 12 UNION: 목록이 여럿이라 고치지 않고 손으로 넣기
    String union = mapper("""
          <select id="un">
            SELECT PRODUCT_ID
                 , PRODUCT_NAME
              FROM PRODUCT
            UNION ALL
            SELECT PRODUCT_ID
                 , PRODUCT_NAME
              FROM PRODUCT
          </select>
        """);
    List<ColumnSync.Change> uc = run(union, DB, ADD, NEW, Set.of());
    eq(uc.size(), 1);
    eq(uc.get(0).kind(), ColumnSync.Kind.ADD_BY_HAND);
    eq(applied(union, uc), union);

    // 13 include 칼럼 목록 조각: 조각에 넣고, 조각을 부르는 문장에는 넣지 않는다 · 다른 파일 조각이면 손으로
    String inc = mapper("""
          <sql id="Base_Column_List">
                   PRODUCT_ID
                 , PRODUCT_NAME
                 , UNIT_PRICE
                 , USE_YN
          </sql>
          <select id="inc">
            SELECT <include refid="Base_Column_List"/>
              FROM PRODUCT
          </select>
          <select id="far">
            SELECT p.PRODUCT_ID
                 , <include refid="other.Mapper.cols"/>
              FROM PRODUCT p
          </select>
        """);
    List<ColumnSync.Change> ic = run(inc, DB, ADD, NEW, Set.of());
    out = applied(inc, ic);
    if (args.length > 0) System.out.println(out);
    ok(out.contains("         , USE_YN\n         , NEW_COL\n  </sql>"), "조각 목록에 넣기\n" + out);
    eq(out.split("NEW_COL", -1).length - 1, 1);
    int byHand = 0;
    for (ColumnSync.Change c : ic) if (c.kind() == ColumnSync.Kind.ADD_BY_HAND) byHand++;
    eq(byHand, 1);

    // 14 <trim prefix="SET"> UPDATE · insertSelective <trim prefix="("> INSERT: 목록 모양을 몰라 손으로
    String trim = mapper("""
          <update id="ut">
            UPDATE PRODUCT
            <trim prefix="SET" suffixOverrides=",">
              <if test="productName != null">PRODUCT_NAME = #{productName},</if>
              <if test="useYn != null">USE_YN = #{useYn},</if>
            </trim>
             WHERE PRODUCT_ID = #{productId}
          </update>
          <insert id="is">
            INSERT INTO PRODUCT
            <trim prefix="(" suffix=")" suffixOverrides=",">
              <if test="productId != null">PRODUCT_ID,</if>
              <if test="useYn != null">USE_YN,</if>
            </trim>
            <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
              <if test="productId != null">#{productId},</if>
              <if test="useYn != null">#{useYn},</if>
            </trim>
          </insert>
        """);
    List<ColumnSync.Change> trc = run(trim, DB, ADD, NEW, Set.of());
    eq(trc.size(), 2);
    for (ColumnSync.Change c : trc) eq(c.kind(), ColumnSync.Kind.ADD_BY_HAND);

    // 15 CDATA 끝 표시가 있는 줄은 복제 · 지우기를 하지 않는다
    String cdata = mapper("""
          <update id="cd">
            <![CDATA[
            UPDATE PRODUCT SET
                   PRODUCT_NAME = #{productName}
                 , UNIT_PRICE = #{unitPrice}
                 , USE_YN = #{useYn} ]]>
             WHERE PRODUCT_ID = #{productId}
          </update>
        """);
    out = applied(cdata, run(cdata, DB, ADD, NEW, Set.of("USE_YN")));
    if (args.length > 0) System.out.println(out);
    ok(out.contains("         , USE_YN = #{useYn} ]]>\n"), "CDATA 끝 줄 그대로\n" + out);
    eq(out.split("]]>", -1).length - 1, 1);
    ok(!out.contains("NEW_COL = #{newCol} ]]>"), "CDATA 끝 표시 복제 안 함\n" + out);

    // 16 두 번째 실행: 이미 넣은 목록은 건너뛰고, 그 칼럼이 빠진 목록은 (새 칼럼이 아니므로) 체크 끈 채 낸다
    List<ColumnSync.NewColumn> all = new ArrayList<>();
    for (String c : List.of("PRODUCT_ID", "PRODUCT_NAME", "UNIT_PRICE", "USE_YN", "NEW_COL")) {
      all.add(new ColumnSync.NewColumn(c, c.equals("NEW_COL") ? "newCol" : c.toLowerCase(), "", "VARCHAR"));
    }
    String once = applied(gen, run(gen, DB, ADD, NEW, Set.of()));
    eq(String.valueOf(run(once, DB, all, Set.of(), Set.of())), "[]");
    String half = mapper("""
          <select id="s">
            SELECT PRODUCT_ID
                 , PRODUCT_NAME
                 , UNIT_PRICE
                 , USE_YN
                 , NEW_COL
              FROM PRODUCT
          </select>
          <update id="u">
            UPDATE PRODUCT
            <set>
              <if test="productName != null">PRODUCT_NAME = #{productName},</if>
              <if test="unitPrice != null">UNIT_PRICE = #{unitPrice},</if>
              <if test="useYn != null">USE_YN = #{useYn},</if>
            </set>
             WHERE PRODUCT_ID = #{productId}
          </update>
        """);
    boolean offered = false;
    for (ColumnSync.Change c : run(half, DB, all, Set.of(), Set.of())) {
      ok(c.part() == ColumnSync.Part.UPDATE && !c.checked(), "새 칼럼이 아니면 UPDATE 에만 체크 끔: " + c);
      if (c.column().equals("NEW_COL") && c.kind() == ColumnSync.Kind.ADD) offered = true;
    }
    ok(offered, "UPDATE 에 빠진 NEW_COL");

    eq(ColumnSync.commentText("a */ <b> & c"), "a * / &lt;b&gt; &amp; c");
    System.out.println("ColumnSyncCheck 통과");
  }
}
