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
        List<ApiSaved.Line> kept = ApiSaved.savableHeaders(List.of(new ApiSaved.Line("Accept", "*/*", true),
                new ApiSaved.Line("authorization", "Bearer x", true), new ApiSaved.Line(" ", "v", true), new ApiSaved.Line("X-A", "1", false)));
        check(kept.size() == 2 && kept.get(0).name().equals("Accept") && !kept.get(1).on(), "비밀 · 빈 이름만 빠지고 체크 상태는 남는다: " + kept);
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
        List<ApiSaved.Line> q = ApiSaved.savableQuery(List.of(new ApiSaved.Line("userPwd", "p@ss", true), new ApiSaved.Line("page", "1", false)));
        check(q.get(0).value().isEmpty() && q.get(0).on() && q.get(1).value().equals("1"), "비밀 이름 파라미터 값만 비운다: " + q);
        String json = ApiSaved.savableBody("{\"userId\":\"kim\", \"password\" : \"a\\\"b\", \"accessToken\":\"t\", \"n\":1}");
        check(json.equals("{\"userId\":\"kim\", \"password\" : \"\", \"accessToken\":\"\", \"n\":1}"), "JSON 비밀 값만 비운다: " + json);
        String form = ApiSaved.savableBody("id=kim&passwd=1%262&x=");
        check(form.equals("id=kim&passwd=&x="), "폼 비밀 값만 비운다: " + form);
        check(ApiSaved.savableBody("plain text") .equals("plain text") && ApiSaved.savableBody(null) == null, "그 밖은 그대로");
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
