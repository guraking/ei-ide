#!/usr/bin/env python3
"""한국어 언어 팩을 메뉴 · 단추만 영어로 깎아 본체에 넣는다.

사용법: python kit\\ko\\apply_ko.py [팩]
  팩  한국어 언어 팩(localization-ko) zip 또는 풀린 폴더. 없으면 PACK_SOURCES 를 차례로 찾는다.
      262 판 팩은 마켓에 없어 예전에 릴리스로 따로 받아 둔 것을 쓴다(플러그인설치.bat 이 release\\localization-ko 로 옮겨 둠).

팩 안 모든 jar 의 .properties 에서 메뉴 · 단추 키(MENU_BUTTON_KEY)를 지운다. 지운 글은 IntelliJ 가 영어 원문으로 보여 주고,
대화 상자 본문 · 알림 · 오류 · 입력칸 이름 · 설명은 한국어로 남는다. kit/ko/override/<jar 안 경로> 에 같은 이름의
.properties 가 있으면 그 줄이 팩 번역을 덮어쓴다(우리 번역). plugin.xml 의 until-build 는 우리 판 번호(앞자리.*)까지 넓힌다. 결과는 bundled-plugins/localization-ko 에 통째로 바꿔 넣는다.
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
from check_update import ROOT, current_build, prefix  # noqa: E402

PACK_DIR = "localization-ko"
# 팩을 찾는 곳. 앞의 것이 먼저다.
PACK_SOURCES = [ROOT / "release" / PACK_DIR, ROOT / "release" / "plugins" / PACK_DIR,
                Path(os.environ.get("APPDATA", "")) / "JetBrains" / "EIIDE2026.2" / "plugins" / PACK_DIR]
OUT = ROOT / "bundled-plugins" / PACK_DIR
OVERRIDE = KIT / "ko" / "override"
UNTIL_BUILD = re.compile(r'until-build="[^"*]*"')
# 메뉴 · 툴바 동작 이름, 단추 · 체크 상자 · 라디오 · 탭 · 툴 창 줄 이름. 키 이름으로만 고른다.
MENU_BUTTON_KEY = re.compile(
    r"^(action|group)\..*\.text$"
    r"|(^|\.)(button|buttons|btn|checkbox|check\.box|radio|tab|menu)(\.|$)"
    r"|^toolwindow\.stripe\.")


def key_of(line):
    """properties 한 줄의 키. 주석 · 빈 줄이면 None. 이어지는 줄인지는 부르는 쪽이 가린다."""
    s = line.lstrip()
    if not s or s[0] in "#!":
        return None
    m = re.match(r"((?:\\.|[^=:\s\\])+)", s)
    return m.group(1).replace("\\", "") if m else None


def trim(text, override=None):
    """메뉴 · 단추 키를 지우고 override(키 → 줄)를 덮어쓴 properties 글을 돌려준다. 값이 \\ 로 이어지는 줄은 앞 줄을 따른다."""
    out, cont, drop, seen = [], False, False, set()
    for line in text.splitlines():
        if cont:
            cont = line.endswith("\\")
            if not drop:
                out.append(line)
            continue
        k = key_of(line)
        cont = line.endswith("\\")
        drop = bool(k) and (bool(MENU_BUTTON_KEY.search(k)) or bool(override and k in override))
        if k and override and k in override:
            out.append(override[k])
            seen.add(k)
        elif not drop:
            out.append(line)
    for k, l in (override or {}).items():
        if k not in seen:
            out.append(l)
    return "\n".join(out) + "\n"


def overrides_for(name):
    """override 폴더에서 jar 안 경로 name 에 맞는 키 → 줄. 없으면 None."""
    p = OVERRIDE / name
    if not p.is_file():
        return None
    return {key_of(l): l for l in p.read_text(encoding="utf-8").splitlines() if key_of(l)}


def trim_jar(data):
    """jar(bytes) 의 .properties 를 깎은 새 jar(bytes)와 지운 줄 수."""
    src, buf, removed = zipfile.ZipFile(io.BytesIO(data)), io.BytesIO(), 0
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            body = src.read(info)
            if info.filename == "META-INF/plugin.xml":
                # 팩은 받은 판(262.10968.63)까지로 묶여 있다. 우리 판 번호는 262.10968.N 이라 끝을 앞자리.* 로 넓힌다.
                body = UNTIL_BUILD.sub(f'until-build="{prefix(current_build())}.*"', body.decode("utf-8")).encode("utf-8")
            elif info.filename.endswith(".properties"):
                text = body.decode("utf-8")
                new = trim(text, overrides_for(info.filename))
                removed += len(text.splitlines()) - len(new.splitlines())
                body = new.encode("utf-8")
            dst.writestr(info, body)
    return buf.getvalue(), removed


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
        total = 0
        for jar in jars:
            data, removed = trim_jar(jar.read_bytes())
            jar.write_bytes(data)
            total += removed
            print(f"  {jar.name}: 메뉴 · 단추 줄 {removed}개 지움")
        if total == 0:
            raise SystemExit("지운 줄이 없습니다. 이미 깎은 팩이거나 팩 구조가 바뀌었는지 확인하세요.")
        if OUT.exists():
            shutil.rmtree(OUT)
        shutil.copytree(root, OUT)
    print(f"넣음: {OUT} (메뉴 · 단추 줄 {total}개 지움)")


if __name__ == "__main__":
    main()
