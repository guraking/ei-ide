"""기본 메뉴 새 아이콘 02."""
from lib import text, FOLDER, badged, tint

I = {}   # 이름 → (몸통, 설명)
def icon(name, desc, body): I[name] = (body, desc)

def n(v): return f'{round(v, 2):g}'
def T(s, x, base, size, c, a='middle', tr=0.0): return text(s, x, base, size, c, a, tr)
def st(d, c='$N', fill='none'):  # 둥근 끝 1px 선
    return f'<path d="{d}" stroke="{c}" fill="{fill}" stroke-linecap="round" stroke-linejoin="round"/>'
def harrow(x1, x2, y, c, h=2):
    s = 1 if x2 > x1 else -1
    return st(f'M{n(x1)} {n(y)}H{n(x2)}M{n(x2 - s * h)} {n(y - h)}L{n(x2)} {n(y)}L{n(x2 - s * h)} {n(y + h)}', f'${c}')
def varrow(x, y1, y2, c, h=2):
    s = 1 if y2 > y1 else -1
    return st(f'M{n(x)} {n(y1)}V{n(y2)}M{n(x - h)} {n(y2 - s * h)}L{n(x)} {n(y2)}L{n(x + h)} {n(y2 - s * h)}', f'${c}')
def lines(ys, x1, x2, c='$N'): return st(''.join(f'M{n(x1)} {n(y)}H{n(x2)}' for y in ys), c)
def flip(body): return f'<g transform="matrix(-1 0 0 1 16 0)">{body}</g>'
def notched(inner, x=8, y=8, w=8, h=8):
    """badged 와 같은 오른쪽 아래 비움을 여러 도형에 건다."""
    mid = 'n02_' + '_'.join(n(v).replace('.', 'p') for v in (x, y, w, h))
    return (f'<mask id="{mid}" maskUnits="userSpaceOnUse" x="0" y="0" width="16" height="16">'
            f'<rect width="16" height="16" fill="white"/><rect x="{n(x - .5)}" y="{n(y - .5)}" width="{n(w + 1)}" height="{n(h + 1)}" rx="2" fill="black"/></mask>'
            f'<g mask="url(#{mid})">{inner}</g>')
def sfolder(x, y, w=6, h=5):  # 작은 폴더 윤곽
    return (f'M{n(x)} {n(y + h - .8)}V{n(y + .8)}Q{n(x)} {n(y)} {n(x + .8)} {n(y)}H{n(x + 2.2)}L{n(x + 3.2)} {n(y + 1)}'
            f'H{n(x + w - .8)}Q{n(x + w)} {n(y + 1)} {n(x + w)} {n(y + 1.8)}V{n(y + h - .8)}Q{n(x + w)} {n(y + h)} {n(x + w - .8)} {n(y + h)}'
            f'H{n(x + .8)}Q{n(x)} {n(y + h)} {n(x)} {n(y + h - .8)}Z')

# 표시(8..16 칸)
PLUS = st('M12 9V15M9 12H15', '$N')
PENCIL = '<path d="M13.4 8.9L15.1 10.6L10.7 15H9V13.3Z" fill="$F" stroke="$N" stroke-linejoin="round"/>'
XMARK = st('M9.5 9.5L14.5 14.5M14.5 9.5L9.5 14.5', '$pink')
CHECK = st('M9 12L11.3 14.3L15 9.7', '$green')
WRENCH = '<path d="M13.6 8.6A2 2 0 0 0 11.3 11.3L8.8 13.8A0.9 0.9 0 0 0 10.2 15.2L12.7 12.7A2 2 0 0 0 15.4 10.4L14.2 11.6L13.1 11.3L12.8 10.2Z" fill="' + tint('orange') + '" stroke="$orange" stroke-linejoin="round"/>'

