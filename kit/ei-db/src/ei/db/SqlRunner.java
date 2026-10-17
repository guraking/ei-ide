package ei.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import ei.db.console.GuardDialog;
import ei.db.console.ResultView;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 실행의 공통 입구(콘솔 · MyBatis 실행 · 로그 변환 · 표 데이터 보기). 화면 스레드에서 부른다.
 * 순서: 읽기 전용 접속의 변경 거절 → 위험 문장(WHERE 없는 UPDATE · DELETE, DROP · TRUNCATE)과 운영 접속의 변경은 확인창
 * → 백그라운드 실행 → 결과 창에 표시.
 */
public final class SqlRunner {
  private SqlRunner() {}

  public static void run(Project project, DbConfig c, String sql, List<Object> binds) {
    boolean write = SqlGuard.isWrite(sql);
    if (write && c.readOnly) {
      Notify.error(project, c.name + " 은 읽기 전용 접속이라 변경 문장을 실행하지 않습니다.");
      return;
    }
    String danger = SqlGuard.danger(sql);
    if ((danger != null || (c.production && write)) && !GuardDialog.confirm(project, c, sql, danger, SqlGuard.table(sql))) {
      return;
    }
    new Task.Backgroundable(project, c.name + " 에서 실행 중", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          QueryResult result = DbSessions.get(project).run(c, sql, binds);
          ApplicationManager.getApplication().invokeLater(() -> ResultView.show(project, c, result));
        } catch (Exception e) {
          ApplicationManager.getApplication().invokeLater(() -> ResultView.showError(project, c, sql, e));
        }
      }
    }.queue();
  }
}
