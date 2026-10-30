package local.bootservers;

import com.intellij.execution.Executor;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;

import java.util.Set;

/**
 * 서버 대시보드가 요청 수 · 응답 시간 · DB 풀을 읽을 수 있게, 액추에이터가 든 애플리케이션 실행에
 * -Dmanagement.endpoints.web.exposure.include 로 health · metrics 를 연다. 서버 소스 · 설정 파일은 바꾸지 않는다.
 * 설정 파일에 이미 노출 목록이 있으면 그 목록에 더한 값을 넣는다(시스템 속성이 설정 파일을 이기므로 원래 목록을 잃지 않게).
 * 목록이 「*」 이거나 이미 둘 다 있거나, VM 옵션에 같은 키가 있으면 넣지 않는다. 환경 변수로 준 값은 보지 않는다.
 */
public final class ActuatorExposurePatcher extends JavaProgramPatcher {

    // spring-boot-actuator-3.3.4.jar 처럼 판이 붙은 jar. spring-boot-actuator-autoconfigure 도 같이 걸리지만 둘은 늘 함께 온다.
    private static final String ACTUATOR_JAR = "spring-boot-actuator-";
    private static final String PROPERTY_PREFIX = "-D" + Metrics.EXPOSURE_KEY + "=";
    private static final String PROFILE_KEY = "spring.profiles.active";

    @Override
    public void patchJavaParameters(Executor executor, RunProfile configuration, JavaParameters javaParameters) {
        if (!(configuration instanceof ApplicationConfiguration app) || !hasActuator(javaParameters)) {
            return;
        }
        ParametersList vm = javaParameters.getVMParametersList();
        for (String p : vm.getList()) {
            if (p.startsWith(PROPERTY_PREFIX)) {
                return;
            }
        }
        Module module = app.getConfigurationModule().getModule();
        String existing = module == null ? null : ReadAction.compute(() -> {
            if (module.isDisposed()) {
                return null;
            }
            SpringWebConfig.ModuleConfig config = SpringWebConfig.moduleConfig(module);
            Set<String> profiles = Links.profiles(vm.getPropertyValue(PROFILE_KEY), config.sources());
            return Links.value(config.sources(), profiles, Metrics.EXPOSURE_KEY);
        });
        String merged = Metrics.exposure(existing);
        if (merged != null) {
            vm.addProperty(Metrics.EXPOSURE_KEY, merged);
        }
    }

    private static boolean hasActuator(JavaParameters parameters) {
        for (String path : parameters.getClassPath().getPathList()) {
            String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
            if (name.startsWith(ACTUATOR_JAR)) {
                return true;
            }
        }
        return false;
    }
}
