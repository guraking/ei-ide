"""기본 메뉴 새 아이콘 01."""
from lib import text, badged, tint

I = {}   # 이름 → (몸통, 설명)
def icon(name, desc, body): I[name] = (body, desc)

def T(s, x, base, size, c, a='middle', tr=0.0): return text(s, x, base, size, c, a, tr)
RC = 'stroke-linecap="round" stroke-linejoin="round"'
def ln(d, c='$N'): return f'<path d="{d}" stroke="{c}" {RC}/>'

# ── 공용 모양 ──
WIN = 'M3 2.5H13C13.83 2.5 14.5 3.17 14.5 4V12C14.5 12.83 13.83 13.5 13 13.5H3C2.17 13.5 1.5 12.83 1.5 12V4C1.5 3.17 2.17 2.5 3 2.5Z'
PROJ = WIN + 'M1.5 5.5H14.5M5.5 5.5V13.5'                       # 프로젝트 창: 제목 줄 + 왼쪽 도구 창
LAYOUT = WIN + 'M6.5 2.5V13.5M6.5 8.5H14.5'                     # 창 배치: 세 칸
CLIP = ('M5 2.5H4C3.17 2.5 2.5 3.17 2.5 4V13C2.5 13.83 3.17 14.5 4 14.5H12C12.83 14.5 13.5 13.83 13.5 13V4'
        'C13.5 3.17 12.83 2.5 12 2.5H11M6.5 1.5H9.5C10.05 1.5 10.5 1.95 10.5 2.5C10.5 3.05 10.05 3.5 9.5 3.5H6.5'
        'C5.95 3.5 5.5 3.05 5.5 2.5C5.5 1.95 5.95 1.5 6.5 1.5Z')
FILE = 'M3.5 2.5C3.5 1.95 3.95 1.5 4.5 1.5H9L12.5 5V13.5C12.5 14.05 12.05 14.5 11.5 14.5H4.5C3.95 14.5 3.5 14.05 3.5 13.5Z'
BALLOON = ('M3 2.5H13C13.83 2.5 14.5 3.17 14.5 4V10C14.5 10.83 13.83 11.5 13 11.5H6L3.5 13.5V11.5H3'
           'C2.17 11.5 1.5 10.83 1.5 10V4C1.5 3.17 2.17 2.5 3 2.5Z')
def cube(dx, fill, c):  # 작은 상자(꾸러미), 1.5..7.5 칸을 dx 만큼 옮김
    p = lambda x: x + dx
    return (f'<path d="M{p(1.5)} 8.5L{p(4.5)} 7L{p(7.5)} 8.5V12L{p(4.5)} 13.5L{p(1.5)} 12Z" fill="{fill}" stroke="{c}" stroke-linejoin="round"/>'
            f'<path d="M{p(1.5)} 8.5L{p(4.5)} 10L{p(7.5)} 8.5M{p(4.5)} 10V13.5" stroke="{c}" stroke-linejoin="round"/>')
def method(cx, cy):  # IntelliJ 메서드 노드
    return f'<circle cx="{cx}" cy="{cy}" r="5" fill="{tint("pink")}" stroke="$pink"/>' + T('m', cx, cy + 2.3, 8, '$pink')

# 오른쪽 아래 표시(8..15 칸)
X_B = ln('M10 10L14 14M14 10L10 14', '$pink')
NEXT_B = ln('M9.5 12H14.5M12.5 10L14.5 12L12.5 14', '$blue')
PREV_B = ln('M14.5 12H9.5M11.5 10L9.5 12L11.5 14', '$blue')
CLOCK_B = '<circle cx="12" cy="12" r="3.5" fill="$F" stroke="$blue"/>' + ln('M12 10.2V12L13.3 12.8', '$blue')
UNDO_B = ln('M9.3 12A2.8 2.8 0 1 0 12.1 9.2H10.8M12 7.9L10.6 9.2L12 10.5', '$blue')
FLOPPY_B = (f'<path d="M9.5 8.5H13.5L15.5 10.5V15.5H9.5Z" fill="{tint("blue")}" stroke="$blue" stroke-linejoin="round"/>'
            + ln('M11 8.5V10.5H13V8.5M11 15.5V13H14V15.5', '$blue'))
