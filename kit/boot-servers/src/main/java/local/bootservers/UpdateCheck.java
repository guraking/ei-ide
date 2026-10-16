package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDE 를 켤 때 GitHub 릴리스를 한 번 보고 지금 판보다 새 판이 있으면 알림을 띄운다.
 * 릴리스 태그는 build-windows.bat 이 붙이는 「v262.10968.N」 꼴이고, 지금 판은 ApplicationInfo 의 「IC-262.10968.N」 이다.
 * 판 번호 앞자리(262.10968)가 다르면 비교하지 않는다. 네트워크 실패는 조용히 넘긴다.
 */
public final class UpdateCheck implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(UpdateCheck.class);
    private static final String LATEST = "https://api.github.com/repos/guraking/ei-ide/releases/latest";
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v(\\d+\\.\\d+)\\.(\\d+)\"");
    private static final Pattern EXE = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.exe)\"");
    private static final Pattern PAGE = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+/releases/tag/[^\"]+)\"");

    @Override
    public void appStarted() {
        ApplicationManager.getApplication().executeOnPooledThread(this::check);
    }

    private void check() {
        String build = ApplicationInfo.getInstance().getBuild().asStringWithoutProductCode(); // 262.10968.2
        int dot = build.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String base = build.substring(0, dot);
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
        if (!tag.find() || !tag.group(1).equals(base)) {
            return;
        }
        int latest = Integer.parseInt(tag.group(2));
        if (latest <= current) {
            return;
        }
        Matcher exe = EXE.matcher(json);
        Matcher page = PAGE.matcher(json);
        String url = exe.find() ? exe.group(1) : page.find() ? page.group(1) : "https://github.com/guraking/ei-ide/releases";
        String version = base + "." + latest;
        ApplicationManager.getApplication().invokeLater(() -> NotificationGroupManager.getInstance()
                .getNotificationGroup("EI IDE 새 판")
                .createNotification("EI IDE " + version + " 이 나왔습니다", "지금 판: " + build, NotificationType.INFORMATION)
                .addActions(List.of(
                        NotificationAction.createSimpleExpiring("내려받기", () -> BrowserUtil.browse(url)),
                        NotificationAction.createSimpleExpiring("릴리스 보기", () -> BrowserUtil.browse("https://github.com/guraking/ei-ide/releases"))))
                .notify(null));
    }
}
