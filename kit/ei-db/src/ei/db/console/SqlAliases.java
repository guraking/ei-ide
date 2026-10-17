package ei.db.console;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 문장 하나에서 표 별칭(대문자) → 표 이름. 표 이름 자체도 키로 넣는다. 구문 분석이 아니라 FROM · JOIN · UPDATE · INTO 뒤를 본다. */
final class SqlAliases {
  private SqlAliases() {}

  private static final Pattern FROM = Pattern.compile("\\bFROM\\b(.*?)(?=\\bWHERE\\b|\\bGROUP\\b|\\bORDER\\b|\\bHAVING\\b|\\bUNION\\b|\\bCONNECT\\b|\\bSTART\\b|\\bJOIN\\b|\\(|\\)|;|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern SINGLE = Pattern.compile("\\b(?:JOIN|UPDATE|INTO)\\s+([\\w.$#\"]+)(?:\\s+(?:AS\\s+)?([\\w$#]+))?", Pattern.CASE_INSENSITIVE);
  private static final Set<String> NOT_ALIAS = Set.of("ON", "WHERE", "SET", "VALUES", "USING", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "JOIN", "GROUP", "ORDER", "SELECT");

  static Map<String, String> parse(String sql) {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher f = FROM.matcher(sql);
    while (f.find()) {
      for (String part : f.group(1).split(",")) {
        String[] words = part.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) continue;
        String alias = words.length >= 3 && words[1].equalsIgnoreCase("AS") ? words[2] : words.length >= 2 ? words[1] : null;
        put(out, words[0], alias);
      }
    }
    Matcher j = SINGLE.matcher(sql);
    while (j.find()) put(out, j.group(1), j.group(2));
    return out;
  }

  /** 커서가 든 문장: 앞뒤 ; 사이. */
  static String statementAround(String text, int offset) {
    int from = text.lastIndexOf(';', Math.max(0, offset - 1)) + 1;
    int to = text.indexOf(';', offset);
    return text.substring(from, to < 0 ? text.length() : to);
  }

  private static void put(Map<String, String> out, String rawTable, String alias) {
    String table = rawTable.replace("\"", "");
    table = table.substring(table.lastIndexOf('.') + 1);
    if (table.isEmpty()) return;
    out.putIfAbsent(table.toUpperCase(Locale.ROOT), table);
    if (alias != null && !NOT_ALIAS.contains(alias.toUpperCase(Locale.ROOT))) out.put(alias.toUpperCase(Locale.ROOT), table);
  }
}
