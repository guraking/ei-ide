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

    checkRun();
    System.out.println("SyncCheck 통과");
  }

  private static SyncPanel.Change out(String status, SyncPanel.Repo repo) {
    return new SyncPanel.Change(SyncPanel.Kind.OUTGOING, status, "p", repo);
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
}
