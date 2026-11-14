package ei.db.console;

import ei.db.bench.SqlParts;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 결과 창 찾기 칸에 쓴 조건(「USERID = 'A'」)을 원래 조회 문장에 붙인 문장 만들기. IntelliJ 클래스를 쓰지 않는다.
 * 조건 글은 해석하지 않고 그대로 WHERE 에 넣는다: 문법 확인은 DB 가 한다.
 */
final class ResultFilter {
  private ResultFilter() {}

  /** 조건으로 보는 글: 비교 기호나 LIKE · IN · IS · BETWEEN 낱말이 들어 있다. 아니면 글자 찾기다. */
  private static final Pattern CONDITION = Pattern.compile("[=<>]|(?<![\\w$#])(LIKE|IN|IS|BETWEEN)(?![\\w$#])", Pattern.CASE_INSENSITIVE);
  /** 원래 문장을 감쌀 때 붙이는 별칭. */
  private static final String WRAP_ALIAS = "ei_f";

  static boolean isCondition(String text) {
    return text != null && CONDITION.matcher(text).find();
  }

  /**
   * base 의 결과 가운데 condition 에 맞는 행만 읽는 문장.
   * 표 하나를 조회한 문장(SqlText.notEditable 이 null)은 그 WHERE 에 AND 로 붙여 결과 칸 고치기 · 행 지우기가 그대로 되게 하고,
   * 그 밖의 문장은 「SELECT * FROM (원래 문장) WHERE 조건」으로 감싼다(조건은 결과 칼럼 이름으로 쓴다).
   * 원래 문장 · 조건 끝의 줄 주석이 뒤 글을 삼키지 않게 닫는 괄호 앞에서 줄을 바꾼다.
   */
  static String apply(String base, String condition) {
    String sql = base.strip();
    while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).strip();
    String cond = condition.strip();
    if (SqlText.notEditable(sql) == null) {
      List<SqlParts.Part> parts = SqlParts.split(sql);
      SqlParts.Part where = SqlParts.find(parts, "WHERE");
      if (where != null) {
        return sql.substring(0, where.bodyStart()) + " (" + where.body() + "\n) AND (" + cond + "\n) " + sql.substring(where.end());
      }
      SqlParts.Part from = SqlParts.find(parts, "FROM");
      if (from != null) return sql.substring(0, from.end()) + " WHERE (" + cond + "\n) " + sql.substring(from.end());
    }
    return "SELECT * FROM (" + sql + "\n) " + WRAP_ALIAS + " WHERE (" + cond + "\n)";
  }
}
