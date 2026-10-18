package local.bootservers.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.Lsp4jClient;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerNotificationsHandler;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import com.intellij.util.concurrency.AppExecutorUtil;
import kotlin.Unit;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Vue 언어 서버(하이브리드 모드: 템플릿 · 스타일만 맡는다).
 * 스크립트 형식 정보는 vtsls 안의 @vue/typescript-plugin 이 갖고 있어, Vue 서버의 tsserver/request 알림을
 * vtsls 의 typescript.tsserverRequest 명령으로 넘기고 답을 tsserver/response 알림으로 돌려준다.
 */
final class VueServerDescriptor extends ProjectWideLspServerDescriptor {

    private static final Logger LOG = Logger.getInstance(VueServerDescriptor.class);
    /** 첫 요청은 tsserver 가 프로젝트를 읽는 동안 기다리므로 기본(10초)보다 넉넉히. */
    private static final int TSSERVER_TIMEOUT_MS = 30_000;
    /** Vue 서버가 vtsls 보다 먼저 뜨면 vtsls 가 준비될 때까지 기다리는 횟수 · 간격(최대 2분, 큰 프로젝트는 vtsls 준비에 1분 넘게 걸린다). */
    private static final int WAIT_ATTEMPTS = 600;
    private static final long WAIT_INTERVAL_MS = 200;

    private final Path node;
    private final Path base;
    private final Path tsdk;

    VueServerDescriptor(Project project, Path node, Path base, Path tsdk) {
        super(project, "Vue");
        this.node = node;
        this.base = base;
        this.tsdk = tsdk;
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return WebTools.isVue(file.getExtension());
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() {
        return WebServers.commandLine(getProject(), WebTools.vueCommand(node, base, tsdk));
    }

    @Override
    public @NotNull String getLanguageId(@NotNull VirtualFile file) {
        return WebTools.VUE_EXTENSION;
    }

    @Override
    public @NotNull Class<? extends LanguageServer> getLsp4jServerClass() {
        return VueLanguageServer.class;
    }

    @Override
    public @NotNull Lsp4jClient createLsp4jClient(@NotNull LspServerNotificationsHandler handler) {
        return new VueClient(handler, getProject());
    }

    /** Vue 서버 쪽으로 보내는 사용자 알림. */
    public interface VueLanguageServer extends LanguageServer {
        @JsonNotification("tsserver/response")
        void tsserverResponse(JsonArray params);
    }

    /** Vue 서버가 보내는 사용자 알림을 받는 클라이언트. */
    public static final class VueClient extends Lsp4jClient {

        private final Project project;
        private final Gson gson = new Gson();

        VueClient(LspServerNotificationsHandler handler, Project project) {
            super(handler);
            this.project = project;
        }

        @JsonNotification("tsserver/request")
        public void tsserverRequest(JsonElement params) {
            JsonArray request = WebTools.tsserverRequest(params);
            if (request == null) {
                LOG.warn("tsserver/request 모양이 맞지 않아 버립니다");
                return;
            }
            JsonElement id = request.get(0);
            String command = request.get(1).getAsString();
            JsonElement args = request.size() > 2 ? request.get(2) : JsonNull.INSTANCE;
            AppExecutorUtil.getAppExecutorService().execute(() -> respond(id, forward(command, args)));
        }

        /** vtsls 에 넘긴 결과. vtsls 가 없거나 실패하면 null. */
        private JsonElement forward(String command, JsonElement args) {
            LspServer ts = WebServers.running(project, TsServerDescriptor.class);
            for (int i = 0; ts == null && i < WAIT_ATTEMPTS && !project.isDisposed(); i++) {
                try {
                    Thread.sleep(WAIT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                ts = WebServers.running(project, TsServerDescriptor.class);
            }
            if (ts == null) {
                LOG.warn("vtsls 가 돌고 있지 않아 Vue 서버 요청에 빈 답을 보냅니다: " + command);
                return null;
            }
            Object result = ts.sendRequestSync(TSSERVER_TIMEOUT_MS, server -> server.getWorkspaceService()
                    .executeCommand(new ExecuteCommandParams(WebTools.TSSERVER_REQUEST_COMMAND, List.of(command, args))));
            return result == null ? null : result instanceof JsonElement json ? json : gson.toJsonTree(result);
        }

        private void respond(JsonElement id, JsonElement result) {
            LspServer vue = WebServers.running(project, VueServerDescriptor.class);
            if (vue == null) {
                return;
            }
            JsonArray params = WebTools.tsserverResponse(id, result);
            vue.sendNotification(server -> {
                ((VueLanguageServer) server).tsserverResponse(params);
                return Unit.INSTANCE;
            });
        }
    }
}
