package local.bootservers;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실행 중인 서버 들여다보기: 스프링 부트 /actuator/health 의 UP · DOWN, JVM 힙 사용량.
 * 모두 화면 스레드 밖에서 부른다(네트워크 · attach 를 기다린다).
 */
final class ServerProbe {

    enum Health { UP, DOWN }

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final String UP_FIELD = "\"UP\"";
    private static final String JAVA_EXE = "java";
    // attach 로 연 JMX 연결. 같은 프로세스는 다시 붙지 않고, 끝난 프로세스의 연결은 다음 조회 때 닫는다.
    private static final Map<Long, JMXConnector> CONNECTIONS = new ConcurrentHashMap<>();

    private ServerProbe() {
    }

    /**
     * url(…/actuator/health)을 불러 UP · DOWN. 액추에이터가 없거나(404) 로그인이 필요하면(401 · 403) 알 수 없으므로 null.
     * 연결이 안 되거나 200 이 아니거나 본문에 "UP" 이 없으면 DOWN.
     */
    static Health health(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == HTTP_NOT_FOUND || code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) {
                return null;
            }
            return code == HTTP_OK && response.body().contains(UP_FIELD) ? Health.UP : Health.DOWN;
        }
        catch (IOException e) {
            return Health.DOWN;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 프로세스(또는 그 아래 java 프로세스, catalina.bat 처럼 스크립트로 띄운 경우)의 힙 사용량 { 사용, 최대 } 바이트.
     * attach 를 못 하면(다른 사용자 · attach 꺼짐 · 끝난 프로세스) null. 최대를 모르면 -1.
     */
    static long[] heap(long rootPid) {
        closeDead();
        long pid = javaPid(rootPid);
        try {
            JMXConnector connector = CONNECTIONS.get(pid);
            if (connector == null) {
                connector = connect(pid);
                CONNECTIONS.put(pid, connector);
            }
            MemoryMXBean memory = ManagementFactory.newPlatformMXBeanProxy(
                    connector.getMBeanServerConnection(), ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
            MemoryUsage usage = memory.getHeapMemoryUsage();
            return new long[] {usage.getUsed(), usage.getMax()};
        }
        catch (IOException | AttachNotSupportedException | RuntimeException | LinkageError e) {
            close(pid);
            return null;
        }
    }

    private static JMXConnector connect(long pid) throws IOException, AttachNotSupportedException {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            return JMXConnectorFactory.connect(new JMXServiceURL(vm.startLocalManagementAgent()));
        }
        finally {
            vm.detach();
        }
    }

    /** rootPid 가 java 면 그대로, 아니면 그 아래 첫 java 프로세스. 없으면 rootPid. */
    static long javaPid(long rootPid) {
        Optional<ProcessHandle> root = ProcessHandle.of(rootPid);
        if (root.isEmpty() || isJava(root.get())) {
            return rootPid;
        }
        Iterator<ProcessHandle> it = root.get().descendants().iterator();
        while (it.hasNext()) {
            ProcessHandle child = it.next();
            if (isJava(child)) {
                return child.pid();
            }
        }
        return rootPid;
    }

    private static boolean isJava(ProcessHandle process) {
        String command = process.info().command().orElse("");
        String name = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        return name.equals(JAVA_EXE) || name.equals(JAVA_EXE + ".exe");
    }

    private static void closeDead() {
        for (Long pid : CONNECTIONS.keySet()) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                continue;
            }
            close(pid);
        }
    }

    private static void close(long pid) {
        JMXConnector connector = CONNECTIONS.remove(pid);
        if (connector == null) {
            return;
        }
        try {
            connector.close();
        }
        catch (IOException ignored) {
            // 이미 끝난 프로세스와의 연결은 닫기도 실패할 수 있다. 버리면 된다.
        }
    }
}
