package local.bootservers;

import com.intellij.diagnostic.ThreadDumper;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IDE 끄기가 늦을 때 무엇이 붙잡는지 idea.log 에 남긴다.
 * 끄기가 확정된 뒤(beforeAppWillBeClosed, 프로젝트 저장 · 닫기 전) FIRST_DELAY_MS 가 지나도 JVM 이 살아 있으면
 * INTERVAL_MS 마다 스레드 덤프를 적고, LIMIT_MS 를 넘기면 멈춘다. 그 안에 끝나면 아무것도 적지 않는다.
 * 데몬 스레드라 JVM 이 끝나는 것을 막지 않는다. 프로젝트 닫기가 취소돼 끄기가 멈추면 덤프를 멈추고 다음 끄기를 다시 지켜본다.
 */
public final class ExitWatch implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(ExitWatch.class);
    private static final long FIRST_DELAY_MS = 3_000;
    private static final long INTERVAL_MS = 3_000;
    private static final long LIMIT_MS = 30_000;
    /** 지켜보는 스레드가 돌고 있으면 true. 끄기 한 번에 하나만 띄운다. */
    private static final AtomicBoolean WATCHING = new AtomicBoolean();

    @Override
    public void beforeAppWillBeClosed(boolean isRestart) {
        if (!WATCHING.compareAndSet(false, true)) return;
        long start = System.nanoTime();
        Thread thread = new Thread(() -> watch(start), "Moa exit watch");
        thread.setDaemon(true);
        thread.start();
    }

    private static void watch(long start) {
        try {
            Thread.sleep(FIRST_DELAY_MS);
            while (exiting()) {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (elapsed > LIMIT_MS) return;
                LOG.info("IDE 끄기 " + elapsed + " ms 째, 아직 안 끝남. 스레드 덤프:\n" + ThreadDumper.dumpThreadsToString());
                Thread.sleep(INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.info("IDE 끄기 스레드 덤프 실패", t);
        } finally {
            WATCHING.set(false);
        }
    }

    /** 앱이 이미 정리돼 없으면(마지막 정리 단계) 아직 끄는 중으로 본다. */
    private static boolean exiting() {
        Application app = ApplicationManager.getApplication();
        return app == null || app.isExitInProgress() || app.isDisposed();
    }
}
