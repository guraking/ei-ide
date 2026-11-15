package local.bootservers;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.CommonBundle;
import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.Executor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.execution.ui.actions.CloseAction;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.KillableProcess;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.remote.RemoteConfiguration;
import com.intellij.execution.remote.RemoteConfigurationType;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.task.ProjectTaskListener;
import com.intellij.task.ProjectTaskManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import local.bootservers.was.ExplodedSync;
import local.bootservers.was.JeusBase;
import local.bootservers.was.RunSupport;
import local.bootservers.was.TomcatBase;
import local.bootservers.was.WasSettings;
import local.bootservers.was.WeblogicBase;
import local.bootservers.was.WildflyBase;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 서버 창에 추가한 WAS 를 시작 · 중지 · 재시작 · 반영한다. Tomcat · JBoss/WildFly · WebLogic · JEUS 를 실행한다.
 * 시작하면 올릴 모듈을 먼저 빌드하고, WAS 마다 전용 실행 폴더를 꾸린 뒤 실행 창에 띄운다.
 * Tomcat 은 프로젝트 폴더를 그 자리에서 연결하고, WildFly · WebLogic · JEUS 는 펼친 war 폴더로 복사해 올린다.
 * 디버그로 시작하면 디버그 포트로 띄우고, JVM 의 대기 문구가 나오면 원격 디버거를 붙인다.
 * <p>
 * JEUS 는 {@link JeusBase} 의 명령 틀로 시작 · 중지 · 배포한다. 비밀번호는 설정에 두지 않고 처음 시작할 때 묻고,
 * 원하면 IDE 비밀번호 저장소에 기억한다. JEUS 의 JVM 옵션은 도메인 설정에서 읽으므로 디버그 포트를 이 플러그인이 열 수 없다.
 * 사용자가 도메인 설정의 JVM 옵션이나 시작 명령 틀의 {vmoptions} 로 jdwp 를 넣으면, 대기 문구를 보고 디버거만 붙인다.
 * 기본 틀은 명령줄 인자로 비밀번호를 넘기므로 실행 중에는 OS 프로세스 목록에서 보일 수 있다(실행 창 · 기록에는 가려서 보인다).
 * 현장 JEUS 가 파일에서 비밀번호를 읽을 수 있으면 틀의 {passwordFile} 로 바꿔 이를 피한다({@link JeusBase} 참고).
 * <p>
 * 시작 전에 HTTP · 관리(종료) · 디버그 포트가 비었는지 본다. 이미 쓰이면 다른 서버를 잘못 끄거나 그 서버에 붙지 않도록 띄우지 않는다.
 */
@Service(Service.Level.PROJECT)
public final class WasRunner {

    private static final Logger LOG = Logger.getInstance(WasRunner.class);
    private static final String TITLE = "WAS";
    private static final String LISTENING = "Listening for transport dt_socket at address";
    // 정상 종료 명령이 먹히지 않을 때 프로세스를 강제로 끝내기까지 기다리는 시간. Tomcat 은 짧게, 중지 명령이 JVM 을 하나 더 띄우고
    // 여러 앱을 내리는 WildFly · WebLogic · JEUS 는 길게(백신이 실시간 검사하는 PC 에서는 중지 명령 JVM 이 뜨는 데만 몇 초 걸린다).
    private static final int STOP_GRACE_SECONDS = 10;
    private static final int STOP_GRACE_LONG_SECONDS = 30;
    // 시작 완료 문구를 기다렸다가 보조 판정(HTTP 포트가 답함)을 시작하는 때와 묻는 간격.
    private static final long READY_DELAY_SECONDS = 5;
    private static final long READY_PERIOD_SECONDS = 3;
    // 디버거 붙이기를 부탁한 뒤 디버그 세션이 생겼는지 보는 때. 없으면 한 번 다시 붙이고, 그래도 없으면 알린다.
    private static final long DEBUG_ATTACH_CHECK_SECONDS = 15;
    // 디버거 대기 문구가 출력 두 조각에 걸쳐도 찾도록 앞 조각에서 남겨 두는 글자 수.
    private static final int LISTENING_TAIL_CHARS = 200;
    // JEUS 배포 명령 하나를 기다리는 한도. 비밀번호 입력 등을 기다리며 멈춘 명령이 작업 스레드를 붙잡지 않게 한다.
    private static final int DEPLOY_TIMEOUT_SECONDS = 180;
    // 비밀번호 저장소에서 이 플러그인의 항목을 구분하는 이름.
    private static final String CREDENTIAL_SUBSYSTEM = "KingGura WAS";
    // plugin.xml 의 알림 묶음 id.
    private static final String NOTIFICATION_GROUP = "서버 실행";
    // 서버 실행 폴더 안 JEUS 비밀번호 파일 이름. 틀에 {passwordFile} 이 있을 때만 만들고, 서버가 꺼지면 지운다.
    private static final String PASSWORD_FILE = "jeus-password";
    // 스크립트 · 도메인 설정이 JVM 옵션을 정하는 WAS 에 출력 인코딩만 더하는 환경 변수. JVM 이 명령줄 옵션보다 먼저 읽는다.
    private static final String JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";
    // WildFly standalone.conf 의 기본 JVM 옵션. JAVA_OPTS 를 주면 기본값을 통째로 대신하므로 VM 옵션 앞에 다시 넣는다.
    private static final String WILDFLY_DEFAULT_OPTS =
            "-Xms64m -Xmx512m -XX:MetaspaceSize=96M -XX:MaxMetaspaceSize=256m -Djava.net.preferIPv4Stack=true "
            + "-Djboss.modules.system.pkgs=org.jboss.byteman -Djava.awt.headless=true";

