package ei.db.gen;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JSON → DTO 자체 점검: java -cp out:test-out ei.db.gen.JsonDtoCheck (인자를 주면 만든 글을 찍는다) */
public class JsonDtoCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }
  static void ok(boolean v, String what) { if (!v) throw new AssertionError(what); }

  static int errorAt(String json) {
    try {
      JsonDto.parse(json);
    } catch (JsonDto.JsonException e) {
      return e.position;
    }
    throw new AssertionError("틀린 JSON 을 받았습니다: " + json);
  }

  public static void main(String[] args) throws Exception {
    // 파서
    Map<?, ?> m = (Map<?, ?>) JsonDto.parse(" {\"a\": \"x\\\"\\\\\\/\\n\\u00e9\\uAC00\", \"b\": -12, \"c\": 1.5e3, \"d\": [true, false, null], \"e\": {}} ");
    eq(m.get("a"), "x\"\\/\n\u00e9\uAC00");
    eq(m.get("b"), new BigInteger("-12"));
    eq(m.get("c"), new BigDecimal("1.5e3"));
    eq(m.get("d"), java.util.Arrays.asList(true, false, null));
    eq(m.get("e"), Map.of());
    eq(List.copyOf(m.keySet()), List.of("a", "b", "c", "d", "e"));
    eq(JsonDto.parse("0"), BigInteger.ZERO);
    eq(errorAt("{\"a\": 1,}"), 9);
    eq(errorAt("{\"a\" 1}"), 6);
    eq(errorAt("[1, 2"), 6);
    eq(errorAt("\"abc"), 5);
    eq(errorAt("01"), 2);
    eq(errorAt("1."), 3);
    eq(errorAt("{\"a\": tru}"), 7);
    eq(errorAt("\"\\x\""), 2);
    eq(errorAt("\"\\u12g4\""), 2);
    eq(errorAt("{} x"), 4);
    eq(errorAt(""), 1);
    eq(errorAt("[".repeat(600)), 514);

    // 형 · 이름 · 중첩 · 배열 합치기 · Lombok
    String json = """
        {
          "user_id": 1,
          "userName": "kim",
          "score": 1,
          "active": true,
          "memo": null,
          "class": "A",
          "tags": [],
          "address": {"zip": "123", "geo": {"lat": 37.5}},
          "orders": [
            {"order_no": 10, "amount": 100, "items": [{"sku": "a"}]},
            {"order_no": 11, "amount": 99.9, "coupon": "C1", "items": []}
          ],
          "matrix": [[1, 2], [3]],
          "list": {"x": 1}
        }
        """;
    String lombok = JsonDto.dto(json, "com.example.dto", "ResponseDto", true);
    if (args.length > 0) System.out.println(lombok);
    eq(lombok, """
        package com.example.dto;

        import com.fasterxml.jackson.annotation.JsonProperty;
        import java.math.BigDecimal;
        import java.util.List;
        import lombok.Getter;
        import lombok.Setter;

        @Getter
        @Setter
        public class ResponseDto {
            @JsonProperty("user_id")
            private Long userId;
            private String userName;
            private Long score;
            private Boolean active;
            private Object memo;
            @JsonProperty("class")
            private String class_;
            private List<Object> tags;
            private Address address;
            private List<Orders> orders;
            private List<List<Long>> matrix;
            private List2 list;

            @Getter
            @Setter
            public static class Address {
                private String zip;
                private Geo geo;
            }

            @Getter
            @Setter
            public static class Geo {
                private BigDecimal lat;
            }

            @Getter
            @Setter
            public static class Orders {
                @JsonProperty("order_no")
                private Long orderNo;
                private BigDecimal amount;
                private List<Items> items;
                private String coupon;
            }

            @Getter
            @Setter
            public static class Items {
                private String sku;
            }

            @Getter
            @Setter
            public static class List2 {
                private Long x;
            }
        }
        """);

    // 최상위 배열은 첫 원소, 패키지 없음, Lombok 끔 → getter/setter, 겹치는 클래스 · 필드 이름에 숫자
    String plain = JsonDto.dto("[{\"a_b\": \"x\", \"aB\": 2, \"data\": {\"data\": {\"v\": 1}}}, {\"z\": 1}]", "", "Data", false);
    if (args.length > 0) System.out.println(plain);
    eq(plain, """
        import com.fasterxml.jackson.annotation.JsonProperty;

        public class Data {
            @JsonProperty("a_b")
            private String aB;
            @JsonProperty("aB")
            private Long aB2;
            private Data2 data;

            public String getAB() {
                return aB;
            }

            public void setAB(String aB) {
                this.aB = aB;
            }

            public Long getAB2() {
                return aB2;
            }

            public void setAB2(Long aB2) {
                this.aB2 = aB2;
            }

            public Data2 getData() {
                return data;
            }

            public void setData(Data2 data) {
                this.data = data;
            }

            public static class Data2 {
                private Data3 data;

                public Data3 getData() {
                    return data;
                }

                public void setData(Data3 data) {
                    this.data = data;
                }
            }

            public static class Data3 {
                private Long v;

                public Long getV() {
                    return v;
                }

                public void setV(Long v) {
                    this.v = v;
                }
            }
        }
        """);

    // 키의 따옴표 · 역빗금은 @JsonProperty 안에서 이스케이프
    ok(JsonDto.dto("{\"a\\\"b\": 1}", "", "X", true).contains("@JsonProperty(\"a\\\"b\")"), "JsonProperty 이스케이프");
    // 최상위 꼴 오류
    for (String bad : List.of("1", "[]", "[1]", "\"s\"")) {
      try {
        JsonDto.dto(bad, "", "X", true);
        throw new AssertionError("받으면 안 됨: " + bad);
      } catch (JsonDto.JsonException e) {
        eq(e.position, 0);
      }
    }
    System.out.println("JsonDtoCheck 통과");
  }
}
