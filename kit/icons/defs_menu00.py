"""기본 메뉴 새 아이콘 00."""
import math
from lib import text, FOLDER, badged, tint

I = {}   # 이름 → (몸통, 설명)
def icon(name, desc, body): I[name] = (body, desc)

def T(s, x, base, size, c, a='middle', tr=0.0): return text(s, x, base, size, c, a, tr)
def st(d, c='$N', extra=''): return f'<path d="{d}" stroke="{c}" stroke-linecap="round" stroke-linejoin="round"{extra}/>'
def mirror(body): return f'<g transform="matrix(-1 0 0 1 16 0)">{body}</g>'
def flip(body): return f'<g transform="matrix(1 0 0 -1 0 16)">{body}</g>'

FILE = 'M3.5 2.5C3.5 1.95 3.95 1.5 4.5 1.5H9L12.5 5V13.5C12.5 14.05 12.05 14.5 11.5 14.5H4.5C3.95 14.5 3.5 14.05 3.5 13.5Z'
BUBBLE = 'M1.5 3.5C1.5 2.95 1.95 2.5 2.5 2.5H12.5C13.05 2.5 13.5 2.95 13.5 3.5V10.5C13.5 11.05 13.05 11.5 12.5 11.5H6.5L3.5 14V11.5H2.5C1.95 11.5 1.5 11.05 1.5 10.5Z'
PLUS_BADGE = lambda c: st('M12 9.5V14.5M9.5 12H14.5', f'${c}')
PENCIL = lambda c: f'<path d="M13.8 8.2L15 9.4L10.5 13.9L9 14.4L9.5 12.9Z" fill="$F" stroke="${c}" stroke-linejoin="round"/>'

# ── 복사(겹친 쪽 + 앞쪽 표시) ──
def copy(name, desc, mark):
    icon(name, desc, st('M5 2.5V2.5C5 1.95 5.45 1.5 6 1.5H12C12.83 1.5 13.5 2.17 13.5 3V10.5C13.5 11.05 13.05 11.5 12.5 11.5')
         + '<rect x="2.5" y="4.5" width="8" height="10" rx="1.5" fill="$F" stroke="$N"/>' + mark)
copy('copyPath', '경로 복사', st('M7.8 7L5.2 12', '$blue'))
copy('copyPlain', '서식 없이 복사', st('M4.5 7.5H8.5M6.5 7.5V12'))
copy('copyReference', '참조 복사', st('M4.6 8.6H8.4M4.6 10.9H8.4M6 7L5.4 12.5M7.6 7L7 12.5', '$blue'))
copy('copyRich', '서식 있게 복사', T('A', 6.5, 12.6, 8, '$orange'))

# ── 탭 닫기 ──
def tab(x, w, inner=''): return f'<path d="M{x} 12.5V4.5C{x} 3.95 {x+.45} 3.5 {x+1} 3.5H{x+w-1}C{x+w-.55} 3.5 {x+w} 3.95 {x+w} 4.5V12.5" fill="$F" stroke="$N" stroke-linejoin="round"/>' + inner
def tx(cx, cy=8, h=1.25, c='$pink'): return st(f'M{cx-h} {cy-h}L{cx+h} {cy+h}M{cx+h} {cy-h}L{cx-h} {cy+h}', c)
BASE = st('M1 12.5H15')
icon('closeAllTabs', '탭 모두 닫기', tab(1.5, 3.6, tx(3.3, h=1.1)) + tab(6.2, 3.6, tx(8, h=1.1)) + tab(10.9, 3.6, tx(12.7, h=1.1)) + BASE)
icon('closeOtherTabs', '다른 탭 닫기', tab(1.5, 3.6, tx(3.3, h=1.1)) + f'<path d="M6.2 12.5V4.5C6.2 3.95 6.65 3.5 7.2 3.5H8.8C9.35 3.5 9.8 3.95 9.8 4.5V12.5" fill="{tint("blue")}" stroke="$blue" stroke-linejoin="round"/>' + tab(10.9, 3.6, tx(12.7, h=1.1)) + BASE)
LEFT = tab(1.5, 7, tx(5, 8, 1.5)) + BASE + st('M14.5 7.5H10.5M12.5 5.5L10.5 7.5L12.5 9.5', '$blue')
icon('closeTabsLeft', '왼쪽 탭 닫기', LEFT)
icon('closeTabsRight', '오른쪽 탭 닫기', mirror(LEFT))

