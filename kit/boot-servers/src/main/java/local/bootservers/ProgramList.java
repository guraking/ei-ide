package local.bootservers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프로그램 목록(프로그램 사양서용) 한 줄을 만드는 순수 논리. 파일 이름과 내용 글만 보고 구분 · 패키지 · 설명을 정한다.
 * 구분은 자바 클래스 주석(@Controller 등) → 클래스 이름 끝말 → 확장자 순으로 정한다.
 */
final class ProgramList {

    static final String KIND_CONTROLLER = "Controller";
    static final String KIND_SERVICE = "Service";
    static final String KIND_DAO = "DAO·Mapper";
    static final String KIND_VO = "VO·DTO";
    static final String KIND_CONFIG = "Config";
    static final String KIND_JAVA = "기타 Java";
    static final String KIND_JSP = "JSP";
    static final String KIND_MAPPER_XML = "Mapper XML";
    static final String KIND_JS = "JS";
    static final String KIND_CSS = "CSS";
    static final String KIND_SETTINGS = "설정 파일";
    static final String KIND_OTHER = "기타";

    static final List<String> PROGRAM_COLUMNS = List.of(
            "번호", "모듈", "구분", "경로", "파일명", "패키지", "클래스명", "설명", "줄 수", "마지막 수정일");
    static final int[] PROGRAM_WIDTHS = {6, 16, 12, 60, 30, 32, 28, 60, 8, 14};
    static final List<String> URL_COLUMNS = List.of("번호", "HTTP 방식", "URL", "클래스", "메서드", "설명");
    static final int[] URL_WIDTHS = {6, 12, 50, 50, 24, 60};

    /** 훑지 않는 폴더 이름(빌드 결과 · 도구 폴더 · 생성 코드). */
    static final Set<String> EXCLUDED_DIRS = Set.of("build", "out", "target", "node_modules", ".idea", ".git", "generated");
    /** 설명 칸 최대 글자 수(긴 머리 주석이 칸을 채우지 않게). */
    static final int DESCRIPTION_MAX = 300;

    private static final String EXT_JAVA = "java";
    private static final Set<String> EXT_JSP = Set.of("jsp", "jspf");
    private static final String EXT_XML = "xml";
    private static final Set<String> EXT_JS = Set.of("js", "mjs");
    private static final Set<String> EXT_CSS = Set.of("css", "scss", "less");
    private static final Set<String> EXT_SETTINGS = Set.of("properties", "yml", "yaml", "json", "conf");

