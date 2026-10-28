package kinggura.sync;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static kinggura.sync.SyncPanel.GIT_LONG_TIMEOUT_SEC;
import static kinggura.sync.SyncPanel.git;

/**
 * 역머지: 서버의 기준 브랜치(develop · main 등)를 내 개인 브랜치에 합치고 개인 브랜치로 푸시한다.
 * GitLab 사이트에서 하던 「develop → 개인 브랜치」 머지를 IDE 에서 한다.
 *
 * 내 저장소가 개인 브랜치만 받도록(fetch refspec) 설정돼 있어도 되게, 브랜치 목록은 서버에 직접 묻고(ls-remote)
 * 필요한 브랜치만 이름으로 받아 refs/remotes/<원격>/<이름> 에 둔다. 로컬 브랜치 · fetch 설정은 건드리지 않는다.
 *
 * 「내 파일」은 둘을 합친 것이다.
 *  1) 개인 브랜치가 기준 브랜치와 마지막으로 맞춰진 시점(merge-base) 뒤 개인 브랜치에서 바뀐 파일, 지금 브랜치면 커밋 안 한 변경까지.
 *  2) 내 Git 이메일(user.email)로 MINE_DAYS 일 안에 커밋한 파일(두 브랜치 어디에 있든). 이미 develop 에 들어간 내 작업을 남이 고친 경우를 잡는다.
 * 들어올 파일 가운데 내 파일과 겹치고, 들어올 커밋 중 남(이름 · 이메일이 둘 다 내 user.name · user.email 과 다름)의 커밋이 고친 것은
 * 충돌이 나든 조용히 합쳐지든 내 작업에 영향이 있어 미리 보기에서 먼저 보여 준다. 들어올 커밋이 모두 내 것이면 경고하지 않는다.
 *
 * 명령은 모두 작업 스레드에서 부른다. IntelliJ 클래스는 쓰지 않는다(점검: SyncCheck).
 */
final class ReverseMerge {
  static final String CONFIG_FROM = "ei.reverseMerge.from";
  static final String CONFIG_INTO = "ei.reverseMerge.into";
  /** 브랜치에 원격 설정(branch.<이름>.remote)이 없을 때 쓰는 원격. git clone 의 기본 이름이다. */
  static final String DEFAULT_REMOTE = "origin";
  /** 기준 브랜치를 처음 고를 때 이 순서로 서버 목록에서 찾는다. */
  static final List<String> FROM_CANDIDATES = List.of("develop", "dev", "development", "main", "master");
  /** 내 이름으로 커밋한 파일을 「내 파일」로 보는 기간(일). */
  static final int MINE_DAYS = 30;
  private static final String HEADS = "refs/heads/";
  private static final char LOG_START = '\u0001';
  private static final char LOG_SEP = '\u001f';
  private static final String LOG_FORMAT = "--format=%x01%an%x1f%ad%x1f%s";
  private static final String LOG_DATE = "--date=format:%m-%d %H:%M";
  private static final String AUTHOR_FORMAT = "--format=%x01%an%x1f%ae";
  /** configs 가 한 번에 읽는 설정 키(git config --get-regexp). */
  private static final String CONFIG_KEYS = "^(user\\.(name|email)|branch\\..*\\.remote|ei\\.reverseMerge\\..*)$";

  /** 들어올 파일 하나. author · when · subject 는 기준 브랜치에서 이 파일을 마지막으로 바꾼 커밋. */
  record Incoming(String path, String author, String when, String subject, boolean mine) {}

  /** 미리 보기: 들어올 커밋 수와 파일(내 파일이 앞). */
  record Preview(int commits, List<Incoming> files) {
    int mineCount() {
      int n = 0;
      for (Incoming f : files) if (f.mine()) n++;
      return n;
    }
  }

  enum Result { MERGED, CONFLICT }

  /** 커밋 안 한 변경이 있어 합치지 않았다. stash 를 참으로 다시 부르면 그 변경을 Stash 해 두고 합친다. */
  static final class DirtyException extends IOException {
    DirtyException() { super(SyncBundle.message("rmerge.dirty")); }
  }

  /** 합치기 전에 커밋 안 한 변경을 넣어 두는 Stash 의 이름. 되살릴 때 맨 위 Stash 가 이 이름일 때만 꺼낸다(남의 Stash 를 꺼내지 않게). */
  static final String STASH_MESSAGE = "EI Merge Branches";

  private ReverseMerge() {}

