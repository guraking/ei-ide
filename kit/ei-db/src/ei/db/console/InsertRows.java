package ei.db.console;

import ei.db.DbKind;
import ei.db.gen.Names;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「엑셀 붙여 넣어 행 넣기」 · 「테스트 데이터 만들기」가 함께 쓰는 INSERT 묶음: 값마다 SQL 글(콘솔 · 파일)과 바인드 값(바로 넣기)을 같이 든다.
 * 문장 모양은 SqlText.inserts 와 같다(칼럼 · 값 한 줄에 하나, 쉼표는 줄 앞, 값 뒤에 칼럼 이름 주석).
 * 표 · 칼럼 이름은 Names.sql 표기. IntelliJ 클래스를 쓰지 않는다(PasteRowsCheck · TestDataCheck 로 점검).
 */
public final class InsertRows {
  private InsertRows() {}

  /** 콘솔에 넣는 행 수 한도. 넘으면 파일로 저장하게 한다. */
  public static final int CONSOLE_MAX_ROWS = 1000;

  private static final String FIRST = "       ";
  private static final String NEXT = "     , ";
  private static final int COMMENT_GAP = 2;
  private static final String NULL_SQL = "NULL";
  private static final String BIND = "?";
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final String ORACLE_DATE_FORMAT = "YYYY-MM-DD HH24:MI:SS";
  /** 시각 없이 날짜만 담는 형식 이름(PostgreSQL · MySQL). Oracle 의 DATE 는 시각도 담는다. */
  private static final String DATE_ONLY_TYPE = "DATE";

  /** 칼럼 형식의 갈래. LOB 은 LOB · 이진, OTHER 는 논리 · UUID · JSON · 시각만 등 검증하지 않고 글로 넘기는 형식. */
  public enum Type { NUMBER, DATE, TEXT, LOB, OTHER }

  /** 값 하나. sql 은 문장에 쓸 글, bind 는 ? 에 넣을 값. raw 면 시퀀스 같은 식이라 바인드하지 않고 sql 을 문장에 그대로 쓴다. */
  public record Val(String sql, Object bind, boolean raw) {}

  /** table 은 SQL 표기(스키마.표), columns 는 메타에서 읽은 칼럼 이름, rows 의 행마다 값 수는 칼럼 수와 같다. */
  public record Batch(DbKind kind, String table, List<String> columns, List<List<Val>> rows) {}

  public static final Val NULL = new Val(NULL_SQL, null, false);

  public static Type typeOf(String dbType) {
    String t = dbType == null ? "" : dbType.strip().toUpperCase(Locale.ROOT);
    if (SqlText.isUneditableType(t)) return Type.LOB;
    if (SqlText.isNumericType(t)) return Type.NUMBER;
    if (t.startsWith("DATE") || t.startsWith("TIMESTAMP")) return Type.DATE;
    if (t.contains("CHAR") || t.contains("TEXT")) return Type.TEXT;
    return Type.OTHER;
  }

  /** 글 값. 작은따옴표는 SqlText.literal 이 이스케이프한다. */
  public static Val text(String s, DbKind kind) {
    return new Val(SqlText.literal(s, kind), s, false);
  }

  public static Val number(BigDecimal n) {
    return new Val(n.toPlainString(), n, false);
  }

  /**
   * 날짜 · 시각 값. Oracle 은 TO_DATE('…','YYYY-MM-DD HH24:MI:SS'), 그 밖은 형식이 DATE 면 DATE '…'(시각은 버린다), 아니면 TIMESTAMP '…'.
   */
  public static Val date(DbKind kind, String dbType, LocalDateTime t) {
    if (kind == DbKind.ORACLE) {
      return new Val("TO_DATE('" + STAMP.format(t) + "','" + ORACLE_DATE_FORMAT + "')", Timestamp.valueOf(t), false);
    }
    if (dbType != null && DATE_ONLY_TYPE.equalsIgnoreCase(dbType.strip())) {
      return new Val("DATE '" + DAY.format(t) + "'", java.sql.Date.valueOf(t.toLocalDate()), false);
    }
    return new Val("TIMESTAMP '" + STAMP.format(t) + "'", Timestamp.valueOf(t), false);
  }

  /** 바인드하지 않고 문장에 그대로 쓰는 식(시퀀스). 부르는 쪽이 이름을 검증한다. */
  public static Val raw(String expression) {
    return new Val(expression, null, true);
  }

  /** 행마다 INSERT 문 하나, 문장 사이는 빈 줄. 행이 없으면 빈 글. */
  public static String text(Batch b) {
    List<String> out = new ArrayList<>(b.rows().size());
    for (int r = 0; r < b.rows().size(); r++) out.add(statement(b, r));
    return String.join("\n", out);
  }

  /** row 번째 행의 INSERT 문(끝에 「;」 와 줄바꿈). */
  public static String statement(Batch b, int row) {
    StringBuilder sb = new StringBuilder("INSERT INTO ").append(b.table()).append(" (\n");
    for (int k = 0; k < b.columns().size(); k++) sb.append(k == 0 ? FIRST : NEXT).append(Names.sql(b.kind(), b.columns().get(k))).append('\n');
    sb.append(") VALUES (\n");
    List<Val> values = b.rows().get(row);
    int width = 0;
    for (Val v : values) width = Math.max(width, v.sql().length());
    for (int k = 0; k < values.size(); k++) {
      String v = values.get(k).sql();
      sb.append(k == 0 ? FIRST : NEXT).append(v).append(" ".repeat(width - v.length() + COMMENT_GAP))
        .append("-- ").append(b.columns().get(k)).append('\n');
    }
    return sb.append(");\n").toString();
  }

  /**
   * 바로 넣기에 쓰는 문장: 값 자리는 ?, 식(raw) 칼럼은 그 식 그대로. 식 칼럼은 첫 행으로 정한다.
   * 어느 행이 그 칼럼에서 식 여부나 식 글이 첫 행과 다르면 IllegalArgumentException.
   */
  public static String template(Batch b) {
    if (b.rows().isEmpty()) throw new IllegalArgumentException("넣을 행이 없습니다.");
    List<Val> first = b.rows().get(0);
    for (List<Val> row : b.rows()) {
      for (int k = 0; k < first.size(); k++) {
        Val a = first.get(k);
        Val v = row.get(k);
        if (a.raw() != v.raw() || a.raw() && !a.sql().equals(v.sql())) {
          throw new IllegalArgumentException("칼럼 " + b.columns().get(k) + " 의 식이 행마다 달라 한 문장으로 넣을 수 없습니다.");
        }
      }
    }
    List<String> names = new ArrayList<>();
    List<String> values = new ArrayList<>();
    for (int k = 0; k < first.size(); k++) {
      names.add(Names.sql(b.kind(), b.columns().get(k)));
      values.add(first.get(k).raw() ? first.get(k).sql() : BIND);
    }
    return "INSERT INTO " + b.table() + " (" + String.join(", ", names) + ") VALUES (" + String.join(", ", values) + ")";
  }

  /** template 의 ? 순서대로 행마다 바인드 값(식 칼럼은 뺀다). */
  public static List<List<Object>> binds(Batch b) {
    List<List<Object>> out = new ArrayList<>(b.rows().size());
    for (List<Val> row : b.rows()) {
      List<Object> one = new ArrayList<>(row.size());
      for (Val v : row) {
        if (!v.raw()) one.add(v.bind());
      }
      out.add(one);
    }
    return out;
  }
}
