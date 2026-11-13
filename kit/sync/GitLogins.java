package kinggura.sync;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 설정 → 버전 관리 → Git 로그인에 등록한 서버별 https 로그인.
 * git 이 비밀번호를 물으려고 도우미(sh.exe · askpass 스크립트 · Git Credential Manager)를 띄우지 않도록,
 * git 을 띄울 때 서버마다 `http.<서버>/.extraHeader = Authorization: Basic ...` 를 환경변수 GIT_CONFIG_PARAMETERS(`git -c` 와 같은 자리, 모든 판의 git 이 읽는다)로 넘긴다.
 * git 은 원격 주소와 맞는 서버의 값만 쓰고, 값은 명령줄 · 로그에 남지 않는다.
 *
 * 목록(서버 · 방식 · 아이디)은 앱 수준 PropertiesComponent, 비밀번호 · 토큰은 IDE 비밀번호 저장소(PasswordSafe)에 서버 주소로 둔다.
 * load · save · env 는 비밀번호 저장소를 열므로 화면 스레드에서 부르지 않는다.
 */
final class GitLogins {
  /** 비밀번호 · 토큰 중 무엇을 Basic 비밀번호 자리에 넣는지. 서버에는 둘 다 같은 Basic 인증으로 간다. */
  enum Kind { PASSWORD, TOKEN }

  /** server 는 serverOf 로 다듬은 `scheme://host[:port]`. user 는 토큰 방식이면 비어도 된다. secret 은 화면 · 저장 때만 채운다. */
  record Login(String server, Kind kind, String user, String secret) {}

  private static final String LIST_KEY = "kinggura.sync.gitLogins";
  private static final String SERVICE = "EI Git Login";
  /** 토큰 방식에서 아이디를 비웠을 때 Basic 아이디. GitLab 은 개인 토큰을 아무 아이디 · oauth2 와 함께 받고, GitHub 도 아이디를 보지 않는다. */
  private static final String TOKEN_USER = "oauth2";
  private static final String FIELD_SEP = "\t";
  private static final String LINE_SEP = "\n";
  /**
   * `git -c` 값을 하위 git 에 물려주는 환경값. 한 항목은 작은따옴표로 감싼 `'키=값'`, 항목 사이는 공백(git 2.31 미만은 이 모양만 읽는다).
   * GIT_CONFIG_COUNT 는 git 2.31 부터라 쓰지 않는다(둘 다 넣으면 새 git 이 머리글을 두 번 보낸다).
   */
  static final String ENV_PARAMETERS = "GIT_CONFIG_PARAMETERS";
  private static final String QUOTE = "'";
  private static final String QUOTE_ESCAPED = "'\\''";
  /** 주소에 적어도 적지 않아도 같은 서버로 보는 기본 포트. */
  private static final int HTTPS_PORT = 443;
  private static final int HTTP_PORT = 80;

  /** 마지막으로 읽은 로그인(비밀번호 포함). save · forget 때와, stored 가 읽은 비밀번호 저장소 값과 다를 때(다른 곳에서 지움 · 저장소가 잠겨 있다 열림) 비운다. */
  private static volatile List<Login> cachedLogins;
  /** 캐시를 비울 때마다 하나 올린다. 읽는 도중 비워졌으면 읽은 옛 값을 캐시에 넣지 않는다. */
  private static final java.util.concurrent.atomic.AtomicInteger VERSION = new java.util.concurrent.atomic.AtomicInteger();

  private GitLogins() {}

  /** 등록한 목록(비밀번호 · 토큰 없이, 저장소를 열지 않는다). */
  static List<Login> list() {
    return parse(PropertiesComponent.getInstance().getValue(LIST_KEY, ""));
  }

  /** 등록한 로그인이 하나라도 있는가(비밀번호 저장소를 열지 않는다). IDE 밖(점검 main)이면 false. */
  static boolean any() {
    return ApplicationManager.getApplication() != null && !list().isEmpty();
  }

  /** 등록한 로그인(비밀번호 · 토큰 포함). */
  static List<Login> load() {
    List<Login> result = new ArrayList<>();
    for (Login login : list()) {
      result.add(new Login(login.server(), login.kind(), login.user(), PasswordSafe.getInstance().getPassword(attributes(login.server()))));
    }
    return result;
  }