  /** 원격 추적 ref. 받아 온 브랜치는 여기 있다. */
  static String remoteRef(String remote, String branch) {
    return "refs/remotes/" + remote + "/" + branch;
  }

  /** ls-remote --heads 출력에서 브랜치 이름만. 서버 순서(이름순) 그대로. */
  static List<String> parseHeads(String output) {
    List<String> names = new ArrayList<>();
    for (String line : output.split("\n")) {
      int at = line.indexOf(HEADS);
      if (at >= 0) names.add(line.substring(at + HEADS.length()).trim());
    }
    return names;
  }

  /** 목록에서 고를 첫 값: 저장해 둔 것, 없으면 candidates 순서로 처음 있는 것, 그것도 없으면 null. */
  static String pick(List<String> names, String saved, List<String> candidates) {
    if (saved != null && names.contains(saved)) return saved;
    for (String c : candidates) if (names.contains(c)) return c;
    return null;
  }

  /** git log LOG_FORMAT --name-only 출력 → 경로별 가장 최근 커밋(작성자 · 때 · 제목). log 는 새것부터 나오므로 처음 본 것이 최근이다. */
  static Map<String, String[]> parseLog(String output) {
    Map<String, String[]> latest = new HashMap<>();
    String[] commit = null;
    for (String line : output.split("\n")) {
      if (line.isEmpty()) continue;
      if (line.charAt(0) == LOG_START) {
        String[] parts = line.substring(1).split(String.valueOf(LOG_SEP), -1);
        commit = parts.length == 3 ? parts : null;
      }
      else if (commit != null) {
        latest.putIfAbsent(line.trim(), commit);
      }
    }
    return latest;
  }

  /**
   * AUTHOR_FORMAT · --name-only 로그 → 남의 커밋이 고친 경로. 이름이나 이메일 가운데 하나라도 내 것과 같으면 내 커밋이다.
   * 내 이름 · 이메일을 모르면(null) 모든 커밋을 남의 것으로 본다.
   */
  static Set<String> touchedByOthers(String output, String myName, String myEmail) {
    Set<String> paths = new HashSet<>();
    boolean others = false;
    for (String line : output.split("\n")) {
      if (line.isEmpty()) continue;
      if (line.charAt(0) == LOG_START) {
        String[] parts = line.substring(1).split(String.valueOf(LOG_SEP), -1);
        boolean byMe = parts.length == 2 && (parts[0].equals(myName) || (myEmail != null && parts[1].equalsIgnoreCase(myEmail)));
        others = !byMe;
      }
      else if (others) {
        paths.add(line.trim());
      }
    }
    return paths;
  }

  /** 줄마다 경로 하나인 출력 → 경로 집합. */
  static Set<String> lines(String output) {
    Set<String> set = new LinkedHashSet<>();
    for (String line : output.split("\n")) if (!line.isBlank()) set.add(line.trim());
    return set;
  }

  /** 들어올 경로 · 내 경로 · 커밋 정보로 미리 보기 줄을 만든다. 내 파일이 앞, 그 안에서는 경로순. */
  static List<Incoming> plan(Set<String> theirs, Set<String> mine, Map<String, String[]> log) {
    List<Incoming> rows = new ArrayList<>();
    for (String path : theirs) {
      String[] c = log.getOrDefault(path, new String[]{"", "", ""});
      rows.add(new Incoming(path, c[0], c[1], c[2], mine.contains(path)));
    }
    rows.sort(Comparator.comparing((Incoming r) -> !r.mine()).thenComparing(Incoming::path));
    return rows;
  }

  /** 값이 없으면(종료 코드 1) null. */
  static String config(File root, String key) throws InterruptedException {
    try {
      String value = git(root, "config", "--get", key).trim();
      return value.isEmpty() ? null : value;
    }
    catch (IOException missing) {
      return null;
    }
  }

  /**
   * 역머지가 쓰는 설정(user.* · branch.*.remote · ei.reverseMerge.*)을 git 한 번으로 다 읽는다. 하나씩 읽으면 git 을 넷 띄운다(회사 PC 는 하나에 0.8초).
   * 맞는 키가 없으면(종료 코드 1) 빈 맵.
   */
  static Map<String, String> configs(File root) throws InterruptedException {
    try {
      return parseConfigs(git(root, "config", "--get-regexp", CONFIG_KEYS));
    }
    catch (IOException missing) {
      return Map.of();
    }
  }

