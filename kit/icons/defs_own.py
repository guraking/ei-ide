"""우리 플러그인 · 폴더 · 파일 종류 아이콘."""
from lib import text, FOLDER, PACKAGE, badged, tint

I = {}   # 이름 → (몸통, 설명)
def icon(name, desc, body): I[name] = (body, desc)

def T(s, x, base, size, c, a='middle', tr=0.0): return text(s, x, base, size, c, a, tr)
SPARK = 'M12.5 1L13.2 2.8L15 3.5L13.2 4.2L12.5 6L11.8 4.2L10 3.5L11.8 2.8Z'
def spark(c='purple'): return f'<path d="{SPARK}" fill="${c}"/>'
def badge_box(c, inner):  # 8..15 칸 둥근 네모 표시
    return f'<rect x="8.5" y="8.5" width="7" height="7" rx="1.5" fill="{tint(c)}" stroke="${c}"/>' + inner
def badge_circle(c, inner=''):
    return f'<circle cx="12" cy="12" r="3.5" fill="{tint(c)}" stroke="${c}"/>' + inner

# ── 대소문자 바꾸기 ──
icon('caseConvert', '대소문자 바꾸기 묶음', T('A', 5.2, 12, 10, '$N') + T('a', 11.3, 12, 9, '$blue'))
icon('caseCamel', 'camelCase', T('a', 4.6, 12, 9, '$N') + T('B', 11, 12, 10, '$blue'))
icon('casePascal', 'PascalCase', T('A', 5, 12, 10, '$blue') + T('b', 11.3, 12, 9, '$N'))
icon('caseSnake', 'snake_case', T('a', 3.4, 10.5, 8, '$N') + T('b', 12.6, 10.5, 8, '$N') + '<path d="M6.5 12.5H9.5" stroke="$blue" stroke-linecap="round"/>')
icon('caseUpperSnake', 'UPPER_SNAKE', T('A', 3.4, 10.5, 8, '$N') + T('B', 12.6, 10.5, 8, '$N') + '<path d="M6.5 12.5H9.5" stroke="$blue" stroke-linecap="round"/>')
icon('textUpper', 'SQL 대문자로', T('AB', 8, 12, 9, '$N', tr=0.3) + '<path d="M2.5 14.5H13.5" stroke="$blue" stroke-linecap="round"/>')
icon('textLower', 'SQL 소문자로', T('ab', 8, 11.5, 9, '$N', tr=0.3) + '<path d="M2.5 14.5H13.5" stroke="$blue" stroke-linecap="round"/>')

# ── AI ──
BUBBLE = 'M2.5 4C2.5 3.17 3.17 2.5 4 2.5H9.5V9.5C9.5 10.33 8.83 11 8 11H5.5L3 13.5V11C2.67 11 2.5 10.6 2.5 10Z'
icon('aiExplain', 'AI 설명', f'<path d="M1.5 4.5C1.5 3.4 2.4 2.5 3.5 2.5H10V10.5C10 11.6 9.1 12.5 8 12.5H5L2.5 14.5V12.4C1.9 12.1 1.5 11.5 1.5 10.8Z" fill="$F" stroke="$N" stroke-linejoin="round"/><path d="M4 6H8M4 8.5H7" stroke="$N" stroke-linecap="round"/>' + spark())
icon('aiFix', 'AI 고치기', '<path d="M9.6 2.6A3 3 0 0 0 6.2 6.6L2.2 10.6A1.4 1.4 0 0 0 4.2 12.6L8.2 8.6A3 3 0 0 0 12.2 5.2L10.4 7L8.8 6.6L8.4 5Z" fill="$F" stroke="$N" stroke-linejoin="round"/>' + spark() + '<path d="M12.5 9.5L13 10.8L14.3 11.3L13 11.8L12.5 13.1L12 11.8L10.7 11.3L12 10.8Z" fill="$purple"/>')
icon('aiAsk', 'AI 에게 묻기', f'<path d="M1.5 4.5C1.5 3.4 2.4 2.5 3.5 2.5H10V10.5C10 11.6 9.1 12.5 8 12.5H5L2.5 14.5V12.4C1.9 12.1 1.5 11.5 1.5 10.8Z" fill="$F" stroke="$N" stroke-linejoin="round"/>' + T('?', 5.8, 10.3, 8, '$N') + spark())
icon('aiConsoleError', 'AI 콘솔 오류 설명', '<rect x="1.5" y="3.5" width="11" height="10" rx="1.5" fill="$F" stroke="$N"/><path d="M4 7L5.5 8.5L4 10" stroke="$N" stroke-linecap="round" stroke-linejoin="round"/><path d="M7 10.5H9.5" stroke="$pink" stroke-linecap="round"/>' + spark())

