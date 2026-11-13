#!/usr/bin/env python3
"""설치본에 넣을 Full Line 언어 모델(Java · HTML)을 받아 bundled-plugins/fullLine 에 넣는다.
인터넷이 막힌 PC 에서도 Full Line(한 줄 통째 제안)이 첫날부터 돈다.

사용법: python kit/fullline/apply_fullline.py

Full Line 은 플러그인 폴더에 full-line-model-<태그>.zip 이 있고 그 안 model.xml 의 판이 플러그인이 찾는 판과 같으면
내려받지 않고 그 묶음을 푼다(LocalModelsBundleManager). 판이 다르면 원래대로 JetBrains 서버에서 받으려 한다.
묶음은 JetBrains 가 내려받게 하는 파일(local-model-<태그>-<판>.jar)을 이름만 바꿔 넣는다. 안의 파일은 이름 끝으로 찾는다.

받은 파일의 SHA-256 이 아래 값과 다르거나 안의 model.xml 판이 다르면 아무것도 바꾸지 않고 멈춘다. 같은 파일이 이미 있으면 받지 않는다.
fullLine 플러그인 판이 PLUGIN_VERSION 과 다르면 멈춘다. 플러그인을 바꾸면 그 판의 JavaFullLineSupporter · HtmlFullLineSupporter 에 박힌
모델 판(nativeLLaMAVersion)을 확인해 MODELS 의 판 · SHA256 을 함께 바꾼다.
"""
import hashlib
import re
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

PLUGIN_VERSION = "262.10968.63"
# Full Line 레지스트리 full.line.local.models.host 의 기본값.
HOST = "https://download.jetbrains.com/resources/ml/full-line/models"
# 태그: (판, SHA-256). 태그는 언어 ID 소문자(Java) 또는 supporter 의 customTag(HTML).
MODELS = {
    "java": ("0.1.87-native-llama-bundle", "f1a5c4a3c38f0ab01dbf87b71cfab8494cf96cb96241fbb5a97fe96d8ab36ae9"),
    "html": ("0.1.12-native-llama-bundle", "67e97964f6fe40add3c6c8ba2adae122e9e1c9b272907216e9ffe6b1a337fd7e"),
}
PLUGIN = Path(__file__).resolve().parents[2] / "bundled-plugins" / "fullLine"


def bundle_name(tag):
    return f"full-line-model-{tag}.zip"


def model_version(archive):
    """묶음 안 model.xml 의 <version> 값. model.xml 이 없거나 판이 없으면 None."""
    with zipfile.ZipFile(archive) as zf:
        names = [n for n in zf.namelist() if n.endswith("model.xml")]
        if len(names) != 1:
            return None
        found = re.search(r"<version>\s*([^<\s]+)\s*</version>", zf.read(names[0]).decode("utf-8"))
        return found.group(1) if found else None


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def plugin_version():
    with zipfile.ZipFile(PLUGIN / "lib" / "fullLine.jar") as zf:
        found = re.search(r"<version>([^<]+)</version>", zf.read("META-INF/plugin.xml").decode("utf-8"))
    return found.group(1) if found else None


def main():
    if not (PLUGIN / "lib" / "fullLine.jar").is_file():
        sys.exit(f"아무것도 바꾸지 않았습니다. Full Line 플러그인이 없습니다: {PLUGIN}")
    actual_plugin = plugin_version()
    if actual_plugin != PLUGIN_VERSION:
        sys.exit(f"아무것도 바꾸지 않았습니다. Full Line 플러그인 판이 {actual_plugin} 입니다({PLUGIN_VERSION} 기준). "
                 "새 판의 모델 판을 확인해 이 대본의 MODELS 를 바꾸세요.")
    with tempfile.TemporaryDirectory(prefix="ei-fullline-") as work:
        ready = {}
        for tag, (version, expected) in MODELS.items():
            target = PLUGIN / bundle_name(tag)
            if target.is_file() and sha256(target) == expected:
                print(f"이미 있음: {target.name} ({version})")
                continue
            url = f"{HOST}/{tag}/{version}/local-model-{tag}-{version}.jar"
            archive = Path(work) / bundle_name(tag)
            print(f"받는 중: {url}")
            with urllib.request.urlopen(url, timeout=600) as response, archive.open("wb") as out:
                shutil.copyfileobj(response, out)
            actual = sha256(archive)
            if actual != expected:
                sys.exit(f"아무것도 바꾸지 않았습니다. {tag} 모델의 SHA-256 이 다릅니다: {actual} ({archive.stat().st_size} 바이트)")
            if model_version(archive) != version:
                sys.exit(f"아무것도 바꾸지 않았습니다. {tag} 모델 안 model.xml 의 판이 {version} 이 아닙니다.")
            ready[tag] = archive
        # 모두 받고 확인한 뒤에 넣는다. 하나라도 실패하면 위에서 멈춰 플러그인 폴더는 그대로다.
        # 옮기다 끊겨도 반쪽 묶음이 제 이름으로 남지 않게 옆 이름에 쓴 뒤 바꾼다.
        for tag, archive in ready.items():
            partial = PLUGIN / (bundle_name(tag) + ".part")
            try:
                shutil.copyfile(archive, partial)
                partial.replace(PLUGIN / bundle_name(tag))
            finally:
                partial.unlink(missing_ok=True)
            print(f"넣음: {PLUGIN / bundle_name(tag)} ({archive.stat().st_size // (1 << 20)} MB)")


if __name__ == "__main__":
    main()
