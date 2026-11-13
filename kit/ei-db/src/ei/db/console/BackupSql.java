package ei.db.console;

import ei.db.DbKind;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「고치기 전 백업」의 순수 논리(IntelliJ 클래스 없음, 점검은 test 의 BackupCheck).
 * UPDATE · DELETE 문에서 표 · 별칭 · WHERE 절을 떼어 같은 행을 읽는 SELECT 를 만들고, 읽은 행으로 되돌리는 SQL 을 만들고,
 * 백업 파일의 이름과 머리 주석을 만들고 읽는다.
 * 받아들이는 꼴은 표 하나짜리 「UPDATE 표 [별칭] SET … [WHERE …]」 · 「DELETE [FROM] 표 [별칭] [WHERE …]」 뿐이다.
 * 그 밖의 UPDATE · DELETE · MERGE 는 「지원 안 함」과 이유를 돌려준다(틀린 백업을 만들지 않는다).
 */
public final class BackupSql {
  private BackupSql() {}

  /** 백업이 만들어진 곳. label 은 파일 머리에 쓰는 글이자 목록에 보이는 글이다. */
  public enum Origin {
    CONSOLE("콘솔"), GRID_EDIT("결과 표에서 고침"), GRID_DELETE("결과 표에서 지움"), MAPPER("매퍼 실행");

    private final String label;

    Origin(String label) { this.label = label; }

    public String label() { return label; }

    static Origin ofLabel(String label) {
      for (Origin o : values()) {
        if (o.label.equals(label)) return o;
      }
      return null;
    }
  }

  /** 파일에 든 되돌리는 문장의 꼴. */
  public enum Mode {
    UPDATE("UPDATE(PK 로 찾아 원래 값으로)"), INSERT("INSERT(지워진 행을 다시 넣음)"), INSERT_NO_PK("INSERT(PK 없음, 직접 확인)"),
    INSERT_PK_CHANGED("INSERT(PK 값을 바꾸는 UPDATE, 직접 확인)");

    private final String label;

    Mode(String label) { this.label = label; }

    String label() { return label; }

    static Mode ofLabel(String label) {
      for (Mode m : values()) {
        if (m.label.equals(label)) return m;
      }
      return null;
    }
  }

  /**
   * 백업할 대상. table 은 문장에 쓴 그대로(스키마 · 따옴표 포함), schema · name 은 그 조각(쓴 그대로, MySQL 백틱만 뗌, schema 는 없으면 null).
   * where 는 WHERE 뒤 글 그대로(없으면 null = 모든 행). bindsBefore 는 WHERE 앞의 ? 수, whereBinds 는 WHERE 안의 ? 수.
   * setColumns 는 UPDATE 의 SET 이 값을 넣는 칼럼 이름(대문자, 따옴표 뗌. 「(A, B) = (…)」 · 「별칭.칼럼」 은 넉넉하게 모두). DELETE 면 비어 있다.
   */
  public record Target(boolean delete, String table, String schema, String name, String alias, String where, int bindsBefore, int whereBinds,
                       Set<String> setColumns) {}

  /** target 과 unsupported 가 모두 null 이면 백업 대상 문장이 아니다(SELECT · INSERT · DDL 등). unsupported 는 백업하지 못하는 이유. */
  public record Parsed(Target target, String unsupported) {
    static final Parsed NONE = new Parsed(null, null);

    public boolean none() { return target == null && unsupported == null; }
  }

