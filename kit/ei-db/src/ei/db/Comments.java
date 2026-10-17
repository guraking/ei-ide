package ei.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결과 칼럼의 한글 주석. Oracle 은 결과 메타에 표 이름을 주지 않으므로 FROM · JOIN 에 나온 표들의 칼럼 주석을
 * 모아 칼럼 이름으로 맞춘다. 같은 이름이 여러 표에 있으면 FROM 에 먼저 나온 표를 쓴다. 표별로 캐시하고 새로고침 때 비운다.
 */
public final class Comments {
  private Comments() {}

  private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();
  private static final Pattern FROM = Pattern.compile("\\bFROM\\b(.*?)(?=\\bWHERE\\b|\\bGROUP\\b|\\bORDER\\b|\\bHAVING\\b|\\bUNION\\b|\\bCONNECT\\b|\\bSTART\\b|\\)|$)", Pattern.DOTALL);
  private static final Pattern JOIN = Pattern.compile("\\bJOIN\\s+([\\w.$#\"]+)");

  public static void clear(String configId) {
    CACHE.keySet().removeIf(k -> k.startsWith(configId + "|"));
  }

  /** 표 하나의 칼럼 이름(대문자) → 주석. */
  public static Map<String, String> ofTable(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    String key = c.id + "|" + schema + "|" + table;
    Map<String, String> cached = CACHE.get(key);
    if (cached != null) return cached;
    Map<String, String> m = new HashMap<>();
    for (Meta.Column col : Meta.columns(conn, c, schema, table)) {
      if (!col.comment().isEmpty()) m.put(col.name().toUpperCase(Locale.ROOT), col.comment());
    }
    CACHE.put(key, m);
    return m;
  }

  /** FROM 절과 JOIN 에서 표 이름(스키마 · 따옴표 제거, 대문자). */
  static List<String> tablesOf(String sql) {
    String s = SqlGuard.normalize(sql);
    List<String> out = new ArrayList<>();
    Matcher f = FROM.matcher(s);
    if (f.find()) {
      for (String part : f.group(1).split(",|\\bJOIN\\b")) {
        String t = part.trim().split("\\s+")[0];
        if (!t.isEmpty() && !t.startsWith("(")) add(out, t);
      }
    }
    Matcher j = JOIN.matcher(s);
    while (j.find()) add(out, j.group(1));
    return out;
  }

  private static void add(List<String> out, String raw) {
    String n = raw.replace("\"", "");
    n = n.substring(n.lastIndexOf('.') + 1);
    if (!n.isEmpty() && !out.contains(n)) out.add(n);
  }

  /** 칼럼 이름(결과에 나온 그대로) → 주석. 실패하면 빈 맵. */
  public static Map<String, String> forQuery(Connection conn, DbConfig c, String sql, List<String> columns) {
    Map<String, String> out = new HashMap<>();
    String schema = Meta.schemaOf(c);
    List<Map<String, String>> maps = new ArrayList<>();
    for (String t : tablesOf(sql)) {
      try {
        String name = c.kind == DbKind.ORACLE ? t : t.toLowerCase(Locale.ROOT);
        maps.add(ofTable(conn, c, schema, name));
      } catch (SQLException ignored) {
        // 다른 스키마의 표 · 별칭 · 서브쿼리면 주석 없이 둔다.
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
