package local.bootservers;

import com.intellij.execution.Executor;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.module.Module;
import org.jetbrains.annotations.NotNull;

/**
 * 프로젝트 트리 우클릭 메뉴 「스프링 부트 서버」와 그 하위 동작.
 * 모듈 최상위 폴더를 우클릭했을 때만 보인다(LangDataKeys.MODULE_CONTEXT).
 */
final class ServerActions {

    private ServerActions() {
    }

    private static Module module(AnActionEvent e) {
        return e.getData(LangDataKeys.MODULE_CONTEXT);
    }

    /** 하위 메뉴. 모듈 노드가 아니면 메뉴째 숨긴다. */
    public static final class Menu extends DefaultActionGroup {
        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(module(e) != null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /** 실행 중이 아닐 때만 켜지는 시작 동작. 실행기(실행 · 디버그)만 다르다. */
    abstract static class StartBase extends AnAction {
        abstract Executor executor();

        @Override
        public void update(@NotNull AnActionEvent e) {
            Module module = module(e);
            e.getPresentation().setEnabled(module != null && !ServerLauncher.isRunning(module));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Module module = module(e);
            if (module != null) {
                ServerLauncher.start(module, executor());
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    public static final class Start extends StartBase {
        @Override
        Executor executor() {
            return DefaultRunExecutor.getRunExecutorInstance();
        }
    }

    public static final class Debug extends StartBase {
        @Override
        Executor executor() {
            return DefaultDebugExecutor.getDebugExecutorInstance();
        }
    }

    /** 실행 중일 때만 켜지는 동작(재시작 · 중지). */
    abstract static class RunningBase extends AnAction {
        abstract void perform(Module module);

        @Override
        public void update(@NotNull AnActionEvent e) {
            Module module = module(e);
            e.getPresentation().setEnabled(module != null && ServerLauncher.isRunning(module));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Module module = module(e);
            if (module != null) {
                perform(module);
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    public static final class Restart extends RunningBase {
        @Override
        void perform(Module module) {
            ServerLauncher.restart(module);
        }
    }

    public static final class Stop extends RunningBase {
        @Override
        void perform(Module module) {
            ServerLauncher.stop(module);
        }
    }
}