# ── 데이터베이스 · MyBatis ──
GRID = '<rect x="1.5" y="2.5" width="13" height="11" rx="1.5" fill="$F" stroke="$N"/><path d="M1.5 6H14.5M6 6V13.5M10.5 6V13.5" stroke="$N"/>'
icon('columnRename', '칼럼 이름 바꾸기', '<rect x="1.5" y="2.5" width="9" height="11" rx="1.5" fill="$F" stroke="$N"/><path d="M1.5 6H10.5M5.5 2.5V13.5" stroke="$N"/><rect x="5.5" y="6" width="5" height="7.5" fill="' + tint('blue') + '"/><path d="M13.6 6.4L14.6 7.4L10 12L8.5 12.5L9 11Z" fill="$F" stroke="$blue" stroke-linejoin="round"/>')
icon('dynamicWhere', '동적 WHERE', '<path d="M1.5 2.5H11.5L7.5 7.5V12L5.5 13.5V7.5Z" fill="$F" stroke="$N" stroke-linejoin="round"/>' + badge_box('teal', T('if', 12, 14, 5.6, '$teal')))
icon('paging', '페이징 SQL', '<rect x="4.5" y="1.5" width="9" height="10" rx="1.5" fill="$F" stroke="$N"/><path d="M2.5 4.5V12.5C2.5 13.6 3.4 14.5 4.5 14.5H11.5" stroke="$N" stroke-linecap="round"/><path d="M7.5 6.5H10.5M9 5L10.5 6.5L9 8" stroke="$blue" stroke-linecap="round" stroke-linejoin="round"/>')
LOG = '<rect x="1.5" y="2.5" width="9" height="11" rx="1.5" fill="$F" stroke="$N"/><path d="M4 5.5H8M4 8H8M4 10.5H6.5" stroke="$N" stroke-linecap="round"/>'
icon('logRun', 'SQL 로그 실행', LOG + '<path d="M10 8.2C10 7.6 10.7 7.2 11.2 7.6L14.6 10.1C15.1 10.5 15.1 11.2 14.6 11.6L11.2 14.1C10.7 14.5 10 14.1 10 13.5Z" fill="$green"/>')
icon('logSend', 'SQL 로그 콘솔로 보내기', LOG + '<path d="M9.5 10.5H15M13 8.5L15 10.5L13 12.5" stroke="$blue" stroke-linecap="round" stroke-linejoin="round"/>')
icon('genFeature', '기능 한 벌 만들기', '<path d="M7 1.5L12.5 4.25L7 7L1.5 4.25Z" fill="$F" stroke="$N" stroke-linejoin="round"/><path d="M1.5 7L7 9.75L8 9.25M1.5 9.75L7 12.5L8 12" stroke="$N" stroke-linecap="round" stroke-linejoin="round"/>' + badge_circle('green', '<path d="M12 10.2V13.8M10.2 12H13.8" stroke="$green" stroke-linecap="round"/>'))
icon('jsonDto', 'JSON → DTO', T('{', 3.6, 11.6, 10, '$orange') + T('}', 12.4, 11.6, 10, '$orange') + '<circle cx="8" cy="7.8" r="2.5" fill="' + tint('blue') + '" stroke="$blue"/>')
icon('validation', '검증 어노테이션', '<path d="M8 1.5L13.5 3.5V7.5C13.5 10.7 11.2 13.3 8 14.5C4.8 13.3 2.5 10.7 2.5 7.5V3.5Z" fill="' + tint('green') + '" stroke="$green" stroke-linejoin="round"/><path d="M5.5 8L7.2 9.7L10.5 6.3" stroke="$green" stroke-linecap="round" stroke-linejoin="round"/>')
icon('enumSync', 'Enum 맞추기', '<rect x="1.5" y="1.5" width="8" height="8" rx="1.5" fill="' + tint('purple') + '" stroke="$purple"/>' + T('E', 5.5, 8.4, 7.5, '$purple') + '<path d="M11.5 4.5A3.5 3.5 0 0 1 14 8M14 8L12.6 7M14 8L14.6 6.4M8.5 14.5A3.5 3.5 0 0 1 4.5 12M4.5 12L5.9 13M4.5 12L3.9 13.6" stroke="$N" stroke-linecap="round" stroke-linejoin="round"/>')