# 공용 바탕
FRAME = '<rect x="1.5" y="2.5" width="13" height="11" rx="1.5" fill="$F" stroke="$N"/>'
TABP = FRAME + st('M1.5 6H14.5M7.5 2.5V6', '$N')
RUN = 'M2.5 2.8C2.5 2.03 3.33 1.55 4 1.94L11.5 6.27C12.17 6.65 12.17 7.62 11.5 8L4 12.33C3.33 12.72 2.5 12.24 2.5 11.47Z'
def braces(x1, x2, y1, y2, c):  # 왼쪽 · 오른쪽 중괄호
    m = (y1 + y2) / 2
    def one(x, s):
        return (f'M{n(x + 1.5 * s)} {n(y1)}C{n(x + .5 * s)} {n(y1)} {n(x + .5 * s)} {n(y1 + .5)} {n(x + .5 * s)} {n(y1 + 1)}V{n(m - 1)}'
                f'C{n(x + .5 * s)} {n(m - .4)} {n(x + .2 * s)} {n(m)} {n(x)} {n(m)}C{n(x + .2 * s)} {n(m)} {n(x + .5 * s)} {n(m + .4)} {n(x + .5 * s)} {n(m + 1)}'
                f'V{n(y2 - 1)}C{n(x + .5 * s)} {n(y2 - .5)} {n(x + .5 * s)} {n(y2)} {n(x + 1.5 * s)} {n(y2)}')
    return st(one(x1, 1) + one(x2, -1), c)

# ── 편집 ──
icon('rebase', '리베이스', st('M4.5 14.5V4', '$N') + '<circle cx="4.5" cy="11.5" r="1.5" fill="$F" stroke="$N"/>'
     + st('M4.5 4H9.5', '$teal') + '<circle cx="11.5" cy="4" r="2" fill="' + tint('teal') + '" stroke="$teal"/><circle cx="4.5" cy="4" r="1.5" fill="$F" stroke="$N"/>'
     + varrow(11.5, 14.5, 7.5, 'teal'))
icon('refactor', '리팩터링', '<path d="M2 4.5H7.5M2 7.5H6M2 10.5H5" stroke="$N" stroke-linecap="round"/>' + '<path d="M14 8.5A3.5 3.5 0 0 1 8.2 11.2M8 7.5A3.5 3.5 0 0 1 13.8 4.8" stroke="$blue" stroke-linecap="round"/><path d="M14 2.8V5H11.8M8 13.2V11H10.2" stroke="$blue" stroke-linecap="round" stroke-linejoin="round"/>')
icon('rename', '이름 바꾸기', text('ab', 1.5, 11.5, 9, '$N') + '<path d="M12.5 3.5V12.5M11 3H12.5M12.5 3H14M11 13H12.5M12.5 13H14" stroke="$blue" stroke-linecap="round"/>')
icon('replaceStructural', '구조 바꾸기', braces(1.5, 9.5, 1.5, 9.5, '$N') + st('M13.5 4.5V10.5C13.5 11.6 12.6 12.5 11.5 12.5H5.5M7.5 10.5L5.5 12.5L7.5 14.5', '$blue'))
icon('searchStructural', '구조 찾기', '<circle cx="7" cy="7" r="5" fill="$F" stroke="$N"/>' + st('M10.7 10.7L14.5 14.5', '$N') + braces(4.2, 9.8, 4.5, 9.5, '$blue'))
icon('reverseLines', '줄 순서 뒤집기', lines([3.5, 8, 12.5], 1.5, 7.5) + varrow(10.5, 13.5, 2.5, 'blue') + varrow(13.5, 2.5, 13.5, 'blue'))
icon('sortLines', '줄 정렬', lines([3.5], 1.5, 9.5) + lines([8], 1.5, 7.5) + lines([12.5], 1.5, 5.5) + varrow(12.5, 2.5, 13.5, 'blue'))
icon('selectionExtend', '선택 넓히기', '<rect x="5.5" y="4.5" width="5" height="7" rx="1" fill="' + tint('blue') + '" stroke="$blue"/>'
     + harrow(3.5, 1, 8, 'N', 1.5) + harrow(12.5, 15, 8, 'N', 1.5))
icon('selectionShrink', '선택 좁히기', '<rect x="5.5" y="4.5" width="5" height="7" rx="1" fill="' + tint('blue') + '" stroke="$blue"/>'
     + harrow(1, 3.5, 8, 'N', 1.5) + harrow(15, 12.5, 8, 'N', 1.5))
icon('surroundWith', '감싸기', st('M4 2.5H2.5V13.5H4M12 2.5H13.5V13.5H12', '$N') + lines([8], 5.5, 10.5, '$blue'))
icon('unindent', '내어쓰기', lines([2.5, 13.5], 1.5, 14.5) + lines([6.5, 9.5], 7.5, 14.5) + harrow(5.5, 1.5, 8, 'blue'))
icon('toggleCase', '대소문자 뒤집기', T('A', 4.8, 10.5, 9, '$N') + T('a', 11.4, 10.5, 9, '$N')
     + st('M3.5 13.5H12.5M5 12L3.5 13.5L5 15M11 12L12.5 13.5L11 15', '$blue'))