GEAR_B = ('<circle cx="12" cy="12" r="1.2" fill="$blue"/><path d="M12 8.5V9.7M12 14.3V15.5M8.5 12H9.7M14.3 12H15.5M9.5 9.5L10.3 10.3'
          'M13.7 13.7L14.5 14.5M14.5 9.5L13.7 10.3M10.3 13.7L9.5 14.5" stroke="$blue" stroke-linecap="round"/><circle cx="12" cy="12" r="2.3" stroke="$blue"/>')

# ── 이동 · 탐색 ──
icon('goto', '이동', '<circle cx="9.5" cy="8" r="5" fill="$F" stroke="$N"/><circle cx="9.5" cy="8" r="1.5" fill="$blue"/>'
     + ln('M1.5 8H7M5 6L7 8L5 10', '$blue'))
icon('gotoLine', '줄로 이동', ln('M1.5 3.5H3.5M1.5 12.5H3.5M8 3.5H14.5M8 12.5H12.5') + ln('M1.5 8H5.5M3.5 6L5.5 8L3.5 10M8 8H14.5', '$blue'))
icon('hierarchyCall', '호출 계층', '<rect x="1.5" y="1.5" width="5" height="4" rx="1" fill="$F" stroke="$N"/>'
     '<rect x="9.5" y="6.5" width="5" height="3" rx="1" fill="$F" stroke="$N"/><rect x="9.5" y="11.5" width="5" height="3" rx="1" fill="$F" stroke="$N"/>'
     + ln('M4 5.5V13H8M4 8H8M6.5 6.5L8 8L6.5 9.5M6.5 11.5L8 13L6.5 14.5', '$blue'))
icon('hierarchyMethod', '메서드 계층', '<rect x="1.5" y="1.5" width="5" height="4" rx="1" fill="$F" stroke="$N"/>' + ln('M4 5.5V10.5H6.5')
     + f'<circle cx="10.5" cy="10.5" r="4" fill="{tint("pink")}" stroke="$pink"/>' + T('m', 10.5, 12.5, 7, '$pink'))
icon('highlightUsages', '사용처 강조', ln('M1.5 3.5H14.5M1.5 8H3M13 8H14.5M1.5 12.5H9.5')
     + f'<rect x="4.5" y="6" width="7" height="4" rx="1" fill="{tint("orange")}" stroke="$orange"/>')
icon('historySelection', '선택 영역 기록', f'<rect x="1.5" y="2" width="12" height="4" rx="1" fill="{tint("blue")}" stroke="$blue"/>'
     + ln('M3.5 4H11.5', '$blue') + ln('M1.5 9.5H6M1.5 12.5H6') + '<circle cx="11.5" cy="11.5" r="3.5" fill="$F" stroke="$N"/>' + ln('M11.5 9.8V11.5L12.8 12.3'))
icon('matchBrace', '짝 괄호로 이동', ln('M5 2.5H4.5C3.8 2.5 3.5 3 3.5 3.8V6.5C3.5 7.3 3 8 2 8C3 8 3.5 8.7 3.5 9.5V12.2C3.5 13 3.8 13.5 4.5 13.5H5'
     'M11 2.5H11.5C12.2 2.5 12.5 3 12.5 3.8V6.5C12.5 7.3 13 8 14 8C13 8 12.5 8.7 12.5 9.5V12.2C12.5 13 12.2 13.5 11.5 13.5H11')
     + ln('M6 8H10M7.3 6.7L6 8L7.3 9.3M8.7 6.7L10 8L8.7 9.3', '$blue'))
icon('methodNext', '다음 메서드', method(6.5, 6.5) + ln('M13.5 7.5V14.5M12 13L13.5 14.5L15 13', '$blue'))
icon('methodPrev', '이전 메서드', method(6.5, 9.5) + ln('M13.5 8.5V1.5M12 3L13.5 1.5L15 3', '$blue'))
icon('navbar', '경로 막대', '<rect x="1.5" y="4.5" width="13" height="7" rx="1.5" fill="$F" stroke="$N"/>'
     + ln('M4.5 6.5L6 8L4.5 9.5M9 6.5L10.5 8L9 9.5', '$blue'))

