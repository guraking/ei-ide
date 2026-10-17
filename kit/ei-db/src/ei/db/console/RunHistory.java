package ei.db.console;

import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SqlRunner 로 실행한 문장의 기록. IDE 설정 폴더의 ei-db/history.tsv 에 한 줄 한 건(오래된 것부터), 최근 MAX 건만 둔다.
 * 줄 형식: 시각(ms) · 접속 id · 접속 이름 · 걸린 시간(ms) · 건수 · 성공(1/0) · 문장 · 바인드 값(0개 이상, 한 칸에 하나),
 * 탭으로 나누고 \ 탭 줄바꿈은 \\ \t \n \r 로 쓴다. 바인드 칸이 없는 예전 줄은 값 없음으로 읽는다.
 * 바인드 값 칸은 「형식 글자:값」. S 문자열 · L Long · I Integer · N BigDecimal(그 밖의 숫자 포함) · B Boolean ·
 * D 날짜(java.sql.Date) · T 시각(Timestamp) · 칸 전체가 「-」이면 null. 그 밖의 형식은 문자열로 남긴다.
 * 저장 전에 비밀번호를 MASK 로 가린다: 문장의 IDENTIFIED BY 값 · PASSWORD '값', 그리고 비밀번호 이름(isSecretName)의 자리에서 온 바인드 값
 * (BindDialog 가 markSecret 으로 알려 준 값과 같은 값). 가린 기록은 다시 실행해도 원래 값으로 돌지 않는다.
 * 형식 · 자르기는 IntelliJ 없이 돈다(test 의 RunHistoryCheck). 여러 스레드에서 불러도 된다.
 */
public final class RunHistory {

  public static final int MAX = 500;
  /** 건수를 모를 때(실패 · 취소). */
  public static final int NO_COUNT = -1;
  /** 바인드 값을 뺀 칸 수. */
  private static final int FIELDS = 7;
  private static final String NULL_BIND = "-";
  static final String MASK = "****";
  /** 기억해 두는 비밀번호 바인드 값 수(오래된 것부터 잊는다). */
  private static final int MAX_SECRETS = 50;
  private static final Pattern IDENTIFIED_BY = Pattern.compile(
      "(?i)(\\bIDENTIFIED\\b(?:\\s+WITH\\s+\\S+)?\\s+BY\\s+)(?:\"[^\"]*\"|'(?:[^']|'')*'|[^\\s;]+)");
  /** PostgreSQL 의 [ENCRYPTED] PASSWORD '값'. */
  private static final Pattern PASSWORD_LITERAL = Pattern.compile("(?i)(\\bPASSWORD\\s+)'(?:[^']|'')*'");
  private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(PASSWORD|PASSWD|PWD).*");
  private static final Set<Object> SECRETS = new LinkedHashSet<>();

  /** count 는 조회면 읽은 행 수, 변경이면 변경 수, 모르면 NO_COUNT. binds 는 ? 자리 값(없으면 빈 목록). */
  public record Entry(long time, String configId, String configName, long millis, int count, boolean ok, String sql, List<Object> binds) {}

  private final Path file;
  /** 오래된 것부터. 처음 쓸 때 파일에서 읽는다. */
  private List<Entry> entries;

  RunHistory(Path file) {
    this.file = file;
  }

  public static RunHistory get() {
    return Holder.INSTANCE;
  }

  /** IntelliJ 가 없는 점검에서는 get() 을 부르지 않으므로 PathManager 를 읽지 않는다. */
  private static final class Holder {
    static final RunHistory INSTANCE = new RunHistory(PathManager.getConfigDir().resolve("ei-db").resolve("history.tsv"));
  }

  /** 최근 것부터. */
  public synchronized List<Entry> entries() throws IOException {
    List<Entry> out = new ArrayList<>(load());
    Collections.reverse(out);
    return out;
  }

  /** 비밀번호를 가려 더하고 파일을 다시 쓴다. 파일을 읽지 못했으면 덮지 않고 예외를 낸다. */
  public synchronized void add(Entry e) throws IOException {
    List<Entry> list = load();
    list.add(masked(e));
    while (list.size() > MAX) list.remove(0);
    StringBuilder text = new StringBuilder();
    for (Entry x : list) text.append(format(x)).append('\n');
    ConsoleFiles.write(file, text.toString());
  }

  private List<Entry> load() throws IOException {
    if (entries != null) return entries;
    List<Entry> list = new ArrayList<>();
    for (String line : ConsoleFiles.read(file).split("\n")) {
      Entry e = parse(line);
      if (e != null) list.add(e);
    }
    entries = list;
    return list;
  }

  /** 바인드 자리 이름이 비밀번호(PASSWORD · PASSWD · PWD 를 품은 이름, 대소문자 무시)인지. */
  public static boolean isSecretName(String name) {
    return name != null && SECRET_NAME.matcher(name).matches();
  }

