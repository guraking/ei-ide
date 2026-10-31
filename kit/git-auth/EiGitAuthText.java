package git4idea.commands;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EiGitAuthEnv 의 순수 부분(IntelliJ 클래스 없음). 점검은 kit/git-auth/EiGitAuthCheck.java.
 * 키 · 형식은 kinggura-sync 의 GitLogins 와 같아야 한다(본체는 플러그인 클래스를 볼 수 없어 따로 둔다).
 */
final class EiGitAuthText {
  /** 앱 PropertiesComponent 키. 줄마다 서버 \t 방식(PASSWORD|TOKEN) \t 아이디. */
  static final String LIST_KEY = "kinggura.sync.gitLogins";
  /** PasswordSafe 서비스 이름 접두. generateServiceName(SERVICE, 서버) 로 쓴다. */
  static final String SERVICE = "EI Git Login";
  static final String KIND_TOKEN = "TOKEN";
  /** 토큰 방식에서 아이디를 비웠을 때 Basic 아이디. GitLab 은 개인 토큰을 아무 아이디와 받고, GitHub 는 아이디를 보지 않는다. */
  static final String TOKEN_USER = "oauth2";
  private static final String FIELD_SEP = "\t";
  private static final String LINE_SEP = "\n";
  /** git 이 읽는 환경 설정 개수 · 키 · 값(git-config(1) ENVIRONMENT, git 2.31 이상). */
  static final String ENV_COUNT = "GIT_CONFIG_COUNT";
  private static final String ENV_KEY = "GIT_CONFIG_KEY_";
  private static final String ENV_VALUE = "GIT_CONFIG_VALUE_";
  /**
   * git 이 인증에 실패했거나 비밀번호 도우미를 띄우지 못했을 때 내는 줄(소문자로 비교).
   * git 의 한국어 번역은 이 문구들을 영어로 두지만, 번역이 바뀌거나 도우미(Git Credential Manager)가 한국어로 낼 때를 위해 한국어 문구도 본다.
   */
  private static final String[] FAILURE_LINES = {
    "authentication failed", "could not read username", "could not read password", "returned error: 401",
    "인증에 실패", "인증이 실패", "인증 실패"
  };
  /** 주소에 적어도 적지 않아도 같은 서버로 보는 기본 포트. */
  private static final int HTTPS_PORT = 443;
  private static final int HTTP_PORT = 80;
  /** git 출력 줄에서 서버 주소(scheme://host[:port]) 부분. 따옴표 · 한글 · 경로 앞에서 끊는다. */
  private static final Pattern SERVER_IN_TEXT = Pattern.compile("https?://[A-Za-z0-9._~%:@\\[\\]-]+", Pattern.CASE_INSENSITIVE);

  private EiGitAuthText() {}

  /**
   * 주소를 `scheme://host[:port]` 로 다듬는다(경로 · 아이디 · 끝 / · 기본 포트(https 443 · http 80)를 버리고 host 는 소문자).
   * https · http 가 아니거나 host 가 없으면 null(ssh · 파일 원격은 이 방식을 쓰지 않는다).
   */
  static String serverOf(String url) {
    if (url == null || !url.contains("://")) return null;
    URI uri;
    try {
      uri = new URI(url.trim());
    }
    catch (URISyntaxException e) {
      return null;
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null || uri.getHost().isEmpty()) return null;
    int port = uri.getPort();
    boolean defaultPort = port < 0 || port == (scheme.equals("https") ? HTTPS_PORT : HTTP_PORT);
    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
  }

  /**
   * 목록에서 server 줄의 {방식, 아이디, 저장한 주소}. 없거나 깨진 줄이면 null.
   * 예전에 기본 포트를 적어 저장한 줄(`https://host:443`)도 같은 서버로 본다. 비밀번호 저장소 키는 저장한 주소 그대로다.
   */
  static String[] findLogin(String list, String server) {
    if (list == null) return null;
    for (String line : list.split(LINE_SEP)) {
      String[] parts = line.split(FIELD_SEP, -1);
      if (parts.length == 3 && server.equals(serverOf(parts[0]))) return new String[]{parts[1], parts[2], parts[0]};
    }
    return null;
  }

  /** git 출력 한 줄에 주소가 적힌 서버들. 없으면 빈 집합. */
  static Set<String> serversIn(String line) {
    Set<String> servers = new LinkedHashSet<>();
    Matcher matcher = SERVER_IN_TEXT.matcher(line);
    while (matcher.find()) {
      String server = serverOf(matcher.group());
      if (server != null) servers.add(server);
    }
    return servers;
  }

  /** 목록 줄의 아이디. 토큰 방식에서 비었으면 TOKEN_USER. */
  static String userOf(String kind, String user) {
    String trimmed = user == null ? "" : user.trim();
    return trimmed.isEmpty() && KIND_TOKEN.equals(kind) ? TOKEN_USER : trimmed;
  }

  /** `Authorization: Basic base64(아이디:비밀번호)`. */
  static String header(String user, String secret) {
    String pair = user + ":" + secret;
    return "Authorization: Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 서버 → 머리글을 git 환경값으로. base 의 GIT_CONFIG_COUNT(숫자가 아니면 0) 뒤에 잇는다.
   * headers 가 비면 빈 맵.
   */
  static Map<String, String> env(Map<String, String> headers, Map<String, String> base) {
    Map<String, String> env = new LinkedHashMap<>();
    int start;
    try {
      start = Integer.parseInt(base.getOrDefault(ENV_COUNT, "0").trim());
    }
    catch (NumberFormatException e) {
      start = 0;
    }
    int index = start;
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      env.put(ENV_KEY + index, "http." + entry.getKey() + "/.extraHeader");
      env.put(ENV_VALUE + index, entry.getValue());
      index++;
    }
    if (index > start) env.put(ENV_COUNT, String.valueOf(index));
    return env;
  }

  /** git 출력 한 줄이 인증 실패 · 도우미 실패인지. */
  static boolean isAuthFailure(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    for (String failure : FAILURE_LINES) {
      if (lower.contains(failure)) return true;
    }
    return false;
  }
}
