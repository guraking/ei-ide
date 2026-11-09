"""Moa Mono 글꼴 4개에 한자 · 일본어 가나 · 주음부호 · 전각 기호를 더한다.

쓰는 법: python kit/font/add_cjk.py <NotoSansCJK-Regular.ttc> <NotoSansCJK-Bold.ttc> [글꼴 폴더]
  - 글꼴 폴더 기본값은 kit/boot-servers/src/main/resources/fonts (그 안의 MoaMono-*.ttf 를 덮어쓴다).
  - Noto Sans CJK(OFL 1.1)의 간체 중국어(SC) 글꼴에서 Moa Mono 에 없는 CJK 글자만 가져온다.
    영문 · 한글 · 기존 글자와 그 힌팅은 건드리지 않는다.
  - 크기는 Moa Mono 한글과 맞춘다: 글자 폭은 한글과 같은 900, 모양은 0.92 배, 세로 가운데는 한글 가운데에 맞춘다.
  - 기울임 글꼴은 post.italicAngle 만큼 기울인다.
  - TrueType 글자 수 한도(65535)에 닿으면 확장 B 이후의 드문 한자는 빠진다(그 글자는 IDE 가 다른 글꼴로 대신 그린다).
  - 새 글자에는 TrueType 힌팅이 없다(ttfautohint 가 한자를 힌팅하지 못한다).
"""
import math
import os
import sys
from concurrent.futures import ProcessPoolExecutor

from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTCollection, TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_DIR = os.path.join(ROOT, 'kit', 'boot-servers', 'src', 'main', 'resources', 'fonts')
SOURCE_NAME = 'Noto Sans CJK SC'
# 가져올 유니코드 구역(CJK 부수 · 기호 · 가나 · 주음 · 한자 · 호환 한자 · 세로 · 전각 · 원문자 한자 · 확장 B 이후).
RANGES = [(0x2E80, 0x2FFF), (0x3000, 0x31FF), (0x3200, 0x33FF), (0x3400, 0x4DBF), (0x4E00, 0x9FFF),
          (0xF900, 0xFAFF), (0xFE10, 0xFE1F), (0xFE30, 0xFE4F), (0xFF00, 0xFFEF), (0x1F200, 0x1F2FF),
          (0x20000, 0x3FFFF)]
# Moa Mono 한글 글자 폭(Pretendard 를 900 에 맞춰 넣었다). 원본 전각 폭 1000 이 이 폭이 된다.
ADVANCE_RATIO = 0.9
# 원본 한글과 Moa Mono 한글의 잉크 높이 비(858/904 ≈ 0.95)와 전각 폭 900 안에 한자가 들어가는 크기의 절충값.
SCALE = 0.92
# 원본 한글 세로 가운데(-77..827)를 Moa Mono 한글 세로 가운데(-78..780)로 옮긴다.
SOURCE_MID = 375
TARGET_MID = 351
MAX_GLYPHS = 65535
# Bézier → 2차 곡선 변환 허용 오차(글꼴 단위).
CU2QU_ERROR = 1.0
STYLE_SOURCE = {'Regular': 0, 'Italic': 0, 'Bold': 1, 'BoldItalic': 1}


def in_ranges(code):
    for lo, hi in RANGES:
        if lo <= code <= hi:
            return True
    return False


def source_font(ttc):
    for font in TTCollection(ttc).fonts:
        if font['name'].getDebugName(4) == SOURCE_NAME or font['name'].getDebugName(1) == SOURCE_NAME:
            return font
    raise SystemExit(f'{ttc} 에 {SOURCE_NAME} 이 없습니다.')


def glyph_name(code):
    return f'uni{code:04X}' if code <= 0xFFFF else f'u{code:05X}'


