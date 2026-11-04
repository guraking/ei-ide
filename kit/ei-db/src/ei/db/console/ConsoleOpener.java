package ei.db.console;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.ProjectCloseListener;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
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
 * 프로젝트를 닫을 때 열려 있던 콘솔 탭(접속 · 번호 · 전체 탭 중 자리 · 보던 탭)을 기억했다가 다시 열 때 같은 자리에 연다(Remember · Restore).
 * 되살린 콘솔은 탭만 열고 DB 에는 붙지 않는다(연결은 첫 실행 때). 지워진 접속의 콘솔은 열지 않는다.
 */
public final class ConsoleOpener {
  private ConsoleOpener() {}

  static final Key<String> CONFIG_ID = Key.create("ei.db.console.configId");
  /** 접속 id → 그 접속에서 마지막으로 연 콘솔. SQL 을 보내는 곳. */
  private static final Key<Map<String, LightVirtualFile>> CONSOLES = Key.create("ei.db.console.files");
  /** 접속 id → 지금까지 연 콘솔 수. 두 번째부터 탭 이름에 번호를 붙인다. */
  private static final Key<Map<String, Integer>> COUNTS = Key.create("ei.db.console.counts");
  /** 그 콘솔의 접속 안 번호(1부터). 저장 파일 자리와 같다. */
  private static final Key<Integer> NUMBER = Key.create("ei.db.console.number");
  /** 프로젝트 설정에 남기는 열린 콘솔 목록(「접속 id \t 번호 \t 전체 탭 중 자리」, 탭 순서)과 보던 콘솔(「접속 id \t 번호」). 자리가 없는 옛 형식은 끝에 연다. */
  private static final String OPEN_KEY = "ei.db.console.openTabs";
  private static final String SELECTED_KEY = "ei.db.console.selectedTab";
  private static final String SEPARATOR = "\t";

  /** 프로젝트를 닫을 때(설정 저장 전) 열린 콘솔 탭을 기억한다. */
  public static final class Remember implements ProjectCloseListener {
    @Override
    public void projectClosingBeforeSave(@NotNull Project project) {
      remember(project);
    }
  }

