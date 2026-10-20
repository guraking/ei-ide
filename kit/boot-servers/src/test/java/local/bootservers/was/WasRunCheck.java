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
        System.out.println("WasRunCheck 통과");
    }

    private static void ports() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
            check(RunSupport.portInUse(port), "듣고 있는 포트는 사용 중");
        }
        check(!RunSupport.portInUse(port), "닫은 포트는 빈 포트");
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
