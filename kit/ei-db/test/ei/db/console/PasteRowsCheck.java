package ei.db.console;

import ei.db.DbKind;
import ei.db.Meta;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 엑셀 붙여 넣기 읽기 · 칼럼 맞추기 · 값 바꾸기 · INSERT 만들기 자체 점검: java -cp out:test-out ei.db.console.PasteRowsCheck */
public class PasteRowsCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static void yes(boolean b, String what) { if (!b) throw new AssertionError(what); }

  static Meta.Column col(String name, String type, int size, int scale, boolean nullable, String comment) {
    return new Meta.Column(name, type, size, scale, false, false, nullable, comment);
  }

  static final List<Meta.Column> COLUMNS = List.of(
      col("ORDER_ID", "NUMBER", 6, 0, false, "주문번호"),
      col("CUST_NM", "VARCHAR2", 10, 0, true, "고객 이름"),
      col("AMOUNT", "NUMBER", 12, 2, true, "금액"),
      col("ORDER_DT", "DATE", 7, 0, true, "주문일"),
      col("MEMO", "CLOB", 0, 0, true, ""));

  static PasteRows.Problem only(PasteRows.Result r) {
    eq(r.problems().size(), 1);
    return r.problems().get(0);
  }

  public static void main(String[] args) {
    // 읽기: CRLF · 끝의 빈 줄 · 가운데 빈 줄 · 따옴표 칸(줄바꿈 · 탭 · "")
    eq(PasteRows.parse("a\tb\r\nc\td\r\n"), List.of(List.of("a", "b"), List.of("c", "d")));
    eq(PasteRows.parse("a\tb\n\n\t\nc\td\n\n"), List.of(List.of("a", "b"), List.of("c", "d")));
    eq(PasteRows.parse("1\t\"줄1\n줄2\t탭 \"\"따옴\"\"\"\tx\r\n2\t\"\"\ty"),
       List.of(List.of("1", "줄1\n줄2\t탭 \"따옴\"", "x"), List.of("2", "", "y")));
    eq(PasteRows.parse("\"닫지 않음\tb"), List.of(List.of("\"닫지 않음", "b")));
    eq(PasteRows.parse("\"abc\" def\tb"), List.of(List.of("\"abc\" def", "b")));
    eq(PasteRows.parse("a\t\r\n"), List.of(List.of("a", "")));
    eq(PasteRows.parse(""), List.of());
    eq(PasteRows.parse("a\rb"), List.of(List.of("a"), List.of("b")));

    // 머리 맞추기: 이름(대소문자 무시) · 한글 주석 · 모르는 머리
    eq(List.of(0, 1, PasteRows.SKIP, 3), box(PasteRows.match(List.of("order_id", " 고객 이름 ", "비고", "주문일"), COLUMNS)));
    eq(List.of(0, PasteRows.SKIP), box(PasteRows.match(List.of("ORDER_ID", "주문번호"), COLUMNS)));
    yes(PasteRows.looksLikeHeader(List.of("주문번호", "고객 이름", "비고"), COLUMNS), "한글 머리");
    yes(!PasteRows.looksLikeHeader(List.of("1001", "홍길동", "3,000"), COLUMNS), "데이터 줄은 머리가 아님");
    eq(List.of(0, 1, 2, 3, 4, PasteRows.SKIP), box(PasteRows.byOrder(6, COLUMNS.size())));

    // 값 바꾸기: 숫자 쉼표 · 날짜 꼴 · 작은따옴표 · 빈 칸 · 공백 다듬기
    List<List<String>> rows = PasteRows.parse("주문번호\t고객 이름\t금액\t주문일\r\n1,001\t O'Neil \t1,234,567.5\t2024/03/05 14:30\r\n1002\t\t\t20240306\r\n");
    int[] map = PasteRows.match(rows.get(0), COLUMNS);
    PasteRows.Result ok = PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), map, rows.get(0), rows.subList(1, rows.size()), true);
    eq(ok.problems(), List.of());
    eq(ok.columns(), List.of("ORDER_ID", "CUST_NM", "AMOUNT", "ORDER_DT"));
    InsertRows.Batch batch = new InsertRows.Batch(DbKind.ORACLE, "APP.ORDERS", ok.columns(), ok.rows());
    eq(InsertRows.text(batch),
       "INSERT INTO APP.ORDERS (\n"
       + "       ORDER_ID\n"
       + "     , CUST_NM\n"
       + "     , AMOUNT\n"
       + "     , ORDER_DT\n"
       + ") VALUES (\n"
       + "       " + pad("1001") + "-- ORDER_ID\n"
       + "     , " + pad("'O''Neil'") + "-- CUST_NM\n"
       + "     , " + pad("1234567.5") + "-- AMOUNT\n"
       + "     , " + pad("TO_DATE('2024-03-05 14:30:00','YYYY-MM-DD HH24:MI:SS')") + "-- ORDER_DT\n"
       + ");\n"
       + "\n"
       + "INSERT INTO APP.ORDERS (\n"
       + "       ORDER_ID\n"
       + "     , CUST_NM\n"
       + "     , AMOUNT\n"
       + "     , ORDER_DT\n"
       + ") VALUES (\n"
       + "       " + pad("1002") + "-- ORDER_ID\n"
       + "     , " + pad("NULL") + "-- CUST_NM\n"
       + "     , " + pad("NULL") + "-- AMOUNT\n"
       + "     , " + pad("TO_DATE('2024-03-06 00:00:00','YYYY-MM-DD HH24:MI:SS')") + "-- ORDER_DT\n"
       + ");\n");
    eq(InsertRows.template(batch), "INSERT INTO APP.ORDERS (ORDER_ID, CUST_NM, AMOUNT, ORDER_DT) VALUES (?, ?, ?, ?)");
    eq(InsertRows.binds(batch).get(0), java.util.Arrays.asList(new BigDecimal("1001"), "O'Neil", new BigDecimal("1234567.5"),
        Timestamp.valueOf(LocalDateTime.of(2024, 3, 5, 14, 30, 0))));
    eq(InsertRows.binds(batch).get(1).get(1), null);
    // 공백 다듬기를 끄면 글 칼럼의 공백이 남는다(숫자 · 날짜는 늘 뗀다).
    PasteRows.Result raw = PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), map, rows.get(0), rows.subList(1, 2), false);
    eq(raw.rows().get(0).get(1).sql(), "' O''Neil '");
    eq(raw.rows().get(0).get(0).sql(), "1001");

    // 날짜 꼴과 DB 별 리터럴
    eq(PasteRows.parseDate("2024-01-02"), LocalDateTime.of(2024, 1, 2, 0, 0));
    eq(PasteRows.parseDate("2024.1.2 03:04:05"), LocalDateTime.of(2024, 1, 2, 3, 4, 5));
    eq(PasteRows.parseDate("2024. 1. 2."), LocalDateTime.of(2024, 1, 2, 0, 0));
    eq(PasteRows.parseDate("20240102 23:59"), LocalDateTime.of(2024, 1, 2, 23, 59));
    eq(PasteRows.parseDate("2024-01-02 03:04:05.0"), LocalDateTime.of(2024, 1, 2, 3, 4, 5));
    eq(PasteRows.parseDate("2024-13-02"), null);
    eq(PasteRows.parseDate("2024-01/02"), null);
    eq(PasteRows.parseDate("01/02/2024"), null);
    eq(PasteRows.parseDate("2024-01-02 03:04:05.123"), null);
    eq(PasteRows.parseDate("45293"), null);
    Meta.Column pgDate = col("d", "date", 13, 0, true, "");
    Meta.Column pgStamp = col("ts", "timestamp", 29, 6, true, "");
    eq(PasteRows.value(DbKind.POSTGRES, pgDate, "2024/03/05 14:30", true).value().sql(), "DATE '2024-03-05'");
    eq(PasteRows.value(DbKind.POSTGRES, pgStamp, "2024/03/05 14:30", true).value().sql(), "TIMESTAMP '2024-03-05 14:30:00'");
    eq(PasteRows.value(DbKind.MYSQL, col("s", "VARCHAR", 20, 0, true, ""), "a\\b'c", true).value().sql(), "'a\\\\b''c'");

    // 문제: 숫자 칼럼에 글 · 쉼표 자리 틀림 · 자릿수 · 날짜 · 길이 · NOT NULL · 칸 수
    int[] all = PasteRows.byOrder(4, COLUMNS.size());
    PasteRows.Problem p = only(PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), all, null, List.of(List.of("abc", "a", "1", "2024-01-01")), true));
    yes(p.blocking() && p.row() == 0 && p.cell() == 0 && p.column().equals("ORDER_ID") && p.reason().startsWith(PasteRows.NOT_NUMBER), "숫자 칼럼에 글");
    yes(only(convert("1", "a", "1,5", "")).reason().startsWith(PasteRows.NOT_NUMBER), "쉼표 자리가 틀린 숫자");
    yes(only(convert("1234567", "a", "", "")).reason().startsWith(PasteRows.TOO_MANY_DIGITS), "정수 자릿수");
    eq(convert("1", "a", "1234567890.129", "").problems(), List.of());
    yes(only(convert("1", "a", "12345678901", "")).reason().startsWith(PasteRows.TOO_MANY_DIGITS), "소수 칼럼의 정수 자릿수");
    eq(PasteRows.value(DbKind.ORACLE, col("RATE", "NUMBER", 2, 2, true, ""), "0", true).reason(), null);
    yes(only(convert("1", "a", "", "3월 5일")).reason().startsWith(PasteRows.BAD_DATE), "날짜");
    p = only(convert("1", "12345678901", "", ""));
    yes(p.blocking() && p.reason().startsWith(PasteRows.TOO_LONG), "길이 초과");
    p = only(convert("1", "가나다라", "", ""));
    yes(!p.blocking() && p.reason().startsWith(PasteRows.MAYBE_TOO_LONG), "Oracle 바이트 기준 주의");
    eq(PasteRows.convert(DbKind.POSTGRES, COLUMNS, Set.of(), all, null, List.of(List.of("1", "가나다라", "", "")), true).problems(), List.of());
    p = only(convert("", "a", "", ""));
    yes(p.blocking() && p.reason().equals(PasteRows.EMPTY_NOT_NULL), "NOT NULL 인데 빔");
    p = only(PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), all, null, List.of(List.of("1", "a")), true));
    yes(p.blocking() && p.cell() == PasteRows.WHOLE && p.reason().startsWith(PasteRows.WIDTH_DIFFERS), "칸 수 다름");

    // 맞추기 문제: 모르는 머리(주의) · 같은 칼럼 두 번(막음) · 빠진 NOT NULL(주의, 기본값 있으면 없음) · 남는 칸(주의)
    List<String> head = List.of("고객 이름", "비고");
    PasteRows.Result r = PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), PasteRows.match(head, COLUMNS), head, List.of(List.of("a", "b")), true);
    eq(r.columns(), List.of("CUST_NM"));
    eq(r.problems().size(), 2);
    yes(!r.blocked() && r.problems().get(0).reason().equals(PasteRows.NO_COLUMN) && r.problems().get(1).reason().equals(PasteRows.MISSING_NOT_NULL), "주의 둘");
    r = PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of("ORDER_ID"), PasteRows.match(head, COLUMNS), head, List.of(List.of("a", "b")), true);
    eq(r.problems().size(), 1);
    r = PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), new int[]{0, 0}, null, List.of(List.of("1", "2")), true);
    yes(r.blocked() && r.problems().get(0).reason().equals(PasteRows.SAME_COLUMN), "같은 칼럼 두 번");
    r = PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), PasteRows.byOrder(6, COLUMNS.size()), null, List.of(List.of("1", "a", "", "", "m", "x")), true);
    yes(!r.blocked() && only(r).reason().equals(PasteRows.EXTRA_CELLS), "남는 칸");
    r = PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), new int[]{PasteRows.SKIP}, null, List.of(List.of("1")), true);
    yes(r.blocked(), "넣을 칼럼 없음");

    // 식(시퀀스) 칼럼은 바인드하지 않는다. 행마다 다르면 한 문장으로 못 넣는다.
    InsertRows.Batch seq = new InsertRows.Batch(DbKind.POSTGRES, "PUBLIC.T", List.of("id", "Name"),
        List.of(List.of(InsertRows.raw("nextval('s')"), InsertRows.text("a", DbKind.POSTGRES)),
                List.of(InsertRows.raw("nextval('s')"), InsertRows.NULL)));
    eq(InsertRows.template(seq), "INSERT INTO PUBLIC.T (ID, \"Name\") VALUES (nextval('s'), ?)");
    eq(InsertRows.binds(seq), List.of(List.of("a"), java.util.Collections.singletonList(null)));
    try {
      InsertRows.template(new InsertRows.Batch(DbKind.POSTGRES, "T", List.of("id"),
          List.of(List.of(InsertRows.raw("nextval('s')")), List.of(InsertRows.NULL))));
      throw new AssertionError("식이 행마다 다르면 실패해야 한다");
    } catch (IllegalArgumentException expected) {
      // 기대한 실패
    }
    eq(InsertRows.typeOf("VARCHAR2"), InsertRows.Type.TEXT);
    eq(InsertRows.typeOf("timestamptz"), InsertRows.Type.DATE);
    eq(InsertRows.typeOf("TIMESTAMP(6)"), InsertRows.Type.DATE);
    eq(InsertRows.typeOf("int4"), InsertRows.Type.NUMBER);
    eq(InsertRows.typeOf("BLOB"), InsertRows.Type.LOB);
    eq(InsertRows.typeOf("bool"), InsertRows.Type.OTHER);

    System.out.println("PasteRowsCheck 통과");
  }

  static PasteRows.Result convert(String... cells) {
    return PasteRows.convert(DbKind.ORACLE, COLUMNS, Set.of(), PasteRows.byOrder(cells.length, COLUMNS.size()), null, List.of(List.of(cells)), true);
  }

  /** 값 뒤를 가장 긴 값(TO_DATE 식) 폭 + 2칸까지 공백으로 채운다. */
  static String pad(String value) {
    int width = "TO_DATE('2024-03-05 14:30:00','YYYY-MM-DD HH24:MI:SS')".length();
    return value + " ".repeat(width - value.length() + 2);
  }

  static List<Integer> box(int[] a) {
    List<Integer> out = new java.util.ArrayList<>();
    for (int v : a) out.add(v);
    return out;
  }
}
