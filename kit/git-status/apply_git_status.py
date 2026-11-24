#!/usr/bin/env python3
"""IntelliJ 가 Git 저장소를 다루는 두 곳을 3개씩 동시에 돌게 한다: 변경 목록(Commit 창 Changes)의 git status, 켤 때 저장소 등록.

사용법: python kit\\git-status\\apply_git_status.py <intellij-community 폴더>

GitChangeProvider.getChanges 는 고칠 저장소마다 GitStagingAreaHolder.refresh(git status)를 차례로 돌린다.
회사 PC 보안 프로그램 아래에서는 git 하나 띄우는 데 0.5~1초가 걸려, 저장소 9개를 다시 읽으면 그만큼 줄 서서 기다린다.
status 만 EI_STATUS_SLOTS 개씩 동시에 먼저 돌려 두고, 변경 목록을 만드는 반복은 원래 순서 · 원래 코드 그대로 그 결과를 쓴다.
git 명령 · 인자 · 결과 해석은 바꾸지 않는다. 실패하면 원래처럼 dirtyPaths 순서의 첫 실패를 던지고 남은 status 는 취소한다.
저장소별 · 전체 시간은 kinggura-sync 의 git 시간 로그(#kinggura.sync.time, 디버그)에 같이 남는다.

VcsRepositoryManager.findNewRoots 는 새 저장소를 하나씩 만든다. Git 저장소 하나를 만들 때 git 을 네 번 차례로 띄워
(config -l · for-each-ref · reflog · worktree list) 회사 PC 에서 저장소 하나에 약 3초, 9개면 27초 동안 Commit 창이 기다렸다(11-20 회사 로그).
새 저장소가 둘 이상이면 EI_CREATE_SLOTS 개씩 동시에 만든다. 화면 스레드에서 불렸거나 읽기 잠금을 쥔 채 불렸으면
(기다리는 동안 쓰기 작업과 서로 막힐 수 있다) 원래대로 하나씩 만든다. 만들지 못한 root 를 빼는 것 · 예외는 원래와 같다.

GitRepositoryImpl 은 저장소 하나를 만들 때 worktree 목록(git worktree list)과 최근 브랜치(git reflog)를 다 읽을 때까지 등록을 막았다.
둘 다 뒤에서 읽고, 다 읽으면 화면(workingTreesLoaded · repositoryUpdated)에 알린다. 브랜치 목록은 apply_defaults 39 가 파일에서 읽게 한다.
GitUserRegistry 는 저장소마다 user.name · user.email 을 git 두 번으로 읽었다. 한 번(git config --get-regexp)으로 읽는다.

고칠 원문을 정확히 한 번 찾지 못하면 아무 파일도 바꾸지 않는다. 고치기 전 원본은
<intellij-community>/../backup/<시각>-before-gitstatus/ 에 남긴다. 두 번 실행해도 같은 결과가 된다.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import patchlib  # noqa: E402

TARGET = "plugins/git4idea/backend/src/status/GitChangeProvider.java"
LOG_LINE = '  static final Logger LOG = Logger.getInstance("#GitStatus");\n'
CONSTANTS = LOG_LINE + (
    "  // Moa: 저장소마다의 git status 를 동시에 돌릴 수. kinggura-sync 동기화 창도 같은 수(SyncPanel.REPO_SLOTS)로 status 를 동시에 돌린다.\n"
    "  private static final int EI_STATUS_SLOTS = 3;\n"
    "  private static final java.util.concurrent.ExecutorService EI_STATUS_POOL =\n"
    "    com.intellij.util.concurrency.AppExecutorUtil.createBoundedApplicationPoolExecutor(\"Moa Git status\", EI_STATUS_SLOTS);\n"
    "  // Moa: status 를 기다리는 동안 취소를 보는 간격.\n"
    "  private static final long EI_CANCEL_CHECK_MS = 100;\n"
    "  // Moa: kinggura-sync 의 git 시간 로그(SyncPanel.TIME_LOG)와 같은 분류라 같은 디버그 설정으로 함께 켜진다.\n"
    "  private static final Logger EI_TIME_LOG = Logger.getInstance(\"#kinggura.sync.time\");\n")
LOOP_LINE = "      for (Map.Entry<VirtualFile, RootDirtySet> entry : dirtyPaths.entrySet()) {\n"
LOOP = (
    "      // Moa: 저장소마다의 git status 를 먼저 EI_STATUS_SLOTS 개씩 동시에 돌려 두고, 아래 반복은 그 결과로 원래 순서대로 목록을 만든다.\n"
    "      Map<VirtualFile, EiStatus> eiStatuses = eiRefreshAll(dirtyPaths, repositoryManager, progress);\n") + LOOP_LINE
REFRESH_LINES = (
    "        boolean wasEmptyStaging = stageAreaHolder.isEmpty();\n"
    "        List<GitFileStatus> newChanges = stageAreaHolder.refresh(rootDirtyPaths);\n")
REFRESH = (
    "        // Moa: 앞에서 미리 돌린 결과. 그사이 새로 잡힌 저장소처럼 결과가 없으면 원래대로 여기서 돌린다.\n"
    "        EiStatus eiStatus = eiStatuses.get(root);\n"
    "        boolean wasEmptyStaging = eiStatus != null ? eiStatus.wasEmptyStaging() : stageAreaHolder.isEmpty();\n"
    "        List<GitFileStatus> newChanges = eiStatus != null ? eiStatus.changes() : stageAreaHolder.refresh(rootDirtyPaths);\n")
HELPER_ANCHOR = "  private static void updateDirtyPathsForSubmodule(@NotNull GitSubmodule submodule,\n"
HELPER = (
    "  /** Moa: 한 저장소의 status 결과와, 그 status 전에 스테이징 기록이 비어 있었는지(하위 모듈 처리에 쓴다). */\n"
    "  private record EiStatus(boolean wasEmptyStaging, @NotNull List<GitFileStatus> changes) {\n"
    "  }\n"
    "\n"
    "  /**\n"
    "   * Moa: dirtyPaths 의 저장소마다 GitStagingAreaHolder.refresh(git status)를 EI_STATUS_SLOTS 개씩 동시에 돌린 결과(root → 결과).\n"
    "   * 저장소를 찾지 못한 root 는 빠진다. dirtyPaths 순서로 결과를 받아, 첫 실패의 예외를 그대로 던진다.\n"
    "   * 실패 · 취소로 빠져나가면 아직 돌지 않은 status 는 버리고 도는 것은 취소한다(각자의 진행 표시를 취소해 git 을 멈춘다).\n"
    "   */\n"
    "  private static @NotNull Map<VirtualFile, EiStatus> eiRefreshAll(@NotNull Map<VirtualFile, RootDirtySet> dirtyPaths,\n"
    "                                                                 @NotNull GitRepositoryManager repositoryManager,\n"
    "                                                                 @NotNull ProgressIndicator progress) throws VcsException {\n"
    "    long started = System.nanoTime();\n"
    "    Map<VirtualFile, java.util.concurrent.Future<EiStatus>> futures = new java.util.LinkedHashMap<>();\n"
    "    List<ProgressIndicator> indicators = new ArrayList<>();\n"
    "    Map<VirtualFile, EiStatus> statuses = new HashMap<>();\n"
    "    boolean done = false;\n"
    "    try {\n"
    "      for (Map.Entry<VirtualFile, RootDirtySet> dirty : dirtyPaths.entrySet()) {\n"
    "        GitRepository repo = repositoryManager.getRepositoryForRoot(dirty.getKey());\n"
    "        if (repo == null) continue;\n"
    "        RootDirtySet paths = dirty.getValue();\n"
    "        // 부모 진행 표시가 취소되면 이것도 취소로 보인다. 이것만 따로 취소해도 부모는 그대로다.\n"
    "        ProgressIndicator indicator = new com.intellij.concurrency.SensitiveProgressWrapper(progress);\n"
    "        indicators.add(indicator);\n"
    "        futures.put(dirty.getKey(), EI_STATUS_POOL.submit(() -> eiRefresh(repo, paths, indicator)));\n"
    "      }\n"
    "      for (Map.Entry<VirtualFile, java.util.concurrent.Future<EiStatus>> entry : futures.entrySet()) {\n"
    "        statuses.put(entry.getKey(), eiAwait(entry.getValue(), progress));\n"
    "      }\n"
    "      done = true;\n"
    "    }\n"
    "    finally {\n"
    "      if (!done) {\n"
    "        for (java.util.concurrent.Future<EiStatus> future : futures.values()) future.cancel(false);\n"
    "        for (ProgressIndicator indicator : indicators) indicator.cancel();\n"
    "      }\n"
    "    }\n"
    "    if (EI_TIME_LOG.isDebugEnabled()) {\n"
    "      EI_TIME_LOG.debug(\"IntelliJ 변경 목록 status 전체 \" + (System.nanoTime() - started) / 1_000_000 + \" ms, 리포지토리 \" + futures.size() + \"개\");\n"
    "    }\n"
    "    return statuses;\n"
    "  }\n"
    "\n"
    "  /** Moa: indicator 아래에서 한 저장소의 status 를 돌린다(EI_STATUS_POOL 스레드에서 부른다). */\n"
    "  private static @NotNull EiStatus eiRefresh(@NotNull GitRepository repo, @NotNull RootDirtySet paths,\n"
    "                                           @NotNull ProgressIndicator indicator) throws VcsException {\n"
    "    com.intellij.openapi.util.Ref<EiStatus> result = new com.intellij.openapi.util.Ref<>();\n"
    "    com.intellij.openapi.util.Ref<VcsException> failure = new com.intellij.openapi.util.Ref<>();\n"
    "    com.intellij.openapi.progress.ProgressManager.getInstance().executeProcessUnderProgress(() -> {\n"
    "      long started = System.nanoTime();\n"
    "      GitStagingAreaHolder holder = repo.getStagingAreaHolder();\n"
    "      boolean wasEmpty = holder.isEmpty();\n"
    "      try {\n"
    "        result.set(new EiStatus(wasEmpty, holder.refresh(paths)));\n"
    "      }\n"
    "      catch (VcsException e) {\n"
    "        failure.set(e);\n"
    "      }\n"
    "      if (EI_TIME_LOG.isDebugEnabled()) {\n"
    "        EI_TIME_LOG.debug(\"[\" + repo.getRoot().getName() + \"] IntelliJ 변경 목록 status \" + (System.nanoTime() - started) / 1_000_000 + \" ms\");\n"
    "      }\n"
    "    }, indicator);\n"
    "    if (!failure.isNull()) throw failure.get();\n"
    "    return result.get();\n"
    "  }\n"
    "\n"
    "  /** Moa: future 의 결과. 기다리는 동안 progress 취소를 EI_CANCEL_CHECK_MS 마다 본다. 작업이 던진 예외는 그대로 다시 던진다. */\n"
    "  private static @NotNull EiStatus eiAwait(@NotNull java.util.concurrent.Future<EiStatus> future,\n"
    "                                         @NotNull ProgressIndicator progress) throws VcsException {\n"
    "    while (true) {\n"
    "      progress.checkCanceled();\n"
    "      try {\n"
    "        return future.get(EI_CANCEL_CHECK_MS, java.util.concurrent.TimeUnit.MILLISECONDS);\n"
    "      }\n"
    "      catch (java.util.concurrent.TimeoutException ignored) {\n"
    "        // 아직 도는 중: 취소를 보고 다시 기다린다.\n"
    "      }\n"
    "      catch (InterruptedException e) {\n"
    "        Thread.currentThread().interrupt();\n"
    "        throw new com.intellij.openapi.progress.ProcessCanceledException(e);\n"
    "      }\n"
    "      catch (java.util.concurrent.ExecutionException e) {\n"
    "        Throwable cause = e.getCause();\n"
    "        if (cause instanceof VcsException vcs) throw vcs;\n"
    "        if (cause instanceof RuntimeException runtime) throw runtime;\n"
    "        if (cause instanceof Error error) throw error;\n"
    "        throw new VcsException(cause);\n"
    "      }\n"
    "    }\n"
    "  }\n"
    "\n") + HELPER_ANCHOR
REPO_TARGET = "platform/dvcs-impl/src/com/intellij/dvcs/repo/VcsRepositoryManager.kt"
REPO_DOC_LINE = "/**\n * VcsRepositoryManager creates, stores and updates all repository's information using registered [VcsRepositoryCreator]\n"
REPO_CONSTANTS = (
    "// Moa: 새 저장소를 동시에 만들 수. Commit 창 status(GitChangeProvider) · kinggura-sync 동기화 창과 같은 수다.\n"
    "private const val EI_CREATE_SLOTS = 3\n"
    "private val EI_CREATE_POOL =\n"
    "  com.intellij.util.concurrency.AppExecutorUtil.createBoundedApplicationPoolExecutor(\"Moa VCS repository\", EI_CREATE_SLOTS)\n"
    "// Moa: 만들기를 기다리는 동안 취소를 보는 간격.\n"
    "private const val EI_CANCEL_CHECK_MS = 100L\n"
    "// Moa: kinggura-sync 의 git 시간 로그(SyncPanel.TIME_LOG)와 같은 분류라 같은 디버그 설정으로 함께 켜진다.\n"
    "private val EI_TIME_LOG = com.intellij.openapi.diagnostic.Logger.getInstance(\"#kinggura.sync.time\")\n"
    "// Moa: 이 스레드가 eiCreateAll 의 저장소 만들기 중인지. 만들기 안에서 다시 저장소 목록을 고치려 하면 원래(같은 스레드가 MODIFY_LOCK 을 쥔 경우)처럼\n"
    "// 오류를 남기고 돌아간다. 기다리면 MODIFY_LOCK 을 쥐고 이 만들기를 기다리는 스레드와 서로 막힌다.\n"
    "private val EI_CREATING: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }\n"
    "\n") + REPO_DOC_LINE
FIND_NEW_ROOTS = (
    "  private fun findNewRoots(knownRoots: Set<VirtualFile>): Map<VirtualFile, Repository> {\n"
    "    val newRootsMap = HashMap<VirtualFile, Repository>()\n")
FIND_NEW_ROOTS_PARALLEL = FIND_NEW_ROOTS + (
    "    // Moa: 새 저장소가 둘 이상이면 EI_CREATE_SLOTS 개씩 동시에 만든다(Git 저장소 하나에 git 을 네 번 차례로 띄운다).\n"
    "    // 화면 스레드나 읽기 잠금을 쥔 채 불렸으면 기다리는 동안 쓰기 작업과 서로 막힐 수 있어 아래 원래대로 하나씩 만든다.\n"
    "    val eiApp = ApplicationManager.getApplication()\n"
    "    val eiRoots = vcsManager.getAllVcsRoots().filter { it.vcs != null && !knownRoots.contains(it.path) }\n"
    "    if (eiRoots.size > 1 && !eiApp.isDispatchThread && !eiApp.isReadAccessAllowed) {\n"
    "      return eiCreateAll(eiRoots)\n"
    "    }\n")
RECURSION_CHECK = "    if (MODIFY_LOCK.isHeldByCurrentThread) {\n"
RECURSION_CHECK_POOL = (
    "    // Moa: eiCreateAll 의 만들기 스레드도 MODIFY_LOCK 을 쥔 스레드 안에서 도는 것과 같다.\n"
    "    if (MODIFY_LOCK.isHeldByCurrentThread || EI_CREATING.get()) {\n")
INVALID_ROOTS = "  private fun findInvalidRoots(repositories: Collection<Repository>): Collection<VirtualFile> {\n"
REPO_HELPERS = (
    "  /**\n"
    "   * Moa: roots 의 저장소를 EI_CREATE_SLOTS 개씩 동시에 만든 결과(root → 저장소). 만들지 못한 root 는 빠진다(findNewRoots 와 같다).\n"
    "   * 만들기마다 지금 진행 표시(runUnderDisposeAwareIndicator)를 따르는 진행 표시 아래에서 돈다.\n"
    "   * roots 순서로 결과를 받아 첫 예외를 그대로 던진다. 실패 · 취소로 빠져나가면 아직 돌지 않은 만들기는 버리고 도는 것은 취소한다.\n"
    "   */\n"
    "  private fun eiCreateAll(roots: List<com.intellij.openapi.vcs.VcsRoot>): Map<VirtualFile, Repository> {\n"
    "    val started = System.nanoTime()\n"
    "    val parent = com.intellij.openapi.progress.ProgressManager.getGlobalProgressIndicator()\n"
    "                 ?: com.intellij.openapi.progress.EmptyProgressIndicator()\n"
    "    val futures = LinkedHashMap<VirtualFile, java.util.concurrent.Future<Repository?>>()\n"
    "    val indicators = ArrayList<com.intellij.openapi.progress.ProgressIndicator>()\n"
    "    val newRootsMap = HashMap<VirtualFile, Repository>()\n"
    "    var done = false\n"
    "    try {\n"
    "      for (root in roots) {\n"
    "        val vcs = root.vcs ?: continue\n"
    "        val rootPath = root.path\n"
    "        // 부모 진행 표시가 취소되면 이것도 취소로 보인다. 이것만 따로 취소해도 부모는 그대로다.\n"
    "        val indicator = com.intellij.concurrency.SensitiveProgressWrapper(parent)\n"
    "        indicators.add(indicator)\n"
    "        futures.put(rootPath, EI_CREATE_POOL.submit(java.util.concurrent.Callable {\n"
    "          com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(com.intellij.openapi.util.Computable {\n"
    "            val createStarted = System.nanoTime()\n"
    "            EI_CREATING.set(true)\n"
    "            val repository = try {\n"
    "              tryCreateRepository(project = project, vcs = vcs, rootPath = rootPath, disposable = this)\n"
    "            }\n"
    "            finally {\n"
    "              EI_CREATING.set(false)\n"
    "            }\n"
    "            if (EI_TIME_LOG.isDebugEnabled) {\n"
    "              EI_TIME_LOG.debug(\"[${rootPath.name}] IntelliJ 저장소 등록 ${(System.nanoTime() - createStarted) / 1_000_000} ms\")\n"
    "            }\n"
    "            repository\n"
    "          }, indicator)\n"
    "        }))\n"
    "      }\n"
    "      for ((rootPath, future) in futures) {\n"
    "        val repository = eiAwait(future, parent) ?: continue\n"
    "        newRootsMap.put(rootPath, repository)\n"
    "      }\n"
    "      done = true\n"
    "    }\n"
    "    finally {\n"
    "      if (!done) {\n"
    "        for (future in futures.values) future.cancel(false)\n"
    "        for (indicator in indicators) indicator.cancel()\n"
    "      }\n"
    "    }\n"
    "    if (EI_TIME_LOG.isDebugEnabled) {\n"
    "      EI_TIME_LOG.debug(\"IntelliJ 저장소 등록 전체 ${(System.nanoTime() - started) / 1_000_000} ms, 리포지토리 ${futures.size}개\")\n"
    "    }\n"
    "    return newRootsMap\n"
    "  }\n"
    "\n"
    "  /** Moa: future 의 결과. 기다리는 동안 parent 취소를 EI_CANCEL_CHECK_MS 마다 본다. 만들기가 던진 예외는 그대로 다시 던진다. */\n"
    "  private fun eiAwait(future: java.util.concurrent.Future<Repository?>, parent: com.intellij.openapi.progress.ProgressIndicator): Repository? {\n"
    "    while (true) {\n"
    "      parent.checkCanceled()\n"
    "      try {\n"
    "        return future.get(EI_CANCEL_CHECK_MS, TimeUnit.MILLISECONDS)\n"
    "      }\n"
    "      catch (ignored: java.util.concurrent.TimeoutException) {\n"
    "        // 아직 도는 중: 취소를 보고 다시 기다린다.\n"
    "      }\n"
    "      catch (e: InterruptedException) {\n"
    "        Thread.currentThread().interrupt()\n"
    "        throw ProcessCanceledException(e)\n"
    "      }\n"
    "      catch (e: java.util.concurrent.ExecutionException) {\n"
    "        throw e.cause ?: e\n"
    "      }\n"
    "    }\n"
    "  }\n"
    "\n") + INVALID_ROOTS

GIT_REPO_TARGET = "plugins/git4idea/backend/src/repo/GitRepositoryImpl.kt"
GIT_REPO_IMPORT = "import kotlinx.coroutines.cancel\n"
GIT_REPO_IMPORTS = GIT_REPO_IMPORT + "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch\n"
RECENT_FIELD = "  private var recentCheckoutBranches = emptyList<GitLocalBranch>()\n"
RECENT_FIELD_SEQ = RECENT_FIELD + (
    "\n"
    "  // Moa: 뒤에서 읽는 최근 브랜치(reflog)의 차례 번호. 늦게 끝난 옛 읽기가 새 결과를 덮지 않게 한다.\n"
    "  private val eiRecentSeq = java.util.concurrent.atomic.AtomicLong()\n")
INIT_WORKTREES = (
    "    repoInfo = readRepoInfo(config)\n"
    "\n"
    "    runBlockingMaybeCancellable {\n"
    "      workingTreeHolder.updateState()\n"
    "    }\n")
INIT_WORKTREES_ASYNC = (
    "    repoInfo = readRepoInfo(config)\n"
    "\n"
    "    // Moa: worktree 목록(git worktree list)은 저장소 등록을 막지 않고 뒤에서 읽는다. 다 읽으면 workingTreesLoaded 로 화면에 알린다.\n"
    "    workingTreeHolder.scheduleReload()\n")
RECENT_READ = "      recentCheckoutBranches = collectRecentCheckoutBranches(project, root) { branch: GitLocalBranch -> localBranches.containsKey(branch) }\n"
RECENT_READ_ASYNC = (
    "      // Moa: 최근 브랜치(git reflog)는 여기서 기다리지 않는다. 저장소를 알린 뒤(createInstance) · 다시 읽은 뒤(update) eiReadRecentLater 가 뒤에서 읽는다.\n")
UPDATE_READ = "    repoInfo = readRepoInfo()\n"
UPDATE_READ_RECENT = UPDATE_READ + "    eiReadRecentLater()\n"
CREATED_TAGS = "        tagsHolder.scheduleReload()\n"
CREATED_TAGS_RECENT = CREATED_TAGS + "        eiReadRecentLater()\n"
RECENT_HELPER_ANCHOR = "  override fun dispose() {\n"
RECENT_HELPER = (
    "  /**\n"
    "   * Moa: 최근 브랜치(git reflog)를 뒤에서 읽어 recentCheckoutBranches 에 넣고, 바뀌었으면 repositoryUpdated 로 브랜치 창에 알린다.\n"
    "   * 저장소를 만들 때는 repositoryCreated 를 보낸 뒤, 정보를 다시 읽을 때는 repoInfo 를 바꾼 뒤 부른다. 다 읽기 전에는 지난 결과(처음엔 빈 목록)를 쓴다.\n"
    "   * 그사이 더 새 읽기가 시작됐으면 이 결과는 버린다. 저장소가 닫히면(coroutineScope 취소) 알리지 않는다.\n"
    "   */\n"
    "  private fun eiReadRecentLater() {\n"
    "    val eiSeq = eiRecentSeq.incrementAndGet()\n"
    "    val localBranches = repoInfo.localBranchesWithHashes\n"
    "    coroutineScope.launch(Dispatchers.IO) {\n"
    "      val recent = collectRecentCheckoutBranches(project, root) { branch: GitLocalBranch -> localBranches.containsKey(branch) }\n"
    "      if (eiRecentSeq.get() != eiSeq || recent == recentCheckoutBranches) return@launch\n"
    "      recentCheckoutBranches = recent\n"
    "      if (!project.isDisposed && !isDisposed) {\n"
    "        project.messageBus.syncPublisher(GitRepositoryFrontendSynchronizer.TOPIC).repositoryUpdated(this@GitRepositoryImpl)\n"
    "      }\n"
    "    }\n"
    "  }\n"
    "\n") + RECENT_HELPER_ANCHOR
USER_TARGET = "plugins/git4idea/backend/src/GitUserRegistry.java"
USER_READ = (
    "    String userName = StringUtil.nullize(GitConfigUtil.getValue(project, root, GitConfigUtil.USER_NAME));\n"
    "    String userEmail = StringUtil.notNullize(GitConfigUtil.getValue(project, root, GitConfigUtil.USER_EMAIL));\n")
USER_READ_ONCE = (
    "    // Moa: user.name · user.email 을 git config 한 번으로 읽는다(저장소마다 git 두 번 → 한 번).\n"
    "    Map<String, String> eiUser = eiReadUser(project, root);\n"
    "    String userName = StringUtil.nullize(eiUser.get(GitConfigUtil.USER_NAME));\n"
    "    String userEmail = StringUtil.notNullize(eiUser.get(GitConfigUtil.USER_EMAIL));\n")
USER_HELPER_ANCHOR = "  @Override\n  public void dispose() {\n"
USER_HELPER = (
    "  // Moa: eiReadUser 가 읽는 키. git 은 키 이름을 소문자로 내놓는다.\n"
    "  private static final String EI_USER_KEYS = \"^user\\\\.(name|email)$\";\n"
    "\n"
    "  /**\n"
    "   * Moa: root 에서 git config --null --get-regexp 로 읽은 user.name · user.email(키 → 값). 값이 여럿이면 git config --get 처럼 마지막 값이다.\n"
    "   * 둘 다 없으면(종료 코드 1) 빈 표. 그 밖의 실패는 GitConfigUtil.getValue 처럼 VcsException 을 던진다.\n"
    "   */\n"
    "  private static @NotNull Map<String, String> eiReadUser(@NotNull Project project, @NotNull VirtualFile root) throws VcsException {\n"
    "    git4idea.commands.GitLineHandler h = new git4idea.commands.GitLineHandler(project, root, git4idea.commands.GitCommand.CONFIG);\n"
    "    h.setEnableInteractiveCallbacks(false);\n"
    "    h.setSilent(true);\n"
    "    h.addParameters(\"--null\", \"--get-regexp\", EI_USER_KEYS);\n"
    "    String output = git4idea.commands.Git.getInstance().runCommand(h).getOutputOrThrow(1);\n"
    "    Map<String, String> values = new java.util.HashMap<>();\n"
    "    int start = 0;\n"
    "    int keyEnd;\n"
    "    // 한 항목은 「키\\n값\\0」 이다(GitConfigUtil.getValues 와 같은 해석).\n"
    "    while ((keyEnd = output.indexOf('\\n', start)) != -1) {\n"
    "      int valueEnd = output.indexOf('\\u0000', keyEnd + 1);\n"
    "      if (valueEnd == -1) break;\n"
    "      values.put(output.substring(start, keyEnd), output.substring(keyEnd + 1, valueEnd));\n"
    "      start = valueEnd + 1;\n"
    "    }\n"
    "    return values;\n"
    "  }\n"
    "\n") + USER_HELPER_ANCHOR

EDITS = [(TARGET, LOG_LINE, CONSTANTS), (TARGET, LOOP_LINE, LOOP), (TARGET, REFRESH_LINES, REFRESH),
         (TARGET, HELPER_ANCHOR, HELPER),
         (REPO_TARGET, REPO_DOC_LINE, REPO_CONSTANTS), (REPO_TARGET, FIND_NEW_ROOTS, FIND_NEW_ROOTS_PARALLEL),
         (REPO_TARGET, INVALID_ROOTS, REPO_HELPERS), (REPO_TARGET, RECURSION_CHECK, RECURSION_CHECK_POOL),
         (GIT_REPO_TARGET, GIT_REPO_IMPORT, GIT_REPO_IMPORTS), (GIT_REPO_TARGET, RECENT_FIELD, RECENT_FIELD_SEQ),
         (GIT_REPO_TARGET, INIT_WORKTREES, INIT_WORKTREES_ASYNC), (GIT_REPO_TARGET, RECENT_READ, RECENT_READ_ASYNC),
         (GIT_REPO_TARGET, UPDATE_READ, UPDATE_READ_RECENT), (GIT_REPO_TARGET, CREATED_TAGS, CREATED_TAGS_RECENT),
         (GIT_REPO_TARGET, RECENT_HELPER_ANCHOR, RECENT_HELPER),
         (USER_TARGET, USER_READ, USER_READ_ONCE), (USER_TARGET, USER_HELPER_ANCHOR, USER_HELPER)]


def patch(text, target=TARGET):
    """target 파일의 글을 고친 글. 이미 모두 고쳐져 있으면 None. 원문이 정확히 한 번 있지 않으면 ValueError."""
    changed = False
    for rel, old, new in EDITS:
        if rel != target:
            continue
        fixed = patchlib.edit(text, old, new)
        if fixed is not None:
            text, changed = fixed, True
    return text if changed else None


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    patchlib.apply_edits(Path(sys.argv[1]).resolve(), EDITS, tag="gitstatus")


if __name__ == "__main__":
    main()