icon('transpose', '두 글자 맞바꾸기', T('A', 4.8, 11, 8, '$N') + T('B', 11.2, 11, 8, '$N')
     + st('M3.5 4C5 1.8 11 1.8 12.5 4M10.5 3.9L12.5 4L12.8 2', '$blue') + st('M12.5 12.5C11 14.7 5 14.7 3.5 12.5M5.5 12.6L3.5 12.5L3.2 14.5', '$blue'))

# ── 실행 · 프로젝트 ──
icon('rootUnmark', '루트 지정 풀기', badged(FOLDER, XMARK))
icon('runConfigAdd', '실행 구성 추가', badged(RUN, PLUS, fill=tint('green'), stroke='$green'))
icon('runConfigEdit', '실행 구성 고치기', badged(RUN, PENCIL, fill=tint('green'), stroke='$green'))
TRASH = 'M3.5 4.5H12.5V13C12.5 13.83 11.83 14.5 11 14.5H5C4.17 14.5 3.5 13.83 3.5 13Z'
icon('safeDelete', '안전하게 지우기', notched(f'<path d="{TRASH}" fill="$F" stroke="$N" stroke-linejoin="round"/>' + st('M6.5 7V11.5M9.5 7V11.5', '$N'))
     + st('M2 4.5H14M6 4.5V2.5H10V4.5', '$N')
     + '<path d="M12 8.5L15 9.5V11.8C15 13.5 13.7 14.8 12 15.5C10.3 14.8 9 13.5 9 11.8V9.5Z" fill="' + tint('green') + '" stroke="$green" stroke-linejoin="round"/>'
     + st('M10.6 12L11.6 13L13.4 11', '$green'))
icon('saveAsFolder', '폴더에 다른 이름으로 저장', notched(st('M4.5 2.5V5H9.5V2.5M4 12.5V9H10V12.5M1.5 13.5V2.5H10.5L12.5 4.5V13.5Z', '$N', '$F'), 7.5, 8, 8.5, 8)
     + f'<path d="{sfolder(8.5, 9.5, 7, 5.5)}" fill="{tint("blue")}" stroke="$blue" stroke-linejoin="round"/>')
