package kinggura.sync;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** 자체 점검: java -cp out:test-out:<IDE jar> kinggura.sync.SyncCheck */
public class SyncCheck {
  /** git 을 띄우는 횟수를 줄이며 새로 만든 출력 읽기 · 명령 고르기. */
  static void checkFewerSpawns() {
    // status --porcelain=v1 -z -uall: 추적 안 함 · 올려 둔 변경 · 올리지 않은 변경, 이름 바꾸기는 옛 경로 칸을 건너뛴다.
    ReverseMerge.WorkTree w = ReverseMerge.parseStatusZ(" M a.txt\0?? new/b.txt\0R  c2.txt\0c1.txt\0 D d.txt\0");
    eq(w.untracked(), Set.of("new/b.txt"));
    eq(w.staged(), true);
    eq(w.unstaged(), Set.of("a.txt", "d.txt"));
    eq(ReverseMerge.blocksMerge(w, Set.of("zzz")), true);
    ReverseMerge.WorkTree clean = ReverseMerge.parseStatusZ(" M a b.txt\0?? x\0");
    eq(clean.staged(), false);
    eq(clean.unstaged(), Set.of("a b.txt"));
    eq(ReverseMerge.blocksMerge(clean, Set.of("x")), false);
    eq(ReverseMerge.blocksMerge(clean, Set.of("a b.txt")), true);
    eq(ReverseMerge.parseStatusZ("").untracked(), Set.of());
    // 충돌이 남은 항목(UU)은 올려 둔 변경으로 보아 합치기를 막는다.
    eq(ReverseMerge.parseStatusZ("UU m.txt\0").staged(), true);
    // 옮기지도 따라잡지도 않으면 diff 는 하나, 모두 하면 셋.
    eq(ReverseMerge.touchedCommands("HEAD", "HEAD", "abc").size(), 1);
    eq(ReverseMerge.touchedCommands("HEAD", "refs/remotes/origin/f", "abc").size(), 2);
    eq(ReverseMerge.touchedCommands("refs/heads/f", "refs/remotes/origin/f", "abc").size(), 3);
    // for-each-ref --format=%(objectname) %(refname)
    java.util.Map<String, String> tips = ReverseMerge.parseRefTips("aaa refs/remotes/origin/HEAD\nbbb refs/remotes/origin/develop\nccc refs/remotes/up/main\n");
    eq(tips.get("refs/remotes/origin/develop"), "bbb");
    eq(ReverseMerge.parseRefs(String.join("\n", tips.keySet()), "origin"), List.of("develop"));
  }

  /** 2026-11-06 동기화 전수조사에서 고친 순수 판단들. */
  static void checkAudit1106() throws Exception {
    checkFewerSpawns();
    // 인자 없는 fetch 가 기준 브랜치까지 받는지(remote.<원격>.fetch 줄들을 git 규칙대로).
    eq(ReverseMerge.fetches(java.util.Map.of("remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"), "origin", "develop"), true);
    eq(ReverseMerge.fetches(java.util.Map.of("remote.origin.fetch", "refs/heads/*:refs/remotes/origin/*"), "origin", "develop"), true);
    eq(ReverseMerge.fetches(java.util.Map.of("remote.origin.fetch", "+refs/heads/develop:refs/remotes/origin/develop"), "origin", "develop"), true);
    eq(ReverseMerge.fetches(java.util.Map.of("remote.origin.fetch", "+refs/heads/feature/a:refs/remotes/origin/feature/a"), "origin", "develop"), false);
    eq(ReverseMerge.fetches(java.util.Map.of("remote.origin.fetch", "+refs/heads/*:refs/remotes/upstream/*"), "origin", "develop"), false);
    eq(ReverseMerge.fetches(java.util.Map.of(), "origin", "develop"), false);
    // fetch 줄이 여럿이면(Feature/* 를 더 받는 줄 등) 어느 줄이든 기준 브랜치를 받으면 된다.
    String twoLines = "remote.origin.fetch +refs/heads/Feature/*:refs/remotes/origin/Feature/*\nremote.origin.fetch +refs/heads/develop:refs/remotes/origin/develop\n";
    eq(ReverseMerge.fetches(ReverseMerge.parseConfigs(twoLines), "origin", "develop"), true);
    eq(ReverseMerge.fetches(ReverseMerge.parseConfigs(twoLines), "origin", "master"), false);
    eq(ReverseMerge.fetches(ReverseMerge.parseConfigs("remote.origin.fetch +refs/heads/*:refs/remotes/origin/*\nremote.origin.fetch ^refs/heads/develop\n"), "origin", "develop"), false);
    // 받아 온 뒤 비교의 앞선 · 뒤진 수(rev-list --left-right --count HEAD...@{u}).
    eq(SyncPanel.parseLeftRight("2\t5\n"), new SyncPanel.Tracking(2, 5));
    eq(SyncPanel.parseLeftRight("0\t0"), new SyncPanel.Tracking(0, 0));
    try {
      SyncPanel.parseLeftRight("fatal: no upstream");
      throw new AssertionError("parseLeftRight 가 깨진 출력을 받아들였다");
    }
    catch (java.io.IOException expected) {
      // 읽지 못하면 IOException.
    }
    SyncPanel.Repo r = new SyncPanel.Repo(new File("."), SyncPanel.Vcs.GIT, "main", "origin/main");
    // 대소문자만 다른 경로: 윈도우(대소문자 안 가림)에서는 같은 파일이라 충돌, 리눅스에서는 따로.
    List<SyncPanel.Change> ci = SyncPanel.merge(r, java.util.Map.of("Foo.vue", "M"), java.util.Map.of("foo.vue", "R"), Set.of(), false);
    eq(ci.size(), 1);
    eq(ci.get(0).kind(), SyncPanel.Kind.CONFLICT);
    eq(SyncPanel.merge(r, java.util.Map.of("Foo.vue", "M"), java.util.Map.of("foo.vue", "R"), Set.of(), true).size(), 2);
    // 저장소에 대소문자만 다른 두 이름이 함께 커밋된 것 찾기.
    eq(SyncPanel.caseClashes(SyncPanel.lsFilesPaths("H a/Designchanges.vue\nH a/DesignChanges.vue\nH b.txt\n"), false),
       List.of("a/DesignChanges.vue", "a/Designchanges.vue"));
    eq(SyncPanel.caseClashes(List.of("A", "a"), true), List.of());
    eq(SyncPanel.caseClashes(SyncPanel.lsFilesPaths("M a/Foo.vue\nM a/Foo.vue\nM a/Foo.vue\n"), false), List.of());
    // 멈춘 합치기 표시.
    File repo = java.nio.file.Files.createTempDirectory("state").toFile();
    new File(repo, ".git").mkdir();
    eq(SyncPanel.gitState(repo), null);
    java.nio.file.Files.writeString(new File(repo, ".git/MERGE_HEAD").toPath(), "x");
    eq(SyncPanel.gitState(repo), SyncBundle.message("sync.state.merge"));
    // 정션 · 링크로 연 경로가 git 맨 위 폴더와 실제로 같으면 IntelliJ 경로를 쓰고, 안쪽 폴더 매핑이면 git 맨 위 폴더.
    File inner = new File(repo, "inner");
    inner.mkdir();
    eq(SyncPanel.topLevel(repo, repo.getCanonicalPath() + "\n"), repo);
    eq(SyncPanel.topLevel(inner, repo.getPath()), repo);
    // 링크로 연 경로와 실제 경로는 같은 저장소로 본다. 윈도우에서 링크를 만들 권한이 없으면 링크 확인만 건너뛴다.
    eq(SyncPanel.sameDir(inner, repo), false);
    File link = new File(repo.getParentFile(), repo.getName() + "-link");
    try {
      java.nio.file.Files.createSymbolicLink(link.toPath(), repo.toPath());
      eq(SyncPanel.sameDir(link, repo), true);
      link.delete();
    }
    catch (java.io.IOException | UnsupportedOperationException noLink) {
      System.out.println("링크를 만들 수 없어 sameDir 링크 확인을 건너뜀: " + noLink);
    }
    // 우리 Stash 는 맨 위가 아니어도 찾는다.
    eq(ReverseMerge.ourStash("stash@{0}\tOn feature/kim: 남의것\nstash@{1}\tOn feature/kim: " + ReverseMerge.STASH_MESSAGE + "\n"), "stash@{1}");
    eq(ReverseMerge.ourStash("stash@{0}\tOn main: 남의것\n"), null);
    eq(ReverseMerge.ourStash(""), null);
    // 합치기를 막는 추적 안 한 파일: 대소문자를 가리지 않는 저장소면 대소문자만 다른 이름도 막는다.
    eq(ReverseMerge.inTheWay(new java.util.LinkedHashSet<>(List.of("new.txt", "Mine.VUE", "other.txt")), Set.of("new.txt", "mine.vue", "x.txt"), true), List.of("new.txt", "Mine.VUE"));
    eq(ReverseMerge.inTheWay(new java.util.LinkedHashSet<>(List.of("new.txt", "Mine.VUE")), Set.of("new.txt", "mine.vue"), false), List.of("new.txt"));
    eq(ReverseMerge.inTheWay(Set.of(), Set.of("a"), true), List.of());
    eq(ReverseMerge.pathList(List.of("a.txt", "b/c.txt")), "a.txt\nb/c.txt");
    // 기억된 합치기가 지금 브랜치와 관련 있을 때만 쓴다.
    eq(ReverseMerge.rememberedFor("featureB", "featureB", false), true);
    eq(ReverseMerge.rememberedFor("develop", "featureB", false), false);
    eq(ReverseMerge.rememberedFor(null, "featureB", false), false);
    eq(ReverseMerge.rememberedFor("Feature/b", "feature/b", true), true);
    eq(ReverseMerge.rememberedFor("Feature/b", "feature/b", false), false);
    // Undo: 커밋을 옮기지 않은 reflog 줄(stash 의 reset: moving to HEAD)은 건너뛴다.
    String f = "\u001f";
    GitUndo.Plan p = GitUndo.plan("", "b" + f + "main@{t3}" + f + "reset: moving to HEAD\nb" + f + "main@{t2}" + f + "commit: 고침\na" + f + "main@{t1}" + f + "commit: 앞", "main");
    eq(p, new GitUndo.Plan(GitUndo.Mode.SOFT, "commit: 고침", "t2", "a", false));
    eq(GitUndo.plan("", "b" + f + "x" + f + "reset: moving to HEAD\nb" + f + "x" + f + "commit (initial): one", "main"), null);
    eq(GitUndo.fromUpstream("rebase (finish): refs/heads/kim onto abc123", "origin/kim", "abc123"), true);
    eq(GitUndo.fromUpstream("rebase (finish): refs/heads/kim onto abc123", "origin/kim", "fff000"), false);
    eq(GitUndo.fromUpstream("commit: 고침", "origin/kim", "abc123"), false);
    // 머리글 인증은 https · http 원격만. 파일 경로 · ssh 원격은 서버로 보지 않는다.
    eq(GitLogins.isHttpUrl("https://axplm-gitlab.example.com/a.git"), true);
    eq(GitLogins.isHttpUrl("D:/git/x.git"), false);
    eq(GitLogins.isHttpUrl("git@host:2024/repo.git"), false);
    eq(GitRemoteAuth.serverUrls(List.of("D:/git/x.git", "git@host:2024/repo.git", "https://Git.Example.com:443/a.git")).keySet(),
       Set.of("https://git.example.com"));
    // 다른 서버의 비밀번호는 넘기지 않는다.
    GitLogins.Login a = new GitLogins.Login("https://a.example.com", GitLogins.Kind.PASSWORD, "kim", "pw");
    GitLogins.Login b = new GitLogins.Login("https://b.example.com:8443", GitLogins.Kind.TOKEN, "", "tok");
    eq(GitLogins.matching(List.of(a, b), Set.of("https://b.example.com:8443")), List.of(b));
    eq(GitLogins.matching(List.of(a, b), Set.of()), List.of());
  }

