package local.bootservers;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
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
import java.util.concurrent.ConcurrentHashMap;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

/**
 * 실행 중인 서버 들여다보기: 스프링 부트 액추에이터 health 의 UP · DOWN, JVM 힙 사용량. 모두 attach 로 연 JMX 로 읽는다.
 * 모두 화면 스레드 밖에서 부른다(네트워크 · attach 를 기다린다).
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
    // attach 로 연 JMX 연결. 같은 프로세스는 다시 붙지 않고, 끝난 프로세스의 연결은 다음 조회 때 닫는다.
    private static final Map<Long, JMXConnector> CONNECTIONS = new ConcurrentHashMap<>();

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

    /**
     * 프로세스(또는 그 아래 java 프로세스, catalina.bat 처럼 스크립트로 띄운 경우)의 힙 사용량 { 사용, 최대 } 바이트.
     * attach 를 못 하면(다른 사용자 · attach 꺼짐 · 끝난 프로세스) null. 최대를 모르면 -1.
     */
    static long[] heap(long rootPid) {
        long pid = javaPid(rootPid);
        MBeanServerConnection server = connection(rootPid);
        if (server == null) {
            return null;
        }
        try {
            MemoryUsage usage = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class)
                    .getHeapMemoryUsage();
            return new long[] {usage.getUsed(), usage.getMax()};
        }
        catch (IOException | RuntimeException | LinkageError e) {
            close(pid);
            return null;
        }
    }

    /** JVM 값: CPU(0~1, 모르면 NaN) · 코어 수 · 힙 사용 · 최대(모르면 -1) · 스레드 · 최고 스레드 · GC 누적 횟수. */
    record Jvm(double cpu, int cores, long heapUsed, long heapMax, int threads, int peakThreads, long gcCount) {
    }

    /** 대시보드용 JVM 값. heap 과 같은 attach 연결을 쓴다. attach 를 못 하면 null. */
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
        try {
            JMXConnector connector = CONNECTIONS.get(pid);
            if (connector == null) {
                connector = connect(pid);
                CONNECTIONS.put(pid, connector);
            }
            return connector.getMBeanServerConnection();
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
