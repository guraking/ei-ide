package git4idea.commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** EiGitAuthText 자체 점검. 실패하면 예외로 끝난다. */
public final class EiGitAuthCheck {
  public static void main(String[] args) {
    eq(EiGitAuthText.serverOf("https://User@Git.Corp.com:8443/grp/app.git"), "https://git.corp.com:8443");
    eq(EiGitAuthText.serverOf("http://git.corp.com/app"), "http://git.corp.com");
    eq(EiGitAuthText.serverOf("git@git.corp.com:grp/app.git"), null);
    eq(EiGitAuthText.serverOf("ssh://git@git.corp.com/app"), null);
    eq(EiGitAuthText.serverOf("https://"), null);
    eq(EiGitAuthText.serverOf("https://git.corp.com:443/app/"), "https://git.corp.com");
    eq(EiGitAuthText.serverOf("http://git.corp.com:80"), "http://git.corp.com");
    eq(EiGitAuthText.serverOf("http://git.corp.com:443"), "http://git.corp.com:443");

    String list = "https://a.com\tPASSWORD\tkim\nbroken\nhttps://b.com\tTOKEN\t\nhttps://d.com:443\tTOKEN\tlee";
    eq(String.join(",", EiGitAuthText.findLogin(list, "https://a.com")), "PASSWORD,kim,https://a.com");
    eq(String.join(",", EiGitAuthText.findLogin(list, "https://b.com")), "TOKEN,,https://b.com");
    eq(EiGitAuthText.findLogin(list, "https://c.com"), null);
    // 기본 포트를 적어 저장한 예전 줄도 찾고, 비밀번호 저장소 키는 저장한 주소 그대로.
    eq(String.join(",", EiGitAuthText.findLogin(list, "https://d.com")), "TOKEN,lee,https://d.com:443");
    eq(EiGitAuthText.findLogin(list, "https://a.com:8443"), null);
    eq(EiGitAuthText.userOf("TOKEN", " "), "oauth2");
    eq(EiGitAuthText.userOf("PASSWORD", ""), "");
    eq(EiGitAuthText.userOf("TOKEN", " lee "), "lee");

    // base64("kim:pw") = a2ltOnB3
    eq(EiGitAuthText.header("kim", "pw"), "Authorization: Basic a2ltOnB3");

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("https://a.com", "H1");
    headers.put("https://b.com", "H2");
    Map<String, String> env = EiGitAuthText.env(headers, Map.of("GIT_CONFIG_COUNT", "2"));
    eq(env.get("GIT_CONFIG_KEY_2"), "http.https://a.com/.extraHeader");
    eq(env.get("GIT_CONFIG_VALUE_3"), "H2");
    eq(env.get("GIT_CONFIG_COUNT"), "4");
    eq(EiGitAuthText.env(headers, Map.of("GIT_CONFIG_COUNT", "x")).get("GIT_CONFIG_COUNT"), "2");
    eq(EiGitAuthText.env(Map.of(), Map.of()), Map.of());

    eq(EiGitAuthText.isAuthFailure("fatal: Authentication failed for 'https://a.com/x.git/'"), true);
    eq(EiGitAuthText.isAuthFailure("fatal: could not read Username for 'https://a.com': No such device or address"), true);
    eq(EiGitAuthText.isAuthFailure("fatal: unable to access 'https://a.com/x.git/': The requested URL returned error: 401"), true);
    eq(EiGitAuthText.isAuthFailure("Everything up-to-date"), false);
    eq(EiGitAuthText.isAuthFailure("fatal: 'https://a.com/x.git/'에 대한 인증이 실패했습니다"), true);
    eq(EiGitAuthText.isAuthFailure("remote: 인증에 실패했습니다"), true);
    eq(EiGitAuthText.isAuthFailure("푸시 인증서 서명에 실패했습니다"), false);

    // 실패 줄의 서버는 scheme · host · port 가 모두 같아야 한다(b.a.com 실패가 a.com 이 되지 않음).
    eq(EiGitAuthText.serversIn("fatal: Authentication failed for 'https://Kim@B.A.com/x.git/'"), java.util.Set.of("https://b.a.com"));
    eq(EiGitAuthText.serversIn("fatal: unable to access 'https://a.com:443/x/': The requested URL returned error: 401"), java.util.Set.of("https://a.com"));
    eq(EiGitAuthText.serversIn("fatal: could not read Username: terminal prompts disabled"), java.util.Set.of());
    System.out.println("EiGitAuthCheck OK");
  }

  private static void eq(Object actual, Object expected) {
    if (!Objects.equals(actual, expected)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
  }
}
