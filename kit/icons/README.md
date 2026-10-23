# 아이콘 원본

우리 플러그인 · 메뉴 · 폴더 · 파일 아이콘(16×16 SVG, 밝게 · `_dark`)을 만드는 대본.

```
pip install fonttools
python kit/icons/sheet.py <출력 폴더> defs_menu00 defs_menu01 defs_menu02
```

`<출력 폴더>/svg` 에 SVG 가 생기고 `sheet.html` 이 견본 시트다. 만든 SVG 는 각 플러그인 `resources/icons/ei/` 로 옮긴다
(MyBatis 태그는 ei-db `icons/ei/tags/태그이름.svg`). 어느 동작 · 폴더 · 파일에 다는지는 boot-servers `icons/ei/*.properties`.
글자 윤곽은 Inter Bold(`lib.py` 의 FONT 경로)에서 뽑는다.
