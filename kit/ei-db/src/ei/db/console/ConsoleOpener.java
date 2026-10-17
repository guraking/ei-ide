package ei.db.console;

import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import ei.db.DbConfig;
import ei.db.DbSettings;
import ei.db.Notify;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * SQL 콘솔 = 메모리 파일(LightVirtualFile, 이름 끝 .sql 이라 SQL 색칠이 붙는다). 한 접속에 콘솔을 여러 개 열 수 있고,
 * 같은 접속의 콘솔은 연결 하나(미반영 변경 · 커밋 · 롤백)를 같이 쓴다.
 * 파일 사용자 데이터에 접속 id 와 이 세션에서만 쓰는 자동 커밋 값을 둔다. 탭을 닫아도 파일(내용)은 프로젝트가 닫힐 때까지 남는다.
 */
public final class ConsoleOpener {
  private ConsoleOpener() {}

  static final Key<String> CONFIG_ID = Key.create("ei.db.console.configId");
  /** 콘솔에서 바꾼 자동 커밋. null 이면 접속 설정 값. 설정 파일에는 저장하지 않는다. */
  static final Key<Boolean> AUTO_COMMIT = Key.create("ei.db.console.autoCommit");
  /** 접속 id → 그 접속에서 마지막으로 연 콘솔. SQL 을 보내는 곳. */
  private static final Key<Map<String, LightVirtualFile>> CONSOLES = Key.create("ei.db.console.files");
  /** 접속 id → 지금까지 연 콘솔 수. 두 번째부터 탭 이름에 번호를 붙인다. */
  private static final Key<Map<String, Integer>> COUNTS = Key.create("ei.db.console.counts");

  /** 그 접속에서 마지막으로 연 콘솔 탭을 열고(없으면 만든다) initialSql 이 null 이 아니면 끝에 덧붙인다. */
  public static void open(Project project, DbConfig c, String initialSql) {
    LightVirtualFile file = consoles(project).get(c.id);
    show(project, c, file == null ? create(project, c) : file, initialSql);
  }

  /** 「새 콘솔」: 늘 빈 콘솔을 새 탭으로 연다. 이후 open 은 이 콘솔로 보낸다. */
  public static void openNew(Project project, DbConfig c) {
    show(project, c, create(project, c), null);
  }

  private static LightVirtualFile create(Project project, DbConfig c) {
    Map<String, Integer> counts = project.getUserData(COUNTS);
    if (counts == null) {
      counts = new HashMap<>();
      project.putUserData(COUNTS, counts);
    }
    int n = counts.merge(c.id, 1, Integer::sum);
    LightVirtualFile file = new LightVirtualFile(n == 1 ? fileName(c) : "콘솔 · " + c.name + " (" + n + ").sql", "");
    file.putUserData(CONFIG_ID, c.id);
    consoles(project).put(c.id, file);
    return file;
  }

  private static void show(Project project, DbConfig c, LightVirtualFile file, String initialSql) {
    Document doc = FileDocumentManager.getInstance().getDocument(file);
    if (doc == null) {
      Notify.error(project, "콘솔 문서를 만들지 못했습니다: " + c.name);
      return;
    }
    int caret = doc.getTextLength();
    if (initialSql != null && !initialSql.isBlank()) {
      String before = doc.getText();
      // 빈 줄로 앞 문장과 떼어 문장 나누기가 섞이지 않게 한다.
      String gap = before.isBlank() ? "" : before.endsWith("\n\n") ? "" : before.endsWith("\n") ? "\n" : "\n\n";
      String text = gap + initialSql.strip() + "\n";
      caret = doc.getTextLength() + gap.length();
      WriteCommandAction.runWriteCommandAction(project, "콘솔에 SQL 넣기", null, () -> doc.insertString(doc.getTextLength(), text));
    }
    PsiNavigationSupport.getInstance().createNavigatable(project, file, caret).navigate(true);
  }

  static boolean isConsole(VirtualFile file) {
    return file != null && file.getUserData(CONFIG_ID) != null;
  }

  /** 콘솔이 쓰는 접속(설정 사본에 이 콘솔의 자동 커밋을 얹은 것). 접속이 지워졌으면 null. */
  static DbConfig config(VirtualFile file) {
    DbConfig base = DbSettings.getInstance().find(file.getUserData(CONFIG_ID));
    if (base == null) return null;
    DbConfig c = base.copy();
    Boolean auto = file.getUserData(AUTO_COMMIT);
    if (auto != null) c.autoCommit = auto;
    return c;
  }

  /** 콘솔을 다른 접속에 묶는다. 그 접속에 이미 콘솔이 있으면 그 콘솔은 목록에서만 빠진다(탭은 남는다). */
  static void rebind(Project project, VirtualFile file, DbConfig target) {
    Map<String, LightVirtualFile> consoles = consoles(project);
    consoles.remove(file.getUserData(CONFIG_ID));
    if (file instanceof LightVirtualFile light) consoles.put(target.id, light);
    file.putUserData(CONFIG_ID, target.id);
    file.putUserData(AUTO_COMMIT, null);
    try {
      file.rename(ConsoleOpener.class, fileName(target));
    } catch (IOException e) {
      // 메모리 파일은 이름 바꾸기에 실패하지 않는다. 실패해도 탭 이름만 옛것으로 남는다.
      Notify.error(project, "콘솔 이름을 바꾸지 못했습니다: " + e.getMessage());
    }
  }

  private static String fileName(DbConfig c) {
    return "콘솔 · " + c.name + ".sql";
  }

  private static Map<String, LightVirtualFile> consoles(Project project) {
    Map<String, LightVirtualFile> map = project.getUserData(CONSOLES);
    if (map == null) {
      map = new HashMap<>();
      project.putUserData(CONSOLES, map);
    }
    return map;
  }
}