  /** 대소문자만 다른 서버 브랜치를 막는 음수 refspec. */
  static void checkCaseClash() {
    List<String> server = List.of("Feature/gpm/dxplmdev.hbchoi", "develop", "feature/gpm/dxplmdev.jmlee", "feature/gpm/dxplmdev.kim", "main");
    // 내 추적 브랜치가 쓰는 소문자 폴더를 남긴다.
    eq(ReverseMerge.caseClashExcludes(server, List.of("feature/gpm/dxplmdev.jmlee")), List.of("^refs/heads/Feature/*"));
    // 내 브랜치가 대문자 쪽이면 대문자를 남긴다(axplm-back-serviceadapter 모양).
    eq(ReverseMerge.caseClashExcludes(server, List.of("Feature/gpm/dxplmdev.jmlee")), List.of("^refs/heads/feature/*"));
    // keep 이 비거나 null 이면 브랜치가 많은 철자.
    eq(ReverseMerge.caseClashExcludes(server, java.util.Arrays.asList(null, "develop")), List.of("^refs/heads/Feature/*"));
    // 겹치지 않으면 빈 목록. 서로 다른 이름의 폴더는 겹침이 아니다.
    eq(ReverseMerge.caseClashExcludes(List.of("develop", "feature/a", "bugfix/a"), List.of()), List.of());
    // 깊은 단계만 겹침, 이름 자체(잎)만 겹침, 잎과 폴더가 겹침.
    eq(ReverseMerge.caseClashExcludes(List.of("feature/GPM/x", "feature/gpm/y", "feature/gpm/z"), List.of()), List.of("^refs/heads/feature/GPM/*"));
    eq(ReverseMerge.caseClashExcludes(List.of("Develop", "develop", "main"), List.of("develop")), List.of("^refs/heads/Develop"));
    eq(ReverseMerge.caseClashExcludes(List.of("Release", "release/1", "release/2"), List.of()), List.of("^refs/heads/Release"));
    eq(ReverseMerge.excluded(List.of("^refs/heads/Feature/*"), "Feature/gpm/dxplmdev.jmlee"), true);
    eq(ReverseMerge.excluded(List.of("^refs/heads/Feature/*"), "feature/gpm/dxplmdev.jmlee"), false);
    eq(ReverseMerge.excluded(List.of("^refs/heads/Develop"), "Develop"), true);
    eq(ReverseMerge.excluded(List.of("^refs/heads/Develop"), "Develop2"), false);
    // 받기 설정에 음수 줄이 있으면 그 브랜치는 받지 않는 것으로 본다(역머지 From 은 따로 받음).
    eq(ReverseMerge.fetches(java.util.Map.of("remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*\n^refs/heads/Feature/*"), "origin", "Feature/x"), false);
    eq(ReverseMerge.negativeRefspecs("git version 2.47.1.windows.1"), true);
    eq(ReverseMerge.negativeRefspecs("git version 2.29.0"), true);
    eq(ReverseMerge.negativeRefspecs("git version 2.28.0"), false);
    eq(ReverseMerge.negativeRefspecs("git version 3.0.0"), true);
    eq(ReverseMerge.negativeRefspecs(""), false);
    // 윈도우: for-each-ref 가 디스크 폴더 철자로 낸 참조도 찾고, 서버 철자 Into 가 지금 브랜치면 HEAD 와 비교한다.
    java.util.Map<String, String> tips = java.util.Map.of("refs/remotes/origin/Feature/gpm/develop", "abc");
    eq(ReverseMerge.tipOf(tips, "refs/remotes/origin/feature/gpm/develop", true), "abc");
    eq(ReverseMerge.tipOf(tips, "refs/remotes/origin/feature/gpm/develop", false), null);
    eq(ReverseMerge.intoTip("origin", "Feature/x", "feature/x", true), "HEAD");
    eq(ReverseMerge.intoTip("origin", "Feature/x", "feature/x", false), "refs/remotes/origin/Feature/x");
    eq(ReverseMerge.folded(java.util.Map.of("core.ignorecase", "true")), true);
    eq(ReverseMerge.folded(java.util.Map.of()), false);
    // git 오류는 UTF-8 이면 그대로, 아니면 시스템 문자 집합으로 읽는다(깨진 UTF-8 이 대체 문자로 남지 않는다).
    eq(SyncPanel.decodeError("fatal: 한글".getBytes(java.nio.charset.StandardCharsets.UTF_8), "git"), "fatal: 한글");
  }

  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  /**
   * 점검을 돌리고 끝에 JVM 을 끝낸다. git 을 동시에 돌리는 곳(SyncPanel.inParallel)이 쓰는 IDE 작업 스레드는 데몬이 아니어서
   * 그냥 돌아가면 점검이 끝나도 프로세스가 남는다. 실패하면 원인을 찍고 1 로 끝낸다(빌드 대본이 멈추지 않고 실패로 본다).
   */
  public static void main(String[] args) {
    int code = 0;
    try {
      checks();
    }
    catch (Throwable e) {
      e.printStackTrace();
      code = 1;
    }
    System.out.flush();
    System.exit(code);
  }

