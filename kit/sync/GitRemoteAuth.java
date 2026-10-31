package kinggura.sync;

import com.intellij.externalProcessAuthHelper.AuthenticationMode;
import com.intellij.externalProcessAuthHelper.PassthroughAuthenticationGate;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.commands.GitHttpAuthenticator;
import git4idea.commands.GitHttpGuiAuthenticator;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버와 통신하는 git 명령을 IntelliJ Git(kit/git-auth 의 EiGitAuthEnv)과 같은 순서로 인증해 돌린다.
 * 첫 실행: 설정 → Git 로그인(runBytes 가 넣음) → 이번 실행에서 로그인 창에 넣은 값 → IntelliJ 가 기억한 비밀번호 · GitLab · GitHub 계정 토큰.
 * 아직 성공한 적 없는 값은 서버마다 한 명령만 들고 나가고, 같은 서버의 다른 명령은 그 결과를 기다려 성공한 값을 쓰거나 실패한 값은 다시 보내지 않는다(계정 잠김 방지).
 * 인증에 실패하면 ask 일 때만 실패한 서버의 값을 지우고 IntelliJ 로그인 창으로 물어 한 번 더 돌린다. 자동 fetch(ask 아님)는 값을 지우지 않는다.
 * 화면 스레드에서 부르지 않는다.
 */
final class GitRemoteAuth {
  /** 로그인 창에서 받은 값. Remember 를 고르지 않아도 동시에 도는 저장소 · 다음 명령이 다시 묻지 않게 실패할 때까지 메모리에 둔다. */
  private static final Map<String, GitLogins.Login> SESSION = new ConcurrentHashMap<>();
  /** 서버별 로그인 창 취소 시각. 여러 저장소를 동시에 받을 때 취소한 창이 저장소 수만큼 다시 뜨지 않게 한다. */
  private static final Map<String, Long> CANCELLED = new ConcurrentHashMap<>();
  private static final long CANCEL_QUIET_MS = 60_000;
  /** 저장소를 동시에 돌려도 로그인 창은 하나씩 띄워, 앞 창의 값을 뒤 저장소가 쓰게 한다. */
  private static final Object DIALOG_LOCK = new Object();
  /** 서버별 줄 세우기. 아직 성공한 적 없는 값으로는 한 명령씩만 서버에 간다. */
  private static final Map<String, ReentrantLock> GATES = new ConcurrentHashMap<>();
  /** 서버별로 인증에 성공한 머리글. 이 값이면 줄 세우지 않고 동시에 돌린다. */
  private static final Map<String, String> VERIFIED = new ConcurrentHashMap<>();
  /** 서버별로 인증에 실패한 머리글과 그때 git 오류. 같은 값은 다시 보내지 않고 이 오류로 바로 끝낸다. */
  private static final Map<String, Rejection> REJECTED = new ConcurrentHashMap<>();
  /** git 오류 글에서 서버 주소(scheme://host[:port]) 부분. 따옴표 · 한글 · 경로 앞에서 끊는다. */
  private static final Pattern SERVER_IN_TEXT = Pattern.compile("https?://[A-Za-z0-9._~%:@\\[\\]-]+", Pattern.CASE_INSENSITIVE);

  private GitRemoteAuth() {}

  private record Rejection(String header, String error) {}

  /** 이번 실행에 넣은 값과 그 출처. */
  private static final class Attempt {
    final Map<String, GitLogins.Login> logins = new LinkedHashMap<>();
    final Map<String, GitHttpAuthenticator> authenticators = new LinkedHashMap<>();
    /** 서버 → 이번에 나갈 머리글(Git 로그인 표 값 포함). 값이 없는 서버는 빠진다. */
    final Map<String, String> headers = new LinkedHashMap<>();

    void use(String server, GitLogins.Login login, GitHttpAuthenticator authenticator) {
      logins.put(server, login);
      headers.put(server, GitLogins.header(login));
      if (authenticator != null) authenticators.put(server, authenticator);
    }

    void forget(Collection<String> servers) {
      for (String server : servers) {
        GitLogins.forget(server);
        GitLogins.Login used = logins.get(server);
        if (used != null) SESSION.remove(server, used);
        GitHttpAuthenticator authenticator = authenticators.get(server);
        if (authenticator != null) authenticator.forgetPassword();
      }
    }

    /** 성공한 값을 기억하고 그 서버의 실패 기록을 지운다. */
    void verified(Collection<String> servers) {
      for (String server : servers) {
        REJECTED.remove(server);
        String header = headers.get(server);
        if (header != null) VERIFIED.put(server, header);
      }
    }

