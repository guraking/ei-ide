package ei.db.console;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.regex.Pattern;

/**
 * 「값 크게 보기」의 값 종류 알아보기와 줄 맞춤. IntelliJ 에 기대지 않는다(JDK 만 쓴다).
 * JSON · XML 이 아니거나 깨져 있으면 null 을 돌려주어 부르는 쪽이 원문을 그대로 보이게 한다. 예외를 던지지 않는다.
 */
final class ValueFormat {
  private ValueFormat() {}

  enum Kind { JSON, XML }

  /** 줄 맞춘 글과 그 종류. */
  record Formatted(Kind kind, String text) {}

  private static final String INDENT = "  ";
  private static final int INDENT_WIDTH = INDENT.length();
  /** JSON 의 따옴표 없는 값: 숫자 · true · false · null. */
  private static final Pattern JSON_LITERAL = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?|true|false|null");
  /** 이보다 깊게 겹친 JSON 은 줄을 맞추지 않는다(들여쓰기가 깊이의 제곱으로 늘어 메모리를 다 쓴다). */
  static final int MAX_JSON_DEPTH = 500;
  private static final String JSON_PUNCTUATION = "{}[],:\"";
  private static final String XML_DECLARATION_START = "<?xml";
  private static final String XML_DECLARATION_END = "?>";
  private static final String NO_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
  private static final String INDENT_AMOUNT = "{http://xml.apache.org/xslt}indent-amount";
  private static final String YES = "yes";

  /** 앞뒤 공백을 뺀 글이 { · [ 로 시작하면 JSON, < 로 시작하면 XML 로 줄을 맞춘다. 둘 다 아니거나 깨졌으면 null. */
  static Formatted format(String raw) {
    String s = raw.strip();
    if (s.isEmpty()) return null;
    char first = s.charAt(0);
    if (first == '{' || first == '[') {
      String json = json(s);
      return json == null ? null : new Formatted(Kind.JSON, json);
    }
    if (first == '<') {
      String xml = xml(s);
      return xml == null ? null : new Formatted(Kind.XML, xml);
    }
    return null;
  }

  /**
   * JSON 을 토큰만 훑어 두 칸 들여쓰기로 다시 쓴다(문자열 안은 그대로). 빈 객체 · 배열은 한 줄({} · []).
   * 괄호 짝이 안 맞거나, 문자열이 닫히지 않았거나, 따옴표 없는 값이 숫자 · true · false · null 이 아니거나,
   * 맨 바깥 값 뒤에 글이 더 있거나, MAX_JSON_DEPTH 보다 깊으면 null. 쉼표 · 쌍점의 자리까지는 따지지 않는다.
   */
  static String json(String text) {
    StringBuilder out = new StringBuilder(text.length() + text.length() / 4);
    StringBuilder stack = new StringBuilder();
    int n = text.length();
    int i = 0;
    boolean closed = false;
    while (i < n) {
      char ch = text.charAt(i);
      if (Character.isWhitespace(ch)) { i++; continue; }
      if (closed) return null;
      if (ch == '"') {
        int end = stringEnd(text, i);
        if (end < 0 || stack.isEmpty()) return null;
        out.append(text, i, end + 1);
        i = end + 1;
      } else if (ch == '{' || ch == '[') {
        char close = ch == '{' ? '}' : ']';
        out.append(ch);
        int next = skipBlanks(text, i + 1);
        if (next < n && text.charAt(next) == close) {
          out.append(close);
          i = next + 1;
          closed = stack.isEmpty();
          continue;
        }
        if (stack.length() >= MAX_JSON_DEPTH) return null;
        stack.append(close);
        newline(out, stack.length());
        i++;
      } else if (ch == '}' || ch == ']') {
        int top = stack.length() - 1;
        if (top < 0 || stack.charAt(top) != ch) return null;
        stack.setLength(top);
        newline(out, top);
        out.append(ch);
        closed = top == 0;
        i++;
      } else if (ch == ',') {
        if (stack.isEmpty()) return null;
        out.append(ch);
        newline(out, stack.length());
        i++;
      } else if (ch == ':') {
        if (stack.isEmpty()) return null;
        out.append(": ");
        i++;
      } else {
        int end = i;
        while (end < n && !Character.isWhitespace(text.charAt(end)) && JSON_PUNCTUATION.indexOf(text.charAt(end)) < 0) end++;
        if (stack.isEmpty() || !JSON_LITERAL.matcher(text.substring(i, end)).matches()) return null;
        out.append(text, i, end);
        i = end;
      }
    }
    return closed ? out.toString() : null;
  }

  /** open 자리의 따옴표로 시작한 문자열이 닫히는 자리. 닫히지 않으면 -1. */
  private static int stringEnd(String text, int open) {
    for (int i = open + 1; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '\\') i++;
      else if (ch == '"') return i;
    }
    return -1;
  }

  private static int skipBlanks(String text, int from) {
    int i = from;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
    return i;
  }

  private static void newline(StringBuilder out, int depth) {
    out.append('\n');
    for (int i = 0; i < depth; i++) out.append(INDENT);
  }

  /**
   * XML 을 두 칸 들여쓰기로 다시 쓴다. 공백뿐인 글 노드는 지운다(보기용이라 공백만 든 요소는 빈 요소로 보인다).
   * 원문에 XML 선언이 있으면 그 선언을 그대로 맨 위 줄에 둔다.
   * DOCTYPE 이 있거나(외부 엔티티 · 엔티티 폭탄을 막으려고 아예 받지 않는다) 깨진 XML 이면 null. 외부 DTD · 스키마 · 스타일시트는 읽지 않는다.
   */
  static String xml(String text) {
    try {
      // IDE 클래스 경로의 다른 구현이 아니라 JDK 구현을 쓴다(아래 보안 설정 이름이 JDK 구현의 것이다).
      DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setFeature(NO_DOCTYPE, true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new Strict());
      Document document = builder.parse(new InputSource(new StringReader(text)));
      dropBlankText(document);
      TransformerFactory transformers = TransformerFactory.newDefaultInstance();
      transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      Transformer transformer = transformers.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, YES);
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, YES);
      transformer.setOutputProperty(INDENT_AMOUNT, String.valueOf(INDENT_WIDTH));
      StringWriter out = new StringWriter(text.length() + text.length() / 4);
      transformer.transform(new DOMSource(document), new StreamResult(out));
      String body = out.toString().replace("\r\n", "\n").strip();
      int declarationEnd = text.startsWith(XML_DECLARATION_START) ? text.indexOf(XML_DECLARATION_END) : -1;
      return declarationEnd < 0 ? body : text.substring(0, declarationEnd + XML_DECLARATION_END.length()) + "\n" + body;
    } catch (Exception | StackOverflowError e) {
      return null;
    }
  }

  /** 공백뿐인 글 노드를 지운다(들여쓰기가 원문의 줄바꿈과 겹쳐 빈 줄이 생기지 않게). */
  private static void dropBlankText(Node node) {
    Node child = node.getFirstChild();
    while (child != null) {
      Node next = child.getNextSibling();
      if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) node.removeChild(child);
      else dropBlankText(child);
      child = next;
    }
  }

  /** 기본 처리기는 오류를 표준 오류에 찍는다. 찍지 않고 모두 실패로 돌린다. */
  private static final class Strict implements ErrorHandler {
    @Override public void warning(SAXParseException e) { }
    @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
    @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
  }
}