  static final String R_MERGE = "MERGE 문은 백업하지 않습니다";
  static final String R_WRAPPED = "WITH · EXPLAIN ANALYZE 로 감싼 변경 문장은 백업하지 않습니다";
  static final String R_SHAPE = "표 하나짜리 단순 UPDATE · DELETE 가 아닙니다(조인 · 서브쿼리 대상 · ONLY · DB 링크 · 파티션 지정 등)";
  static final String R_CLAUSE = "FROM · USING · RETURNING · ORDER BY · LIMIT · WHERE CURRENT OF 가 있는 문장은 백업하지 않습니다";
  static final String R_BINDS = "? 자리 수와 값 수가 달라 WHERE 쪽 값을 가를 수 없습니다";
  static final String R_BLOCK = "PL/SQL 블록 · 프로시저 호출(BEGIN · DECLARE · DO · CALL · EXEC)은 안에서 무엇을 바꾸는지 알 수 없어 백업하지 않습니다";
  static final String R_TRUNCATE = "TRUNCATE 는 백업하지 않습니다(되돌릴 수 없습니다)";
  static final String R_MODIFIER = "IGNORE · LOW_PRIORITY · QUICK 이 붙은 UPDATE · DELETE 는 백업하지 않습니다";
  static final String R_VOLATILE = "WHERE 에 실행할 때마다 값이 달라지는 것(ROWNUM · 난수 · 시퀀스)이 있어 같은 행을 읽는다고 보장할 수 없습니다";
  static final String R_PARSE = "문장을 끝까지 읽지 못했습니다(닫히지 않은 따옴표 · 주석 · 괄호, 문장 안의 ;)";

  private static final char WORD = 'w';
  private static final char QUOTED = 'q';
  private static final char LITERAL = 'l';
  private static final char SYMBOL = 's';
  private static final char BIND = '?';
  /** 스키마.표 까지만 받는다(카탈로그.스키마.표 · DB 링크는 PK 를 정확히 찾지 못한다). */
  private static final int MAX_NAME_PARTS = 2;
  private static final Set<String> DML_WORDS = Set.of("UPDATE", "DELETE", "MERGE");
  /** 안에서 무엇을 바꾸는지 알 수 없는 실행의 첫 낱말. BEGIN 은 트랜잭션 시작(BEGIN_TX_WORDS 가 뒤따르거나 혼자)이면 뺀다. */
  private static final Set<String> BLOCK_WORDS = Set.of("BEGIN", "DECLARE", "DO", "CALL", "EXEC", "EXECUTE");
  private static final Set<String> BEGIN_TX_WORDS = Set.of("TRANSACTION", "WORK", "ISOLATION", "READ", "NOT", "DEFERRABLE");
  /** MySQL 이 UPDATE · DELETE 바로 뒤에 받는 수식어. 표 이름으로 읽지 않는다. */
  private static final Set<String> MYSQL_MODIFIERS = Set.of("IGNORE", "LOW_PRIORITY", "QUICK");
  /** WHERE 안에 있으면 백업 SELECT 와 실제 문장이 다른 행을 고를 수 있거나(ROWNUM · 난수), SELECT 가 값을 바꾸는(시퀀스) 낱말. */
  private static final Set<String> VOLATILE_WORDS = Set.of("ROWNUM", "RANDOM", "RAND", "DBMS_RANDOM", "NEXTVAL", "SYS_GUID", "GEN_RANDOM_UUID");

  /** text 의 [start, end) 낱말 하나. text 는 WORD 면 대문자, 나머지는 쓴 그대로. depth 는 괄호 깊이(괄호 자신은 바깥 깊이). */
  private record Tok(char type, String text, int start, int end, int depth) {
    boolean word(String upper) { return type == WORD && text.equals(upper); }

    boolean symbol(String s) { return type == SYMBOL && text.equals(s); }

    boolean name() { return type == WORD || type == QUOTED; }
  }

