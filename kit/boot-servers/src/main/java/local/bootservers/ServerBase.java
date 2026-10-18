package local.bootservers;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 개발 서버의 기본 주소(http://localhost:포트 + 컨텍스트 경로).
 * 실행 중인 서버의 포트(controller 모듈을 실행하는 서버 우선) → 설정 파일 server.port → 8080, 여기에 컨텍스트 경로.
 */
final class ServerBase {

    private static final Logger LOG = Logger.getInstance(ServerBase.class);
    private static final String[] CONFIG_FILES = {"application.properties", "application.yml", "application.yaml"};

    private ServerBase() {
    }

    /** 읽기 동작 안에서 부른다. */
    static String resolve(Project project, @Nullable Module controller) {
        Integer port = null;
        Module running = null;
        ServerRegistry registry = ServerRegistry.getInstance(project);
        for (RunnerAndConfigurationSettings s : RunManager.getInstance(project)
                .getConfigurationSettingsList(ApplicationConfigurationType.getInstance())) {
            ServerRegistry.Run run = registry.get(s.getName());
            if (run == null || run.port == null || ServerLauncher.runningDescriptors(s).isEmpty()) {
                continue;
            }
            Module m = ((ApplicationConfiguration) s.getConfiguration()).getConfigurationModule().getModule();
            boolean serves = controller != null && m != null
                    && (m.equals(controller) || ModuleUtilCore.getAllDependentModules(controller).contains(m));
            if (port == null || serves) {
                port = run.port;
                running = m;
            }
            if (serves) {
                break;
            }
        }
        List<VirtualFile> configs = configFiles(project, controller, running);
        if (port == null) {
            port = HttpText.parsePort(configValue(configs, HttpText.PORT_KEY));
        }
        String contextPath = null;
        for (String key : HttpText.CONTEXT_PATH_KEYS) {
            contextPath = configValue(configs, key);
            if (contextPath != null) {
                break;
            }
        }
        return HttpText.baseUrl(port != null ? port : HttpText.DEFAULT_PORT, contextPath);
    }

    /** 컨트롤러 모듈 → 실행 중인 서버 모듈 → 프로젝트 전체 순으로, 설정 파일이 처음 나오는 범위의 파일들(시험 자원 제외). 색인 중이면 빈 목록. */
    private static List<VirtualFile> configFiles(Project project, @Nullable Module controller, @Nullable Module running) {
        List<VirtualFile> result = new ArrayList<>();
        if (DumbService.isDumb(project)) {
            return result;
        }
        Set<GlobalSearchScope> scopes = new LinkedHashSet<>();
        for (Module m : new Module[]{controller, running}) {
            if (m != null) {
                scopes.add(GlobalSearchScope.moduleScope(m));
            }
        }
        scopes.add(GlobalSearchScope.projectScope(project));
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        for (GlobalSearchScope scope : scopes) {
            for (String name : CONFIG_FILES) {
                for (VirtualFile file : FilenameIndex.getVirtualFilesByName(name, scope)) {
                    if (!index.isInTestSourceContent(file)) {
                        result.add(file);
                    }
                }
            }
            if (!result.isEmpty()) {
                break;
            }
        }
        return result;
    }

    private static String configValue(List<VirtualFile> files, String key) {
        for (VirtualFile file : files) {
            try {
                String value = HttpText.configValue(file.getName(), VfsUtilCore.loadText(file), key);
                if (value != null) {
                    return value;
                }
            } catch (IOException e) {
                LOG.warn("설정 파일을 읽지 못했습니다: " + file.getPath(), e);
            }
        }
        return null;
    }
}
