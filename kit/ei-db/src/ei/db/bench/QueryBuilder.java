package ei.db.bench;

import ei.db.DbKind;
import ei.db.gen.Names;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 단계별 작성기의 SQL 만들기: 표 → 칼럼 → 연결 → 조건 → 묶기 → 정렬 → 페이징. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 * 모양은 사용자 SQL 규칙을 따른다: SELECT 는 키워드만 한 줄, 쉼표는 줄 앞(「     , 」), 표 · 칼럼 대문자, 별칭 소문자.
 * 연결은 옛 방식(FROM 에 쉼표로 나열 · WHERE 에 조건 · 바깥 조인은 (+))이나 표준 방식(JOIN … ON)으로 쓴다.
 * PostgreSQL · MySQL 에는 (+) 가 없어 늘 표준 방식으로 쓴다.
 */
public final class QueryBuilder {
  private QueryBuilder() {}

  /** 연결 모양. */
  public enum JoinStyle { OLD, ANSI }

  /** 값 자리 표기: 콘솔 이름 자리(:이름), MyBatis(#{이름}), 값을 글자 그대로. */
  public enum Bind { COLON, MYBATIS, VALUE }

  /** 칼럼 형식 묶음. 조건 연산자 · 집계 함수를 고를 때 쓴다. */
  public enum ColKind { NUMBER, TEXT, DATE, OTHER }

  /** 집계 함수. COUNT_ALL 은 칼럼 없는 COUNT(*). */
  public enum Agg {
    COUNT("건수", "CNT"), COUNT_DISTINCT("서로 다른 값 건수", "CNT"), SUM("합계", "SUM"), AVG("평균", "AVG"), MAX("가장 큰 값", "MAX"), MIN("가장 작은 값", "MIN");

    public final String label;
    final String prefix;

    Agg(String label, String prefix) { this.label = label; this.prefix = prefix; }

    /** 합계 · 평균은 숫자 칼럼에만 맞다. */
    public boolean fits(ColKind kind) { return (this != SUM && this != AVG) || kind == ColKind.NUMBER; }

    String apply(String expr) {
      return switch (this) {
        case COUNT -> "COUNT(" + expr + ")";
        case COUNT_DISTINCT -> "COUNT(DISTINCT " + expr + ")";
        case AVG -> "ROUND(AVG(" + expr + "), 2)";
        default -> name() + "(" + expr + ")";
      };
    }
  }

  /** 조건 연산자. values 는 받는 값 수(0 · 1 · 2, LIST 는 쉼표로 여러 개). */
  public enum Op {
    EQ("같다 (=)", 1, EnumSet.allOf(ColKind.class)),
    NE("다르다 (<>)", 1, EnumSet.allOf(ColKind.class)),
    GT("크다 (>)", 1, EnumSet.of(ColKind.NUMBER, ColKind.DATE)),
    GE("크거나 같다 (>=)", 1, EnumSet.of(ColKind.NUMBER, ColKind.DATE)),
    LT("작다 (<)", 1, EnumSet.of(ColKind.NUMBER, ColKind.DATE)),
    LE("작거나 같다 (<=)", 1, EnumSet.of(ColKind.NUMBER, ColKind.DATE)),
    CONTAINS("글자가 들어 있다 (LIKE %값%)", 1, EnumSet.of(ColKind.TEXT, ColKind.OTHER)),
    STARTS("글자로 시작한다 (LIKE 값%)", 1, EnumSet.of(ColKind.TEXT, ColKind.OTHER)),
    ENDS("글자로 끝난다 (LIKE %값)", 1, EnumSet.of(ColKind.TEXT, ColKind.OTHER)),
    BETWEEN("사이 (BETWEEN)", 2, EnumSet.of(ColKind.NUMBER, ColKind.TEXT)),
    DATE_RANGE("기간 (시작일 ~ 끝일, 끝일 하루 전체 포함)", 2, EnumSet.of(ColKind.DATE)),
    IN("여러 값 중 하나 (IN)", LIST, EnumSet.allOf(ColKind.class)),
    NOT_IN("여러 값이 아님 (NOT IN)", LIST, EnumSet.allOf(ColKind.class)),
    IS_NULL("비어 있다 (IS NULL)", 0, EnumSet.allOf(ColKind.class)),
    NOT_NULL("비어 있지 않다 (IS NOT NULL)", 0, EnumSet.allOf(ColKind.class));

