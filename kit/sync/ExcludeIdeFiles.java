package kinggura.sync;

import com.intellij.openapi.application.ApplicationManager;
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
 * Git 저장소마다 .git/info/exclude 에 IDE 파일 무시 줄을 넣어 변경 목록에 .iml · .idea 와 이클립스의 .project · .classpath · .settings 가 뜨지 않게 한다.
 * exclude 는 저장소 안에 있지만 git 이 추적하지 않으므로 팀 .gitignore 와 서버에는 아무 영향이 없다.
 * 이미 추적 중인 파일에는 효과가 없다(그건 git rm --cached 로 빼야 한다). .git 이 폴더가 아닌 저장소(worktree · submodule)는 건너뛴다.
 * 이클립스 파일(.project · .classpath)을 커밋해 쓰는 저장소(회사 저장소)는 새로 생긴 이클립스 파일도 커밋해야 하므로
 * 이클립스 줄(ECLIPSE_LINES)을 넣지 않고, 예전에 넣었으면 뺀다. 추적 여부는 git ls-files 로 보므로 작업 스레드에서 돈다.
 * 빼는 것은 우리 표시 줄(MARKER) 바로 아래에 이어진 우리 줄뿐이다. 사용자가 직접 적은 줄은 지우지 않는다.
 * 매핑이 바뀔 때마다 도는 갱신은 한 번에 하나씩 돈다(LOCK). 같은 exclude 를 둘이 동시에 읽고 쓰지 않는다.
 */
public final class ExcludeIdeFiles implements VcsListener {

    private static final Logger LOG = Logger.getInstance(ExcludeIdeFiles.class);
    private static final List<String> IDE_LINES = List.of("*.iml", ".idea/");
    private static final List<String> ECLIPSE_LINES = List.of(".project", ".classpath", ".settings/");
    // 저장소 어디든 추적 중인 이클립스 설정 파일.
    private static final String[] TRACKED_ECLIPSE = {"ls-files", "--", ":(glob)**/.project", ":(glob)**/.classpath"};
    private static final String MARKER = "# EI IDE: IDE 가 만드는 파일. 변경 목록에 띄우지 않는다.";
    private static final Object LOCK = new Object();

    private final Project project;

    public ExcludeIdeFiles(Project project) {
        this.project = project;
    }

    @Override
    public void directoryMappingChanged() {
        ApplicationManager.getApplication().executeOnPooledThread(this::update);
    }

    private void update() {
        synchronized (LOCK) {
            updateLocked();
        }
    }

    private void updateLocked() {
        for (VcsRoot root : ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()) {
            if (root.getVcs() == null || !"Git".equals(root.getVcs().getName())) {
                continue;
            }
            Path exclude = root.getPath().toNioPath().resolve(".git").resolve("info").resolve("exclude");
            if (!Files.isDirectory(exclude.getParent().getParent())) {
                continue;
            }
            try {
                boolean eclipseTracked = !SyncPanel.git(root.getPath().toNioPath().toFile(), TRACKED_ECLIPSE).isBlank();
                List<String> have = Files.exists(exclude) ? Files.readAllLines(exclude, StandardCharsets.UTF_8) : List.of();
                List<String> out = eclipseTracked ? withoutOurEclipseLines(have) : new ArrayList<>(have);
                List<String> add = new ArrayList<>();
                for (String line : eclipseTracked ? IDE_LINES : concat(IDE_LINES, ECLIPSE_LINES)) {
                    if (!have.contains(line)) {
                        add.add(line);
                    }
                }
                if (!add.isEmpty()) {
                    out.add(MARKER);
                    out.addAll(add);
                }
                if (out.equals(have)) {
                    continue;
                }
                Files.createDirectories(exclude.getParent());
                Files.write(exclude, out, StandardCharsets.UTF_8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.warn("exclude 갱신 실패: " + exclude, e);
            }
        }
    }

    /** 우리 표시 줄 바로 아래 이어진 우리 줄(IDE_LINES · ECLIPSE_LINES) 중 이클립스 줄을 뺀다. 그래서 비는 묶음은 표시 줄도 뺀다. 그 밖의 줄은 그대로 둔다. */
    static List<String> withoutOurEclipseLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        // out 안에서 지금 묶음의 표시 줄 위치. 묶음 밖이면 -1.
        int marker = -1;
        for (String line : lines) {
            boolean ours = IDE_LINES.contains(line) || ECLIPSE_LINES.contains(line);
            if (marker >= 0 && ours) {
                if (!ECLIPSE_LINES.contains(line)) {
                    out.add(line);
                }
                continue;
            }
            dropEmptyBlock(out, marker);
            marker = line.equals(MARKER) ? out.size() : -1;
            out.add(line);
        }
        dropEmptyBlock(out, marker);
        return out;
    }

    private static void dropEmptyBlock(List<String> out, int marker) {
        if (marker >= 0 && marker == out.size() - 1) {
            out.remove(marker);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
