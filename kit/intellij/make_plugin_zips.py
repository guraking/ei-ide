#!/usr/bin/env python3
"""우리 플러그인 셋을 IntelliJ IDEA 에 「디스크에서 플러그인 설치」로 넣을 zip 으로 묶는다(Moa 가 보안 프로그램에 막힐 때 대신 쓰는 판).

사용법: python kit\\intellij\\make_plugin_zips.py <넣을 폴더> [플러그인 jar 폴더]
  플러그인 jar 폴더를 주지 않으면 bundled-plugins/<이름>/lib/<이름>.jar(compile_plugins.py 가 만든 본체 jar)를 쓴다.
  결과: <넣을 폴더>/<이름>-<판>.zip, 안은 <이름>/lib/<이름>.jar(IntelliJ 플러그인 zip 모양).

jar 안의 plugin.xml 만 바꾼다. 클래스는 Moa 본체 jar 와 같다.
  - <idea-version since-build> 를 넣는다. until-build 는 두지 않아 새 판 IntelliJ 에도 설치된다(새 판이 나오면 그 판으로 다시 컴파일해 묶는다).
  - Moa 본체에만 있는 주 메뉴(kit/menu 의 Source · Search · Project)에 넣던 항목은 IntelliJ 의 같은 자리 메뉴로 옮긴다.
고칠 글을 찾지 못하면 아무 zip 도 쓰지 않는다.
"""
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NAMES = ["boot-servers", "kinggura-sync", "ei-db"]
PLUGIN_XML = "META-INF/plugin.xml"
# 우리 플러그인이 컴파일되는 IntelliJ 판(2026.3)의 앞자리. kit/check_update.py 로 판을 올리면 같이 바꾼다.
SINCE_BUILD = "263"
ID_END = "</id>"
IDEA_VERSION = f'\n  <idea-version since-build="{SINCE_BUILD}"/>'
# Moa 주 메뉴(kit/menu/apply_menu.py) → IntelliJ 메뉴: Source → Code, Search → Edit › Find, Project → Tools.
MENU_MAP = {"KG.SourceMenu": "CodeMenu", "KG.SearchMenu": "FindMenuGroup", "KG.ProjectMenu": "ToolsMenu"}
VERSION = re.compile(r"<version>([^<]+)</version>")


def stock_xml(xml):
    """Moa 용 plugin.xml 글 → IntelliJ 용. (바꾼 글, 판)."""
    if "<idea-version" in xml or xml.count(ID_END) != 1:
        raise SystemExit("plugin.xml 에 <idea-version> 이 이미 있거나 </id> 가 한 번이 아닙니다.")
    version = VERSION.search(xml)
    if version is None:
        raise SystemExit("plugin.xml 에 <version> 이 없습니다(compile_plugins.py 가 묶은 jar 인지 확인하세요).")
    xml = xml.replace(ID_END, ID_END + IDEA_VERSION, 1)
    for moa, stock in MENU_MAP.items():
        xml = xml.replace(f'group-id="{moa}"', f'group-id="{stock}"')
    left = [moa for moa in MENU_MAP if moa in xml]
    if left:
        raise SystemExit(f"옮기지 못한 Moa 메뉴가 남았습니다: {left}")
    return xml, version.group(1)


def stock_jar(source, target):
    """source jar 를 plugin.xml 만 바꿔 target 에 쓴다. 판을 돌려준다."""
    version = None
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as out:
        for info in src.infolist():
            data = src.read(info)
            if info.filename == PLUGIN_XML:
                xml, version = stock_xml(data.decode("utf-8"))
                data = xml.encode("utf-8")
            out.writestr(info, data, zipfile.ZIP_DEFLATED)
    if version is None:
        raise SystemExit(f"jar 에 {PLUGIN_XML} 가 없습니다: {source}")
    return version


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit(__doc__)
    out = Path(sys.argv[1]).resolve()
    jar_dir = Path(sys.argv[2]).resolve() if len(sys.argv) == 3 else None
    sources = {n: (jar_dir / f"{n}.jar" if jar_dir else ROOT / "bundled-plugins" / n / "lib" / f"{n}.jar") for n in NAMES}
    missing = [str(p) for p in sources.values() if not p.is_file()]
    if missing:
        raise SystemExit("jar 가 없습니다. 아무 zip 도 쓰지 않았습니다:\n  " + "\n  ".join(missing))

    # 모든 jar 를 먼저 바꿔 본다. 하나라도 실패하면 zip 을 쓰지 않는다.
    out.mkdir(parents=True, exist_ok=True)
    staged = {}
    try:
        for name, source in sources.items():
            jar = out / f"{name}.jar.tmp"
            staged[name] = (jar, stock_jar(source, jar))
        for name, (jar, version) in staged.items():
            target = out / f"{name}-{version}.zip"
            part = target.with_name(target.name + ".part")
            with zipfile.ZipFile(part, "w", zipfile.ZIP_STORED) as z:
                z.write(jar, f"{name}/lib/{name}.jar")
            part.replace(target)
            print(f"만듦: {target}")
    finally:
        for jar, _ in staged.values():
            jar.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
