package local.bootservers;

import java.util.List;

/** ErrorScan · ErrorReport · ErrorRules · SqlFill · LogFields 자체 점검. 실패하면 예외로 끝난다. */
public final class ErrorScanCheck {

    public static void main(String[] args) {
        rules();
        fields();
        sqlFill();
        npeWithOurFrame();
        mybatisWithRequest();
        grouping();
        startupFailure();
        printStackTrace();
        warnResolved();
        limits();
        chunks();
        fieldFilter();
        sourceFind();
        System.out.println("ErrorScanCheck 통과 (설명 규칙 " + ErrorRules.size() + "개)");
    }

    private static void sourceFind() {
        // 스택 없는 log.error 한 줄: 찍은 로거 · 「파일 없음」 설명 · 스택 줄 없음.
        String line = "2026-11-14 18:42:23.213 ERROR --- com.sk.supp.SuppApplication              : [] "
                + "\"C:/dev/supp_ktnet/config/tradesign_local.properties\" 설정파일을 찾을 수가 없습니다.";
        ErrorScan scan = new ErrorScan();
        scan.feed(line + "\n2026-11-14 18:42:23.300 INFO --- com.sk.supp.SuppApplication : 다음\n");
        scan.flush();
        check(scan.items().size() == 1, "한 줄 오류 하나: " + scan.items().size());
        ErrorReport r = scan.items().get(0).last();
        check("com.sk.supp.SuppApplication".equals(r.logger()) && r.frame() == null && r.lines().size() == 1, "찍은 로거: " + r.logger());
        check(r.explain() != null && r.explain().title().equals("파일 없음")
                && r.explain().text().contains("C:/dev/supp_ktnet/config/tradesign_local.properties"), "파일 없음 설명: " + r.explain());
        check(r.mapperId() == null, "매퍼 id 없음");

        String source = "class A {\n  void a() {\n    log.info(\"시작: {}\", x);\n"
                + "    log.error(\"\\\"{}\\\" 설정파일을 찾을 수가 없습니다.\", path);\n    log.error(\": \");\n  }\n}\n";
        check(SourceFind.logLine(source, r.rootMessage()) == 3, "찍은 줄: " + SourceFind.logLine(source, r.rootMessage()));
        check(SourceFind.logLine(source, "전혀 다른 글") == -1, "맞는 문자열 없음");
        check(SourceFind.logLine("String s = String.format(\"주문 %d 건 실패: %s\", n, why);", "주문 3 건 실패: 재고 없음") == 0, "format 자리");

        check("com.x.UserMapper.selectUser".equals(SourceFind.mapperId(
                "### Error querying database.\n### The error may involve com.x.UserMapper.selectUser-Inline\n### SQL: SELECT 1")), "involve");
        check("com.x.UserMapper.selectUser".equals(SourceFind.mapperId(
                "org.apache.ibatis.binding.BindingException: Invalid bound statement (not found): com.x.UserMapper.selectUser")), "not found");
        check("com.x.UserMapper.selectUser".equals(SourceFind.mapperId(
                "Mapped Statements collection does not contain value for com.x.UserMapper.selectUser")), "collection");
        check(SourceFind.mapperId("java.lang.NullPointerException: x") == null, "매퍼 오류 아님");

        String xml = "<?xml version=\"1.0\"?>\n<mapper namespace=\"com.x.UserMapper\">\n  <select id=\"selectUserList\" resultType=\"map\">SELECT 1</select>\n"
                + "  <select id=\"selectUser\"\n          resultType=\"map\">SELECT 2</select>\n</mapper>";
        check(SourceFind.isMapperOf(xml, "com.x.UserMapper") && !SourceFind.isMapperOf(xml, "com.x.User"), "namespace");
        check(SourceFind.statementOffset(xml, "selectUser") == xml.indexOf("<select id=\"selectUser\""), "문장 자리");
        check(SourceFind.statementOffset(xml, "none") == -1, "없는 문장");
    }

