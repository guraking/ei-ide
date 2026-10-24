package ei.db.mybatis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 매퍼 XML 을 문서 글자에서 바로 읽는다(XML PSI 를 쓰지 않는다).
 * 주석 · 처리 지시 · DOCTYPE 은 건너뛰고, CDATA 는 그대로, 일반 글과 속성 값은 엔티티를 풀어 담는다.
 * 편집 중이라 닫히지 않은 태그는 문서 끝에서 닫힌 것으로 본다.
 */
public final class MapperDoc {
  public static final List<String> STATEMENT_TAGS = List.of("select", "insert", "update", "delete");

  /** 요소이거나(name != null) 글 조각(text != null). start · end 는 문서 안의 위치(end 는 끝 다음). */
  public static final class Node {
    public final String name;
    public final String text;
    public final Map<String, String> attrs = new LinkedHashMap<>();
    public final List<Node> children = new ArrayList<>();
    public final int start;
    public int end;

    Node(String name, String text, int start, int end) {
      this.name = name;
      this.text = text;
      this.start = start;
      this.end = end;
    }

    public String attr(String key) { return attrs.get(key); }
  }

  public final Node root;
  public final String namespace;
  public final List<Node> statements = new ArrayList<>();
  public final Map<String, Node> fragments = new LinkedHashMap<>();

  private MapperDoc(Node root) {
    this.root = root;
    Node mapper = null;
    for (Node n : root.children) {
      if ("mapper".equals(n.name)) mapper = n;
    }
    namespace = mapper == null ? null : mapper.attr("namespace");
    if (mapper == null) return;
    for (Node n : mapper.children) {
      if (n.name == null) continue;
      if (STATEMENT_TAGS.contains(n.name) && n.attr("id") != null) statements.add(n);
      if ("sql".equals(n.name) && n.attr("id") != null) fragments.put(n.attr("id"), n);
    }
  }

  /** &lt;mapper&gt; 가 없으면 namespace 는 null, 문장 목록은 빈 채로 돌려준다. */
  public static MapperDoc parse(CharSequence text) {
    return new MapperDoc(new Parser(text.toString()).parse());
  }

  /** offset 을 감싸는 문장. 없으면 null. */
  public Node statementAt(int offset) {
    for (Node s : statements) {
      if (offset >= s.start && offset <= s.end) return s;
    }
    return null;
  }

  public Node statement(String id) {
    for (Node s : statements) {
      if (id.equals(s.attr("id"))) return s;
    }
    return null;
  }

  /** 네임스페이스의 마지막 마디(예: com.x.OrderMapper → OrderMapper). */
  public String shortNamespace() {
    if (namespace == null) return "";
    return namespace.substring(namespace.lastIndexOf('.') + 1);
  }

  /** &lt;include refid&gt; 의 대상. 같은 파일 것만 찾으며 "네임스페이스.id" 꼴도 받는다. */
  public Node fragment(String refid) {
    if (refid == null) return null;
    if (namespace != null && refid.startsWith(namespace + ".")) refid = refid.substring(namespace.length() + 1);
    return fragments.get(refid);
  }

  /**
   * n 안의 글 조각을 문서 차례로 이은 것(요소 경계마다 빈칸 하나). 같은 파일의 &lt;include&gt; 는 그 sql 조각의 글로 바꾸고,
   * 자기 자신을 다시 부르는 조각은 한 번만 편다.
   */
  public String plainText(Node n) {
    StringBuilder b = new StringBuilder();
    appendText(n, b, new ArrayList<>());
    return b.toString();
  }

  private void appendText(Node n, StringBuilder b, List<Node> open) {
    for (Node c : n.children) {
      if (c.name == null) {
        b.append(c.text);
        continue;
      }
      Node target = "include".equals(c.name) ? fragment(c.attr("refid")) : c;
      if (target != null && !open.contains(target)) {
        open.add(target);
        appendText(target, b, open);
        open.remove(open.size() - 1);
      }
      b.append(' ');
    }
  }

