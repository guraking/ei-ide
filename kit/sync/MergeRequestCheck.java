package kinggura.sync;

/** 자체 점검: GitLab 원격 주소 → 웹 주소, 새 Merge Request 주소, 권한 없음 문구, 단추를 붙일 브랜치. */
public class MergeRequestCheck {
  public static void main(String[] args) {
    eq(MergeRequest.webUrl("https://gitlab.example.com/pjt/axplm/ext/ext-front-app.git"), "https://gitlab.example.com/pjt/axplm/ext/ext-front-app");
    eq(MergeRequest.webUrl("https://user@gitlab.example.com:8443/g/r.git/"), "https://gitlab.example.com:8443/g/r");
    eq(MergeRequest.webUrl("https://oauth2:to@ken@host/g/r.git"), "https://host/g/r");
    eq(MergeRequest.webUrl("http://host/g/r"), "http://host/g/r");
    eq(MergeRequest.webUrl("ssh://git@host:2222/g/r.git"), "https://host/g/r");
    eq(MergeRequest.webUrl("git@host:g/sub/r.git"), "https://host/g/sub/r");
    eq(MergeRequest.webUrl("C:\\repos\\r"), null);
    eq(MergeRequest.webUrl("C:/repos/r"), null);
    eq(MergeRequest.webUrl("file:///c/repos/r.git"), null);
    eq(MergeRequest.webUrl("/srv/git/r.git"), null);
    eq(MergeRequest.webUrl(""), null);
    eq(MergeRequest.webUrl(null), null);
    eq(MergeRequest.newPage("https://h/g/r", "feature/gpm/dev.jhlim", "develop"),
       "https://h/g/r/-/merge_requests/new?merge_request%5Bsource_branch%5D=feature%2Fgpm%2Fdev.jhlim&merge_request%5Btarget_branch%5D=develop");
    eq(MergeRequest.newPage("https://h/g/r", "a b", null), "https://h/g/r/-/merge_requests/new?merge_request%5Bsource_branch%5D=a+b");
    eq(MergeRequest.noUploadRight("remote: You are not allowed to upload code.\nfatal: unable to access 'https://h/g/r.git/': The requested URL returned error: 403"), true);
    eq(MergeRequest.noUploadRight("fatal: Authentication failed"), false);
    eq(MergeRequest.noUploadRight(null), false);
    eq(MergeRequest.offersFor("feature/x", "develop"), true);
    eq(MergeRequest.offersFor("develop", "develop"), false);
    eq(MergeRequest.offersFor("main", "develop"), false);
    eq(MergeRequest.offersFor("master", "release"), false);
    System.out.println("MergeRequestCheck 통과");
  }

  private static void eq(Object actual, Object expected) {
    if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError("기대 " + expected + ", 실제 " + actual);
  }
}
