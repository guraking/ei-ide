package local.bootservers;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.Iterator;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

/**
 * 실행 중인 서버 들여다보기: 스프링 부트 액추에이터 health 의 UP · DOWN, JVM 힙 사용량. 모두 attach 로 연 JMX 로 읽는다.
 * 모두 화면 스레드 밖에서 부른다(네트워크 · attach 를 기다린다).
 * JMX 호출에는 시간 제한이 있다. 디버그 중단점에 모든 스레드가 멈춘 서버는 응답하지 않으므로, 제한을 넘기면 그 연결을 버리고
 * 멈춘 호출이 끝날 때까지 그 프로세스는 바로 「알 수 없음」으로 돌려준다(다른 서버를 재는 일이 막히지 않게).
 */
final class ServerProbe {

    enum Health { UP, DOWN }

    // 스프링 부트 JMX 끝점 이름: {도메인}:type=Endpoint,name={끝점}[,context=…]
    private static final String ENDPOINT_PATTERN_START = "*:type=Endpoint,name=";
    private static final String ENDPOINT_PATTERN_END = ",*";
    private static final String HEALTH_ENDPOINT = "Health";
    private static final String HEALTH_OPERATION = "health";
    private static final String STATUS_FIELD = "status";
    private static final String UP = "UP";
    private static final String JAVA_EXE = "java";
    private static final String CALL_THREAD_NAME = "Moa IDE JMX 조회";
    private static final String STUCK_MESSAGE = "JMX 응답 시간 초과 pid=";
    // attach 로 연 JMX 연결. 같은 프로세스는 다시 붙지 않고, 끝난 프로세스의 연결은 다음 조회 때 닫는다.
    private static final Map<Long, JMXConnector> CONNECTIONS = new ConcurrentHashMap<>();
    // JMX 호출 하나(MBean 읽기 · 부르기)의 시간 제한
    private static final long CALL_TIMEOUT_SECONDS = 3;
    // attach 와 관리 에이전트 시작까지의 시간 제한(처음 붙을 때 에이전트를 띄우느라 호출 하나보다 오래 걸린다)
    private static final long CONNECT_TIMEOUT_SECONDS = 10;
    // 시간 제한을 넘긴 호출이 아직 끝나지 않은 프로세스. 그 호출이 끝나면 빠진다.
    private static final Set<Long> STUCK = ConcurrentHashMap.newKeySet();
    // 띄운 프로세스 pid → 그 java 프로세스 pid(스크립트로 띄운 WAS 는 자식). 한 번 찾으면 살아 있는 동안 자식 프로세스를 다시 훑지 않는다.
    private static final Map<Long, Long> JAVA_PIDS = new ConcurrentHashMap<>();
    // JMX 호출을 돌리는 스레드. 인텔리제이 클래스를 쓰지 않으므로(MetricsProbeCheck 가 함께 컴파일) JDK 풀을 쓴다. IDE 를 끌 때 막지 않게 데몬.
    private static final ExecutorService CALLS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, CALL_THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    private ServerProbe() {
    }

    /**
     * 스프링 부트 액추에이터의 JMX health 끝점으로 UP · DOWN. HTTP 를 거치지 않으므로 앱의 보안 필터 · 접근 로그와 상관없다.
     * attach 를 못 하거나 끝점이 JMX 에 열려 있지 않거나(spring.jmx.enabled 꺼짐) 읽다 실패하면 알 수 없으므로 null.
     */
    static Health health(long rootPid) {
        MBeanServerConnection server = connection(rootPid);
        if (server == null) {
            return null;
        }
        try {
            ObjectName endpoint = endpoint(server, HEALTH_ENDPOINT);
            if (endpoint == null) {
                return null;
            }
            Object answer = server.invoke(endpoint, HEALTH_OPERATION, new Object[0], new String[0]);
            return answer instanceof Map<?, ?> map && UP.equals(map.get(STATUS_FIELD)) ? Health.UP : Health.DOWN;
        }
        catch (IOException | javax.management.JMException | RuntimeException e) {
            return null;
        }
    }

    /** 액추에이터 끝점 MBean(Health · Metrics …). 도메인(spring.jmx.default-domain)과 unique-names 꼬리는 가리지 않는다. 없으면 null. */
    static ObjectName endpoint(MBeanServerConnection server, String name) throws IOException, javax.management.JMException {
        Set<ObjectName> found = server.queryNames(new ObjectName(ENDPOINT_PATTERN_START + name + ENDPOINT_PATTERN_END), null);
        return found.isEmpty() ? null : found.iterator().next();
    }

    /** JVM 값: CPU(0~1, 모르면 NaN) · 코어 수 · 힙 사용 · 최대(모르면 -1) · 스레드 · 최고 스레드 · GC 누적 횟수. */
    record Jvm(double cpu, int cores, long heapUsed, long heapMax, int threads, int peakThreads, long gcCount) {
    }

    /** 대시보드 · 서버 창 머리줄 힙에 쓰는 JVM 값. attach 를 못 하면(다른 사용자 · attach 꺼짐 · 끝난 프로세스) null. */
    static Jvm jvm(long rootPid) {
        long pid = javaPid(rootPid);
        MBeanServerConnection server = connection(rootPid);
        if (server == null) {
            return null;
        }
        try {
            MemoryUsage heap = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class)
                    .getHeapMemoryUsage();
            ThreadMXBean threads = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
            com.sun.management.OperatingSystemMXBean os = ManagementFactory.newPlatformMXBeanProxy(
                    server, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, com.sun.management.OperatingSystemMXBean.class);
            double cpu = os.getProcessCpuLoad();
            long gc = 0;
            Set<ObjectName> collectors = server.queryNames(new ObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*"), null);
            for (ObjectName name : collectors) {
                long count = ManagementFactory.newPlatformMXBeanProxy(server, name.getCanonicalName(), GarbageCollectorMXBean.class).getCollectionCount();
                gc += Math.max(count, 0);
            }
            return new Jvm(cpu < 0 ? Double.NaN : cpu, os.getAvailableProcessors(), heap.getUsed(), heap.getMax(),
                    threads.getThreadCount(), threads.getPeakThreadCount(), gc);
        }
        catch (IOException | javax.management.JMException | RuntimeException | LinkageError e) {
            close(pid);
            return null;
        }
    }

