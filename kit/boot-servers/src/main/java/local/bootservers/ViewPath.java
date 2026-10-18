package local.bootservers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스프링 MVC 뷰 이름 → 웹 뿌리 기준 JSP 경로 계산과, 설정 글에서 prefix · suffix 읽기.
 * 인텔리제이 클래스를 쓰지 않는다(NavCheck 로 점검).
 */
public final class ViewPath {

    public static final String DEFAULT_PREFIX = "/WEB-INF/views/";
    public static final String DEFAULT_SUFFIX = ".jsp";
    public static final String PREFIX_KEY = "spring.mvc.view.prefix";
    public static final String SUFFIX_KEY = "spring.mvc.view.suffix";

    private static final String REDIRECT = "redirect:";
    private static final String FORWARD = "forward:";
    private static final String SLASH = "/";
    private static final String QUERY = "?";
    private static final String ATTR_PREFIX = "prefix";

    /** InternalResourceViewResolver 빈 하나(여는 태그부터 닫는 태그 또는 /> 까지). */
    private static final Pattern RESOLVER_BEAN = Pattern.compile(
            "<bean\\b[^>]*InternalResourceViewResolver[^>]*?(/>|>.*?</bean>)", Pattern.DOTALL);
    /** <property name="prefix" value="..."/> */
    private static final Pattern PROPERTY = Pattern.compile(
            "<property\\s+name\\s*=\\s*[\"'](prefix|suffix)[\"']\\s+value\\s*=\\s*[\"']([^\"']*)[\"']");
    /** p:prefix="..." 와 <mvc:jsp prefix="..." suffix="..."/> 의 속성. */
    private static final Pattern ATTRIBUTE = Pattern.compile("(?:\\s|p:)(prefix|suffix)\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern MVC_JSP = Pattern.compile("<mvc:jsp\\b[^>]*>");

    private ViewPath() {
    }

    /**
     * 뷰 이름이 가리키는 웹 뿌리 기준 경로(「/」 로 시작, 겹친 「/」 는 하나로).
     * redirect: 는 파일이 아니므로 null. forward: 는 뒤 경로가 그대로 파일 경로일 때(뒤가 suffix 로 끝남)만 그 경로, 아니면 null.
     */
    public static String resolve(String prefix, String suffix, String viewName) {
        if (viewName == null || viewName.isBlank() || viewName.startsWith(REDIRECT)) {
            return null;
        }
        String safeSuffix = suffix == null ? "" : suffix;
        if (viewName.startsWith(FORWARD)) {
            String path = strip(viewName.substring(FORWARD.length()));
            return !safeSuffix.isEmpty() && path.endsWith(safeSuffix) ? normalize(path) : null;
        }
        return normalize((prefix == null ? "" : prefix) + viewName.trim() + safeSuffix);
    }

    /** 앞에 「/」 를 붙이고 겹친 「/」 를 하나로 줄인다. */
    public static String normalize(String path) {
        return (SLASH + path.trim()).replaceAll("/{2,}", SLASH);
    }

    /**
     * 웹 뿌리 기준 경로가 prefix · suffix 아래에 있으면 그 뷰 이름(앞 「/」 없이), 아니면 null.
     * 찾기 검색어로 쓰며, 실제 일치는 resolve 로 다시 확인한다.
     */
    public static String viewName(String prefix, String suffix, String path) {
        String full = normalize(path);
        String head = normalize(prefix == null ? "" : prefix);
        String tail = suffix == null ? "" : suffix;
        if (!head.endsWith(SLASH)) {
            head = head + SLASH;
        }
        if (!full.startsWith(head) || !full.endsWith(tail) || full.length() <= head.length() + tail.length()) {
            return null;
        }
        return full.substring(head.length(), full.length() - tail.length());
    }

    /** yml(yaml) · properties 글에서 prefix · suffix 값. 없는 쪽은 null. 결과는 {prefix, suffix}. */
    public static String[] fromConfig(String text, boolean yaml) {
        return new String[]{firstValue(text, PREFIX_KEY, yaml), firstValue(text, SUFFIX_KEY, yaml)};
    }

    /** 스프링 XML 의 InternalResourceViewResolver 빈 · mvc:jsp 에서 {prefix, suffix} 쌍들. 값이 없는 쪽은 null. */
    public static List<String[]> fromXml(String xml) {
        List<String[]> result = new ArrayList<>();
        Matcher bean = RESOLVER_BEAN.matcher(xml);
        while (bean.find()) {
            String[] pair = new String[2];
            String body = bean.group();
            int openEnd = body.indexOf('>');
            fill(pair, ATTRIBUTE.matcher(body.substring(0, openEnd < 0 ? body.length() : openEnd)));
            fill(pair, PROPERTY.matcher(body));
            result.add(pair);
        }
        Matcher jsp = MVC_JSP.matcher(xml);
        while (jsp.find()) {
            String[] pair = new String[2];
            fill(pair, ATTRIBUTE.matcher(jsp.group()));
            result.add(pair);
        }
        return result;
    }

    private static void fill(String[] pair, Matcher matcher) {
        while (matcher.find()) {
            pair[ATTR_PREFIX.equals(matcher.group(1)) ? 0 : 1] = matcher.group(2);
        }
    }

    private static String firstValue(String text, String key, boolean yaml) {
        List<ConfigKeys.Hit> hits = ConfigKeys.find(text, key, yaml);
        return hits.isEmpty() ? null : hits.get(0).value();
    }

    private static String strip(String path) {
        int query = path.indexOf(QUERY);
        return (query < 0 ? path : path.substring(0, query)).trim();
    }
}
