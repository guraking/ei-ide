package local.bootservers;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.management.MBeanServerConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 서버 대시보드 · 연결 지도의 값을 모은다. 5초마다 화면 스레드에서 서버 목록을 받아(snapshot), 화면 스레드 밖에서 JVM · 액추에이터 값을 재고,
 * 10초마다 설정 파일의 연결 주소에 포트로 붙어 본 뒤, 화면 스레드에서 기록을 고치고 onChange 를 부른다.
 * 실행 중인 서버가 없으면 재지 않는다. 서버를 멈추면 그 서버 기록을 지운다. 기록은 디스크에 남기지 않는다.
 * 연결이 끊기면(처음 확인에서 끊겨 있어도) 알림을 한 번 띄우고, 다시 붙을 때까지 같은 연결로 또 띄우지 않는다.
 */
final class ServerMonitor {

    /** 서버 창의 줄 하나. module 은 스프링 부트만(WAS 는 null), pid 는 실행 중일 때만(-1). */
    record Watched(String name, Module module, String vmProfile, boolean live, long pid, Integer port) {
    }

    /** 한 연결의 마지막 확인. checked 가 false 면 아직 확인 전. downSince 는 끊긴 것을 처음 본 시각(붙어 있으면 0). */
    record LinkState(boolean checked, boolean up, long millis, long downSince) {
        static final LinkState UNKNOWN = new LinkState(false, false, 0, 0);
    }

    /** 연결 하나. to 는 붙는 서버 이름(우리 서버 목록에 있을 때) 또는 null(바깥 DB · Redis · HTTP). file 은 그 주소가 적힌 설정 파일. */
    record Link(String from, String to, Links.Target target, VirtualFile file) {
        String key() {
            return from + "→" + target.kind() + "|" + target.address().toLowerCase(Locale.ROOT);
        }
    }