    public final String label;
    public final int values;
    final Set<ColKind> kinds;

    Op(String label, int values, Set<ColKind> kinds) { this.label = label; this.values = values; this.kinds = kinds; }

    public boolean fits(ColKind kind) { return kinds.contains(kind); }

    @Override public String toString() { return label; }
  }

  /** Op.values 의 「쉼표로 여러 개」. */
  public static final int LIST = -1;

  /** HAVING 비교 연산자(집계 결과는 숫자). */
  public static final List<String> COMPARE = List.of("=", "<>", ">", ">=", "<", "<=");

  public record Table(String name, String alias) {}

  /** 고른 칼럼. column 이 「*」 이고 agg 가 COUNT 면 COUNT(*). as 가 비면 집계는 자동 별칭(SUM_AMOUNT). */
  public record Col(String alias, String column, String type, String comment, Agg agg, String as) {
    public String expr(DbKind kind) {
      if ("*".equals(column)) return agg == null ? (alias == null ? "*" : alias + ".*") : "COUNT(*)";
      String base = alias + "." + Names.sql(kind, column);
      return agg == null ? base : agg.apply(base);
    }

    public String label(DbKind kind) {
      if (as != null && !as.isBlank()) return as.strip();
      if (agg == null) return null;
      return "*".equals(column) ? "CNT" : agg.prefix + "_" + Names.sql(kind, column).replace("\"", "");
    }
  }

  /** 연결 조건 한 쌍. right 쪽 표가 뒤에 나온다. outer 면 오른쪽 표에 맞는 행이 없어도 왼쪽 행을 남긴다. guessed 는 외래 키 없이 이름으로 짐작한 것. */
  public record Join(String leftAlias, String leftColumn, String rightAlias, String rightColumn, boolean outer, boolean guessed) {}

  /** 조건. value · value2 는 화면에 넣은 값(비면 이름 자리만 만든다). */
  public record Cond(String alias, String column, ColKind kind, Op op, String value, String value2) {}

  public record Having(Col col, String op, String value) {}

  public record Order(String expr, boolean desc) {}

  /** 작성기 전체 상태. limit 0 이면 페이징 없음. */
  public static final class Spec {
    public DbKind kind = DbKind.ORACLE;
    public JoinStyle style = JoinStyle.OLD;
    public Bind bind = Bind.COLON;
    public boolean distinct;
    public final List<Table> tables = new ArrayList<>();
    public final List<Col> cols = new ArrayList<>();
    /** 사용자가 5단계에서 직접 고른 묶을 칼럼(agg 는 null). SELECT 앞에도 놓는다. */
    public final List<Col> groups = new ArrayList<>();
    public final List<Join> joins = new ArrayList<>();
    public final List<Cond> conds = new ArrayList<>();
    public final List<Having> havings = new ArrayList<>();
    public final List<Order> orders = new ArrayList<>();
    public int limit;
    public int offset;

    /** PostgreSQL · MySQL 은 (+) 가 없다. */
    /** Oracle 밖에서는 옛 방식이어도 (+) 를 못 쓰므로 안쪽 조인만 콤마 · WHERE, 바깥 조인은 LEFT OUTER JOIN 으로 쓴다. */
    public JoinStyle effectiveStyle() { return style; }
  }

  private static final String FIRST = "       ";
  private static final String NEXT = "     , ";
  private static final String AND = "   AND ";
  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
  private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern NUMERIC_TYPE = Pattern.compile("(?i).*(NUMBER|NUMERIC|DECIMAL|INT|FLOAT|DOUBLE|REAL|SERIAL|MONEY).*");
  private static final Pattern DATE_TYPE = Pattern.compile("(?i).*(DATE|TIME).*");
  private static final Pattern TEXT_TYPE = Pattern.compile("(?i).*(CHAR|TEXT|CLOB|STRING).*");
  /** 날짜 값 · 이름 자리의 꼴(화면의 안내와 같다). */
  public static final String DATE_FORMAT = "YYYY-MM-DD";
  private static final String FROM_SUFFIX = "From";
  private static final String TO_SUFFIX = "To";
  private static final String LIST_SUFFIX = "List";
  /** Oracle ROWNUM 페이징의 행 번호 별칭. */
  static final String ROW_NUMBER = "RN";

