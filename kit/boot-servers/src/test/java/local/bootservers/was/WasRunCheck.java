package local.bootservers.was;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

/** WAS 실행 보조(포트 · 인코딩 · 앱 폴더 차지 · JEUS 명령 · 비밀번호 파일) 자체 점검. 실패하면 예외로 끝난다. */
public final class WasRunCheck {

    public static void main(String[] args) throws Exception {
        ports();
        encoding();
        weblogicKeepsUserApp();
        jeusDeployQuotesPath();
        passwordFile();
        tomcatServerXml();
        exploded();
        System.out.println("WasRunCheck 통과");
    }

    private static void tomcatServerXml() throws IOException {
        String stock = "<Server port=\"8005\" shutdown=\"SHUTDOWN\">\n<!-- <Connector port=\"8080\" protocol=\"HTTP/1.1\"/> -->\n"
                + "<Connector port=\"8080\" protocol=\"HTTP/1.1\" redirectPort=\"8443\"/>\n<Connector port=\"8009\" protocol=\"AJP/1.3\"/>\n</Server>";
        String out = TomcatBase.patchServerXml(stock, 9090, 9005);
        check(out.contains("<Server port=\"9005\"") && out.contains("<Connector port=\"9090\" protocol=\"HTTP/1.1\" redirectPort=\"8443\"/>")
                && out.contains("port=\"8009\" protocol=\"AJP/1.3\""), "기본 server.xml");
        String edited = "<Server shutdown=\"SHUTDOWN\"\n  port='8005'>\n<Connector port=\"8443\" protocol=\"org.apache.coyote.http11.Http11NioProtocol\" SSLEnabled=\"true\"/>\n"
                + "<Connector\n   protocol=\"org.apache.coyote.http11.Http11NioProtocol\"\n   port=\"8080\" connectionTimeout=\"20000\"/>\n</Server>";
        out = TomcatBase.patchServerXml(edited, 9090, 9005);
        check(out.contains("port='9005'") && out.contains("port=\"8443\"") && out.contains("port=\"9090\" connectionTimeout"), "속성 순서 · 구현 클래스 이름 · SSL 건너뜀");
        check(TomcatBase.patchServerXml("<Server port=\"8005\"><Connector port=\"8080\"/></Server>", 1, 2).contains("<Connector port=\"1\"/>"), "protocol 없으면 HTTP/1.1");
        boolean failed = false;
        try {
            TomcatBase.patchServerXml("<Server port=\"8005\"><Connector port=\"8009\" protocol=\"AJP/1.3\"/></Server>", 1, 2);
        }
        catch (IOException e) {
            failed = true;
        }
        check(failed, "HTTP 연결자 없으면 실패");
        // CP949 주석이 든 server.xml 도 읽고, 주석 밖 바이트는 그대로 둔다.
        Path home = Files.createTempDirectory("tc-home");
        Files.createDirectories(home.resolve("conf"));
        byte[] korean = "<!-- 회사 설정 -->".getBytes("MS949");
        byte[] rest = "<Server port=\"8005\"><Connector port=\"8080\" protocol=\"HTTP/1.1\"/><Valve pattern=\"".getBytes("ISO-8859-1");
        byte[] tail = "한\"/></Server>".getBytes("MS949");
        Files.write(home.resolve("conf/server.xml"), concat(korean, rest, tail));
        Path base = Files.createTempDirectory("tc-base");
        TomcatBase.prepare(home, base, 9090, 9005, List.of(), true);
        byte[] written = Files.readAllBytes(base.resolve("conf/server.xml"));
        String asLatin = new String(written, "ISO-8859-1");
        check(asLatin.contains("port=\"9090\"") && asLatin.endsWith(new String(tail, "ISO-8859-1")), "CP949 server.xml 바이트 유지");
    }

