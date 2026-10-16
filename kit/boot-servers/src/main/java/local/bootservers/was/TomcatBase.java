package local.bootservers.was;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Tomcat 설치 폴더(CATALINA_HOME)는 건드리지 않고, 서버마다 따로 쓰는 실행 폴더(CATALINA_BASE)를 꾸린다.
 * 프로젝트는 복사하지 않고 컨텍스트 파일로 연결한다. 웹 폴더를 그대로 docBase 로 쓰고,
 * 컴파일 결과 폴더는 /WEB-INF/classes 로, 의존 jar 는 /WEB-INF/lib/이름.jar 로 얹는다(Tomcat 8.5 이상의 Resources 설정).
 * IDE 와 무관한 순수 자바라서 단독으로 시험할 수 있다.
 */
public final class TomcatBase {

    /** 올릴 앱 하나. contextPath 는 "/" 또는 "/로 시작하는 경로". */
    public record App(String contextPath, Path docBase, List<Path> classDirs, List<Path> jars) {
    }

    private static final Pattern SERVER_PORT = Pattern.compile("(<Server\\s+port=\")-?\\d+(\")");
    // 기본 server.xml 의 첫 HTTP 연결자. 주석 안의 연결자는 앞에서 주석을 지운 뒤에 찾으므로 걸리지 않는다.
    private static final Pattern HTTP_CONNECTOR = Pattern.compile("(<Connector\\s+port=\")\\d+(\"\\s+protocol=\"HTTP/1\\.1\")");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private TomcatBase() {
    }

    /** lib/catalina.jar 안의 판 정보. Tomcat 설치 폴더가 아니면 null. 예: "Apache Tomcat/9.0.98" */
    public static String detectVersion(Path home) {
        Path jar = home.resolve("lib").resolve("catalina.jar");
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("org/apache/catalina/util/ServerInfo.properties");
            if (entry == null) {
                return null;
            }
            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                props.load(in);
            }
            return props.getProperty("server.info");
        }
        catch (IOException e) {
            return null;
        }
    }

    /**
     * 실행 폴더를 새로 꾸민다. conf 는 매번 설치 폴더 것으로 다시 만들고 포트만 바꾼다.
     * 앱 컨텍스트 파일도 매번 다시 쓰므로, 빠진 앱은 다음 시작부터 올라가지 않는다.
     *
     * @throws IOException server.xml 에서 HTTP 연결자를 찾지 못했거나 파일을 쓰지 못한 경우
     */
    public static void prepare(Path home, Path base, int httpPort, int shutdownPort, List<App> apps, boolean reloadable)
            throws IOException {
        for (String dir : List.of("conf", "logs", "temp", "work", "webapps")) {
            Files.createDirectories(base.resolve(dir));
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(home.resolve("conf"), Files::isRegularFile)) {
            for (Path f : files) {
                Files.copy(f, base.resolve("conf").resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path serverXml = base.resolve("conf").resolve("server.xml");
        Files.writeString(serverXml, patchServerXml(Files.readString(serverXml, StandardCharsets.UTF_8), httpPort, shutdownPort),
                StandardCharsets.UTF_8);

        Path contexts = base.resolve("conf").resolve("Catalina").resolve("localhost");
        deleteTree(contexts);
        Files.createDirectories(contexts);
        for (App app : apps) {
            Files.writeString(contexts.resolve(contextFileName(app.contextPath()) + ".xml"), contextXml(app, reloadable),
                    StandardCharsets.UTF_8);
        }
    }

    static String patchServerXml(String xml, int httpPort, int shutdownPort) throws IOException {
        String live = XML_COMMENT.matcher(xml).replaceAll("");
        Matcher connector = HTTP_CONNECTOR.matcher(live);
        if (!connector.find()) {
            throw new IOException("server.xml 에서 HTTP/1.1 연결자(<Connector port=\"..\" protocol=\"HTTP/1.1\")를 찾지 못했습니다.");
        }
        Matcher server = SERVER_PORT.matcher(live);
        if (!server.find()) {
            throw new IOException("server.xml 에서 <Server port=\"..\"> 를 찾지 못했습니다.");
        }
        live = server.replaceFirst("$1" + shutdownPort + "$2");
        return HTTP_CONNECTOR.matcher(live).replaceFirst("$1" + httpPort + "$2");
    }

    /** Tomcat 규칙: "/" → ROOT, "/a/b" → a#b */
    static String contextFileName(String contextPath) {
        String p = contextPath.replaceAll("^/+|/+$", "");
        return p.isEmpty() ? "ROOT" : p.replace('/', '#');
    }

    static String contextXml(App app, boolean reloadable) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Context docBase=\"").append(attr(app.docBase())).append("\" reloadable=\"").append(reloadable).append("\">\n");
        sb.append("  <Resources>\n");
        for (Path dir : app.classDirs()) {
            sb.append("    <PreResources className=\"org.apache.catalina.webresources.DirResourceSet\" base=\"")
                    .append(attr(dir)).append("\" webAppMount=\"/WEB-INF/classes\"/>\n");
        }
        for (Path jar : app.jars()) {
            sb.append("    <PostResources className=\"org.apache.catalina.webresources.FileResourceSet\" base=\"")
                    .append(attr(jar)).append("\" webAppMount=\"/WEB-INF/lib/").append(attr(jar.getFileName())).append("\"/>\n");
        }
        sb.append("  </Resources>\n</Context>\n");
        return sb.toString();
    }

    private static String attr(Path p) {
        return p.toString().replace('\\', '/').replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
