package ei.db.mybatis;

import java.util.ArrayList;
import java.util.List;

/**
 * 매퍼 XML 문장마다 SQL 안에 「/* UserMapper.selectUserList *&#47;」 아이디 주석을 넣을 자리.
 * 주석은 문장 본문 첫 줄(여는 태그 · CDATA · XML 주석 다음) 위에 같은 들여쓰기로 한 줄을 따로 넣는다. SQL 글 안이라 DB 로 함께 넘어간다.
 * SQL 이 여는 태그와 같은 줄에서 시작하면 그 앞에 붙여 넣는다. Oracle 힌트는 SELECT 뒤에 그대로 남는다.
 * 문장 안에 같은 아이디 글이 이미 있으면 건너뛴다. IntelliJ 클래스를 쓰지 않는다.
 */
public final class SqlIdComment {
  private SqlIdComment() {}

  /** offset 에 text 를 넣는다. */
  public record Insert(int offset, String text) {}

  private static final String CDATA_OPEN = "<![CDATA[";
  private static final String XML_COMMENT_OPEN = "<!--";
  private static final String XML_COMMENT_CLOSE = "-->";

  /** 문장 아이디 주석의 이름. namespace 가 없으면 fileBase(확장자 뺀 파일 이름)를 앞에 쓴다. */
  public static String label(MapperDoc doc, String fileBase, String id) {
    String prefix = doc.namespace == null || doc.namespace.isBlank() ? fileBase : doc.shortNamespace();
    return prefix + "." + id;
  }

  /** 넣을 자리들(문서 앞쪽부터). text 는 doc 을 만든 그 문서 글이어야 한다. */
  public static List<Insert> plan(CharSequence text, MapperDoc doc, String fileBase) {
    List<Insert> out = new ArrayList<>();
    for (MapperDoc.Node s : doc.statements) {
      String label = label(doc, fileBase, s.attr("id"));
      int end = Math.min(s.end, text.length());
      if (text.subSequence(s.start, end).toString().contains(label)) continue;
      int body = openTagEnd(text, s.start, end);
      if (body < 0) continue;
      Insert at = insertAt(text, body, end, label);
      if (at != null) out.add(at);
    }
    return out;
  }

  /** 여는 태그의 「>」 다음 위치. 따옴표 안의 「>」는 건너뛴다. 스스로 닫힌 태그거나 찾지 못하면 -1. */
  private static int openTagEnd(CharSequence t, int from, int end) {
    char quote = 0;
    for (int i = from; i < end; i++) {
      char c = t.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return i > from && t.charAt(i - 1) == '/' ? -1 : i + 1;
      }
    }
    return -1;
  }

  private static Insert insertAt(CharSequence t, int body, int end, String label) {
    String comment = "/* " + label + " */";
    int p = skipSpaceAndXmlComments(t, body, end);
    if (startsWith(t, p, CDATA_OPEN)) p = skipSpaceAndXmlComments(t, p + CDATA_OPEN.length(), end);
    if (p >= end) return null;
    int lineStart = p;
    while (lineStart > body && t.charAt(lineStart - 1) != '\n') lineStart--;
    if (lineStart == body) return new Insert(p, comment + " ");
    String indent = t.subSequence(lineStart, p).toString();
    return new Insert(p, comment + "\n" + indent);
  }

  private static int skipSpaceAndXmlComments(CharSequence t, int p, int end) {
    while (true) {
      p = skipSpace(t, p, end);
      if (!startsWith(t, p, XML_COMMENT_OPEN)) return p;
      int close = indexOf(t, XML_COMMENT_CLOSE, p + XML_COMMENT_OPEN.length(), end);
      if (close < 0) return end;
      p = close + XML_COMMENT_CLOSE.length();
    }
  }

  private static int skipSpace(CharSequence t, int p, int end) {
    while (p < end && Character.isWhitespace(t.charAt(p))) p++;
    return p;
  }

  private static boolean startsWith(CharSequence t, int p, String s) {
    return p + s.length() <= t.length() && t.subSequence(p, p + s.length()).toString().equals(s);
  }

  private static int indexOf(CharSequence t, String s, int from, int end) {
    for (int i = from; i + s.length() <= end; i++) {
      if (startsWith(t, i, s)) return i;
    }
    return -1;
  }
}