  /** 프로젝트를 열면 기억한 콘솔 탭을 다시 연다. 시작 작업은 작업 스레드라 화면 스레드로 넘긴다. */
  public static final class Restore implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
      ApplicationManager.getApplication().invokeLater(() -> restore(project), project.getDisposed());
    }
  }

  static void remember(Project project) {
    List<String> open = new ArrayList<>();
    List<VirtualFile> tabs = tabFiles(project);
    for (VirtualFile f : openConsoles(project)) {
      String key = tabKey(f);
      if (key != null) open.add(key + SEPARATOR + tabs.indexOf(f));
    }
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    props.setList(OPEN_KEY, open);
    VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
    String current = selected.length > 0 && isConsole(selected[0]) ? tabKey(selected[0]) : null;
    props.setValue(SELECTED_KEY, current);
  }

  static void restore(Project project) {
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    List<String> open = props.getList(OPEN_KEY);
    if (open == null || open.isEmpty()) return;
    String selectedKey = props.getValue(SELECTED_KEY);
    FileEditorManager editors = FileEditorManager.getInstance(project);
    VirtualFile selected = null;
    for (String key : open) {
      String[] parts = key.split(SEPARATOR);
      boolean valid = parts.length == 2 || parts.length == 3;
      DbConfig c = valid ? DbSettings.getInstance().find(parts[0]) : null;
      int n = valid ? parseNumber(parts[1]) : 0;
      if (c == null || n < 1 || hasNumber(project, c.id, n)) continue;
      LightVirtualFile file = create(project, c, n);
      int index = parts.length == 3 ? parseNumber(parts[2]) : -1;
      openAt(project, file, index);
      if ((parts[0] + SEPARATOR + parts[1]).equals(selectedKey)) selected = file;
    }
    if (selected != null) editors.openFile(selected, false);
  }

  /** 지금 편집기 창의 탭 파일들(탭 순서). 창이 없으면 열린 파일 전체. */
  private static List<VirtualFile> tabFiles(Project project) {
    EditorWindow window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
    return window != null ? window.getFileList() : List.of(FileEditorManager.getInstance(project).getOpenFiles());
  }

  /**
   * 콘솔을 지금 창의 index 번째 탭으로 연다(선택 · 포커스는 옮기지 않는다). index 가 0 보다 작거나 탭 수를 넘으면 맨 끝.
   * 기억한 목록이 탭 순서라 앞 콘솔부터 넣으면 뒤 콘솔의 자리도 맞는다.
   * shortcut: 창을 나눠 쓴 경우 자리는 지금 창 기준이다, 나눈 창별 자리가 필요해지면 창 번호도 기억한다.
   */
  private static void openAt(Project project, VirtualFile file, int index) {
    FileEditorManagerEx editors = FileEditorManagerEx.getInstanceEx(project);
    EditorWindow window = editors.getCurrentWindow();
    if (window == null) {
      editors.openFile(file, false);
      return;
    }
    int at = index < 0 || index > window.getTabCount() ? window.getTabCount() : index;
    FileEditorOpenOptions d = new FileEditorOpenOptions();
    FileEditorOpenOptions options = d.copy(false, d.reuseOpen, d.usePreviewTab, false, d.pin, at, d.isExactState,
        d.openMode, d.waitForCompositeOpen, d.isSingletonEditorInWindow, d.forceFocus, d.internalHint);
    editors.openFile(file, window, options);
  }

  private static String tabKey(VirtualFile f) {
    Integer n = f.getUserData(NUMBER);
    String id = f.getUserData(CONFIG_ID);
    return n == null || id == null ? null : id + SEPARATOR + n;
  }

  /** 손상된 번호는 0(되살리지 않음). */
  private static int parseNumber(String text) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** 이미 연 같은 접속 · 번호 콘솔이 있는지(사용자가 시작 직후 먼저 연 경우). */
  private static boolean hasNumber(Project project, String id, int n) {
    for (VirtualFile f : openConsoles(project)) {
      if (id.equals(f.getUserData(CONFIG_ID)) && Integer.valueOf(n).equals(f.getUserData(NUMBER))) return true;
    }
    return false;
  }

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
      Notify.error(project, EiDbBundle.message("console.connection.removed.named", file.getName()));
      return false;
    }
    show(project, c, light, sql);
    return true;
  }

  private static LightVirtualFile create(Project project, DbConfig c) {
    return create(project, c, counts(project).getOrDefault(c.id, 0) + 1);
  }

  /** 접속 c 의 n 번째 콘솔. 번호 세기는 지금까지 쓴 가장 큰 번호로 맞춘다(되살린 번호 뒤로 새 콘솔이 붙게). */
  private static LightVirtualFile create(Project project, DbConfig c, int n) {
    counts(project).merge(c.id, n, Math::max);
    ConsoleStore store = ConsoleStore.get(project);
    String saved = store.load(c.id, n);
    LightVirtualFile file = new LightVirtualFile(n == 1 ? fileName(c) : EiDbBundle.message("console.file.name.numbered", c.name, n), saved == null ? "" : saved);
    file.putUserData(CONFIG_ID, c.id);
    file.putUserData(NUMBER, n);
    consoles(project).put(c.id, file);
    if (saved != null) store.track(file, c.id, n);
    return file;
  }

  private static void show(Project project, DbConfig c, LightVirtualFile file, String initialSql) {
    Document doc = FileDocumentManager.getInstance().getDocument(file);
    if (doc == null) {
      Notify.error(project, EiDbBundle.message("console.document.failed", c.name));
      return;
    }
    int caret = doc.getTextLength();
    if (initialSql != null && !initialSql.isBlank()) {
      String before = doc.getText();
      // 빈 줄로 앞 문장과 떼어 문장 나누기가 섞이지 않게 한다.
      String gap = before.isBlank() ? "" : before.endsWith("\n\n") ? "" : before.endsWith("\n") ? "\n" : "\n\n";
      String text = gap + initialSql.strip() + "\n";
      caret = doc.getTextLength() + gap.length();
      WriteCommandAction.runWriteCommandAction(project, EiDbBundle.message("console.command.insert.sql"), null, () -> doc.insertString(doc.getTextLength(), text));
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
    int n = ConsoleStore.get(project).rebind(file, target.id, counts(project).getOrDefault(target.id, 0));
    counts(project).put(target.id, n);
    file.putUserData(NUMBER, n);
    try {
      file.rename(ConsoleOpener.class, fileName(target));
    } catch (IOException e) {
      // 메모리 파일은 이름 바꾸기에 실패하지 않는다. 실패해도 탭 이름만 옛것으로 남는다.
      Notify.error(project, EiDbBundle.message("console.rename.failed", e.getMessage()));
    }
  }

  private static String fileName(DbConfig c) {
    return EiDbBundle.message("console.file.name", c.name);
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