# ── 보기 · 디버거 ──
icon('appearance', '모양(테마)', '<circle cx="8" cy="8" r="6.5" fill="$F" stroke="$N"/><path d="M8 1.5A6.5 6.5 0 0 0 8 14.5Z" fill="$N"/>')
DOT = '<circle cx="6" cy="6" r="4.5" fill="$pink"/>'
icon('breakpointRestore', '중단점 되살리기', DOT + st('M9.5 11.5H12.75A1.75 1.75 0 0 1 12.75 15H11.5M11 10L9.5 11.5L11 13'))
icon('breakpointTemporary', '한 번 쓰는 중단점', DOT + '<circle cx="12" cy="12" r="3" fill="$F" stroke="$N"/>' + st('M12 10.6V12H13.2'))
icon('bug', '버그', st('M6 4.5A2 2 0 0 1 10 4.5') + '<path d="M5 7.5C5 6.4 5.9 5.5 7 5.5H9C10.1 5.5 11 6.4 11 7.5V10.5C11 12.16 9.66 13.5 8 13.5C6.34 13.5 5 12.16 5 10.5Z" fill="' + tint('pink') + '" stroke="$pink"/>'
     + st('M8 7.5V13M2.5 6.5L5 7.5M13.5 6.5L11 7.5M2 9.5H5M14 9.5H11M2.5 13L5.2 11.5M13.5 13L10.8 11.5'))
icon('diagnostics', '진단', st('M1.5 8.5H4.5L6 5L8.5 12L10.5 3.5L12 8.5H14.5', '$pink'))

# ── 코드 고치기 ──
icon('changeSignature', '시그니처 바꾸기', st('M3.5 3C2 5 2 10 3.5 12M8.5 3C10 5 10 10 8.5 12') + T('m', 6, 9.8, 7, '$N') + PENCIL('blue'))
icon('codeCleanup', '코드 정리', st('M1.5 3.5H8.5M1.5 6.5H6.5M1.5 9.5H4.5')
     + st('M14.5 1.5L11 8.3', '$orange') + f'<path d="M9.2 7.4L12.8 9.3L11.2 14.5H6.5Z" fill="{tint("orange")}" stroke="$orange" stroke-linejoin="round"/>' + st('M8.4 11.6L7.8 14.3M10.2 11.2L9.6 14.3', '$orange'))
icon('commentAdd', '주석 달기', badged(BUBBLE, PLUS_BADGE('green')))
icon('commentBlock', '블록 주석', T('/*', 4.5, 7, 7.5, '$green') + st('M9.5 4H14.5M1.5 12H6.5') + T('*/', 11.5, 15, 7.5, '$green'))
icon('duplicateLine', '줄 복제', st('M1.5 4.5H11.5') + st('M1.5 8H8.5', '$blue') + PLUS_BADGE('blue'))
icon('emmetTag', 'Emmet 태그 펼치기', st('M4.5 3L1.5 6.5L4.5 10M7.5 3L10.5 6.5L7.5 10') + PENCIL('blue'))
icon('encapsulate', '필드 감싸기', st('M3 2.5H1.5V10.5H3M7 2.5H8.5V10.5H7') + T('f', 5, 9.2, 7.5, '$N')
     + '<rect x="9" y="10" width="6" height="4.5" rx="1" fill="$orange"/>' + st('M10.3 10V8.8A1.7 1.7 0 0 1 13.7 8.8V10', '$orange'))
icon('escapeEntities', '엔티티로 바꾸기', st('M3 2.5H1.5V13.5H3M13 2.5H14.5V13.5H13') + T('&;', 8, 11.6, 8.5, '$orange'))
icon('extract', '추출', '<rect x="1.5" y="5.5" width="9" height="9" rx="1.5" stroke="$N" stroke-dasharray="2 1.5"/>' + st('M6 10L14 2M9.5 2H14V6.5', '$blue'))
icon('fillParagraph', '문단 채우기', st('M1.5 2V14M14.5 2V14', '$blue') + st('M4 4H12M4 7H12M4 10H12M4 13H12'))
icon('forceReturn', '강제 반환', st('M13.5 3.5V7.5C13.5 8.6 12.6 9.5 11.5 9.5H4.5M7 7L4.5 9.5L7 12') + st('M2 6.5V12.5', '$pink'))
icon('generate', '코드 만들기', st('M5 6.5L2 10L5 13.5M9 6.5L12 10L9 13.5') + st('M12.5 1.5V6.5M10 4H15', '$green'))

# ── 리팩터링 · 구조 ──
def arrowhead(cx, cy, r, deg, s=1.4):
    a = math.radians(deg)
    px, py = cx + r * math.cos(a), cy + r * math.sin(a)
    dx, dy = -math.sin(a), math.cos(a)          # 시계 방향
    bx, by = px - s * dx, py - s * dy
    return f'M{bx + s*dy:.2f} {by - s*dx:.2f}L{px:.2f} {py:.2f}L{bx - s*dy:.2f} {by + s*dx:.2f}'
