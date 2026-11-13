package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.ShutDownTracker;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDE 를 켤 때 GitHub 릴리스를 한 번 보고 지금 판보다 새 판이 있으면 알림을 띄운다.
 * 릴리스 태그는 build-windows.bat 이 붙이는 「v263.7307.N」 꼴이고, 지금 판은 ApplicationInfo 의 「IC-263.7307.N」 이다.
 * 마지막 자리 N 만 비교한다. N 은 바탕 커뮤니티 판(앞자리)이 바뀌어도 이어서 매기므로, 앞자리가 달라도 N 이 크면 새 판이다.
 * 네트워크 실패는 조용히 넘긴다.
 * 「지금 업데이트」는 설치 파일을 임시 폴더에 받고, IDE 가 닫힌 뒤 설치 화면을 띄운다(설치 화면에서 옛 판 정리를 고를 수 있다).
 */
public final class UpdateCheck implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(UpdateCheck.class);
    private static final String LATEST = "https://api.github.com/repos/guraking/ei-ide/releases/latest";
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v(\\d+\\.\\d+)\\.(\\d+)\"");
    private static final Pattern EXE = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.exe)\"");
    /** 이 주소로 시작하는 설치 파일만 받아 실행한다. 릴리스 응답이 다른 곳의 exe 를 가리키면 브라우저로만 연다. */
    private static final String DOWNLOAD_PREFIX = "https://github.com/guraking/ei-ide/releases/download/";
    private static final Pattern PAGE = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+/releases/tag/[^\"]+)\"");
    /** 끝날 때 설치 파일을 띄우는 작업을 이미 맡겼으면 true. 여러 번 받아도 한 번만 맡긴다. */
    private static final AtomicBoolean LAUNCH_REGISTERED = new AtomicBoolean();
    /** 끝날 때 띄울 설치 파일(마지막으로 받은 것). */
    private static volatile File pendingInstaller;
    /** 지금 끝나는 것이 다시 시작(플러그인 설치 등)이면 true. 다시 시작할 때는 설치 파일을 띄우지 않는다. */
    private static volatile boolean restarting;

    @Override
    public void appStarted() {
        ApplicationManager.getApplication().executeOnPooledThread(this::check);
    }

    @Override
    public void appWillBeClosed(boolean isRestart) {
        restarting = isRestart;
    }

    private void check() {
        String build = ApplicationInfo.getInstance().getBuild().asStringWithoutProductCode(); // 263.7307.N
        int dot = build.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        int current;
        try {
            current = Integer.parseInt(build.substring(dot + 1));
        } catch (NumberFormatException e) {
            return;
        }
        String json;
        try {
            json = HttpRequests.request(LATEST).connectTimeout(5000).readTimeout(5000).readString();
        } catch (Exception e) {
            LOG.info("새 판 확인 실패: " + e.getMessage());
            return;
        }
        Matcher tag = TAG.matcher(json);
        if (!tag.find()) {
            return;
        }
        int latest = Integer.parseInt(tag.group(2));
        if (latest <= current) {
            return;
        }
        Matcher exe = EXE.matcher(json);
        Matcher page = PAGE.matcher(json);
        String url = exe.find() ? exe.group(1) : page.find() ? page.group(1) : "https://github.com/guraking/ei-ide/releases";
        String version = tag.group(1) + "." + latest;
        boolean installable = url.startsWith(DOWNLOAD_PREFIX) && url.endsWith(".exe");
        ApplicationManager.getApplication().invokeLater(() -> NotificationGroupManager.getInstance()
                .getNotificationGroup("Moa IDE 새 판")
                .createNotification(BootServersBundle.message("update.available", version), BootServersBundle.message("update.current", build), NotificationType.INFORMATION)
                .addActions(List.of(
                        installable
                                ? NotificationAction.createSimpleExpiring(BootServersBundle.message("update.now"), () -> download(url, version))
                                : NotificationAction.createSimpleExpiring(BootServersBundle.message("update.download"), () -> BrowserUtil.browse(url)),
                        NotificationAction.createSimpleExpiring(BootServersBundle.message("update.releases"), () -> BrowserUtil.browse("https://github.com/guraking/ei-ide/releases"))))
                .notify(null));
    }

    private static void download(String url, String version) {
        File target = new File(PathManager.getTempPath(), "moaide-" + version + ".exe");
        new Task.Backgroundable(null, BootServersBundle.message("update.progress", version), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    HttpRequests.request(url).connectTimeout(10000).saveToFile(target, indicator);
                } catch (IOException e) {
                    LOG.warn("설치 파일 받기 실패: " + url, e);
                    ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(
                            BootServersBundle.message("update.download.failed", e.getMessage()), BootServersBundle.message("update.title")));
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> askInstall(target, version));
            }
        }.queue();
    }

    private static void askInstall(File installer, String version) {
        int answer = Messages.showYesNoDialog(
                BootServersBundle.message("update.install.confirm", version),
                BootServersBundle.message("update.title"), BootServersBundle.message("update.install.now"), BootServersBundle.message("update.install.later"), Messages.getInformationIcon());
        // 설치 파일은 IDE 가 쓰는 파일을 덮으므로 JVM 이 끝날 때 띄운다. 「나중에」 · 끄기 취소면 다음에 끌 때 뜬다.
        // 다시 시작(플러그인 설치 등)으로 끝날 때는 띄우지 않는다. 다시 켠 뒤 새 판 알림에서 다시 고른다.
        pendingInstaller = installer;
        if (LAUNCH_REGISTERED.compareAndSet(false, true)) {
            ShutDownTracker.getInstance().registerShutdownTask(() -> {
                File pending = pendingInstaller;
                if (!restarting && pending != null) {
                    launch(pending);
                }
            });
        }
        if (answer == Messages.YES) {
            ApplicationManager.getApplication().exit();
        }
    }

    /** cmd start 는 ShellExecute 로 띄우므로 관리자 승인(UAC)이 필요한 설치 파일도 실행된다. */
    private static void launch(File installer) {
        try {
            new ProcessBuilder("cmd", "/c", "start", "", installer.getAbsolutePath()).start();
        } catch (IOException e) {
            LOG.warn("설치 파일 실행 실패: " + installer, e);
        }
    }
}