    private static final Set<String> ANNOTATION_CONTROLLER = Set.of("Controller", "RestController");
    private static final Set<String> ANNOTATION_SERVICE = Set.of("Service");
    private static final Set<String> ANNOTATION_DAO = Set.of("Repository", "Mapper");
    private static final Set<String> ANNOTATION_CONFIG = Set.of("Configuration");
    /** 클래스 이름 끝말(소문자 비교). */
    private static final List<String> SUFFIX_CONTROLLER = List.of("controller");
    private static final List<String> SUFFIX_SERVICE = List.of("service", "serviceimpl");
    private static final List<String> SUFFIX_DAO = List.of("dao", "daoimpl", "mapper", "repository");
    private static final List<String> SUFFIX_VO = List.of("vo", "dto");
    private static final List<String> SUFFIX_CONFIG = List.of("config", "configuration");

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern ANNOTATION = Pattern.compile("@(?:[\\w$]+\\.)*([\\w$]+)");
    private static final Pattern MAPPER_NAMESPACE =
            Pattern.compile("<(?:mapper|sqlMap)\\b[^>]*?\\bnamespace\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern JSP_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern JSP_COMMENT = Pattern.compile("(?s)<%--(.*?)--%>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern JSP_SCRIPT = Pattern.compile("(?s)<%.*?%>");
    private static final Pattern INLINE_LINK = Pattern.compile("\\{@(?:link|linkplain)\\s+([^}\\s]+)(?:\\s+([^}]*))?}");
    private static final Pattern INLINE_TAG = Pattern.compile("\\{@\\w+\\s*([^}]*)}");
    private static final Pattern PARAGRAPH = Pattern.compile("(?i)</?p\\b");
    private static final Pattern SENTENCE_END = Pattern.compile("\\.(\\s|$)");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final String DOC_START = "/**";
    private static final String DOC_END = "*/";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ProgramList() {
    }

    static boolean isExcludedDir(String name) {
        return EXCLUDED_DIRS.contains(name);
    }

    /** 확장자(소문자, 점 뺌). 없으면 빈 글. */
    static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    /** 구분. text 는 파일 내용(이진 · 못 읽은 파일은 null). */
    static String kind(String fileName, String text) {
        String ext = extension(fileName);
        if (ext.equals(EXT_JAVA)) {
            return javaKind(baseName(fileName), text);
        }
        if (EXT_JSP.contains(ext)) {
            return KIND_JSP;
        }
        if (ext.equals(EXT_XML)) {
            return text != null && MAPPER_NAMESPACE.matcher(text).find() ? KIND_MAPPER_XML : KIND_SETTINGS;
        }
        if (EXT_JS.contains(ext)) {
            return KIND_JS;
        }
        if (EXT_CSS.contains(ext)) {
            return KIND_CSS;
        }
        return EXT_SETTINGS.contains(ext) ? KIND_SETTINGS : KIND_OTHER;
    }

    private static String javaKind(String className, String text) {
        Set<String> annotations = classAnnotations(text, className);
        if (containsAny(annotations, ANNOTATION_CONTROLLER)) {
            return KIND_CONTROLLER;
        }
        if (containsAny(annotations, ANNOTATION_SERVICE)) {
            return KIND_SERVICE;
        }
        if (containsAny(annotations, ANNOTATION_DAO)) {
            return KIND_DAO;
        }
        if (containsAny(annotations, ANNOTATION_CONFIG)) {
            return KIND_CONFIG;
        }
        String lower = className.toLowerCase(Locale.ROOT);
        if (endsWithAny(lower, SUFFIX_CONTROLLER)) {
            return KIND_CONTROLLER;
        }
        if (endsWithAny(lower, SUFFIX_SERVICE)) {
            return KIND_SERVICE;
        }
        if (endsWithAny(lower, SUFFIX_DAO)) {
            return KIND_DAO;
        }
        if (endsWithAny(lower, SUFFIX_VO)) {
            return KIND_VO;
        }
        return endsWithAny(lower, SUFFIX_CONFIG) ? KIND_CONFIG : KIND_JAVA;
    }

    private static boolean containsAny(Set<String> values, Set<String> wanted) {
        for (String value : values) {
            if (wanted.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String value, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 클래스 선언 앞(머리)에 붙은 주석 이름들(단순 이름). 선언을 못 찾으면 빈 집합. */
    static Set<String> classAnnotations(String text, String className) {
        Set<String> result = new HashSet<>();
        int declaration = declarationIndex(text, className);
        if (declaration < 0) {
            return result;
        }
        Matcher m = ANNOTATION.matcher(text.substring(0, declaration));
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    /** 파일 이름과 같은 최상위 형(class · interface · enum · record · @interface) 선언 위치. 없으면 -1. */
    static int declarationIndex(String text, String className) {
        if (text == null) {
            return -1;
        }
        Matcher m = Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(className) + "\\b").matcher(text);
        return m.find() ? m.start() : -1;
    }

    static String javaPackage(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = PACKAGE.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 클래스 선언 바로 앞의 Javadoc(/** … *&#47;) 글. 사이에 문장(;)이 끼면 클래스 것이 아니므로 null. */
    static String classJavadoc(String text, String className) {
        int declaration = declarationIndex(text, className);
        if (declaration < 0) {
            return null;
        }
        String head = text.substring(0, declaration);
        int start = head.lastIndexOf(DOC_START);
        if (start < 0) {
            return null;
        }
        int end = head.indexOf(DOC_END, start + DOC_START.length());
        if (end < 0 || head.indexOf(';', end) >= 0) {
            return null;
        }
        return head.substring(start, end + DOC_END.length());
    }

    /** Javadoc 첫 문장. 블록 태그(@param 등) · 문단(&lt;p&gt;) 앞까지에서 「마침표 + 공백」 까지, 태그는 벗긴다. */
    static String firstSentence(String javadoc) {
        if (javadoc == null) {
            return "";
        }
        String body = javadoc.trim();
        if (body.startsWith(DOC_START)) {
            body = body.substring(DOC_START.length());
        }
        if (body.endsWith(DOC_END)) {
            body = body.substring(0, body.length() - DOC_END.length());
        }
        StringBuilder joined = new StringBuilder();
        for (String line : body.split("\\R")) {
            String stripped = line.strip();
            while (stripped.startsWith("*")) {
                stripped = stripped.substring(1).strip();
            }
            if (stripped.startsWith("@")) {
                break;
            }
            joined.append(stripped).append(' ');
        }
        String summary = joined.toString();
        Matcher paragraph = PARAGRAPH.matcher(summary);
        if (paragraph.find()) {
            String before = summary.substring(0, paragraph.start());
            summary = before.isBlank() ? summary.substring(paragraph.start()) : before;
        }
        summary = INLINE_LINK.matcher(summary).replaceAll(m -> Matcher.quoteReplacement(
                m.group(2) != null && !m.group(2).isBlank() ? m.group(2) : simpleTarget(m.group(1))));
        summary = INLINE_TAG.matcher(summary).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        summary = collapse(unescapeHtml(HTML_TAG.matcher(summary).replaceAll(" ")));
        Matcher end = SENTENCE_END.matcher(summary);
        return limit(end.find() ? summary.substring(0, end.start() + 1) : summary);
    }

    /** {@link a.b.C#m} → C#m. */
    private static String simpleTarget(String target) {
        int hash = target.indexOf('#');
        String type = hash < 0 ? target : target.substring(0, hash);
        int dot = type.lastIndexOf('.');
        return (dot < 0 ? type : type.substring(dot + 1)) + (hash < 0 ? "" : target.substring(hash));
    }

    /** JSP 설명: &lt;title&gt; 글, 없거나 비면 첫 &lt;%-- --%&gt; 주석. */
    static String jspDescription(String text) {
        if (text == null) {
            return "";
        }
        Matcher title = JSP_TITLE.matcher(text);
        if (title.find()) {
            String value = collapse(unescapeHtml(HTML_TAG.matcher(JSP_SCRIPT.matcher(title.group(1)).replaceAll(" ")).replaceAll(" ")));
            if (!value.isEmpty()) {
                return limit(value);
            }
        }
        Matcher comment = JSP_COMMENT.matcher(text);
        return comment.find() ? limit(collapse(comment.group(1))) : "";
    }

    /** MyBatis mapper · iBATIS sqlMap 의 namespace. 없으면 빈 글. */
    static String mapperNamespace(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = MAPPER_NAMESPACE.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 줄 수. 끝 줄바꿈 뒤 빈 줄은 세지 않는다. */
    static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || (ch == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n'))) {
                lines++;
            }
        }
        char last = text.charAt(text.length() - 1);
        return last == '\n' || last == '\r' ? lines - 1 : lines;
    }

    /** 「프로그램 목록」 한 행. text 가 null(이진 · 못 읽음)이면 줄 수 · 설명은 빈 칸. */
    static List<Object> programRow(int no, String module, String path, String fileName, String text, long modifiedMillis) {
        String kind = kind(fileName, text);
        String ext = extension(fileName);
        boolean java = ext.equals(EXT_JAVA);
        String className = java ? baseName(fileName) : "";
        String description;
        if (java) {
            description = firstSentence(classJavadoc(text, className));
        } else if (kind.equals(KIND_JSP)) {
            description = jspDescription(text);
        } else if (kind.equals(KIND_MAPPER_XML)) {
            description = mapperNamespace(text);
        } else {
            description = "";
        }
        List<Object> row = new ArrayList<>();
        row.add(no);
        row.add(module);
        row.add(kind);
        row.add(path);
        row.add(fileName);
        row.add(java ? javaPackage(text) : "");
        row.add(className);
        row.add(description);
        row.add(text == null ? null : lineCount(text));
        row.add(date(modifiedMillis));
        return row;
    }

    /** 「URL 목록」 한 행. */
    static List<Object> urlRow(int no, String httpMethod, String url, String className, String methodName, String description) {
        return List.of(no, httpMethod, url, className, methodName, description);
    }

    static String date(long millis) {
        return millis <= 0 ? "" : DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private static String unescapeHtml(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static String collapse(String s) {
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    private static String limit(String s) {
        return s.length() > DESCRIPTION_MAX ? s.substring(0, DESCRIPTION_MAX) : s;
    }
}
