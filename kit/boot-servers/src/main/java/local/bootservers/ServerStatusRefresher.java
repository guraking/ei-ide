package local.bootservers;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.KillableProcess;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * 애플리케이션 실행 설정(스프링 부트 서버)의 프로세스가 뜨면 실행 기록을 시작하고, 뜨거나 시작을 마치거나 끝날 때 프로젝트 트리를 다시 그려 상태 표시를 맞춘다.
 * 중지(서버 창 · 실행 창 · 툴바 · Ctrl+F2 어디서든) 뒤 STOP_GRACE_SECONDS 안에 끝나지 않으면 프로세스를 강제로 끝낸다.
 * 다른 종류의 실행 설정은 보지 않는다. WAS 는 WasRunner 가 직접 기록 · 중지한다.
 */
public final class ServerStatusRefresher implements ExecutionListener {

    // 정상 종료(스프링 컨텍스트 닫기)를 기다리는 한도. 넘으면 프로세스를 강제로 끝낸다.
    private static final int STOP_GRACE_SECONDS = 10;

    private final Project project;

    public ServerStatusRefresher(Project project) {
        this.project = project;
    }

    /** 서버 창 · 트리가 상태를 보이는 실행 설정(ServerLauncher.settingsOf 와 같은 애플리케이션 종류)인가. */
    private static boolean isServer(RunnerAndConfigurationSettings settings) {
        return settings != null && settings.getConfiguration() instanceof ApplicationConfiguration;
    }

    @Override
    public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        RunnerAndConfigurationSettings settings = env.getRunnerAndConfigurationSettings();
        if (!isServer(settings)) {
            return;
        }
        ServerRegistry registry = ServerRegistry.getInstance(project);
        registry.track(settings.getName(), handler);
        ServerRegistry.Run run = registry.get(settings.getName());
        // 기록기의 출력 감시가 먼저 붙어 있으므로, 같은 출력에서 시작 완료(run.started)가 이미 반영된 뒤에 아래가 불린다.
        // 서버 창이 닫혀 있어도 트리의 「시작 중」 이 「실행 중 :포트」 로 바뀌게 한다.
        handler.addProcessListener(new ProcessListener() {
            private boolean shownStarted;

            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (!shownStarted && run.started) {
                    shownStarted = true;
                    refreshLater();
                }
            }

            @Override
            public void processWillTerminate(@NotNull ProcessEvent event, boolean willBeDestroyed) {
                if (willBeDestroyed && handler instanceof KillableProcess killable) {
                    AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                        if (!handler.isProcessTerminated() && killable.canKillProcess()) {
                            killable.killProcess();
                        }
                    }, STOP_GRACE_SECONDS, TimeUnit.SECONDS);
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                // 실패 판정에 쓰는 종료 코드(run.exitCode)는 기록기가 이 알림에서 적으므로, 적힌 뒤에 다시 그린다.
                refreshLater();
            }
        });
        ProjectView.getInstance(project).refresh();
    }

    @Override
    public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler, int exitCode) {
        if (isServer(env.getRunnerAndConfigurationSettings())) {
            ProjectView.getInstance(project).refresh();
        }
    }

    private void refreshLater() {
        ApplicationManager.getApplication().invokeLater(() -> ProjectView.getInstance(project).refresh(), project.getDisposed());
    }
}
