package git4idea.commands;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.externalProcessAuthHelper.AuthenticationMode;
import com.intellij.externalProcessAuthHelper.PassthroughAuthenticationGate;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.EnvironmentUtil;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * https 원격 명령에 인증을 git 환경값(`http.<서버>/.extraHeader`)으로 미리 넘긴다.
 * git 이 비밀번호 도우미(GIT_ASKPASS 스크립트 · sh.exe)를 띄우지 못하는 PC 에서도 Push · Pull · Fetch 가 되게 한다.
 *
 * 서버마다 처음 찾은 것 하나를 쓴다.
 * 1. 이번 IDE 실행에서 로그인 창에 넣은 값(동시에 도는 다른 명령과 나눠 쓴다. 실패할 때까지).
 * 2. 직전에 그 서버가 인증에 실패했으면 IntelliJ 로그인 창(handler 의 authenticator, 저장 · 지우기는 GitHandlerAuthenticationManager 가 한다).
 *    로그인 창은 한 번에 하나만 띄우고, 취소하면 그 서버는 CANCEL_QUIET_MS 동안 다시 묻지 않는다.
 * 3. 설정 → 버전 관리 → Git 로그인(kinggura-sync) 목록과 비밀번호 저장소의 값. 앞서 실패한 값과 같으면 건너뛴다(같은 틀린 값을 다시 보내지 않게).
 * 4. 조용한 출처: IntelliJ 가 기억한 비밀번호, GitLab · GitHub 계정 토큰(GitHttpAuthDataProvider 중 isSilent).
 * 아무것도 없으면 아무 값도 넣지 않고 git 의 원래 흐름(askpass)을 둔다.
 * 실패 줄이 보이면 onAuthFailed 를 불러 GitImplBase 가 한 번 다시 돌게 하고, 명령이 끝나면 실패한 서버의 1 · 4 값을 잊고 「다음에 묻기」로 둔다.
 * 3 의 값은 실패 줄에 그 서버 주소가 적혀 있을 때만 비밀번호 저장소에서 지우고, 아니면 이번 IDE 실행 동안 건너뛸 값으로만 기억한다.
 */
final class EiGitAuthEnv {
  private static final Logger LOG = Logger.getInstance(EiGitAuthEnv.class);

  /** 마지막 명령이 인증에 실패한 서버. 다음 명령에서 로그인 창을 먼저 띄운다. 로그인 창에서 값을 받으면 뺀다. */
  private static final Set<String> NEEDS_LOGIN = ConcurrentHashMap.newKeySet();
  /** 서버 → 로그인 창에서 받은 머리글. 동시에 도는 다른 명령이 창을 다시 띄우지 않고 쓴다. */
  private static final Map<String, String> SESSION = new ConcurrentHashMap<>();
  /** 서버 → 실패했지만 어느 서버 탓인지 몰라 지우지 않은 Git 로그인 표의 머리글. 표 값이 바뀌면 다시 쓴다. */
  private static final Map<String, String> REJECTED_LIST = new ConcurrentHashMap<>();
  /** 서버별 로그인 창 취소 시각. */
  private static final Map<String, Long> CANCELLED = new ConcurrentHashMap<>();
  /** 취소한 로그인 창을 다시 띄우지 않는 시간. 동기화 창(GitRemoteAuth)과 같다. */
  private static final long CANCEL_QUIET_MS = 60_000;
  /** 로그인 창을 하나씩 띄워, 앞 창의 값을 뒤 명령이 쓰게 한다. */
  private static final Object DIALOG_LOCK = new Object();

  private EiGitAuthEnv() {}

