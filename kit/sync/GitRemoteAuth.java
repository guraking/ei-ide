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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버와 통신하는 git 명령을 IntelliJ Git(kit/git-auth 의 EiGitAuthEnv)과 같은 순서로 인증해 돌린다.
 * 첫 실행: 설정 → Git 로그인(runBytes 가 넣음) → 이번 실행에서 로그인 창에 넣은 값 → IntelliJ 가 기억한 비밀번호 · GitLab · GitHub 계정 토큰.
 * 인증에 실패하면 실패한 서버의 값을 지우고, ask 면 IntelliJ 로그인 창으로 물어 한 번 더 돌린다.
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

  private GitRemoteAuth() {}

  /** 이번 실행에 넣은 값과 그 출처. */
  private static final class Attempt {
    final Map<String, GitLogins.Login> logins = new LinkedHashMap<>();
    final Map<String, GitHttpAuthenticator> authenticators = new LinkedHashMap<>();

    void forget(Collection<String> servers) {
      for (String server : servers) {
        GitLogins.forget(server);
        GitLogins.Login used = logins.get(server);
        if (used != null) SESSION.remove(server, used);
        GitHttpAuthenticator authenticator = authenticators.get(server);
        if (authenticator != null) authenticator.forgetPassword();
      }
    }
  }

  /** git 을 돌려 표준 출력을 돌려준다. 시간 제한 · 오류 문구는 SyncPanel.run 과 같다. ask 가 false 면(자동 fetch) 로그인 창을 띄우지 않는다. */
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
    try {
      return SyncPanel.gitWith(root, timeoutSec, List.copyOf(first.logins.values()), args);
    }
    catch (IOException e) {
      if (!SyncPanel.isGitAuthError(e.getMessage())) throw e;
      Collection<String> failed = failedServers(urls.keySet(), e.getMessage());
      first.forget(failed);
      if (!ask) throw e;
      Attempt second = dialogAttempt(project, root, urls, failed);
      if (second.logins.isEmpty()) throw e;
      List<GitLogins.Login> logins = new ArrayList<>(second.logins.values());
      for (Map.Entry<String, GitLogins.Login> entry : first.logins.entrySet()) {
        if (!failed.contains(entry.getKey())) logins.add(entry.getValue());
      }
      try {
        String output = SyncPanel.gitWith(root, timeoutSec, logins, args);
        for (GitHttpAuthenticator authenticator : second.authenticators.values()) authenticator.saveAuthData();
        return output;
      }
      catch (IOException retry) {
        if (SyncPanel.isGitAuthError(retry.getMessage())) second.forget(second.logins.keySet());
        throw retry;
      }
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
      if (GitLogins.hasSecret(server)) continue;
      GitLogins.Login session = SESSION.get(server);
      if (session != null) {
        attempt.logins.put(server, session);
        continue;
      }
      GitHttpAuthenticator silent = authenticator(project, root, entry.getValue(), AuthenticationMode.SILENT);
      GitLogins.Login login = ask(silent, server, entry.getValue());
      if (login != null) {
        attempt.logins.put(server, login);
        attempt.authenticators.put(server, silent);
      }
    }
    return attempt;
  }

  private static Attempt dialogAttempt(Project project, File root, Map<String, String> urls, Collection<String> servers) {
    Attempt attempt = new Attempt();
    for (String server : servers) {
      synchronized (DIALOG_LOCK) {
        GitLogins.Login session = SESSION.get(server);
        if (session != null) {
          attempt.logins.put(server, session);
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
        attempt.logins.put(server, login);
        attempt.authenticators.put(server, full);
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

  /** git 오류에 주소(host)가 적힌 서버들. 어느 서버도 적혀 있지 않으면 모두. */
  static List<String> failedServers(Collection<String> servers, String error) {
    String lower = error.toLowerCase(Locale.ROOT);
    List<String> named = new ArrayList<>();
    for (String server : servers) {
      if (lower.contains(server.substring(server.indexOf("://") + "://".length()))) named.add(server);
    }
    return named.isEmpty() ? new ArrayList<>(servers) : named;
  }
}
