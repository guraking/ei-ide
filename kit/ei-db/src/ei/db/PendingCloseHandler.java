package ei.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseHandler;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 프로젝트를 닫거나 IDE 를 끌 때(둘 다 ProjectCloseHandler.canClose 를 거친다) 자동 커밋을 끈 접속에 미반영 변경이 있으면
 * 「커밋 / 롤백 / 닫기 취소」 를 묻는다. 미반영 변경이 없으면 묻지 않는다. 화면 스레드에서 불린다.
 * 롤백은 닫으면서 DbSessions.dispose 가 한다. 커밋은 진행 창에서 차례로 하고, 하나라도 실패하면 알리고 닫지 않는다.
 * 진행 창을 취소하면(망이 막혀 커밋이 돌아오지 않을 때) 기다림만 그만두고 접속은 끊지 않은 채 「커밋 안 됨」 을 알리고 닫지 않는다.
 * 창을 닫거나(Esc) 「닫기 취소」 면 프로젝트를 그대로 둔다.
 */
public final class PendingCloseHandler implements ProjectCloseHandler {
  private static final int COMMIT = 0;
  private static final int ROLLBACK = 1;
  private static final int KEEP_OPEN = 2;
  private static final String LINE = "\n";
  /** 커밋을 기다리며 진행 창의 취소를 보는 간격. */
  private static final long COMMIT_POLL_MS = 200;

  @Override
  public boolean canClose(@NotNull Project project) {
    // 이번 실행에서 DB 를 쓰지 않은 프로젝트는 서비스를 만들지 않는다.
    DbSessions sessions = project.getServiceIfCreated(DbSessions.class);
    if (sessions == null) return true;
    Map<String, Integer> pending = sessions.pendingAll();
    if (pending.isEmpty()) return true;
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, Integer> e : pending.entrySet()) {
      lines.add(EiDbBundle.message("sessions.close.pending.item", name(e.getKey()), e.getValue()));
    }
    String[] options = {EiDbBundle.message("console.commit"), EiDbBundle.message("console.rollback"), EiDbBundle.message("sessions.close.keep")};
    int choice = Messages.showDialog(project, EiDbBundle.message("sessions.close.pending", String.join(LINE, lines)),
        EiDbBundle.message("sessions.close.pending.title"), options, KEEP_OPEN, Messages.getWarningIcon());
    if (choice == ROLLBACK) return true;
    if (choice != COMMIT) return false;
    String failure;
    try {
      failure = ProgressManager.getInstance().runProcessWithProgressSynchronously(
          (ThrowableComputable<String, RuntimeException>) () -> commitAll(sessions, new ArrayList<>(pending.keySet())), EiDbBundle.message("sessions.close.committing"), true, project);
    } catch (ProcessCanceledException e) {
      failure = EiDbBundle.message("sessions.close.commit.cancelled");
    }
    if (failure == null) return true;
    Messages.showErrorDialog(project, failure, EiDbBundle.message("sessions.close.pending.title"));
    return false;
  }

  /**
   * 차례로 커밋한다. 실패하거나 진행 창을 취소하면 거기서 멈추고 알릴 글, 모두 되면 null.
   * 커밋은 풀 스레드에서 돌리고 여기서는 기다리기만 해서, 취소하면 돌아오지 않는 커밋을 두고 빠져나온다(접속은 그대로 둔다).
   */
  private static String commitAll(DbSessions sessions, List<String> ids) {
    for (String id : ids) {
      Future<?> commit = ApplicationManager.getApplication().executeOnPooledThread(() -> {
        sessions.commit(id);
        return null;
      });
      try {
        if (!waitFor(commit)) return EiDbBundle.message("sessions.close.commit.cancelled");
      } catch (ExecutionException e) {
        return EiDbBundle.message("sessions.close.commit.failed", name(id), e.getCause().getMessage());
      }
    }
    return null;
  }

  /** 커밋이 끝나면 참, 진행 창을 취소하면(또는 기다리던 스레드가 끊기면) 거짓. 취소해도 커밋 자체는 멈추지 않는다. */
  private static boolean waitFor(Future<?> f) throws ExecutionException {
    ProgressIndicator indicator = ProgressManager.getGlobalProgressIndicator();
    while (indicator == null || !indicator.isCanceled()) {
      try {
        f.get(COMMIT_POLL_MS, TimeUnit.MILLISECONDS);
        return true;
      } catch (TimeoutException e) {
        // 아직 커밋 중이다. 취소를 다시 본다.
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  private static String name(String id) {
    DbConfig c = DbSettings.getInstance().find(id);
    return c == null ? id : c.name;
  }
}
