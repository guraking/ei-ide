package ei.db.console;

/** SQL 정렬 · 대소문자 자체 점검: java -cp out:test-out ei.db.console.SqlFormatterCheck */
public class SqlFormatterCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) {
    eq(SqlFormatter.changeCase("select a.name, 'Kim' from t a -- note\nwhere a.id = #{userId} and \"Mixed\" = 1", true),
       "SELECT A.NAME, 'Kim' FROM T A -- note\nWHERE A.ID = #{userId} AND \"Mixed\" = 1");
    eq(SqlFormatter.changeCase("SELECT X FROM T WHERE N = 'It''s'", false), "select x from t where n = 'It''s'");
    // q'[…]' · E'…' · $tag$…$tag$ 는 한 덩어리: 글자를 바꾸지 않고, 앞에 공백을 넣지 않고, 안의 ; 로 나누지 않는다.
    eq(SqlFormatter.changeCase("select q'[it's]' from dual where x='a'", true), "SELECT q'[it's]' FROM DUAL WHERE X='a'");
    eq(SqlFormatter.changeCase("select E'it\\'s ok' from t", true), "SELECT E'it\\'s ok' FROM T");
    eq(SqlFormatter.changeCase("select $b$ Mixed $b$ from t", false), "select $b$ Mixed $b$ from t");
    eq(SqlFormatter.changeCase("SELECT * FROM V$SESSION WHERE A = ${x}", false), "select * from v$session where a = ${x}");
    eq(SqlFormatter.format("select q'[a;b]' x from dual"), "SELECT\n       q'[a;b]' x\n  FROM dual");
    eq(SqlFormatter.format("select E'a\\';b' x from t"), "SELECT\n       E'a\\';b' x\n  FROM t");
    // PostgreSQL 연산자는 쪼개지 않고(3글자를 2글자보다 먼저), 앞 글자가 붙은 문자열은 한 덩어리.
    eq(SqlFormatter.format("select d->>'a' x, d#>>'{a,b}' y, d->'a' z, d#>'{a}' w from t where d @> '{}' and d <@ e and a && b and d ?| c and d ?& c"),
       "SELECT\n       d ->> 'a' x\n     , d #>> '{a,b}' y\n     , d -> 'a' z\n     , d #> '{a}' w\n  FROM t\n WHERE d @> '{}'\n   AND d <@ e\n   AND a && b\n   AND d ?| c\n   AND d ?& c");
    eq(SqlFormatter.format("select N'가;나' a, X'0A' b, B'101' c, U&'d\\0061' d from t"),
       "SELECT\n       N'가;나' a\n     , X'0A' b\n     , B'101' c\n     , U&'d\\0061' d\n  FROM t");
    eq(SqlFormatter.changeCase("select n'It''s' from t", true), "SELECT n'It''s' FROM T");
    eq(SqlFormatter.format("select sid from v$session where serial#>0"), "SELECT\n       sid\n  FROM v$session\n WHERE serial# > 0");
    eq(SqlFormatter.format("select sid from v$session where serial#>'5'"), "SELECT\n       sid\n  FROM v$session\n WHERE serial# > '5'");
    eq(SqlFormatter.format("select d#>'{a}' w from t"), "SELECT\n       d #> '{a}' w\n  FROM t");
    // 바인드 자리 뒤 || 는 이어 붙이기: ?| 로 갈라지지 않는다.
    eq(SqlFormatter.format("select a from t where nm like '%'||?||'%'"), "SELECT\n       a\n  FROM t\n WHERE nm LIKE '%' || ? || '%'");
    // PL/SQL 이름표 <<이름>> 과 %TYPE · %ROWTYPE · %NOTFOUND 는 앞 낱말에 붙여 둔다.
    eq(SqlFormatter.format("<<outer>> v emp.ename%TYPE"), "<<outer>> v emp.ename%TYPE");
    eq(SqlFormatter.format("r emp%rowtype"), "r emp%rowtype");
    eq(SqlFormatter.format("exit when c1%NOTFOUND"), "exit WHEN c1%NOTFOUND");
    eq(SqlFormatter.format("select a%b from t"), "SELECT\n       a % b\n  FROM t");
    // 문장 끝 ; 앞의 줄 주석: ; 는 다음 줄로 가서 주석 밖에 남는다.
    eq(SqlFormatter.format("select a from t -- 끝\n;select b from u"), "SELECT\n       a\n  FROM t  -- 끝\n;\n\nSELECT\n       b\n  FROM u");

    // 줄 끝 주석은 그 칼럼 줄에 남고 한 열에 맞춘다. 뒤 쉼표로 쓴 문장도 앞 쉼표로 바뀐다.
    String generated = "SELECT t.OFLN_BID_NO,  -- 오프라인입찰번호\n       t.ANC_NM,       -- 공고명\n       t.ITEM_DSCD     -- 품목구분코드\n  FROM APP.BID t\n WHERE 1 = 1";
    eq(SqlFormatter.format(generated),
       "SELECT\n       t.OFLN_BID_NO  -- 오프라인입찰번호\n     , t.ANC_NM       -- 공고명\n     , t.ITEM_DSCD    -- 품목구분코드\n  FROM APP.BID t\n WHERE 1 = 1");
    // 쉼표는 줄 앞, SELECT 다음 줄부터 칼럼, 칼럼 뒤 /* */ 주석은 한 열로.
    eq(SqlFormatter.format("SELECT A.X /* 가 */,\n A.LONG_NAME /* 나 */\nFROM T A ORDER BY A.X DESC, A.Y"),
       "SELECT\n       A.X          /* 가 */\n     , A.LONG_NAME  /* 나 */\n  FROM T A\n ORDER BY A.X DESC\n     , A.Y");
    eq(SqlFormatter.format("select a -- 가\n, bb -- 나\nfrom t"), "SELECT\n       a   -- 가\n     , bb  -- 나\n  FROM t");
    eq(SqlFormatter.format("-- 머리 주석\nselect a from t"), "-- 머리 주석\nSELECT\n       a\n  FROM t");

    eq(SqlFormatter.format("INSERT INTO T_A(a_no, nm, amt) VALUES ('A1', '가나 001', nvl(x, 0))"),
       "INSERT INTO T_A (\n"
       + "       a_no\n"
       + "     , nm\n"
       + "     , amt\n"
       + ") VALUES (\n"
       + "       'A1'        -- a_no\n"
       + "     , '가나 001'  -- nm\n"
       + "     , nvl(x, 0)   -- amt\n"
       + ")");

    System.out.println(SqlFormatter.format(
        "select a.id, a.name, nvl(b.qty,0) qty from orders a, items b where a.id = b.order_id(+) and a.dt between :s and :e and a.st in ('A','B') and exists (select 1 from x where x.id=a.id) order by a.id desc, a.name;"
        + "update t set a=1, b='x' where id=#{id};"
        + "select * from a left outer join b on a.id=b.id and b.x=1 union all select * from c group by k having count(*)>1"));
    System.out.println("SqlFormatterCheck 통과");
  }
}