GEAR = '<path fill-rule="evenodd" clip-rule="evenodd" d="M3.22655 4.36961C2.92233 4.76924 2.66735 5.20781 2.47037 5.67626L3.30992 7.0943C3.6406 7.65283 3.6406 8.34718 3.30992 8.9057L2.47037 10.3237C2.66736 10.7922 2.92233 11.2308 3.22656 11.6304L4.87261 11.6124C5.52165 11.6053 6.12297 11.9524 6.44133 12.5181L7.24899 13.953C7.49593 13.984 7.74792 14 8.00408 14C8.2602 14 8.51214 13.984 8.75904 13.9531L9.56671 12.5181C9.88507 11.9524 10.4864 11.6053 11.1354 11.6124L12.7816 11.6304C13.0858 11.2308 13.3408 10.7923 13.5377 10.3239L12.6981 8.9057C12.3674 8.34718 12.3674 7.65283 12.6981 7.0943L13.5377 5.67613C13.3408 5.20773 13.0858 4.76921 12.7816 4.36961L11.1354 4.38764C10.4864 4.39475 9.88507 4.04758 9.56671 3.48194L8.75904 2.04693C8.51214 2.01599 8.2602 2 8.00408 2C7.74792 2 7.49594 2.016 7.24899 2.04695L6.44133 3.48194C6.12297 4.04758 5.52165 4.39475 4.87261 4.38764L3.22655 4.36961ZM10.655 8.00001C10.655 9.46412 9.46811 10.651 8.004 10.651C6.5399 10.651 5.353 9.46412 5.353 8.00001C5.353 6.53591 6.5399 5.34902 8.004 5.34902C9.46811 5.34902 10.655 6.53591 10.655 8.00001ZM4.88356 3.3877C5.16752 3.39081 5.4306 3.23892 5.56988 2.99146L6.43817 1.44875C6.54914 1.25159 6.7401 1.1101 6.96387 1.07676C7.30327 1.0262 7.65062 1 8.00408 1C8.3575 1 8.7048 1.02619 9.04414 1.07674C9.26792 1.11007 9.45889 1.25157 9.56986 1.44873L10.4382 2.99146C10.5774 3.23892 10.8405 3.39081 11.1245 3.3877L12.8938 3.36832C13.1196 3.36585 13.3372 3.46012 13.4781 3.63661C13.9099 4.17766 14.2632 4.78416 14.5207 5.43884C14.6034 5.6491 14.5763 5.88489 14.4612 6.07931L13.5586 7.60376C13.4139 7.84811 13.4139 8.15189 13.5586 8.39625L14.4612 9.92069C14.5763 10.1151 14.6034 10.3509 14.5207 10.5612C14.2632 11.2158 13.9099 11.8223 13.4781 12.3634C13.3372 12.5399 13.1196 12.6342 12.8938 12.6317L11.1245 12.6123C10.8405 12.6092 10.5774 12.7611 10.4382 13.0085L9.56986 14.5513C9.45889 14.7484 9.26792 14.8899 9.04414 14.9233C8.7048 14.9738 8.3575 15 8.00408 15C7.65062 15 7.30327 14.9738 6.96387 14.9232C6.7401 14.8899 6.54914 14.7484 6.43817 14.5512L5.56988 13.0085C5.4306 12.7611 5.16752 12.6092 4.88356 12.6123L3.1144 12.6317C2.8886 12.6342 2.67096 12.5399 2.5301 12.3634C2.09822 11.8223 1.74489 11.2158 1.48738 10.561C1.40469 10.3508 1.43184 10.115 1.54695 9.92057L2.44942 8.39625C2.5941 8.15189 2.5941 7.84811 2.44942 7.60376L1.54695 6.07944C1.43184 5.88502 1.40469 5.64924 1.48738 5.43898C1.74489 4.78425 2.09822 4.1777 2.53009 3.63661C2.67096 3.46012 2.8886 3.36585 3.1144 3.36832L4.88356 3.3877ZM9.655 8.00001C9.655 8.91183 8.91582 9.65101 8.004 9.65101C7.09218 9.65101 6.353 8.91183 6.353 8.00001C6.353 7.08819 7.09218 6.34902 8.004 6.34902C8.91582 6.34902 9.655 7.08819 9.655 8.00001Z" fill="$N"/>'
icon('settingsManage', '설정 가져오기 · 내보내기', notched(GEAR) + varrow(10.5, 15, 9, 'blue', 1.5) + varrow(13.5, 9, 15, 'blue', 1.5))
icon('skipCommit', '커밋 건너뛰기', st('M1 11.5H5.5M10.5 11.5H15', '$N') + '<circle cx="8" cy="11.5" r="2.5" fill="$F" stroke="$N"/>'
     + st('M2.5 8C4 3 12 3 13.5 8M11 7.2L13.5 8L14.2 5.5', '$orange'))
icon('vcsCreate', 'VCS 저장소 만들기', notched('<circle cx="4.5" cy="4" r="2" stroke="$N"/><path d="M4.5 11.5H8.5C9.6 11.5 10.5 10.6 10.5 9.5V8" stroke="$N"/>'
     + st('M4.5 6.5V14.5', '$N') + '<circle cx="10.5" cy="6" r="2" stroke="$N"/>') + st('M12 9V15M9 12H15', '$green'))
icon('worktree', '작업 트리', f'<path d="{sfolder(1.5, 1.5, 7, 5.5)}" fill="$F" stroke="$N" stroke-linejoin="round"/>'
     + f'<path d="{sfolder(7.5, 9, 7, 5.5)}" fill="$F" stroke="$N" stroke-linejoin="round"/>' + st('M4.5 7V10C4.5 11 5 11.5 6 11.5H7.5', '$teal'))

