package local.bootservers;

import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 스프링 부트 모듈의 애플리케이션 실행 설정 하나를 서버 하나로 보고 시작 · 중지 · 재시작한다.
 *
 * 한 모듈을 프로필만 달리해 여러 실행 설정(dxplm-gpm · dxplm-ptn 등)으로 띄울 수 있다.
 * 모듈 기준 동작(프로젝트 트리 우클릭 · 트리 상태 표시)은 모듈 이름과 같은 실행 설정을 기본으로 삼고,
 * 처음 시작할 때 그 이름으로 실행 설정을 만든다. 이후에는 그 설정을 재사용하므로
 * 사용자가 실행 설정에서 고친 포트 · 프로필 · VM 옵션이 그대로 유지된다.
 */
final class ServerLauncher {

    // 스프링 부트 2.x · 3.x 공통 정규 이름
    private static final String BOOT_APP_ANNOTATION = "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String TITLE = "스프링 부트 서버";

    private ServerLauncher() {
    }

    /** 모듈 이름과 같은 이름의 애플리케이션 실행 설정. 없으면 null. */
    static RunnerAndConfigurationSettings findSettings(Module module) {
        RunManager runManager = RunManager.getInstance(module.getProject());
        return runManager.findConfigurationByTypeAndName(ApplicationConfigurationType.getInstance(), module.getName());
    }

    /**
     * 이 모듈(Gradle 이면 폴더 모듈 또는 그 .main 모듈)을 실행 모듈로 둔 애플리케이션 실행 설정 목록. 이름순.
     * 메인 클래스는 보지 않으므로, 서버 창은 {@link #serverModules} 의 메인 클래스 목록으로 한 번 더 거른다.
     */
    static List<RunnerAndConfigurationSettings> settingsOf(Module module) {
        ModuleManager moduleManager = ModuleManager.getInstance(module.getProject());
        List<RunnerAndConfigurationSettings> result = new ArrayList<>();
        for (RunnerAndConfigurationSettings s : RunManager.getInstance(module.getProject())
                .getConfigurationSettingsList(ApplicationConfigurationType.getInstance())) {
            Module m = ((ApplicationConfiguration) s.getConfiguration()).getConfigurationModule().getModule();
            if (m != null && folderModule(moduleManager, m).equals(module)) {
                result.add(s);
            }
        }
        result.sort(Comparator.comparing(RunnerAndConfigurationSettings::getName));
        return result;
    }

    /** 살아 있는 실행 화면 목록. 모두 종료됐으면 빈 목록. */
    static List<RunContentDescriptor> runningDescriptors(RunnerAndConfigurationSettings settings) {
        List<RunContentDescriptor> result = new ArrayList<>();
        List<RunContentDescriptor> descriptors = ExecutionManagerImpl.getInstance(settings.getConfiguration().getProject())
                .getRunningDescriptors(s -> s == settings);
        for (RunContentDescriptor descriptor : descriptors) {
            ProcessHandler handler = descriptor.getProcessHandler();
            if (handler != null && !handler.isProcessTerminated()) {
                result.add(descriptor);
            }
        }
        return result;
    }

