package ei.db.gen;

import ei.db.DbKind;
import ei.db.Meta;

import java.io.StringReader;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** 기능 한 벌 생성 자체 점검: java -cp out:test-out ei.db.gen.FeatureGenCheck (인자를 주면 만든 글을 찍는다) */
public class FeatureGenCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  static void ok(boolean v, String what) { if (!v) throw new AssertionError(what); }
  static void no(String text, String part, String what) { ok(!text.contains(part), what + ": " + part); }
  static void has(String text, String part, String what) { ok(text.contains(part), what + ": " + part); }

  static Document parse(String xml) throws Exception {
    String noDoctype = xml.replaceAll("<!DOCTYPE[^>]*>", "");
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(noDoctype)));
  }

  /** ORDER_ID + LINE_NO 복합 PK. */
  static final List<Meta.Column> ITEM = List.of(
      new Meta.Column("ORDER_ID", "NUMBER", 12, 0, true, true, false, "주문 ID"),
      new Meta.Column("LINE_NO", "NUMBER", 4, 0, true, false, false, "줄 번호"),
      new Meta.Column("ITEM_NAME", "VARCHAR2", 200, 0, false, false, true, "품목명"),
      new Meta.Column("QTY", "NUMBER", 8, 0, false, false, true, "수량"),
      new Meta.Column("ORDERED_AT", "DATE", 7, 0, false, false, true, "주문일시"));

  static FeatureGen gen(List<Meta.Column> cols, String table, DbKind kind, boolean split, boolean rest, Set<String> search) {
    // 기능 한 벌 창은 MERGE 를 꺼 둔 채 연다.
    return gen(cols, table, kind, split, rest, search, java.util.EnumSet.complementOf(java.util.EnumSet.of(MapperGen.Kind.MERGE)));
  }

  static FeatureGen gen(List<Meta.Column> cols, String table, DbKind kind, boolean split, boolean rest, Set<String> search,
                        Set<MapperGen.Kind> kinds) {
    return new FeatureGen(new FeatureGen.Spec(table, cols, new MapperGen.Options(true, true, true, true, kind), kinds, "com.x.dto.OrderItemDto", "com.x.mapper.OrderItemMapper",
        "com.x.service.OrderItemService", split ? "com.x.service.impl.OrderItemServiceImpl" : null,
        "com.x.controller.OrderItemController", rest, FeatureGen.defaultBaseUrl(table), search));
  }

  public static void main(String[] args) throws Exception {
    eq(FeatureGen.defaultBaseUrl("ORDER_ITEM"), "/order-item");
    eq(FeatureGen.defaultBaseUrl("$$"), "/item");

    // Oracle · 복합 PK · 검색 조건(문자 · 날짜) · 클래스 하나 · @RestController
    FeatureGen g = gen(ITEM, "ORDER_ITEM", DbKind.ORACLE, false, true, Set.of("ITEM_NAME", "ORDERED_AT"));
    eq(g.missingNote(), null);
    String xml = g.file(FeatureGen.Out.XML);
    if (args.length > 0) {
      for (FeatureGen.Out o : FeatureGen.Out.values()) System.out.println("==== " + o + "\n" + g.file(o));
    }
    Document doc = parse(xml);
    eq(doc.getDocumentElement().getAttribute("namespace"), "com.x.mapper.OrderItemMapper");
    eq(doc.getElementsByTagName("select").getLength(), 2);
    eq(doc.getElementsByTagName("insert").getLength(), 1);
    eq(doc.getElementsByTagName("update").getLength(), 1);
    eq(doc.getElementsByTagName("delete").getLength(), 1);
    has(xml, "AND oi.ITEM_NAME LIKE '%' || #{itemName} || '%'", "고른 문자 칼럼 LIKE");
    has(xml, "oi.ORDERED_AT <![CDATA[ >= ]]> #{orderedAtFrom}", "고른 날짜 From");
    has(xml, "oi.ORDERED_AT <![CDATA[ <= ]]> #{orderedAtTo}", "고른 날짜 To");
    no(xml, "oi.QTY = #{qty}", "안 고른 칼럼 조건 없음");
    no(xml, "test=\"orderId != null\"", "안 고른 PK 칼럼 조건 없음");
    has(xml, "    SELECT\n           oi.ORDER_ID", "SELECT 키워드만 한 줄");
    has(xml, "         , oi.LINE_NO", "앞 쉼표");
    has(xml, "     WHERE 1 = 1\n       AND ORDER_ID = #{orderId}\n       AND LINE_NO = #{lineNo}\n", "복합 PK 조건");
    has(xml, "<if test=\"qty != null\">QTY = #{qty},</if>", "수정 SET 은 그대로");

    String svc = g.file(FeatureGen.Out.SERVICE);
    has(svc, "package com.x.service;\n\nimport com.x.dto.OrderItemDto;\nimport com.x.mapper.OrderItemMapper;\n", "서비스 import");
    has(svc, "@Service\npublic class OrderItemService {\n\n    private final OrderItemMapper orderItemMapper;\n", "서비스 클래스");
    has(svc, "    public List<OrderItemDto> selectOrderItemList(Map<String, Object> param) {\n        return orderItemMapper.selectOrderItemList(param);\n    }\n", "서비스 목록");
    has(svc, "    @Transactional\n    public int deleteOrderItem(OrderItemDto param) {", "서비스 삭제 트랜잭션");
    eq(g.file(FeatureGen.Out.SERVICE_IMPL), null);
    eq(g.blocks(FeatureGen.Out.SERVICE_IMPL).size(), 0);

    String ctl = g.file(FeatureGen.Out.CONTROLLER);
    has(ctl, "@RestController\n@RequestMapping(\"/order-item\")\npublic class OrderItemController {", "REST 컨트롤러");
    has(ctl, "    @GetMapping\n    public List<OrderItemDto> selectOrderItemList(@RequestParam Map<String, Object> param) {", "GET 목록");
    has(ctl, "    @GetMapping(\"/{orderId}/{lineNo}\")\n    public OrderItemDto selectOrderItem(@PathVariable(\"orderId\") Long orderId, @PathVariable(\"lineNo\") Long lineNo) {\n"
        + "        OrderItemDto param = new OrderItemDto();\n        param.setOrderId(orderId);\n        param.setLineNo(lineNo);\n", "GET 상세 복합 PK");
    has(ctl, "    @PostMapping\n    public int insertOrderItem(@RequestBody OrderItemDto param) {", "POST");
    has(ctl, "    @PutMapping(\"/{orderId}/{lineNo}\")\n    public int updateOrderItem(@PathVariable(\"orderId\") Long orderId, @PathVariable(\"lineNo\") Long lineNo, @RequestBody OrderItemDto param) {", "PUT");
    has(ctl, "    @DeleteMapping(\"/{orderId}/{lineNo}\")", "DELETE");
    has(ctl, "import org.springframework.web.bind.annotation.RestController;", "RestController import");
    no(ctl, "ResponseBody", "REST 는 @ResponseBody 없음");
    no(ctl, "\t", "탭 없음");
    eq(g.file(FeatureGen.Out.MAPPER).contains("int deleteOrderItem(OrderItemDto param);"), true);

    // 인터페이스 + Impl · @Controller
    FeatureGen sp = gen(ITEM, "ORDER_ITEM", DbKind.ORACLE, true, false, Set.of());
    String itf = sp.file(FeatureGen.Out.SERVICE);
    has(itf, "public interface OrderItemService {\n    List<OrderItemDto> selectOrderItemList(Map<String, Object> param);\n", "서비스 인터페이스");
    no(itf, "@Service", "인터페이스는 @Service 없음");
    no(itf, "Mapper", "인터페이스는 매퍼 모름");
    String impl = sp.file(FeatureGen.Out.SERVICE_IMPL);
    has(impl, "package com.x.service.impl;", "Impl 패키지");
    has(impl, "import com.x.service.OrderItemService;", "Impl 이 인터페이스 import");
    has(impl, "@Service\npublic class OrderItemServiceImpl implements OrderItemService {", "Impl 선언");
    has(impl, "    @Override\n    @Transactional\n    public int insertOrderItem(OrderItemDto param) {", "Impl @Override");
    has(impl, "    public OrderItemServiceImpl(OrderItemMapper orderItemMapper) {", "Impl 생성자");
    String mvc = sp.file(FeatureGen.Out.CONTROLLER);
    has(mvc, "@Controller\n@RequestMapping(\"/order-item\")", "@Controller");
    has(mvc, "    public String selectOrderItemList(@RequestParam Map<String, Object> param, Model model) {\n"
        + "        model.addAttribute(\"list\", orderItemService.selectOrderItemList(param));\n        return \"order-item/list\";\n", "화면 목록");
    has(mvc, "return \"order-item/detail\";", "화면 상세");
    has(mvc, "    @PostMapping\n    @ResponseBody\n    public int insertOrderItem(", "@ResponseBody 등록");
    has(mvc, "import org.springframework.ui.Model;", "Model import");
    no(mvc, "import java.util.List;", "화면 컨트롤러는 List 안 씀");
    no(mvc, "RestController", "@Controller 는 RestController 없음");
    String noCond = sp.file(FeatureGen.Out.XML);
    parse(noCond);
    no(noCond, "<if test=\"itemName != null and", "검색 칼럼 없음");
    has(noCond, "     WHERE 1 = 1\n  </select>", "조건 없는 목록");

    // PK 없음 · MySQL
    List<Meta.Column> log = List.of(new Meta.Column("MSG", "VARCHAR", 100, 0, false, false, true, ""),
                                    new Meta.Column("LOGGED_AT", "TIMESTAMP", 0, 0, false, false, true, ""));
    FeatureGen nk = gen(log, "app_log", DbKind.MYSQL, false, true, Set.of("MSG"));
    ok(nk.missingNote() != null && nk.missingNote().contains("PK"), "PK 없음 알림");
    String nkXml = nk.file(FeatureGen.Out.XML);
    parse(nkXml);
    has(nkXml, "CONCAT('%', #{msg}, '%')", "MySQL LIKE");
    has(nkXml, "FROM app_log al", "MySQL 이름 그대로");
    no(nkXml, "<update", "PK 없으면 수정 없음");
    no(nkXml, "<delete", "PK 없으면 삭제 없음");
    no(nkXml, "loggedAtFrom", "안 고른 날짜 조건 없음");
    String nkCtl = nk.file(FeatureGen.Out.CONTROLLER);
    no(nkCtl, "PathVariable", "PK 없으면 경로 변수 없음");
    no(nkCtl, "@PutMapping", "PK 없으면 PUT 없음");
    no(nkCtl, "@DeleteMapping", "PK 없으면 DELETE 없음");
    no(nkCtl, "selectOrderItem(", "PK 없으면 상세 없음");
    eq(nk.blocks(FeatureGen.Out.CONTROLLER).size(), 2);
    eq(nk.blocks(FeatureGen.Out.SERVICE).size(), 2);

    // PostgreSQL · PK 만 있는 표: 수정 없음
    FeatureGen pg = gen(List.of(new Meta.Column("code", "varchar", 10, 0, true, false, false, "")), "code", DbKind.POSTGRES, false, true, Set.of("code"));
    has(pg.missingNote(), "수정", "PK 만 있음 알림");
    String pgXml = pg.file(FeatureGen.Out.XML);
    has(pgXml, "FROM CODE c", "PostgreSQL 대문자");
    has(pgXml, "AND c.CODE LIKE '%' || #{code} || '%'", "PostgreSQL LIKE");
    no(pg.file(FeatureGen.Out.CONTROLLER), "@PutMapping", "PK 만 있으면 PUT 없음");
    has(pg.file(FeatureGen.Out.CONTROLLER), "@DeleteMapping(\"/{code}\")", "PK 만 있어도 DELETE");

    // 고른 문장만: 등록 · 삭제
    FeatureGen part = gen(ITEM, "ORDER_ITEM", DbKind.ORACLE, false, true, Set.of(), EnumSet.of(MapperGen.Kind.INSERT, MapperGen.Kind.DELETE));
    String partCtl = part.file(FeatureGen.Out.CONTROLLER);
    eq(part.blocks(FeatureGen.Out.CONTROLLER).size(), 2);
    no(partCtl, "java.util.Map", "목록 없으면 Map 없음");
    no(partCtl, "GetMapping", "목록 · 상세 없으면 GET 없음");
    no(partCtl, "PutMapping", "수정 안 고름");
    has(partCtl, "import org.springframework.web.bind.annotation.PathVariable;", "삭제는 경로 변수");
    no(part.file(FeatureGen.Out.SERVICE), "java.util.List", "목록 없으면 List 없음");
    String partXml = part.file(FeatureGen.Out.XML);
    parse(partXml);
    no(partXml, "<select", "SELECT 안 고름");
    no(partXml, "<resultMap", "resultMap 안 고름");

    // 덧붙이기
    String oldSvc = "package com.x.service;\n\nimport java.util.List;\n\n@Service\npublic class OrderItemService {\n    private final OrderItemMapper orderItemMapper;\n\n"
        + "    public int insertOrderItem(OrderItemDto param) { return 0; }\n}\n";
    eq(g.cannotMerge(FeatureGen.Out.SERVICE, oldSvc), null);
    Merge.Result r = g.merge(FeatureGen.Out.SERVICE, oldSvc);
    eq(r.skipped(), List.of("insertOrderItem"));
    eq(r.added().size(), 4);
    ok(r.text().startsWith(oldSvc.substring(0, oldSvc.lastIndexOf('}'))
        .replace("import java.util.List;\n", "import java.util.List;\nimport com.x.dto.OrderItemDto;\nimport com.x.mapper.OrderItemMapper;\n"
                 + "import java.util.Map;\nimport org.springframework.stereotype.Service;\nimport org.springframework.transaction.annotation.Transactional;\n")), "원래 글 보존");
    ok(g.cannotMerge(FeatureGen.Out.CONTROLLER, "public class OrderItemController {\n}\n").contains("orderItemService"), "필드 없으면 덧붙이지 않음");
    ok(g.cannotMerge(FeatureGen.Out.DTO, "class A {}") != null, "DTO 는 덧붙이지 않음");
    eq(sp.cannotMerge(FeatureGen.Out.SERVICE, "public interface OrderItemService {\n}\n"), null);
    String oldXml = Xml.HEADER + "<mapper namespace=\"com.x.mapper.OrderItemMapper\">\n  <select id=\"selectOrderItemList\">SELECT 1 FROM DUAL</select>\n</mapper>\n";
    Merge.Result xr = g.merge(FeatureGen.Out.XML, oldXml);
    parse(xr.text());
    eq(xr.skipped(), List.of("selectOrderItemList"));
    eq(xr.added().size(), 5);

    System.out.println("FeatureGenCheck 통과");
  }
}
