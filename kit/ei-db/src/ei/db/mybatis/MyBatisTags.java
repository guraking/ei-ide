package ei.db.mybatis;

import java.util.ArrayList;
import java.util.List;

/**
 * 매퍼 XML 우클릭 「MyBatis 태그 넣기」의 글 만들기. IntelliJ 클래스 없이 문서 글과 선택 범위만 다룬다.
 * 감싸는 태그(BLOCK)는 고른 줄 전체를 한 단계 들여 태그 안에 넣고, 고른 것이 없으면 커서 자리에 빈 틀을 넣는다.
 * 줄 안 태그(INLINE)는 고른 글자 그대로 감싸거나(본문이 있는 틀) 커서 자리에 넣는다(본문이 없는 틀, 고른 글은 그대로 둔다).
 */
public final class MyBatisTags {
  private MyBatisTags() {}

  /** 틀 안의 커서 자리. 없으면 본문 끝에 둔다. */
  static final String CARET = "{caret}";
  /** 틀 안의 본문 자리. BLOCK 은 앞의 탭 개수가 들여쓰기 단계다. */
  static final String BODY = "{body}";
  private static final char DEPTH = '\t';

  public enum Kind { BLOCK, INLINE }

  /** section 이 바뀌는 곳에 메뉴 구분선이 들어간다. emptyBody 는 고른 것이 없을 때 본문에 넣을 글이다. */
  public enum Tag {
    SELECT(0, "<select>", Kind.BLOCK, "", "<select id=\"{caret}\" parameterType=\"map\" resultType=\"map\">", "\t{body}", "</select>"),
    INSERT(0, "<insert>", Kind.BLOCK, "", "<insert id=\"{caret}\" parameterType=\"map\">", "\t{body}", "</insert>"),
    UPDATE(0, "<update>", Kind.BLOCK, "", "<update id=\"{caret}\" parameterType=\"map\">", "\t{body}", "</update>"),
    DELETE(0, "<delete>", Kind.BLOCK, "", "<delete id=\"{caret}\" parameterType=\"map\">", "\t{body}", "</delete>"),
    MERGE(0, "MERGE INTO", Kind.BLOCK, "",
        "<update id=\"{caret}\" parameterType=\"map\">",
        "\tMERGE INTO 표 t",
        "\tUSING DUAL",
        "\t   ON (t.키칼럼 = #{키})",
        "\t WHEN MATCHED THEN",
        "\t      UPDATE SET",
        "\t             t.칼럼 = #{칼럼}",
        "\t WHEN NOT MATCHED THEN",
        "\t      INSERT (",
        "\t             키칼럼",
        "\t           , 칼럼",
        "\t      ) VALUES (",
        "\t             #{키}",
        "\t           , #{칼럼}",
        "\t      )",
        "</update>"),
    RESULT_MAP(0, "<resultMap>", Kind.BLOCK, "<result column=\"\" property=\"\"/>",
        "<resultMap id=\"{caret}\" type=\"map\">", "\t{body}", "</resultMap>"),
    SQL(1, "<sql>", Kind.BLOCK, "", "<sql id=\"{caret}\">", "\t{body}", "</sql>"),
    INCLUDE(1, "<include>", Kind.INLINE, "", "<include refid=\"{caret}\"/>"),
    IF(2, "<if>", Kind.BLOCK, "", "<if test=\"{caret}\">", "\t{body}", "</if>"),
    CHOOSE(2, "<choose> <when> <otherwise>", Kind.BLOCK, "",
        "<choose>", "\t<when test=\"{caret}\">", "\t\t{body}", "\t</when>", "\t<otherwise>", "\t</otherwise>", "</choose>"),
    WHEN(2, "<when>", Kind.BLOCK, "", "<when test=\"{caret}\">", "\t{body}", "</when>"),
    OTHERWISE(2, "<otherwise>", Kind.BLOCK, "", "<otherwise>", "\t{body}", "</otherwise>"),
    WHERE(2, "<where>", Kind.BLOCK, "", "<where>", "\t{body}", "</where>"),
    SET(2, "<set>", Kind.BLOCK, "", "<set>", "\t{body}", "</set>"),
    TRIM(2, "<trim>", Kind.BLOCK, "", "<trim prefix=\"{caret}\" prefixOverrides=\"AND |OR \" suffixOverrides=\",\">", "\t{body}", "</trim>"),
    FOREACH(2, "<foreach>", Kind.BLOCK, "#{item}",
        "<foreach collection=\"{caret}\" item=\"item\" open=\"(\" separator=\",\" close=\")\">", "\t{body}", "</foreach>"),
    BIND(2, "<bind>", Kind.INLINE, "", "<bind name=\"{caret}\" value=\"\"/>"),
    SELECT_KEY(3, "<selectKey>", Kind.BLOCK, "",
        "<selectKey keyProperty=\"{caret}\" resultType=\"long\" order=\"BEFORE\">", "\t{body}", "</selectKey>"),
    CDATA(3, "<![CDATA[ ]]>", Kind.INLINE, "", "<![CDATA[ {body} ]]>");

    public final int section;
    public final String label;
    final Kind kind;
    final String emptyBody;
    final String[] lines;

