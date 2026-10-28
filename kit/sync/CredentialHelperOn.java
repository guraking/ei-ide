package kinggura.sync;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import git4idea.config.GitVcsApplicationSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Git 설정의 「Use credential helper」(자격 증명 도우미 사용)를 켠다.
 * 꺼져 있으면 IntelliJ 는 https 아이디 · 비밀번호를 묻기 위해 IDE 임시 폴더의 intellij-git-askpass-local.sh 를 git 이 띄우게 하는데,
 * 보안 프로그램이 이 스크립트 실행을 막는 PC(당사 PC)에서는 Push 가 「could not read Username」으로 실패한다.
 * 켜면 git 은 Git Credential Manager(윈도우 자격 증명 관리자)를 써서 그 스크립트를 띄우지 않는다.
 * IDE 설정마다 한 번만 적용한다. 그 뒤 사용자가 끄면 그대로 둔다.
 */
public final class CredentialHelperOn implements StartupActivity.DumbAware {
  /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. */
  private static final String APPLIED_KEY = "kinggura.sync.credentialHelperOn.applied";

  @Override
  public void runActivity(@NotNull Project project) {
    PropertiesComponent properties = PropertiesComponent.getInstance();
    if (properties.getBoolean(APPLIED_KEY)) return;
    properties.setValue(APPLIED_KEY, true);
    GitVcsApplicationSettings.getInstance().setUseCredentialHelper(true);
  }
}
