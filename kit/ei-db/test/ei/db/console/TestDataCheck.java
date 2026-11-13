package ei.db.console;

import ei.db.DbKind;
import ei.db.Meta;
import ei.db.console.TestData.Method;
import ei.db.console.TestData.Plan;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 테스트 데이터 만들기 자체 점검: java -cp out:test-out ei.db.console.TestDataCheck */
public class TestDataCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static void yes(boolean b, String what) { if (!b) throw new AssertionError(what); }

  static Meta.Column col(String name, String type, int size, int scale, boolean pk, boolean nullable) {
    return new Meta.Column(name, type, size, scale, pk, false, nullable, "");
  }

  static final LocalDate TODAY = LocalDate.of(2024, 6, 30);
  static final Meta.Column ID = col("ORDER_ID", "NUMBER", 6, 0, true, false);
  static final Meta.Column NAME = col("CUST_NM", "VARCHAR2", 9, 0, false, false);
  static final Meta.Column CODE = col("USE_YN", "CHAR", 1, 0, false, false);
  static final Meta.Column AMOUNT = col("AMOUNT", "NUMBER", 5, 2, false, true);
  static final Meta.Column DAY = col("ORDER_DT", "DATE", 7, 0, false, true);
  static final Meta.Column NOTE = col("NOTE", "CLOB", 0, 0, false, true);
  static final Meta.Column REG = col("REG_DT", "DATE", 7, 0, false, false);
  static final List<Meta.Column> COLUMNS = List.of(ID, NAME, CODE, AMOUNT, DAY, NOTE, REG);
  static final Set<String> DEFAULTS = Set.of("REG_DT");

  static List<Plan> defaults() {
    List<Plan> out = new java.util.ArrayList<>();
    for (Meta.Column c : COLUMNS) out.add(TestData.defaultPlan(c, DEFAULTS.contains(c.name()), TODAY));
    return out;
  }

  static TestData.Output one(DbKind kind, Meta.Column c, Method m, String arg, int rows) {
    return TestData.generate(kind, List.of(new Plan(c, m, arg)), Set.of(), rows, TestData.DEFAULT_SEED);
  }

  public static void main(String[] args) {
    // 기본 방식: PK 순번 · 글 칼럼이름_순번 · 1글자 목록 · 숫자 난수 · 날짜 최근 1년 · LOB 넣지 않음 · 기본값 쓰기
    List<Plan> plans = defaults();
    eq(plans.stream().map(Plan::method).toList(),
       List.of(Method.SEQUENCE, Method.NAME_SEQ, Method.LIST, Method.RANDOM_NUMBER, Method.RANDOM_DATE, Method.SKIP, Method.DEFAULT));
    eq(plans.get(2).arg(), "Y,N");
    eq(plans.get(3).arg(), "1~999");
    eq(plans.get(4).arg(), "2023-06-30~2024-06-30");
    yes(!TestData.methods(ID, false).contains(Method.NULL), "NOT NULL 은 NULL 방식을 못 고름");
    yes(TestData.methods(AMOUNT, false).contains(Method.NULL), "NULL 허용은 NULL 방식을 고름");
    yes(!TestData.methods(ID, false).contains(Method.DEFAULT) && TestData.methods(REG, true).contains(Method.DEFAULT), "기본값 쓰기");
    eq(TestData.methods(NOTE, false), List.of(Method.NULL, Method.SKIP));

    // 줄 수 · 칼럼(넣지 않음 · 기본값 칼럼은 빠짐) · 씨앗 재현 · 미리보기는 앞쪽 행
    TestData.Output a = TestData.generate(DbKind.ORACLE, plans, DEFAULTS, 300, 7L);
    eq(a.errors(), List.of());
    eq(a.warnings(), List.of());
    eq(a.rows().size(), 300);
    eq(a.columns(), List.of("ORDER_ID", "CUST_NM", "USE_YN", "AMOUNT", "ORDER_DT"));
    eq(a, TestData.generate(DbKind.ORACLE, plans, DEFAULTS, 300, 7L));
    yes(!a.rows().equals(TestData.generate(DbKind.ORACLE, plans, DEFAULTS, 300, 8L).rows()), "씨앗이 다르면 결과가 다름");
    eq(TestData.preview(DbKind.ORACLE, plans, DEFAULTS, 300, 7L).rows(), a.rows().subList(0, TestData.PREVIEW_ROWS));

    // 길이 · 자릿수 · 유일 · NOT NULL · 날짜 범위
    Set<Object> ids = new HashSet<>();
    Set<Object> names = new HashSet<>();
    Timestamp from = Timestamp.valueOf("2023-06-30 00:00:00");
    Timestamp to = Timestamp.valueOf("2024-06-30 23:59:59");
    for (List<InsertRows.Val> row : a.rows()) {
      ids.add(row.get(0).bind());
      String name = (String) row.get(1).bind();
      names.add(name);
      yes(name.length() <= 9 && name.startsWith("CUST"), "글 길이: " + name);
      yes(row.get(2).bind().equals("Y") || row.get(2).bind().equals("N"), "코드");
      BigDecimal amount = (BigDecimal) row.get(3).bind();
      yes(amount.scale() <= 2 && amount.compareTo(BigDecimal.ONE) >= 0 && amount.compareTo(new BigDecimal("999")) <= 0, "숫자 범위: " + amount);
      Timestamp day = (Timestamp) row.get(4).bind();
      yes(!day.before(from) && !day.after(to), "날짜 범위: " + day);
      yes(row.get(4).sql().startsWith("TO_DATE('") && row.get(4).sql().endsWith("','YYYY-MM-DD HH24:MI:SS')"), "Oracle 날짜 리터럴");
      for (int i = 0; i < 3; i++) yes(row.get(i).bind() != null, "NOT NULL");
    }
    eq(ids.size(), 300);
    eq(names.size(), 300);
    eq(a.rows().get(0).get(0).sql(), "1");
    eq(a.rows().get(299).get(1).sql(), "'CUST__300'");
    eq(TestData.nameSeq("CUST_NM", 7, 9), "CUST_NM_7");
    eq(TestData.nameSeq("CUST_NM", 1234, 5), "1234");
    eq(TestData.nameSeq("CUST_NM", 12, 4), "C_12");

    // 날짜 리터럴 DB 별
    Meta.Column pgDay = col("d", "date", 13, 0, false, true);
    Meta.Column pgStamp = col("ts", "timestamptz", 35, 6, false, true);
    String range = "2024-01-01~2024-01-01";
    yes(one(DbKind.POSTGRES, pgDay, Method.RANDOM_DATE, range, 3).rows().get(0).get(0).sql().equals("DATE '2024-01-01'"), "PG DATE");
    yes(one(DbKind.POSTGRES, pgStamp, Method.RANDOM_DATE, range, 3).rows().get(0).get(0).sql().startsWith("TIMESTAMP '2024-01-01 "), "PG TIMESTAMP");
    yes(one(DbKind.ORACLE, DAY, Method.FIXED, "2024/02/03", 1).rows().get(0).get(0).sql().equals("TO_DATE('2024-02-03 00:00:00','YYYY-MM-DD HH24:MI:SS')"), "고정 날짜");

    // 시퀀스: DB 별 식, 이름 검증, 바로 넣기 문장에는 식 그대로
    eq(one(DbKind.ORACLE, ID, Method.DB_SEQUENCE, "APP.SEQ_ORDER", 2).rows().get(1).get(0).sql(), "APP.SEQ_ORDER.NEXTVAL");
    TestData.Output pgSeq = one(DbKind.POSTGRES, col("order_id", "int8", 19, 0, true, false), Method.DB_SEQUENCE, "seq_order", 2);
    eq(pgSeq.rows().get(0).get(0).sql(), "nextval('seq_order')");
    eq(InsertRows.template(new InsertRows.Batch(DbKind.POSTGRES, "PUBLIC.T", pgSeq.columns(), pgSeq.rows())),
       "INSERT INTO PUBLIC.T (ORDER_ID) VALUES (nextval('seq_order'))");
    yes(!one(DbKind.ORACLE, ID, Method.DB_SEQUENCE, "x'); DROP TABLE t; --", 2).errors().isEmpty(), "시퀀스 이름 검증");
    yes(!one(DbKind.MYSQL, ID, Method.DB_SEQUENCE, "s", 2).errors().isEmpty(), "MySQL 은 시퀀스 없음");

    // 설정 오류: 순번이 자릿수를 넘음 · 글 순번 길이 · NOT NULL 에 NULL · 범위 · 목록 값 형식 · 줄 수 한도
    yes(!one(DbKind.ORACLE, ID, Method.SEQUENCE, "999990", 100).errors().isEmpty(), "순번 자릿수 초과");
    eq(one(DbKind.ORACLE, ID, Method.SEQUENCE, "999900", 100).errors(), List.of());
    yes(!one(DbKind.ORACLE, CODE, Method.NAME_SEQ, "", 10).errors().isEmpty(), "순번이 칼럼 크기보다 김");
    yes(!one(DbKind.ORACLE, ID, Method.NULL, "", 1).errors().isEmpty(), "NOT NULL 에 NULL");
    yes(!one(DbKind.ORACLE, AMOUNT, Method.RANDOM_NUMBER, "1~10000", 1).errors().isEmpty(), "범위가 자릿수를 넘음");
    yes(!one(DbKind.ORACLE, AMOUNT, Method.RANDOM_NUMBER, "9~1", 1).errors().isEmpty(), "거꾸로 된 범위");
    yes(!one(DbKind.ORACLE, AMOUNT, Method.LIST, "1, abc", 1).errors().isEmpty(), "숫자 목록에 글");
    yes(!one(DbKind.ORACLE, NAME, Method.FIXED, "1234567890", 1).errors().isEmpty(), "고정값 길이 초과");
    yes(!one(DbKind.ORACLE, DAY, Method.RANDOM_DATE, "어제~오늘", 1).errors().isEmpty(), "날짜 범위 형식");
    yes(!one(DbKind.ORACLE, ID, Method.SEQUENCE, "1", TestData.MAX_ROWS + 1).errors().isEmpty(), "줄 수 한도");
    yes(!one(DbKind.ORACLE, ID, Method.KOREAN_NAME, "", 1).errors().isEmpty(), "숫자 칼럼에 글 방식");
    eq(one(DbKind.ORACLE, AMOUNT, Method.RANDOM_NUMBER, "0.5~0.5", 3).rows().get(2).get(0).sql(), "0.50");

    // 한글 표본: Oracle 은 크기/3 글자까지(바이트 기준일 수 있다), PostgreSQL 은 글자 수
    for (List<InsertRows.Val> row : one(DbKind.ORACLE, NAME, Method.KOREAN_NAME, "", 50).rows()) {
      yes(((String) row.get(0).bind()).getBytes(StandardCharsets.UTF_8).length <= 9, "Oracle 한글 바이트");
    }
    yes(!one(DbKind.ORACLE, CODE, Method.KOREAN_NAME, "", 1).errors().isEmpty(), "한글 한 글자도 안 들어감");
    eq(((String) one(DbKind.POSTGRES, col("c", "bpchar", 1, 0, false, false), Method.KOREAN_NAME, "", 1).rows().get(0).get(0).bind()).length(), 1);
    for (List<InsertRows.Val> row : one(DbKind.ORACLE, col("W", "VARCHAR2", 3, 0, false, true), Method.ENGLISH_WORD, "", 50).rows()) {
      yes(((String) row.get(0).bind()).length() <= 3, "영문 길이");
    }

    // 주의: 넣지 않는 NOT NULL · 순번 없는 PK
    TestData.Output w = TestData.generate(DbKind.ORACLE, List.of(new Plan(ID, Method.RANDOM_NUMBER, "1~9"), new Plan(NAME, Method.SKIP, "")), Set.of(), 5, 1L);
    eq(w.errors(), List.of());
    eq(w.warnings().size(), 2);
    yes(!TestData.generate(DbKind.ORACLE, List.of(new Plan(NAME, Method.SKIP, "")), Set.of(), 5, 1L).errors().isEmpty(), "넣을 칼럼 없음");

    // 목록 돌려 쓰기 · INSERT 모양
    TestData.Output list = one(DbKind.ORACLE, CODE, Method.LIST, "A, B,C", 4);
    eq(list.rows().stream().map(r -> r.get(0).sql()).toList(), List.of("'A'", "'B'", "'C'", "'A'"));
    eq(InsertRows.statement(new InsertRows.Batch(DbKind.ORACLE, "APP.T", list.columns(), list.rows()), 0),
       "INSERT INTO APP.T (\n       USE_YN\n) VALUES (\n       'A'  -- USE_YN\n);\n");

    System.out.println("TestDataCheck 통과");
  }
}
