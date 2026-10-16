package kinggura.sync;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsListener;
import com.intellij.openapi.vcs.VcsRoot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Git 저장소마다 .git/info/exclude 에 IDE 파일 무시 줄을 넣어 변경 목록에 .iml · .idea 가 뜨지 않게 한다.
 * exclude 는 저장소 안에 있지만 git 이 추적하지 않으므로 팀 .gitignore 와 서버에는 아무 영향이 없다.
 * 이미 추적 중인 파일에는 효과가 없다(그건 git rm --cached 로 빼야 한다). .git 이 폴더가 아닌 저장소(worktree · submodule)는 건너뛴다.
 */
public final class ExcludeIdeFiles implements VcsListener {

    private static final Logger LOG = Logger.getInstance(ExcludeIdeFiles.class);
    private static final List<String> LINES = List.of("*.iml", ".idea/");

    private final Project project;

    public ExcludeIdeFiles(Project project) {
        this.project = project;
    }

    @Override
    public void directoryMappingChanged() {
        for (VcsRoot root : ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()) {
            if (root.getVcs() == null || !"Git".equals(root.getVcs().getName())) {
                continue;
            }
            Path exclude = root.getPath().toNioPath().resolve(".git").resolve("info").resolve("exclude");
            if (!Files.isDirectory(exclude.getParent().getParent())) {
                continue;
            }
            try {
                List<String> have = Files.exists(exclude) ? Files.readAllLines(exclude, StandardCharsets.UTF_8) : List.of();
                List<String> add = new ArrayList<>();
                for (String line : LINES) {
                    if (!have.contains(line)) {
                        add.add(line);
                    }
                }
                if (add.isEmpty()) {
                    continue;
                }
                Files.createDirectories(exclude.getParent());
                List<String> out = new ArrayList<>(have);
                out.add("# EI IDE: IDE 가 만드는 파일. 변경 목록에 띄우지 않는다.");
                out.addAll(add);
                Files.write(exclude, out, StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.warn("exclude 갱신 실패: " + exclude, e);
            }
        }
    }
}
