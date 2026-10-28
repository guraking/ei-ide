package kinggura.sync;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.util.ui.NamedColorUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 동기화 창 우클릭 「Undo Last Git Action…」이 되돌릴 동작을 읽고 실행한다.
 *
 * 지금 브랜치의 reflog 맨 위 동작을 그 앞 위치로 돌린다. 커밋 · amend 는 커밋만 없애고 바뀐 파일은 색인에 남긴다(reset --soft).
 * 합치기 · 받기 · 리베이스 · reset · cherry-pick 은 앞 위치로 돌리되 커밋 안 한 내 변경은 지키고, 지킬 수 없으면 git 이 아무것도 바꾸지 않고 멈춘다(reset --keep).
 * HEAD reflog 맨 위가 지금 브랜치로 바꾼 체크아웃이면 앞 브랜치로 다시 바꾼다.
 * 되돌린 것도 reflog 에 남으므로 한 번 더 하면 되돌리기 전으로 돌아간다.
 * 합치기 · 리베이스 · cherry-pick · revert 가 진행 중이거나 브랜치가 없으면(detached HEAD) 하지 않는다.
 */
final class GitUndo {
  enum Mode { SOFT, KEEP, CHECKOUT }

  /** 되돌릴 동작. subject 는 reflog 제목(「commit: 메시지」), when 은 그 시각, target 은 돌아갈 커밋 또는 브랜치, onServer 는 없어질 커밋이 원격 추적 브랜치에 있는지. */
  record Plan(Mode mode, String subject, String when, String target, boolean onServer) {}

  // reflog 한 줄: 커밋 · 선택자(브랜치@{시각}) · 제목을 0x1f 로 나눈다(제목에 다른 문자는 다 올 수 있다).
  private static final String FIELD = "\u001f";
  private static final String REFLOG_FORMAT = "--format=%H%x1f%gd%x1f%gs";
  // git 의 상대 시각(「3 minutes ago」)은 영어라 날짜 · 시각으로 받는다.
  private static final String REFLOG_DATE = "--date=format:%Y-%m-%d %H:%M";
  private static final String CHECKOUT_PREFIX = "checkout: moving from ";
  private static final String CHECKOUT_TO = " to ";
  private static final List<String> SOFT_PREFIXES = List.of("commit: ", "commit (amend): ");
  // 앞 위치가 없는 첫 줄(브랜치 만들기 · clone · 첫 커밋).
  private static final List<String> NO_UNDO_PREFIXES = List.of("branch: ", "clone: ", "commit (initial): ");
  // 진행 중인 합치기 · cherry-pick · revert · 리베이스를 나타내는 .git 안 파일 · 폴더.
  private static final List<String> IN_PROGRESS = List.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply");
  private static final int SHORT_HASH = 8;

  private GitUndo() {}

  /** root 의 되돌릴 동작을 읽는다. 할 수 없으면 사용자에게 보일 이유를 담은 IOException. 작업 스레드에서 부른다. */
  static Plan read(File root) throws IOException, InterruptedException {
    File gitDir = new File(SyncPanel.git(root, "rev-parse", "--absolute-git-dir").trim());
    for (String name : IN_PROGRESS) {
      if (new File(gitDir, name).exists()) throw new IOException(SyncBundle.message("undo.in.progress"));
    }
    String branch;
    try {
      branch = SyncPanel.git(root, "symbolic-ref", "--short", "-q", "HEAD").trim();
    }
    catch (IOException detached) {
      throw new IOException(SyncBundle.message("undo.detached"));
    }
    String head = SyncPanel.git(root, "log", "-g", "-n", "1", REFLOG_DATE, REFLOG_FORMAT, "HEAD").trim();
    String log = SyncPanel.git(root, "log", "-g", "-n", "2", REFLOG_DATE, REFLOG_FORMAT, "refs/heads/" + branch).trim();
    Plan plan = plan(head, log, branch);
    if (plan == null) throw new IOException(SyncBundle.message("undo.nothing", branch));
    if (plan.mode() == Mode.CHECKOUT) {
      try {
        SyncPanel.git(root, "show-ref", "--verify", "--quiet", "refs/heads/" + plan.target());
      }
      catch (IOException notBranch) {
        throw new IOException(SyncBundle.message("undo.checkout.not.branch", plan.target()));
      }
      return plan;
    }
    boolean onServer;
    try {
      SyncPanel.git(root, "merge-base", "--is-ancestor", "HEAD", "@{u}");
      onServer = true;
    }
    catch (IOException notAncestorOrNoUpstream) {
      onServer = false;
    }
    return new Plan(plan.mode(), plan.subject(), plan.when(), plan.target(), onServer);
  }

