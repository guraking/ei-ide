package ei.db.console;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.mybatis.SqlValues;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 커서 문장(선택 영역이 있으면 그 첫 문장)의 실행 계획을 결과 창에 보인다. 콘솔 도구줄 단추 · 우클릭 · Ctrl+Shift+E 가 부른다. 화면 스레드.
 * 문장은 실행하지 않는다: Oracle 은 EXPLAIN PLAN ... FOR 후 DBMS_XPLAN.DISPLAY, PostgreSQL · MySQL 은 EXPLAIN(ANALYZE 없이).
 * 이름 자리가 있으면 콘솔 실행처럼 값을 묻는다.
 */
public final class ExplainPlan {
  private ExplainPlan() {}

  private static final String STATEMENT_ID_PREFIX = "EI_";
  private static final String ORACLE_DISPLAY = "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, ?, 'TYPICAL'))";

  public static void run(Project project, Editor editor, VirtualFile file) {
    DbConfig c = ConsoleOpener.config(project, file);
    if (c == null) {
      Notify.error(project, "이 콘솔의 접속이 지워졌습니다. 도구줄에서 다른 접속을 고르세요.");
      return;
    }
    String sql = statement(editor);
    if (sql == null) {
      Notify.info(project, "실행 계획을 볼 문장이 없습니다.");
      return;
    }
    List<SqlParams.Bound> bound = BindDialog.ask(project, List.of(sql));
    if (bound == null) return;
    SqlParams.Bound stmt = bound.get(0);
    new Task.Backgroundable(project, c.name + " 실행 계획 읽는 중", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          QueryResult result = explain(DbSessions.get(project), c, stmt);
          ApplicationManager.getApplication().invokeLater(() -> ResultView.show(project, c, result));
        } catch (Exception e) {
          ApplicationManager.getApplication().invokeLater(() -> ResultView.showError(project, c, stmt.sql(), e));
        }
      }
    }.queue();
  }

  /** 선택 영역의 첫 문장, 없으면 커서 문장. 끝의 ; 는 뗀다. 없으면 null. */
  private static String statement(Editor editor) {
    String selected = editor.getSelectionModel().getSelectedText();
    SqlSplitter.Stmt s;
    if (selected != null && !selected.isBlank()) {
      List<SqlSplitter.Stmt> all = SqlSplitter.split(selected);
      s = all.isEmpty() ? null : all.get(0);
    } else {
      s = SqlSplitter.at(editor.getDocument().getText(), editor.getCaretModel().getOffset());
    }
    if (s == null) return null;
    String text = s.text().strip();
    return text.endsWith(";") ? text.substring(0, text.length() - 1).strip() : text;
  }

  private static QueryResult explain(DbSessions sessions, DbConfig c, SqlParams.Bound stmt) throws SQLException {
    if (c.kind != DbKind.ORACLE) return sessions.run(c, "EXPLAIN " + stmt.sql(), stmt.binds(), DbSessions.ALL_ROWS);
    // STATEMENT_ID 는 바인드를 받지 않아(문자열 리터럴만) 숫자로만 만든 값을 이어 붙인다.
    String id = STATEMENT_ID_PREFIX + System.nanoTime();
    // EXPLAIN PLAN 은 바인드 값을 받지 않으므로(ORA-01006) 값은 리터럴로 넣는다. 계획만 보고 실행하지 않는다.
    // 리터럴: 문자열은 ' 겹치기, 숫자 그대로, NULL, 날짜는 ANSI DATE · TIMESTAMP 글자(NLS 설정과 무관).
    String sql = stmt.binds() == null ? stmt.sql() : SqlValues.inline(stmt.sql(), stmt.binds());
    try (Statement st = sessions.connection(c).createStatement()) {
      st.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + id + "' FOR " + sql);
    }
    return sessions.run(c, ORACLE_DISPLAY, List.of(id), DbSessions.ALL_ROWS);
  }
}
