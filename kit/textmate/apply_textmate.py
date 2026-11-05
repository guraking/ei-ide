#!/usr/bin/env python3
"""IntelliJ TextMate 색칠 엔진의 \\G 위치 버그를 고친다.

사용법: python kit\\textmate\\apply_textmate.py <intellij-community 폴더>

여러 줄에 걸친 구역(예: <!-- … -->)이 닫힐 때 엔진은 그 구역이 열린 줄의 칸 위치를 \\G 위치로 다시 쓴다.
열린 줄과 닫힌 줄이 다르면 그 위치는 다른 줄의 칸이라 맞지 않는다. 닫는 줄에서 우연히 같은 칸에 오면
\\G 로 시작하는 규칙(Vue 문법의 tag-stuff 등)이 다시 열려 </template> 같은 태그가 빨갛게(invalid) 칠해진다.
같은 줄에서 열린 구역일 때만 그 위치를 쓰게 조건 하나를 더한다. VS Code 엔진과 같은 동작이다.

고칠 원문을 정확히 한 번 찾지 못하면 아무 파일도 바꾸지 않는다. 고치기 전 원본은
<intellij-community>/../backup/<시각>-before-textmate/ 에 남긴다. 두 번 실행해도 같은 결과가 된다.
"""
import shutil
import sys
import time
from pathlib import Path

TARGET = "plugins/textmate/core/src/org/jetbrains/plugins/textmate/language/syntax/lexer/TextMateLexerCore.kt"
ORIGINAL = "          if (poppedState.matchData.matched && !poppedState.matchedEOL) {\n"
PATCHED = "          if (poppedState.matchData.matched && !poppedState.matchedEOL && poppedState.string?.id == string.id) {\n"


def patch(text):
    """원문을 고친 글을 돌려준다. 이미 고쳐져 있으면 None. 원문이 정확히 한 번 있지 않으면 ValueError."""
    if PATCHED in text:
        return None
    count = text.count(ORIGINAL)
    if count != 1:
        raise ValueError(f"원문을 정확히 한 번 찾지 못했습니다({count}번): {ORIGINAL.strip()}")
    return text.replace(ORIGINAL, PATCHED)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    root = Path(sys.argv[1]).resolve()
    target = root / TARGET
    if not target.is_file():
        raise SystemExit(f"아무 파일도 바꾸지 않았습니다. 파일이 없습니다: {target}")
    raw = target.read_bytes().decode("utf-8")
    newline = "\r\n" if "\r\n" in raw else "\n"
    try:
        fixed = patch(raw.replace("\r\n", "\n"))
    except ValueError as e:
        raise SystemExit(f"아무 파일도 바꾸지 않았습니다. {e}\n  {TARGET}")
    if fixed is None:
        print(f"이미 적용됨: {TARGET}")
        return

    backup = root.parent / "backup" / (time.strftime("%Y%m%d-%H%M%S") + "-before-textmate")
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup / target.name)
    target.write_bytes(fixed.replace("\n", newline).encode("utf-8"))
    print(f"적용: {TARGET}")


if __name__ == "__main__":
    main()
