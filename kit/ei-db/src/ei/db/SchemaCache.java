package ei.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 접속마다 읽어 둔 표 · 칼럼 · 외래 키. 자동완성 · 작업대 · 탐색기 · 정의서가 같이 쓴다. 탐색기 새로고침 · 접속 끊기(Comments.clear)에서 비운다.
 * 표 목록은 표와 뷰(Meta.tableAndViewTypes)를 함께, 칼럼 · 외래 키는 메타 이름 그대로의 표 이름으로 둔다.
 * 작업 스레드용(Connection 을 받는 것)은 없으면 그 자리에서 읽어 넣는다.
 * 자동완성용(Project 를 받는 것)은 화면 · 자동완성 스레드를 막지 않도록 없으면 null 을 돌려주고 뒤에서 읽어 둔다(다음 자동완성부터 쓰인다).
 * 자동완성은 열린 접속에서 읽는다. 아직 열지 않은 접속은 처음 한 번만 뒤에서 연다. 그 열기가 실패하면 clear(끊기 · 새로고침) 전까지
 * 자동완성으로는 다시 열지 않는다(비밀번호가 틀린 접속이 입력마다 로그인해 계정이 잠기지 않게). 문장 실행으로 열린 접속은 그대로 쓴다.
 * 읽기에 실패한 접속은 FAILURE_COOLDOWN_MS 동안 자동완성에서 다시 읽지 않는다(끊긴 접속에 입력마다 묻지 않게).
 */
public final class SchemaCache {
  private SchemaCache() {}

  private static final Logger LOG = Logger.getInstance(SchemaCache.class);
  /** 자동완성 읽기에 실패한 뒤 그 접속을 다시 읽지 않는 시간. 접속을 끊고 다시 열면(clear) 바로 풀린다. */
  static final long FAILURE_COOLDOWN_MS = 60_000;

  /** 키: 접속 id | 스키마. */
  private static final Map<String, List<Meta.Table>> TABLES = new ConcurrentHashMap<>();
  /** 키: 접속 id | 스키마 | 표 이름(메타 그대로). */
  private static final Map<String, List<Meta.Column>> COLUMNS = new ConcurrentHashMap<>();
  private static final Map<String, List<Meta.ForeignKey>> FKS = new ConcurrentHashMap<>();
  private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
  /** 자동완성이 열기를 이미 시도한 접속 id. clear 에서 지운다. */
  private static final Set<String> CONNECT_TRIED = ConcurrentHashMap.newKeySet();
  /** 접속 id → 자동완성 읽기에 마지막으로 실패한 시각(ms). */
  private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
  /** 접속마다 clear 할 때 오르는 번호. 읽기 시작 뒤 번호가 바뀌었으면 그 결과는 새로고침 전 것이라 버린다. */
  private static final Map<String, Integer> GENERATION = new ConcurrentHashMap<>();
  private static final String SEP = "|";

  // ---- 작업 스레드용: 없으면 읽어 넣는다 ----

  /** 스키마의 표 · 뷰. */
  public static List<Meta.Table> tables(Connection conn, DbConfig c, String schema) throws SQLException {
    String key = key(c.id, schema);
    List<Meta.Table> have = TABLES.get(key);
    if (have != null) return have;
    int gen = generation(c.id);
    List<Meta.Table> read = List.copyOf(Meta.tables(conn, c, schema, Meta.tableAndViewTypes(c)));
    putIfCurrent(TABLES, key, read, c.id, gen);
    return read;
  }

  /** 표의 칼럼(PK · FK · 주석 포함). table 은 메타 이름 그대로. */
  public static List<Meta.Column> columns(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    String key = key(c.id, schema, table);
    List<Meta.Column> have = COLUMNS.get(key);
    if (have != null) return have;
    int gen = generation(c.id);
    List<Meta.Column> read = List.copyOf(Meta.columns(conn, c, schema, table));
    putIfCurrent(COLUMNS, key, read, c.id, gen);
    return read;
  }

  /** 표의 외래 키(그 표가 다른 표를 가리키는 것). table 은 메타 이름 그대로. */
  public static List<Meta.ForeignKey> importedKeys(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    String key = key(c.id, schema, table);
    List<Meta.ForeignKey> have = FKS.get(key);
    if (have != null) return have;
    int gen = generation(c.id);
    List<Meta.ForeignKey> read = List.copyOf(Meta.importedKeys(conn, c, schema, table));
    putIfCurrent(FKS, key, read, c.id, gen);
    return read;
  }

  /** 이미 읽어 둔 칼럼. 없으면 null(읽지 않는다). */
  public static List<Meta.Column> cachedColumns(DbConfig c, String schema, String table) {
    return COLUMNS.get(key(c.id, schema, table));
  }

  // ---- 자동완성용: 없으면 null, 열린 접속에서만 뒤에서 읽는다 ----

  /** 기본 스키마의 표 · 뷰. 아직 없으면 null. */
  public static List<Meta.Table> tables(Project project, DbConfig c) {
    String schema = Meta.schemaOf(c);
    List<Meta.Table> t = TABLES.get(key(c.id, schema));
    if (t == null) loadOpen(project, c, "t" + SEP + key(c.id, schema), conn -> tables(conn, c, schema));
    return t;
  }

