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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버 창의 상태 점검 · 대시보드가 액추에이터 health · metrics 를 HTTP 대신 JMX 로 읽을 수 있게, 액추에이터가 든 애플리케이션 실행에
 * -Dspring.jmx.enabled=true 와 -Dmanagement.endpoints.jmx.exposure.include 를 넣는다. 웹(HTTP) 노출은 건드리지 않는다.
 * 서버 소스 · 설정 파일은 바꾸지 않는다. JMX 로 읽으면 앱의 보안 필터를 거치지 않아 로그인 · 세션 · 접근 로그가 생기지 않는다.
 * 설정 파일에 이미 JMX 노출 목록이 있으면 그 목록에 더한 값을 넣는다(시스템 속성이 설정 파일을 이기므로 원래 목록을 잃지 않게).
 * 목록이 「*」 이거나 이미 둘 다 있거나, VM 옵션에 같은 키가 있으면 그 키는 넣지 않는다. 환경 변수로 준 값은 보지 않는다.
 * 설정 파일이 spring.jmx.enabled 를 false 로 꺼 두었으면 아무것도 넣지 않는다(팀이 MBean 충돌 등으로 끈 것을 IDE 가 켜지 않게).
 * 설정 파일 · VM 옵션에 없으면 -Dspring.application.admin.enabled=true 도 넣어, 「Started …」 줄을 못 읽어도 서버 창이 시작 완료를 JMX 로 안다.
 * 스프링 부트 2.x 는 설정이 없으면 JMX 로 모든 끝점을 연다. 그때 목록을 넣으면 오히려 health · metrics 로 줄어들므로 넣지 않는다.
 */
public final class ActuatorExposurePatcher extends JavaProgramPatcher {

    // spring-boot-actuator-3.3.4.jar 처럼 판이 붙은 jar. spring-boot-actuator-autoconfigure 도 같이 걸리지만 둘은 늘 함께 온다.
    private static final String ACTUATOR_JAR = "spring-boot-actuator-";
    private static final String PROPERTY_PREFIX = "-D" + Metrics.EXPOSURE_KEY + "=";
    private static final String PROFILE_KEY = "spring.profiles.active";
    // 스프링 부트 2.2 부터 기본값이 false 라 JMX 끝점이 아예 등록되지 않는다.
    private static final String JMX_ENABLED_KEY = "spring.jmx.enabled";
    // SpringApplication 관리 MBean(시작 완료 Ready). 설정 파일에 값이 있으면(켬 · 끔) 그대로 둔다.
    private static final String ADMIN_KEY = "spring.application.admin.enabled";
    // 액추에이터 jar 이름의 큰 판 번호(spring-boot-actuator-2.7.18.jar · spring-boot-actuator-autoconfigure-3.3.4.jar).
    private static final Pattern ACTUATOR_MAJOR = Pattern.compile("^spring-boot-actuator-(?:autoconfigure-)?(\\d+)\\.");
    // 모든 끝점을 JMX 로 여는 것이 기본인 마지막 큰 판(3.0 부터 기본은 health 만).
    private static final int EXPOSE_ALL_BY_DEFAULT_MAJOR = 2;

    @Override
    public void patchJavaParameters(Executor executor, RunProfile configuration, JavaParameters javaParameters) {
        int major = actuatorMajor(javaParameters);
        if (!(configuration instanceof ApplicationConfiguration app) || major < 0) {
            return;
        }
        ParametersList vm = javaParameters.getVMParametersList();
        Module module = app.getConfigurationModule().getModule();
        // 설정 파일의 { spring.jmx.enabled, JMX 노출 목록, spring.application.admin.enabled }. 모듈이 없으면 모두 모른다.
        String[] config = module == null ? new String[3] : ReadAction.compute(() -> {
            if (module.isDisposed()) {
                return new String[3];
            }
            SpringWebConfig.ModuleConfig read = SpringWebConfig.moduleConfig(module);
            Set<String> profiles = Links.profiles(vm.getPropertyValue(PROFILE_KEY), read.sources());
            return new String[] {Links.value(read.sources(), profiles, JMX_ENABLED_KEY), Links.value(read.sources(), profiles, Metrics.EXPOSURE_KEY),
                    Links.value(read.sources(), profiles, ADMIN_KEY)};
        });
        if (!vm.hasProperty(JMX_ENABLED_KEY)) {
            if (Boolean.FALSE.toString().equalsIgnoreCase(config[0] == null ? "" : config[0].strip())) {
                return;
            }
            vm.addProperty(JMX_ENABLED_KEY, Boolean.TRUE.toString());
        }
        // 시작 완료(Ready)를 JMX 로 알리는 관리 MBean. 「Started …」 줄이 콘솔에 안 나와도 서버 창이 「실행 중」을 안다(ServerProbe.ready).
        if (!vm.hasProperty(ADMIN_KEY) && config[2] == null) {
            vm.addProperty(ADMIN_KEY, Boolean.TRUE.toString());
        }
        for (String p : vm.getList()) {
            if (p.startsWith(PROPERTY_PREFIX)) {
                return;
            }
        }
        String existing = config[1];
        if (existing == null && major <= EXPOSE_ALL_BY_DEFAULT_MAJOR) {
            return;
        }
        String merged = Metrics.exposure(existing);
        if (merged != null) {
            vm.addProperty(Metrics.EXPOSURE_KEY, merged);
        }
    }

    /** 클래스패스의 액추에이터 큰 판 번호. 액추에이터가 없으면 -1, 판을 읽지 못하면(이름에 판이 없는 jar) 3 으로 본다. */
    private static int actuatorMajor(JavaParameters parameters) {
        for (String path : parameters.getClassPath().getPathList()) {
            String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
            if (name.startsWith(ACTUATOR_JAR)) {
                Matcher m = ACTUATOR_MAJOR.matcher(name);
                return m.find() ? Integer.parseInt(m.group(1)) : EXPOSE_ALL_BY_DEFAULT_MAJOR + 1;
            }
        }
        return -1;
    }
}
