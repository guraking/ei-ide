package ei.db.mybatis;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/** 계층 코드 만들기 순수 논리 자체 점검: java -cp out:test-out ei.db.mybatis.ServiceCodeCheck */
public class ServiceCodeCheck {
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

  static final String XML = """
      <mapper namespace="com.x.OrderMapper">
        <resultMap id="itemMap" type="com.x.OrderItemDto"><id property="itemId" column="ITEM_ID"/></resultMap>
        <select id="selectOrderList" parameterType="com.x.OrderDto" resultType="com.x.OrderDto">SELECT 1</select>
        <select id="selectItems" resultMap="itemMap">SELECT 1</select>
        <select id="selectItemsNs" resultMap="com.x.OrderMapper.itemMap" parameterType="map">SELECT 1</select>
        <select id="countOrder" parameterType="hashmap" resultType="int">SELECT 1</select>
        <select id="selectName" parameterType="long" resultType="string">SELECT 1</select>
        <select id="selectRow" resultType="map">SELECT 1</select>
        <select id="selectAlias" parameterType="searchDto" resultType="orderDto">SELECT 1</select>
        <select id="selectNothing">SELECT 1</select>
        <select id="selectBadMap" resultMap="noSuch">SELECT 1</select>
        <insert id="insertOrder" parameterType="com.x.OrderDto">INSERT</insert>
        <update id="updateOrder" parameterType="java.util.Map">UPDATE</update>
        <delete id="deleteAll">DELETE</delete>
      </mapper>
      """;

