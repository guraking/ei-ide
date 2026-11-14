package local.bootservers;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 서버 프로세스 출력 하나를 읽어 오류를 모은다. 출력 스레드는 글을 쌓기만 하고, 읽기({@link ErrorScan})는 TICK_MS 마다 풀 스레드에서 한다.
 * 한 번 쉬는 동안 새 글이 없으면 열린 오류 묶음을 마무리한다. 모은 것이 바뀌었을 때만 화면 스레드로 사본을 넘긴다.
 * 쌓인 글이 MAX_PENDING_CHARS 를 넘으면 그 뒤 글은 읽지 않고 버린다(콘솔에는 그대로 나온다). 끝나면(dispose) 주기 작업을 멈춘다.
 */
final class ErrorWatcher implements Disposable {

    private static final Logger LOG = Logger.getInstance(ErrorWatcher.class);
    private static final int TICK_MS = 700;
    private static final int MAX_PENDING_CHARS = 2 * 1024 * 1024;

    private final ErrorScan scan = new ErrorScan();
    private final StringBuilder pending = new StringBuilder();
    private final ScheduledFuture<?> future;
    // pending 을 잡고 쓴다.
    private boolean dropped;
    // 아래 둘은 tick 안에서만(동기화) 쓴다.
    private boolean idle = true;
    private long shownChanges = -1;
    private volatile Consumer<List<ErrorScan.Item>> listener;

    ErrorWatcher(ProcessHandler handler) {
        future = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(() -> tick(false), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (outputType == ProcessOutputTypes.SYSTEM) {
                    return;
                }
                synchronized (pending) {
                    if (pending.length() + event.getText().length() > MAX_PENDING_CHARS) {
                        dropped = true;
                        return;
                    }
                    pending.append(event.getText());
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                // 끝난 뒤에는 새 글이 없으므로 마지막으로 한 번 읽고 주기 작업을 멈춘다.
                AppExecutorUtil.getAppExecutorService().execute(() -> {
                    tick(true);
                    future.cancel(false);
                });
            }
        }, this);
    }

    /** 바뀐 오류 목록을 받을 곳(화면 스레드에서 불린다). 붙이자마자 지금까지 모은 것을 한 번 넘긴다. */
    void listen(Consumer<List<ErrorScan.Item>> listener) {
        this.listener = listener;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            synchronized (this) {
                shownChanges = -1;
            }
            tick(false);
        });
    }

    /** 모은 오류를 비운다. */
    void clear() {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            synchronized (this) {
                scan.clear();
            }
            tick(false);
        });
    }

    private synchronized void tick(boolean end) {
        try {
            String chunk;
            boolean lost;
            synchronized (pending) {
                chunk = pending.toString();
                pending.setLength(0);
                lost = dropped;
                dropped = false;
            }
            if (lost) {
                // 버린 글 앞뒤가 한 줄로 이어지지 않게 끊는다.
                scan.flush();
            }
            if (!chunk.isEmpty()) {
                scan.feed(chunk);
                idle = false;
            }
            else if (!idle) {
                scan.flush();
                idle = true;
            }
            if (end) {
                scan.flush();
            }
            Consumer<List<ErrorScan.Item>> to = listener;
            if (to != null && scan.changes() != shownChanges) {
                shownChanges = scan.changes();
                List<ErrorScan.Item> items = scan.items();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!Disposer.isDisposed(this)) {
                        to.accept(items);
                    }
                }, ModalityState.any());
            }
        }
        catch (RuntimeException | LinkageError e) {
            // 주기 작업은 예외 하나로 멈추므로 여기서 받는다. 오류 탭이 비어 보일 뿐 콘솔은 그대로다.
            LOG.warn("서버 출력 오류 읽기 실패", e);
        }
    }

    @Override
    public void dispose() {
        future.cancel(false);
        listener = null;
    }
}