  /** 목록을 통째로 바꾼다. secret 이 null 인 줄은 저장된 비밀번호 · 토큰을 그대로 두고, 빠진 서버의 것은 지운다. */
  static void save(List<Login> logins) {
    for (Login old : list()) {
      if (!containsExactly(logins, old.server())) PasswordSafe.getInstance().setPassword(attributes(old.server()), null);
    }
    for (Login login : logins) {
      if (login.secret() != null) PasswordSafe.getInstance().setPassword(attributes(login.server()), login.secret());
    }
    PropertiesComponent.getInstance().setValue(LIST_KEY, format(logins), "");
    dropCache();
  }

  /** 비밀번호 저장소 키는 저장한 주소 그대로라 지울 때는 정확히 같은 주소만 남은 것으로 본다. */
  private static boolean containsExactly(List<Login> logins, String server) {
    for (Login login : logins) {
      if (login.server().equals(server)) return true;
    }
    return false;
  }

  /** 그 서버의 등록 로그인(비밀번호 · 토큰 포함). 등록이 없거나 값이 비었으면 null. 읽은 값이 env 캐시와 다르면 캐시를 비운다. */
  static Login stored(String server) {
    Login login = find(list(), server);
    String secret = login == null ? null : PasswordSafe.getInstance().getPassword(attributes(login.server()));
    Login result = secret == null || secret.isEmpty() ? null : new Login(login.server(), login.kind(), login.user(), secret);
    List<Login> cached = cachedLogins;
    if (login != null && cached != null) {
      Login before = find(cached, login.server());
      String old = before == null || before.secret() == null || before.secret().isEmpty() ? null : before.secret();
      if (!Objects.equals(old, result == null ? null : result.secret())) dropCache();
    }
    return result;
  }

  private static synchronized void dropCache() {
    VERSION.incrementAndGet();
    cachedLogins = null;
  }

  /** 인증에 실패한 값을 지운다. 목록 줄은 남겨 표에서 값만 다시 넣게 한다. 등록이 없는 서버면 아무 일도 안 한다. */
  static void forget(String server) {
    Login login = find(list(), server);
    if (login == null) return;
    PasswordSafe.getInstance().setPassword(attributes(login.server()), null);
    dropCache();
  }

  /**
   * git 을 띄울 때 더할 환경값. servers(serverOf 로 다듬은 주소)에 든 서버의 로그인만 넣는다. 등록이 없으면 빈 맵.
   * git 은 이 환경값을 훅(pre-push 등)에도 물려주므로, 그 명령이 닿지 않는 다른 서버의 비밀번호는 넘기지 않는다.
   * base 는 IDE 의 환경(이미 GIT_CONFIG_PARAMETERS 가 있으면 그 뒤에 잇는다).
   */
  static Map<String, String> env(java.util.Set<String> servers, Map<String, String> base) {
    // IDE 없이 도는 점검(SyncCheck)에서는 저장한 로그인이 없다.
    if (ApplicationManager.getApplication() == null || servers.isEmpty()) return Map.of();
    List<Login> logins = cachedLogins;
    if (logins == null) {
      int version = VERSION.get();
      logins = load();
      boolean any = list().isEmpty();
      for (Login login : logins) any |= login.secret() != null && !login.secret().isEmpty();
      // 등록이 있는데 값이 하나도 없으면 비밀번호 저장소가 잠겨 있었을 수 있어 다음 명령에서 다시 읽는다. 읽는 사이 비워졌으면 넣지 않는다.
      synchronized (GitLogins.class) {
        if (any && VERSION.get() == version) cachedLogins = logins;
      }
    }
    return env(matching(logins, servers), base);
  }

  /** logins 가운데 servers 에 든 서버의 것. */
  static List<Login> matching(List<Login> logins, java.util.Set<String> servers) {
    List<Login> result = new ArrayList<>();
    for (Login login : logins) {
      if (servers.contains(normalized(login.server()))) result.add(login);
    }
    return result;
  }

