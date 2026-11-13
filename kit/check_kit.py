#!/usr/bin/env python3
"""빌드 전에 kit 손질이 모두 들어갔는지 본다. build-windows.bat 이 빌드 전에 부른다.

사용법: python kit\\check_kit.py
각 손질 대본이 넣는 글(대본의 상수)이 소스에 그대로 있는지, 설치본에 넣을 파일이 kit 과 같은지 본다.
빠진 단계가 있으면 그 단계와 돌릴 명령을 적고 1 로 끝난다.
"""
import importlib.util
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent
ROOT = KIT.parent
sys.path.insert(0, str(KIT))
import compile_plugins  # noqa: E402
import customize  # noqa: E402
from check_update import PRODUCT_NAME  # noqa: E402
from patchlib import is_applied, read_source  # noqa: E402

SRC_DIR = "intellij-community"
# apply_brand.bat 이 복사하는 kit 그림 폴더 → 소스 안 자리.
BRAND_DIRS = {"win": "build/idea-community-images/win", "linux": "build/idea-community-images/linux",
              "res": "community-resources/resources"}
KO_LIB = "bundled-plugins/localization-ko/lib"


def load(rel):
    """kit 아래 대본을 모듈로 읽는다(폴더마다 이름이 겹칠 수 있어 경로로 읽는다)."""
    path = KIT / rel
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def edits_applied(src, edits):
    texts = {}
    for rel, old, new in edits:
        path = src / rel
        if not path.is_file():
            return False
        if rel not in texts:
            texts[rel] = read_source(path)[0]
        if not is_applied(texts[rel], old, new):
            return False
    return True


def same_files(source_dir, target_dir, names=None):
    """source_dir 의 파일(names 를 주면 그 이름만)이 target_dir 에 같은 내용으로 모두 있는지. 비교할 파일이 없으면 False."""
    files = [source_dir / n for n in names] if names else [f for f in source_dir.iterdir() if f.is_file()]
    return bool(files) and all(f.is_file() and (target_dir / f.name).is_file()
                               and (target_dir / f.name).read_bytes() == f.read_bytes() for f in files)


def menu_applied(src):
    menu = load("menu/apply_menu.py")
    module = src / menu.MODULE
    xml = module / menu.PLUGIN_XML
    java = KIT / "menu" / "KingGuraMainMenuCustomizer.java"
    target = module / menu.JAVA_DIR / java.name
    return (xml.is_file() and menu.MARK in read_source(xml)[0]
            and target.is_file() and target.read_bytes() == java.read_bytes())


def git_auth_applied(src):
    auth = load("git-auth/apply_git_auth.py")
    target = src / auth.TARGET
    return (target.is_file() and auth.HOOK in read_source(target)[0]
            and same_files(KIT / "git-auth", src / auth.COMMANDS, auth.NEW_FILES))


def missing_steps(root):
    """root(작업 폴더: intellij-community · bundled-plugins 가 있는 곳)에서 빠진 단계: (설명, 돌릴 명령) 목록."""
    src = root / SRC_DIR
    defaults = load("defaults/apply_defaults.py")
    textmate = load("textmate/apply_textmate.py")
    defender = load("defender/apply_defender.py")
    git_retry = load("git-retry/apply_git_retry.py")
    steps = [
        ("이름 바꾸기(brand)", f'python kit\\customize.py {SRC_DIR} "{PRODUCT_NAME}" brand',
         lambda: edits_applied(src, customize.brand_edits(PRODUCT_NAME))),
        ("번들 덜기(slim)", f'python kit\\customize.py {SRC_DIR} "{PRODUCT_NAME}" slim',
         lambda: edits_applied(src, customize.slim_edits())),
        ("이클립스 메뉴", f"python kit\\menu\\apply_menu.py {SRC_DIR}", lambda: menu_applied(src)),
        ("기본값", f"python kit\\defaults\\apply_defaults.py {SRC_DIR}", lambda: edits_applied(src, defaults.EDITS)),
        ("Git 인증", f"python kit\\git-auth\\apply_git_auth.py {SRC_DIR}", lambda: git_auth_applied(src)),
        ("TextMate 엔진", f"python kit\\textmate\\apply_textmate.py {SRC_DIR}",
         lambda: edits_applied(src, [(textmate.TARGET, textmate.ORIGINAL, textmate.PATCHED)])),
        ("Defender 제외", f"python kit\\defender\\apply_defender.py {SRC_DIR}",
         lambda: edits_applied(src, [(defender.TARGET, defender.ORIGINAL, defender.PATCHED)])),
        ("Git 다시 하기", f"python kit\\git-retry\\apply_git_retry.py {SRC_DIR}", lambda: edits_applied(src, git_retry.EDITS)),
        ("한국어 팩", "python kit\\ko\\apply_ko.py", lambda: any((root / KO_LIB).glob("*.jar"))),
        ("그림(아이콘 · 시작 화면)", "kit\\brand\\apply_brand.bat",
         lambda: all(same_files(KIT / "brand" / d, src / t) for d, t in BRAND_DIRS.items())),
        ("Moa Mono 글꼴 파일", "python kit\\compile_plugins.py",
         lambda: same_files(compile_plugins.FONT_SOURCE, root / compile_plugins.FONT_TARGET.relative_to(ROOT))),
    ]
    return [(label, command) for label, command, applied in steps if not applied()]


def main():
    missing = missing_steps(ROOT)
    if not missing:
        print("kit 손질 확인: 모두 들어 있음")
        return
    lines = [f"  {label}: {command}" for label, command in missing]
    raise SystemExit("kit 손질이 빠진 소스입니다. 아래 단계를 먼저 실행한 뒤 다시 빌드하세요.\n" + "\n".join(lines))


if __name__ == "__main__":
    main()
