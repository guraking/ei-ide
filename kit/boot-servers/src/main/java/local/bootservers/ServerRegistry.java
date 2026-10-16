package local.bootservers;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실행 설정 이름(WAS 는 was:id)별 마지막 실행 기록. 서버 창과 프로젝트 트리가 상태 · 포트 · 실행 시간을 여기서 읽는다.
 * 기록은 IDE 를 끄면 사라진다(디스크에 남기지 않는다).
 */
@Service(Service.Level.PROJECT)
public final class ServerRegistry {

    // 스프링 부트 2.x 「Tomcat started on port(s): 8080 (http)」, 3.x 「Tomcat started on port 8080 (http)」, Netty · Undertow 도 같은 꼴.
    private static final Pattern PORT = Pattern.compile("started on port(?:\\(s\\))?:? (\\d+)");
    // 스프링 부트 「Started SuppApplication in 6.4 seconds」, Tomcat 「Server startup in [506] milliseconds」,
    // WildFly 「WFLYSRV0025: ... started in」 · 오류가 있어도 뜬 경우 「WFLYSRV0026」, WebLogic 「<BEA-000360> The server started in RUNNING mode」,
    // JEUS 「JEUS server ... RUNNING」 · 「Successfully started the server」 — JEUS 문구는 판마다 달라 실제 로그로 확인하지 못한 최선 추정이다.
    private static final Pattern STARTED = Pattern.compile(
            "Started \\S+ in [\\d.]+ seconds|Server startup in|WFLYSRV0025|WFLYSRV0026|BEA-000360|JEUS server .*RUNNING|Successfully started the server");
    // 시작 실패 이유로 보여 줄 줄. 스프링 부트 실패 보고의 대표 문구만 잡는다.
    private static final Pattern FAILURE = Pattern.compile("was already in use|Address already in use|APPLICATION FAILED TO START|Caused by: .*");

    /** 한 번의 실행. 출력 스레드에서 쓰고 화면 스레드에서 읽으므로 volatile. */
    public static final class Run {
        public final long startMillis = System.currentTimeMillis();
        public volatile boolean started;
        public volatile Integer port;
        public volatile Integer exitCode;
        public volatile boolean stoppedByUser;
        public volatile String error;

        /** 시작이 끝나기 전에 사용자가 아닌 이유로 끝났으면 실패로 본다. */
        public boolean failed() {
            return exitCode != null && !started && !stoppedByUser;
        }
    }

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public static ServerRegistry getInstance(@NotNull Project project) {
        return project.getService(ServerRegistry.class);
    }

    public Run get(String name) {
        return runs.get(name);
    }

    /** 새 실행을 기록하고 출력에서 포트 · 시작 완료 · 실패 이유를 읽는다. 같은 이름의 이전 기록은 바꾼다. */
    void track(String name, ProcessHandler handler) {
        Run run = new Run();
        runs.put(name, run);
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                String text = event.getText();
                if (run.port == null) {
                    Matcher m = PORT.matcher(text);
                    if (m.find()) {
                        run.port = Integer.valueOf(m.group(1));
                    }
                }
                if (!run.started && STARTED.matcher(text).find()) {
                    run.started = true;
                }
                if (!run.started && run.error == null) {
                    Matcher m = FAILURE.matcher(text);
                    if (m.find()) {
                        run.error = text.strip();
                    }
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                run.exitCode = event.getExitCode();
            }
        });
    }

    /** 실행 설정 이름이 바뀌면 기록도 새 이름으로 옮긴다. 실행 중에 이름을 바꿔도 상태 · 포트가 이어진다. */
    void rename(String oldName, String newName) {
        Run run = runs.remove(oldName);
        if (run != null) {
            runs.put(newName, run);
        }
    }

    /** 사용자가 중지한 실행은 종료 코드가 0 이 아니어도 실패로 치지 않는다. */
    void markStopped(String name) {
        Run run = runs.get(name);
        if (run != null) {
            run.stoppedByUser = true;
        }
    }
}