  /**
   * 문장을 읽어 백업 대상인지 가른다. bindCount 는 실행 때 넘기는 ? 값의 수(0 이면 ? 를 자리로 보지 않는다).
   * 끝의 ; 하나는 뗀다. kind 는 문자열 · 따옴표 규칙을 정한다(MySQL 의 \ 이스케이프 · 백틱 · # 주석, PostgreSQL 의 E'…' · $tag$…$tag$, Oracle 의 q'[…]').
   */
  public static Parsed parse(String sql, int bindCount, DbKind kind) {
    String text = sql.strip();
    if (text.endsWith(";")) text = text.substring(0, text.length() - 1).strip();
    List<Tok> all = scan(text, kind);
    if (all == null) return startsLikeChange(text) ? new Parsed(null, R_PARSE) : Parsed.NONE;
    if (all.isEmpty()) return Parsed.NONE;
    Tok head = all.get(0);
    if (head.word("MERGE")) return new Parsed(null, R_MERGE);
    if (head.word("TRUNCATE")) return new Parsed(null, R_TRUNCATE);
    Tok second = all.size() > 1 ? all.get(1) : null;
    boolean beginTx = head.word("BEGIN") && (second == null || (second.type() == WORD && BEGIN_TX_WORDS.contains(second.text())));
    if (head.type() == WORD && BLOCK_WORDS.contains(head.text()) && !beginTx) return new Parsed(null, R_BLOCK);
    // JDBC 호출 꼴 「{call …}」 · 「{? = call …}」.
    if (head.symbol("{") && hasWord(all, "CALL")) return new Parsed(null, R_BLOCK);
    if (kind == DbKind.MYSQL && second != null && second.type() == WORD && MYSQL_MODIFIERS.contains(second.text())
        && (head.word("UPDATE") || head.word("DELETE"))) {
      return new Parsed(null, R_MODIFIER);
    }
    if (head.word("WITH") || (head.word("EXPLAIN") && hasWord(all, "ANALYZE"))) {
      return hasChange(all) ? new Parsed(null, R_WRAPPED) : Parsed.NONE;
    }
    boolean delete = head.word("DELETE");
    if (!delete && !head.word("UPDATE")) return Parsed.NONE;

    List<Tok> top = new ArrayList<>();
    for (Tok t : all) {
      if (t.depth() == 0) top.add(t);
    }
    for (Tok t : top) {
      if (t.symbol(";")) return new Parsed(null, R_PARSE);
    }
    int i = 1;
    if (delete && i < top.size() && top.get(i).word("FROM")) i++;
    // 표 이름: 이름 [. 이름]
    List<Tok> parts = new ArrayList<>();
    if (i >= top.size() || !top.get(i).name() || top.get(i).word("ONLY")) return new Parsed(null, R_SHAPE);
    parts.add(top.get(i++));
    while (i + 1 < top.size() && top.get(i).symbol(".") && top.get(i + 1).name()) {
      parts.add(top.get(i + 1));
      i += 2;
    }
    if (parts.size() > MAX_NAME_PARTS) return new Parsed(null, R_SHAPE);
    // 별칭: [AS] 이름
    String alias = null;
    if (i < top.size() && top.get(i).word("AS")) {
      if (i + 1 >= top.size() || !top.get(i + 1).name()) return new Parsed(null, R_SHAPE);
      alias = written(text, top.get(i + 1));
      i += 2;
    } else if (i < top.size() && top.get(i).name() && !top.get(i).word("SET") && !top.get(i).word("WHERE")) {
      alias = written(text, top.get(i));
      i++;
    }
    if (delete) {
      if (i < top.size() && !top.get(i).word("WHERE")) return new Parsed(null, R_SHAPE);
    } else {
      if (i >= top.size() || !top.get(i).word("SET")) return new Parsed(null, R_SHAPE);
      i++;
    }
    Tok set = delete ? null : top.get(i - 1);
    Tok where = null;
    for (int k = i; k < top.size(); k++) {
      Tok t = top.get(k);
      Tok next = k + 1 < top.size() ? top.get(k + 1) : null;
      if (where == null && t.word("WHERE")) {
        where = t;
        if (next != null && next.word("CURRENT") && k + 2 < top.size() && top.get(k + 2).word("OF")) return new Parsed(null, R_CLAUSE);
        continue;
      }
      if (t.word("RETURNING") || t.word("RETURN") || t.word("LIMIT")) return new Parsed(null, R_CLAUSE);
      // UPDATE … FROM(PostgreSQL)은 WHERE 앞에만 온다. WHERE 안의 FROM(IS DISTINCT FROM)은 조건의 일부다.
      if (where == null && (t.word("FROM") || t.word("USING"))) return new Parsed(null, R_CLAUSE);
      if (next != null && ((t.word("ORDER") && next.word("BY")) || (t.word("LOG") && next.word("ERRORS")))) return new Parsed(null, R_CLAUSE);
    }
    String condition = null;
    int before = 0;
    int inWhere = 0;
    if (where != null) {
      condition = text.substring(where.end()).strip();
      if (condition.isEmpty()) return new Parsed(null, R_SHAPE);
      for (Tok t : all) {
        if (t.start() > where.start() && t.type() == WORD && VOLATILE_WORDS.contains(t.text())) return new Parsed(null, R_VOLATILE);
      }
    }
    for (Tok t : all) {
      if (t.type() != BIND) continue;
      if (where != null && t.start() > where.start()) inWhere++; else before++;
    }
    if (bindCount > 0 && before + inWhere != bindCount) return new Parsed(null, R_BINDS);
    if (bindCount == 0) {
      before = 0;
      inWhere = 0;
    }
    List<String> names = new ArrayList<>();
    for (Tok p : parts) names.add(written(text, p));
    String schema = names.size() == MAX_NAME_PARTS ? unbacktick(names.get(0)) : null;
    String name = unbacktick(names.get(names.size() - 1));
    return new Parsed(new Target(delete, String.join(".", names), schema, name, alias, condition, before, inWhere, assigned(all, set, where)), null);
  }

