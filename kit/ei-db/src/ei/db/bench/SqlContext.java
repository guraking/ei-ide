package ei.db.bench;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자동완성 자리 판정: 커서 앞 글(문장 시작부터)만 보고 지금 무엇을 칠 차례인지 고른다. IntelliJ 에 기대지 않는다.
 * TABLE 은 FROM · JOIN · 쉼표(FROM 안) 뒤, JOIN_TABLE 은 JOIN 바로 뒤, ON 은 ON · ON 조건의 AND 뒤,
 * OPERATOR 는 조건 자리에서 칼럼을 친 뒤 한 칸 띈 곳, ORDER 는 ORDER BY 칼럼 뒤 한 칸 띈 곳, AFTER_TABLE 은 FROM 표(와 별칭) 뒤 한 칸 띈 곳.
 */
public final class SqlContext {
  private SqlContext() {}

  public enum Place { TABLE, JOIN_TABLE, ON, OPERATOR, ORDER, AFTER_TABLE, OTHER }

  private static final Pattern LAST_KEYWORD = Pattern.compile(
      "(?<![\\w$#.])(SELECT|FROM|JOIN|ON|WHERE|AND|OR|GROUP\\s+BY|ORDER\\s+BY|HAVING|SET|INTO|UPDATE|BY)(?![\\w$#])");
  private static final Pattern COLUMN_THEN_SPACE = Pattern.compile("(?:[A-Za-z_][\\w$#]*\\s*\\.\\s*)?[A-Za-z_][\\w$#]*\\s+$");
  private static final Pattern TABLE_THEN_SPACE = Pattern.compile("[A-Za-z_][\\w$#.]*(?:\\s+(?:AS\\s+)?[A-Za-z_][\\w$#]*)?\\s+$", Pattern.CASE_INSENSITIVE);

  /** before 는 커서가 든 문장의 시작부터 커서까지(지금 치는 낱말은 뺀 글). */
  public static Place at(String before) {
    String masked = SqlParts.mask(before);
    int[] depth = SqlParts.depth(masked);
    int here = depth.length == 0 ? 0 : depth[depth.length - 1] + (masked.endsWith("(") ? 1 : 0);
    String upper = masked.toUpperCase(Locale.ROOT);
    Matcher m = LAST_KEYWORD.matcher(upper);
    String key = null;
    int end = -1;
    while (m.find()) {
      int d = depth[m.start()];
      if (d != here) continue;
      key = m.group(1).replaceAll("\\s+", " ");
      end = m.end();
    }
    if (key == null) return Place.OTHER;
    String tail = before.substring(end);
    String tailMasked = masked.substring(end);
    boolean blank = tail.isBlank();
    switch (key) {
      case "FROM", "UPDATE", "INTO" -> {
        if (blank) return Place.TABLE;
        if (tailMasked.strip().endsWith(",")) return Place.TABLE;
        String lastItem = tail.substring(tailMasked.lastIndexOf(',') + 1);
        return TABLE_THEN_SPACE.matcher(lastItem).find() && lastItem.endsWith(" ") && lastItem.strip().split("\\s+").length <= 3 ? Place.AFTER_TABLE : Place.OTHER;
      }
      case "JOIN" -> {
        if (blank) return Place.JOIN_TABLE;
        return TABLE_THEN_SPACE.matcher(tail.stripLeading()).matches() ? Place.AFTER_TABLE : Place.OTHER;
      }
      case "ON" -> { return blank ? Place.ON : operatorOr(tail); }
      case "AND", "OR" -> {
        if (blank) return inOn(upper, depth, here) ? Place.ON : Place.OTHER;
        return operatorOr(tail);
      }
      case "WHERE", "HAVING" -> { return blank ? Place.OTHER : operatorOr(tail); }
      case "ORDER BY" -> {
        if (blank || tailMasked.strip().endsWith(",")) return Place.OTHER;
        String lastItem = tail.substring(tailMasked.lastIndexOf(',') + 1);
        return COLUMN_THEN_SPACE.matcher(lastItem.stripLeading()).matches() ? Place.ORDER : Place.OTHER;
      }
      default -> { return Place.OTHER; }
    }
  }

  /** 조건 자리에서 칼럼 하나를 치고 한 칸 띄었으면 OPERATOR. */
  private static Place operatorOr(String tail) {
    return COLUMN_THEN_SPACE.matcher(tail.stripLeading()).matches() && tail.strip().split("\\s+").length == 1 ? Place.OPERATOR : Place.OTHER;
  }

  /** 마지막 AND 가 ON 조건 안인지: 같은 깊이에서 마지막 ON 이 마지막 WHERE · JOIN 보다 뒤에 있으면. */
  private static boolean inOn(String upper, int[] depth, int here) {
    Matcher m = Pattern.compile("(?<![\\w$#.])(ON|WHERE|JOIN|GROUP|ORDER|HAVING)(?![\\w$#])").matcher(upper);
    String last = null;
    while (m.find()) if (depth[m.start()] == here) last = m.group(1);
    return "ON".equals(last);
  }
}
