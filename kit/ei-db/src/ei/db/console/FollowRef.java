package ei.db.console;

import ei.db.DbKind;
import ei.db.Meta;
import ei.db.QueryResult;
import ei.db.bench.JoinAdvisor;
import ei.db.bench.QueryBuilder;
import ei.db.bench.SqlParts;
import ei.db.gen.Names;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 결과 칸의 값이 가리키는 행 찾기(「참조 따라가기」)의 대상 판정과 SELECT 만들기. IntelliJ 에 기대지 않는다.
 * 선언된 외래 키(가리키는 표 · 이 값을 가리키는 표)를 먼저 쓰고, 하나도 없을 때만 이름으로 짐작한다(JoinAdvisor 의 규칙).
 * shortcut: 외래 키의 상대 표가 다른 스키마에 있어도 조회한 표와 같은 스키마로 본다. 스키마를 넘는 외래 키를 따라가야 하면 Meta.ForeignKey 에 스키마를 더한다.
 */
final class FollowRef {
  private FollowRef() {}

  /** REFERENCE: 이 값이 가리키는 행, REFERRED: 이 값을 가리키는 행들, GUESS: 이름으로 짐작한 표의 행. */
  enum Kind { REFERENCE, REFERRED, GUESS }

  /** 열 곳. table · columns 는 메타 이름 그대로, from 은 columns 와 같은 순서로 조건 값이 든 결과 칼럼 번호. */
  record Target(Kind kind, String table, List<String> columns, List<Integer> from) {}

  private static final String SELECT_LEAD = "       ";
  private static final String ALL = "*";
  /** 짐작할 때 JoinAdvisor 에 넘기는 「지금 결과」 쪽 표의 자리 이름과 두 표의 별칭. */
  private static final String SELF = "";
  private static final String SELF_ALIAS = "a";
  private static final String OTHER_ALIAS = "b";

  /** 조회 문장에 나온 표가 꼭 하나면 그 표, 아니면(조인 · 하위 조회의 다른 표 · 표 없음) null. */
  static SqlParts.TableRef singleTable(String sql) {
    List<SqlParts.TableRef> tables = SqlParts.tables(sql);
    return tables.size() == 1 ? tables.get(0) : null;
  }

  /** 조건 값으로 쓸 수 있는 칸인지: null · 이진 · 잘린 LOB 글 · LOB 형식 칼럼이 아니어야 한다. */
  static boolean followable(Object value, String type) {
    return value != null && !(value instanceof QueryResult.BinaryValue) && !SqlText.isLobValue(value) && !SqlText.isUneditableType(type);
  }

  /**
   * 선언된 외래 키로 찾은 대상. columns 는 결과 칼럼 이름, col 은 누른 칼럼 번호, row 는 그 행의 값.
   * imported 는 조회한 표의 외래 키, exported 는 조회한 표를 가리키는 외래 키.
   * 여러 칼럼 외래 키는 결과에 있고 값이 조건으로 쓸 수 있는 칼럼만 조건에 넣는다(누른 칼럼은 늘 들어간다).
   * 이름 없는 외래 키는 칼럼마다 따로 본다.
   */
  static List<Target> declared(List<String> columns, int col, List<Object> row, List<Meta.ForeignKey> imported, List<Meta.ForeignKey> exported) {
    List<Target> out = new ArrayList<>();
    collect(out, Kind.REFERENCE, columns, col, row, imported);
    collect(out, Kind.REFERRED, columns, col, row, exported);
    return out;
  }

  private static void collect(List<Target> out, Kind kind, List<String> columns, int col, List<Object> row, List<Meta.ForeignKey> fks) {
    boolean forward = kind == Kind.REFERENCE;
    Map<String, List<Meta.ForeignKey>> groups = new LinkedHashMap<>();
    for (Meta.ForeignKey fk : fks) {
      String other = forward ? fk.pkTable() : fk.fkTable();
      String key = other + "|" + (fk.name().isEmpty() ? fk.fkColumn() : fk.name());
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(fk);
    }
    for (List<Meta.ForeignKey> group : groups.values()) {
      List<String> names = new ArrayList<>();
      List<Integer> from = new ArrayList<>();
      boolean clicked = false;
      for (Meta.ForeignKey fk : group) {
        String mine = forward ? fk.fkColumn() : fk.pkColumn();
        String theirs = forward ? fk.pkColumn() : fk.fkColumn();
        if (names.contains(theirs)) continue;
        boolean here = mine.equalsIgnoreCase(columns.get(col));
        int index = here ? col : indexOf(columns, mine);
        if (index < 0 || !followable(row.get(index), null)) continue;
        clicked |= here;
        names.add(theirs);
        from.add(index);
      }
      if (clicked) out.add(new Target(kind, forward ? group.get(0).pkTable() : group.get(0).fkTable(), names, from));
    }
  }

