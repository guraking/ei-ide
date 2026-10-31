package ei.db;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 실행 전 위험 문장 판정. 주석 · 문자열을 지운 뒤 키워드로만 본다(구문 분석은 하지 않는다). */
public final class SqlGuard {
  private SqlGuard() {}

  /** 프로시저 실행(CALL · EXEC · BEGIN · DECLARE)은 안에서 무엇을 바꿀지 모르므로 변경으로 본다. REPLACE 는 MySQL 의 REPLACE INTO. */
  private static final Pattern WRITE = Pattern.compile(
      "^(INSERT|UPDATE|DELETE|MERGE|REPLACE|CREATE|ALTER|DROP|TRUNCATE|RENAME|GRANT|REVOKE|COMMENT|BEGIN|DECLARE|CALL|EXEC|EXECUTE)\\b");
  /** Oracle · MySQL 에서 실행 전에 앞 변경을 커밋하는 문장. ALTER SESSION 과 MySQL 의 CREATE/DROP TEMPORARY TABLE 은 커밋하지 않는다. */
  private static final Pattern DDL = Pattern.compile(
      "^(CREATE(?!\\s+TEMPORARY\\b)|ALTER(?!\\s+SESSION\\b)|DROP(?!\\s+TEMPORARY\\b)|TRUNCATE|RENAME|GRANT|REVOKE|COMMENT)\\b");
  /** WITH … 와 EXPLAIN ANALYZE … 뒤에 오는 변경. SELECT … FOR UPDATE 는 잠금이라 뺀다. */
  private static final Pattern DML = Pattern.compile("\\b(INSERT|(?<!FOR\\s{1,10})UPDATE|DELETE|MERGE)\\b");
  private static final Pattern WITH = Pattern.compile("^WITH\\b");
  private static final Pattern EXPLAIN_ANALYZE = Pattern.compile("^EXPLAIN\\b.*?\\bANALYZE\\b", Pattern.DOTALL);
  private static final Pattern UPDATE = Pattern.compile("^UPDATE\\s+([\\w.$#\"]+)");
  private static final Pattern DELETE = Pattern.compile("^DELETE\\s+(?:FROM\\s+)?([\\w.$#\"]+)");
  private static final Pattern DROP = Pattern.compile("^(DROP|TRUNCATE)\\s+(?:TABLE\\s+)?([\\w.$#\"]+)");
  private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b");
  /** 끝의 ; 가 문장의 일부인 PL/SQL 단위(END 이름; 까지 그대로 보낸다). */
  private static final Pattern PLSQL = Pattern.compile(
      "^(?:CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE)\\b|DECLARE\\b|BEGIN\\b)");
  /** 따옴표로 감싼 이름: 큰따옴표(Oracle · PostgreSQL, "" 이스케이프) · 백틱(MySQL, `` 이스케이프). WHERE 판정에서만 지운다. 이름 안의 WHERE 를 조건절로 보지 않게. */
  private static final Pattern QUOTED_NAME = Pattern.compile("\"(?:[^\"]++|\"\")*+\"|`(?:[^`]++|``)*+`");
  /** 소유 수량자(++ · *+)는 긴 문자열에서 StackOverflowError 를 막는다. 문자열('' 이스케이프 포함) · 블록 주석 · 줄 주석을 한 번에 훑어 앞에 나온 것부터 지운다(문자열 안의 -- 를 주석으로 보지 않게). */
  private static final Pattern STRING_OR_COMMENT = Pattern.compile("'(?:[^']++|'')*+'|/\\*.*?\\*/|--[^\\n]*", Pattern.DOTALL);

  /** 주석과 문자열 안을 지우고 대문자로 바꾼 본문. 문자열은 '' 로 남는다. */
  static String normalize(String sql) {
    Matcher m = STRING_OR_COMMENT.matcher(sql);
    StringBuilder out = new StringBuilder();
    while (m.find()) m.appendReplacement(out, m.group().startsWith("'") ? "''" : " ");
    m.appendTail(out);
    return out.toString().trim().toUpperCase(Locale.ROOT);
  }

  /** DbSessions.normalizeSql 의 본문: 앞뒤 공백과 끝의 ; 하나를 뗀다. PL/SQL 단위(isPlsql)는 ; 를 남긴다. */
  static String trimStatement(String sql) {
    String text = sql.strip();
    if (text.endsWith(";") && !isPlsql(text)) text = text.substring(0, text.length() - 1).strip();
    return text;
  }

  /** CREATE [OR REPLACE] PROCEDURE · FUNCTION · PACKAGE [BODY] · TRIGGER · TYPE [BODY], DECLARE · BEGIN 블록인지. */
  public static boolean isPlsql(String sql) {
    return PLSQL.matcher(normalize(sql)).find();
  }

  public static boolean isWrite(String sql) {
    String s = normalize(sql);
    if (WRITE.matcher(s).find()) return true;
    return (WITH.matcher(s).find() || EXPLAIN_ANALYZE.matcher(s).find()) && DML.matcher(s).find();
  }

  /** 자동 커밋이 꺼져 있어도 Oracle · MySQL 이 앞 변경까지 커밋해 버리는 DDL 인지. */
  public static boolean isDdl(String sql) {
    return DDL.matcher(normalize(sql)).find();
  }

  /** 막아야 할 이유. 괜찮으면 null. WHERE 는 괄호 밖(서브쿼리 아닌 것)만 센다. */
  public static String danger(String sql) {
    String s = normalize(sql);
    boolean where = WHERE.matcher(topLevel(QUOTED_NAME.matcher(s).replaceAll("\"\""))).find();
    if (UPDATE.matcher(s).find() && !where) return "WHERE 없는 UPDATE";
    if (DELETE.matcher(s).find() && !where) return "WHERE 없는 DELETE";
    Matcher m = DROP.matcher(s);
    if (m.find()) return m.group(1) + " 문";
    return null;
  }

  /** 괄호 안 글자를 공백으로 바꾼 본문(깊이 0 만 남김). */
  static String topLevel(String s) {
    StringBuilder out = new StringBuilder(s.length());
    int depth = 0;
    for (char ch : s.toCharArray()) {
      if (ch == '(') depth++;
      out.append(depth > 0 ? ' ' : ch);
      if (ch == ')' && depth > 0) depth--;
    }
    return out.toString();
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
