package ei.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 자동완성용 표 · 칼럼 목록. 화면 스레드나 자동완성 스레드를 막지 않도록 없으면 null 을 돌려주고 뒤에서 읽어 둔다.
 * 다음 자동완성부터 쓰인다. 탐색기 새로고침 · 접속 끊기(Comments.clear)에서 함께 비운다.
 */
public final class SchemaCache {
  private SchemaCache() {}

  private static final Logger LOG = Logger.getInstance(SchemaCache.class);
  private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

  private static final Map<String, List<Meta.Table>> TABLES = new ConcurrentHashMap<>();
  private static final Map<String, List<Meta.Column>> COLUMNS = new ConcurrentHashMap<>();
  private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

  /** 기본 스키마의 표 · 뷰. 아직 없으면 null(읽기 시작). */
  public static List<Meta.Table> tables(Project project, DbConfig c) {
    List<Meta.Table> t = TABLES.get(c.id);
    if (t == null) load(c.id, () -> {
      Connection conn = DbSessions.get(project).connection(c);
      TABLES.put(c.id, Meta.tables(conn, c, Meta.schemaOf(c), TABLE_TYPES));
    });
    return t;
  }

  /** 표의 칼럼. 표 이름은 대소문자를 가리지 않는다. 아직 없으면 null(읽기 시작). */
  public static List<Meta.Column> columns(Project project, DbConfig c, String table) {
    String key = c.id + "|" + table.toUpperCase(Locale.ROOT);
    List<Meta.Column> cols = COLUMNS.get(key);
    if (cols == null) load(key, () -> {
      String name = table;
      List<Meta.Table> known = TABLES.get(c.id);
      if (known != null) {
        for (Meta.Table t : known) {
          if (t.name().equalsIgnoreCase(table)) { name = t.name(); break; }
        }
      }
      Connection conn = DbSessions.get(project).connection(c);
      COLUMNS.put(key, Meta.columns(conn, c, Meta.schemaOf(c), name));
    });
    return cols;
  }

  public static void clear(String configId) {
    TABLES.remove(configId);
    COLUMNS.keySet().removeIf(k -> k.startsWith(configId + "|"));
  }

  private interface Loader { void run() throws Exception; }

  /** 같은 키는 한 번만 동시에 읽는다. 실패는 기록만 하고 다음 자동완성 때 다시 시도한다. */
  private static void load(String key, Loader loader) {
    if (!LOADING.add(key)) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        loader.run();
      } catch (Exception e) {
        LOG.info("자동완성 목록을 읽지 못했습니다: " + e.getMessage());
      } finally {
        LOADING.remove(key);
      }
    });
  }
}
