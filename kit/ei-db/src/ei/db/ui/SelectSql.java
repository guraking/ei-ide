package ei.db.ui;

import ei.db.Meta;
import ei.db.gen.Names;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * 탐색기 「SELECT · INSERT · UPDATE 문 만들기」의 본문. 칼럼마다 한 줄이고 쉼표는 줄 앞(「     , 」)에 둔다.
 * 주석이 있으면 줄 끝에 `-- 주석` 을 같은 열에 맞춰 붙인다.
 * 스키마 · 표 · 칼럼 이름은 대문자, 별칭은 소문자로 쓴다(따옴표 없는 이름은 Oracle 은 그대로, PostgreSQL 은 소문자로 접어 같은 표를 가리킨다).
 * 값 자리는 NULL 로 두고, 조건을 바로 이어 쓰도록 문장 끝 ; 은 붙이지 않는다.
 */
final class SelectSql {
  private SelectSql() {}

  private static final String ALIAS = "t";
  private static final String VALUE = "NULL";

  static String build(String schema, String table, List<Meta.Column> columns) {
    StringBuilder sb = new StringBuilder("SELECT\n");
    if (columns.isEmpty()) {
      sb.append(LEAD_FIRST).append(ALIAS).append(".*\n");
    } else {
      List<String> items = new ArrayList<>();
      List<String> comments = new ArrayList<>();
      for (Meta.Column col : columns) {
        items.add(ALIAS + "." + up(col.name()));
        comments.add(col.comment());
      }
      leading(sb, LEAD_FIRST, items, comments);
    }
    sb.append("  FROM ").append(up(schema)).append('.').append(up(table)).append(' ').append(ALIAS).append('\n');
    sb.append(" WHERE 1 = 1");
    return sb.toString();
  }

  /** 모든 칼럼에 NULL 을 넣는 INSERT. 값 줄 끝에는 칼럼 이름 · 형식을 달아 어느 값인지 보이게 한다. */
  static String insert(String schema, String table, List<Meta.Column> columns) {
    List<String> names = new ArrayList<>();
    List<String> comments = new ArrayList<>();
    List<String> values = new ArrayList<>();
    List<String> valueNotes = new ArrayList<>();
    for (Meta.Column col : columns) {
      names.add(up(col.name()));
      comments.add(col.comment());
      values.add(VALUE);
      valueNotes.add(up(col.name()) + " " + col.typeLabel());
    }
    StringBuilder sb = new StringBuilder("INSERT INTO ").append(up(schema)).append('.').append(up(table)).append(" (\n");
    leading(sb, LEAD_FIRST, names, comments);
    sb.append(") VALUES (\n");
    leading(sb, LEAD_FIRST, values, valueNotes);
    sb.append(")");
    return sb.toString();
  }

  /** PK 가 아닌 칼럼은 SET, PK 칼럼은 WHERE 조건으로. PK 가 없으면 조건을 직접 쓰라는 줄을 남긴다. */
  static String update(String schema, String table, List<Meta.Column> columns) {
    List<String> sets = new ArrayList<>();
    List<String> setNotes = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    List<String> keyNotes = new ArrayList<>();
    for (Meta.Column col : columns) {
      String item = ALIAS + "." + up(col.name()) + " = " + VALUE;
      if (col.pk()) { keys.add(item); keyNotes.add(col.comment()); }
      else { sets.add(item); setNotes.add(col.comment()); }
    }
    StringBuilder sb = new StringBuilder("UPDATE ").append(up(schema)).append('.').append(up(table)).append(' ').append(ALIAS).append('\n');
    if (sets.isEmpty()) sb.append("   SET -- 바꿀 칼럼이 없습니다(모두 PK)\n");
    else leading(sb, "   SET ", sets, setNotes);
    sb.append(" WHERE 1 = 1");
    if (keys.isEmpty()) {
      sb.append("\n   -- PK 가 없습니다. 조건을 직접 쓰세요");
    } else {
      sb.append('\n');
      lines(sb, "   AND ", keys, keyNotes);
      sb.setLength(sb.length() - 1);
    }
    return sb.toString();
  }

  /** 앞 쉼표 목록의 줄 머리: 첫 칼럼은 키워드 다음 줄 7칸, 다음 칼럼부터 「     , 」. */
  private static final String LEAD_FIRST = "       ";
  private static final String LEAD_NEXT = "     , ";
  static final String GUESS_NOTE = "이름이 같은 칼럼으로 추정 (FK 없음)";

