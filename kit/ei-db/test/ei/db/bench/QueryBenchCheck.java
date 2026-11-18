package ei.db.bench;

import ei.db.DbKind;
import ei.db.Meta;

import java.util.List;
import java.util.Map;

/** 쿼리 작업대 논리 자체 점검: java -cp out:test-out ei.db.bench.QueryBenchCheck */
public class QueryBenchCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }
  static void yes(boolean b, Object what) { if (!b) throw new AssertionError(String.valueOf(what)); }

  static Meta.Column col(String name, String type, boolean pk) { return new Meta.Column(name, type, 10, 0, pk, false, true, ""); }

  public static void main(String[] args) {
    parts();
    builder();
    advisor();
    patterns();
    tools();
    joins();
    dialect();
    lint();
    explain();
    errors();
    context();
    fixes();
    mysql();
    for (Examples.Example e : Examples.ALL) yes(!e.oracle().isBlank() && !e.postgres().isBlank() && !e.oracle().contains("\t"), e.title());
    System.out.println("QueryBenchCheck 통과");
  }

  static void parts() {
    List<SqlParts.Part> p = SqlParts.split("SELECT a, 'FROM x' FROM T t WHERE (SELECT 1 FROM D) = 1 ORDER BY a;");
    eq(p.size(), 4);
    eq(p.get(1).key(), "FROM");
    eq(p.get(1).body(), "T t");
    eq(p.get(3).body(), "a");
    eq(SqlParts.splitAnd("a = 1 AND b BETWEEN 1 AND 2 AND (c = 1 AND d = 2)"), List.of("a = 1", "b BETWEEN 1 AND 2", "(c = 1 AND d = 2)"));
    List<SqlParts.Source> s = SqlParts.sources("ORDERS o LEFT OUTER JOIN CUSTOMER c ON o.CUST_ID = c.CUST_ID, ITEM i");
    eq(s.size(), 3);
    eq(s.get(1).join(), "LEFT");
    eq(s.get(1).alias(), "c");
    eq(s.get(1).on(), "o.CUST_ID = c.CUST_ID");
    eq(s.get(2).ref(), "i");
    eq(SqlParts.refs("o.X = c.Y AND EXISTS (SELECT 1 FROM Z z WHERE z.A = o.X)").toString(), "[O, C]");
    eq(SqlParts.split("SELECT * FROM T START WITH P IS NULL CONNECT BY PRIOR ID = P").get(2).key(), "START WITH");
    eq(SqlParts.split("SELECT \"A(B\" FROM T WHERE X = 1").get(1).body(), "T");
    eq(SqlParts.split("SELECT q'[it's (FROM]' FROM T WHERE X = 1").get(1).body(), "T");
    eq(SqlParts.mask("SELECT \"a--b\", 1 FROM T"), "SELECT \"a__b\", 1 FROM T");
    // 따옴표 · 백틱으로 감싼 이름 안의 키워드 · 괄호 · 작은따옴표는 문장을 가르지 않는다.
    eq(SqlParts.split("SELECT \"FROM\", b FROM T t WHERE x = 1").get(1).body(), "T t");
    eq(SqlParts.split("SELECT `it's`, `a(b` FROM T WHERE x = 1").get(1).body(), "T");
    eq(SqlParts.unquote("`ORDERS`"), "ORDERS");
    eq(SqlParts.sources("`ORDERS` o, \"CUSTOMER\" c").get(1).alias(), "c");
  }

  static void builder() {
    QueryBuilder.Spec s = new QueryBuilder.Spec();
    s.tables.add(new QueryBuilder.Table("ORDERS", "o"));
    s.tables.add(new QueryBuilder.Table("CUSTOMER", "c"));
    s.cols.add(new QueryBuilder.Col("c", "GRADE", "VARCHAR2", "등급", null, null));
    s.cols.add(new QueryBuilder.Col("o", "AMOUNT", "NUMBER", "", QueryBuilder.Agg.SUM, null));
    s.joins.add(new QueryBuilder.Join("o", "CUST_ID", "c", "CUST_ID", true, false));
    s.conds.add(new QueryBuilder.Cond("o", "STATUS", QueryBuilder.ColKind.TEXT, QueryBuilder.Op.EQ, "D", null));
    s.conds.add(new QueryBuilder.Cond("o", "ORDER_DT", QueryBuilder.ColKind.DATE, QueryBuilder.Op.DATE_RANGE, "", ""));
    s.orders.add(new QueryBuilder.Order("SUM_AMOUNT", true));
    eq(QueryBuilder.render(s),
        "SELECT\n"
        + "       c.GRADE                      -- 등급\n"
        + "     , SUM(o.AMOUNT) AS SUM_AMOUNT\n"
        + "  FROM ORDERS o\n"
        + "     , CUSTOMER c\n"
        + " WHERE 1 = 1\n"
        + "   AND o.CUST_ID = c.CUST_ID(+)\n"
        + "   AND o.STATUS = :status\n"
        + "   AND o.ORDER_DT >= TO_DATE(:orderDtFrom, 'YYYY-MM-DD')\n"
        + "   AND o.ORDER_DT < TO_DATE(:orderDtTo, 'YYYY-MM-DD') + 1\n"
        + " GROUP BY\n"
        + "       c.GRADE\n"
        + " ORDER BY\n"
        + "       SUM_AMOUNT DESC");
    s.style = QueryBuilder.JoinStyle.ANSI;
    s.bind = QueryBuilder.Bind.MYBATIS;
    String ansi = QueryBuilder.render(s);
    yes(ansi.contains("  FROM ORDERS o\n  LEFT OUTER JOIN CUSTOMER c\n    ON o.CUST_ID = c.CUST_ID\n"), ansi);
    yes(ansi.contains("o.STATUS = #{status}"), ansi);
    String count = QueryBuilder.countSql(s);
    yes(count.contains("o.STATUS = 'D'") && !count.contains("ORDER_DT") && !count.contains("ORDER BY"), count);
    eq(QueryBuilder.unfilled(s), 1);
    eq(QueryBuilder.problems(s).size(), 0);
    s.joins.clear();
    yes(QueryBuilder.problems(s).get(0).contains("CUSTOMER"), QueryBuilder.problems(s));
    s.kind = DbKind.POSTGRES;
    s.style = QueryBuilder.JoinStyle.OLD;
    pgOld();
    s.limit = 10;
    s.offset = 20;
    yes(QueryBuilder.render(s).endsWith("LIMIT 10 OFFSET 20"), QueryBuilder.render(s));
    eq(QueryBuilder.page(DbKind.ORACLE, "SELECT 1 FROM DUAL", 0, 5), "SELECT *\n  FROM (\n        SELECT 1 FROM DUAL\n       )\n WHERE ROWNUM <= 5");
    yes(QueryBuilder.page(DbKind.ORACLE, "X", 20, 10).contains("WHERE ROWNUM <= 30") && QueryBuilder.page(DbKind.ORACLE, "X", 20, 10).endsWith("WHERE RN > 20"), "page");
    eq(QueryBuilder.kindOf("VARCHAR2"), QueryBuilder.ColKind.TEXT);
    eq(QueryBuilder.kindOf("TIMESTAMP(6)"), QueryBuilder.ColKind.DATE);
    eq(QueryBuilder.kindOf("NUMBER"), QueryBuilder.ColKind.NUMBER);
    eq(QueryBuilder.literal(DbKind.ORACLE, QueryBuilder.ColKind.TEXT, "O'K"), "'O''K'");
    eq(QueryBuilder.literal(DbKind.POSTGRES, QueryBuilder.ColKind.TEXT, "C:\\temp"), "'C:\\temp'");
    eq(QueryBuilder.literal(DbKind.MYSQL, QueryBuilder.ColKind.TEXT, "C:\\temp'"), "'C:\\\\temp'''");
  }

  /** (+) 가 없는 DB 의 옛 방식: 안쪽 조인은 콤마 · WHERE, 바깥 조인은 짝 표 뒤 LEFT OUTER JOIN. 짝이 여러 묶음에 걸치면 표준 JOIN. */
  static void pgOld() {
    QueryBuilder.Spec s = new QueryBuilder.Spec();
    s.kind = DbKind.POSTGRES;
    s.style = QueryBuilder.JoinStyle.OLD;
    s.tables.add(new QueryBuilder.Table("orders", "o"));
    s.tables.add(new QueryBuilder.Table("customer", "c"));
    s.tables.add(new QueryBuilder.Table("payment", "p"));
    s.joins.add(new QueryBuilder.Join("o", "cust_id", "c", "cust_id", false, false));
    s.joins.add(new QueryBuilder.Join("o", "order_id", "p", "order_id", true, false));
    String sql = QueryBuilder.render(s);
    yes(sql.toLowerCase().replace("\"", "").contains("  from orders o\n  left outer join payment p\n    on o.order_id = p.order_id\n     , customer c\n where 1 = 1\n   and o.cust_id = c.cust_id"), sql);
    yes(!sql.contains("(+)"), sql);
    s.joins.add(new QueryBuilder.Join("c", "cust_id", "p", "cust_id", true, false));
    sql = QueryBuilder.render(s);
    yes(sql.toLowerCase().replace("\"", "").contains("  inner join customer c") && sql.toLowerCase().replace("\"", "").contains("  left outer join payment p"), sql);
  }

  static void advisor() {
    List<QueryBuilder.Table> t = List.of(new QueryBuilder.Table("TB_GPM_NPP_PART_LINK", "tgnpl"), new QueryBuilder.Table("TB_GPM_NPP_PART", "tgnp"));
    Map<String, List<Meta.Column>> cols = Map.of(
        "TB_GPM_NPP_PART_LINK", List.of(col("ID", "NUMBER", true), col("NPP_PART_ID", "NUMBER", false)),
        "TB_GPM_NPP_PART", List.of(col("ID", "NUMBER", true), col("NAME", "VARCHAR2", false)));
    List<QueryBuilder.Join> j = JoinAdvisor.suggest(t, cols, List.of());
    eq(j.size(), 1);
    eq(j.get(0), new QueryBuilder.Join("tgnpl", "NPP_PART_ID", "tgnp", "ID", false, true));
    List<QueryBuilder.Join> fk = JoinAdvisor.suggest(List.of(new QueryBuilder.Table("ORDERS", "o"), new QueryBuilder.Table("CUSTOMER", "c")), Map.of(),
        List.of(new Meta.ForeignKey("FK_O_C", "ORDERS", "CUST_ID", "CUSTOMER", "CUST_ID")));
    eq(fk.get(0), new QueryBuilder.Join("o", "CUST_ID", "c", "CUST_ID", false, false));
  }

  static Patterns.Fill fill(DbKind kind) {
    return new Patterns.Fill(kind, QueryBuilder.JoinStyle.OLD, QueryBuilder.Bind.COLON, "orders", "orders_bak", List.of("cust_id"), List.of("amount"),
        "status", "amount", "order_dt", "parent_id", "status", List.of("R", "진행"));
  }

  static void patterns() {
    for (DbKind kind : new DbKind[]{DbKind.ORACLE, DbKind.POSTGRES}) {
      for (Patterns.Kind k : Patterns.Kind.values()) {
        String sql = Patterns.render(k, fill(kind));
        yes(sql.startsWith("--") && !sql.contains("null"), k + "\n" + sql);
      }
    }
    yes(Patterns.render(Patterns.Kind.TOP_N, fill(DbKind.ORACLE)).contains("FETCH FIRST 10 ROWS ONLY") && Patterns.render(Patterns.Kind.TOP_N, fill(DbKind.MYSQL)).endsWith("LIMIT 10"), "top n");
    yes(Patterns.render(Patterns.Kind.PERIOD, fill(DbKind.ORACLE)).contains("< TO_DATE(:toDt, 'YYYY-MM-DD') + 1"), "period");
    yes(Patterns.render(Patterns.Kind.JOIN_LOOKUP, fill(DbKind.ORACLE)).contains("o.CUST_ID = ob.CUST_ID(+)") && Patterns.render(Patterns.Kind.JOIN_LOOKUP, fill(DbKind.POSTGRES)).contains("LEFT OUTER JOIN ORDERS_BAK ob"), "join lookup");
    yes(Patterns.render(Patterns.Kind.DELETE_DUP, fill(DbKind.ORACLE)).contains("ROWID IN") && Patterns.render(Patterns.Kind.DELETE_DUP, fill(DbKind.POSTGRES)).contains("CTID IN")
        && Patterns.render(Patterns.Kind.DELETE_DUP, fill(DbKind.MYSQL)).contains("o.order_dt < b.order_dt"), "delete dup");
    yes(Patterns.render(Patterns.Kind.UPDATE_FROM, fill(DbKind.ORACLE)).contains("MERGE INTO ORDERS o") && Patterns.render(Patterns.Kind.UPDATE_FROM, fill(DbKind.MYSQL)).contains("INNER JOIN orders_bak ob"), "update from");
    yes(Patterns.render(Patterns.Kind.UPDATE_ROW, fill(DbKind.POSTGRES)).endsWith("WHERE CUST_ID = :custId") && Patterns.render(Patterns.Kind.DELETE_ROW, fill(DbKind.POSTGRES)).contains("WHERE CUST_ID = :custId"), "update · delete 는 조건이 붙는다");
    // 꼭 채워야 하는 칸만 채워도(비워도 되는 칸은 빈 채로) 문장이 나온다.
    for (DbKind kind : DbKind.values()) {
      for (Patterns.Kind k : Patterns.Kind.values()) {
        Patterns.Fill full = fill(kind);
        Patterns.Fill empty = new Patterns.Fill(kind, full.style(), full.bind(), "", "", List.of(), List.of(), "", "", "", "", "", List.of());
        List<Patterns.Field> need = Patterns.missing(k, empty);
        Patterns.Fill only = new Patterns.Fill(kind, full.style(), full.bind(), full.table(), need.contains(Patterns.Field.TABLE2) ? full.table2() : "",
            need.contains(Patterns.Field.KEYS) ? full.keys() : List.of(), need.contains(Patterns.Field.COLS) ? full.cols() : List.of(),
            need.contains(Patterns.Field.GROUP) ? full.group() : "", need.contains(Patterns.Field.VALUE) ? full.value() : "",
            need.contains(Patterns.Field.ORDER) ? full.order() : "", need.contains(Patterns.Field.PARENT) ? full.parent() : "",
            need.contains(Patterns.Field.STATUS) ? full.status() : "", need.contains(Patterns.Field.STATUS_VALUES) ? full.statusValues() : List.of());
        yes(Patterns.missing(k, only).isEmpty() && !Patterns.render(k, only).contains("null"), "꼭 필요한 칸만: " + kind + " " + k);
      }
    }
    List<String> items = List.of("ORDERS" + PickText.COMMENT_GAP + "주문", "ORDER_ITEM", "CUSTOMER");
    yes(PickText.filter(items, "ord", false, false).size() == 2 && PickText.filter(items, "주문", false, false).size() == 1 && PickText.filter(items, "zz", false, false).isEmpty(), "pick filter");
    yes(PickText.filter(items, "PART, cu", true, false).equals(List.of("CUSTOMER")) && PickText.filter(items, "ORDERS", false, true).size() == 3, "pick filter many · all");
    yes(PickText.picked("ord", items.get(0), false, false).equals("ORDERS") && PickText.picked("A, cu", "CUSTOMER", true, false).equals("A, CUSTOMER"), "pick put");
    yes(PickText.picked("A", "CUSTOMER", true, true).equals("A, CUSTOMER") && PickText.picked("A, ", "CUSTOMER", true, true).equals("A, CUSTOMER") && PickText.picked("", "CUSTOMER", true, true).equals("CUSTOMER"), "pick append");
    yes(PickText.picked("A, customer, B", "CUSTOMER", true, true).equals("A, B") && PickText.picked("cu", "CUSTOMER", true, false).equals("CUSTOMER"), "pick toggle");
    yes(PickText.chosen("A, orders", items.get(0)) && !PickText.chosen("A, ORDER", items.get(0)), "pick chosen");
    yes(Patterns.render(Patterns.Kind.SET_MINUS, fill(DbKind.ORACLE)).contains("\nMINUS\n") && Patterns.render(Patterns.Kind.SET_MINUS, fill(DbKind.POSTGRES)).contains("\nEXCEPT\n"), "minus");
    yes(Patterns.render(Patterns.Kind.ROLLUP, fill(DbKind.ORACLE)).endsWith("ROLLUP (o.STATUS)") && Patterns.render(Patterns.Kind.ROLLUP, fill(DbKind.MYSQL)).endsWith("WITH ROLLUP"), "rollup");
    yes(Patterns.render(Patterns.Kind.FULL_JOIN, fill(DbKind.POSTGRES)).contains("FULL OUTER JOIN") && Patterns.render(Patterns.Kind.FULL_JOIN, fill(DbKind.MYSQL)).contains("RIGHT OUTER JOIN"), "full join");
    yes(Patterns.render(Patterns.Kind.NOT_IN_LIST, fill(DbKind.ORACLE)).contains("NOT IN ('R', '진행')") && Patterns.render(Patterns.Kind.NOT_IN_LIST, fill(DbKind.ORACLE)).contains("IS NULL"), "not in");
    yes(Patterns.render(Patterns.Kind.TODAY, fill(DbKind.ORACLE)).contains("< TRUNC(SYSDATE) + 1") && Patterns.render(Patterns.Kind.RECENT_DAYS, fill(DbKind.POSTGRES)).contains(">= CURRENT_DATE - 7"), "today · recent");
    yes(Patterns.render(Patterns.Kind.DELETE_OLD, fill(DbKind.ORACLE)).contains("WHERE ORDER_DT < TO_DATE(:beforeDt") && Patterns.render(Patterns.Kind.DELETE_NOT_IN, fill(DbKind.ORACLE)).contains("WHERE ob.CUST_ID = ORDERS.CUST_ID"), "delete 는 조건이 붙는다");
    yes(Patterns.from(Patterns.Kind.SET_MINUS, Patterns.Field.COLS) == Patterns.From.BOTH && Patterns.from(Patterns.Kind.EXISTS, Patterns.Field.KEYS) == Patterns.From.BOTH
        && Patterns.from(Patterns.Kind.JOIN_LOOKUP, Patterns.Field.COLS) == Patterns.From.SECOND && Patterns.from(Patterns.Kind.JOIN_LOOKUP, Patterns.Field.KEYS) == Patterns.From.BOTH
        && Patterns.from(Patterns.Kind.LIST, Patterns.Field.COLS) == Patterns.From.FIRST && Patterns.from(Patterns.Kind.PARENT_COLS, Patterns.Field.COLS) == Patterns.From.FIRST, "칼럼 목록의 테이블");
    yes(Patterns.typeOf("VARCHAR2(100)") == Patterns.Type.TEXT && Patterns.typeOf("timestamp") == Patterns.Type.DATE && Patterns.typeOf("int4") == Patterns.Type.NUMBER
        && Patterns.typeOf("NUMBER(12,2)") == Patterns.Type.NUMBER && Patterns.typeOf("bool") == Patterns.Type.ANY && Patterns.typeOf("bpchar") == Patterns.Type.TEXT, "형 갈래");
    yes(Patterns.single(Patterns.Kind.HIERARCHY, Patterns.Field.KEYS) && Patterns.single(Patterns.Kind.LEAF, Patterns.Field.KEYS) && !Patterns.single(Patterns.Kind.DUPLICATES, Patterns.Field.KEYS), "하나만 받는 칸");
    java.util.Map<String, String> t1 = java.util.Map.of("CUST_ID", "NUMBER", "AMOUNT", "NUMBER(12,2)", "STATUS", "VARCHAR2(10)", "ORDER_DT", "DATE", "YMD", "VARCHAR2(8)", "PARENT_ID", "NUMBER");
    java.util.Map<String, String> t2 = java.util.Map.of("CUST_ID", "VARCHAR2(20)", "STATUS", "VARCHAR2(10)");
    java.util.Map<String, String> none = java.util.Map.of();
    for (Patterns.Kind k : Patterns.Kind.values()) {
      if (Patterns.need(k, Patterns.Field.COLS) != Patterns.Type.TEXT && Patterns.need(k, Patterns.Field.VALUE) != Patterns.Type.TEXT) {
        yes(Patterns.warnings(k, fill(DbKind.ORACLE), t1, none).isEmpty(), "맞는 칼럼이면 알림 없음: " + k + " " + Patterns.warnings(k, fill(DbKind.ORACLE), t1, none));
      }
      yes(Patterns.warnings(k, fill(DbKind.ORACLE), none, none).isEmpty(), "칼럼을 못 읽었으면 따지지 않음: " + k);
    }
    List<String> setWarn = Patterns.warnings(Patterns.Kind.SET_MINUS, fill(DbKind.ORACLE), t1, t2);
    yes(setWarn.size() == 1 && setWarn.get(0).contains("amount") && setWarn.get(0).contains("orders_bak 에 없는"), "둘째 테이블에 없는 칼럼: " + setWarn);
    List<String> typeWarn = Patterns.warnings(Patterns.Kind.EXISTS, fill(DbKind.ORACLE), t1, t2);
    yes(typeWarn.size() == 1 && typeWarn.get(0).contains("형이 두 테이블에서 다릅니다"), "두 테이블 형 다름: " + typeWarn);
    Patterns.Fill ymd = new Patterns.Fill(DbKind.ORACLE, QueryBuilder.JoinStyle.OLD, QueryBuilder.Bind.COLON, "orders", "", List.of("cust_id", "amount"), List.of("nope"), "status", "status", "ymd", "parent_id", "status", List.of("R"));
    yes(Patterns.warnings(Patterns.Kind.TODAY, ymd, t1, none).toString().contains("날짜 형이 아닙니다(VARCHAR2(8))"), "날짜 형 아님");
    yes(Patterns.warnings(Patterns.Kind.SUM_BY, ymd, t1, none).toString().contains("숫자 형이 아닙니다"), "숫자 형 아님");
    yes(Patterns.warnings(Patterns.Kind.LIST, ymd, t1, none).toString().contains("nope 는 orders 에 없는 칼럼"), "없는 칼럼");
    yes(Patterns.warnings(Patterns.Kind.HIERARCHY, ymd, t1, none).toString().contains("칼럼 하나만 씁니다"), "하나만");
    yes(Patterns.warnings(Patterns.Kind.JOIN_LOOKUP, fill(DbKind.ORACLE), t1, java.util.Map.of("CUST_ID", "NUMBER", "NAME", "VARCHAR2(10)")).toString().contains("amount 는 orders_bak 에 없는"), "조인으로 붙일 칼럼은 둘째 테이블 것");
    Patterns.Fill same = new Patterns.Fill(DbKind.ORACLE, QueryBuilder.JoinStyle.OLD, QueryBuilder.Bind.COLON, "orders", "orders_bak", List.of("cust_id"), List.of("CUST_ID", "amount"), "", "", "", "", "", List.of());
    yes(Patterns.warnings(Patterns.Kind.UPSERT, same, none, none).size() == 1 && Patterns.warnings(Patterns.Kind.JOIN_LOOKUP, same, none, none).isEmpty(), "기준 칼럼과 겹침");
    yes(!Patterns.render(Patterns.Kind.INSERT_MISSING, same).contains("(CUST_ID, CUST_ID"), "없는 것만 넣기는 겹친 칼럼을 한 번만");
    Patterns.Fill two = new Patterns.Fill(DbKind.ORACLE, QueryBuilder.JoinStyle.OLD, QueryBuilder.Bind.COLON, "orders", "", List.of(), List.of(), "status, cust_id", "amount", "", "", "", List.of());
    yes(Patterns.render(Patterns.Kind.COUNT_BY, two).contains("GROUP BY\n       o.STATUS\n     , o.CUST_ID") && Patterns.render(Patterns.Kind.SUM_BY, two).contains("o.STATUS\n     , o.CUST_ID\n     , COUNT(*) AS CNT"), "묶을 칼럼 여러 개");
    String dup = Patterns.render(Patterns.Kind.DUPLICATES, fill(DbKind.ORACLE));
    yes(dup.contains("HAVING COUNT(*) > 1") && dup.contains("GROUP BY\n       CUST_ID\n"), dup);
    yes(Patterns.render(Patterns.Kind.HIERARCHY, fill(DbKind.POSTGRES)).startsWith("-- 계층") && Patterns.render(Patterns.Kind.HIERARCHY, fill(DbKind.POSTGRES)).contains("WITH RECURSIVE"), "hier");
    yes(Patterns.render(Patterns.Kind.UPSERT, fill(DbKind.POSTGRES)).contains("ON CONFLICT (CUST_ID)"), "upsert");
    Patterns.Fill part = new Patterns.Fill(DbKind.POSTGRES, QueryBuilder.JoinStyle.OLD, QueryBuilder.Bind.COLON, "part", null, List.of("part_id"), List.of(), null, null, null, "parent_id", null, List.of());
    String partTree = Patterns.render(Patterns.Kind.HIERARCHY, part);
    yes(partTree.contains("FROM PART p\n     INNER JOIN tree p2\n        ON p.PARENT_ID = p2.PART_ID") && !partTree.contains("tree p\n"), "hier alias p: " + partTree);
    Patterns.Fill empty = new Patterns.Fill(DbKind.ORACLE, QueryBuilder.JoinStyle.OLD, QueryBuilder.Bind.COLON, "", "", List.of(), List.of(), "", "", "", "", "", List.of());
    yes(Patterns.missing(Patterns.Kind.DUPLICATES, empty).contains(Patterns.Field.KEYS), "missing");
  }

  static void tools() {
    SqlTools.InResult in = SqlTools.inList("A\nB\nA\nO'K\n", List.of("CODE"), DbKind.ORACLE);
    eq(in.sql(), "-- 값 3개 (같은 값 1개 뺌)\nCODE IN ('A', 'B', 'O''K')");
    eq(SqlTools.inList("1\t2\n3\tx\n", List.of(), DbKind.POSTGRES).sql(), "-- 값 2개\n(COL1, COL2) IN ((1, '2'), (3, 'x'))");
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 1500; i++) many.append(i).append('\n');
    yes(SqlTools.inList(many.toString(), List.of("ID"), DbKind.ORACLE).sql().contains("\n    OR ID IN ("), "split");
    eq(SqlTools.countOf("SELECT * FROM T WHERE A = 1 ORDER BY B"), "SELECT COUNT(*) AS CNT\n  FROM (\n        SELECT * FROM T WHERE A = 1\n       ) q");
    SqlTools.Dml del = SqlTools.toDelete("SELECT o.* FROM ORDERS o WHERE o.STATUS = 'X' AND 1 = 1", DbKind.ORACLE);
    eq(del.sql(), "DELETE\n  FROM ORDERS o\n WHERE o.STATUS = 'X'");
    SqlTools.Dml up = SqlTools.toUpdate("SELECT o.MEMO, c.NAME FROM ORDERS o, CUSTOMER c WHERE o.CUST_ID = c.CUST_ID AND c.GRADE = 'VIP'", DbKind.ORACLE, QueryBuilder.Bind.COLON);
    eq(up.sql(), "UPDATE ORDERS o\n   SET MEMO = :memo\n WHERE EXISTS (\n        SELECT 1\n          FROM CUSTOMER c\n         WHERE o.CUST_ID = c.CUST_ID\n           AND c.GRADE = 'VIP'\n       )");
    eq(in.width(), 1);
    eq(SqlTools.inList("1\t2\n3\tx\n", List.of("A"), DbKind.POSTGRES).width(), 2);
    yes(SqlTools.sameCondition(up.sql(), up.sql().replace("MEMO = :memo", "MEMO = 'x',\n STATUS = 'Y'") + " ;"), "SET 만 고치면 조건은 같다");
    yes(!SqlTools.sameCondition(up.sql(), up.sql().replace("'VIP'", "'GOLD'")), "조건을 고치면 다르다");
    yes(!SqlTools.sameCondition(del.sql(), "DELETE FROM ORDERS o"), "조건을 지우면 다르다");
    yes(SqlTools.sameCondition("DELETE\n  FROM T", "delete from T;"), "둘 다 조건이 없으면 같다");
    try {
      SqlTools.toDelete("SELECT * FROM A a, B b WHERE a.X = b.X(+)", DbKind.ORACLE);
      throw new AssertionError("outer");
    } catch (IllegalArgumentException expected) {
      // 바깥 조인은 바꾸지 않는다
    }
    String with = "WITH base AS (SELECT * FROM T WHERE A = 1), summary AS (SELECT B, COUNT(*) CNT FROM base GROUP BY B) SELECT * FROM summary";
    eq(SqlTools.ctes(with).size(), 2);
    eq(SqlTools.ctes(with).get(1).name(), "summary");
    eq(SqlTools.upTo(with, 0), "WITH base AS (SELECT * FROM T WHERE A = 1)\nSELECT\n       *\n  FROM base");
  }

  static void joins() {
    JoinStyleConvert.Result r = JoinStyleConvert.toAnsi("SELECT o.*, c.NAME\n  FROM ORDERS o, CUSTOMER c, ITEM i\n WHERE o.CUST_ID = c.CUST_ID(+)\n   AND c.TYPE(+) = 'A'\n   AND i.ORDER_ID = o.ORDER_ID\n   AND o.STATUS = 'D'\n ORDER BY o.ORDER_ID");
    eq(r.sql(), "SELECT o.*, c.NAME\n  FROM ORDERS o\n  LEFT OUTER JOIN CUSTOMER c\n    ON o.CUST_ID = c.CUST_ID\n   AND c.TYPE = 'A'\n  INNER JOIN ITEM i\n    ON i.ORDER_ID = o.ORDER_ID\n WHERE 1 = 1\n   AND o.STATUS = 'D'\n ORDER BY o.ORDER_ID");
    JoinStyleConvert.Result back = JoinStyleConvert.toOld(r.sql());
    eq(back.sql(), "SELECT o.*, c.NAME\n  FROM ORDERS o\n     , CUSTOMER c\n     , ITEM i\n WHERE 1 = 1\n   AND o.CUST_ID = c.CUST_ID(+)\n   AND c.TYPE(+) = 'A'\n   AND i.ORDER_ID = o.ORDER_ID\n   AND o.STATUS = 'D'\n ORDER BY o.ORDER_ID");
  }

  static void dialect() {
    DialectConvert.Result pg = DialectConvert.toPostgres("SELECT NVL(a, 0), DECODE(s, 'A', '가', '나'), SYSDATE, 'NVL(x)' FROM DUAL WHERE ROWNUM <= 10");
    eq(pg.sql(), "SELECT COALESCE(a, 0), CASE s WHEN 'A' THEN '가' ELSE '나' END, NOW(), 'NVL(x)'\n LIMIT 10");
    DialectConvert.Result ora = DialectConvert.toOracle("SELECT a::text, NOW(), name ILIKE 'kim%' FROM t LIMIT 5");
    eq(ora.sql(), "SELECT *\n  FROM (\n        SELECT CAST(a AS VARCHAR2(4000)), SYSDATE, UPPER(name) LIKE UPPER('kim%') FROM t\n       )\n WHERE ROWNUM <= 5");
    eq(DialectConvert.toPostgres("SELECT LISTAGG(x, ',') WITHIN GROUP (ORDER BY y) FROM t").sql(), "SELECT STRING_AGG(CAST(x AS VARCHAR), ',' ORDER BY y) FROM t");
    eq(DialectConvert.toOracle("SELECT STRING_AGG(x, ',' ORDER BY y) FROM t").sql(), "SELECT LISTAGG(x, ',') WITHIN GROUP (ORDER BY y) FROM t");
    eq(DialectConvert.toOracle("SELECT 1").sql(), "SELECT 1\n  FROM DUAL");
    yes(DialectConvert.toOracle("SELECT a FROM t LIMIT 99999999999").sql().contains("LIMIT"), "huge limit left as is");
    yes(DialectConvert.toOracle("SELECT a FROM t LIMIT 5 OFFSET 99999999999").sql().contains("OFFSET"), "huge offset left as is");
    eq(DialectConvert.toOracle("SELECT x::double precision, y::float8 FROM t").sql(), "SELECT CAST(x AS BINARY_DOUBLE), CAST(y AS BINARY_DOUBLE) FROM t");
    eq(DialectConvert.toOracle("SELECT a::timestamp with time zone, b::timestamptz, c::timestamp without time zone FROM t").sql(),
       "SELECT CAST(a AS TIMESTAMP WITH TIME ZONE), CAST(b AS TIMESTAMP WITH TIME ZONE), CAST(c AS TIMESTAMP) FROM t");
    eq(DialectConvert.toOracle("SELECT * FROM t WHERE id = :id::int").sql(), "SELECT * FROM t WHERE id = CAST(:id AS NUMBER)");
    eq(DialectConvert.toOracle("SELECT n::character varying(20), m::int8 FROM t").sql(), "SELECT CAST(n AS VARCHAR2(20)), CAST(m AS NUMBER) FROM t");
  }

  static void lint() {
    yes(SqlLint.check("SELECT * FROM A a, B b WHERE a.X = 1").get(0).title().contains("b"), "cartesian");
    eq(SqlLint.check("SELECT * FROM A a, B b WHERE a.X = b.X").size(), 0);
    String inner = "SELECT a.X FROM A a JOIN B b ON b.ID = a.ID";
    yes(JoinStyleConvert.toOldOrSame(inner, false).sql().contains(", B b"), "pg inner to comma");
    String left = "SELECT a.X FROM A a LEFT JOIN B b ON b.ID = a.ID";
    eq(JoinStyleConvert.toOldOrSame(left, false).sql(), left);
    yes(JoinStyleConvert.toOldOrSame(left, true).sql().contains("(+)"), "oracle left to plus");
    eq(JoinStyleConvert.toOldOrSame(inner + "; " + inner, true).sql(), inner + "; " + inner);
    eq(JoinStyleConvert.toOldOrSame("SELECT * FROM A NATURAL JOIN B", true).sql(), "SELECT * FROM A NATURAL JOIN B");
    String fsql = "SELECT SUM(a.X), to_char(a.D, 'x(1)'), DBMS_RANDOM.VALUE(), COUNT(*) FROM A a WHERE a.X IN (1) AND EXISTS (SELECT 1 FROM B)";
    List<int[]> fns = SqlParts.functionSpans(fsql);
    eq(fns.size(), 4);
    eq(fsql.substring(fns.get(2)[0], fns.get(2)[1]), "DBMS_RANDOM.VALUE");
    eq(SqlParts.functionSpans("INSERT INTO T (A, B) VALUES (1, NVL(2, 3))").size(), 1);
    List<int[]> cols = SqlParts.columnSpans("SELECT o.STATUS, c . NAME, PKG.F(1), 'x.Y' -- a.B\n FROM T o");
    eq(cols.size(), 2);
    eq(cols.get(0)[0], 7);
    eq(cols.get(1)[1], 25);
    eq(SqlLint.check("SELECT * FROM A a CROSS JOIN B b").size(), 0);
    yes(SqlLint.check("SELECT a.DEPT, COUNT(*) FROM A a").get(0).title().contains("GROUP BY"), "group");
    yes(SqlLint.check("SELECT a.DEPT, a.NAME, COUNT(*) CNT FROM A a GROUP BY a.DEPT").get(0).detail().contains("a.NAME"), "group missing");
    eq(SqlLint.check("SELECT a.DEPT, COUNT(*) CNT, SUM(a.X) OVER () T FROM A a GROUP BY a.DEPT").size(), 0);
    yes(SqlLint.check("SELECT * FROM A WHERE X = NULL").get(0).title().contains("NULL"), "eq null");
    yes(SqlLint.check("SELECT * FROM A a, B b WHERE a.X = b.X(+) AND b.Y = 'Z'").get(0).title().contains("바깥"), "outer");
    eq(SqlLint.check("SELECT * FROM A a, B b WHERE a.X = b.X(+) AND b.Y IS NULL").size(), 0);
    eq(SqlLint.check("UPDATE A SET X = 1").size(), 0);
    SqlLint.Graph g = SqlLint.graph("SELECT * FROM ORDERS o, CUSTOMER c WHERE o.CUST_ID = c.ID");
    eq(g.links().get(0), new SqlLint.Link("o", "CUST_ID", "c", "ID", "o.CUST_ID = c.ID"));
  }

  static void explain() {
    List<SqlExplain.Step> steps = SqlExplain.explain("SELECT o.*, COUNT(*) OVER () T FROM ORDERS o LEFT JOIN CUSTOMER c ON o.C = c.C WHERE o.S LIKE '%a%' AND (o.A, o.B) IN ((1, 2)) ORDER BY o.D DESC");
    eq(steps.get(0).part(), "SELECT");
    yes(steps.get(1).lines().get(1).contains("왼쪽 바깥 조인"), steps.get(1).lines());
    yes(steps.get(2).lines().get(1).contains("들어 있는"), steps.get(2).lines());
    yes(steps.get(2).lines().get(2).contains("튜플 IN"), steps.get(2).lines());
    yes(steps.get(3).lines().get(0).contains("큰 것부터"), steps.get(3).lines());
    eq(steps.get(steps.size() - 1).part(), "실행 순서");
  }

  static void errors() {
    DbErrors.Help h = DbErrors.explain(DbKind.ORACLE, 904, null, "ORA-00904: \"CUSTID\": invalid identifier", "SELECT CUSTID FROM T");
    eq(h.code(), "ORA-00904");
    eq(h.word(), "CUSTID");
    eq(DbErrors.locate(h, "SELECT CUSTID FROM T"), 7);
    DbErrors.Help p = DbErrors.explain(DbKind.POSTGRES, 0, "42883", "ERROR: function nvl(integer, integer) does not exist\n  Position: 8", "SELECT NVL(a, 0) FROM t");
    eq(p.position(), 7);
    yes(p.hints().get(0).contains("COALESCE"), p.hints());
    eq(DbErrors.explain(DbKind.ORACLE, 99999, null, "ORA-99999", "SELECT 1 FROM DUAL"), null);
  }

  static void context() {
    eq(SqlContext.at("SELECT * FROM "), SqlContext.Place.TABLE);
    eq(SqlContext.at("SELECT * FROM ORDERS o, "), SqlContext.Place.TABLE);
    eq(SqlContext.at("SELECT * FROM ORDERS o "), SqlContext.Place.AFTER_TABLE);
    eq(SqlContext.at("SELECT * FROM ORDERS o LEFT JOIN "), SqlContext.Place.JOIN_TABLE);
    eq(SqlContext.at("SELECT * FROM ORDERS o JOIN CUSTOMER c ON "), SqlContext.Place.ON);
    eq(SqlContext.at("SELECT * FROM ORDERS o WHERE o.STATUS "), SqlContext.Place.OPERATOR);
    eq(SqlContext.at("SELECT * FROM ORDERS o WHERE o.STATUS = 'A' AND "), SqlContext.Place.OTHER);
    eq(SqlContext.at("SELECT * FROM ORDERS o ORDER BY o.ID "), SqlContext.Place.ORDER);
    eq(SqlContext.at("SELECT * FROM ORDERS o WHERE EXISTS (SELECT 1 FROM "), SqlContext.Place.TABLE);
  }

  /** 오류 고친 것: 빈 값 칼럼, 계층 고리, CDATA, TRUNC · ROWNUM 페이지 · SET 별칭 바꾸기. */
  static void fixes() {
    yes(SqlLint.unlinked(SqlLint.graph("SELECT h.H FROM GENERATE_SERIES(0, 23) AS h(H) LEFT JOIN ORDERS o ON EXTRACT(HOUR FROM o.REG_DT) = h.H")).isEmpty(), "table function source");
    yes(SqlLint.unlinked(SqlLint.graph("SELECT c.ID FROM CUSTOMER c LEFT JOIN LATERAL (SELECT 1 FROM ORDERS x WHERE x.CUST_ID = c.CUST_ID) o ON TRUE")).isEmpty(), "lateral source");
    yes(SqlLint.unlinked(SqlLint.graph("SELECT 1 FROM ORDERS o, CUSTOMER c")).equals(List.of("c")), "loose table still found");
    yes(SqlLint.unlinked(SqlLint.graph("SELECT p.PART_NO, t.TAG FROM PART p, UNNEST(STRING_TO_ARRAY(p.TAGS, ',')) WITH ORDINALITY AS t(TAG, N)")).isEmpty(), "table function with ordinality");
    eq(SqlParts.sources("UNNEST(x) WITH ORDINALITY AS t(TAG, N)").get(0).alias(), "t");
    yes(SqlLint.unlinked(SqlLint.graph("SELECT p.PART_NO, t.TAG FROM PART p, UNNEST(STRING_TO_ARRAY(p.TAGS, ',')) WITH ORDINALITY AS t(TAG, N)")).isEmpty(), "table function with ordinality");
    eq(SqlParts.sources("UNNEST(x) WITH ORDINALITY AS t(TAG, N)").get(0).alias(), "t");
    Patterns.Fill e = new Patterns.Fill(DbKind.ORACLE, QueryBuilder.JoinStyle.OLD, QueryBuilder.Bind.COLON, "orders", "", List.of(), List.of(), "", "", "", "", "", List.of());
    yes(Patterns.missing(Patterns.Kind.RANK, e).contains(Patterns.Field.VALUE), "rank needs value");
    String hier = Patterns.render(Patterns.Kind.HIERARCHY, fill(DbKind.POSTGRES));
    yes(hier.contains("NOT LIKE ('% > ' ||") && hier.contains("AS VARCHAR(4000))\n         , o.*\n      FROM"), hier);
    eq(QueryBuilder.cdata("SELECT 1\n WHERE a.X >= #{x} -- 시작\n   AND a.Y = 1"),
        "SELECT 1\n <![CDATA[ WHERE a.X >= #{x} ]]>  -- 시작\n   AND a.Y = 1");
    eq(QueryBuilder.cdata(" WHERE a.X = '<b>' AND 1 = 1"), " WHERE a.X = '<b>' AND 1 = 1");
    DialectConvert.Result t = DialectConvert.toPostgres("SELECT TRUNC(o.ORDER_DT, 'MM'), TRUNC(o.ORDER_DT), TRUNC(o.AMOUNT, 2) FROM ORDERS o");
    yes(t.sql().contains("DATE_TRUNC('month', o.ORDER_DT)") && t.sql().contains("DATE_TRUNC('day', o.ORDER_DT)") && t.sql().contains("TRUNC(o.AMOUNT, 2)"), t.sql());
    DialectConvert.Result pg = DialectConvert.toPostgres("SELECT * FROM (SELECT q.*, ROWNUM AS RN FROM (SELECT o.* FROM ORDERS o ORDER BY o.ORDER_ID DESC) q WHERE ROWNUM <= 30) WHERE RN > 20");
    yes(pg.sql().contains("LIMIT 10 OFFSET 20") && !pg.sql().toUpperCase(java.util.Locale.ROOT).contains("ROWNUM"), pg.sql());
    String huge = "SELECT * FROM (SELECT q.*, ROWNUM AS RN FROM (SELECT o.* FROM ORDERS o) q WHERE ROWNUM <= 99999999999) WHERE RN > 20";
    yes(DialectConvert.toPostgres(huge).sql().contains("ROWNUM"), "huge rownum left as is");
    yes(DialectConvert.toPostgres("SELECT a FROM t WHERE ROWNUM <= 99999999999").sql().contains("ROWNUM"), "huge rownum");
    DialectConvert.Result up = DialectConvert.toPostgres("UPDATE ORDERS o SET o.STATUS = 'X', o.MEMO = NULL WHERE o.ORDER_ID = 1");
    yes(up.sql().contains("SET STATUS = 'X', MEMO = NULL") && up.sql().contains("WHERE o.ORDER_ID = 1"), up.sql());
    yes(String.join(" ", DialectConvert.toPostgres("SELECT * FROM USER_TAB_COLUMNS").notes()).contains("데이터 사전"), "dictionary note");
    QueryBuilder.Spec g = new QueryBuilder.Spec();
    g.tables.add(new QueryBuilder.Table("ORDERS", "o"));
    g.groups.add(new QueryBuilder.Col("o", "STATUS", "VARCHAR2", "", null, null));
    String gs = QueryBuilder.render(g);
    yes(gs.startsWith("SELECT\n       o.STATUS\n     , COUNT(*) AS CNT\n") && gs.contains(" GROUP BY\n       o.STATUS"), gs);
    g.cols.add(new QueryBuilder.Col("o", "CUST_ID", "NUMBER", "", null, null));
    g.cols.add(new QueryBuilder.Col("o", "AMOUNT", "NUMBER", "", QueryBuilder.Agg.SUM, null));
    eq(QueryBuilder.groupBy(g), List.of("o.STATUS", "o.CUST_ID"));
    yes(!QueryBuilder.render(g).contains("COUNT(*)"), "no auto count when aggregated");
  }

  /** MySQL: || 는 OR, DATE + 1 은 숫자라 CONCAT · DATE_ADD · INTERVAL 로 만든다. Oracle · PostgreSQL 글은 그대로다. */
  static void mysql() {
    QueryBuilder.Spec s = new QueryBuilder.Spec();
    s.tables.add(new QueryBuilder.Table("ORDERS", "o"));
    s.cols.add(new QueryBuilder.Col("o", "ORDER_ID", "INT", "", null, null));
    for (QueryBuilder.Op op : new QueryBuilder.Op[]{QueryBuilder.Op.CONTAINS, QueryBuilder.Op.STARTS, QueryBuilder.Op.ENDS}) {
      s.conds.add(new QueryBuilder.Cond("o", "MEMO", QueryBuilder.ColKind.TEXT, op, "", null));
    }
    s.conds.add(new QueryBuilder.Cond("o", "ORDER_DT", QueryBuilder.ColKind.DATE, QueryBuilder.Op.DATE_RANGE, "", ""));
    for (DbKind kind : DbKind.values()) {
      s.kind = kind;
      String sql = QueryBuilder.render(s);
      if (kind == DbKind.MYSQL) {
        yes(!sql.contains("||") && sql.contains("CONCAT(") && sql.contains("INTERVAL 1 DAY)") && !sql.contains(") + 1"), sql);
      } else {
        yes(sql.contains("||") && sql.contains(" + 1") && !sql.contains("CONCAT("), sql);
      }
    }
    eq(QueryBuilder.nextDay(DbKind.MYSQL, "d"), "DATE_ADD(d, INTERVAL 1 DAY)");
    yes(SqlExplain.condition("o.MEMO LIKE CONCAT('%', :memo, '%')").contains("들어 있는"), "explain contains");
    yes(SqlExplain.condition("o.MEMO LIKE CONCAT(:memo, '%')").contains("시작하는"), "explain starts");
    yes(SqlExplain.condition("o.MEMO LIKE CONCAT('%', :memo)").contains("끝나는"), "explain ends");
    yes(SqlExplain.condition("o.MEMO LIKE '%' || :memo || '%'").contains("들어 있는"), "explain ||");
    eq(QueryBuilder.nextDay(DbKind.POSTGRES, "d"), "d + 1");
    QueryBuilder.Spec v = new QueryBuilder.Spec();
    v.kind = DbKind.MYSQL;
    v.bind = QueryBuilder.Bind.VALUE;
    v.tables.add(new QueryBuilder.Table("ORDERS", "o"));
    v.conds.add(new QueryBuilder.Cond("o", "MEMO", QueryBuilder.ColKind.TEXT, QueryBuilder.Op.CONTAINS, "a\\b", null));
    yes(QueryBuilder.render(v).contains("o.MEMO LIKE '%a\\\\\\\\b%'"), QueryBuilder.render(v));

    String hier = Patterns.render(Patterns.Kind.HIERARCHY, fill(DbKind.MYSQL));
    yes(hier.contains("WITH RECURSIVE") && hier.contains("CONCAT(") && hier.contains("CHAR(4000)") && !hier.contains("||") && !hier.contains("VARCHAR(4000)"), hier);
    String up = Patterns.render(Patterns.Kind.UPSERT, fill(DbKind.MYSQL));
    yes(up.contains("ON DUPLICATE KEY UPDATE") && !up.contains("ON CONFLICT"), up);
    for (Patterns.Kind k : Patterns.Kind.values()) {
      String sql = Patterns.render(k, fill(DbKind.MYSQL));
      yes(sql.startsWith("--") && !sql.contains("null") && !SqlParts.mask(sql).contains("||"), k + "\n" + sql);
    }

    eq(MysqlConvert.convert("SELECT CURRENT_DATE - 30, a.ORDER_DT - b.DUE_DT, r.FIRST_RCV - p.PO_DT, NOW() - a.QUERY_START"),
        "SELECT CURRENT_DATE - INTERVAL 30 DAY, DATEDIFF(a.ORDER_DT, b.DUE_DT), DATEDIFF(r.FIRST_RCV, p.PO_DT), NOW() - a.QUERY_START");
    eq(MysqlConvert.convert("SELECT a || '-' || b, x::NUMERIC FROM T WHERE n ILIKE '%' || :k || '%'"),
        "SELECT CONCAT(a, '-', b), CAST(x AS DECIMAL(38, 10)) FROM T WHERE n LIKE CONCAT('%', :k, '%')");
    eq(MysqlConvert.convert("SELECT 'a||b' AS \"별칭\""), "SELECT 'a||b' AS `별칭`");

    // 모든 예제의 MySQL 글에 PostgreSQL 전용 문법이 남지 않는다(MySQL 8.0 실행 확인은 따로 했다).
    String[] pgOnly = {"||", "::", "ILIKE", "INTERVAL '", "DATE_TRUNC", "STRING_AGG", "GENERATE_SERIES", "FULL OUTER JOIN", "PG_", "CURRENT_SCHEMA", "RETURNING", "NULLS LAST", "NULLS FIRST", "FILTER (WHERE"};
    for (Examples.Example e : Examples.ALL) {
      eq(e.sql(DbKind.ORACLE), e.oracle());
      eq(e.sql(DbKind.POSTGRES), e.postgres());
      String my = e.sql(DbKind.MYSQL);
      String code = SqlParts.mask(my).toUpperCase(java.util.Locale.ROOT).replaceAll("--[^\n]*", "");
      for (String bad : pgOnly) yes(!code.contains(bad), e.title() + ": " + bad + "\n" + my);
      yes(!my.isBlank() && !my.contains("\t"), e.title());
    }
    for (String title : MysqlExamples.BY_TITLE.keySet()) {
      boolean found = false;
      for (Examples.Example e : Examples.ALL) if (e.title().equals(title)) found = true;
      yes(found, "MySQL 글의 제목이 예제에 없음: " + title);
    }
  }
}