  /** SET 과 WHERE 사이에서 값을 받는 쪽(SET 바로 뒤 · 깊이 0 의 쉼표 뒤부터 깊이 0 의 = 앞까지)의 이름들. set 이 null 이면 빈 집합. */
  private static Set<String> assigned(List<Tok> all, Tok set, Tok where) {
    Set<String> out = new HashSet<>();
    if (set == null) return out;
    boolean target = true;
    for (Tok t : all) {
      if (t.start() < set.end() || (where != null && t.start() >= where.start())) continue;
      if (t.depth() == 0 && t.symbol(",")) {
        target = true;
      } else if (t.depth() == 0 && t.symbol("=")) {
        target = false;
      } else if (target && t.type() == WORD) {
        out.add(t.text());
      } else if (target && t.type() == QUOTED) {
        out.add(t.text().substring(1, t.text().length() - 1).toUpperCase(Locale.ROOT));
      }
    }
    return out;
  }

  /** 백업할 행을 읽는 문장: 같은 표 · 같은 별칭 · 같은 WHERE 의 SELECT *. Oracle 은 표 별칭에 AS 를 받지 않으므로 AS 없이 쓴다. */
  public static String select(Target t) {
    return "SELECT\n       *\n  FROM " + t.table() + (t.alias() == null ? "" : " " + t.alias())
        + (t.where() == null ? "" : "\n WHERE " + t.where());
  }

  /** 되돌리는 문장들(body)과 뺀 LOB · 이진 칼럼(excluded), 문장의 꼴(mode). */
  public record Restore(String body, List<String> excluded, Mode mode) {}

  /**
   * 읽어 둔 행으로 되돌리는 문장을 만든다. DELETE 면 행마다 INSERT. UPDATE 면 PK 로 찾아 PK 아닌 모든 칼럼을 원래 값으로 되돌리는 UPDATE,
   * PK 가 없거나(pkUpper 가 빔) PK 로 되돌리는 문장을 만들 수 없으면(PK 가 LOB · 이진, PK 칼럼뿐인 표) INSERT 로 남기고 mode 를 INSERT_NO_PK 로 한다.
   * LOB · 이진 칼럼은 뺀다(SqlText 규칙). 남는 칼럼이 없거나, 글로 옮길 수 없는 값(드라이버 객체: 사용자 정의 형 · XML 등)이 있으면
   * IllegalArgumentException(한국어 문구): 틀린 값을 적은 백업을 만들지 않는다.
   * table 은 문장에 쓴 그대로, pkUpper 는 대문자 PK 칼럼 이름.
   */
  public static Restore restore(boolean delete, String table, List<String> columns, List<String> types, List<List<Object>> rows,
                                Set<String> pkUpper, DbKind kind) {
    for (int i = 0; i < columns.size(); i++) {
      String type = i < types.size() ? types.get(i) : "";
      if (SqlText.isUneditableType(type)) continue;
      for (List<Object> row : rows) {
        Object v = row.get(i);
        if (v != null && !SqlText.isLobValue(v) && String.valueOf(v).startsWith(v.getClass().getName() + "@")) {
          throw new IllegalArgumentException("칼럼 " + columns.get(i) + "(" + type + ") 의 값을 SQL 글로 옮길 수 없습니다.");
        }
      }
    }
    String body = "";
    Mode mode = delete ? Mode.INSERT : Mode.UPDATE;
    if (!delete && !pkUpper.isEmpty()) {
      try {
        body = SqlText.updates(table, columns, types, rows, pkUpper, kind);
      } catch (IllegalArgumentException e) {
        // PK 로 되돌릴 수 없는 표: 아래에서 INSERT 로 남긴다.
        body = "";
      }
    }
    if (body.isEmpty()) {
      if (!delete) mode = Mode.INSERT_NO_PK;
      body = SqlText.inserts(table, columns, types, rows, kind);
    }
    return new Restore(body, excludedOf(body), mode);
  }

