package kinggura.sync;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 자체 점검: JGitRead 가 맡은 명령이 진짜 git 과 같은 표준 출력 · 종료 코드를 내는가. 같은 저장소에 두 가지로 돌려 바이트를 견준다.
 * fetch 는 같은 상태의 저장소 둘에 하나는 JGit, 하나는 git 으로 받아 참조 목록을 견준다. java -cp … kinggura.sync.JGitCheck
 */
public class JGitCheck {
  // SyncPanel.gitArgs 가 붙이는 앞 인자.
  private static final List<String> PREFIX = List.of("-c", "core.quotepath=false", "-c", "i18n.logOutputEncoding=UTF-8");
  private static int handled;

  public static void main(String[] args) throws Exception {
    File tmp = Files.createTempDirectory("jgit").toFile();
    File origin = new File(tmp, "origin.git");
    File seed = new File(tmp, "seed");
    File mine = new File(tmp, "mine");
    sh(tmp, "git init -q --bare -b develop origin.git && git init -q -b develop seed");
    sh(seed, "git config user.name t && git config user.email t@t && mkdir -p app/sub && printf 'a\\n' > app/A.java && printf '#!/bin/sh\\n' > run.sh && chmod +x run.sh"
             + " && ln -s app/A.java link && printf '한글\\n' > '한글.txt' && git add -A && git commit -qm one && git tag v1 && git tag -a -m t v1a"
             + " && git checkout -q -b feature/kim && printf 'b\\n' > app/sub/B.java && git add -A && git commit -qm two"
             + " && git checkout -q develop && printf 'c\\n' > C.java && git add -A && git commit -qm three"
             + " && git merge -q --no-edit feature/kim && git branch Feature/up develop && git branch gone develop"
             + " && git remote add origin ../origin.git && git push -q origin develop feature/kim Feature/up gone --tags");
    sh(tmp, "git clone -q origin.git mine");
    sh(mine, "git config user.name t && git config user.email t@t && git checkout -q -b mywork origin/feature/kim && printf 'd\\n' > D.java && git add -A && git commit -qm four"
             + " && git checkout -q develop");

    // 로컬 읽기: 같은 인자로 git 과 견준다.
    for (String[] c : List.of(
      a("rev-parse", "HEAD"), a("rev-parse", "--show-toplevel"), a("rev-parse", "--absolute-git-dir"),
      a("rev-parse", "--show-toplevel", "--abbrev-ref", "HEAD", "@{u}"), a("rev-parse", "--abbrev-ref", "HEAD"),
      a("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"), a("rev-parse", "refs/remotes/origin/feature/kim"),
      a("rev-parse", "--verify", "--quiet", "refs/heads/develop"), a("rev-parse", "--verify", "--quiet", "refs/heads/none"),
      a("rev-parse", "v1a"), a("rev-parse", "HEAD^"), a("rev-parse", "mywork"),
      a("rev-list", "--count", "@{u}..HEAD"), a("rev-list", "--count", "HEAD..refs/remotes/origin/feature/kim"),
      a("rev-list", "--left-right", "--count", "HEAD...@{u}"), a("rev-list", "--left-right", "--count", "--no-merges", "HEAD...refs/heads/mywork"),
      a("rev-list", "--count", "refs/heads/mywork..HEAD"), a("rev-list", "--count", "HEAD.."),
      a("merge-base", "HEAD", "refs/heads/mywork"), a("merge-base", "--is-ancestor", "refs/remotes/origin/feature/kim", "HEAD"),
      a("merge-base", "--is-ancestor", "refs/heads/mywork", "HEAD"),
      a("for-each-ref", "--format=%(refname)", "refs/remotes/origin/"), a("for-each-ref", "--format=%(refname)", "refs/tags/"),
      a("symbolic-ref", "--short", "-q", "HEAD"), a("symbolic-ref", "--short", "HEAD"),
      a("ls-tree", "-r", "--name-only", "HEAD"), a("ls-tree", "-r", "--name-only", "refs/heads/mywork"),
      a("show", "HEAD:app/A.java"), a("show", "HEAD:run.sh"), a("show", "HEAD:link"), a("show", "HEAD:한글.txt"),
      a("show", "refs/heads/mywork:D.java"), a("show", "origin/feature/kim:app/sub/B.java"), a("show", "HEAD^:./C.java")
    )) same(mine, c, true);
    String blob = git(mine, "rev-parse", "HEAD:app/A.java").trim();
    same(mine, a("cat-file", "blob", blob), true);
    same(new File(mine, "app"), a("rev-parse", "--show-toplevel"), true);
    same(new File(mine, "app"), a("show", "HEAD:./A.java"), true);
    // 떨어진 HEAD 에서 --abbrev-ref HEAD 는 HEAD.
    sh(mine, "git checkout -q --detach HEAD");
    same(mine, a("rev-parse", "--abbrev-ref", "HEAD"), true);
    same(mine, a("symbolic-ref", "--short", "-q", "HEAD"), true);
    sh(mine, "git checkout -q develop");
    // 맡지 않고 git 에 넘기는 모양(null).
    for (String[] c : List.of(a("rev-parse", "HEAD~1"), a("rev-parse", "abc1234"), a("status"), a("log", "-1"), a("config", "--get", "user.name"),
                              a("show", "HEAD:app"), a("show", "HEAD:none.txt"), a("ls-tree", "HEAD"), a("rev-parse", "--verify", "--quiet", "nothere"))) {
      eq(JGitRead.run(mine, 30, List.of(), full(c)), null);
    }
    eq(JGitRead.run(new File(mine, "app"), 30, List.of(), full(a("ls-tree", "-r", "--name-only", "HEAD"))), null);

    // 서버: ls-remote 는 바이트, fetch 는 받은 뒤 참조 목록을 git 과 견준다.
    sh(seed, "git checkout -q develop && printf 'e\\n' > E.java && git add -A && git commit -qm five && git push -q origin develop && git push -q origin --delete gone"
             + " && git branch -f Feature/up HEAD~1 && git push -q -f origin Feature/up && git push -q origin develop:refs/heads/feature/new");
    same(mine, a("ls-remote", "--heads", "origin"), true);
    sh(tmp, "cp -r mine viaGit");
    File viaGit = new File(tmp, "viaGit");
    fetchSame(mine, viaGit, "fetch", "--quiet", "--prune");
    eq(git(mine, "for-each-ref", "--format=%(refname)", "refs/remotes/origin/gone"), "");
    // 명시한 refspec 만 받고(서버에서 지운 브랜치면 실패 → git 에 맡김).
    fetchSame(mine, viaGit, "fetch", "--quiet", "origin", "+refs/heads/feature/new:refs/remotes/origin/feature/new");
    eq(JGitRead.run(mine, 30, List.of(), full(a("fetch", "--quiet", "origin", "+refs/heads/gone:refs/remotes/origin/gone"))), null);
    // 제외 refspec 에 드는 참조는 prune 하지 않는다(git 2.29). JGit 은 스스로는 이를 지키지 않는다.
    sh(mine, "git update-ref refs/remotes/origin/Old/x HEAD && git config --add remote.origin.fetch '^refs/heads/Old/*'");
    sh(viaGit, "git update-ref refs/remotes/origin/Old/x HEAD && git config --add remote.origin.fetch '^refs/heads/Old/*'");
    fetchSame(mine, viaGit, "fetch", "--quiet", "--prune");
    eq(git(mine, "for-each-ref", "--format=%(refname)", "refs/remotes/origin/Old/"), "refs/remotes/origin/Old/x\n");
    // core.ignorecase 저장소: 서버에 대소문자만 다른 원본이 있으면(윈도우에선 같은 파일) 지우지 않는다. git 은 지운다(이 저장소가 막으려는 일).
    sh(mine, "git config core.ignorecase true && git update-ref refs/remotes/origin/feature/UP HEAD");
    eq(JGitRead.run(mine, 30, List.of(), full(a("fetch", "--quiet", "--prune"))).length, 0);
    eq(git(mine, "for-each-ref", "--format=%(refname)", "refs/remotes/origin/feature/UP"), "refs/remotes/origin/feature/UP\n");
    sh(mine, "git config core.ignorecase false");
    eq(JGitRead.run(mine, 30, List.of(), full(a("fetch", "--quiet", "--prune"))).length, 0);
    eq(git(mine, "for-each-ref", "--format=%(refname)", "refs/remotes/origin/feature/UP"), "");

    checkHttp(tmp);
    eq(handled > 40, true);
    System.out.println("JGitCheck 통과 (" + handled + "개 견줌)");
  }

