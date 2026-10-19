package local.bootservers;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;

/**
 * JDK 24 이상으로 자바 프로그램을 실행할 때 sun.misc.Unsafe 메모리 메서드 경고(JEP 471 · 498)를 끈다.
 * 오래된 라이브러리(jvm-driver 등)가 부르는 Unsafe::putInt 같은 호출은 JDK 24 부터 기본값이 warn 이라
 * 콘솔 맨 앞에 WARNING 네 줄이 찍힌다. 동작은 바뀌지 않고 경고만 사라진다.
 * 사용자가 VM 옵션에 같은 옵션을 이미 넣었으면 그 값을 따른다. JDK 23 이하에는 넣지 않는다.
 */
public final class UnsafeWarningPatcher extends JavaProgramPatcher {

    private static final String OPTION = "--sun-misc-unsafe-memory-access";
    private static final String ALLOW = OPTION + "=allow";
    // 경고가 기본으로 켜지는 첫 판(JEP 498)
    private static final JavaSdkVersion WARN_FROM = JavaSdkVersion.JDK_24;

    @Override
    public void patchJavaParameters(Executor executor, RunProfile configuration, JavaParameters javaParameters) {
        Sdk jdk = javaParameters.getJdk();
        if (jdk == null) {
            return;
        }
        JavaSdkVersion version = JavaSdk.getInstance().getVersion(jdk);
        if (version == null || !version.isAtLeast(WARN_FROM)) {
            return;
        }
        ParametersList vm = javaParameters.getVMParametersList();
        for (String p : vm.getList()) {
            if (p.startsWith(OPTION)) {
                return;
            }
        }
        vm.add(ALLOW);
    }
}
