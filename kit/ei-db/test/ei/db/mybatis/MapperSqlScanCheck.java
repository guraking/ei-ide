package ei.db.mybatis;

import java.util.ArrayList;
import java.util.List;

/** MapperSqlScan 자체 점검. 대부분은 「긋지 않는다」(오탐 방지) 확인이다. */
public class MapperSqlScanCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  /** SQL 하나의 칼럼을 「표.칼럼」 목록으로. */
  static List<String> cols(String sql) { return cols(sql, false); }

  static List<String> cols(String sql, boolean fragment) {
    MapperSqlScan.Result r = new MapperSqlScan.Result(new ArrayList<>(), new ArrayList<>());
    MapperSqlScan.scanSql(sql, 0, fragment, r);
    List<String> out = new ArrayList<>();
    for (MapperSqlScan.Column c : r.columns()) {
      eq(sql.substring(c.start(), c.end()), c.name());
      out.add((c.table().schema() == null ? "" : c.table().schema() + ".") + c.table().name() + "." + c.name());
    }
    return out;
  }

  static List<String> tabs(String sql) {
    MapperSqlScan.Result r = new MapperSqlScan.Result(new ArrayList<>(), new ArrayList<>());
    MapperSqlScan.scanSql(sql, 0, false, r);
    List<String> out = new ArrayList<>();
    for (MapperSqlScan.Table t : r.tables()) {
      eq(sql.substring(t.start(), t.end()), t.name());
      out.add((t.schema() == null ? "" : t.schema() + ".") + t.name());
    }
    return out;
  }

  /** 매퍼 XML 본문(mapper 태그 안)을 훑어 「표.칼럼」 과 표 목록. 위치가 문서 글과 맞는지도 본다. */
  static MapperSqlScan.Result xml(String body) {
    String text = "<?xml version=\"1.0\"?>\n<mapper namespace=\"a.B\">\n" + body + "\n</mapper>";
    MapperSqlScan.Result r = MapperSqlScan.scan(text, MapperDoc.parse(text));
    for (MapperSqlScan.Table t : r.tables()) eq(text.substring(t.start(), t.end()), t.name());
    for (MapperSqlScan.Column c : r.columns()) eq(text.substring(c.start(), c.end()), c.name());
    return r;
  }

  static List<String> xcols(String body) {
    List<String> out = new ArrayList<>();
    for (MapperSqlScan.Column c : xml(body).columns()) out.add(c.table().name() + "." + c.name());
    return out;
  }

  static List<String> xtabs(String body) {
    List<String> out = new ArrayList<>();
    for (MapperSqlScan.Table t : xml(body).tables()) out.add(t.name());
    return out;
  }

  public static void main(String[] args) {
    // ---- 긋는 것: 별칭이 표 하나로 정해지는 칼럼
    eq(cols("SELECT a.USER_ID, b.DEPT_NM FROM TB_USER a, TB_DEPT b WHERE a.DEPT_ID = b.DEPT_ID(+)"),
        List.of("TB_USER.USER_ID", "TB_DEPT.DEPT_NM", "TB_USER.DEPT_ID", "TB_DEPT.DEPT_ID"));
    eq(tabs("SELECT a.USER_ID FROM TB_USER a, TB_DEPT b"), List.of("TB_USER", "TB_DEPT"));
    eq(cols("select u.user_id from tb_user u left join tb_dept d on d.dept_id = u.dept_id where d.use_yn = 'Y'"),
        List.of("tb_user.user_id", "tb_dept.dept_id", "tb_user.dept_id", "tb_dept.use_yn"));
    eq(cols("SELECT TB_USER.USER_ID FROM TB_USER, TB_DEPT"), List.of("TB_USER.USER_ID"));
    eq(cols("SELECT a.X FROM SCOTT.EMP a, DEPT d"), List.of("SCOTT.EMP.X"));
    eq(cols("SELECT /*+ INDEX(a IDX_01) */ a.X -- a.Y\n FROM T a, U b /* b.Z */"), List.of("T.X"));
    // 서브쿼리 안에서 바깥 별칭(상관 서브쿼리), 안쪽 별칭이 바깥을 가린다.
    eq(cols("SELECT a.ID FROM T a, U u WHERE EXISTS (SELECT 1 FROM V b WHERE b.ID = a.ID)"), List.of("T.ID", "V.ID", "T.ID"));
    eq(cols("SELECT a.ID FROM T a, U u WHERE a.ID IN (SELECT a.K FROM V a, W w)"), List.of("T.ID", "T.ID", "V.K"));
    // 따옴표 이름.
    eq(cols("SELECT a.\"userNm\" FROM \"TbUser\" a, T b"), List.of("\"TbUser\".\"userNm\""));

    // ---- 긋는 것: 한정자 없는 칼럼(표가 정확히 하나)
    eq(cols("SELECT USER_ID, USER_NM AS NM, DEPT_ID D, COUNT(*) CNT, SYSDATE, ROWNUM RN FROM TB_USER WHERE USER_ID = #{id} AND DEL_YN IS NULL AND ROWNUM <= 10 ORDER BY NM"),
        List.of("TB_USER.USER_ID", "TB_USER.USER_NM", "TB_USER.DEPT_ID", "TB_USER.USER_ID", "TB_USER.DEL_YN"));
    eq(cols("SELECT DISTINCT USER_NM FROM TB_USER u WHERE (A = 1 OR B LIKE 'x%') AND u.C = 2"), List.of("TB_USER.C", "TB_USER.USER_NM", "TB_USER.B"));
    eq(cols("INSERT INTO TB_USER (USER_ID, USER_NM) VALUES (#{id}, #{nm})"), List.of("TB_USER.USER_ID", "TB_USER.USER_NM"));
    eq(cols("UPDATE TB_USER SET USER_NM = #{nm}, UPD_DT = SYSDATE, CNT = CASE WHEN A = 1 THEN 1 ELSE 2 END, X = (SELECT MAX(Q) FROM Z) WHERE USER_ID = #{id}"),
        List.of("TB_USER.USER_NM", "TB_USER.UPD_DT", "TB_USER.CNT", "TB_USER.X", "TB_USER.USER_ID"));
    eq(cols("DELETE FROM TB_USER WHERE USER_ID = #{id}"), List.of("TB_USER.USER_ID"));
    eq(cols("DELETE TB_USER WHERE USER_ID = #{id}"), List.of("TB_USER.USER_ID"));
    eq(cols("MERGE INTO TB_USER t USING (SELECT #{id} AS ID FROM DUAL) s ON (t.USER_ID = s.ID) "
        + "WHEN MATCHED THEN UPDATE SET USER_NM = #{nm}, t.UPD_DT = SYSDATE WHEN NOT MATCHED THEN INSERT (USER_ID, USER_NM) VALUES (s.ID, #{nm})"),
        List.of("TB_USER.USER_NM", "TB_USER.USER_ID", "TB_USER.USER_NM", "TB_USER.USER_ID", "TB_USER.UPD_DT"));
    eq(cols("INSERT INTO t (id, nm) VALUES (#{id}, #{nm}) ON CONFLICT (id) DO UPDATE SET nm = EXCLUDED.nm"), List.of("t.id", "t.nm", "t.nm"));
    eq(cols("UPDATE t SET a = u.b FROM u WHERE t.id = u.id"), List.of("t.a", "u.b", "t.id", "u.id"));

    // ---- 긋지 않는 것: 표가 정해지지 않는 원본
    eq(cols("SELECT v.X FROM (SELECT a.ID X FROM T a, U u) v WHERE v.X = 1"), List.of("T.ID"));
    eq(tabs("SELECT v.X FROM (SELECT ID X FROM T) v"), List.of("T"));
    eq(cols("WITH c AS (SELECT ID FROM T) SELECT c.NOPE, x.NOPE, NOPE FROM c x WHERE NOPE = 1"), List.of());
    eq(tabs("WITH c AS (SELECT ID FROM T), d (a, b) AS (SELECT 1, 2 FROM DUAL) SELECT 1 FROM c, d, c x"), List.of("T"));
    eq(cols("SELECT t.COLUMN_VALUE, t.X FROM TABLE(F_SPLIT(#{s})) t"), List.of());
    eq(tabs("SELECT 1 FROM TABLE(F_SPLIT(#{s})) t, XMLTABLE('/a' PASSING x) y, generate_series(1, 3) g, LATERAL (SELECT 1 FROM Z) l"), List.of("Z"));
    eq(tabs("SELECT SEQ_USER.NEXTVAL FROM DUAL"), List.of());
    eq(cols("SELECT SEQ_USER.NEXTVAL, a.ROWID, PKG_X.F_NAME(a.ID), a.ID FROM T a, DUAL"), List.of("T.ID", "T.ID"));
    eq(tabs("SELECT a.X FROM TB_REMOTE@DBLINK a, OTHER.T2@L2.WORLD b, T c"), List.of("T"));
    eq(cols("SELECT a.X, b.Y, c.Z FROM TB_REMOTE@DBLINK a, OTHER.T2@L2.WORLD b, T c"), List.of("T.Z"));
    eq(cols("SELECT a.b.c, x.y.z FROM T a, U x"), List.of());
    eq(cols("SELECT a.* , COUNT(a.*) FROM T a, U b"), List.of());
    eq(cols("SELECT z.X FROM T a, U b"), List.of());
    eq(cols("SELECT a.X FROM T a UNION ALL SELECT a.Y FROM U a"), List.of());
    eq(cols("SELECT A FROM T UNION ALL SELECT B FROM T"), List.of());
    eq(cols("SELECT a.X FROM T PARTITION (P1) a, U b"), List.of());
    eq(cols("SELECT * FROM T PIVOT (SUM(Q) FOR M IN ('1' AS M1)) WHERE M1 = 1"), List.of());
    eq(cols("SELECT A FROM T AS OF TIMESTAMP SYSDATE - 1 WHERE B = 1"), List.of());
    eq(cols("SELECT a.x FROM t AS a(x, y), u b"), List.of());
    eq(cols("SELECT A FROM T JOIN U USING (ID) WHERE B = 1"), List.of());
    eq(cols("SELECT A, B FROM T, U WHERE C = 1"), List.of());
    eq(cols("SELECT A FROM T, (SELECT 1 X FROM DUAL) WHERE B = 1"), List.of());
    // 상관 서브쿼리의 한정자 없는 이름은 바깥 표 것일 수 있다: 안쪽은 보지 않는다.
    eq(cols("SELECT A FROM T WHERE EXISTS (SELECT 1 FROM U WHERE U_ID = T_ID AND Q = 1)"), List.of("T.A"));
    eq(cols("SELECT (SELECT MAX(X) FROM U WHERE K = ID) M FROM T"), List.of());
    // 안쪽 문장에 모르는 원본이 있으면 바깥 별칭으로 나가 찾지 않는다.
    eq(cols("SELECT a.ID FROM T a, U u WHERE EXISTS (SELECT 1 FROM TABLE(F(1)) WHERE a.NOPE = 1)"), List.of("T.ID"));

    // ---- 긋지 않는 것: 칼럼이 아닌 낱말 자리
    eq(cols("SELECT USER_NM AS NM, UPPER(X) UX, 'a' LIT, 1 ONE, NULL N, CASE WHEN A = 1 THEN B END C, T.*, CAST(A AS VARCHAR2(10)) V FROM T ORDER BY NM, UX"),
        List.of("T.USER_NM"));
    eq(cols("SELECT SYSDATE, CURRENT_DATE, LEVEL, ROWID, USER, CURRENT_TIMESTAMP FROM T WHERE LEVEL <= 3 AND SYSDATE > #{d} AND PRIOR A = B"), List.of());
    eq(cols("SELECT A FROM T WHERE X BETWEEN LO AND HI AND EXTRACT(YEAR FROM DT) = 2024 AND NOT EXISTS (SELECT 1 FROM U)"), List.of("T.A", "T.X"));
    eq(cols("SELECT A FROM T GROUP BY A HAVING CNT > 1 AND TOTAL > 2 ORDER BY ALIAS1"), List.of("T.A"));
    eq(cols("SELECT A FROM T WHERE B IS DISTINCT FROM C"), List.of("T.A", "T.B"));
    eq(tabs("SELECT A FROM T WHERE B IS DISTINCT FROM C AND TRIM(BOTH ' ' FROM D) = 'x' AND SUBSTRING(E FROM 1 FOR 2) = 'ab'"), List.of("T"));
    eq(tabs("SELECT A FROM T WHERE X = 1 FOR UPDATE OF A NOWAIT"), List.of("T"));
    eq(cols("SELECT a.X::text, a.Y::public.mytype, 'x'::a.b FROM T a, U u"), List.of("T.X", "T.Y"));
    eq(cols("SELECT LEFT(a.X, 2) FROM T a JOIN U b ON LEFT(a.K, 3) = b.K"), List.of("T.X", "T.K", "U.K"));
    eq(cols("INSERT INTO T (A, B) SELECT X, Y FROM S WHERE Z = 1"), List.of("T.A", "T.B"));
    eq(cols("INSERT INTO T VALUES (#{a}, #{b})"), List.of());
    eq(cols("INSERT INTO T (SELECT A FROM S)"), List.of());
    eq(cols("INSERT ALL INTO T1 (A) VALUES (X) INTO T2 (B) VALUES (X) SELECT X FROM S"), List.of("S.X"));
    eq(cols("UPDATE (SELECT A FROM T) SET A = 1"), List.of());
    eq(cols("UPDATE T SET (A, B) = (SELECT 1, 2 FROM DUAL) WHERE C = 1"), List.of("T.C"));

    // ---- 긋지 않는 것: 문자열 · 주석 · 바인드 · 치환
    eq(cols("SELECT a.X FROM T a, U b WHERE a.Y = 'b.NOPE' AND a.Z = #{b.nope} /* b.NOPE */ -- b.NOPE"), List.of("T.X", "T.Y", "T.Z"));
    eq(cols("SELECT a.X FROM ${tbl} a, U b"), List.of());
    eq(tabs("SELECT a.X FROM ${schema}.T a, TB_${suffix} b, T_${x}_Y c, ${t} d, U e"), List.of("U"));
    eq(cols("SELECT a.${col}, a.X_${n}, ${alias}.Y, a.OK FROM T a, U b ORDER BY ${order}"), List.of("T.OK"));
    eq(cols("SELECT A FROM T WHERE B = 1 ORDER BY ${order}"), List.of());
    eq(cols("SELECT A FROM T ${joins} WHERE B = 1"), List.of());
    eq(cols("SELECT a.\"${col}\" FROM T a, U b"), List.of());
    eq(cols("UPDATE T SET A = 1, ${col} = 2, B = 3 WHERE C = #{c}"), List.of("T.A", "T.B"));
    eq(cols("INSERT INTO T (A, ${cols}) VALUES (1, 2)"), List.of());

    // ---- 건너뛰는 문장
    eq(cols("SELECT a.X FROM T a, U b WHERE a.Y = 'unterminated"), List.of());
    eq(cols("SELECT a.X FROM T a, U b WHERE (a.Y = 1"), List.of());
    eq(cols("BEGIN UPDATE T SET A = 1; UPDATE U SET B = 2; END;"), List.of());
    eq(cols("{call PKG.PROC(#{a})}"), List.of());
    eq(cols("SELECT a.X FROM T a, U b WHERE a.Y = q'[it's]'"), List.of());
    eq(cols("SELECT a.X FROM T a, U b WHERE a.Y = E'it\\'s'"), List.of());
    eq(cols("SELECT a.X FROM T a, U b WHERE a.Y = $$b.Z$$"), List.of());
    eq(cols("DECLARE v NUMBER; BEGIN SELECT A INTO v FROM T; END;"), List.of());
    eq(tabs("SELECT a.X FROM T a, U b; DELETE FROM V"), List.of());
    eq(cols("SELECT a.X FROM T a, U b;"), List.of("T.X"));

    // ---- sql 조각: 한정자 없는 것은 보지 않고, 조각 안에서 별칭이 정해지는 것만
    eq(cols("USER_ID, USER_NM, a.X", true), List.of());
    eq(cols("FROM TB_USER a WHERE a.USER_ID = #{id} AND DEL_YN = 'N'", true), List.of("TB_USER.USER_ID"));

    // ---- 매퍼 XML: 위치 · CDATA · 엔티티 · 동적 태그 · include
    eq(xcols("<select id=\"a\">SELECT u.USER_ID FROM TB_USER u, TB_DEPT d WHERE u.AGE &lt;= #{age} AND u.NM &lt;&gt; 'x' AND d.ID = u.DEPT_ID</select>"),
        List.of("TB_USER.USER_ID", "TB_USER.AGE", "TB_USER.NM", "TB_DEPT.ID", "TB_USER.DEPT_ID"));
    eq(xcols("<select id=\"a\"><![CDATA[SELECT u.USER_ID FROM TB_USER u, TB_DEPT d WHERE u.AGE <= #{age} ]]> AND u.NM = &apos;x&apos; <![CDATA[ AND u.Z < 3]]></select>"),
        List.of("TB_USER.USER_ID", "TB_USER.AGE", "TB_USER.NM", "TB_USER.Z"));
    eq(xcols("<select id=\"a\">SELECT USER_ID, USER_NM FROM TB_USER<where><if test=\"nm != null and nm > ''\">AND USER_NM = #{nm}</if>"
        + "<if test=\"ids != null\">AND USER_ID IN <foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></if></where> ORDER BY 1</select>"),
        List.of("TB_USER.USER_ID", "TB_USER.USER_NM", "TB_USER.USER_NM", "TB_USER.USER_ID"));
    eq(xcols("<update id=\"u\">UPDATE TB_USER<set><if test=\"nm != null\">USER_NM = #{nm},</if><if test=\"age != null\">AGE = #{age},</if></set>WHERE USER_ID = #{id}</update>"),
        List.of("TB_USER.USER_NM", "TB_USER.AGE", "TB_USER.USER_ID"));
    eq(xcols("<update id=\"u\">UPDATE TB_USER <trim prefix=\"SET\" suffixOverrides=\",\"><if test=\"a\">USER_NM = #{nm},</if></trim> WHERE USER_ID = #{id}</update>"),
        List.of("TB_USER.USER_NM", "TB_USER.USER_ID"));
    eq(xcols("<insert id=\"i\">INSERT INTO TB_USER <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\"><if test=\"a\">USER_ID,</if><if test=\"b\">USER_NM,</if></trim>"
        + "<trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\"><if test=\"a\">#{id},</if><if test=\"b\">#{nm},</if></trim></insert>"),
        List.of("TB_USER.USER_ID", "TB_USER.USER_NM"));
    eq(xcols("<insert id=\"i\"><selectKey keyProperty=\"id\" resultType=\"long\" order=\"BEFORE\">SELECT s.NOPE FROM TB_SEQ s, X y</selectKey>"
        + "INSERT INTO TB_USER (USER_ID) VALUES (#{id})</insert>"), List.of("TB_USER.USER_ID"));
    // include: 한정자 없는 것은 보지 않는다(조각이 표를 더할 수 있다). 별칭이 이 문장에서 정해진 것은 본다.
    eq(xcols("<sql id=\"cols\">u.USER_ID, u.USER_NM, PLAIN</sql><select id=\"a\">SELECT <include refid=\"cols\"/>, u.AGE, BARE FROM TB_USER u WHERE BARE2 = 1</select>"),
        List.of("TB_USER.AGE"));
    eq(xtabs("<select id=\"a\">SELECT 1 FROM <include refid=\"t\"/> a, TB_<include refid=\"s\"/> b, TB_OK c</select>"), List.of("TB_OK"));
    // 태그 경계 뒤가 별칭 자리면 그 문장 전체를 건너뛴다.
    eq(xcols("<select id=\"a\">SELECT a.X FROM <if test=\"p\">T1</if><if test=\"q\">T2</if> a, U b WHERE b.Y = 1</select>"), List.of());
    eq(xcols("<select id=\"a\">SELECT a.X FROM T1 <if test=\"p\">a</if>, U b WHERE b.Y = 1</select>"), List.of());
    // choose 로 같은 별칭이 다른 표가 되면 그 별칭은 보지 않는다.
    eq(xcols("<select id=\"a\">SELECT a.X, b.Y FROM <choose><when test=\"p\">T1 a</when><otherwise>T2 a</otherwise></choose>, U b</select>"), List.of("U.Y"));
    // 동적 태그로 표가 늘 수 있으면 한정자 없는 것은 보지 않는다.
    eq(xcols("<select id=\"a\">SELECT A FROM T <if test=\"p\">, U</if> WHERE B = 1</select>"), List.of());
    eq(xcols("<select id=\"a\">SELECT A FROM T WHERE B = 1 <!-- AND C.NOPE = 1 --> AND D = 2</select>"), List.of("T.A", "T.B", "T.D"));
    eq(xcols("<select id=\"a\">SELECT A FROM T${suffix}<where>B = 1</where></select>"), List.of());
    eq(xtabs("<select id=\"a\">SELECT A FROM TB_USER<where>B = 1</where></select>"), List.of("TB_USER"));
    // 닫히지 않은(쓰는 중인) 문장 · id 없는 문장도 예외 없이.
    eq(xcols("<select id=\"a\">SELECT a. FROM T a, U b WHERE a.X = "), List.of("T.X"));
    eq(xcols("<select>SELECT a.X FROM T a, U b</select>"), List.of());
    eq(xcols("<select id=\"e\"></select><select id=\"f\">   </select>"), List.of());

    // ---- 흔한 매퍼 꼴
    eq(cols("SELECT * FROM (SELECT ROWNUM RN, A.* FROM (SELECT USER_ID, USER_NM FROM TB_USER WHERE DEL_YN = 'N' ORDER BY REG_DT DESC) A WHERE ROWNUM <= #{end}) WHERE RN >= #{start}"),
        List.of());
    eq(cols("SELECT A.*, (SELECT CD_NM FROM TB_CODE WHERE CD = A.STATUS) STATUS_NM FROM TB_X A"), List.of("TB_X.STATUS"));
    eq(cols("SELECT NVL(MAX(SEQ), 0) + 1 FROM TB_X WHERE ID = #{id} AND DEL_YN != 'Y'"), List.of("TB_X.ID", "TB_X.DEL_YN"));
    eq(cols("MERGE INTO T USING DUAL ON (ID = #{id}) WHEN MATCHED THEN UPDATE SET NM = #{nm} WHEN NOT MATCHED THEN INSERT (ID, NM) VALUES (#{id}, #{nm})"),
        List.of("T.NM", "T.ID", "T.NM"));
    eq(cols("SELECT SQL_CALC_FOUND_ROWS A, B X FROM T"), List.of("T.B"));
    eq(cols("SELECT USER_ID ID, USER_NM NM FROM T"), List.of("T.USER_NM"));
    eq(cols("SELECT a.`X`, `a`.Y FROM `T` a, U b"), List.of());
    eq(cols("SELECT a.data->>'k', a.arr[1], public.f(a.x), now() FROM t a, u b WHERE a.id = ANY(#{ids})"), List.of("t.data", "t.arr", "t.x", "t.id"));
    eq(xcols("<select id=\"a\"><if test=\"k == 1\">SELECT A FROM T WHERE X = 1</if><if test=\"k == 2\">SELECT B FROM T WHERE Y = 1</if></select>"), List.of());
    eq(xcols("<insert id=\"i\">INSERT ALL <foreach collection=\"list\" item=\"r\">INTO T (A, B) VALUES (#{r.a}, #{r.b})</foreach> SELECT * FROM DUAL</insert>"), List.of());
    eq(xcols("<select id=\"a\">SELECT a.X FROM T a, U b WHERE a.ID IN <foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach> AND b.Y = ${v}</select>"),
        List.of("T.X", "T.ID", "U.Y"));

    // 쪽 나누기 머리 · 꼬리를 include 로 감싼 문장: 별칭이 정해진 것은 보고 한정자 없는 것은 보지 않는다.
    eq(xcols("<select id=\"a\"><include refid=\"pageHead\"/>SELECT a.X, B FROM T a WHERE a.Y = 1 AND C = 2<include refid=\"pageTail\"/></select>"),
        List.of("T.X", "T.Y"));
    eq(xcols("<select id=\"a\"><include refid=\"all\"/></select>"), List.of());
    eq(tabs("WITH x AS (INSERT INTO t (a) VALUES (1) RETURNING a) SELECT 1 FROM x, u"), List.of("u", "t"));
    eq(cols("WITH x AS (UPDATE t a SET v = 1 RETURNING a.id) SELECT a.col FROM other a, x"), List.of("t.id", "other.col"));
    eq(cols("SELECT A, BINARY B FROM T WHERE BINARY C = 'x'"), List.of("T.A"));
    eq(cols("UPDATE ${x} T SET A = 1"), List.of());

    eq(MapperSqlScan.systemName("user_tables"), true);
    eq(MapperSqlScan.systemName("V$SESSION"), true);
    eq(MapperSqlScan.systemName("TB_USER"), false);

    // 큰 문장도 한 번 훑기로 끝난다(문장 길이에 비례).
    StringBuilder big = new StringBuilder("SELECT a.C0");
    for (int i = 1; i < 20000; i++) big.append(", a.C").append(i);
    big.append(" FROM T a, U b");
    long t0 = System.nanoTime();
    eq(cols(big.toString()).size(), 20000);
    if (System.nanoTime() - t0 > 3_000_000_000L) throw new AssertionError("너무 느림");
    System.out.println("MapperSqlScanCheck 통과");
  }
}