  /** https 서버: Git 로그인이 없으면 git 에 맡기고, 있으면 그 머리글을 보낸다. */
  private static void checkHttp(File tmp) throws Exception {
    AtomicReference<String> sent = new AtomicReference<>();
    HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    http.createContext("/", exchange -> {
      sent.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"x\"");
      exchange.sendResponseHeaders(401, -1);
      exchange.close();
    });
    http.start();
    try {
      String server = "http://127.0.0.1:" + http.getAddress().getPort();
      File repo = new File(tmp, "httpRepo");
      sh(tmp, "git init -q httpRepo");
      sh(repo, "git remote add origin " + server + "/x.git");
      eq(JGitRead.run(repo, 30, List.of(), full(a("fetch", "--quiet"))), null);
      eq(JGitRead.run(repo, 30, null, full(a("ls-remote", "--heads", "origin"))), null);
      // 머리글을 보내고, 거절되면 git 에 맡긴다(git 은 비밀번호 도우미에 더 묻는다).
      GitLogins.Login login = new GitLogins.Login(server, GitLogins.Kind.PASSWORD, "kim", "wrong");
      for (String[] c : List.of(a("fetch", "--quiet"), a("ls-remote", "--heads", "origin"))) {
        sent.set(null);
        eq(JGitRead.run(repo, 30, List.of(login), full(c)), null);
        eq("Authorization: " + sent.get(), GitLogins.header(login));
      }
      // 다른 서버로 넘겨지면 그 서버에는 머리글을 보내지 않는다.
      AtomicReference<String> leaked = new AtomicReference<>("없음");
      HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      other.createContext("/", exchange -> {
        leaked.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
      });
      other.start();
      http.createContext("/moved/", exchange -> {
        sent.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + other.getAddress().getPort() + exchange.getRequestURI().getPath().substring("/moved".length()) + "?" + exchange.getRequestURI().getQuery());
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
      });
      try {
        sh(repo, "git remote set-url origin " + server + "/moved/x.git");
        sent.set(null);
        eq(JGitRead.run(repo, 30, List.of(login), full(a("ls-remote", "--heads", "origin"))), null);
        eq("Authorization: " + sent.get(), GitLogins.header(login));
        eq(leaked.get(), "null");
      }
      finally {
        other.stop(0);
      }
      // 상대 경로 원격은 git 에 맡긴다(어디 기준인지 git 과 다를 수 있다).
      sh(repo, "git remote set-url origin ../origin.git");
      eq(JGitRead.run(repo, 30, List.of(), full(a("ls-remote", "--heads", "origin"))), null);
    }
    finally {
      http.stop(0);
    }
  }

  /** JGit 으로 맡고(null 아님) 출력 · 종료 코드 1 이 git 과 같아야 한다. mustHandle 이 거짓이면 맡지 않아도 된다. */
  private static void same(File dir, String[] command, boolean mustHandle) throws Exception {
    Result real = real(dir, full(command));
    byte[] out;
    try {
      out = JGitRead.run(dir, 30, List.of(), full(command));
    }
    catch (IOException e) {
      eq(real.exit + " " + real.err, "1 ");
      eq(e.getMessage(), "git " + command[0] + " 실패: 종료 코드 1");
      handled++;
      return;
    }
    if (out == null) {
      if (mustHandle) throw new AssertionError("JGit 이 맡지 않음: " + String.join(" ", command));
      return;
    }
    eq(real.exit, 0);
    eq(new String(out, StandardCharsets.UTF_8), new String(real.out, StandardCharsets.UTF_8));
    eq(Arrays.equals(out, real.out), true);
    handled++;
  }

  /** viaJGit 은 JGitRead, viaGit 은 git 으로 같은 fetch 를 하고 모든 참조가 같아야 한다. */
  private static void fetchSame(File viaJGit, File viaGit, String... command) throws Exception {
    byte[] out = JGitRead.run(viaJGit, 30, List.of(), full(command));
    if (out == null) throw new AssertionError("JGit 이 맡지 않음: " + String.join(" ", command));
    eq(out.length, 0);
    Result real = real(viaGit, full(command));
    eq(real.exit + " " + new String(real.out, StandardCharsets.UTF_8), "0 ");
    eq(git(viaJGit, "for-each-ref", "--format=%(objectname) %(refname)"), git(viaGit, "for-each-ref", "--format=%(objectname) %(refname)"));
    handled++;
  }

  private record Result(int exit, byte[] out, String err) {}

  private static Result real(File dir, String[] args) throws Exception {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command).directory(dir);
    builder.environment().put("LANGUAGE", "C");
    File err = File.createTempFile("jgitcheck", ".err");
    builder.redirectError(err);
    Process p = builder.start();
    p.getOutputStream().close();
    byte[] out = p.getInputStream().readAllBytes();
    int exit = p.waitFor();
    String text = Files.readString(err.toPath()).trim();
    err.delete();
    return new Result(exit, out, text);
  }

  private static String git(File dir, String... args) throws Exception {
    Result r = real(dir, args);
    if (r.exit != 0) throw new AssertionError("git " + String.join(" ", args) + ": " + r.err);
    return new String(r.out, StandardCharsets.UTF_8);
  }

  private static void sh(File dir, String script) throws Exception {
    Process p = new ProcessBuilder("sh", "-c", script).directory(dir).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (p.waitFor() != 0) throw new AssertionError(script + "\n" + out);
  }

  private static String[] a(String... args) {
    return args;
  }

  private static String[] full(String[] command) {
    List<String> all = new ArrayList<>(PREFIX);
    all.addAll(List.of(command));
    return all.toArray(String[]::new);
  }

  static void eq(Object a, Object b) {
    if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b);
  }
}
