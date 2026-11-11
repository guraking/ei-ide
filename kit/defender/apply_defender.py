#!/usr/bin/env python3
"""Microsoft Defender 알림의 「제외」 단추가 서명 없는 도우미 대본도 실행하게 한다.

사용법: python kit\\defender\\apply_defender.py <intellij-community 폴더>

IntelliJ 는 bin\\defender-exclusions.ps1 을 관리자 권한으로 돌리기 전에 Authenticode 서명이 'Valid' 인지 본다.
JetBrains 는 배포판에서만 이 대본에 서명하므로 소스로 빌드한 판은 늘 'NotSigned' 로 멈춘다.
원래 개발판(snapshot)에만 허용하던 'NotSigned' 를 모든 판에 허용한다. 'HashMismatch' 같은 다른 상태는 그대로 막는다.
대본은 설치 폴더(Program Files) 안에 있어 관리자만 바꿀 수 있고, 실행할 때 Windows 가 관리자 권한을 다시 묻는다.

고칠 원문을 정확히 한 번 찾지 못하면 아무 파일도 바꾸지 않는다. 고치기 전 원본은
<intellij-community>/../backup/<시각>-before-defender/ 에 남긴다. 두 번 실행해도 같은 결과가 된다.
"""
import shutil
import sys
import time
from pathlib import Path

TARGET = "platform/platform-impl/src/com/intellij/diagnostic/WindowsDefenderChecker.java"
ORIGINAL = '      if ("NotSigned".equals(status) && ApplicationInfo.getInstance().getBuild().isSnapshot()) {\n'
PATCHED = '      if ("NotSigned".equals(status)) {\n'


def patch(text):
    """원문을 고친 글을 돌려준다. 이미 고쳐져 있으면 None. 원문이 정확히 한 번 있지 않으면 ValueError."""
    if PATCHED in text:
        return None
    count = text.count(ORIGINAL)
    if count != 1:
        raise ValueError(f"원문을 정확히 한 번 찾지 못했습니다({count}번): {ORIGINAL.strip()}")
    return text.replace(ORIGINAL, PATCHED)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    root = Path(sys.argv[1]).resolve()
    target = root / TARGET
    if not target.is_file():
        raise SystemExit(f"아무 파일도 바꾸지 않았습니다. 파일이 없습니다: {target}")
    raw = target.read_bytes().decode("utf-8")
    newline = "\r\n" if "\r\n" in raw else "\n"
    try:
        fixed = patch(raw.replace("\r\n", "\n"))
    except ValueError as e:
        raise SystemExit(f"아무 파일도 바꾸지 않았습니다. {e}\n  {TARGET}")
    if fixed is None:
        print(f"이미 적용됨: {TARGET}")
        return

    backup = root.parent / "backup" / (time.strftime("%Y%m%d-%H%M%S") + "-before-defender")
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup / target.name)
    target.write_bytes(fixed.replace("\n", newline).encode("utf-8"))
    print(f"적용: {TARGET}")


if __name__ == "__main__":
    main()
