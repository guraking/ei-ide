package local.bootservers.web;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import org.eclipse.lsp4j.ConfigurationItem;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/** vtsls(JS · TS, Vue 플러그인으로 .vue 의 스크립트까지). */
final class TsServerDescriptor extends ProjectWideLspServerDescriptor {

    private final Path node;
    private final Path base;
    private final Path tsdk;

    TsServerDescriptor(Project project, Path node, Path base, Path tsdk) {
        super(project, "TypeScript (vtsls)");
        this.node = node;
        this.base = base;
        this.tsdk = tsdk;
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return WebTools.isScript(file.getExtension()) || WebTools.isVue(file.getExtension());
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() {
        return WebServers.commandLine(getProject(), WebTools.vtslsCommand(node, base));
    }

    @Override
    public @NotNull String getLanguageId(@NotNull VirtualFile file) {
        String id = WebTools.languageId(file.getExtension());
        return id != null ? id : super.getLanguageId(file);
    }

    /** vtsls 는 시작할 때 빈 section 으로 설정 전체를 한 번 묻는다. */
    @Override
    public Object getWorkspaceConfiguration(@NotNull ConfigurationItem item) {
        String section = item.getSection();
        return section == null || section.isEmpty() ? WebTools.vtslsSettings(tsdk, base) : null;
    }
}
