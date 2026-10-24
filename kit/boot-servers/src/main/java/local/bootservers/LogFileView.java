package local.bootservers;

import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.AppExecutorUtil;
import local.bootservers.was.WasLogFiles;

import javax.swing.JPanel;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 로그 파일 하나를 실행 창의 콘솔 탭에 보이고 파일 끝을 따라간다. 콘솔이므로 우클릭 「로그 거르기」 를 그대로 쓴다.
 * 탭을 닫으면 따라 읽기를 멈춘다.
 */
final class LogFileView {

    // 처음 열 때 보일 파일 끝 크기. 오래된 큰 로그를 통째로 읽지 않는다.
    private static final long INITIAL_BYTES = 512 * 1024;
    private static final long POLL_MILLIS = 1000;
    // UTF-8 이 아닌 로그(WebLogic · JEUS 가 OS 기본 문자셋으로 쓴 것)를 풀 문자셋.
    // 날짜 이름 로그(catalina.날짜.log)의 다음 날 파일이 생겼는지 볼 간격(poll 횟수). 날이 바뀌는 일이라 자주 볼 까닭이 없다.
    private static final int NEWER_CHECK_POLLS = 60;
    private static final Charset FALLBACK = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
    // 안내 줄(파일 순환 · 새 날짜 파일 등) 앞 표시
    private static final String NOTE_PREFIX = "ⓘ ";

    private LogFileView() {
    }

    /** 화면 스레드에서 부른다. */
    static void open(Project project, String serverName, Path file) {
        ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).filters(new LogLevelFilter()).getConsole();
        DefaultActionGroup actions = new DefaultActionGroup();
        JPanel panel = ConsolePanel.create(console, actions);
        ConsolePanel.addConsoleActions(console, actions);
        // 프로세스가 없는 탭은 실행 창이 끝난 탭으로 보고 다음 실행에 다시 쓰거나(닫아 버리거나) 이 탭이 끝난 다른 탭을 차지하므로 다시 쓰기를 막는다.
        RunContentDescriptor descriptor = new RunContentDescriptor(console, null, panel, serverName + " · " + file.getFileName(), AllIcons.FileTypes.Text) {
            @Override
            public boolean isContentReuseProhibited() {
                return true;
            }
        };
        Disposer.register(descriptor, console);
        console.print(BootServersBundle.message("log.view.file", file) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);

        LogTail tail = new LogTail(file, FALLBACK, INITIAL_BYTES);
        int[] polls = {0};
        Path[] noticed = {null};
        ScheduledFuture<?> future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
            show(console, tail.poll());
            if (polls[0]++ % NEWER_CHECK_POLLS == 0) {
                noticeNewer(console, file, noticed);
            }
        }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS);
        Disposer.register(descriptor, () -> future.cancel(false));
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
    }

    /** 날짜 이름 로그에 더 늦은 날짜 파일이 생겼으면 한 번 알린다. noticed[0] 은 마지막으로 알린 파일. */
    private static void noticeNewer(ConsoleView console, Path file, Path[] noticed) {
        Path newer;
        try {
            newer = WasLogFiles.newerDated(file);
        }
        catch (IOException e) {
            // 새 날짜 파일 알림은 덧붙이는 안내라 폴더를 못 읽으면 이번 차례만 건너뛴다. 파일 자체를 못 읽으면 따라 읽기가 알린다.
            return;
        }
        if (newer != null && !newer.equals(noticed[0])) {
            noticed[0] = newer;
            console.print("\n" + NOTE_PREFIX + BootServersBundle.message("log.view.newer", newer.getFileName()) + "\n",
                    ConsoleViewContentType.LOG_INFO_OUTPUT);
        }
    }

    private static void show(ConsoleView console, LogTail.Poll poll) {
        String note = switch (poll.event()) {
            case NONE -> null;
            case MISSING -> BootServersBundle.message("log.view.missing");
            case APPEARED -> BootServersBundle.message("log.view.appeared");
            case ROTATED -> BootServersBundle.message("log.view.rotated");
            case ERROR -> BootServersBundle.message("log.view.error", poll.error());
        };
        if (note != null) {
            console.print("\n" + NOTE_PREFIX + note + "\n", poll.event() == LogTail.Event.ERROR ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.LOG_INFO_OUTPUT);
        }
        if (!poll.text().isEmpty()) {
            console.print(poll.text(), ConsoleViewContentType.NORMAL_OUTPUT);
        }
    }
}
