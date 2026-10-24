package kinggura.sync;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** 자체 점검: java -cp out:test-out:<IDE jar> kinggura.sync.SyncCheck */
public class SyncCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  public static void main(String[] args) throws Exception {
    // 모른 척 목록: 소문자(assume-unchanged) · 'S'(skip-worktree, 디스크에 있는 것만). 'H' 는 보통 파일.
    Set<String> onDisk = Set.of("skip.yml", "한글 경로/c.txt");
    eq(SyncPanel.parseAssumed("H src/A.java\nh src/main/resources/application-local.yml\nh 한글 경로/b.txt\nS skip.yml\nS sparse.txt\ns 한글 경로/c.txt\n", onDisk::contains),
       List.of("src/main/resources/application-local.yml", "한글 경로/b.txt", "skip.yml", "한글 경로/c.txt"));
    eq(SyncPanel.parseAssumed("", p -> true), List.of());

    SyncPanel.Repo r = new SyncPanel.Repo(new File("."), SyncPanel.Vcs.GIT, "main", "origin/main");
    SyncPanel.Repo svn = new SyncPanel.Repo(new File("."), SyncPanel.Vcs.SVN, null, "12");
    eq(SyncPanel.summary(List.of(new SyncPanel.Change(SyncPanel.Kind.ASSUMED, "Assume Unchanged", "a", r))), "원격과 같습니다 · Assume Unchanged 1");
    eq(SyncPanel.summary(List.of(new SyncPanel.Change(SyncPanel.Kind.OUTGOING, "M", "b", r))), "들어올 0 · 나갈 1 · 충돌 0");

    // 되돌리기 판정: Git 의 A · R · C 는 스테이징만 풀기, 새 파일은 아무것도, SVN 추가는 svn revert 가 파일을 남기므로 되돌리기.
    for (String s : List.of("A", "AM", "AD", "R", "RM", "C")) eq(SyncPanel.undoOf(out(s, r)), SyncPanel.Undo.UNSTAGE);
    for (String s : List.of("M", "MM", "D", "T")) eq(SyncPanel.undoOf(out(s, r)), SyncPanel.Undo.REVERT);
    eq(SyncPanel.undoOf(out("??", r)), SyncPanel.Undo.NONE);
    eq(SyncPanel.undoOf(out("?", svn)), SyncPanel.Undo.NONE);
    eq(SyncPanel.undoOf(out("A", svn)), SyncPanel.Undo.REVERT);
    eq(SyncPanel.undoOf(new SyncPanel.Change(SyncPanel.Kind.INCOMING, "M", "a", r)), SyncPanel.Undo.NONE);

    // svn propget --xml
    eq(SyncPanel.parseSvnProperty(""), "");
    eq(SyncPanel.parseSvnProperty("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<properties>\n</properties>\n"), "");
    eq(SyncPanel.parseSvnProperty("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<properties>\n<target path=\".\">\n"
                                  + "<property name=\"svn:ignore\">target\n한글.txt\n</property>\n</target>\n</properties>\n"), "target\n한글.txt\n");
    try {
      SyncPanel.parseSvnProperty("svn: E155007: 작업본이 아닙니다");
      throw new AssertionError("깨진 XML 은 예외여야 한다");
    }
    catch (IOException expected) {
      // 덮어쓰지 않도록 예외가 나야 맞다.
    }

