#!/usr/bin/env python3
"""apply_ko.trim 자체 점검: 메뉴 · 단추 키와 이어지는 줄을 지우고, 나머지는 두고, 우리 번역을 덮어쓰거나 덧붙인다."""
import apply_ko as a

text = ("# c\naction.Foo.text=열기\naction.Foo.description=설명\nbutton.ok=확인\nerror.x=오류 \\\n  이어짐\n"
        "keep=a \\\n  menu.x=b\ndialog.message=본문\nsome.checkbox.label=상자\nlong.btn=a\\\n b\nlast=끝")
got = a.trim(text, {"dialog.message": "dialog.message=우리 번역", "new.key": "new.key=새"})
want = "# c\naction.Foo.description=설명\nerror.x=오류 \\\n  이어짐\nkeep=a \\\n  menu.x=b\ndialog.message=우리 번역\nlast=끝\nnew.key=새\n"
assert got == want, repr(got)
assert a.key_of("a\\=b=c") == "a=b" and a.key_of("  # x") is None
print("apply_ko 점검 통과")