  /** git config --get-regexp 출력(「키 값」 한 줄씩) → 맵. 값이 비어 있는 키는 뺀다. */
  static Map<String, String> parseConfigs(String output) {
    Map<String, String> result = new HashMap<>();
    for (String line : output.split("\n")) {
      int space = line.indexOf(' ');
      if (space > 0 && space < line.trim().length()) result.put(line.substring(0, space), line.substring(space + 1).trim());
    }
    return result;
  }

  static void saveConfig(File root, String key, String value) throws IOException, InterruptedException {
    git(root, "config", key, value);
  }

  /** branch 가 쓰는 원격 이름. 설정이 없으면 DEFAULT_REMOTE. */
  static String remoteOf(File root, String branch) throws InterruptedException {
    return remoteOf(configs(root), branch);
  }

  /** remoteOf 와 같고 configs 로 읽어 둔 설정을 쓴다. */
  static String remoteOf(Map<String, String> cfg, String branch) {
    String remote = branch == null ? null : cfg.get("branch." + branch + ".remote");
    return remote == null ? DEFAULT_REMOTE : remote;
  }

  /**
   * git 명령 여럿을 동시에 돌려 입력 순서대로 결과를 돌려준다. 서로 기다릴 필요 없는 명령을 차례로 띄우면 명령 수만큼 git 시작 시간이 쌓인다.
   * 하나가 실패하면 그 예외를 던진다. IntelliJ 클래스 없이 돌아 SyncCheck 에서도 쓴다.
   */
  static List<String> gitAll(File root, List<String[]> commands) throws IOException, InterruptedException {
    String[] out = new String[commands.size()];
    Exception[] failed = new Exception[commands.size()];
    List<Thread> threads = new ArrayList<>();
    for (int k = 0; k < commands.size(); k++) {
      int at = k;
      Thread t = new Thread(() -> {
        try {
          out[at] = git(root, commands.get(at));
        }
        catch (IOException | InterruptedException e) {
          failed[at] = e;
        }
      }, "ei-git");
      t.start();
      threads.add(t);
    }
    for (Thread t : threads) t.join();
    for (Exception e : failed) {
      if (e instanceof IOException io) throw io;
      if (e instanceof InterruptedException ie) throw ie;
    }
    return Arrays.asList(out);
  }

  /** 서버에 있는 브랜치 이름들. 내 fetch 설정과 상관없이 서버에 직접 묻는다. */
  static List<String> serverBranches(File root, String remote) throws IOException, InterruptedException {
    return parseHeads(git(root, GIT_LONG_TIMEOUT_SEC, "ls-remote", "--heads", remote));
  }

  /** 지금까지 받아 둔 원격 브랜치 이름들(refs/remotes/<원격>/*). 서버에 가지 않아 바로 나온다. 이름순. */
  static List<String> knownBranches(File root, String remote) throws IOException, InterruptedException {
    return parseRefs(git(root, "for-each-ref", "--format=%(refname)", remoteRef(remote, "")), remote);
  }

  /** for-each-ref 가 낸 refs/remotes/<원격>/<이름> 줄에서 이름만. <원격>/HEAD 는 브랜치가 아니라 뺀다. */
  static List<String> parseRefs(String output, String remote) {
    String prefix = remoteRef(remote, "");
    List<String> names = new ArrayList<>();
    for (String line : output.split("\n")) {
      String ref = line.trim();
      if (!ref.startsWith(prefix)) continue;
      String name = ref.substring(prefix.length());
      if (!name.isEmpty() && !name.equals("HEAD")) names.add(name);
    }
    return names;
  }

  /** 브랜치들을 이름으로 받아 remoteRef 에 둔다. 서버에서 브랜치가 지워졌으면 IOException. */
  static void fetch(File root, String remote, String... branches) throws IOException, InterruptedException {
    List<String> args = new ArrayList<>(List.of("fetch", "--quiet", remote));
    for (String b : new LinkedHashSet<>(List.of(branches))) args.add("+" + HEADS + b + ":" + remoteRef(remote, b));
    git(root, GIT_LONG_TIMEOUT_SEC, args.toArray(String[]::new));
  }

  /** 개인 브랜치의 기준점: into 가 지금 브랜치면 HEAD(커밋 안 한 것 포함), 아니면 받아 온 서버 개인 브랜치. */
  static String intoTip(String remote, String into, String current) {
    return into.equals(current) ? "HEAD" : remoteRef(remote, into);
  }

  /** from 을 into 에 합치면 들어올 것. 두 브랜치를 미리 fetch 해 두어야 한다. */
  static Preview preview(File root, String remote, String from, String into, String current) throws IOException, InterruptedException {
    return preview(root, configs(root), remote, from, into, current);
  }