  /** SqlText 가 문장 맨 위에 단 「LOB 칼럼 제외」 줄에서 칼럼 이름을 읽는다. 없으면 빈 목록. */
  static List<String> excludedOf(String body) {
    if (!body.startsWith(SqlText.LOB_EXCLUDED)) return List.of();
    int end = body.indexOf('\n');
    String line = body.substring(SqlText.LOB_EXCLUDED.length(), end < 0 ? body.length() : end);
    return List.of(line.split(", "));
  }

  /** 결과 표의 「행 지우기」 문장: PK 로 찾는 DELETE. preview 는 리터럴, sql 은 ? 자리 + binds. null 인 PK 값은 두 형태 모두 IS NULL. */
  static SqlText.Update delete(String table, List<String> pkNames, List<Object> pkValues, DbKind kind) {
    StringBuilder preview = new StringBuilder("DELETE FROM ").append(table).append("\n WHERE 1 = 1");
    StringBuilder sql = new StringBuilder(preview);
    List<Object> binds = new ArrayList<>();
    for (int i = 0; i < pkNames.size(); i++) {
      Object v = pkValues.get(i);
      String head = "\n   AND " + pkNames.get(i);
      if (v == null) {
        preview.append(head).append(" IS NULL");
        sql.append(head).append(" IS NULL");
        continue;
      }
      preview.append(head).append(" = ").append(SqlText.literal(v, kind));
      sql.append(head).append(" = ?");
      binds.add(v);
    }
    return new SqlText.Update(preview.append(";\n").toString(), sql.append('\n').toString(), binds);
  }

  // ---- 파일 이름 · 머리 ----

  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final DateTimeFormatter HEAD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  static final String EXTENSION = ".sql";
  private static final int MAX_NAME_LENGTH = 80;
  private static final char SAFE_CHAR = '_';
  /** 윈도우가 파일 이름으로 받지 않는 장치 이름. */
  private static final Set<String> DEVICE_NAMES = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
      "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

  /**
   * 파일 · 폴더 이름으로 쓸 글: 글자 · 숫자 · - · _ · . 밖의 글자는 _ 로 바꾸고, 끝의 점(윈도우가 떼어 버림)도 _ 로 바꾼다.
   * 비었거나 점뿐이면 _, 윈도우 장치 이름이면 앞에 _ 를 붙이고, MAX_NAME_LENGTH 자에서 자른다.
   */
  public static String safeName(String name) {
    StringBuilder sb = new StringBuilder();
    String text = name == null ? "" : name.strip();
    for (int i = 0; i < text.length() && sb.length() < MAX_NAME_LENGTH; i++) {
      char ch = text.charAt(i);
      sb.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.' ? ch : SAFE_CHAR);
    }
    int end = sb.length();
    while (end > 0 && sb.charAt(end - 1) == '.') sb.setCharAt(--end, SAFE_CHAR);
    if (sb.isEmpty()) return String.valueOf(SAFE_CHAR);
    String out = sb.toString();
    int dot = out.indexOf('.');
    String stem = dot < 0 ? out : out.substring(0, dot);
    return DEVICE_NAMES.contains(stem.toUpperCase(Locale.ROOT)) ? SAFE_CHAR + out : out;
  }

  /** 「yyyyMMdd-HHmmss_표.sql」. 같은 초에 같은 표를 또 쓰면 nth(2 부터)를 「-2」 꼴로 붙인다. table 은 스키마 · 따옴표를 뗀 이름으로 쓴다. */
  public static String fileName(LocalDateTime time, String table, int nth) {
    String bare = table.replace("\"", "").replace("`", "");
    bare = bare.substring(bare.lastIndexOf('.') + 1);
    return FILE_TIME.format(time) + "_" + safeName(bare) + (nth > 1 ? "-" + nth : "") + EXTENSION;
  }