  static void checks() throws Exception {
    checkCaseClash();
    // 모른 척 목록: 소문자(assume-unchanged) · 'S'(skip-worktree, 디스크에 있는 것만). 'H' 는 보통 파일.
    Set<String> onDisk = Set.of("skip.yml", "한글 경로/c.txt");
    eq(SyncPanel.parseAssumed("H src/A.java\nh src/main/resources/application-local.yml\nh 한글 경로/b.txt\nS skip.yml\nS sparse.txt\ns 한글 경로/c.txt\n", onDisk::contains),
       List.of("src/main/resources/application-local.yml", "한글 경로/b.txt", "skip.yml", "한글 경로/c.txt"));
    eq(SyncPanel.parseAssumed("", p -> true), List.of());
    // git log --name-status 는 최신 커밋이 먼저 나온다. 같은 경로는 최신 상태를 쓴다.
    eq(SyncPanel.parseNameStatus("D\ta.txt\n\nA\ta.txt\nM\tb.txt\n"), java.util.Map.of("a.txt", "D", "b.txt", "M"));

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
    checkAudit1106();
    checkCloneBranch();
    checkMavenUnignore();

    // Push 단추 숫자 · 받을 저장소: git status -b 브랜치 줄의 ahead · behind.
    eq(SyncPanel.parseTracking("## main...origin/main [ahead 2, behind 13]\n M a.txt\n"), new SyncPanel.Tracking(2, 13));
    eq(SyncPanel.parseTracking("## main...origin/main [behind 1]\n"), new SyncPanel.Tracking(0, 1));
    eq(SyncPanel.parseTracking("## feature/new\n?? b/\n"), new SyncPanel.Tracking(0, 0));
    eq(SyncPanel.parseTracking("## HEAD (no branch)\n"), new SyncPanel.Tracking(0, 0));
    // 원격 추적 참조 값이 어긋난 fetch 오류만 그 참조를 지우고 다시 한다.
    eq(SyncPanel.refRace("git fetch 실패: error: cannot lock ref 'refs/remotes/origin/Feature/gpm/dxplmdev.hbchoi': is at d48d7dda02013f87869d5fceb73715e59df608b5 but expected 85a32cc78e90fdc3bdf1104f2e992970614da35e"), "refs/remotes/origin/Feature/gpm/dxplmdev.hbchoi");
    eq(SyncPanel.refRace("error: cannot lock ref 'refs/remotes/origin/a': unable to resolve reference"), null);
    eq(SyncPanel.refRace("error: cannot lock ref 'refs/heads/main': is at 1a but expected 2b"), null);
    eq(SyncPanel.refRace("fatal: Authentication failed"), null);
    eq(SyncPanel.refRace(null), null);
    eq(SyncPanel.refRace("error: 'refs/remotes/origin/Feature' exists; cannot create 'refs/remotes/origin/feature/x'"), "refs/remotes/origin/Feature");
    // 경로 묶음: 명령줄 한도를 넘지 않게 나누고 순서 · 개수는 그대로.
    List<String> many = new java.util.ArrayList<>();
    for (int i = 0; i < 3000; i++) many.add("src/main/java/com/shop/order/Order" + i + ".java");
    List<List<String>> parts = SyncPanel.chunks(many);
    eq(parts.size() > 1, true);
    List<String> joined = new java.util.ArrayList<>();
    for (List<String> part : parts) {
      eq(String.join(" ", part).length() < 32_000, true);
      joined.addAll(part);
    }
    eq(joined, many);
    eq(SyncPanel.chunks(List.of()), List.of());
    eq(SyncPanel.chunks(List.of("a")), List.of(List.of("a")));
    // svn 경로의 @ 는 끝에 @ 를 붙여 이름 그대로 읽게 한다.
    eq(SyncPanel.svnPath("img/icon@2x.png"), "img/icon@2x.png@");
    eq(SyncPanel.svnPath("img/icon.png"), "img/icon.png");
    // .gitignore 줄 이스케이프: 글로브 글자 · 맨 앞 # · ! · 끝 공백.
    eq(SyncPanel.gitignoreEscape("/a[1]*?.txt"), "/a\\[1\\]\\*\\?.txt");
    eq(SyncPanel.gitignoreEscape("#x"), "\\#x");
    eq(SyncPanel.gitignoreEscape("!x"), "\\!x");
    eq(SyncPanel.gitignoreEscape("/a#b!c"), "/a#b!c");
    eq(SyncPanel.gitignoreEscape("/sp  "), "/sp\\ \\ ");
    checkGitignore();
    eq(SyncPanel.shortDir("src/main/java/com/shop/order"), "src/main/java/\u2026/order");
    eq(SyncPanel.shortDir("src/main/resources/mapper"), "src/main/resources/mapper");
    eq(SyncPanel.shortDir("src"), "src");
    eq(SyncPanel.nameSlash("src/a/Foo.java"), 5);
    eq(SyncPanel.nameSlash("src/a/tooling/"), 5);
    eq(SyncPanel.nameSlash("tooling/"), -1);
    eq(SyncPanel.nameSlash("Foo.java"), -1);
    // 받기 알림: 커밋 수와 겹치지 않는 파일 수.
    // PULLED_FORMAT(-m): 합치기 커밋 m 은 부모마다 한 번씩 나오고, 커밋 수는 한 번 · 파일 수에서는 빠진다. 다시 읽을 파일에는 들어간다.
    String pulledLog = "\u0001c2 c1\n\nA.java\nb/B.xml\n\u0001c1 c0\n\nA.java\n\u0001m c2 x\n\nM.java\n\u0001m c2 x\n\nA.java\n\u0001c0\n\nR.java\n";
    eq(SyncPanel.countPulled(pulledLog), new SyncPanel.Pulled(4, 3));
    eq(SyncPanel.pulledPaths(pulledLog), Set.of("A.java", "b/B.xml", "M.java", "R.java"));
    eq(SyncPanel.countPulled(""), new SyncPanel.Pulled(0, 0));

    checkUnmappedVcs();
    checkBuildFiles();
    checkWorkspaceRepos();
    checkOutputDirs();
    checkInSvn();
    checkRun();
    checkReverseMerge();
    checkFindWindowsGit();
    checkGitLogins();
    checkGitUndo();
    checkStability1112();
    checkPullFailures();
    checkStability1113();
    System.out.println("SyncCheck 통과");
  }

