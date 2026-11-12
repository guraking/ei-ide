#!/usr/bin/env python3
"""소스 손질 대본(customize · apply_defaults · apply_menu · textmate · defender · git-auth)이 같이 쓰는 읽기 · 고치기 · 쓰기.

edit(text, old, new) 의 판단
  - 이미 적용됨: new 가 글에 있고, 원문 후보가 글에 없거나 new 안에 들어 있는 원문(줄 덧붙이기)일 때.
    new 가 빈 글(지우기)이면 원문 후보가 하나도 없을 때.
  - 고침: 원문 후보 중 글에 있는 첫 후보가 정확히 한 번 있을 때. new 가 원본 다른 곳에 우연히 있어도 원문이 남아 있으면 고친다.
  - 그 밖(원문이 없거나 둘 이상): ValueError.
"""
import shutil
import time


def read_source(path):
    """줄끝을 \\n 으로 맞춘 글과 원래 줄끝을 돌려준다. 원문 비교는 \\n 기준이고, 쓸 때 원래 줄끝으로 되돌린다.
    모든 줄이 \\r\\n 일 때만 바꾼다. LF · CRLF 가 섞인 파일은 그대로 두어(쓸 때도 그대로) 한쪽으로 통일되지 않게 한다.
    섞인 파일에서 CRLF 줄에 걸친 원문은 찾지 못해 「못 찾음」으로 멈춘다(파일은 쓰지 않는다)."""
    raw = path.read_bytes().decode("utf-8")
    crlf = raw.count("\r\n")
    if crlf > 0 and crlf == raw.count("\n"):
        return raw.replace("\r\n", "\n"), "\r\n"
    return raw, "\n"


def encode_source(text, newline):
    return text.replace("\n", newline).encode("utf-8")


def write_source(path, text, newline):
    # 바이트로 써서 운영체제가 줄끝을 바꾸지 않게 한다(윈도우의 write_text 는 \n 을 \r\n 으로 바꾼다).
    path.write_bytes(encode_source(text, newline))


def is_applied(text, old, new):
    """old(원문 하나 또는 후보 묶음)를 new 로 바꾼 상태인지."""
    candidates = old if isinstance(old, tuple) else (old,)
    if not new:
        return all(c not in text for c in candidates)
    return new in text and all(c not in text or c in new for c in candidates)


def edit(text, old, new):
    """고친 글. 이미 적용됐으면 None. 원문(후보 중 글에 있는 첫 것)이 정확히 한 번 있지 않으면 ValueError."""
    if is_applied(text, old, new):
        return None
    candidates = old if isinstance(old, tuple) else (old,)
    found = next((c for c in candidates if c in text), candidates[0])
    count = text.count(found)
    if count != 1:
        raise ValueError(f"원문을 정확히 한 번 찾지 못했습니다({count}번): {found[:80]}")
    return text.replace(found, new)


def backup_dir(root, tag):
    """고치기 전 원본을 둘 폴더: <소스>/../backup/<시각>-before[-tag]."""
    return root.parent / "backup" / (time.strftime("%Y%m%d-%H%M%S") + "-before" + (f"-{tag}" if tag else ""))


def apply_edits(root, edits, tag=None, backup=True, prepare=None):
    """edits = [(상대 경로, 원문, 바꿀 글)] 를 모두 메모리에서 고쳐 본 뒤, 하나라도 못 하면 아무 파일도 쓰지 않고 멈춘다.
    prepare(rel, text) 는 파일을 읽은 직후 한 번 불러 옛 손질을 되돌리는 등 먼저 손볼 글을 돌려준다(바뀌면 그 파일도 씀).
    바꾼 파일의 원본은 backup 이 참이면 backup_dir 에 같은 상대 경로로 남긴다. 바꾼 상대 경로 목록을 돌려준다."""
    texts, newlines, changed, errors = {}, {}, [], []
    for rel, old, new in edits:
        if rel not in texts:
            path = root / rel
            if not path.is_file():
                errors.append(f"파일이 없습니다: {rel}")
                texts[rel] = None
                continue
            text, newlines[rel] = read_source(path)
            prepared = prepare(rel, text) if prepare else text
            if prepared != text:
                changed.append(rel)
            texts[rel] = prepared
        if texts[rel] is None:
            continue
        try:
            fixed = edit(texts[rel], old, new)
        except ValueError as e:
            errors.append(f"{e}\n  {rel}")
            continue
        if fixed is None:
            print(f"이미 적용됨: {rel}")
            continue
        texts[rel] = fixed
        if rel not in changed:
            changed.append(rel)
    if errors:
        raise SystemExit("아무 파일도 바꾸지 않았습니다.\n" + "\n".join(errors))
    dest_root = backup_dir(root, tag) if backup and changed else None
    for rel in changed:
        path = root / rel
        if dest_root:
            dest = dest_root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, dest)
        write_source(path, texts[rel], newlines[rel])
        print(f"적용: {rel}")
    return changed
