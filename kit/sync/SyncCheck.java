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

    // 스테이징 구분: porcelain XY 의 X 는 Staged, Y 는 Unstaged, 새 파일은 Unstaged.
    eq(SyncPanel.stageOf("M "), SyncPanel.Stage.STAGED);
    eq(SyncPanel.stageOf(" M"), SyncPanel.Stage.UNSTAGED);
    eq(SyncPanel.stageOf("MM"), SyncPanel.Stage.BOTH);
    eq(SyncPanel.stageOf("A "), SyncPanel.Stage.STAGED);
    eq(SyncPanel.stageOf("AM"), SyncPanel.Stage.BOTH);
    eq(SyncPanel.stageOf("??"), SyncPanel.Stage.UNSTAGED);
    eq(SyncPanel.stageOf(" D"), SyncPanel.Stage.UNSTAGED);

    // 마지막 Git 동작 되돌리기: 브랜치 바꾸기는 앞 브랜치로, 커밋은 soft, 그 밖은 keep. 앞 위치가 없거나 진행 중이면 null.
    eq(GitUndo.plan("checkout: moving from main to feature/한글", "feature/한글", "branch: Created from main"),
       new GitUndo.Plan(GitUndo.Way.SWITCH_BACK, "main"));
    eq(GitUndo.plan("commit: 고침", "main", "commit: 고침"), new GitUndo.Plan(GitUndo.Way.SOFT, "main@{1}"));
    eq(GitUndo.plan("commit (amend): 고침", "main", "commit (amend): 고침"), new GitUndo.Plan(GitUndo.Way.SOFT, "main@{1}"));
    eq(GitUndo.plan("merge dev: Merge made by the 'ort' strategy.", "main", "merge dev: Merge made by the 'ort' strategy."),
       new GitUndo.Plan(GitUndo.Way.KEEP, "main@{1}"));
    eq(GitUndo.plan("commit (merge): Merge branch 'dev'", "main", "commit (merge): Merge branch 'dev'"), new GitUndo.Plan(GitUndo.Way.KEEP, "main@{1}"));
    eq(GitUndo.plan("pull: Fast-forward", "main", "pull: Fast-forward"), new GitUndo.Plan(GitUndo.Way.KEEP, "main@{1}"));
    eq(GitUndo.plan("rebase (finish): returning to refs/heads/main", "main", "rebase (finish): refs/heads/main onto 1234"),
       new GitUndo.Plan(GitUndo.Way.KEEP, "main@{1}"));
    eq(GitUndo.plan("rebase (pick): 고침", "main", "commit: 앞"), null);
    eq(GitUndo.plan("commit (initial): 처음", "main", "commit (initial): 처음"), null);
    eq(GitUndo.plan("clone: from https://example.com/a.git", "main", "clone: from https://example.com/a.git"), null);
    eq(GitUndo.plan("commit: 고침", null, null), null);
    eq(GitUndo.plan("", "main", "commit: 고침"), null);
    eq(GitUndo.undoneLine(new GitUndo.Plan(GitUndo.Way.SOFT, "main@{1}"), "main"), "reset: moving to main@{1}");
    eq(GitUndo.undoneLine(new GitUndo.Plan(GitUndo.Way.SWITCH_BACK, "main"), "dev"), "checkout: moving from dev to main");
    eq(GitUndo.undoneLine(new GitUndo.Plan(GitUndo.Way.SWITCH_BACK, "main"), null), null);

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