  /** preview 와 같고 configs 로 읽어 둔 설정을 쓴다. 명령은 두 묶음으로 동시에 돌린다(차례로 하면 여덟 번 git 시작 시간이 쌓인다). */
  static Preview preview(File root, Map<String, String> cfg, String remote, String from, String into, String current)
    throws IOException, InterruptedException {
    String f = remoteRef(remote, from);
    String i = intoTip(remote, into, current);
    String email = cfg.get("user.email");
    List<String[]> first = new ArrayList<>(List.of(
      new String[]{"rev-list", "--count", i + ".." + f},
      new String[]{"diff", "--name-only", i + "..." + f},
      new String[]{"diff", "--name-only", f + "..." + i}));
    if (into.equals(current)) first.add(new String[]{"diff", "--name-only", "HEAD"});
    if (email != null) first.add(new String[]{"log", "--author=" + email, "--since=" + MINE_DAYS + ".days", "--format=", "--name-only", f, i});
    List<String> out = gitAll(root, first);
    int commits = Integer.parseInt(out.get(0).trim());
    Set<String> theirs = lines(out.get(1));
    Set<String> mine = lines(out.get(2));
    for (int k = 3; k < out.size(); k++) mine.addAll(lines(out.get(k)));
    if (theirs.isEmpty()) return new Preview(commits, plan(theirs, mine, Map.of()));
    // 들어올 파일이 있을 때만: 누가 고쳤나(남의 커밋 가리기)와 파일별 마지막 커밋.
    List<String> logs = gitAll(root, List.of(new String[]{"log", AUTHOR_FORMAT, "--name-only", i + ".." + f},
                                             new String[]{"log", LOG_FORMAT, LOG_DATE, "--name-only", i + ".." + f}));
    if (!mine.isEmpty()) mine.retainAll(touchedByOthers(logs.get(0), cfg.get("user.name"), email));
    return new Preview(commits, plan(theirs, mine, parseLog(logs.get(1))));
  }

  /**
   * into 로 옮겨(지금 브랜치가 아니면) 서버 개인 브랜치를 먼저 따라잡고(fast-forward 만), from 을 합친다. 충돌이면 CONFLICT(into 에서 합치기 상태로 둔다).
   * push 면 합친 뒤 서버 개인 브랜치로 올린다. 서버 개인 브랜치와 내 것이 갈라져 있으면 합치기 전에 멈춘다.
   * 커밋 안 한 변경(새 파일 빼고)이 있으면 stash 가 거짓이면 아무것도 하지 않고 DirtyException 으로 멈춘다. 그 상태로 합치다 충돌이 나 합치기를 취소하면 git 이 그 변경을 되살리지 못할 수 있다.
   * stash 가 참이면 그 변경을 Stash(STASH_MESSAGE) 해 두고 합친 뒤 원래 브랜치에서 되살린다. 실패해도 되살린다. 충돌이면 Stash 를 두고, finish 가 마무리한 뒤 되살린다.
   * into 로 옮겼으면 끝난 뒤(실패 포함, 충돌 빼고) 원래 브랜치로 돌아온다.
   */
  static Result merge(File root, String remote, String from, String into, String current, boolean push, boolean stash) throws IOException, InterruptedException {
    boolean stashed = false;
    if (!lines(git(root, "status", "--porcelain", "-uno")).isEmpty()) {
      if (!stash) throw new DirtyException();
      git(root, GIT_LONG_TIMEOUT_SEC, "stash", "push", "-m", STASH_MESSAGE);
      stashed = true;
    }
    String intoRemote = remoteRef(remote, into);
    boolean switched = !into.equals(current);
    if (switched) {
      if (hasRef(root, HEADS + into)) git(root, GIT_LONG_TIMEOUT_SEC, "checkout", into);
      else {
        // --track 은 fetch 설정이 그 브랜치를 받지 않으면(개인 브랜치만 받는 clone) 실패해서, 추적 설정을 직접 적는다.
        git(root, GIT_LONG_TIMEOUT_SEC, "checkout", "-b", into, intoRemote);
        saveConfig(root, "branch." + into + ".remote", remote);
        saveConfig(root, "branch." + into + ".merge", HEADS + into);
      }
    }
    try {
      if (Integer.parseInt(git(root, "rev-list", "--count", "HEAD.." + intoRemote).trim()) > 0) {
        try {
          git(root, GIT_LONG_TIMEOUT_SEC, "merge", "--ff-only", intoRemote);
        }
        catch (IOException diverged) {
          throw new IOException(SyncBundle.message("rmerge.diverged", into));
        }
      }
      try {
        git(root, GIT_LONG_TIMEOUT_SEC, "merge", "--no-edit", remoteRef(remote, from));
      }
      catch (IOException e) {
        if (!conflicts(root).isEmpty()) return Result.CONFLICT;
        throw e;
      }
      push(root, remote, into, push);
    }
    catch (IOException e) {
      if (switched) checkoutBack(root, current, e);
      if (stashed) unstashAfterFailure(root, e);
      throw e;
    }
    if (switched) git(root, GIT_LONG_TIMEOUT_SEC, "checkout", current);
    if (stashed) unstash(root);
    return Result.MERGED;
  }

