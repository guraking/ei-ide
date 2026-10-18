package local.bootservers.web;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.platform.lsp.api.LspServer;
import com.intellij.platform.lsp.api.LspServerManager;
import com.intellij.platform.lsp.api.LspServerState;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** 두 서버 설명이 함께 쓰는 실행 줄 · 서버 찾기. */
final class WebServers {

    private WebServers() {
    }

    /** 작업 폴더는 프로젝트 뿌리(없으면 사용자 폴더의 .ei-web). */
    static GeneralCommandLine commandLine(Project project, List<String> command) {
        Path root = WebLspSupport.projectRoot(project);
        return new GeneralCommandLine(command)
                .withCharset(StandardCharsets.UTF_8)
                .withWorkDirectory((root != null ? root : WebLspSupport.baseDir()).toFile());
    }

    /** 이 프로젝트에서 돌고 있는, 주어진 설명 종류의 서버. */
    static @Nullable LspServer running(Project project, Class<?> descriptorClass) {
        for (LspServer server : LspServerManager.getInstance(project).getServersForProvider(WebLspSupport.class)) {
            if (descriptorClass.isInstance(server.getDescriptor()) && server.getState() == LspServerState.Running) {
                return server;
            }
        }
        return null;
    }
}
