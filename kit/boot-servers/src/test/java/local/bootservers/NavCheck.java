package local.bootservers;

import java.util.List;

/** ViewPath · ConfigKeys 자체 점검. 실패하면 예외로 끝난다. */
public final class NavCheck {

    private static final String P = ViewPath.DEFAULT_PREFIX;
    private static final String S = ViewPath.DEFAULT_SUFFIX;

    public static void main(String[] args) {
        resolve();
        viewName();
        placeholder();
        yaml();
        properties();
        prefixSuffix();
        System.out.println("NavCheck 통과");
    }

    private static void resolve() {
        eq("/WEB-INF/views/order/list.jsp", ViewPath.resolve(P, S, "order/list"), "기본");
        eq("/WEB-INF/views/order/list.jsp", ViewPath.resolve(P, S, "/order/list"), "앞 / 겹침");
        eq("/WEB-INF/jsp/a.jsp", ViewPath.resolve("/WEB-INF/jsp", ".jsp", "/a"), "prefix 끝 / 없음");
        eq("/views/a.jsp", ViewPath.resolve("views/", ".jsp", "a"), "prefix 앞 / 없음");
        eq(null, ViewPath.resolve(P, S, "redirect:/order/list"), "redirect");
        eq("/WEB-INF/x.jsp", ViewPath.resolve(P, S, "forward:/WEB-INF/x.jsp"), "forward 파일");
        eq("/WEB-INF/x.jsp", ViewPath.resolve(P, S, "forward:/WEB-INF/x.jsp?a=1"), "forward 쿼리");
        eq(null, ViewPath.resolve(P, S, "forward:/order/list"), "forward URL");
        eq(null, ViewPath.resolve(P, S, " "), "빈 이름");
        eq("/a", ViewPath.resolve(null, null, "a"), "prefix · suffix 없음");
    }

    private static void viewName() {
        eq("order/list", ViewPath.viewName(P, S, "/WEB-INF/views/order/list.jsp"), "기본");
        eq("a", ViewPath.viewName("/WEB-INF/jsp", ".jsp", "/WEB-INF/jsp/a.jsp"), "prefix 끝 / 없음");
        eq(null, ViewPath.viewName(P, S, "/WEB-INF/jsp/a.jsp"), "다른 폴더");
        eq(null, ViewPath.viewName(P, ".html", "/WEB-INF/views/a.jsp"), "다른 확장자");
        eq(null, ViewPath.viewName(P, S, "/WEB-INF/views/.jsp"), "이름 없음");
    }

    private static void placeholder() {
        String v = "${a.b.c:default}";
        eq("a.b.c", ConfigKeys.placeholderKeyAt(v, 4), "기본값 앞");
        eq("a.b.c", ConfigKeys.placeholderKeyAt(v, 12), "기본값 위");
        eq("a.b.c", ConfigKeys.placeholderKeyAt("${a.b.c}", 0), "$ 위");
        eq(null, ConfigKeys.placeholderKeyAt("x ${a}", 0), "밖");
        eq("y", ConfigKeys.placeholderKeyAt("${x:${y}}", 6), "안쪽 자리표");
        eq("x", ConfigKeys.placeholderKeyAt("${x:${y}}", 2), "바깥 자리표");
        eq("a.b", ConfigKeys.placeholderKeyAt("#{'${a.b}'.split(',')}", 5), "SpEL 안");
        eq("b", ConfigKeys.placeholderKeyAt("${a}-${b}", 7), "둘째");
        eq(null, ConfigKeys.placeholderKeyAt("${a", 2), "닫히지 않음");
        eq(null, ConfigKeys.placeholderKeyAt("${}", 1), "빈 키");
        eq("a.b", ConfigKeys.placeholderKeyAt("${ a.b }", 3), "공백");
    }

