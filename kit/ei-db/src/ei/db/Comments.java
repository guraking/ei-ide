package ei.db;

import com.intellij.openapi.diagnostic.Logger;
import ei.db.bench.SqlParts;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 결과 칼럼의 한글 주석. Oracle 은 결과 메타에 표 이름을 주지 않으므로 문장에 나온 표들(SqlParts.tables)의 칼럼 주석을
 * 모아 칼럼 이름으로 맞춘다. 같은 이름이 여러 표에 있으면 먼저 나온 표를 쓴다. 「스키마.표」 는 그 스키마에서 찾는다.
 * 결과를 보이기 전에 불리므로 칼럼 주석만 읽는다(getColumns 한 번, PK · 외래 키는 읽지 않는다). SchemaCache 에 칼럼이 있으면 그것을 쓴다.
 * 표별로 캐시하고 새로고침 · 접속 끊기 때 비운다.
 */
public final class Comments {
  private Comments() {}

  private static final Logger LOG = Logger.getInstance(Comments.class);
  private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

  public static void clear(String configId) {
    CACHE.keySet().removeIf(k -> k.startsWith(configId + "|"));
    SchemaCache.clear(configId);
  }

  /** 표 하나의 칼럼 이름(대문자) → 주석. table 은 메타 이름 그대로. */
  public static Map<String, String> ofTable(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    String key = c.id + "|" + schema + "|" + table;
    Map<String, String> cached = CACHE.get(key);
    if (cached != null) return cached;
    Map<String, String> m = new HashMap<>();
    List<Meta.Column> known = SchemaCache.cachedColumns(c, schema, table);
    if (known != null) {
      for (Meta.Column col : known) put(m, col);
    } else {
      for (Meta.ColumnRow r : Meta.columnRows(conn, c, schema, table, false)) put(m, r.column());
    }
    CACHE.put(key, m);
    return m;
  }

  private static void put(Map<String, String> m, Meta.Column col) {
    if (!col.comment().isEmpty()) m.put(col.name().toUpperCase(Locale.ROOT), col.comment());
  }

  /** 칼럼 이름(결과에 나온 그대로) → 주석. 실패하면 빈 맵. */
  public static Map<String, String> forQuery(Connection conn, DbConfig c, String sql, List<String> columns) {
    Map<String, String> out = new HashMap<>();
    String defaultSchema = Meta.schemaOf(c);
    List<Map<String, String>> maps = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<SqlParts.TableRef> refs;
    try {
      refs = SqlParts.tables(sql);
    } catch (RuntimeException e) {
      // 주석은 덧붙이는 정보라 문장 나누기에 실패해도 결과는 보인다.
      LOG.warn("결과 칼럼 주석용 표 이름을 읽지 못했습니다", e);
      return out;
    }
    for (SqlParts.TableRef t : refs) {
      String schema = t.schema() == null ? defaultSchema : Meta.storedName(c, t.schema());
      String name = Meta.storedName(c, t.name());
      if (!seen.add(schema + "." + name)) continue;
      try {
        maps.add(ofTable(conn, c, schema, name));
      } catch (SQLException ignored) {
        // 권한 없는 표 · WITH 이름 · DB 링크면 주석 없이 둔다.
      }
    }
    for (String col : columns) {
      String key = col.toUpperCase(Locale.ROOT);
      for (Map<String, String> m : maps) {
        String v = m.get(key);
        if (v != null) { out.put(col, v); break; }
      }
    }
    return out;
  }
}
