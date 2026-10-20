package ei.db.mybatis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * select 문장 하나에서 Oracle ROWNUM 페이징 문장(&lt;id&gt;Page)과 건수 문장(&lt;id&gt;Count)을 만든다.
 * IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다). 본문은 동적 태그까지 글자 그대로 옮기고 들여쓰기만 맞춘다.
 */
public final class PagingGen {
  private PagingGen() {}

  public static final String PAGE_SUFFIX = "Page";
  public static final String COUNT_SUFFIX = "Count";
  public static final String START_ROW = "startRow";
  public static final String END_ROW = "endRow";

  /** 문서에 넣을 자리와 글, 새 문장 id 들. */
  public record Result(int offset, String text, String pageId, String countId) {}

  private static final String SELECT = "select";
  private static final String INDENT = WhereGen.INDENT;
  /** 페이징 문장에서 원래 본문이 놓일 깊이(문장 본문 들여쓰기 기준). */
  private static final String PAGE_BODY_INDENT = " ".repeat(16);
  private static final String COUNT_BODY_INDENT = " ".repeat(8);
  private static final String COUNT_RESULT_TYPE = "int";
  private static final String PARAMETER_TYPE = "parameterType";
  private static final Pattern ID_ATTR = Pattern.compile("(\\bid\\s*=\\s*)([\"'])(.*?)\\2", Pattern.DOTALL);
  private static final Pattern ORDER_BY = Pattern.compile("ORDER\\s+(?:SIBLINGS\\s+)?BY\\b", Pattern.CASE_INSENSITIVE);
  private static final String CDATA_OPEN = "<![CDATA[";
  private static final String CDATA_CLOSE = "]]>";
  private static final String COMMENT_OPEN = "<!--";
  private static final String COMMENT_CLOSE = "-->";

  /**
   * statement(select) 바로 뒤에 넣을 두 문장. 문서에 같은 id 가 이미 있거나 select 가 아니거나 닫는 태그가 없으면
   * IllegalArgumentException(화면에 보일 문구).
   */
  public static Result generate(String text, MapperDoc doc, MapperDoc.Node statement) {
    if (!SELECT.equals(statement.name)) throw new IllegalArgumentException("select 문장에서만 만들 수 있습니다.");
    String id = statement.attr("id");
    if (id == null || id.isBlank()) throw new IllegalArgumentException("문장에 id 가 없습니다.");
    String pageId = id + PAGE_SUFFIX;
    String countId = id + COUNT_SUFFIX;
    for (String newId : List.of(pageId, countId)) {
      if (doc.statement(newId) != null) throw new IllegalArgumentException("이미 " + newId + " 문장이 있어 만들지 않았습니다.");
    }
    int bodyStart = WhereGen.openEnd(text, statement.start);
    int bodyEnd = WhereGen.closeStart(text, statement);
    if (bodyStart < 0 || bodyEnd < 0) throw new IllegalArgumentException("<select> 의 닫는 태그를 찾지 못했습니다.");
    String body = text.substring(bodyStart, bodyEnd);
    if (body.isBlank()) throw new IllegalArgumentException("select 본문이 비었습니다.");

    String ind = WhereGen.indentAt(text, statement.start);
    String b = ind + INDENT;
    Matcher idm = ID_ATTR.matcher(text.substring(statement.start, bodyStart));
    if (!idm.find()) throw new IllegalArgumentException("문장의 id 속성을 읽지 못했습니다.");
    String pageOpen = idm.replaceFirst("$1$2" + Matcher.quoteReplacement(pageId) + "$2");

    StringBuilder out = new StringBuilder("\n");
    out.append(ind).append(pageOpen).append('\n')
        .append(b).append("SELECT\n")
        .append(b).append("       *\n")
        .append(b).append("  FROM (\n")
        .append(b).append("        SELECT\n")
        .append(b).append("               ROWNUM AS RNUM\n")
        .append(b).append("             , a.*\n")
        .append(b).append("          FROM (\n")
        .append(reindent(body, b + PAGE_BODY_INDENT))
        .append(b).append("               ) a\n")
        .append(b).append("         WHERE ROWNUM <![CDATA[<=]]> #{").append(END_ROW).append("}\n")
        .append(b).append("       )\n")
        .append(b).append(" WHERE RNUM <![CDATA[>]]> #{").append(START_ROW).append("}\n")
        .append(ind).append("</select>\n");

    String pt = statement.attr(PARAMETER_TYPE);
    out.append(ind).append("<select id=\"").append(countId).append('"');
    if (pt != null && !pt.isBlank()) out.append(' ').append(PARAMETER_TYPE).append("=\"").append(escape(pt.trim())).append('"');
    out.append(" resultType=\"").append(COUNT_RESULT_TYPE).append("\">\n")
        .append(b).append("SELECT\n")
        .append(b).append("       COUNT(*)\n")
        .append(b).append("  FROM (\n")
        .append(reindent(withoutOuterOrderBy(body), b + COUNT_BODY_INDENT))
        .append(b).append("       )\n")
        .append(ind).append("</select>");
    return new Result(statement.end, out.toString(), pageId, countId);
  }