    private static void rules() {
        check(ErrorRules.size() >= 100, "설명 규칙 100개 이상");
        ErrorRules.Explain e = ErrorRules.match("java.sql.SQLSyntaxErrorException: ORA-00942: table or view does not exist", "");
        check(e != null && e.title().equals("표 없음"), "ORA-00942");
        e = ErrorRules.match("Cannot invoke \"String.length()\" because \"name\" is null", "");
        check(e != null && e.text().startsWith("name 이(가) null 이라서 String.length()"), "NPE 변수 이름: " + e);
        e = ErrorRules.match("Invalid bound statement (not found): com.x.UserMapper.selectUser", "");
        check(e != null && e.text().contains("com.x.UserMapper.selectUser"), "MyBatis BindingException");
        e = ErrorRules.match("Web server failed to start. Port 8080 was already in use.", "");
        check(e != null && e.text().contains("8080"), "포트 사용 중");
        e = ErrorRules.match("ORA-12345: 이상한 오류", "");
        check(e != null && e.text().contains("ORA-12345") && e.text().contains("이상한 오류"), "Oracle 일반");
        check(ErrorRules.match("그냥 글", "") == null, "맞는 규칙 없음");
        check(ErrorRules.match(null, "Caused by: java.lang.NullPointerException") != null, "전체 글로 맞춤");
        check(ErrorRules.isFramework("org.springframework.web.Foo") && !ErrorRules.isFramework("com.company.user.UserService"), "프레임워크 판정");
    }

    private static void fields() {
        LogFields f = LogFields.of("2026-11-14T10:00:00.123+09:00 ERROR 1234 --- [demo] [nio-8080-exec-1] c.e.d.UserController   : 실패 [x]");
        check("nio-8080-exec-1".equals(f.thread()) && "c.e.d.UserController".equals(f.logger()), "스프링 부트 3: " + f);
        f = LogFields.of("2026-11-14 10:00:00.123 ERROR 1234 --- [nio-8080-exec-2] o.s.web.servlet.DispatcherServlet : x");
        check("nio-8080-exec-2".equals(f.thread()) && "o.s.web.servlet.DispatcherServlet".equals(f.logger()), "스프링 부트 2: " + f);
        f = LogFields.of("10:00:00.123 [http-nio-8080-exec-3] DEBUG com.x.mapper.UserMapper.select - ==>  Preparing: SELECT 1");
        check("http-nio-8080-exec-3".equals(f.thread()) && "com.x.mapper.UserMapper.select".equals(f.logger()), "logback: " + f);
        f = LogFields.of("2026-11-14 10:00:00 INFO [main] [app,6553d1d5c0a5a7e16553d1d5c0a5a7e1,6553d1d5c0a5a7e1] c.x.A - 시작");
        check("main".equals(f.thread()) && "6553d1d5c0a5a7e16553d1d5c0a5a7e1".equals(f.requestId()), "추적 ID: " + f);
        f = LogFields.of("10:00:00 INFO [main] c.x.A - traceId=abc-123 처리");
        check("abc-123".equals(f.requestId()), "이름 붙은 요청 ID: " + f);
        check(LogFields.of("\tat a.b.C.d(C.java:1)") == LogFields.NONE, "스택 줄은 칸 없음");
        check(LogFields.of("[ERROR] 실패").thread() == null, "레벨 대괄호는 스레드 아님");
    }

    private static void sqlFill() {
        check("SELECT * FROM A WHERE ID = 10 AND NM = 'O''Neil' AND X IS NULL".equals(
                SqlFill.fill("SELECT * FROM A WHERE ID = ? AND NM = ? AND X IS ?", "10(Integer), O'Neil(String), null")), "채우기");
        check("SELECT '?' FROM A WHERE B = 'x, y' -- ?\n AND C = 1".equals(
                SqlFill.fill("SELECT '?' FROM A WHERE B = ? -- ?\n AND C = ?", "x, y(String), 1(Long)")), "문자열 · 주석 안 ? · 값 안 쉼표");
        check(SqlFill.fill("SELECT ? FROM A", "1(Integer), 2(Integer)") == null, "수가 다르면 채우지 않음");
        check(SqlFill.fill("SELECT 1 FROM A", "") != null, "값 없음");
        check(SqlFill.fill("SELECT ? FROM A", null) == null, "값 줄 없음");
    }

    private static final String T = "2026-11-14 10:00:0";