    /** 실패한 값을 기억해 같은 값이 다시 나가지 않게 한다. */
    void rejected(Collection<String> servers, String error) {
      for (String server : servers) {
        String header = headers.get(server);
        if (header == null) continue;
        VERIFIED.remove(server, header);
        REJECTED.put(server, new Rejection(header, error));
      }
    }

    /** 앞서 실패한 값을 그대로 들고 가는 서버들. */
    List<String> knownBad() {
      List<String> bad = new ArrayList<>();
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        Rejection rejection = REJECTED.get(entry.getKey());
        if (rejection != null && rejection.header().equals(entry.getValue())) bad.add(entry.getKey());
      }
      return bad;
    }
  }

  /** git 을 돌려 표준 출력을 돌려준다. 시간 제한 · 오류 문구는 SyncPanel.run 과 같다. ask 가 false 면(자동 fetch) 로그인 창을 띄우지 않고 저장한 값도 지우지 않는다. */
  static String run(File root, long timeoutSec, boolean ask, String... args) throws IOException, InterruptedException {
    Project project = null;
    Map<String, String> urls = new LinkedHashMap<>();
    if (ApplicationManager.getApplication() != null) {
      VirtualFile dir = LocalFileSystem.getInstance().findFileByIoFile(root);
      for (Project open : ProjectManager.getInstance().getOpenProjects()) {
        GitRepository repo = dir == null ? null : GitRepositoryManager.getInstance(open).getRepositoryForRootQuick(dir);
        if (repo == null) continue;
        project = open;
        urls = serverUrls(remoteUrls(repo));
        break;
      }
    }
    if (project == null || urls.isEmpty()) return SyncPanel.gitWith(root, timeoutSec, List.of(), args);

    Attempt first = silentAttempt(project, root, urls);
    List<ReentrantLock> held = new ArrayList<>();
    try {
      // 서버 이름 순으로 잡아 여러 서버를 함께 쓰는 명령끼리 서로 기다리다 멈추지 않게 한다.
      for (String server : new TreeSet<>(first.headers.keySet())) {
        if (first.headers.get(server).equals(VERIFIED.get(server))) continue;
        ReentrantLock gate = GATES.computeIfAbsent(server, key -> new ReentrantLock());
        gate.lockInterruptibly();
        held.add(gate);
      }
      // 기다리는 동안 앞 명령이 값을 지웠거나 로그인 창 값을 남겼을 수 있어 다시 읽는다.
      if (!held.isEmpty()) first = silentAttempt(project, root, urls);
      return runGated(project, root, urls, first, timeoutSec, ask, args);
    }
    finally {
      for (ReentrantLock gate : held) gate.unlock();
    }
  }

  private static String runGated(Project project, File root, Map<String, String> urls, Attempt first, long timeoutSec, boolean ask, String... args)
    throws IOException, InterruptedException {
    IOException failure;
    Collection<String> failed = first.knownBad();
    if (!failed.isEmpty()) {
      failure = new IOException(REJECTED.get(failed.iterator().next()).error());
      if (!ask) throw failure;
    }
    else {
      try {
        String output = SyncPanel.gitWith(root, timeoutSec, List.copyOf(first.logins.values()), args);
        // shortcut: 원격이 여럿인 저장소는 명령이 어느 서버에 갔는지 몰라 성공으로 기억하지 않는다(늘 줄 세움). 느리면 args 의 원격 이름으로 서버를 가린다.
        if (urls.size() == 1) first.verified(urls.keySet());
        return output;
      }
      catch (IOException e) {
        if (!SyncPanel.isGitAuthError(e.getMessage())) throw e;
        failed = failedServers(urls.keySet(), e.getMessage());
        first.rejected(failed, e.getMessage());
        if (!ask) throw e;
        failure = e;
      }
    }
    first.forget(failed);
    Attempt second = dialogAttempt(project, root, urls, failed);
    if (second.logins.isEmpty()) throw failure;
    List<GitLogins.Login> logins = new ArrayList<>(second.logins.values());
    for (Map.Entry<String, GitLogins.Login> entry : first.logins.entrySet()) {
      if (!failed.contains(entry.getKey())) logins.add(entry.getValue());
    }
    try {
      String output = SyncPanel.gitWith(root, timeoutSec, logins, args);
      for (GitHttpAuthenticator authenticator : second.authenticators.values()) authenticator.saveAuthData();
      second.verified(second.logins.keySet());
      return output;
    }
    catch (IOException retry) {
      if (SyncPanel.isGitAuthError(retry.getMessage())) {
        second.rejected(second.logins.keySet(), retry.getMessage());
        second.forget(second.logins.keySet());
      }
      throw retry;
    }
  }

  private static List<String> remoteUrls(GitRepository repo) {
    List<String> urls = new ArrayList<>();
    for (GitRemote remote : repo.getRemotes()) urls.addAll(remote.getUrls());
    return urls;
  }

  private static Attempt silentAttempt(Project project, File root, Map<String, String> urls) {
    Attempt attempt = new Attempt();
    for (Map.Entry<String, String> entry : urls.entrySet()) {
      String server = entry.getKey();
      GitLogins.Login stored = GitLogins.stored(server);
      if (stored != null) {
        // 표 값은 runBytes 가 넣으므로 logins 에는 넣지 않고 머리글만 기억한다.
        attempt.headers.put(server, GitLogins.header(stored));
        continue;
      }
      GitLogins.Login session = SESSION.get(server);
      if (session != null) {
        attempt.use(server, session, null);
        continue;
      }
      GitHttpAuthenticator silent = authenticator(project, root, entry.getValue(), AuthenticationMode.SILENT);
      GitLogins.Login login = ask(silent, server, entry.getValue());
      if (login != null) attempt.use(server, login, silent);
    }
    return attempt;
  }

  private static Attempt dialogAttempt(Project project, File root, Map<String, String> urls, Collection<String> servers) {
    Attempt attempt = new Attempt();
    for (String server : servers) {
      synchronized (DIALOG_LOCK) {
        GitLogins.Login session = SESSION.get(server);
        if (session != null) {
          attempt.use(server, session, null);
          continue;
        }
        Long cancelled = CANCELLED.get(server);
        if (cancelled != null && System.currentTimeMillis() - cancelled < CANCEL_QUIET_MS) continue;
        GitHttpAuthenticator full = authenticator(project, root, urls.get(server), AuthenticationMode.FULL);
        GitLogins.Login login = ask(full, server, urls.get(server));
        if (login == null) {
          if (full.wasCancelled()) CANCELLED.put(server, System.currentTimeMillis());
          continue;
        }
        CANCELLED.remove(server);
        SESSION.put(server, login);
        attempt.use(server, login, full);
      }
    }
    return attempt;
  }

  private static GitHttpAuthenticator authenticator(Project project, File root, String url, AuthenticationMode mode) {
    return new GitHttpGuiAuthenticator(project, List.of(url), root.toPath(), PassthroughAuthenticationGate.getInstance(), mode);
  }

  /** 아이디 · 비밀번호를 물어 Login 으로. 취소 · 빈 값이면 null. */
  private static GitLogins.Login ask(GitHttpAuthenticator authenticator, String server, String url) {
    String user = authenticator.askUsername(url);
    if (user == null) return null;
    String password = authenticator.askPassword(url);
    if (password == null || password.isEmpty()) return null;
    return new GitLogins.Login(server, GitLogins.Kind.PASSWORD, user, password);
  }

  /** 원격 주소들을 서버 → 그 서버의 첫 주소로. ssh 등 http(s) 가 아닌 주소는 뺀다. */
  static Map<String, String> serverUrls(List<String> urls) {
    Map<String, String> servers = new LinkedHashMap<>();
    for (String url : urls) {
      try {
        servers.putIfAbsent(GitLogins.serverOf(url), url);
      }
      catch (IllegalArgumentException e) {
        // ssh · 파일 원격은 머리글 인증을 쓰지 않는다.
      }
    }
    return servers;
  }

  /** git 오류에 주소가 적힌 서버들(scheme · host · port 가 모두 같아야 한다). 어느 서버도 적혀 있지 않으면 모두. */
  static List<String> failedServers(Collection<String> servers, String error) {
    Set<String> inError = new HashSet<>();
    Matcher matcher = SERVER_IN_TEXT.matcher(error);
    while (matcher.find()) {
      try {
        inError.add(GitLogins.serverOf(matcher.group()));
      }
      catch (IllegalArgumentException e) {
        // 주소처럼 보이지만 서버가 아닌 글은 건너뛴다.
      }
    }
    List<String> named = new ArrayList<>();
    for (String server : servers) {
      if (inError.contains(server)) named.add(server);
    }
    return named.isEmpty() ? new ArrayList<>(servers) : named;
  }
}