  /** 비밀번호 자리에 들어간 값을 알려 둔다. 이 값과 같은 바인드 값은 기록에서 가린다. null 은 무시. */
  public static void markSecret(Object value) {
    if (value == null) return;
    synchronized (SECRETS) {
      SECRETS.remove(value);
      SECRETS.add(value);
      while (SECRETS.size() > MAX_SECRETS) SECRETS.remove(SECRETS.iterator().next());
    }
  }

  private static boolean isSecretValue(Object value) {
    synchronized (SECRETS) {
      return value != null && SECRETS.contains(value);
    }
  }

  /** 문장 안의 IDENTIFIED BY 값 · PASSWORD '값' 을 MASK 로. */
  static String maskSql(String sql) {
    if (sql == null) return null;
    String s = IDENTIFIED_BY.matcher(sql).replaceAll("$1" + MASK);
    return PASSWORD_LITERAL.matcher(s).replaceAll("$1'" + MASK + "'");
  }

  static Entry masked(Entry e) {
    List<Object> binds = new ArrayList<>(e.binds().size());
    for (Object v : e.binds()) binds.add(isSecretValue(v) ? MASK : v);
    return new Entry(e.time(), e.configId(), e.configName(), e.millis(), e.count(), e.ok(), maskSql(e.sql()), binds);
  }

  static String format(Entry e) {
    return e.time() + "\t" + escape(e.configId()) + "\t" + escape(e.configName()) + "\t" + e.millis() + "\t" + e.count()
        + "\t" + (e.ok() ? "1" : "0") + "\t" + escape(e.sql()) + formatBinds(e.binds());
  }

  private static String formatBinds(List<Object> binds) {
    StringBuilder b = new StringBuilder();
    for (Object v : binds) b.append('\t').append(escape(formatBind(v)));
    return b.toString();
  }

  static String formatBind(Object v) {
    if (v == null) return NULL_BIND;
    if (v instanceof Long) return "L:" + v;
    if (v instanceof Integer) return "I:" + v;
    if (v instanceof BigDecimal d) return "N:" + d.toPlainString();
    if (v instanceof Number) return "N:" + new BigDecimal(v.toString()).toPlainString();
    if (v instanceof Boolean) return "B:" + v;
    if (v instanceof Date || v instanceof LocalDate) return "D:" + v;
    if (v instanceof Timestamp t) return "T:" + t;
    if (v instanceof LocalDateTime t) return "T:" + Timestamp.valueOf(t);
    if (v instanceof java.util.Date d) return "T:" + new Timestamp(d.getTime());
    return "S:" + v;
  }

  /** 형식 글자가 틀리거나 값이 깨졌으면 IllegalArgumentException. */
  static Object parseBind(String s) {
    if (s.equals(NULL_BIND)) return null;
    if (s.length() < 2 || s.charAt(1) != ':') throw new IllegalArgumentException("바인드 값 형식이 틀렸습니다: " + s);
    String v = s.substring(2);
    return switch (s.charAt(0)) {
      case 'S' -> v;
      case 'L' -> Long.valueOf(v);
      case 'I' -> Integer.valueOf(v);
      case 'N' -> new BigDecimal(v);
      case 'B' -> Boolean.valueOf(v);
      case 'D' -> Date.valueOf(v);
      case 'T' -> Timestamp.valueOf(v);
      default -> throw new IllegalArgumentException("바인드 값 형식이 틀렸습니다: " + s);
    };
  }

  /** 깨진 줄(칸 수 · 숫자 · 바인드 값이 틀림)은 null. */
  static Entry parse(String line) {
    String[] f = line.split("\t", -1);
    if (f.length < FIELDS) return null;
    try {
      List<Object> binds = new ArrayList<>();
      for (int i = FIELDS; i < f.length; i++) binds.add(parseBind(unescape(f[i])));
      return new Entry(Long.parseLong(f[0]), unescape(f[1]), unescape(f[2]), Long.parseLong(f[3]), Integer.parseInt(f[4]),
          "1".equals(f[5]), unescape(f[6]), binds);
    } catch (IllegalArgumentException ex) {
      // NumberFormatException 포함. 깨진 줄 하나 때문에 나머지 기록을 잃지 않게 그 줄만 버린다.
      return null;
    }
  }

  static String escape(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
  }

  static String unescape(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch != '\\' || i + 1 == s.length()) {
        out.append(ch);
        continue;
      }
      char next = s.charAt(++i);
      out.append(switch (next) {
        case 't' -> '\t';
        case 'n' -> '\n';
        case 'r' -> '\r';
        default -> next;
      });
    }
    return out.toString();
  }
}