  private static final java.util.regex.Pattern OUR_FILE = java.util.regex.Pattern.compile("\\d{8}-\\d{6}_.*\\.sql");

  /** 우리가 쓴 백업 파일인지: 이름이 「yyyyMMdd-HHmmss_….sql」 꼴이고 첫 줄이 우리 머리. 보존 일수 정리는 이런 파일만 지운다. */
  static boolean isOurs(String fileName, String firstLine) {
    return fileName != null && OUR_FILE.matcher(fileName).matches() && TITLE.equals(firstLine);
  }

  /** 파일 머리에 적는 것. excluded 는 뺀 LOB · 이진 칼럼, sql 은 원래 문장(? 값은 적지 않는다). */
  public record Header(LocalDateTime time, String connection, Origin origin, String table, int rows, List<String> excluded, Mode mode, String sql) {}

  private static final String TITLE = "-- Moa IDE 고치기 전 백업";
  private static final String K_TIME = "-- 시각: ";
  private static final String K_CONNECTION = "-- 접속: ";
  private static final String K_ORIGIN = "-- 종류: ";
  private static final String K_TABLE = "-- 표: ";
  private static final String K_ROWS = "-- 행 수: ";
  private static final String K_EXCLUDED = "-- 뺀 칼럼(LOB · 이진): ";
  private static final String K_MODE = "-- 되돌리는 문장: ";
  private static final String K_SQL = "-- 원래 SQL:";
  private static final String SQL_LINE = "--   ";
  private static final String NONE = "없음";
  private static final String NOTE = "-- 안내: 트리거 · 시퀀스로 따라 바뀐 것은 되돌리지 않습니다. PK 값을 바꾼 UPDATE 는 PK 로 찾는 문장으로 되돌릴 수 없습니다.\n"
      + "-- 안내: 실행 전에 지금 데이터와 견주어 확인하세요. 실행이 실패 · 취소 · 롤백됐어도 이 파일은 남습니다.";
  static final String END = "-- ==== 여기부터 되돌리는 문장 ====";

  /** 머리 주석(끝 줄바꿈 포함). 그 뒤에 되돌리는 문장을 이어 쓴다. */
  public static String format(Header h) {
    StringBuilder sb = new StringBuilder(TITLE).append('\n');
    sb.append(K_TIME).append(HEAD_TIME.format(h.time())).append('\n');
    sb.append(K_CONNECTION).append(oneLine(h.connection())).append('\n');
    sb.append(K_ORIGIN).append(h.origin().label()).append('\n');
    sb.append(K_TABLE).append(oneLine(h.table())).append('\n');
    sb.append(K_ROWS).append(h.rows()).append('\n');
    sb.append(K_EXCLUDED).append(h.excluded().isEmpty() ? NONE : String.join(", ", h.excluded())).append('\n');
    sb.append(K_MODE).append(h.mode().label()).append('\n');
    sb.append(NOTE).append('\n');
    sb.append(K_SQL).append('\n');
    for (String line : h.sql().strip().split("\\R", -1)) sb.append(SQL_LINE).append(line).append('\n');
    return sb.append(END).append('\n').toString();
  }

