#!/usr/bin/env python3
"""IntelliJ Git(git4idea)이 변경 목록(Commit 창 Changes)을 새로 읽을 때 저장소마다의 git status 를 3개씩 동시에 돌리게 한다.

사용법: python kit\\git-status\\apply_git_status.py <intellij-community 폴더>

GitChangeProvider.getChanges 는 고칠 저장소마다 GitStagingAreaHolder.refresh(git status)를 차례로 돌린다.
회사 PC 보안 프로그램 아래에서는 git 하나 띄우는 데 0.5~1초가 걸려, 저장소 9개를 다시 읽으면 그만큼 줄 서서 기다린다.
status 만 EI_STATUS_SLOTS 개씩 동시에 먼저 돌려 두고, 변경 목록을 만드는 반복은 원래 순서 · 원래 코드 그대로 그 결과를 쓴다.
git 명령 · 인자 · 결과 해석은 바꾸지 않는다. 실패하면 원래처럼 dirtyPaths 순서의 첫 실패를 던지고 남은 status 는 취소한다.
저장소별 · 전체 시간은 kinggura-sync 의 git 시간 로그(#kinggura.sync.time, 디버그)에 같이 남는다.

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
EDITS = [(TARGET, LOG_LINE, CONSTANTS), (TARGET, LOOP_LINE, LOOP), (TARGET, REFRESH_LINES, REFRESH),
         (TARGET, HELPER_ANCHOR, HELPER)]


def patch(text):
    """네 곳을 고친 글. 이미 모두 고쳐져 있으면 None. 원문이 정확히 한 번 있지 않으면 ValueError."""
    changed = False
    for _, old, new in EDITS:
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
