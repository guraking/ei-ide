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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Vue 언어 서버(하이브리드 모드: 템플릿 · 스타일만 맡는다).
 * 스크립트 형식 정보는 vtsls 안의 @vue/typescript-plugin 이 갖고 있어, Vue 서버의 tsserver/request 알림을
 * vtsls 의 typescript.tsserverRequest 명령으로 넘기고 답을 tsserver/response 알림으로 돌려준다.
 */
final class VueServerDescriptor extends ProjectWideLspServerDescriptor {

    private static final Logger LOG = Logger.getInstance(VueServerDescriptor.class);
    /** 첫 요청은 tsserver 가 프로젝트를 읽는 동안 기다리므로 기본(10초)보다 넉넉히. */
    private static final int TSSERVER_TIMEOUT_MS = 30_000;
    /** Vue 서버가 vtsls 보다 먼저 뜨면 vtsls 가 준비될 때까지 기다리는 시간 · 확인 간격(큰 프로젝트는 vtsls 준비에 1분 넘게 걸린다). */
    private static final long WAIT_LIMIT_MS = 120_000;
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
        /** vtsls 를 기다리는 하나뿐인 기다림. 처음 요청 때 만든다. */
        private CompletableFuture<LspServer> tsReady;

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
            CompletableFuture<LspServer> ready = tsServer();
            // 처음 기다림이 끝난 뒤에는 기다리지 않는다: 지금 vtsls 가 없으면 바로 빈 답.
            CompletableFuture<LspServer> server = ready.isDone()
                    ? CompletableFuture.completedFuture(WebServers.running(project, TsServerDescriptor.class)) : ready;
            server.thenCompose(ts -> forward(ts, command, args))
                    .exceptionally(error -> {
                        LOG.warn("vtsls 에 넘긴 Vue 서버 요청이 실패해 빈 답을 보냅니다: " + command, error);
                        return null;
                    })
                    .thenAcceptAsync(result -> respond(id, result), AppExecutorUtil.getAppExecutorService());
        }

        /** 이 Vue 서버가 처음 받은 요청부터 vtsls 가 뜰 때까지(최대 WAIT_LIMIT_MS) 한 번만 기다린다. 못 찾으면 null 로 끝난다. */
        private synchronized CompletableFuture<LspServer> tsServer() {
            if (tsReady == null) {
                CompletableFuture<LspServer> ready = new CompletableFuture<>();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_LIMIT_MS);
                ScheduledFuture<?> poll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
                    try {
                        LspServer ts = project.isDisposed() ? null : WebServers.running(project, TsServerDescriptor.class);
                        if (ts != null || project.isDisposed() || System.nanoTime() > deadline) {
                            ready.complete(ts);
                        }
                    } catch (RuntimeException e) {
                        // 예외가 나면 주기 작업이 멈추므로 기다림도 끝내 요청이 쌓이지 않게 한다.
                        ready.completeExceptionally(e);
                    }
                }, 0, WAIT_INTERVAL_MS, TimeUnit.MILLISECONDS);
                ready.whenComplete((ts, error) -> poll.cancel(false));
                tsReady = ready;
            }
            return tsReady;
        }

        /** vtsls 에 넘기고 결과를 기다리지 않고 받는다. vtsls 가 없으면 null, 답이 없으면 TSSERVER_TIMEOUT_MS 뒤 실패. */
        private CompletableFuture<JsonElement> forward(LspServer ts, String command, JsonElement args) {
            if (ts == null) {
                LOG.warn("vtsls 가 돌고 있지 않아 Vue 서버 요청에 빈 답을 보냅니다: " + command);
                return CompletableFuture.completedFuture(null);
            }
            // LspServer.sendRequest 는 코틀린 suspend 라 자바에서 기다리지 않고 부를 수 없어, 알림 통로로 lsp4j 서버를 받아 요청 결과를 잇는다.
            CompletableFuture<Object> answer = new CompletableFuture<>();
            ts.sendNotification(server -> {
                server.getWorkspaceService()
                        .executeCommand(new ExecuteCommandParams(WebTools.TSSERVER_REQUEST_COMMAND, List.of(command, args)))
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                answer.completeExceptionally(error);
                            } else {
                                answer.complete(result);
                            }
                        });
                return Unit.INSTANCE;
            });
            return answer.orTimeout(TSSERVER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .thenApply(result -> result == null ? null : result instanceof JsonElement json ? json : gson.toJsonTree(result));
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
