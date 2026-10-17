package ei.db;

import java.io.IOException;
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

  /** ~/.m2/repository/<groupPath> 아래에서 artifact 이름으로 시작하는 jar 중 경로가 가장 뒤인 것(대개 최신 판). 없으면 null. */
  public static Path findInMaven(DbKind kind) {
    Path root = Path.of(System.getProperty("user.home"), ".m2", "repository").resolve(kind.groupPath);
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

  public static Driver load(DbConfig c) throws ReflectiveOperationException, IOException {
    Path jar = jarFor(c);
    if (jar == null) {
      throw new IOException(c.kind.label + " 드라이버 jar 를 찾지 못했습니다. 접속 설정에서 jar 를 고르세요.");
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
}
