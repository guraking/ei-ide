package local.bootservers;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** 프로세스가 뜨면 실행 기록을 시작하고, 뜨거나 끝날 때 프로젝트 트리를 다시 그려 상태 표시를 맞춘다. */
public final class ServerStatusRefresher implements ExecutionListener {

    private final Project project;

    public ServerStatusRefresher(Project project) {
        this.project = project;
    }

    @Override
    public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        RunnerAndConfigurationSettings settings = env.getRunnerAndConfigurationSettings();
        if (settings != null) {
            ServerRegistry.getInstance(project).track(settings.getName(), handler);
        }
        ProjectView.getInstance(project).refresh();
    }

    @Override
    public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler, int exitCode) {
        ProjectView.getInstance(project).refresh();
    }
}
