package kinggura.sync;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.vcs.commit.CommitModeManager;
import git4idea.config.GitVcsApplicationSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Git 스테이징 영역을 끈다. 1054 판이 켜 둔 값(git.xml STAGING_AREA_ENABLED=true)이 기존 설치에 남아 있어서다.
 * IDE 설정마다 한 번만 적용한다. 그 뒤 사용자가 설정 → Git → Enable staging area 를 켜면 그대로 둔다.
 */
public final class GitStagingOff implements StartupActivity.DumbAware {
  /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. */
  private static final String APPLIED_KEY = "kinggura.sync.gitStagingOff.applied";

  @Override
  public void runActivity(@NotNull Project project) {
    PropertiesComponent properties = PropertiesComponent.getInstance();
    if (properties.getBoolean(APPLIED_KEY)) return;
    properties.setValue(APPLIED_KEY, true);

    GitVcsApplicationSettings settings = GitVcsApplicationSettings.getInstance();
    if (!settings.isStagingAreaEnabled()) return;
    settings.setStagingAreaEnabled(false);
    // 설정값만 바꾸면 열린 커밋 창은 다음 시작까지 Staged · Unstaged 로 남는다. Git 설정 화면이 하는 것처럼 알린다.
    ApplicationManager.getApplication().getMessageBus().syncPublisher(CommitModeManager.SETTINGS).settingsChanged();
  }
}
