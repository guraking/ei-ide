package ei.db.gen;

/** 매퍼 XML 안에 넣을 글 다듬기. */
final class Xml {
  private Xml() {}

  static final String HEADER = """
      <?xml version="1.0" encoding="UTF-8" ?>
      <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
      """;

  /** 요소 본문(SQL 주석 등)에 들어갈 글. */
  static String text(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  static String attr(String s) {
    return text(s).replace("\"", "&quot;");
  }

  /** &lt;!-- --&gt; 안에서는 -- 가 금지라 한 칸 띄운다. */
  static String comment(String s) {
    String t = s.replace('\n', ' ');
    while (t.contains("--")) t = t.replace("--", "- -");
    return t.endsWith("-") ? t + " " : t;
  }
}
