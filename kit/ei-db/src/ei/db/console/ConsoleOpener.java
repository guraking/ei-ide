package ei.db.console;

import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.Notify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 콘솔 = 메모리 파일(LightVirtualFile, 이름 끝 .sql 이라 SQL 색칠이 붙는다). 한 접속에 콘솔을 여러 개 열 수 있고,
 * 같은 접속의 콘솔은 연결 하나(미반영 변경 · 커밋 · 롤백)를 같이 쓴다.
 * 파일 사용자 데이터에 접속 id 를 둔다. 콘솔에서 바꾼 자동 커밋은 접속마다 하나(DbSessions.autoCommitOverride)라 같은 접속의 콘솔이 같이 쓴다.
 * 탭을 닫아도 파일(내용)은 프로젝트가 닫힐 때까지 남는다.
 * 콘솔 글은 ConsoleStore 가 접속 id · 콘솔 번호별로 저장하고, 다음 세션에 같은 번호의 콘솔을 처음 만들 때 되살린다.
 */
public final class ConsoleOpener {
  private ConsoleOpener() {}

  static final Key<String> CONFIG_ID = Key.create("ei.db.console.configId");
  /** 접속 id → 그 접속에서 마지막으로 연 콘솔. SQL 을 보내는 곳. */
  private static final Key<Map<String, LightVirtualFile>> CONSOLES = Key.create("ei.db.console.files");
  /** 접속 id → 지금까지 연 콘솔 수. 두 번째부터 탭 이름에 번호를 붙인다. */
  private static final Key<Map<String, Integer>> COUNTS = Key.create("ei.db.console.counts");

  /** 그 접속에서 마지막으로 연 콘솔 탭을 열고(없으면 만든다) initialSql 이 null 이 아니면 끝에 덧붙인다. 연 콘솔을 돌려준다. */
  public static VirtualFile open(Project project, DbConfig c, String initialSql) {
    LightVirtualFile file = consoles(project).get(c.id);
    if (file == null) file = create(project, c);
    show(project, c, file, initialSql);
    return file;
  }

  /** 「새 콘솔」: 다음 번호의 콘솔을 새 탭으로 연다(그 번호로 저장된 글이 있으면 되살리고, 없으면 빈 콘솔). 이후 open 은 이 콘솔로 보낸다. */
  public static void openNew(Project project, DbConfig c) {
    show(project, c, create(project, c), null);
  }

  /** 지금 편집기 탭으로 열린 콘솔들(탭 순서). */
  public static List<VirtualFile> openConsoles(Project project) {
    List<VirtualFile> out = new ArrayList<>();
    for (VirtualFile f : FileEditorManager.getInstance(project).getOpenFiles()) {
      if (isConsole(f) && f instanceof LightVirtualFile) out.add(f);
    }
    return out;
  }

  /** 그 콘솔이 쓰는 접속(자동 커밋 덮어쓰기를 얹은 사본). 접속이 지워졌으면 null. */
  public static DbConfig connection(Project project, VirtualFile file) {
    return config(project, file);
  }

  /** 고른 콘솔 탭을 열고 sql 을 끝에 덧붙인다. 접속이 지워졌으면 알리고 false. */
  public static boolean append(Project project, VirtualFile file, String sql) {
    DbConfig c = config(file);
    if (c == null || !(file instanceof LightVirtualFile light)) {
      Notify.error(project, "이 콘솔의 접속이 지워졌습니다: " + file.getName());
      return false;
    }
    show(project, c, light, sql);
    return true;
  }

  private static LightVirtualFile create(Project project, DbConfig c) {
    int n = counts(project).merge(c.id, 1, Integer::sum);
    ConsoleStore store = ConsoleStore.get(project);
    String saved = store.load(c.id, n);
    LightVirtualFile file = new LightVirtualFile(n == 1 ? fileName(c) : "콘솔 · " + c.name + " (" + n + ").sql", saved == null ? "" : saved);
    file.putUserData(CONFIG_ID, c.id);
    consoles(project).put(c.id, file);
    if (saved != null) store.track(file, c.id, n);
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

  /** 콘솔이 쓰는 접속 설정(자동 커밋 덮어쓰기 없이). 접속이 지워졌으면 null. 실행 · 커밋 표시에는 config(project, file). */
  static DbConfig config(VirtualFile file) {
    return DbSettings.getInstance().find(file.getUserData(CONFIG_ID));
  }

  /** 콘솔이 쓰는 접속(설정 사본에 그 접속의 자동 커밋 덮어쓰기를 얹은 것). 접속이 지워졌으면 null. */
  static DbConfig config(Project project, VirtualFile file) {
    DbConfig base = config(file);
    if (base == null) return null;
    DbConfig c = base.copy();
    Boolean auto = DbSessions.get(project).autoCommitOverride(c.id);
    if (auto != null) c.autoCommit = auto;
    return c;
  }

  /** 콘솔을 다른 접속에 묶는다. 그 접속에 이미 콘솔이 있으면 그 콘솔은 목록에서만 빠진다(탭은 남는다). */
  static void rebind(Project project, VirtualFile file, DbConfig target) {
    Map<String, LightVirtualFile> consoles = consoles(project);
    consoles.remove(file.getUserData(CONFIG_ID));
    if (file instanceof LightVirtualFile light) consoles.put(target.id, light);
    file.putUserData(CONFIG_ID, target.id);
    counts(project).put(target.id, ConsoleStore.get(project).rebind(file, target.id, counts(project).getOrDefault(target.id, 0)));
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

  private static Map<String, Integer> counts(Project project) {
    Map<String, Integer> map = project.getUserData(COUNTS);
    if (map == null) {
      map = new HashMap<>();
      project.putUserData(COUNTS, map);
    }
    return map;
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