  /** 대소문자 막기 줄 정리(남길 브랜치를 막는 옛 음수 줄은 지움) · 추적 원격이 없을 때 하나뿐인 원격. 진짜 git 저장소로. */
  private static void checkStability1113() throws Exception {
    eq(ReverseMerge.regexQuoted("^refs/heads/a.b/*"), "\\^refs/heads/a\\.b/\\*");
    File tmp = java.nio.file.Files.createTempDirectory("stab1113").toFile();
    sh(tmp, "git init -q --bare srv.git && git init -q -b main seed && cd seed && git config user.name t && git config user.email t@t"
            + " && git commit -q --allow-empty -m one && git branch Feature/a && git branch feature/b && git branch fix/a.b"
            + " && git push -q ../srv.git main Feature/a feature/b fix/a.b && cd .. && git clone -q srv.git mine");
    File mine = new File(tmp, "mine");
    // 옛 줄: 소문자 쪽을 막아 두었는데 지금 브랜치가 feature/b 로 바뀜. 글자 그대로 같은 줄만 지우고(fix/aXb 줄은 남김) 대문자 쪽을 막는다.
    sh(mine, "git config --add remote.origin.fetch '^refs/heads/feature/*' && git config --add remote.origin.fetch '^refs/heads/feature/*'"
             + " && git config --add remote.origin.fetch '^refs/heads/fix/aXb' && git update-ref -d refs/remotes/origin/feature/b");
    eq(ReverseMerge.blockCaseClashes(mine, ReverseMerge.configs(mine), "origin", java.util.Arrays.asList("feature/b", null)), true);
    eq(SyncPanel.git(mine, "config", "--get-all", "remote.origin.fetch"), "+refs/heads/*:refs/remotes/origin/*\n^refs/heads/fix/aXb\n^refs/heads/Feature/*\n");
    eq(SyncPanel.git(mine, "rev-parse", "--verify", "-q", "refs/remotes/origin/feature/b").isBlank(), false);
    // 두 번째: 줄이 그대로다.
    ReverseMerge.blockCaseClashes(mine, ReverseMerge.configs(mine), "origin", java.util.Arrays.asList("feature/b", null));
    eq(SyncPanel.git(mine, "config", "--get-all", "remote.origin.fetch"), "+refs/heads/*:refs/remotes/origin/*\n^refs/heads/fix/aXb\n^refs/heads/Feature/*\n");
    // 추적 브랜치 없는 브랜치: 원격이 하나뿐이면 그 원격, 여럿이면 origin.
    sh(mine, "git remote rename origin corp");
    eq(ReverseMerge.remoteOf(ReverseMerge.configs(mine), "local-only"), "corp");
    eq(ReverseMerge.remoteOf(ReverseMerge.configs(mine), "main"), "corp");
    sh(mine, "git remote add other ../srv.git");
    eq(ReverseMerge.remoteOf(ReverseMerge.configs(mine), "local-only"), ReverseMerge.DEFAULT_REMOTE);
    eq(ReverseMerge.remoteOf(java.util.Map.of(), null), ReverseMerge.DEFAULT_REMOTE);
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

  /** 워크스페이스 판정과 그 안 저장소 폴더: .git · package.json · 빌드 파일이 있는 폴더, 없으면 묶음 폴더 한 단계 아래. 점 · 빌드 결과 · SVN 폴더는 빼고 이름순. */
  private static void checkWorkspaceRepos() throws IOException {
    java.nio.file.Path ws = java.nio.file.Files.createTempDirectory("ws");
    eq(CloneIntoProject.looseWorkspace(ws), true);
    eq(CloneIntoProject.repoDirs(ws), List.of());
    java.nio.file.Path vue = java.nio.file.Files.createDirectories(ws.resolve("vue/.git")).getParent();
    java.nio.file.Path boot = java.nio.file.Files.createDirectories(ws.resolve("springboot-test"));
    java.nio.file.Files.writeString(boot.resolve("pom.xml"), "");
    java.nio.file.Path backend = java.nio.file.Files.createDirectories(ws.resolve("supp-backend/.git")).getParent();
    java.nio.file.Files.createDirectories(backend.resolve("supp"));
    java.nio.file.Files.writeString(backend.resolve("supp/build.gradle"), "");
    java.nio.file.Path web = java.nio.file.Files.createDirectories(ws.resolve("supp-frontend"));
    java.nio.file.Files.writeString(web.resolve("package.json"), "{}");
    // 묶음 폴더: Git 저장소 · 빌드 파일이 두 단계 아래인 폴더는 붙이고, 아무것도 없는 폴더는 붙이지 않는다.
    java.nio.file.Path groupGit = java.nio.file.Files.createDirectories(ws.resolve("group/api/.git")).getParent();
    java.nio.file.Path groupDeep = java.nio.file.Files.createDirectories(ws.resolve("group/batch/app"));
    java.nio.file.Files.writeString(groupDeep.resolve("pom.xml"), "");
    java.nio.file.Files.createDirectories(ws.resolve("group/notes"));
    java.nio.file.Files.createDirectories(ws.resolve("docs"));
    java.nio.file.Files.createDirectories(ws.resolve(".idea"));
    java.nio.file.Files.writeString(ws.resolve(".idea/pom.xml"), "");
    java.nio.file.Files.createDirectories(ws.resolve("svn-app/.svn"));
    java.nio.file.Files.writeString(ws.resolve("svn-app/pom.xml"), "");
    java.nio.file.Files.createDirectories(ws.resolve("group/svn-lib/.svn"));
    java.nio.file.Files.createDirectories(ws.resolve("target"));
    java.nio.file.Files.writeString(ws.resolve("target/pom.xml"), "");
    eq(CloneIntoProject.repoDirs(ws), List.of(groupGit, groupDeep.getParent(), boot, backend, web, vue));
    // 저장소 폴더 자체를 열거나 맨 위에 빌드 파일 · package.json 이 있으면 워크스페이스가 아니다.
    eq(CloneIntoProject.looseWorkspace(vue), false);
    eq(CloneIntoProject.looseWorkspace(boot), false);
    eq(CloneIntoProject.looseWorkspace(java.nio.file.Path.of(System.getProperty("user.home"))), false);
    java.nio.file.Files.writeString(ws.resolve("package.json"), "{}");
    eq(CloneIntoProject.looseWorkspace(ws), false);
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
    eq(ReverseMerge.serverSpelling(List.of("develop", "feature/kim"), "Feature/kim"), "feature/kim");
    eq(ReverseMerge.serverSpelling(List.of("develop", "feature/kim"), "feature/kim"), null);
    eq(ReverseMerge.serverSpelling(List.of("develop"), "feature/kim"), null);
    eq(ReverseMerge.serverSpelling(List.of("develop"), null), null);
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
    eq(knownBranches(mine), List.of("feature/kim"));
    ReverseMerge.fetch(mine, "origin", "develop", "feature/kim");
    eq(knownBranches(mine), List.of("develop", "feature/kim"));
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
      merge(mine, "origin", "develop", "feature/kim", "feature/kim", true, false);
      throw new AssertionError("커밋 안 한 변경이 있으면 합치지 않아야 한다");
    }
    catch (ReverseMerge.DirtyException expected) {
      eq(ReverseMerge.conflicts(mine), Set.of());
    }
    sh(mine, "git checkout -q -- B.java");
    eq(merge(mine, "origin", "develop", "feature/kim", "feature/kim", true, false), ReverseMerge.Result.CONFLICT);
    eq(ReverseMerge.conflicts(mine), Set.of("A.java"));
    // 충돌이 남았으면 마무리하지 않는다. 해결하면 합치기 커밋 · 푸시까지 한다.
    eq(ReverseMerge.finish(mine, "origin", "feature/kim", "feature/kim", true, false), false);
    sh(mine, "echo resolved > A.java && git add A.java");
    eq(ReverseMerge.finish(mine, "origin", "feature/kim", "feature/kim", true, false), true);
    eq(SyncPanel.git(mine, "rev-parse", "HEAD").trim(), SyncPanel.git(server, "rev-parse", "feature/kim").trim());
    eq(ReverseMerge.finish(mine, "origin", "feature/kim", "feature/kim", true, false), false);  // 이미 마무리됨(MERGE_HEAD 없음)
    // 충돌 없는 쪽: 다른 개발자가 내 파일을 건드리지 않은 develop 을 새 개인 브랜치로 합치고 푸시.
    sh(other, "git push -q origin develop~1:refs/heads/feature/new");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/new");
    eq(merge(mine, "origin", "develop", "feature/new", "feature/kim", true, false), ReverseMerge.Result.MERGED);
    // 다른 브랜치에 합쳤으면 원래 브랜치로 돌아온다.
    eq(SyncPanel.git(mine, "rev-parse", "--abbrev-ref", "HEAD").trim(), "feature/kim");
    eq(SyncPanel.git(mine, "rev-parse", "feature/new").trim(), SyncPanel.git(server, "rev-parse", "feature/new").trim());
    // fetch 설정에 없는 브랜치로 올려도 원격 추적 ref 가 서버와 같다(동기화 창이 조회 없이 바로 센다).
    eq(SyncPanel.git(mine, "rev-parse", ReverseMerge.remoteRef("origin", "feature/new")).trim(), SyncPanel.git(server, "rev-parse", "feature/new").trim());
    // 서버와 갈라져 멈춰도 원래 브랜치로 돌아온다.
    sh(other, "git fetch -q origin feature/new && git checkout -q -b fnew origin/feature/new && echo o > O.java && git add O.java && git commit -qm o && git push -q origin fnew:feature/new");
    sh(mine, "git checkout -q feature/new && echo l > L.java && git add L.java && git commit -qm l && git checkout -q feature/kim");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/new");
    try {
      merge(mine, "origin", "develop", "feature/new", "feature/kim", true, false);
      throw new AssertionError("갈라진 개인 브랜치는 멈춰야 한다");
    }
    catch (IOException expected) {
      eq(SyncPanel.git(mine, "rev-parse", "--abbrev-ref", "HEAD").trim(), "feature/kim");
    }
    eq(ReverseMerge.unpushed(mine, "origin", "feature/new"), 1);
    // Stash 하고 합치기: 합칠 파일(B.java)과 겹치는 변경을 넣어 두었다가 합친 뒤 원래 브랜치에서 되살린다. 남의 Stash 는 건드리지 않는다.
    sh(other, "git checkout -q develop && echo d > D.java && echo y >> B.java && git add D.java B.java && git commit -qm d && git push -q origin develop && git push -q origin develop~1:refs/heads/feature/stash");
    sh(mine, "echo keep > K.java && git add K.java && git stash push -q -m 남의것 && echo dirty >> B.java");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/stash");
    eq(merge(mine, "origin", "develop", "feature/stash", "feature/kim", false, true), ReverseMerge.Result.MERGED);
    eq(SyncPanel.git(mine, "rev-parse", "--abbrev-ref", "HEAD").trim(), "feature/kim");
    eq(SyncPanel.git(mine, "status", "--porcelain", "-uno"), " M B.java\n");
    eq(SyncPanel.git(mine, "stash", "list", "--format=%s").contains("남의것"), true);
    eq(SyncPanel.git(mine, "stash", "list", "--format=%s").contains(ReverseMerge.STASH_MESSAGE), false);
    ReverseMerge.unstash(mine);  // 맨 위가 우리 Stash 가 아니면 꺼내지 않는다.
    eq(SyncPanel.git(mine, "stash", "list", "--format=%s").contains("남의것"), true);
    sh(mine, "git checkout -q -- B.java");
    // 겹치지 않는 변경(A.java)은 묻지 않고 그대로 둔 채 합친다. 올려 둔(staged) 변경은 겹치지 않아도 막는다.
    sh(mine, "echo dirty >> A.java && git add A.java");
    try {
      merge(mine, "origin", "develop", "feature/kim", "feature/kim", false, false);
      throw new AssertionError("올려 둔 변경이 있으면 합치지 않아야 한다");
    }
    catch (ReverseMerge.DirtyException expected) {
      sh(mine, "git reset -q");
    }
    eq(merge(mine, "origin", "develop", "feature/kim", "feature/kim", false, false), ReverseMerge.Result.MERGED);
    eq(SyncPanel.git(mine, "status", "--porcelain", "-uno"), " M A.java\n");
    eq(SyncPanel.git(mine, "show", "HEAD:B.java").contains("y"), true);
    eq(SyncPanel.git(mine, "stash", "list", "--format=%s").contains(ReverseMerge.STASH_MESSAGE), false);
    sh(mine, "git checkout -q -- A.java");
    // 올리지 않은 내 커밋이 고친 파일(A.java)을 또 고쳐 두어도, 합치기가 그 파일을 건드리지 않으면 묻지 않는다.
    sh(mine, "echo unpushed >> A.java && git commit -qam 올리기전 && echo dirty >> A.java");
    sh(other, "echo e > E.java && git add E.java && git commit -qm e && git push -q origin develop");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/kim");
    eq(merge(mine, "origin", "develop", "feature/kim", "feature/kim", false, false), ReverseMerge.Result.MERGED);
    eq(SyncPanel.git(mine, "status", "--porcelain", "-uno"), " M A.java\n");
    sh(mine, "git checkout -q -- A.java");
    // 미리 보기 뒤 남이 서버 개인 브랜치에 올려도, merge 가 into 를 다시 받아 그 위에서 합치고 Push 한다.
    sh(mine, "git push -q origin feature/kim");
    sh(other, "git fetch -q origin feature/kim && git checkout -q FETCH_HEAD && echo k > K2.java && git add K2.java && git commit -qm k2"
              + " && git push -q origin HEAD:feature/kim && git checkout -q develop && echo f > F.java && git add F.java && git commit -qm f && git push -q origin develop");
    ReverseMerge.fetch(mine, "origin", "develop");
    eq(merge(mine, "origin", "develop", "feature/kim", "feature/kim", true, false), ReverseMerge.Result.MERGED);
    eq(SyncPanel.git(mine, "rev-parse", "HEAD").trim(), SyncPanel.git(server, "rev-parse", "feature/kim").trim());
    eq(SyncPanel.git(mine, "log", "-1", "--format=%s", "HEAD^1").trim(), "k2");
    // fetch 와 push 사이에 서버가 앞서면(push 주소를 앞선 저장소로 돌려 흉내) 합치기를 되돌리고 PushRejectedException. 커밋 안 한 변경은 그대로.
    sh(tmp.toFile(), "git clone -q --bare server.git ahead.git");
    sh(other, "git fetch -q origin feature/kim && git checkout -q FETCH_HEAD && echo h > H.java && git add H.java && git commit -qm h"
              + " && git push -q " + tmp.resolve("ahead.git").toString().replace('\\', '/') + " HEAD:feature/kim && git checkout -q develop && echo g > G.java && git add G.java && git commit -qm g && git push -q origin develop");
    sh(mine, "git config remote.origin.pushurl " + tmp.resolve("ahead.git").toString().replace('\\', '/') + " && echo dirty >> A.java");
    String beforeRejected = SyncPanel.git(mine, "rev-parse", "HEAD").trim();
    try {
      merge(mine, "origin", "develop", "feature/kim", "feature/kim", true, false);
      throw new AssertionError("서버가 앞서면 Push 가 거절돼야 한다");
    }
    catch (ReverseMerge.PushRejectedException expected) {
      eq(SyncPanel.git(mine, "rev-parse", "HEAD").trim(), beforeRejected);
      eq(SyncPanel.git(mine, "status", "--porcelain", "-uno"), " M A.java\n");
    }
    eq(ReverseMerge.behindServer("! [remote rejected] HEAD -> develop (pre-receive hook declined)"), false);
    sh(mine, "git config --unset remote.origin.pushurl && git checkout -q -- A.java");
    eq(ReverseMerge.lastUnpushed(mine, "origin", "feature/kim"), "");
    sh(mine, "git commit -q --allow-empty -m 올릴것");
    eq(ReverseMerge.lastUnpushed(mine, "origin", "feature/kim"), "올릴것");
    sh(mine, "git reset -q --hard HEAD~1");
    // 대소문자만 바뀐 이름: From 쪽이 바꾼 것만 잡고(갈라진 곳에 지금 이름이 있음), 내 쪽이 바꾼 것은 두며, 폴더 이름도 잡는다.
    eq(ReverseMerge.caseRenames(Set.of("A.vue", "b/C.vue", "D.vue"), Set.of("a.vue", "B/C.vue", "D.vue", "e.vue"), Set.of("A.vue", "b/C.vue", "D.vue")),
       java.util.Map.of("A.vue", "a.vue", "b/C.vue", "B/C.vue"));
    eq(ReverseMerge.caseRenames(Set.of("a.vue"), Set.of("A.vue"), Set.of("A.vue")), java.util.Map.of());
    // 다른 개발자가 develop 에서 Big.vue → big.vue, ui/X.vue → UI/X.vue 로 이름의 대소문자만 바꾸고 내용도 고쳤다(내용이 비슷해 git 이 이름 바꾸기로 본다).
    sh(other, "git checkout -q develop && git pull -q origin develop && mkdir -p ui && echo x > ui/X.vue && seq 1 30 > Big.vue && git add ui Big.vue && git commit -qm ui && git push -q origin develop");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/kim");
    eq(merge(mine, "origin", "develop", "feature/kim", "feature/kim", true, false), ReverseMerge.Result.MERGED);
    // 대소문자를 가리지 않는 파일 시스템(윈도우)에서도 돌도록 임시 이름을 거쳐 옮긴다.
    sh(other, "git mv Big.vue big.tmp && git mv big.tmp big.vue && echo more >> big.vue && git mv ui/X.vue X.tmp && rm -rf ui && mkdir UI && git mv X.tmp UI/X.vue && git commit -qam 대소문자 && git push -q origin develop");
    ReverseMerge.fetch(mine, "origin", "develop", "feature/kim");
    String tipBefore = SyncPanel.git(mine, "rev-parse", "HEAD").trim();
    // 대소문자를 가리는 저장소면 git 이 그대로 합칠 수 있어 묻지 않는다. 가리지 않는 저장소(윈도우)면 아무것도 하지 않고 멈춘다.
    sh(mine, "git config core.ignorecase true");
    try {
      merge(mine, "origin", "develop", "feature/kim", "feature/kim", true, false);
      throw new AssertionError("대소문자만 바뀐 이름이 있으면 묻기 전에 합치지 않아야 한다");
    }
    catch (ReverseMerge.CaseRenameException expected) {
      eq(expected.renames, java.util.Map.of("Big.vue", "big.vue", "ui/X.vue", "UI/X.vue"));
      eq(SyncPanel.git(mine, "rev-parse", "HEAD").trim(), tipBefore);
      eq(SyncPanel.git(mine, "status", "--porcelain"), "");
    }
    // 이름을 맞춰 커밋하고 합친 뒤 Push 한다. 새 이름만 남고 From 쪽 내용이 들어온다.
    String rDevelop = SyncPanel.git(mine, "rev-parse", ReverseMerge.remoteRef("origin", "develop")).trim();
    eq(ReverseMerge.merge(mine, "origin", "develop", rDevelop, "feature/kim", "feature/kim", true, false, true), ReverseMerge.Result.MERGED);
    Set<String> files = ReverseMerge.lines(SyncPanel.git(mine, "ls-tree", "-r", "--name-only", "HEAD"));
    eq(files.contains("big.vue") && files.contains("UI/X.vue") && !files.contains("Big.vue") && !files.contains("ui/X.vue"), true);
    eq(SyncPanel.git(mine, "show", "HEAD:big.vue").contains("more"), true);
    eq(SyncPanel.git(mine, "log", "-1", "--format=%s", "HEAD^1"), "파일 이름 대소문자를 origin/develop 에 맞춤\n");
    eq(SyncPanel.git(mine, "rev-parse", "HEAD").trim(), SyncPanel.git(server, "rev-parse", "feature/kim").trim());
    eq(SyncPanel.git(mine, "status", "--porcelain"), "");
    sh(mine, "git config core.ignorecase false");
    ReverseMerge.saveConfig(mine, ReverseMerge.CONFIG_FROM, "develop");
    java.util.Map<String, String> cfg = ReverseMerge.configs(mine);
    eq(cfg.get("user.email"), "me@x");
    eq(cfg.get(ReverseMerge.CONFIG_FROM), "develop");
    eq(cfg.get(ReverseMerge.CONFIG_INTO), null);
    eq(ReverseMerge.remoteOf(cfg, "feature/kim"), "origin");
    eq(ReverseMerge.parseConfigs("user.name 나\nbranch.a.remote origin\nempty.key\n"), java.util.Map.of("user.name", "나", "branch.a.remote", "origin"));
    eq(ReverseMerge.gitAll(mine, List.of(new String[]{"rev-parse", "--abbrev-ref", "HEAD"}, new String[]{"config", "--get", "user.email"})),
       List.of("feature/kim\n", "me@x\n"));
  }

