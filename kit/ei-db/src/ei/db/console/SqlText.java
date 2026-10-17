package ei.db.console;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 결과 행을 SQL 글로 바꾸는 순수 함수들(INSERT · UPDATE 복사, 결과 칸 고치기). 쉼표는 줄 앞에 둔다. */
final class SqlText {
  private SqlText() {}

  private static final String FIRST = "       ";
  private static final String NEXT = "     , ";
  /** 값과 값 뒤 칼럼 주석 사이 최소 간격. */
  private static final int COMMENT_GAP = 2;
  private static final Pattern NUMERIC_TYPE = Pattern.compile(
      "(?i)(NUMBER|NUMERIC|DECIMAL|DEC|FLOAT\\d*|DOUBLE( PRECISION)?|REAL|BINARY_FLOAT|BINARY_DOUBLE"
      + "|(TINY|SMALL|MEDIUM|BIG)?INT(EGER)?\\d*|(SMALL|BIG)?SERIAL\\d*)( UNSIGNED)?");
  private static final Pattern UNEDITABLE_TYPE = Pattern.compile("(?i)(\\w*LOB|LONG( RAW)?|\\w*RAW|BYTEA|(VAR)?BINARY|IMAGE)");
  private static final String IDENT = "[A-Z_][\\w$#]*";
  private static final Pattern SET_OPERATION = Pattern.compile("\\b(UNION|INTERSECT|MINUS|EXCEPT)\\b");
  private static final Pattern SELECT = Pattern.compile("\\bSELECT\\b");
  private static final Pattern GROUPED = Pattern.compile("\\b(GROUP BY|HAVING|DISTINCT|UNIQUE|CONNECT BY|START WITH)\\b");
  /** 대문자 · 공백 하나로 고른 문장: 1 은 SELECT 목록, 2 는 첫 FROM 뒤 전부. */
  private static final Pattern SELECT_FROM = Pattern.compile("SELECT (.+?) FROM (.+)");
  private static final Pattern SELECT_LIST = Pattern.compile(
      "\\*|(" + IDENT + "\\.)?" + IDENT + "( ?, ?(" + IDENT + "\\.)?" + IDENT + ")*");
  private static final Pattern SCHEMA_TABLE = Pattern.compile(IDENT + "\\.");
  private static final Pattern FROM_REST = Pattern.compile(
      IDENT + "( (?!(WHERE|ORDER|FOR|FETCH|OFFSET|LIMIT)\\b)(AS )?" + IDENT + ")?"
      + "( (WHERE|ORDER BY|FOR UPDATE|FETCH|OFFSET|LIMIT)\\b.*)?");

  /** ANSI 표기(TIMESTAMP · DATE 글자)라 Oracle · MySQL · PostgreSQL 에서 같이 쓴다. */
  static String literal(Object v) {
    if (v == null) return "NULL";
    if (v instanceof Number) return text(v);
    if (v instanceof Timestamp t) return "TIMESTAMP '" + t + "'";
    if (v instanceof java.sql.Date d) return "DATE '" + d + "'";
    return "'" + text(v).replace("'", "''") + "'";
  }

