package ei.db;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 실행 전 위험 문장 판정. 주석 · 문자열을 지운 뒤 앞머리 키워드로만 본다(구문 분석은 하지 않는다). */
public final class SqlGuard {
  private SqlGuard() {}

  private static final Pattern WRITE = Pattern.compile("^(INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE|RENAME|GRANT|REVOKE|BEGIN|DECLARE|CALL)\\b");
  private static final Pattern UPDATE = Pattern.compile("^UPDATE\\s+([\\w.$#\"]+)");
  private static final Pattern DELETE = Pattern.compile("^DELETE\\s+(?:FROM\\s+)?([\\w.$#\"]+)");
  private static final Pattern DROP = Pattern.compile("^(DROP|TRUNCATE)\\s+(?:TABLE\\s+)?([\\w.$#\"]+)");
  private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b");

  /** 주석과 문자열 안을 지우고 대문자로 바꾼 본문. */
  static String normalize(String sql) {
    String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ").replaceAll("'([^']|'')*'", "''");
    return s.trim().toUpperCase(Locale.ROOT);
  }

  public static boolean isWrite(String sql) {
    return WRITE.matcher(normalize(sql)).find();
  }

  /** 막아야 할 이유. 괜찮으면 null. */
  public static String danger(String sql) {
    String s = normalize(sql);
    if (UPDATE.matcher(s).find() && !WHERE.matcher(s).find()) return "WHERE 없는 UPDATE";
    if (DELETE.matcher(s).find() && !WHERE.matcher(s).find()) return "WHERE 없는 DELETE";
    Matcher m = DROP.matcher(s);
    if (m.find()) return m.group(1) + " 문";
    return null;
  }

  /** 대상 표 이름(스키마 제외, 따옴표 제거). 모르면 null. */
  public static String table(String sql) {
    String s = normalize(sql);
    for (Pattern p : new Pattern[]{UPDATE, DELETE}) {
      Matcher m = p.matcher(s);
      if (m.find()) return bare(m.group(1));
    }
    Matcher m = DROP.matcher(s);
    return m.find() ? bare(m.group(2)) : null;
  }

  private static String bare(String name) {
    String n = name.replace("\"", "");
    return n.substring(n.lastIndexOf('.') + 1);
  }
}
