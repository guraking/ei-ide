package kinggura.sync;

import com.intellij.dvcs.DvcsRememberedInputs;
import com.intellij.dvcs.repo.ClonePathProvider;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Clone 창(Git · GitHub 탭)의 Directory 기본값.
 * 프로젝트 폴더가 Git 저장소 · SVN 작업본 안이면 그 저장소(위로 올라가며 .git · .svn 이 있는 가장 바깥 폴더)의 상위 폴더
 * (SVN 프로젝트에서 받은 Git 은 새 프로젝트로 열리므로 SVN 작업본 밖에 둔다, CloneIntoProject).
 * 안쪽 저장소를 연 경우도 바깥 저장소 밖에 둔다: 프로젝트 · 저장소 C:\dev\git\supp-backend\supp, 바깥 저장소 C:\dev\git\supp-backend → C:\dev\git\basic.
 * 사용자 폴더와 그 위 · 드라이브 맨 위에 우연히 있는 .git 은 보지 않는다(CloneIntoProject.aboveWorkspaces).
 * 저장소 밖이면 프로젝트 폴더를 워크스페이스로 보고 그 안: 프로젝트 C:\dev\git → C:\dev\git\basic.
 * 프로젝트가 없거나(시작 화면) 상위 폴더가 없으면 null 을 돌려 IntelliJ 기본값(마지막 Clone 폴더)을 쓴다.
 */
public final class CloneNextToProject extends ClonePathProvider {

  @Override
  public @Nullable String getParentDirectoryPath(@NotNull Project project, @NotNull DvcsRememberedInputs rememberedInputs) {
    String base = project.isDefault() ? null : project.getBasePath();
    if (base == null) return null;
    Path repo = null;
    for (Path dir = Path.of(base); dir != null && !CloneIntoProject.aboveWorkspaces(dir); dir = dir.getParent()) {
      if (Files.exists(dir.resolve(".git")) || Files.isDirectory(dir.resolve(".svn"))) repo = dir;
    }
    if (repo == null) return base;
    Path parent = repo.getParent();
    return parent == null ? null : parent.toString();
  }

  @Override
  public @Nullable String getRelativeDirectoryPathForVcsUrl(@NotNull Project project, @NotNull String vcsUrl) {
    return null;
  }
}
