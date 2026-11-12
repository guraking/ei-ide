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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 콘솔에 찍힌 MyBatis 로그(==&gt;  Preparing: … / ==&gt; Parameters: …)를 실행 가능한 SQL 로 바꾼다.
 * log4jdbc 의 sqltiming 로그도 parseAll 이 읽는다(sqlTiming).
 * Preparing 은 마지막 것을 쓰고, Parameters 는 그 뒤에서 같은 스레드([…])의 첫 줄을 쓴다(스레드가 안 보이면 바로 다음 것).
 */
public final class MyBatisLog {
  private static final String PREPARING = "Preparing:";
  private static final String PARAMETERS = "Parameters:";
  private static final String ARROW = "==>";
  /** value(Type) 의 끝: 닫는 괄호 뒤에 ", " 또는 줄 끝이 와야 한다. */
  private static final Pattern TYPE_END = Pattern.compile("\\(([A-Za-z_$][\\w.$]*)\\)(?=, |$)");
  private static final Pattern THREAD = Pattern.compile("\\[([^\\]]+)]");
  /** log4jdbc 로거 이름 끝(jdbc.sqltiming · j.sqltiming)과 SQL 끝의 실행 시간 표시. */
  private static final String SQL_TIMING = "sqltiming";
  private static final Pattern EXECUTED = Pattern.compile("\\{executed in (\\d+) msec}\\s*$");
  /** sqltiming 뒤의 구분자와 연결 번호. */
  private static final Pattern TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d{1,3})?\\b");
  /** SQL 맨 앞 블록 주석 안의 이름(MyBatis 문장 표시, 예: 매퍼.문장id). */
  private static final Pattern HEAD_COMMENT = Pattern.compile("^\\s*/\\*\\s*(\\S+)\\s*\\*/");
  private static final Pattern TIMING_HEAD = Pattern.compile("^\\s*[:\\-]?\\s*(\\d+\\.\\s+)?");
  private static final String JAVA_DATE_FORMAT = "EEE MMM dd HH:mm:ss zzz yyyy";
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
    /** Preparing(또는 sqltiming 머리) 줄 번호(0부터). parseAll 이 채우고, 그 밖에는 -1. */
    public int line = -1;
    /** 로그 머리의 시각(예: 19:41:24.051) · 스레드(대괄호 안) · log4jdbc 실행 시간(예: 5 msec). 없으면 null. */
    public String time;
    public String thread;
    public String elapsed;

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
    e.thread = thread;
    e.time = time(line.substring(0, line.indexOf(ARROW)));
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

  /**
   * 마지막 쿼리부터 위로 최대 max 개를 줄 순서대로 읽는다. 각 Parameters 는 parse 와 같은 규칙으로 찾는다.
   * log4jdbc 의 jdbc.sqltiming 로그(머리 줄부터 {executed in N msec} 줄까지, 값이 이미 채워진 SQL)도 쿼리 하나로 읽는다.
   */
  public static List<Entry> parseAll(List<String> lines, int max) {
    List<Entry> out = new ArrayList<>();
    for (int k = lines.size() - 1; k >= 0 && out.size() < max; k--) {
      Entry e = isLine(lines.get(k), PREPARING) ? parse(lines, k) : sqlTiming(lines, k);
      if (e == null) continue;
      if (e.line < 0) e.line = k;
      k = e.line;
      out.add(0, e);
    }
    return out;
  }

  /**
   * 같은 쿼리는 가장 뒤(최근) 것 하나만 남겨 최대 max 개를 줄 순서대로 돌려준다. entries 는 줄 순서여야 한다.
   * 같은 쿼리: SQL 맨 앞 블록 주석 안의 매퍼.문장id 가 같거나, 없으면 로거의 매퍼.문장id 가, 그것도 없으면 공백을 줄인 SQL 이 같은 것.
   * 그래서 한 실행이 MyBatis 로그와 log4jdbc 로그로 두 번 찍혀도, 값이 다른 여러 번의 실행도 한 줄이 된다.
   */
  public static List<Entry> distinct(List<Entry> entries, int max) {
    Set<String> seen = new HashSet<>();
    List<Entry> out = new ArrayList<>();
    for (int k = entries.size() - 1; k >= 0 && out.size() < max; k--) {
      Entry e = entries.get(k);
      if (seen.add(queryKey(e))) out.add(0, e);
    }
    return out;
  }

  private static String queryKey(Entry e) {
    String name = queryName(e);
    return name != null ? name : e.sql.replaceAll("\\s+", " ").strip();
  }

  /** 쿼리 이름: SQL 맨 앞 블록 주석 안의 이름, 없으면 로거의 매퍼.문장id. 둘 다 없으면 null. */
  public static String queryName(Entry e) {
    Matcher m = HEAD_COMMENT.matcher(e.sql);
    if (m.find()) return m.group(1);
    return e.statementId() == null ? null : e.mapperName() + "." + e.statementId();
  }

  /** 거르기: text 가 비었거나, 쿼리 이름 · SQL(공백을 줄인 것)에 대소문자 없이 들어 있으면 참. */
  public static boolean matches(Entry e, String text) {
    if (text == null || text.isBlank()) return true;
    String needle = text.strip().toLowerCase(Locale.ROOT);
    String name = queryName(e);
    if (name != null && name.toLowerCase(Locale.ROOT).contains(needle)) return true;
    return e.sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).contains(needle);
  }

  /** 로그 머리 글에서 처음 나오는 시각. 없으면 null. */
  private static String time(String head) {
    Matcher m = TIME.matcher(head);
    return m.find() ? m.group() : null;
  }

  /**
   * lines[end] 가 {executed in N msec} 로 끝나면, 그 위로 가장 가까운 sqltiming 머리 줄부터 end 까지의 SQL.
   * 머리 줄의 로거 뒤 「 : 」 · 「 - 」 와 log4jdbc 의 연결 번호(「1. 」)는 뗀다. 아니면 null.
   */
  static Entry sqlTiming(List<String> lines, int end) {
    Matcher m = EXECUTED.matcher(lines.get(end));
    if (!m.find()) return null;
    int head = end;
    while (head >= 0 && lines.get(head).indexOf(SQL_TIMING) < 0) head--;
    if (head < 0) return null;
    StringBuilder sql = new StringBuilder();
    for (int k = head; k <= end; k++) {
      String l = lines.get(k);
      if (k == end) l = l.substring(0, m.start());
      if (k == head) l = TIMING_HEAD.matcher(l.substring(l.indexOf(SQL_TIMING) + SQL_TIMING.length())).replaceFirst("");
      sql.append(l).append('\n');
    }
    String text = sql.toString().strip();
    if (text.isEmpty()) return null;
    Entry e = new Entry();
    String prefix = lines.get(head).substring(0, lines.get(head).indexOf(SQL_TIMING));
    e.sql = text;
    e.line = head;
    e.time = time(prefix);
    Matcher t = THREAD.matcher(prefix);
    e.thread = t.find() ? t.group(1).trim() : null;
    e.elapsed = m.group(1) + " msec";
    return e;
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
