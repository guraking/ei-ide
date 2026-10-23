package ei.db.mybatis;

import java.util.Objects;

/** MyBatis 태그 넣기 자체 점검: java -cp out:test-out ei.db.mybatis.MyBatisTagsCheck */
public class MyBatisTagsCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  /** 고친 글에 커서 자리를 | 로 찍는다. */
  static String run(String doc, int s, int e, MyBatisTags.Tag tag) {
    MyBatisTags.Edit ed = MyBatisTags.edit(doc, s, e, tag, "  ");
    String out = doc.substring(0, ed.start()) + ed.text() + doc.substring(ed.end());
    return out.substring(0, ed.caret()) + "|" + out.substring(ed.caret());
  }

  static String run(String doc, String sel, MyBatisTags.Tag tag) {
    int s = doc.indexOf(sel);
    return run(doc, s, s + sel.length(), tag);
  }

  public static void main(String[] args) {
    String sql = "  <select id=\"a\">\n    SELECT 1\n      FROM T\n  </select>\n";

    // 고른 줄 전체를 감싸고 한 단계 들인다. 줄 중간부터 골라도 줄 전체.
    eq(run(sql, "SELECT 1\n      FROM", MyBatisTags.Tag.IF),
       "  <select id=\"a\">\n    <if test=\"|\">\n      SELECT 1\n        FROM T\n    </if>\n  </select>\n");
    // 다음 줄 맨 앞에서 끝난 선택은 그 줄을 넣지 않는다.
    eq(run(sql, "    SELECT 1\n", MyBatisTags.Tag.WHERE),
       "  <select id=\"a\">\n    <where>\n      SELECT 1|\n    </where>\n      FROM T\n  </select>\n");
    // 빈 줄은 들이지 않는다.
    eq(run("  A\n\n  B", 0, 8, MyBatisTags.Tag.SET), "  <set>\n    A\n\n    B|\n  </set>");
    // 중첩 단계
    eq(run("  A", 0, 3, MyBatisTags.Tag.CHOOSE),
       "  <choose>\n    <when test=\"|\">\n      A\n    </when>\n    <otherwise>\n    </otherwise>\n  </choose>");

    // 고른 것 없음: 들여쓴 빈 자리면 그 자리에서, 글 뒤면 다음 줄에서 시작한다.
    String blank = "  <select>\n    \n  </select>";
    int at = blank.indexOf("    \n") + 4;
    eq(run(blank, at, at, MyBatisTags.Tag.FOREACH),
       "  <select>\n    <foreach collection=\"|\" item=\"item\" open=\"(\" separator=\",\" close=\")\">\n      #{item}\n    </foreach>\n  </select>");
    String after = "    AND A IN";
    eq(run(after, after.length(), after.length(), MyBatisTags.Tag.WHERE), "    AND A IN\n    <where>\n      |\n    </where>");

    // 줄 안 태그
    String cond = "AND A <= #{a}";
    eq(run(cond, "<=", MyBatisTags.Tag.CDATA), "AND A <![CDATA[ <=| ]]> #{a}");
    eq(run(cond, 4, 4, MyBatisTags.Tag.CDATA), "AND <![CDATA[ | ]]>A <= #{a}");
    eq(run(cond, 0, 3, MyBatisTags.Tag.INCLUDE), "AND<include refid=\"|\"/> A <= #{a}");
    eq(run("", 0, 0, MyBatisTags.Tag.BIND), "<bind name=\"|\" value=\"\"/>");

    // 본문 없는 BLOCK(MERGE)은 고른 줄을 지우지 않는다.
    String merged = run("  X", 0, 3, MyBatisTags.Tag.MERGE);
    eq(merged.startsWith("  X\n  <update id=\"|\" parameterType=\"map\">\n    MERGE INTO "), true);
    eq(merged.endsWith("\n  </update>"), true);

    // 탭 들여쓰기
    eq(MyBatisTags.edit("\tA", 0, 2, MyBatisTags.Tag.IF, "\t").text(), "\t<if test=\"\">\n\t\tA\n\t</if>");

    // 모든 틀: 커서 자리가 글 안에 있고, 같은 단계의 여는 · 닫는 태그 수가 맞는다.
    for (MyBatisTags.Tag t : MyBatisTags.Tag.values()) {
      MyBatisTags.Edit ed = MyBatisTags.edit("  ", 2, 2, t, "  ");
      if (ed.caret() < 0 || ed.caret() > 2 + ed.text().length()) throw new AssertionError("커서 자리: " + t);
      if (ed.text().contains("{")) {
        if (ed.text().contains("{caret}") || ed.text().contains("{body}")) throw new AssertionError("자리표시 남음: " + t);
      }
    }

    try {
      MyBatisTags.edit("ab", 1, 5, MyBatisTags.Tag.IF, "  ");
      throw new AssertionError("범위 밖은 예외");
    } catch (IllegalArgumentException expected) {
      // 범위 검사가 동작한다.
    }

    System.out.println("MyBatisTags 통과");
  }
}