  public static ColKind kindOf(String type) {
    if (type == null) return ColKind.OTHER;
    if (DATE_TYPE.matcher(type).matches()) return ColKind.DATE;
    if (NUMERIC_TYPE.matcher(type).matches()) return ColKind.NUMBER;
    if (TEXT_TYPE.matcher(type).matches()) return ColKind.TEXT;
    return ColKind.OTHER;
  }

  /** 표 이름 → 겹치지 않는 소문자 별칭(머리글자, 겹치면 2 · 3 …). used 에 이미 쓴 별칭을 넣어 둔다. */
  public static String aliasFor(String table, Set<String> used) {
    String base = Names.alias(table);
    String a = base;
    for (int n = 2; used.contains(a); n++) a = base + n;
    return a;
  }

  /**
   * SELECT 에 놓을 칼럼: 직접 고른 묶을 칼럼(고른 칼럼에 없는 것) → 고른 칼럼 순.
   * 묶을 칼럼을 골랐는데 집계가 하나도 없으면 COUNT(*) AS CNT 를 붙인다(값마다 몇 건).
   * 묶을 때 표.* 는 GROUP BY 와 맞지 않아 뺀다.
   */
  public static List<Col> selected(Spec s) {
    List<Col> out = new ArrayList<>();
    for (Col g : s.groups) {
      boolean there = false;
      for (Col c : s.cols) there |= c.agg() == null && c.column().equals(g.column()) && java.util.Objects.equals(c.alias(), g.alias());
      if (!there) out.add(g);
    }
    boolean agg = false;
    for (Col c : s.cols) {
      if (!s.groups.isEmpty() && c.agg() == null && "*".equals(c.column())) continue;
      out.add(c);
      agg |= c.agg() != null;
    }
    if (!s.groups.isEmpty() && !agg) out.add(new Col(null, "*", null, "", Agg.COUNT, null));
    return out;
  }

  /** 묶을 칼럼을 골랐거나 집계 · HAVING 이 있으면 집계 아닌 SELECT 칼럼이 모두 GROUP BY 에 들어간다(직접 고른 것이 앞). 아니면 빈 목록. */
  public static List<String> groupBy(Spec s) {
    List<Col> sel = selected(s);
    boolean grouped = !s.groups.isEmpty() || !s.havings.isEmpty();
    for (Col c : sel) grouped |= c.agg() != null;
    List<String> out = new ArrayList<>();
    if (!grouped) return out;
    for (Col c : sel) {
      if (c.agg() == null && !"*".equals(c.column()) && !out.contains(c.expr(s.kind))) out.add(c.expr(s.kind));
    }
    return out;
  }

  /** 화면에 보일 SQL. 값 자리가 MyBatis 면 부등호 줄을 CDATA 로 감싼다(매퍼 XML 에 그대로 붙인다). */
  public static String render(Spec s) {
    String sql = render(s, s.bind, true);
    return s.bind == Bind.MYBATIS ? cdata(sql) : sql;
  }

  private static final String CDATA_OPEN = "<![CDATA[ ";
  private static final String CDATA_CLOSE = " ]]>";

