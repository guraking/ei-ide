package ei.db.mybatis;

import java.util.HashMap;
import java.util.Map;

/** 「고치기 전과 비교」 의 SQL 같음 판정 자체 점검. */
public class MapperCompareCheck {
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

  static DynamicSql.Result resolve(String xml, Map<String, Object> values) {
    MapperDoc doc = MapperDoc.parse(xml);
    return DynamicSql.resolve(doc, doc.statement("find"), values);
  }

  static String mapper(String body) {
    return "<mapper namespace=\"a.B\"><sql id=\"cols\">ID, NAME</sql><select id=\"find\">" + body + "</select></mapper>";
  }

  public static void main(String[] args) {
    Map<String, Object> values = new HashMap<>();
    values.put("id", 7);
    values.put("name", null);
    String before = mapper("SELECT <include refid=\"cols\"/> FROM T WHERE ID = #{id} <if test=\"name != null\">AND NAME = #{name}</if>");
    // 줄바꿈 · 들여쓰기만 다르면 같다.
    String spaced = mapper("\n  SELECT <include refid=\"cols\"/>\n    FROM T\n   WHERE ID = #{id}\n  <if test=\"name != null\">\n   AND NAME = #{name}\n  </if>\n");
    check(MapperCompare.sameSql(resolve(before, values), resolve(spaced, values)), "공백만 다르면 같은 SQL");
    // 조건이 바뀌면 다르다.
    String changed = mapper("SELECT <include refid=\"cols\"/> FROM T WHERE ID &gt;= #{id}");
    check(!MapperCompare.sameSql(resolve(before, values), resolve(changed, values)), "조건이 바뀌면 다른 SQL");
    // 같은 글이라도 바인드 값이 다르면 다르다(빈 값이 null 이 되어 <if> 가 빠지는 경우 포함).
    Map<String, Object> other = new HashMap<>(values);
    other.put("name", "kim");
    check(!MapperCompare.sameSql(resolve(before, values), resolve(before, other)), "바인드가 다르면 다른 SQL");
    // 조각(include) 안이 바뀌어도 다르다.
    String fragment = before.replace("ID, NAME", "ID, NAME, AGE");
    check(!MapperCompare.sameSql(resolve(before, values), resolve(fragment, values)), "조각이 바뀌면 다른 SQL");
    // 따옴표 안 공백 차이는 다른 SQL 이다.
    check(!MapperCompare.squeeze("SELECT 'A  B' FROM T").equals(MapperCompare.squeeze("SELECT 'A B' FROM T")), "따옴표 안 공백은 그대로");
    check(MapperCompare.squeeze("  SELECT  'it''s  x'\n  FROM\tT ").equals("SELECT 'it''s  x' FROM T"), "따옴표 밖만 줄임: " + MapperCompare.squeeze("  SELECT  'it''s  x'\n  FROM\tT "));
    check(MapperCompare.squeeze("").isEmpty(), "빈 글");
    // databaseId: 접속 종류와 같은 것 먼저, 없으면 databaseId 없는 것.
    MapperDoc dbs = MapperDoc.parse("<mapper namespace=\"a.B\"><select id=\"x\" databaseId=\"oracle\">O</select>"
        + "<select id=\"x\">P</select><select id=\"x\" databaseId=\"postgresql\">G</select></mapper>");
    check("postgresql".equals(MapperCompare.pick(dbs, "x", "postgresql").attr("databaseId")), "같은 databaseId");
    check(MapperCompare.pick(dbs, "x", "mysql").attr("databaseId") == null, "없으면 databaseId 없는 문장");
    check(MapperCompare.pick(dbs, null, "oracle") == null && MapperCompare.pick(dbs, " ", "oracle") == null && MapperCompare.pick(dbs, "y", "oracle") == null,
        "id 가 없거나 비면 null");
    System.out.println("MapperCompareCheck 통과");
  }
}
