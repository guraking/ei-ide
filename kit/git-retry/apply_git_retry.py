#!/usr/bin/env python3
"""IntelliJ Git(git4idea)의 서버 명령(Clone · Fetch · Pull · Push · ls-remote)이 libcurl-4.dll 을 읽지 못해 실패하면 쉬었다가 다시 하게 한다.

사용법: python kit\\git-retry\\apply_git_retry.py <intellij-community 폴더>

회사 PC 보안 프로그램(응용 프로그램 제어)이 git-remote-https 의 libcurl-4.dll 읽기를 가끔 막는다(LoadLibraryExW() failed).
서버에 닿기 전에 끝난 실패라 다시 해도 안전하다. GitImplBase 의 「인증 실패면 다시」 반복(명령마다 새 handler 를 만드는 곳)에
1 · 2 · 3초(+0~1초, 여러 저장소가 함께 다시 하다 또 막히지 않게)를 쉬고 세 번까지 다시 하는 줄을 더한다.
서버 명령만 다시 하고(커밋 · 병합 · rebase 는 안 함), 쉬는 동안에도 0.1초마다 취소를 본다.
앞 판 손질(모든 명령을 다시 하던 것)이 남아 있으면 먼저 원문으로 되돌리고 고친다.
kinggura-sync 의 SyncPanel.runBytes 가 우리 창의 git 에 하는 것과 같은 규칙이다.

고칠 원문을 정확히 한 번 찾지 못하면 아무 파일도 바꾸지 않는다. 고치기 전 원본은
<intellij-community>/../backup/<시각>-before-gitretry/ 에 남긴다. 두 번 실행해도 같은 결과가 된다.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import patchlib  # noqa: E402

TARGET = "plugins/git4idea/backend/src/commands/GitImplBase.java"
LOG_LINE = "  private static final Logger LOG = Logger.getInstance(GitImplBase.class);\n"
CONSTANTS = LOG_LINE + (
    "  // Moa: git 이 DLL 을 읽지 못했을 때 내는 문구(윈도우 git: compat/win32 · run-command)와 다시 하는 횟수 · 쉬는 시간 · 쉬는 동안 취소를 보는 간격.\n"
    "  private static final java.util.List<String> EI_LIBRARY_LOAD_ERRORS = java.util.List.of(\"LoadLibraryExW\", \"failed to load library\");\n"
    "  private static final int EI_LIBRARY_RETRIES = 3;\n"
    "  private static final long EI_LIBRARY_RETRY_DELAY_MS = 1000;\n"
    "  private static final long EI_CANCEL_CHECK_MS = 100;\n"
    "  // Moa: 다시 하는 명령. 서버에 닿기 전에 끝난 실패라 다시 해도 안전한 서버 명령만(커밋 · 병합 · rebase 처럼 저장소를 바꾸는 명령은 안 한다).\n"
    "  private static final java.util.Set<String> EI_RETRY_COMMANDS = java.util.Set.of(\n"
    "    GitCommand.CLONE.name(), GitCommand.FETCH.name(), GitCommand.PULL.name(), GitCommand.PUSH.name(), GitCommand.LS_REMOTE.name());\n")
RUN_LINE = "      result = run(handler, outputCollector);\n"
RETRY = RUN_LINE + (
    "      // Moa: 보안 프로그램이 libcurl-4.dll 읽기를 잠깐 막은 서버 명령 실패는 쉬었다가 새 handler 로 다시 한다(handler 는 한 번만 돈다).\n"
    "      for (int eiAttempt = 1; eiAttempt <= EI_LIBRARY_RETRIES && eiLibraryLoadFailed(handler, result); eiAttempt++) {\n"
    "        LOG.warn(\"git 이 라이브러리를 읽지 못해 다시 실행(\" + eiAttempt + \"/\" + EI_LIBRARY_RETRIES + \"): \" + result.getErrorOutputAsJoinedString());\n"
    "        long eiWait = EI_LIBRARY_RETRY_DELAY_MS * eiAttempt + (long)(Math.random() * EI_LIBRARY_RETRY_DELAY_MS);\n"
    "        // 쉬는 동안에도 취소를 EI_CANCEL_CHECK_MS 마다 본다.\n"
    "        for (long eiSlept = 0; eiSlept < eiWait; eiSlept += EI_CANCEL_CHECK_MS) {\n"
    "          com.intellij.openapi.progress.ProgressManager.checkCanceled();\n"
    "          try {\n"
    "            Thread.sleep(Math.min(EI_CANCEL_CHECK_MS, eiWait - eiSlept));\n"
    "          }\n"
    "          catch (InterruptedException e) {\n"
    "            Thread.currentThread().interrupt();\n"
    "            throw new ProcessCanceledException(e);\n"
    "          }\n"
    "        }\n"
    "        com.intellij.openapi.progress.ProgressManager.checkCanceled();\n"
    "        handler = handlerConstructor.compute();\n"
    "        result = run(handler, outputCollectorConstructor.compute());\n"
    "      }\n")
HELPER_ANCHOR = "  /**\n   * Run handler with per-project locking, logging and authentication\n   */\n"
HELPER = (
    "  /** Moa: 다시 하는 서버 명령(EI_RETRY_COMMANDS)이고, 실패한 결과의 오류 줄에 DLL 읽기 실패 문구가 있는가. */\n"
    "  private static boolean eiLibraryLoadFailed(@NotNull GitLineHandler handler, @NotNull GitCommandResult result) {\n"
    "    if (result.success() || !EI_RETRY_COMMANDS.contains(handler.getCommand().name())) return false;\n"
    "    String error = result.getErrorOutputAsJoinedString();\n"
    "    for (String part : EI_LIBRARY_LOAD_ERRORS) {\n"
    "      if (error.contains(part)) return true;\n"
    "    }\n"
    "    return false;\n"
    "  }\n\n") + HELPER_ANCHOR
EDITS = [(TARGET, LOG_LINE, CONSTANTS), (TARGET, RUN_LINE, RETRY), (TARGET, HELPER_ANCHOR, HELPER)]

# 앞 판 손질(모든 git 명령을 다시 하고, 한 번에 쉬던 것). get-source.bat 은 이미 받은 소스를 다시 받지 않으므로 먼저 원문으로 되돌린다.
OLD_CONSTANTS = LOG_LINE + (
    "  // Moa: git 이 DLL 을 읽지 못했을 때 내는 문구(윈도우 git: compat/win32 · run-command)와 다시 하는 횟수 · 쉬는 시간.\n"
    "  private static final java.util.List<String> EI_LIBRARY_LOAD_ERRORS = java.util.List.of(\"LoadLibraryExW\", \"failed to load library\");\n"
    "  private static final int EI_LIBRARY_RETRIES = 3;\n"
    "  private static final long EI_LIBRARY_RETRY_DELAY_MS = 1000;\n")
OLD_RETRY = RUN_LINE + (
    "      // Moa: 보안 프로그램이 libcurl-4.dll 읽기를 잠깐 막은 실패는 쉬었다가 새 handler 로 다시 한다(handler 는 한 번만 돈다).\n"
    "      for (int eiAttempt = 1; eiAttempt <= EI_LIBRARY_RETRIES && eiLibraryLoadFailed(result); eiAttempt++) {\n"
    "        LOG.warn(\"git 이 라이브러리를 읽지 못해 다시 실행(\" + eiAttempt + \"/\" + EI_LIBRARY_RETRIES + \"): \" + result.getErrorOutputAsJoinedString());\n"
    "        try {\n"
    "          Thread.sleep(EI_LIBRARY_RETRY_DELAY_MS * eiAttempt + (long)(Math.random() * EI_LIBRARY_RETRY_DELAY_MS));\n"
    "        }\n"
    "        catch (InterruptedException e) {\n"
    "          Thread.currentThread().interrupt();\n"
    "          throw new ProcessCanceledException(e);\n"
    "        }\n"
    "        com.intellij.openapi.progress.ProgressManager.checkCanceled();\n"
    "        handler = handlerConstructor.compute();\n"
    "        result = run(handler, outputCollectorConstructor.compute());\n"
    "      }\n")
OLD_HELPER = (
    "  /** Moa: 실패한 결과의 오류 줄에 DLL 읽기 실패 문구가 있는가. */\n"
    "  private static boolean eiLibraryLoadFailed(@NotNull GitCommandResult result) {\n"
    "    if (result.success()) return false;\n"
    "    String error = result.getErrorOutputAsJoinedString();\n"
    "    for (String part : EI_LIBRARY_LOAD_ERRORS) {\n"
    "      if (error.contains(part)) return true;\n"
    "    }\n"
    "    return false;\n"
    "  }\n\n") + HELPER_ANCHOR
OLD_EDITS = [(OLD_CONSTANTS, LOG_LINE), (OLD_RETRY, RUN_LINE), (OLD_HELPER, HELPER_ANCHOR)]


def prepare(rel, text):
    """앞 판 손질이 있으면 원문으로 되돌린다."""
    if rel != TARGET:
        return text
    for old, original in OLD_EDITS:
        if old in text:
            print(f"옛 수정 되돌림: {rel}")
            text = text.replace(old, original)
    return text


def patch(text):
    """세 곳을 고친 글(앞 판 손질은 먼저 되돌림). 이미 모두 고쳐져 있으면 None. 원문이 정확히 한 번 있지 않으면 ValueError."""
    prepared = prepare(TARGET, text)
    changed = prepared != text
    text = prepared
    for _, old, new in EDITS:
        fixed = patchlib.edit(text, old, new)
        if fixed is not None:
            text, changed = fixed, True
    return text if changed else None


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    patchlib.apply_edits(Path(sys.argv[1]).resolve(), EDITS, tag="gitretry", prepare=prepare)


if __name__ == "__main__":
    main()
