#!/usr/bin/env python3
"""설치본에 함께 넣을 MinGit 을 bundled-plugins/kinggura-sync/git 에 푼다. Git 이 없는 사내망 PC 에서도 Git 기능이 돌게 한다.

사용법: python kit/mingit/apply_mingit.py

받은 zip 의 SHA-256 이 아래 값과 다르면 아무것도 바꾸지 않고 멈춘다. 같은 판이 이미 풀려 있으면 받지 않는다.
판을 올릴 때는 VERSION · TAG · SHA256 을 함께 바꾼다(git-for-windows/git 릴리스의 asset digest).
폴더 안 경로가 바뀌면 SyncPanel.BUNDLED_GIT · apply_defaults 의 BUNDLED_GIT · build-windows.bat 의 검사도 맞춘다.
"""
import hashlib
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

VERSION = "2.56.0.4"
TAG = "v2.56.0.windows.4"
SHA256 = "e3bf78351fe777fba47ddee5950082f8a98bb0f02604fdc7ba3ee2cb68ffa877"
URL = f"https://github.com/git-for-windows/git/releases/download/{TAG}/MinGit-{VERSION}-64-bit.zip"
PLUGIN = Path(__file__).resolve().parents[2] / "bundled-plugins" / "kinggura-sync"
TARGET = PLUGIN / "git"
MARKER = "ei-mingit-version.txt"
CHECK_EXE = Path("ucrt64") / "bin" / "git.exe"


def main():
    marker = TARGET / MARKER
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == VERSION and (TARGET / CHECK_EXE).is_file():
        print(f"이미 있음: MinGit {VERSION}")
        return
    with tempfile.TemporaryDirectory(dir=PLUGIN) as work:
        archive = Path(work) / "mingit.zip"
        print(f"받는 중: {URL}")
        with urllib.request.urlopen(URL, timeout=300) as response, archive.open("wb") as out:
            shutil.copyfileobj(response, out)
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != SHA256:
            sys.exit(f"아무것도 바꾸지 않았습니다. SHA-256 이 다릅니다: {actual}")
        unpacked = Path(work) / "git"
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(unpacked)
        if not (unpacked / CHECK_EXE).is_file():
            sys.exit(f"아무것도 바꾸지 않았습니다. zip 안에 {CHECK_EXE} 가 없습니다.")
        (unpacked / MARKER).write_text(VERSION + "\n", encoding="utf-8")
        if TARGET.exists():
            shutil.rmtree(TARGET)
        unpacked.rename(TARGET)
    print(f"넣음: {TARGET} (MinGit {VERSION})")


if __name__ == "__main__":
    main()
