package local.bootservers.was;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** WAS 를 띄우기 전 점검(포트) · JVM 출력 인코딩 옵션. IDE 와 무관한 순수 자바. */
public final class RunSupport {

    // 이미 듣고 있는 서버가 있는지 붙어 보는 한도. 로컬이라 짧아도 된다.
    private static final int CONNECT_TIMEOUT_MILLIS = 200;
    // 시작 완료 보조 판정에서 HTTP 답을 기다리는 한도.
    private static final int HTTP_ANSWER_MILLIS = 1000;
    private static final byte[] HEAD_REQUEST = "HEAD / HTTP/1.0\r\nHost: localhost\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    // 실행 창이 출력을 UTF-8 로 읽으므로 JVM 콘솔 출력만 UTF-8 로 맞춘다. sun.* 는 JDK 8~18, stdout · stderr.encoding 은 JDK 19 부터 쓴다.
    // file.encoding 은 앱의 파일 읽기 · 쓰기 기본 문자셋까지 바꾸므로 넣지 않는다.
    private static final List<String> ENCODING_OPTIONS = List.of(
            "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8");

    private RunSupport() {
    }

    /**
     * 포트를 다른 프로세스가 쓰고 있으면 true. 모든 주소로 묶어 보고(실패하면 사용 중), 묶이더라도
     * localhost 로 붙어 본다 — 윈도우는 다른 프로세스가 127.0.0.1 만 잡고 있어도 모든 주소 묶기를 허락하기 때문이다.
     */
    public static boolean portInUse(int port) {
        // 주소 재사용은 자바 기본값(유닉스 켬 · 윈도우 끔)을 둔다. 서버들도 그렇게 묶으므로, 방금 끈 서버의 TIME_WAIT 때문에 재시작을 막지 않는다.
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress(port));
        }
        catch (IOException e) {
            return true;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS);
            return true;
        }
        catch (IOException e) {
            // 붙지 못하면 듣는 서버가 없다.
            return false;
        }
    }

    /**
     * localhost 의 포트가 HTTP 요청에 답하면 true. 시작을 마쳤는지 보는 보조 판정에 쓴다.
     * 포트가 열린 것만으로는 모자라다: Tomcat 은 앱을 올리기 전에 포트를 묶고(bindOnInit) 연결자를 시작한 뒤에야 답한다.
     * 답이 HTTP_ANSWER_MILLIS 안에 한 바이트라도 오면 답한 것으로 본다(상태 코드는 보지 않는다).
     */
    public static boolean httpAnswers(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS);
            s.setSoTimeout(HTTP_ANSWER_MILLIS);
            s.getOutputStream().write(HEAD_REQUEST);
            s.getOutputStream().flush();
            return s.getInputStream().read() != -1;
        }
        catch (IOException e) {
            return false;
        }
    }

    /** 출력 인코딩 옵션 중 vmOptions 에 이미 없는 것만 띄어쓰기로 이어 돌려준다(사용자가 준 값을 덮지 않는다). */
    public static String encodingOptions(String vmOptions) {
        List<String> missing = new ArrayList<>();
        for (String option : ENCODING_OPTIONS) {
            String key = option.substring(0, option.indexOf('=') + 1);
            if (!Pattern.compile("(^|\\s)" + Pattern.quote(key)).matcher(vmOptions).find()) {
                missing.add(option);
            }
        }
        return String.join(" ", missing);
    }
}