CY_R = 5
nodes = ''.join(f'<circle cx="{8 + CY_R*math.cos(math.radians(d)):.2f}" cy="{8 + CY_R*math.sin(math.radians(d)):.2f}" r="1.75" fill="$F" stroke="$N"/>' for d in (-90, 30, 150))
icon('cyclicDeps', '순환 의존', f'<circle cx="8" cy="8" r="{CY_R}" stroke="$orange"/>' + st(' '.join(arrowhead(8, 8, CY_R, d) for d in (-30, 90, 210)), '$orange') + nodes)
icon('delegate', '위임', '<rect x="1.5" y="1.5" width="6" height="6" rx="1.5" fill="$F" stroke="$N"/><rect x="8.5" y="8.5" width="6" height="6" rx="1.5" fill="$F" stroke="$N"/>' + st('M4.5 8V11.5H7M5.5 10L7 11.5L5.5 13', '$blue'))

# ── 오류 이동 ──
ERR = '<circle cx="6.5" cy="6.5" r="5" fill="$pink"/><path d="M6.5 4V7" stroke="#FFFFFF" stroke-linecap="round"/><circle cx="6.5" cy="9" r="0.75" fill="#FFFFFF"/>'
icon('errorNext', '다음 오류', ERR + st('M12.5 9V14.5M10.5 12.5L12.5 14.5L14.5 12.5'))
icon('errorPrev', '이전 오류', ERR + st('M12.5 14.5V9M10.5 11L12.5 9L14.5 11'))

# ── 파일 ──
icon('fileEncoding', '파일 인코딩', badged(FILE, '<ellipse cx="10.5" cy="12" rx="1.3" ry="2.2" stroke="$teal"/>' + st('M13.8 9.8V14.2M13 10.5L13.8 9.8', '$teal')))
icon('fileType', '파일 종류 바꾸기', badged(FILE, st('M9.5 10H14.5M13 8.5L14.5 10L13 11.5M14.5 13.5H9.5M11 12L9.5 13.5L11 15', '$blue')))
icon('foldCollapse', '접기', '<rect x="1.5" y="5.5" width="5" height="5" rx="1" fill="$F" stroke="$blue"/>' + st('M2.8 8H5.2', '$blue') + st('M8.5 4H14.5M8.5 8H14.5M8.5 12H12.5'))
icon('foldExpand', '펼치기', '<rect x="1.5" y="5.5" width="5" height="5" rx="1" fill="$F" stroke="$blue"/>' + st('M2.8 8H5.2M4 6.8V9.2', '$blue') + st('M8.5 4H14.5M8.5 8H14.5M8.5 12H12.5'))

# ── 찾기 ──
LENS = '<circle cx="7" cy="7" r="4.5" fill="$F" stroke="$N"/>' + st('M10.2 10.2L13.5 13.5')
icon('findAction', '동작 찾기', LENS + '<path d="M7.7 4.3L5.3 7.5H7.1L6.3 9.8L8.8 6.5H7Z" fill="$orange" stroke="$orange" stroke-width="0.5" stroke-linejoin="round"/>')
icon('findInFiles', '파일에서 찾기', badged(FOLDER, '<circle cx="11.5" cy="11.5" r="2.5" stroke="$blue"/>' + st('M13.3 13.3L15 15', '$blue')))
icon('findUsages', '사용처 찾기', '<circle cx="5.5" cy="7" r="3.5" fill="$F" stroke="$N"/>' + st('M8 9.5L10.5 12') + st('M11 4H14.5M11 7H14.5M12.5 10H14.5', '$blue'))

# ── VCS ──
CLOUD = 'M4.5 12.5A3 3 0 0 1 3.9 6.6A4.2 4.2 0 0 1 11.9 5.4A3.6 3.6 0 0 1 12 12.5Z'
icon('gitRemotes', '원격 저장소', badged(CLOUD, st('M10 9.5V14.2M13.8 9.5C13.8 11.6 10 11.4 10 13', '$orange')
                                    + '<circle cx="10" cy="9.3" r="1.2" fill="$orange"/><circle cx="10" cy="14.2" r="1.2" fill="$orange"/><circle cx="13.8" cy="9.3" r="1.2" fill="$orange"/>'))

SECTIONS = [('기본 메뉴 새 아이콘 00', [
    'appearance', 'breakpointRestore', 'breakpointTemporary', 'bug', 'changeSignature', 'closeAllTabs', 'closeOtherTabs',
    'closeTabsLeft', 'closeTabsRight', 'codeCleanup', 'commentAdd', 'commentBlock', 'copyPath', 'copyPlain', 'copyReference',
    'copyRich', 'cyclicDeps', 'delegate', 'diagnostics', 'duplicateLine', 'emmetTag', 'encapsulate', 'errorNext', 'errorPrev',
    'escapeEntities', 'extract', 'fileEncoding', 'fileType', 'fillParagraph', 'findAction', 'findInFiles', 'findUsages',
    'foldCollapse', 'foldExpand', 'forceReturn', 'generate', 'gitRemotes'])]
