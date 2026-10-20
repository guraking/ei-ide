package ei.db.mybatis;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 매퍼 문장에 넣을 동적 조건(&lt;where&gt; 와 &lt;if&gt;) 글을 만든다. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 * 만든 글은 2칸 들여쓰기, 줄 끝은 \n. LIKE 는 Oracle 의 || 이어 붙이기 꼴로 만든다.
 */
public final class WhereGen {
  private WhereGen() {}

  public static final String EQ = "=";
  public static final String LIKE = "LIKE";
  public static final String GE = ">=";
  public static final String LE = "<=";
  public static final List<String> OPS = List.of(EQ, LIKE, GE, LE);

  /** 조건 하나. field 는 #{} 이름, type 은 자바 형식 글(정규 · 단순 이름 모두 받는다). */
  public record Cond(String field, String column, String type, String op) {}

  /** 문서에 넣을 자리와 글. */
  public record Insert(int offset, String text) {}

  static final String INDENT = "  ";
  private static final String WHERE_TAG = "where";
  /** 접속 종류를 모르므로 Oracle 꼴로 고정한다. */
  private static final String LIKE_PREFIX = "'%' || ";
  private static final String LIKE_SUFFIX = " || '%'";
  private static final Set<String> TEXT_TYPES = Set.of("java.lang.String", "String");
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
  /** 첫 FROM(또는 UPDATE) 뒤 표 이름과 그 뒤 낱말. 낱말이 SQL 키워드면 별칭이 아니다. */
  private static final Pattern FIRST_TABLE = Pattern.compile(
      "\\b(?:FROM|UPDATE)\\s+[A-Za-z_][\\w$#.]*(?:\\s+(?:AS\\s+)?([A-Za-z_][\\w$#]*))?", Pattern.CASE_INSENSITIVE);
  private static final Set<String> NOT_ALIAS = Set.of("WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER",
      "ON", "GROUP", "ORDER", "UNION", "MINUS", "INTERSECT", "CONNECT", "START", "HAVING", "SET", "FOR", "WITH");

  /** userName → USER_NAME, userID → USER_ID, htmlURLPath → HTML_URL_PATH. 숫자 앞에는 _ 를 넣지 않는다. */
  public static String column(String field) {
    StringBuilder b = new StringBuilder(field.length() + 4);
    for (int i = 0; i < field.length(); i++) {
      char c = field.charAt(i);
      if (i > 0 && Character.isUpperCase(c)) {
        char prev = field.charAt(i - 1);
        boolean nextLower = i + 1 < field.length() && Character.isLowerCase(field.charAt(i + 1));
        if (Character.isLowerCase(prev) || Character.isDigit(prev) || (Character.isUpperCase(prev) && nextLower)) b.append('_');
      }
      b.append(Character.toUpperCase(c));
    }
    return b.toString();
  }

  public static boolean isText(String type) {
    return type != null && TEXT_TYPES.contains(type.trim());
  }

  /** 칼럼 · 별칭으로 쓸 수 있는 이름인지. */
  public static boolean validName(String name) {
    return name != null && NAME.matcher(name).matches();
  }

  /** 문장 본문에서 첫 표의 별칭. 없으면 빈 글. */
  public static String guessAlias(String sql) {
    Matcher m = FIRST_TABLE.matcher(sql);
    if (!m.find() || m.group(1) == null) return "";
    String word = m.group(1);
    return NOT_ALIAS.contains(word.toUpperCase(Locale.ROOT)) ? "" : word;
  }

  /** &lt;if&gt; 하나(줄 끝 \n 포함). alias 가 비면 칼럼 앞에 붙이지 않는다. */
  public static String ifBlock(Cond c, String alias, String indent) {
    String test = c.field() + " != null" + (isText(c.type()) ? " and " + c.field() + " != ''" : "");
    String col = (alias == null || alias.isBlank() ? "" : alias.trim() + ".") + c.column();
    String value = "#{" + c.field() + "}";
    String cond = switch (c.op()) {
      case EQ -> col + " = " + value;
      case LIKE -> col + " LIKE " + LIKE_PREFIX + value + LIKE_SUFFIX;
      case GE, LE -> col + " <![CDATA[ " + c.op() + " ]]> " + value;
      default -> throw new IllegalArgumentException("모르는 비교 방식: " + c.op());
    };
    return indent + "<if test=\"" + test + "\">\n"
        + indent + INDENT + "AND " + cond + "\n"
        + indent + "</if>\n";
  }

