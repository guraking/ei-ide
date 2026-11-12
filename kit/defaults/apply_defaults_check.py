#!/usr/bin/env python3
"""apply_defaults 자체 점검: 원문만 담은 가짜 소스에 모두 적용되고, 두 번째 실행은 그대로이며,
예전 판(1103 까지)이 넣은 jar 만 읽는 글꼴 등록 코드는 새 코드로 바뀌고, 옛 default-laf 줄은 지워지는지 본다."""
import subprocess
import sys
import tempfile
from pathlib import Path

import apply_defaults as d
import customize

SEPARATOR = "\n//--\n"
OLD_LAF_LINE = '  <default-laf dark="EI Glass"/>\n'


def make(root):
    files = {}
    for rel, old, _ in d.EDITS:
        files.setdefault(rel, []).append(old)
    for rel, olds in files.items():
        text = SEPARATOR.join(olds) + "\n"
        if rel == d.DEFAULT_LAF_FILE:
            text += OLD_LAF_LINE
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_bytes(text.encode("utf-8"))


def run(root):
    subprocess.run([sys.executable, str(Path(d.__file__).resolve()), str(root)], check=True, stdout=subprocess.DEVNULL)


def snapshot(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}


def main():
    # 11) 원문은 customize.py slim 이 만든 목록의 끝이다(slim 원문 뒤에는 줄바꿈이 온다).
    slim_list = next(new for rel, _, new in customize.slim_edits() if rel == d.PROPS_FILE and "removeAll" in new)
    assert (slim_list + "\n").endswith(d.SVN_BUNDLE_ORIGINAL), "11) 원문은 slim 목록 끝과 같아야 한다"
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp, "intellij-community")
        make(root)
        run(root)
        first = snapshot(root)
        for rel, _, new in d.EDITS:
            assert new in first[rel].decode("utf-8"), f"적용 안 됨: {rel}\n{new[:80]}"
        laf = first[d.DEFAULT_LAF_FILE].decode("utf-8")
        assert OLD_LAF_LINE not in laf and laf.count(d.DEFAULT_LAF_LINE) == 1, laf
        run(root)
        assert snapshot(root) == first, "두 번째 실행에서 바뀌면 안 된다"

        # 예전 판으로 손질된 소스: 새 등록 코드 자리에 옛 코드가 있다.
        font = root / d.FONT_SERVICE_FILE
        font.write_bytes(first[d.FONT_SERVICE_FILE].decode("utf-8").replace(d.FONT_REGISTER, d.FONT_REGISTER_JAR_ONLY).encode("utf-8"))
        run(root)
        assert snapshot(root) == first, "옛 글꼴 등록 코드가 새 코드로 바뀌어야 한다"
        assert first[d.FONT_SERVICE_FILE].decode("utf-8").count("private static void registerEiFonts()") == 1
    print("apply_defaults 점검 통과")


if __name__ == "__main__":
    main()
