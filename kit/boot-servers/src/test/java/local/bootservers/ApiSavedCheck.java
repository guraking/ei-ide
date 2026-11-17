package local.bootservers;

import java.util.List;

/** ApiSaved 자체 점검. 실패하면 예외로 끝난다. */
public final class ApiSavedCheck {

    public static void main(String[] args) {
        secrets();
        merge();
        scrub();
        body();
        verdict();
        System.out.println("ApiSavedCheck 통과");
    }

    private static void secrets() {
        check(ApiSaved.isSecretHeader(" authorization "), "Authorization 은 비밀");
        check(ApiSaved.isSecretHeader("Cookie") && ApiSaved.isSecretHeader("Proxy-Authorization"), "Cookie · Proxy-Authorization 은 비밀");
        check(!ApiSaved.isSecretHeader("Accept") && !ApiSaved.isSecretHeader(null), "다른 헤더 · null 은 비밀 아님");
        ApiSaved.Scrubbed s = ApiSaved.scrub(List.of(), List.of(new ApiSaved.Line("Accept", "*/*", true),
                new ApiSaved.Line("authorization", "Bearer x", true), new ApiSaved.Line(" ", "v", true), new ApiSaved.Line("X-A", "1", false),
                new ApiSaved.Line("X-Auth-Token", "tok", true)), "http://h/a", "");
        List<ApiSaved.Line> kept = s.headers();
        check(kept.size() == 3 && kept.get(0).name().equals("Accept") && !kept.get(1).on(), "Authorization · 빈 이름만 빠지고 체크 상태는 남는다: " + kept);
        check(kept.get(2).value().isEmpty() && "tok".equals(s.secrets().get(ApiSaved.HEADER + "x-auth-token")) && s.droppedHeader(),
                "비밀 이름 헤더는 값만 비우고 비밀로 옮긴다: " + s);
        check(!ApiSaved.changesData("get") && !ApiSaved.changesData("HEAD") && !ApiSaved.changesData("OPTIONS"), "GET · HEAD · OPTIONS 는 안전");
        check(ApiSaved.changesData("POST") && ApiSaved.changesData("DELETE") && ApiSaved.changesData(null), "그 밖은 데이터를 바꿀 수 있음");
    }

    private static void merge() {
        List<ApiSaved.Line> fresh = List.of(new ApiSaved.Line("page", "1", true), new ApiSaved.Line("newParam", "a", true));
        List<ApiSaved.Line> saved = List.of(new ApiSaved.Line("PAGE", "7", false), new ApiSaved.Line("mine", "x", true));
        List<ApiSaved.Line> out = ApiSaved.merge(fresh, saved);
        check(out.size() == 3, "코드 줄 둘 + 저장에만 있는 줄 하나: " + out);
        check(out.get(0).name().equals("page") && out.get(0).value().equals("7") && !out.get(0).on(), "같은 이름은 저장 값 · 체크, 이름은 코드 것");
        check(out.get(1).value().equals("a") && out.get(1).on(), "저장에 없는 코드 줄은 시험 값 그대로");
        check(out.get(2).name().equals("mine"), "사용자가 더한 줄은 뒤에");
        check(ApiSaved.merge(fresh, List.of()).equals(fresh), "저장이 비면 코드 줄 그대로");
    }