  /** 화면 스레드가 아닌 곳(git 명령을 띄우는 스레드)에서 부른다. 비밀번호 저장소 · 로그인 창을 쓸 수 있다. */
  static void prepare(@NotNull Project project,
                      @NotNull GitLineHandler handler,
                      @NotNull GitHttpAuthenticator authenticator,
                      @NotNull Runnable onAuthFailed) {
    AuthenticationMode mode = handler.getIgnoreAuthenticationMode();
    if (mode == AuthenticationMode.NONE) return;

    Map<String, String> headers = new LinkedHashMap<>();
    // 서버 → 비밀번호 저장소 키(목록에 저장한 주소).
    Map<String, String> fromList = new LinkedHashMap<>();
    Map<String, String> fromSession = new LinkedHashMap<>();
    Map<String, GitHttpAuthenticator> silentUsed = new LinkedHashMap<>();
    String list = PropertiesComponent.getInstance().getValue(EiGitAuthText.LIST_KEY, "");
    for (String url : handler.getUrls()) {
      String server = EiGitAuthText.serverOf(url);
      if (server == null || headers.containsKey(server)) continue;

      String shared = SESSION.get(server);
      if (shared == null && mode == AuthenticationMode.FULL && NEEDS_LOGIN.contains(server)) shared = askShared(authenticator, server, url);
      if (shared != null) {
        headers.put(server, shared);
        fromSession.put(server, shared);
        continue;
      }
      String[] login = EiGitAuthText.findLogin(list, server);
      String secret = login == null ? null : PasswordSafe.getInstance().getPassword(attributes(login[2]));
      if (secret != null && !secret.isEmpty()) {
        String header = EiGitAuthText.header(EiGitAuthText.userOf(login[0], login[1]), secret);
        if (!header.equals(REJECTED_LIST.get(server))) {
          headers.put(server, header);
          fromList.put(server, login[2]);
          continue;
        }
      }
      GitHttpAuthenticator silent = new GitHttpGuiAuthenticator(project, List.of(url), handler.getWorkingDirectory(),
                                                                PassthroughAuthenticationGate.getInstance(), AuthenticationMode.SILENT);
      String header = ask(silent, url);
      if (header != null) {
        headers.put(server, header);
        silentUsed.put(server, silent);
      }
    }

    Map<String, String> env = EiGitAuthText.env(headers, EnvironmentUtil.getEnvironmentMap());
    for (Map.Entry<String, String> entry : env.entrySet()) {
      handler.addCustomEnvironmentVariable(entry.getKey(), entry.getValue());
    }

    Set<String> servers = new LinkedHashSet<>();
    for (String url : handler.getUrls()) {
      String server = EiGitAuthText.serverOf(url);
      if (server != null) servers.add(server);
    }
    if (servers.isEmpty()) return;
    handler.addLineListener(new GitLineHandlerListener() {
      private volatile boolean myFailed;
      /** 실패 줄에 주소가 적힌 서버. */
      private final Set<String> myNamed = ConcurrentHashMap.newKeySet();

      @Override
      public void onLineAvailable(String line, Key outputType) {
        if (!EiGitAuthText.isAuthFailure(line)) return;
        for (String server : EiGitAuthText.serversIn(line)) {
          if (servers.contains(server)) myNamed.add(server);
        }
        if (myFailed) return;
        myFailed = true;
        onAuthFailed.run();
      }

      @Override
      public void processTerminated(int exitCode) {
        if (!myFailed) return;
        // 실패 줄에 주소가 없으면(원격이 여럿인 모든 원격 fetch 등) 어느 서버인지 몰라 모두 다음에 묻되, Git 로그인 표의 값은 지우지 않는다.
        boolean named = !myNamed.isEmpty();
        Set<String> failed = named ? myNamed : servers;
        LOG.info("EI Git login: auth failed for " + failed + (named ? "" : " (server not named)"));
        for (String server : failed) {
          String listKey = fromList.get(server);
          if (listKey != null) {
            if (named) PasswordSafe.getInstance().setPassword(attributes(listKey), null);
            else REJECTED_LIST.put(server, headers.get(server));
          }
          String shared = fromSession.get(server);
          if (shared != null) SESSION.remove(server, shared);
          GitHttpAuthenticator silent = silentUsed.get(server);
          if (silent != null) silent.forgetPassword();
          NEEDS_LOGIN.add(server);
        }
      }
    });
  }

  /**
   * 로그인 창으로 물어 받은 머리글을 SESSION 에 두고 돌려준다. 앞 명령이 이미 받아 두었으면 그 값.
   * 이 서버의 창을 CANCEL_QUIET_MS 안에 취소했거나 이번에 취소 · 빈 값이면 null.
   */
  private static String askShared(GitHttpAuthenticator authenticator, String server, String url) {
    synchronized (DIALOG_LOCK) {
      String shared = SESSION.get(server);
      if (shared != null) return shared;
      Long cancelled = CANCELLED.get(server);
      if (cancelled != null && System.currentTimeMillis() - cancelled < CANCEL_QUIET_MS) return null;
      String header = ask(authenticator, url);
      if (header == null) {
        if (authenticator.wasCancelled()) CANCELLED.put(server, System.currentTimeMillis());
        return null;
      }
      CANCELLED.remove(server);
      SESSION.put(server, header);
      NEEDS_LOGIN.remove(server);
      return header;
    }
  }

  /** authenticator 에 아이디 · 비밀번호를 물어 머리글로. 취소 · 빈 값이면 null. */
  private static String ask(GitHttpAuthenticator authenticator, String url) {
    String user = authenticator.askUsername(url);
    if (user == null) return null;
    String password = authenticator.askPassword(url);
    if (password == null || password.isEmpty()) return null;
    return EiGitAuthText.header(user, password);
  }

  private static CredentialAttributes attributes(String server) {
    return new CredentialAttributes(CredentialAttributesKt.generateServiceName(EiGitAuthText.SERVICE, server));
  }
}
