package kinggura.sync;

import com.intellij.dvcs.DvcsRememberedInputs;
import com.intellij.dvcs.repo.ClonePathProvider;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Clone 창(Git · GitHub 탭)의 Directory 기본값을 열린 프로젝트의 상위 폴더로 한다.
 * C:\dev\git\supp-backend 를 연 채 basic 을 받으면 C:\dev\git\basic 이 제안된다(프로젝트 안이 아니라 옆).
 * 프로젝트가 없거나(시작 화면) 상위 폴더가 없으면 null 을 돌려 IntelliJ 기본값(마지막 Clone 폴더)을 쓴다.
 */
public final class CloneNextToProject extends ClonePathProvider {

  @Override
  public @Nullable String getParentDirectoryPath(@NotNull Project project, @NotNull DvcsRememberedInputs rememberedInputs) {
    String base = project.isDefault() ? null : project.getBasePath();
    if (base == null) return null;
    Path parent = Path.of(base).getParent();
    return parent == null ? null : parent.toString();
  }

  @Override
  public @Nullable String getRelativeDirectoryPathForVcsUrl(@NotNull Project project, @NotNull String vcsUrl) {
    return null;
  }
}
