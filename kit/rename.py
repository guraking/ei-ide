#!/usr/bin/env python3
"""제품 이름을 바꾼다. customize.py brand 를 이미 적용한 소스에 쓴다.

사용법: python3 rename.py <intellij-community 폴더> "<지금 이름>" "<새 이름>"

바꾸는 것: 제품 이름(창 제목 · 정보 화면) · 설치 폴더 이름 · 설정 폴더 이름(영숫자만) · 설치 파일 이름(소문자 영숫자).
고치기 전 원본은 <intellij-community>/../backup/<시각>-before/ 에 같은 상대경로로 남긴다. 같은 인자로 두 번 돌리면 아무것도 바꾸지 않는다.
"""
import re
import shutil
import sys
import time
from pathlib import Path

APP_INFO = "community-resources/resources/idea/IdeaApplicationInfo.xml"
PROPS = "build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt"


def slug(name):
    s = re.sub(r"[^A-Za-z0-9]", "", name)
    if not s:
        raise SystemExit("이름에는 영문자나 숫자가 하나 이상 있어야 합니다(설정 폴더 이름에 쓰입니다).")
    return s


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    root, old, new = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    edits = [
        (APP_INFO, f'fullname="{old}"', f'fullname="{new}"'),
        (PROPS, f'fullName {{ "{old}" }}', f'fullName {{ "{new}" }}'),
        (PROPS, f'installDirNameHandler {{ "{old}" }}', f'installDirNameHandler {{ "{new}" }}'),
        (PROPS, f'return "{slug(old)}${{appInfo.majorVersion}}', f'return "{slug(new)}${{appInfo.majorVersion}}'),
        (PROPS, f'= "{slug(old).lower()}-$buildNumber"', f'= "{slug(new).lower()}-$buildNumber"'),
    ]
    backup = root.parent / "backup" / (time.strftime("%Y%m%d-%H%M%S") + "-before")
    for rel, o, n in edits:
        path = root / rel
        text = path.read_text(encoding="utf-8")
        if n in text:
            print(f"이미 적용됨: {rel}: {n}")
            continue
        if text.count(o) != 1:
            raise SystemExit(f"원문을 정확히 한 번 찾지 못했습니다: {rel}: {o}")
        dest = backup / rel
        if not dest.exists():
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, dest)
        path.write_text(text.replace(o, n), encoding="utf-8")
        print(f"적용: {rel}: {n}")


if __name__ == "__main__":
    main()