  /**
   * 문자열 · 주석 밖에 부등호(< · >)가 있는 줄을 <![CDATA[ … ]]> 로 감싼다. 들여쓰기는 밖에 둔다.
   * foreach 줄 · 이미 CDATA 인 줄 · -- 주석만 있는 줄은 두고, 줄 끝 -- 주석은 CDATA 밖으로 뺀다.
   */
  public static String cdata(String sql) {
    StringBuilder out = new StringBuilder();
    String[] lines = sql.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) out.append('\n');
      String line = lines[i];
      String masked = SqlParts.mask(line);
      boolean compare = masked.indexOf('<') >= 0 || masked.indexOf('>') >= 0;
      if (!compare || line.contains("<foreach") || line.contains("<![CDATA[")) {
        out.append(line);
        continue;
      }
      int body = 0;
      while (body < line.length() && line.charAt(body) == ' ') body++;
      int dash = commentStart(line, masked);
      String code = (dash < 0 ? line.substring(body) : line.substring(body, dash)).stripTrailing();
      String tail = dash < 0 ? "" : "  " + line.substring(dash);
      out.append(line, 0, body).append(CDATA_OPEN).append(code).append(CDATA_CLOSE).append(tail);
    }
    return out.toString();
  }

  /** 줄 끝 -- 주석의 자리(없으면 -1). mask 는 주석과 문자열 속을 공백으로 만드므로, -- 부터 줄 끝까지 가린 글이 모두 공백인 곳이 주석이다. */
  private static int commentStart(String line, String masked) {
    for (int i = 0; i + 1 < line.length(); i++) {
      if (line.charAt(i) == '-' && line.charAt(i + 1) == '-' && masked.substring(i).isBlank()) return i;
    }
    return -1;
  }

  /**
   * 건수 세기용 SQL: 값을 넣은 조건만 값 그대로 넣고(값이 빈 조건은 뺀다), 정렬 · 페이징은 뺀다. 표가 없으면 null.
   */
  public static String countSql(Spec s) {
    if (s.tables.isEmpty()) return null;
    Spec copy = copy(s);
    copy.conds.clear();
    for (Cond c : s.conds) {
      boolean filled = c.op().values == 0 || (c.value() != null && !c.value().isBlank() && (c.op().values != 2 || (c.value2() != null && !c.value2().isBlank())));
      if (filled) copy.conds.add(c);
    }
    copy.orders.clear();
    copy.limit = 0;
    copy.offset = 0;
    return "SELECT COUNT(*) AS CNT\n  FROM (\n" + indent(render(copy, Bind.VALUE, false), "        ") + "\n       ) q";
  }

  /** 건수 세기에서 뺀 조건 수(값이 비어 있는 것). */
  public static int unfilled(Spec s) {
    int n = 0;
    for (Cond c : s.conds) {
      if (c.op().values != 0 && (c.value() == null || c.value().isBlank())) n++;
    }
    return n;
  }

  private static Spec copy(Spec s) {
    Spec c = new Spec();
    c.kind = s.kind;
    c.style = s.style;
    c.bind = s.bind;
    c.distinct = s.distinct;
    c.tables.addAll(s.tables);
    c.cols.addAll(s.cols);
    c.groups.addAll(s.groups);
    c.joins.addAll(s.joins);
    c.conds.addAll(s.conds);
    c.havings.addAll(s.havings);
    c.orders.addAll(s.orders);
    c.limit = s.limit;
    c.offset = s.offset;
    return c;
  }

  private static String render(Spec s, Bind bind, boolean paging) {
    if (s.tables.isEmpty()) return "";
    StringBuilder sb = new StringBuilder(s.distinct ? "SELECT DISTINCT\n" : "SELECT\n");
    List<String> items = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    for (Col c : selected(s)) {
      String label = c.label(s.kind);
      items.add(label == null ? c.expr(s.kind) : c.expr(s.kind) + " AS " + label);
      notes.add(c.comment() == null ? "" : c.comment());
    }
    if (items.isEmpty()) {
      for (Table t : s.tables) {
        items.add(t.alias() + ".*");
        notes.add("");
      }
    }
    leading(sb, items, notes);

    boolean old = s.effectiveStyle() == JoinStyle.OLD;
    List<String> where = new ArrayList<>();
    List<String> whereNotes = new ArrayList<>();
    List<String> chains = old && s.kind != DbKind.ORACLE ? commaChains(s, where, whereNotes) : null;
    if (chains != null) {
      sb.append("  FROM ").append(chains.get(0)).append('\n');
      for (int i = 1; i < chains.size(); i++) sb.append(NEXT).append(chains.get(i)).append('\n');
    } else if (old && s.kind == DbKind.ORACLE) {
      List<String> from = new ArrayList<>();
      for (Table t : s.tables) from.add(Names.sql(s.kind, t.name()) + " " + t.alias());
      sb.append("  FROM ").append(from.get(0)).append('\n');
      for (int i = 1; i < from.size(); i++) sb.append(NEXT).append(from.get(i)).append('\n');
      for (Join j : s.joins) {
        where.add(j.leftAlias() + "." + Names.sql(s.kind, j.leftColumn()) + " = " + j.rightAlias() + "." + Names.sql(s.kind, j.rightColumn()) + (j.outer() ? "(+)" : ""));
        whereNotes.add(j.guessed() ? "추정 연결: 맞는지 확인하세요" : "");
      }
    } else {
      sb.append("  FROM ").append(Names.sql(s.kind, s.tables.get(0).name())).append(' ').append(s.tables.get(0).alias()).append('\n');
      for (int i = 1; i < s.tables.size(); i++) {
        Table t = s.tables.get(i);
        List<Join> mine = joinsOf(s, t.alias());
        boolean outer = false;
        for (Join j : mine) outer |= j.outer();
        String head = mine.isEmpty() ? "  CROSS JOIN " : outer ? "  LEFT OUTER JOIN " : "  INNER JOIN ";
        sb.append(head).append(Names.sql(s.kind, t.name())).append(' ').append(t.alias());
        if (mine.isEmpty()) sb.append("  -- 연결 조건이 없어 모든 조합이 나옵니다");
        sb.append('\n');
        for (int k = 0; k < mine.size(); k++) {
          Join j = mine.get(k);
          sb.append(k == 0 ? "    ON " : AND)
              .append(j.leftAlias()).append('.').append(Names.sql(s.kind, j.leftColumn())).append(" = ")
              .append(j.rightAlias()).append('.').append(Names.sql(s.kind, j.rightColumn()));
          if (j.guessed()) sb.append("  -- 추정 연결: 맞는지 확인하세요");
          sb.append('\n');
        }
      }
    }
    Set<String> names = new HashSet<>();
    for (Cond c : s.conds) {
      where.add(condition(s.kind, bind, c, names));
      whereNotes.add("");
    }
    sb.append(" WHERE 1 = 1\n");
    lines(sb, where, whereNotes);

    List<String> group = groupBy(s);
    if (!group.isEmpty()) {
      sb.append(" GROUP BY\n");
      leading(sb, group, blanks(group.size()));
    }
    if (!s.havings.isEmpty()) {
      List<String> having = new ArrayList<>();
      for (Having h : s.havings) having.add(h.col().expr(s.kind) + " " + h.op() + " " + number(h.value()));
      sb.append(" HAVING ").append(having.get(0)).append('\n');
      for (int i = 1; i < having.size(); i++) sb.append(AND).append(having.get(i)).append('\n');
    }
    if (!s.orders.isEmpty()) {
      List<String> order = new ArrayList<>();
      for (Order o : s.orders) order.add(o.expr() + (o.desc() ? " DESC" : ""));
      sb.append(" ORDER BY\n");
      leading(sb, order, blanks(order.size()));
    }
    String sql = sb.toString().stripTrailing();
    return paging && s.limit > 0 ? page(s.kind, sql, s.offset, s.limit) : sql;
  }

  /**
   * (+) 가 없는 DB 의 옛 방식 FROM. 바깥 조인 표는 짝 표 바로 뒤에 LEFT OUTER JOIN 으로 붙이고, 나머지는 콤마로 늘어놓는다.
   * 콤마보다 JOIN 이 먼저 묶여 ON 은 같은 묶음 안의 표만 부를 수 있으므로, 바깥 조인 표의 짝이 여러 묶음에 걸치면 null(표준 JOIN 으로 쓴다).
   * 성공하면 안쪽 조인 조건을 where 에 더한다.
   */
  private static List<String> commaChains(Spec s, List<String> where, List<String> whereNotes) {
    List<StringBuilder> chains = new ArrayList<>();
    Map<String, Integer> chainOf = new HashMap<>();
    Set<String> outerTables = new HashSet<>();
    for (int i = 0; i < s.tables.size(); i++) {
      Table t = s.tables.get(i);
      String from = Names.sql(s.kind, t.name()) + " " + t.alias();
      List<Join> mine = i == 0 ? List.of() : joinsOf(s, t.alias());
      boolean outer = false;
      for (Join j : mine) outer |= j.outer();
      if (!outer) {
        chainOf.put(t.alias(), chains.size());
        chains.add(new StringBuilder(from));
        continue;
      }
      Integer chain = null;
      for (Join j : mine) {
        Integer c = chainOf.get(j.leftAlias());
        if (c == null || (chain != null && !chain.equals(c))) return null;
        chain = c;
      }
      StringBuilder sb = chains.get(chain).append("\n  LEFT OUTER JOIN ").append(from);
      for (int k = 0; k < mine.size(); k++) {
        Join j = mine.get(k);
        sb.append('\n').append(k == 0 ? "    ON " : AND)
            .append(j.leftAlias()).append('.').append(Names.sql(s.kind, j.leftColumn())).append(" = ")
            .append(j.rightAlias()).append('.').append(Names.sql(s.kind, j.rightColumn()));
        if (j.guessed()) sb.append("  -- 추정 연결: 맞는지 확인하세요");
      }
      chainOf.put(t.alias(), chain);
      outerTables.add(t.alias());
    }
    for (Join j : s.joins) {
      if (outerTables.contains(j.rightAlias())) continue;
      where.add(j.leftAlias() + "." + Names.sql(s.kind, j.leftColumn()) + " = " + j.rightAlias() + "." + Names.sql(s.kind, j.rightColumn()));
      whereNotes.add(j.guessed() ? "추정 연결: 맞는지 확인하세요" : "");
    }
    List<String> out = new ArrayList<>();
    for (StringBuilder c : chains) out.add(c.toString());
    return out;
  }

  /** 오른쪽 표가 alias 인 연결들. */
  private static List<Join> joinsOf(Spec s, String alias) {
    List<Join> out = new ArrayList<>();
    for (Join j : s.joins) if (j.rightAlias().equals(alias)) out.add(j);
    return out;
  }

  /** 앞에서 offset 건 건너뛰고 limit 건. Oracle 은 판에 상관없이 되는 ROWNUM 감싸기, 그 밖은 LIMIT · OFFSET. */
  public static String page(DbKind kind, String sql, int offset, int limit) {
    if (kind != DbKind.ORACLE) return sql + "\n LIMIT " + limit + (offset > 0 ? " OFFSET " + offset : "");
    if (offset <= 0) return "SELECT *\n  FROM (\n" + indent(sql, "        ") + "\n       )\n WHERE ROWNUM <= " + limit;
    return "SELECT *\n  FROM (\n        SELECT q.*\n             , ROWNUM AS " + ROW_NUMBER + "\n          FROM (\n" + indent(sql, "                ")
        + "\n               ) q\n         WHERE ROWNUM <= " + (offset + limit) + "\n       )\n WHERE " + ROW_NUMBER + " > " + offset;
  }

  /** 조건 한 줄. names 는 이미 쓴 이름 자리(같은 칼럼 조건이 둘이면 뒤에 2 를 붙인다). */
  static String condition(DbKind kind, Bind bind, Cond c, Set<String> names) {
    String col = c.alias() + "." + Names.sql(kind, c.column());
    String name = unique(Names.camel(c.column()), names);
    return switch (c.op()) {
      case EQ -> col + " = " + value(kind, bind, c.kind(), name, c.value());
      case NE -> col + " <> " + value(kind, bind, c.kind(), name, c.value());
      case GT -> col + " > " + value(kind, bind, c.kind(), name, c.value());
      case GE -> col + " >= " + value(kind, bind, c.kind(), name, c.value());
      case LT -> col + " < " + value(kind, bind, c.kind(), name, c.value());
      case LE -> col + " <= " + value(kind, bind, c.kind(), name, c.value());
      case CONTAINS -> col + " LIKE " + like(bind, name, c.value(), true, true);
      case STARTS -> col + " LIKE " + like(bind, name, c.value(), false, true);
      case ENDS -> col + " LIKE " + like(bind, name, c.value(), true, false);
      case BETWEEN -> col + " BETWEEN " + value(kind, bind, c.kind(), name + FROM_SUFFIX, c.value())
          + " AND " + value(kind, bind, c.kind(), name + TO_SUFFIX, c.value2());
      case DATE_RANGE -> col + " >= " + value(kind, bind, ColKind.DATE, name + FROM_SUFFIX, c.value())
          + "\n" + AND + col + " < " + value(kind, bind, ColKind.DATE, name + TO_SUFFIX, c.value2()) + " + 1";
      case IN, NOT_IN -> col + (c.op() == Op.NOT_IN ? " NOT IN " : " IN ") + list(kind, bind, c.kind(), name + LIST_SUFFIX, c.value());
      case IS_NULL -> col + " IS NULL";
      case NOT_NULL -> col + " IS NOT NULL";
    };
  }

  private static String unique(String name, Set<String> names) {
    String n = name;
    for (int i = 2; !names.add(n); i++) n = name + i;
    return n;
  }

  /** 값 하나: Bind.VALUE 면 넣은 값을 글자 그대로, 아니면 이름 자리(넣은 값은 건수 세기에만 쓴다). 날짜는 YYYY-MM-DD 로 받는다. */
  static String value(DbKind kind, Bind bind, ColKind ck, String name, String raw) {
    if (bind != Bind.VALUE) {
      String place = bind == Bind.MYBATIS ? "#{" + name + "}" : ":" + name;
      if (ck != ColKind.DATE) return place;
      return kind == DbKind.ORACLE ? "TO_DATE(" + place + ", '" + DATE_FORMAT + "')" : "CAST(" + place + " AS DATE)";
    }
    return literal(ck, raw == null ? "" : raw.strip());
  }

  /** 글자 그대로의 값. 숫자 칼럼의 숫자는 따옴표 없이, 날짜는 DATE 'YYYY-MM-DD'(꼴이 다르면 따옴표 글자), 나머지는 '' 로 감싼다. */
  public static String literal(ColKind ck, String raw) {
    if (ck == ColKind.NUMBER && NUMBER.matcher(raw).matches()) return raw;
    if (ck == ColKind.DATE && DATE.matcher(raw).matches()) return "DATE '" + raw + "'";
    return quote(raw);
  }

  public static String quote(String raw) {
    return "'" + raw.replace("'", "''") + "'";
  }

  private static String number(String raw) {
    String v = raw == null ? "" : raw.strip();
    return NUMBER.matcher(v).matches() ? v : quote(v);
  }

  private static String like(Bind bind, String name, String raw, boolean before, boolean after) {
    if (bind == Bind.VALUE) {
      String v = raw == null ? "" : raw.strip();
      return quote((before ? "%" : "") + v + (after ? "%" : ""));
    }
    String place = bind == Bind.MYBATIS ? "#{" + name + "}" : ":" + name;
    return (before ? "'%' || " : "") + place + (after ? " || '%'" : "");
  }

  /** IN 목록. MyBatis 는 foreach, 그 밖에는 넣은 값(쉼표로 나눔)을 글자 그대로(콘솔 이름 자리는 값 하나만 받는다), 값이 없으면 이름 자리 하나. */
  private static String list(DbKind kind, Bind bind, ColKind ck, String name, String raw) {
    List<String> values = new ArrayList<>();
    if (raw != null) {
      for (String v : raw.split(",")) if (!v.isBlank()) values.add(literal(ck, v.strip()));
    }
    if (bind == Bind.MYBATIS) {
      return "<foreach collection=\"" + name + "\" item=\"item\" open=\"(\" separator=\", \" close=\")\">#{item}</foreach>";
    }
    if (values.isEmpty()) return "(" + value(kind, bind == Bind.VALUE ? Bind.COLON : bind, ck, name, null) + ")";
    return "(" + String.join(", ", values) + ")";
  }

  /**
   * 고쳐야 할 것 · 확인할 것(한국어 한 줄씩): 다른 표와 이어지지 않은 표, 추정 연결, 집계에 안 맞는 형식,
   * 묶은 뒤 정렬할 수 없는 칼럼, 바깥 조인한 표에 건 WHERE 조건(옛 방식에서 안쪽 조인이 된다).
   */
  public static List<String> problems(Spec s) {
    List<String> out = new ArrayList<>();
    Map<String, Set<String>> graph = new LinkedHashMap<>();
    for (Table t : s.tables) graph.put(t.alias(), new HashSet<>());
    Set<String> outerTables = new HashSet<>();
    boolean guessed = false;
    for (Join j : s.joins) {
      if (graph.containsKey(j.leftAlias()) && graph.containsKey(j.rightAlias())) {
        graph.get(j.leftAlias()).add(j.rightAlias());
        graph.get(j.rightAlias()).add(j.leftAlias());
      }
      if (j.outer()) outerTables.add(j.rightAlias());
      guessed |= j.guessed();
    }
    if (s.tables.size() > 1) {
      Set<String> reached = reach(graph, s.tables.get(0).alias());
      for (Table t : s.tables) {
        if (!reached.contains(t.alias())) out.add("표 " + t.name() + " (" + t.alias() + ") 가 다른 표와 이어지지 않았습니다. 「연결」 단계에서 조건을 넣지 않으면 모든 조합(곱하기)이 나옵니다.");
      }
    }
    if (guessed) out.add("「추정」 연결은 외래 키 없이 칼럼 이름으로 짐작한 것입니다. 맞는지 확인하세요.");
    for (Col c : s.cols) {
      if (c.agg() != null && !"*".equals(c.column()) && !c.agg().fits(kindOf(c.type()))) {
        out.add(c.alias() + "." + c.column() + " 는 숫자 칼럼이 아니라 " + c.agg().label + " 를 낼 수 없습니다.");
      }
    }
    List<String> group = groupBy(s);
    if (!group.isEmpty()) {
      Set<String> allowed = new LinkedHashSet<>(group);
      for (Col c : selected(s)) {
        if (c.agg() != null) {
          allowed.add(c.expr(s.kind));
          if (c.label(s.kind) != null) allowed.add(c.label(s.kind));
        }
      }
      for (Order o : s.orders) {
        if (!allowed.contains(o.expr())) out.add("묶은 뒤에는 " + o.expr() + " 로 정렬할 수 없습니다. 묶은 칼럼이나 집계로 정렬하세요.");
      }
    }
    if (s.effectiveStyle() == JoinStyle.OLD) {
      for (Cond c : s.conds) {
        if (outerTables.contains(c.alias()) && c.op() != Op.IS_NULL) {
          out.add(c.alias() + "." + c.column() + " 조건 때문에 바깥 조인이 안쪽 조인처럼 됩니다(맞는 행이 없는 줄이 빠집니다). 빠져도 되면 그대로 두세요.");
        }
      }
    }
    return out;
  }

  private static Set<String> reach(Map<String, Set<String>> graph, String start) {
    Set<String> seen = new HashSet<>();
    List<String> todo = new ArrayList<>(List.of(start));
    while (!todo.isEmpty()) {
      String a = todo.remove(todo.size() - 1);
      if (!seen.add(a)) continue;
      todo.addAll(graph.getOrDefault(a, Set.of()));
    }
    return seen;
  }

  /** 앞 쉼표 목록. 주석은 같은 열에 맞춘다(줄바꿈은 공백으로). */
  static void leading(StringBuilder sb, List<String> items, List<String> comments) {
    int width = 0;
    for (String item : items) width = Math.max(width, NEXT.length() + item.length());
    for (int i = 0; i < items.size(); i++) {
      String line = (i == 0 ? FIRST : NEXT) + items.get(i);
      String comment = comments.get(i).replaceAll("\\s+", " ").trim();
      sb.append(comment.isEmpty() ? line : pad(line, width) + "  -- " + comment).append('\n');
    }
  }

  /** 모든 줄을 「   AND 」 로 시작한다. */
  private static void lines(StringBuilder sb, List<String> items, List<String> comments) {
    int width = 0;
    for (String item : items) width = Math.max(width, item.length());
    for (int i = 0; i < items.size(); i++) {
      String comment = comments.get(i);
      sb.append(AND).append(comment.isEmpty() ? items.get(i) : pad(items.get(i), width) + "  -- " + comment).append('\n');
    }
  }

  private static List<String> blanks(int n) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) out.add("");
    return out;
  }

  private static String pad(String s, int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }

  /** 모든 줄 앞에 prefix. */
  public static String indent(String text, String prefix) {
    StringBuilder sb = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(line.isEmpty() ? "" : prefix + line);
    }
    return sb.toString();
  }
}
