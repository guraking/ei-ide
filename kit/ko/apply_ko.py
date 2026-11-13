#!/usr/bin/env python3
"""한국어 언어 팩에 우리 번역을 덮어 본체에 넣는다.

사용법: python kit\\ko\\apply_ko.py [팩]
  팩  한국어 언어 팩(localization-ko) zip 또는 풀린 폴더. 없으면 PACK_SOURCES 를 차례로 찾는다.
      우리 판에 맞는 팩이 마켓에 없으면 예전에 릴리스로 따로 받아 둔 것을 쓴다(플러그인설치.bat 이 release\\localization-ko 로 옮겨 둠).

kit/ko/override/<jar 안 경로> 에 같은 이름의 .properties 가 있으면 그 줄이 팩 번역을 덮어쓴다(우리 번역, 기준은 kit/ko/용어집.md).
덮어쓸 줄은 팩 줄과 모양(자리표시 {n} · HTML 태그 · 단독 따옴표 · 단축 글자)이 같아야 하고, 다르면 아무것도 바꾸지 않고 끝낸다.
plugin.xml 의 until-build 는 우리 판 번호(앞자리.*)까지 넓힌다. 결과는 bundled-plugins/localization-ko 에 통째로 바꿔 넣는다.
"""
import io
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

KIT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(KIT))
from check_update import ROOT, config_version, current_build, prefix  # noqa: E402

PACK_DIR = "localization-ko"
# 설정 폴더 이름 앞부분: 지금 이름, 이름을 Moa IDE 로 바꾸기 전 이름(새 설정 폴더가 아직 없는 PC). 뒤에 get-source.bat 의 판(2026.3 꼴)이 붙는다.
CONFIG_PREFIXES = ("MoaIDE", "EIIDE")
JETBRAINS_CONFIG = Path(os.environ.get("APPDATA", "")) / "JetBrains"
# 팩을 찾는 곳. 앞의 것이 먼저다.
PACK_SOURCES = [ROOT / "release" / PACK_DIR, ROOT / "release" / "plugins" / PACK_DIR] + [
    JETBRAINS_CONFIG / (name + config_version(current_build())) / "plugins" / PACK_DIR for name in CONFIG_PREFIXES]
OUT = ROOT / "bundled-plugins" / PACK_DIR
OVERRIDE = KIT / "ko" / "override"
UNTIL_BUILD = re.compile(r'until-build="[^"*]*"')
PLACEHOLDER = re.compile(r"\{(\d+)")
# 태그 이름 뒤에는 공백 · > · / 가 온다. choice 구문의 「1<s}」 같은 부등호는 태그가 아니다.
TAG = re.compile(r"<(/?[A-Za-z][A-Za-z0-9]*)(?=[\s>/])")
# 단축 글자: 팩은 「열기(_O)」, 영어 원문은 「_Open」 · 「&Open」 꼴이다. 팩 줄과 비교할 때만 센다.
MNEMONIC = re.compile(r"\(_\w\)|&\w")


def key_of(line):
    """properties 한 줄의 키. 주석 · 빈 줄이면 None. 이어지는 줄인지는 부르는 쪽이 가린다."""
    s = line.lstrip()
    if not s or s[0] in "#!":
        return None
    m = re.match(r"((?:\\.|[^=:\s\\])+)", s)
    return m.group(1).replace("\\", "") if m else None


def value_of(line):
    """properties 한 줄(이어진 줄은 합친 것)의 값. 이스케이프는 그대로 둔다."""
    s = line.lstrip()
    m = re.match(r"((?:\\.|[^=:\s\\])+)\s*[=:]?\s*", s)
    return s[m.end():] if m else ""


def shape(value):
    """번역이 지켜야 하는 모양: 자리표시 번호 · 태그 · 단독 따옴표 수 · 단축 글자 수."""
    lone_quotes = value.replace("''", "").count("'") if "{" in value else 0
    return (set(PLACEHOLDER.findall(value)), sorted(t.lower() for t in TAG.findall(value)), lone_quotes,
            len(MNEMONIC.findall(value)))


def overlay(text, override=None):
    """override(키 → 줄)를 덮어쓴 properties 글, 덮어쓴 줄 수, 모양이 달라 못 쓴 키 목록.
    값이 \\ 로 이어지는 팩 줄은 합쳐서 비교하고 통째로 바꾼다. 팩에 없는 키는 끝에 덧붙인다."""
    out, bad, seen = [], [], set()
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        start, whole, i = i, lines[i], i + 1
        k = key_of(whole)
        while k and whole.endswith("\\") and i < len(lines):
            whole, i = whole[:-1] + lines[i].lstrip(), i + 1
        if not (k and override and k in override):
            out += lines[start:i]
            continue
        seen.add(k)
        if shape(value_of(whole)) == shape(value_of(override[k])):
            out.append(override[k])
        else:
            bad.append(k)
            out.append(whole)
    for k, l in (override or {}).items():
        if k not in seen:
            out.append(l)
    return "\n".join(out) + "\n", len(seen) - len(bad) + len((override or {}).keys() - seen), bad


