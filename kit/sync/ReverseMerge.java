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
 * 들어올 파일 가운데 내 파일과 겹치는 것은 충돌이 나든 조용히 합쳐지든 내 작업에 영향이 있어 미리 보기에서 먼저 보여 준다.
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

  static void saveConfig(File root, String key, String value) throws IOException, InterruptedException {
    git(root, "config", key, value);
  }

  /** branch 가 쓰는 원격 이름. 설정이 없으면 DEFAULT_REMOTE. */
  static String remoteOf(File root, String branch) throws InterruptedException {
    String remote = branch == null ? null : config(root, "branch." + branch + ".remote");
    return remote == null ? DEFAULT_REMOTE : remote;
  }

  /** 서버에 있는 브랜치 이름들. 내 fetch 설정과 상관없이 서버에 직접 묻는다. */
  static List<String> serverBranches(File root, String remote) throws IOException, InterruptedException {
    return parseHeads(git(root, GIT_LONG_TIMEOUT_SEC, "ls-remote", "--heads", remote));
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
    String f = remoteRef(remote, from);
    String i = intoTip(remote, into, current);
    int commits = Integer.parseInt(git(root, "rev-list", "--count", i + ".." + f).trim());
    Set<String> theirs = lines(git(root, "diff", "--name-only", i + "..." + f));
    Set<String> mine = lines(git(root, "diff", "--name-only", f + "..." + i));
    if (into.equals(current)) mine.addAll(lines(git(root, "diff", "--name-only", "HEAD")));
    String email = config(root, "user.email");
    if (email != null) {
      mine.addAll(lines(git(root, "log", "--author=" + email, "--since=" + MINE_DAYS + ".days", "--format=", "--name-only", f, i)));
    }
    Map<String, String[]> log = theirs.isEmpty() ? Map.of() : parseLog(git(root, "log", LOG_FORMAT, LOG_DATE, "--name-only", i + ".." + f));
    return new Preview(commits, plan(theirs, mine, log));
  }

  /**
   * into 로 옮겨(지금 브랜치가 아니면) 서버 개인 브랜치를 먼저 따라잡고(fast-forward 만), from 을 합친다. 충돌이면 CONFLICT(into 에서 합치기 상태로 둔다).
   * push 면 합친 뒤 서버 개인 브랜치로 올린다. 서버 개인 브랜치와 내 것이 갈라져 있으면 합치기 전에 멈춘다.
   * 커밋 안 한 변경(새 파일 빼고)이 있으면 아무것도 하지 않고 멈춘다. 그 상태로 합치다 충돌이 나 합치기를 취소하면 git 이 그 변경을 되살리지 못할 수 있다.
   * into 로 옮겼으면 끝난 뒤(실패 포함, 충돌 빼고) 원래 브랜치로 돌아온다.
   */
  static Result merge(File root, String remote, String from, String into, String current, boolean push) throws IOException, InterruptedException {
    if (!lines(git(root, "status", "--porcelain", "-uno")).isEmpty()) throw new IOException(SyncBundle.message("rmerge.dirty"));
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
      throw e;
    }
    if (switched) git(root, GIT_LONG_TIMEOUT_SEC, "checkout", current);
    return Result.MERGED;
  }

  /** 아직 해결하지 않은 충돌 파일(저장소 기준 경로). */
  static Set<String> conflicts(File root) throws IOException, InterruptedException {
    return lines(git(root, "diff", "--name-only", "--diff-filter=U"));
  }

  /**
   * 충돌을 해결한 뒤 합치기를 마무리한다: 합치기 커밋, push 면 서버 개인 브랜치로 올리기, into 로 옮겼으면 원래 브랜치로 돌아오기.
   * 충돌이 남았거나 합치기를 취소해(MERGE_HEAD 없음) 마무리할 것이 없으면 false 를 돌려주고 아무것도 하지 않는다.
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