  /** 지금까지 받아 둔 원격 브랜치 이름(refs/remotes/origin/*). */
  private static List<String> knownBranches(File root) throws IOException, InterruptedException {
    return ReverseMerge.parseRefs(SyncPanel.git(root, "for-each-ref", "--format=%(refname)", ReverseMerge.remoteRef("origin", "")), "origin");
  }

  /** 지금 받아 둔 From 을 합친다(미리 보기 없이 합치기를 점검할 때). */
  private static ReverseMerge.Result merge(File root, String remote, String from, String into, String current, boolean push, boolean stash)
    throws IOException, InterruptedException {
    return ReverseMerge.merge(root, remote, from, SyncPanel.git(root, "rev-parse", ReverseMerge.remoteRef(remote, from)).trim(), into, current, push, stash, false);
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

  /** 열 때 Maven 무시 풀기: 우리가 무시한 pom 만, 우리 기록이 없는 저장소는 전부, 다른 저장소는 그대로. */
  private static void checkMavenUnignore() {
    List<String> ignored = List.of("C:/git/a/pom.xml", "C:/git/a/sample/pom.xml", "C:/git/b/pom.xml", "C:/git/c/pom.xml", "C:/git/ab/pom.xml");
    eq(MavenRepos.toUnignore(ignored, List.of("C:/git/a/pom.xml"), List.of("C:/git/a")), List.of("C:/git/a/pom.xml"));
    eq(MavenRepos.toUnignore(ignored, List.of("C:/git/a/pom.xml"), List.of("C:/git/b")), List.of("C:/git/b/pom.xml"));
    eq(MavenRepos.toUnignore(ignored, List.of(), List.of("C:/git/a")), List.of("C:/git/a/pom.xml", "C:/git/a/sample/pom.xml"));
    eq(MavenRepos.toUnignore(ignored, List.of(), List.of("C:/git/x")), List.of());
  }

  /** 받을 브랜치: 서버 브랜치 읽기 · 내 브랜치 위로 · 찾기 · 추적 걸고 꺼내기. */
  private static void checkCloneBranch() throws Exception {
    eq(CloneBranch.parseBranches("refs/remotes/origin/HEAD\nrefs/remotes/origin/main\nrefs/remotes/origin/feature/gpm/dxplmdev.jmlee\nrefs/remotes/up/x\n", "origin"),
       List.of("feature/gpm/dxplmdev.jmlee", "main"));
    eq(CloneBranch.nameParts(List.of("이 정민", "JMLee@corp.com", "jm", " ")), java.util.Set.of("이정민", "jmlee"));
    List<String> all = List.of("develop", "feature/a.kim", "feature/b.JMLEE", "main");
    eq(CloneBranch.mine(all, java.util.Set.of("jmlee")), List.of("feature/b.JMLEE"));
    CloneBranch.Remote remote = new CloneBranch.Remote("origin", "main", all);
    eq(CloneBranch.ordered(remote, List.of("feature/b.JMLEE"), ""), List.of("feature/b.JMLEE", "main", "develop", "feature/a.kim"));
    eq(CloneBranch.ordered(remote, List.of(), " FEATURE "), List.of("feature/a.kim", "feature/b.JMLEE"));
    eq(CloneBranch.ordered(remote, List.of(), "없음"), List.of());

    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("clone-branch");
    sh(tmp.toFile(), "git init -q -b main src && cd src && git -c user.name=t -c user.email=t@x commit -q --allow-empty -m init"
                     + " && git branch dev/jmlee && git clone -q . ../work");
    File work = tmp.resolve("work").toFile();
    CloneBranch.Remote cloned = CloneBranch.remote(work);
    eq(cloned, new CloneBranch.Remote("origin", "main", List.of("dev/jmlee", "main")));
    CloneBranch.checkout(work, "origin", "dev/jmlee");
    eq(SyncPanel.git(work, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}").trim(), "origin/dev/jmlee");
    // 같은 이름 내 브랜치가 있으면 그리로 바꾼다.
    CloneBranch.checkout(work, "origin", "main");
    eq(SyncPanel.git(work, "rev-parse", "--abbrev-ref", "HEAD").trim(), "main");
    // 빈 저장소(추적 대상 없음)는 고를 것이 없다.
    sh(tmp.toFile(), "git init -q empty");
    eq(CloneBranch.remote(tmp.resolve("empty").toFile()), null);
  }

  /** 받기 실패 글 가르기: 갈라짐 · 덮어쓰기 · 충돌만 받기 창으로, 서버 연결 · 인증 실패는 이유를 보인다. 메시지 없는 예외는 예외 이름. */
  private static void checkPullFailures() {
    eq(SyncPanel.pullNeedsDialog("git merge 실패: fatal: Not possible to fast-forward, aborting."), true);
    eq(SyncPanel.pullNeedsDialog("git merge 실패: error: Your local changes to the following files would be overwritten by merge:\n\ta.txt"), true);
    eq(SyncPanel.pullNeedsDialog("git pull 실패: error: Pulling is not possible because you have unmerged files."), true);
    eq(SyncPanel.pullNeedsDialog("git fetch 실패: fatal: unable to access 'https://git.example.com/a.git/': Could not resolve host: git.example.com"), false);
    eq(SyncPanel.pullNeedsDialog("git fetch 실패: fatal: Authentication failed for 'https://git.example.com/a.git/'"), false);
    eq(SyncPanel.messageOf(new IOException((String) null)), "java.io.IOException");
    eq(SyncPanel.messageOf(new IOException("x")), "x");
  }

  /** 2026-11-12 안정화 전수조사: 저장소 찾기 · 참조 읽기 · 빈 저장소 · 받기 목록 · 이클립스 파일 찾기. */
  private static void checkStability1112() throws Exception {
    java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("stab");
    // 저장소 찾기: 가까운 것부터, 사용자 폴더 · 드라이브 맨 위는 보지 않는다.
    java.nio.file.Path outer = java.nio.file.Files.createDirectories(tmp.resolve("outer/.git"));
    java.nio.file.Path inner = java.nio.file.Files.createDirectories(tmp.resolve("outer/a/inner/src"));
    java.nio.file.Files.createDirectories(tmp.resolve("outer/a/inner/.svn"));
    eq(SyncPanel.vcsDirsAbove(inner), List.of(tmp.resolve("outer/a/inner"), outer.getParent()));
    eq(SyncPanel.unmappedVcs(inner.toFile()), SyncPanel.Vcs.SVN);
    eq(SyncPanel.vcsDirsAbove(tmp), List.of());
    eq(SyncPanel.aboveWorkspaces(java.nio.file.Path.of(System.getProperty("user.home"))), true);
    eq(SyncPanel.aboveWorkspaces(tmp.getRoot()), true);
    eq(SyncPanel.aboveWorkspaces(tmp), false);

    // 참조 읽기(git 없이): 낱 파일 · packed-refs · 기호 참조 · 없음.
    File repo = tmp.resolve("refs").toFile();
    sh(tmp.toFile(), "git init -q -b main refs && cd refs && git config user.email a@b && git config user.name a"
                     + " && echo 1 > a && git add a && git commit -qm one && git update-ref refs/remotes/origin/main HEAD");
    String head = SyncPanel.git(repo, "rev-parse", "HEAD").trim();
    eq(SyncPanel.refHash(repo, "HEAD"), head);
    eq(SyncPanel.refHash(repo, "refs/remotes/origin/main"), head);
    sh(repo, "git pack-refs --all");
    eq(new File(repo, ".git/refs/remotes/origin/main").exists(), false);
    eq(SyncPanel.refHash(repo, "refs/remotes/origin/main"), head);
    eq(SyncPanel.refHash(repo, "HEAD"), head);
    eq(SyncPanel.refHash(repo, "refs/remotes/origin/none"), null);
    eq(SyncPanel.refHash(tmp.toFile(), "HEAD"), null);
    sh(repo, "echo 2 >> a && git commit -qam two");
    eq(SyncPanel.refHash(repo, "HEAD"), SyncPanel.git(repo, "rev-parse", "HEAD").trim());

    // 커밋 없는 새 저장소도 저장소 정보를 읽는다(브랜치는 기호 참조, 추적 브랜치 없음).
    File empty = tmp.resolve("empty").toFile();
    sh(tmp.toFile(), "git init -q -b main empty");
    SyncPanel.Repo unborn = SyncPanel.gitRepo(empty);
    eq(unborn.branch(), "main");
    eq(unborn.upstream(), null);

    // 받기 목록(PULLED_FORMAT): 합치기 커밋은 한 번만 세고 그 차이도 다시 읽을 파일에 든다.
    sh(repo, "git checkout -q -b side && echo s > s.txt && git add s.txt && git commit -qm side"
             + " && git checkout -q main && echo m > m.txt && git add m.txt && git commit -qm main"
             + " && git merge -q --no-ff -m merge side");
    String before = SyncPanel.git(repo, "rev-parse", "HEAD~1~1").trim();
    String log = SyncPanel.git(repo, "log", SyncPanel.PULLED_FORMAT, "--name-only", "--no-renames", "-m", before + "..HEAD");
    eq(SyncPanel.countPulled(log), new SyncPanel.Pulled(3, 2));
    eq(SyncPanel.pulledPaths(log), Set.of("m.txt", "s.txt"));
    eq(SyncPanel.pulledPaths(log).containsAll(java.util.Arrays.asList(SyncPanel.git(repo, "diff", "--name-only", before).trim().split("\n"))), true);
  }

  private static void sh(File dir, String command) throws Exception {
    SyncPanel.run(dir, "sh", "-c", command);
  }

  /** .gitignore 에 더하기: 이미 있는 줄은 건너뛰고, 줄 끝은 파일이 쓰던 CRLF · LF 를 따른다. */
  private static void checkGitignore() throws IOException {
    File crlf = java.nio.file.Files.createTempDirectory("ignore-crlf").toFile();
    java.nio.file.Files.writeString(new File(crlf, ".gitignore").toPath(), "/target/\r\n/a[1].txt");
    SyncPanel.addToGitignore(crlf, List.of("/a[1].txt", "/b.txt"));
    eq(java.nio.file.Files.readString(new File(crlf, ".gitignore").toPath()), "/target/\r\n/a[1].txt\r\n/a\\[1\\].txt\r\n/b.txt\r\n");
    File lf = java.nio.file.Files.createTempDirectory("ignore-lf").toFile();
    SyncPanel.addToGitignore(lf, List.of("/b.txt", "/b.txt"));
    eq(java.nio.file.Files.readString(new File(lf, ".gitignore").toPath()), "/b.txt\n");
  }

  /** 띄울 git: PATH 의 설치본(실제 exe 우선) → 없으면 내장 MinGit → 그것도 없으면 「git」. */
  private static void checkFindWindowsGit() throws Exception {
    File base = java.nio.file.Files.createTempDirectory("find-git").toFile();
    File bundled = new File(base, "ide/git.exe");
    File cmd = new File(base, "Git/cmd");
    File real = new File(base, "Git/mingw64/bin/git.exe");
    File wrapperOnly = new File(base, "Only");
    eq(SyncPanel.findWindowsGit(null, bundled), "git");
    bundled.getParentFile().mkdirs();
    bundled.createNewFile();
    eq(SyncPanel.findWindowsGit(null, bundled), bundled.getPath());
    eq(SyncPanel.findWindowsGit(new File(base, "empty").getPath(), bundled), bundled.getPath());
    cmd.mkdirs();
    new File(cmd, "git.exe").createNewFile();
    eq(SyncPanel.findWindowsGit(cmd.getPath(), bundled), "git");
    real.getParentFile().mkdirs();
    real.createNewFile();
    eq(SyncPanel.findWindowsGit(cmd.getPath(), bundled), real.getPath());
    wrapperOnly.mkdirs();
    new File(wrapperOnly, "git.exe").createNewFile();
    eq(SyncPanel.findWindowsGit(wrapperOnly.getPath() + File.pathSeparator + cmd.getPath(), bundled), "git");
    // 새 판(UCRT 빌드) Git 은 실제 exe 가 ucrt64\bin 에 있다.
    File ucrtCmd = new File(base, "GitUcrt/cmd");
    File ucrtReal = new File(base, "GitUcrt/ucrt64/bin/git.exe");
    ucrtCmd.mkdirs();
    new File(ucrtCmd, "git.exe").createNewFile();
    ucrtReal.getParentFile().mkdirs();
    ucrtReal.createNewFile();
    eq(SyncPanel.findWindowsGit(ucrtCmd.getPath(), bundled), ucrtReal.getPath());
    // 설정에서 고른 경로: 껍데기면 옆의 진짜 exe, 진짜 exe 자체(ucrt64\bin)면 옆에 더 없으므로 null(고른 경로 그대로 씀).
    eq(SyncPanel.realGitNextTo(ucrtCmd), ucrtReal.getPath());
    eq(SyncPanel.realGitNextTo(ucrtReal.getParentFile()), null);
    eq(SyncPanel.realGitNextTo(null), null);
  }

  /** Git 로그인: 서버 주소 다듬기, Basic 머리글, GIT_CONFIG_* 환경값, 저장 형식. */
  private static void checkGitLogins() {
    java.util.Map<String, String> servers = GitRemoteAuth.serverUrls(List.of("https://u@A.com/x.git", "git@b.com:x.git", "https://a.com/y.git", "https://c.com:8443/z"));
    eq(List.copyOf(servers.keySet()), List.of("https://a.com", "https://c.com:8443"));
    eq(servers.get("https://a.com"), "https://u@A.com/x.git");
    eq(GitRemoteAuth.failedServers(servers.keySet(), "fatal: Authentication failed for 'https://C.com:8443/z/'"), List.of("https://c.com:8443"));
    eq(GitRemoteAuth.failedServers(servers.keySet(), "fatal: could not read Username"), List.of("https://a.com", "https://c.com:8443"));
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
    eq(env, java.util.Map.of("GIT_CONFIG_PARAMETERS", "'http.https://a.com/.extraHeader=" + GitLogins.header(pw) + "' 'http.https://b.com:8443/.extraHeader="
                                                      + GitLogins.header(token) + "'"));
    // IDE 에 이미 GIT_CONFIG_PARAMETERS 가 있으면 그 뒤에 잇는다.
    eq(GitLogins.env(List.of(pw), java.util.Map.of("GIT_CONFIG_PARAMETERS", "'a.b=1' ")).get("GIT_CONFIG_PARAMETERS"),
       "'a.b=1' 'http.https://a.com/.extraHeader=" + GitLogins.header(pw) + "'");
    eq(GitLogins.env(List.of(empty), java.util.Map.of()), java.util.Map.of());
    eq(GitLogins.quoted("it's"), "'it'\\''s'");

    List<GitLogins.Login> parsed = GitLogins.parse(GitLogins.format(List.of(pw, token)) + "\nbroken\nhttps://d.com\tWHAT\tx");
    eq(parsed.size(), 2);
    eq(parsed.get(0), new GitLogins.Login("https://a.com", GitLogins.Kind.PASSWORD, "kim", null));
    eq(parsed.get(1), new GitLogins.Login("https://b.com:8443", GitLogins.Kind.TOKEN, "", null));
    eq(GitLogins.parse(""), List.of());
  }
}