  /**
   * 여러 표의 조인 SELECT(구식 조인: FROM 에 표를 나열하고 WHERE 에 조건). 표마다 별칭을 붙이고 모든 칼럼을 앞 쉼표로 나열한다.
   * 조건은 고른 표끼리의 FK 로 만들고, 두 표 사이에 FK 가 없으면 한쪽의 PK 칼럼과 이름이 같은 칼럼을 찾아 추정 조건을 단다.
   * 그래도 못 이은 표는 조건을 직접 쓰라는 주석 줄을 남긴다. tables 는 고른 순서, columns · fks 는 표 이름(대소문자 그대로) 기준.
   */
  static String join(String schema, List<String> tables, Map<String, List<Meta.Column>> columns, List<Meta.ForeignKey> fks) {
    Map<String, String> alias = aliases(tables);
    List<String> items = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    for (String t : tables) {
      for (Meta.Column col : columns.getOrDefault(t, List.of())) {
        items.add(alias.get(t) + "." + up(col.name()));
        notes.add(col.comment());
      }
    }
    StringBuilder sb = new StringBuilder("SELECT\n");
    leading(sb, LEAD_FIRST, items, notes);
    List<String> from = new ArrayList<>();
    for (String t : tables) from.add(up(schema) + "." + up(t) + " " + alias.get(t));
    sb.append("  FROM ").append(String.join(", ", from)).append('\n');
    sb.append(" WHERE 1 = 1");

    List<String> conds = new ArrayList<>();
    List<String> condNotes = new ArrayList<>();
    Set<String> linked = new HashSet<>();
    for (Meta.ForeignKey fk : fks) {
      if (!alias.containsKey(fk.fkTable()) || !alias.containsKey(fk.pkTable()) || fk.fkTable().equals(fk.pkTable())) continue;
      conds.add(alias.get(fk.fkTable()) + "." + up(fk.fkColumn()) + " = " + alias.get(fk.pkTable()) + "." + up(fk.pkColumn()));
      condNotes.add(fk.name().isEmpty() ? "FK" : "FK " + fk.name());
      linked.add(pair(fk.fkTable(), fk.pkTable()));
    }
    for (int i = 0; i < tables.size(); i++) {
      for (int j = i + 1; j < tables.size(); j++) {
        String a = tables.get(i);
        String b = tables.get(j);
        if (linked.contains(pair(a, b))) continue;
        int before = conds.size();
        guess(a, b, columns, alias, conds, condNotes);
        guess(b, a, columns, alias, conds, condNotes);
        if (conds.size() > before) linked.add(pair(a, b));
      }
    }
    if (!conds.isEmpty()) {
      sb.append('\n');
      lines(sb, "   AND ", conds, condNotes);
      sb.setLength(sb.length() - 1);
    }
    for (int i = 1; i < tables.size(); i++) {
      String t = tables.get(i);
      boolean any = false;
      for (String other : tables) any |= !other.equals(t) && linked.contains(pair(t, other));
      if (!any) sb.append("\n   -- ").append(up(t)).append(" 와 이을 조건을 찾지 못했습니다. 직접 쓰세요");
    }
    return sb.toString();
  }

  /** parent 의 PK 칼럼과 이름이 같은 칼럼이 child 에 있으면 child.칼럼 = parent.칼럼. 이미 같은 조건이 있으면 넣지 않는다. */
  private static void guess(String child, String parent, Map<String, List<Meta.Column>> columns, Map<String, String> alias,
                            List<String> conds, List<String> notes) {
    Set<String> childNames = new HashSet<>();
    for (Meta.Column c : columns.getOrDefault(child, List.of())) childNames.add(up(c.name()));
    for (Meta.Column p : columns.getOrDefault(parent, List.of())) {
      if (!p.pk() || !childNames.contains(up(p.name()))) continue;
      String cond = alias.get(child) + "." + up(p.name()) + " = " + alias.get(parent) + "." + up(p.name());
      String reverse = alias.get(parent) + "." + up(p.name()) + " = " + alias.get(child) + "." + up(p.name());
      if (conds.contains(cond) || conds.contains(reverse)) continue;
      conds.add(cond);
      notes.add(GUESS_NOTE);
    }
  }

  private static String pair(String a, String b) {
    return a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a;
  }

  /** Names.alias(소문자 머리글자)를 쓰고, 겹치면 뒤에 2 · 3 … 을 붙인다. */
  private static Map<String, String> aliases(List<String> tables) {
    Map<String, String> out = new LinkedHashMap<>();
    Set<String> used = new HashSet<>();
    for (String t : tables) {
      String base = Names.alias(t);
      String a = base;
      for (int n = 2; !used.add(a); n++) a = base + n;
      out.put(t, a);
    }
    return out;
  }

  /** 앞 쉼표 목록. 첫 줄은 first(LEAD_NEXT 와 같은 폭), 다음 줄부터 LEAD_NEXT. 주석은 같은 열에 맞춘다(줄바꿈은 공백으로). */
  private static void leading(StringBuilder sb, String first, List<String> items, List<String> comments) {
    int width = 0;
    for (String item : items) width = Math.max(width, LEAD_NEXT.length() + item.length());
    for (int i = 0; i < items.size(); i++) {
      String line = (i == 0 ? first : LEAD_NEXT) + items.get(i);
      String comment = comments.get(i).replaceAll("\\s+", " ").trim();
      sb.append(comment.isEmpty() ? line : pad(line, width) + "  -- " + comment).append('\n');
    }
  }

  /** 모든 줄을 prefix 로 시작한다(AND 조건). 주석은 같은 열에 맞춘다(줄바꿈은 공백으로). */
  private static void lines(StringBuilder sb, String prefix, List<String> items, List<String> comments) {
    int width = 0;
    for (String item : items) width = Math.max(width, item.length());
    for (int i = 0; i < items.size(); i++) {
      String comment = comments.get(i).replaceAll("\\s+", " ").trim();
      sb.append(prefix).append(comment.isEmpty() ? items.get(i) : pad(items.get(i), width) + "  -- " + comment).append('\n');
    }
  }

  private static String up(String name) {
    return name.toUpperCase(Locale.ROOT);
  }

  private static String pad(String s, int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }
}