    private static void yaml() {
        String y = String.join("\n",
                "# 맨 위 주석",
                "spring:",
                "  mvc:",
                "    view:",
                "      prefix: /WEB-INF/jsp/   # 끝 주석",
                "      suffix: \".jsp\"",
                "  datasource:",
                "    url: \"jdbc:oracle:thin:@x:1521/y#z\"",
                "app:",
                "  upload-dir: /data",
                "  \"quoted.key\": q",
                "  servers:",
                "    - name: a",
                "      port: 1",
                "    - name: b",
                "  note: |",
                "    fake: line",
                "  after: ok",
                "flat.key.name: f",
                "---",
                "spring:",
                "  config:",
                "    activate:",
                "      on-profile: dev",
                "app:",
                "  upload_dir: /dev");
        List<ConfigKeys.Hit> hits = ConfigKeys.find(y, "spring.mvc.view.prefix", true);
        eq(1, hits.size(), "중첩 키 개수");
        eq("/WEB-INF/jsp/", hits.get(0).value(), "주석 뗀 값");
        eq(y.indexOf("prefix:"), hits.get(0).offset(), "키 위치");
        eq(".jsp", ConfigKeys.find(y, "spring.mvc.view.suffix", true).get(0).value(), "따옴표 값");
        eq("jdbc:oracle:thin:@x:1521/y#z", ConfigKeys.find(y, "spring.datasource.url", true).get(0).value(), "따옴표 안 #");
        List<ConfigKeys.Hit> upload = ConfigKeys.find(y, "app.uploadDir", true);
        eq(2, upload.size(), "느슨한 바인딩 · 여러 문서");
        eq("/data", upload.get(0).value(), "첫 문서");
        eq("/dev", upload.get(1).value(), "둘째 문서 upload_dir");
        eq(y.indexOf("upload_dir"), upload.get(1).offset(), "둘째 문서 위치");
        eq("q", ConfigKeys.find(y, "app.quoted.key", true).get(0).value(), "따옴표 키");
        eq(2, ConfigKeys.find(y, "app.servers.name", true).size(), "목록 항목");
        eq(y.indexOf("port: 1"), ConfigKeys.find(y, "app.servers.port", true).get(0).offset(), "목록 항목 둘째 줄");
        eq(0, ConfigKeys.find(y, "app.note.fake", true).size(), "블록 글 안은 키 아님");
        eq("ok", ConfigKeys.find(y, "app.after", true).get(0).value(), "블록 글 뒤");
        eq("f", ConfigKeys.find(y, "flat.key.name", true).get(0).value(), "점 들어간 키");
        eq("", ConfigKeys.find(y, "spring.mvc", true).get(0).value(), "부모 키");
        eq(0, ConfigKeys.find(y, "spring.mvc.view.prefixx", true).size(), "없는 키");
        eq("dev", ConfigKeys.find(y.replace("\n", "\r\n"), "spring.config.activate.on-profile", true).get(0).value(), "CRLF");
    }

    private static void properties() {
        String p = String.join("\n",
                "# 주석 a.b=x",
                "! 주석",
                "spring.mvc.view.prefix=/WEB-INF/views/",
                "  spring.mvc.view.suffix : .jsp  ",
                "app.upload-dir /data",
                "long.value=a,\\",
                "  app.fake=no",
                "a\\:b=esc",
                "empty=");
        List<ConfigKeys.Hit> hits = ConfigKeys.find(p, "spring.mvc.view.prefix", false);
        eq("/WEB-INF/views/", hits.get(0).value(), "= 값");
        eq(p.indexOf("spring.mvc.view.prefix"), hits.get(0).offset(), "키 위치");
        eq(".jsp", ConfigKeys.find(p, "spring.mvc.view.suffix", false).get(0).value(), ": 값 · 앞뒤 공백");
        eq(p.indexOf("spring.mvc.view.suffix"), ConfigKeys.find(p, "spring.mvc.view.suffix", false).get(0).offset(), "들여쓴 키 위치");
        eq("/data", ConfigKeys.find(p, "APP.UPLOAD_DIR", false).get(0).value(), "공백 구분 · 느슨한 바인딩");
        eq(0, ConfigKeys.find(p, "app.fake", false).size(), "이어진 줄은 키 아님");
        eq(0, ConfigKeys.find(p, "a.b", false).size(), "주석은 키 아님");
        eq("esc", ConfigKeys.find(p, "a:b", false).get(0).value(), "\\: 키");
        eq("", ConfigKeys.find(p, "empty", false).get(0).value(), "빈 값");
    }

    private static void prefixSuffix() {
        String[] yml = ViewPath.fromConfig("spring:\n  mvc:\n    view:\n      prefix: /WEB-INF/jsp/\n", true);
        eq("/WEB-INF/jsp/", yml[0], "yml prefix");
        eq(null, yml[1], "yml suffix 없음");
        String[] props = ViewPath.fromConfig("spring.mvc.view.suffix=.jspx", false);
        eq(null, props[0], "properties prefix 없음");
        eq(".jspx", props[1], "properties suffix");

        String xml = "<beans>\n"
                + "<bean id=\"other\" class=\"x.Y\"><property name=\"prefix\" value=\"no\"/></bean>\n"
                + "<bean class=\"org.springframework.web.servlet.view.InternalResourceViewResolver\">\n"
                + "  <property name=\"prefix\" value=\"/WEB-INF/jsp/\"/>\n"
                + "  <property name='suffix' value='.jsp'/>\n"
                + "</bean>\n"
                + "<bean class=\"org.springframework.web.servlet.view.InternalResourceViewResolver\" p:prefix=\"/p/\" p:suffix=\".jsp\"/>\n"
                + "<mvc:view-resolvers><mvc:jsp prefix=\"/m/\" suffix=\".jsp\"/></mvc:view-resolvers>\n"
                + "</beans>";
        List<String[]> pairs = ViewPath.fromXml(xml);
        eq(3, pairs.size(), "xml 해석기 개수");
        eq("/WEB-INF/jsp/", pairs.get(0)[0], "property prefix");
        eq(".jsp", pairs.get(0)[1], "property suffix 홑따옴표");
        eq("/p/", pairs.get(1)[0], "p: prefix");
        eq("/m/", pairs.get(2)[0], "mvc:jsp prefix");
        eq(0, ViewPath.fromXml("<beans/>").size(), "해석기 없음");
    }

    private static void eq(Object expected, Object actual, String name) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
