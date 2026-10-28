package kinggura.sync;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 설정 → 버전 관리 → Git 로그인에 등록한 서버별 https 로그인.
 * git 이 비밀번호를 물으려고 도우미(sh.exe · askpass 스크립트 · Git Credential Manager)를 띄우지 않도록,
 * git 을 띄울 때 서버마다 `http.<서버>/.extraHeader = Authorization: Basic ...` 를 환경변수(GIT_CONFIG_COUNT, git 2.31 이상)로 넘긴다.
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
  /** git 이 읽는 환경 설정 개수 · 키 · 값(git-config(1) ENVIRONMENT). */
  private static final String ENV_COUNT = "GIT_CONFIG_COUNT";
  private static final String ENV_KEY = "GIT_CONFIG_KEY_";
  private static final String ENV_VALUE = "GIT_CONFIG_VALUE_";

  /** 마지막으로 만든 환경값. save 때 비운다. */
  private static volatile Map<String, String> cachedEnv;

  private GitLogins() {}

  /** 등록한 목록(비밀번호 · 토큰 없이, 저장소를 열지 않는다). */
  static List<Login> list() {
    return parse(PropertiesComponent.getInstance().getValue(LIST_KEY, ""));
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
      if (find(logins, old.server()) == null) PasswordSafe.getInstance().setPassword(attributes(old.server()), null);
    }
    for (Login login : logins) {
      if (login.secret() != null) PasswordSafe.getInstance().setPassword(attributes(login.server()), login.secret());
    }
    PropertiesComponent.getInstance().setValue(LIST_KEY, format(logins), "");
    cachedEnv = null;
  }

  /** git 을 띄울 때 더할 환경값. 등록이 없으면 빈 맵. base 는 IDE 의 환경(이미 GIT_CONFIG_COUNT 가 있으면 그 뒤에 잇는다). */
  static Map<String, String> env(Map<String, String> base) {
    Map<String, String> env = cachedEnv;
    if (env == null) {
      env = env(load(), base);
      cachedEnv = env;
    }
    return env;
  }

  /** env 의 순수 부분. 비밀번호 · 토큰이 빈 로그인은 건너뛴다. */
  static Map<String, String> env(List<Login> logins, Map<String, String> base) {
    Map<String, String> env = new LinkedHashMap<>();
    int start;
    try {
      start = Integer.parseInt(base.getOrDefault(ENV_COUNT, "0").trim());
    }
    catch (NumberFormatException e) {
      start = 0;
    }
    int index = start;
    for (Login login : logins) {
      if (login.secret() == null || login.secret().isEmpty()) continue;
      env.put(ENV_KEY + index, "http." + login.server() + "/.extraHeader");
      env.put(ENV_VALUE + index, header(login));
      index++;
    }
    if (index > start) env.put(ENV_COUNT, String.valueOf(index));
    return env;
  }

  /** `Authorization: Basic base64(아이디:비밀번호)`. 토큰 방식에서 아이디가 비면 TOKEN_USER. */
  static String header(Login login) {
    String user = login.user() == null || login.user().isBlank() ? (login.kind() == Kind.TOKEN ? TOKEN_USER : "") : login.user().trim();
    String pair = user + ":" + login.secret();
    return "Authorization: Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 주소를 `https://host[:port]` 로 다듬는다. 경로 · 아이디 · 끝 / 는 버리고 host 는 소문자.
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
    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
  }

  static Login find(List<Login> logins, String server) {
    for (Login login : logins) {
      if (login.server().equals(server)) return login;
    }
    return null;
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