    private static void exploded() throws IOException {
        Path web = Files.createTempDirectory("sync-src");
        Files.writeString(web.resolve("a.txt"), "a");
        Files.writeString(web.resolve("b.txt"), "b");
        Path war = Files.createTempDirectory("sync-war");
        // a.txt 자리에 비지 않은 폴더가 있어 덮어쓸 수 없다(잠긴 jar 대신).
        Files.createDirectories(war.resolve("a.txt"));
        Files.writeString(war.resolve("a.txt").resolve("x"), "x");
        boolean partial = false;
        try {
            ExplodedSync.sync(new TomcatBase.App("/p", web, List.of(), List.of()), war);
        }
        catch (ExplodedSync.Partial e) {
            partial = e.changed && e.getMessage().contains("a.txt");
        }
        check(partial, "한 파일이 실패해도 Partial(changed)");
        check(Files.readString(war.resolve("b.txt")).equals("b"), "실패한 파일 뒤의 파일도 복사");
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static void ports() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
            check(RunSupport.portInUse(port), "듣고 있는 포트는 사용 중");
        }
        check(!RunSupport.portInUse(port), "닫은 포트는 빈 포트");
        check(!RunSupport.httpAnswers(port), "닫은 포트는 답 없음");
        // 묶기만 하고 답하지 않는 포트(Tomcat 이 앱을 올리는 중)는 답 없음, 한 바이트라도 답하면 답함.
        try (ServerSocket silent = new ServerSocket(0)) {
            check(!RunSupport.httpAnswers(silent.getLocalPort()), "묶기만 한 포트는 답 없음");
        }
        try (ServerSocket http = new ServerSocket(0)) {
            Thread answer = new Thread(() -> {
                try (java.net.Socket c = http.accept()) {
                    c.getOutputStream().write("HTTP/1.0 404 Not Found\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
                catch (IOException ignored) {
                    // 점검 끝에 닫혀도 된다.
                }
            });
            answer.start();
            check(RunSupport.httpAnswers(http.getLocalPort()), "답하는 포트");
        }
    }

    private static void encoding() {
        check(RunSupport.encodingOptions("").equals("-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"), "빈 옵션은 넷 다");
        check(!RunSupport.encodingOptions("").contains("file.encoding"), "file.encoding 은 넣지 않음");
        check(RunSupport.encodingOptions("-Xmx1g -Dstdout.encoding=MS949").equals("-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dstderr.encoding=UTF-8"),
                "사용자가 준 값은 덮지 않음");
        check(RunSupport.encodingOptions("-Dsun.stdout.encoding=UTF-8").contains("-Dstdout.encoding=UTF-8"), "sun. 접두는 다른 옵션");
    }

    private static void weblogicKeepsUserApp() throws IOException {
        Path root = Files.createTempDirectory("wl-check");
        Path web = Files.createDirectories(root.resolve("src/webapp"));
        Files.writeString(web.resolve("index.jsp"), "new");
        TomcatBase.App app = new TomcatBase.App("/myapp", web, List.of(), List.of());

        Path domain = root.resolve("domain");
        Path userApp = Files.createDirectories(domain.resolve("autodeploy/myapp.war/WEB-INF"));
        Files.writeString(userApp.resolve("web.xml"), "user");
        try {
            WeblogicBase.prepare(domain, List.of(app));
            throw new AssertionError("표시 없는 같은 이름 앱 폴더면 멈춰야 함");
        }
        catch (IOException expected) {
            // 멈추는 것이 맞다.
        }
        check(Files.readString(userApp.resolve("web.xml")).equals("user"), "사용자 앱은 그대로");
        check(Files.notExists(domain.resolve("autodeploy/myapp.war/index.jsp")), "사용자 앱에 복사하지 않음");

        Path archive = domain.resolve("autodeploy/other.war");
        Files.writeString(archive, "zip");
        try {
            WeblogicBase.publish(domain, new TomcatBase.App("/other", web, List.of(), List.of()));
            throw new AssertionError("같은 이름의 war 파일이 있으면 멈춰야 함");
        }
        catch (IOException expected) {
            // 멈추는 것이 맞다.
        }
        check(Files.readString(archive).equals("zip"), "war 파일은 그대로");

        Path empty = Files.createDirectories(domain.resolve("autodeploy/fresh.war"));
        WeblogicBase.prepare(domain, List.of(new TomcatBase.App("/fresh", web, List.of(), List.of())));
        check(Files.exists(empty.resolve("WEB-INF/.kinggura")) && Files.exists(empty.resolve("index.jsp")), "빈 폴더는 차지해 맞춤");
        WeblogicBase.prepare(domain, List.of(new TomcatBase.App("/fresh", web, List.of(), List.of())));
        check(Files.exists(empty.resolve("index.jsp")), "자기 폴더는 다시 맞춤");
    }

    private static void jeusDeployQuotesPath() {
        List<String> argv = JeusBase.command(JeusBase.DEFAULT_DEPLOY, Map.of(
                "home", "/opt/jeus", "host", "localhost", "port", "9736", "user", "admin", "password", "pw",
                "path", "/tmp/my apps/ROOT.war", "name", "ROOT", "server", "adminServer", "ctx", "/"), false);
        check(argv.get(argv.size() - 1).equals("deploy-application -path \"/tmp/my apps/ROOT.war\" -id ROOT -servers adminServer -cr /"),
                "배포 경로를 안쪽 따옴표로 감쌈: " + argv);
    }

    private static void passwordFile() throws IOException {
        Path file = Files.createTempDirectory("jeus-check").resolve("sub/jeus-password");
        JeusBase.writePasswordFile(file, "비밀 1");
        check(Files.readString(file).equals("비밀 1"), "비밀번호 내용");
        // POSIX 를 지원하지 않는 파일 시스템(윈도우 NTFS)에서는 ACL 항목이 소유자 하나뿐인지 본다.
        if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
            check(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)).equals("rw-------"), "사용자만 읽고 씀");
        }
        else {
            List<AclEntry> acl = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
            check(acl.size() == 1 && acl.get(0).principal().equals(Files.getOwner(file)), "소유자만 접근: " + acl);
        }
        JeusBase.writePasswordFile(file, "새것");
        check(Files.readString(file).equals("새것"), "다시 쓰면 새 내용");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