    private static void npeWithOurFrame() {
        ErrorScan scan = new ErrorScan();
        scan.feed(T + "1.000 DEBUG 1 --- [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet : POST \"/api/user/save?mode=1\", parameters={masked}\n");
        scan.feed(T + "2.000 ERROR 1 --- [nio-8080-exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() threw exception\n");
        scan.feed("java.lang.NullPointerException: Cannot invoke \"String.trim()\" because \"name\" is null\n");
        scan.feed("\tat com.company.user.UserService.save(UserService.java:42)\n");
        scan.feed("\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1)\n");
        scan.feed(T + "3.000  INFO 1 --- [main] c.x.A : 다음\n");
        List<ErrorScan.Item> groups = scan.items();
        check(groups.size() == 1, "NPE 묶음 하나");
        ErrorReport r = groups.get(0).last();
        check("java.lang.NullPointerException".equals(r.rootClass()), "원인 예외: " + r.rootClass());
        check(r.frame() != null && r.frame().className().equals("com.company.user.UserService") && r.frame().line() == 42, "우리 코드 줄: " + r.frame());
        check(r.explain() != null && r.explain().text().contains("name"), "NPE 설명");
        check("POST".equals(r.method()) && "/api/user/save?mode=1".equals(r.path()) && r.params() == null, "요청: " + r.method() + " " + r.path());
        check("02.000".equals(r.time().substring(r.time().length() - 6)), "시각: " + r.time());
        check(r.lines().size() == 4, "묶음 줄 수: " + r.lines().size());
        String copy = r.copyText(null, 20);
        check(copy.contains("우리 코드: com.company.user.UserService.save(UserService.java:42)") && copy.contains("요청: POST /api/user/save"), "복사 글");
    }

    private static void mybatisWithRequest() {
        ErrorScan scan = new ErrorScan();
        scan.feed("10:00:00.100 [http-nio-8080-exec-5] DEBUG o.s.web.servlet.DispatcherServlet - GET \"/user/list\", parameters={id:[7]}\n");
        scan.feed("10:00:00.200 [http-nio-8080-exec-9] DEBUG com.x.OtherMapper.find - ==>  Preparing: SELECT * FROM OTHER WHERE A = ?\n");
        scan.feed("10:00:00.300 [http-nio-8080-exec-5] DEBUG com.x.UserMapper.select - ==>  Preparing: SELECT * FROM TB_USR WHERE ID = ? AND NM = ?\n");
        scan.feed("10:00:00.301 [http-nio-8080-exec-9] DEBUG com.x.OtherMapper.find - ==> Parameters: 1(Integer)\n");
        scan.feed("10:00:00.302 [http-nio-8080-exec-5] DEBUG com.x.UserMapper.select - ==> Parameters: 7(Long), 홍길동(String)\n");
        scan.feed("10:00:00.400 [http-nio-8080-exec-5] ERROR c.x.Handler - 처리 실패\n");
        scan.feed("org.springframework.jdbc.BadSqlGrammarException: \n### Error querying database.  Cause: java.sql.SQLSyntaxErrorException: ORA-00942: table or view does not exist\n");
        scan.feed("### SQL: SELECT * FROM TB_USR WHERE ID = ? AND NM = ?\n");
        scan.feed("\tat com.x.UserService.list(UserService.java:9)\n");
        scan.feed("Caused by: java.sql.SQLSyntaxErrorException: ORA-00942: table or view does not exist\n");
        scan.feed("\tat oracle.jdbc.Foo.bar(Foo.java:1)\n");
        scan.flush();
        ErrorReport r = scan.items().get(0).last();
        check(r.explain() != null && r.explain().title().equals("표 없음"), "ORA-00942 설명: " + r.explain());
        check("SELECT * FROM TB_USR WHERE ID = ? AND NM = ?".equals(r.sql()) && "7(Long), 홍길동(String)".equals(r.sqlParams()), "같은 스레드 SQL · 값: " + r.sqlParams());
        check("SELECT * FROM TB_USR WHERE ID = 7 AND NM = '홍길동'".equals(SqlFill.fill(r.sql(), r.sqlParams())), "채운 SQL");
        check("GET".equals(r.method()) && "/user/list".equals(r.path()) && "id:[7]".equals(r.params()), "요청 · 파라미터");
        check(r.frame() != null && r.frame().className().equals("com.x.UserService"), "원인 아래 우리 코드가 없으면 전체에서: " + r.frame());
        check("ORA-00942: table or view does not exist".equals(r.rootMessage()), "가장 안쪽 원인: " + r.rootMessage());
    }

