package ei.db.gen;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 코드 표 enum 자체 점검: java -cp out ei.db.gen.EnumGenCheck (인자를 주면 만든 글을 찍는다) */
public class EnumGenCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  static void ok(boolean v, String what) { if (!v) throw new AssertionError(what); }

  static List<EnumGen.Item> items(String... codes) {
    List<List<Object>> rows = new java.util.ArrayList<>();
    for (String c : codes) rows.add(Arrays.asList(c, c + "명"));
    return EnumGen.items(rows, 0, 1);
  }

  public static void main(String[] args) {
    eq(EnumGen.constName("a01"), "A01");
    eq(EnumGen.constName("01"), "C01");
    eq(EnumGen.constName(""), "CODE");
    eq(EnumGen.constName("가나"), "CODE");
    eq(EnumGen.constName("in-progress"), "IN_PROGRESS");
    eq(EnumGen.constName(" A  B "), "A_B");
    eq(EnumGen.constNames(items("A", "a", "A_2", "A")), List.of("A", "A_2", "A_2_2", "A_3"));
    eq(EnumGen.constNames(items("", "가")), List.of("CODE", "CODE_2"));

    // null 코드는 건너뛰고 null 이름은 빈 글자, 숫자는 그대로 글자로
    List<List<Object>> rows = List.of(Arrays.asList(new BigDecimal("10"), null), Arrays.asList(null, "x"), Arrays.asList("Y", "예 \"인용\""));
    List<EnumGen.Item> its = EnumGen.items(rows, 0, 1);
    eq(its, List.of(new EnumGen.Item("10", ""), new EnumGen.Item("Y", "예 \"인용\"")));

    eq(EnumGen.oneLine("SELECT CODE\n     , NAME -- 이름 */ 끝\n  FROM T\n WHERE G = '--x';\n"),
       "SELECT CODE , NAME /* 이름 * / 끝 */ FROM T WHERE G = '--x'");

    String sql = "SELECT CD, NM /* 주석 */ FROM CODE_DETAIL WHERE GRP = 'A&amp;&#47;' -- 끝";
    String text = EnumGen.text("com.x.code", "UseYn", its, new EnumGen.Source(sql, "개발 DB", List.of("CD", "NM")));
    if (args.length > 0) System.out.println(text);
    ok(text.startsWith("package com.x.code;\n\n/**\n"), "package 줄");
    ok(text.contains("    C10(\"10\", \"\"),\n    Y(\"Y\", \"예 \\\"인용\\\"\")\n    ;\n"), "상수");
    ok(text.contains("public static UseYn of(String code) {"), "of");
    int javadocEnd = text.indexOf("*/");
    ok(javadocEnd > text.indexOf("@ei-columns"), "Javadoc 이 중간에 닫히지 않음");
    EnumGen.Source src = EnumGen.source(text);
    eq(src.sql(), EnumGen.oneLine(sql));
    eq(src.connection(), "개발 DB");
    eq(src.columns(), List.of("CD", "NM"));
    // 같은 입력이면 같은 글(다시 맞추기에서 「바뀐 것 없음」 판정)
    eq(EnumGen.text("com.x.code", "UseYn", its, src), text);

    String noCols = EnumGen.text("", "E", its, new EnumGen.Source("SELECT 1, 2 FROM DUAL", "운영", null));
    ok(noCols.startsWith("/**"), "패키지 없음");
    eq(EnumGen.source(noCols).columns(), null);
    eq(EnumGen.source("public enum X { A }"), null);
    eq(EnumGen.source("/**\n * @ei-sql SELECT 1 FROM DUAL\n */"), null);
    eq(EnumGen.unescape(EnumGen.escape("a*/b&#47;&amp;")), "a*/b&#47;&amp;");
    System.out.println("EnumGenCheck 통과");
  }
}