def overrides_for(name):
    """override 폴더에서 jar 안 경로 name 에 맞는 키 → 줄. 없으면 None."""
    p = OVERRIDE / name
    if not p.is_file():
        return None
    return {key_of(l): l for l in p.read_text(encoding="utf-8").splitlines() if key_of(l)}


def override_files():
    """override 폴더의 번역 파일들(jar 안 경로, / 구분)."""
    return {p.relative_to(OVERRIDE).as_posix() for p in OVERRIDE.rglob("*.properties")}


def overlay_jar(data, used=None):
    """jar(bytes) 의 .properties 에 우리 번역을 덮은 새 jar(bytes), 덮어쓴 줄 수, 못 쓴 「경로: 키」 목록.
    used(집합)를 주면 jar 에 있던 .properties 경로를 담는다(팩에 없는 번역 파일을 찾는 데 쓴다)."""
    src, buf, applied, bad = zipfile.ZipFile(io.BytesIO(data)), io.BytesIO(), 0, []
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            body = src.read(info)
            if info.filename == "META-INF/plugin.xml":
                # 팩은 받은 판까지로 묶여 있다. 우리 판 번호(get-source.bat 판의 앞자리.N)가 들어가게 끝을 앞자리.* 로 넓힌다.
                body = UNTIL_BUILD.sub(f'until-build="{prefix(current_build())}.*"', body.decode("utf-8")).encode("utf-8")
            elif info.filename.endswith(".properties"):
                if used is not None:
                    used.add(info.filename)
                new, n, wrong = overlay(body.decode("utf-8"), overrides_for(info.filename))
                applied += n
                bad += [f"{info.filename}: {k}" for k in wrong]
                body = new.encode("utf-8")
            dst.writestr(info, body)
    return buf.getvalue(), applied, bad


def find_pack():
    """팩 zip 또는 폴더 경로. 못 찾으면 끝낸다."""
    if len(sys.argv) > 1:
        return Path(sys.argv[1])
    for p in PACK_SOURCES:
        if (p / "lib").is_dir():
            return p
    raise SystemExit("한국어 팩을 찾지 못했습니다. 팩 zip 이나 폴더를 인자로 주세요: " + ", ".join(map(str, PACK_SOURCES)))


def main():
    src = find_pack()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / PACK_DIR
        if src.is_dir():
            shutil.copytree(src, root)
        elif zipfile.is_zipfile(src):
            zipfile.ZipFile(src).extractall(tmp)
            found = [p for p in Path(tmp).iterdir() if (p / "lib").is_dir()]
            if len(found) != 1:
                raise SystemExit("팩 zip 안에 <플러그인>/lib 폴더가 하나여야 합니다.")
            root = found[0]
        else:
            raise SystemExit(f"팩이 zip 도 폴더도 아닙니다: {src}")
        jars = list((root / "lib").glob("*.jar"))
        if not jars:
            raise SystemExit("팩 lib 폴더에 jar 가 없습니다.")
        print(f"한국어 팩: {src}")
        total, bad, used = 0, [], set()
        for jar in jars:
            data, applied, wrong = overlay_jar(jar.read_bytes(), used)
            jar.write_bytes(data)
            total += applied
            bad += wrong
            print(f"  {jar.name}: 우리 번역 {applied}줄 덮음")
        if bad:
            raise SystemExit(f"우리 번역 {len(bad)}줄이 팩 줄과 모양({{n}} · 태그 · 따옴표 · 단축 글자)이 달라 넣지 않았습니다:\n  "
                             + "\n  ".join(bad[:50]))
        unused = sorted(override_files() - used)
        if unused:
            raise SystemExit(f"우리 번역 파일 {len(unused)}개가 팩 jar 에 없는 경로라 넣지 못했습니다(팩 구조가 바뀌었는지 확인하세요):\n  "
                             + "\n  ".join(unused))
        if total == 0:
            raise SystemExit(f"덮어쓴 줄이 없습니다. {OVERRIDE} 가 비었거나 팩 구조가 바뀌었는지 확인하세요.")
        if OUT.exists():
            shutil.rmtree(OUT)
        shutil.copytree(root, OUT)
    print(f"넣음: {OUT} (우리 번역 {total}줄)")


if __name__ == "__main__":
    main()
