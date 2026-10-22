package ei.db.gen;

import ei.db.DbKind;
import ei.db.Meta;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** 생성기 자체 점검: java -cp out:test-out ei.db.gen.GenCheck (인자를 주면 만든 XML · 인터페이스를 찍는다) */
public class GenCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  static void ok(boolean v, String what) { if (!v) throw new AssertionError(what); }

  /** DOCTYPE 은 인터넷에서 DTD 를 읽으려 하므로 떼고 잘 짜였는지만 본다. */
  static Document parse(String xml) throws Exception {
    String noDoctype = xml.replaceAll("<!DOCTYPE[^>]*>", "");
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(noDoctype)));
  }

  static final List<Meta.Column> COLS = List.of(
      new Meta.Column("PRODUCT_ID", "NUMBER", 12, 0, true, false, false, "상품 ID"),
      new Meta.Column("SUPPLIER_ID", "NUMBER", 12, 0, false, true, true, "공급사 ID"),
      new Meta.Column("PRODUCT_NAME", "VARCHAR2", 200, 0, false, false, true, "상품명 <A&B> */ --"),
      new Meta.Column("UNIT_PRICE", "NUMBER", 12, 2, false, false, true, "단가"),
      new Meta.Column("USE_YN", "CHAR", 1, 0, false, false, true, ""),
      new Meta.Column("CREATED_AT", "DATE", 7, 0, false, false, true, "등록일시"));

  public static void main(String[] args) throws Exception {
    eq(Names.camel("PRODUCT_ID"), "productId");
    eq(Names.camel("productId"), "productId");
    eq(Names.camel("COUNT(*)"), "count");
    eq(Names.camel("1ST_NO"), "f1stNo");
    eq(Names.pascal("ORDER_ITEM"), "OrderItem");
    eq(Names.alias("ORDER_ITEM"), "oi");
    eq(Names.alias("ORDER_REQUEST"), "t");
    eq(Names.sql(DbKind.POSTGRES, "product"), "PRODUCT");
    eq(Names.sql(DbKind.POSTGRES, "Product"), "\"Product\"");
    eq(Names.sql(DbKind.POSTGRES, "PRODUCT"), "\"PRODUCT\"");
    eq(Names.sql(DbKind.ORACLE, "PRODUCT_1$"), "PRODUCT_1$");
    eq(Names.sql(DbKind.ORACLE, "Product"), "\"Product\"");
    eq(Names.sql(DbKind.ORACLE, "A\"B"), "\"A\"\"B\"");
    eq(Names.sql(DbKind.MYSQL, "OrderItem"), "OrderItem");
    eq(Names.sql(DbKind.POSTGRES, "public", "order_item"), "PUBLIC.ORDER_ITEM");

    eq(TypeMap.javaType("NUMBER", false), "Long");
    eq(TypeMap.javaType("NUMBER", true), "BigDecimal");
    eq(TypeMap.javaType("INTEGER", false), "Integer");
    eq(TypeMap.javaType("SMALLINT", false), "Integer");
    eq(TypeMap.javaType("VARCHAR2", false), "String");
    eq(TypeMap.javaType("CLOB", false), "String");
    eq(TypeMap.javaType("TEXT", false), "String");
    eq(TypeMap.javaType("DATE", false), "LocalDateTime");
    eq(TypeMap.javaType("TIMESTAMP(6)", false), "LocalDateTime");
    eq(TypeMap.javaType("BLOB", false), "Object");
    eq(TypeMap.forColumn("NUMBER", -127), "BigDecimal");
    List<List<Object>> rows = List.of(List.of(new BigDecimal("3.00")), List.of(new BigDecimal("2.50")));
    eq(TypeMap.anyFraction(rows, 0), true);
    eq(TypeMap.anyFraction(List.of(List.of(new BigDecimal("3.00"))), 0), false);

    MapperGen.Options style = new MapperGen.Options(true, true, true, true, DbKind.ORACLE);
    MapperGen g = new MapperGen("PRODUCT", COLS, "com.example.mapper.ProductMapper", "com.example.dto.ProductDto", style);
    String xml = g.xmlFile(MapperGen.allKinds());
    if (args.length > 0) System.out.println(xml + "\n" + g.interfaceFile(MapperGen.allKinds()));
    Document doc = parse(xml);
    eq(doc.getDocumentElement().getAttribute("namespace"), "com.example.mapper.ProductMapper");
    eq(doc.getElementsByTagName("select").getLength(), 2);
    eq(doc.getElementsByTagName("resultMap").getLength(), 1);
    eq(doc.getElementsByTagName("id").getLength(), 1);
    ok(xml.startsWith("<?xml") && xml.contains("mybatis-3-mapper.dtd"), "DOCTYPE");
    ok(xml.contains("p.CREATED_AT <![CDATA[ >= ]]> #{createdAtFrom}"), "날짜 From CDATA");
    ok(xml.contains("p.CREATED_AT <![CDATA[ <= ]]> #{createdAtTo}"), "날짜 To CDATA");
    ok(!xml.contains("&lt;=") && !xml.contains("&gt;="), "부등호 엔티티 없음");
    ok(xml.contains("AND p.PRODUCT_NAME LIKE '%' || #{productName} || '%'"), "문자 LIKE");
    ok(xml.contains("AND p.UNIT_PRICE = #{unitPrice}"), "숫자 =");
    ok(xml.contains("WHERE 1 = 1\n"), "WHERE 1 = 1");
    ok(xml.contains("FROM PRODUCT p\n"), "FROM");
    ok(xml.contains("    SELECT\n           p.PRODUCT_ID   /* 상품 ID */\n         , p.SUPPLIER_ID  /* 공급사 ID */\n"), "SELECT 다음 줄부터 앞 쉼표");
    ok(xml.contains("property=\"unitPrice\" column=\"UNIT_PRICE\""), "낙타 대응");
    ok(xml.contains("/* 상품 ID */"), "SQL 주석");
    ok(xml.contains("<if test=\"productName != null\">PRODUCT_NAME = #{productName},</if>"), "update set if");
    ok(xml.contains("AND PRODUCT_ID = #{productId}"), "PK 조건");

    MapperGen plain = new MapperGen("PRODUCT", COLS, "m.ProductMapper", "ProductDto", new MapperGen.Options(false, false, false, false, DbKind.MYSQL));
    String plainXml = plain.xmlFile(MapperGen.allKinds());
    parse(plainXml);
    ok(plainXml.contains("&gt;= #{createdAtFrom}") && plainXml.contains("<where>"), "CDATA 끔 · where 태그");
    ok(plainXml.contains("CONCAT('%', #{productName}, '%')"), "MySQL LIKE");
    ok(plainXml.contains("     WHERE PRODUCT_ID = #{productId}"), "WHERE 1 = 1 끔");

    MapperGen noPk = new MapperGen("LOG", List.of(new Meta.Column("MSG", "VARCHAR2", 10, 0, false, false, true, "")), "a.LogMapper", "a.LogDto", style);
    eq(noPk.xmlBlocks(MapperGen.allKinds()).size(), 3);

    MapperGen pkOnly = new MapperGen("CODE", List.of(new Meta.Column("CD", "VARCHAR2", 10, 0, true, false, false, "")), "a.CodeMapper", "a.CodeDto", style);
    String pkOnlyXml = pkOnly.xmlFile(MapperGen.allKinds());
    parse(pkOnlyXml);
    ok(!pkOnlyXml.contains("<update") && !pkOnlyXml.contains("<set>"), "PK 만 있으면 UPDATE 없음");
    ok(!pkOnly.interfaceFile(MapperGen.allKinds()).contains("updateCode"), "PK 만 있으면 수정 메서드 없음");
    eq(pkOnly.xmlBlocks(MapperGen.allKinds()).size(), 5);

    MapperGen pg = new MapperGen("order_item", List.of(new Meta.Column("Qty", "int4", 10, 0, true, false, false, "")), "a.M", "a.D",
                                 new MapperGen.Options(true, true, true, false, DbKind.POSTGRES));
    ok(pg.xmlFile(MapperGen.allKinds()).contains("      FROM ORDER_ITEM oi\n") && pg.xmlFile(MapperGen.allKinds()).contains("oi.\"Qty\""), "PostgreSQL 이름 표기");

    String itf = g.interfaceFile(MapperGen.allKinds());
    ok(itf.contains("List<ProductDto> selectProductList(Map<String, Object> param);"), "목록 메서드");
    ok(itf.contains("import com.example.dto.ProductDto;"), "DTO import");
    ok(itf.contains("int deleteProduct(ProductDto param);"), "삭제 메서드");

    String dto = g.dtoFile();
    ok(dto.contains("private BigDecimal unitPrice;") && dto.contains("private LocalDateTime createdAt;")
       && dto.contains("private Long productId;"), "DTO 형식");
    ok(dto.contains("import java.math.BigDecimal;") && dto.contains("@Getter"), "DTO import");
    String getters = DtoGen.dto("a", "X", g.fields(), false, false);
    ok(getters.contains("public Long getProductId() {") && getters.contains("public void setUseYn(String useYn) {"), "getter · setter");

    String existing = Xml.HEADER + "<mapper namespace=\"x\">\n  <select id=\"selectProduct\">SELECT 1 FROM dual</select>\n</mapper>\n";
    Merge.Result mr = Merge.xml(existing, g.xmlBlocks(MapperGen.allKinds()));
    parse(mr.text());
    eq(mr.skipped(), List.of("selectProduct"));
    eq(mr.added().size(), 5);
    ok(mr.text().startsWith(existing.substring(0, existing.indexOf("</mapper>"))), "원래 글 보존");
    eq(Merge.xml("<x/>", List.of()).text(), null);

    String itfOld = "package com.example.mapper;\n\nimport java.util.List;\n\npublic interface ProductMapper {\n    int insertProduct(ProductDto param);\n}\n";
    Merge.Result jr = Merge.java(itfOld, g.methods(MapperGen.allKinds()), g.interfaceImports(MapperGen.allKinds()));
    eq(jr.skipped(), List.of("insertProduct"));
    ok(jr.text().contains("import java.util.List;\nimport com.example.dto.ProductDto;\nimport java.util.Map;\n"), "import 덧붙임");
    ok(jr.text().endsWith("    int deleteProduct(ProductDto param);\n}\n"), "메서드 덧붙임");
    eq(jr.text().split("import java.util.List;", -1).length, 2);

    List<List<String>> cols = ErdLayout.columns(List.of("ORDER_ITEM", "PRODUCT", "SUPPLIER", "ORDERS", "USERS", "CODE"),
        List.of(new String[] {"ORDER_ITEM", "PRODUCT"}, new String[] {"PRODUCT", "SUPPLIER"}, new String[] {"ORDER_ITEM", "ORDERS"},
                new String[] {"ORDERS", "USERS"}, new String[] {"CODE", "CODE"}), 2);
    eq(cols, List.of(List.of("SUPPLIER", "USERS"), List.of("CODE"), List.of("PRODUCT", "ORDERS"), List.of("ORDER_ITEM")));
    List<List<String>> cyc = ErdLayout.columns(List.of("A", "B"), List.of(new String[] {"A", "B"}, new String[] {"B", "A"}), 10);
    eq(cyc.size(), 2);
    // ERD 머리 색 띠: 접두어를 빼고 다음 토막으로 묶는다.
    eq(ErdLayout.category("TB_ORDER", 10), ErdLayout.category("TB_ORDER_ITEM", 10));
    eq(ErdLayout.category("tb_order", 10), ErdLayout.category("TB_ORDER", 10));
    eq(ErdLayout.category("ORDER_ITEM", 10), ErdLayout.category("ORDER", 10));
    eq(ErdLayout.category("TB_", 10), ErdLayout.category("TB", 10));
    for (String n : List.of("", "_", "A", "TB_CUSTOMER", "VERY_LONG_PREFIX_NAME")) {
      int k = ErdLayout.category(n, 10);
      ok(k >= 0 && k < 10, "범위 " + n);
    }

    System.out.println("GenCheck 통과");
  }
}