  /** 매퍼 인터페이스에 더할 메서드 글. elementType 은 원래 반환의 원소 형식, params 는 괄호를 포함한 매개변수 목록 글. */
  public static String pageMethod(String elementType, String pageId, String params) {
    return "java.util.List<" + elementType + "> " + pageId + params + ";";
  }

  public static String countMethod(String countId, String params) {
    return "int " + countId + params + ";";
  }

  /**
   * 맨 바깥(괄호 · 태그 깊이 0, 문자열 · 주석 밖) 마지막 ORDER BY 부터 끝까지 뺀다. CDATA 안의 것이면 CDATA 는 닫아 둔다.
   * 없으면 받은 글 그대로.
   */
  static String withoutOuterOrderBy(String body) {
    int found = -1;
    boolean foundInCdata = false;
    int parens = 0;
    int tags = 0;
    boolean cdata = false;
    int i = 0;
    while (i < body.length()) {
      char c = body.charAt(i);
      if (cdata && body.startsWith(CDATA_CLOSE, i)) {
        cdata = false;
        i += CDATA_CLOSE.length();
      } else if (!cdata && body.startsWith(CDATA_OPEN, i)) {
        cdata = true;
        i += CDATA_OPEN.length();
      } else if (!cdata && body.startsWith(COMMENT_OPEN, i)) {
        i = skipPast(body, i, COMMENT_CLOSE);
      } else if (!cdata && c == '<') {
        int end = WhereGen.openEnd(body, i);
        boolean closing = i + 1 < body.length() && body.charAt(i + 1) == '/';
        int gt = body.indexOf('>', i);
        if (closing) tags--;
        else if (end >= 0) tags++;
        i = closing || end < 0 ? (gt < 0 ? body.length() : gt + 1) : end;
      } else if (c == '\'' || c == '"') {
        int close = body.indexOf(c, i + 1);
        i = close < 0 ? body.length() : close + 1;
      } else if (body.startsWith("--", i)) {
        i = skipPast(body, i, "\n");
      } else if (body.startsWith("/*", i)) {
        i = skipPast(body, i, "*/");
      } else if (c == '(') {
        parens++;
        i++;
      } else if (c == ')') {
        parens--;
        i++;
      } else {
        if (parens == 0 && tags == 0 && (i == 0 || !isWord(body.charAt(i - 1))) && ORDER_BY.matcher(body).region(i, body.length()).lookingAt()) {
          found = i;
          foundInCdata = cdata;
        }
        i++;
      }
    }
    if (found < 0) return body;
    return body.substring(0, found).stripTrailing() + (foundInCdata ? CDATA_CLOSE : "");
  }

  /** 앞뒤 빈 줄을 떼고, 빈 줄 아닌 줄들의 공통 앞 빈칸을 indent 로 바꾼다. 줄마다 \n 으로 끝난다. */
  static String reindent(String body, String indent) {
    List<String> lines = new ArrayList<>(List.of(body.split("\r?\n", -1)));
    while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
    int common = Integer.MAX_VALUE;
    for (String l : lines) {
      if (l.isBlank()) continue;
      int k = 0;
      while (k < l.length() && (l.charAt(k) == ' ' || l.charAt(k) == '\t')) k++;
      common = Math.min(common, k);
    }
    StringBuilder b = new StringBuilder();
    for (String l : lines) {
      if (l.isBlank()) b.append('\n');
      else b.append(indent).append(l.substring(common).stripTrailing()).append('\n');
    }
    return b.toString();
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
  }

  private static int skipPast(String s, int from, String close) {
    int k = s.indexOf(close, from + 1);
    return k < 0 ? s.length() : k + close.length();
  }

  private static boolean isWord(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
  }
}
