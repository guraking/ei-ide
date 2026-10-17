package ei.db.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 콘솔 글 · 실행 기록 파일 규칙. IntelliJ 클래스를 쓰지 않는다(test 의 ConsoleFilesCheck 가 점검).
 * 콘솔 파일 이름은 접속 id 의 첫 콘솔이 「id.sql」, n 번째가 「id-n.sql」이고, 프로젝트마다 하위 폴더(projectDir)에 둔다.
 * 예전에는 프로젝트 구분 없이 root 에 바로 두었다(migrate 가 프로젝트마다 복사한다).
 */
final class ConsoleFiles {
  private ConsoleFiles() {}

  private static final String SUFFIX = ".sql";

  /** id 에서 파일 이름에 쓸 수 없는 글자는 _ 로 바꾼다(id 는 보통 UUID 라 그대로 남는다). */
  static Path consolePath(Path dir, String id, int n) {
    String safe = safe(id);
    return dir.resolve(n == 1 ? safe + SUFFIX : safe + "-" + n + SUFFIX);
  }

  /** 프로젝트 구분 글(프로젝트 locationHash)로 만든 root 아래 폴더. IDE 창(프로젝트) 둘이 같은 파일을 덮지 않게 한다. */
  static Path projectDir(Path root, String projectKey) {
    return root.resolve(safe(projectKey));
  }

  private static String safe(String name) {
    String s = name.replaceAll("[^A-Za-z0-9_.-]", "_");
    // 「.」 · 「..」 만 남으면 위 폴더를 가리키므로 막는다.
    return s.matches("\\.*") ? s.replace('.', '_') : s;
  }

  /**
   * 예전 자리(legacyDir 바로 아래 *.sql)의 콘솔 파일을 dir 로 복사한다. 예전 파일은 모든 프로젝트가 같이 쓰던 것이라 남겨 두고
   * 프로젝트마다 한 번씩 복사한다. dir 에 같은 이름이 이미 있으면 덮지 않는다. 복사한 파일 수를 돌려준다.
   */
  static int migrate(Path legacyDir, Path dir) throws IOException {
    if (!Files.isDirectory(legacyDir)) return 0;
    List<Path> old = new ArrayList<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(legacyDir, "*" + SUFFIX)) {
      for (Path p : files) {
        if (Files.isRegularFile(p)) old.add(p);
      }
    }
    int moved = 0;
    for (Path p : old) {
      Path target = dir.resolve(p.getFileName().toString());
      if (Files.exists(target)) continue;
      Files.createDirectories(dir);
      try {
        Files.copy(p, target);
        moved++;
      } catch (FileAlreadyExistsException e) {
        // 같은 프로젝트의 다른 콘솔이 같은 때에 먼저 복사했다.
      }
    }
    return moved;
  }

  /** used 다음 번호부터 파일이 없는 첫 번호. 남의 저장 글을 덮지 않을 자리를 찾는다. */
  static int freeSlot(Path dir, String id, int used) {
    int n = used + 1;
    while (Files.exists(consolePath(dir, id, n))) n++;
    return n;
  }

  /** UTF-8 글. 파일이 없으면 "". */
  static String read(Path file) throws IOException {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return "";
    }
  }

  /** 같은 폴더의 임시 파일에 쓰고 바꿔 끼워, 쓰다 끊겨도 반쯤 쓴 파일이 남지 않게 한다. 폴더가 없으면 만든다. */
  static void write(Path file, String text) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path temp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temp, text, StandardCharsets.UTF_8);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
