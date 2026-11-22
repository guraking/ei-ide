#!/usr/bin/env python3
"""IDE 를 끌 때 자동 빌드(Build project automatically)를 오래 기다리지 않게 한다.

사용법: python kit\\exit-fast\\apply_exit_fast.py <intellij-community 폴더>

저장하면 도는 자동 빌드가 돌던 중에 IDE 를 끄면, IntelliJ BuildManager 가 그 빌드를 취소하고
  1) 프로젝트를 닫을 때(projectClosing) 빌드가 끝나길 15초까지,
  2) 앱을 정리할 때 자동 빌드 작업(runAutoMake)이 시간 제한 없이(IntelliJ 가 10초 뒤 포기)
기다려 끄기가 25초 넘게 늦었다(11-23 회사 로그). IDE 를 끄는 중일 때만
  1) 은 EI_EXIT_BUILD_WAIT_SECONDS 초까지, 2) 는 EI_AUTOMAKE_WAIT_STEP_MS 마다 보며 끄는 중이면 더 기다리지 않게 바꾼다.
끄는 중이 아닐 때(프로젝트만 닫기 · 평소 자동 빌드)는 원래대로다. 취소한 빌드는 IntelliJ 가 15초 뒤 넘어가던 것과 같이 남겨 두고 끈다.

고칠 원문을 정확히 한 번 찾지 못하면 아무 파일도 바꾸지 않는다. 고치기 전 원본은
<intellij-community>/../backup/<시각>-before-exitfast/ 에 남긴다. 두 번 실행해도 같은 결과가 된다.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import patchlib  # noqa: E402

TARGET = "java/compiler/impl/src/com/intellij/compiler/server/BuildManager.java"

LOG_LINE = "  private static final Logger LOG = Logger.getInstance(BuildManager.class);\n"
CONSTANTS = LOG_LINE + (
    "  // Moa: IDE 를 끄는 중일 때 취소한 빌드를 기다리는 시간, 자동 빌드 끝남을 다시 보는 간격.\n"
    "  private static final int EI_EXIT_BUILD_WAIT_SECONDS = 1;\n"
    "  private static final long EI_AUTOMAKE_WAIT_STEP_MS = 500;\n")

CLOSE_WAIT = "              currentBuild.waitFor(15, TimeUnit.SECONDS);\n"
CLOSE_WAIT_NEW = (
    "              // Moa: IDE 를 끄는 중이면 취소한 빌드를 오래 기다리지 않는다.\n"
    "              currentBuild.waitFor(ApplicationManager.getApplication().isExitInProgress() ? EI_EXIT_BUILD_WAIT_SECONDS : 15, TimeUnit.SECONDS);\n")

AUTOMAKE_WAIT = "        pair.first.waitFor();\n"
AUTOMAKE_WAIT_NEW = (
    "        // Moa: IDE 를 끄는 중이면 자동 빌드를 더 기다리지 않는다(시간 제한 없이 기다리면 앱 정리가 10초 늦어진다).\n"
    "        while (!pair.first.waitFor(EI_AUTOMAKE_WAIT_STEP_MS, TimeUnit.MILLISECONDS)) {\n"
    "          if (ApplicationManager.getApplication().isExitInProgress()) break;\n"
    "        }\n")

EDITS = [(TARGET, LOG_LINE, CONSTANTS), (TARGET, CLOSE_WAIT, CLOSE_WAIT_NEW), (TARGET, AUTOMAKE_WAIT, AUTOMAKE_WAIT_NEW)]


def patch(text):
    """세 곳을 고친 글. 이미 모두 고쳐져 있으면 None. 원문이 정확히 한 번 있지 않으면 ValueError."""
    changed = False
    for _, old, new in EDITS:
        fixed = patchlib.edit(text, old, new)
        if fixed is not None:
            text, changed = fixed, True
    return text if changed else None


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    patchlib.apply_edits(Path(sys.argv[1]).resolve(), EDITS, tag="exitfast")


if __name__ == "__main__":
    main()
