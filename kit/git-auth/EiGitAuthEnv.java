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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * https 원격 명령에 인증을 git 환경값(`http.<서버>/.extraHeader`)으로 미리 넘긴다.
 * git 이 비밀번호 도우미(GIT_ASKPASS 스크립트 · sh.exe)를 띄우지 못하는 PC 에서도 Push · Pull · Fetch 가 되게 한다.
 *
 * 서버마다 처음 찾은 것 하나를 쓴다.
 * 1. 설정 → 버전 관리 → Git 로그인(kinggura-sync) 목록과 비밀번호 저장소의 값.
 * 2. 직전에 그 서버가 인증에 실패했으면 IntelliJ 로그인 창(handler 의 authenticator, 저장 · 지우기는 GitHandlerAuthenticationManager 가 한다).
 * 3. 조용한 출처: IntelliJ 가 기억한 비밀번호, GitLab · GitHub 계정 토큰(GitHttpAuthDataProvider 중 isSilent).
 * 아무것도 없으면 아무 값도 넣지 않고 git 의 원래 흐름(askpass)을 둔다.
 * 실패 줄이 보이면 1 · 3 의 값을 지우고 서버를 「다음에 묻기」로 두며 onAuthFailed 를 불러 GitImplBase 가 한 번 다시 돌게 한다.
 */
final class EiGitAuthEnv {
  private static final Logger LOG = Logger.getInstance(EiGitAuthEnv.class);

  /** 마지막 명령이 인증에 실패한 서버. 다음 명령에서 로그인 창을 먼저 띄운다. */
  private static final Set<String> NEEDS_LOGIN = ConcurrentHashMap.newKeySet();

  private EiGitAuthEnv() {}

  /** 화면 스레드가 아닌 곳(git 명령을 띄우는 스레드)에서 부른다. 비밀번호 저장소 · 로그인 창을 쓸 수 있다. */
  static void prepare(@NotNull Project project,
                      @NotNull GitLineHandler handler,
                      @NotNull GitHttpAuthenticator authenticator,
                      @NotNull Runnable onAuthFailed) {
    AuthenticationMode mode = handler.getIgnoreAuthenticationMode();
    if (mode == AuthenticationMode.NONE) return;

    Map<String, String> headers = new LinkedHashMap<>();
    List<String> fromList = new ArrayList<>();
    List<GitHttpAuthenticator> silentUsed = new ArrayList<>();
    String list = PropertiesComponent.getInstance().getValue(EiGitAuthText.LIST_KEY, "");
    for (String url : handler.getUrls()) {
      String server = EiGitAuthText.serverOf(url);
      if (server == null || headers.containsKey(server)) continue;

      String[] login = EiGitAuthText.findLogin(list, server);
      String secret = login == null ? null : PasswordSafe.getInstance().getPassword(attributes(server));
      if (secret != null && !secret.isEmpty()) {
        headers.put(server, EiGitAuthText.header(EiGitAuthText.userOf(login[0], login[1]), secret));
        fromList.add(server);
        continue;
      }
      if (mode == AuthenticationMode.FULL && NEEDS_LOGIN.remove(server)) {
        String header = ask(authenticator, url);
        if (header != null) headers.put(server, header);
        continue;
      }
      GitHttpAuthenticator silent = new GitHttpGuiAuthenticator(project, List.of(url), handler.getWorkingDirectory(),
                                                                PassthroughAuthenticationGate.getInstance(), AuthenticationMode.SILENT);
      String header = ask(silent, url);
      if (header != null) {
        headers.put(server, header);
        silentUsed.add(silent);
      }
    }

    Map<String, String> env = EiGitAuthText.env(headers, EnvironmentUtil.getEnvironmentMap());
    for (Map.Entry<String, String> entry : env.entrySet()) {
      handler.addCustomEnvironmentVariable(entry.getKey(), entry.getValue());
    }

    List<String> servers = new ArrayList<>();
    for (String url : handler.getUrls()) {
      String server = EiGitAuthText.serverOf(url);
      if (server != null) servers.add(server);
    }
    if (servers.isEmpty()) return;
    handler.addLineListener(new GitLineHandlerListener() {
      private volatile boolean myFailed;

      @Override
      public void onLineAvailable(String line, Key outputType) {
        if (myFailed || !EiGitAuthText.isAuthFailure(line)) return;
        myFailed = true;
        onAuthFailed.run();
      }

      @Override
      public void processTerminated(int exitCode) {
        if (!myFailed) return;
        // shortcut: 원격이 여럿인 명령(모든 원격 fetch)은 어느 서버가 실패했는지 가르지 않고 모두 다음에 묻는다.
        LOG.info("EI Git login: auth failed for " + servers);
        for (String server : fromList) {
          PasswordSafe.getInstance().setPassword(attributes(server), null);
        }
        for (GitHttpAuthenticator silent : silentUsed) {
          silent.forgetPassword();
        }
        NEEDS_LOGIN.addAll(servers);
      }
    });
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
