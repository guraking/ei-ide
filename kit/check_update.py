#!/usr/bin/env python3
"""커뮤니티 새 판 점검: 새 판이 나왔는지 보고, 그 소스에 우리 손질(kit)이 그대로 들어가는지와
우리 플러그인이 새 판에서 컴파일되는지를 한 번에 확인한다. 지금 쓰는 소스 · 대본은 바꾸지 않는다.

사용법: python kit\\check_update.py [판 번호]
  판 번호  262.11234.5 처럼 점검할 판. 없으면 JetBrains 의 최신 정식판.

하는 일
  1. 지금 판(get-source.bat 의 TAG)과 점검할 판을 비교한다. 같으면 「최신」으로 끝낸다.
  2. 새 판 소스(intellij-community · android)를 intellij-community-new 에 받는다(이미 받았으면 건너뜀).
  3. 받은 새 소스에 brand · slim · 메뉴 · 기본값 대본을 실제로 적용해 본다(새 폴더라 지금 소스는 그대로).
  4. 새 판 플랫폼 jar(ideaIC-<판>.zip)를 받아 우리 플러그인 소스(kit/ei-db · boot-servers · sync)를 컴파일해 본다.
  5. 판 번호가 박혀 있어 손봐야 할 곳을 찾아 적는다.
결과는 화면과 logs\\새판점검-<판>.txt 에 남는다. 새 판으로 옮기는 일(TAG 바꾸기 · 폴더 바꾸기)은 하지 않는다.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

KIT = Path(__file__).resolve().parent
ROOT = KIT.parent
NEW_SRC = ROOT / "intellij-community-new"
TAG_MARK = ".ei-tag"
PRODUCT_NAME = "EI IDE"
COMMUNITY_GIT = "https://github.com/JetBrains/intellij-community.git"
ANDROID_GIT = "https://github.com/JetBrains/android.git"
# 2025.3 부터 커뮤니티 판 배포가 통합판(IIU)으로 합쳐져, 정식판 판 번호는 IIU 목록에서 읽는다.
RELEASES_URL = "https://data.services.jetbrains.com/products/releases?code=IIU&type=release&latest=true"
PLATFORM_ZIP_URL = ("https://www.jetbrains.com/intellij-repository/releases/"
                    "com/jetbrains/intellij/idea/ideaIC/{build}/ideaIC-{build}.zip")
BUILD_RE = re.compile(r"^\d+\.\d+\.\d+$")
TAG_IN_GET_SOURCE = re.compile(r'set "TAG=idea/([0-9.]+)"')
JAVA_RELEASE = "21"
# 우리 플러그인 소스(kit 기준). sync 는 한 폴더에 소스 · 점검이 섞여 있어 *Check.java 를 뺀다.
PLUGIN_SOURCES = {
    "ei-db": "ei-db/src",
    "boot-servers": "boot-servers/src/main/java",
    "kinggura-sync": "sync",
}
# 판 번호가 박혀 있을 수 있는 파일 종류와, 찾지 않을 폴더.
SCAN_SUFFIXES = {".bat", ".py", ".sh", ".java", ".xml", ".kt"}
SCAN_SKIP = {".git", "intellij-community", "intellij-community-new", "backup", "logs", "tmpjars", "plugins",
             "jdk", "gradle", "drivers", "__pycache__", "out", "textmate-bundles"}


def current_build():
    text = (ROOT / "get-source.bat").read_bytes().decode("cp949", errors="replace")
    m = TAG_IN_GET_SOURCE.search(text)
    if not m:
        raise SystemExit("get-source.bat 에서 TAG 를 찾지 못했습니다.")
    return m.group(1)


def latest_build():
    with urllib.request.urlopen(RELEASES_URL, timeout=30) as r:
        data = json.load(r)
    return data["IIU"][0]["build"], data["IIU"][0]["version"]


def prefix(build):
    """262.10968.63 → 262.10968. 우리 판 번호(262.10968.N)의 앞자리다."""
    return build.rsplit(".", 1)[0]


def run(cmd, cwd=None):
    """명령을 실행해 (성공 여부, 출력) 을 돌려준다. 출력은 화면에도 그대로 흘린다."""
    print("  $ " + " ".join(str(c) for c in cmd))
    p = subprocess.run([str(c) for c in cmd], cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       encoding="utf-8", errors="replace")
    print(p.stdout.rstrip())
    return p.returncode == 0, p.stdout


def get_source(build):
    """새 판 소스를 NEW_SRC 에 얕게 받는다. 다른 판이 이미 있으면 덮지 않고 멈춘다."""
    tag = "idea/" + build
    mark = NEW_SRC / TAG_MARK
    if NEW_SRC.exists():
        have = mark.read_text(encoding="utf-8").strip() if mark.is_file() else "(알 수 없음)"
        if have != tag:
            raise SystemExit(f"{NEW_SRC} 에 다른 판({have})이 있습니다. 지우거나 이름을 바꾼 뒤 다시 실행하세요.")
        print(f"새 판 소스가 이미 있습니다: {tag}")
        return
    ok, _ = run(["git", "clone", "--depth", "1", "--branch", tag, COMMUNITY_GIT, NEW_SRC])
    if not ok:
        raise SystemExit(f"{tag} 소스를 받지 못했습니다. 태그가 아직 없거나 네트워크 문제입니다. 받다 만 {NEW_SRC} 는 지운 뒤 다시 실행하세요.")
    ok, _ = run(["git", "clone", "--depth", "1", "--branch", tag, ANDROID_GIT, NEW_SRC / "android"])
    if not ok:
        raise SystemExit(f"android {tag} 를 받지 못했습니다. 받다 만 {NEW_SRC} 는 지운 뒤 다시 실행하세요.")
    mark.write_text(tag, encoding="utf-8")


def apply_kit():
    """kit 대본을 새 소스에 차례로 적용한다. 앞 단계가 실패해도 뒤 단계까지 돌려 고칠 곳을 한 번에 모은다."""
    steps = [
        ("이름 바꾸기(brand)", [KIT / "customize.py", NEW_SRC, PRODUCT_NAME, "brand"]),
        ("번들 덜기(slim)", [KIT / "customize.py", NEW_SRC, PRODUCT_NAME, "slim"]),
        ("메뉴", [KIT / "menu" / "apply_menu.py", NEW_SRC]),
        ("기본값", [KIT / "defaults" / "apply_defaults.py", NEW_SRC]),
    ]
    results = []
    for name, args in steps:
        print(f"\n== {name}")
        ok, out = run([sys.executable, *args])
        results.append((name, ok, out.strip()))
    # 그림은 apply_brand.bat 이 폴더째 복사하므로 대상 폴더가 있는지만 본다.
    missing = [d for d in ("build/idea-community-images/win", "build/idea-community-images/linux",
                           "community-resources/resources") if not (NEW_SRC / d).is_dir()]
    results.append(("그림(아이콘 · 시작 화면) 자리", not missing, "없는 폴더: " + ", ".join(missing) if missing else "있음"))
    return results


def platform_jars(build):
    """ideaIC-<판>.zip 을 tmpjars 에 받아 lib · plugins 아래 jar 만 푼 폴더를 돌려준다(이미 풀었으면 그대로)."""
    out = ROOT / "tmpjars" / f"ideaIC-{build}"
    if (out / ".done").is_file():
        return out
    zip_path = ROOT / "tmpjars" / f"ideaIC-{build}.zip"
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    if not zip_path.is_file():
        part = zip_path.with_suffix(".zip.part")
        print(f"플랫폼 jar 받는 중(수백 MB): ideaIC-{build}.zip")
        with urllib.request.urlopen(PLATFORM_ZIP_URL.format(build=build), timeout=60) as r, open(part, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
        part.replace(zip_path)
    with zipfile.ZipFile(zip_path) as z:
        for name in z.namelist():
            if name.endswith(".jar") and (name.startswith("lib/") or name.startswith("plugins/")):
                z.extract(name, out)
    (out / ".done").write_text(build, encoding="utf-8")
    return out


def find_javac():
    """~/.jdks 의 JDK 21 이상 중 하나, 없으면 JAVA_HOME, 없으면 PATH 의 javac."""
    exe = "javac.exe" if os.name == "nt" else "javac"
    jdks = sorted(Path.home().joinpath(".jdks").glob("jdk-2*"), reverse=True)
    for home in jdks + ([Path(os.environ["JAVA_HOME"])] if "JAVA_HOME" in os.environ else []):
        if (home / "bin" / exe).is_file():
            return home / "bin" / exe
    found = shutil.which("javac")
    if not found:
        raise SystemExit("javac 를 찾지 못했습니다. ~/.jdks 에 JDK 21 이상을 두거나 JAVA_HOME 을 지정하세요.")
    return Path(found)


def argfile_line(path):
    # javac 인자 파일은 따옴표 안의 \ 를 이스케이프로 읽으므로 / 로 바꿔 쓴다.
    return '"' + str(path).replace("\\", "/") + '"'


def compile_plugins(jar_dir, javac, work):
    """플러그인마다 소스를 jar_dir 아래 모든 jar 를 classpath 로 컴파일한다. (이름, 성공, 출력) 목록."""
    jars = sorted(jar_dir.rglob("*.jar"))
    results = []
    for name, rel in PLUGIN_SOURCES.items():
        sources = [p for p in (KIT / rel).rglob("*.java") if not p.name.endswith("Check.java")]
        if not sources:
            results.append((name, False, f"소스가 없습니다: kit/{rel}"))
            continue
        out = work / name
        shutil.rmtree(out, ignore_errors=True)
        out.mkdir(parents=True)
        args = work / f"{name}.args"
        lines = ["-encoding", "UTF-8", "--release", JAVA_RELEASE, "-proc:none", "-nowarn",
                 "-XDaddTypeAnnotationsToSymbol=false", "-d", argfile_line(out),
                 "-cp", argfile_line(os.pathsep.join(str(j) for j in jars))]
        lines += [argfile_line(s) for s in sources]
        args.write_text("\n".join(lines), encoding="utf-8")
        print(f"\n== 컴파일: {name} ({len(sources)}개 파일)")
        ok, text = run([javac, "@" + str(args)])
        results.append((name, ok, text.strip()))
    return results


def version_spots(old_prefix):
    """판 번호 앞자리(예: 262.10968)가 박힌 줄. 새 판이 앞자리가 다르면 이 줄들을 손봐야 한다."""
    spots = []
    for folder, dirs, files in os.walk(ROOT):
        # 소스 · 백업처럼 큰 폴더는 들어가지 않는다.
        dirs[:] = sorted(d for d in dirs if d not in SCAN_SKIP)
        for file in sorted(files):
            path = Path(folder) / file
            if path.suffix in SCAN_SUFFIXES and path != Path(__file__).resolve():
                spots += spots_in(path, old_prefix)
    return spots


def spots_in(path, old_prefix):
    spots = []
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("cp949", errors="replace")  # 윈도우 배치 파일은 CP949 로 저장돼 있다.
    for no, line in enumerate(text.splitlines(), 1):
        if old_prefix in line:
            spots.append(f"{path.relative_to(ROOT)}:{no}: {line.strip()[:140]}")
    return spots


def main():
    if len(sys.argv) > 2:
        raise SystemExit(__doc__)
    old = current_build()
    if len(sys.argv) == 2:
        new, version = sys.argv[1], "(지정한 판)"
        if not BUILD_RE.match(new):
            raise SystemExit(f"판 번호 형식이 아닙니다: {new} (예: 262.11234.5)")
    else:
        new, version = latest_build()
        if new == old:
            print(f"지금 판 {old} 이 최신 정식판입니다. 점검할 것이 없습니다.")
            return
    print(f"지금 판 {old} → 점검할 판 {new} {version}")

    get_source(new)
    kit_results = apply_kit()
    jar_dir = platform_jars(new)
    compile_results = compile_plugins(jar_dir, find_javac(), ROOT / "tmpjars" / f"compile-{new}")
    spots = version_spots(prefix(old)) if prefix(old) != prefix(new) else []

    lines = [f"새 판 점검: {old} → {new} {version}", "", "[kit 대본]"]
    for name, ok, out in kit_results:
        lines.append(f"  {'통과' if ok else '실패'}  {name}")
        if not ok:
            lines += ["      " + l for l in out.splitlines()[-15:]]
    lines += ["", "[플러그인 컴파일]"]
    for name, ok, out in compile_results:
        lines.append(f"  {'통과' if ok else '실패'}  {name}")
        if not ok:
            lines += ["      " + l for l in out.splitlines()[-40:]]
    lines += ["", "[판 번호 앞자리가 박힌 곳]"]
    if spots:
        lines.append(f"  앞자리가 {prefix(old)} → {prefix(new)} 로 바뀝니다. 옮길 때 아래 줄을 손봐야 합니다.")
        lines += ["  " + s for s in spots]
    else:
        lines.append("  앞자리가 같아 손볼 곳이 없습니다.")
    all_ok = all(ok for _, ok, _ in kit_results + compile_results)
    lines += ["", "결과: " + ("모두 통과 — 새 판으로 옮길 수 있습니다." if all_ok else "고칠 곳이 있습니다(위 실패 항목).")]
    report = "\n".join(lines)
    log = ROOT / "logs" / f"새판점검-{new}.txt"
    log.parent.mkdir(exist_ok=True)
    log.write_text(report, encoding="utf-8")
    print("\n" + report + f"\n\n기록: {log}")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
