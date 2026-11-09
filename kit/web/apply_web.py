#!/usr/bin/env python3
"""설치본에 함께 넣을 Node.js 와 웹 언어 서버(vtsls · Vue)를 bundled-plugins/boot-servers/web 에 넣는다.
인터넷이 막힌 사내망 PC 에서도 .js · .ts · .vue 를 열면 받기 알림 없이 언어 서버가 뜨게 한다.

사용법: python kit/web/apply_web.py

폴더 구조는 사용자 폴더 .ei-web 과 같다: web/node(node.exe), web/servers(node_modules).
판은 IDE 안 「언어 서버 받기」와 같은 파일에서 읽는다(PINNED): versions.properties 의 Node 판 · win-x64 zip SHA-256,
package.json · package-lock.json 의 언어 서버 판. 언어 서버는 잠금 파일대로 npm ci 로 받아 모든 패키지의 무결성 해시를 확인한다.
Node zip 의 SHA-256 이 다르거나 npm 이 실패하면 아무것도 바꾸지 않고 멈춘다. 같은 판이 이미 들어 있으면 받지 않는다.
받기 · 풀기 · npm 은 시스템 임시 폴더에서 한다. 판 표시 파일(MARKER)은 다 옮긴 뒤에 써서, 덜 옮긴 폴더는 다음 실행 때 다시 만든다.
판을 올릴 때는 PINNED 폴더의 파일만 고친다(방법은 versions.properties 머리 주석).
폴더 안 경로가 바뀌면 WebTools(BUNDLED_WEB · bundledNode · serversInstalled) · build-windows.bat 의 검사도 맞춘다.
"""
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

KIT = Path(__file__).resolve().parents[1]
# WebTools.PINNED_RESOURCE_DIR 와 같은 폴더(플러그인 jar 의 /web/).
PINNED = KIT / "boot-servers" / "src" / "main" / "resources" / "web"
PINNED_FILES = ("package.json", "package-lock.json")
VERSIONS_FILE = PINNED / "versions.properties"
TARGET = KIT.parent / "bundled-plugins" / "boot-servers" / "web"
MARKER = "ei-web-version.txt"
NPM_CLI = Path("node_modules") / "npm" / "bin" / "npm-cli.js"
# WebTools.bundledNode · serversInstalled 가 보는 파일.
CHECK_FILES = (
    Path("node") / "node.exe",
    Path("servers") / "node_modules" / "@vtsls" / "language-server" / "bin" / "vtsls.js",
    Path("servers") / "node_modules" / "@vue" / "language-server" / "bin" / "vue-language-server.js",
    Path("servers") / "node_modules" / "@vue" / "typescript-plugin" / "package.json",
    Path("servers") / "node_modules" / "typescript" / "lib" / "tsserver.js",
)


def read_properties(path):
    """key=value 줄만 읽는다(# · ! 주석, 빈 줄 건너뜀). 없는 파일이면 멈춘다."""
    if not path.is_file():
        sys.exit(f"아무것도 바꾸지 않았습니다. {path} 가 없습니다.")
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line[0] in "#!" or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def pinned_packages():
    """package.json 의 dependencies 를 「이름@판」 으로. 판이 정확한 숫자가 아니면 멈춘다."""
    deps = json.loads((PINNED / "package.json").read_text(encoding="utf-8")).get("dependencies", {})
    packages = []
    for name, version in deps.items():
        if not all(part.isdigit() for part in version.split(".")):
            sys.exit(f"아무것도 바꾸지 않았습니다. package.json 의 {name} 판이 고정되어 있지 않습니다: {version}")
        packages.append(f"{name}@{version}")
    return tuple(packages)


def main():
    versions = read_properties(VERSIONS_FILE)
    node_version = versions.get("node.version")
    node_sha256 = versions.get("node.sha256.win-x64")
    if not node_version or not node_sha256:
        sys.exit(f"아무것도 바꾸지 않았습니다. {VERSIONS_FILE} 에 node.version · node.sha256.win-x64 가 없습니다.")
    for name in PINNED_FILES:
        if not (PINNED / name).is_file():
            sys.exit(f"아무것도 바꾸지 않았습니다. {PINNED / name} 가 없습니다.")
    packages = pinned_packages()
    node_zip = f"node-{node_version}-win-x64"
    node_url = f"https://nodejs.org/dist/{node_version}/{node_zip}.zip"
    # 잠금 파일이 바뀌면 다시 넣도록 그 해시도 판 표시에 넣는다.
    lock_hash = hashlib.sha256((PINNED / "package-lock.json").read_bytes()).hexdigest()
    version_text = " ".join((node_version,) + packages + (lock_hash,))
    marker = TARGET / MARKER
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == version_text and all((TARGET / f).is_file() for f in CHECK_FILES):
        print(f"이미 있음: Node {node_version} · {' '.join(packages)}")
        return
    with tempfile.TemporaryDirectory(prefix="ei-web-") as work:
        work = Path(work)
        archive = work / "node.zip"
        print(f"받는 중: {node_url}")
        with urllib.request.urlopen(node_url, timeout=300) as response, archive.open("wb") as out:
            shutil.copyfileobj(response, out)
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != node_sha256.lower():
            sys.exit(f"아무것도 바꾸지 않았습니다. Node zip 의 SHA-256 이 다릅니다: {actual}")
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(work)
        unpacked = work / "web"
        unpacked.mkdir()
        (work / node_zip).rename(unpacked / "node")
        servers = unpacked / "servers"
        servers.mkdir()
        for name in PINNED_FILES:
            shutil.copyfile(PINNED / name, servers / name)
        # npm 은 JS 라서 윈도우가 아닌 PC(점검용)에서는 그 PC 의 node 로 zip 안의 npm 을 돌린다.
        node = str(unpacked / "node" / "node.exe") if sys.platform == "win32" else "node"
        command = [node, str(unpacked / "node" / NPM_CLI), "ci", "--prefix", str(servers), "--no-fund", "--no-audit"]
        print("npm ci " + " ".join(packages))
        if subprocess.run(command, cwd=servers).returncode != 0:
            sys.exit("아무것도 바꾸지 않았습니다. npm ci 가 실패했습니다.")
        for f in CHECK_FILES:
            if not (unpacked / f).is_file():
                sys.exit(f"아무것도 바꾸지 않았습니다. {f} 가 없습니다.")
        # 옛 설치는 옮겨 두었다가 새 것이 다 들어간 뒤에 지운다. 넣다가 실패하면 옛 것을 되돌린다.
        old = TARGET.with_name(TARGET.name + ".old")
        if old.exists():
            shutil.rmtree(old)
        if TARGET.exists():
            TARGET.rename(old)
        # 임시 폴더와 드라이브가 다를 수 있으므로 rename 대신 move(복사 뒤 지우기)를 쓴다.
        try:
            shutil.move(str(unpacked), str(TARGET))
            marker.write_text(version_text + "\n", encoding="utf-8")
        except BaseException:
            shutil.rmtree(TARGET, ignore_errors=True)
            if old.exists():
                old.rename(TARGET)
            raise
        shutil.rmtree(old, ignore_errors=True)
    print(f"넣음: {TARGET} (Node {node_version})")


if __name__ == "__main__":
    main()
