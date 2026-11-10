#!/usr/bin/env python3
"""apply_ko.overlay 자체 점검: 우리 번역을 덮어쓰거나 덧붙이고, 이어지는 줄은 통째로 바꾸고, 모양이 다른 번역은 넣지 않는다."""
import apply_ko as a

text = ("# c\naction.Foo.text=열기(_O)\nerror.x=오류 \\\n  이어짐\nkeep=a \\\n  menu.x=b\n"
        "dialog.message=파일 {0}을(를) 열 수 없습니다\ntag=<b>굵게</b>\nquote=''{0}'' 없음\nlast=끝")
over = {"action.Foo.text": "action.Foo.text=파일 열기(_O)", "error.x": "error.x=새 오류",
        "menu.x": "menu.x=이어진 줄 안의 글은 키가 아님",
        "dialog.message": "dialog.message={0} 파일을 열 수 없습니다", "new.key": "new.key=새",
        "tag": "tag=굵게", "quote": "quote='{0}' 없음"}
got, applied, bad = a.overlay(text, over)
want = ("# c\naction.Foo.text=파일 열기(_O)\nerror.x=새 오류\nkeep=a \\\n  menu.x=b\n"
        "dialog.message={0} 파일을 열 수 없습니다\ntag=<b>굵게</b>\nquote=''{0}'' 없음\nlast=끝\n"
        "menu.x=이어진 줄 안의 글은 키가 아님\nnew.key=새\n")
assert got == want, repr(got)
assert bad == ["tag", "quote"], bad
assert applied == 5, applied
assert a.shape("열기(_O)") != a.shape("열기"), "단축 글자가 빠지면 모양이 달라야 한다"
assert a.shape("메서드{0, choice, 1\\#|1<s}") == a.shape("메서드{0, choice, 1\\#|1<}"), "choice 의 부등호는 태그가 아니다"
assert a.key_of("a\\=b=c") == "a=b" and a.key_of("  # x") is None
print("apply_ko 점검 통과")
