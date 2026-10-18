package local.bootservers;

/** UrlPattern 자체 점검. 실패하면 예외로 끝난다. */
public final class UrlPatternCheck {

    public static void main(String[] args) {
        join();
        matches();
        accepts();
        System.out.println("UrlPatternCheck 통과");
    }

    private static void join() {
        eq("/api/order/list", UrlPattern.join("/api/order", "/list"), "기본");
        eq("/api/order/list", UrlPattern.join("api/order/", "list"), "앞뒤 / 없음 · 겹침");
        eq("/api/order", UrlPattern.join("/api/order", ""), "메서드 경로 없음");
        eq("/list", UrlPattern.join("", "/list"), "클래스 경로 없음");
        eq("/", UrlPattern.join("", ""), "둘 다 없음");
        eq("/", UrlPattern.join(null, null), "null");
        eq("/a/b", UrlPattern.join(" /a// ", "//b/"), "공백 · 겹친 /");
        eq("/order/list.do", UrlPattern.join("/order", "list.do"), "확장자");
    }

    private static void matches() {
        yes(UrlPattern.matches("/api/order/{id}", "/api/order/123"), "변수");
        yes(!UrlPattern.matches("/api/order/{id}", "/api/order"), "변수 칸 빠짐");
        yes(!UrlPattern.matches("/api/order/{id}", "/api/order/1/2"), "칸 남음");
        yes(UrlPattern.matches("/api/order/{id}", "/api/order/123?x=1#top"), "쿼리 · 조각 뗌");
        yes(UrlPattern.matches("/api/order/{id}", "/api/order/123/"), "끝 /");
        yes(UrlPattern.matches("/api/{kind}/{id:\\d{3}}", "/api/order/123"), "정규식 변수 안 중괄호");
        yes(UrlPattern.matches("/file/{name}.json", "/file/a.json"), "변수 + 글자");
        yes(!UrlPattern.matches("/file/{name}.json", "/file/a.xml"), "글자 다름");
        yes(UrlPattern.matches("/api/*/list", "/api/order/list"), "별 하나");
        yes(!UrlPattern.matches("/api/*/list", "/api/a/b/list"), "별 하나는 한 칸");
        yes(UrlPattern.matches("/api/**", "/api"), "별 둘 0칸");
        yes(UrlPattern.matches("/api/**", "/api/a/b/c"), "별 둘 여러 칸");
        yes(UrlPattern.matches("/api/**/list", "/api/a/b/list"), "별 둘 가운데");
        yes(UrlPattern.matches("/*.do", "/order.do"), "별 + 확장자");
        yes(!UrlPattern.matches("/Api/order", "/api/order"), "대소문자 가림");
        yes(UrlPattern.matches("/a.b(c)", "/a.b(c)"), "정규식 글자는 그대로");
        yes(!UrlPattern.matches("/a.b", "/axb"), "점은 글자");
        yes(UrlPattern.matches("/", "/"), "뿌리");
        yes(UrlPattern.matches("/a/{broken", "/a/{broken"), "닫히지 않은 중괄호는 글자");
    }

    private static void accepts() {
        String shown = "GET /api/order/{id} OrderController.detail";
        yes(UrlPattern.accepts("/api/order/{id}", shown, ""), "빈 검색어");
        yes(UrlPattern.accepts("/api/order/{id}", shown, "ordercontroller"), "글 포함 대소문자 없이");
        yes(UrlPattern.accepts("/api/order/{id}", shown, "/api/order/123"), "실제 URL");
        yes(!UrlPattern.accepts("/api/order/{id}", shown, "/api/user/1"), "다른 URL");
        yes(!UrlPattern.accepts("/api/order/{id}", shown, "123"), "/ 없는 숫자는 글 비교만");
    }

    private static void eq(Object expected, Object actual, String name) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }

    private static void yes(boolean ok, String name) {
        if (!ok) {
            throw new AssertionError(name);
        }
    }
}
