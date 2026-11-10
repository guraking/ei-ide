package ei.db.mybatis;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 「주석 만들기」 자체 점검: java -cp out:resources:test-out ei.db.mybatis.DocCommentCheck */
public class DocCommentCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  public static void main(String[] args) throws Exception {
    DocComment.Dict d;
    try (Reader r = new InputStreamReader(Objects.requireNonNull(DocCommentCheck.class.getResourceAsStream("/doc/DocWords.properties")), StandardCharsets.UTF_8)) {
      d = DocComment.dict(r, "word.gpm=개발 관리\nverb.select=검색\n잘못된 줄");
    }
    // 사용자 사전이 기본을 덮는다
    eq(d.words().get("gpm"), "개발 관리");
    eq(d.verbs().get("select"), "검색");
    try (Reader r = new InputStreamReader(Objects.requireNonNull(DocCommentCheck.class.getResourceAsStream("/doc/DocWords.properties")), StandardCharsets.UTF_8)) {
      d = DocComment.dict(r, "");
    }

    // 낱말 쪼개기
    eq(DocComment.words("selectUserList"), List.of("select", "user", "list"));
    eq(DocComment.words("USE_YN"), List.of("use", "yn"));
    eq(DocComment.words("parseURLPath"), List.of("parse", "url", "path"));
    eq(DocComment.words("getItem2List"), List.of("get", "item2", "list"));

    // 조사
    eq(DocComment.objectParticle("사용자 목록"), "을");
    eq(DocComment.objectParticle("부품 개발 일정"), "을");
    eq(DocComment.objectParticle("메뉴"), "를");
    eq(DocComment.objectParticle("abc"), "을(를)");

    // 한 줄 설명
    eq(DocComment.sentence("selectUserList", d), "사용자 목록을 조회한다.");
    eq(DocComment.sentence("savePartDevSchedule", d), "부품 개발 일정을 저장한다.");
    eq(DocComment.sentence("existsUser", d), "사용자 여부를 확인한다.");
    eq(DocComment.sentence("countUser", d), "사용자 건수를 조회한다.");
    eq(DocComment.sentence("deleteAttachFile", d), "첨부 파일을 삭제한다.");
    eq(DocComment.sentence("insertMenu", d), "메뉴를 등록한다.");
    eq(DocComment.sentence("userList", d), "사용자 목록을 처리한다.");
    eq(DocComment.sentence("init", d), "초기화한다.");

    // SQL 읽기: 구식 조인, 조건, 매개변수 ↔ 칼럼
    DocComment.SqlFacts f = DocComment.facts("""
        SELECT /* x */ A.USER_ID, B.DEPT_NM, EXTRACT(YEAR FROM A.REG_DT) AS Y
          FROM TB_USER A, SCH.TB_DEPT B
         WHERE 1 = 1
           AND A.DEPT_CD = B.DEPT_CD(+)
           AND A.DEPT_CD = #{deptCd}
           AND A.USER_NM LIKE '%' || #{vo.userNm} || '%'
           AND A.USE_YN = #{useYn, jdbcType=VARCHAR}
           AND ROWNUM <= #{end}
        """);
    eq(f.tables(), List.of("TB_USER", "TB_DEPT"));
    eq(f.conditions(), List.of("DEPT_CD", "USER_NM", "USE_YN"));
    eq(f.paramColumns(), Map.of("deptCd", "DEPT_CD", "userNm", "USER_NM", "useYn", "USE_YN"));

    DocComment.SqlFacts ins = DocComment.facts("INSERT INTO TB_MENU (MENU_ID, MENU_NM, REG_DT) VALUES (#{menuId}, NVL(#{menuNm}, 'x'), SYSDATE)");
    eq(ins.tables(), List.of("TB_MENU"));
    eq(ins.conditions(), List.of());
    eq(ins.paramColumns(), Map.of("menuId", "MENU_ID", "menuNm", "MENU_NM"));

    DocComment.SqlFacts upd = DocComment.facts("UPDATE TB_MENU SET MENU_NM = #{menuNm} WHERE MENU_ID = #{menuId}");
    eq(upd.tables(), List.of("TB_MENU"));
    eq(upd.conditions(), List.of("MENU_ID"));
    eq(upd.paramColumns(), Map.of("menuNm", "MENU_NM", "menuId", "MENU_ID"));