# ── 편집 ──
icon('indent', '들여쓰기', ln('M1.5 3H14.5M7.5 6.5H14.5M7.5 9.5H14.5M1.5 13H14.5') + ln('M1.5 8H5M3.5 6.5L5 8L3.5 9.5', '$blue'))
TAB = 'M1.5 8H5.5M4 6.5L5.5 8L4 9.5M7 5.5V10.5'
SP = lambda c: ''.join(f'<circle cx="{x}" cy="8" r="0.9" fill="{c}"/>' for x in (2.4, 4.2, 6))
icon('indentSpaces', '탭을 공백으로', ln(TAB) + ln('M8.5 6.5L9.5 8L8.5 9.5') +
     ''.join(f'<circle cx="{x}" cy="8" r="0.9" fill="$blue"/>' for x in (10.9, 12.5, 14.1)))
icon('indentTabs', '공백을 탭으로', SP('$N') + ln('M7.5 6.5L8.5 8L7.5 9.5') + ln('M9.5 8H13M11.5 6.5L13 8L11.5 9.5M14.5 5.5V10.5', '$blue'))
icon('inline', '인라인', '<rect x="4.5" y="1.5" width="7" height="4" rx="1" fill="$F" stroke="$N"/>' + ln('M1.5 12.5H4.5M11.5 12.5H14.5')
     + ln('M8 5.5V10M6.5 8.5L8 10L9.5 8.5M6 12.5H10', '$blue'))
icon('invertBoolean', '불 값 뒤집기', ln('M3 8.5V8A5 5 0 0 1 10.8 3.9M5.2 12.1A5 5 0 0 0 13 8V7.5M1.5 7L3 8.5L4.5 7M11.5 9L13 7.5L14.5 9')
     + ln('M8 5.5V8.3', '$blue') + '<circle cx="8" cy="10.6" r="0.8" fill="$blue"/>')
icon('joinLines', '줄 합치기', ln('M1.5 3.5H4.5L8.5 8M1.5 12.5H4.5L8.5 8') + ln('M8.5 8H14.5', '$blue'))
icon('nullity', 'Null 여부', T('@', 6.8, 13, 11, '$N') + T('?', 13.3, 7, 7.5, '$orange'))
icon('optimizeImports', '가져오기 정리', ln('M7.5 7H11M7.5 10H14.5M7.5 13H12.5') + ln('M1.5 10H5M3.5 8.5L5 10L3.5 11.5', '$blue')
     + '<path d="M12.5 1L13.2 2.8L15 3.5L13.2 4.2L12.5 6L11.8 4.2L10 3.5L11.8 2.8Z" fill="$blue"/>')
icon('pasteHistory', '붙여 넣기 기록', badged(CLIP + 'M5.5 6.5H10.5M5.5 9H7M5.5 11.5H7', CLOCK_B))
icon('pastePlain', '서식 없이 붙여 넣기', f'<path d="{CLIP}" fill="$F" stroke="$N"/>' + ln('M5.5 6.5H10.5M8 6.5V12', '$blue'))

# ── 창 · 배치 · 프로젝트 ──
icon('layoutRestore', '창 배치 되돌리기', badged(LAYOUT, UNDO_B))
icon('layoutSave', '창 배치 저장', badged(LAYOUT, FLOPPY_B))
icon('notificationClose', '알림 닫기', badged(BALLOON + 'M4 5.5H11M4 8H7', X_B))
icon('projectClose', '프로젝트 닫기', badged(PROJ, X_B))
icon('projectCloseAll', '모든 프로젝트 닫기', ln('M3.5 1.5H13C13.83 1.5 14.5 2.17 14.5 3V7')
     + badged('M2.5 3.5H11C11.55 3.5 12 3.95 12 4.5V13C12 13.55 11.55 14 11 14H2.5C1.95 14 1.5 13.55 1.5 13V4.5C1.5 3.95 1.95 3.5 2.5 3.5ZM1.5 6H12M4.5 6V14', X_B))
