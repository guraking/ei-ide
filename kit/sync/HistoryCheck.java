package kinggura.sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

/** 자체 점검: java -cp out kinggura.sync.HistoryCheck (git 이 PATH 에 있어야 한다) */
public class HistoryCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  public static void main(String[] args) throws Exception {
    String out = "\u001eabc\u001f이정민\u001f1761126840\u001f업체선정 요청 인터페이스 추가\n\nM\tsrc/A.java\nA\t한글 경로/B.xml\nR100\told.txt\tnew.txt\n"
                 + "\u001edef\u001fkim\u001f1761120000\u001f빈 커밋\n";
    List<History.Entry> log = History.parseGitLog(out);
    eq(log.size(), 2);
    eq(log.get(0).author(), "이정민");
    eq(log.get(0).time(), Instant.ofEpochSecond(1761126840));
    eq(log.get(0).files(), List.of(new History.FileChange("M", "src/A.java"), new History.FileChange("A", "한글 경로/B.xml"),
                                   new History.FileChange("R", "new.txt")));
    eq(log.get(1).files(), List.of());
    eq(History.parseGitLog(""), List.of());
    bad(() -> History.parseGitLog("\u001eabc\u001fkim\n"));
    bad(() -> History.parseGitLog("\u001eabc\u001fkim\u001fx\u001fmsg\n"));

    String svn = """
      <?xml version="1.0" encoding="UTF-8"?>
      <log>
      <logentry revision="12">
      <author>jmlee</author>
      <date>2026-10-22T09:54:00.123456Z</date>
      <paths>
      <path action="M" kind="file">/trunk/app/src/A.java</path>
      <path action="A" kind="file">/trunk/app2/B.java</path>
      <path action="D" kind="file">/trunk/app/한글.txt</path>
      </paths>
      <msg>첫 줄
      둘째 줄</msg>
      </logentry>
      <logentry revision="11">
      </logentry>
      </log>
      """;
    List<History.Entry> slog = History.parseSvnLog(svn, "/trunk/app");
    eq(slog.size(), 2);
    eq(slog.get(0).id(), "12");
    eq(slog.get(0).message(), "첫 줄");
    eq(slog.get(0).time(), Instant.parse("2026-10-22T09:54:00.123456Z"));
    eq(slog.get(0).files(), List.of(new History.FileChange("M", "src/A.java"), new History.FileChange("D", "한글.txt")));
    eq(slog.get(1).author(), "");
    eq(slog.get(1).time(), Instant.EPOCH);
    bad(() -> History.parseSvnLog("svn: E155007: 작업본이 아닙니다", "/trunk"));

    String info = "<?xml version=\"1.0\"?><info><entry><url>https://svn/repo/trunk/app</url><relative-url>^/trunk/app</relative-url>"
                  + "<repository><root>https://svn/repo</root></repository></entry></info>";
    eq(List.of(History.parseSvnInfo(info)), List.of("https://svn/repo", "/trunk/app"));
    bad(() -> History.parseSvnInfo("<info/>"));

    checkRealGit();
    System.out.println("HistoryCheck 통과");
  }

  /** 실제 git log: 프로젝트 폴더 밖 커밋 · 합치기 커밋은 빠지고 경로는 폴더 기준. */
  private static void checkRealGit() throws Exception {
    File repo = Files.createTempDirectory("history").toFile();
    File app = new File(repo, "app");
    eq(app.mkdir(), true);
    git(repo, "init", "-q", "-b", "main");
    git(repo, "config", "user.name", "tester");
    git(repo, "config", "user.email", "t@t");
    commit(repo, "app/한글.java", "앱 첫 커밋");
    commit(repo, "other.txt", "밖 커밋");
    git(repo, "checkout", "-q", "-b", "side");
    commit(repo, "app/side.java", "곁가지");
    git(repo, "checkout", "-q", "main");
    commit(repo, "app/main.java", "본줄기");
    git(repo, "merge", "-q", "--no-ff", "-m", "Merge branch 'side'", "side");
    List<History.Entry> log = History.parseGitLog(git(app, History.gitLogArgs(100)));
    eq(log.stream().map(History.Entry::message).toList(), List.of("본줄기", "곁가지", "앱 첫 커밋"));
    eq(log.get(2).files(), List.of(new History.FileChange("A", "한글.java")));
  }

  private static void commit(File repo, String path, String message) throws IOException, InterruptedException {
    File f = new File(repo, path);
    Files.writeString(f.toPath(), message);
    git(repo, "add", path);
    git(repo, "commit", "-q", "-m", message);
  }

  /** 점검은 IntelliJ 클래스 없이 돌아야 해서 SyncPanel.git 대신 직접 실행한다. */
  private static String git(File dir, String... args) throws IOException, InterruptedException {
    List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "core.quotepath=false"));
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    if (p.waitFor() != 0) throw new IOException(out);
    return out;
  }

  interface Failing { void run() throws Exception; }

  private static void bad(Failing f) throws Exception {
    try {
      f.run();
    }
    catch (IOException expected) {
      return;
    }
    throw new AssertionError("IOException 이어야 한다");
  }
}
