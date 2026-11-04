#!/usr/bin/env python3
"""설치본에 함께 넣을 Node.js 와 웹 언어 서버(vtsls · Vue)를 bundled-plugins/boot-servers/web 에 넣는다.
인터넷이 막힌 사내망 PC 에서도 .js · .ts · .vue 를 열면 받기 알림 없이 언어 서버가 뜨게 한다.

사용법: python kit/web/apply_web.py

폴더 구조는 사용자 폴더 .ei-web 과 같다: web/node(node.exe), web/servers(node_modules).
Node zip 의 SHA-256 이 아래 값과 다르거나 npm 이 실패하면 아무것도 바꾸지 않고 멈춘다. 같은 판이 이미 들어 있으면 받지 않는다.
받기 · 풀기 · npm 은 시스템 임시 폴더에서 한다. 판 표시 파일(MARKER)은 다 옮긴 뒤에 써서, 덜 옮긴 폴더는 다음 실행 때 다시 만든다.
판을 올릴 때는 NODE_VERSION · NODE_SHA256(nodejs.org/dist/<판>/SHASUMS256.txt) · PACKAGES 를 함께 바꾼다.
폴더 안 경로가 바뀌면 WebTools(BUNDLED_WEB · bundledNode · serversInstalled) · build-windows.bat 의 검사도 맞춘다.
"""
import hashlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

NODE_VERSION = "v24.22.2"
NODE_ZIP = f"node-{NODE_VERSION}-win-x64"
NODE_SHA256 = "ca1bff5d8ead13c9f2ea404a176db46bd430704d5b5113734049fa365e7799e2"
NODE_URL = f"https://nodejs.org/dist/{NODE_VERSION}/{NODE_ZIP}.zip"
# WebTools.SERVER_PACKAGES 와 같은 패키지를 판만 고정한다. typescript 는 tsserver.js 가 있는 5 판이어야 한다.
PACKAGES = ("@vtsls/language-server@0.3.0", "@vue/language-server@3.3.14", "@vue/typescript-plugin@3.3.14", "typescript@5.9.3")
TARGET = Path(__file__).resolve().parents[2] / "bundled-plugins" / "boot-servers" / "web"
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


def version_text():
    return " ".join((NODE_VERSION,) + PACKAGES)


def main():
    marker = TARGET / MARKER
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == version_text() and all((TARGET / f).is_file() for f in CHECK_FILES):
        print(f"이미 있음: Node {NODE_VERSION} · {' '.join(PACKAGES)}")
        return
    with tempfile.TemporaryDirectory(prefix="ei-web-") as work:
        work = Path(work)
        archive = work / "node.zip"
        print(f"받는 중: {NODE_URL}")
        with urllib.request.urlopen(NODE_URL, timeout=300) as response, archive.open("wb") as out:
            shutil.copyfileobj(response, out)
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != NODE_SHA256:
            sys.exit(f"아무것도 바꾸지 않았습니다. Node zip 의 SHA-256 이 다릅니다: {actual}")
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(work)
        unpacked = work / "web"
        unpacked.mkdir()
        (work / NODE_ZIP).rename(unpacked / "node")
        servers = unpacked / "servers"
        servers.mkdir()
        # npm 은 JS 라서 윈도우가 아닌 PC(점검용)에서는 그 PC 의 node 로 zip 안의 npm 을 돌린다.
        node = str(unpacked / "node" / "node.exe") if sys.platform == "win32" else "node"
        command = [node, str(unpacked / "node" / NPM_CLI), "install", "--prefix", str(servers),
                   "--no-fund", "--no-audit", "--save-exact", *PACKAGES]
        print("npm install " + " ".join(PACKAGES))
        if subprocess.run(command, cwd=servers).returncode != 0:
            sys.exit("아무것도 바꾸지 않았습니다. npm install 이 실패했습니다.")
        for f in CHECK_FILES:
            if not (unpacked / f).is_file():
                sys.exit(f"아무것도 바꾸지 않았습니다. {f} 가 없습니다.")
        if TARGET.exists():
            shutil.rmtree(TARGET)
        # 임시 폴더와 드라이브가 다를 수 있으므로 rename 대신 move(복사 뒤 지우기)를 쓴다.
        try:
            shutil.move(str(unpacked), str(TARGET))
            marker.write_text(version_text() + "\n", encoding="utf-8")
        except BaseException:
            shutil.rmtree(TARGET, ignore_errors=True)
            raise
    print(f"넣음: {TARGET} (Node {NODE_VERSION})")


if __name__ == "__main__":
    main()