  /** git 원격 주소가 https · http 인가. 파일 경로(D:/git/x.git) · ssh(git@host:repo) 원격은 머리글 인증을 쓰지 않는다. */
  static boolean isHttpUrl(String url) {
    String lower = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
    return lower.startsWith("https://") || lower.startsWith("http://");
  }

  /** env 의 순수 부분. 비밀번호 · 토큰이 빈 로그인은 건너뛴다. 넣을 값이 없으면 빈 맵. */
  static Map<String, String> env(List<Login> logins, Map<String, String> base) {
    String before = base.getOrDefault(ENV_PARAMETERS, "").trim();
    StringBuilder params = new StringBuilder(before);
    for (Login login : logins) {
      if (login.secret() == null || login.secret().isEmpty()) continue;
      if (params.length() > 0) params.append(' ');
      params.append(quoted("http." + login.server() + "/.extraHeader=" + header(login)));
    }
    return params.length() == before.length() ? Map.of() : Map.of(ENV_PARAMETERS, params.toString());
  }

  /** git 의 sq_quote 모양: 작은따옴표로 감싸고 안의 작은따옴표는 '\'' 로. */
  static String quoted(String text) {
    return QUOTE + text.replace(QUOTE, QUOTE_ESCAPED) + QUOTE;
  }

  /** `Authorization: Basic base64(아이디:비밀번호)`. 토큰 방식에서 아이디가 비면 TOKEN_USER. */
  static String header(Login login) {
    String user = login.user() == null || login.user().isBlank() ? (login.kind() == Kind.TOKEN ? TOKEN_USER : "") : login.user().trim();
    String pair = user + ":" + login.secret();
    return "Authorization: Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 주소를 `https://host[:port]` 로 다듬는다. 경로 · 아이디 · 끝 / · 기본 포트(https 443 · http 80)는 버리고 host 는 소문자.
   * https · http 가 아니거나 host 가 없으면 IllegalArgumentException(ssh 원격은 이 방식을 쓰지 않는다).
   */
  static String serverOf(String url) {
    String text = url == null ? "" : url.trim();
    if (!text.contains("://")) text = "https://" + text;
    URI uri;
    try {
      uri = new URI(text);
    }
    catch (URISyntaxException e) {
      throw new IllegalArgumentException(SyncBundle.message("gitlogin.error.server"), e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null || uri.getHost().isEmpty()) {
      throw new IllegalArgumentException(SyncBundle.message("gitlogin.error.server"));
    }
    int port = uri.getPort();
    boolean defaultPort = port < 0 || port == (scheme.equals("https") ? HTTPS_PORT : HTTP_PORT);
    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
  }

  /** server 와 같은 서버의 줄. 예전에 기본 포트를 적어 저장한 줄(`https://host:443`)도 같은 서버로 본다. */
  static Login find(List<Login> logins, String server) {
    String wanted = normalized(server);
    for (Login login : logins) {
      if (normalized(login.server()).equals(wanted)) return login;
    }
    return null;
  }

  private static String normalized(String server) {
    try {
      return serverOf(server);
    }
    catch (IllegalArgumentException e) {
      return server;
    }
  }

  /** 한 줄에 서버 · 방식 · 아이디(탭으로 나눔). 깨진 줄은 건너뛴다. */
  static List<Login> parse(String text) {
    List<Login> result = new ArrayList<>();
    for (String line : text.split(LINE_SEP)) {
      String[] parts = line.split(FIELD_SEP, -1);
      if (parts.length != 3 || parts[0].isEmpty()) continue;
      try {
        result.add(new Login(parts[0], Kind.valueOf(parts[1]), parts[2], null));
      }
      catch (IllegalArgumentException e) {
        // 모르는 방식 이름: 그 줄만 버린다.
      }
    }
    return result;
  }

  static String format(List<Login> logins) {
    StringBuilder text = new StringBuilder();
    for (Login login : logins) {
      if (text.length() > 0) text.append(LINE_SEP);
      text.append(login.server()).append(FIELD_SEP).append(login.kind().name()).append(FIELD_SEP).append(login.user() == null ? "" : login.user().trim());
    }
    return text.toString();
  }

  private static CredentialAttributes attributes(String server) {
    return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SERVICE, server));
  }
}
