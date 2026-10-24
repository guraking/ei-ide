package ei.db.mybatis;

import ei.db.gen.DtoGen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * select 문장의 SELECT 칼럼과 resultType 클래스 필드를 짝지어 &lt;resultMap&gt; 을 만든다(IntelliJ 클래스 없음).
 * 짝은 MyBatis 자동 매핑 규칙(대소문자 무시, 밑줄 무시)으로 찾는다. 받을 필드가 없는 칼럼은 resultMap 안에 주석으로 남긴다.
 * resultMap 은 문장 바로 위에 두고, 문장의 resultType 속성은 resultMap="새 id" 로 바꾼다(MyBatis 는 둘을 함께 받지 않는다).
 */
public final class ResultMapGen {
  private ResultMapGen() {}

  public static final String ID_SUFFIX = "ResultMap";
  private static final String SELECT = "select";
  private static final Pattern RESULT_TYPE_ATTR = Pattern.compile("\\bresultType\\s*=\\s*([\"'])(.*?)\\1", Pattern.DOTALL);
  /** DtoGen.resultMap 이 쓰는 한 단계 들여쓰기. */
  private static final String GEN_INDENT = "  ";
  private static final String DEFAULT_INDENT = "  ";

  /**
   * 바꿀 내용. 문서에 attr 를 먼저 바꾸고(뒤쪽) insertAt 에 insertText 를 넣는다(앞쪽).
   * paired 는 짝지은 칼럼 수, unmatched 는 받을 필드가 없는 칼럼.
   */
  public record Result(String id, int insertAt, String insertText, int attrStart, int attrEnd, String attrText,
                       int paired, List<String> unmatched) {}

  /**
   * fields 는 resultType 클래스의 필드 이름. select 가 아니거나, 이미 resultMap 을 쓰거나, 같은 id 의 resultMap 이 있거나,
   * SELECT 칼럼을 읽지 못하면 IllegalArgumentException(화면에 보일 문구).
   */
  public static Result generate(String text, MapperDoc doc, MapperDoc.Node statement, List<String> fields) {
    if (!SELECT.equals(statement.name)) throw new IllegalArgumentException("select 문장에서만 만들 수 있습니다.");
    String stmtId = statement.attr("id");
    if (stmtId == null || stmtId.isBlank()) throw new IllegalArgumentException("문장에 id 가 없습니다.");
    if (statement.attr("resultMap") != null) throw new IllegalArgumentException("이 문장은 이미 resultMap 을 씁니다.");
    String type = statement.attr("resultType");
    if (type == null || type.isBlank()) throw new IllegalArgumentException("문장에 resultType 이 없습니다.");
    String id = stmtId + ID_SUFFIX;
    if (MapperRefs.declOffset(doc, MapperRefs.RESULT_MAP_TAG, id) >= 0) {
      throw new IllegalArgumentException("이미 " + id + " resultMap 이 있어 만들지 않았습니다.");
    }
    List<String> columns = ResultColumns.of(doc.plainText(statement));
    if (columns == null) throw new IllegalArgumentException("SELECT * 이거나 SELECT 를 읽지 못해 칼럼을 알 수 없습니다.");
    if (columns.isEmpty()) throw new IllegalArgumentException("이름을 알 수 있는 SELECT 칼럼이 없습니다. 식에는 별칭을 붙여 주세요.");

    int openEnd = WhereGen.openEnd(text, statement.start);
    if (openEnd < 0) throw new IllegalArgumentException("<select> 여는 태그를 읽지 못했습니다.");
    Matcher m = RESULT_TYPE_ATTR.matcher(text);
    m.region(statement.start, openEnd);
    if (!m.find()) throw new IllegalArgumentException("문장의 resultType 속성을 읽지 못했습니다.");

    List<DtoGen.Field> pairs = new ArrayList<>();
    List<String> unmatched = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    for (ResultColumns.Issue issue : ResultColumns.compare(columns, fields)) {
      if (!issue.columnWithoutField) continue;
      unmatched.add(issue.column);
      String similar = issue.similar == null ? "" : " (비슷한 필드: " + issue.similar + ")";
      notes.add("<!-- 받을 필드 없음: " + comment(issue.column) + similar + " -->");
    }
    for (String c : columns) {
      if (unmatched.contains(c)) continue;
      String field = fieldOf(c, fields);
      if (field != null) pairs.add(new DtoGen.Field(c, field, null, null));
    }

    String indent = WhereGen.indentAt(text, statement.start);
    if (indent.isEmpty()) indent = DEFAULT_INDENT;
    String body = DtoGen.resultMap(id, type.trim(), pairs, Set.of());
    StringBuilder b = new StringBuilder();
    String[] lines = body.split("\n");
    for (int i = 0; i < lines.length; i++) {
      if (i == lines.length - 1) {
        for (String note : notes) b.append(indent).append(indent).append(note).append('\n');
      }
      b.append(reindent(lines[i], indent)).append('\n');
    }
    b.append('\n');
    int insertAt = WhereGen.lineStart(text, statement.start);
    String attr = "resultMap=" + m.group(1) + id + m.group(1);
    return new Result(id, insertAt, b.toString(), m.start(), m.end(), attr, pairs.size(), unmatched);
  }

  /** column 과 같은 자동 매핑 키의 첫 필드. 없으면 null. */
  private static String fieldOf(String column, List<String> fields) {
    String key = ResultColumns.key(column);
    for (String f : fields) {
      if (ResultColumns.key(f).equals(key)) return f;
    }
    return null;
  }

  /** DtoGen 의 두 칸 단계를 문장 들여쓰기 단계로 바꾼다. */
  private static String reindent(String line, String indent) {
    int level = 0;
    int i = 0;
    while (line.startsWith(GEN_INDENT, i)) {
      level++;
      i += GEN_INDENT.length();
    }
    return indent.repeat(level) + line.substring(i);
  }

  /** XML 주석 안에서는 -- 가 금지라 한 칸 띄운다(따옴표 별칭에만 들어갈 수 있다). */
  private static String comment(String s) {
    return s.replace("--", "- -");
  }
}