    private static final long SAMPLE_SECONDS = 5;
    // 연결 확인은 재기 두 번에 한 번(10초), 느린 요청 목록은 여섯 번에 한 번(30초)
    private static final int LINK_ROUNDS = 2;
    private static final int SLOW_ROUNDS = 6;
    private static final int CONNECT_TIMEOUT_MILLIS = 1500;
    private static final int SLOW_TOP = 5;
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]");
    private static final String NOTIFICATION_GROUP = "서버 연결";

    private final Project project;
    private final Supplier<List<Watched>> snapshot;
    private final Supplier<String> slowTarget;
    private final Runnable onChange;
    private final Consumer<String> showMap;
    private final ScheduledFuture<?> future;
    private int round;
    private volatile boolean busy;
    // 연결 지도 탭이 화면에 보이는가. 보일 때만 설정을 읽고 포트를 확인한다(바깥 DB · Redis 에 쓸데없이 붙지 않게).
    private volatile boolean mapShown;

    // 아래는 화면 스레드에서만 읽고 쓴다.
    private final Map<String, Metrics.History> histories = new HashMap<>();
    private final Map<String, MetricsProbe.Access> access = new HashMap<>();
    private final Map<String, List<Metrics.Slow>> slow = new HashMap<>();
    private List<Watched> servers = List.of();
    private List<Link> links = List.of();
    private final Map<String, LinkState> linkStates = new HashMap<>();
    private final Set<String> muted = new HashSet<>();
    private final Set<String> notified = new HashSet<>();
    private Set<String> profileNames = Set.of();
    private String profileOverride;

    /**
     * @param snapshot   화면 스레드에서 부르는 서버 목록
     * @param slowTarget 화면 스레드에서 부르는, 느린 요청을 잴 서버 이름(대시보드가 보일 때만, 아니면 null)
     * @param onChange   값이 바뀐 뒤 화면 스레드에서 부른다
     * @param showMap    알림의 「연결 지도 보기」. 연결을 시작한 서버 이름을 받는다
     */
    ServerMonitor(Project project, Disposable parent, Supplier<List<Watched>> snapshot, Supplier<String> slowTarget,
                  Runnable onChange, Consumer<String> showMap) {
        this.project = project;
        this.snapshot = snapshot;
        this.slowTarget = slowTarget;
        this.onChange = onChange;
        this.showMap = showMap;
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(this::tick, 0, SAMPLE_SECONDS, TimeUnit.SECONDS);
        Disposer.register(parent, () -> future.cancel(false));
    }

    Metrics.History history(String name) {
        return histories.get(name);
    }

    MetricsProbe.Access access(String name) {
        return access.getOrDefault(name, MetricsProbe.Access.NONE);
    }

    List<Metrics.Slow> slow(String name) {
        return slow.getOrDefault(name, List.of());
    }

    List<Watched> servers() {
        return servers;
    }

    List<Link> links() {
        return links;
    }

    LinkState state(Link link) {
        return linkStates.getOrDefault(link.key(), LinkState.UNKNOWN);
    }

    Set<String> profileNames() {
        return profileNames;
    }

    String profileOverride() {
        return profileOverride;
    }

    /** null 이면 서버마다 VM 옵션(없으면 설정)의 프로필. 바꾸면 연결을 바로 다시 찾는다. */
    void setProfileOverride(String profile) {
        profileOverride = profile;
        refreshLinks();
    }

    /** 연결을 지금 다시 찾고 확인한다(알림의 「다시 확인」 · 프로필 바꾸기). */
    void refreshLinks() {
        round = 0;
        AppExecutorUtil.getAppExecutorService().execute(this::tick);
    }

    /** 연결 지도 탭이 보이거나 가려질 때 화면 스레드에서 부른다. 보이게 되면 바로 한 번 확인한다. */
    void setMapShown(boolean shown) {
        if (shown == mapShown) {
            return;
        }
        mapShown = shown;
        if (shown) {
            refreshLinks();
        }
    }

    /** 스케줄러 스레드. 앞 차례가 아직 돌고 있으면 건너뛴다. */
    private void tick() {
        if (busy || project.isDisposed()) {
            return;
        }
        busy = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            List<Watched> list = snapshot.get();
            String slowName = slowTarget.get();
            String override = profileOverride;
            boolean checkLinks = mapShown && round % LINK_ROUNDS == 0;
            boolean checkSlow = round % SLOW_ROUNDS == 0;
            round++;
            AppExecutorUtil.getAppExecutorService().execute(() -> {
                try {
                    measure(list, slowName, override, checkLinks, checkSlow);
                }
                finally {
                    busy = false;
                }
            });
        }, ModalityState.any(), project.getDisposed());
    }

    /** 화면 스레드 밖. 다 잰 뒤 화면 스레드에서 apply. */
    private void measure(List<Watched> list, String slowName, String override, boolean checkLinks, boolean checkSlow) {
        boolean anyLive = false;
        Map<String, Metrics.Sample> samples = new LinkedHashMap<>();
        Map<String, MetricsProbe.Access> accesses = new HashMap<>();
        Map<String, List<Metrics.Slow>> slows = new HashMap<>();
        for (Watched w : list) {
            if (!w.live()) {
                continue;
            }
            anyLive = true;
            ServerProbe.Jvm jvm = w.pid() < 0 ? null : ServerProbe.jvm(w.pid());
            MBeanServerConnection server = w.module() == null || w.pid() < 0 ? null : ServerProbe.connection(w.pid());
            MetricsProbe.Requests r = w.module() == null ? MetricsProbe.Requests.of(MetricsProbe.Access.NONE) : MetricsProbe.requests(server);
            accesses.put(w.name(), r.access());
            samples.put(w.name(), sample(jvm, r));
            if (checkSlow && w.name().equals(slowName) && r.access() == MetricsProbe.Access.OK) {
                slows.put(w.name(), Metrics.slowest(MetricsProbe.slow(server), SLOW_TOP));
            }
        }
        List<Link> found = null;
        Set<String> names = null;
        Map<String, LinkState> checked = new HashMap<>();
        if (checkLinks && anyLive) {
            names = new LinkedHashSet<>();
            found = findLinks(list, override, names);
            for (Link link : found) {
                checked.put(link.key(), connect(link.target()));
            }
        }
        List<Link> foundLinks = found;
        Set<String> foundNames = names;
        boolean live = anyLive;
        ApplicationManager.getApplication().invokeLater(
                () -> apply(list, live, samples, accesses, slows, foundLinks, foundNames, checked),
                ModalityState.any(), project.getDisposed());
    }

    private static Metrics.Sample sample(ServerProbe.Jvm jvm, MetricsProbe.Requests r) {
        double nan = Double.NaN;
        return new Metrics.Sample(System.currentTimeMillis(),
                jvm == null ? nan : jvm.cpu(), jvm == null ? -1 : jvm.cores(), jvm == null ? -1 : jvm.heapUsed(), jvm == null ? -1 : jvm.heapMax(),
                jvm == null ? -1 : jvm.threads(), jvm == null ? -1 : jvm.peakThreads(), jvm == null ? -1 : jvm.gcCount(),
                r.count(), r.totalSeconds(), r.maxSeconds(), r.serverErrors(), r.clientErrors(), r.poolActive(), r.poolMax(), r.poolPending());
    }

    /** 실행 중인 스프링 부트 서버의 설정에서 연결을 찾는다. 다른 서버의 포트로 가는 localhost 주소는 그 서버로 잇는다. */
    private List<Link> findLinks(List<Watched> list, String override, Set<String> names) {
        record Read(Watched server, SpringWebConfig.ModuleConfig config, Set<String> profiles) {
        }
        List<Read> reads = ReadAction.compute(() -> {
            List<Read> result = new ArrayList<>();
            for (Watched w : list) {
                if (w.module() == null || w.module().isDisposed()) {
                    continue;
                }
                SpringWebConfig.ModuleConfig config = SpringWebConfig.moduleConfig(w.module());
                Set<String> profiles = override != null ? Set.of(override) : Links.profiles(w.vmProfile(), config.sources());
                result.add(new Read(w, config, profiles));
            }
            return result;
        });
        Map<Integer, String> byPort = new HashMap<>();
        for (Read r : reads) {
            names.addAll(Links.profileNames(r.config().sources()));
            Integer port = r.server().live() && r.server().port() != null ? r.server().port() : Links.port(r.config().sources(), r.profiles());
            if (port != null) {
                byPort.putIfAbsent(port, r.server().name());
            }
        }
        List<Link> result = new ArrayList<>();
        for (Read r : reads) {
            if (!r.server().live()) {
                continue;
            }
            for (Links.Target t : Links.targets(r.config().sources(), r.profiles())) {
                String to = LOCAL_HOSTS.contains(t.host().toLowerCase(Locale.ROOT)) ? byPort.get(t.port()) : null;
                if (r.server().name().equals(to)) {
                    continue;
                }
                result.add(new Link(r.server().name(), to, t, r.config().files().get(t.source())));
            }
        }
        return result;
    }

    /** 포트에 붙어 본다. 걸린 시간은 밀리초. */
    private static LinkState connect(Links.Target t) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(t.host(), t.port()), CONNECT_TIMEOUT_MILLIS);
            return new LinkState(true, true, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), 0);
        }
        catch (IOException | IllegalArgumentException | SecurityException e) {
            return new LinkState(true, false, 0, System.currentTimeMillis());
        }
    }

    private void apply(List<Watched> list, boolean anyLive, Map<String, Metrics.Sample> samples, Map<String, MetricsProbe.Access> accesses,
                       Map<String, List<Metrics.Slow>> slows, List<Link> found, Set<String> names, Map<String, LinkState> checked) {
        servers = list;
        histories.keySet().retainAll(samples.keySet());
        access.keySet().retainAll(samples.keySet());
        slow.keySet().retainAll(samples.keySet());
        for (Map.Entry<String, Metrics.Sample> e : samples.entrySet()) {
            histories.computeIfAbsent(e.getKey(), k -> new Metrics.History()).add(e.getValue());
        }
        access.putAll(accesses);
        slow.putAll(slows);
        if (!anyLive) {
            links = List.of();
            linkStates.clear();
            notified.clear();
        }
        else if (found != null) {
            links = found;
            profileNames = names;
            Set<String> keys = new HashSet<>();
            for (Link link : found) {
                keys.add(link.key());
                LinkState before = linkStates.get(link.key());
                LinkState now = checked.get(link.key());
                if (!now.up() && before != null && before.checked() && !before.up()) {
                    now = new LinkState(true, false, 0, before.downSince());
                }
                linkStates.put(link.key(), now);
                if (now.up()) {
                    notified.remove(link.key());
                }
                else if (!muted.contains(link.key()) && notified.add(link.key())) {
                    notifyDown(link);
                }
            }
            linkStates.keySet().retainAll(keys);
            notified.retainAll(keys);
        }
        onChange.run();
    }

    /** 연결 끊김 알림: 연결 지도 보기 · 다시 확인 · 이 연결 알림 끄기. */
    private void notifyDown(Link link) {
        String to = link.to() != null ? link.to() : link.target().title();
        Notification n = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(
                BootServersBundle.message("monitor.link.down.title"),
                BootServersBundle.message("monitor.link.down.text", link.from(), to, link.target().address()),
                NotificationType.WARNING);
        n.addAction(NotificationAction.createSimpleExpiring(BootServersBundle.message("monitor.link.show"), () -> showMap.accept(link.from())));
        n.addAction(NotificationAction.createSimpleExpiring(BootServersBundle.message("monitor.link.retry"), () -> {
            notified.remove(link.key());
            refreshLinks();
        }));
        n.addAction(NotificationAction.createSimpleExpiring(BootServersBundle.message("monitor.link.mute"), () -> muted.add(link.key())));
        n.notify(project);
    }
}
