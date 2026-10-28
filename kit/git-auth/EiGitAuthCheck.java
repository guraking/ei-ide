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

    String list = "https://a.com\tPASSWORD\tkim\nbroken\nhttps://b.com\tTOKEN\t";
    eq(String.join(",", EiGitAuthText.findLogin(list, "https://a.com")), "PASSWORD,kim");
    eq(String.join(",", EiGitAuthText.findLogin(list, "https://b.com")), "TOKEN,");
    eq(EiGitAuthText.findLogin(list, "https://c.com"), null);
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
    System.out.println("EiGitAuthCheck OK");
  }

  private static void eq(Object actual, Object expected) {
    if (!Objects.equals(actual, expected)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
  }
}