    private static void grouping() {
        ErrorScan scan = new ErrorScan();
        for (int i = 0; i < 100; i++) {
            scan.feed(T + "1.000 ERROR 1 --- [exec-" + i + "] c.x.A : 실패 id=" + i + "\n");
            scan.feed("java.lang.IllegalStateException: 주문 " + i + " 없음\n\tat com.x.A.run(A.java:5)\n");
        }
        scan.feed(T + "1.000 ERROR 1 --- [main] c.x.B : 다른 실패\njava.lang.IllegalArgumentException: 다름\n\tat com.x.B.run(B.java:5)\n");
        scan.flush();
        List<ErrorScan.Item> groups = scan.items();
        check(groups.size() == 2 && groups.get(0).count() == 100 && groups.get(1).count() == 1, "같은 오류 묶기: " + groups.size());
        check(groups.get(0).last().lines().get(0).contains("exec-99"), "마지막 것 기억");
        check(scan.changes() == 101, "바뀐 횟수");
    }

    private static void startupFailure() {
        ErrorScan scan = new ErrorScan();
        scan.feed(T + "1.000 ERROR 1 --- [main] o.s.b.d.LoggingFailureAnalysisReporter : \n\n");
        scan.feed("***************************\nAPPLICATION FAILED TO START\n***************************\n\n");
        scan.feed("Description:\n\nWeb server failed to start. Port 8080 was already in use.\n\nAction:\n\n");
        scan.feed("Identify and stop the process that's listening on port 8080 or configure this application to listen on another port.\n\n");
        scan.feed(T + "2.000  WARN 1 --- [main] o.s.b.SpringApplication : 끝\n");
        ErrorReport r = scan.items().get(0).last();
        check(r.startup() && r.description().equals("Web server failed to start. Port 8080 was already in use."), "시작 실패 설명: " + r.description());
        check(r.action().startsWith("Identify and stop"), "할 일");
        check(r.explain() != null && r.explain().title().equals("포트 사용 중"), "포트 설명");
    }

    private static void printStackTrace() {
        ErrorScan scan = new ErrorScan();
        scan.feed("java.io.FileNotFoundException: C:\\a\\b.txt (지정된 파일을 찾을 수 없습니다)\n\tat java.base/java.io.FileInputStream.open0(Native Method)\n");
        scan.feed("\tat app//com.y.Reader.read(Reader.java:3)\n");
        scan.feed("\u001B[31m\u001B[0m");
        scan.feed("그냥 출력 Exception 이라는 낱말\n");
        scan.flush();
        check(scan.items().size() == 1, "글 안의 Exception 낱말은 새 오류 아님");
        ErrorReport r = scan.items().get(0).last();
        check(r.frame() != null && r.frame().className().equals("com.y.Reader") && r.frame().line() == 3, "모듈 접두 프레임: " + r.frame());
        check(r.explain() != null && r.explain().title().equals("파일 없음"), "파일 없음 설명");
        check(r.lines().size() == 3, "레벨 없이 시작한 묶음은 스택 뒤 보통 줄에서 끝남");
    }

    private static void warnResolved() {
        ErrorScan scan = new ErrorScan();
        scan.feed(T + "1.000  WARN 1 --- [exec-1] .w.s.m.s.DefaultHandlerExceptionResolver : Resolved [org.springframework.web.bind.MissingServletRequestParameterException: Required request parameter 'id' for method parameter type Long is not present]\n");
        scan.feed(T + "1.000  WARN 1 --- [exec-1] c.x.A : 그냥 경고\n");
        scan.flush();
        List<ErrorScan.Item> groups = scan.items();
        check(groups.size() == 1, "예외 든 WARN 만 오류: " + groups.size());
        check(groups.get(0).last().explain().title().equals("필수 파라미터 없음"), "필수 파라미터 설명");
    }