  /** 맨 위 Stash 가 합치기 전에 넣어 둔 것(STASH_MESSAGE)이면 꺼내 되살린다. 꺼내지 못하면 Stash 를 그대로 두고 그 안내를 담은 IOException. */
  static void unstash(File root) throws IOException, InterruptedException {
    if (!git(root, "stash", "list", "-1", "--format=%s").contains(STASH_MESSAGE)) return;
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "stash", "pop");
    }
    catch (IOException e) {
      throw new IOException(e.getMessage() + "\n" + SyncBundle.message("rmerge.unstash.failed"), e);
    }
  }

  /** 합치기가 실패한 뒤 Stash 를 되살린다. 되살리지 못해도 cause 를 그대로 알리도록 그 실패는 cause 에 덧붙인다. */
  private static void unstashAfterFailure(File root, IOException cause) throws InterruptedException {
    try {
      unstash(root);
    }
    catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

  /** 아직 해결하지 않은 충돌 파일(저장소 기준 경로). */
  static Set<String> conflicts(File root) throws IOException, InterruptedException {
    return lines(git(root, "diff", "--name-only", "--diff-filter=U"));
  }

  /**
   * 충돌을 해결한 뒤 합치기를 마무리한다: 합치기 커밋, push 면 서버 개인 브랜치로 올리기, into 로 옮겼으면 원래 브랜치로 돌아오기.
   * 충돌이 남았거나 합치기를 취소해(MERGE_HEAD 없음) 마무리할 것이 없으면 false 를 돌려주고 아무것도 하지 않는다.
   * 마무리했으면 합치기 전에 넣어 둔 Stash 가 있을 때 되살린다(unstash).
   */
  static boolean finish(File root, String remote, String into, String current, boolean push) throws IOException, InterruptedException {
    if (!hasRef(root, "MERGE_HEAD") || !conflicts(root).isEmpty()) return false;
    git(root, "commit", "--no-edit");
    boolean switched = !into.equals(current);
    try {
      push(root, remote, into, push);
    }
    catch (IOException e) {
      if (switched) checkoutBack(root, current, e);
      throw e;
    }
    if (switched) git(root, GIT_LONG_TIMEOUT_SEC, "checkout", current);
    unstash(root);
    return true;
  }

  /** 합친 결과를 서버 개인 브랜치로 올린다. 실패하면 합치기는 내 PC 에 남아 있다는 안내를 붙인다. */
  private static void push(File root, String remote, String into, boolean push) throws IOException, InterruptedException {
    if (!push) return;
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "push", remote, "HEAD:" + HEADS + into);
    }
    catch (IOException e) {
      throw new IOException(e.getMessage() + "\n" + SyncBundle.message("rmerge.push.failed", into), e);
    }
  }

  /** 원래 브랜치로 돌아간다. 돌아가지 못해도 cause 를 그대로 알리도록 그 실패는 cause 에 덧붙인다. */
  private static void checkoutBack(File root, String branch, IOException cause) throws InterruptedException {
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "checkout", branch);
    }
    catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

  /** 내 PC 의 branch 에만 있고 서버(remote/branch)에는 없는 커밋 수. 내 PC 에 그 브랜치가 없으면 0. */
  static int unpushed(File root, String remote, String branch) throws IOException, InterruptedException {
    if (!hasRef(root, HEADS + branch)) return 0;
    return Integer.parseInt(git(root, "rev-list", "--count", remoteRef(remote, branch) + ".." + HEADS + branch).trim());
  }

  private static boolean hasRef(File root, String ref) throws InterruptedException {
    try {
      git(root, "rev-parse", "--verify", "--quiet", ref);
      return true;
    }
    catch (IOException missing) {
      return false;
    }
  }
}
