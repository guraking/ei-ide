package ei.db.console;

import java.nio.file.Files;
import java.nio.file.Path;

public class ConsoleFilesCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("consoles").resolve("ei-db").resolve("consoles");
    String id = "0f8e-11aa";
    eq(ConsoleFiles.consolePath(dir, id, 1).getFileName().toString(), "0f8e-11aa.sql");
    eq(ConsoleFiles.consolePath(dir, id, 3).getFileName().toString(), "0f8e-11aa-3.sql");
    eq(ConsoleFiles.consolePath(dir, "../a/b:c", 1).getFileName().toString(), ".._a_b_c.sql");
    eq(ConsoleFiles.consolePath(dir, "../a", 1).getParent(), dir);

    eq(ConsoleFiles.read(ConsoleFiles.consolePath(dir, id, 1)), "");
    eq(ConsoleFiles.freeSlot(dir, id, 0), 1);
    ConsoleFiles.write(ConsoleFiles.consolePath(dir, id, 1), "SELECT 1\nFROM DUAL;\n한글");
    eq(ConsoleFiles.read(ConsoleFiles.consolePath(dir, id, 1)), "SELECT 1\nFROM DUAL;\n한글");
    ConsoleFiles.write(ConsoleFiles.consolePath(dir, id, 1), "");
    eq(ConsoleFiles.read(ConsoleFiles.consolePath(dir, id, 1)), "");
    ConsoleFiles.write(ConsoleFiles.consolePath(dir, id, 2), "b");
    eq(ConsoleFiles.freeSlot(dir, id, 0), 3);
    eq(ConsoleFiles.freeSlot(dir, id, 4), 5);
    try (var s = Files.list(dir)) { eq(s.count(), 2L); } // 임시 파일이 남지 않는다
    System.out.println("ConsoleFilesCheck 통과");
  }
}
