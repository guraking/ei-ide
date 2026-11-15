package ei.db.console;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 콘솔 글을 IDE 설정 폴더의 ei-db/consoles/<프로젝트 구분>/ 에 접속 id(와 콘솔 번호)별로 저장해 IDE 를 다시 열어도 남게 한다.
 * 예전 자리(ei-db/consoles/ 바로 아래) 파일은 이 프로젝트에서 첫 콘솔을 열 때 한 번 이 프로젝트 폴더로 옮긴다(있는 파일은 덮지 않는다).
 * 글이 바뀌면 SAVE_DELAY_MS 동안 모았다가 백그라운드에서 쓰고, 프로젝트가 닫힐 때(IDE 종료 포함) 남은 것을 모두 쓴다.
 * 저장 파일을 읽지 못한 콘솔은 그 파일을 덮지 않도록 저장하지 않는다.
 * 탭을 닫으면 바로 쓰고 지켜보기를 풀어 문서를 놓는다. 같은 콘솔 탭을 다시 열면 다시 지켜보고 한 번 쓴다(닫힌 동안 덧붙인 글).
 */
@Service(Service.Level.PROJECT)
public final class ConsoleStore implements Disposable {

  private static final int SAVE_DELAY_MS = 1000;

  private final Project project;
  private final Path root = PathManager.getConfigDir().resolve("ei-db").resolve("consoles");
  private final Path dir;
  private boolean migrated;
  /** 지켜본 적 있는 콘솔 파일의 저장 자리. 닫았다 다시 연 탭을 다시 지켜보는 데 쓴다. */
  private static final Key<Path> SAVED_AT = Key.create("ei.db.console.savedAt");
  private final Map<Document, Path> paths = new ConcurrentHashMap<>();
  /** 문서마다 변경 리스너를 붙인 Disposable. 닫을 때 풀어 문서를 놓는다. */
  private final Map<Document, Disposable> listeners = new ConcurrentHashMap<>();
  private final Set<Document> dirty = ConcurrentHashMap.newKeySet();
  private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);

  public ConsoleStore(Project project) {
    this.project = project;
    this.dir = ConsoleFiles.projectDir(root, project.getLocationHash());
    project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
      @Override
      public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        reopened(file);
      }

      @Override
      public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (!source.isFileOpen(file)) release(file);
      }
    });
  }

  static ConsoleStore get(Project project) {
    return project.getService(ConsoleStore.class);
  }

  /** 접속 id 의 n 번째 콘솔에 저장된 글. 없으면 "". 읽지 못하면 알리고 null. 작은 파일 하나라 부른 스레드에서 읽는다. */
  String load(String id, int n) {
    migrateOnce();
    Path p = ConsoleFiles.consolePath(dir, id, n);
    try {
      return ConsoleFiles.read(p);
    } catch (IOException e) {
      Notify.error(project, EiDbBundle.message("console.store.read.failed", p, e.getMessage()));
      return null;
    }
  }

  /** 예전 자리 파일 복사. 실패하면 알리고 다시 시도하지 않는다. */
  private void migrateOnce() {
    if (migrated) return;
    migrated = true;
    try {
      ConsoleFiles.migrate(root, dir);
    } catch (IOException e) {
      Notify.error(project, EiDbBundle.message("console.store.migrate.failed", root, e.getMessage()));
    }
  }

  /** 이 콘솔 글을 접속 id 의 n 번째 자리에 저장하기 시작한다. */
  void track(VirtualFile file, String id, int n) {
    Document doc = FileDocumentManager.getInstance().getDocument(file);
    if (doc == null) return;
    Path p = ConsoleFiles.consolePath(dir, id, n);
    file.putUserData(SAVED_AT, p);
    watch(doc, p);
  }

  private void watch(Document doc, Path p) {
    if (paths.put(doc, p) != null) return;
    Disposable d = Disposer.newDisposable(this, "ei.db.console.store");
    listeners.put(doc, d);
    doc.addDocumentListener(new DocumentListener() {
      @Override
      public void documentChanged(@NotNull DocumentEvent event) {
        changed(doc);
      }
    }, d);
  }

  /** 지켜보던 콘솔 탭을 다시 열었을 때: 다시 지켜보고, 닫힌 동안 덧붙인 글이 있을 수 있어 한 번 쓴다. */
  private void reopened(VirtualFile file) {
    Path p = file.getUserData(SAVED_AT);
    if (p == null) return;
    Document doc = FileDocumentManager.getInstance().getDocument(file);
    if (doc == null || paths.containsKey(doc)) return;
    watch(doc, p);
    changed(doc);
  }

  /** 콘솔 탭이 모두 닫혔을 때: 남은 글을 바로 쓰고 리스너 · 자리를 풀어 문서를 놓는다. */
  private void release(VirtualFile file) {
    Document doc = FileDocumentManager.getInstance().getCachedDocument(file);
    if (doc == null || !paths.containsKey(doc)) return;
    dirty.remove(doc);
    save(doc);
    paths.remove(doc);
    Disposable d = listeners.remove(doc);
    if (d != null) Disposer.dispose(d);
  }

  /**
   * 다른 접속으로 옮긴 콘솔을 그 접속의 빈 자리(이번 세션에서 쓴 used 다음, 저장 파일이 없는 첫 번호)로 옮겨 저장한다.
   * 옛 자리 파일은 그대로 둔다. 고른 번호를 돌려준다.
   */
  int rebind(VirtualFile file, String id, int used) {
    int n = ConsoleFiles.freeSlot(dir, id, used);
    track(file, id, n);
    Document doc = FileDocumentManager.getInstance().getDocument(file);
    if (doc != null) changed(doc);
    return n;
  }

  private void changed(Document doc) {
    dirty.add(doc);
    alarm.cancelAllRequests();
    alarm.addRequest(this::saveDirty, SAVE_DELAY_MS);
  }

  private void saveDirty() {
    for (Document doc : new ArrayList<>(dirty)) {
      dirty.remove(doc);
      save(doc);
    }
  }

  private void save(Document doc) {
    Path p = paths.get(doc);
    if (p == null) return;
    try {
      // 불변 사본이라 읽기 동작 없이 어느 스레드에서나 읽을 수 있다.
      ConsoleFiles.write(p, doc.getImmutableCharSequence().toString());
    } catch (IOException e) {
      Notify.error(project.isDisposed() ? null : project, EiDbBundle.message("console.store.save.failed", p, e.getMessage()));
    }
  }

  /** 프로젝트가 닫힐 때(IDE 종료 포함). 모아 둔 것을 기다리지 않고 모든 콘솔을 바로 쓴다. */
  @Override
  public void dispose() {
    for (Document doc : paths.keySet()) save(doc);
  }
}