    private final Project project;
    // 콘솔 · 「오류」 칸 사이 비율을 기억할 열쇠(WAS 서버 모두 같이 쓴다).
    private static final String ERRORS_SPLIT_KEY = "Moa.WasConsole.errors.split";
    private final Map<String, ProcessHandler> running = new ConcurrentHashMap<>();
    private final Map<String, Boolean> debugMode = new ConcurrentHashMap<>();
    // 살아 있는 프로세스를 띄울 때 쓴 설정(서버 id → 설정). 실행 중에 설정을 고쳐도 중지는 띄운 포트 · 명령으로 보낸다.
    private final Map<String, WasSettings.Server> launchedWith = new ConcurrentHashMap<>();
    // 이번 IDE 실행 동안 쓰는 JEUS 비밀번호(서버 id → 비밀번호). 기억하지 않기로 한 비밀번호도 중지 · 배포에 쓰려고 둔다.
    private final Map<String, String> jeusPasswords = new ConcurrentHashMap<>();
    // 시작 전 빌드 · 포트 점검 · 실행 폴더 꾸미기 중인 서버 id. 프로세스가 생기거나 실패 · 취소될 때까지 두어, 그사이 다시 누른 시작을 막는다.
    // 값은 시작 한 번마다 새로 만든 표(중지로 그만둔 뒤 다시 누른 시작과 앞 시작의 늦은 빌드 결과를 가른다).
    private final Map<String, Object> launching = new ConcurrentHashMap<>();
    // 재시작을 걸어 두고 종료를 기다리는 서버 id. 여러 번 눌러도 다시 띄우기는 한 번만 한다.
    private final Set<String> restarting = ConcurrentHashMap.newKeySet();
    // 서버 id → 그 서버의 반영(펼친 war 복사 · 배포 명령)을 한 번에 하나씩 돌리는 실행기. 두 반영이 같은 폴더를 함께 지우고 복사하면 가짜 실패가 난다.
    // 서버마다 따로 두어 한 서버의 느린 JEUS 배포가 다른 서버의 반영을 막지 않게 한다.
    private final Map<String, ExecutorService> syncs = new ConcurrentHashMap<>();

    public WasRunner(Project project) {
        this.project = project;
        // 저장 → 자동 빌드가 끝나면, 「자동 반영」 이 켜진 실행 중인 WildFly · WebLogic · JEUS 에 바뀐 파일을 올린다.
        // Tomcat 은 실행 폴더가 컴파일 결과를 직접 보므로 할 일이 없다.
        project.getMessageBus().connect().subscribe(ProjectTaskListener.TOPIC, new ProjectTaskListener() {
            @Override
            public void finished(@NotNull ProjectTaskManager.Result result) {
                if (result.isAborted() || result.hasErrors()) {
                    return;
                }
                // 설정 창이 목록을 고치는 사이에도 돌 수 있으므로 복사본을 돈다.
                for (WasSettings.Server s : new ArrayList<>(WasSettings.getInstance(project).servers())) {
                    if (s.reloadable && copiesApps(s) && isLive(s)) {
                        syncCopied(s);
                    }
                }
            }
        });
    }

    public static WasRunner getInstance(@NotNull Project project) {
        return project.getService(WasRunner.class);
    }

    static String registryKey(WasSettings.Server server) {
        return "was:" + server.id;
    }

    /** 종류별 설치 폴더 판별. 맞지 않으면 null. */
    static String detectVersion(WasSettings.Kind kind, Path home) {
        return switch (kind) {
            case TOMCAT -> TomcatBase.detectVersion(home);
            case WILDFLY -> WildflyBase.detectVersion(home);
            case WEBLOGIC -> WeblogicBase.detectVersion(home);
            case JEUS -> JeusBase.detectVersion(home);
        };
    }

    /** 앱을 펼친 war 로 복사해 올리는 종류인지(Tomcat 은 그 자리에서 연결한다). */
    private static boolean copiesApps(WasSettings.Server server) {
        return server.kind() != WasSettings.Kind.TOMCAT;
    }

    /** 서버 전용 실행 폴더. 서버 창의 「로그 파일 보기」 가 로그 폴더를 찾을 때도 쓴다. */
    static Path baseDir(WasSettings.Server server) {
        return PathManager.getSystemDir().resolve("kinggura-was").resolve(server.id);
    }

    /** 시작 전 빌드 · 포트 점검 · 실행 폴더 꾸미기 중이라 프로세스가 아직 없는지. 서버 묶음이 시작을 기다릴 때 쓴다. */
    boolean isLaunching(WasSettings.Server server) {
        return launching.containsKey(server.id);
    }

    /**
     * 시작 전 준비(빌드 · 실행 폴더 꾸미기)를 그만둔다. 빌드가 끝나지 않고 걸려도 중지 단추로 풀 수 있게 한다.
     * 이미 시작한 빌드는 그대로 끝나고, 끝난 뒤 서버를 띄우지 않는다. 준비 중이 아니었으면 false.
     */
    boolean cancelLaunch(WasSettings.Server server) {
        return handler(server) == null && launching.remove(server.id) != null;
    }

    /** 살아 있는 프로세스. 없거나 끝났으면 null. */
    ProcessHandler handler(WasSettings.Server server) {
        ProcessHandler handler = running.get(server.id);
        return handler != null && !handler.isProcessTerminated() ? handler : null;
    }

    ServerLauncher.State state(WasSettings.Server server) {
        ServerRegistry.Run run = ServerRegistry.getInstance(project).get(registryKey(server));
        ProcessHandler handler = running.get(server.id);
        if (handler != null && !handler.isProcessTerminated()) {
            return run != null && run.started ? ServerLauncher.State.RUNNING : ServerLauncher.State.STARTING;
        }
        return run != null && run.failed() ? ServerLauncher.State.FAILED : ServerLauncher.State.STOPPED;
    }

    boolean isLive(WasSettings.Server server) {
        ServerLauncher.State s = state(server);
        return s == ServerLauncher.State.RUNNING || s == ServerLauncher.State.STARTING;
    }