# ── MyBatis 태그(태그 넣기 메뉴) ──
XMLB = '<path d="M4 4.5L1.5 8L4 11.5" stroke="$N" stroke-linecap="round" stroke-linejoin="round"/>'
def stmt(name, letter, c, desc):
    icon(name, desc, XMLB + f'<rect x="5.5" y="3.5" width="9" height="9" rx="2" fill="{tint(c)}" stroke="${c}"/>' + T(letter, 10, 10.6, 7.5, f'${c}'))
stmt('tagSelect', 'S', 'blue', '<select>')
stmt('tagInsert', 'I', 'green', '<insert>')
stmt('tagUpdate', 'U', 'orange', '<update>')
stmt('tagDelete', 'D', 'pink', '<delete>')
stmt('tagMerge', 'M', 'purple', 'MERGE')
icon('tagResultMap', '<resultMap>', '<rect x="1.5" y="2.5" width="13" height="11" rx="1.5" fill="$F" stroke="$N"/><path d="M4 6H6M4 8.5H6M4 11H6" stroke="$N" stroke-linecap="round"/><path d="M8 6H12M8 8.5H12M8 11H12" stroke="$indigo" stroke-linecap="round"/>')
icon('tagSql', '<sql> 조각', XMLB + '<path d="M6.5 3.5H12C12.8 3.5 13.5 4.2 13.5 5V6.3A1.2 1.2 0 0 0 13.5 8.7V11C13.5 11.8 12.8 12.5 12 12.5H6.5Z" fill="' + tint('indigo') + '" stroke="$indigo" stroke-linejoin="round"/>')
icon('tagInclude', '<include>', '<path d="M9.5 3.5H12.5C13.6 3.5 14.5 4.4 14.5 5.5V10.5C14.5 11.6 13.6 12.5 12.5 12.5H9.5" stroke="$indigo" stroke-linecap="round"/><path d="M1.5 8H10M7.5 5.5L10 8L7.5 10.5" stroke="$N" stroke-linecap="round" stroke-linejoin="round"/>')
icon('tagIf', '<if>', '<path d="M8 1.5L14.5 8L8 14.5L1.5 8Z" fill="' + tint('teal') + '" stroke="$teal" stroke-linejoin="round"/>' + T('if', 8, 10.2, 6, '$teal'))
icon('tagChoose', '<choose> · <when> · <otherwise>', '<circle cx="3.5" cy="8" r="1.5" fill="$teal"/><path d="M5 8H7.5M7.5 8L9.5 4H13M7.5 8L9.5 12H13M7.5 8H13" stroke="$teal" stroke-linecap="round" stroke-linejoin="round"/><path d="M11.5 2.5L13 4L11.5 5.5M11.5 10.5L13 12L11.5 13.5M11.5 6.5L13 8L11.5 9.5" stroke="$N" stroke-linecap="round" stroke-linejoin="round"/>')
icon('tagWhere', '<where>', '<path d="M2.5 3.5H13.5L9.5 8.5V12.5L6.5 14V8.5Z" fill="' + tint('teal') + '" stroke="$teal" stroke-linejoin="round"/>')
icon('tagSet', '<set>', XMLB + T('=', 10, 11.4, 11, '$teal'))
icon('tagTrim', '<trim>', '<circle cx="4" cy="4.5" r="2" stroke="$N"/><circle cx="4" cy="11.5" r="2" stroke="$N"/><path d="M5.6 5.6L14 11.5M5.6 10.4L14 4.5" stroke="$teal" stroke-linecap="round"/>')
icon('tagForeach', '<foreach>', '<path d="M12.5 6A5 5 0 1 0 13 9.5" stroke="$teal" stroke-linecap="round"/><path d="M12.8 2.8V6.3H9.3" stroke="$teal" stroke-linecap="round" stroke-linejoin="round"/><path d="M6 8H10" stroke="$N" stroke-linecap="round"/>')
icon('tagBind', '<bind>', '<path d="M6.8 9.2L9.2 6.8" stroke="$N" stroke-linecap="round"/><path d="M8.6 4.4L9.9 3.1A2.4 2.4 0 0 1 13.3 6.5L12 7.8M4.4 8.6L3.1 9.9A2.4 2.4 0 0 0 6.5 13.3L7.8 12" stroke="$teal" stroke-linecap="round"/>')
icon('tagSelectKey', '<selectKey>', '<circle cx="5" cy="11" r="3" fill="' + tint('yellow') + '" stroke="$yellow"/><path d="M7.2 8.8L13.5 2.5M11.5 4.5L13 6M10 6L11 7" stroke="$yellow" stroke-linecap="round"/>')
icon('tagCdata', 'CDATA', '<path d="M5 2.5H3.5V13.5H5M11 2.5H12.5V13.5H11" stroke="$N" stroke-linecap="round" stroke-linejoin="round"/>' + T('<', 8, 10.8, 8, '$orange'))

