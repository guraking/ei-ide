package ei.db.console;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** 다른 묶음에서 XlsxWriter 로 엑셀 한 장을 쓰는 입구. 형식은 XlsxWriter.write 와 같다. */
public final class XlsxExport {
  private XlsxExport() {}

  private static final String TEMP_PREFIX = ".ei-xlsx-";
  private static final String TEMP_SUFFIX = ".tmp";

  /**
   * comments 가 null 이면 주석 행 없이 머리 행 하나만 둔다.
   * 같은 폴더의 임시 파일에 다 쓴 뒤 path 로 옮긴다: 쓰다 실패하면 원래 있던 path 는 그대로 남고 임시 파일은 지운다.
   */
  public static void write(Path path, List<String> columns, List<String> comments, List<List<Object>> rows) throws IOException {
    Path dir = path.toAbsolutePath().getParent();
    Path temp = Files.createTempFile(dir, TEMP_PREFIX, TEMP_SUFFIX);
    try {
      XlsxWriter.write(temp, columns, comments, rows);
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException e) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }
}
