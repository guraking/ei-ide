package ei.db.console;

/** SQL 정렬 · 대소문자 자체 점검: java -cp out:test-out ei.db.console.SqlFormatterCheck */
public class SqlFormatterCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    eq(SqlFormatter.changeCase("select a.name, 'Kim' from t a -- note\nwhere a.id = #{userId} and \"Mixed\" = 1", true),
       "SELECT A.NAME, 'Kim' FROM T A -- note\nWHERE A.ID = #{userId} AND \"Mixed\" = 1");
    eq(SqlFormatter.changeCase("SELECT X FROM T WHERE N = 'It''s'", false), "select x from t where n = 'It''s'");

    System.out.println(SqlFormatter.format(
        "select a.id, a.name, nvl(b.qty,0) qty from orders a, items b where a.id = b.order_id(+) and a.dt between :s and :e and a.st in ('A','B') and exists (select 1 from x where x.id=a.id) order by a.id desc, a.name;"
        + "update t set a=1, b='x' where id=#{id};"
        + "select * from a left outer join b on a.id=b.id and b.x=1 union all select * from c group by k having count(*)>1"));
    System.out.println("SqlFormatterCheck 통과");
  }
}