# ── Git · SVN ──
FILE = 'M3.5 2.5C3.5 1.95 3.95 1.5 4.5 1.5H9L12.5 5V13.5C12.5 14.05 12.05 14.5 11.5 14.5H4.5C3.95 14.5 3.5 14.05 3.5 13.5Z'
LOCK = '<rect x="9.5" y="11" width="6" height="4.5" rx="1" fill="$orange"/><path d="M10.8 11V9.8A1.7 1.7 0 0 1 14.2 9.8V11" stroke="$orange"/>'
icon('assumeUnchanged', '변경 무시(assume-unchanged)', badged(FILE, LOCK, notch=(9, 7.5, 7, 8.5)))
icon('assumeUnchangedUndo', '변경 무시 풀기', badged(FILE, '<rect x="9.5" y="11" width="6" height="4.5" rx="1" fill="$green"/><path d="M10.8 11V9.8A1.7 1.7 0 0 1 14.2 9.8" stroke="$green" stroke-linecap="round"/>', notch=(9, 7.5, 7, 8.5)))

# ── 프로젝트 창 폴더 (폴더 · 패키지 + 오른쪽 아래 표시) ──
def folder(name, desc, badge): icon(name, desc, badged(FOLDER, badge))
def package(name, desc, badge): icon(name, desc, badged(FOLDER, '<circle cx="5.5" cy="9" r="1.5" stroke="$N"/>' + badge))
DB = lambda c: f'<path d="M9.5 9.5C9.5 8.67 10.6 8 12 8C13.4 8 14.5 8.67 14.5 9.5V14C14.5 14.83 13.4 15.5 12 15.5C10.6 15.5 9.5 14.83 9.5 14Z" fill="{tint(c)}" stroke="${c}"/><path d="M9.5 9.5C9.5 10.33 10.6 11 12 11C13.4 11 14.5 10.33 14.5 9.5" stroke="${c}"/>'
GEAR = lambda c: f'<circle cx="12" cy="12" r="1.2" fill="${c}"/><path d="M12 8.5V9.7M12 14.3V15.5M8.5 12H9.7M14.3 12H15.5M9.5 9.5L10.3 10.3M13.7 13.7L14.5 14.5M14.5 9.5L13.7 10.3M10.3 13.7L9.5 14.5" stroke="${c}" stroke-linecap="round"/><circle cx="12" cy="12" r="2.3" stroke="${c}"/>'
IMG = lambda c: f'<rect x="8.5" y="8.5" width="7" height="7" rx="1.5" fill="{tint(c)}" stroke="${c}"/><path d="M8.8 14.2L11 11.8L12.6 13.4L13.4 12.6L15.2 14.4" stroke="${c}" stroke-linejoin="round"/><circle cx="13.3" cy="10.6" r="0.8" fill="${c}"/>'
folder('folderMapper', 'mapper · mappers · sqlmap', badge_box('purple', T('M', 12, 14, 5.6, '$purple')))
folder('folderSql', 'sql · db · ddl', DB('blue'))
folder('folderWebInf', 'WEB-INF · META-INF', GEAR('orange'))
folder('folderStatic', 'static · public', badge_circle('sky', '<path d="M8.5 12H15.5M12 8.5C10.5 10.5 10.5 13.5 12 15.5C13.5 13.5 13.5 10.5 12 8.5" stroke="$sky"/>'))
folder('folderTemplates', 'templates · views · jsp', badge_box('teal', '<path d="M8.5 11H15.5M11 11V15.5" stroke="$teal"/>'))
folder('folderImages', 'images · img · assets', IMG('green'))
folder('folderScripts', 'js · scripts', badge_box('yellow', T('JS', 12, 14, 4.6, '$yellow')))
folder('folderStyles', 'css · styles · scss', badge_box('blue', T('#', 12, 14, 6, '$blue')))
folder('folderDocs', 'doc · docs', '<path d="M9.5 8.5H13L14.5 10V15.5H9.5Z" fill="' + tint('indigo') + '" stroke="$indigo" stroke-linejoin="round"/><path d="M11 12H13M11 14H13" stroke="$indigo" stroke-linecap="round"/>')
folder('folderI18n', 'messages · i18n · lang', badge_box('pink', T('A', 12, 14, 5.6, '$pink')))
package('pkgController', 'controller · web · api 패키지', badge_circle('sky', '<path d="M8.5 12H15.5M12 8.5C10.5 10.5 10.5 13.5 12 15.5C13.5 13.5 13.5 10.5 12 8.5" stroke="$sky"/>'))
package('pkgService', 'service · biz 패키지', GEAR('green'))
package('pkgDao', 'dao · mapper · repository 패키지', DB('blue'))
package('pkgModel', 'dto · vo · model · domain · entity 패키지', '<path d="M12 8.5L15.5 10.25V13.75L12 15.5L8.5 13.75V10.25Z" fill="' + tint('purple') + '" stroke="$purple" stroke-linejoin="round"/><path d="M8.5 10.25L12 12L15.5 10.25M12 12V15.5" stroke="$purple" stroke-linejoin="round"/>')
package('pkgConfig', 'config 패키지', '<path d="M9 10H15M9 14H15" stroke="$orange" stroke-linecap="round"/><circle cx="11" cy="10" r="1.3" fill="$orange"/><circle cx="13.2" cy="14" r="1.3" fill="$orange"/>')
package('pkgUtil', 'util · common 패키지', '<path d="M13.6 8.6A2 2 0 0 0 11.3 11.3L8.8 13.8A0.9 0.9 0 0 0 10.2 15.2L12.7 12.7A2 2 0 0 0 15.4 10.4L14.2 11.6L13.1 11.3L12.8 10.2Z" fill="' + tint('teal') + '" stroke="$teal" stroke-linejoin="round"/>')
package('pkgException', 'exception · error 패키지', '<path d="M12.8 8.5L9.5 12.5H12L11.2 15.5L14.5 11.5H12Z" fill="$pink" stroke="$pink" stroke-linejoin="round"/>')
package('pkgFilter', 'interceptor · filter · aop 패키지', '<path d="M8.5 9H15.5L13 12V15L11 15.8V12Z" fill="' + tint('indigo') + '" stroke="$indigo" stroke-linejoin="round"/>')

