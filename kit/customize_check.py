#!/usr/bin/env python3
"""customize.py brand 점검: 원래 소스와 예전 이름(EI IDE)으로 바뀐 소스가 같은 결과가 되는지, 두 번 돌려도 같은지 본다."""
import subprocess
import sys
import tempfile
from pathlib import Path

import customize

ORIGINAL = {
    customize.APP_INFO: '<names fullname="IntelliJ IDEA" motto="The Leading IDE for Professional Development in Java and Kotlin"/>\n',
    customize.PROPS: 'fullName { "IntelliJ IDEA Open Source" }\ninstallDirNameHandler { "IntelliJ IDEA OSS" }\n'
                     'return "IdeaIC${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"\nx = "ideaIC-$buildNumber"\n',
}
EXPECTED = {
    customize.APP_INFO: '<names fullname="Moa IDE" motto="Personal Java IDE"/>\n',
    customize.PROPS: 'fullName { "Moa IDE" }\ninstallDirNameHandler { "Moa IDE" }\n'
                     'return "MoaIDE${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"\nx = "moaide-$buildNumber"\n',
}


def make(root: Path):
    for rel, text in ORIGINAL.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(text, encoding="utf-8")


def run(root: Path, name: str):
    subprocess.run([sys.executable, str(Path(__file__).with_name("customize.py")), str(root), name, "brand"],
                   check=True, stdout=subprocess.DEVNULL)


def check(root: Path, label: str):
    for rel, text in EXPECTED.items():
        got = (root / rel).read_text(encoding="utf-8")
        assert got == text, f"{label}: {rel}\n{got}"


def main():
    with tempfile.TemporaryDirectory() as tmp:
        fresh, renamed = Path(tmp, "fresh"), Path(tmp, "renamed")
        make(fresh)
        make(renamed)
        run(fresh, "Moa IDE")
        check(fresh, "원래 소스")
        run(renamed, "EI IDE")
        run(renamed, "Moa IDE")
        check(renamed, "EI IDE 로 바뀐 소스")
        run(renamed, "Moa IDE")
        check(renamed, "두 번째 실행")
    print("customize 점검 통과")


if __name__ == "__main__":
    main()
