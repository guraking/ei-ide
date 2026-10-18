package local.bootservers.web;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/** Vue 를 쓰는 프로젝트의 .vue 파일을 열면 Vue 서버를 띄운다. 같은 파일에 vtsls 는 {@link WebLspSupport} 가 띄운다. */
public final class VueLspSupport implements LspServerSupportProvider {

    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspServerStarter starter) {
        if (!WebTools.isVue(file.getExtension())) {
            return;
        }
        WebLspSupport.Tools tools = WebLspSupport.tools(project, file);
        if (tools != null && tools.root() != null
                && WebTools.usesVue(Path.of(file.getParent().getPath()), tools.root())) {
            starter.ensureServerStarted(new VueServerDescriptor(project, tools.node(), tools.base(), tools.tsdk()));
        }
    }
}