    /** 이 모듈의 실행 설정 가운데 살아 있는 것. 없으면 빈 목록. */
    private static List<RunnerAndConfigurationSettings> liveSettings(Module module) {
        List<RunnerAndConfigurationSettings> result = new ArrayList<>();
        for (RunnerAndConfigurationSettings s : settingsOf(module)) {
            if (!runningDescriptors(s).isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }

    static boolean isRunning(Module module) {
        return !liveSettings(module).isEmpty();
    }

    /**
     * 서버를 시작한다. 실행 설정이 없으면 모듈 안의 @SpringBootApplication 클래스로 새로 만든다.
     * 색인 중이거나 메인 클래스를 하나로 정할 수 없으면 알림만 띄우고 시작하지 않는다.
     */
    static boolean start(Module module, Executor executor) {
        RunnerAndConfigurationSettings settings = ensureSettings(module);
        if (settings != null) {
            start(settings, executor);
        }
        return settings != null;
    }

    static void start(RunnerAndConfigurationSettings settings, Executor executor) {
        RunManager.getInstance(settings.getConfiguration().getProject()).setSelectedConfiguration(settings);
        ProgramRunnerUtil.executeConfiguration(settings, executor);
    }

    /** 모듈 이름의 실행 설정. 없으면 메인 클래스로 새로 만든다. 만들 수 없으면 사유를 알리고 null. */
    static RunnerAndConfigurationSettings ensureSettings(Module module) {
        RunnerAndConfigurationSettings settings = findSettings(module);
        if (settings != null) {
            return settings;
        }
        PsiClass mainClass = findMainClass(module);
        return mainClass == null ? null : createSettings(module, mainClass);
    }

    /** 이 모듈의 살아 있는 실행 설정을 모두 중지한다. */
    static void stop(Module module) {
        for (RunnerAndConfigurationSettings s : liveSettings(module)) {
            stop(s);
        }
    }

    /** 살아 있는 실행 화면을 모두 중지한다. */
    static void stop(RunnerAndConfigurationSettings settings) {
        ServerRegistry.getInstance(settings.getConfiguration().getProject()).markStopped(settings.getName());
        for (RunContentDescriptor descriptor : runningDescriptors(settings)) {
            ExecutionManagerImpl.stopProcess(descriptor);
        }
    }

    /** 이 모듈의 살아 있는 실행 설정을 모두 재시작한다. */
    static void restart(Module module) {
        for (RunnerAndConfigurationSettings s : liveSettings(module)) {
            restart(s);
        }
    }

    /** 첫 실행 화면을 같은 실행기(실행 · 디버그)로 다시 띄운다. 종료를 기다린 뒤 시작하므로 포트가 겹치지 않는다. */
    static void restart(RunnerAndConfigurationSettings settings) {
        List<RunContentDescriptor> descriptors = runningDescriptors(settings);
        if (descriptors.isEmpty()) {
            return;
        }
        ExecutionUtil.restart(descriptors.get(0));
    }

    /**
     * 우클릭한 모듈 폴더 아래(본 소스만, 시험 소스 제외)에서 @SpringBootApplication 클래스를 찾는다.
     * Gradle 프로젝트는 폴더 모듈(supp)과 소스 묶음 모듈(supp.main · supp.test)이 나뉘고 의존성은 supp.main 에만 붙으므로,
     * 우클릭한 모듈 하나가 아니라 그 폴더 아래 전체를 본다.
     * 정확히 하나일 때만 돌려주고, 없거나 여럿이면 사유를 알리고 null 을 돌려준다.
     */
    private static PsiClass findMainClass(Module module) {
        Project project = module.getProject();
        if (DumbService.isDumb(project)) {
            Messages.showWarningDialog(project, "색인이 끝난 뒤 다시 시도하세요.", TITLE);
            return null;
        }
        PsiClass annotation = JavaPsiFacade.getInstance(project)
                .findClass(BOOT_APP_ANNOTATION, GlobalSearchScope.allScope(project));
        if (annotation == null) {
            Messages.showErrorDialog(project,
                    "프로젝트에 스프링 부트 의존성이 없습니다. Gradle · Maven 프로젝트로 연결됐는지 확인하세요.", TITLE);
            return null;
        }
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Collection<PsiClass> found = new ArrayList<>();
        for (PsiClass c : AnnotatedElementsSearch.searchPsiClasses(annotation, GlobalSearchScope.projectScope(project)).findAll()) {
            VirtualFile file = c.getContainingFile() == null ? null : c.getContainingFile().getVirtualFile();
            if (file != null && !index.isInTestSourceContent(file) && isUnderAny(file, roots)) {
                found.add(c);
            }
        }
        if (found.isEmpty()) {
            Messages.showErrorDialog(project, "'" + module.getName() + "' 모듈에 @SpringBootApplication 클래스가 없습니다.", TITLE);
            return null;
        }
        if (found.size() > 1) {
            Messages.showErrorDialog(project,
                    "'" + module.getName() + "' 모듈에 @SpringBootApplication 클래스가 " + found.size()
                            + "개 있습니다. 모듈 이름과 같은 이름으로 실행 설정을 직접 만들면 그 설정으로 시작합니다.",
                    TITLE);
            return null;
        }
        return found.iterator().next();
    }

    /** 서버 창 · 트리에 보여 줄 상태. */
    enum State {
        RUNNING("실행 중"), STARTING("시작 중"), STOPPED("중지됨"), FAILED("실패");

        final String label;

        State(String label) {
            this.label = label;
        }
    }

    /**
     * 모듈 기준 상태(프로젝트 트리 · 우클릭 메뉴). 살아 있는 실행 설정이 있으면 모듈 이름 설정을 우선해 그 상태,
     * 없으면 모듈 이름 설정의 마지막 상태(실패 · 중지됨)다.
     */
    static State state(Module module) {
        RunnerAndConfigurationSettings named = findSettings(module);
        List<RunnerAndConfigurationSettings> live = liveSettings(module);
        if (!live.isEmpty()) {
            return state(live.contains(named) ? named : live.get(0));
        }
        return named == null ? State.STOPPED : state(named);
    }

    static State state(RunnerAndConfigurationSettings settings) {
        ServerRegistry.Run run = ServerRegistry.getInstance(settings.getConfiguration().getProject()).get(settings.getName());
        if (!runningDescriptors(settings).isEmpty()) {
            // 기록이 없으면 이 플러그인이 보기 전에 뜬 실행이므로 시작이 끝난 것으로 본다.
            return run == null || run.started ? State.RUNNING : State.STARTING;
        }
        return run != null && run.failed() ? State.FAILED : State.STOPPED;
    }

    /**
     * @SpringBootApplication 클래스(시험 소스 제외)를 가진 모듈과 그 메인 클래스 정규 이름들. 모듈 이름순.
     * 읽기 동작 안에서, 색인이 끝난 뒤 불러야 한다.
     */
    static Map<Module, Set<String>> serverModules(Project project) {
        PsiClass annotation = JavaPsiFacade.getInstance(project)
                .findClass(BOOT_APP_ANNOTATION, GlobalSearchScope.allScope(project));
        if (annotation == null) {
            return Map.of();
        }
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        Map<Module, Set<String>> result = new TreeMap<>(Comparator.comparing(Module::getName));
        for (PsiClass c : AnnotatedElementsSearch.searchPsiClasses(annotation, GlobalSearchScope.projectScope(project)).findAll()) {
            VirtualFile file = c.getContainingFile() == null ? null : c.getContainingFile().getVirtualFile();
            Module module = ModuleUtilCore.findModuleForPsiElement(c);
            if (file != null && module != null && c.getQualifiedName() != null && !index.isInTestSourceContent(file)) {
                result.computeIfAbsent(folderModule(moduleManager, module), k -> new TreeSet<>()).add(c.getQualifiedName());
            }
        }
        return result;
    }

    /** Gradle 은 소스 묶음마다 「폴더 모듈.main」 모듈을 만든다. 서버 이름은 우클릭하는 폴더 모듈 이름으로 맞춘다. */
    private static Module folderModule(ModuleManager moduleManager, Module module) {
        String name = module.getName();
        if (name.endsWith(".main")) {
            Module folder = moduleManager.findModuleByName(name.substring(0, name.length() - ".main".length()));
            if (folder != null) {
                return folder;
            }
        }
        return module;
    }

    private static boolean isUnderAny(VirtualFile file, VirtualFile[] roots) {
        for (VirtualFile root : roots) {
            if (VfsUtilCore.isAncestor(root, file, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 실행 설정 이름은 우클릭한 모듈 이름으로 두어(트리 「실행 중」 표시가 이 이름으로 찾는다),
     * 실행 클래스패스는 메인 클래스가 실제로 들어 있는 모듈(Gradle 이면 supp.main)로 잡는다.
     */
    private static RunnerAndConfigurationSettings createSettings(Module module, PsiClass mainClass) {
        RunManager runManager = RunManager.getInstance(module.getProject());
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(
                module.getName(), ApplicationConfigurationType.getInstance().getConfigurationFactories()[0]);
        ApplicationConfiguration configuration = (ApplicationConfiguration) settings.getConfiguration();
        configuration.setMainClass(mainClass);
        Module classModule = ModuleUtilCore.findModuleForPsiElement(mainClass);
        configuration.setModule(classModule != null ? classModule : module);
        runManager.addConfiguration(settings);
        return settings;
    }
}