# ── 창 · 탭 · 나누기 ──
icon('tabKeep', '탭 고정', TABP + st('M5.5 9.8L7.2 11.5L10.5 8.2', '$green'))
icon('tabList', '탭 목록', TABP + st('M5.5 8.5L8 11L10.5 8.5', '$blue'))
icon('tabNext', '다음 탭', TABP + harrow(4.5, 11.5, 10, 'blue'))
icon('tabPrev', '이전 탭', flip(TABP + harrow(4.5, 11.5, 10, 'blue')))
icon('tabs', '탭', FRAME + '<path d="M2 3H7.5V6H2Z" fill="' + tint('blue') + '"/>' + st('M1.5 6H14.5M7.5 2.5V6M11 2.5V6', '$N') + lines([8.5, 11], 4, 12))
icon('toolWindow', '도구 창', FRAME + '<path d="M2 3H6V13H2Z" fill="' + tint('blue') + '"/>' + st('M6 2.5V13.5', '$N') + lines([5.5, 8], 3, 4.5, '$blue'))
icon('splitChooser', '나눌 쪽 고르기', FRAME + st('M6.5 5.5L8 4L9.5 5.5M6.5 10.5L8 12L9.5 10.5M5 6.5L3.5 8L5 9.5M11 6.5L12.5 8L11 9.5', '$blue')
     + '<circle cx="8" cy="8" r="1" fill="$N"/>')
icon('splitRotate', '나누기 방향 돌리기', notched(FRAME + st('M8 2.5V13.5', '$N'))
     + st('M14.8 12A2.8 2.8 0 1 1 13.8 9.8M14.3 8.2V10.2H12.3', '$blue'))
SPL = FRAME + '<path d="M8 3H14V13H8Z" fill="' + tint('blue') + '"/>' + st('M8 2.5V13.5', '$N')
icon('splitterNext', '다음 나눈 칸', SPL + harrow(3.5, 11.5, 8, 'blue'))
icon('splitterPrev', '이전 나눈 칸', flip(SPL + harrow(3.5, 11.5, 8, 'blue')))
icon('unsplit', '나누기 풀기', FRAME + harrow(3, 6.5, 8, 'blue', 1.5) + harrow(13, 9.5, 8, 'blue', 1.5))
icon('windowsMerge', '창 합치기', '<rect x="1.5" y="1.5" width="5" height="4" rx="1" fill="' + tint('blue') + '" stroke="$blue"/>'
     + '<rect x="9.5" y="1.5" width="5" height="4" rx="1" fill="' + tint('blue') + '" stroke="$blue"/>'
     + '<rect x="1.5" y="7.5" width="13" height="7" rx="1.5" fill="$F" stroke="$N"/>' + st('M1.5 10H14.5M8 7.5V10', '$N'))

# ── 도움말 ──
icon('support', '지원', st('M2.5 9V7.5A5.5 5.5 0 0 1 13.5 7.5V9', '$N')
     + '<rect x="1.5" y="8.5" width="3" height="4.5" rx="1" fill="$F" stroke="$N"/><rect x="11.5" y="8.5" width="3" height="4.5" rx="1" fill="$F" stroke="$N"/>'
     + st('M13 13V13.5C13 14.05 12.55 14.5 12 14.5H9.5', '$blue') + '<circle cx="8.5" cy="14.5" r="1" fill="$blue"/>')
icon('video', '동영상', '<rect x="1.5" y="3.5" width="13" height="9" rx="2" fill="$F" stroke="$N"/>'
     + '<path d="M6.5 5.8V10.2L10.3 8Z" fill="$blue" stroke="$blue" stroke-linejoin="round"/>')
icon('welcome', '시작 화면', FRAME + st('M1.5 5.5H14.5', '$N') + st('M4.5 10L8 7.2L11.5 10M5.8 9V12H10.2V9', '$blue'))
icon('whatsNew', '새 기능', '<path d="M8 1.5C8.5 5.5 10.5 7.5 14.5 8C10.5 8.5 8.5 10.5 8 14.5C7.5 10.5 5.5 8.5 1.5 8C5.5 7.5 7.5 5.5 8 1.5Z" fill="'
     + tint('yellow') + '" stroke="$yellow" stroke-linejoin="round"/>')

SECTIONS = [('기본 메뉴 새 아이콘 02', [
    'rebase', 'refactor', 'rename', 'replaceStructural', 'reverseLines', 'rootUnmark', 'runConfigAdd', 'runConfigEdit',
    'safeDelete', 'saveAsFolder', 'searchStructural', 'selectionExtend', 'selectionShrink', 'settingsManage', 'skipCommit',
    'sortLines', 'splitChooser', 'splitRotate', 'splitterNext', 'splitterPrev', 'support', 'surroundWith', 'tabKeep',
    'tabList', 'tabNext', 'tabPrev', 'tabs', 'toggleCase', 'toolWindow', 'transpose', 'unindent', 'unsplit', 'vcsCreate',
    'video', 'welcome', 'whatsNew', 'windowsMerge', 'worktree'])]