    DocComment.SqlFacts ansi = DocComment.facts("SELECT * FROM TB_A a LEFT OUTER JOIN TB_B b ON a.ID = b.ID FROM ${dyn} WHERE a.ID IN (SELECT ID FROM TB_C)");
    eq(ansi.tables(), List.of("TB_A", "TB_C", "TB_B"));

    eq(DocComment.facts("DELETE FROM TB_FILE WHERE FILE_ID = #{fileId}").tables(), List.of("TB_FILE"));
    eq(DocComment.facts("SELECT * FROM TB_X FOR UPDATE NOWAIT").tables(), List.of("TB_X"));

    // 메서드 주석 전체
    DocComment.Tail tail = new DocComment.Tail("jmlee", "2026-11-10");
    String mapper = DocComment.forMethod(new DocComment.Method("selectUserList",
            List.of(new DocComment.Param("deptCd", "String")), "List<UserVo>", List.of(), null,
            List.of(new DocComment.Sql("UserMapper.selectUserList", "SELECT * FROM TB_USER WHERE DEPT_CD = #{deptCd}"))),
        d, Map.of("TB_USER", "사용자"), Map.of("DEPT_CD", "부서 코드"), tail);
    eq(mapper, """
        /**
         * 사용자 목록을 조회한다.
         *
         * 표: TB_USER(사용자)
         * 조건: 부서 코드(DEPT_CD)
         * SQL: UserMapper.selectUserList
         *
         * @param deptCd 부서 코드
         * @return 사용자 목록
         * @author jmlee
         * @since 2026-11-10
         */""");

    // DB 주석이 없으면 사전 · 형식 이름으로
    String save = DocComment.forMethod(new DocComment.Method("savePartDevSchedule",
            List.of(new DocComment.Param("vo", "PartDevScheduleVo")), "int", List.of("java.io.IOException"), null, List.of()),
        d, Map.of(), Map.of(), new DocComment.Tail(null, ""));
    eq(save, """
        /**
         * 부품 개발 일정을 저장한다.
         *
         * @param vo 부품 개발 일정
         * @return 처리 건수
         * @throws IOException 처리 중 오류가 나면
         */""");

    // 컨트롤러: 주소 줄, 화면 이름, 스프링 형식 사전
    String ctrl = DocComment.forMethod(new DocComment.Method("userList",
            List.of(new DocComment.Param("model", "Model"), new DocComment.Param("deptCd", "String")), "String", List.of(), "GET /user/list", List.of()),
        d, Map.of(), Map.of(), new DocComment.Tail("", null));
    eq(ctrl, """
        /**
         * 사용자 목록을 처리한다.
         *
         * 주소: GET /user/list
         *
         * @param model 화면 모델
         * @param deptCd 부서 코드
         * @return 화면 이름
         */""");

    eq(DocComment.returnText(new DocComment.Method("existsUser", List.of(), "boolean", List.of(), null, List.of()), d), "사용자 여부");
    eq(DocComment.returnText(new DocComment.Method("countUser", List.of(), "int", List.of(), null, List.of()), d), "사용자 건수");
    eq(DocComment.returnText(new DocComment.Method("getUser", List.of(), "UserVo", List.of(), null, List.of()), d), "사용자");
    eq(DocComment.returnText(new DocComment.Method("getAll", List.of(), "List<MenuVo>", List.of(), null, List.of()), d), "전체 목록");
    eq(DocComment.returnText(new DocComment.Method("run", List.of(), "void", List.of(), null, List.of()), d), null);
    eq(DocComment.returnText(new DocComment.Method("search", List.of(), "List<MenuVo>", List.of(), null, List.of()), d), "메뉴 목록");

    eq(DocComment.forClass("UserServiceImpl", d, tail), """
        /**
         * 사용자 서비스 구현.
         *
         * @author jmlee
         * @since 2026-11-10
         */""");

    // 주석을 닫는 글은 바꾼다
    String closing = DocComment.forMethod(new DocComment.Method("getA", List.of(new DocComment.Param("a", "String")), "void", List.of(), null, List.of()),
        d, Map.of(), Map.of("A", "끝 */ 표시"), new DocComment.Tail(null, null));
    if (closing.indexOf("*/") != closing.length() - 2) throw new AssertionError(closing);

