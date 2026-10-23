"""16x16 아이콘 생성 공용부. 색 토큰을 밝은 · 어두운 판으로 바꿔 두 파일을 만든다."""
import re
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

# 토큰 → (밝은 판, 어두운 판). 어두운 판 강조색은 glass-cat 팔레트 그대로.
COLORS = {
    'N': ('#6C707E', '#CED0D6'),   # IntelliJ 기본 선 색
    'F': ('#EBECF0', '#43454A'),   # IntelliJ 기본 채움
    'purple': ('#9F45D9', '#BF5AF2'), 'blue': ('#0A6FE0', '#0A84FF'), 'teal': ('#1E9E96', '#40C8C0'),
    'green': ('#1FA347', '#30D158'), 'pink': ('#E02D53', '#FF375F'), 'orange': ('#E68A00', '#FF9F0A'),
    'sky': ('#1E9BD6', '#64D2FF'), 'indigo': ('#5856D6', '#7D7AFF'), 'yellow': ('#C29A00', '#FFD60A'),
}
FONT = TTFont('/usr/share/fonts/opentype/inter/Inter-Bold.otf')
GS = FONT.getGlyphSet(); CMAP = FONT.getBestCmap(); UPM = FONT['head'].unitsPerEm; HMTX = FONT['hmtx']

def text(s, x, base, size, color, anchor='start', track=0.0):
    """글자를 윤곽선 path 로 바꾼다(아이콘에 글꼴을 싣지 않으려고). size 는 대문자 높이가 아니라 em 크기."""
    k = size / UPM
    width = sum(HMTX[CMAP[ord(c)]][0] for c in s) * k + track * (len(s) - 1)
    if anchor == 'middle': x -= width / 2
    elif anchor == 'end': x -= width
    out = []
    for c in s:
        g = CMAP[ord(c)]
        pen = SVGPathPen(GS)
        GS[g].draw(TransformPen(pen, (k, 0, 0, -k, x, base)))
        d = pen.getCommands()
        if d: out.append(d)
        x += HMTX[g][0] * k + track
    d = re.sub(r'(\d+\.\d{2})\d+', r'\1', ' '.join(out))
    return f'<path d="{d}" fill="{color}"/>'

def paint(body, dark):
    i = 1 if dark else 0
    def rep(m):
        tok = m.group(1)
        return COLORS[tok][i]
    return re.sub(r'\$([a-zA-Z]+)', rep, body)

def svg(body, dark):
    return ('<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">\n'
            + paint(body, dark) + '\n</svg>\n')

# 자주 쓰는 모양
FOLDER = 'M1.5 3.87C1.5 3.05 2.07 2.5 2.63 2.5H6.12C6.26 2.5 6.39 2.56 6.48 2.65L8.25 4.5H13C13.83 4.5 14.5 5.17 14.5 6V12.13C14.5 12.95 13.93 13.5 13.37 13.5H2.63C2.07 13.5 1.5 12.95 1.5 12.13Z'
PACKAGE = 'M2.5 4.5L8 1.75L13.5 4.5V11.5L8 14.25L2.5 11.5Z'

def badged(base_d, badge, fill='$F', stroke='$N', notch=(8, 8, 8, 8)):
    """IntelliJ 식으로 오른쪽 아래를 비우고 작은 표시를 얹는다. badge 는 (8..16, 8..16) 칸에 그린 몸통."""
    x, y, w, h = notch
    mid = f'm{x}{y}'
    return (f'<mask id="{mid}" maskUnits="userSpaceOnUse" x="0" y="0" width="16" height="16">'
            f'<rect width="16" height="16" fill="white"/><rect x="{x-0.5}" y="{y-0.5}" width="{w+1}" height="{h+1}" rx="2" fill="black"/></mask>'
            f'<g mask="url(#{mid})"><path d="{base_d}" fill="{fill}" stroke="{stroke}"/></g>' + badge)

def tint(color):  # 강조색 엷은 채움
    return f'${color}" fill-opacity="0.16'
