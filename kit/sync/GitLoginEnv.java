package kinggura.sync;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsEnvCustomizer;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitVcs;
import git4idea.http.GitAskPassAppHandler;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IntelliJ 에 넣은 플러그인 판에서 IntelliJ Git 명령(Push · Pull · Fetch · Clone 등)에 「Git 로그인」 값을 넘긴다
 * (Moa 본체 손질 kit/git-auth 의 3 번 출처와 같은 값 · 같은 환경값). git 이 비밀번호 도우미(askpass · sh.exe)를 띄우지 않고 인증한다.
 * 서버와 통신하는 명령(IntelliJ 가 https 인증 도우미 환경값을 넣은 명령)에만 넣는다. 로컬 명령의 훅에는 비밀번호를 넘기지 않는다.
 * 그 저장소 원격의 서버 것만 넣는다. 저장소를 모르면(Clone 등) 등록한 모든 서버 것을 넣는다(git 은 주소가 맞는 서버의 값만 보낸다).
 * Git 로그인에 등록이 없으면 아무것도 넣지 않고 IntelliJ 원래 인증을 둔다. Moa 에서는 본체 손질이 하므로 돌지 않는다.
 * git 명령을 띄우는 스레드에서 불린다(GitHandler.prepareEnvironment, 레지스트리 git.use.env.from.project.context 가 켜져 있을 때 · 기본 켜짐).
 */
public final class GitLoginEnv extends VcsEnvCustomizer {
  @Override
  public void customizeCommandAndEnvironment(Project project, Map<String, String> envs, VcsExecutableContext context) {
    if (project == null || !(context.getVcs() instanceof GitVcs) || !envs.containsKey(GitAskPassAppHandler.IJ_ASK_PASS_HANDLER_ENV)) return;
    if (MoaProduct.isMoa() || !GitLogins.any()) return;
    envs.putAll(GitLogins.env(servers(project, context.getRoot()), envs));
  }

  /** 그 저장소 원격(받기 · 올리기 주소)의 https · http 서버. 저장소를 모르면 등록한 모든 서버. */
  private static Set<String> servers(Project project, VirtualFile root) {
    List<String> urls = new ArrayList<>();
    GitRepository repo = root == null ? null : GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root);
    if (repo == null) {
      for (GitLogins.Login login : GitLogins.list()) urls.add(login.server());
    }
    else {
      for (GitRemote remote : repo.getRemotes()) {
        urls.addAll(remote.getUrls());
        urls.addAll(remote.getPushUrls());
      }
    }
    return GitRemoteAuth.serverUrls(urls).keySet();
  }
}
