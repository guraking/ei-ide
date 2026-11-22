package kinggura.sync;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.BinaryContentRevision;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.commit.CommitMessageUi;
import com.intellij.vcs.commit.CommitWorkflowUi;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 커밋 창 문구 칸 위의 「문구 넣기」. 체크한 변경(새 파일 포함)을 이 PC 안에서 읽어 무엇을 고쳤는지 메뉴별로 적는다(CommitSummary).
 * 문구 칸에 쓴 글은 지우지 않고 그 아래에 덧붙인다. 파일을 못 읽으면 그 파일만 파일 단위(「X 수정」)로 적고 오류는 띄우지 않는다.
 * 고치기 전 내용은 Git 이면 JGit 으로 읽고(회사 PC 에서 파일마다 git 을 띄우면 1초 가까이 걸린다), 못 하면 IntelliJ 의 리비전 읽기를 쓴다.
 */
public final class CommitSummaryAction extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance(CommitSummaryAction.class);
  // 이보다 큰 파일은 메서드까지 보지 않는다(만든 파일 · 묶은 js 등).
  private static final int MAX_BYTES = 1024 * 1024;
  private static final long JGIT_TIMEOUT_SEC = 10;
  // 라우터 · 메뉴 파일 찾기: 들여다보지 않는 폴더, 깊이 · 개수 · 크기 한도.
  private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git", ".svn", ".idea", "dist", "build", "target", "out", "bin", "coverage", ".nuxt", ".next");
  private static final Set<String> ROUTE_EXTS = Set.of("js", "ts", "jsx", "tsx", "mjs");
  private static final Set<String> ROUTE_WORDS = Set.of("router", "routes", "route", "menu", "menus");
  private static final Set<String> FRONT_EXTS = Set.of("vue", "jsx", "tsx", "js", "ts", "mjs", "cjs");
  private static final int ROUTE_MAX_DEPTH = 8;
  private static final int ROUTE_MAX_FILES = 300;
  private static final int ROUTE_MAX_BYTES = 512 * 1024;

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    CommitWorkflowUi ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
    e.getPresentation().setVisible(e.getProject() != null && ui != null);
    e.getPresentation().setEnabled(ui != null && (!ui.getIncludedChanges().isEmpty() || !ui.getIncludedUnversionedFiles().isEmpty()));
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    CommitWorkflowUi ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
    if (project == null || ui == null) return;
    List<Change> changes = List.copyOf(ui.getIncludedChanges());
    List<FilePath> unversioned = List.copyOf(ui.getIncludedUnversionedFiles());
    if (changes.isEmpty() && unversioned.isEmpty()) return;
    CommitMessageUi message = ui.getCommitMessageUi();
    Map<String, String> names = CommitSummarySettings.names();
    ProgressManager.getInstance().run(new Task.Backgroundable(project, SyncBundle.message("commitsummary.progress"), true) {
      private String summary = "";

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        List<CommitSummary.FileChange> files = new ArrayList<>();
        Set<File> frontRoots = new LinkedHashSet<>();
        for (Change change : changes) {
          indicator.checkCanceled();
          FilePath path = ChangesUtil.getFilePath(change);
          VirtualFile root = root(project, path);
          CommitSummary.Kind kind = switch (change.getType()) {
            case NEW -> CommitSummary.Kind.ADDED;
            case DELETED -> CommitSummary.Kind.DELETED;
            default -> CommitSummary.Kind.MODIFIED;
          };
          String before = before(project, change.getBeforeRevision(), root);
          String after = after(change.getAfterRevision());
          files.add(new CommitSummary.FileChange(relative(root, path), kind, before, after));
          if (root != null && front(path)) frontRoots.add(new File(root.getPath()));
        }
        for (FilePath path : unversioned) {
          indicator.checkCanceled();
          VirtualFile root = root(project, path);
          files.add(new CommitSummary.FileChange(relative(root, path), CommitSummary.Kind.ADDED, null, disk(path)));
          if (root != null && front(path)) frontRoots.add(new File(root.getPath()));
        }
        List<String> routes = new ArrayList<>();
        for (File root : frontRoots) routes.addAll(routeFiles(root, indicator));
        summary = CommitSummary.summarize(files, names, routes);
      }

      @Override
      public void onSuccess() {
        String old = message.getText();
        // 단추를 다시 눌러도 같은 글을 두 번 넣지 않는다.
        if (summary.isEmpty() || old.contains(summary)) return;
        message.setText(old.isBlank() ? summary : old.stripTrailing() + "\n" + summary);
        message.focus();
      }
    });
  }

  private static VirtualFile root(Project project, FilePath path) {
    return ReadAction.compute(() -> project.isDisposed() ? null : ProjectLevelVcsManager.getInstance(project).getVcsRootFor(path));
  }

  private static boolean front(FilePath path) {
    return FRONT_EXTS.contains(extension(path.getName()));
  }

  private static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** 저장소 기준 '/' 경로. 저장소를 모르면 전체 경로. */
  private static String relative(VirtualFile root, FilePath path) {
    String relative = root == null ? null : FileUtil.getRelativePath(root.getPath(), path.getPath(), '/');
    return relative != null ? relative : path.getPath();
  }

  /** 고치기 전 내용. 바이너리 · 너무 큰 파일 · 못 읽으면 null. */
  private static String before(Project project, ContentRevision revision, VirtualFile root) {
    if (revision == null || revision instanceof BinaryContentRevision || revision.getFile().getFileType().isBinary()) return null;
    FilePath file = revision.getFile();
    VcsRevisionNumber number = revision.getRevisionNumber();
    String relative = root == null ? null : FileUtil.getRelativePath(root.getPath(), file.getPath(), '/');
    if (relative != null && number != VcsRevisionNumber.NULL && !relative.startsWith("..")) {
      try {
        byte[] bytes = JGitRead.run(new File(root.getPath()), JGIT_TIMEOUT_SEC, null, "show", number.asString() + ":" + relative);
        if (bytes != null) return bytes.length > MAX_BYTES ? null : new String(bytes, charset(project, file));
      }
      catch (IOException e) {
        LOG.info("커밋 문구: 고치기 전 내용을 JGit 으로 못 읽음 " + relative + ": " + e.getMessage());
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return content(revision);
  }

  /** 고친 뒤 내용(작업본). 열린 편집기의 저장 안 한 글도 IntelliJ 가 커밋하는 글과 같게 읽는다. */
  private static String after(ContentRevision revision) {
    if (revision == null || revision instanceof BinaryContentRevision || revision.getFile().getFileType().isBinary()) return null;
    VirtualFile file = revision.getFile().getVirtualFile();
    if (file != null && file.getLength() > MAX_BYTES) return null;
    if (revision instanceof CurrentContentRevision) {
      try {
        return ReadAction.compute(revision::getContent);
      }
      catch (VcsException e) {
        LOG.info("커밋 문구: 작업본 내용을 못 읽음 " + revision.getFile().getPath() + ": " + e.getMessage());
        return null;
      }
    }
    return content(revision);
  }

  private static String content(ContentRevision revision) {
    try {
      String text = revision.getContent();
      return text == null || text.length() > MAX_BYTES ? null : text;
    }
    catch (VcsException e) {
      LOG.info("커밋 문구: 리비전 내용을 못 읽음 " + revision.getFile().getPath() + ": " + e.getMessage());
      return null;
    }
  }

  /** 아직 버전 관리에 없는 새 파일의 내용. */
  private static String disk(FilePath path) {
    VirtualFile file = path.getVirtualFile();
    if (file == null || file.isDirectory() || file.getFileType().isBinary() || file.getLength() > MAX_BYTES) return null;
    try {
      return ReadAction.compute(() -> VfsUtilCore.loadText(file));
    }
    catch (IOException e) {
      LOG.info("커밋 문구: 새 파일 내용을 못 읽음 " + path.getPath() + ": " + e.getMessage());
      return null;
    }
  }

  private static Charset charset(Project project, FilePath file) {
    Charset charset = file.getCharset(project);
    return charset != null ? charset : StandardCharsets.UTF_8;
  }

  /** 저장소 안의 라우터 · 메뉴 파일(폴더 이름이나 파일 이름에 router · routes · menu 가 든 js · ts) 내용. */
  private static List<String> routeFiles(File root, ProgressIndicator indicator) {
    List<String> texts = new ArrayList<>();
    Path start = root.toPath();
    try {
      Files.walkFileTree(start, Set.of(), ROUTE_MAX_DEPTH, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          indicator.checkCanceled();
          Path name = dir.getFileName();
          return !dir.equals(start) && name != null && SKIP_DIRS.contains(name.toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (!attrs.isRegularFile() || attrs.size() > ROUTE_MAX_BYTES || !route(start.relativize(file))) return FileVisitResult.CONTINUE;
          try {
            texts.add(Files.readString(file, StandardCharsets.UTF_8));
          }
          catch (IOException e) {
            LOG.info("커밋 문구: 라우터 파일을 못 읽음 " + file + ": " + e.getMessage());
          }
          return texts.size() >= ROUTE_MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
          return FileVisitResult.CONTINUE;
        }
      });
    }
    catch (IOException e) {
      LOG.info("커밋 문구: 라우터 파일 찾기 실패 " + root + ": " + e.getMessage());
    }
    return texts;
  }

  private static boolean route(Path relative) {
    String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
    if (!ROUTE_EXTS.contains(extension(name))) return false;
    String stem = name.substring(0, name.lastIndexOf('.'));
    for (String word : ROUTE_WORDS) {
      if (stem.contains(word)) return true;
    }
    for (int i = 0; i < relative.getNameCount() - 1; i++) {
      if (ROUTE_WORDS.contains(relative.getName(i).toString().toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }
}