  /**
   * 표의 칼럼. 표 이름은 대소문자를 가리지 않는다. 아직 없으면 null.
   * 표 목록을 먼저 읽어 메타 이름(Oracle 대문자 · PostgreSQL 소문자 · 따옴표 이름)을 맞춘 뒤 칼럼을 읽는다:
   * 소문자로 친 「e.」 를 그대로 물어 빈 목록이 캐시에 굳지 않게 한다.
   */
  public static List<Meta.Column> columns(Project project, DbConfig c, String table) {
    String schema = Meta.schemaOf(c);
    String name = metaName(project, c, schema, table);
    if (name == null) return null;
    String key = key(c.id, schema, name);
    List<Meta.Column> cols = COLUMNS.get(key);
    if (cols == null) loadOpen(project, c, "c" + SEP + key, conn -> columns(conn, c, schema, name));
    return cols;
  }

  /** 표의 외래 키. 표 이름은 대소문자를 가리지 않는다. 아직 없으면 null. */
  public static List<Meta.ForeignKey> importedKeys(Project project, DbConfig c, String table) {
    String schema = Meta.schemaOf(c);
    String name = metaName(project, c, schema, table);
    if (name == null) return null;
    String key = key(c.id, schema, name);
    List<Meta.ForeignKey> fks = FKS.get(key);
    if (fks == null) loadOpen(project, c, "f" + SEP + key, conn -> importedKeys(conn, c, schema, name));
    return fks;
  }

  /** 읽어 둔 표 목록에서 대소문자만 다른 이름을 메타 이름으로. 목록에 없으면 Meta.storedName. 표 목록을 아직 못 읽었으면 읽기를 걸고 null. */
  private static String metaName(Project project, DbConfig c, String schema, String table) {
    List<Meta.Table> known = tables(project, c);
    if (known == null) return null;
    String bare = table.replace("\"", "");
    for (Meta.Table t : known) if (t.name().equalsIgnoreCase(bare)) return t.name();
    return Meta.storedName(c, table);
  }

  public static void clear(String configId) {
    synchronized (GENERATION) {
      GENERATION.merge(configId, 1, Integer::sum);
      String prefix = configId + SEP;
      TABLES.keySet().removeIf(k -> k.startsWith(prefix));
      COLUMNS.keySet().removeIf(k -> k.startsWith(prefix));
      FKS.keySet().removeIf(k -> k.startsWith(prefix));
      FAILED.remove(configId);
      CONNECT_TRIED.remove(configId);
    }
  }

  private static String key(String configId, String schema) {
    return configId + SEP + schema;
  }

  private static String key(String configId, String schema, String table) {
    return configId + SEP + schema + SEP + table;
  }

  private static int generation(String configId) {
    return GENERATION.getOrDefault(configId, 0);
  }

  /** 읽기 시작한 뒤 그 접속에 clear 가 없었을 때만 넣는다. clear 와 같은 잠금 안에서 확인하고 넣는다. */
  private static <T> void putIfCurrent(Map<String, T> cache, String key, T value, String configId, int gen) {
    synchronized (GENERATION) {
      if (generation(configId) == gen) cache.put(key, value);
    }
  }

  /** 열린 접속에서 읽는 일. */
  private interface Reader { void read(Connection conn) throws SQLException; }

  /**
   * 쿨다운 중이 아닐 때 풀 스레드에서 읽는다. 같은 키는 한 번만 동시에 읽는다.
   * 열린 접속은 살아 있는지 묻지 않고 그대로 쓴다. 열리지 않은 접속은 clear 뒤 처음 한 번만 연다(실패해도 다시 열지 않음).
   * 읽기 · 열기에 실패하면 그 시각을 남겨 쿨다운한다.
   */
  private static void loadOpen(Project project, DbConfig c, String key, Reader reader) {
    DbSessions sessions = DbSessions.get(project);
    Connection open = sessions.openConnection(c.id);
    if (coolingDown(c.id)) return;
    if (open == null && !CONNECT_TRIED.add(c.id)) return;
    if (!LOADING.add(key)) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        reader.read(open != null ? open : sessions.connection(c));
      } catch (Exception e) {
        FAILED.put(c.id, System.currentTimeMillis());
        LOG.info("자동완성 목록을 읽지 못했습니다(" + TimeUnit.MILLISECONDS.toSeconds(FAILURE_COOLDOWN_MS) + "초 동안 다시 읽지 않음): " + e.getMessage());
      } finally {
        LOADING.remove(key);
      }
    });
  }

  private static boolean coolingDown(String configId) {
    Long at = FAILED.get(configId);
    return at != null && System.currentTimeMillis() - at < FAILURE_COOLDOWN_MS;
  }

  /** 자동완성 안내용: 자동완성이 연 접속 열기가 실패해 목록을 읽지 않는 중이면 true. */
  public static boolean waitingForConnection(Project project, DbConfig c) {
    return !DbSessions.get(project).isOpen(c.id) && FAILED.containsKey(c.id);
  }
}
