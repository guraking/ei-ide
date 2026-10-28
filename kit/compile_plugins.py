#!/usr/bin/env python3
"""우리 플러그인(ei-db · boot-servers · kinggura-sync)을 동시에 컴파일 · 점검해 본체 jar 로 넣는다.

사용법: python kit\\compile_plugins.py [플러그인 이름 ...]
  이름을 주지 않으면 셋 다. 예: python kit\\compile_plugins.py kinggura-sync

하는 일(플러그인마다 따로 동시에)
  1. kit 소스를 지난 빌드 결과(intellij-community\\out\\idea-ce\\dist.all · dist.win.x64)의 jar 로 컴파일한다.
  2. 그 플러그인의 *Check.java 를 컴파일해 모두 돌린다.
  3. 컴파일 결과 + 리소스를 tmpjars\\compile\\<이름>.jar 로 묶는다.
모든 플러그인이 통과했을 때만 bundled-plugins\\<이름>\\lib\\<이름>.jar 를 바꾼다. 하나라도 실패하면 아무 jar 도 바꾸지 않는다.
javac 는 check_update.py 와 같은 방법으로 찾는다(~/.jdks 의 jdk-2*, JAVA_HOME, PATH 순).
"""
import os
import shutil
import subprocess
import sys
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from check_update import KIT, ROOT, JAVA_RELEASE, PLUGIN_SOURCES, MIXED_CHECK_SOURCES, argfile_line, find_javac

DIST_DIRS = [ROOT / "intellij-community/out/idea-ce/dist.all", ROOT / "intellij-community/out/idea-ce/dist.win.x64"]
WORK = ROOT / "tmpjars" / "compile"
# 플러그인 → jar 에 그대로 넣을 리소스 폴더.
RESOURCES = {
    "ei-db": KIT / "ei-db/resources",
    "boot-servers": KIT / "boot-servers/src/main/resources",
    "kinggura-sync": KIT / "sync/resources",
}
# 리소스 폴더 밖에 있어 jar 의 META-INF 로 넣는 파일.
META_INF_FILES = {"kinggura-sync": [KIT / "sync/plugin.xml", KIT / "sync/kinggura-sync-svn.xml"]}
CHECK_SOURCES = {
    "ei-db": KIT / "ei-db/test",
    "boot-servers": KIT / "boot-servers/src/test/java",
    "kinggura-sync": KIT / "sync",
}


def platform_classpath():
    """지난 빌드 결과의 jar 중 우리 플러그인 것을 뺀 목록. 우리 것은 지금 컴파일하는 소스가 대신한다."""
    jars = []
    for d in DIST_DIRS:
        if not d.is_dir():
            raise SystemExit(f"빌드 결과가 없습니다: {d}\n전체 빌드(build-windows.bat)를 한 번 끝낸 뒤 돌리세요.")
        for jar in sorted(d.rglob("*.jar")):
            if not PLUGIN_SOURCES.keys() & set(jar.parts):
                jars.append(str(jar))
    return jars


def run_args(exe, args_file, lines):
    """인자를 파일로 넘겨 실행한다(classpath 가 윈도우 명령 줄 길이 한도를 넘는다). (성공, 출력)."""
    args_file.write_text("\n".join(lines), encoding="utf-8")
    p = subprocess.run([str(exe), "@" + str(args_file)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       encoding="utf-8", errors="replace")
    return p.returncode == 0, p.stdout


def javac_lines(out, classpath, sources):
    return (["-encoding", "UTF-8", "--release", JAVA_RELEASE, "-proc:none", "-nowarn",
             "-XDaddTypeAnnotationsToSymbol=false", "-d", argfile_line(out), "-cp", argfile_line(os.pathsep.join(classpath))]
            + [argfile_line(s) for s in sources])


def check_class(path):
    """점검 파일의 완전한 클래스 이름(package 줄 + 파일 이름)."""
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("package "):
            return line.split()[1].rstrip(";") + "." + path.stem
    return path.stem


def pack(name, classes, target):
    target.unlink(missing_ok=True)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        for root in (classes, RESOURCES[name]):
            for f in sorted(root.rglob("*")):
                if f.is_file():
                    z.write(f, f.relative_to(root).as_posix())
        for f in META_INF_FILES.get(name, []):
            z.write(f, "META-INF/" + f.name)


def build(name, javac, jars):
    """한 플러그인을 컴파일 · 점검 · 묶는다. (이름, 성공, 기록)."""
    log = []
    sources = [p for p in (KIT / PLUGIN_SOURCES[name]).rglob("*.java")
               if name not in MIXED_CHECK_SOURCES or not p.name.endswith("Check.java")]
    if not sources:
        return name, False, [f"소스가 없습니다: kit/{PLUGIN_SOURCES[name]}"]
    classes = WORK / name
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    ok, text = run_args(javac, WORK / f"{name}.args", javac_lines(classes, jars, sources))
    log.append(f"컴파일 {len(sources)}개 파일: {'통과' if ok else '실패'}")
    if not ok:
        return name, False, log + text.strip().splitlines()[-30:]

    checks = sorted(CHECK_SOURCES[name].rglob("*Check.java"))
    check_classes = WORK / f"{name}-check"
    shutil.rmtree(check_classes, ignore_errors=True)
    check_classes.mkdir()
    classpath = [str(classes), str(RESOURCES[name])] + jars
    if checks:
        ok, text = run_args(javac, WORK / f"{name}-check.args", javac_lines(check_classes, classpath, checks))
        if not ok:
            return name, False, log + ["점검 컴파일 실패"] + text.strip().splitlines()[-30:]
    java = javac.with_name("java.exe" if os.name == "nt" else "java")
    run_cp = argfile_line(os.pathsep.join([str(check_classes)] + classpath))
    for check in checks:
        cls = check_class(check)
        ok, text = run_args(java, WORK / f"{name}-run.args", ["-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", run_cp, cls])
        tail = text.strip().splitlines()
        log.append(f"{'통과' if ok else '실패'} {cls}: {tail[-1] if tail else ''}")
        if not ok:
            return name, False, log + tail[-20:]

    pack(name, classes, WORK / f"{name}.jar")
    return name, True, log


def main():
    names = sys.argv[1:] or list(PLUGIN_SOURCES)
    unknown = [n for n in names if n not in PLUGIN_SOURCES]
    if unknown:
        raise SystemExit(f"모르는 플러그인: {', '.join(unknown)} (있는 것: {', '.join(PLUGIN_SOURCES)})")
    javac = find_javac()
    jars = platform_classpath()
    WORK.mkdir(parents=True, exist_ok=True)
    print(f"javac: {javac}, classpath jar {len(jars)}개, 동시 {len(names)}개")
    with ThreadPoolExecutor(len(names)) as pool:
        results = list(pool.map(lambda n: build(n, javac, jars), names))
    for name, ok, log in results:
        print(f"\n== {name}: {'통과' if ok else '실패'}")
        print("\n".join("  " + line for line in log))
    if not all(ok for _, ok, _ in results):
        raise SystemExit("\n실패한 플러그인이 있어 본체 jar 를 하나도 바꾸지 않았습니다.")
    for name in names:
        target = ROOT / "bundled-plugins" / name / "lib" / f"{name}.jar"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(WORK / f"{name}.jar", target)
        print(f"넣음: {target}")


if __name__ == "__main__":
    main()