  /** format 이 만든 머리를 읽는다. lines 는 파일의 앞 줄들(END 줄 뒤는 보지 않는다). 우리 백업 파일 꼴이 아니면 null. */
  public static Header parseHeader(List<String> lines) {
    if (lines.isEmpty() || !lines.get(0).equals(TITLE)) return null;
    LocalDateTime time = null;
    String connection = null;
    Origin origin = null;
    String table = null;
    int rows = -1;
    List<String> excluded = List.of();
    Mode mode = null;
    StringBuilder sql = null;
    boolean ended = false;
    for (int i = 1; i < lines.size() && !ended; i++) {
      String line = lines.get(i);
      if (line.equals(END)) {
        ended = true;
      } else if (sql != null) {
        if (!line.startsWith(SQL_LINE.stripTrailing())) return null;
        if (!sql.isEmpty()) sql.append('\n');
        sql.append(line.length() >= SQL_LINE.length() ? line.substring(SQL_LINE.length()) : "");
      } else if (line.equals(K_SQL)) {
        sql = new StringBuilder();
      } else if (line.startsWith(K_TIME)) {
        try {
          time = LocalDateTime.parse(line.substring(K_TIME.length()), HEAD_TIME);
        } catch (DateTimeParseException e) {
          return null;
        }
      } else if (line.startsWith(K_CONNECTION)) {
        connection = line.substring(K_CONNECTION.length());
      } else if (line.startsWith(K_ORIGIN)) {
        origin = Origin.ofLabel(line.substring(K_ORIGIN.length()));
      } else if (line.startsWith(K_TABLE)) {
        table = line.substring(K_TABLE.length());
      } else if (line.startsWith(K_ROWS)) {
        try {
          rows = Integer.parseInt(line.substring(K_ROWS.length()));
        } catch (NumberFormatException e) {
          return null;
        }
      } else if (line.startsWith(K_EXCLUDED)) {
        String v = line.substring(K_EXCLUDED.length());
        excluded = v.equals(NONE) ? List.of() : List.of(v.split(", "));
      } else if (line.startsWith(K_MODE)) {
        mode = Mode.ofLabel(line.substring(K_MODE.length()));
      }
    }
    if (!ended || time == null || connection == null || origin == null || table == null || rows < 0 || mode == null || sql == null) return null;
    return new Header(time, connection, origin, table, rows, excluded, mode, sql.toString());
  }

  private static String oneLine(String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").strip();
  }

  // ---- 문장 읽기 ----

  private static String written(String text, Tok t) {
    return text.substring(t.start(), t.end());
  }

  private static String unbacktick(String name) {
    return name.startsWith("`") && name.endsWith("`") && name.length() > 1 ? name.substring(1, name.length() - 1).replace("``", "`") : name;
  }

  private static boolean hasWord(List<Tok> all, String upper) {
    for (Tok t : all) {
      if (t.word(upper)) return true;
    }
    return false;
  }

  /** 어느 깊이에든 UPDATE · DELETE · MERGE 낱말이 있는지. FOR UPDATE(조회 잠금)의 UPDATE 는 뺀다. */
  private static boolean hasChange(List<Tok> all) {
    for (int i = 0; i < all.size(); i++) {
      Tok t = all.get(i);
      if (t.type() != WORD || !DML_WORDS.contains(t.text())) continue;
      if (t.word("UPDATE") && i > 0 && all.get(i - 1).word("FOR")) continue;
      return true;
    }
    return false;
  }

  private static boolean startsLikeChange(String text) {
    String upper = text.toUpperCase(Locale.ROOT);
    for (String w : new String[]{"UPDATE", "DELETE", "MERGE", "WITH"}) {
      if (upper.startsWith(w) && (upper.length() == w.length() || !isIdent(upper.charAt(w.length())))) return true;
    }
    return false;
  }

  private static boolean isIdent(char ch) {
    return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '#';
  }

