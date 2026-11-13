package ei.db.console;

/** 「값 크게 보기」의 JSON · XML 알아보기와 줄 맞춤(ValueFormat) 점검. */
public class ValueFormatCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  public static void main(String[] args) {
    // JSON: 두 칸 들여쓰기, 빈 객체 · 배열은 한 줄, 문자열 안의 괄호 · 따옴표 · 공백은 그대로.
    eq(ValueFormat.json("{\"a\":1,\"b\":[true,null,-1.5e3],\"c\":{},\"d\":[ ],\"e\":\"x{,}:\\\" y\"}"),
       "{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null,\n    -1.5e3\n  ],\n  \"c\": {},\n  \"d\": [],\n  \"e\": \"x{,}:\\\" y\"\n}");
    eq(ValueFormat.json("[ {\"a\" : \"한글\"} ,\n 2 ]"), "[\n  {\n    \"a\": \"한글\"\n  },\n  2\n]");
    eq(ValueFormat.json("{}"), "{}");
    eq(ValueFormat.format("  [1]\n").kind(), ValueFormat.Kind.JSON);
    // 깨진 JSON · JSON 아닌 글은 null(원문 그대로 보인다).
    eq(ValueFormat.json("{\"a\":1"), null);
    eq(ValueFormat.json("{\"a\":1]"), null);
    eq(ValueFormat.json("{\"a\":\"x}"), null);
    eq(ValueFormat.json("{\"a\":1} x"), null);
    eq(ValueFormat.json("{a:1}"), null);
    eq(ValueFormat.format("[INFO] 시작했습니다"), null);
    eq(ValueFormat.format("[2024-01-01]"), null);
    eq(ValueFormat.format("그냥 글"), null);
    eq(ValueFormat.format("   "), null);
    // 한도까지 겹친 값은 맞추고, 더 깊으면 맞추지 않는다(메모리를 다 쓰지 않는다).
    int deep = ValueFormat.MAX_JSON_DEPTH;
    eq(ValueFormat.json("[".repeat(deep) + "1" + "]".repeat(deep)).length() > deep, true);
    eq(ValueFormat.json("[".repeat(deep + 1) + "1" + "]".repeat(deep + 1)), null);
    eq(ValueFormat.json("[".repeat(200_000) + "]".repeat(200_000)), null);
    // 깊게 겹친 XML 도 예외 없이 끝난다(맞추거나 null).
    ValueFormat.xml("<a>".repeat(20_000) + "</a>".repeat(20_000));

    // XML: 두 칸 들여쓰기, 원문의 줄바꿈과 겹친 빈 줄이 없다. 선언은 원문 그대로.
    eq(ValueFormat.xml("<a><b x=\"1\">글</b>\n\n  <c/></a>"), "<a>\n  <b x=\"1\">글</b>\n  <c/>\n</a>");
    eq(ValueFormat.xml("<?xml version=\"1.0\" encoding=\"UTF-8\"?><a><b>1</b></a>"),
       "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<a>\n  <b>1</b>\n</a>");
    eq(ValueFormat.format("<a><b/></a>").kind(), ValueFormat.Kind.XML);
    // 깨진 XML 은 null.
    eq(ValueFormat.xml("<a><b></a>"), null);
    eq(ValueFormat.format("<10 이하"), null);
    // DOCTYPE(외부 엔티티 · 엔티티 폭탄)은 받지 않는다.
    eq(ValueFormat.xml("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>"), null);
    eq(ValueFormat.xml("<!DOCTYPE a [<!ENTITY x \"y\">]><a>&x;</a>"), null);
    System.out.println("ValueFormatCheck 통과");
  }
}
