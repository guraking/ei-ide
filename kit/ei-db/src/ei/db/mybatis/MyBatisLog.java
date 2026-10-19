package ei.db.mybatis;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 콘솔에 찍힌 MyBatis 로그(==&gt;  Preparing: … / ==&gt; Parameters: …)를 실행 가능한 SQL 로 바꾼다.
 * Preparing 은 마지막 것을 쓰고, Parameters 는 그 뒤에서 같은 스레드([…])의 첫 줄을 쓴다(스레드가 안 보이면 바로 다음 것).
 */
public final class MyBatisLog {
  private static final String PREPARING = "Preparing:";
  private static final String PARAMETERS = "Parameters:";
  private static final String ARROW = "==>";
  /** value(Type) 의 끝: 닫는 괄호 뒤에 ", " 또는 줄 끝이 와야 한다. */
  private static final Pattern TYPE_END = Pattern.compile("\\(([A-Za-z_$][\\w.$]*)\\)(?=, |$)");
  private static final Pattern THREAD = Pattern.compile("\\[([^\\]]+)]");
  private static final String JAVA_DATE_FORMAT = "EEE MMM dd HH:mm:ss zzz yyyy";
  private static final int MAX_INCLUDE_DEPTH = 16;
  private static final Set<String> SQL_WORDS = Set.of("AND", "OR", "NOT", "IN", "LIKE", "BETWEEN", "IS", "VALUES",
                                                      "SET", "WHERE", "ESCAPE", "THEN", "WHEN", "ELSE");

  public static final class Entry {
    /** ? 가 든 SQL. */
    public String sql;
    /** 로그의 형식에 맞춰 바꾼 값(String · Long · BigDecimal · java.sql.Date · Timestamp · LocalDate · Boolean · null 등). */
    public final List<Object> values = new ArrayList<>();
    /** ==&gt; 앞의 로거 이름(예: c.e.o.mapper.OrderMapper.selectOrderItems). 없으면 null. */
    public String logger;
    /** Parameters 줄을 찾았는지. ? 가 없는 문장이면 없어도 된다. */
    public boolean hasParameters;
    /** Preparing 이 있던 줄 번호(0부터). parseAll 이 채우고, 그 밖에는 -1. */
    public int line = -1;

    public String runnableSql() { return SqlValues.inline(sql, values); }

    /** 로거의 끝에서 둘째 마디(매퍼 이름). 없으면 null. */
    public String mapperName() {
      String[] p = loggerParts();
      return p.length >= 2 ? p[p.length - 2] : null;
    }

    /** 로거의 마지막 마디(문장 id). 없으면 null. */
    public String statementId() {
      String[] p = loggerParts();
      return p.length >= 2 ? p[p.length - 1] : null;
    }

    /** 로거 앞부분(줄여 찍힌 패키지 포함, 문장 id 제외). */
    public String loggerNamespace() {
      return logger == null || !logger.contains(".") ? null : logger.substring(0, logger.lastIndexOf('.'));
    }

    private String[] loggerParts() { return logger == null ? new String[0] : logger.split("\\."); }
  }

  private MyBatisLog() {}

  public static Entry parse(String text) {
    List<String> lines = Arrays.asList(text.split("\r?\n"));
    return parse(lines, lines.size() - 1);
  }

  /** lines[0..maxPreparingLine] 에서 마지막 Preparing 을 찾는다. 없으면 null. Parameters 는 그 뒤 어디서든 찾는다. */
  public static Entry parse(List<String> lines, int maxPreparingLine) {
    int prep = -1;
    for (int k = Math.min(maxPreparingLine, lines.size() - 1); k >= 0; k--) {
      if (isLine(lines.get(k), PREPARING)) {
        prep = k;
        break;
      }
    }
    if (prep < 0) return null;
    String line = lines.get(prep);
    Entry e = new Entry();
    e.sql = after(line, PREPARING).trim();
    e.logger = logger(line);
    String thread = thread(line);
    for (int k = prep + 1; k < lines.size(); k++) {
      String l = lines.get(k);
      if (isLine(l, PREPARING) && sameSource(e, thread, l)) break;
      if (isLine(l, PARAMETERS) && sameSource(e, thread, l)) {
        e.values.addAll(parameters(after(l, PARAMETERS).replaceFirst("^ ", "")));
        e.hasParameters = true;
        break;
      }
    }
    return e;
  }

