package local.bootservers;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 개발 서버의 기본 주소(http://localhost:포트 + 컨텍스트 경로).
 * 실행 중인 서버의 포트(controller 모듈을 실행하는 서버 우선) → 설정 파일 server.port → 8080, 여기에 컨텍스트 경로.
 * 설정 파일은 연결 지도와 같은 규칙(SpringWebConfig.moduleConfig + Links)으로 읽는다: application-{프로필}.* · yml 프로필 문서는
 * 켜진 프로필(실행 설정 VM 옵션의 spring.profiles.active, 없으면 설정 파일의 값)일 때만 읽고, 뒤에 읽은 값이 이긴다.
 */
final class ServerBase {

    private static final String PROFILE_KEY = "spring.profiles.active";

    private ServerBase() {
    }

    /** 읽기 동작 안에서 부른다. */
    static String resolve(Project project, @Nullable Module controller) {
        Integer port = null;
        RunnerAndConfigurationSettings running = null;
        Module runningModule = null;
        ServerRegistry registry = ServerRegistry.getInstance(project);
        for (RunnerAndConfigurationSettings s : RunManager.getInstance(project)
                .getConfigurationSettingsList(ApplicationConfigurationType.getInstance())) {
            ServerRegistry.Run run = registry.get(s.getName());
            if (run == null || run.port == null || ServerLauncher.runningDescriptors(s).isEmpty()) {
                continue;
            }
            Module m = moduleOf(s);
            boolean serves = controller != null && m != null
                    && (m.equals(controller) || ModuleUtilCore.getAllDependentModules(controller).contains(m));
            if (port == null || serves) {
                port = run.port;
                running = s;
                runningModule = m;
            }
            if (serves) {
                break;
            }
        }
        Module configModule = configModule(project, controller, runningModule);
        String contextPath = null;
        if (configModule != null) {
            List<Links.Source> sources = SpringWebConfig.moduleConfig(configModule).sources();
            RunnerAndConfigurationSettings profileFrom = running != null ? running : settingsOf(configModule);
            Set<String> profiles = Links.profiles(vmProfile(profileFrom), sources);
            if (port == null) {
                port = HttpText.parsePort(Links.value(sources, profiles, HttpText.PORT_KEY));
            }
            for (String key : HttpText.CONTEXT_PATH_KEYS) {
                contextPath = Links.value(sources, profiles, key);
                if (contextPath != null) {
                    break;
                }
            }
        }
        return HttpText.baseUrl(port != null ? port : HttpText.DEFAULT_PORT, contextPath);
    }

    /** 컨트롤러 모듈 → 실행 중인 서버 모듈 → 프로젝트 모듈(의존 차례) 가운데 설정 파일이 처음 있는 모듈. 없으면 null. */
    private static @Nullable Module configModule(Project project, @Nullable Module controller, @Nullable Module running) {
        List<Module> candidates = new ArrayList<>();
        for (Module m : new Module[]{controller, running}) {
            if (m != null) {
                candidates.add(m);
            }
        }
        Module[] all = ModuleManager.getInstance(project).getSortedModules();
        candidates.addAll(List.of(all));
        for (Module m : candidates) {
            if (!m.isDisposed() && !SpringWebConfig.configFiles(m).isEmpty()) {
                return m;
            }
        }
        return null;
    }

    /** 이 모듈의 서버 실행 설정: 모듈 이름과 같은 것, 없으면 이름순 첫 것. 없으면 null. */
    private static @Nullable RunnerAndConfigurationSettings settingsOf(Module module) {
        Module folder = ServerLauncher.folderModule(ModuleManager.getInstance(module.getProject()), module);
        RunnerAndConfigurationSettings named = ServerLauncher.findSettings(folder);
        if (named != null) {
            return named;
        }
        List<RunnerAndConfigurationSettings> list = ServerLauncher.settingsOf(folder);
        return list.isEmpty() ? null : list.get(0);
    }

    private static @Nullable Module moduleOf(RunnerAndConfigurationSettings settings) {
        return ((ApplicationConfiguration) settings.getConfiguration()).getConfigurationModule().getModule();
    }

    /** 실행 설정 VM 옵션의 spring.profiles.active. 실행 설정이 없거나 값이 없으면 빈 글. */
    private static String vmProfile(@Nullable RunnerAndConfigurationSettings settings) {
        if (settings == null) {
            return "";
        }
        String vm = ((ApplicationConfiguration) settings.getConfiguration()).getVMParameters();
        return ServersPanel.getProperty(vm == null ? "" : vm, PROFILE_KEY);
    }
}
