package local.bootservers.web;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * JS · TS · .vue 파일을 열면 vtsls 를 띄운다. Vue 서버는 {@link VueLspSupport} 가 띄운다
 * (플랫폼은 fileOpened 한 번에 서버 하나만 띄우므로 제공자를 나눈다).
 * Node 나 언어 서버가 없으면 받기 단추가 달린 알림을 프로젝트마다 한 번 띄운다.
 */
public final class WebLspSupport implements LspServerSupportProvider {

    static final String NOTIFICATION_GROUP = "웹 언어 지원";
    /** 알림을 띄웠고 받기가 끝나지 않은 프로젝트 표시. */
    private static final Key<Boolean> NOTICE_SHOWN = Key.create("local.bootservers.web.noticeShown");

    /** 서버를 띄울 때 쓰는 도구 위치. root 는 프로젝트 뿌리(없으면 null). */
    record Tools(Path node, Path base, @Nullable Path root, Path tsdk) {
    }

    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspServerStarter starter) {
        String ext = file.getExtension();
        if (!WebTools.isVue(ext) && !WebTools.isScript(ext)) {
            return;
        }
        Tools tools = tools(project, file);
        if (tools != null) {
            starter.ensureServerStarted(new TsServerDescriptor(project, tools.node(), tools.base(), tools.tsdk()));
        }
    }

    /** 로컬 파일이고 Node · 언어 서버가 있으면 그 위치, 아니면 null(없는 도구는 받기 알림을 띄운다). */
    static @Nullable Tools tools(Project project, VirtualFile file) {
        if (!file.isInLocalFileSystem()) {
            return null;
        }
        Path base = baseDir();
        Path node = WebTools.findNode(base, System.getenv("PATH"), SystemInfo.isWindows);
        if (node == null) {
            notifyMissing(project, "Node.js 가 없어 JavaScript · TypeScript · Vue 언어 지원을 켤 수 없습니다.",
                    SystemInfo.isWindows && NodeDist.arch(System.getProperty("os.arch")) != null
                            ? NotificationAction.createSimpleExpiring("Node.js 받기", () -> WebInstaller.installNode(project))
                            : null);
            return null;
        }
        if (!WebTools.serversInstalled(base)) {
            notifyMissing(project, "JavaScript · TypeScript · Vue 언어 서버가 설치되어 있지 않습니다.",
                    NotificationAction.createSimpleExpiring("언어 서버 받기", () -> WebInstaller.installServers(project, node)));
            return null;
        }
        Path root = projectRoot(project);
        return new Tools(node, base, root, WebTools.tsdk(root, base));
    }

    static Path baseDir() {
        return WebTools.baseDir(System.getProperty("user.home"));
    }

    static @Nullable Path projectRoot(Project project) {
        VirtualFile dir = ProjectUtil.guessProjectDir(project);
        return dir == null || !dir.isInLocalFileSystem() ? null : Path.of(dir.getPath());
    }

    /** 받기가 끝나면 서버를 다시 띄운다. 열린 파일마다 fileOpened 가 다시 불려 남은 도구가 없으면 다시 알린다. */
    static void restart(Project project) {
        clearNotice(project);
        if (!project.isDisposed()) {
            LspServerManager manager = LspServerManager.getInstance(project);
            manager.stopAndRestartIfNeeded(WebLspSupport.class);
            manager.stopAndRestartIfNeeded(VueLspSupport.class);
        }
    }

    static void clearNotice(Project project) {
        project.putUserData(NOTICE_SHOWN, null);
    }

    private static void notifyMissing(Project project, String content, @Nullable NotificationAction action) {
        if (Boolean.TRUE.equals(project.getUserData(NOTICE_SHOWN))) {
            return;
        }
        project.putUserData(NOTICE_SHOWN, Boolean.TRUE);
        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("웹 언어 지원", content, NotificationType.WARNING);
        if (action != null) {
            notification.addAction(action);
        } else {
            notification.setContent(content + "<br>Node.js 를 설치해 PATH 에 넣거나 " + baseDir().resolve(WebTools.NODE_DIR)
                    + " 에 풀어 두세요.");
        }
        notification.notify(project);
    }
}