  /** 마지막 Preparing 부터 위로 최대 max 개를 줄 순서대로 읽는다. 각 Parameters 는 parse 와 같은 규칙으로 찾는다. */
  public static List<Entry> parseAll(List<String> lines, int max) {
    List<Integer> found = new ArrayList<>();
    for (int k = lines.size() - 1; k >= 0 && found.size() < max; k--) {
      if (isLine(lines.get(k), PREPARING)) found.add(0, k);
    }
    List<Entry> out = new ArrayList<>();
    for (int k : found) {
      Entry e = parse(lines, k);
      e.line = k;
      out.add(e);
    }
    return out;
  }

  private static boolean sameSource(Entry e, String thread, String line) {
    String t = thread(line);
    if (thread != null && t != null && !thread.equals(t)) return false;
    String lg = logger(line);
    return e.logger == null || lg == null || e.logger.equals(lg);
  }

  private static boolean isLine(String line, String marker) {
    int a = line.indexOf(ARROW);
    return a >= 0 && line.indexOf(marker, a) >= 0;
  }

  private static String after(String line, String marker) {
    return line.substring(line.indexOf(marker, line.indexOf(ARROW)) + marker.length());
  }

  /** ==&gt; 앞의 마지막 낱말에서 끝의 ':' '-' 를 뗀 것. 점이 없으면 로거가 아니라고 본다. */
  private static String logger(String line) {
    String head = line.substring(0, line.indexOf(ARROW)).trim().replaceAll("[\\s:\\-]+$", "");
    String last = head.substring(head.lastIndexOf(' ') + 1).trim();
    return last.contains(".") && !last.contains("[") ? last : null;
  }

  private static String thread(String line) {
    Matcher m = THREAD.matcher(line.substring(0, line.indexOf(ARROW)));
    return m.find() ? m.group(1).trim() : null;
  }

  /** "100231(Long), a, b(String), null" 을 값 목록으로. 값 안의 쉼표 · 괄호는 형식 표시 위치로 구별한다. */
  public static List<Object> parameters(String text) {
    List<Object> out = new ArrayList<>();
    String s = text.stripTrailing();
    int i = 0;
    while (i < s.length()) {
      if (s.startsWith("null", i) && (i + 4 == s.length() || s.startsWith(", ", i + 4))) {
        out.add(null);
        i += 4;
      } else {
        Matcher m = TYPE_END.matcher(s);
        if (!m.find(i)) {
          out.add(s.substring(i));
          break;
        }
        out.add(typed(s.substring(i, m.start()), m.group(1)));
        i = m.end();
      }
      if (s.startsWith(", ", i)) i += 2;
    }
    return out;
  }

  static Object typed(String v, String type) {
    try {
      switch (type) {
        case "Integer": case "Long": case "Short": case "Byte":
          return Long.valueOf(v.trim());
        case "BigDecimal": case "Double": case "Float": case "BigInteger":
          return new BigDecimal(v.trim());
        case "Boolean":
          return Boolean.valueOf(v.trim());
        case "LocalDate":
          return LocalDate.parse(v.trim());
        case "LocalDateTime":
          return LocalDateTime.parse(v.trim());
        case "Date": case "Timestamp":
          return date(v.trim());
        default:
          return v;
      }
    } catch (RuntimeException | ParseException e) {
      // 형식과 글이 맞지 않으면(사용자 정의 toString 등) 글 그대로 문자열로 넣는다.
      return v;
    }
  }

  private static Object date(String v) throws ParseException {
    if (v.length() == "yyyy-MM-dd".length()) return Date.valueOf(v);
    if (Character.isDigit(v.charAt(0))) return Timestamp.valueOf(v.replace('T', ' '));
    return new Timestamp(new SimpleDateFormat(JAVA_DATE_FORMAT, Locale.US).parse(v).getTime());
  }

