package ei.db.bench;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DoNotAskOption;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;

import java.util.List;

/**
 * 실행 전 쿼리 점검(SqlLint)을 실행 확인창에 붙인다. 걸리는 것이 없으면 묻지 않는다.
 * 「다시 묻지 않기」 를 고르면 KEY 를 끄고, 쿼리 작업대의 「실행 전 쿼리 점검」 으로 다시 켠다.
 */
public final class LintGate {
  private LintGate() {}

  /** 켜짐(기본) · 꺼짐 저장 열쇠. */
  static final String KEY = "ei.db.bench.lintBeforeRun";

  static boolean enabled() { return PropertiesComponent.getInstance().getBoolean(KEY, true); }

  static void setEnabled(boolean on) { PropertiesComponent.getInstance().setValue(KEY, on, true); }

  /** 실행해도 되면 true. 점검이 꺼졌거나 걸린 것이 없으면 묻지 않고 true. */
  public static boolean confirm(Project project, String sql) {
    if (!enabled()) return true;
    List<SqlLint.Issue> issues;
    try {
      issues = SqlLint.check(sql);
    } catch (RuntimeException e) {
      return true; // 점검은 돕는 것이라, 읽지 못하는 문장은 막지 않는다
    }
    if (issues.isEmpty()) return true;
    StringBuilder sb = new StringBuilder("실행하기 전에 확인하세요.\n");
    for (SqlLint.Issue i : issues) sb.append("\n· ").append(i.title()).append("\n   ").append(i.detail());
    return MessageDialogBuilder.okCancel("실행 전 쿼리 점검", sb.toString())
        .yesText("그래도 실행")
        .noText("취소")
        .icon(Messages.getWarningIcon())
        .doNotAsk(new DoNotAskOption.Adapter() {
          @Override
          public void rememberChoice(boolean isSelected, int exitCode) {
            if (isSelected) setEnabled(false);
          }
        })
        .ask(project);
  }
}
