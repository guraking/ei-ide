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
import local.bootservers.BootServersBundle;
import com.intellij.credentialStore.Credentials;
import com.intellij.util.net.JdkProxyProvider;
import com.intellij.util.net.ProxyAuthentication;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Node.js · 언어 서버를 사용자 폴더의 .ei-web 아래로 받는 뒷일. 받았으면 서버를 다시 띄우고, 실패 · 취소면 알림 표시만 지워 다음 파일을 열 때 다시 알린다. */
final class WebInstaller {

    /** 설치 실패 알림에 보이는 npm 출력 끝 줄 수. */
    private static final int OUTPUT_TAIL_LINES = 30;
    private static final String TEMP_SUFFIX = ".tmp";
    /** 새 Node 를 넣는 동안 옛 Node 를 옮겨 두는 폴더 꼬리. 다 넣은 뒤 지우고, 실패하면 되돌린다. */
    private static final String OLD_SUFFIX = ".old";
    private static final String PROXY_MASK = "<프록시>";

    private WebInstaller() {
    }

    /** nodejs.org 에서 versions.properties 에 고정한 판의 윈도우 zip 을 받아 SHA256 을 맞춰 보고 <base>/node 에 푼다. */
    static void installNode(Project project) {
        new Task.Backgroundable(project, BootServersBundle.message("web.install.node.progress"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    downloadNode(indicator);
                } catch (IOException e) {
                    fail(project, BootServersBundle.message("web.install.node.failed"), e.getMessage());
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
            throw new IOException(BootServersBundle.message("web.install.node.arch"));
        }
        indicator.setText(BootServersBundle.message("web.install.node.check"));
        String version = WebTools.nodeVersion();
        String zipName = NodeDist.zipName(version, arch);
        String expected = NodeDist.expectedSha256(
                HttpRequests.request(NodeDist.shasumsUrl(version)).readString(indicator), zipName);
        if (expected == null) {
            throw new IOException(BootServersBundle.message("web.install.node.no.hash", NodeDist.SHASUMS, zipName));
        }
        Path base = WebLspSupport.baseDir();
        Path nodeDir = base.resolve(WebTools.NODE_DIR);
        Path unpacked = base.resolve(WebTools.NODE_DIR + TEMP_SUFFIX);
        Files.createDirectories(base);
        Path zip = Files.createTempFile(base, "node-", ".zip");
        try {
            indicator.setText(BootServersBundle.message("web.install.downloading", zipName));
            HttpRequests.request(NodeDist.zipUrl(version, arch)).saveToFile(zip, indicator);
            indicator.checkCanceled();
            String actual = NodeDist.sha256(zip);
            if (!expected.equals(actual)) {
                throw new IOException(BootServersBundle.message("web.install.node.sha", expected, actual));
            }
            indicator.setText(BootServersBundle.message("web.install.node.unzip"));
            FileUtil.delete(unpacked);
            NodeDist.unzipFlatten(zip, unpacked);
            replace(nodeDir, unpacked, base.resolve(WebTools.NODE_DIR + OLD_SUFFIX));
        } finally {
            Files.deleteIfExists(zip);
            FileUtil.delete(unpacked);
        }
    }

    /**
     * 옛 폴더를 지우지 않고 old 로 이름만 바꾼 뒤 새 폴더를 옮기고, 그다음에 old 를 지운다.
     * 이름 바꾸기는 통째로 되거나 안 되므로, 언어 서버가 node.exe 를 잡고 있으면 옛 설치가 그대로 남고 IOException 이다.
     * 새 폴더를 옮기다 실패하면 old 를 되돌린다.
     */
    private static void replace(Path target, Path fresh, Path old) throws IOException {
        if (!FileUtil.delete(old.toFile())) {
            throw new IOException(BootServersBundle.message("web.install.node.old", old));
        }
        boolean had = Files.exists(target);
        if (had) {
            try {
                Files.move(target, old);
            } catch (IOException e) {
                throw new IOException(BootServersBundle.message("web.install.node.busy", e.getMessage()), e);
            }
        }
        try {
            Files.move(fresh, target);
        } catch (IOException e) {
            if (had) {
                FileUtil.delete(target.toFile());
                Files.move(old, target);
            }
            throw e;
        }
        FileUtil.delete(old.toFile());
    }

    /** 판을 고정한 package.json · package-lock.json 을 넣고 node 로 npm ci 를 돌려 <base>/servers 에 언어 서버를 받는다. IDE 프록시 설정을 npm 에 넘긴다. */
    static void installServers(Project project, Path node) {
        new Task.Backgroundable(project, BootServersBundle.message("web.install.servers.progress"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                Path base = WebLspSupport.baseDir();
                List<String> command = WebTools.installCommand(WebTools.npmCommand(node, SystemInfo.isWindows), base);
                ProcessOutput output;
                String proxy = npmProxy();
                try {
                    WebTools.writePinnedPackages(WebTools.serversDir(base));
                    GeneralCommandLine cmd = new GeneralCommandLine(command)
                            .withCharset(StandardCharsets.UTF_8)
                            .withWorkDirectory(WebTools.serversDir(base).toFile());
                    if (proxy != null) {
                        for (String name : WebTools.PROXY_ENV_NAMES) {
                            cmd.withEnvironment(name, proxy);
                        }
                    }
                    indicator.setText("npm ci");
                    output = new CapturingProcessHandler(cmd).runProcessWithProgressIndicator(indicator);
                } catch (IOException | ExecutionException e) {
                    fail(project, BootServersBundle.message("web.install.servers.failed"), e.getMessage());
                    return;
                }
                if (output.isCancelled()) {
                    return;
                }
                if (output.getExitCode() != 0 || !WebTools.serversInstalled(base)) {
                    fail(project, BootServersBundle.message("web.install.servers.failed.code", output.getExitCode()),
                            hideProxy(tail(output.getStdout() + "\n" + output.getStderr()), proxy));
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

    /**
     * IDE 설정의 프록시(PAC · 자동 감지 포함)가 npm 저장소에 쓰는 HTTP 프록시 주소. 아는 인증 정보가 있으면 주소에 넣는다.
     * 프록시를 쓰지 않으면 null. 이 주소는 비밀번호를 담을 수 있으므로 로그 · 알림에 남기지 않는다.
     */
    private static @Nullable String npmProxy() {
        List<Proxy> proxies = JdkProxyProvider.getInstance().getProxySelector().select(URI.create(WebTools.NPM_REGISTRY));
        for (Proxy proxy : proxies) {
            if (proxy.type() == Proxy.Type.HTTP && proxy.address() instanceof InetSocketAddress address) {
                Credentials credentials = ProxyAuthentication.getInstance().getKnownAuthentication(address.getHostString(), address.getPort());
                return WebTools.proxyUrl(address.getHostString(), address.getPort(),
                        credentials == null ? null : credentials.getUserName(),
                        credentials == null ? null : credentials.getPasswordAsString());
            }
        }
        return null;
    }

    /** npm 출력에 프록시 주소(인증 정보 포함)가 나오면 가린다. */
    private static String hideProxy(String text, @Nullable String proxy) {
        return proxy == null ? text : text.replace(proxy, PROXY_MASK);
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
