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
    String out = "\u001eabc\u001fp1 p2\u001f이정민\u001f1761126840\u001fHEAD -> develop, origin/develop, tag: v1\u001f업체선정 요청\n\n둘째 줄\n\u001fjm@x\u001d\n\n:100644 100644 a1 a2 M\tsrc/A.java\n:000000 100644 0000000000000000000000000000000000000000 b2 A\t한글 경로/B.xml\n"
                 + ":100644 100644 c1 c1 R100\told.txt\tnew.txt\n"
                 + "\u001edef\u001f\u001fkim\u001f1761120000\u001fHEAD, grafted\u001f빈 커밋\n\u001f\u001d\n";
    List<History.Entry> log = History.parseGitLog(out);
    eq(log.size(), 2);
    eq(log.get(0).author(), "이정민");
    eq(log.get(0).email(), "jm@x");
    eq(log.get(0).parents(), List.of("p1", "p2"));
    eq(log.get(0).merge(), true);
    eq(log.get(0).refs(), List.of("develop", "origin/develop", "tag: v1"));
    eq(log.get(0).subject(), "업체선정 요청");
    eq(log.get(0).message(), "업체선정 요청\n\n둘째 줄");
    eq(log.get(0).time(), Instant.ofEpochSecond(1761126840));
    eq(log.get(0).files(), List.of(new History.FileChange("M", "src/A.java", "src/A.java", "a1", "a2"),
                                   new History.FileChange("A", "한글 경로/B.xml", "한글 경로/B.xml", "0000000000000000000000000000000000000000", "b2"),
                                   new History.FileChange("R", "new.txt", "old.txt", "c1", "c1")));
    eq(log.get(1).parents(), List.of());
    eq(log.get(1).refs(), List.of());
    eq(log.get(1).files(), List.of());
    eq(History.parseGitLog(""), List.of());
    bad(() -> History.parseGitLog("\u001eabc\u001fkim\u001d\n"));
    bad(() -> History.parseGitLog("\u001eabc\u001f\u001fkim\u001fx\u001f\u001fmsg\u001d\n"));
    bad(() -> History.parseGitLog("\u001eabc\u001f\u001fkim\u001f1\u001f\u001fmsg\n"));
    bad(() -> History.parseGitLog("\u001eabc\u001f\u001fkim\u001f1\u001f\u001fmsg\u001f\u001d\n:100644 a1 M\tx\n"));

    eq(History.parseMe("user.name 이 정민\nuser.email JM@X\n"), new History.Me("이 정민", "JM@X"));
    eq(History.parseMe(""), History.Me.NONE);
    History.Me me = new History.Me("", "jm@x");
    eq(me.is(log.get(0)), true);
    eq(me.is(log.get(1)), false);
    eq(History.Me.NONE.is(log.get(1)), false);  // 빈 이름 · 메일끼리 같다고 보지 않는다

    checkLayout();

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
    eq(slog.get(0).subject(), "첫 줄");
    eq(slog.get(0).parents(), List.of("11"));
    eq(slog.get(1).parents(), List.of());
    eq(slog.get(0).time(), Instant.parse("2026-10-22T09:54:00.123456Z"));
    eq(slog.get(0).files(), List.of(new History.FileChange("M", "src/A.java"), new History.FileChange("D", "한글.txt")));
    eq(slog.get(1).author(), "");
    eq(slog.get(1).time(), Instant.EPOCH);
    bad(() -> History.parseSvnLog("svn: E155007: 작업본이 아닙니다", "/trunk"));

    String info = "<?xml version=\"1.0\"?><info><entry><url>https://svn/repo/trunk/app</url><relative-url>^/trunk/app</relative-url>"
                  + "<repository><root>https://svn/repo</root></repository></entry></info>";
    eq(List.of(History.parseSvnInfo(info)), List.of("https://svn/repo", "/trunk/app"));
    bad(() -> History.parseSvnInfo("<info/>"));
    // 한글 · 공백 · + 가 든 폴더: relative-url 은 인코딩되어 오고 svn log 경로는 풀려 온다.
    String koInfo = "<?xml version=\"1.0\"?><info><entry><relative-url>^/trunk/%ED%95%9C%EA%B8%80%20%ED%8F%B4%EB%8D%94/a+b</relative-url>"
                    + "<repository><root>https://svn/repo</root></repository></entry></info>";
    String koPrefix = History.parseSvnInfo(koInfo)[1];
    eq(koPrefix, "/trunk/한글 폴더/a+b");
    String koLog = "<log><logentry revision=\"3\"><paths><path action=\"M\">/trunk/한글 폴더/a+b/src/X.java</path></paths><msg>m</msg></logentry></log>";
    eq(History.parseSvnLog(koLog, koPrefix).get(0).files(), List.of(new History.FileChange("M", "src/X.java")));

    checkRealGit();
    checkClashes();
    System.out.println("HistoryCheck 통과");
  }

  /** 실제 git log: 프로젝트 폴더 밖 커밋은 빠지고 경로는 폴더 기준. 합치기 커밋은 첫 부모와 비교한 파일을 보인다. 다음 묶음은 첫 HEAD 에서 센다. */
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
    String head = git(repo, "rev-parse", "HEAD").trim();
    List<History.Entry> log = History.parseGitLog(git(app, History.gitLogArgs(head, 100, 0)));
    eq(log.stream().map(History.Entry::subject).toList(), List.of("Merge branch 'side'", "본줄기", "곁가지", "앱 첫 커밋"));
    eq(log.get(0).parents(), List.of(log.get(1).id(), log.get(2).id()));
    eq(log.get(1).parents(), List.of(log.get(3).id()));  // 폴더 밖 「밖 커밋」을 건너뛴 부모
    eq(names(log.get(3).files()), List.of(new History.FileChange("A", "한글.java")));
    eq(log.get(1).refs(), List.of());
    eq(log.get(0).refs(), List.of("main"));
    eq(names(log.get(0).files()), List.of(new History.FileChange("A", "side.java")));
    // 첫 묶음 뒤 새 커밋이 생겨도 첫 HEAD 에서 세면 다음 묶음이 어긋나지 않는다.
    commit(repo, "app/later.java", "나중 커밋");
    // 다음 묶음: 앞의 둘을 건너뛰면 나머지 둘이 같은 순서로 온다.
    List<History.Entry> next = History.parseGitLog(git(app, History.gitLogArgs(head, 100, 2)));
    eq(next.stream().map(History.Entry::id).toList(), List.of(log.get(2).id(), log.get(3).id()));
    git(repo, "mv", "app/main.java", "app/renamed.java");
    git(repo, "commit", "-q", "-m", "이름 바꿈");
    List<History.Entry> renamed = History.parseGitLog(git(app, History.gitLogArgs("HEAD", 1, 0)));
    eq(names(renamed.get(0).files()), List.of(new History.FileChange("R", "renamed.java", "main.java")));
    List<History.Row> rows = History.layout(log);
    // 0 합치기(본줄기 · 곁가지) / 1 본줄기 / 2 곁가지 / 3 첫 커밋. 곁가지는 1 칸, 나머지는 0 칸.
    eq(History.links(log, rows, 0), List.of(new History.Link(History.LinkKind.MERGED_FROM, log.get(2))));
    eq(History.links(log, rows, 1), List.of());
    eq(History.links(log, rows, 2), List.of(new History.Link(History.LinkKind.MERGED_INTO, log.get(0))));
    eq(History.links(log, rows, 3), List.of(new History.Link(History.LinkKind.BRANCHED_TO, log.get(2))));
    eq(History.passingTarget(rows, 1, 1), 2);  // 본줄기 줄의 1 칸 선은 곁가지 커밋으로 간다
    eq(History.passingTarget(rows, 1, 0), -1);  // 점이 있는 칸은 지나가는 선이 아니다
    eq(History.passingTarget(rows, 2, 3), -1);
    eq(rows.get(0).bottom(), List.of(new History.Seg(0, 0, 0), new History.Seg(0, 1, 1)));
    eq(rows.get(3).top(), List.of(new History.Seg(0, 0, 0), new History.Seg(1, 0, 1)));
  }

  /**
   * 내 소스 건드림: 나(me)는 f · g · h 를 고친다. kim 은
   * 1) 내 base 판 위에서 f · g 를 고친다(이어 고침). 내 가지를 합치며 f 는 자기 것을 택한다(덮어씀). g 는 양쪽을 다 넣어 합쳐 빠진다.
   * 2) 합친 뒤 h 를 내 커밋 전 내용으로 되돌리고(되돌림), other 를 고친다(이어 고침).
   * 3) 그 뒤 내가 kim 의 f 를 고치고(남의 소스 이어 고침) other 를 kim 전 내용으로 되돌린다(남의 소스 되돌림).
   */
  private static void checkClashes() throws Exception {
    File repo = Files.createTempDirectory("clash").toFile();
    File app = new File(repo, "app");
    eq(app.mkdir(), true);
    git(repo, "init", "-q", "-b", "main");
    git(repo, "config", "user.name", "me");
    git(repo, "config", "user.email", "me@x");
    write(repo, "app/f", "base\n");
    write(repo, "app/g", "1\n2\n3\n4\n5\n");
    write(repo, "app/h", "old\n");
    write(repo, "app/other", "x\n");
    git(repo, "add", ".");
    git(repo, "commit", "-q", "-m", "base");
    git(repo, "checkout", "-q", "-b", "kim");
    write(repo, "app/f", "kim\n");
    write(repo, "app/g", "1\n2\n3\n4\nkim\n");
    kim(repo, "kim 작업");
    git(repo, "checkout", "-q", "main");
    write(repo, "app/f", "mine\n");
    write(repo, "app/g", "mine\n2\n3\n4\n5\n");
    write(repo, "app/h", "new\n");
    git(repo, "commit", "-q", "-am", "내 작업");
    git(repo, "checkout", "-q", "kim");
    try {
      git(repo, "-c", "user.name=kim", "-c", "user.email=kim@x", "merge", "-q", "main", "-m", "merge");
    }
    catch (IOException conflict) {
      // f 만 충돌한다. kim 은 자기 판을 택한다.
    }
    git(repo, "checkout", "--ours", "app/f");
    git(repo, "add", "app/f");
    kim(repo, "합치기 - 내 것 택함");
    write(repo, "app/h", "old\n");
    kim(repo, "옛 작업본 올림");
    write(repo, "app/other", "y\n");
    kim(repo, "상관없는 파일");
    write(repo, "app/f", "kim2\n");
    write(repo, "app/other", "x\n");
    git(repo, "commit", "-q", "-am", "내가 남의 것");
    String head = git(repo, "rev-parse", "HEAD").trim();
    List<History.Entry> log = History.parseGitLog(git(app, History.gitLogArgs("kim", 100, 0)));
    List<String> merges = new java.util.ArrayList<>();
    for (History.Entry e : log) if (e.merge()) merges.add(e.id());
    eq(merges.size(), 1);
    java.util.Map<String, List<List<History.FileChange>>> sides = History.parseSides(git(app, History.sideArgs(merges)));
    eq(sides.get(merges.get(0)).size(), 2);
    java.util.Map<String, List<History.Clash>> clashes = History.clashes(log, new History.Me("me", "me@x"), sides);
    java.util.Map<String, String> bySubject = new java.util.TreeMap<>();
    for (History.Entry e : log) {
      List<History.Clash> found = clashes.get(e.id());
      if (found == null) continue;
      List<String> parts = new java.util.ArrayList<>();
      for (History.Clash c : found) parts.add(c.change().path() + " " + (c.others() ? "남 " : "") + c.kind() + " " + c.owner().subject());
      bySubject.put(e.subject(), String.join(", ", parts));
    }
    eq(bySubject, java.util.Map.of("kim 작업", "f EDITED base, g EDITED base", "합치기 - 내 것 택함", "f OVERWRITTEN 내 작업",
                                   "옛 작업본 올림", "h REVERTED 내 작업", "상관없는 파일", "other EDITED base",
                                   "내가 남의 것", "f 남 EDITED kim 작업, other 남 REVERTED 상관없는 파일"));
    eq(History.strongest(clashes.get(log.get(0).id())), History.ClashKind.REVERTED);
    // 나를 모르면(user.name · email 없음) 아무것도 찾지 않는다.
    eq(History.clashes(log, History.Me.NONE, sides), java.util.Map.of());
    eq(History.gitHead(app), "kim");
    git(repo, "checkout", "-q", "--detach", head);
    eq(History.gitHead(app), head.substring(0, 7));
    eq(History.gitHead(Files.createTempDirectory("nogit").toFile()), "");
  }

  private static void write(File repo, String path, String text) throws IOException {
    Files.writeString(new File(repo, path).toPath(), text);
  }

  private static void kim(File repo, String message) throws IOException, InterruptedException {
    git(repo, "-c", "user.name=kim", "-c", "user.email=kim@x", "commit", "-q", "-am", message);
  }

  /** 가지 선: M(합치기) ← A(본줄기) · B(곁가지) ← R(뿌리). 곁가지는 1번 칸으로 갈라졌다가 R 에서 0번 칸으로 모인다. */
  private static void checkLayout() {
    List<History.Row> rows = History.layout(List.of(entry("M", "A", "B"), entry("A", "R"), entry("B", "R"), entry("R")));
    eq(rows.get(0), new History.Row(0, List.of(), List.of(new History.Seg(0, 0, 0), new History.Seg(0, 1, 1))));
    eq(rows.get(1), new History.Row(0, List.of(new History.Seg(0, 0, 0), new History.Seg(1, 1, 1)),
                                    List.of(new History.Seg(0, 0, 0), new History.Seg(1, 1, 1))));
    eq(rows.get(2), new History.Row(1, List.of(new History.Seg(0, 0, 0), new History.Seg(1, 1, 1)),
                                    List.of(new History.Seg(0, 0, 0), new History.Seg(1, 1, 1))));
    eq(rows.get(3), new History.Row(0, List.of(new History.Seg(0, 0, 0), new History.Seg(1, 0, 1)), List.of()));
    // 목록 밖 부모를 기다리는 칸은 끝까지 이어지고, 위에서 오지 않은 커밋은 빈 칸에 선다.
    List<History.Row> cut = History.layout(List.of(entry("X", "gone"), entry("Y", "gone2")));
    eq(cut.get(1), new History.Row(1, List.of(new History.Seg(0, 0, 0)), List.of(new History.Seg(0, 0, 0), new History.Seg(1, 1, 1))));
  }

  private static History.Entry entry(String id, String... parents) {
    return new History.Entry(id, List.of(parents), "a", "", Instant.EPOCH, List.of(), id, List.of());
  }

  /** 내용 해시를 뺀 상태 · 경로만(실제 git 해시는 실행마다 같지 않을 수 있다). */
  private static List<History.FileChange> names(List<History.FileChange> files) {
    List<History.FileChange> result = new java.util.ArrayList<>();
    for (History.FileChange f : files) result.add(new History.FileChange(f.status(), f.path(), f.oldPath()));
    return result;
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
