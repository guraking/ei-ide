package ei.db.console;

import ei.db.mybatis.SqlValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 콘솔 문장의 이름 자리 찾기 · 바꾸기. 순수 자바.
 * 자리: :name, #{name}(MyBatis, #{name,jdbcType=..} 은 쉼표 앞이 이름), ${name}(글자 그대로 치환).
 * 문자열 · 따옴표 이름 · 주석 · PostgreSQL 의 :: 형변환 · $tag$ 본문 안은 자리로 보지 않는다.
 * 트리거 본문의 :NEW. · :OLD. 와 :1 같은 숫자 자리도 자리로 보지 않는다.
 */
public final class SqlParams {
  private SqlParams() {}

  public enum Kind { COLON, MYBATIS, RAW }

  /** text 안의 [start, end) 자리. */
  public record Param(String name, Kind kind, int start, int end) {
    /** 대화상자에 보이는 쓴 꼴. */
    public String label() {
      return switch (kind) {
        case COLON -> ":" + name;
        case MYBATIS -> "#{" + name + "}";
        case RAW -> "${" + name + "}";
      };
    }
  }

  /** ? 로 바꾼 문장과 ? 순서대로의 값. */
  public record Bound(String sql, List<Object> binds) {}

  public static List<Param> find(String sql) {
    List<Param> out = new ArrayList<>();
    int n = sql.length();
    int i = 0;
    while (i < n) {
      char ch = sql.charAt(i);
      char next = i + 1 < n ? sql.charAt(i + 1) : 0;
      if (ch == '-' && next == '-') {
        int k = sql.indexOf('\n', i);
        i = k < 0 ? n : k;
      } else if (ch == '/' && next == '*') {
        int k = sql.indexOf("*/", i + 2);
        i = k < 0 ? n : k + 2;
      } else if (ch == ':' && next == ':') {
        i += 2;
      } else if (ch == ':' && isNameStart(next) && (i == 0 || !isIdent(sql.charAt(i - 1)))) {
        int j = i + 1;
        while (j < n && isIdent(sql.charAt(j))) j++;
        String name = sql.substring(i + 1, j);
        if (!isRowRef(sql, name, j)) out.add(new Param(name, Kind.COLON, i, j));
        i = j;
      } else if ((ch == '#' || ch == '$') && next == '{') {
        int close = sql.indexOf('}', i + 2);
        if (close < 0) return out;
        String name = sql.substring(i + 2, close).split(",", 2)[0].trim();
        if (!name.isEmpty()) out.add(new Param(name, ch == '#' ? Kind.MYBATIS : Kind.RAW, i, close + 1));
        i = close + 1;
      } else {
        int end = quotedEnd(sql, i);
        i = end > i ? end : i + 1;
      }
    }
    return out;
  }

  /** 이름별 첫 자리(대화상자 줄 순서). */
  public static List<Param> distinct(List<String> sqls) {
    Map<String, Param> out = new LinkedHashMap<>();
    for (String sql : sqls) {
      for (Param p : find(sql)) out.putIfAbsent(p.name(), p);
    }
    return new ArrayList<>(out.values());
  }

  /**
   * 자리를 바꾼다. values 는 이름 → 입력 글. :name · #{name} 은 ? 와 value(입력 글), ${name} 은 입력 글 그대로.
   * 자리가 없으면 binds 는 null(값 없이 실행).
   */
  public static Bound apply(String sql, Map<String, String> values) {
    List<Param> params = find(sql);
    if (params.isEmpty()) return new Bound(sql, null);
    StringBuilder sb = new StringBuilder();
    List<Object> binds = new ArrayList<>();
    int last = 0;
    for (Param p : params) {
      String text = values.getOrDefault(p.name(), "");
      sb.append(sql, last, p.start());
      if (p.kind() == Kind.RAW) {
        sb.append(text);
      } else {
        sb.append('?');
        binds.add(value(text));
      }
      last = p.end();
    }
    sb.append(sql.substring(last));
    return new Bound(sb.toString(), binds);
  }

  /** 비면 NULL, 'abc' 는 문자열, 따옴표 없는 숫자는 숫자(앞 0 이 있는 코드 값은 문자열), 날짜 꼴은 날짜, 나머지 문자열. */
  public static Object value(String text) {
    return SqlValues.parse(text, false);
  }

  /** :NEW.칼럼 · :OLD.칼럼(트리거 행 참조). */
  private static boolean isRowRef(String sql, String name, int end) {
    String up = name.toUpperCase(Locale.ROOT);
    return (up.equals("NEW") || up.equals("OLD")) && end < sql.length() && sql.charAt(end) == '.';
  }

  private static boolean isNameStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isIdent(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
  }

  /** i 에서 시작하는 ' " ` q'[ ]' $tag$ 의 끝 다음 위치. 따옴표가 아니면 i, 닫히지 않으면 글 끝. */
  private static int quotedEnd(String text, int i) {
    int n = text.length();
    char ch = text.charAt(i);
    boolean wordStart = i == 0 || !isIdent(text.charAt(i - 1));
    if ((ch == 'q' || ch == 'Q') && wordStart && i + 2 < n && text.charAt(i + 1) == '\'') {
      char open = text.charAt(i + 2);
      int k = "([{<".indexOf(open);
      char close = k >= 0 ? ")]}>".charAt(k) : open;
      int end = text.indexOf(close + "'", i + 3);
      return end < 0 ? n : end + 2;
    }
    if (ch == '\'' || ch == '"' || ch == '`') {
      int j = i + 1;
      while (j < n) {
        if (text.charAt(j) == ch) {
          if (j + 1 < n && text.charAt(j + 1) == ch) { j += 2; continue; }
          return j + 1;
        }
        j++;
      }
      return n;
    }
    if (ch == '$' && wordStart) {
      int j = i + 1;
      while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
      if (j < n && text.charAt(j) == '$') {
        String tag = text.substring(i, j + 1);
        int end = text.indexOf(tag, j + 1);
        return end < 0 ? n : end + tag.length();
      }
    }
    return i;
  }
}