icon('projectWindowNext', '다음 프로젝트 창', badged(PROJ, NEXT_B))
icon('projectWindowPrev', '이전 프로젝트 창', badged(PROJ, PREV_B))
icon('projectsManage', '프로젝트 관리', '<rect x="1.5" y="1.5" width="3" height="3" rx="0.75" fill="$F" stroke="$N"/><rect x="1.5" y="6.5" width="3" height="3" rx="0.75" fill="$F" stroke="$N"/>'
     '<rect x="1.5" y="11.5" width="3" height="3" rx="0.75" fill="$F" stroke="$N"/>' + ln('M6.5 3H14.5M6.5 8H7M6.5 13H7') + GEAR_B)

# ── 기타 ──
icon('logFile', '로그 파일', badged(FILE + 'M5.5 5H8.5M5.5 7.5H7M5.5 10H7',
     f'<rect x="8.5" y="8.5" width="7" height="7" rx="1.5" fill="{tint("orange")}" stroke="$orange"/>' + ln('M11 10.5V13.5H13.5', '$orange')))
icon('macroPlay', '매크로 재생', '<circle cx="6" cy="6" r="4" fill="$pink"/>'
     '<path d="M10 9.2C10 8.6 10.7 8.2 11.2 8.6L14.6 11.1C15.1 11.5 15.1 12.2 14.6 12.6L11.2 15.1C10.7 15.5 10 15.1 10 14.5Z" fill="$green"/>')
icon('migrate', '옮겨 가기', cube(0, '$F', '$N') + cube(7, tint('blue'), '$blue') + ln('M3 3.5H12.5M11 2L12.5 3.5L11 5', '$blue'))
icon('mnemonicRemove', '기억 문자 책갈피 지우기', badged('M3 1.5H10C10.83 1.5 11.5 2.17 11.5 3V10C11.5 10.83 10.83 11.5 10 11.5H3C2.17 11.5 1.5 10.83 1.5 10V3C1.5 2.17 2.17 1.5 3 1.5Z', '')
     + T('A', 5.8, 8.8, 6.5, '$N') + f'<circle cx="12" cy="12" r="3.5" fill="{tint("pink")}" stroke="$pink"/>' + ln('M10.3 12H13.7', '$pink'))
icon('move', '옮기기', '<rect x="1.5" y="5" width="6" height="6" rx="1.5" fill="$F" stroke="$N"/>' + ln('M9.5 8H14.5M12.5 6L14.5 8L12.5 10', '$blue'))
icon('processes', '진행 중인 작업', '<rect x="1.5" y="5.5" width="13" height="5" rx="2.5" fill="$F" stroke="$N"/><rect x="3" y="7" width="6" height="2" rx="1" fill="$blue"/>')
icon('productivity', '생산성 통계', '<rect x="1.5" y="10.5" width="2" height="3" rx="0.5" fill="$F" stroke="$N"/><rect x="5" y="8" width="2" height="5.5" rx="0.5" fill="$F" stroke="$N"/>'
     f'<rect x="8.5" y="5.5" width="2" height="8" rx="0.5" fill="$F" stroke="$N"/><rect x="12" y="2.5" width="2" height="11" rx="0.5" fill="{tint("green")}" stroke="$green"/>')

NAMES = ['goto', 'gotoLine', 'hierarchyCall', 'hierarchyMethod', 'highlightUsages', 'historySelection', 'indent', 'indentSpaces',
         'indentTabs', 'inline', 'invertBoolean', 'joinLines', 'layoutRestore', 'layoutSave', 'logFile', 'macroPlay', 'matchBrace',
         'methodNext', 'methodPrev', 'migrate', 'mnemonicRemove', 'move', 'navbar', 'notificationClose', 'nullity', 'optimizeImports',
         'pasteHistory', 'pastePlain', 'processes', 'productivity', 'projectClose', 'projectCloseAll', 'projectWindowNext',
         'projectWindowPrev', 'projectsManage']
SECTIONS = [('기본 메뉴 새 아이콘 01', NAMES)]