def merge(target_path, ttc):
    target = TTFont(target_path)
    source = source_font(ttc)
    src_cmap = source.getBestCmap()
    src_glyphs = source.getGlyphSet()
    tgt_cmap = target.getBestCmap()
    order = list(target.getGlyphOrder())
    existing = set(order)
    slant = math.tan(math.radians(-target['post'].italicAngle))
    dy = TARGET_MID - SOURCE_MID * SCALE

    codes = [c for c in sorted(src_cmap) if in_ranges(c) and c not in tgt_cmap]
    # 같은 원본 글자를 가리키는 코드는 새 글자 하나를 같이 쓴다.
    made = {}
    glyf = target['glyf']
    hmtx = target['hmtx']
    cmap_add = {}
    skipped = 0
    for code in codes:
        src = src_cmap[code]
        if src not in made and len(order) >= MAX_GLYPHS:
            # 코드 순서라 BMP(기본 · 확장 A 한자, 가나)가 먼저 다 들어가고, 한도에 닿으면 확장 B 이후의 나머지를 뺀다.
            skipped += 1
            continue
        if src not in made:
            name = glyph_name(code)
            if name in existing:
                name = name + '.cjk'
            src_adv = source['hmtx'][src][0]
            adv = round(src_adv * ADVANCE_RATIO)
            dx = (adv - src_adv * SCALE) / 2
            pen = TTGlyphPen(None)
            # 기울임은 세로 위치에 비례해 x 를 민다: x' = s·x + slant·(s·y + dy) + dx
            matrix = (SCALE, 0, slant * SCALE, SCALE, dx + slant * dy, dy)
            src_glyphs[src].draw(TransformPen(Cu2QuPen(pen, CU2QU_ERROR, reverse_direction=True), matrix))
            glyph = pen.glyph()
            glyph.recalcBounds(glyf)
            glyf[name] = glyph
            hmtx[name] = (adv, glyph.xMin if glyph.numberOfContours else 0)
            order.append(name)
            existing.add(name)
            made[src] = name
        cmap_add[code] = made[src]
    if not cmap_add:
        # 이미 더한 글꼴(다시 실행)은 더할 글자가 없으므로 쓰지 않는다. 마지막 글자 자리는 0.
        return os.path.basename(target_path), 0, 0, len(order), skipped, 0
    target.setGlyphOrder(order)
    for table in target['cmap'].tables:
        if not table.isUnicode():
            continue
        for code, name in cmap_add.items():
            if table.format == 12 or code <= 0xFFFF:
                table.cmap[code] = name
    target['OS/2'].recalcUnicodeRanges(target)
    target['OS/2'].recalcCodePageRanges(target)
    target.save(target_path)
    return os.path.basename(target_path), len(cmap_add), len(made), len(order), skipped, max(cmap_add)


def check(folder):
    """모든 글꼴에 기본 한자 · 가나가 있고 영문 · 한글 폭이 그대로인지 확인한다."""
    for style in STYLE_SOURCE:
        font = TTFont(os.path.join(folder, f'MoaMono-{style}.ttf'))
        cmap = font.getBestCmap()
        for ch in '中国語日本あア漢字한A':
            assert ord(ch) in cmap, f'{style}: {ch} 없음'
        assert font['hmtx'][cmap[ord('A')]][0] == 600, f'{style}: 영문 폭 바뀜'
        assert font['hmtx'][cmap[ord('한')]][0] == 900, f'{style}: 한글 폭 바뀜'
        assert font['hmtx'][cmap[ord('中')]][0] == 900, f'{style}: 한자 폭이 한글과 다름'
        assert sum(1 for c in cmap if 0x4E00 <= c <= 0x9FFF) >= 20000, f'{style}: 한자 수 부족'


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    ttcs = [sys.argv[1], sys.argv[2]]
    folder = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_DIR
    with ProcessPoolExecutor() as pool:
        jobs = [pool.submit(merge, os.path.join(folder, f'MoaMono-{style}.ttf'), ttcs[src])
                for style, src in STYLE_SOURCE.items()]
        for job in jobs:
            name, codes, glyphs, total, skipped, last = job.result()
            print(f'{name}: 글자 {codes}개(모양 {glyphs}개) 추가, 전체 {total}, 한도로 뺀 글자 {skipped}개, 마지막 U+{last:X}')
    check(folder)
    print('점검 통과')


if __name__ == '__main__':
    main()
