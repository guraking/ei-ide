package ei.db.mybatis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 매퍼 판정(뿌리 태그)과 매퍼 표 파일 하나 고치기 자체 점검: java -cp out:test-out ei.db.mybatis.MapperIndexCheck */
public class MapperIndexCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    // 뿌리 태그가 정확히 mapper 일 때만
    String head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n";
    eq(MapperDoc.isMapperText(head + "<mapper namespace=\"a.B\">\n</mapper>"), true);
    eq(MapperDoc.isMapperText("﻿<!-- 머리 <mapper -->\n<mapper>"), true);
    eq(MapperDoc.isMapperText("<mapper/>"), true);
    eq(MapperDoc.isMapperText("<mapper\nnamespace='x'>"), true);
    eq(MapperDoc.isMapperText("<!DOCTYPE mapper [ <!ENTITY a \"<mapper>\"> ]>\n<mapper>"), true);
    eq(MapperDoc.isMapperText(head.replace("mapper", "configuration") + "<configuration>\n  <mappers>\n    <mapper resource=\"a.xml\"/>\n  </mappers>\n</configuration>"), false);
    eq(MapperDoc.isMapperText("<mappers><mapper resource=\"a.xml\"/></mappers>"), false);
    eq(MapperDoc.isMapperText("<project><!-- <mapper> --></project>"), false);
    eq(MapperDoc.isMapperText("<mapperX>"), false);
    eq(MapperDoc.isMapperText("<!-- 닫히지 않은 주석 <mapper>"), false);
    eq(MapperDoc.isMapperText(""), false);
    eq(MapperDoc.isMapperText("<mapper"), true);

    // 설정 파일의 typeAlias: 별칭은 소문자로, 주석 안 · 설정 파일 아닌 것은 무시
    String config = "<?xml version=\"1.0\"?>\n<configuration>\n  <typeAliases>\n"
        + "    <typeAlias alias=\"searchVO\" type=\"egov.com.cmm.ComDefaultVO\"/>\n"
        + "    <!-- <typeAlias alias=\"x\" type=\"a.X\"/> -->\n"
        + "    <typeAlias type=' a.B ' alias='bVo'/>\n    <typeAlias alias=\"searchvo\" type=\"a.Later\"/>\n"
        + "    <package name=\"a.dto\"/>\n  </typeAliases>\n  <mappers><mapper resource=\"a.xml\"/></mappers>\n</configuration>";
    eq(MapperDoc.typeAliases(config), Map.of("searchvo", "egov.com.cmm.ComDefaultVO", "bvo", "a.B"));
    eq(MapperDoc.isMapperText(config), false);
    eq(MapperDoc.typeAliases("<mapper><typeAliases><typeAlias alias=\"a\" type=\"b\"/></typeAliases></mapper>"), Map.of());

    // 파일 하나가 바뀔 때 표 고치기: 받은 맵은 그대로, 빈 목록은 빠짐, 원본은 앞에
    Map<String, List<String>> t = new HashMap<>();
    t.put("a.A", List.of("A.xml"));
    t.put("a.B", List.of("B.xml", "build/B.xml"));
    Map<String, List<String>> renamed = MapperIndex.replaced(t, "A.xml", "a.A", "A.xml#2", "a.C", true);
    eq(renamed.containsKey("a.A"), false);
    eq(renamed.get("a.C"), List.of("A.xml#2"));
    eq(t.get("a.A"), List.of("A.xml"));
    Map<String, List<String>> added = MapperIndex.replaced(t, null, null, "src/B.xml", "a.B", true);
    eq(added.get("a.B"), List.of("src/B.xml", "B.xml", "build/B.xml"));
    eq(t.get("a.B"), List.of("B.xml", "build/B.xml"));
    Map<String, List<String>> appended = MapperIndex.replaced(t, null, null, "out/B.xml", "a.B", false);
    eq(appended.get("a.B"), List.of("B.xml", "build/B.xml", "out/B.xml"));
    Map<String, List<String>> deleted = MapperIndex.replaced(t, "build/B.xml", "a.B", null, null, false);
    eq(deleted.get("a.B"), List.of("B.xml"));
    // 매퍼가 아니게 된 파일(namespace 없음)은 빠지기만 한다
    eq(MapperIndex.replaced(t, "B.xml", "a.B", "B.xml#2", null, true).get("a.B"), List.of("build/B.xml"));
    System.out.println("MapperIndexCheck 통과");
  }
}