  /** 주석은 건너뛰고 문자열 · 따옴표 이름은 한 덩이로 읽는다. 닫히지 않은 따옴표 · 주석 · 괄호가 있으면 null. */
  private static List<Tok> scan(String s, DbKind kind) {
    List<Tok> out = new ArrayList<>();
    int n = s.length();
    int depth = 0;
    int i = 0;
    while (i < n) {
      char ch = s.charAt(i);
      char next = i + 1 < n ? s.charAt(i + 1) : 0;
      if (Character.isWhitespace(ch)) {
        i++;
      } else if ((ch == '-' && next == '-') || (ch == '#' && kind == DbKind.MYSQL)) {
        int k = s.indexOf('\n', i);
        i = k < 0 ? n : k;
      } else if (ch == '/' && next == '*') {
        int k = s.indexOf("*/", i + 2);
        if (k < 0) return null;
        // PostgreSQL 은 주석 안의 주석을 겹쳐 읽어 끝나는 곳이 다르다: 읽지 못한 문장으로 돌린다.
        if (kind == DbKind.POSTGRES && s.substring(i + 2, k).contains("/*")) return null;
        i = k + 2;
      } else if (ch == '\'' || (ch == '"' && kind == DbKind.MYSQL)) {
        int end = stringEnd(s, i, kind);
        if (end < 0) return null;
        out.add(new Tok(LITERAL, "", i, end, depth));
        i = end;
      } else if (ch == '"' || ch == '`') {
        int end = quotedEnd(s, i, ch);
        if (end < 0) return null;
        out.add(new Tok(QUOTED, s.substring(i, end), i, end, depth));
        i = end;
      } else if (ch == '$' && kind == DbKind.POSTGRES && (i == 0 || !isIdent(s.charAt(i - 1))) && dollarTagEnd(s, i) > 0) {
        int tagEnd = dollarTagEnd(s, i);
        int close = s.indexOf(s.substring(i, tagEnd), tagEnd);
        if (close < 0) return null;
        int end = close + (tagEnd - i);
        out.add(new Tok(LITERAL, "", i, end, depth));
        i = end;
      } else if (ch == '?') {
        out.add(new Tok(BIND, "?", i, i + 1, depth));
        i++;
      } else if (isIdent(ch)) {
        int j = i + 1;
        while (j < n && isIdent(s.charAt(j))) j++;
        out.add(new Tok(WORD, s.substring(i, j).toUpperCase(Locale.ROOT), i, j, depth));
        i = j;
      } else {
        if (ch == ')') {
          depth--;
          if (depth < 0) return null;
        }
        out.add(new Tok(SYMBOL, String.valueOf(ch), i, i + 1, depth));
        if (ch == '(') depth++;
        i++;
      }
    }
    return depth == 0 ? out : null;
  }

  /**
   * at 의 따옴표에서 시작한 문자열이 끝난 다음 위치. 닫히지 않았으면 -1.
   * '' 는 따옴표 하나. MySQL 과 PostgreSQL 의 E'…' 는 \ 뒤 글자를 건너뛴다. Oracle 의 q'[…]' 는 닫는 구분 글자 + ' 까지.
   */
  private static int stringEnd(String s, int at, DbKind kind) {
    int n = s.length();
    char quote = s.charAt(at);
    char before = at > 0 ? s.charAt(at - 1) : 0;
    boolean prefixed = at > 0 && (at < 2 || !isIdent(s.charAt(at - 2)) || ((s.charAt(at - 2) == 'n' || s.charAt(at - 2) == 'N') && (at < 3 || !isIdent(s.charAt(at - 3)))));
    if (kind == DbKind.ORACLE && prefixed && (before == 'q' || before == 'Q') && at + 1 < n) {
      char open = s.charAt(at + 1);
      char close = switch (open) {
        case '[' -> ']';
        case '{' -> '}';
        case '(' -> ')';
        case '<' -> '>';
        default -> open;
      };
      int k = s.indexOf(close + "'", at + 2);
      return k < 0 ? -1 : k + 2;
    }
    boolean backslash = kind == DbKind.MYSQL || (kind == DbKind.POSTGRES && prefixed && (before == 'e' || before == 'E'));
    int j = at + 1;
    while (j < n) {
      char ch = s.charAt(j);
      if (backslash && ch == '\\') {
        j += 2;
      } else if (ch == quote) {
        if (j + 1 < n && s.charAt(j + 1) == quote) j += 2; else return j + 1;
      } else {
        j++;
      }
    }
    return -1;
  }

  /** 따옴표 이름("…" · `…`, 같은 따옴표 둘은 따옴표 하나)이 끝난 다음 위치. 닫히지 않았으면 -1. */
  private static int quotedEnd(String s, int at, char quote) {
    int n = s.length();
    int j = at + 1;
    while (j < n) {
      if (s.charAt(j) != quote) {
        j++;
      } else if (j + 1 < n && s.charAt(j + 1) == quote) {
        j += 2;
      } else {
        return j + 1;
      }
    }
    return -1;
  }

  /** at 의 $ 가 PostgreSQL 달러 따옴표 꼬리표($$ · $tag$)의 시작이면 꼬리표가 끝난 다음 위치, 아니면 -1. */
  private static int dollarTagEnd(String s, int at) {
    int j = at + 1;
    while (j < s.length() && (Character.isLetter(s.charAt(j)) || s.charAt(j) == '_' || (j > at + 1 && Character.isDigit(s.charAt(j))))) j++;
    return j < s.length() && s.charAt(j) == '$' ? j + 1 : -1;
  }
}
