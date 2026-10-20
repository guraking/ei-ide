package ei.db.gen;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 코드 표 조회 결과 → enum 글. 원래 SQL · 접속 이름 · 코드 칼럼을 클래스 Javadoc 에 남겨 「enum 다시 맞추기」가 읽는다.
 * IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 */
public final class EnumGen {
  private EnumGen() {}

  public static final String SQL_TAG = "@ei-sql";
  public static final String CONNECTION_TAG = "@ei-connection";
  public static final String COLUMNS_TAG = "@ei-columns";
  private static final String EMPTY_NAME = "CODE";
  private static final String DIGIT_PREFIX = "C";
  private static final String DUPLICATE_MARK = "_";
  private static final String COLUMN_SEPARATOR = ", ";
  private static final String INDENT = "    ";
  /** Javadoc 안에서 별표 · 빗금이 주석을 닫지 않게 빗금을 문자 참조로 바꾼다. & 도 함께 바꿔야 되돌릴 때 원래 &#47; 와 섞이지 않는다. */
  private static final Pattern ESCAPED = Pattern.compile("&(#47|amp);");
  /** 문자열 리터럴이나 한 줄 주석. 한 줄로 합칠 때 -- 주석이 뒤 글을 먹지 않게 블록 주석으로 바꾼다. */
  private static final Pattern STRING_OR_LINE_COMMENT = Pattern.compile("'(?:[^']|'')*'|--[^\\r\\n]*");
  private static final Pattern TRAILING_SEMICOLONS = Pattern.compile("[\\s;]+$");

  /** 상수 하나. code · label 은 null 이 아니다. */
  public record Item(String code, String label) {}

  /**
   * rows 의 codeCol · labelCol 값으로 상수를 만든다. 코드가 null 인 행은 건너뛴다. 이름이 null 이면 빈 글자.
   * 같은 코드가 다시 나와도 상수는 따로 만든다(of 는 앞의 것을 돌려준다).
   */
  public static List<Item> items(List<List<Object>> rows, int codeCol, int labelCol) {
    List<Item> out = new ArrayList<>();
    for (List<Object> row : rows) {
      String code = value(row, codeCol);
      if (code == null) continue;
      String label = value(row, labelCol);
      out.add(new Item(code, label == null ? "" : label));
    }
    return out;
  }

  private static String value(List<Object> row, int col) {
    Object v = col < row.size() ? row.get(col) : null;
    if (v == null) return null;
    return v instanceof BigDecimal d ? d.toPlainString() : v.toString();
  }

  /** 코드 값 → 상수 이름: 대문자, [A-Z0-9_] 밖은 밑줄(이어진 밑줄은 하나로, 앞뒤 밑줄은 뗌), 숫자로 시작하면 C, 비면 CODE. */
  public static String constName(String code) {
    String s = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_").replaceAll("_{2,}", "_").replaceAll("^_|_$", "");
    if (s.isEmpty()) return EMPTY_NAME;
    return Character.isDigit(s.charAt(0)) ? DIGIT_PREFIX + s : s;
  }

  /** 순서대로 상수 이름. 겹치면 둘째부터 _2, _3 … (그 이름도 이미 있으면 다음 번호). */
  public static List<String> constNames(List<Item> items) {
    Map<String, Integer> seen = new HashMap<>();
    List<String> used = new ArrayList<>();
    for (Item it : items) {
      String base = constName(it.code());
      String name = base;
      int n = seen.getOrDefault(base, 1);
      while (used.contains(name)) {
        n++;
        name = base + DUPLICATE_MARK + n;
      }
      seen.put(base, n);
      used.add(name);
    }
    return used;
  }

  /** SQL 을 한 줄로: -- 주석은 블록 주석으로, 공백은 하나로, 끝의 ; 는 뗀다. */
  public static String oneLine(String sql) {
    Matcher m = STRING_OR_LINE_COMMENT.matcher(sql);
    StringBuilder b = new StringBuilder();
    while (m.find()) {
      String part = m.group();
      String rep = part.startsWith("--") ? "/*" + part.substring(2).replace("*/", "* /") + " */" : part;
      m.appendReplacement(b, Matcher.quoteReplacement(rep));
    }
    m.appendTail(b);
    return TRAILING_SEMICOLONS.matcher(b.toString().replaceAll("\\s+", " ").strip()).replaceAll("");
  }

  static String escape(String s) {
    return s.replace("&", "&amp;").replace("*/", "*&#47;");
  }

