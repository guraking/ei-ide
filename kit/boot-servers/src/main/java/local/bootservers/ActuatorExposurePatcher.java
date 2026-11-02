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
 * 서버 창의 상태 점검 · 대시보드가 액추에이터 health · metrics 를 HTTP 대신 JMX 로 읽을 수 있게, 액추에이터가 든 애플리케이션 실행에
 * -Dspring.jmx.enabled=true 와 -Dmanagement.endpoints.jmx.exposure.include 를 넣는다. 웹(HTTP) 노출은 건드리지 않는다.
 * 서버 소스 · 설정 파일은 바꾸지 않는다. JMX 로 읽으면 앱의 보안 필터를 거치지 않아 로그인 · 세션 · 접근 로그가 생기지 않는다.
 * 설정 파일에 이미 JMX 노출 목록이 있으면 그 목록에 더한 값을 넣는다(시스템 속성이 설정 파일을 이기므로 원래 목록을 잃지 않게).
 * 목록이 「*」 이거나 이미 둘 다 있거나, VM 옵션에 같은 키가 있으면 그 키는 넣지 않는다. 환경 변수로 준 값은 보지 않는다.
 */
public final class ActuatorExposurePatcher extends JavaProgramPatcher {

    // spring-boot-actuator-3.3.4.jar 처럼 판이 붙은 jar. spring-boot-actuator-autoconfigure 도 같이 걸리지만 둘은 늘 함께 온다.
    private static final String ACTUATOR_JAR = "spring-boot-actuator-";
    private static final String PROPERTY_PREFIX = "-D" + Metrics.EXPOSURE_KEY + "=";
    private static final String PROFILE_KEY = "spring.profiles.active";
    // 스프링 부트 2.2 부터 기본값이 false 라 JMX 끝점이 아예 등록되지 않는다.
    private static final String JMX_ENABLED_KEY = "spring.jmx.enabled";

    @Override
    public void patchJavaParameters(Executor executor, RunProfile configuration, JavaParameters javaParameters) {
        if (!(configuration instanceof ApplicationConfiguration app) || !hasActuator(javaParameters)) {
            return;
        }
        ParametersList vm = javaParameters.getVMParametersList();
        if (!vm.hasProperty(JMX_ENABLED_KEY)) {
            vm.addProperty(JMX_ENABLED_KEY, Boolean.TRUE.toString());
        }
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
