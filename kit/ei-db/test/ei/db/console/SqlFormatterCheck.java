package ei.db.console;

/** SQL 정렬 · 대소문자 자체 점검: java -cp out:test-out ei.db.console.SqlFormatterCheck */
public class SqlFormatterCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    eq(SqlFormatter.changeCase("select a.name, 'Kim' from t a -- note\nwhere a.id = #{userId} and \"Mixed\" = 1", true),
       "SELECT A.NAME, 'Kim' FROM T A -- note\nWHERE A.ID = #{userId} AND \"Mixed\" = 1");
    eq(SqlFormatter.changeCase("SELECT X FROM T WHERE N = 'It''s'", false), "select x from t where n = 'It''s'");

    // 줄 끝 주석은 그 칼럼 줄에 남고 한 열에 맞춘다(탐색기 SELECT 만들기 결과를 다시 정렬해도 같은 모양).
    String generated = "SELECT t.OFLN_BID_NO,  -- 오프라인입찰번호\n       t.ANC_NM,       -- 공고명\n       t.ITEM_DSCD     -- 품목구분코드\n  FROM APP.BID t\n WHERE 1 = 1";
    eq(SqlFormatter.format(generated), generated);
    eq(SqlFormatter.format("select a -- 가\n, bb -- 나\nfrom t"), "SELECT a,  -- 가\n       bb  -- 나\n  FROM t");
    eq(SqlFormatter.format("-- 머리 주석\nselect a from t"), "-- 머리 주석\nSELECT a\n  FROM t");

    eq(SqlFormatter.format("INSERT INTO T_A(a_no, nm, amt) VALUES ('A1', '가나 001', nvl(x, 0))"),
       "INSERT INTO T_A (\n"
       + "       a_no,\n"
       + "       nm,\n"
       + "       amt\n"
       + ") VALUES (\n"
       + "       'A1',        -- a_no\n"
       + "       '가나 001',  -- nm\n"
       + "       nvl(x, 0)    -- amt\n"
       + ")");

    System.out.println(SqlFormatter.format(
        "select a.id, a.name, nvl(b.qty,0) qty from orders a, items b where a.id = b.order_id(+) and a.dt between :s and :e and a.st in ('A','B') and exists (select 1 from x where x.id=a.id) order by a.id desc, a.name;"
        + "update t set a=1, b='x' where id=#{id};"
        + "select * from a left outer join b on a.id=b.id and b.x=1 union all select * from c group by k having count(*)>1"));
    System.out.println("SqlFormatterCheck 통과");
  }
}