  static String text(Object v) {
    return v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v);
  }

  /** 행마다 INSERT 문 하나. 칼럼 · 값을 한 줄에 하나씩 두고 값 뒤에 칼럼 이름 주석(같은 열)을 단다. 문장 사이는 빈 줄. */
  static String inserts(String table, List<String> columns, List<List<Object>> rows) {
    List<String> out = new ArrayList<>();
    for (List<Object> row : rows) {
      StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (\n");
      for (int i = 0; i < columns.size(); i++) sb.append(i == 0 ? FIRST : NEXT).append(columns.get(i)).append('\n');
      sb.append(") VALUES (\n");
      List<String> values = new ArrayList<>();
      int width = 0;
      for (Object v : row) {
        values.add(literal(v));
        width = Math.max(width, values.get(values.size() - 1).length());
      }
      for (int i = 0; i < values.size(); i++) {
        String v = values.get(i);
        sb.append(i == 0 ? FIRST : NEXT).append(v).append(" ".repeat(width - v.length() + COMMENT_GAP))
          .append("-- ").append(columns.get(i)).append('\n');
      }
      out.add(sb.append(");\n").toString());
    }
    return String.join("\n", out);
  }

  /**
   * 행마다 UPDATE 문 하나. SET 은 PK 가 아닌 칼럼, WHERE 는 PK 칼럼(null 이면 IS NULL).
   * pkUpper(대문자 칼럼 이름)가 결과 칼럼과 하나도 겹치지 않으면 첫 칼럼으로 조건을 걸고 위에 확인하라는 주석을 단다.
   */
  static String updates(String table, List<String> columns, List<List<Object>> rows, Set<String> pkUpper) {
    boolean[] key = new boolean[columns.size()];
    boolean anyKey = false;
    for (int i = 0; i < columns.size(); i++) {
      key[i] = pkUpper.contains(columns.get(i).toUpperCase(Locale.ROOT));
      anyKey |= key[i];
    }
    StringBuilder sb = new StringBuilder();
    if (!anyKey) {
      if (!columns.isEmpty()) key[0] = true;
      sb.append("-- PK 를 찾지 못해 첫 칼럼(").append(columns.isEmpty() ? "" : columns.get(0)).append(")으로 조건을 걸었습니다. 실행 전에 WHERE 를 확인하세요.\n");
    }
    List<String> out = new ArrayList<>();
    for (List<Object> row : rows) {
      String one = update(table, columns, row, key);
      if (one != null) out.add(one); // PK 만 고른 결과는 바꿀 칼럼이 없다.
    }
    return out.isEmpty() ? "" : sb.append(String.join("\n", out)).toString();
  }

  /** UPDATE 문 하나(값은 리터럴). key[i] 칼럼은 WHERE, 나머지는 SET. SET 할 칼럼이 없으면 null. */
  static String update(String table, List<String> columns, List<Object> row, boolean[] key) {
    return build(table, columns, row, key, null);
  }

  /** 같은 UPDATE 의 두 형태: preview 는 리터럴(보여 주기용), sql 은 ? 자리 + binds(실행용). null 값은 두 형태 모두 NULL · IS NULL 글자. */
  record Update(String preview, String sql, List<Object> binds) {}

  /** SET 할 칼럼이 없으면 null. */
  static Update updateWithBinds(String table, List<String> columns, List<Object> row, boolean[] key) {
    String preview = build(table, columns, row, key, null);
    if (preview == null) return null;
    List<Object> binds = new ArrayList<>();
    return new Update(preview, build(table, columns, row, key, binds), binds);
  }

  /** binds 가 null 이면 값을 리터럴로, 아니면 ? 로 쓰고 값을 binds 에 순서대로 더한다. */
  private static String build(String table, List<String> columns, List<Object> row, boolean[] key, List<Object> binds) {
    StringBuilder set = new StringBuilder();
    StringBuilder where = new StringBuilder();
    List<Object> setValues = new ArrayList<>();
    List<Object> whereValues = new ArrayList<>();
    for (int i = 0; i < columns.size(); i++) {
      Object v = row.get(i);
      if (key[i]) {
        where.append("   AND ").append(columns.get(i)).append(v == null ? " IS NULL" : " = " + value(v, binds, whereValues)).append('\n');
      } else {
        set.append(set.isEmpty() ? "   SET " : NEXT).append(columns.get(i)).append(" = ").append(value(v, binds, setValues)).append('\n');
      }
    }
    if (set.isEmpty()) return null;
    if (binds != null) {
      binds.addAll(setValues);
      binds.addAll(whereValues);
    }
    // 마지막 줄바꿈 자리에 ; 를 붙인다.
    where.setLength(where.length() - 1);
    return "UPDATE " + table + "\n" + set + " WHERE 1 = 1\n" + where + ";\n";
  }

  private static String value(Object v, List<Object> binds, List<Object> collected) {
    if (binds == null || v == null) return literal(v);
    collected.add(v);
    return "?";
  }

  /** NUMBER · INTEGER · DECIMAL · FLOAT 같은 숫자 형식 이름인지(JDBC 형식 이름 기준). */
  static boolean isNumericType(String type) {
    return type != null && NUMERIC_TYPE.matcher(type.strip()).matches();
  }

  /** LOB · 이진 형식인지. 결과 창에는 잘린 글 · 크기만 보이므로 이 칼럼은 고치지 않는다. */
  static boolean isUneditableType(String type) {
    return type != null && UNEDITABLE_TYPE.matcher(type.strip()).matches();
  }

  /**
   * 결과 칸에 고쳐 쓴 글을 칼럼 형식에 맞는 값으로. 빈 칸(공백만 있어도)은 null, 숫자 형식은 BigDecimal,
   * DATE · TIMESTAMP 형식은 「yyyy-mm-dd hh:mm:ss」 이면 Timestamp, 「yyyy-mm-dd」 이면 Date, 나머지는 글자 그대로.
   * 형식에 맞지 않으면 IllegalArgumentException(한국어 문구).
   */
  static Object parseEdit(String text, String type) {
    if (text == null || text.isBlank()) return null;
    String t = text.strip();
    if (isNumericType(type)) {
      try {
        return new BigDecimal(t.replace(",", ""));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("숫자가 아닙니다: " + text);
      }
    }
    String upper = type == null ? "" : type.toUpperCase(Locale.ROOT);
    if (upper.contains("DATE") || upper.contains("TIMESTAMP")) {
      try {
        return Timestamp.valueOf(t);
      } catch (IllegalArgumentException notTimestamp) {
        try {
          return java.sql.Date.valueOf(t);
        } catch (IllegalArgumentException notDate) {
          throw new IllegalArgumentException("날짜는 yyyy-mm-dd 또는 yyyy-mm-dd hh:mm:ss 로 써 주세요: " + text);
        }
      }
    }
    return text;
  }

  /**
   * 조회 문장이 결과 칸 고치기에 맞지 않는 이유. 괜찮으면 null. 주석 · 문자열은 지우고 본다.
   * 허용: SELECT 하나 · FROM 표 하나(별칭 가능, 스키마 없이)이고 SELECT 목록이 * 이거나 별칭 없는 칼럼 이름(표.칼럼 포함)들.
   * 뒤에는 WHERE · ORDER BY · FOR UPDATE · FETCH · OFFSET · LIMIT 만. 집합 연산 · 하위 조회 · 묶기 · 계층 조회는 막는다.
   */
  static String notEditable(String sql) {
    String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ").replaceAll("'([^']|'')*'", "''")
        .replaceAll("\\s+", " ").strip().toUpperCase(Locale.ROOT);
    if (s.endsWith(";")) s = s.substring(0, s.length() - 1).strip();
    if (SET_OPERATION.matcher(s).find()) return "UNION · INTERSECT · MINUS · EXCEPT 로 합친 결과는 고칠 수 없습니다.";
    if (SELECT.matcher(s).results().count() != 1) return "하위 조회가 있는 결과는 고칠 수 없습니다.";
    if (GROUPED.matcher(s).find()) return "묶거나(GROUP BY · DISTINCT) 계층 조회(CONNECT BY)한 결과는 고칠 수 없습니다.";
    Matcher m = SELECT_FROM.matcher(s);
    if (!m.matches()) return "표 하나를 조회한 SELECT 결과만 고칠 수 있습니다.";
    if (!SELECT_LIST.matcher(m.group(1)).matches()) return "SELECT 목록이 * 이거나 별칭 없는 칼럼 이름일 때만 고칠 수 있습니다.";
    if (SCHEMA_TABLE.matcher(m.group(2)).lookingAt()) return "스키마를 붙인 표는 고칠 수 없습니다(지금 접속 스키마의 표만).";
    if (!FROM_REST.matcher(m.group(2)).matches()) return "여러 표를 조인한 결과는 고칠 수 없습니다(표 하나만).";
    return null;
  }
}