    private static void limits() {
        ErrorScan scan = new ErrorScan();
        scan.feed(T + "1.000 ERROR 1 --- [main] c.x.A : 긴 스택\njava.lang.RuntimeException: x\n");
        for (int i = 0; i < 10_000; i++) {
            scan.feed("\tat com.x.A.m" + i + "(A.java:1)\n");
        }
        scan.feed("x".repeat(100_000) + "\n");
        scan.flush();
        check(scan.items().get(0).last().lines().size() == ErrorScan.BLOCK_LINES, "묶음 줄 한도");
        ErrorScan many = new ErrorScan();
        for (int i = 0; i < ErrorScan.MAX_GROUPS + 50; i++) {
            many.feed("ERROR c.x.A : 실패\njava.lang.RuntimeException: x\n\tat com.x.A.m" + i + "(A.java:1)\n");
        }
        many.flush();
        check(many.items().size() == ErrorScan.MAX_GROUPS, "오류 종류 한도");
        check(many.items().get(0).last().frame().method().equals("m50"), "오래된 것부터 지움");
        ErrorScan longLine = new ErrorScan();
        longLine.feed("ERROR " + "y".repeat(ErrorScan.MAX_LINE_CHARS * 3));
        longLine.flush();
        check(longLine.items().get(0).last().lines().get(0).length() <= ErrorScan.MAX_LINE_CHARS, "줄 길이 한도");
        new ErrorScan().flush();
    }

    private static void chunks() {
        ErrorScan scan = new ErrorScan();
        String text = T + "1.000 ERROR 1 --- [main] c.x.A : 실패\r\njava.lang.IllegalStateException: 상태\r\n\tat com.x.A.run(A.java:5)\r\n";
        for (char c : text.toCharArray()) {
            scan.feed(String.valueOf(c));
        }
        scan.flush();
        ErrorReport r = scan.items().get(0).last();
        check(r.lines().size() == 3 && !r.lines().get(0).endsWith("\r") && r.frame().line() == 5, "한 글자씩 · CRLF");
    }

    private static void fieldFilter() {
        String log = String.join("\n",
                "10:00:00 [exec-1] INFO c.x.A - traceId=req-1 시작",
                "10:00:01 [exec-2] INFO c.x.B - traceId=req-2 시작",
                "10:00:02 [exec-1] ERROR c.x.A - traceId=req-1 실패",
                "java.lang.IllegalStateException: x",
                "\tat c.x.A.run(A.java:1)",
                "10:00:03 [exec-2] DEBUG c.x.C - traceId=req-2 끝");
        java.util.Set<LogFilter.Level> all = java.util.EnumSet.allOf(LogFilter.Level.class);
        List<LogFilter.Line> r1 = LogFilter.filterFrom(log, LogFilter.Resume.START, all, "", false,
                List.of(new LogFilter.FieldRule(LogFilter.Field.REQUEST, "req-1", true))).lines();
        check(r1.size() == 4 && r1.get(0).number() == 0 && r1.get(1).number() == 2 && r1.get(3).number() == 4, "같은 요청 따라가기(스택 포함): " + r1);
        List<LogFilter.Line> noB = LogFilter.filterFrom(log, LogFilter.Resume.START, all, "", false,
                List.of(new LogFilter.FieldRule(LogFilter.Field.LOGGER, "c.x.B", false))).lines();
        check(noB.size() == 5, "로거 빼기: " + noB.size());
        List<LogFilter.Line> two = LogFilter.filterFrom(log, LogFilter.Resume.START, all, "", false,
                List.of(new LogFilter.FieldRule(LogFilter.Field.THREAD, "exec-1", true), new LogFilter.FieldRule(LogFilter.Field.THREAD, "exec-2", true),
                        new LogFilter.FieldRule(LogFilter.Field.LOGGER, "c.x.C", false))).lines();
        check(two.size() == 5, "같은 칸 이것만 둘은 또는 · 다른 칸 빼기는 그리고: " + two.size());
        check(LogFilter.filterFrom(log, LogFilter.Resume.START, all, "", false).lines().size() == 6, "규칙 없으면 그대로");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
