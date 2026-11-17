package kinggura.sync;

import com.intellij.dvcs.push.VcsPushOptionValue;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationAction;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsNotifier;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.GitRemoteBranch;
import git4idea.branch.GitBranchUtil;
import git4idea.push.GitPushNotificationCustomizer;
import git4idea.push.GitPushRepoResult;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab 의 새 Merge Request 페이지를 브라우저로 연다. 보낼 쪽은 지금 브랜치(원격 이름), 받는 쪽은 설정의 브랜치(SyncSettings.mrTarget, 기본 develop).
 * 받는 쪽 브랜치가 원격에 없으면 받는 쪽을 비워 GitLab 이 그 저장소 기본 브랜치를 고르게 한다.
 * Git 메뉴 · 동기화 창 저장소 줄 우클릭(이 클래스)과 Push 결과 알림의 단추(PushActions)가 같은 주소를 쓴다.
 */
public final class MergeRequest extends DumbAwareAction {
  /** plugin.xml 의 동작 ID. 동기화 창 메뉴가 이 ID 로 꺼내 쓴다. */
  static final String ID = "KingGura.MergeRequest";
  private static final String NOTIFICATION_ID = "kinggura.sync.mergeRequest";
  /** GitLab 새 Merge Request 페이지 경로와 칸 이름(GitLab merge_requests/new 의 query 이름). */
  private static final String NEW_PATH = "/-/merge_requests/new";
  private static final String SOURCE_PARAM = "merge_request%5Bsource_branch%5D=";
  private static final String TARGET_PARAM = "merge_request%5Btarget_branch%5D=";
  /** 기본 브랜치로 흔히 쓰는 이름. 여기로 올린 Push 에는 Merge Request 단추를 붙이지 않는다. */
  private static final Set<String> MAIN_BRANCHES = Set.of("main", "master");
  /** GitLab 이 올리기 권한이 없을 때 내는 문구(GitLab git_access 의 upload 거절). 소문자로 비교한다. */
  private static final String NO_UPLOAD = "not allowed to upload code";
  private static final String GIT_SUFFIX = ".git";
  /** scp 꼴 ssh 주소(git@host:group/repo.git). */
  private static final Pattern SCP_URL = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(?!//)(.+)$");
  /** scheme://[user[:password]@]host[:port]/path. 아이디 · 비밀번호는 브라우저로 넘기지 않게 버린다(비밀번호에 @ 가 있어도 마지막 @ 까지). */
  private static final Pattern SCHEME_URL = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^/]*@)?([^/:]+)(:\\d+)?/(.+)$");
  private static final String HTTPS = "https";
  private static final String HTTP = "http";

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    GitRepository repo = project == null ? null : GitBranchUtil.guessRepositoryForOperation(project, e.getDataContext());
    e.getPresentation().setVisible(project != null);
    e.getPresentation().setEnabled(repo != null && pageOf(repo) != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    GitRepository repo = project == null ? null : GitBranchUtil.guessRepositoryForOperation(project, e.getDataContext());
    String page = repo == null ? null : pageOf(repo);
    if (page != null) BrowserUtil.browse(page);
  }

  /** 지금 브랜치의 새 Merge Request 주소. 브랜치가 없거나(HEAD 만 있음) 웹 주소로 바꿀 원격이 없으면 null. */
  static String pageOf(GitRepository repo) {
    GitLocalBranch branch = repo.getCurrentBranch();
    if (branch == null) return null;
    GitRemoteBranch tracked = branch.findTrackedBranch(repo);
    GitRemote remote = tracked != null ? tracked.getRemote() : defaultRemote(repo);
    if (remote == null) return null;
    String source = tracked != null ? tracked.getNameForRemoteOperations() : branch.getName();
    return page(repo, remote, source);
  }

  /** 원격 remote 의 source 브랜치에서 받는 쪽 브랜치로 가는 새 Merge Request 주소. 웹 주소로 바꿀 수 없으면 null. */
  private static String page(GitRepository repo, GitRemote remote, String source) {
    String web = webUrl(remote.getFirstUrl());
    if (web == null) return null;
    String target = SyncSettings.mrTarget();
    boolean targetExists = repo.getBranches().findRemoteBranch(remote.getName() + "/" + target) != null;
    return newPage(web, source, targetExists ? target : null);
  }

  /** 브랜치가 따라가는 원격이 없을 때: origin, 없으면 첫 원격. */
  private static GitRemote defaultRemote(GitRepository repo) {
    GitRemote first = null;
    for (GitRemote remote : repo.getRemotes()) {
      if (GitRemote.ORIGIN.equals(remote.getName())) return remote;
      if (first == null) first = remote;
    }
    return first;
  }

  /**
   * git 원격 주소를 웹 주소로. https · http 는 그대로(아이디 · 끝의 .git 뺌), ssh(ssh:// 와 git@host:path)는 https://host/path.
   * file 등 그 밖의 주소나 빈 값이면 null.
   */
  static String webUrl(String url) {
    if (url == null) return null;
    String text = url.trim();
    while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
    if (text.endsWith(GIT_SUFFIX)) text = text.substring(0, text.length() - GIT_SUFFIX.length());
    Matcher scheme = SCHEME_URL.matcher(text);
    if (scheme.matches()) {
      String name = scheme.group(1).toLowerCase(Locale.ROOT);
      String path = scheme.group(4);
      if (name.equals(HTTPS) || name.equals(HTTP)) return name + "://" + scheme.group(2) + (scheme.group(3) == null ? "" : scheme.group(3)) + "/" + path;
      // ssh 포트는 웹 포트가 아니므로 버린다.
      return name.equals("ssh") ? HTTPS + "://" + scheme.group(2) + "/" + path : null;
    }
    if (text.contains("://")) return null;
    Matcher scp = SCP_URL.matcher(text);
    // 윈도우 경로(C:\…, C:/…)는 한 글자 호스트처럼 보이므로 뺀다.
    if (scp.matches() && scp.group(1).length() > 1) return HTTPS + "://" + scp.group(1) + "/" + scp.group(2);
    return null;
  }

  /** 웹 주소 web 저장소의 새 Merge Request 페이지. target 이 null 이면 받는 쪽 칸을 넣지 않는다. */
  static String newPage(String web, String source, String target) {
    String page = web + NEW_PATH + "?" + SOURCE_PARAM + URLEncoder.encode(source, StandardCharsets.UTF_8);
    return target == null ? page : page + "&" + TARGET_PARAM + URLEncoder.encode(target, StandardCharsets.UTF_8);
  }

  /** Push 결과가 올리기 권한 없음(GitLab 403)인가. */
  static boolean noUploadRight(String error) {
    return error != null && error.toLowerCase(Locale.ROOT).contains(NO_UPLOAD);
  }

  /** 이 브랜치로 올린 Push 에 Merge Request 단추를 붙일까. 받는 쪽 브랜치 · main · master 는 아니다. */
  static boolean offersFor(String remoteBranch, String target) {
    return !remoteBranch.equals(target) && !MAIN_BRANCHES.contains(remoteBranch);
  }

  /**
   * IntelliJ Push 결과 알림에 저장소마다 「Merge Request 만들기」 단추를 붙인다(올리기가 된 저장소만, 태그만 올린 것 · 받는 쪽 브랜치 제외).
   * 올리기 권한 없음(403 「not allowed to upload code」)으로 실패한 저장소가 있으면 쉬운 말 안내를 따로 띄운다.
   * Push 동작 자체는 건드리지 않는다. 여기서 난 오류는 IntelliJ 가 기록만 하고 단추 없이 알림을 띄운다(GitPusher).
   */
  public static final class PushActions implements GitPushNotificationCustomizer {
    private final Project project;

    public PushActions(Project project) {
      this.project = project;
    }

    // Kotlin suspend 함수: 멈추지 않고 바로 값을 돌려주므로 continuation 은 쓰지 않는다.
    @Override
    public Object getActions(@NotNull GitRepository repository, @NotNull GitPushRepoResult result,
                             @NotNull Map<String, ? extends VcsPushOptionValue> params, @NotNull Continuation<? super List<? extends AnAction>> continuation) {
      if (result.getType() == GitPushRepoResult.Type.ERROR) {
        if (noUploadRight(result.getError())) warnNoUploadRight(repository);
        return List.of();
      }
      boolean pushed = result.getType() == GitPushRepoResult.Type.SUCCESS || result.getType() == GitPushRepoResult.Type.NEW_BRANCH
                       || result.getType() == GitPushRepoResult.Type.FORCED;
      if (!pushed || !result.getSourceBranch().startsWith(GitBranch.REFS_HEADS_PREFIX)) return List.of();
      GitRemote remote = null;
      for (GitRemote r : repository.getRemotes()) {
        if (r.getName().equals(result.getTargetRemote())) remote = r;
      }
      // getTargetBranch 는 refs/remotes/<원격>/<브랜치>.
      String prefix = "refs/remotes/" + result.getTargetRemote() + "/";
      if (remote == null || !result.getTargetBranch().startsWith(prefix)) return List.of();
      String branch = result.getTargetBranch().substring(prefix.length());
      if (!offersFor(branch, SyncSettings.mrTarget())) return List.of();
      String page = page(repository, remote, branch);
      if (page == null) return List.of();
      String text = GitUtil.getRepositoryManager(project).moreThanOneRoot()
                    ? SyncBundle.message("mr.action.repo", repository.getRoot().getName())
                    : SyncBundle.message("mr.action");
      return List.of(NotificationAction.createSimple(text, () -> BrowserUtil.browse(page)));
    }

    private void warnNoUploadRight(GitRepository repository) {
      NotificationAction settings = NotificationAction.createSimpleExpiring(SyncBundle.message("mr.noright.settings"),
        () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, GitLoginConfigurable.class));
      VcsNotifier.getInstance(project).notifyError(NOTIFICATION_ID, SyncBundle.message("mr.noright.title", repository.getRoot().getName()),
                                                   SyncBundle.message("mr.noright.text", SyncSettings.mrTarget()), settings);
    }
  }
}
