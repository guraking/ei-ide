package ei.db.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 콘솔 글 · 실행 기록 파일 규칙. IntelliJ 클래스를 쓰지 않는다(test 의 ConsoleFilesCheck 가 점검).
 * 콘솔 파일 이름은 접속 id 의 첫 콘솔이 「id.sql」, n 번째가 「id-n.sql」이다.
 */
final class ConsoleFiles {
  private ConsoleFiles() {}

  private static final String SUFFIX = ".sql";

  /** id 에서 파일 이름에 쓸 수 없는 글자는 _ 로 바꾼다(id 는 보통 UUID 라 그대로 남는다). */
  static Path consolePath(Path dir, String id, int n) {
    String safe = id.replaceAll("[^A-Za-z0-9_.-]", "_");
    return dir.resolve(n == 1 ? safe + SUFFIX : safe + "-" + n + SUFFIX);
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
