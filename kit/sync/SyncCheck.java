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

    // git 이 감싼 경로: 이스케이프 · 8진수 UTF-8 바이트.
    eq(SyncPanel.unquote("\"a\\\"b\\\\c\\td\""), "a\"b\\c\td");
    eq(SyncPanel.unquote("\"\\355\\225\\234.txt\""), "한.txt");
    eq(SyncPanel.unquote("plain.txt"), "plain.txt");
    checkNestedRepo();

    // Push 단추 숫자: git status -b 브랜치 줄의 ahead.
    eq(SyncPanel.parseAhead("## main...origin/main [ahead 2, behind 1]"), 2);
    eq(SyncPanel.parseAhead("## main...origin/main [ahead 13]"), 13);
    eq(SyncPanel.parseAhead("## main...origin/main [behind 4]"), 0);
    eq(SyncPanel.parseAhead("## main...origin/main"), 0);
    eq(SyncPanel.parseAhead("## No commits yet on main"), 0);
    // 받기 알림: 커밋 수와 겹치지 않는 파일 수.
    eq(SyncPanel.countPulled("\u0001\n\nA.java\nb/B.xml\n\u0001\n\nA.java\n"), new SyncPanel.Pulled(2, 2));
    eq(SyncPanel.countPulled(""), new SyncPanel.Pulled(0, 0));

    checkUnmappedVcs();
    checkBuildFiles();
    checkOutputDirs();
    checkInSvn();
    checkRun();
    checkReverseMerge();
    checkGitLogins();
    checkGitUndo();
    System.out.println("SyncCheck 통과");
  }

  private static SyncPanel.Change out(String status, SyncPanel.Repo repo) {
    return new SyncPanel.Change(SyncPanel.Kind.OUTGOING, status, "p", repo);
  }

  /** 빌드 파일 찾기: 두 단계까지, pom.xml 이 Gradle 보다 앞, 찾은 폴더 아래 · 점 폴더 · 빌드 결과 폴더는 보지 않는다. */
  private static void checkBuildFiles() throws IOException {
    java.nio.file.Path repo = java.nio.file.Files.createTempDirectory("clone");
    eq(CloneIntoProject.buildFiles(repo), List.of());
    java.nio.file.Path eclipse = java.nio.file.Files.createDirectories(repo.resolve("jpa-mybatis-multi"));
    java.nio.file.Files.writeString(eclipse.resolve("pom.xml"), "");
    java.nio.file.Files.writeString(eclipse.resolve("build.gradle"), "");
    java.nio.file.Files.createDirectories(eclipse.resolve("child"));
    java.nio.file.Files.writeString(eclipse.resolve("child/pom.xml"), "");
    java.nio.file.Path app = java.nio.file.Files.createDirectories(repo.resolve("apps/web"));
    java.nio.file.Files.writeString(app.resolve("build.gradle.kts"), "");
    java.nio.file.Files.writeString(app.resolve("settings.gradle.kts"), "");
    java.nio.file.Files.createDirectories(repo.resolve("a/b/c"));
    java.nio.file.Files.writeString(repo.resolve("a/b/c/pom.xml"), "");
    java.nio.file.Files.createDirectories(repo.resolve(".idea/x"));
    java.nio.file.Files.writeString(repo.resolve(".idea/x/pom.xml"), "");
    java.nio.file.Files.createDirectories(repo.resolve("target"));
    java.nio.file.Files.writeString(repo.resolve("target/pom.xml"), "");
    eq(CloneIntoProject.buildFiles(repo), List.of(app.resolve("settings.gradle.kts"), eclipse.resolve("pom.xml")));
    java.nio.file.Files.writeString(repo.resolve("pom.xml"), "");
    eq(CloneIntoProject.buildFiles(repo), List.of(repo.resolve("pom.xml")));
  }

  /** 빌드 결과 폴더: pom · Gradle · package.json 옆 폴더와 .classpath 출력 폴더, 없는 폴더도 고르고 고른 폴더 · 점 폴더 아래는 보지 않는다. */
  private static void checkOutputDirs() throws IOException {
    eq(CloneIntoProject.eclipseOutput("<classpath><classpathentry kind=\"src\" path=\"src\"/><classpathentry path=\"WebContent/WEB-INF/classes/\" kind=\"output\"/></classpath>"), "WebContent/WEB-INF/classes");
    eq(CloneIntoProject.eclipseOutput("<classpathentry kind=\"src\" path=\"src\" sourcepath=\"x\"/><classpathentry kind=\"output\" path=\"bin\"/>"), "bin");
    eq(CloneIntoProject.eclipseOutput("<classpathentry kind=\"output\" path=\".\"/>"), null);
    eq(CloneIntoProject.eclipseOutput("<classpathentry kind=\"output\" path=\"../out\"/>"), null);
    eq(CloneIntoProject.eclipseOutput("<classpathentry kind=\"output\" path=\"C:/out\"/>"), null);
    eq(CloneIntoProject.eclipseOutput("<classpathentry kind=\"src\" path=\"src\"/>"), null);
    java.nio.file.Path repo = java.nio.file.Files.createTempDirectory("output");
    eq(CloneIntoProject.outputDirs(repo), List.of());
    java.nio.file.Path eclipse = java.nio.file.Files.createDirectories(repo.resolve("jpa-mybatis-multi"));
    java.nio.file.Files.writeString(eclipse.resolve("pom.xml"), "");
    java.nio.file.Files.writeString(eclipse.resolve(".classpath"), "<classpathentry kind=\"output\" path=\"bin\"/>");
    java.nio.file.Files.createDirectories(eclipse.resolve("target/sub"));
    java.nio.file.Files.writeString(eclipse.resolve("target/sub/package.json"), "");
    java.nio.file.Path front = java.nio.file.Files.createDirectories(repo.resolve("front"));
    java.nio.file.Files.writeString(front.resolve("package.json"), "");
    java.nio.file.Files.createDirectories(front.resolve("node_modules/lib"));
    java.nio.file.Files.writeString(front.resolve("node_modules/lib/package.json"), "");
    java.nio.file.Files.createDirectories(repo.resolve(".idea/x"));
    java.nio.file.Files.writeString(repo.resolve(".idea/x/pom.xml"), "");
    java.nio.file.Path app = java.nio.file.Files.createDirectories(repo.resolve("app"));
    java.nio.file.Files.writeString(app.resolve("build.gradle"), "");
    java.nio.file.Files.writeString(app.resolve("settings.gradle"), "");
    java.nio.file.Files.createDirectories(repo.resolve("a/b/c"));
    java.nio.file.Files.writeString(repo.resolve("a/b/c/pom.xml"), "");
    eq(CloneIntoProject.outputDirs(repo), List.of(app.resolve("build"), front.resolve("node_modules"), eclipse.resolve("bin"), eclipse.resolve("target")));
  }

  /** SVN 작업본 판정: 그 폴더나 위 어디에 .svn 폴더가 있으면 SVN 이다(.svn 파일은 아니다). */
  private static void checkInSvn() throws IOException {
    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("insvn");
    java.nio.file.Path inner = java.nio.file.Files.createDirectories(tmp.resolve("wc/src/main"));
    eq(CloneIntoProject.inSvn(inner), false);
    java.nio.file.Files.writeString(tmp.resolve("wc/.svn"), "");
    eq(CloneIntoProject.inSvn(inner), false);
    java.nio.file.Files.delete(tmp.resolve("wc/.svn"));
    java.nio.file.Files.createDirectories(tmp.resolve("wc/.svn"));
    eq(CloneIntoProject.inSvn(inner), true);
    eq(CloneIntoProject.inSvn(tmp.resolve("wc")), true);
    eq(CloneIntoProject.inSvn(tmp), false);
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
    // 들어올 커밋이 모두 내 것(이름 또는 이메일이 같음)이면 남이 고친 파일은 없다. 남의 커밋이 고친 파일만 잡힌다.
    String authors = "\u0001나\u001fme@x\nA.java\n\n\u0001나\u001fME@X\nB.java\n\n\u0001이개발\u001fother@x\nC.java\n";
    eq(ReverseMerge.touchedByOthers(authors, "나", "me@x"), Set.of("C.java"));
    eq(ReverseMerge.touchedByOthers(authors, "다른이름", "me@x"), Set.of("C.java"));
    eq(ReverseMerge.touchedByOthers(authors, null, null), Set.of("A.java", "B.java", "C.java"));

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
    eq(ReverseMerge.parseRefs("refs/remotes/origin/HEAD\nrefs/remotes/origin/develop\nrefs/remotes/upstream/x\nrefs/remotes/origin/feature/kim\n", "origin"),
       List.of("develop", "feature/kim"));
    // 개인 브랜치만 받은 clone 은 받아 둔 브랜치만 안다. 서버 목록은 뒤에서 따로 합친다.
    eq(ReverseMerge.knownBranches(mine, "origin"), List.of("feature/kim"));
    ReverseMerge.fetch(mine, "origin", "develop", "feature/kim");
    eq(ReverseMerge.knownBranches(mine, "origin"), List.of("develop", "feature/kim"));
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
    // 커밋 안 한 변경이 있으면 아무것도 하지 않고 멈춘다.
    sh(mine, "echo dirty >> B.java");
    try {
      ReverseMerge.merge(mine, "origin", "develop", "feature/kim", "feature/kim", true, false);
      throw new AssertionError("커밋 안 한 변경이 있으면 합치지 않아야 한다");
    }
    catch (ReverseMerge.DirtyException expected) {
      eq(ReverseMerge.conflicts(mine), Set.of());
    }
    sh(mine, "git checkout -q -- B.java");
    eq(ReverseMerge.merge(mine, "origin", "develop", "feature/kim", "feature/kim", true, false), ReverseMerge.Result.CONFLICT);
    eq(ReverseMerge.conflicts(mine), Set.of("A.java"));
    // 충돌이 남았으면 마무리하지 않는다. 해결하면 합치기 커밋 · 푸시까지 한다.
    eq(ReverseMerge.finish(mine, "origin", "feature/kim", "feature/kim", true), false);
    sh(mine, "echo resolved > A.java && git add A.java");
    eq(ReverseMerge.finish(mine, "origin", "feature/kim", "feature/kim", true), true);
    eq(SyncPanel.git(mine, "rev-parse", "HEAD").trim(), SyncPanel.git(server, "rev-parse", "feature/kim").trim());
    eq(ReverseMerge.finish(mine, "origin", "feature/kim", "feature/kim", true), false);  // 이미 마무리됨(MERGE_HEAD 없음)
    // 충돌 없는 쪽: 다른 개발자가 내 파일을 건드리지 않은 develop 을 새 개인 브랜치로 합치고 푸시.
    sh(other, "git push -q origin develop~1:refs/heads/feature/new");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/new");
    eq(ReverseMerge.merge(mine, "origin", "develop", "feature/new", "feature/kim", true, false), ReverseMerge.Result.MERGED);
    // 다른 브랜치에 합쳤으면 원래 브랜치로 돌아온다.
    eq(SyncPanel.git(mine, "rev-parse", "--abbrev-ref", "HEAD").trim(), "feature/kim");
    eq(SyncPanel.git(mine, "rev-parse", "feature/new").trim(), SyncPanel.git(server, "rev-parse", "feature/new").trim());
    // 서버와 갈라져 멈춰도 원래 브랜치로 돌아온다.
    sh(other, "git fetch -q origin feature/new && git checkout -q -b fnew origin/feature/new && echo o > O.java && git add O.java && git commit -qm o && git push -q origin fnew:feature/new");
    sh(mine, "git checkout -q feature/new && echo l > L.java && git add L.java && git commit -qm l && git checkout -q feature/kim");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/new");
    try {
      ReverseMerge.merge(mine, "origin", "develop", "feature/new", "feature/kim", true, false);
      throw new AssertionError("갈라진 개인 브랜치는 멈춰야 한다");
    }
    catch (IOException expected) {
      eq(SyncPanel.git(mine, "rev-parse", "--abbrev-ref", "HEAD").trim(), "feature/kim");
    }
    eq(ReverseMerge.unpushed(mine, "origin", "feature/new"), 1);
    // Stash 하고 합치기: 커밋 안 한 변경을 넣어 두었다가 합친 뒤 원래 브랜치에서 되살린다. 남의 Stash 는 건드리지 않는다.
    sh(other, "git checkout -q develop && echo d > D.java && git add D.java && git commit -qm d && git push -q origin develop && git push -q origin develop~1:refs/heads/feature/stash");
    sh(mine, "echo keep > K.java && git add K.java && git stash push -q -m 남의것 && echo dirty >> B.java");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/stash");
    eq(ReverseMerge.merge(mine, "origin", "develop", "feature/stash", "feature/kim", false, true), ReverseMerge.Result.MERGED);
    eq(SyncPanel.git(mine, "rev-parse", "--abbrev-ref", "HEAD").trim(), "feature/kim");
    eq(SyncPanel.git(mine, "status", "--porcelain", "-uno"), " M B.java\n");
    eq(SyncPanel.git(mine, "stash", "list", "--format=%s").contains("남의것"), true);
    eq(SyncPanel.git(mine, "stash", "list", "--format=%s").contains(ReverseMerge.STASH_MESSAGE), false);
    ReverseMerge.unstash(mine);  // 맨 위가 우리 Stash 가 아니면 꺼내지 않는다.
    eq(SyncPanel.git(mine, "stash", "list", "--format=%s").contains("남의것"), true);
    sh(mine, "git checkout -q -- B.java");
    ReverseMerge.saveConfig(mine, ReverseMerge.CONFIG_FROM, "develop");
    eq(ReverseMerge.config(mine, ReverseMerge.CONFIG_FROM), "develop");
    eq(ReverseMerge.config(mine, ReverseMerge.CONFIG_INTO), null);
    java.util.Map<String, String> cfg = ReverseMerge.configs(mine);
    eq(cfg.get("user.email"), "me@x");
    eq(cfg.get(ReverseMerge.CONFIG_FROM), "develop");
    eq(cfg.get(ReverseMerge.CONFIG_INTO), null);
    eq(ReverseMerge.remoteOf(cfg, "feature/kim"), "origin");
    eq(ReverseMerge.parseConfigs("user.name 나\nbranch.a.remote origin\nempty.key\n"), java.util.Map.of("user.name", "나", "branch.a.remote", "origin"));
    eq(ReverseMerge.gitAll(mine, List.of(new String[]{"rev-parse", "--abbrev-ref", "HEAD"}, new String[]{"config", "--get", "user.email"})),
       List.of("feature/kim\n", "me@x\n"));
  }

  /** 마지막 Git 동작 되돌리기: reflog 줄 해석, 실제 저장소에서 커밋 · 되돌리기의 되돌리기 · 체크아웃 · 합치기 되돌리기와 멈추는 경우. */
  static void checkGitUndo() throws Exception {
    String f = "\u001f";
    eq(GitUndo.whenOf("main@{2026-10-28 22:10}"), "2026-10-28 22:10");
    eq(GitUndo.whenOf("odd"), "odd");
    GitUndo.Plan p = GitUndo.plan("h" + f + "HEAD@{t}" + f + "commit: 고침", "b" + f + "main@{t2}" + f + "commit: 고침\na" + f + "main@{t1}" + f + "commit: 앞", "main");
    eq(p, new GitUndo.Plan(GitUndo.Mode.SOFT, "commit: 고침", "t2", "a", false));
    eq(GitUndo.plan("", "b" + f + "x" + f + "commit (amend): 고침\na" + f + "x" + f + "commit: 앞", "main").mode(), GitUndo.Mode.SOFT);
    eq(GitUndo.plan("", "b" + f + "x" + f + "merge dev: Fast-forward\na" + f + "x" + f + "commit: 앞", "main").mode(), GitUndo.Mode.KEEP);
    eq(GitUndo.plan("h" + f + "HEAD@{t}" + f + "checkout: moving from dev to main", "", "main"),
       new GitUndo.Plan(GitUndo.Mode.CHECKOUT, "checkout: moving from dev to main", "t", "dev", false));
    // 다른 브랜치로 간 체크아웃은 이 브랜치의 되돌리기가 아니다.
    eq(GitUndo.plan("h" + f + "x" + f + "checkout: moving from dev to other", "b" + f + "x" + f + "commit: c\na" + f + "x" + f + "commit: b", "main").mode(),
       GitUndo.Mode.SOFT);
    eq(GitUndo.plan("", "b" + f + "x" + f + "commit (initial): one", "main"), null);
    eq(GitUndo.plan("", "b" + f + "x" + f + "branch: Created from HEAD\na" + f + "x" + f + "commit: 앞", "main"), null);

    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("undo");
    File r = tmp.resolve("r").toFile();
    sh(tmp.toFile(), "git init -q -b main r && cd r && git config user.email me@x && git config user.name 나"
                     + " && echo 1 > a && git add a && git commit -qm one && echo 2 > a && git commit -qam two");
    String one = SyncPanel.git(r, "rev-parse", "HEAD~1").trim();
    String two = SyncPanel.git(r, "rev-parse", "HEAD").trim();
    // 커밋 되돌리기: 커밋만 없어지고 바뀐 파일은 색인에 남는다.
    p = GitUndo.read(r);
    eq(p.mode(), GitUndo.Mode.SOFT);
    eq(p.target(), one);
    eq(p.onServer(), false);
    GitUndo.run(r, p);
    eq(SyncPanel.git(r, "rev-parse", "HEAD").trim(), one);
    eq(SyncPanel.git(r, "diff", "--cached", "--name-only"), "a\n");
    // 한 번 더 하면 되돌리기 전으로 돌아간다.
    p = GitUndo.read(r);
    eq(p.mode(), GitUndo.Mode.KEEP);
    GitUndo.run(r, p);
    eq(SyncPanel.git(r, "rev-parse", "HEAD").trim(), two);
    eq(SyncPanel.git(r, "status", "--porcelain"), "");
    // 체크아웃 되돌리기: 앞 브랜치로 다시 바꾼다.
    sh(r, "git checkout -q -b dev && git checkout -q main");
    p = GitUndo.read(r);
    eq(p.mode(), GitUndo.Mode.CHECKOUT);
    eq(p.target(), "dev");
    GitUndo.run(r, p);
    eq(SyncPanel.git(r, "rev-parse", "--abbrev-ref", "HEAD").trim(), "dev");
    // 합치기 되돌리기: 겹치는 내 변경이 있으면 아무것도 바꾸지 않고 멈추고, 없으면 앞 위치로 돌린다.
    sh(r, "git checkout -q main && git checkout -q -b side && echo 3 > a && git commit -qam three && git checkout -q main && git merge -q side");
    p = GitUndo.read(r);
    eq(p.mode(), GitUndo.Mode.KEEP);
    eq(p.target(), two);
    sh(r, "echo dirty > a");
    try {
      GitUndo.run(r, p);
      throw new AssertionError("내 변경과 겹치면 멈춰야 한다");
    }
    catch (IOException expected) {
      eq(java.nio.file.Files.readString(new File(r, "a").toPath()), "dirty\n");
      eq(SyncPanel.git(r, "rev-parse", "HEAD").trim(), SyncPanel.git(r, "rev-parse", "side").trim());
    }
    sh(r, "git checkout -q -- a");
    GitUndo.run(r, p);
    eq(SyncPanel.git(r, "rev-parse", "HEAD").trim(), two);
    // 서버에 있는 커밋을 없애면 알린다.
    sh(tmp.toFile(), "git clone -q --bare r server.git && cd r && git remote add origin ../server.git && git fetch -q origin && git branch -q -u origin/main"
                     + " && echo 4 > a && git commit -qam four && git push -q origin main");
    eq(GitUndo.read(r).onServer(), true);
    // 합치기가 진행 중이거나 브랜치가 없으면 하지 않는다.
    sh(r, "touch .git/MERGE_HEAD");
    try {
      GitUndo.read(r);
      throw new AssertionError("합치기 중에는 되돌리지 않아야 한다");
    }
    catch (IOException expected) {
      sh(r, "rm .git/MERGE_HEAD");
    }
    sh(r, "git checkout -q --detach");
    try {
      GitUndo.read(r);
      throw new AssertionError("detached HEAD 에서는 되돌리지 않아야 한다");
    }
    catch (IOException expected) {
      // 멈춤.
    }
  }

  /** 저장소 안의 다른 저장소 폴더(.git 이 있는 폴더)만 중첩 저장소로 본다. compare 는 IDE 작업 스레드를 써서 여기서는 판정만 본다. */
  private static void checkNestedRepo() throws Exception {
    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("nested");
    sh(tmp.toFile(), "git init -q outer && cd outer && git init -q inner && mkdir plain && echo p > plain/p.txt");
    File outer = tmp.resolve("outer").toFile();
    eq(SyncPanel.isNestedRepo(new File(outer, "inner/")), true);
    eq(SyncPanel.isNestedRepo(new File(outer, "plain/")), false);
    // git 은 중첩 저장소를 「?? inner/」 로 보고하므로 compare 가 이 줄을 거른다.
    eq(SyncPanel.git(outer, "status", "--porcelain=v1", "-unormal").contains("?? inner/"), true);
  }

  private static void sh(File dir, String command) throws Exception {
    SyncPanel.run(dir, "sh", "-c", command);
  }

  /** Git 로그인: 서버 주소 다듬기, Basic 머리글, GIT_CONFIG_* 환경값, 저장 형식. */
  private static void checkGitLogins() {
    eq(GitLogins.serverOf("https://AXPLM-GitLab.example.com/pjt/a.git"), "https://axplm-gitlab.example.com");
    eq(GitLogins.serverOf("gitlab.example.com:8443/x"), "https://gitlab.example.com:8443");
    eq(GitLogins.serverOf("https://user@host.com/"), "https://host.com");
    for (String bad : List.of("", "git@host.com:a/b.git", "ssh://host.com/a")) {
      try {
        GitLogins.serverOf(bad);
        throw new AssertionError("https 가 아닌 주소는 거절해야 한다: " + bad);
      }
      catch (IllegalArgumentException expected) {
        // 거절이 맞다.
      }
    }

    GitLogins.Login pw = new GitLogins.Login("https://a.com", GitLogins.Kind.PASSWORD, "kim", "p:w");
    GitLogins.Login token = new GitLogins.Login("https://b.com:8443", GitLogins.Kind.TOKEN, "", "glpat-1");
    GitLogins.Login empty = new GitLogins.Login("https://c.com", GitLogins.Kind.TOKEN, "", "");
    eq(GitLogins.header(pw), "Authorization: Basic " + java.util.Base64.getEncoder().encodeToString("kim:p:w".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    eq(GitLogins.header(token), "Authorization: Basic " + java.util.Base64.getEncoder().encodeToString("oauth2:glpat-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    java.util.Map<String, String> env = GitLogins.env(List.of(pw, empty, token), java.util.Map.of());
    eq(env.get("GIT_CONFIG_COUNT"), "2");
    eq(env.get("GIT_CONFIG_KEY_0"), "http.https://a.com/.extraHeader");
    eq(env.get("GIT_CONFIG_KEY_1"), "http.https://b.com:8443/.extraHeader");
    eq(env.get("GIT_CONFIG_VALUE_1"), GitLogins.header(token));
    // IDE 에 이미 GIT_CONFIG_COUNT 가 있으면 그 뒤에 잇는다.
    java.util.Map<String, String> after = GitLogins.env(List.of(pw), java.util.Map.of("GIT_CONFIG_COUNT", "2"));
    eq(after.get("GIT_CONFIG_KEY_2"), "http.https://a.com/.extraHeader");
    eq(after.get("GIT_CONFIG_COUNT"), "3");
    eq(GitLogins.env(List.of(empty), java.util.Map.of()), java.util.Map.of());

    List<GitLogins.Login> parsed = GitLogins.parse(GitLogins.format(List.of(pw, token)) + "\nbroken\nhttps://d.com\tWHAT\tx");
    eq(parsed.size(), 2);
    eq(parsed.get(0), new GitLogins.Login("https://a.com", GitLogins.Kind.PASSWORD, "kim", null));
    eq(parsed.get(1), new GitLogins.Login("https://b.com:8443", GitLogins.Kind.TOKEN, "", null));
    eq(GitLogins.parse(""), List.of());
  }
}
