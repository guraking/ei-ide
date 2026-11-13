#!/usr/bin/env python3
"""설치본에 함께 넣을 Gradle 배포본들을 bundled-plugins/boot-servers/gradle 에 푼다.
IntelliJ 는 모든 Gradle 프로젝트를 이 Gradle 로 돌린다(apply_defaults 35). 사내망에서 Wrapper 배포본 받기가 막혀도 동기화 · 빌드가 돈다.
프로젝트의 wrapper 가 9 보다 낮은 판을 적었으면 8 대, 그 밖에는 9 대를 쓴다. Gradle 9 가 없앤 API 를 부르는 옛 플러그인이 8 대 프로젝트에 남아 있다.

사용법: python kit/gradle/apply_gradle.py

받은 zip 의 SHA-256 이 아래 값과 다르면 아무것도 바꾸지 않고 멈춘다. 같은 판들이 이미 풀려 있으면 받지 않는다.
받기 · 풀기는 시스템 임시 폴더에서 한다. 판 표시 파일(MARKER)은 다 옮긴 뒤에 써서, 덜 풀린 폴더는 다음 실행 때 다시 받는다.
판을 올릴 때는 VERSIONS(판 → https://gradle.org/release-checksums/ 의 Binary-only ZIP SHA-256)와
apply_defaults 의 GRADLE_HOME · GRADLE_HOME_8 · build-windows.bat 의 검사를 함께 바꾼다.
"""
import hashlib
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

VERSIONS = {
    "9.9.1": "d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228",
    "8.14.7": "903bfc4bc7622323124054c10f509246212a1902948bdaf26c379cca8735e856",
}
URL = "https://services.gradle.org/distributions/gradle-{}-bin.zip"
TARGET = Path(__file__).resolve().parents[2] / "bundled-plugins" / "boot-servers" / "gradle"
MARKER = "moa-gradle-version.txt"
# 판 표시 파일의 내용: 넣은 판들을 한 줄에 하나씩.
MARKED = "\n".join(VERSIONS)
# 있어야 하는 파일. IntelliJ 는 lib 의 gradle-*.jar 로 판을 읽고, 명령줄 빌드는 bin 을 쓴다.
CHECK_FILES = tuple(Path(f"gradle-{version}") / part for version in VERSIONS for part in (Path("bin") / "gradle.bat", "lib"))


def main():
    marker = TARGET / MARKER
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == MARKED and all((TARGET / f).exists() for f in CHECK_FILES):
        print(f"이미 있음: Gradle {' · '.join(VERSIONS)}")
        return
    with tempfile.TemporaryDirectory(prefix="moa-gradle-") as work:
        unpacked = Path(work) / "gradle"
        for version, sha256 in VERSIONS.items():
            archive = Path(work) / f"gradle-{version}.zip"
            url = URL.format(version)
            print(f"받는 중: {url}")
            with urllib.request.urlopen(url, timeout=300) as response, archive.open("wb") as out:
                shutil.copyfileobj(response, out)
            actual = hashlib.sha256(archive.read_bytes()).hexdigest()
            if actual != sha256:
                sys.exit(f"아무것도 바꾸지 않았습니다. Gradle {version} 의 SHA-256 이 다릅니다: {actual}")
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(unpacked)
        for f in CHECK_FILES:
            if not (unpacked / f).exists():
                sys.exit(f"아무것도 바꾸지 않았습니다. zip 안에 {f} 가 없습니다.")
        # 옛 설치는 옮겨 두었다가 새 것이 다 들어간 뒤에 지운다. 넣다가 실패하면 옛 것을 되돌린다.
        old = TARGET.with_name(TARGET.name + ".old")
        if old.exists():
            shutil.rmtree(old)
        if TARGET.exists():
            TARGET.rename(old)
        try:
            shutil.move(str(unpacked), str(TARGET))
            (TARGET / MARKER).write_text(MARKED + "\n", encoding="utf-8")
        except BaseException:
            shutil.rmtree(TARGET, ignore_errors=True)
            if old.exists():
                old.rename(TARGET)
            raise
        shutil.rmtree(old, ignore_errors=True)
    print(f"넣음: {TARGET} (Gradle {' · '.join(VERSIONS)})")


if __name__ == "__main__":
    main()