    Tag(int section, String label, Kind kind, String emptyBody, String... lines) {
      this.section = section;
      this.label = label;
      this.kind = kind;
      this.emptyBody = emptyBody;
      this.lines = lines;
    }

    boolean hasBody() {
      for (String l : lines) if (l.contains(BODY)) return true;
      return false;
    }
  }

  /** 문서의 [start, end) 를 text 로 바꾸고 커서를 caret(바꾼 뒤 문서 기준)에 둔다. */
  public record Edit(int start, int end, String text, int caret) {}

  /**
   * selStart == selEnd 면 고른 것이 없는 것이다. 본문 자리가 없는 틀(MERGE · include · bind)은 고른 것을 무시한다. unit 은 한 단계 들여쓰기 글(공백 몇 칸 또는 탭)이다.
   * 범위가 문서 밖이거나 거꾸로면 IllegalArgumentException.
   */
  public static Edit edit(String doc, int selStart, int selEnd, Tag tag, String unit) {
    if (selStart < 0 || selEnd > doc.length() || selStart > selEnd) {
      throw new IllegalArgumentException("선택 범위가 문서 밖입니다: " + selStart + ".." + selEnd);
    }
    // 본문 자리가 없는 틀은 고른 글을 감싸지 않고 고른 끝에 넣는다.
    boolean selected = selStart < selEnd && tag.hasBody();
    if (!selected) selStart = selEnd;
    if (tag.kind == Kind.INLINE) {
      return selected ? inline(selStart, tag, doc.substring(selStart, selEnd), selStart, selEnd)
                      : inline(selEnd, tag, tag.emptyBody, selEnd, selEnd);
    }
    if (!selected) {
      int lineStart = doc.lastIndexOf('\n', selStart - 1) + 1;
      String indent = leading(doc.substring(lineStart, selStart));
      // 커서 앞이 공백뿐이면 첫 줄은 이미 들여 있는 자리에서 시작한다.
      boolean atIndent = indent.length() == selStart - lineStart;
      return block(selStart, selStart, tag, List.of(tag.emptyBody), atIndent ? indent : leading(doc.substring(lineStart)), unit, !atIndent);
    }
    int start = doc.lastIndexOf('\n', selStart - 1) + 1;
    int lastChar = selEnd - 1;
    // 다음 줄 맨 앞에서 끝난 선택은 그 줄을 넣지 않는다.
    if (doc.charAt(lastChar) == '\n' && lastChar > start) lastChar--;
    int end = doc.indexOf('\n', lastChar);
    if (end < 0) end = doc.length();
    List<String> lines = List.of(doc.substring(start, end).split("\n", -1));
    String base = commonIndent(lines);
    List<String> body = new ArrayList<>();
    for (String l : lines) body.add(l.isBlank() ? "" : l.substring(base.length()));
    return block(start, end, tag, body, base, unit, false);
  }

  private static Edit inline(int at, Tag tag, String body, int start, int end) {
    String t = tag.lines[0];
    int caretAt = t.indexOf(CARET);
    String filled = t.replace(CARET, "");
    int bodyAt = filled.indexOf(BODY);
    String text = filled.replace(BODY, body);
    int caret = caretAt >= 0 ? caretAt : bodyAt >= 0 ? bodyAt + body.length() : text.length();
    return new Edit(start, end, text, at + caret);
  }

  /** newLineFirst 면 커서 뒤에 바로 이어 쓰지 않고 줄을 바꾼 뒤 indent 에서 시작한다. */
  private static Edit block(int start, int end, Tag tag, List<String> body, String indent, String unit, boolean newLineFirst) {
    StringBuilder b = new StringBuilder();
    int caret = -1;
    int bodyEnd = -1;
    boolean first = true;
    if (newLineFirst) b.append('\n');
    for (String line : tag.lines) {
      int depth = 0;
      while (depth < line.length() && line.charAt(depth) == DEPTH) depth++;
      String rest = line.substring(depth);
      String lead = (first && !newLineFirst && start == end ? "" : indent) + unit.repeat(depth);
      if (!first) b.append('\n');
      first = false;
      if (rest.equals(BODY)) {
        for (int i = 0; i < body.size(); i++) {
          if (i > 0) b.append('\n');
          if (!body.get(i).isEmpty() || body.size() == 1) b.append(lead).append(body.get(i));
        }
        bodyEnd = b.length();
        continue;
      }
      b.append(lead);
      int c = rest.indexOf(CARET);
      if (c >= 0) caret = b.length() + c;
      b.append(rest.replace(CARET, ""));
    }
    return new Edit(start, end, b.toString(), start + (caret >= 0 ? caret : bodyEnd));
  }

  private static String leading(String s) {
    int i = 0;
    while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
    return s.substring(0, i);
  }

  /** 빈 줄을 뺀 줄들이 모두 가진 앞 공백. */
  private static String commonIndent(List<String> lines) {
    String common = null;
    for (String l : lines) {
      if (l.isBlank()) continue;
      String lead = leading(l);
      if (common == null) {
        common = lead;
        continue;
      }
      int n = 0;
      while (n < common.length() && n < lead.length() && common.charAt(n) == lead.charAt(n)) n++;
      common = common.substring(0, n);
    }
    return common == null ? "" : common;
  }
}
