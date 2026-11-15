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

    // 여는 태그 하나(속성 순서 · 줄바꿈은 가리지 않는다). 주석 안의 태그는 앞에서 주석을 지운 뒤에 찾으므로 걸리지 않는다.
    private static final Pattern SERVER_TAG = Pattern.compile("<Server\\b[^>]*>");
    private static final Pattern CONNECTOR_TAG = Pattern.compile("<Connector\\b[^>]*>");
    private static final Pattern PORT_ATTR = Pattern.compile("(\\bport\\s*=\\s*[\"'])-?\\d+([\"'])");
    private static final Pattern PROTOCOL_ATTR = Pattern.compile("\\bprotocol\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern SSL_ATTR = Pattern.compile("\\bSSLEnabled\\s*=\\s*[\"']true[\"']", Pattern.CASE_INSENSITIVE);
    // HTTP 연결자의 protocol 값: 기본 「HTTP/1.1」 또는 구현 클래스 이름(org.apache.coyote.http11.Http11NioProtocol 등). 없으면 HTTP/1.1 이다.
    private static final Pattern HTTP_PROTOCOL = Pattern.compile("HTTP/1\\.1|http11", Pattern.CASE_INSENSITIVE);
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
        // 회사에서 고친 server.xml 은 한국어 주석이 CP949 로 저장돼 있을 수 있다. 바꾸는 것은 ASCII 숫자뿐이므로 바이트를 그대로 옮기는 ISO-8859-1 로 읽고 쓴다.
        Files.writeString(serverXml, patchServerXml(Files.readString(serverXml, StandardCharsets.ISO_8859_1), httpPort, shutdownPort),
                StandardCharsets.ISO_8859_1);

        Path contexts = contextDir(base);
        deleteTree(contexts);
        Files.createDirectories(contexts);
        for (App app : apps) {
            Files.writeString(contexts.resolve(contextFileName(app.contextPath()) + ".xml"), contextXml(app, reloadable),
                    StandardCharsets.UTF_8);
        }
    }

    /** 앱 컨텍스트 파일 폴더(conf/Catalina/localhost). 이 파일의 수정 시각이 바뀌면 Tomcat(autoDeploy)이 그 앱을 다시 올린다. */
    public static Path contextDir(Path base) {
        return base.resolve("conf").resolve("Catalina").resolve("localhost");
    }

    /**
     * 종료 포트(&lt;Server port&gt;)와 첫 HTTP 연결자(SSL 이 아닌, protocol 이 HTTP/1.1 · Http11… 이거나 없는 &lt;Connector&gt;)의 포트를 바꾼다.
     * 속성 순서 · 따옴표 · 줄바꿈은 가리지 않는다. 주석은 지운 채 돌려준다.
     */
    static String patchServerXml(String xml, int httpPort, int shutdownPort) throws IOException {
        String live = XML_COMMENT.matcher(xml).replaceAll("");
        Matcher connector = CONNECTOR_TAG.matcher(live);
        int start = -1;
        int end = -1;
        while (connector.find()) {
            String tag = connector.group();
            Matcher protocol = PROTOCOL_ATTR.matcher(tag);
            boolean http = !protocol.find() || HTTP_PROTOCOL.matcher(protocol.group(1)).find();
            if (http && !SSL_ATTR.matcher(tag).find() && PORT_ATTR.matcher(tag).find()) {
                start = connector.start();
                end = connector.end();
                break;
            }
        }
        if (start < 0) {
            throw new IOException("server.xml 에서 HTTP/1.1 연결자(<Connector port=\"..\" protocol=\"HTTP/1.1\")를 찾지 못했습니다.");
        }
        Matcher server = SERVER_TAG.matcher(live);
        if (!server.find() || !PORT_ATTR.matcher(server.group()).find()) {
            throw new IOException("server.xml 에서 <Server port=\"..\"> 를 찾지 못했습니다.");
        }
        if (server.start() > start) {
            throw new IOException("server.xml 에서 <Server> 보다 앞에 <Connector> 가 있습니다.");
        }
        return live.substring(0, server.start()) + withPort(server.group(), shutdownPort)
                + live.substring(server.end(), start) + withPort(live.substring(start, end), httpPort) + live.substring(end);
    }

    private static String withPort(String tag, int port) {
        return PORT_ATTR.matcher(tag).replaceFirst("$1" + port + "$2");
    }

    /** Tomcat 규칙: "/" → ROOT, "/a/b" → a#b */
    public static String contextFileName(String contextPath) {
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