    checkUnmappedVcs();
    checkRun();
    checkReverseMerge();
    System.out.println("SyncCheck 통과");
  }

  private static SyncPanel.Change out(String status, SyncPanel.Repo repo) {
    return new SyncPanel.Change(SyncPanel.Kind.OUTGOING, status, "p", repo);
  }

  /** 루트 미등록 폴더: 가장 가까운 .svn · .git 을 따르고, 폴더 이름의 "git" 은 보지 않는다. */
  private static void checkUnmappedVcs() throws IOException {
    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("unmapped");
    File svnTop = tmp.resolve("svnwc").toFile();
    File svnInner = new File(svnTop, "git/dxplm");
    eq(svnInner.mkdirs() && new File(svnTop, ".svn").mkdir(), true);
    eq(SyncPanel.unmappedVcs(svnInner), SyncPanel.Vcs.SVN);
    File gitInSvn = new File(svnTop, "sub");
    eq(new File(gitInSvn, ".git").mkdirs(), true);
    eq(SyncPanel.unmappedVcs(gitInSvn), SyncPanel.Vcs.GIT);
    eq(SyncPanel.underVcs(svnInner), true);
    File plain = tmp.resolve("workspace").toFile();
    eq(plain.mkdir(), true);
    eq(SyncPanel.underVcs(plain), false);
  }

  /** run: 출력 · 실패 · 중단 시 프로세스 종료. */
  private static void checkRun() throws Exception {
    File dir = new File(".");
    eq(SyncPanel.run(dir, "sh", "-c", "printf 'a\\r\\nb'"), "a\nb");
    try {
      SyncPanel.run(dir, "sh", "-c", "echo 실패 >&2; exit 3");
      throw new AssertionError("0 이 아닌 종료 코드는 예외여야 한다");
    }
    catch (IOException e) {
      eq(e.getMessage().contains("실패"), true);
    }
    Thread worker = new Thread(() -> {
      try {
        SyncPanel.run(dir, "sh", "-c", "sleep 300 & wait");
        throw new AssertionError("중단되어야 한다");
      }
      catch (InterruptedException expected) {
        // 중단되면 여기로 온다.
      }
      catch (IOException e) {
        throw new AssertionError(e);
      }
    });
    worker.start();
    Thread.sleep(500);
    worker.interrupt();
    worker.join(5000);
    eq(worker.isAlive(), false);
    Thread.sleep(1000);
    Process ps = new ProcessBuilder("sh", "-c", "pgrep -af '[s]leep 300' || true").start();
    eq(new String(ps.getInputStream().readAllBytes()).trim(), "");
  }

  /**
   * 역머지: 서버(bare) · 개인 브랜치만 받는 내 clone · 다른 개발자 clone 으로 실제 git 을 돌려 본다.
   * 다른 개발자가 develop 에서 내 파일(A.java)과 남의 파일(B.java)을 고치면 A 만 「내 파일」로 잡히고, 합치고 푸시까지 되는지.
   */
  private static void checkReverseMerge() throws Exception {
    eq(ReverseMerge.parseHeads("abc\trefs/heads/develop\ndef\trefs/heads/feature/kim\n"), List.of("develop", "feature/kim"));
    eq(ReverseMerge.pick(List.of("main", "develop"), null, ReverseMerge.FROM_CANDIDATES), "develop");
    eq(ReverseMerge.pick(List.of("main", "develop"), "main", ReverseMerge.FROM_CANDIDATES), "main");
    eq(ReverseMerge.pick(List.of("main"), "gone", List.of("x")), null);
    var log = ReverseMerge.parseLog("\u0001이개발\u001f10-23 17:42\u001f두 번째\nA.java\n\n\u0001박개발\u001f10-22 09:10\u001f첫 번째\nA.java\nB.java\n");
    eq(log.get("A.java")[0], "이개발");
    eq(log.get("B.java")[2], "첫 번째");

    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("rmerge");
    File server = tmp.resolve("server.git").toFile();
    File other = tmp.resolve("other").toFile();
    File mine = tmp.resolve("mine").toFile();
    sh(tmp.toFile(), "git init -q --bare -b develop server.git && git clone -q server.git other");
    sh(other, "git config user.email other@x && git config user.name 이개발 && echo a > A.java && echo b > B.java && git add . && git commit -qm init"
              + " && git push -q origin develop && git push -q origin develop:feature/kim");
    // 개인 브랜치만 받는 clone(사용자 회사 설정과 같다).
    sh(tmp.toFile(), "git clone -q --single-branch -b feature/kim server.git mine");
    sh(mine, "git config user.email me@x && git config user.name 나 && echo mine >> A.java && git commit -qam 내작업 && git push -q");
    sh(other, "echo theirs > A.java.tmp && printf 'a\\n' > A.java && echo x >> B.java && echo c > C.java && git add . && git rm -q --cached A.java.tmp"
              + " && git commit -qm 남의작업 && git push -q origin develop");
    eq(ReverseMerge.serverBranches(mine, "origin"), List.of("develop", "feature/kim"));
    ReverseMerge.fetch(mine, "origin", "develop", "feature/kim");
    ReverseMerge.Preview p = ReverseMerge.preview(mine, "origin", "develop", "feature/kim", "feature/kim");
    eq(p.commits(), 1);
    eq(p.files().size(), 2);  // A.java 는 내용이 같아 diff 에 없다 → B, C
    eq(p.mineCount(), 0);
    // 다른 개발자가 내 파일 A.java 를 실제로 바꾸면 내 파일로 잡힌다.
    sh(other, "echo changed > A2.java && git add A2.java && echo z > A.java && git commit -qam A수정 && git push -q origin develop");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/kim");
    p = ReverseMerge.preview(mine, "origin", "develop", "feature/kim", "feature/kim");
    eq(p.files().get(0).path(), "A.java");
    eq(p.files().get(0).mine(), true);
    eq(p.files().get(0).author(), "이개발");
    eq(p.mineCount(), 1);
    eq(ReverseMerge.merge(mine, "origin", "develop", "feature/kim", "feature/kim", true), ReverseMerge.Result.CONFLICT);
    sh(mine, "git merge --abort");
    // 충돌 없는 쪽: 다른 개발자가 내 파일을 건드리지 않은 develop 을 새 개인 브랜치로 합치고 푸시.
    sh(other, "git push -q origin develop~1:refs/heads/feature/new");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/new");
    eq(ReverseMerge.merge(mine, "origin", "develop", "feature/new", "feature/kim", true), ReverseMerge.Result.MERGED);
    eq(SyncPanel.git(mine, "rev-parse", "--abbrev-ref", "HEAD").trim(), "feature/new");
    eq(SyncPanel.git(mine, "rev-parse", "HEAD").trim(), SyncPanel.git(server, "rev-parse", "feature/new").trim());
    ReverseMerge.saveConfig(mine, ReverseMerge.CONFIG_FROM, "develop");
    eq(ReverseMerge.config(mine, ReverseMerge.CONFIG_FROM), "develop");
    eq(ReverseMerge.config(mine, ReverseMerge.CONFIG_INTO), null);
  }

  private static void sh(File dir, String command) throws Exception {
    SyncPanel.run(dir, "sh", "-c", command);
  }
}
