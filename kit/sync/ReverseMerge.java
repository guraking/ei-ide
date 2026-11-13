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
  // core.ignorecase 를 읽는 명령. 설정이 없으면 실패하지 않고 false 를 내어 다른 명령과 묶어(gitAll) 돌릴 수 있다.
  static final String[] IGNORE_CASE = {"config", "--bool", "--default", "false", "core.ignorecase"};
  static final String CONFIG_INTO = "ei.reverseMerge.into";
  /** 브랜치에 원격 설정(branch.<이름>.remote)이 없을 때 쓰는 원격. git clone 의 기본 이름이다. */
  static final String DEFAULT_REMOTE = "origin";
  /** 기준 브랜치를 처음 고를 때 이 순서로 서버 목록에서 찾는다. */
  static final List<String> FROM_CANDIDATES = List.of("develop", "dev", "development", "main", "master");
  /** 내 이름으로 커밋한 파일을 「내 파일」로 보는 기간(일). */
  static final int MINE_DAYS = 30;
  static final String HEADS = "refs/heads/";
  private static final String FETCH_KEY_SUFFIX = ".fetch";
  private static final com.intellij.openapi.diagnostic.Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ReverseMerge.class);
  private static final char LOG_START = '\u0001';
  private static final char LOG_SEP = '\u001f';
  private static final String LOG_FORMAT = "--format=%x01%an%x1f%ad%x1f%s";
  private static final String LOG_DATE = "--date=format:%m-%d %H:%M";
  private static final String AUTHOR_FORMAT = "--format=%x01%an%x1f%ae";
  /** configs 가 한 번에 읽는 설정 키(git config --get-regexp). */
  private static final String CONFIG_KEYS = "^(user\\.(name|email)|branch\\..*\\.(remote|merge)|remote\\..*\\.fetch|ei\\.reverseMerge\\..*)$";

  /** 들어올 파일 하나. author · when · subject 는 기준 브랜치에서 이 파일을 마지막으로 바꾼 커밋. */
  record Incoming(String path, String author, String when, String subject, boolean mine) {}

  /** 미리 보기: 들어올 커밋 수와 파일(내 파일이 앞). fromTip 은 미리 본 From 커밋(합칠 때 이 커밋을 합친다). */
  record Preview(int commits, List<Incoming> files, String fromTip) {
    int mineCount() {
      int n = 0;
      for (Incoming f : files) if (f.mine()) n++;
      return n;
    }
  }

  /** CONFLICT_STASHED 는 충돌이고 이번 합치기가 커밋 안 한 변경을 Stash 해 두었다(finish 에 stashed 로 넘긴다). */
  enum Result { MERGED, CONFLICT, CONFLICT_STASHED }

  /** 커밋 안 한 변경이 있어 합치지 않았다. stash 를 참으로 다시 부르면 그 변경을 Stash 해 두고 합친다. */
  static final class DirtyException extends IOException {
    DirtyException() { super(SyncBundle.message("rmerge.dirty")); }
  }

  /**
   * 합쳐 올 From 쪽이 파일 · 폴더 이름의 대소문자만 바꿨다(renames: 지금 이름 → From 쪽 이름). 대소문자를 가리지 않는 파일 시스템(core.ignorecase)에서는
   * git merge 가 새 이름을 「추적 안 된 파일을 덮어씀」으로 보고 멈춘다. 아무것도 바꾸지 않았고, matchCase 를 참으로 다시 부르면 이름을 먼저 맞춰 커밋하고 합친다.
   */
  static final class CaseRenameException extends IOException {
    final Map<String, String> renames;

    CaseRenameException(Map<String, String> renames) {
      super(SyncBundle.message("rmerge.case", renames.size()));
      this.renames = renames;
    }
  }

  /** 합치기 전에 커밋 안 한 변경을 넣어 두는 Stash 의 이름. 되살릴 때 맨 위 Stash 가 이 이름일 때만 꺼낸다(남의 Stash 를 꺼내지 않게). */
  /**
   * 합친 뒤 Push 를 서버가 받지 않았다: 미리 보기 뒤 다른 사람이 into 에 먼저 올렸다. merge 는 방금 만든 합치기 커밋을 되돌려 두었으므로
   * merge 를 다시 부르면 서버의 새 into 위에서 다시 합친다.
   */
  static final class PushRejectedException extends IOException {
    PushRejectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** git push 오류가 서버 쪽이 앞서 있어서인가(「! [rejected] … (fetch first | non-fast-forward)」). 서버 훅의 거절「[remote rejected]」은 아니다. */
  static boolean behindServer(String pushError) {
    return pushError != null && pushError.contains("[rejected]");
  }

  static final String STASH_MESSAGE = "EI Merge Branches";
  /** 미리 본 커밋(해시)을 합칠 때의 합치기 커밋 메시지. git 이 원격 브랜치를 이름으로 합칠 때 쓰는 문구와 같다(원격/브랜치, into). */
  private static final String MERGE_MESSAGE = "Merge remote-tracking branch '%s' into %s";
  /** 합치기 전에 파일 이름 대소문자를 From(원격/브랜치)에 맞추는 커밋의 메시지. */
  private static final String CASE_MESSAGE = "파일 이름 대소문자를 %s 에 맞춤";
  /** 대소문자만 다른 이름으로 옮길 때 거쳐 가는 임시 이름의 꼬리. 같은 폴더에 이 이름의 파일이 있으면 옮기기가 실패하고 아무것도 바꾸지 않는다. */
  private static final String CASE_TMP_SUFFIX = ".moa-case-tmp";
  /** 합치기를 막는 추적 안 한 파일을 안내에 보이는 줄 수. 나머지는 개수로 알린다. */
  private static final int PATH_LIST_MAX = 10;
  /** blockCaseClashes 가 git branch -d -r 한 번에 넘기는 원격 추적 참조 수. 윈도우 명령 줄 길이 한도 안에 들게 한다. */
  private static final int DELETE_BATCH = 100;
  /** 음수 refspec 이 들어온 git 판 2.29(릴리스 노트 2.29.0 「negative refspecs」). */
  private static final int NEGATIVE_REFSPEC_MAJOR = 2;
  private static final int NEGATIVE_REFSPEC_MINOR = 29;
  private static final java.util.regex.Pattern GIT_VERSION = java.util.regex.Pattern.compile("git version (\\d+)\\.(\\d+)");

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

  /**
   * 기억된 From · Into 가 지금 브랜치에 합친 것이었는가(Into 가 지금 브랜치). 아니면 기본값을 쓴다.
   * 지금 브랜치를 develop 에 합친 반대 방향 기억은 쓰지 않는다. 그대로 채우면 평소처럼 Merge 를 눌러 develop 에 올리게 된다.
   * folded 가 참이면(윈도우) 대소문자만 다른 이름을 같은 브랜치로 본다.
   */
  static boolean rememberedFor(String savedInto, String current, boolean folded) {
    return savedInto != null && (folded ? savedInto.equalsIgnoreCase(current) : savedInto.equals(current));
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
        latest.putIfAbsent(SyncPanel.pathLine(line), commit);
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
        paths.add(SyncPanel.pathLine(line));
      }
    }
    return paths;
  }

  /** 줄마다 경로 하나인 출력 → 경로 집합(git 이 감싼 경로는 풀어서). */
  static Set<String> lines(String output) {
    Set<String> set = new LinkedHashSet<>();
    for (String line : output.split("\n")) if (!line.isBlank()) set.add(SyncPanel.pathLine(line));
    return set;
  }

  /**
   * 대소문자만 바뀐 이름: ours 에만 있는 경로 가운데 theirs 에만 있는 경로와 대소문자만 다르고, 갈라진 곳(fork)에 ours 쪽 이름이 있던 것.
   * fork 에 있던 이름이어야 theirs 쪽이 바꾼 것이다(ours 쪽이 바꿨으면 그대로 두어야 한다). 결과는 ours 경로 → theirs 경로, ours 경로순.
   */
  static Map<String, String> caseRenames(Set<String> ours, Set<String> theirs, Set<String> fork) {
    Map<String, String> theirsByLower = new HashMap<>();
    for (String path : theirs) if (!ours.contains(path)) theirsByLower.put(path.toLowerCase(Locale.ROOT), path);
    Map<String, String> renames = new TreeMap<>();
    for (String path : ours) {
      if (theirs.contains(path) || !fork.contains(path)) continue;
      String target = theirsByLower.get(path.toLowerCase(Locale.ROOT));
      if (target != null) renames.put(path, target);
    }
    return renames;
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
      if (space <= 0 || space >= line.trim().length()) continue;
      String key = line.substring(0, space);
      String value = line.substring(space + 1).trim();
      // remote.<원격>.fetch 는 줄이 여럿일 수 있다(예: Feature/* 를 더 받는 줄). 줄바꿈으로 이어 모두 남긴다(fetches).
      if (key.endsWith(FETCH_KEY_SUFFIX) && result.containsKey(key)) value = result.get(key) + "\n" + value;
      result.put(key, value);
    }
    return result;
  }

  static void saveConfig(File root, String key, String value) throws IOException, InterruptedException {
    git(root, "config", key, value);
  }

  /**
   * 인자 없는 git fetch 가 branch 를 받아 remoteRef(remote, branch) 에 두는가. remote.<원격>.fetch 줄(여럿이면 줄바꿈으로 이은 것) 가운데
   * 하나라도 refs/heads/<branch> 를 그 자리로 옮기면 참이다. + 가 없거나 별표 위치가 다른 줄도 git 규칙대로 본다. 줄이 없으면 거짓.
   */
  static boolean fetches(Map<String, String> cfg, String remote, String branch) {
    String lines = cfg.get("remote." + remote + FETCH_KEY_SUFFIX);
    if (lines == null) return false;
    boolean fetched = false;
    for (String line : lines.split("\n")) {
      // 앞 + 는 강제 갱신 표시. ^ 로 시작하는 줄은 빼는 규칙이라 줄 순서와 상관없이 맞으면 받지 않는다.
      String spec = line.startsWith("+") ? line.substring(1) : line;
      if (spec.startsWith("^")) {
        if (globPart(spec.substring(1), HEADS + branch) != null) return false;
        continue;
      }
      int colon = spec.indexOf(':');
      if (colon < 0) continue;
      String part = globPart(spec.substring(0, colon), HEADS + branch);
      if (part != null && spec.substring(colon + 1).replace("*", part).equals(remoteRef(remote, branch))) fetched = true;
    }
    return fetched;
  }

  /**
   * 대소문자만 다른 서버 브랜치 · 폴더(Feature/x 와 feature/y 등)를 가려, 받지 않을 쪽의 음수 refspec(^refs/heads/…) 목록을 낸다.
   * 윈도우는 대소문자만 다른 원격 추적 참조가 같은 파일 · 폴더라, 둘 다 받으면 fetch --prune 이 디스크의 철자를 보고 남길 브랜치의 사본까지 지운다.
   * 앞 폴더부터 한 단계씩 보고, 소문자로 같은 이름이 둘 이상이면 keep(앞쪽이 먼저)이 쓰는 철자를, keep 에 없으면 브랜치가 더 많은 철자를 남긴다.
   * 막은 철자 아래 브랜치는 더 깊은 단계에서 보지 않는다. 겹치는 이름이 없으면 빈 목록.
   */
  static List<String> caseClashExcludes(List<String> branches, List<String> keep) {
    List<String> left = new ArrayList<>(branches);
    List<String> excludes = new ArrayList<>();
    for (int depth = 1; ; depth++) {
      Map<String, Map<String, Integer>> spellings = new TreeMap<>();
      for (String b : left) {
        String p = namePrefix(b, depth);
        if (p != null) spellings.computeIfAbsent(p.toLowerCase(Locale.ROOT), k -> new TreeMap<>()).merge(p, 1, Integer::sum);
      }
      if (spellings.isEmpty()) return excludes;
      for (Map<String, Integer> group : spellings.values()) {
        if (group.size() < 2) continue;
        String kept = keptSpelling(group, keep, depth);
        for (String spelling : group.keySet()) {
          if (spelling.equals(kept)) continue;
          boolean leaf = false;
          boolean folder = false;
          for (String b : left) {
            leaf |= b.equals(spelling);
            folder |= b.startsWith(spelling + "/");
          }
          if (leaf) excludes.add("^" + HEADS + spelling);
          if (folder) excludes.add("^" + HEADS + spelling + "/*");
          left.removeIf(b -> b.equals(spelling) || b.startsWith(spelling + "/"));
        }
      }
    }
  }

  /** 브랜치 이름의 앞 depth 단계(슬래시로 나눈 조각). 조각이 그보다 적으면 null. */
  private static String namePrefix(String branch, int depth) {
    int end = -1;
    for (int i = 0; i < depth; i++) {
      if (end == branch.length()) return null;
      int slash = branch.indexOf('/', end + 1);
      end = slash < 0 ? branch.length() : slash;
    }
    return branch.substring(0, end);
  }

  /** 소문자로 같은 철자 묶음에서 남길 철자: keep 가운데 앞쪽이 쓰는 철자, 없으면 브랜치가 가장 많은 철자(같으면 이름순 앞). */
  private static String keptSpelling(Map<String, Integer> group, List<String> keep, int depth) {
    for (String k : keep) {
      String p = k == null ? null : namePrefix(k, depth);
      if (p != null && group.containsKey(p)) return p;
    }
    String best = null;
    for (Map.Entry<String, Integer> e : group.entrySet()) {
      if (best == null || e.getValue() > group.get(best)) best = e.getKey();
    }
    return best;
  }

  /** git version 출력(「git version 2.47.1.windows.1」)이 음수 refspec(^…)을 아는 판(2.29 이상)이면 참. 읽지 못하면 거짓. */
  static boolean negativeRefspecs(String versionOutput) {
    java.util.regex.Matcher m = GIT_VERSION.matcher(versionOutput);
    if (!m.find()) return false;
    int major = Integer.parseInt(m.group(1));
    int minor = Integer.parseInt(m.group(2));
    return major > NEGATIVE_REFSPEC_MAJOR || major == NEGATIVE_REFSPEC_MAJOR && minor >= NEGATIVE_REFSPEC_MINOR;
  }

  /** 음수 refspec 목록(caseClashExcludes) 가운데 하나라도 refs/heads/<branch> 를 빼면 참. */
  static boolean excluded(List<String> excludes, String branch) {
    for (String ex : excludes) {
      if (globPart(ex.substring(1), HEADS + branch) != null) return true;
    }
    return false;
  }

  /**
   * 대소문자만 다른 서버 브랜치를 remote.<원격>.fetch 의 음수 refspec 으로 막는다. 설정에 남아 IntelliJ 의 fetch 에도 적용된다.
   * 막은 철자로 보이는 원격 추적 참조는 지우고 원격을 다시 받는다(--prune 없이). 윈도우에서 그 참조는 남길 브랜치와 같은 파일일 수 있어,
   * 지운 뒤 받아야 남길 철자의 폴더 · 파일로 다시 생긴다. 서버에 겹치는 이름이 없으면 아무것도 바꾸지 않고 false.
   * 사용자가 부르지 않은 자동 확인에서 쓰므로 인증 실패에도 로그인 창을 띄우지 않는다.
   */
  static boolean blockCaseClashes(File root, Map<String, String> cfg, String remote, List<String> keep) throws IOException, InterruptedException {
    List<String> excludes = caseClashExcludes(parseHeads(GitRemoteAuth.run(root, GIT_LONG_TIMEOUT_SEC, false, "ls-remote", "--heads", remote)), keep);
    if (excludes.isEmpty()) return false;
    // 음수 refspec 을 모르는 git 은 설정에 그 줄이 있으면 IntelliJ 의 fetch 까지 모두 실패한다.
    if (!negativeRefspecs(git(root, "version"))) {
      LOG.warn("대소문자만 다른 서버 브랜치가 있으나 git 이 음수 refspec 을 몰라(2.29 미만) 막지 못함 [" + root.getName() + "] " + excludes);
      return false;
    }
    String key = "remote." + remote + FETCH_KEY_SUFFIX;
    List<String> have = List.of(cfg.getOrDefault(key, "").split("\n"));
    List<String> added = new ArrayList<>();
    for (String ex : excludes) {
      if (have.contains(ex)) continue;
      git(root, "config", "--add", key, ex);
      added.add(ex);
    }
    List<String> stale = new ArrayList<>();
    for (String name : parseRefs(git(root, "for-each-ref", "--format=%(refname)", remoteRef(remote, "")), remote)) {
      if (excluded(excludes, name)) stale.add(remote + "/" + name);
    }
    // 한 번에 다 넘기면 윈도우 명령 줄 길이(32K)를 넘을 수 있어 나눠 지운다.
    for (int i = 0; i < stale.size(); i += DELETE_BATCH) {
      List<String> args = new ArrayList<>(List.of("branch", "-d", "-r"));
      args.addAll(stale.subList(i, Math.min(stale.size(), i + DELETE_BATCH)));
      git(root, args.toArray(String[]::new));
    }
    GitRemoteAuth.run(root, GIT_LONG_TIMEOUT_SEC, false, "fetch", "--quiet", remote);
    LOG.info("대소문자만 다른 서버 브랜치를 받지 않게 막음 [" + root.getName() + "] " + key + " 에 더함 " + added + ", 지운 원격 추적 참조 " + stale);
    return true;
  }

  /** git refspec 의 별표 하나짜리 모양 pattern 이 ref 에 맞으면 별표 자리 글자(별표가 없으면 빈 글), 아니면 null. */
  private static String globPart(String pattern, String ref) {
    int star = pattern.indexOf('*');
    if (star < 0) return pattern.equals(ref) ? "" : null;
    String prefix = pattern.substring(0, star);
    String suffix = pattern.substring(star + 1);
    if (ref.length() < prefix.length() + suffix.length() || !ref.startsWith(prefix) || !ref.endsWith(suffix)) return null;
    return ref.substring(prefix.length(), ref.length() - suffix.length());
  }

  /** 서버 목록에 대소문자까지 같은 이름이 없고 대소문자만 다른 이름이 있으면 그 서버 이름, 아니면 null. */
  static String serverSpelling(List<String> server, String name) {
    if (name == null || server.contains(name)) return null;
    for (String s : server) if (s.equalsIgnoreCase(name)) return s;
    return null;
  }

  /** branch 가 쓰는 원격 이름(configs 로 읽어 둔 branch.<이름>.remote). 설정이 없으면 DEFAULT_REMOTE. */
  static String remoteOf(Map<String, String> cfg, String branch) {
    String remote = branch == null ? null : cfg.get("branch." + branch + ".remote");
    return remote == null ? DEFAULT_REMOTE : remote;
  }

  /**
   * git 명령 여럿을 동시에 돌려 입력 순서대로 결과를 돌려준다. 서로 기다릴 필요 없는 명령을 차례로 띄우면 명령 수만큼 git 시작 시간이 쌓인다.
   * 하나가 실패하면 그 예외를 던지고, 부른 쪽이 중단되면 아직 도는 git 도 끝낸다(SyncPanel.inParallel).
   */
  static List<String> gitAll(File root, List<String[]> commands) throws IOException, InterruptedException {
    return SyncPanel.inParallel(commands, args -> git(root, args));
  }

  /** 서버에 있는 브랜치 이름들. 내 fetch 설정과 상관없이 서버에 직접 묻는다. */
  static List<String> serverBranches(File root, String remote) throws IOException, InterruptedException {
    return serverBranches(root, remote, GIT_LONG_TIMEOUT_SEC);
  }

  /** serverBranches 와 같고 시간 제한(초)을 따로 준다. */
  static List<String> serverBranches(File root, String remote, long timeoutSec) throws IOException, InterruptedException {
    return parseHeads(GitRemoteAuth.run(root, timeoutSec, true, "ls-remote", "--heads", remote));
  }

  /** for-each-ref --format=%(objectname) %(refname) 출력 → 참조 이름 → 커밋. 참조 이름 순서는 출력 순서. */
  static Map<String, String> parseRefTips(String output) {
    Map<String, String> tips = new LinkedHashMap<>();
    for (String line : output.split("\n")) {
      int space = line.indexOf(' ');
      if (space > 0) tips.put(line.substring(space + 1).trim(), line.substring(0, space));
    }
    return tips;
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
    fetch(root, true, remote, branches);
  }

  /** fetch 와 같고 인증에 실패해도 로그인 창을 띄우지 않는다(사용자가 부르지 않은 자동 fetch). */
  static void fetchQuietly(File root, String remote, String... branches) throws IOException, InterruptedException {
    fetch(root, false, remote, branches);
  }

  private static void fetch(File root, boolean ask, String remote, String... branches) throws IOException, InterruptedException {
    List<String> args = new ArrayList<>(List.of("fetch", "--quiet", remote));
    for (String b : new LinkedHashSet<>(List.of(branches))) args.add("+" + HEADS + b + ":" + remoteRef(remote, b));
    GitRemoteAuth.run(root, GIT_LONG_TIMEOUT_SEC, ask, args.toArray(String[]::new));
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
    // shortcut: rev-parse 를 다른 명령과 동시에 돌려, 그 사이 자동 fetch 가 From 을 바꾸면 목록과 fromTip 이 어긋날 수 있다. 어긋남이 보이면 먼저 rev-parse 만 따로 돌린다.
    List<String[]> first = new ArrayList<>(List.of(
      new String[]{"rev-list", "--count", i + ".." + f},
      new String[]{"diff", "--name-only", i + "..." + f},
      new String[]{"diff", "--name-only", f + "..." + i},
      new String[]{"rev-parse", f}));
    // --no-optional-locks: 작업본과 비교하는 diff 가 index.lock 을 잡아 IntelliJ 의 커밋 · 체크아웃을 막지 않게 한다.
    if (into.equals(current)) first.add(new String[]{"--no-optional-locks", "diff", "--name-only", "HEAD"});
    if (email != null) first.add(new String[]{"log", "--author=" + email, "--since=" + MINE_DAYS + ".days", "--format=", "--name-only", f, i});
    List<String> out = gitAll(root, first);
    int commits = Integer.parseInt(out.get(0).trim());
    Set<String> theirs = lines(out.get(1));
    Set<String> mine = lines(out.get(2));
    String fromTip = out.get(3).trim();
    for (int k = 4; k < out.size(); k++) mine.addAll(lines(out.get(k)));
    if (theirs.isEmpty()) return new Preview(commits, plan(theirs, mine, Map.of()), fromTip);
    // 들어올 파일이 있을 때만: 누가 고쳤나(남의 커밋 가리기)와 파일별 마지막 커밋.
    List<String> logs = gitAll(root, List.of(new String[]{"log", AUTHOR_FORMAT, "--name-only", i + ".." + f},
                                             new String[]{"log", LOG_FORMAT, LOG_DATE, "--name-only", i + ".." + f}));
    if (!mine.isEmpty()) mine.retainAll(touchedByOthers(logs.get(0), cfg.get("user.name"), email));
    return new Preview(commits, plan(theirs, mine, parseLog(logs.get(1))), fromTip);
  }

  /**
   * into 로 옮겨(지금 브랜치가 아니면) 서버 개인 브랜치를 먼저 따라잡고(fast-forward 만), from 의 fromTip 커밋을 합친다. 충돌이면 CONFLICT(into 에서 합치기 상태로 둔다).
   * fromTip 은 미리 본 From 커밋이다. 그 뒤 자동 fetch 가 From 을 새로 받아 와도 미리 보기에 없던 커밋은 합치지 않는다.
   * push 면 합친 뒤 서버 개인 브랜치로 올린다. 서버 개인 브랜치와 내 것이 갈라져 있으면 합치기 전에 멈춘다.
   * 커밋 안 한 변경이 합치기를 막으면(blocksMerge) stash 가 거짓이면 아무것도 하지 않고 DirtyException 으로 멈춘다. 그 상태로 합치다 충돌이 나 합치기를 취소하면 git 이 그 변경을 되살리지 못할 수 있다.
   * 막지 않는 수정(합치기가 건드리지 않는 파일)은 그대로 둔 채 합친다. 충돌 마무리 커밋(finish)은 인덱스만 담으므로 그 수정은 커밋에 들어가지 않는다.
   * stash 가 참이면 그 변경을 Stash(STASH_MESSAGE) 해 두고 합친 뒤 원래 브랜치에서 되살린다. 실패해도(into 로 옮기기 실패 포함) 원래 브랜치로 돌아가 되살린다.
   * 충돌이면 Stash 를 두고 CONFLICT_STASHED 를 돌려준다. finish 가 마무리한 뒤 되살린다.
   * 시작 전에 into 를 서버에서 다시 받아, 미리 보기 뒤 남이 올린 into 커밋 위에서 합친다.
   * 그래도 Push 를 서버가 앞서 있어 거절하면(behindServer) 방금 만든 합치기 커밋만 되돌리고(reset --keep, 커밋 안 한 변경은 그대로) PushRejectedException.
   * into 로 옮겼으면 끝난 뒤(실패 포함, 충돌 빼고) 원래 브랜치로 돌아온다. 되살리지 못하면 그 안내를 예외 메시지에 붙인다.
   * 대소문자를 가리지 않는 저장소(core.ignorecase)에서 From 쪽이 이름의 대소문자만 바꿨으면(caseRenames) matchCase 가 거짓이면 아무것도 하지 않고 CaseRenameException.
   * 참이면 합치기 바로 전에 into 에서 그 이름을 git mv 로 맞춰 커밋(CASE_MESSAGE)한다. 합치기가 충돌 없이 실패하거나 Push 가 거절되면 그 커밋까지 되돌린다.
   */
  static Result merge(File root, String remote, String from, String fromTip, String into, String current, boolean push, boolean stash, boolean matchCase)
    throws IOException, InterruptedException {
    // 같은 저장소에서 받기 · 다른 Merge · Undo 가 도는 중이면 checkout · stash 가 엇갈리지 않게 시작하지 않는다.
    String work = SyncPanel.beginWork(root);
    try {
      refuseInProgress(root);
      return mergeAlone(root, remote, from, fromTip, into, current, push, stash, matchCase);
    }
    finally {
      SyncPanel.endWork(work);
    }
  }

  private static Result mergeAlone(File root, String remote, String from, String fromTip, String into, String current, boolean push, boolean stash,
                                   boolean matchCase) throws IOException, InterruptedException {
    fetch(root, remote, into);
    String intoRemote = remoteRef(remote, into);
    // 서로 기다릴 필요 없는 읽기는 묶어서 동시에 돌린다. 회사 PC 는 git 을 하나 띄울 때마다 보안 검사로 0.5초 안팎이 든다.
    List<String> local = gitAll(root, List.of(IGNORE_CASE,
                                              new String[]{"--no-optional-locks", "status", "--porcelain=v1", "-z", "-uall"},
                                              new String[]{"rev-parse", "HEAD"}));
    boolean folded = Boolean.parseBoolean(local.get(0).trim());
    WorkTree work = parseStatusZ(local.get(1));
    String head = local.get(2).trim();
    boolean switched = !(into.equals(current) || folded && into.equalsIgnoreCase(current));
    boolean stashed = false;
    // 합치기가 시작하는 곳: 옮겨 갈 브랜치(없으면 서버 것으로 만든다) 또는 지금 HEAD. 서버 것이 앞서 있으면 따라잡은 뒤 거기서 합친다.
    String start = !switched ? "HEAD" : hasRef(root, HEADS + into) ? HEADS + into : intoRemote;
    List<String[]> second = new ArrayList<>();
    second.add(new String[]{"rev-list", "--count", start + ".." + intoRemote});
    if (folded) second.add(new String[]{"ls-tree", "-r", "--name-only", fromTip});
    List<String> counted = gitAll(root, second);
    int behind = Integer.parseInt(counted.get(0).trim());
    String base = behind > 0 ? intoRemote : start;
    Map<String, String> renames = Map.of();
    Map<String, Boolean> touched;
    if (folded) {
      Set<String> fromTree = lines(counted.get(1));
      String fork = forkPoint(root, base, fromTip);
      List<String[]> third = new ArrayList<>(touchedCommands(start, base, fromTip));
      third.add(new String[]{"ls-tree", "-r", "--name-only", base});
      if (fork != null) third.add(new String[]{"ls-tree", "-r", "--name-only", fork});
      List<String> out = gitAll(root, third);
      int trees = third.size() - (fork != null ? 2 : 1);
      touched = parseTouched(out.subList(0, trees));
      renames = caseRenames(lines(out.get(trees)), fromTree, fork != null ? lines(out.get(trees + 1)) : Set.of());
    }
    else {
      touched = parseTouched(gitAll(root, touchedCommands(start, base, fromTip)));
    }
    if (!renames.isEmpty() && !matchCase) throw new CaseRenameException(renames);
    // 추적 안 한 파일은 Stash 에 들어가지 않고 git 이 덮어쓰지도 않는다. 옮기기 · 합치기 도중에 영어 오류로 멈추지 않게 미리 이름을 알린다.
    // 지우기만 하는 경로는 그 자리에 추적 안 한 파일이 있어도 git 이 멈추지 않으므로 뺀다.
    Set<String> written = new HashSet<>();
    for (Map.Entry<String, Boolean> t : touched.entrySet()) if (t.getValue()) written.add(t.getKey());
    List<String> inWay = inTheWay(work.untracked(), written, folded);
    if (!inWay.isEmpty()) throw new IOException(SyncBundle.message("rmerge.untracked", pathList(inWay)));
    if (blocksMerge(work, touched.keySet())) {
      if (!stash) throw new DirtyException();
      git(root, GIT_LONG_TIMEOUT_SEC, "stash", "push", "-m", STASH_MESSAGE);
      stashed = true;
    }
    try {
      if (switched) {
        if (hasRef(root, HEADS + into)) git(root, GIT_LONG_TIMEOUT_SEC, "checkout", into);
        else {
          // --track 은 fetch 설정이 그 브랜치를 받지 않으면(개인 브랜치만 받는 clone) 실패해서, 추적 설정을 직접 적는다.
          git(root, GIT_LONG_TIMEOUT_SEC, "checkout", "-b", into, intoRemote);
          saveConfig(root, "branch." + into + ".remote", remote);
          saveConfig(root, "branch." + into + ".merge", HEADS + into);
        }
      }
      // 옮기지 않았으면 HEAD 가 start 라 앞에서 센 수를 그대로 쓴다.
      if ((switched ? Integer.parseInt(git(root, "rev-list", "--count", "HEAD.." + intoRemote).trim()) : behind) > 0) {
        try {
          git(root, GIT_LONG_TIMEOUT_SEC, "merge", "--ff-only", intoRemote);
        }
        catch (IOException diverged) {
          // 실제로 갈라졌을 때만 갈라졌다고 안내한다. 아니면(추적 안 한 파일 덮어쓰기 · index.lock · 파일 잠금) git 오류 원문을 그대로 보인다.
          if (isAncestor(root, "HEAD", intoRemote)) throw diverged;
          throw new IOException(SyncBundle.message("rmerge.diverged", into) + "\n" + diverged.getMessage(), diverged);
        }
      }
      // 옮기지도 따라잡지도 않았으면 HEAD 는 처음 읽은 그대로다.
      String beforeMerge = !switched && behind == 0 ? head : git(root, "rev-parse", "HEAD").trim();
      if (!renames.isEmpty()) matchCase(root, renames, String.format(CASE_MESSAGE, remote + "/" + from));
      try {
        git(root, GIT_LONG_TIMEOUT_SEC, "merge", "--no-edit", "-m", String.format(MERGE_MESSAGE, remote + "/" + from, into), fromTip);
      }
      catch (IOException e) {
        if (!conflicts(root).isEmpty()) return stashed ? Result.CONFLICT_STASHED : Result.CONFLICT;
        // 충돌 없이 멈춘 합치기(훅 거부 · 시간 초과 등)는 반쯤 된 합치기 상태를 지워, 원래 브랜치로 옮기거나 Stash 를 꺼낼 때 섞이지 않게 한다.
        abortMerge(root, e);
        if (!renames.isEmpty()) undoCommit(root, beforeMerge, e);
        throw e;
      }
      try {
        push(root, remote, into, push, switched);
      }
      catch (IOException e) {
        if (!behindServer(e.getMessage())) throw e;
        if (!undoCommit(root, beforeMerge, e)) throw e;
        throw new PushRejectedException(SyncBundle.message("rmerge.push.rejected", remote + "/" + into), e);
      }
      if (switched) git(root, GIT_LONG_TIMEOUT_SEC, "checkout", current);
    }
    catch (IOException e) {
      throw restore(root, current, switched, stashed, e);
    }
    if (stashed) unstash(root);
    return Result.MERGED;
  }

  /**
   * 합치기가 바꾸는 파일: start(옮겨 갈 브랜치 또는 HEAD)로 옮기기, base(따라잡은 서버 브랜치, 따라잡지 않으면 start)로 따라잡기, base 에서 fromTip 합치기.
   * 이름 바꾸기는 옛 경로 · 새 경로를 모두 바뀐 파일로 본다(--no-renames). 세 diff 는 동시에 돌린다.
   * 값은 그 경로에 파일을 쓰는가(세 diff 가운데 하나라도 지우기가 아님)다.
   */
  static Map<String, Boolean> touched(File root, String start, String base, String fromTip) throws IOException, InterruptedException {
    return parseTouched(gitAll(root, touchedCommands(start, base, fromTip)));
  }

  /** touched 가 돌릴 diff. 양쪽이 같은 커밋 이름인 diff(옮기지 않음 · 따라잡지 않음)는 바뀐 것이 없어 뺀다. */
  static List<String[]> touchedCommands(String start, String base, String fromTip) {
    List<String[]> commands = new ArrayList<>();
    if (!start.equals("HEAD")) commands.add(new String[]{"diff", "--name-status", "--no-renames", "HEAD", start});
    if (!base.equals(start)) commands.add(new String[]{"diff", "--name-status", "--no-renames", start, base});
    commands.add(new String[]{"diff", "--name-status", "--no-renames", base + "..." + fromTip});
    return commands;
  }

  /** touchedCommands 출력들 → 경로마다 그 경로에 파일을 쓰는가. */
  static Map<String, Boolean> parseTouched(List<String> outputs) {
    Map<String, Boolean> touched = new LinkedHashMap<>();
    for (String out : outputs) {
      for (Map.Entry<String, String> e : SyncPanel.parseNameStatus(out).entrySet()) touched.merge(e.getKey(), !"D".equals(e.getValue()), Boolean::logicalOr);
    }
    return touched;
  }

  /** 작업본 상태: 추적 안 한 파일, 올려 둔(staged) 변경이 있는가, 올리지 않은 고친 파일. */
  record WorkTree(Set<String> untracked, boolean staged, Set<String> unstaged) {}

  /**
   * git status --porcelain=v1 -z -uall 출력 → WorkTree. 한 항목은 「XY 경로」이고 이름 바꾸기(X 또는 Y 가 R · C)는 다음 칸에 옛 경로가 더 온다.
   * ?? 는 추적 안 한 파일, X 가 빈칸이 아니면 올려 둔 변경, Y 가 빈칸이 아니면 올리지 않은 변경이다.
   * ls-files --others --exclude-standard · diff --cached --name-only · diff --name-only 셋을 git 한 번으로 대신한다.
   */
  static WorkTree parseStatusZ(String output) {
    Set<String> untracked = new LinkedHashSet<>();
    Set<String> unstaged = new LinkedHashSet<>();
    boolean staged = false;
    String[] items = output.split("\0");
    for (int k = 0; k < items.length; k++) {
      String item = items[k];
      if (item.length() < 4) continue;
      char x = item.charAt(0);
      char y = item.charAt(1);
      String path = item.substring(3);
      if (x == 'R' || x == 'C' || y == 'R' || y == 'C') k++;
      if (x == '?' && y == '?') {
        untracked.add(path);
        continue;
      }
      if (x != ' ') staged = true;
      if (y != ' ') unstaged.add(path);
    }
    return new WorkTree(untracked, staged, unstaged);
  }

  /**
   * 커밋 안 한 변경이 합치기를 막는가. 올려 둔(staged) 변경이 있으면 git merge 가 거부하므로 참이다.
   * 아니면 고친 파일이 합치기가 바꾸는 파일(touched)과 겹칠 때만 참이다.
   */
  static boolean blocksMerge(WorkTree work, Set<String> touched) {
    if (work.staged()) return true;
    for (String path : work.unstaged()) if (touched.contains(path)) return true;
    return false;
  }

  /**
   * 추적 안 한 파일(untracked) 가운데 합치기가 만들거나 바꾸는 경로(touched)와 같은 것. git 은 이런 파일을 덮어쓰지 않고 옮기기 · 합치기를 멈춘다.
   * ignoreCase 면(core.ignorecase) 대소문자만 다른 이름도 같은 파일로 본다. 결과는 untracked 순서.
   */
  static List<String> inTheWay(Set<String> untracked, Set<String> touched, boolean ignoreCase) {
    Set<String> keys = new HashSet<>();
    for (String path : touched) keys.add(ignoreCase ? path.toLowerCase(Locale.ROOT) : path);
    List<String> hits = new ArrayList<>();
    for (String path : untracked) if (keys.contains(ignoreCase ? path.toLowerCase(Locale.ROOT) : path)) hits.add(path);
    return hits;
  }

  /** 경로 목록을 한 줄에 하나씩, PATH_LIST_MAX 줄까지 보이고 나머지는 개수로 알린다. */
  static String pathList(List<String> paths) {
    StringBuilder text = new StringBuilder();
    for (int k = 0; k < paths.size(); k++) {
      if (k == PATH_LIST_MAX) {
        text.append(SyncBundle.message("rmerge.case.more", paths.size() - k));
        break;
      }
      text.append(paths.get(k)).append('\n');
    }
    return text.toString().trim();
  }

  /** 끝나지 않은 합치기 · cherry-pick · revert · rebase · git am 이 있으면 IOException. 그 위에서 합치면 Stash 가 그 상태를 지우거나 그 일을 대신 마무리하게 된다. */
  private static void refuseInProgress(File root) throws IOException, InterruptedException {
    // 보통 저장소는 .git 이 폴더라 git 을 띄우지 않는다. 워크트리 · 서브모듈처럼 .git 이 파일이면 git 에 묻는다.
    File dotGit = new File(root, ".git");
    File gitDir = dotGit.isDirectory() ? dotGit : new File(git(root, "rev-parse", "--absolute-git-dir").trim());
    for (String name : GitUndo.IN_PROGRESS) {
      if (new File(gitDir, name).exists()) throw new IOException(SyncBundle.message("rmerge.in.progress", name));
    }
  }

  /** 합치기 상태(MERGE_HEAD)가 남아 있으면 merge --abort 로 지운다. 지우지 못하면 그 실패를 cause 에 덧붙인다. */
  private static void abortMerge(File root, IOException cause) throws InterruptedException {
    if (!hasRef(root, "MERGE_HEAD")) return;
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "merge", "--abort");
    }
    catch (IOException abortFailed) {
      cause.addSuppressed(abortFailed);
    }
  }

  /** ancestor 가 commit 의 조상(같은 커밋 포함)이면 true. */
  private static boolean isAncestor(File root, String ancestor, String commit) throws InterruptedException {
    try {
      git(root, "merge-base", "--is-ancestor", ancestor, commit);
      return true;
    }
    catch (IOException notAncestor) {
      return false;
    }
  }

  /** HEAD 를 before 로 되돌린다(reset --keep, 커밋 안 한 변경은 그대로). 되돌리지 못하면 그 실패를 cause 에 덧붙이고 false. */
  private static boolean undoCommit(File root, String before, IOException cause) throws InterruptedException {
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "reset", "--quiet", "--keep", before);
      return true;
    }
    catch (IOException resetFailed) {
      cause.addSuppressed(resetFailed);
      return false;
    }
  }

  /**
   * renames(지금 이름 → 새 이름)를 caseMove 로 옮기고 message 로 커밋한다. 새 이름의 폴더가 없으면 만든다(폴더 이름 대소문자가 바뀐 경우).
   * 옮기다 실패하면 이미 옮긴 것을 거꾸로 되돌리고 그 실패를 던진다. 부르기 전에 올려 둔(staged) 변경이 없어야 한다(blocksMerge).
   */
  private static void matchCase(File root, Map<String, String> renames, String message) throws IOException, InterruptedException {
    List<Map.Entry<String, String>> moved = new ArrayList<>();
    try {
      for (Map.Entry<String, String> r : renames.entrySet()) {
        File parent = new File(root, r.getValue()).getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException(SyncBundle.message("rmerge.case.mkdir", parent));
        caseMove(root, r.getKey(), r.getValue());
        moved.add(r);
      }
      git(root, "commit", "--quiet", "--no-verify", "-m", message);
    }
    catch (IOException e) {
      for (int i = moved.size() - 1; i >= 0; i--) {
        try {
          caseMove(root, moved.get(i).getValue(), moved.get(i).getKey());
        }
        catch (IOException undoFailed) {
          e.addSuppressed(undoFailed);
        }
      }
      throw e;
    }
  }

  /**
   * from 을 to 로 git mv 한다. 대소문자만 다른 이름으로 바로 옮기면 윈도우 git 이 거부하는 경우가 있어 같은 폴더의 임시 이름(CASE_TMP_SUFFIX)을 거쳐 두 번 옮긴다.
   * 두 번째가 실패하면 임시 이름을 from 으로 되돌리고 그 실패를 던진다.
   */
  private static void caseMove(File root, String from, String to) throws IOException, InterruptedException {
    String tmp = from + CASE_TMP_SUFFIX;
    git(root, "mv", "--", from, tmp);
    try {
      git(root, "mv", "--", tmp, to);
    }
    catch (IOException e) {
      try {
        git(root, "mv", "--", tmp, from);
      }
      catch (IOException undoFailed) {
        e.addSuppressed(undoFailed);
      }
      throw e;
    }
  }

  /**
   * into 가 지금 브랜치인가. 윈도우는 대소문자만 다른 브랜치 이름을 한 브랜치로 본다(.git/refs 폴더가 겹침).
   * 그때는 옮기지 않고 지금 브랜치에 합쳐 서버 이름(into)으로 올린다.
   */
  private static boolean sameBranch(File root, String into, String current) throws InterruptedException {
    return into.equals(current) || into.equalsIgnoreCase(current) && ignoreCase(root);
  }

  /** 저장소가 파일 이름 대소문자를 가리지 않는가(core.ignorecase). 설정이 없으면 가린다. */
  static boolean ignoreCase(File root) throws InterruptedException {
    try {
      return Boolean.parseBoolean(git(root, "config", "--bool", "core.ignorecase").trim());
    }
    catch (IOException unset) {
      return false;
    }
  }

  /** 두 커밋이 갈라진 곳. 공통 조상이 없으면 null. */
  private static String forkPoint(File root, String a, String b) throws InterruptedException {
    try {
      return git(root, "merge-base", a, b).trim();
    }
    catch (IOException none) {
      return null;
    }
  }

  /** 커밋 ref 의 모든 파일 경로. ref 가 null 이면 빈 집합. */
  private static Set<String> tree(File root, String ref) throws IOException, InterruptedException {
    return ref == null ? Set.of() : lines(git(root, "ls-tree", "-r", "--name-only", ref));
  }

  /**
   * 합치기 전에 넣어 둔 Stash(STASH_MESSAGE, 가장 최근 것)를 꺼내 되살린다. 그 사이 IntelliJ 등이 Stash 를 더 만들어 맨 위가 아니어도 찾아 꺼낸다.
   * 우리 Stash 가 없으면(이미 꺼냄) 아무것도 하지 않는다. 꺼내지 못하면 Stash 를 그대로 두고 그 안내를 담은 IOException.
   */
  static void unstash(File root) throws IOException, InterruptedException {
    String ref = ourStash(git(root, "stash", "list", "--format=%gd%x09%s"));
    if (ref == null) return;
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "stash", "pop", ref);
    }
    catch (IOException e) {
      throw new IOException(e.getMessage() + "\n" + SyncBundle.message("rmerge.unstash.failed"), e);
    }
  }

  /** stash list --format=%gd%x09%s 출력에서 우리 Stash(STASH_MESSAGE)의 이름(stash@{n}). 가장 최근 것, 없으면 null. */
  static String ourStash(String list) {
    for (String line : list.split("\n")) {
      int tab = line.indexOf('\t');
      if (tab > 0 && line.substring(tab + 1).contains(STASH_MESSAGE)) return line.substring(0, tab);
    }
    return null;
  }

  /**
   * 합치기가 실패한 뒤: into 로 옮겼으면 원래 브랜치(current)로 돌아가고, stashed 면 거기서 Stash 를 되살린다.
   * 돌아가지 못했거나 되살리지 못하면 Stash 는 그대로 두고, 원래 브랜치에서 되살리라는 안내를 cause 메시지에 붙인 예외를 돌려준다.
   */
  private static IOException restore(File root, String current, boolean switched, boolean stashed, IOException cause) throws InterruptedException {
    boolean back = !switched || checkoutBack(root, current, cause);
    if (!stashed) return cause;
    if (back) {
      try {
        unstash(root);
        return cause;
      }
      catch (IOException e) {
        cause.addSuppressed(e);
      }
    }
    return new IOException(cause.getMessage() + "\n" + SyncBundle.message("rmerge.stash.left", current), cause);
  }

  /** 아직 해결하지 않은 충돌 파일(저장소 기준 경로). */
  static Set<String> conflicts(File root) throws IOException, InterruptedException {
    return lines(git(root, "diff", "--name-only", "--diff-filter=U"));
  }

  /**
   * 충돌을 해결한 뒤 합치기를 마무리한다: 합치기 커밋, push 면 서버 개인 브랜치로 올리기, into 로 옮겼으면 원래 브랜치로 돌아오기.
   * 충돌이 남았거나 합치기를 취소해(MERGE_HEAD 없음) 마무리할 것이 없으면 false 를 돌려주고 아무것도 하지 않는다.
   * stashed 는 이 합치기가 Stash 를 했는지(merge 가 CONFLICT_STASHED 를 돌려줬는지)다. 참일 때만 원래 브랜치에서 되살린다(unstash).
   * push · 돌아오기가 실패해도 원래 브랜치로 돌아가 되살린다. 커밋이 실패하면 into 에 남고, Stash 가 남았다는 안내를 붙인다.
   */
  static boolean finish(File root, String remote, String into, String current, boolean push, boolean stashed) throws IOException, InterruptedException {
    String work = SyncPanel.beginWork(root);
    try {
      return finishAlone(root, remote, into, current, push, stashed);
    }
    finally {
      SyncPanel.endWork(work);
    }
  }

  private static boolean finishAlone(File root, String remote, String into, String current, boolean push, boolean stashed)
    throws IOException, InterruptedException {
    if (!hasRef(root, "MERGE_HEAD") || !conflicts(root).isEmpty()) return false;
    try {
      // 커밋 훅(lint 등)이 느려도 끊지 않게 합치기와 같은 긴 시간 제한을 쓴다.
      git(root, GIT_LONG_TIMEOUT_SEC, "commit", "--no-edit");
    }
    catch (IOException e) {
      if (!stashed) throw e;
      throw new IOException(e.getMessage() + "\n" + SyncBundle.message("rmerge.stash.left", current), e);
    }
    boolean switched = !sameBranch(root, into, current);
    try {
      if (push) pushAfterFinish(root, remote, into, switched);
      if (switched) git(root, GIT_LONG_TIMEOUT_SEC, "checkout", current);
    }
    catch (IOException e) {
      throw restore(root, current, switched, stashed, e);
    }
    if (stashed) unstash(root);
    return true;
  }

  /**
   * 충돌을 풀어 마무리한 합치기를 올린다. 그사이 남이 into 에 먼저 올려 거절되면(behindServer) 합치기 커밋은 두고(사용자가 푼 충돌을 잃지 않게)
   * 서버 into 를 다시 받아 그 위에 합친 뒤 한 번 더 올린다. 그 합치기가 충돌 · 오류로 멈추면 취소해 마무리한 커밋만 남기고, into 에서 Pull · Push 하라는 안내를 붙인다.
   */
  private static void pushAfterFinish(File root, String remote, String into, boolean switched) throws IOException, InterruptedException {
    try {
      push(root, remote, into, true, switched);
      return;
    }
    catch (IOException rejected) {
      if (!behindServer(rejected.getMessage())) throw rejected;
    }
    fetch(root, remote, into);
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "merge", "--no-edit", remoteRef(remote, into));
    }
    catch (IOException e) {
      abortMerge(root, e);
      throw new IOException(SyncBundle.message("rmerge.push.rejected.finish", remote + "/" + into, into) + "\n" + e.getMessage(), e);
    }
    push(root, remote, into, true, switched);
  }

  /**
   * 합친 결과를 서버 개인 브랜치로 올린다. 실패하면 합치기는 내 PC 에 남아 있다는 안내를 붙인다.
   * switched(into 로 옮겨 합침)면 Merge Branches 를 다시 하면 올라가고, 아니면(지금 브랜치) 다시 열어도 합칠 것이 없어 Git → Push 로 올리라고 안내한다.
   */
  private static void push(File root, String remote, String into, boolean push, boolean switched) throws IOException, InterruptedException {
    if (!push) return;
    try {
      GitRemoteAuth.run(root, GIT_LONG_TIMEOUT_SEC, true, "push", remote, "HEAD:" + HEADS + into);
    }
    catch (IOException e) {
      throw new IOException(e.getMessage() + "\n" + SyncBundle.message(switched ? "rmerge.push.failed" : "rmerge.push.failed.current", into), e);
    }
    // 개인 브랜치만 받는 clone 은 fetch 설정에 into 가 없어 git push 가 원격 추적 ref 를 옮기지 않는다.
    // 그대로 두면 동기화 창이 다음 조회까지 옛 into 로 「into 에 안 합쳐진 내 커밋」을 센다. 방금 올린 커밋으로 맞춘다.
    // 같은 저장소의 fetch 와 겹치지 않게 서버 명령과 같은 잠금 아래서 하고, 실패해도 Push 는 이미 됐으므로 기록만 남긴다(다음 조회가 맞춘다).
    try {
      SyncPanel.gitWith(root, SyncPanel.GIT_LONG_TIMEOUT_SEC, List.of(), "update-ref", remoteRef(remote, into), "HEAD");
    }
    catch (IOException e) {
      LOG.warn("Push 뒤 원격 추적 참조를 맞추지 못함 " + root + ": " + e.getMessage());
    }
  }

  /** 원래 브랜치로 돌아간다. 돌아가지 못하면 false. cause 를 그대로 알리도록 그 실패는 cause 에 덧붙인다. */
  private static boolean checkoutBack(File root, String branch, IOException cause) throws InterruptedException {
    try {
      git(root, GIT_LONG_TIMEOUT_SEC, "checkout", branch);
      return true;
    }
    catch (IOException e) {
      cause.addSuppressed(e);
      return false;
    }
  }

  /** 내 PC 의 branch 에만 있고 서버(remote/branch)에는 없는 커밋 수. 내 PC 에 그 브랜치가 없으면 0. */
  static int unpushed(File root, String remote, String branch) throws IOException, InterruptedException {
    // 보통은 git 한 번으로 센다. 실패하면 내 PC 에 그 브랜치가 없어서(0)인지 다른 까닭인지 가린다.
    try {
      return Integer.parseInt(git(root, "rev-list", "--count", remoteRef(remote, branch) + ".." + HEADS + branch).trim());
    }
    catch (IOException e) {
      if (!hasRef(root, HEADS + branch)) return 0;
      throw e;
    }
  }

  /** unpushed 커밋 가운데 맨 위 커밋의 제목. 없으면 빈 글. */
  static String lastUnpushed(File root, String remote, String branch) throws IOException, InterruptedException {
    return git(root, "log", "-1", "--format=%s", remoteRef(remote, branch) + ".." + HEADS + branch).trim();
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