# ── 파일 종류 ──
def doc(name, desc, c, letter, size=7):
    icon(name, desc, f'<path d="{FILE}" fill="$F" stroke="$N"/><rect x="1.5" y="7.5" width="9" height="6" rx="1.5" fill="${c}"/>' + T(letter, 6, 12.3, size, '#FFFFFF'))
doc('fileExcel', '엑셀 xlsx · xls', 'green', 'X')
doc('fileWord', '워드 docx · doc', 'blue', 'W')
doc('filePpt', '파워포인트 pptx · ppt', 'orange', 'P')
doc('filePdf', 'PDF', 'pink', 'PDF', 4.8)
doc('fileHwp', '한글 hwp · hwpx', 'sky', 'H')
icon('fileMapper', 'MyBatis 매퍼 XML', '<path d="M4.5 4.5L1 8L4.5 11.5" stroke="$orange" stroke-linecap="round" stroke-linejoin="round"/><rect x="6.5" y="3.5" width="9" height="9" rx="2" fill="' + tint('purple') + '" stroke="$purple"/>' + T('M', 11, 10.6, 7, '$purple'))
icon('fileSpringConfig', 'application*.yml · properties', '<path d="M13.5 2.5C13.5 9.5 10 13.5 5.5 13.5C3.5 13.5 2.5 12.2 2.5 10.5C2.5 6 7 2.5 13.5 2.5Z" fill="' + tint('green') + '" stroke="$green" stroke-linejoin="round"/><path d="M2.5 13.5C5 10 8 7.5 11 5.5" stroke="$green" stroke-linecap="round"/>')
icon('fileLogConfig', 'logback · log4j 설정', LOG + GEAR('orange'))
icon('fileLog', '로그 *.log', '<rect x="2.5" y="1.5" width="11" height="13" rx="1.5" fill="$F" stroke="$N"/><path d="M5 5H11M5 8H11M5 11H8.5" stroke="$N" stroke-linecap="round"/>')
icon('fileEnv', '.env', '<rect x="1.5" y="2.5" width="13" height="11" rx="1.5" fill="$F" stroke="$N"/><circle cx="5.5" cy="8" r="2" stroke="$yellow"/><path d="M7.5 8H12.5M11 8V10" stroke="$yellow" stroke-linecap="round"/>')
icon('fileSvelte', 'Svelte', '<rect x="2.5" y="1.5" width="11" height="13" rx="3" fill="' + tint('orange') + '" stroke="$orange"/>' + T('S', 8, 11.6, 9, '$orange'))
icon('fileAstro', 'Astro', '<path d="M8 1.5L13.5 14.5H10.5L8 8L5.5 14.5H2.5Z" fill="' + tint('purple') + '" stroke="$purple" stroke-linejoin="round"/>')
icon('fileScss', 'SCSS · Sass', '<circle cx="8" cy="8" r="6.5" fill="' + tint('pink') + '" stroke="$pink"/>' + T('S', 8, 11.3, 8.5, '$pink'))

