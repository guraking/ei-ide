package ei.db.bench;

import ei.db.Meta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 고른 표들 사이의 연결 조건 추천. IntelliJ 에 기대지 않는다(자체 점검에서 바로 부른다).
 * 1) DB 외래 키가 있으면 그것을 쓴다(확실). 2) 없으면 이름으로 짐작한다(guessed): 한쪽 PK 칼럼과 이름이 같은 칼럼,
 * 또는 PK 가 ID 하나인 표를 「표 이름 끝 토막 + _ID」 칼럼이 가리키는 경우(TB_GPM_NPP_PART.ID ← NPP_PART_ID).
 * 연결은 앞에 고른 표가 왼쪽, 뒤에 고른 표가 오른쪽이다. 한 쌍에 외래 키가 있으면 짐작은 하지 않는다.
 */
public final class JoinAdvisor {
  private JoinAdvisor() {}

  private static final String ID = "ID";
  private static final String ID_SUFFIX = "_ID";

  /** columns 는 표 이름(대문자) → 칼럼, fks 는 고른 표들의 외래 키(importedKeys). */
  public static List<QueryBuilder.Join> suggest(List<QueryBuilder.Table> tables, Map<String, List<Meta.Column>> columns, List<Meta.ForeignKey> fks) {
    List<QueryBuilder.Join> out = new ArrayList<>();
    for (int i = 0; i < tables.size(); i++) {
      for (int k = i + 1; k < tables.size(); k++) {
        QueryBuilder.Table left = tables.get(i);
        QueryBuilder.Table right = tables.get(k);
        int before = out.size();
        Set<String> seen = new HashSet<>();
        for (Meta.ForeignKey fk : fks) {
          String key = fk.name() + "|" + fk.fkTable() + "|" + fk.fkColumn();
          if (!seen.add(key)) continue;
          if (same(fk.fkTable(), right.name()) && same(fk.pkTable(), left.name())) {
            out.add(new QueryBuilder.Join(left.alias(), fk.pkColumn(), right.alias(), fk.fkColumn(), false, false));
          } else if (same(fk.fkTable(), left.name()) && same(fk.pkTable(), right.name())) {
            out.add(new QueryBuilder.Join(left.alias(), fk.fkColumn(), right.alias(), fk.pkColumn(), false, false));
          }
        }
        if (out.size() > before) continue;
        List<Meta.Column> lc = columns.getOrDefault(key(left.name()), List.of());
        List<Meta.Column> rc = columns.getOrDefault(key(right.name()), List.of());
        guessSameName(left, lc, right, rc, out);
        if (out.size() > before) continue;
        guessIdSuffix(left, lc, right, rc, out, false);
        guessIdSuffix(right, rc, left, lc, out, true);
      }
    }
    return out;
  }

  /** 한쪽의 PK 칼럼과 이름이 같은 칼럼이 다른 쪽에 있으면 그 칼럼끼리. 표마다 PK 가 ID 인 꼴이 흔해 ID 끼리는 잇지 않는다. */
  private static void guessSameName(QueryBuilder.Table left, List<Meta.Column> lc, QueryBuilder.Table right, List<Meta.Column> rc, List<QueryBuilder.Join> out) {
    for (Meta.Column l : lc) {
      for (Meta.Column r : rc) {
        if (!l.name().equalsIgnoreCase(r.name()) || !(l.pk() || r.pk())) continue;
        if (l.name().equalsIgnoreCase(ID)) continue;
        out.add(new QueryBuilder.Join(left.alias(), l.name(), right.alias(), r.name(), false, true));
      }
    }
  }

  /** parent 의 PK 가 ID 하나이고 child 에 「parent 이름 끝 토막_ID」 칼럼이 있으면 잇는다. swap 이면 child 가 왼쪽 표다. */
  private static void guessIdSuffix(QueryBuilder.Table parent, List<Meta.Column> pc, QueryBuilder.Table child, List<Meta.Column> cc,
                                    List<QueryBuilder.Join> out, boolean swap) {
    String pk = null;
    int pks = 0;
    for (Meta.Column c : pc) {
      if (c.pk()) { pks++; pk = c.name(); }
    }
    if (pks != 1 || !pk.equalsIgnoreCase(ID)) return;
    String table = key(SqlParts.simpleName(parent.name()));
    for (Meta.Column c : cc) {
      String n = key(c.name());
      if (!n.endsWith(ID_SUFFIX) || n.equals(ID)) continue;
      String base = n.substring(0, n.length() - ID_SUFFIX.length());
      if (base.isEmpty() || !(table.equals(base) || table.endsWith("_" + base))) continue;
      out.add(swap ? new QueryBuilder.Join(child.alias(), c.name(), parent.alias(), pk, false, true)
                   : new QueryBuilder.Join(parent.alias(), pk, child.alias(), c.name(), false, true));
    }
  }

  private static boolean same(String a, String b) {
    return SqlParts.simpleName(a).equalsIgnoreCase(SqlParts.simpleName(b));
  }

  public static String key(String name) {
    return name.toUpperCase(Locale.ROOT);
  }
}
