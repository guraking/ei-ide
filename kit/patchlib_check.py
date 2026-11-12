#!/usr/bin/env python3
"""patchlib 자체 점검: 고침 · 이미 적용됨 판단 · 줄 덧붙이기 · 지우기 · 후보 묶음 · 거절 · 줄끝 보존 · 모두 아니면 아무것도."""
import tempfile
from pathlib import Path

import patchlib as p

# 바꾸기: 고친 뒤 다시 돌리면 그대로.
assert p.edit("x = false;\n", "x = false;", "x = true;") == "x = true;\n"
assert p.edit("x = true;\n", "x = false;", "x = true;") is None
# 바꿀 글이 원본 다른 곳에 우연히 있어도 원문이 남아 있으면 고친다.
assert p.edit("y -> true);\nx -> isSet());\n", "x -> isSet());", "x -> true);") == "y -> true);\nx -> true);\n"
# 줄 덧붙이기(바꿀 글이 원문을 품음): 한 번만 덧붙는다.
once = p.edit("a\nanchor\n", "anchor\n", "added\nanchor\n")
assert once == "a\nadded\nanchor\n" and p.edit(once, "anchor\n", "added\nanchor\n") is None
# 지우기: 원문이 없으면 적용된 것.
assert p.edit("a\nb\n", "b\n", "") == "a\n" and p.edit("a\n", "b\n", "") is None
# 후보 묶음: 글에 있는 첫 후보를 고친다.
assert p.edit('n="Old"', ('n="Orig"', 'n="Old"'), 'n="New"') == 'n="New"'
# 원문이 없거나 둘이면 거절.
for bad in ("nothing\n", "x = false;\nx = false;\n"):
    try:
        p.edit(bad, "x = false;", "x = true;")
        raise AssertionError("거절해야 함: " + bad)
    except ValueError:
        pass

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp, "src")
    root.mkdir()
    (root / "crlf.txt").write_bytes(b"x = false;\r\nz\r\n")
    (root / "lf.txt").write_bytes(b"k = 1\n")
    # 하나라도 못 찾으면 아무 파일도 바꾸지 않는다.
    try:
        p.apply_edits(root, [("crlf.txt", "x = false;", "x = true;"), ("lf.txt", "missing", "m")])
        raise AssertionError("멈춰야 함")
    except SystemExit:
        pass
    assert (root / "crlf.txt").read_bytes() == b"x = false;\r\nz\r\n"
    changed = p.apply_edits(root, [("crlf.txt", "x = false;\nz", "x = true;\nz"), ("lf.txt", "k = 1", "k = 2")], tag="t")
    assert changed == ["crlf.txt", "lf.txt"], changed
    assert (root / "crlf.txt").read_bytes() == b"x = true;\r\nz\r\n", "CRLF 파일은 CRLF 로 남아야 한다"
    backups = list(Path(tmp, "backup").glob("*-before-t"))
    assert len(backups) == 1 and (backups[0] / "crlf.txt").read_bytes() == b"x = false;\r\nz\r\n"
    assert p.apply_edits(root, [("lf.txt", "k = 1", "k = 2")]) == []
print("patchlib 점검 통과")