SECTIONS = [
    ('우리 플러그인 · 메뉴 항목', ['caseConvert', 'caseCamel', 'casePascal', 'caseSnake', 'caseUpperSnake', 'textUpper', 'textLower',
                       'aiExplain', 'aiFix', 'aiAsk', 'aiConsoleError', 'columnRename', 'dynamicWhere', 'paging', 'logRun', 'logSend',
                       'genFeature', 'jsonDto', 'validation', 'enumSync', 'assumeUnchanged', 'assumeUnchangedUndo']),
    ('MyBatis 태그 넣기', ['tagSelect', 'tagInsert', 'tagUpdate', 'tagDelete', 'tagMerge', 'tagResultMap', 'tagSql', 'tagInclude',
                     'tagIf', 'tagChoose', 'tagWhere', 'tagSet', 'tagTrim', 'tagForeach', 'tagBind', 'tagSelectKey', 'tagCdata']),
    ('프로젝트 창 폴더 · 패키지', ['folderMapper', 'folderSql', 'folderWebInf', 'folderStatic', 'folderTemplates', 'folderImages', 'folderScripts',
                        'folderStyles', 'folderDocs', 'folderI18n', 'pkgController', 'pkgService', 'pkgDao', 'pkgModel', 'pkgConfig', 'pkgUtil',
                        'pkgException', 'pkgFilter']),
    ('파일 종류', ['fileMapper', 'fileSpringConfig', 'fileLogConfig', 'fileLog', 'fileEnv', 'fileExcel', 'fileWord', 'filePpt', 'filePdf', 'fileHwp',
               'fileSvelte', 'fileAstro', 'fileScss']),
]