  /** &lt;where&gt; 로 감싼 조건들(줄 끝 \n 포함). */
  public static String whereBlock(List<Cond> conds, String alias, String indent) {
    StringBuilder b = new StringBuilder(indent).append("<where>\n");
    for (Cond c : conds) b.append(ifBlock(c, alias, indent + INDENT));
    return b.append(indent).append("</where>\n").toString();
  }

  /**
   * 문장 statement 에 넣을 자리와 글. 문장 안에 &lt;where&gt; 가 있으면 그 닫는 태그 앞에 &lt;if&gt; 만,
   * 없으면 caret 줄에 &lt;where&gt; 를 넣는다(caret 앞이 빈칸뿐이면 그 줄 앞에, 아니면 그 줄 뒤에). 자리는 문장 본문 안으로 좁힌다.
   */
  public static Insert insertion(String text, MapperDoc.Node statement, int caret, List<Cond> conds, String alias) {
    if (conds.isEmpty()) throw new IllegalArgumentException("고른 조건이 없습니다.");
    int bodyStart = openEnd(text, statement.start);
    int bodyEnd = closeStart(text, statement);
    if (bodyStart < 0 || bodyEnd < 0) throw new IllegalArgumentException("<" + statement.name + "> 의 닫는 태그를 찾지 못했습니다.");
    MapperDoc.Node where = find(statement, WHERE_TAG);
    if (where != null) {
      int close = closeStart(text, where);
      if (close < 0) throw new IllegalArgumentException("<where> 의 닫는 태그를 찾지 못했습니다.");
      String indent = indentAt(text, where.start) + INDENT;
      StringBuilder b = new StringBuilder();
      for (Cond c : conds) b.append(ifBlock(c, alias, indent));
      return place(text, close, b.toString(), indentAt(text, where.start));
    }
    String block = whereBlock(conds, alias, indentAt(text, statement.start) + INDENT);
    int at = Math.max(bodyStart, Math.min(caret, bodyEnd));
    if (!blankBefore(text, at)) {
      int lineEnd = text.indexOf('\n', at);
      at = lineEnd < 0 || lineEnd > bodyEnd ? at : lineEnd + 1;
    }
    return place(text, at, block, indentAt(text, statement.start));
  }

  /** 줄 앞이 빈칸뿐이면 줄 머리에 글을 넣고, 아니면 줄을 바꿔 넣은 뒤 원래 글 앞을 tailIndent 로 맞춘다. */
  private static Insert place(String text, int at, String block, String tailIndent) {
    if (blankBefore(text, at)) return new Insert(lineStart(text, at), block);
    return new Insert(at, "\n" + block + tailIndent);
  }

  private static MapperDoc.Node find(MapperDoc.Node n, String name) {
    for (MapperDoc.Node c : n.children) {
      if (name.equals(c.name)) return c;
      MapperDoc.Node inner = c.name == null ? null : find(c, name);
      if (inner != null) return inner;
    }
    return null;
  }

  // ---- 문서 글자 위치 (PagingGen 도 쓴다) ----

  /** start 의 여는 태그 끝(&gt; 다음). 스스로 닫히거나 끝을 찾지 못하면 -1. 따옴표 안의 &gt; 는 건너뛴다. */
  static int openEnd(String text, int start) {
    char quote = 0;
    for (int i = start + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return text.charAt(i - 1) == '/' ? -1 : i + 1;
      }
    }
    return -1;
  }

  /** 요소의 닫는 태그(&lt;/이름&gt;) 시작. 없으면 -1. */
  static int closeStart(String text, MapperDoc.Node n) {
    int open = openEnd(text, n.start);
    if (open < 0 || n.end > text.length()) return -1;
    int k = text.lastIndexOf("</", n.end - 1);
    if (k < open) return -1;
    String tag = text.substring(k + 2, n.end - 1).trim();
    return text.charAt(n.end - 1) == '>' && tag.equals(n.name) ? k : -1;
  }

  /** at 이 있는 줄의 앞 빈칸. */
  static String indentAt(String text, int at) {
    int from = lineStart(text, at);
    int i = from;
    while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
    return text.substring(from, i);
  }

  static int lineStart(String text, int at) {
    return text.lastIndexOf('\n', at - 1) + 1;
  }

  private static boolean blankBefore(String text, int at) {
    return text.substring(lineStart(text, at), at).isBlank();
  }
}
