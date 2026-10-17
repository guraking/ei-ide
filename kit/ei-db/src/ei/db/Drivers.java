package ei.db;

import com.intellij.util.io.HttpRequests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * JDBC 드라이버를 플러그인에 넣지 않고 사용자 PC 의 jar 에서 읽는다(사내망에서도 받을 것이 없게).
 * DriverManager 는 다른 클래스 로더의 드라이버를 거절하므로 Driver 객체를 직접 쓴다.
 */
public final class Drivers {
  private Drivers() {}

  private static final Map<String, Driver> LOADED = new ConcurrentHashMap<>();
  private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
  private static final Pattern RELEASE = Pattern.compile("<release>\\s*([^<\\s]+)\\s*</release>");
  private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
  private static final Pattern MIRROR = Pattern.compile("<mirror>(.*?)</mirror>", Pattern.DOTALL);
  private static final Pattern MIRROR_OF = Pattern.compile("<mirrorOf>\\s*([^<]+?)\\s*</mirrorOf>");
  private static final Pattern MIRROR_URL = Pattern.compile("<url>\\s*([^<]+?)\\s*</url>");

  private static Path m2Repository() {
    return Path.of(System.getProperty("user.home"), ".m2", "repository");
  }

  /** ~/.m2/repository/<groupPath> 아래에서 artifact 이름으로 시작하는 jar 중 경로가 가장 뒤인 것(대개 최신 판). 없으면 null. */
  public static Path findInMaven(DbKind kind) {
    Path root = m2Repository().resolve(kind.groupPath);
    if (!Files.isDirectory(root)) return null;
    try (Stream<Path> files = Files.walk(root, 6)) {
      return files.filter(p -> {
        String n = p.getFileName().toString();
        return n.startsWith(kind.artifactPrefix) && n.endsWith(".jar") && !n.contains("-sources") && !n.contains("-javadoc");
      }).max(Comparator.comparing(Path::toString)).orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /** 설정의 jar, 비었으면 ~/.m2 에서 찾은 jar. 못 찾으면 null. */
  public static Path jarFor(DbConfig c) {
    if (c.driverJar != null && !c.driverJar.isBlank()) {
      Path p = Path.of(c.driverJar);
      return Files.isRegularFile(p) ? p : null;
    }
    return findInMaven(c.kind);
  }

  /** 직접 고른 jar 가 없어졌으면 오류, 고르지 않았고 ~/.m2 에도 없으면 내려받는다. */
  public static Driver load(DbConfig c) throws ReflectiveOperationException, IOException {
    Path jar = jarFor(c);
    if (jar == null) {
      if (c.driverJar != null && !c.driverJar.isBlank()) {
        throw new IOException("고른 드라이버 jar 가 없습니다: " + c.driverJar);
      }
      jar = download(c.kind);
    }
    String key = jar.toAbsolutePath() + "|" + c.kind.driverClass;
    Driver cached = LOADED.get(key);
    if (cached != null) return cached;
    // 로더는 드라이버가 살아 있는 동안 계속 써야 하므로 닫지 않는다.
    URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, Drivers.class.getClassLoader());
    Driver driver = (Driver) Class.forName(c.kind.driverClass, true, loader).getDeclaredConstructor().newInstance();
    LOADED.put(key, driver);
    return driver;
  }

  /**
   * 회사 Nexus 같은 mirror 가 ~/.m2/settings.xml 에 있으면(mirrorOf 가 *, central, external:*) 그 주소, 없으면 Maven Central.
   * shortcut: mirror 인증(servers)은 쓰지 않는다, 인증이 필요한 사내 저장소를 써야 할 때 settings.xml 의 server 계정을 붙인다.
   */
  static String repository(String settingsXml) {
    if (settingsXml != null) {
      Matcher m = MIRROR.matcher(settingsXml);
      while (m.find()) {
        Matcher of = MIRROR_OF.matcher(m.group(1));
        Matcher url = MIRROR_URL.matcher(m.group(1));
        if (of.find() && url.find()) {
          String target = of.group(1);
          if (target.equals("*") || target.contains("central") || target.startsWith("external:")) {
            String u = url.group(1);
            return u.endsWith("/") ? u : u + "/";
          }
        }
      }
    }
    return MAVEN_CENTRAL;
  }

  /** maven-metadata.xml 의 release, 없으면 마지막 version. */
  static String latestVersion(String metadata) throws IOException {
    Matcher r = RELEASE.matcher(metadata);
    if (r.find()) return r.group(1);
    String last = null;
    Matcher v = VERSION.matcher(metadata);
    while (v.find()) last = v.group(1);
    if (last == null) throw new IOException("드라이버 판 정보를 읽지 못했습니다.");
    return last;
  }

  static String sha1(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 최신 판 드라이버를 내려받아 ~/.m2 의 Maven 자리에 둔다(다음부터 findInMaven 이 찾고 빌드 도구도 같이 쓴다).
   * 저장소의 .sha1 과 맞지 않으면 버린다. IDE 프록시 설정을 따른다. 화면 스레드에서 부르지 않는다.
   */
  public static Path download(DbKind kind) throws IOException {
    Path settings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
    String base = repository(Files.isRegularFile(settings) ? Files.readString(settings, StandardCharsets.UTF_8) : null);
    String dir = base + kind.groupPath + "/" + kind.downloadArtifact + "/";
    String version = latestVersion(HttpRequests.request(dir + "maven-metadata.xml").readString());
    String name = kind.downloadArtifact + "-" + version + ".jar";
    Path target = m2Repository().resolve(kind.groupPath).resolve(kind.downloadArtifact).resolve(version).resolve(name);
    if (Files.isRegularFile(target)) return target;
    String url = dir + version + "/" + name;
    byte[] bytes = HttpRequests.request(url).readBytes(null);
    String expected = HttpRequests.request(url + ".sha1").readString().trim().split("\\s+")[0];
    if (!expected.equalsIgnoreCase(sha1(bytes))) {
      throw new IOException("내려받은 드라이버의 SHA-1 이 저장소 값과 다릅니다: " + url);
    }
    Files.createDirectories(target.getParent());
    Path tmp = Files.createTempFile(target.getParent(), name, ".part");
    Files.write(tmp, bytes);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    return target;
  }
}
