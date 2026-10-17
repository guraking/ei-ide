package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * IDE 를 켤 때 사용자 폴더의 .jdks 아래 JDK(플러그인설치.bat 이 푼 Temurin 등)를 SDK 목록에 등록한다.
 * 한 번 등록한 폴더는 기억해 두고 다시 넣지 않는다. 사용자가 SDK 목록에서 지워도 되살리지 않기 위해서다.
 * 이미 같은 폴더를 가리키는 SDK 가 있으면 건너뛴다.
 */
public final class JdkAutoRegister implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(JdkAutoRegister.class);
    // 인텔리제이가 JDK 를 내려받는 기본 자리와 같다.
    private static final String JDKS_DIR = ".jdks";
    private static final String JAVA_SDK_TYPE = "JavaSDK";
    private static final String DONE_KEY = "ei.jdks.registered";
    private static final String SEPARATOR = "\n";

    @Override
    public void appStarted() {
        ApplicationManager.getApplication().executeOnPooledThread(JdkAutoRegister::register);
    }

    private static void register() {
        File[] dirs = new File(System.getProperty("user.home"), JDKS_DIR).listFiles(File::isDirectory);
        SdkType type = SdkType.findByName(JAVA_SDK_TYPE);
        if (dirs == null || type == null) return;
        PropertiesComponent props = PropertiesComponent.getInstance();
        Set<String> done = new HashSet<>(Arrays.asList(props.getValue(DONE_KEY, "").split(SEPARATOR)));
        List<String> candidates = new ArrayList<>();
        for (File dir : dirs) {
            String home = dir.getAbsolutePath();
            if (!done.contains(home) && type.isValidSdkHome(home)) candidates.add(home);
        }
        if (candidates.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            Set<Path> existing = new HashSet<>();
            for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
                if (sdk.getHomePath() != null) existing.add(Path.of(sdk.getHomePath()).toAbsolutePath().normalize());
            }
            for (String home : candidates) {
                if (!existing.contains(Path.of(home).toAbsolutePath().normalize())) {
                    Sdk added = SdkConfigurationUtil.createAndAddSDK(home, type);
                    if (added == null) {
                        LOG.warn("JDK 를 등록하지 못했습니다: " + home);
                        continue;
                    }
                }
                done.add(home);
            }
            done.remove("");
            props.setValue(DONE_KEY, String.join(SEPARATOR, done));
        }, ModalityState.nonModal());
    }
}
