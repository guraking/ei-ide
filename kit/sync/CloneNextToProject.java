package kinggura.sync;

import com.intellij.dvcs.DvcsRememberedInputs;
import com.intellij.dvcs.repo.ClonePathProvider;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Clone 창(Git · GitHub 탭)의 Directory 기본값을 열린 프로젝트가 든 Git 저장소의 상위 폴더로 한다.
 * 저장소는 프로젝트 폴더에서 위로 올라가며 .git 이 있는 가장 바깥 폴더이고, 없으면 프로젝트 폴더 자체다.
 * 프로젝트가 C:\dev\git\supp-backend\supp 이고 저장소가 C:\dev\git\supp-backend 면 basic 은 C:\dev\git\basic 으로 제안된다.
 * 프로젝트가 없거나(시작 화면) 상위 폴더가 없으면 null 을 돌려 IntelliJ 기본값(마지막 Clone 폴더)을 쓴다.
 */
public final class CloneNextToProject extends ClonePathProvider {

  @Override
  public @Nullable String getParentDirectoryPath(@NotNull Project project, @NotNull DvcsRememberedInputs rememberedInputs) {
    String base = project.isDefault() ? null : project.getBasePath();
    if (base == null) return null;
    Path root = Path.of(base);
    for (Path dir = root; dir != null; dir = dir.getParent()) {
      if (Files.exists(dir.resolve(".git"))) root = dir;
    }
    Path parent = root.getParent();
    return parent == null ? null : parent.toString();
  }

  @Override
  public @Nullable String getRelativeDirectoryPathForVcsUrl(@NotNull Project project, @NotNull String vcsUrl) {
    return null;
  }
}