    // Git 사용자 이름
    eq(DocComment.parseUserName("[core]\n\tname = x\n[user]\n\tname = \"Lee Jm\" ; 주석\n\temail = a@b\n"), "Lee Jm");
    eq(DocComment.parseUserName("[User]\nName=jmlee # c\n[user]\nname = 이정민\n"), "이정민");
    eq(DocComment.parseUserName("[user]\nemail = a@b\n"), null);
    Path tmp = Files.createTempDirectory("doccheck");
    try {
      Path repo = tmp.resolve("repo");
      Files.createDirectories(repo.resolve(".git"));
      Files.createDirectories(repo.resolve("src/main"));
      Path home = tmp.resolve("home");
      Files.createDirectories(home);
      Files.writeString(home.resolve(".gitconfig"), "[user]\n name = 홈이름\n");
      eq(DocComment.gitUserName(repo.resolve("src/main"), home), "홈이름");
      Files.writeString(repo.resolve(".git/config"), "[user]\n name = 저장소이름\n");
      eq(DocComment.gitUserName(repo.resolve("src/main"), home), "저장소이름");
      // 작업 트리: .git 파일이 가리키는 곳, 없으면 commondir 의 설정
      Path wt = tmp.resolve("wt");
      Files.createDirectories(wt);
      Path wtGit = repo.resolve(".git/worktrees/wt");
      Files.createDirectories(wtGit);
      Files.writeString(wtGit.resolve("commondir"), "../..\n");
      Files.writeString(wt.resolve(".git"), "gitdir: " + wtGit + "\n");
      eq(DocComment.gitUserName(wt, home), "저장소이름");
      eq(DocComment.gitUserName(tmp.resolve("home"), null), null);
    } finally {
      try (var walk = Files.walk(tmp)) {
        for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
    }

    // 매퍼 XML 아이디 주석
    String xml = """
        <mapper namespace="com.x.UserMapper">
          <select id="selectUserList" resultType="map">
            SELECT /*+ INDEX(A IX_USER) */ A.* FROM TB_USER A
          </select>
          <insert id="insertUser"><![CDATA[
            insert into TB_USER (ID) values (#{id})
          ]]></insert>
          <update id="updateUser">
            UPDATE /* UserMapper.updateUser */ TB_USER SET NM = #{nm}
          </update>
          <delete id="deleteUser" parameterType="a>b">
            <!-- 설명 -->
            <include refid="del"/> WHERE ID = #{id}
          </delete>
          <select id="empty"/>
        </mapper>
        """;
    MapperDoc md = MapperDoc.parse(xml);
    StringBuilder b = new StringBuilder(xml);
    List<SqlIdComment.Insert> plan = SqlIdComment.plan(xml, md, "UserMapper");
    eq(plan.size(), 3);
    for (int i = plan.size() - 1; i >= 0; i--) b.insert(plan.get(i).offset(), plan.get(i).text());
    String done = b.toString();
    if (!done.contains("<select id=\"selectUserList\" resultType=\"map\">\n    /* UserMapper.selectUserList */\n    SELECT /*+ INDEX(A IX_USER) */ A.*")) throw new AssertionError(done);
    if (!done.contains("<![CDATA[\n    /* UserMapper.insertUser */\n    insert into")) throw new AssertionError(done);
    if (!done.contains("<!-- 설명 -->\n    /* UserMapper.deleteUser */\n    <include refid=\"del\"/>")) throw new AssertionError(done);
    // SQL 이 태그와 같은 줄이면 그 앞에 붙인다
    String one = "<mapper namespace=\"a.B\"><select id=\"x\">SELECT 1</select></mapper>";
    List<SqlIdComment.Insert> onePlan = SqlIdComment.plan(one, MapperDoc.parse(one), "B");
    eq(onePlan.size(), 1);
    eq(new StringBuilder(one).insert(onePlan.get(0).offset(), onePlan.get(0).text()).toString(),
        "<mapper namespace=\"a.B\"><select id=\"x\">/* B.x */ SELECT 1</select></mapper>");
    // 두 번 돌려도 더 넣지 않는다
    eq(SqlIdComment.plan(done, MapperDoc.parse(done), "UserMapper").size(), 0);
    // namespace 가 없으면 파일 이름
    eq(SqlIdComment.label(MapperDoc.parse("<mapper><select id=\"a\">SELECT 1</select></mapper>"), "AMapper", "a"), "AMapper.a");

    System.out.println("DocCommentCheck 통과");
  }
}
