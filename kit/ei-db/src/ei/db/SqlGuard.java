package ei.db;

import ei.db.console.SqlSplitter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 실행 전 위험 문장 판정. 주석 · 문자열을 지운 뒤 키워드로만 본다(구문 분석은 하지 않는다). */
public final class SqlGuard {
  private SqlGuard() {}

  /**
   * 프로시저 실행(CALL · EXEC · BEGIN · DECLARE · PostgreSQL DO · JDBC 이스케이프 {call …} · {? = call …})은 안에서 무엇을 바꿀지 모르므로 변경으로 본다.
   * REPLACE 는 MySQL 의 REPLACE INTO. COPY 는 TO(내보내기)도 함께 변경으로 본다. LOCK 은 트랜잭션 끝까지 잠금을 쥔다.
   */
  private static final Pattern WRITE = Pattern.compile(
      "^(?:(?:INSERT|UPDATE|DELETE|MERGE|REPLACE|CREATE|ALTER|DROP|TRUNCATE|RENAME|GRANT|REVOKE|COMMENT|BEGIN|DECLARE|CALL|EXEC|EXECUTE"
      + "|DO|COPY|REFRESH|VACUUM|CLUSTER|REINDEX|LOCK|FLASHBACK|PURGE|ANALYZE|AUDIT|NOAUDIT)\\b|\\{)");
  /** Oracle · MySQL 에서 실행 전에 앞 변경을 커밋하는 문장. ALTER SESSION 과 MySQL 의 CREATE/DROP TEMPORARY TABLE 은 커밋하지 않는다. */
  private static final Pattern DDL = Pattern.compile(
      "^(CREATE(?!\\s+TEMPORARY\\b)|ALTER(?!\\s+SESSION\\b)|DROP(?!\\s+TEMPORARY\\b)|TRUNCATE|RENAME|GRANT|REVOKE|COMMENT)\\b");
  /** WITH … 와 EXPLAIN ANALYZE … 뒤에 오는 변경. SELECT … FOR UPDATE 는 잠금이라 뺀다. */
  private static final Pattern DML = Pattern.compile("\\b(INSERT|(?<!FOR\\s{1,10})UPDATE|DELETE|MERGE)\\b");
  private static final Pattern WITH = Pattern.compile("^WITH\\b");
  private static final Pattern EXPLAIN_ANALYZE = Pattern.compile("^EXPLAIN\\b.*?\\bANALYZE\\b", Pattern.DOTALL);
  private static final Pattern UPDATE = Pattern.compile("^UPDATE\\s+([\\w.$#\"]+)");
  private static final Pattern DELETE = Pattern.compile("^DELETE\\s+(?:FROM\\s+)?([\\w.$#\"]+)");
  /** DROP 뒤 객체 종류(한 · 두 낱말)와 IF EXISTS 는 건너뛰고 이름을 잡는다. 두 낱말 종류를 한 낱말보다 먼저 맞춘다. */
  private static final Pattern DROP = Pattern.compile(
      "^(DROP|TRUNCATE)\\s+"
      + "(?:(?:MATERIALIZED\\s+VIEW|PACKAGE\\s+BODY|TYPE\\s+BODY|PUBLIC\\s+SYNONYM|PUBLIC\\s+DATABASE\\s+LINK|DATABASE\\s+LINK|FOREIGN\\s+TABLE"
      + "|TABLE|INDEX|VIEW|SEQUENCE|SYNONYM|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE|USER|SCHEMA|DATABASE|ROLE|TABLESPACE)\\s+)?"
      + "(?:CONCURRENTLY\\s+)?(?:IF\\s+EXISTS\\s+)?([\\w.$#\"]+)");
  private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b");
  /**
   * 괄호 밖 INTO 가 있는 조회: PostgreSQL 의 SELECT … INTO 새표(표를 만든다), MySQL 의 INTO OUTFILE · DUMPFILE(서버에 파일을 쓴다).
   * MySQL 의 INTO @변수는 세션 변수에 담기만 하므로 뺀다. Oracle 의 SELECT INTO 는 PL/SQL 블록 안에서만 쓰여 이미 변경으로 본다.
   * Oracle 12c 의 WITH FUNCTION · PROCEDURE 는 선언부 본문의 SELECT … INTO 변수라서 뺀다.
   */
  private static final Pattern SELECT_INTO = Pattern.compile("^(?:SELECT|WITH(?!\\s+(?:FUNCTION|PROCEDURE)\\b))\\b.*\\bINTO\\b(?!\\s*@)", Pattern.DOTALL);
  /** 트랜잭션을 끝내는 문장(PostgreSQL 의 END · ABORT 포함). 저장점까지만 되돌리는 ROLLBACK … TO 와 2단계 커밋의 … PREPARED 는 뺀다. */
  private static final Pattern TX_END = Pattern.compile("^(?:COMMIT|END|ABORT|ROLLBACK)\\b(?!\\s+PREPARED\\b)(?!.*\\bTO\\b)", Pattern.DOTALL);
  /** 공백뿐인 줄. 문장 수를 셀 때 지운다: 드라이버는 빈 줄에서 문장을 나누지 않는다(SqlSplitter 는 콘솔 편의로 나눈다). */
  private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t\\r]*(?=\\n)");
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

  /**
   * 실행 전 문장 정리: 앞뒤 공백과 끝의 ; 하나를 뗀다. PL/SQL 단위(isPlsql: CREATE [OR REPLACE] PROCEDURE · FUNCTION · PACKAGE [BODY] ·
   * TRIGGER · TYPE [BODY], DECLARE · BEGIN 블록)는 ; 를 남긴다. 콘솔 대기열 비교도 이 함수로 같은 글을 만든다.
   */
  public static String trimStatement(String sql) {
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
    if (SELECT_INTO.matcher(topLevel(QUOTED_NAME.matcher(s).replaceAll("\"\""))).find()) return true;
    return (WITH.matcher(s).find() || EXPLAIN_ANALYZE.matcher(s).find()) && DML.matcher(s).find();
  }

  /** COMMIT · ROLLBACK(저장점까지가 아닌 것) · PostgreSQL END · ABORT 처럼 지금 트랜잭션을 끝내는 문장인지. */
  public static boolean isTxEnd(String sql) {
    return TX_END.matcher(normalize(sql)).find();
  }

  /**
   * 한 칸에 문장이 둘 이상인지(SqlSplitter 의 ; 경계 · / 줄 기준, 빈 줄은 경계로 보지 않는다).
   * PostgreSQL · MySQL(allowMultiQueries) 드라이버는 이어진 문장을 모두 실행하지만 판정은 맨 앞 문장만 보므로 실행 전에 거절한다.
   */
  public static boolean hasSeveralStatements(String sql) {
    return hasSeveralStatements(sql, null);
  }

  /** hasSeveralStatements 와 같되 kind 가 MySQL 이면 # 줄 주석을 주석으로 본다. kind 는 null 이어도 된다. */
  public static boolean hasSeveralStatements(String sql, DbKind kind) {
    return SqlSplitter.split(BLANK_LINE.matcher(sql).replaceAll(""), kind == DbKind.MYSQL).size() > 1;
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
