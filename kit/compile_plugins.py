#!/usr/bin/env python3
"""우리 플러그인(ei-db · boot-servers · kinggura-sync)을 동시에 컴파일 · 점검해 본체 jar 로 넣는다.

사용법: python kit\\compile_plugins.py [플러그인 이름 ...]
  이름을 주지 않으면 셋 다. 예: python kit\\compile_plugins.py kinggura-sync

하는 일
  0. kit 손질 대본 점검(KIT_CHECKS · KIT_JAVA_CHECKS)을 먼저 돌린다. 하나라도 실패하면 컴파일하지 않고 멈춘다.
  플러그인마다 따로 동시에
  1. kit 소스를 지난 빌드 결과(intellij-community\\out\\idea-ce\\dist.all · dist.win.x64)의 jar 로 컴파일한다.
  2. 그 플러그인의 *Check.java 를 컴파일해 모두 돌린다(점검마다 JVM 하나, CHECK_WORKERS 개씩 동시에).
  3. 컴파일 결과 + 리소스 + BUNDLED_LIBS(판 · SHA-256 고정, Maven Central 에서 받아 tmpjars\\libs 에 둠)를 tmpjars\\compile\\<이름>.jar 로 묶는다.
모든 플러그인이 통과했을 때만 bundled-plugins\\<이름>\\lib\\<이름>.jar 를 바꾼다. 하나라도 실패하면 아무 jar 도 바꾸지 않는다.
먼저 Moa Mono 글꼴 파일을 bundled-plugins\\boot-servers\\fonts 에 맞춘다(설치본 plugins\\boot-servers\\fonts, IDE 가 켤 때 파일 그대로 등록).
  kit 원본을 옮기기만 하므로 JDK · 지난 빌드 결과가 없어 컴파일이 멈추는 PC(첫 빌드 · 노트북)에서도 build-windows.bat 의 글꼴 확인을 통과한다.
javac 는 check_update.find_javac(~/.jdks 의 JDK 21 이상 중 가장 높은 판 → JAVA_HOME → PATH)로 찾는다.
"""
import filecmp
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

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
META_INF_FILES = {"kinggura-sync": [KIT / "sync/plugin.xml", KIT / "sync/kinggura-sync-svn.xml",
                                    KIT / "sync/kinggura-sync-maven.xml"]}
CHECK_SOURCES = {
    "ei-db": KIT / "ei-db/test",
    "boot-servers": KIT / "boot-servers/src/test/java",
    "kinggura-sync": KIT / "sync",
}
PLUGIN_XML = "META-INF/plugin.xml"
# 플러그인 jar 에 클래스째 함께 묶는 라이브러리: (Maven Central 경로, SHA-256). 받은 jar 는 LIB_DIR 에 두고 다음부터 그것을 쓴다.
# 플러그인 클래스 로더가 자기 jar 를 먼저 찾으므로 IDE 안 다른 판(설정 동기화의 JGit 등)과 섞이지 않는다.
MAVEN_CENTRAL = "https://repo1.maven.org/maven2/"
BUNDLED_LIBS = {
    # 동기화 창의 읽기 · 조회(kinggura.sync.JGitRead). JGit 이 부르는 slf4j-api · commons-codec 은 IDE 본체 jar 에 있어 넣지 않는다.
    "kinggura-sync": [
        ("org/eclipse/jgit/org.eclipse.jgit/7.8.0.202609011348-r/org.eclipse.jgit-7.8.0.202609011348-r.jar",
         "cc63976f92e8058d05a543f320a6237accf2f17b745ab20946f246ac0b54dfd6"),
        ("com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3.jar",
         "d65226949713c4c61a784f41c51167e7b0316f93764398ebba9e4336b3d954c2"),
    ],
}
LIB_DIR = ROOT / "tmpjars" / "libs"
LIB_DOWNLOAD_TIMEOUT_SEC = 300
# 묶는 라이브러리에서 빼는 것: 그 jar 자신의 매니페스트 · 서명(남으면 우리 jar 가 서명이 깨진 jar 로 읽힌다) · 모듈 정보.
LIB_SKIP = re.compile(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))|(.*/)?module-info\.class", re.IGNORECASE)
# plugin.xml 에서 클래스 이름을 담는 속성.
CLASS_ATTRS = ("implementation", "class", "instance", "serviceImplementation", "implementationClass",
               "factoryClass", "provider")
