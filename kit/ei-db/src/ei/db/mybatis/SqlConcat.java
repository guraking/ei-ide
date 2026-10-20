package ei.db.mybatis;

import ei.db.gen.Names;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자바 코드에서 이어 붙인 SQL 조각 → SQL 글 · 매퍼 XML 문장. IntelliJ 에 기대지 않는다(자체 점검 SqlConcatCheck 에서 바로 부른다).
 * 조각 모으기(PSI)는 StringSqlAction 이 한다.
 */
final class SqlConcat {
  private SqlConcat() {}

  /** 글 조각(리터럴 · 상수 값) 또는 바인드로 바꿀 식의 소스 글. */
  record Piece(String text, boolean literal) {
    static Piece literal(String text) { return new Piece(text, true); }

    static Piece expr(String text) { return new Piece(text, false); }
  }

  private static final String DEFAULT_BIND = "param";
  private static final String DEFAULT_ID = "statement";
  private static final String SELECT = "select";
  private static final String UPDATE = "update";
  private static final List<String> STATEMENTS = List.of(SELECT, "insert", UPDATE, "delete");
  private static final String BODY_INDENT = "    ";
  private static final String FORMATTER = "ei.db.console.SqlFormatter";
  /** 끝이 .getXxx() · .isXxx() 인 식(받는 쪽이 없어도 된다). */
  private static final Pattern GETTER = Pattern.compile("(?:^|\\.)\\s*(?:get|is)([A-Z][\\w$]*)\\s*\\(\\s*\\)\\s*$");
  private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
  private static final Pattern THIS = Pattern.compile("^this\\s*\\.\\s*");
  private static final Pattern FIRST_WORD = Pattern.compile("[A-Za-z]+");
  private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

  /** 조각을 차례로 잇는다. 식은 #{바인드 이름}. */
  static String sql(List<Piece> pieces) {
    StringBuilder b = new StringBuilder();
    for (Piece p : pieces) {
      if (p.literal()) b.append(p.text());
      else b.append("#{").append(bindName(p.text())).append('}');
    }
    return b.toString();
  }

  /**
   * 식 글 → 바인드 이름. this. 는 뗀다. 단순 이름은 그대로, 끝이 x.getUserId() 면 userId, x.isUse() 면 use,
   * 그 밖엔 식별자만 남겨 낙타 꼴로 잇는다(map.get("id") → mapGetId). 식별자가 없으면 param.
   */
  static String bindName(String expr) {
    String e = THIS.matcher(expr.strip()).replaceFirst("");
    if (IDENT.matcher(e).matches()) return e;
    Matcher g = GETTER.matcher(e);
    if (g.find()) return Names.lowerFirst(g.group(1));
    StringBuilder b = new StringBuilder();
    Matcher m = IDENT.matcher(e);
    while (m.find()) b.append(b.length() == 0 ? Names.lowerFirst(m.group()) : Names.upperFirst(m.group()));
    return b.length() == 0 ? DEFAULT_BIND : b.toString();
  }

  /** 주석을 건너뛴 첫 낱말로 고른 매퍼 요소 이름. WITH 는 select, MERGE 는 update, 모르는 낱말(CALL 등)은 select. */
  static String statementKind(String sql) {
    String s = BLOCK_COMMENT.matcher(LINE_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ");
    Matcher m = FIRST_WORD.matcher(s);
    if (!m.find()) return SELECT;
    String w = m.group().toLowerCase(Locale.ROOT);
    if (w.equals("merge")) return UPDATE;
    return STATEMENTS.contains(w) ? w : SELECT;
  }

  /**
   * 매퍼 XML 문장 하나. id 가 비면 statement. 부등호(&lt; · &gt;)가 있으면 본문 전체를 CDATA 로 감싸고(]]&gt; 는 나눠 쓴다),
   * 없으면 &amp; 만 엔티티로 바꾼다. resultType · parameterType 은 쓰지 않는다(사용자가 채운다).
   */
  static String xml(String sql, String id) {
    String kind = statementKind(sql);
    String name = id == null || id.isBlank() ? DEFAULT_ID : id;
    String body = indent(sql.strip());
    boolean cdata = sql.indexOf('<') >= 0 || sql.indexOf('>') >= 0;
    StringBuilder b = new StringBuilder();
    b.append('<').append(kind).append(" id=\"").append(name).append("\">\n");
    if (cdata) {
      b.append(BODY_INDENT).append("<![CDATA[\n").append(body.replace("]]>", "]]]]><![CDATA[>")).append('\n').append(BODY_INDENT).append("]]>\n");
    } else {
      b.append(body.replace("&", "&amp;")).append('\n');
    }
    return b.append("</").append(kind).append(">\n").toString();
  }

  private static String indent(String s) {
    StringBuilder b = new StringBuilder();
    for (String line : s.split("\n", -1)) {
      if (b.length() > 0) b.append('\n');
      if (!line.isBlank()) b.append(BODY_INDENT).append(line.stripTrailing());
    }
    return b.toString();
  }

  /**
   * 콘솔의 SQL 정렬(ei.db.console.SqlFormatter.format)로 정렬한다.
   * shortcut: SqlFormatter 가 console 패키지 전용이라 리플렉션으로 부른다. SqlFormatter · format 을 public 으로 열면 바로 부른다.
   */
  static String format(String sql) {
    try {
      Method m = Class.forName(FORMATTER).getDeclaredMethod("format", String.class);
      m.setAccessible(true);
      return (String) m.invoke(null, sql);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("SQL 정렬에 실패했습니다: " + e.getCause(), e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("SQL 정렬 기능(" + FORMATTER + ")을 찾지 못했습니다: " + e, e);
    }
  }
}