  static String unescape(String s) {
    Matcher m = ESCAPED.matcher(s);
    StringBuilder b = new StringBuilder();
    while (m.find()) m.appendReplacement(b, "#47".equals(m.group(1)) ? "/" : "&");
    m.appendTail(b);
    return b.toString();
  }

  /** 자바 문자열 리터럴 안에 넣을 글. */
  private static String literal(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (ch < ' ') b.append(String.format("\\u%04x", (int) ch)); else b.append(ch);
        }
      }
    }
    return b.append('"').toString();
  }

  /** 다시 맞출 때 쓰는 정보. columns 는 코드 · 이름 칼럼 이름(Javadoc 에 없으면 null: 앞 두 칼럼을 쓴다). */
  public record Source(String sql, String connection, List<String> columns) {}

  /** enum 파일 전체 글. pkg 가 비면 package 줄을 쓰지 않는다. src.columns 가 null 이면 @ei-columns 를 쓰지 않는다. */
  public static String text(String pkg, String cls, List<Item> items, Source src) {
    StringBuilder b = new StringBuilder();
    if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
    b.append("/**\n");
    b.append(" * 코드 표 enum. 편집기 우클릭 「enum 다시 맞추기」가 아래 SQL 을 다시 실행해 상수를 새로 만든다.\n");
    b.append(" *\n");
    b.append(" * ").append(SQL_TAG).append(' ').append(escape(oneLine(src.sql()))).append('\n');
    b.append(" * ").append(CONNECTION_TAG).append(' ').append(escape(src.connection().strip())).append('\n');
    if (src.columns() != null) {
      b.append(" * ").append(COLUMNS_TAG).append(' ').append(escape(String.join(COLUMN_SEPARATOR, src.columns()))).append('\n');
    }
    b.append(" */\n");
    b.append("public enum ").append(cls).append(" {\n");
    List<String> names = constNames(items);
    for (int i = 0; i < items.size(); i++) {
      Item it = items.get(i);
      b.append(INDENT).append(names.get(i)).append('(').append(literal(it.code())).append(", ").append(literal(it.label())).append(')')
       .append(i == items.size() - 1 ? "" : ",").append('\n');
    }
    b.append(INDENT).append(";\n\n");
    b.append(INDENT).append("private final String code;\n");
    b.append(INDENT).append("private final String label;\n\n");
    b.append(INDENT).append(cls).append("(String code, String label) {\n");
    b.append(INDENT).append(INDENT).append("this.code = code;\n");
    b.append(INDENT).append(INDENT).append("this.label = label;\n");
    b.append(INDENT).append("}\n\n");
    b.append(INDENT).append("public String getCode() {\n");
    b.append(INDENT).append(INDENT).append("return code;\n");
    b.append(INDENT).append("}\n\n");
    b.append(INDENT).append("public String getLabel() {\n");
    b.append(INDENT).append(INDENT).append("return label;\n");
    b.append(INDENT).append("}\n\n");
    b.append(INDENT).append("/** code 가 같은 첫 상수. 없으면 null. */\n");
    b.append(INDENT).append("public static ").append(cls).append(" of(String code) {\n");
    b.append(INDENT).append(INDENT).append("for (").append(cls).append(" v : values()) {\n");
    b.append(INDENT).append(INDENT).append(INDENT).append("if (v.code.equals(code)) return v;\n");
    b.append(INDENT).append(INDENT).append("}\n");
    b.append(INDENT).append(INDENT).append("return null;\n");
    b.append(INDENT).append("}\n");
    return b.append("}\n").toString();
  }

  /** 파일 글에서 태그 값(첫 것). 없으면 null. */
  private static String tag(String fileText, String tag) {
    Matcher m = Pattern.compile("^\\s*\\*?\\s*" + Pattern.quote(tag) + "[ \\t]+(.*?)\\s*(\\*/)?\\s*$", Pattern.MULTILINE).matcher(fileText);
    return m.find() ? unescape(m.group(1)) : null;
  }

  /** Javadoc 의 @ei-sql · @ei-connection · @ei-columns. SQL 이나 접속이 없으면 null. */
  public static Source source(String fileText) {
    String sql = tag(fileText, SQL_TAG);
    String conn = tag(fileText, CONNECTION_TAG);
    if (sql == null || sql.isBlank() || conn == null || conn.isBlank()) return null;
    String cols = tag(fileText, COLUMNS_TAG);
    List<String> columns = null;
    if (cols != null) {
      String[] parts = cols.split(",");
      if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) columns = List.of(parts[0].strip(), parts[1].strip());
    }
    return new Source(sql, conn, columns);
  }
}
