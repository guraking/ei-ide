package ei.db.console;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
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
 */
@Service(Service.Level.PROJECT)
public final class ConsoleStore implements Disposable {

  private static final int SAVE_DELAY_MS = 1000;

  private final Project project;
  private final Path root = PathManager.getConfigDir().resolve("ei-db").resolve("consoles");
  private final Path dir;
  private boolean migrated;
  private final Map<Document, Path> paths = new ConcurrentHashMap<>();
  private final Set<Document> dirty = ConcurrentHashMap.newKeySet();
  private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);

  public ConsoleStore(Project project) {
    this.project = project;
    this.dir = ConsoleFiles.projectDir(root, project.getLocationHash());
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
    if (paths.put(doc, ConsoleFiles.consolePath(dir, id, n)) == null) {
      doc.addDocumentListener(new DocumentListener() {
        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
          changed(doc);
        }
      }, this);
    }
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
