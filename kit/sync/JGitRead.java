package kinggura.sync;

import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.EmptyProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SubmoduleConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.eclipse.jgit.treewalk.TreeWalk;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * git 대신 JGit 으로 도는 읽기 · 조회 명령. SyncPanel.runBytes 가 git 을 띄우기 전에 부른다.
 * 돌려주는 바이트는 git 이 같은 인자로 낼 표준 출력과 같다. 맡지 않는 인자, git 과 결과가 다를 수 있는 저장소, JGit 실패면 null 이고 그때는 git 을 띄운다.
 * git 이 오류 글 없이 종료 코드 1 만 내는 경우(--verify --quiet 의 없는 참조, merge-base 의 아님)는 runOnce 와 같은 글의 IOException 을 던진다.
 *
 * 맡는 것: fetch --quiet [--prune] · ls-remote --heads(서버), rev-parse · rev-list --count · merge-base · for-each-ref · symbolic-ref ·
 * cat-file blob · show 리비전:경로 · ls-tree -r --name-only(로컬, 정해 둔 모양만).
 * 맡지 않는 것: 작업본을 보는 명령(status · diff · ls-files, autocrlf · 색인 해석이 git 과 다를 수 있다), log(서식이 많다),
 * config(JGit 은 시스템 설정 위치를 git 을 띄워 찾는다), 저장소 · 작업본을 바꾸는 명령(pull · push · merge · checkout · stash · reset · commit · mv 등).
 *
 * 서버 명령은 Git 로그인 머리글이 있는 https 서버와 로컬 경로 원격만 맡는다(JGit 은 git 의 비밀번호 도우미를 쓰지 못한다).
 * 머리글이 거절되면(401) git 으로 다시 하지 않고 git 과 같은 인증 실패 글(Authentication failed for '주소')의 IOException 을 던진다.
 * git 도 같은 머리글을 다시 보내 한 명령에 두 번 실패하게 되고(서버 계정 잠김이 빨라진다), GitRemoteAuth 가 이 글로 실패한 서버를 알아본다.
 * 연결 · 인증서 단계에서 실패한 서버(프록시 · 회사 인증서가 git 과 다름)는 이번 실행 동안 git 에만 맡긴다.
 * fetch 의 prune 은 JGit 이 제외 refspec(^refs/heads/…)을 보지 않아 여기서 git 과 같게 한다. core.ignorecase 저장소에서는 서버에 대소문자만 다른 이름이
 * 있는 참조를 지우지 않는다(윈도우에서 같은 파일이라 지우면 내 추적 참조가 사라진다).
 *
 * -Dmoa.jgit=false 면 모두 git 으로 돈다.
 */
final class JGitRead {
  private static final Logger LOG = Logger.getInstance(JGitRead.class);
  private static final String DISABLE_PROPERTY = "moa.jgit";
  private static final boolean ENABLED = !Boolean.FALSE.toString().equalsIgnoreCase(System.getProperty(DISABLE_PROPERTY));
  // SyncPanel.gitArgs 가 모든 git 앞에 붙이는 -c 값. 출력에 영향이 없거나(JGit 은 UTF-8) 아래에서 같게 맞춘다(경로 따옴표는 맡지 않음).
  private static final Set<String> KNOWN_CONFIG = Set.of("core.quotepath=false", "i18n.logOutputEncoding=UTF-8");
  // git 이 짧은 이름을 참조로 풀 때 차례(refs.c ref_rev_parse_rules). 첫 "%s" 는 git 폴더 바로 아래 이름(HEAD 등).
  private static final List<String> REV_RULES = List.of("%s", "refs/%s", "refs/tags/%s", "refs/heads/%s", "refs/remotes/%s", "refs/remotes/%s/HEAD");
  private static final String UPSTREAM = "@{u}";
  private static final String FIRST_PARENT = "^";
  private static final Pattern FULL_HASH = Pattern.compile("\\p{XDigit}{40}");
  // 짧은 해시로도 읽히는 이름 · git 리비전 문법 글자가 든 이름은 git 에 맡긴다.
  private static final Pattern PLAIN_NAME = Pattern.compile("(?!\\p{XDigit}{4,40}$)[\\w./-]+");
  private static final String SYMMETRIC = "...";
  private static final String RANGE = "..";
  private static final String PARENT_DIR = "..";
  private static final String CURRENT_DIR = "./";
  private static final String DEFAULT_REMOTE = "origin";
  private static final String LOCAL_REMOTE = ".";
  // fetch · ls-remote 가 응답 없이 기다리는 최대 초(연결 · 읽기 하나마다). 넘으면 실패로 보고 git 으로 다시 한다.
  private static final int IO_TIMEOUT_SEC = 30;
  private static final String HEADER_SEP = ": ";
  /** git 이 서버에서 인증을 거절당했을 때 내는 글(SyncPanel.isGitAuthError · GitRemoteAuth.failedServers 가 읽는다). */
  private static final String AUTH_FAILED = "fatal: Authentication failed for '%s'";
  /** 연결 · 인증서 단계에서 JGit 이 실패한 서버(GitLogins.serverOf 모양). */
  private static final Set<String> GIT_ONLY_SERVERS = ConcurrentHashMap.newKeySet();