  public static void main(String[] args) {
    MapperDoc doc = MapperDoc.parse(XML);
    UnaryOperator<String> none = a -> a;
    UnaryOperator<String> psi = a -> a.equals("orderDto") ? "com.x.OrderDto" : a;

    // 모양 짐작
    ServiceCode.Sig list = ServiceCode.fromXml(doc, doc.statement("selectOrderList"), none);
    eq(list.returnType(), "java.util.List<com.x.OrderDto>");
    eq(list.params(), List.of(new ServiceCode.Param("com.x.OrderDto", "orderDto")));
    eq(list.guessed(), true);
    eq(ServiceCode.fromXml(doc, doc.statement("selectItems"), none).returnType(), "java.util.List<com.x.OrderItemDto>");
    ServiceCode.Sig ns = ServiceCode.fromXml(doc, doc.statement("selectItemsNs"), none);
    eq(ns.returnType(), "java.util.List<com.x.OrderItemDto>");
    eq(ns.params(), List.of(new ServiceCode.Param("java.util.Map<java.lang.String, java.lang.Object>", "params")));
    ServiceCode.Sig count = ServiceCode.fromXml(doc, doc.statement("countOrder"), none);
    eq(count.returnType(), "int");
    eq(count.params().get(0).name(), "params");
    ServiceCode.Sig name = ServiceCode.fromXml(doc, doc.statement("selectName"), none);
    eq(name.returnType(), "java.lang.String");
    eq(name.params(), List.of(new ServiceCode.Param("long", "value")));
    eq(ServiceCode.fromXml(doc, doc.statement("selectRow"), none).returnType(), "java.util.Map<java.lang.String, java.lang.Object>");
    ServiceCode.Sig alias = ServiceCode.fromXml(doc, doc.statement("selectAlias"), none);
    eq(alias.returnType(), "java.util.List<OrderDto>");
    eq(alias.params(), List.of(new ServiceCode.Param("SearchDto", "searchDto")));
    eq(ServiceCode.fromXml(doc, doc.statement("selectAlias"), psi).returnType(), "java.util.List<com.x.OrderDto>");
    ServiceCode.Sig nothing = ServiceCode.fromXml(doc, doc.statement("selectNothing"), none);
    eq(nothing.returnType(), "java.util.List<java.lang.Object>");
    eq(nothing.params(), List.of());
    eq(ServiceCode.fromXml(doc, doc.statement("selectBadMap"), none).returnType(), "java.util.List<java.lang.Object>");
    eq(ServiceCode.fromXml(doc, doc.statement("insertOrder"), none).returnType(), "int");
    ServiceCode.Sig upd = ServiceCode.fromXml(doc, doc.statement("updateOrder"), none);
    eq(upd.returnType(), "int");
    eq(upd.params().get(0).name(), "params");
    eq(ServiceCode.fromXml(doc, doc.statement("deleteAll"), none).params(), List.of());
    eq(ServiceCode.resultMapType(doc, null), null);
    eq(ServiceCode.resultMapType(doc, "itemMap"), "com.x.OrderItemDto");

    // 이름
    eq(ServiceCode.simple("java.util.Map<java.lang.String, java.util.List<com.x.OrderDto>>"), "Map<String, List<OrderDto>>");
    eq(ServiceCode.simple("java.util.Map.Entry"), "Map.Entry");
    eq(ServiceCode.importsOf(ns), Set.of("java.util.List", "java.util.Map", "com.x.OrderItemDto"));
    eq(ServiceCode.importsOf(name), Set.of());
    eq(ServiceCode.mapperField("OrderMapper"), "orderMapper");
    eq(ServiceCode.serviceField("OrderMapper"), "orderService");
    eq(ServiceCode.serviceField("UserDAO"), "userService");
    eq(ServiceCode.serviceField("Mapper"), "service");
    eq(ServiceCode.baseName("OrderItemDto"), "OrderItem");
    eq(ServiceCode.baseName("UserVO"), "User");
    eq(ServiceCode.baseName("Dto"), "Dto");
    eq(ServiceCode.childListName("OrderItemDto"), "orderItemList");
    eq(ServiceCode.plural("order"), "orders");
    eq(ServiceCode.plural("status"), "statusList");
    eq(ServiceCode.getter("orderId"), "getOrderId");
    eq(ServiceCode.setter("items"), "setItems");
    eq(ServiceCode.elementType("java.util.List<com.x.OrderDto>"), "com.x.OrderDto");
    eq(ServiceCode.elementType("List<A>"), "A");
    eq(ServiceCode.elementType("int"), null);
    eq(ServiceCode.boxed("long"), "Long");
    eq(ServiceCode.boxed("java.lang.String"), "String");
    eq(ServiceCode.boxed(null), "Object");
    eq(ServiceCode.boxed(" "), "Object");
    eq(ServiceCode.defaultUrl("selectOrderList"), "/selectOrderList");
    eq(ServiceCode.defaultHttpMethod("select"), "GET");
    eq(ServiceCode.defaultHttpMethod("insert"), "POST");
    ServiceCode.Prop orderId = new ServiceCode.Prop("orderId", "long");
    ServiceCode.Prop orderNm = new ServiceCode.Prop("orderNm", "java.lang.String");
    ServiceCode.Prop itemId = new ServiceCode.Prop("itemId", "long");
    eq(ServiceCode.commonKeys(List.of(orderId, orderNm), List.of(itemId, new ServiceCode.Prop("orderId", "java.lang.Long"))), List.of(orderId));
    eq(ServiceCode.commonKeys(List.of(orderNm), List.of(itemId)), List.of(orderNm, itemId));
    eq(ServiceCode.commonKeys(List.of(), List.of()), List.of());

    // 코드 글
    eq(ServiceCode.declaration("selectOrderList", list), "List<OrderDto> selectOrderList(OrderDto orderDto);\n");
    eq(ServiceCode.declaration("deleteAll", ServiceCode.fromXml(doc, doc.statement("deleteAll"), none)), "int deleteAll();\n");
    eq(ServiceCode.serviceMethod("orderMapper", "selectOrderList", list), """
        public List<OrderDto> selectOrderList(OrderDto orderDto) {
            return orderMapper.selectOrderList(orderDto);
        }
        """);
    eq(ServiceCode.serviceMethod("m", "run", new ServiceCode.Sig("void", List.of(), false)), """
        public void run() {
            m.run();
        }
        """);
    eq(ServiceCode.controllerMethod("orderService", "selectOrderList", list, "/order/list", "GET"), """
        @GetMapping("/order/list")
        public List<OrderDto> selectOrderList(OrderDto orderDto) {
            return orderService.selectOrderList(orderDto);
        }
        """);
    eq(ServiceCode.controllerMethod("orderService", "insertOrder", list, "/insertOrder", "POST"), """
        @PostMapping("/insertOrder")
        public List<OrderDto> insertOrder(@RequestBody OrderDto orderDto) {
            return orderService.insertOrder(orderDto);
        }
        """);
    eq(ServiceCode.controllerMethod("s", "countOrder", count, "/c", "GET"), """
        @GetMapping("/c")
        public int countOrder(@RequestParam Map<String, Object> params) {
            return s.countOrder(params);
        }
        """);
    eq(ServiceCode.controllerMethod("s", "selectName", name, "/n", "put"), """
        @PutMapping("/n")
        public String selectName(@RequestParam long value) {
            return s.selectName(value);
        }
        """);
    eq(ServiceCode.controllerMethod("s", "deleteAll", nothing, "/d", "TRACE").startsWith("@RequestMapping(\"/d\")\n"), true);
    eq(ServiceCode.controllerImports(list, "GET"), Set.of("java.util.List", "com.x.OrderDto",
        "org.springframework.web.bind.annotation.GetMapping"));
    eq(ServiceCode.controllerImports(list, "POST").contains("org.springframework.web.bind.annotation.RequestBody"), true);

    // 부모-자식
    ServiceCode.Sig items = new ServiceCode.Sig("java.util.List<com.x.OrderItemDto>",
        List.of(new ServiceCode.Param("com.x.OrderDto", "param")), false);
    ServiceCode.Sig orders = new ServiceCode.Sig("java.util.List<com.x.OrderDto>",
        List.of(new ServiceCode.Param("com.x.OrderDto", "param")), false);
    ServiceCode.Join join = new ServiceCode.Join("orderMapper", "selectOrderList", orders,
        "orderMapper", "selectOrderItemList", items, "orderId", "long", "items");
    eq(ServiceCode.parentChild(join), """
        public List<OrderDto> selectOrderListWithItems(OrderDto param) {
            List<OrderDto> orders = orderMapper.selectOrderList(param);
            Map<Long, List<OrderItemDto>> itemsByOrder = orderMapper.selectOrderItemList(param).stream()
                    .collect(Collectors.groupingBy(OrderItemDto::getOrderId));
            for (OrderDto order : orders) {
                order.setItems(itemsByOrder.getOrDefault(order.getOrderId(), List.of()));
            }
            return orders;
        }
        """);
    eq(ServiceCode.parentChildImports(join), Set.of("com.x.OrderDto", "com.x.OrderItemDto", "java.util.List", "java.util.Map",
        "java.util.stream.Collectors"));
    ServiceCode.Sig allItems = new ServiceCode.Sig("java.util.List<com.x.OrderItemDto>", List.of(), true);
    String noArgs = ServiceCode.parentChild(new ServiceCode.Join("orderMapper", "selectOrderList", orders,
        "itemMapper", "selectAll", allItems, "orderId", "", "orderItemList"));
    eq(noArgs.contains("Map<Object, List<OrderItemDto>> orderItemListByOrder = itemMapper.selectAll().stream()"), true);
    eq(noArgs.contains("order.setOrderItemList("), true);
    eq(noArgs.startsWith("public List<OrderDto> selectOrderListWithOrderItemList(OrderDto param) {"), true);
    fails(() -> ServiceCode.parentChild(new ServiceCode.Join("m", "countOrder", count, "m", "c", items, "k", null, "l")), "부모 문장 countOrder");
    fails(() -> ServiceCode.parentChild(new ServiceCode.Join("m", "p", orders, "m", "countOrder", count, "k", null, "l")), "자식 문장 countOrder");
    fails(() -> ServiceCode.parentChild(new ServiceCode.Join("m", "p", orders, "m", "c", items, " ", null, "l")), "연결 키");
    fails(() -> ServiceCode.parentChild(new ServiceCode.Join("m", "p", orders, "m", "c", items, "k", null, "")), "목록 이름");

    // 부모 DTO 필드
    eq(ServiceCode.childField("com.x.OrderItemDto", "items", true), "private List<OrderItemDto> items = new ArrayList<>();\n");
    eq(ServiceCode.childField("com.x.OrderItemDto", "items", false), """
        private List<OrderItemDto> items = new ArrayList<>();

        public List<OrderItemDto> getItems() {
            return items;
        }

        public void setItems(List<OrderItemDto> items) {
            this.items = items;
        }
        """);
    eq(ServiceCode.childFieldImports("com.x.OrderItemDto"), Set.of("java.util.List", "java.util.ArrayList", "com.x.OrderItemDto"));
    System.out.println("ServiceCodeCheck 통과");
  }
}
