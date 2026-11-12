package local.bootservers;

/**
 * 개발 서버 주소 글 처리(IntelliJ 클래스 없음): 기본 주소 만들기. 설정 파일 값은 Links 로 읽는다.
 */
final class HttpText {

    /** OpenAPI 사양에 넣는 HTTP 방식. */
    static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};
    static final String DEFAULT_HOST = "localhost";
    static final int DEFAULT_PORT = 8080;
    static final String PORT_KEY = "server.port";
    /** 스프링 부트 2 이상 · 1.x 의 컨텍스트 경로 키. 앞 키가 먼저. */
    static final String[] CONTEXT_PATH_KEYS = {"server.servlet.context-path", "server.context-path"};

    private HttpText() {
    }

    /** http://localhost:포트 + 컨텍스트 경로(앞 / 붙임 · 끝 / 뗌, 「/」 하나면 없음). */
    static String baseUrl(int port, String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + DEFAULT_HOST + ":" + port + path;
    }

    /** 숫자로 읽히는 포트면 그 값, 아니면 null. */
    static Integer parsePort(String value) {
        String text = value == null ? "" : value.strip();
        if (!text.matches("\\d{1,5}")) {
            return null;
        }
        int port = Integer.parseInt(text);
        return port > 0 && port <= 65535 ? port : null;
    }
}
