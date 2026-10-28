#!/usr/bin/env python3
"""IntelliJ Git(git4idea)이 https 원격 명령에 인증을 환경값으로 미리 넘기게 한다.

사용법: python kit\\git-auth\\apply_git_auth.py <intellij-community 폴더>

  - kit/git-auth/EiGitAuthEnv.java · EiGitAuthText.java 를 git4idea backend 의 git4idea.commands 에 넣는다.
  - GitHandlerAuthenticationManager.prepareHttpAuth 에서 askpass 를 등록한 바로 뒤에 EiGitAuthEnv.prepare 한 줄을 더한다.
고칠 원문을 정확히 한 번 찾지 못하면 아무 파일도 바꾸지 않는다. 고치기 전 원본은 <intellij-community>/../backup/<시각>-before-gitauth/ 에 남긴다.
두 번 실행해도 같은 결과가 된다.
"""
import shutil
import sys
import time
from pathlib import Path

KIT = Path(__file__).resolve().parent
COMMANDS = "plugins/git4idea/backend/src/commands"
NEW_FILES = ["EiGitAuthEnv.java", "EiGitAuthText.java"]
TARGET = COMMANDS + "/GitHandlerAuthenticationManager.java"
ANCHOR = "    myHandler.addCustomEnvironmentVariable(GitAskPassAppHandler.IJ_ASK_PASS_PORT_ENV, Integer.toString(port));\n"
HOOK = "    EiGitAuthEnv.prepare(myProject, myHandler, httpAuthenticator, () -> myHttpAuthFailed = true);\n"


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    root = Path(sys.argv[1]).resolve()
    target = root / TARGET
    if not target.is_file():
        raise SystemExit(f"아무 파일도 바꾸지 않았습니다. 파일이 없습니다: {target}")
    raw = target.read_bytes().decode("utf-8")
    newline = "\r\n" if "\r\n" in raw else "\n"
    text = raw.replace("\r\n", "\n")
    if HOOK not in text and text.count(ANCHOR) != 1:
        raise SystemExit(f"아무 파일도 바꾸지 않았습니다. 원문을 정확히 한 번 찾지 못했습니다({text.count(ANCHOR)}번): {TARGET}\n  {ANCHOR.strip()}")

    backup = root.parent / "backup" / (time.strftime("%Y%m%d-%H%M%S") + "-before-gitauth")
    for name in NEW_FILES:
        dest = root / COMMANDS / name
        if dest.exists():
            (backup / COMMANDS).mkdir(parents=True, exist_ok=True)
            shutil.copy2(dest, backup / COMMANDS / name)
        shutil.copyfile(KIT / name, dest)
        print(f"넣음: {COMMANDS}/{name}")

    if HOOK in text:
        print(f"이미 적용됨: {TARGET}")
        return
    (backup / COMMANDS).mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup / TARGET)
    target.write_bytes(text.replace(ANCHOR, ANCHOR + HOOK).replace("\n", newline).encode("utf-8"))
    print(f"적용: {TARGET}")


if __name__ == "__main__":
    main()