  /**
   * headTop 은 HEAD reflog 맨 위 한 줄, branchLog 는 지금 브랜치 reflog 위 두 줄(REFLOG_FORMAT).
   * 체크아웃으로 이 브랜치에 막 왔으면 앞 브랜치로, 아니면 브랜치 reflog 두 번째 줄의 커밋으로 돌린다. 돌아갈 곳이 없으면 null. onServer 는 거짓으로 둔다.
   */
  static Plan plan(String headTop, String branchLog, String branch) {
    String[] head = headTop.split(FIELD, -1);
    if (head.length == 3 && head[2].startsWith(CHECKOUT_PREFIX) && head[2].endsWith(CHECKOUT_TO + branch)) {
      String from = head[2].substring(CHECKOUT_PREFIX.length(), head[2].length() - (CHECKOUT_TO + branch).length());
      if (from.isEmpty() || from.equals(branch)) return null;
      return new Plan(Mode.CHECKOUT, head[2], whenOf(head[1]), from, false);
    }
    String[] lines = branchLog.split("\n");
    if (lines.length < 2) return null;
    String[] top = lines[0].split(FIELD, -1);
    String[] before = lines[1].split(FIELD, -1);
    if (top.length != 3 || before.length != 3) return null;
    for (String prefix : NO_UNDO_PREFIXES) {
      if (top[2].startsWith(prefix)) return null;
    }
    Mode mode = Mode.KEEP;
    for (String prefix : SOFT_PREFIXES) {
      if (top[2].startsWith(prefix)) mode = Mode.SOFT;
    }
    return new Plan(mode, top[2], whenOf(top[1]), before[0], false);
  }

  /** 「main@{2026-10-28 22:10}」에서 시각. 형식이 다르면 그대로. */
  static String whenOf(String selector) {
    int open = selector.indexOf("@{");
    return open >= 0 && selector.endsWith("}") ? selector.substring(open + 2, selector.length() - 1) : selector;
  }

  /** 확인 창 글(HTML). 이미 서버에 있는 커밋을 없애면 오류 색으로 알린다. */
  static String confirmText(String repoName, Plan plan) {
    String target = StringUtil.escapeXmlEntities(plan.mode() == Mode.CHECKOUT ? plan.target() : StringUtil.first(plan.target(), SHORT_HASH, false));
    String key = switch (plan.mode()) {
      case SOFT -> "undo.confirm.soft";
      case KEEP -> "undo.confirm.keep";
      case CHECKOUT -> "undo.confirm.checkout";
    };
    StringBuilder text = new StringBuilder("<html>")
      .append(SyncBundle.message("undo.confirm.last", StringUtil.escapeXmlEntities(repoName), StringUtil.escapeXmlEntities(plan.subject()),
                                 StringUtil.escapeXmlEntities(plan.when())))
      .append("<br><br>").append(SyncBundle.message(key, target));
    if (plan.onServer()) {
      text.append("<br><br><font color=\"").append(ColorUtil.toHtmlColor(NamedColorUtil.getErrorForeground())).append("\">")
        .append(SyncBundle.message("undo.confirm.server")).append("</font>");
    }
    return text.append("</html>").toString();
  }

  /** plan 을 실행하고 알림 글을 돌려준다. reset --keep 이 내 변경과 겹쳐 멈추면 이유를 붙인 IOException(작업본은 그대로). 작업 스레드에서 부른다. */
  static String run(File root, Plan plan) throws IOException, InterruptedException {
    switch (plan.mode()) {
      case SOFT -> SyncPanel.git(root, "reset", "--soft", plan.target());
      // 커밋 되돌리기(reset --soft)를 다시 되돌릴 때처럼 색인 · 작업본이 이미 target 과 같으면 --soft 로 충분하다.
      // --keep 은 이때도 target 과 HEAD 사이에 바뀐 파일을 내 변경으로 보고 멈춘다.
      case KEEP -> {
        if (matches(root, plan.target())) SyncPanel.git(root, "reset", "--soft", plan.target());
        else keep(root, plan.target());
      }
      case CHECKOUT -> SyncPanel.git(root, SyncPanel.GIT_LONG_TIMEOUT_SEC, "checkout", plan.target());
    }
    return SyncBundle.message("undo.done", root.getName(), plan.subject());
  }

  /** reset --keep. 내 변경과 겹쳐 git 이 멈추면 이유를 앞에 붙인다(작업본은 그대로). */
  private static void keep(File root, String target) throws IOException, InterruptedException {
    try {
      SyncPanel.git(root, SyncPanel.GIT_LONG_TIMEOUT_SEC, "reset", "--keep", target);
    }
    catch (IOException e) {
      throw new IOException(SyncBundle.message("undo.keep.failed") + "\n\n" + e.getMessage());
    }
  }

  /** 색인과 작업본(추적 중인 파일)이 커밋 target 과 같은가. */
  private static boolean matches(File root, String target) throws InterruptedException {
    try {
      SyncPanel.git(root, "diff", "--cached", "--quiet", target);
      SyncPanel.git(root, "diff", "--quiet");
      return true;
    }
    catch (IOException different) {
      return false;
    }
  }
}
