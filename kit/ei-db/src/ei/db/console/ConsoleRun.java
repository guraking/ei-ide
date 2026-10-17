package ei.db.console;

import com.intellij.openapi.actionSystem.ActionPromoter;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import ei.db.DbConfig;
import ei.db.Notify;
import ei.db.SqlGuard;
import ei.db.SqlRunner;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 콘솔 실행. 여러 문장은 앞 문장의 결과(ResultView.show/showError)가 온 뒤에 다음 문장을 넘긴다.
 * SqlRunner 는 문장마다 따로 백그라운드 작업을 띄우므로 한꺼번에 넘기면 순서가 섞인다.
 * 실패 · 확인창 취소 · 읽기 전용 거절이면 남은 문장은 버린다.
 * 이름 자리(:name · #{name} · ${name})가 있으면 실행 전에 모든 문장의 값을 한 번에 묻고 ? 와 값으로 바꿔 넘긴다.
 */
public final class ConsoleRun {
  private ConsoleRun() {}

  /** waiting 은 지금 결과를 기다리는 문장(normalize 한 글). 다른 곳(실행 계획 · MyBatis 등)의 결과로는 움직이지 않게 비교한다. */
  private static final class Queue {
    final DbConfig c;
    final Deque<SqlParams.Bound> rest;
    String waiting;

    Queue(DbConfig c, Deque<SqlParams.Bound> rest) { this.c = c; this.rest = rest; }
  }

  private static final Key<Queue> QUEUE = Key.create("ei.db.console.queue");

  /** 선택 영역이 있으면 그 안의 문장들, 없으면 커서가 있는 문장. */
  static void runCurrent(Project project, Editor editor, VirtualFile file) {
    String selected = editor.getSelectionModel().getSelectedText();
    if (selected != null && !selected.isBlank()) {
      start(project, file, texts(SqlSplitter.split(selected)));
      return;
    }
    SqlSplitter.Stmt stmt = SqlSplitter.at(editor.getDocument().getText(), editor.getCaretModel().getOffset());
    start(project, file, stmt == null ? List.of() : List.of(stmt.text()));
  }

  static void runAll(Project project, Editor editor, VirtualFile file) {
    start(project, file, texts(SqlSplitter.split(editor.getDocument().getText())));
  }

  /** 선택 영역(없으면 콘솔 전체)의 글을 f 로 바꾼다. 바뀐 것이 없으면 실행 취소 기록도 남기지 않는다. */
  static void rewrite(Project project, Editor editor, String name, UnaryOperator<String> f) {
    Document doc = editor.getDocument();
    SelectionModel sel = editor.getSelectionModel();
    int start = sel.hasSelection() ? sel.getSelectionStart() : 0;
    int end = sel.hasSelection() ? sel.getSelectionEnd() : doc.getTextLength();
    String before = doc.getText(new TextRange(start, end));
    String after = f.apply(before);
    if (after.equals(before)) return;
    WriteCommandAction.runWriteCommandAction(project, name, null, () -> doc.replaceString(start, end, after));
  }

  static void format(Project project, Editor editor) {
    rewrite(project, editor, "SQL 정렬", SqlFormatter::format);
  }

  static void toCase(Project project, Editor editor, boolean upper) {
    rewrite(project, editor, upper ? "대문자로" : "소문자로", sql -> SqlFormatter.changeCase(sql, upper));
  }

  private static List<String> texts(List<SqlSplitter.Stmt> stmts) {
    List<String> out = new ArrayList<>();
    for (SqlSplitter.Stmt s : stmts) out.add(s.text());
    return out;
  }

  private static void start(Project project, VirtualFile file, List<String> sqls) {
    if (sqls.isEmpty()) {
      Notify.info(project, "실행할 문장이 없습니다.");
      return;
    }
    DbConfig c = ConsoleOpener.config(file);
    if (c == null) {
      Notify.error(project, "이 콘솔의 접속이 지워졌습니다. 도구줄에서 다른 접속을 고르세요.");
      return;
    }
    List<SqlParams.Bound> bound = BindDialog.ask(project, sqls);
    if (bound == null) return;
    project.putUserData(QUEUE, new Queue(c, new ArrayDeque<>(bound)));
    next(project);
  }

  private static void next(Project project) {
    Queue q = project.getUserData(QUEUE);
    if (q == null) return;
    SqlParams.Bound stmt = q.rest.poll();
    q.waiting = normalize(stmt.sql());
    // 마지막 문장이거나 SqlRunner 가 결과 없이 거절할 문장이면 기다릴 것이 없다.
    if (q.rest.isEmpty() || (q.c.readOnly && SqlGuard.isWrite(stmt.sql()))) project.putUserData(QUEUE, null);
    SqlRunner.run(project, q.c, stmt.sql(), stmt.binds());
  }

  /** ResultView 가 결과를 보인 뒤 부른다. 같은 접속에서 기다리는 그 문장의 결과일 때만 대기열을 움직인다. */
  static void finished(Project project, DbConfig c, String sql, boolean ok) {
    Queue q = project.getUserData(QUEUE);
    if (q == null || !q.c.id.equals(c.id) || sql == null || !normalize(sql).equals(q.waiting)) return;
    if (ok) next(project);
    else stop(project);
  }

  static void stop(Project project) {
    project.putUserData(QUEUE, null);
  }

  /** DbSessions.run 이 실행 전에 하는 정리와 같다: 앞뒤 공백, 끝의 ;(PL/SQL 블록의 END; 는 남김). */
  static String normalize(String sql) {
    String text = sql.strip();
    if (text.endsWith(";") && !text.toUpperCase().endsWith("END;")) text = text.substring(0, text.length() - 1).strip();
    return text;
  }

  private abstract static class ConsoleAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      boolean console = e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null
          && ConsoleOpener.isConsole(e.getData(CommonDataKeys.VIRTUAL_FILE));
      e.getPresentation().setEnabledAndVisible(console);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      Editor editor = e.getData(CommonDataKeys.EDITOR);
      VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
      if (project == null || editor == null || !ConsoleOpener.isConsole(file)) return;
      perform(project, editor, file);
    }

    abstract void perform(Project project, Editor editor, VirtualFile file);
  }

  /** 「실행」 Ctrl+Enter. */
  public static final class Statement extends ConsoleAction {
    @Override
    void perform(Project project, Editor editor, VirtualFile file) {
      runCurrent(project, editor, file);
    }
  }

  /** 「전체 실행」 Ctrl+Shift+Enter. */
  public static final class All extends ConsoleAction {
    @Override
    void perform(Project project, Editor editor, VirtualFile file) {
      runAll(project, editor, file);
    }
  }

  /** 「정렬」 Ctrl+Shift+F. */
  public static final class Format extends ConsoleAction {
    @Override
    void perform(Project project, Editor editor, VirtualFile file) {
      format(project, editor);
    }
  }

  /** 「대문자로」 Ctrl+Shift+X. */
  public static final class Upper extends ConsoleAction {
    @Override
    void perform(Project project, Editor editor, VirtualFile file) {
      toCase(project, editor, true);
    }
  }

  /** 「소문자로」 Ctrl+Shift+Y. */
  public static final class Lower extends ConsoleAction {
    @Override
    void perform(Project project, Editor editor, VirtualFile file) {
      toCase(project, editor, false);
    }
  }

  /** 「실행 계획」 Ctrl+Shift+E. */
  public static final class Explain extends ConsoleAction {
    @Override
    void perform(Project project, Editor editor, VirtualFile file) {
      ExplainPlan.run(project, editor, file);
    }
  }

  /** 같은 단축키의 편집기 동작보다 콘솔 동작을 먼저 본다. 콘솔이 아니면 콘솔 동작이 꺼져 있어 원래 동작이 그대로 돈다. */
  public static final class Promoter implements ActionPromoter {
    @Override
    public List<AnAction> promote(@NotNull List<? extends AnAction> actions, @NotNull DataContext context) {
      List<AnAction> mine = new ArrayList<>();
      for (AnAction a : actions) {
        if (a instanceof ConsoleAction) mine.add(a);
      }
      return mine;
    }
  }
}