# 우리 패키지 클래스 이름만 jar 안에 있는지 본다(플랫폼 클래스는 다른 jar 에 있다).
OUR_CLASS = re.compile(r"(?:local|ei|kinggura)\.[\w.$]+")
ICON_ATTR = "icon"
# jar 의 plugin.xml 에 넣는 판(묶은 때 「년.월일.시분」). 같은 id 가 본체와 설정 폴더 plugins 에 함께 있으면 IntelliJ 는 판이 높은 쪽을 띄우고,
# 판이 없으면 IDE 판(262.10968.N)으로 보아 같을 때 먼저 찾은 설정 폴더 쪽을 띄운다. 묶은 때를 판으로 쓰면 언제나 나중에 묶은 jar 가 뜬다.
VERSION_STAMP = time.strftime("%Y.%m%d.%H%M")
ID_END = "</id>"
# 플러그인 하나의 점검을 동시에 돌릴 JVM 수. 플러그인 셋이 함께 돌므로 코어 수를 나눠 쓴다.
CHECK_WORKERS = max(2, (os.cpu_count() or 1) // len(PLUGIN_SOURCES))
# 컴파일 전에 돌리는 kit 손질 대본 점검(파이썬). 각 파일 폴더에서 돌린다(같은 폴더의 대본을 import 한다).
KIT_CHECKS = [KIT / "customize_check.py", KIT / "patchlib_check.py", KIT / "check_kit_check.py",
              KIT / "defaults" / "apply_defaults_check.py", KIT / "ko" / "apply_ko_check.py",
              KIT / "textmate" / "apply_textmate_check.py", KIT / "defender" / "apply_defender_check.py"]
# 순수 자바 점검: (소스 폴더, 점검 클래스, 함께 컴파일할 파일). IntelliJ 클래스 없이 JDK 만으로 컴파일 · 실행한다.
KIT_JAVA_CHECKS = [(KIT / "git-auth", "git4idea.commands.EiGitAuthCheck", ["EiGitAuthCheck.java", "EiGitAuthText.java"])]
# Moa Mono 글꼴: kit 원본 폴더 → 설치본에 파일로 들어갈 폴더. 원본 폴더의 파일(TTF · 라이선스)을 그대로 맞춘다.
FONT_SOURCE = KIT / "boot-servers" / "src" / "main" / "resources" / "fonts"
FONT_TARGET = ROOT / "bundled-plugins" / "boot-servers" / "fonts"


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


def stamp_version(xml):
    """plugin.xml 글의 </id> 뒤에 <version>VERSION_STAMP</version> 을 넣는다. 이미 판이 있거나 </id> 가 없으면 멈춘다."""
    if "<version>" in xml or ID_END not in xml:
        raise SystemExit("plugin.xml 에 <version> 이 이미 있거나 </id> 가 없습니다.")
    return xml.replace(ID_END, f"{ID_END}\n  <version>{VERSION_STAMP}</version>", 1)


def pack(name, classes, target):
    target.unlink(missing_ok=True)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        files = [(f, f.relative_to(root).as_posix()) for root in (classes, RESOURCES[name])
                 for f in sorted(root.rglob("*")) if f.is_file()]
        files += [(f, "META-INF/" + f.name) for f in META_INF_FILES.get(name, [])]
        written = set()
        for f, arcname in files:
            written.add(arcname)
            if arcname == PLUGIN_XML:
                z.writestr(arcname, stamp_version(f.read_text(encoding="utf-8")))
            else:
                z.write(f, arcname)
        # 앞 것이 이긴다: 우리 파일, 그다음 BUNDLED_LIBS 순서. 같은 이름(라이선스 글 등)은 한 번만 넣는다.
        for lib in bundled_libs(name):
            with zipfile.ZipFile(lib) as source:
                for info in source.infolist():
                    if info.is_dir() or info.filename in written or LIB_SKIP.fullmatch(info.filename):
                        continue
                    written.add(info.filename)
                    z.writestr(info.filename, source.read(info), zipfile.ZIP_DEFLATED)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bundled_libs(name):
    """name 플러그인에 묶을 라이브러리 jar 들. LIB_DIR 에 없거나 SHA-256 이 다르면 받는다. 받은 것도 다르면 멈춘다."""
    jars = []
    for path, expected in BUNDLED_LIBS.get(name, []):
        jar = LIB_DIR / path.rsplit("/", 1)[1]
        if not jar.is_file() or sha256(jar) != expected:
            LIB_DIR.mkdir(parents=True, exist_ok=True)
            part = jar.with_name(jar.name + ".part")
            try:
                with urllib.request.urlopen(MAVEN_CENTRAL + path, timeout=LIB_DOWNLOAD_TIMEOUT_SEC) as response, part.open("wb") as out:
                    shutil.copyfileobj(response, out)
            except OSError as e:
                part.unlink(missing_ok=True)
                raise SystemExit(f"라이브러리를 받지 못했습니다: {MAVEN_CENTRAL + path}\n{e}")
            actual = sha256(part)
            if actual != expected:
                part.unlink()
                raise SystemExit(f"받은 라이브러리의 SHA-256 이 다릅니다: {jar.name}\n기대 {expected}\n실제 {actual}")
            os.replace(part, jar)
        jars.append(jar)
    return jars


def missing_in_jar(jar):
    """jar 의 plugin.xml 과 그것이 config-file 로 부르는 xml 에 적힌 우리 클래스 · /아이콘 중 jar 에 없는 것."""
    missing = []
    with zipfile.ZipFile(jar) as z:
        entries = set(z.namelist())
        xmls, seen = [PLUGIN_XML], set()
        while xmls:
            xml = xmls.pop()
            if xml in seen:
                continue
            seen.add(xml)
            if xml not in entries:
                missing.append(xml)
                continue
            for el in ET.fromstring(z.read(xml)).iter():
                if el.get("config-file"):
                    xmls.append("META-INF/" + el.get("config-file"))
                for attr in CLASS_ATTRS:
                    value = (el.get(attr) or "").strip()
                    if OUR_CLASS.fullmatch(value) and value.replace(".", "/") + ".class" not in entries:
                        missing.append(f"{xml}: {attr}={value}")
                icon = (el.get(ICON_ATTR) or "").strip()
                if icon.startswith("/") and icon[1:] not in entries:
                    missing.append(f"{xml}: {ICON_ATTR}={icon}")
    return missing


def install(names):
    """모든 jar 를 <이름>.jar.new 로 먼저 복사한 뒤 바꾼다. 복사나 바꾸기가 하나라도 실패하면 모두 원래대로 둔다."""
    targets = {n: ROOT / "bundled-plugins" / n / "lib" / f"{n}.jar" for n in names}
    staged = []
    try:
        for name, target in targets.items():
            target.parent.mkdir(parents=True, exist_ok=True)
            new = target.with_name(target.name + ".new")
            staged.append(new)
            shutil.copy2(WORK / f"{name}.jar", new)
    except OSError as e:
        for new in staged:
            new.unlink(missing_ok=True)
        raise SystemExit(f"\n새 jar 복사 실패(IDE 가 켜져 jar 를 잡고 있는지 확인하세요): {e}\n본체 jar 를 하나도 바꾸지 않았습니다.")
    replaced = []
    try:
        for target in targets.values():
            old = target.with_name(target.name + ".old")
            # 앞서 멈춘 실행이 남긴 .old 는 이번 되돌리기에 쓰지 않는다. 원래 jar 가 없던 자리는 old=None(되돌릴 때 새 jar 를 지움).
            old.unlink(missing_ok=True)
            if target.exists():
                shutil.copy2(target, old)
            else:
                old = None
            replaced.append((target, old))
            os.replace(target.with_name(target.name + ".new"), target)
    except OSError as e:
        restore_errors = []
        for target, old in replaced:
            try:
                if old is not None:
                    os.replace(old, target)
                else:
                    target.unlink(missing_ok=True)
            except OSError as re_err:
                restore_errors.append(f"{target}: {re_err}")
        for new in staged:
            new.unlink(missing_ok=True)
        detail = "\n되돌리지 못한 jar(.old 로 직접 되돌리세요):\n" + "\n".join(restore_errors) if restore_errors else ""
        raise SystemExit(f"\n본체 jar 바꾸기 실패(IDE 가 켜져 jar 를 잡고 있는지 확인하세요): {e}\n바꾼 jar 를 되돌렸습니다.{detail}")
    for target, old in replaced:
        if old is not None:
            old.unlink(missing_ok=True)
        print(f"넣음: {target}")


def run_check(java, run_cp, cls, args_file):
    """점검 클래스 하나를 JVM 하나로 돌린다. (클래스, 성공, 출력 줄)."""
    ok, text = run_args(java, args_file, ["-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", run_cp, cls])
    return cls, ok, text.strip().splitlines()


def build(name, javac, jars, work=None):
    """한 플러그인을 컴파일 · 점검 · 묶는다. (이름, 성공, 기록). work 를 주지 않으면 부를 때의 WORK."""
    work = work or WORK
    log = []
    sources = [p for p in (KIT / PLUGIN_SOURCES[name]).rglob("*.java")
               if name not in MIXED_CHECK_SOURCES or not p.name.endswith("Check.java")]
    if not sources:
        return name, False, [f"소스가 없습니다: kit/{PLUGIN_SOURCES[name]}"]
    classes = work / name
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    jars = jars + [str(lib) for lib in bundled_libs(name)]
    ok, text = run_args(javac, work / f"{name}.args", javac_lines(classes, jars, sources))
    log.append(f"컴파일 {len(sources)}개 파일: {'통과' if ok else '실패'}")
    if not ok:
        return name, False, log + text.strip().splitlines()[-30:]

    checks = sorted(CHECK_SOURCES[name].rglob("*Check.java"))
    check_classes = work / f"{name}-check"
    shutil.rmtree(check_classes, ignore_errors=True)
    check_classes.mkdir()
    classpath = [str(classes), str(RESOURCES[name])] + jars
    if checks:
        ok, text = run_args(javac, work / f"{name}-check.args", javac_lines(check_classes, classpath, checks))
        if not ok:
            return name, False, log + ["점검 컴파일 실패"] + text.strip().splitlines()[-30:]
    java = javac.with_name("java.exe" if os.name == "nt" else "java")
    run_cp = argfile_line(os.pathsep.join([str(check_classes)] + classpath))
    with ThreadPoolExecutor(CHECK_WORKERS) as pool:
        runs = list(pool.map(lambda cls: run_check(java, run_cp, cls, work / f"{name}-run-{cls}.args"),
                             [check_class(check) for check in checks]))
    failed = []
    for cls, ok, tail in runs:
        log.append(f"{'통과' if ok else '실패'} {cls}: {tail[-1] if tail else ''}")
        if not ok:
            failed += [f"-- {cls}"] + tail[-20:]
    if failed:
        return name, False, log + failed

    pack(name, classes, work / f"{name}.jar")
    missing = missing_in_jar(work / f"{name}.jar")
    log.append(f"{'실패' if missing else '통과'} plugin.xml 이 부르는 클래스 · 아이콘: jar 에 없는 것 {len(missing)}개")
    if missing:
        return name, False, log + missing
    return name, True, log


def kit_checks(javac):
    """kit 손질 대본 점검을 모두 돌린다. (이름, 성공, 출력 줄) 목록."""
    results = []
    for check in KIT_CHECKS:
        # 점검이 쓰는 한글을 utf-8 로 받는다(윈도우 파이프 기본은 cp949).
        p = subprocess.run([sys.executable, check.name], cwd=check.parent, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           encoding="utf-8", errors="replace", env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        results.append((check.relative_to(KIT).as_posix(), p.returncode == 0, p.stdout.strip().splitlines()))
    java = javac.with_name("java.exe" if os.name == "nt" else "java")
    for folder, cls, files in KIT_JAVA_CHECKS:
        out = WORK / ("kit-" + folder.name)
        shutil.rmtree(out, ignore_errors=True)
        out.mkdir(parents=True)
        ok, text = run_args(javac, WORK / f"kit-{folder.name}.args",
                            ["-encoding", "UTF-8", "--release", JAVA_RELEASE, "-proc:none", "-d", argfile_line(out)]
                            + [argfile_line(folder / f) for f in files])
        if ok:
            _, ok, lines = run_check(java, argfile_line(out), cls, WORK / f"kit-{folder.name}-run.args")
        else:
            lines = ["컴파일 실패"] + text.strip().splitlines()
        results.append((cls, ok, lines))
    return results


def sync_fonts():
    """FONT_SOURCE 의 파일을 FONT_TARGET 에 맞춘다(다른 것만 복사, 원본에 없는 파일은 지움). 바꾼 파일 수."""
    if not FONT_SOURCE.is_dir():
        raise SystemExit(f"글꼴 원본 폴더가 없습니다: {FONT_SOURCE}")
    FONT_TARGET.mkdir(parents=True, exist_ok=True)
    sources = {f.name: f for f in FONT_SOURCE.iterdir() if f.is_file()}
    count = 0
    for name, src in sorted(sources.items()):
        dest = FONT_TARGET / name
        if dest.is_file() and filecmp.cmp(src, dest, shallow=True):
            continue
        # 임시 이름으로 다 복사한 뒤 바꿔 넣어, 끊겨도 반쯤 쓴 글꼴 파일이 제 이름으로 남지 않게 한다.
        temp = dest.with_name(name + ".new")
        shutil.copy2(src, temp)
        os.replace(temp, dest)
        count += 1
    for old in FONT_TARGET.iterdir():
        if old.name not in sources:
            old.unlink()
            count += 1
    return count


def main():
    names = sys.argv[1:] or list(PLUGIN_SOURCES)
    unknown = [n for n in names if n not in PLUGIN_SOURCES]
    if unknown:
        raise SystemExit(f"모르는 플러그인: {', '.join(unknown)} (있는 것: {', '.join(PLUGIN_SOURCES)})")
    print(f"글꼴 파일: {FONT_TARGET} ({sync_fonts()}개 바꿈)")
    javac = find_javac()
    WORK.mkdir(parents=True, exist_ok=True)
    checks = kit_checks(javac)
    for label, ok, lines in checks:
        print(f"{'통과' if ok else '실패'} kit 점검 {label}: {lines[-1] if lines else ''}")
        if not ok:
            print("\n".join("  " + line for line in lines[-20:]))
    if not all(ok for _, ok, _ in checks):
        raise SystemExit("\nkit 점검이 실패해 컴파일하지 않았습니다. 본체 jar 를 하나도 바꾸지 않았습니다.")
    jars = platform_classpath()
    print(f"javac: {javac}, classpath jar {len(jars)}개, 동시 {len(names)}개")
    with ThreadPoolExecutor(len(names)) as pool:
        results = list(pool.map(lambda n: build(n, javac, jars), names))
    for name, ok, log in results:
        print(f"\n== {name}: {'통과' if ok else '실패'}")
        print("\n".join("  " + line for line in log))
    if not all(ok for _, ok, _ in results):
        raise SystemExit("\n실패한 플러그인이 있어 본체 jar 를 하나도 바꾸지 않았습니다.")
    install(names)


if __name__ == "__main__":
    main()
