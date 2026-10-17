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

    // 프로젝트 폴더와 예전 자리 옮기기: 없는 것만 옮기고 있는 것은 덮지 않는다.
    Path root = Files.createTempDirectory("root");
    eq(ConsoleFiles.projectDir(root, "gpm.1a2b").getFileName().toString(), "gpm.1a2b");
    eq(ConsoleFiles.projectDir(root, "..").getParent(), root);
    eq(ConsoleFiles.projectDir(root, "a/b").getParent(), root);
    Path proj = ConsoleFiles.projectDir(root, "p1");
    eq(ConsoleFiles.migrate(root, proj), 0);
    ConsoleFiles.write(root.resolve("x.sql"), "예전 x");
    ConsoleFiles.write(root.resolve("y.sql"), "예전 y");
    ConsoleFiles.write(proj.resolve("y.sql"), "새 y");
    eq(ConsoleFiles.migrate(root, proj), 1);
    eq(ConsoleFiles.read(proj.resolve("x.sql")), "예전 x");
    eq(ConsoleFiles.read(proj.resolve("y.sql")), "새 y");
    eq(ConsoleFiles.read(root.resolve("x.sql")), "예전 x");
    eq(ConsoleFiles.read(root.resolve("y.sql")), "예전 y");
    eq(ConsoleFiles.migrate(root, ConsoleFiles.projectDir(root, "p2")), 2);
    eq(ConsoleFiles.migrate(root, ConsoleFiles.projectDir(root, "p2")), 0);
    System.out.println("ConsoleFilesCheck 통과");
  }
}