  static int indexOf(List<String> columns, String name) {
    for (int i = 0; i < columns.size(); i++) if (columns.get(i).equalsIgnoreCase(name)) return i;
    return -1;
  }

  /**
   * 이름으로 짐작한 대상: PK 가 칼럼 하나인 표 가운데 JoinAdvisor 가 이 칼럼과 잇는 표
   * (PK 이름이 칼럼 이름과 같거나, PK 가 ID 이고 칼럼이 「표 이름 끝 토막_ID」). own(조회한 표, 모르면 null)은 뺀다.
   * pks 는 표 이름 → PK 칼럼들. 순서는 pks 의 순서.
   */
  static List<Target> guessed(String column, int col, String own, Map<String, List<String>> pks) {
    List<Target> out = new ArrayList<>();
    List<Meta.Column> mine = List.of(column(column, false));
    for (Map.Entry<String, List<String>> e : pks.entrySet()) {
      String table = e.getKey();
      if (e.getValue().size() != 1 || table.isEmpty() || table.equalsIgnoreCase(own)) continue;
      String pk = e.getValue().get(0);
      List<QueryBuilder.Join> joins = JoinAdvisor.suggest(
          List.of(new QueryBuilder.Table(SELF, SELF_ALIAS), new QueryBuilder.Table(table, OTHER_ALIAS)),
          Map.of(JoinAdvisor.key(SELF), mine, JoinAdvisor.key(table), List.of(column(pk, true))), List.of());
      if (!joins.isEmpty()) out.add(new Target(Kind.GUESS, table, List.of(pk), List.of(col)));
    }
    return out;
  }

  private static Meta.Column column(String name, boolean pk) {
    return new Meta.Column(name, "", 0, 0, pk, false, !pk, "");
  }

  /** 칸 하나를 다시 읽는 문장과 그 바인드 값. */
  record CellQuery(String sql, List<Object> binds) {}

  /**
   * 결과의 col 칸을 그 행의 PK 로 다시 읽는 문장(「값 크게 보기」의 전체 읽기). table · defs 는 표 정의(메타 이름).
   * PK 가 없거나, PK 칼럼이 결과에 없거나, 그 값이 조건으로 쓸 수 없거나(null · 이진 · LOB), 누른 칼럼이 표에 없으면 null.
   */
  static CellQuery cellQuery(DbKind kind, String table, List<Meta.Column> defs, List<String> columns, List<String> types, List<Object> row, int col) {
    String item = null;
    List<String> keys = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    for (Meta.Column d : defs) {
      if (d.name().equalsIgnoreCase(columns.get(col))) item = d.name();
      if (!d.pk()) continue;
      int index = indexOf(columns, d.name());
      if (index < 0 || !followable(row.get(index), index < types.size() ? types.get(index) : null)) return null;
      keys.add(d.name());
      binds.add(row.get(index));
    }
    return item == null || keys.isEmpty() ? null : new CellQuery(select(kind, null, table, item, keys), binds);
  }

  /**
   * 「SELECT / item / FROM 표 / WHERE 칼럼 = ? AND …」. item 이 null 이면 「*」, schema 가 null 이면 표 이름만 쓴다.
   * 이름은 메타 이름을 받아 Names.sql 표기로 쓴다. 값 자리는 where 순서대로 ? 이다. where 가 비면 IllegalArgumentException.
   */
  static String select(DbKind kind, String schema, String table, String item, List<String> where) {
    if (where.isEmpty()) throw new IllegalArgumentException("조건 칼럼이 없습니다.");
    StringBuilder sb = new StringBuilder("SELECT\n").append(SELECT_LEAD).append(item == null ? ALL : Names.sql(kind, item)).append('\n');
    sb.append("  FROM ").append(schema == null ? Names.sql(kind, table) : Names.sql(kind, schema, table)).append('\n');
    for (int i = 0; i < where.size(); i++) {
      sb.append(i == 0 ? " WHERE " : "\n   AND ").append(Names.sql(kind, where.get(i))).append(" = ?");
    }
    return sb.toString();
  }
}