    private static void scrub() {
        List<ApiSaved.Line> query = List.of(new ApiSaved.Line("userPwd", "p@ss", true), new ApiSaved.Line("page", "1", false),
                new ApiSaved.Line("emptyToken", "", true));
        String body = "{\"userId\":\"kim\", \"password\" : \"a\\\"b$1\", \"accessToken\":\"t\", \"n\":1}";
        String url = "http://h/x?access_token=abc&page=2&apikey=k#frag";
        ApiSaved.Scrubbed s = ApiSaved.scrub(query, List.of(), url, body);
        check(s.query().get(0).value().isEmpty() && s.query().get(0).on() && s.query().get(1).value().equals("1"), "비밀 이름 파라미터 값만 비운다: " + s.query());
        check(!s.secrets().containsKey(ApiSaved.QUERY + "emptytoken"), "빈 비밀은 옮기지 않는다");
        check(s.body().equals("{\"userId\":\"kim\", \"password\" : \"\", \"accessToken\":\"\", \"n\":1}"), "JSON 비밀 값만 비운다: " + s.body());
        check(s.url().equals("http://h/x?access_token=&page=2&apikey=#frag"), "주소 쿼리 비밀 값만 비운다: " + s.url());
        check(!s.droppedHeader(), "뺀 헤더 없음");
        // 되살리기
        check(ApiSaved.restore(s.body(), ApiSaved.BODY, s.secrets()).equals(body), "본문 되살림: " + ApiSaved.restore(s.body(), ApiSaved.BODY, s.secrets()));
        check(ApiSaved.restore(s.url(), ApiSaved.URL, s.secrets()).equals(url), "주소 되살림");
        check(ApiSaved.restore(s.query(), ApiSaved.QUERY, s.secrets()).get(0).value().equals("p@ss"), "쿼리 되살림");
        check(ApiSaved.restore(s.body(), ApiSaved.BODY, java.util.Map.of()).equals(s.body()), "비밀이 없으면 그대로");
        ApiSaved.Scrubbed form = ApiSaved.scrub(List.of(), List.of(), "", "id=kim&passwd=1%262&x=");
        check(form.body().equals("id=kim&passwd=&x=") && ApiSaved.restore(form.body(), ApiSaved.BODY, form.secrets()).equals("id=kim&passwd=1%262&x="),
                "폼 비밀 값 비우고 되살림: " + form.body());
        ApiSaved.Scrubbed plain = ApiSaved.scrub(List.of(), List.of(), "http://h/a", "plain text");
        check(plain.body().equals("plain text") && plain.url().equals("http://h/a") && plain.secrets().isEmpty(), "그 밖은 그대로");
        check(ApiSaved.scrub(List.of(), List.of(), null, null).body() == null, "null 본문");
        check(ApiSaved.withoutQuery("http://h/a?token=1").equals("http://h/a") && ApiSaved.withoutQuery("http://h/a").equals("http://h/a"), "key 에서 쿼리 떼기");
        // 비밀을 되살리지 못한 줄은 합칠 때 코드 값을 둔다.
        List<ApiSaved.Line> merged = ApiSaved.merge(List.of(new ApiSaved.Line("password", "test1234", true)), List.of(new ApiSaved.Line("password", "", false)));
        check(merged.get(0).value().equals("test1234") && !merged.get(0).on(), "빈 비밀은 코드 값, 체크는 저장한 것: " + merged);
        // 같은 이름 여러 줄은 차례대로 짝짓는다.
        List<ApiSaved.Line> ids = ApiSaved.merge(List.of(new ApiSaved.Line("ids", "1", true)),
                List.of(new ApiSaved.Line("ids", "7", true), new ApiSaved.Line("ids", "8", true)));
        check(ids.size() == 2 && ids.get(0).value().equals("7") && ids.get(1).value().equals("8"), "같은 이름 둘째 줄도 남는다: " + ids);
    }

    private static void body() {
        check(ApiSaved.cap(null).isEmpty(), "null 본문은 빈 글");
        String big = "x".repeat(ApiSaved.BODY_LIMIT + 5);
        check(ApiSaved.cap(big).equals("x".repeat(ApiSaved.BODY_LIMIT) + ApiSaved.CUT_NOTE), "상한을 넘으면 앞부분 + 표시");
        check(ApiSaved.cap("abc").equals("abc"), "짧으면 그대로");
        check(ApiSaved.fingerprint("{\"a\":1,\"b\":[1,2]}").equals(ApiSaved.fingerprint("{ \"a\" : 1,\n \"b\": [1, 2] }")), "JSON 은 띄어쓰기 상관없이 같음");
        check(!ApiSaved.fingerprint("{\"a\":1,\"b\":2}").equals(ApiSaved.fingerprint("{\"b\":2,\"a\":1}")), "JSON 순서가 바뀌면 다름");
        check(ApiSaved.fingerprint("a\r\nb").equals(ApiSaved.fingerprint("a\nb")), "줄 끝은 맞춘다");
        check(!ApiSaved.fingerprint("<p>1</p>").equals(ApiSaved.fingerprint("<p>2</p>")), "HTML 은 글자 그대로 견준다");
        check(ApiSaved.fingerprint(big).equals(ApiSaved.fingerprint(big)) && !ApiSaved.fingerprint(big).equals(ApiSaved.fingerprint(big + "y")),
                "큰 본문도 전체로 견준다");
    }

    private static void verdict() {
        check(ApiSaved.verdict("끊김", 200, -1, "a", "") == ApiSaved.Verdict.FAILED, "오류면 실패");
        check(ApiSaved.verdict(null, ApiSaved.NO_CODE, 200, "", "a") == ApiSaved.Verdict.NO_BASELINE, "기준 없음");
        check(ApiSaved.verdict(null, 200, 500, "a", "a") == ApiSaved.Verdict.STATUS, "상태 바뀜");
        check(ApiSaved.verdict(null, 200, 200, "a", "b") == ApiSaved.Verdict.BODY, "본문 바뀜");
        check(ApiSaved.verdict(null, 200, 200, "a", "a") == ApiSaved.Verdict.SAME, "같음");
        check(ApiSaved.verdict(null, 200, 200, null, "a") == ApiSaved.Verdict.BODY, "지문이 없으면 같다고 하지 않는다");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException("ApiSavedCheck 실패: " + what);
        }
    }
}
