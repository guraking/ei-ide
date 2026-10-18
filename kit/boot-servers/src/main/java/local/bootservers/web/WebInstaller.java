package local.bootservers.web;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Node.js · 언어 서버를 사용자 폴더의 .ei-web 아래로 받는 뒷일. 받았으면 서버를 다시 띄우고, 실패 · 취소면 알림 표시만 지워 다음 파일을 열 때 다시 알린다. */
final class WebInstaller {

    /** 설치 실패 알림에 보이는 npm 출력 끝 줄 수. */
    private static final int OUTPUT_TAIL_LINES = 30;
    private static final String TEMP_SUFFIX = ".tmp";

    private WebInstaller() {
    }

    /** nodejs.org 의 최신 LTS 윈도우 zip 을 받아 SHA256 을 맞춰 보고 <base>/node 에 푼다. */
    static void installNode(Project project) {
        new Task.Backgroundable(project, "Node.js 받는 중", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    downloadNode(indicator);
                } catch (IOException e) {
                    fail(project, "Node.js 를 받지 못했습니다", e.getMessage());
                }
            }

            @Override
            public void onSuccess() {
                if (Files.isRegularFile(WebTools.bundledNode(WebLspSupport.baseDir(), SystemInfo.isWindows))) {
                    WebLspSupport.restart(project);
                } else {
                    WebLspSupport.clearNotice(project);
                }
            }

            @Override
            public void onCancel() {
                WebLspSupport.clearNotice(project);
            }
        }.queue();
    }

    private static void downloadNode(ProgressIndicator indicator) throws IOException {
        String arch = NodeDist.arch(System.getProperty("os.arch"));
        if (!SystemInfo.isWindows || arch == null) {
            throw new IOException("윈도우 x64 · arm64 용 zip 만 받을 수 있습니다.");
        }
        indicator.setText("Node.js LTS 판 확인");
        String version = NodeDist.latestLts(HttpRequests.request(NodeDist.INDEX_URL).readString(indicator), arch);
        if (version == null) {
            throw new IOException("index.json 에서 win-" + arch + " zip 이 있는 LTS 판을 찾지 못했습니다.");
        }
        String zipName = NodeDist.zipName(version, arch);
        String expected = NodeDist.expectedSha256(
                HttpRequests.request(NodeDist.shasumsUrl(version)).readString(indicator), zipName);
        if (expected == null) {
            throw new IOException(NodeDist.SHASUMS + " 에 " + zipName + " 의 해시가 없습니다.");
        }
        Path base = WebLspSupport.baseDir();
        Path nodeDir = base.resolve(WebTools.NODE_DIR);
        Path unpacked = base.resolve(WebTools.NODE_DIR + TEMP_SUFFIX);
        Files.createDirectories(base);
        Path zip = Files.createTempFile(base, "node-", ".zip");
        try {
            indicator.setText(zipName + " 받는 중");
            HttpRequests.request(NodeDist.zipUrl(version, arch)).saveToFile(zip, indicator);
            indicator.checkCanceled();
            String actual = NodeDist.sha256(zip);
            if (!expected.equals(actual)) {
                throw new IOException("받은 파일의 SHA256 이 맞지 않습니다(기대 " + expected + ", 실제 " + actual + ").");
            }
            indicator.setText("Node.js 푸는 중");
            FileUtil.delete(unpacked);
            NodeDist.unzipFlatten(zip, unpacked);
            FileUtil.delete(nodeDir);
            Files.move(unpacked, nodeDir);
        } finally {
            Files.deleteIfExists(zip);
            FileUtil.delete(unpacked);
        }
    }

    /** node 로 npm install 을 돌려 <base>/servers 에 언어 서버를 받는다. */
    static void installServers(Project project, Path node) {
        new Task.Backgroundable(project, "언어 서버 받는 중", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path base = WebLspSupport.baseDir();
                List<String> command = WebTools.installCommand(WebTools.npmCommand(node, SystemInfo.isWindows), base);
                ProcessOutput output;
                try {
                    Files.createDirectories(WebTools.serversDir(base));
                    GeneralCommandLine cmd = new GeneralCommandLine(command)
                            .withCharset(StandardCharsets.UTF_8)
                            .withWorkDirectory(WebTools.serversDir(base).toFile());
                    indicator.setText("npm install " + String.join(" ", WebTools.SERVER_PACKAGES));
                    output = new CapturingProcessHandler(cmd).runProcessWithProgressIndicator(indicator);
                } catch (IOException | ExecutionException e) {
                    fail(project, "언어 서버를 받지 못했습니다", e.getMessage());
                    return;
                }
                if (output.isCancelled()) {
                    return;
                }
                if (output.getExitCode() != 0 || !WebTools.serversInstalled(base)) {
                    fail(project, "언어 서버를 받지 못했습니다 (종료 코드 " + output.getExitCode() + ")",
                            tail(output.getStdout() + "\n" + output.getStderr()));
                }
            }

            @Override
            public void onSuccess() {
                if (WebTools.serversInstalled(WebLspSupport.baseDir())) {
                    WebLspSupport.restart(project);
                } else {
                    WebLspSupport.clearNotice(project);
                }
            }

            @Override
            public void onCancel() {
                WebLspSupport.clearNotice(project);
            }
        }.queue();
    }

    private static String tail(String text) {
        List<String> lines = StringUtil.split(text.strip(), "\n");
        return String.join("\n", lines.subList(Math.max(0, lines.size() - OUTPUT_TAIL_LINES), lines.size()));
    }

    private static void fail(Project project, String title, String detail) {
        String content = StringUtil.escapeXmlEntities(detail == null ? "" : detail).replace("\n", "<br>");
        NotificationGroupManager.getInstance().getNotificationGroup(WebLspSupport.NOTIFICATION_GROUP)
                .createNotification(title, content, NotificationType.ERROR)
                .notify(project);
    }
}