  public static String decodeEntities(String s) {
    if (s.indexOf('&') < 0) return s;
    StringBuilder b = new StringBuilder(s.length());
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int semi = c == '&' ? s.indexOf(';', i) : -1;
      String rep = semi > i ? entity(s.substring(i + 1, semi)) : null;
      if (rep == null) {
        b.append(c);
        i++;
      } else {
        b.append(rep);
        i = semi + 1;
      }
    }
    return b.toString();
  }

  private static String entity(String e) {
    switch (e) {
      case "lt": return "<";
      case "gt": return ">";
      case "amp": return "&";
      case "quot": return "\"";
      case "apos": return "'";
      default:
        try {
          if (e.startsWith("#x")) return new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
          if (e.startsWith("#")) return new String(Character.toChars(Integer.parseInt(e.substring(1))));
        } catch (IllegalArgumentException ignored) {
          // 잘못된 문자 참조는 글자 그대로 둔다.
        }
        return null;
    }
  }

  private static final class Parser {
    private final String s;
    private int i;

    Parser(String s) { this.s = s; }

    Node parse() {
      Node doc = new Node("#document", null, 0, s.length());
      List<Node> stack = new ArrayList<>();
      stack.add(doc);
      while (i < s.length()) {
        Node top = stack.get(stack.size() - 1);
        if (s.startsWith("<!--", i)) {
          i = skipPast("-->");
        } else if (s.startsWith("<![CDATA[", i)) {
          int from = i + "<![CDATA[".length();
          int close = s.indexOf("]]>", from);
          int stop = close < 0 ? s.length() : close;
          top.children.add(new Node(null, s.substring(from, stop), i, close < 0 ? stop : close + 3));
          i = close < 0 ? s.length() : close + 3;
        } else if (s.startsWith("<?", i) || s.startsWith("<!", i)) {
          i = skipPast(">");
        } else if (s.startsWith("</", i)) {
          int close = s.indexOf('>', i);
          int stop = close < 0 ? s.length() : close + 1;
          String name = s.substring(i + 2, close < 0 ? s.length() : close).trim();
          for (int k = stack.size() - 1; k > 0; k--) {
            if (stack.get(k).name.equals(name)) {
              while (stack.size() > k) stack.remove(stack.size() - 1).end = stop;
              break;
            }
          }
          i = stop;
        } else if (s.charAt(i) == '<' && i + 1 < s.length() && isNameStart(s.charAt(i + 1))) {
          Node el = startTag();
          top.children.add(el);
          if (el.end < 0) stack.add(el);
        } else {
          int next = s.indexOf('<', i + 1);
          int stop = next < 0 ? s.length() : next;
          top.children.add(new Node(null, decodeEntities(s.substring(i, stop)), i, stop));
          i = stop;
        }
      }
      for (int k = stack.size() - 1; k > 0; k--) stack.get(k).end = s.length();
      return doc;
    }

    /** 여는 태그 하나를 읽는다. 스스로 닫히면 end 를 정하고, 아니면 end = -1 로 돌려준다. */
    private Node startTag() {
      int begin = i;
      i++;
      String name = name();
      Node el = new Node(name, null, begin, -1);
      while (i < s.length()) {
        skipSpace();
        if (i >= s.length()) break;
        if (s.startsWith("/>", i)) {
          i += 2;
          el.end = i;
          return el;
        }
        if (s.charAt(i) == '>') {
          i++;
          return el;
        }
        if (s.charAt(i) == '<') return el;
        String key = name();
        if (key.isEmpty()) {
          i++;
          continue;
        }
        skipSpace();
        String value = "";
        if (i < s.length() && s.charAt(i) == '=') {
          i++;
          skipSpace();
          if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
            char q = s.charAt(i);
            int close = s.indexOf(q, i + 1);
            int stop = close < 0 ? s.length() : close;
            value = decodeEntities(s.substring(i + 1, stop));
            i = close < 0 ? s.length() : close + 1;
          }
        }
        el.attrs.put(key, value);
      }
      return el;
    }

    private String name() {
      int from = i;
      while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || "_-:.".indexOf(s.charAt(i)) >= 0)) i++;
      return s.substring(from, i);
    }

    private void skipSpace() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private int skipPast(String close) {
      int k = s.indexOf(close, i);
      return k < 0 ? s.length() : k + close.length();
    }

    private static boolean isNameStart(char c) { return Character.isLetter(c) || c == '_'; }
  }
}
