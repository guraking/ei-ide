package ei.db.mybatis;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** MyBatis 순수 논리 자체 점검: java -cp out:test-out ei.db.mybatis.MyBatisCheck */
public class MyBatisCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static final String XML = """
      <?xml version="1.0" encoding="UTF-8"?>
      <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
      <mapper namespace="com.example.order.mapper.OrderMapper">
        <!-- <select id="commented"> 는 문장이 아니다 -->
        <sql id="cols">oi.order_id, oi.line_no, p.product_name, oi.qty</sql>
        <select id="selectOrderItems" resultType="OrderItemDto">
          SELECT <include refid="cols"/>
            FROM order_item oi, product p
           WHERE 1 = 1
             AND oi.product_id = p.product_id
             AND oi.order_id = #{orderId}
          <if test="fromDate != null">
             AND oi.created_at &gt;= #{fromDate}
          </if>
          <if test="productIds != null and productIds.size() &gt; 0">
             AND oi.product_id IN
            <foreach collection="productIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </if>
          <if test="status != null and status != ''">
             AND oi.status = #{status}
          </if>
           ORDER BY oi.line_no
        </select>
        <select id="search">
          <bind name="pattern" value="'%' + name + '%'"/>
          SELECT * FROM users
          <where>
            <if test="name != null">AND name LIKE #{pattern}</if>
            <choose>
              <when test="kind == 'A'">AND kind = 'A?'</when>
              <otherwise>OR kind = #{kind, jdbcType=VARCHAR}</otherwise>
            </choose>
            <![CDATA[ AND age < #{age} ]]>
          </where>
          ORDER BY ${sort}
        </select>
        <update id="updateUser">
          UPDATE users
          <set>
            <if test="name != null">name = #{name},</if>
            <if test="age gt 0">age = #{age},</if>
          </set>
          WHERE id = #{id}
        </update>
        <insert id="insertMany">
          INSERT INTO t (a, b)
          <trim prefix="VALUES" prefixOverrides=",">
            <foreach collection="rows" item="r" index="i" separator=" UNION ALL ">, #{r.a}</foreach>
          </trim>
        </insert>
        <delete id="unclosed">
          DELETE FROM t WHERE id = #{id}
      """;

  public static void main(String[] args) {
    MapperDoc doc = MapperDoc.parse(XML);
    eq(doc.namespace, "com.example.order.mapper.OrderMapper");
    eq(doc.shortNamespace(), "OrderMapper");
    eq(doc.statements.size(), 5);
    eq(doc.statement("unclosed").end, XML.length());
    MapperDoc.Node sel = doc.statement("selectOrderItems");
    int caret = XML.indexOf("ORDER BY oi.line_no");
    eq(doc.statementAt(caret), sel);
    eq(doc.statementAt(XML.indexOf("<sql")), null);
    eq(MapperDoc.decodeEntities("a &lt; b &amp;&amp; c &quot;&apos;&#65;&#x42; &bogus;"), "a < b && c \"'AB &bogus;");

    eq(DynamicSql.paramNames(doc, sel), List.of("orderId", "fromDate", "productIds", "status"));
    eq(DynamicSql.listNames(doc, sel).contains("productIds"), true);

    Map<String, Object> p = new HashMap<>();
    p.put("orderId", 100231L);
    p.put("fromDate", Date.valueOf("2026-10-10"));
    p.put("productIds", List.of(1003L, 1007L));
    p.put("status", null);
    DynamicSql.Result r = DynamicSql.resolve(doc, sel, p);
    eq(r.binds, List.of(100231L, Date.valueOf("2026-10-10"), 1003L, 1007L));
    eq(r.summary(), "<if> 3개 중 2개 포함 · foreach 2개");
    eq(r.inlined(), """
        SELECT oi.order_id, oi.line_no, p.product_name, oi.qty
          FROM order_item oi, product p
         WHERE 1 = 1
           AND oi.product_id = p.product_id
           AND oi.order_id = 100231
           AND oi.created_at >= DATE '2026-10-10'
           AND oi.product_id IN
          (1003, 1007)
         ORDER BY oi.line_no""");

    Map<String, Object> q = new HashMap<>();
    q.put("name", "kim");
    q.put("kind", "B");
    q.put("age", 30L);
    q.put("sort", "name DESC");
    DynamicSql.Result s = DynamicSql.resolve(doc, doc.statement("search"), q);
    eq(s.sql.replaceAll("\\s+", " ").trim(),
       "SELECT * FROM users WHERE name LIKE ? OR kind = ? AND age < ? ORDER BY name DESC");
    eq(s.binds, List.of("%kim%", "B", 30L));
    eq(DynamicSql.paramNames(doc, doc.statement("search")), List.of("name", "kind", "age", "sort"));
    q.put("name", null);
    q.put("kind", "A");
    s = DynamicSql.resolve(doc, doc.statement("search"), q);
    eq(s.sql.replaceAll("\\s+", " ").trim(), "SELECT * FROM users WHERE kind = 'A?' AND age < ? ORDER BY name DESC");
    eq(s.inlined().replaceAll("\\s+", " ").trim(), "SELECT * FROM users WHERE kind = 'A?' AND age < 30 ORDER BY name DESC");

    Map<String, Object> u = new HashMap<>();
    u.put("name", "O'Neil");
    u.put("age", 0L);
    u.put("id", 7L);
    DynamicSql.Result up = DynamicSql.resolve(doc, doc.statement("updateUser"), u);
    eq(up.inlined().replaceAll("\\s+", " ").trim(), "UPDATE users SET name = 'O''Neil' WHERE id = 7");

    Map<String, Object> rows = new HashMap<>();
    rows.put("rows", List.of(Map.of("a", 1L), Map.of("a", 2L)));
    DynamicSql.Result ins = DynamicSql.resolve(doc, doc.statement("insertMany"), rows);
    eq(ins.inlined().replaceAll("\\s+", " ").trim(), "INSERT INTO t (a, b) VALUES 1 UNION ALL , 2");
    eq(DynamicSql.paramNames(doc, doc.statement("insertMany")), List.of("rows"));

    // OGNL
    Map<String, Object> o = new HashMap<>();
    o.put("a", 3L);
    o.put("s", "");
    o.put("list", List.of());
    o.put("m", Map.of("x", "y"));
    eq(Ognl.test("a gt 2 and (s == '' || x != null)", o::get), true);
    eq(Ognl.test("list.isEmpty() && !(a lte 1)", o::get), true);
    eq(Ognl.test("s.length() > 0", o::get), false);
    eq(Ognl.test("a.toString() == '3' and a == 3.0", o::get), true);
    eq(Ognl.test("m.x eq \"y\"", k -> DynamicSql.lookup(o, k)), true);
    eq(Ognl.test("a >", o::get), false);
    eq(Ognl.test("a ?? b", o::get), false);
    eq(Ognl.test("missing", o::get), false);
    eq(Ognl.names("productIds != null and productIds.size() > 0 and user.name != ''"),
       List.of("productIds", "productIds", "user.name"));
    // 인자 있는 메서드 · 속성 · 정적 호출
    Map<String, Object> g = new HashMap<>();
    g.put("flag", "Y");
    g.put("name", " kim ");
    g.put("ids", List.of(1L, 2L));
    g.put("arr", new String[] {"a"});
    g.put("status", 0L);
    g.put("blank", "  ");
    eq(Ognl.test("flag.equals('Y') and flag.equalsIgnoreCase('y')", g::get), true);
    eq(Ognl.test("name.trim().startsWith('ki') and name.endsWith('m ') and name.contains('im')", g::get), true);
    eq(Ognl.test("ids.contains(2) and !ids.contains(3)", g::get), true);
    eq(Ognl.test("ids != null and ids.size > 0 and !ids.empty and arr.length == 1 and flag.length == 1", g::get), true);
    eq(Ognl.test("ids.isEmpty()", g::get), false);
    eq(Ognl.test("@org.apache.commons.lang3.StringUtils@isNotEmpty(flag) and @x.StringUtils@isBlank(blank)", g::get), true);
    eq(Ognl.test("@StringUtils@isEmpty(missing) and !@StringUtils@isNotBlank(blank)", g::get), true);
    eq(Ognl.test("@x.ObjectUtils@isEmpty(ids)", g::get), false);
    eq(Ognl.names("@x.StringUtils@isNotEmpty(flag) and ids.size > 0 and name.equals(other)"), List.of("flag", "ids", "name", "other"));
    // 숫자와 빈 문자열: 빈 문자열은 0
    eq(Ognl.test("status != null and status != ''", g::get), false);
    eq(Ognl.test("status == ''", g::get), true);
    eq(Ognl.test("'' == status", g::get), true);
    eq(Ognl.test("flag != ''", g::get), true);
    // 읽지 못하는 식은 예외(DynamicSql 이 세어 알린다)
    for (String bad : List.of("a >", "a ?? b", "flag.foo(1)", "@x.U@hello(flag)", "flag.equals()")) {
      boolean thrown = false;
      try {
        Ognl.evaluate(bad, g::get);
      } catch (IllegalArgumentException ex) {
        thrown = true;
      }
      eq(bad + " " + thrown, bad + " true");
    }

    // 다른 매퍼의 include · include property · 찾지 못한 include · 읽지 못한 식 · _databaseId · _parameter
    String common = "<mapper namespace=\"com.x.Common\"><sql id=\"where\">WHERE A.DEL = 'N' <include refid=\"more\"/></sql>"
        + "<sql id=\"more\">AND A.K = #{k}</sql></mapper>";
    String main = "<mapper namespace=\"com.x.Main\">"
        + "<sql id=\"cols\">${al}.COL, ${al}.ID</sql>"
        + "<select id=\"s\">SELECT <include refid=\"cols\"><property name=\"al\" value=\"x\"/></include> FROM T x"
        + " <include refid=\"com.x.Common.where\"/> <include refid=\"com.x.Nowhere.w\"/>"
        + " <if test=\"_databaseId == 'postgresql'\">LIMIT 1</if><if test=\"_databaseId == 'oracle'\">AND ROWNUM = 1</if>"
        + " <if test=\"_parameter.k != null\">/*k*/</if> <if test=\"k.foo(1)\">BAD</if></select></mapper>";
    MapperDoc md = MapperDoc.parse(main);
    MapperDoc cd = MapperDoc.parse(common);
    java.util.function.Function<String, MapperDoc> mappers = ns -> "com.x.Common".equals(ns) ? cd : null;
    MapperDoc.Node ms = md.statement("s");
    eq(DynamicSql.paramNames(md, ms, mappers), List.of("k"));
    eq(DynamicSql.paramNames(md, ms), List.of("k"));
    Map<String, Object> kv = new HashMap<>();
    kv.put("k", 5L);
    DynamicSql.Result inc = DynamicSql.resolve(md, ms, kv, DynamicSql.databaseId(ei.db.DbKind.POSTGRES), mappers);
    eq(inc.inlined().replaceAll("\\s+", " ").trim(),
       "SELECT x.COL, x.ID FROM T x WHERE A.DEL = 'N' AND A.K = 5 LIMIT 1 /*k*/");
    eq(inc.missingIncludes, new java.util.LinkedHashSet<>(List.of("com.x.Nowhere.w")));
    eq(inc.badExpressions, 1);
    eq(inc.hasWarnings(), true);
    eq(inc.summary(), "<if> 4개 중 2개 포함 · 찾지 못한 include: com.x.Nowhere.w · 읽지 못한 식 1개(거짓으로 셈)");
    inc = DynamicSql.resolve(md, ms, kv, DynamicSql.databaseId(ei.db.DbKind.ORACLE), DynamicSql.NO_MAPPERS);
    eq(inc.inlined().replaceAll("\\s+", " ").trim(), "SELECT x.COL, x.ID FROM T x AND ROWNUM = 1 /*k*/");
    eq(inc.missingIncludes, new java.util.LinkedHashSet<>(List.of("com.x.Common.where", "com.x.Nowhere.w")));
    eq(r.hasWarnings(), false);

    // 값 변환
    eq(SqlValues.parse("2026-10-10", false), Date.valueOf("2026-10-10"));
    eq(SqlValues.parse("2026-10-10 13:31", false), Timestamp.valueOf("2026-10-10 13:31:00"));
    eq(SqlValues.parse("100231", false), 100231L);
    eq(SqlValues.parse("00123", false), "00123");
    eq(SqlValues.parse("'123'", false), "123");
    eq(SqlValues.parse("1.50", false), new BigDecimal("1.50"));
    eq(SqlValues.parse("  ", false), null);
    eq(SqlValues.parse("1003, 1007", true), List.of(1003L, 1007L));
    eq(SqlValues.format("100231"), "'100231'");
    eq(SqlValues.format(List.of(1L, 2L)), "1, 2");
    eq(SqlValues.literal(Timestamp.valueOf("2026-10-10 13:31:18.5")), "TIMESTAMP '2026-10-10 13:31:18'");
    eq(SqlValues.inline("a = ? and b = '?' -- ?\n and c = ? and d = ?", List.of("x", 2L)), "a = 'x' and b = '?' -- ?\n and c = 2 and d = ?");
    // 문자열 필드로 받는 값은 숫자 · 날짜 꼴이어도 문자열, 앞 0 은 늘 문자열
    eq(SqlValues.parse("1001", false, true), "1001");
    eq(SqlValues.parse("20240101", false, true), "20240101");
    eq(SqlValues.parse("2024-01-01", false, true), "2024-01-01");
    eq(SqlValues.parse("'a'", false, true), "a");
    eq(SqlValues.parse("1001, 1002", true, true), List.of("1001", "1002"));
    eq(SqlValues.parse("20240101", false, false), 20240101L);
    eq(SqlValues.parse("007", false), "007");
    // 없는 날짜 · 시각은 예외 없이 문자열
    eq(SqlValues.parse("2026-13-01", false), "2026-13-01");
    eq(SqlValues.parse("2026-02-30", false), "2026-02-30");
    eq(SqlValues.parse("2026-10-10 25:00", false), "2026-10-10 25:00");
    eq(SqlValues.format("2026-13-01"), "2026-13-01");
    // MySQL 은 역빗금도 이스케이프, 다른 DB 는 그대로
    eq(SqlValues.inline("a = ?", List.of("C:\\x'y"), ei.db.DbKind.MYSQL), "a = 'C:\\\\x''y'");
    eq(SqlValues.inline("a = ?", List.of("C:\\x"), ei.db.DbKind.ORACLE), "a = 'C:\\x'");
    eq(SqlValues.inline("a in (?)", List.of(List.of("\\")), ei.db.DbKind.MYSQL), "a in ('\\\\')");
    eq(SqlValues.literal("C:\\x"), "'C:\\x'");

    // 로그
    String log = """
        13:31:18.051 DEBUG c.e.shop.service.OrderService : findItems orderId=100231
        2026-10-17 13:31:18.063 DEBUG 4242 --- [nio-8080-exec-1] c.e.o.mapper.OrderMapper.selectOrderItems  : ==>  Preparing: SELECT oi.order_id FROM order_item oi WHERE oi.order_id = ? AND oi.created_at >= ? AND oi.memo = '?' AND oi.product_id IN ( ? , ? ) AND oi.status = ? AND oi.note = ?
        2026-10-17 13:31:18.063 DEBUG 4242 --- [nio-8080-exec-2] c.e.o.mapper.OtherMapper.x  : ==> Parameters: 9(Long)
        2026-10-17 13:31:18.064 DEBUG 4242 --- [nio-8080-exec-1] c.e.o.mapper.OrderMapper.selectOrderItems  : ==> Parameters: 100231(Long), 2026-10-10(Date), 1003(Long), 1007(Long), null, a, b (c)(String)
        2026-10-17 13:31:18.071 DEBUG 4242 --- [nio-8080-exec-1] c.e.o.mapper.OrderMapper.selectOrderItems  : <==      Total: 4
        """;
    MyBatisLog.Entry e = MyBatisLog.parse(log);
    eq(e.logger, "c.e.o.mapper.OrderMapper.selectOrderItems");
    eq(e.mapperName(), "OrderMapper");
    eq(e.statementId(), "selectOrderItems");
    eq(e.values, java.util.Arrays.asList(100231L, Date.valueOf("2026-10-10"), 1003L, 1007L, null, "a, b (c)"));
    eq(e.runnableSql(), "SELECT oi.order_id FROM order_item oi WHERE oi.order_id = 100231 AND oi.created_at >= DATE '2026-10-10'"
                        + " AND oi.memo = '?' AND oi.product_id IN ( 1003 , 1007 ) AND oi.status = NULL AND oi.note = 'a, b (c)'");
    eq(MyBatisLog.parameters("(String), 1(Integer), 2026-10-10 13:31:18.0(Timestamp), true(Boolean), 1.5(BigDecimal)"),
       List.of("", 1L, Timestamp.valueOf("2026-10-10 13:31:18"), true, new BigDecimal("1.5")));
    eq(MyBatisLog.parameters("2026-10-10(LocalDate), 2026-10-10T01:02:03(LocalDateTime)").toString(), "[2026-10-10, 2026-10-10T01:02:03]");
    eq(MyBatisLog.parse("nothing here"), null);
    List<MyBatisLog.Entry> all = MyBatisLog.parseAll(List.of(log.split("\n")), 10);
    eq(all.size(), 1);
    eq(all.get(0).line, 1);
    eq(all.get(0).values, e.values);
    List<String> two = List.of("[a] x.M.first : ==>  Preparing: select ? from dual", "[b] x.M.second : ==>  Preparing: select 2 from dual",
                               "[a] x.M.first : ==> Parameters: 7(Long)", "[b] x.M.second : ==> Parameters: ");
    List<MyBatisLog.Entry> both = MyBatisLog.parseAll(two, 10);
    eq(both.size(), 2);
    eq(both.get(0).runnableSql() + " / " + both.get(1).statementId(), "select 7 from dual / second");
    eq(MyBatisLog.parseAll(two, 1).get(0).line, 1);
    eq(MyBatisLog.parseAll(List.of("nothing"), 10), List.of());
    // log4jdbc sqltiming: 머리 줄부터 {executed …} 줄까지 한 쿼리. MyBatis 로그와 줄 순서대로 섞인다.
    List<String> mixed = List.of(
        "10/21 19:41:24.039 DEBUG [exec-8] c.m.UserMapper.readOne : ==>  Preparing: SELECT ? FROM dual",
        "10/21 19:41:24.039 DEBUG [exec-8] c.m.UserMapper.readOne : ==> Parameters: 1(Integer)",
        "10/21 19:41:24.051  INFO [exec-8] j.sqltiming : ",
        "        SELECT",
        "              tgp.PART_NUMBER AS PART_NUMBER",
        "          FROM (",
        "",
        "            SELECT 'GM000N4000' AS PART_NUMBER FROM DUAL",
        "",
        "          ) tgp",
        " {executed in 5 msec}",
        "10/21 19:41:24.060  INFO [exec-8] j.resultsettable : ",
        "10/21 19:41:25.000  INFO [exec-9] jdbc.sqltiming - 1. SELECT 2 FROM dual {executed in 12 msec}");
    List<MyBatisLog.Entry> logs = MyBatisLog.parseAll(mixed, 10);
    eq(logs.size(), 3);
    eq(logs.get(0).runnableSql(), "SELECT 1 FROM dual");
    eq(logs.get(1).line, 2);
    eq(logs.get(1).sql, "SELECT\n              tgp.PART_NUMBER AS PART_NUMBER\n          FROM (\n\n            SELECT 'GM000N4000' AS PART_NUMBER FROM DUAL\n\n          ) tgp");
    eq(logs.get(1).statementId(), null);
    eq(logs.get(2).sql, "SELECT 2 FROM dual");
    eq(MyBatisLog.parseAll(mixed, 1).get(0).line, 12);
    // 같은 쿼리(머리 주석 · 매퍼.문장id · SQL)는 최근 것 하나. MyBatis 로그와 log4jdbc 로그로 두 번 찍힌 것도 한 줄.
    List<String> repeated = List.of(
        "[e1] c.m.TermsMapper.readList : ==>  Preparing: /* TermsMapper.readList */ SELECT ? FROM dual",
        "[e1] c.m.TermsMapper.readList : ==> Parameters: 1(Integer)",
        "[e1] j.sqltiming : /* TermsMapper.readList */ SELECT 1 FROM dual {executed in 1 msec}",
        "[e2] c.m.UserMapper.readOne : ==>  Preparing: SELECT ? FROM t",
        "[e2] c.m.UserMapper.readOne : ==> Parameters: 1(Integer)",
        "[e3] j.sqltiming : SELECT  9 FROM dual {executed in 1 msec}",
        "[e4] c.m.TermsMapper.readList : ==>  Preparing: /* TermsMapper.readList */ SELECT ? FROM dual",
        "[e4] c.m.TermsMapper.readList : ==> Parameters: 2(Integer)",
        "[e4] j.sqltiming : /* TermsMapper.readList */ SELECT 2 FROM dual {executed in 1 msec}",
        "[e5] c.m.UserMapper.readOne : ==>  Preparing: SELECT ? FROM t",
        "[e5] c.m.UserMapper.readOne : ==> Parameters: 5(Integer)",
        "[e6] j.sqltiming : SELECT 9 FROM dual {executed in 1 msec}");
    List<MyBatisLog.Entry> once = MyBatisLog.distinct(MyBatisLog.parseAll(repeated, 100), 50);
    eq(once.size(), 3);
    eq(once.get(0).line, 8);
    eq(once.get(1).runnableSql(), "SELECT 5 FROM t");
    eq(once.get(2).line, 11);
    eq(MyBatisLog.distinct(MyBatisLog.parseAll(repeated, 100), 1).get(0).line, 11);
    // 시각 · 스레드 · 실행 시간, 쿼리 이름(주석 → 로거), 거르기.
    eq(logs.get(0).time + " " + logs.get(0).thread + " " + logs.get(0).elapsed, "19:41:24.039 exec-8 null");
    eq(logs.get(1).time + " " + logs.get(1).thread + " " + logs.get(1).elapsed, "19:41:24.051 exec-8 5 msec");
    eq(MyBatisLog.queryName(once.get(0)), "TermsMapper.readList");
    eq(MyBatisLog.queryName(once.get(1)), "UserMapper.readOne");
    eq(MyBatisLog.queryName(once.get(2)), null);
    eq(MyBatisLog.matches(once.get(0), " termsmapper "), true);
    eq(MyBatisLog.matches(once.get(1), "from T"), true);
    eq(MyBatisLog.matches(once.get(1), "dual"), false);
    eq(MyBatisLog.matches(once.get(2), ""), true);
    MyBatisLog.Entry plain = MyBatisLog.parse("DEBUG [main] - ==>  Preparing: select 1 from dual\nDEBUG [main] - ==> Parameters: ");
    eq(plain.logger, null);
    eq(plain.runnableSql(), "select 1 from dual");
    eq(plain.values, List.of());
    eq(MyBatisLog.loggerMatches("c.e.o.mapper.OrderMapper", "com.example.order.mapper.OrderMapper"), true);
    eq(MyBatisLog.loggerMatches("c.e.x.mapper.OrderMapper", "com.example.order.mapper.OrderMapper"), false);

    // resultType 비교
    List<String> cols = ResultColumns.of(r.sql);
    eq(cols, List.of("ORDER_ID", "LINE_NO", "PRODUCT_NAME", "QTY"));
    eq(ResultColumns.of("select a.*, b from t"), null);
    eq(ResultColumns.of("SELECT DISTINCT NVL(x, 0) qty, a + b, CASE WHEN a = 1 THEN 'x, y' END AS kind, c \"camelCase\", COUNT(*)"
                        + " FROM t WHERE (select 1 from dual) = 1"), List.of("QTY", "KIND", "camelCase"));
    List<ResultColumns.Issue> issues = ResultColumns.compare(cols, List.of("orderId", "lineNo", "productName", "quantity", "unitPrice"));
    eq(issues.size(), 3);
    eq(issues.get(0).column + " " + issues.get(0).field + " " + issues.get(0).similar, "QTY qty quantity");
    eq(issues.get(1).field, "quantity");
    eq(issues.get(2).field, "unitPrice");
    eq(ResultColumns.camel("ORDER_ID"), "orderId");
    // 칼럼 뒤 · 앞 주석
    eq(ResultColumns.of("SELECT t.USER_ID /* 사용자 */\n     , t.USER_NM -- 이름, 별칭\n     , /* 메일 */ t.EMAIL AS mail -- x\n"
                        + "     , t.DEPT_CD \"deptCode\" /* 부서 */\n     , 'a b' kind\n  FROM TB_USER t"),
       List.of("USER_ID", "USER_NM", "MAIL", "deptCode", "KIND"));
    eq(ResultColumns.compare(ResultColumns.of("SELECT t.USER_ID /* 사용자 */, t.USER_NM -- 이름\n FROM t"),
                             List.of("userId", "userNm")).size(), 0);

    System.out.println("MyBatisCheck 통과");
  }
}
