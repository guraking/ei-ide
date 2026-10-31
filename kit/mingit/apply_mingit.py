#!/usr/bin/env python3
"""설치본에 함께 넣을 MinGit 을 bundled-plugins/kinggura-sync/git 에 푼다. Git 이 없는 사내망 PC 에서도 Git 기능이 돌게 한다.

사용법: python kit/mingit/apply_mingit.py

받은 zip 의 SHA-256 이 아래 값과 다르면 아무것도 바꾸지 않고 멈춘다. 같은 판이 이미 풀려 있으면 받지 않는다.
받기 · 풀기는 시스템 임시 폴더에서 한다. 중간에 끊겨도 bundled-plugins 안(설치본에 들어가는 곳)에 찌꺼기가 남지 않는다.
옮기다 실패하면 반쯤 옮긴 git 폴더를 지운다. 판 표시 파일(MARKER)은 다 옮긴 뒤에 써서, 덜 풀린 폴더는 다음 실행 때 다시 받는다.
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
# 있어야 하는 실행 파일. ucrt64\bin 은 우리 Git 기능(SyncPanel.BUNDLED_GIT), cmd 는 IntelliJ Git 이 쓴다.
CHECK_EXES = (Path("ucrt64") / "bin" / "git.exe", Path("cmd") / "git.exe")


def main():
    marker = TARGET / MARKER
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == VERSION and all((TARGET / exe).is_file() for exe in CHECK_EXES):
        print(f"이미 있음: MinGit {VERSION}")
        return
    with tempfile.TemporaryDirectory(prefix="ei-mingit-") as work:
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
        for exe in CHECK_EXES:
            if not (unpacked / exe).is_file():
                sys.exit(f"아무것도 바꾸지 않았습니다. zip 안에 {exe} 가 없습니다.")
        if TARGET.exists():
            shutil.rmtree(TARGET)
        # 임시 폴더와 드라이브가 다를 수 있으므로 rename 대신 move(복사 뒤 지우기)를 쓴다.
        try:
            shutil.move(str(unpacked), str(TARGET))
            (TARGET / MARKER).write_text(VERSION + "\n", encoding="utf-8")
        except BaseException:
            shutil.rmtree(TARGET, ignore_errors=True)
            raise
    print(f"넣음: {TARGET} (MinGit {VERSION})")


if __name__ == "__main__":
    main()