  /**
   * 로그의 값을 매퍼 문장의 매개변수 이름에 나눠 준다(대화창 미리 채우기용, 이름 → 입력 글).
   * 로그 SQL 의 ? 마다 바로 앞의 칼럼 이름(키워드 · 괄호 · 쉼표 · ? 는 건너뜀)을 구하고,
   * 매퍼의 #{} 자리도 같은 방식으로 구해 차례대로 맞춘다. 맞는 자리가 없으면 다음 자리에 넣는다.
   * foreach 안의 자리는 collection 이름의 목록으로 모은다.
   */
  public static Map<String, String> prefill(MapperDoc doc, MapperDoc.Node statement, Entry entry) {
    List<String[]> slots = new ArrayList<>();
    StringBuilder flat = new StringBuilder();
    flatten(doc, statement, null, null, flat, slots, 0);
    List<String> logAnchors = anchors(entry.sql, "?");
    Map<String, List<Object>> lists = new LinkedHashMap<>();
    Map<String, String> out = new HashMap<>();
    int pointer = 0;
    for (int k = 0; k < logAnchors.size() && k < entry.values.size(); k++) {
      int found = -1;
      for (int j = pointer; j < slots.size(); j++) {
        if (slots.get(j)[2].equalsIgnoreCase(logAnchors.get(k))) {
          found = j;
          break;
        }
      }
      if (found < 0) found = pointer;
      if (found >= slots.size()) break;
      String[] slot = slots.get(found);
      Object v = entry.values.get(k);
      if (slot[1] != null) {
        lists.computeIfAbsent(slot[1], x -> new ArrayList<>()).add(v);
        pointer = found;
        boolean nextSame = k + 1 < logAnchors.size() && logAnchors.get(k + 1).equalsIgnoreCase(slot[2]);
        if (!nextSame) pointer = found + 1;
      } else {
        out.put(slot[0], SqlValues.format(v));
        pointer = found + 1;
      }
    }
    for (Map.Entry<String, List<Object>> l : lists.entrySet()) out.put(l.getKey(), SqlValues.format(l.getValue()));
    return out;
  }

  /** slots: {이름, foreach collection 또는 null, 앞 칼럼}. 매퍼 글을 태그 없이 이어 붙이며 #{} 를 ? 로 둔다. */
  private static void flatten(MapperDoc doc, MapperDoc.Node n, String foreach, String item, StringBuilder flat,
                              List<String[]> slots, int depth) {
    for (MapperDoc.Node c : n.children) {
      if (c.name == null) {
        String t = c.text;
        int i = 0;
        while (true) {
          int a = t.indexOf("#{", i);
          int close = a < 0 ? -1 : t.indexOf('}', a);
          if (close < 0) {
            flat.append(t, i, t.length());
            break;
          }
          flat.append(t, i, a).append(' ');
          List<String> before = anchors(flat + "?", "?");
          String anchor = before.isEmpty() ? "" : before.get(before.size() - 1);
          String inside = t.substring(a + 2, close);
          String name = (inside.contains(",") ? inside.substring(0, inside.indexOf(',')) : inside).trim();
          boolean inItem = foreach != null && (name.equals(item) || name.startsWith(item + "."));
          slots.add(new String[] {name, inItem ? foreach : null, anchor});
          flat.append("? ");
          i = close + 1;
        }
      } else if ("foreach".equals(c.name)) {
        flat.append(' ').append(c.attr("open") == null ? "" : c.attr("open")).append(' ');
        flatten(doc, c, c.attr("collection") == null ? null : c.attr("collection").trim(), c.attr("item"), flat, slots, depth);
        flat.append(' ').append(c.attr("close") == null ? "" : c.attr("close")).append(' ');
      } else if ("include".equals(c.name)) {
        MapperDoc.Node f = doc.fragment(c.attr("refid"));
        if (f != null && depth < MAX_INCLUDE_DEPTH) flatten(doc, f, foreach, item, flat, slots, depth + 1);
      } else if (!"selectKey".equals(c.name)) {
        flatten(doc, c, foreach, item, flat, slots, depth);
      }
    }
  }

  /** sql 의 각 marker(?) 바로 앞 칼럼 이름 목록. 문자열 · 주석 안의 ? 는 세지 않는다. */
  static List<String> anchors(String sql, String marker) {
    List<String> out = new ArrayList<>();
    String last = "";
    int i = 0;
    while (i < sql.length()) {
      int skip = SqlValues.skipQuotedOrComment(sql, i);
      if (skip > i) {
        i = skip;
        continue;
      }
      char c = sql.charAt(i);
      if (sql.startsWith(marker, i)) {
        out.add(last);
        i += marker.length();
      } else if (Character.isLetter(c) || c == '_') {
        int from = i;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || "_.$#".indexOf(sql.charAt(i)) >= 0)) i++;
        String word = sql.substring(from, i);
        if (!SQL_WORDS.contains(word.toUpperCase())) last = word;
      } else {
        i++;
      }
    }
    return out;
  }

  /** 줄여 찍힌 로거 앞부분(c.e.o.mapper.OrderMapper)이 네임스페이스와 마디마다 앞글자가 맞는지. */
  public static boolean loggerMatches(String loggerNamespace, String namespace) {
    if (loggerNamespace == null || namespace == null) return false;
    String[] a = loggerNamespace.split("\\."), b = namespace.split("\\.");
    if (a.length != b.length) return namespace.endsWith("." + a[a.length - 1]) || namespace.equals(a[a.length - 1]);
    for (int k = 0; k < a.length; k++) {
      if (!b[k].startsWith(a[k])) return false;
    }
    return true;
  }
}
