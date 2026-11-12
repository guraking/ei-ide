package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        // 백그라운드 스레드는 예외를 삼키므로 여기서 기록한다.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                register();
            } catch (RuntimeException e) {
                LOG.warn("JDK 자동 등록 실패", e);
            }
        });
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
        // JDK 판 읽기 · 루트 훑기(setupSdk)는 이 백그라운드 스레드에서 하고, 화면 스레드에서는 목록에 넣기만 한다.
        // 화면 스레드에서 만들면 JDK 마다 모달 진행 창이 떠 시작을 막는다.
        List<Sdk> known = new ArrayList<>(Arrays.asList(ReadAction.compute(() -> ProjectJdkTable.getInstance().getAllJdks())));
        Set<Path> existing = homesOf(known);
        Map<String, Sdk> created = new LinkedHashMap<>();
        for (String home : candidates) {
            if (existing.contains(normalize(home))) {
                created.put(home, null);
                continue;
            }
            VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(home));
            // 만든 이름이 서로 겹치지 않게 앞에서 만든 SDK 도 함께 넘긴다.
            Sdk sdk = dir == null ? null : SdkConfigurationUtil.setupSdk(known.toArray(Sdk[]::new), dir, type, true, null, null);
            if (sdk == null) {
                LOG.warn("JDK 를 등록하지 못했습니다: " + home);
                continue;
            }
            known.add(sdk);
            created.put(home, sdk);
        }
        if (created.isEmpty()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            // 그 사이 사용자가 같은 폴더를 넣었으면 다시 넣지 않는다.
            Set<Path> now = homesOf(Arrays.asList(ProjectJdkTable.getInstance().getAllJdks()));
            for (Map.Entry<String, Sdk> entry : created.entrySet()) {
                if (entry.getValue() != null && !now.contains(normalize(entry.getKey()))) {
                    SdkConfigurationUtil.addSdk(entry.getValue());
                }
                done.add(entry.getKey());
            }
            done.remove("");
            props.setValue(DONE_KEY, String.join(SEPARATOR, done));
        }, ModalityState.nonModal());
    }

    private static Set<Path> homesOf(List<Sdk> sdks) {
        Set<Path> homes = new HashSet<>();
        for (Sdk sdk : sdks) {
            if (sdk.getHomePath() != null) homes.add(normalize(sdk.getHomePath()));
        }
        return homes;
    }

    private static Path normalize(String home) {
        return Path.of(home).toAbsolutePath().normalize();
    }
}
