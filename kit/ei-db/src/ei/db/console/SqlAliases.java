package ei.db.console;

import ei.db.bench.SqlParts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 문장 하나에서 표 별칭(대문자) → 표 이름(따옴표 · 스키마 뺌). 표 이름 자체도 키로 넣는다. 표는 SqlParts.tables 로 찾는다. */
final class SqlAliases {
  private SqlAliases() {}

  /** 별칭 자리에 와도 별칭이 아닌 낱말(쓰는 중인 문장의 다음 절 머리). */
  private static final Set<String> NOT_ALIAS = Set.of("ON", "WHERE", "SET", "VALUES", "USING", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "JOIN", "GROUP", "ORDER", "SELECT");

  static Map<String, String> parse(String sql) {
    Map<String, String> out = new LinkedHashMap<>();
    for (SqlParts.TableRef t : SqlParts.tables(sql)) {
      String table = t.bare();
      out.putIfAbsent(table.toUpperCase(Locale.ROOT), table);
      String alias = t.alias();
      if (alias != null && !NOT_ALIAS.contains(alias.toUpperCase(Locale.ROOT))) out.put(alias.toUpperCase(Locale.ROOT), table);
    }
    return out;
  }

  /** 커서가 든 문장: 앞뒤 ; 사이. */
  static String statementAround(String text, int offset) {
    int from = text.lastIndexOf(';', Math.max(0, offset - 1)) + 1;
    int to = text.indexOf(';', offset);
    return text.substring(from, to < 0 ? text.length() : to);
  }
}
