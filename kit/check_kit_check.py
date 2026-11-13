#!/usr/bin/env python3
"""check_kit 점검: 원문만 담은 가짜 소스에 kit 대본을 빌드 순서대로 모두 돌리면 빠진 단계가 없고,
빈 작업 폴더에서는 모든 단계가 빠졌다고 나오며, 한 단계(Defender)를 되돌리면 그 단계만 나오는지 본다."""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import check_kit as c

KIT = c.KIT
PROPS = c.customize.PROPS
APP_INFO = c.customize.APP_INFO


def run(*args):
    subprocess.run([sys.executable, *map(str, args)], check=True, stdout=subprocess.DEVNULL)


def make_source(src):
    """brand · slim · 기본값 · 메뉴 · Git 인증 · Defender 의 원문을 담은 가짜 소스."""
    defaults = c.load("defaults/apply_defaults.py")
    texts = {}
    for rel, old, _ in defaults.EDITS:
        if old != defaults.SVN_BUNDLE_ORIGINAL:  # slim 이 만든다
            texts.setdefault(rel, []).append(old)
    texts.setdefault(APP_INFO, []).append(
        '<names fullname="IntelliJ IDEA" motto="The Leading IDE for Professional Development in Java and Kotlin"/>\n')
    texts.setdefault(PROPS, []).append(
        'fullName { "IntelliJ IDEA Open Source" }\ninstallDirNameHandler { "IntelliJ IDEA OSS" }\n'
        'return "IdeaIC${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"\nx = "ideaIC-$buildNumber"\n')
    for _, old, _ in c.customize.slim_edits():
        texts[PROPS].append(old + "\n")
    defender = c.load("defender/apply_defender.py")
    texts.setdefault(defender.TARGET, []).append(defender.ORIGINAL)
    retry = c.load("git-retry/apply_git_retry.py")
    texts.setdefault(retry.TARGET, []).extend(old for _, old, _ in retry.EDITS)
    auth = c.load("git-auth/apply_git_auth.py")
    texts.setdefault(auth.TARGET, []).append(auth.ANCHOR)
    menu = c.load("menu/apply_menu.py")
    texts[f"{menu.MODULE}/{menu.PLUGIN_XML}"] = ["<idea-plugin>\n  <extensions>\n  </extensions>\n</idea-plugin>\n"]
    (src / menu.MODULE / menu.JAVA_DIR).mkdir(parents=True)
    for rel, parts in texts.items():
        (src / rel).parent.mkdir(parents=True, exist_ok=True)
        (src / rel).write_bytes("\n//--\n".join(parts).encode("utf-8"))


def apply_all(root):
    src = root / c.SRC_DIR
    run(KIT / "customize.py", src, c.PRODUCT_NAME, "brand")
    run(KIT / "customize.py", src, c.PRODUCT_NAME, "slim")
    for script in ("menu/apply_menu.py", "defaults/apply_defaults.py", "git-auth/apply_git_auth.py",
                   "defender/apply_defender.py", "git-retry/apply_git_retry.py"):
        run(KIT / script, src)
    for folder, target in c.BRAND_DIRS.items():
        shutil.copytree(KIT / "brand" / folder, src / target, dirs_exist_ok=True)
    (root / c.KO_LIB).mkdir(parents=True)
    (root / c.KO_LIB / "localization-ko.jar").write_bytes(b"")
    shutil.copytree(c.compile_plugins.FONT_SOURCE, root / c.compile_plugins.FONT_TARGET.relative_to(c.ROOT))


def main():
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp, "work")
        # 글꼴은 커서(67MB) 작은 가짜 원본 폴더로 바꿔 본다.
        fonts = Path(tmp, "fonts")
        fonts.mkdir()
        (fonts / "Fake.ttf").write_bytes(b"ttf")
        c.compile_plugins.FONT_SOURCE = fonts
        assert len(c.missing_steps(root)) == 10, c.missing_steps(root)
        make_source(root / c.SRC_DIR)
        apply_all(root)
        missing = c.missing_steps(root)
        assert missing == [], missing
        defender = c.load("defender/apply_defender.py")
        target = root / c.SRC_DIR / defender.TARGET
        target.write_bytes(target.read_bytes().replace(defender.PATCHED.encode(), defender.ORIGINAL.encode()))
        assert [label for label, _ in c.missing_steps(root)] == ["Defender 제외"], c.missing_steps(root)
    print("check_kit 점검 통과")


if __name__ == "__main__":
    main()