  /** git 이 실패할 자리(오류 글 없는 종료 코드 1 · 서버 인증 실패). run 이 runOnce 와 같은 글의 IOException 으로 바꾼다. */
  private static final class GitFailure extends Exception {
    GitFailure(String message) {
      super(message, null, false, false);
    }
  }

  private JGitRead() {}

  /** args 는 runBytes 가 받은 git 인자(gitArgs 의 -c 포함). logins 는 서버 명령에 쓸 Git 로그인(null 이면 서버 명령을 맡지 않는다). */
  static byte[] run(File dir, long timeoutSec, List<GitLogins.Login> logins, String... args) throws IOException, InterruptedException {
    // 없는 폴더에서는 git 이 시작하지 못한다. JGit 은 윗 폴더 저장소를 찾아 버린다.
    if (!ENABLED || !dir.isDirectory()) return null;
    int start = 0;
    while (start + 1 < args.length && args[start].equals("-c")) {
      if (!KNOWN_CONFIG.contains(args[start + 1])) return null;
      start += 2;
    }
    if (start >= args.length) return null;
    List<String> command = List.of(args).subList(start, args.length);
    String verb = command.get(0);
    long started = System.nanoTime();
    try (Repository repo = open(dir)) {
      if (repo == null) return null;
      byte[] out = switch (verb) {
        case "fetch" -> fetch(repo, command, timeoutSec, logins);
        case "ls-remote" -> lsRemote(repo, command, timeoutSec, logins);
        case "rev-parse" -> revParse(repo, command);
        case "rev-list" -> revList(repo, command);
        case "merge-base" -> mergeBase(repo, command);
        case "for-each-ref" -> forEachRef(repo, command);
        case "symbolic-ref" -> symbolicRef(repo, command);
        case "cat-file" -> catFile(repo, command);
        case "show" -> show(repo, dir, command);
        case "ls-tree" -> lsTree(repo, dir, command);
        default -> null;
      };
      if (out != null && SyncPanel.TIME_LOG.isDebugEnabled()) {
        SyncPanel.TIME_LOG.debug("[" + dir.getName() + "] jgit " + verb + " " + (System.nanoTime() - started) / 1_000_000 + " ms: " + String.join(" ", command));
      }
      return out;
    }
    catch (GitFailure e) {
      throw new IOException("git " + verb + " 실패: " + e.getMessage());
    }
    catch (Exception | LinkageError e) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      LOG.info("JGit 으로 하지 못해 git 으로 실행 [" + dir.getName() + "] " + String.join(" ", command) + ": " + e);
      return null;
    }
  }

  /**
   * dir 을 담은 저장소. git 과 결과가 다를 수 있는 저장소는 null: 작업본 없는 저장소 · 연결 작업본(worktree) · 얕은 복제 · 부분 복제 ·
   * grafts · refs/replace(git 은 커밋을 바꿔 읽는다).
   */
  private static Repository open(File dir) throws IOException {
    FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(dir.getAbsoluteFile());
    File gitDir = builder.getGitDir();
    if (gitDir == null || new File(gitDir, "commondir").exists() || new File(gitDir, "shallow").exists() || new File(gitDir, "info/grafts").exists()) return null;
    Repository repo = builder.setMustExist(true).build();
    Config config = repo.getConfig();
    boolean partial = config.getString("extensions", null, "partialClone") != null;
    for (String remote : config.getSubsections("remote")) partial |= config.getBoolean("remote", remote, "promisor", false);
    if (repo.isBare() || partial || !repo.getRefDatabase().getRefsByPrefix(Constants.R_REFS + "replace/").isEmpty()) {
      repo.close();
      return null;
    }
    return repo;
  }

  // ---- 서버 ----

  /** fetch --quiet [--prune] [원격 [refspec…]]. 표준 출력은 비어 있다. */
  private static byte[] fetch(Repository repo, List<String> command, long timeoutSec, List<GitLogins.Login> logins) throws Exception {
    boolean quiet = false;
    boolean prune = false;
    String remote = null;
    List<RefSpec> specs = new ArrayList<>();
    for (String arg : command.subList(1, command.size())) {
      if (arg.equals("--quiet")) quiet = true;
      else if (arg.equals("--prune")) prune = true;
      else if (arg.startsWith("-")) return null;
      else if (remote == null) remote = arg;
      else specs.add(new RefSpec(arg));
    }
    if (!quiet) return null;
    if (remote == null) remote = defaultRemote(repo);
    if (remote == null) return null;
    // 하위 모듈이 있으면 git 은 그 저장소도 받는다.
    if (repo.getWorkTree() != null && new File(repo.getWorkTree(), Constants.DOT_GIT_MODULES).exists()) return null;
    RemoteConfig rc = new RemoteConfig(repo.getConfig(), remote);
    Server server = server(rc, logins);
    if (server == null) return null;
    List<RefSpec> used = specs.isEmpty() ? rc.getFetchRefSpecs() : specs;
    if (used.isEmpty()) return null;
    Config config = repo.getConfig();
    prune |= config.getBoolean("remote", remote, "prune", config.getBoolean("fetch", null, "prune", false));

    FetchCommand fetch = Git.wrap(repo).fetch().setRemote(remote).setRefSpecs(specs).setRemoveDeletedRefs(false)
      .setRecurseSubmodules(SubmoduleConfig.FetchRecurseSubmodulesMode.NO).setProgressMonitor(new CancelOnInterrupt());
    FetchResult result = server.call(prepare(fetch, server, timeoutSec), remote);
    for (TrackingRefUpdate update : result.getTrackingRefUpdates()) {
      switch (update.getResult()) {
        case NEW, FORCED, FAST_FORWARD, NO_CHANGE -> { }
        // git 이라면 cannot lock ref 등으로 멈춘다. git 으로 다시 해 그 오류 처리(SyncPanel.fetch)를 그대로 탄다.
        default -> throw new IOException("참조 갱신 " + update.getLocalName() + ": " + update.getResult());
      }
    }
    if (prune) prune(repo, used, result);
    return new byte[0];
  }

  /** ls-remote --heads 원격. 「해시\t참조」 줄을 참조 이름 순으로(git 과 같다). */
  private static byte[] lsRemote(Repository repo, List<String> command, long timeoutSec, List<GitLogins.Login> logins) throws Exception {
    if (command.size() != 3 || !command.get(1).equals("--heads") || command.get(2).startsWith("-")) return null;
    String remote = command.get(2);
    Server server = server(new RemoteConfig(repo.getConfig(), remote), logins);
    if (server == null) return null;
    Collection<Ref> refs = server.call(prepare(Git.wrap(repo).lsRemote().setRemote(remote).setHeads(true), server, timeoutSec), remote);
    List<Ref> sorted = new ArrayList<>(refs);
    sorted.sort(java.util.Comparator.comparing(Ref::getName));
    StringBuilder out = new StringBuilder();
    for (Ref ref : sorted) out.append(ref.getObjectId().name()).append('\t').append(ref.getName()).append('\n');
    return utf8(out);
  }

  /** git fetch 가 원격을 주지 않았을 때 쓰는 원격: 지금 브랜치의 branch.<이름>.remote, 없으면 origin. 로컬 원격(.)이면 null. */
  private static String defaultRemote(Repository repo) throws IOException {
    String full = repo.getFullBranch();
    String remote = full != null && full.startsWith(Constants.R_HEADS)
                    ? repo.getConfig().getString("branch", full.substring(Constants.R_HEADS.length()), "remote") : null;
    if (remote == null) remote = DEFAULT_REMOTE;
    return remote.equals(LOCAL_REMOTE) ? null : remote;
  }

  /** 원격이 닿는 곳과 넣을 머리글. 첫 주소(git fetch 가 쓰는 것)가 절대 로컬 경로면 머리글 없이, Git 로그인이 있는 https 면 그 머리글로. 나머지는 null. */
  private static Server server(RemoteConfig rc, List<GitLogins.Login> logins) {
    if (rc.getURIs().isEmpty()) return null;
    URIish uri = rc.getURIs().get(0);
    String scheme = uri.getScheme();
    if (scheme == null && uri.getHost() == null || "file".equals(scheme)) return new File(uri.getPath()).isAbsolute() ? new Server(null, null, Map.of()) : null;
    if (logins == null || !GitLogins.isHttpUrl(uri.toString())) return null;
    String name = GitLogins.serverOf(uri.toString());
    GitLogins.Login login = GitLogins.find(logins, name);
    if (login == null || login.secret() == null || login.secret().isEmpty() || GIT_ONLY_SERVERS.contains(name)) return null;
    String header = GitLogins.header(login);
    int sep = header.indexOf(HEADER_SEP);
    return new Server(name, uri.toString(), Map.of(header.substring(0, sep), header.substring(sep + HEADER_SEP.length())));
  }

  private static <C extends TransportCommand<C, ?>> C prepare(C command, Server server, long timeoutSec) {
    return command.setTimeout((int) Math.min(timeoutSec, IO_TIMEOUT_SEC)).setTransportConfigCallback(transport -> {
      if (transport instanceof TransportHttp http && server.name() != null) http.setHttpConnectionFactory(new ServerHeaders(server));
    });
  }

  /**
   * JGit 기본 연결에 Git 로그인 머리글을 그 서버로 가는 연결에만 붙인다. 다른 서버로 넘겨질 때(redirect) 비밀번호가 따라가지 않는다
   * (git 은 http.<서버>.extraHeader 로 넘겨 그 서버에만 간다).
   */
  private record ServerHeaders(Server server) implements HttpConnectionFactory2 {
    private static final JDKHttpConnectionFactory JDK = new JDKHttpConnectionFactory();

    @Override
    public HttpConnection create(URL url) throws IOException {
      return withHeaders(JDK.create(url), url);
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
      return withHeaders(JDK.create(url, proxy), url);
    }

    @Override
    public GitSession newSession() {
      return JDK.newSession();
    }

    private HttpConnection withHeaders(HttpConnection connection, URL url) {
      if (server.name().equals(GitLogins.serverOf(url.toString()))) {
        for (Map.Entry<String, String> header : server.headers().entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
      }
      return connection;
    }
  }

  /** 원격 하나. name 은 https 서버, url 은 원격 주소(로컬 경로면 둘 다 null). */
  private record Server(String name, String url, Map<String, String> headers) {
    /** 명령을 돌린다. 연결 · 인증서 단계 실패면 그 서버를 git 에만 맡긴다. 머리글이 거절되면 git 의 인증 실패 글로 끝낸다. 그 밖의 예외는 그대로 던진다. */
    <T> T call(org.eclipse.jgit.api.GitCommand<T> command, String remote) throws Exception {
      try {
        return command.call();
      }
      catch (org.eclipse.jgit.api.errors.TransportException e) {
        if (name != null && networkFailure(e)) {
          GIT_ONLY_SERVERS.add(name);
          LOG.warn("JGit 이 서버에 닿지 못해 이번 실행 동안 git 으로만 받음 [" + remote + "] " + name + ": " + e);
        }
        if (name != null && authRejected(e)) throw new GitFailure(String.format(AUTH_FAILED, url) + "\n" + SyncBundle.message("sync.git.auth"));
        throw e;
      }
    }
  }

  /** 서버가 401 로 거절했다(JGit 은 비밀번호 도우미가 없어 다시 묻지 않고 이 글로 끝낸다). */
  private static boolean authRejected(Throwable e) {
    String notAuthorized = JGitText.get().notAuthorized;
    String noProvider = JGitText.get().noCredentialsProvider;
    for (Throwable t = e; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (t instanceof org.eclipse.jgit.errors.TransportException && message != null && (message.endsWith(notAuthorized) || message.endsWith(noProvider))) return true;
    }
    return false;
  }

  private static boolean networkFailure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SSLException || t instanceof SocketException || t instanceof SocketTimeoutException || t instanceof UnknownHostException) return true;
    }
    return false;
  }

  /**
   * git fetch --prune 과 같게 지운다: specs 의 대상에 드는 내 참조 가운데 그 원본이 서버에 없는 것. 제외 refspec 에 드는 원본은 지우지 않는다(git 2.29 부터).
   * core.ignorecase 저장소에서는 서버에 대소문자만 다른 원본이 있으면 지우지 않는다.
   */
  private static void prune(Repository repo, List<RefSpec> specs, FetchResult result) throws IOException {
    boolean folded = repo.getConfig().getBoolean("core", null, "ignorecase", false);
    Set<String> advertised = new HashSet<>();
    for (Ref ref : result.getAdvertisedRefs()) advertised.add(ref.getName().toLowerCase(Locale.ROOT));
    for (Ref ref : repo.getRefDatabase().getRefs()) {
      if (ref.isSymbolic()) continue;
      String source = sourceOf(specs, ref.getName());
      if (source == null || result.getAdvertisedRef(source) != null || excluded(specs, source)) continue;
      if (folded && advertised.contains(source.toLowerCase(Locale.ROOT))) continue;
      RefUpdate delete = repo.updateRef(ref.getName());
      delete.setForceUpdate(true);
      RefUpdate.Result deleted = delete.delete();
      if (deleted != RefUpdate.Result.FORCED && deleted != RefUpdate.Result.NO_CHANGE) throw new IOException("참조 지우기 " + ref.getName() + ": " + deleted);
    }
  }

  /** 참조 name 을 받아 오는 서버 쪽 이름(첫 positive refspec 기준), 어느 refspec 에도 들지 않으면 null. */
  private static String sourceOf(List<RefSpec> specs, String name) {
    for (RefSpec spec : specs) {
      if (!spec.isNegative() && spec.getDestination() != null && spec.matchDestination(name)) return spec.expandFromDestination(name).getSource();
    }
    return null;
  }

  private static boolean excluded(List<RefSpec> specs, String source) {
    for (RefSpec spec : specs) {
      if (spec.isNegative() && spec.matchSource(source)) return true;
    }
    return false;
  }

  /** 스레드가 멈추라는 말을 받으면 JGit 의 받기를 멈춘다. */
  private static final class CancelOnInterrupt extends EmptyProgressMonitor {
    @Override
    public boolean isCancelled() {
      return Thread.currentThread().isInterrupted();
    }
  }

  // ---- 로컬 ----

  /**
   * rev-parse. 인자 차례대로 한 줄씩: --show-toplevel · --absolute-git-dir(경로 구분은 /), 리비전(해시), --abbrev-ref 뒤의 HEAD · @{u}(짧은 이름).
   * --verify --quiet 로 refs/… 를 물었는데 없으면 종료 코드 1.
   */
  private static byte[] revParse(Repository repo, List<String> command) throws Exception {
    boolean abbrev = false;
    boolean symbolicFullName = false;
    boolean verify = false;
    boolean quiet = false;
    StringBuilder out = new StringBuilder();
    // --verify 는 리비전 하나만 받는다(둘 이상이면 git 이 다른 오류로 멈춘다).
    long revs = command.stream().skip(1).filter(arg -> !arg.startsWith("-")).count();
    if (command.contains("--verify") && revs != 1) return null;
    for (String arg : command.subList(1, command.size())) {
      switch (arg) {
        case "--show-toplevel" -> out.append(gitPath(repo.getWorkTree())).append('\n');
        case "--absolute-git-dir" -> out.append(gitPath(repo.getDirectory())).append('\n');
        case "--abbrev-ref" -> abbrev = true;
        case "--symbolic-full-name" -> symbolicFullName = true;
        case "--verify" -> verify = true;
        case "--quiet" -> quiet = true;
        default -> {
          if (arg.startsWith("-")) return null;
          String line = abbrev ? abbreviated(repo, arg) : symbolicFullName ? fullName(repo, arg) : hashOf(repo, arg);
          if (line == null) {
            if (verify && quiet && arg.startsWith(Constants.R_REFS) && missing(repo, arg)) throw new GitFailure("종료 코드 1");
            return null;
          }
          out.append(line).append('\n');
        }
      }
    }
    if (verify && (abbrev || symbolicFullName)) return null;
    return utf8(out);
  }

  /** --symbolic-full-name 뒤의 HEAD: 지금 브랜치의 전체 참조 이름(떨어진 HEAD 면 HEAD). HEAD 커밋이 없거나 그 밖 이름은 null. */
  private static String fullName(Repository repo, String rev) throws IOException {
    if (!rev.equals(Constants.HEAD)) return null;
    Ref head = repo.exactRef(Constants.HEAD);
    if (head == null || head.getObjectId() == null) return null;
    return head.isSymbolic() ? head.getTarget().getName() : Constants.HEAD;
  }

  /** --abbrev-ref 의 짧은 이름. HEAD 는 지금 브랜치(떨어진 HEAD 면 HEAD), @{u} 는 추적 브랜치. 그 밖 · 못 가리면 null. */
  private static String abbreviated(Repository repo, String rev) throws IOException {
    if (rev.equals(Constants.HEAD)) {
      Ref head = repo.exactRef(Constants.HEAD);
      if (head == null || head.getObjectId() == null) return null;
      return head.isSymbolic() ? shortName(repo, head.getTarget().getName()) : Constants.HEAD;
    }
    if (!rev.equals(UPSTREAM)) return null;
    Ref upstream = upstream(repo);
    return upstream == null ? null : shortName(repo, upstream.getName());
  }

  /** 리비전의 해시. 맡는 모양: HEAD · @{u} · refs/… · 브랜치 · 태그 이름 · 전체 해시, 그리고 그 뒤 ^(첫 부모). 못 풀면 null. */
  private static String hashOf(Repository repo, String rev) throws IOException {
    ObjectId id = resolve(repo, rev);
    return id == null ? null : id.name();
  }

  private static ObjectId resolve(Repository repo, String rev) throws IOException {
    boolean parent = rev.endsWith(FIRST_PARENT);
    String base = parent ? rev.substring(0, rev.length() - FIRST_PARENT.length()) : rev;
    ObjectId id;
    if (FULL_HASH.matcher(base).matches()) {
      id = ObjectId.fromString(base);
      if (!repo.getObjectDatabase().has(id)) return null;
    }
    else {
      Ref ref = ref(repo, base);
      id = ref == null ? null : ref.getObjectId();
    }
    if (id == null || !parent) return id;
    try (RevWalk walk = new RevWalk(repo)) {
      RevCommit commit = walk.parseCommit(id);
      return commit.getParentCount() == 0 ? null : commit.getParent(0).copy();
    }
  }

  /** git 이 이름을 푸는 차례(REV_RULES)로 찾은 참조. @{u} 는 추적 브랜치. 리비전 문법이 든 이름 · 짧은 해시로도 읽히는 이름은 null. */
  private static Ref ref(Repository repo, String name) throws IOException {
    if (name.equals(UPSTREAM)) return upstream(repo);
    if (name.contains(RANGE) || !PLAIN_NAME.matcher(name).matches()) return null;
    for (String rule : REV_RULES) {
      // 첫 차례는 git 폴더 바로 아래 이름이다. HEAD 와 refs/… 만 그렇게 찾고 FETCH_HEAD · ORIG_HEAD 같은 나머지는 git 에 맡긴다.
      if (rule.equals("%s") && !name.equals(Constants.HEAD) && !name.startsWith(Constants.R_REFS)) continue;
      Ref ref = repo.exactRef(String.format(rule, name));
      if (ref != null) return ref;
    }
    return null;
  }

  /** 정확히 그 이름의 참조가 없는가(packed-refs · 낱개 파일 모두). */
  private static boolean missing(Repository repo, String name) throws IOException {
    return PLAIN_NAME.matcher(name).matches() && !name.contains(RANGE) && repo.exactRef(name) == null;
  }

  /**
   * 지금 브랜치의 추적 브랜치 참조(branch.<이름>.remote · merge 를 그 원격의 fetch refspec 으로 옮긴 것). 없거나 받아 둔 참조가 없으면 null.
   * 로컬 원격(.) · merge 원본이 제외 refspec 에 드는 경우는 git 에 맡긴다(null).
   */
  private static Ref upstream(Repository repo) throws IOException {
    String full = repo.getFullBranch();
    if (full == null || !full.startsWith(Constants.R_HEADS)) return null;
    Config config = repo.getConfig();
    String branch = full.substring(Constants.R_HEADS.length());
    String remote = config.getString("branch", branch, "remote");
    String merge = config.getString("branch", branch, "merge");
    if (remote == null || merge == null || remote.equals(LOCAL_REMOTE)) return null;
    String destination = null;
    for (String line : config.getStringList("remote", remote, "fetch")) {
      RefSpec spec = new RefSpec(line);
      if (spec.isNegative()) {
        if (spec.matchSource(merge)) return null;
      }
      else if (destination == null && spec.getDestination() != null && spec.matchSource(merge)) {
        destination = spec.expandFromSource(merge).getDestination();
      }
    }
    return destination == null ? null : repo.exactRef(destination);
  }

  /**
   * git 의 짧은 이름(refs.c shorten_unambiguous_ref, strict): 가장 좁은 차례로 줄인 이름이 다른 차례로 다른 참조를 가리키지 않을 때만. 겹치면 null(git 에 맡김).
   */
  private static String shortName(Repository repo, String full) throws IOException {
    for (int i = REV_RULES.size() - 1; i > 0; i--) {
      String rule = REV_RULES.get(i);
      int at = rule.indexOf("%s");
      String before = rule.substring(0, at);
      String after = rule.substring(at + 2);
      if (full.length() <= before.length() + after.length() || !full.startsWith(before) || !full.endsWith(after)) continue;
      String name = full.substring(before.length(), full.length() - after.length());
      for (int j = 0; j < REV_RULES.size(); j++) {
        if (j != i && repo.exactRef(String.format(REV_RULES.get(j), name)) != null) return null;
      }
      return name;
    }
    return null;
  }

  /** rev-list --count A..B · rev-list --left-right --count [--no-merges] A...B. A · B 가 비면 HEAD. */
  private static byte[] revList(Repository repo, List<String> command) throws IOException {
    boolean count = false;
    boolean leftRight = false;
    boolean noMerges = false;
    String range = null;
    for (String arg : command.subList(1, command.size())) {
      if (arg.equals("--count")) count = true;
      else if (arg.equals("--left-right")) leftRight = true;
      else if (arg.equals("--no-merges")) noMerges = true;
      else if (arg.startsWith("-") || range != null) return null;
      else range = arg;
    }
    if (!count || range == null) return null;
    String sep = leftRight ? SYMMETRIC : RANGE;
    int at = range.indexOf(sep);
    if (at < 0 || range.indexOf(sep, at + sep.length()) >= 0 || !leftRight && range.contains(SYMMETRIC)) return null;
    ObjectId left = resolve(repo, orHead(range.substring(0, at)));
    ObjectId right = resolve(repo, orHead(range.substring(at + sep.length())));
    if (left == null || right == null) return null;
    String out = leftRight ? count(repo, left, right, noMerges) + "\t" + count(repo, right, left, noMerges) : String.valueOf(count(repo, right, left, noMerges));
    return (out + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static String orHead(String rev) {
    return rev.isEmpty() ? Constants.HEAD : rev;
  }

  /** include 에서 닿고 exclude 에서 닿지 않는 커밋 수. */
  private static int count(Repository repo, ObjectId include, ObjectId exclude, boolean noMerges) throws IOException {
    try (RevWalk walk = new RevWalk(repo)) {
      walk.setRetainBody(false);
      walk.markStart(walk.parseCommit(include));
      walk.markUninteresting(walk.parseCommit(exclude));
      if (noMerges) walk.setRevFilter(RevFilter.NO_MERGES);
      int n = 0;
      for (RevCommit ignored : walk) n++;
      return n;
    }
  }

  /** merge-base A B(공통 조상이 하나일 때만, 없으면 종료 코드 1) · merge-base --is-ancestor A B(아니면 종료 코드 1). */
  private static byte[] mergeBase(Repository repo, List<String> command) throws Exception {
    boolean ancestor = command.size() == 4 && command.get(1).equals("--is-ancestor");
    if (!ancestor && command.size() != 3) return null;
    ObjectId a = resolve(repo, command.get(command.size() - 2));
    ObjectId b = resolve(repo, command.get(command.size() - 1));
    if (a == null || b == null) return null;
    try (RevWalk walk = new RevWalk(repo)) {
      if (ancestor) {
        if (walk.isMergedInto(walk.parseCommit(a), walk.parseCommit(b))) return new byte[0];
        throw new GitFailure("종료 코드 1");
      }
      walk.setRevFilter(RevFilter.MERGE_BASE);
      walk.markStart(walk.parseCommit(a));
      walk.markStart(walk.parseCommit(b));
      List<RevCommit> bases = new ArrayList<>();
      for (RevCommit base : walk) bases.add(base);
      if (bases.isEmpty()) throw new GitFailure("종료 코드 1");
      return bases.size() == 1 ? (bases.get(0).name() + "\n").getBytes(StandardCharsets.UTF_8) : null;
    }
  }

  /** for-each-ref --format=%(refname) 접두/. 깨진 참조(가리키는 객체 없음)는 git 처럼 뺀다. */
  private static byte[] forEachRef(Repository repo, List<String> command) throws IOException {
    if (command.size() != 3 || !command.get(1).equals("--format=%(refname)")) return null;
    String prefix = command.get(2);
    if (!prefix.startsWith(Constants.R_REFS) || !prefix.endsWith("/") || !PLAIN_NAME.matcher(prefix).matches()) return null;
    List<String> names = new ArrayList<>();
    for (Ref ref : repo.getRefDatabase().getRefsByPrefix(prefix)) {
      if (ref.getObjectId() != null && repo.getObjectDatabase().has(ref.getObjectId())) names.add(ref.getName());
    }
    names.sort(null);
    StringBuilder out = new StringBuilder();
    for (String name : names) out.append(name).append('\n');
    return utf8(out);
  }

  /** symbolic-ref --short [-q] HEAD. HEAD 가 기호 참조가 아니면 -q 일 때 종료 코드 1. */
  private static byte[] symbolicRef(Repository repo, List<String> command) throws Exception {
    List<String> flags = command.subList(1, command.size());
    boolean quiet = flags.contains("-q");
    if (!flags.contains("--short") || !flags.get(flags.size() - 1).equals(Constants.HEAD) || flags.size() != (quiet ? 3 : 2)) return null;
    Ref head = repo.exactRef(Constants.HEAD);
    if (head == null) return null;
    if (!head.isSymbolic()) {
      if (quiet) throw new GitFailure("종료 코드 1");
      return null;
    }
    String name = shortName(repo, head.getTarget().getName());
    return name == null ? null : (name + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** cat-file blob 전체해시. */
  private static byte[] catFile(Repository repo, List<String> command) throws IOException {
    if (command.size() != 3 || !command.get(1).equals(Constants.TYPE_BLOB) || !FULL_HASH.matcher(command.get(2)).matches()) return null;
    return blob(repo, ObjectId.fromString(command.get(2)));
  }

  /** show 리비전:경로. 경로가 ./ 로 시작하면 dir 기준. 파일(일반 · 실행 · 링크)만 맡는다. */
  private static byte[] show(Repository repo, File dir, List<String> command) throws IOException {
    if (command.size() != 2) return null;
    String arg = command.get(1);
    int colon = arg.indexOf(':');
    if (arg.startsWith("-") || colon <= 0) return null;
    String path = arg.substring(colon + 1);
    if (path.startsWith(CURRENT_DIR)) {
      String prefix = prefix(repo, dir);
      if (prefix == null) return null;
      path = prefix + path.substring(CURRENT_DIR.length());
    }
    if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")) return null;
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals(".") || part.equals(PARENT_DIR)) return null;
    }
    ObjectId id = resolve(repo, arg.substring(0, colon));
    if (id == null) return null;
    try (RevWalk walk = new RevWalk(repo); TreeWalk tree = TreeWalk.forPath(repo, path, walk.parseTree(id))) {
      if (tree == null) return null;
      FileMode mode = tree.getFileMode(0);
      if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE && mode != FileMode.SYMLINK) return null;
      return blob(repo, tree.getObjectId(0));
    }
  }

  /** ls-tree -r --name-only 리비전(저장소 맨 위에서). 따옴표가 붙을 경로(제어 글자 · " · \)가 있으면 git 에 맡긴다. */
  private static byte[] lsTree(Repository repo, File dir, List<String> command) throws IOException {
    if (command.size() != 4 || !command.get(1).equals("-r") || !command.get(2).equals("--name-only") || !"".equals(prefix(repo, dir))) return null;
    ObjectId id = resolve(repo, command.get(3));
    if (id == null) return null;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (RevWalk walk = new RevWalk(repo); TreeWalk tree = new TreeWalk(repo)) {
      tree.addTree(walk.parseTree(id));
      tree.setRecursive(true);
      while (tree.next()) {
        byte[] path = tree.getRawPath();
        for (byte b : path) {
          if ((b >= 0 && b < ' ') || b == 0x7f || b == '"' || b == '\\') return null;
        }
        out.write(path);
        out.write('\n');
      }
    }
    return out.toByteArray();
  }

  private static byte[] blob(Repository repo, ObjectId id) throws IOException {
    try (ObjectStream stream = repo.open(id, Constants.OBJ_BLOB).openStream()) {
      return stream.readAllBytes();
    }
  }

  /** dir 의 작업본 안 위치(맨 위면 "", 아니면 「a/b/」). 작업본 밖이면 null. */
  private static String prefix(Repository repo, File dir) throws IOException {
    String top = repo.getWorkTree().getCanonicalPath();
    String here = dir.getCanonicalPath();
    if (here.equals(top)) return "";
    if (!here.startsWith(top + File.separator)) return null;
    return here.substring(top.length() + 1).replace(File.separatorChar, '/') + "/";
  }

  /** git 이 내는 경로 모양: 실제 경로, 구분은 /. */
  private static String gitPath(File file) throws IOException {
    return file.getCanonicalPath().replace(File.separatorChar, '/');
  }

  private static byte[] utf8(CharSequence text) {
    return text.toString().getBytes(StandardCharsets.UTF_8);
  }
}