    /**
     * 화면 스레드에서 부른다. 올릴 모듈을 빌드하고, 성공하면 이어서 띄운다.
     * @return 빌드나 띄우기를 시작했으면 true. 이미 실행 중이거나 알림만 띄우고 그만뒀으면 false.
     */
    boolean start(WasSettings.Server server, boolean debug) {
        if (isLive(server) || launching.containsKey(server.id)) {
            return false;
        }
        if (!server.kind().supported) {
            Messages.showInfoMessage(project, BootServersBundle.message("was.not.supported", server.kind().label), TITLE);
            return false;
        }
        if (detectVersion(server.kind(), Path.of(server.home)) == null) {
            Messages.showErrorDialog(project, BootServersBundle.message("was.not.home", server.kind().label, server.home), TITLE);
            return false;
        }
        // Tomcat 만 미리 본다. catalina 는 JAVA_HOME · JRE_HOME 이 없으면 바로 끝난다. WebLogic(setDomainEnv) · WildFly(PATH 의 java)는 스스로 찾는다.
        if (server.kind() == WasSettings.Kind.TOMCAT && projectJdkHome() == null
            && isBlank(System.getenv("JAVA_HOME")) && isBlank(System.getenv("JRE_HOME"))) {
            Messages.showErrorDialog(project, BootServersBundle.message("was.no.jdk", server.kind().label), TITLE);
            return false;
        }
        if (server.kind() == WasSettings.Kind.JEUS && jeusPassword(server, true) == null) {
            return false;
        }
        List<Module> modules = deployedModules(server, true);
        Object token = new Object();
        if (modules == null || launching.putIfAbsent(server.id, token) != null) {
            return false;
        }
        if (modules.isEmpty()) {
            launch(server, debug, token);
            return true;
        }
        ProjectTaskManager.getInstance(project).build(modules.toArray(Module[]::new)).onSuccess(result -> {
            if (project.isDisposed()) {
                launching.remove(server.id, token);
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                // 빌드하는 사이 중지를 눌렀으면(cancelLaunch) 이 시작은 띄우지 않는다(그 뒤 다시 누른 시작은 제 표로 따로 간다).
                if (launching.get(server.id) != token) {
                    return;
                }
                if (result.isAborted() || result.hasErrors()) {
                    launching.remove(server.id, token);
                    Messages.showErrorDialog(project, BootServersBundle.message("was.build.failed", server.name), TITLE);
                }
                else {
                    launch(server, debug, token);
                }
            }, project.getDisposed());
        }).onError(e -> launching.remove(server.id, token));
        return true;
    }

    /** 올린 모듈의 컴파일 결과 모듈 목록. 프로젝트에 없는 모듈은 report 면 알리고 null, 아니면 건너뛴다. */
    private List<Module> deployedModules(WasSettings.Server server, boolean report) {
        List<Module> result = new ArrayList<>();
        for (WasSettings.Deployment d : server.deployments) {
            Module folder = ModuleManager.getInstance(project).findModuleByName(d.module);
            if (folder == null) {
                if (report) {
                    Messages.showErrorDialog(project, BootServersBundle.message("was.module.missing", d.module), TITLE);
                    return null;
                }
                continue;
            }
            result.add(classModule(folder));
        }
        return result;
    }

    private List<TomcatBase.App> apps(WasSettings.Server server, Path base) throws IOException {
        List<TomcatBase.App> apps = new ArrayList<>();
        for (WasSettings.Deployment d : server.deployments) {
            Module folder = ModuleManager.getInstance(project).findModuleByName(d.module);
            if (folder != null) {
                apps.add(app(folder, d.contextPath, base));
            }
        }
        return apps;
    }

    /**
     * 화면 스레드에서 부른다. 포트 점검 · 실행 폴더 꾸미기 · 프로세스 만들기는 작업 스레드에서 하고, 실행 창 띄우기만 화면 스레드에서 한다.
     * start 가 launching 에 넣어 둔 서버 id 는 여기서 끝날 때(프로세스를 만들었거나 실패) 뺀다.
     */
    private void launch(WasSettings.Server server, boolean debug, Object token) {
        if (isLive(server)) {
            launching.remove(server.id, token);
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                prepareAndRun(server, debug, token);
            }
            catch (RuntimeException e) {
                showError(BootServersBundle.message("was.launch.failed", server.name, e.getMessage()));
            }
            finally {
                launching.remove(server.id, token);
            }
        });
    }

    /** 작업 스레드에서 부른다. token 이 launching 에서 빠지면(중지로 그만둠) 프로세스를 띄우지 않고, 띄운 직후 빠졌으면 바로 끝낸다. */
    private void prepareAndRun(WasSettings.Server server, boolean debug, Object token) {
        String busy = busyPorts(server, debug);
        if (busy != null) {
            showError(BootServersBundle.message("was.port.busy", busy, server.name));
            return;
        }
        Path base = baseDir(server);
        GeneralCommandLine cmd;
        List<TomcatBase.App> apps;
        try {
            apps = ReadAction.compute(() -> apps(server, base));
            if (server.kind() == WasSettings.Kind.WILDFLY) {
                WildflyBase.prepare(Path.of(server.home), base, apps);
                cmd = wildflyRun(server, base, debug);
            }
            else if (server.kind() == WasSettings.Kind.WEBLOGIC) {
                WeblogicBase.prepare(Path.of(server.home), apps);
                cmd = weblogicRun(server, debug);
            }
            else if (server.kind() == WasSettings.Kind.JEUS) {
                JeusBase.prepare(base, apps);
                cmd = jeusRun(server, debug);
            }
            else {
                TomcatBase.prepare(Path.of(server.home), base, server.httpPort, server.shutdownPort, apps, server.reloadable);
                cmd = tomcatRun(server, base, debug);
            }
        }
        catch (IOException e) {
            showError(BootServersBundle.message("was.run.dir.failed", e.getMessage()));
            return;
        }

        if (launching.get(server.id) != token) {
            return;
        }
        boolean jeus = server.kind() == WasSettings.Kind.JEUS;
        KillableColoredProcessHandler handler;
        try {
            // JEUS 명령줄에는 비밀번호가 들어 있으므로 실행 창에 보일 명령줄은 가린 것으로 준다.
            handler = jeus
                    ? new KillableColoredProcessHandler(cmd.createProcess(),
                            JeusBase.mask(cmd.getCommandLineString(), jeusPasswords.get(server.id)), StandardCharsets.UTF_8)
                    : new KillableColoredProcessHandler(cmd);
        }
        catch (ExecutionException e) {
            showError(BootServersBundle.message("was.launch.failed", server.kind().label, e.getMessage()));
            return;
        }
        ProcessTerminatedListener.attach(handler);
        ServerRegistry registry = ServerRegistry.getInstance(project);
        registry.track(registryKey(server), handler);
        ServerRegistry.Run run = registry.get(registryKey(server));
        run.port = server.httpPort;
        running.put(server.id, handler);
        // 프로세스를 만드는 사이 중지를 눌렀으면(그때는 아직 running 에 없어 준비만 그만뒀다) 지금 끝낸다.
        if (launching.get(server.id) != token) {
            running.remove(server.id, handler);
            registry.markStopped(registryKey(server));
            killNow(handler);
            if (jeus) {
                deletePasswordFile(server);
            }
            return;
        }
        launchedWith.put(server.id, server);
        debugMode.put(server.id, debug);
        // 기록기의 출력 감시가 먼저 붙어 있으므로, 같은 출력에서 시작 완료(run.started)가 이미 반영된 뒤에 아래가 불린다.
        // 출력은 표준 출력 · 오류 두 스레드에서 오므로 한 번만 할 일은 원자 표시로 막는다.
        AtomicBoolean attached = new AtomicBoolean();
        AtomicBoolean deployed = new AtomicBoolean();
        Runnable deployOnce = () -> {
            if (jeus && deployed.compareAndSet(false, true)) {
                syncsOf(server).execute(() -> {
                    for (TomcatBase.App app : apps) {
                        jeusDeploy(server, handler, app);
                    }
                });
            }
        };
        handler.addProcessListener(new ProcessListener() {
            private final StringBuilder tail = new StringBuilder();

            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (debug && !attached.get() && listening(event.getText()) && attached.compareAndSet(false, true)) {
                    ApplicationManager.getApplication().invokeLater(() -> attachDebugger(server, handler, true), project.getDisposed());
                }
                if (run.started) {
                    deployOnce.run();
                }
            }

            private boolean listening(String text) {
                synchronized (tail) {
                    tail.append(text);
                    boolean found = tail.indexOf(LISTENING) >= 0;
                    if (tail.length() > LISTENING_TAIL_CHARS) {
                        tail.delete(0, tail.length() - LISTENING_TAIL_CHARS);
                    }
                    return found;
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                running.remove(server.id, handler);
                launchedWith.remove(server.id, server);
                if (jeus) {
                    deletePasswordFile(server);
                }
            }
        });
        // Tomcat 은 시작 끝 문구가 PC 언어로 나오고(한국어 윈도우면 한국어) JEUS 는 판마다 달라, 문구를 못 읽어도 HTTP 포트가 요청에 답하면 시작이 끝난 것으로 본다.
        // WildFly · WebLogic 은 문구에 언어와 상관없는 번호(WFLYSRV0025 · BEA-000360)가 있고, WildFly 는 앱을 올리기 전에 포트를 열므로 보조 판정을 쓰지 않는다.
        if (server.kind() == WasSettings.Kind.TOMCAT || jeus) {
            int httpPort = server.httpPort;
            ServerRegistry.watchReady(run, handler, READY_DELAY_SECONDS, READY_PERIOD_SECONDS, () -> RunSupport.httpAnswers(httpPort), deployOnce);
        }
        // 실행 창이 startNotify 를 부르기 전에는 출력을 읽지 않으므로, 화면 스레드로 넘기는 사이의 출력도 잃지 않는다.
        // 그사이 프로젝트가 닫혔으면 출력을 읽을 창이 없어 서버가 멈춘 채 남으므로 끝낸다. 끝내기 요청도 startNotify 뒤에야 실행되므로 먼저 부른다.
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                killNow(handler);
                return;
            }
            showConsole(server, handler);
        });
    }

    /**
     * 실행 창에 서버 탭을 띄우고 출력을 붙인다. IntelliJ RunContentExecutor 와 같되, 재실행 · 정지 · 닫기 아래에 콘솔 기본 동작을 붙인다.
     * 화면 스레드에서 부른다.
     */
    private void showConsole(WasSettings.Server server, ProcessHandler handler) {
        FileDocumentManager.getInstance().saveAllDocuments();
        ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        Executor executor = DefaultRunExecutor.getRunExecutorInstance();
        DefaultActionGroup actions = new DefaultActionGroup();
        // 콘솔 오른쪽 「오류」 칸. 출력 읽기는 실행 창과 함께 끝난다.
        ErrorWatcher watcher = new ErrorWatcher(handler);
        RunContentDescriptor[] shown = new RunContentDescriptor[1];
        ErrorPanel errors = new ErrorPanel(project, watcher,
                () -> console instanceof ConsoleViewImpl impl ? impl.getEditor() : null, () -> {},
                ServerRegistry.getInstance(project).get(registryKey(server)), count -> {}, server.name,
                () -> ErrorPanel.revealRun(project, shown[0]));
        JPanel panel = ConsolePanel.create(console, actions, errors, ERRORS_SPLIT_KEY);
        RunContentDescriptor descriptor = new RunContentDescriptor(console, handler, panel, server.name);
        shown[0] = descriptor;
        descriptor.setActivateToolWindowWhenAdded(true);
        Disposer.register(descriptor, console);
        Disposer.register(descriptor, watcher);

        AnAction rerun = DumbAwareAction.create(CommonBundle.message("action.text.rerun"), AllIcons.Actions.Restart, e -> restart(server));
        rerun.registerCustomShortcutSet(CommonShortcuts.getRerun(), panel);
        actions.add(rerun);
        actions.add(new DumbAwareAction(ExecutionBundle.messagePointer("action.AnAction.text.stop"),
                ExecutionBundle.messagePointer("action.AnAction.description.stop"), AllIcons.Actions.Suspend) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                stop(server);
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!handler.isProcessTerminated());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        actions.add(new CloseAction(executor, descriptor, project));
        ConsolePanel.addConsoleActions(console, actions);

        RunContentManager.getInstance(project).showRunContent(executor, descriptor);
        console.attachToProcess(handler);
        handler.startNotify();
    }

    /** 이 서버가 쓸 포트 중 이미 쓰이는 것(예: "HTTP 8080, 관리 9990"). 모두 비었으면 null. 디버그 포트는 디버그로 띄울 때만 본다. */
    private static String busyPorts(WasSettings.Server server, boolean debug) {
        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("HTTP", server.httpPort);
        // WebLogic 은 두 번째 포트를 쓰지 않는다.
        if (server.kind() != WasSettings.Kind.WEBLOGIC) {
            ports.put(server.kind() == WasSettings.Kind.TOMCAT ? BootServersBundle.message("was.port.shutdown") : BootServersBundle.message("was.port.admin"), server.shutdownPort);
        }
        if (debug) {
            ports.put(BootServersBundle.message("was.port.debug"), server.debugPort);
        }
        List<String> busy = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ports.entrySet()) {
            if (RunSupport.portInUse(e.getValue())) {
                busy.add(e.getKey() + " " + e.getValue());
            }
        }
        return busy.isEmpty() ? null : String.join(", ", busy);
    }

    /** 어느 스레드에서든 부른다. */
    /** 실행 창 없이 프로세스를 끝낸다: 출력 읽기를 시작해(끝내기 요청이 그 뒤에 실행된다) 자손 프로세스까지 강제로 끝낸다. */
    private static void killNow(ProcessHandler handler) {
        if (!handler.isStartNotified()) {
            handler.startNotify();
        }
        handler.destroyProcess();
        if (handler instanceof KillableProcess killable && killable.canKillProcess()) {
            killable.killProcess();
        }
    }

    private void showError(String message) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project, message, TITLE), project.getDisposed());
    }

    private GeneralCommandLine tomcatRun(WasSettings.Server server, Path base, boolean debug) {
        StringBuilder opts = new StringBuilder("-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8");
        if (debug) {
            // suspend=y: 디버거가 붙기 전에는 시작하지 않으므로 시작 중 중단점도 걸린다.
            opts.append(" -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:").append(server.debugPort);
        }
        if (!server.vmOptions.isBlank()) {
            opts.append(' ').append(server.vmOptions.strip());
        }
        return tomcatScript(server, base, "run").withEnvironment("CATALINA_OPTS", opts.toString());
    }

    private GeneralCommandLine tomcatScript(WasSettings.Server server, Path base, String command) {
        return withJdk(script(server, "catalina", command), base)
                .withEnvironment("CATALINA_HOME", server.home)
                .withEnvironment("CATALINA_BASE", base.toString());
    }

    private GeneralCommandLine wildflyRun(WasSettings.Server server, Path base, boolean debug) {
        GeneralCommandLine cmd = withJdk(script(server, "standalone",
                "-Djboss.server.base.dir=" + base,
                "-Djboss.http.port=" + server.httpPort,
                "-Djboss.management.http.port=" + server.shutdownPort), base);
        if (debug) {
            // standalone 스크립트가 jdwp 를 suspend=n 으로 붙인다. 디버거는 대기 문구를 보고 붙는다.
            cmd.addParameters("--debug", String.valueOf(server.debugPort));
        }
        if (!server.vmOptions.isBlank()) {
            cmd.withEnvironment("JAVA_OPTS", WILDFLY_DEFAULT_OPTS + " " + server.vmOptions.strip());
        }
        return withOutputEncoding(cmd, server.vmOptions);
    }

    /**
     * 실행 창은 출력을 UTF-8 로 읽는다. JVM 옵션을 스크립트 · 도메인 설정이 정하는 WAS(WildFly · JEUS)는 그 설정을 덮지 않도록
     * JAVA_TOOL_OPTIONS 에 출력 인코딩 옵션을 더한다. 이미 받은 JAVA_TOOL_OPTIONS 와 사용자 VM 옵션에 있는 옵션은 다시 넣지 않는다.
     */
    private static GeneralCommandLine withOutputEncoding(GeneralCommandLine cmd, String vmOptions) {
        String inherited = Objects.requireNonNullElse(System.getenv(JAVA_TOOL_OPTIONS), "");
        String added = RunSupport.encodingOptions(inherited + " " + vmOptions);
        return added.isEmpty() ? cmd : cmd.withEnvironment(JAVA_TOOL_OPTIONS, (inherited + " " + added).strip());
    }

    /**
     * 도메인 폴더의 startWebLogic 을 띄운다. 듣는 포트는 -Dweblogic.ListenPort 로 바꾼다.
     * boot.properties 가 없는 도메인은 처음에 실행 창에서 관리자 계정을 묻는다(실행 창에 입력하면 된다).
     */
    private GeneralCommandLine weblogicRun(WasSettings.Server server, boolean debug) {
        // WebLogic 은 예전부터 file.encoding 도 UTF-8 로 띄웠다(사용자 VM 옵션에 있으면 그 값을 둔다).
        StringBuilder opts = new StringBuilder("-Dweblogic.ListenPort=").append(server.httpPort);
        if (!server.vmOptions.contains("-Dfile.encoding=")) {
            opts.append(" -Dfile.encoding=UTF-8");
        }
        String encoding = RunSupport.encodingOptions(server.vmOptions);
        if (!encoding.isEmpty()) {
            opts.append(' ').append(encoding);
        }
        if (debug) {
            opts.append(" -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:").append(server.debugPort);
        }
        if (!server.vmOptions.isBlank()) {
            opts.append(' ').append(server.vmOptions.strip());
        }
        return withJdk(script(server, "startWebLogic"), Path.of(server.home)).withEnvironment("JAVA_OPTIONS", opts.toString());
    }

    /**
     * JEUS 시작 명령. {vmoptions} 에는 VM 옵션과, 디버그면 jdwp(suspend=n: 서버가 디버거를 기다리지 않음)를 넣는다.
     * 기본 틀은 {vmoptions} 를 쓰지 않는다 — JEUS 는 JVM 옵션을 도메인 설정에서 읽기 때문이다.
     */
    private GeneralCommandLine jeusRun(WasSettings.Server server, boolean debug) throws IOException {
        String vm = server.vmOptions.strip();
        if (debug) {
            vm = (vm + " -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:" + server.debugPort).strip();
        }
        return jeusCommand(server, server.startCommand, JeusBase.DEFAULT_START, Map.of("vmoptions", vm));
    }

    /**
     * JEUS 명령 틀(비어 있으면 fallback)을 채워 명령줄로 만든다. JAVA_HOME 은 건드리지 않는다 —
     * JEUS 스크립트는 설치 때 정한 JDK(bin/jeus.properties)를 쓰고, 지원 JDK 판이 프로젝트 JDK 와 다를 수 있다.
     * 틀에 {passwordFile} 이 있으면 사용자만 읽는 비밀번호 파일을 만든다(이미 있으면 지금 비밀번호로 다시 쓴다).
     *
     * @throws IOException 비밀번호 파일을 만들지 못한 경우
     */
    private GeneralCommandLine jeusCommand(WasSettings.Server server, String template, String fallback, Map<String, String> extra)
            throws IOException {
        String chosen = template.isBlank() ? fallback : template;
        String password = jeusPasswords.getOrDefault(server.id, "");
        Map<String, String> values = new HashMap<>(extra);
        values.put("home", server.home);
        values.put("domain", server.jeusDomain);
        values.put("server", server.jeusServer);
        values.put("user", server.jeusUser);
        values.put("password", password);
        values.put("host", "localhost");
        values.put("port", String.valueOf(server.shutdownPort));
        if (chosen.contains(JeusBase.PASSWORD_FILE_TOKEN)) {
            // 지난 실행이 비정상으로 끝나 남은 파일에 옛 비밀번호가 있을 수 있으므로 늘 지금 비밀번호로 다시 쓴다.
            values.put("passwordFile", JeusBase.writePasswordFile(passwordFile(server), password).toString());
        }
        List<String> argv = JeusBase.command(chosen, values, SystemInfo.isWindows);
        return withOutputEncoding(new GeneralCommandLine(argv).withWorkDirectory(server.home).withCharset(StandardCharsets.UTF_8),
                server.vmOptions);
    }

    private Path passwordFile(WasSettings.Server server) {
        return baseDir(server).resolve(PASSWORD_FILE);
    }

    private void deletePasswordFile(WasSettings.Server server) {
        try {
            Files.deleteIfExists(passwordFile(server));
        }
        catch (IOException e) {
            LOG.warn("JEUS 비밀번호 파일을 지우지 못했습니다: " + passwordFile(server), e);
        }
    }

    /**
     * 앱 하나에 배포 명령을 돌리고, 가린 명령줄과 출력을 그 서버의 실행 창에 붙인다. 작업 스레드에서 부른다.
     * 실패(종료 코드 0 이 아님 · 시간 초과 · 실행 불가)는 실행 창에 오류 글로 알린다.
     */
    private void jeusDeploy(WasSettings.Server server, ProcessHandler console, TomcatBase.App app) {
        String name = ExplodedSync.warName(app.contextPath());
        String failure;
        try {
            // jeusadmin 의 따옴표 안 명령에 들어가므로 역슬래시 대신 빗금으로 준다(윈도우 자바도 빗금 경로를 받는다).
            GeneralCommandLine cmd = jeusCommand(server, server.deployCommand, JeusBase.DEFAULT_DEPLOY, Map.of(
                    "path", JeusBase.warDir(baseDir(server), app.contextPath()).toString().replace('\\', '/'),
                    "name", name,
                    "ctx", app.contextPath())).withRedirectErrorStream(true);
            String shown = JeusBase.mask(cmd.getCommandLineString(), jeusPasswords.get(server.id));
            console.notifyTextAvailable("\n> " + shown + "\n", ProcessOutputTypes.SYSTEM);
            ProcessOutput out = new CapturingProcessHandler(cmd.createProcess(), StandardCharsets.UTF_8, shown)
                    .runProcess((int) TimeUnit.SECONDS.toMillis(DEPLOY_TIMEOUT_SECONDS));
            console.notifyTextAvailable(out.getStdout(), ProcessOutputTypes.STDOUT);
            failure = out.isTimeout() ? BootServersBundle.message("was.deploy.timeout", DEPLOY_TIMEOUT_SECONDS)
                    : out.getExitCode() != 0 ? BootServersBundle.message("was.deploy.exit.code", out.getExitCode()) : null;
        }
        catch (ExecutionException e) {
            failure = BootServersBundle.message("was.deploy.command.failed", e.getMessage());
        }
        catch (IOException e) {
            failure = BootServersBundle.message("was.deploy.password.file.failed", e.getMessage());
        }
        if (failure != null) {
            console.notifyTextAvailable(BootServersBundle.message("was.deploy.failed", name, failure) + "\n", ProcessOutputTypes.STDERR);
        }
    }

    /**
     * JEUS 비밀번호. 이번 실행에 쓴 것 → 비밀번호 저장소 순으로 찾고, 없으면 ask 일 때만 묻는다(화면 스레드).
     * 물어서 받은 비밀번호는 저장소에 기억할지 한 번 묻는다. 취소하거나 빈 값이면 null.
     */
    private String jeusPassword(WasSettings.Server server, boolean ask) {
        String password = jeusPasswords.get(server.id);
        if (password != null) {
            return password;
        }
        CredentialAttributes key = jeusCredential(server);
        // 비밀번호 저장소(윈도우 자격 증명 · KeePass)는 느리거나 잠금 해제를 물을 수 있으므로 화면 스레드를 막지 않게 진행 창 아래서 읽는다.
        password = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> PasswordSafe.getInstance().getPassword(key), server.name, false, project);
        if (password == null && ask) {
            password = Messages.showPasswordDialog(project, BootServersBundle.message("was.jeus.password.prompt", server.jeusUser), server.name, null);
            if (password == null || password.isEmpty()) {
                return null;
            }
            if (Messages.showYesNoDialog(project, BootServersBundle.message("was.jeus.password.remember"), TITLE, null) == Messages.YES) {
                PasswordSafe.getInstance().setPassword(key, password);
            }
        }
        if (password != null) {
            jeusPasswords.put(server.id, password);
        }
        return password;
    }

    /** 기억한 JEUS 비밀번호를 지운다. 다음 시작 때 다시 묻는다. */
    void forgetJeusPassword(WasSettings.Server server) {
        jeusPasswords.remove(server.id);
        deletePasswordFile(server);
        PasswordSafe.getInstance().setPassword(jeusCredential(server), null);
    }

    private static CredentialAttributes jeusCredential(WasSettings.Server server) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(CREDENTIAL_SUBSYSTEM, server.id));
    }

    private static GeneralCommandLine script(WasSettings.Server server, String name, String... args) {
        // WebLogic 도메인 스크립트는 .cmd, Tomcat · WildFly 는 .bat
        String ext = SystemInfo.isWindows ? (server.kind() == WasSettings.Kind.WEBLOGIC ? ".cmd" : ".bat") : ".sh";
        Path bin = Path.of(server.home).resolve("bin").resolve(name + ext);
        return new GeneralCommandLine(bin.toString()).withParameters(args);
    }

    /** 프로젝트 JDK 로 띄운다. Tomcat setclasspath 는 JRE_HOME 이 있으면 그것을 먼저 쓰므로 둘 다 맞춘다. */
    private GeneralCommandLine withJdk(GeneralCommandLine cmd, Path base) {
        cmd.withWorkDirectory(base.toFile()).withCharset(StandardCharsets.UTF_8);
        String home = projectJdkHome();
        if (home != null) {
            cmd.withEnvironment("JAVA_HOME", home).withEnvironment("JRE_HOME", home);
        }
        return cmd;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 프로젝트 SDK 의 설치 폴더. SDK 가 없거나 폴더를 모르면 null. */
    private String projectJdkHome() {
        Sdk sdk = ReadAction.compute(() -> ProjectRootManager.getInstance(project).getProjectSdk());
        return sdk == null ? null : sdk.getHomePath();
    }

    /**
     * 정상 종료 명령(Tomcat: catalina stop, WildFly: 관리 포트로 :shutdown)을 보내고,
     * 명령을 띄운 때부터 graceSeconds 안에 끝나지 않으면 강제로 끝낸다. 시작 전 준비 중이면 준비를 그만둔다.
     * WebLogic 의 stopWebLogic 은 관리자 계정을 물을 수 있어 쓰지 않고 바로 프로세스 종료를 요청한다.
     * JEUS 는 중지 명령 틀(기본: stopServer)을 쓴다. 종료 포트 · 명령은 실행 중에 설정을 고쳤어도 띄울 때의 설정으로 보낸다.
     */
    void stop(WasSettings.Server edited) {
        ProcessHandler handler = running.get(edited.id);
        if (handler == null || handler.isProcessTerminated()) {
            cancelLaunch(edited);
            return;
        }
        WasSettings.Server server = launchedWith.getOrDefault(edited.id, edited);
        ServerRegistry.getInstance(project).markStopped(registryKey(server));
        int grace = graceSeconds(server);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(grace);
            try {
                if (server.kind() == WasSettings.Kind.WEBLOGIC) {
                    handler.destroyProcess();
                }
                else {
                    runDiscardingOutput(stopCommand(server), grace);
                }
                long leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (leftMillis > 0) {
                    handler.waitFor(leftMillis);
                }
            }
            catch (ExecutionException | IOException e) {
                LOG.info("정상 종료 명령을 띄우지 못해 강제로 끝냅니다: " + e.getMessage());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!handler.isProcessTerminated()) {
                handler.destroyProcess();
                // destroyProcess 는 정상 종료 신호일 수 있으므로 프로세스 트리를 강제로 끝낸다.
                if (handler instanceof KillableProcess killable && killable.canKillProcess()) {
                    killable.killProcess();
                }
            }
        });
    }

    private static int graceSeconds(WasSettings.Server server) {
        return server.kind() == WasSettings.Kind.TOMCAT ? STOP_GRACE_SECONDS : STOP_GRACE_LONG_SECONDS;
    }

    private GeneralCommandLine stopCommand(WasSettings.Server server) throws IOException {
        Path base = baseDir(server);
        return switch (server.kind()) {
            case WILDFLY -> withJdk(script(server, "jboss-cli", "--connect", "--controller=localhost:" + server.shutdownPort, "--command=:shutdown"), base);
            case JEUS -> jeusCommand(server, server.stopCommand, JeusBase.DEFAULT_STOP, Map.of());
            default -> tomcatScript(server, base, "stop");
        };
    }

    /**
     * 명령을 돌리고 seconds 까지 기다린다. 출력은 버린다(읽지 않으면 버퍼가 차서 명령이 멈출 수 있다).
     * 한도 안에 끝나지 않으면 자손 프로세스까지 강제로 끝낸다(윈도우 배치 파일은 자식 java 를 남긴다).
     */
    private static void runDiscardingOutput(GeneralCommandLine cmd, int seconds) throws ExecutionException, IOException, InterruptedException {
        Process process = cmd.toProcessBuilder()
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            for (ProcessHandle child : process.descendants().toList()) {
                child.destroyForcibly();
            }
            process.destroyForcibly();
        }
    }

    /**
     * 실행 중이면 끝난 뒤 같은 방식(실행 · 디버그)으로 다시 띄운다. 꺼져 있으면 그냥 띄운다. 이미 재시작을 기다리는 중이면 아무것도 하지 않는다.
     * 다시 띄울 때는 지금 저장된 설정(실행 중에 고친 포트 · 배포 모듈)을 쓴다. 실행 창 재실행 단추는 띄울 때의 설정을 넘기므로 id 로 다시 찾는다.
     */
    void restart(WasSettings.Server given) {
        WasSettings.Server server = current(given);
        boolean debug = debugMode.getOrDefault(server.id, false);
        ProcessHandler handler = running.get(server.id);
        if (handler == null || handler.isProcessTerminated()) {
            start(server, debug);
            return;
        }
        if (!restarting.add(server.id)) {
            return;
        }
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                restarting.remove(server.id);
                ApplicationManager.getApplication().invokeLater(() -> start(server, debug), project.getDisposed());
            }
        });
        // 붙이기 전에 이미 끝났으면 위 알림이 오지 않으므로 여기서 다시 띄운다.
        if (handler.isProcessTerminated() && restarting.remove(server.id)) {
            start(server, debug);
            return;
        }
        stop(server);
    }

    /** 같은 id 의 지금 저장된 설정. 지워졌으면 받은 설정. */
    private WasSettings.Server current(WasSettings.Server server) {
        for (WasSettings.Server s : WasSettings.getInstance(project).servers()) {
            if (s.id.equals(server.id)) {
                return s;
            }
        }
        return server;
    }

    /**
     * 「반영」 단추. 올린 모듈을 다시 빌드한다. Tomcat 은 바뀐 클래스를 보고 스스로 다시 올리고,
     * WildFly · WebLogic · JEUS 는 빌드가 끝나면 바뀐 파일만 복사해 그 앱만 다시 올린다(자동 반영이 꺼져 있어도 이 단추로는 올린다).
     */
    void publish(WasSettings.Server server) {
        List<Module> modules = deployedModules(server, false);
        if (modules == null || modules.isEmpty()) {
            return;
        }
        ProjectTaskManager.getInstance(project).build(modules.toArray(Module[]::new)).onSuccess(result -> {
            if (result.isAborted() || result.hasErrors() || server.reloadable) {
                return;
            }
            if (copiesApps(server)) {
                syncCopied(server);
            }
            else {
                reloadTomcatApps(server);
            }
        });
    }

    /**
     * 「다시 올리기」를 끈 Tomcat 은 바뀐 클래스를 스스로 보지 않으므로, 앱 컨텍스트 파일의 수정 시각을 바꿔 Tomcat(autoDeploy)이 그 앱만 다시 올리게 한다.
     * 실행 중이 아니면 할 일이 없다(다음 시작에 새 클래스로 뜬다).
     */
    private void reloadTomcatApps(WasSettings.Server edited) {
        if (state(edited) != ServerLauncher.State.RUNNING) {
            return;
        }
        // 컨텍스트 파일은 띄울 때의 배포 목록으로 만들었다.
        WasSettings.Server server = launchedWith.getOrDefault(edited.id, edited);
        syncsOf(server).execute(() -> {
            try {
                Path contexts = TomcatBase.contextDir(baseDir(server));
                for (WasSettings.Deployment d : server.deployments) {
                    Path file = contexts.resolve(TomcatBase.contextFileName(d.contextPath) + ".xml");
                    if (Files.isRegularFile(file)) {
                        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
                    }
                }
            }
            catch (IOException e) {
                LOG.warn("Tomcat 반영 실패: " + server.name, e);
                reportSyncFailure(server, e.getMessage());
            }
        });
    }

    private ExecutorService syncsOf(WasSettings.Server server) {
        return syncs.computeIfAbsent(server.id, id -> AppExecutorUtil.createBoundedApplicationPoolExecutor("Moa WAS 반영", 1));
    }

    private void syncCopied(WasSettings.Server server) {
        syncsOf(server).execute(() -> {
            Path base = baseDir(server);
            List<TomcatBase.App> apps;
            try {
                apps = ReadAction.compute(() -> apps(server, base));
            }
            catch (IOException e) {
                LOG.warn(server.kind().label + " 반영 실패: " + server.name, e);
                reportSyncFailure(server, e.getMessage());
                return;
            }
            // 앱 하나가 실패해도 다른 앱은 맞춘다.
            for (TomcatBase.App app : apps) {
                try {
                    if (server.kind() == WasSettings.Kind.WEBLOGIC) {
                        WeblogicBase.publish(Path.of(server.home), app);
                    }
                    else if (server.kind() == WasSettings.Kind.JEUS) {
                        publishJeus(server, base, app);
                    }
                    else {
                        WildflyBase.publish(base, app);
                    }
                }
                catch (IOException e) {
                    LOG.warn(server.kind().label + " 반영 실패: " + server.name, e);
                    reportSyncFailure(server, e.getMessage());
                }
            }
        });
    }

    /** JEUS 앱 하나를 맞추고, 바뀐 것이 있고 서버가 떠 있으면 배포 명령을 다시 돌린다. 몇 파일만 실패해도 들어간 파일은 배포한다. */
    private void publishJeus(WasSettings.Server server, Path base, TomcatBase.App app) throws IOException {
        // shortcut: 다시 올리기를 배포 명령을 한 번 더 돌리는 것으로 대신한다. 현장에서 재배포 명령(redeploy-application 등)이 따로 필요하면 틀을 나눈다.
        boolean changed;
        IOException partial = null;
        try {
            changed = JeusBase.publish(base, app);
        }
        catch (ExplodedSync.Partial e) {
            changed = e.changed;
            partial = e;
        }
        ProcessHandler console = running.get(server.id);
        if (changed && console != null && state(server) == ServerLauncher.State.RUNNING) {
            jeusDeploy(server, console, app);
        }
        if (partial != null) {
            throw partial;
        }
    }

    /** 반영 실패를 그 서버 실행 창(떠 있으면)과 알림으로 보인다. 어느 스레드에서든 부른다. */
    private void reportSyncFailure(WasSettings.Server server, String reason) {
        report(server, BootServersBundle.message("was.publish.failed", server.name, reason));
    }

    /** 글을 그 서버 실행 창(떠 있으면)과 오류 알림으로 보인다. 어느 스레드에서든 부른다. */
    private void report(WasSettings.Server server, String text) {
        ProcessHandler console = running.get(server.id);
        if (console != null) {
            console.notifyTextAvailable(text + "\n", ProcessOutputTypes.STDERR);
        }
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(TITLE, text, NotificationType.ERROR)
                .notify(project);
    }

    /**
     * 디버그 포트에 디버거를 붙인다. Tomcat · WebLogic 은 디버거가 붙을 때까지 서버가 멈춰 기다리므로,
     * DEBUG_ATTACH_CHECK_SECONDS 뒤에도 이 서버의 디버그 세션이 없으면 retry 면 한 번 더 붙이고, 아니면 콘솔과 알림으로 알린다. 화면 스레드에서 부른다.
     */
    private void attachDebugger(WasSettings.Server server, ProcessHandler process, boolean retry) {
        if (process.isProcessTerminated()) {
            return;
        }
        String name = BootServersBundle.message("was.debug.config", server.name);
        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(
                name, RemoteConfigurationType.getInstance().getConfigurationFactories()[0]);
        RemoteConfiguration remote = (RemoteConfiguration) settings.getConfiguration();
        remote.HOST = "localhost";
        remote.PORT = String.valueOf(server.debugPort);
        remote.SERVER_MODE = false;
        remote.USE_SOCKET_TRANSPORT = true;
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance());
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> ApplicationManager.getApplication().invokeLater(() -> {
            if (process.isProcessTerminated() || hasDebugSession(name)) {
                return;
            }
            if (retry) {
                attachDebugger(server, process, false);
            }
            else {
                report(server, BootServersBundle.message("was.debug.not.attached", server.name, server.debugPort));
            }
        }, project.getDisposed()), DEBUG_ATTACH_CHECK_SECONDS, TimeUnit.SECONDS);
    }

    private boolean hasDebugSession(String name) {
        for (XDebugSession session : XDebuggerManager.getInstance(project).getDebugSessions()) {
            if (name.equals(session.getSessionName()) && !session.isStopped()) {
                return true;
            }
        }
        return false;
    }

    /** Gradle 은 컴파일 결과가 「폴더 모듈.main」 에 있다. Maven 은 폴더 모듈 그대로. */
    static Module classModule(Module folder) {
        Module main = ModuleManager.getInstance(folder.getProject()).findModuleByName(folder.getName() + ".main");
        return main != null ? main : folder;
    }

    /** 모듈 하나를 앱으로: 웹 폴더(src/main/webapp) · 컴파일 결과 폴더 · 실행 시 필요한 jar(provided 제외). */
    private static TomcatBase.App app(Module folder, String contextPath, Path base) throws IOException {
        Path docBase = null;
        for (VirtualFile root : ModuleRootManager.getInstance(folder).getContentRoots()) {
            Path web = root.toNioPath().resolve("src/main/webapp");
            if (Files.isDirectory(web)) {
                docBase = web;
                break;
            }
        }
        if (docBase == null) {
            docBase = Files.createDirectories(base.resolve("empty-webapp").resolve(folder.getName()));
        }
        List<Path> dirs = new ArrayList<>();
        Set<Path> jars = new LinkedHashSet<>();
        Set<String> jarNames = new LinkedHashSet<>();
        // 방금 빌드한 결과 폴더는 VFS 에 아직 안 보일 수 있으므로 주소로 받아 디스크에서 확인한다.
        for (String url : OrderEnumerator.orderEntries(classModule(folder)).withoutSdk().runtimeOnly().productionOnly()
                .recursively().classes().getUrls()) {
            Path path = Path.of(VfsUtilCore.urlToPath(url).replaceFirst("!/?$", ""));
            if (url.startsWith("jar://")) {
                // 같은 이름의 jar 가 둘이면 /WEB-INF/lib 에 같은 자리를 차지하므로 먼저 나온 것만 쓴다.
                if (Files.isRegularFile(path) && jarNames.add(path.getFileName().toString())) {
                    jars.add(path);
                }
            }
            else if (Files.isDirectory(path)) {
                dirs.add(path);
            }
        }
        return new TomcatBase.App(contextPath, docBase, dirs, new ArrayList<>(jars));
    }
}
