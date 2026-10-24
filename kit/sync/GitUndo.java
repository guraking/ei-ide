package kinggura.sync;

/**
 * 「마지막 Git 동작 되돌리기」가 할 일을 reflog 한 줄(%gs)로 정한다. IntelliJ 클래스 없이 글만 다룬다.
 * 브랜치 바꾸기는 HEAD reflog 에만 남으므로 HEAD 맨 위 줄을 먼저 보고, 그 밖의 동작은 현재 브랜치의 reflog 맨 위 줄로 본다.
 * 리베이스는 HEAD reflog 에 커밋마다 줄을 남기지만 브랜치 reflog 에는 한 줄만 남아, 브랜치 쪽 바로 앞 위치(<브랜치>@{1})가 리베이스 전이다.
 */
final class GitUndo {
  /** SWITCH_BACK: 앞 브랜치로 다시 바꾼다. SOFT: 커밋만 없애고 그 변경은 Staged 로 남긴다. KEEP: 브랜치를 앞 위치로 옮기되 내 작업본 변경은 지키고, 지킬 수 없으면 git 이 멈춘다. */
  enum Way { SWITCH_BACK, SOFT, KEEP }

  /** target 은 SWITCH_BACK 이면 돌아갈 브랜치(또는 해시), 아니면 reset 할 위치. */
  record Plan(Way way, String target) {}

  private static final String CHECKOUT = "checkout: moving from ";
  private static final String CHECKOUT_TO = " to ";
  private static final String REBASE = "rebase";
  private static final String COMMIT = "commit: ";
  private static final String AMEND = "commit (amend): ";
  // 브랜치가 처음 생긴 줄(branch: Created from … · clone: from …)과 첫 커밋(commit (initial))에는 돌아갈 앞 위치가 없다.
  private static final String CREATED = "branch: ";
  private static final String CLONED = "clone: ";
  private static final String INITIAL = "commit (initial)";
  private static final String PREVIOUS = "@{1}";

  private static final String RESET = "reset: moving to ";

  private GitUndo() {}

  /**
   * plan 대로 되돌린 뒤 HEAD reflog 맨 위에 남을 줄. 같은 줄이 맨 위면 이미 되돌린 것이다.
   * 브랜치 바꾸기는 지금 브랜치(branch)에서 떠나는 줄이라 브랜치가 없으면(detached) 알 수 없어 null.
   */
  static String undoneLine(Plan plan, String branch) {
    if (plan.way() != Way.SWITCH_BACK) return RESET + plan.target();
    return branch == null ? null : CHECKOUT + branch + CHECKOUT_TO + plan.target();
  }

  /**
   * headTop 은 HEAD reflog 맨 위 줄, branch 는 현재 브랜치(브랜치가 없으면 null), branchTop 은 그 브랜치 reflog 맨 위 줄(없으면 null).
   * 되돌릴 수 없으면 null: reflog 가 비었음, 리베이스가 아직 끝나지 않음, 브랜치 없이(detached) 한 동작, 앞 위치가 없는 첫 줄.
   */
  static Plan plan(String headTop, String branch, String branchTop) {
    if (headTop == null || headTop.isBlank()) return null;
    if (headTop.startsWith(CHECKOUT)) {
      int to = headTop.indexOf(CHECKOUT_TO, CHECKOUT.length());
      return to < 0 ? null : new Plan(Way.SWITCH_BACK, headTop.substring(CHECKOUT.length(), to));
    }
    // 리베이스 도중(rebase (pick) 등)은 IntelliJ 의 Abort Rebase 로 끝낸다. 끝난 리베이스는 「rebase (finish)」 또는 「rebase … (finish)」 줄이다.
    if (headTop.startsWith(REBASE) && !headTop.contains("(finish)")) return null;
    if (branch == null || branchTop == null || branchTop.isBlank()) return null;
    if (branchTop.startsWith(CREATED) || branchTop.startsWith(CLONED) || branchTop.startsWith(INITIAL)) return null;
    Way way = branchTop.startsWith(COMMIT) || branchTop.startsWith(AMEND) ? Way.SOFT : Way.KEEP;
    return new Plan(way, branch + PREVIOUS);
  }
}
