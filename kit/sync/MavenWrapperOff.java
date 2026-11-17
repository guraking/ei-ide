package kinggura.sync;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.BundledMaven3;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.project.MavenWrapper;

/**
 * IntelliJ 에 넣은 플러그인 판에서 Maven 을 Maven Wrapper(mvnw) 대신 내장 Maven 3 으로 쓴다(Moa 본체 손질 kit/defaults 34 와 같은 결과).
 * 저장소에 .mvn/wrapper 가 있으면 IntelliJ 는 연결할 때 프로젝트 Maven 을 Wrapper 로 바꾸는데, 사내망은 Wrapper 배포본 받기가 막힌다.
 * 프로젝트를 열 때 한 번, 그 뒤 Maven 설정이 바뀔 때마다(연결 · 설정 창) Wrapper 면 내장으로 되돌린다.
 * 연결 첫 회에 IntelliJ 가 Wrapper 배포본을 받으려는 시도(Moa 의 34-1)는 플러그인에서 막을 수 없어 한 번 실패 알림이 날 수 있다.
 * Maven 플러그인이 켜져 있을 때만 읽힌다(kinggura-sync-maven.xml).
 */
public final class MavenWrapperOff implements StartupActivity.DumbAware {
  @Override
  public void runActivity(@NotNull Project project) {
    if (MoaProduct.isMoa()) return;
    // 설정을 다시 읽으면 IntelliJ 가 새 설정 객체로 바꾸며 듣는 쪽을 옮긴다(copyListeners). 그래서 고칠 때마다 지금 설정 객체를 다시 얻는다.
    MavenGeneralSettings.Listener toBundled = () -> {
      MavenGeneralSettings settings = MavenProjectsManager.getInstance(project).getGeneralSettings();
      if (settings.getMavenHomeType() instanceof MavenWrapper) settings.setMavenHomeType(BundledMaven3.INSTANCE);
    };
    MavenProjectsManager.getInstance(project).getGeneralSettings().addListener(toBundled, project);
    toBundled.changed();
  }
}
