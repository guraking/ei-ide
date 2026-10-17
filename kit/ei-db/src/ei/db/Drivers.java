package ei.db;

import com.intellij.util.io.HttpRequests;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
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
  private static final String HTTPS = "https://";
  private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
  private static final Pattern RELEASE = Pattern.compile("<release>\\s*([^<\\s]+)\\s*</release>");
  private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
  private static final Pattern MIRROR = Pattern.compile("<mirror>(.*?)</mirror>", Pattern.DOTALL);
  private static final Pattern MIRROR_OF = Pattern.compile("<mirrorOf>\\s*([^<]+?)\\s*</mirrorOf>");
  private static final Pattern MIRROR_URL = Pattern.compile("<url>\\s*([^<]+?)\\s*</url>");
  /** 판 번호는 경로 · 주소에 그대로 들어가므로 이 글자만 받는다(.. 은 따로 거절). */
  private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z._-]+");
  private static final Pattern VERSION_PART = Pattern.compile("[.-]");
  private static final Pattern DIGITS = Pattern.compile("\\d+");
  private static final Pattern XML_ENCODING = Pattern.compile("<\\?xml[^>]*encoding\\s*=\\s*[\"']([\\w.-]+)[\"']");
  /** XML 선언을 찾아볼 앞부분 바이트 수. */
  private static final int XML_HEAD = 200;
  /** UTF-8 로 읽히지 않는 settings.xml 은 Windows 한글 기본 인코딩으로 본다. */
  private static final String KOREAN_WINDOWS = "MS949";

  private static Path m2Repository() {
    return Path.of(System.getProperty("user.home"), ".m2", "repository");
  }

  /**
   * ~/.m2/repository/<groupPath>/<artifact>/<판>/<artifact>-<판>.jar 중 판이 가장 높은 것(숫자 단위 비교). 없으면 null.
   * downloadArtifact 를 먼저 보고, 없으면 같은 접두어의 다른 artifact(ojdbc8 등)를 본다. 분류자 jar(-sources 등)는 보지 않는다.
   */
  public static Path findInMaven(DbKind kind) {
    Path group = m2Repository().resolve(kind.groupPath);
    Path best = newest(group.resolve(kind.downloadArtifact), kind.downloadArtifact);
    if (best != null || !Files.isDirectory(group)) return best;
    String bestArtifact = null;
    try (Stream<Path> dirs = Files.list(group)) {
      for (Path dir : (Iterable<Path>) dirs::iterator) {
        String artifact = dir.getFileName().toString();
        if (!artifact.startsWith(kind.artifactPrefix)) continue;
        Path jar = newest(dir, artifact);
        if (jar != null && (bestArtifact == null || compareVersions(artifact, bestArtifact) > 0)) {
          best = jar;
          bestArtifact = artifact;
        }
      }
    } catch (IOException e) {
      return null; // 읽지 못하는 폴더는 없는 것으로 보고 내려받기로 넘어간다.
    }
    return best;
  }

  /** root(artifact 폴더) 아래 <판>/<artifact>-<판>.jar 중 판이 가장 높은 것. 없으면 null. */
  private static Path newest(Path root, String artifact) {
    if (!Files.isDirectory(root)) return null;
    Path best = null;
    String bestVersion = null;
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path dir : (Iterable<Path>) dirs::iterator) {
        String version = dir.getFileName().toString();
        Path jar = dir.resolve(artifact + "-" + version + ".jar");
        if (Files.isRegularFile(jar) && (bestVersion == null || compareVersions(version, bestVersion) > 0)) {
          best = jar;
          bestVersion = version;
        }
      }
    } catch (IOException e) {
      return null; // 읽지 못하는 폴더는 없는 것으로 본다.
    }
    return best;
  }

  /** . · - 로 나눈 조각을 숫자끼리는 수로, 아니면 글자로 비교한다. 조각이 더 많은 쪽이 크다(1.0.1 > 1.0). */
  static int compareVersions(String a, String b) {
    String[] x = VERSION_PART.split(a);
    String[] y = VERSION_PART.split(b);
    for (int i = 0; i < Math.min(x.length, y.length); i++) {
      int c = DIGITS.matcher(x[i]).matches() && DIGITS.matcher(y[i]).matches()
          ? new BigInteger(x[i]).compareTo(new BigInteger(y[i]))
          : x[i].compareTo(y[i]);
      if (c != 0) return c;
    }
    return Integer.compare(x.length, y.length);
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
      try {
        jar = download(c.kind);
      } catch (IOException e) {
        throw new IOException("드라이버 jar 가 없어 내려받으려 했으나 실패했습니다: " + e.getMessage(), e);
      }
    }
    String key = jar.toAbsolutePath() + "|" + c.kind.driverClass;
    Driver cached = LOADED.get(key);
    if (cached != null) return cached;
    // 로더는 드라이버가 살아 있는 동안 계속 써야 하므로 닫지 않는다.
    URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, Drivers.class.getClassLoader());
    Class<?> type;
    try {
      type = Class.forName(c.kind.driverClass, true, loader);
    } catch (ClassNotFoundException e) {
      throw new ClassNotFoundException("드라이버 jar 를 찾지 못했습니다(접속 창의 내려받기): " + c.kind.driverClass + " 가 " + jar + " 에 없습니다.", e);
    }
    Driver driver = (Driver) type.getDeclaredConstructor().newInstance();
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

  /** maven-metadata.xml 의 release, 없으면 마지막 version. 판 번호에 허용하지 않는 글자 · .. 이 있으면 거절. */
  static String latestVersion(String metadata) throws IOException {
    Matcher r = RELEASE.matcher(metadata);
    String found = null;
    if (r.find()) {
      found = r.group(1);
    } else {
      Matcher v = VERSION.matcher(metadata);
      while (v.find()) found = v.group(1);
    }
    if (found == null) throw new IOException("드라이버 판 정보를 읽지 못했습니다.");
    if (!SAFE_VERSION.matcher(found).matches() || found.contains("..")) {
      throw new IOException("드라이버 판 번호가 올바르지 않습니다: " + found);
    }
    return found;
  }

  /** settings.xml 글: XML 선언의 encoding, 없거나 모르는 이름이면 UTF-8, UTF-8 로 읽히지 않으면 MS949. */
  static String decodeSettings(byte[] bytes) {
    Matcher m = XML_ENCODING.matcher(new String(bytes, 0, Math.min(bytes.length, XML_HEAD), StandardCharsets.ISO_8859_1));
    if (m.find() && Charset.isSupported(m.group(1))) return new String(bytes, Charset.forName(m.group(1)));
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      return new String(bytes, Charset.forName(KOREAN_WINDOWS));
    }
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
   * 저장소의 .sha1 과 맞지 않으면 버린다. 저장소 주소가 https 가 아니면 받지 않는다(중간에서 jar 를 바꿔치기할 수 있다).
   * IDE 프록시 설정을 따른다. 화면 스레드에서 부르지 않는다.
   */
  public static Path download(DbKind kind) throws IOException {
    Path settings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
    String base = repository(Files.isRegularFile(settings) ? decodeSettings(Files.readAllBytes(settings)) : null);
    if (!base.regionMatches(true, 0, HTTPS, 0, HTTPS.length())) {
      throw new IOException("저장소 주소가 https 가 아니라 내려받지 않습니다: " + base + " (~/.m2/settings.xml 의 mirror 를 확인하세요)");
    }
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
    try {
      Files.write(tmp, bytes);
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp); // 옮겼으면 이미 없다. 실패했을 때 반쪽 파일을 남기지 않는다.
    }
    return target;
  }
}