    /**
     * 프로세스(또는 그 아래 java 프로세스)에 attach 로 연 JMX 연결. 같은 프로세스는 한 번 연 연결을 다시 쓴다.
     * attach 를 못 하면(다른 사용자 · attach 꺼짐 · 끝난 프로세스) null.
     */
    static MBeanServerConnection connection(long rootPid) {
        closeDead();
        long pid = javaPid(rootPid);
        if (STUCK.contains(pid)) {
            return null;
        }
        try {
            JMXConnector connector = CONNECTIONS.get(pid);
            if (connector == null) {
                connector = timed(pid, CONNECT_TIMEOUT_SECONDS, () -> register(pid, connect(pid)));
            }
            return timedConnection(pid, connector.getMBeanServerConnection());
        }
        catch (Exception | LinkageError e) {
            close(pid);
            return null;
        }
    }

    /** 새 연결을 맡긴다. 다른 스레드가 먼저 맡겼으면 새 연결은 닫고 먼저 것을 돌려준다. */
    private static JMXConnector register(long pid, JMXConnector connector) {
        JMXConnector earlier = CONNECTIONS.putIfAbsent(pid, connector);
        if (earlier == null) {
            return connector;
        }
        closeQuietly(connector);
        return earlier;
    }

    /** 모든 호출을 CALL_TIMEOUT_SECONDS 안에 끝내는 연결. 넘기면 SocketTimeoutException(IOException). */
    private static MBeanServerConnection timedConnection(long pid, MBeanServerConnection server) {
        return (MBeanServerConnection) Proxy.newProxyInstance(MBeanServerConnection.class.getClassLoader(),
                new Class<?>[] {MBeanServerConnection.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, args);
                    }
                    return timed(pid, CALL_TIMEOUT_SECONDS, () -> {
                        try {
                            return method.invoke(server, args);
                        }
                        catch (InvocationTargetException e) {
                            throw rethrowable(e.getCause());
                        }
                    });
                });
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> MBeanServerConnection.class.getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
        };
    }

    private static Exception rethrowable(Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        return (Exception) cause;
    }

    /**
     * call 을 다른 스레드에서 돌리고 seconds 까지 기다린다. 넘기면 그 프로세스를 STUCK 에 넣고 연결을 버린 뒤 SocketTimeoutException.
     * 멈춘 연결을 닫는 일도 원격 호출이라 막힐 수 있으므로 닫기는 다른 스레드에서 한다.
     */
    private static <T> T timed(long pid, long seconds, Callable<T> call) throws Exception {
        if (STUCK.contains(pid)) {
            throw new SocketTimeoutException(STUCK_MESSAGE + pid);
        }
        AtomicBoolean finished = new AtomicBoolean();
        Future<T> future = CALLS.submit(() -> {
            try {
                return call.call();
            }
            finally {
                finished.set(true);
                STUCK.remove(pid);
            }
        });
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            STUCK.add(pid);
            if (finished.get()) {
                STUCK.remove(pid);
            }
            future.cancel(true);
            JMXConnector dropped = CONNECTIONS.remove(pid);
            if (dropped != null) {
                CALLS.execute(() -> closeQuietly(dropped));
            }
            throw new SocketTimeoutException(STUCK_MESSAGE + pid);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new InterruptedIOException(e.getMessage());
        }
        catch (ExecutionException e) {
            throw rethrowable(e.getCause());
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

    /** rootPid 가 java 면 그대로, 아니면 그 아래 첫 java 프로세스. 없으면 rootPid(자식 java 가 아직 안 떴으면 다음에 다시 찾는다). */
    static long javaPid(long rootPid) {
        Long known = JAVA_PIDS.get(rootPid);
        if (known != null && isAlive(known)) {
            return known;
        }
        JAVA_PIDS.remove(rootPid);
        Optional<ProcessHandle> root = ProcessHandle.of(rootPid);
        if (root.isEmpty()) {
            return rootPid;
        }
        if (isJava(root.get())) {
            JAVA_PIDS.put(rootPid, rootPid);
            return rootPid;
        }
        Iterator<ProcessHandle> it = root.get().descendants().iterator();
        while (it.hasNext()) {
            ProcessHandle child = it.next();
            if (isJava(child)) {
                JAVA_PIDS.put(rootPid, child.pid());
                return child.pid();
            }
        }
        return rootPid;
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean isJava(ProcessHandle process) {
        String command = process.info().command().orElse("");
        String name = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        return name.equals(JAVA_EXE) || name.equals(JAVA_EXE + ".exe");
    }

    private static void closeDead() {
        for (Long pid : CONNECTIONS.keySet()) {
            if (!isAlive(pid)) {
                close(pid);
            }
        }
        JAVA_PIDS.values().removeIf(pid -> !isAlive(pid));
    }

    private static void close(long pid) {
        JMXConnector connector = CONNECTIONS.remove(pid);
        if (connector == null) {
            return;
        }
        closeQuietly(connector);
    }

    private static void closeQuietly(JMXConnector connector) {
        try {
            connector.close();
        }
        catch (IOException ignored) {
            // 이미 끝난 프로세스와의 연결은 닫기도 실패할 수 있다. 버리면 된다.
        }
    }
}
