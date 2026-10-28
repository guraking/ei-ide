# EI IDE — 작업 안내 (Claude 용)

인텔리제이 커뮤니티 2026.2.3(태그 `idea/262.10968.63`)을 바탕으로 이클립스 흐름을 얹은 개인 IDE.
지금 상태 · 다음 할 일은 `진행상황.md`, 바뀐 내용은 `수정내역.md`, 사용법은 `README.md` · `docs/index.html`.

## 폴더
- 사용자 PC 원본: `D:\myide` (이 저장소 + 받은 소스 `intellij-community` + `backup` · `logs` · `tmpjars` · `release` 내용물)
- 사용자 설정 폴더: `%APPDATA%\JetBrains\EIIDE2026.2` (시험용 플러그인은 `plugins\<이름>-N.jar`, 옛 판은 `..\_to_delete`)
- `kit/` 소스 손질 대본과 우리 플러그인 소스
  - `kit/ei-db` 데이터베이스(접속 · 콘솔 · 결과 · MyBatis · 생성 · ERD), 점검 `kit/ei-db/test/**/*Check.java`
  - `kit/boot-servers` 서버 창(스프링 부트 · WAS), 점검 `src/test/java`
  - `kit/sync` 동기화 창(Git · SVN), 점검 `SyncCheck.java`
  - `kit/ko/apply_ko.py` 한국어 팩을 메뉴 · 단추만 영어로 깎아 `bundled-plugins/localization-ko` 에 넣음(우리 번역은 `kit/ko/override/<jar 안 경로>`), 점검 `apply_ko_check.py`
  - `kit/git-auth` IntelliJ Git 이 https 원격에 인증을 환경값으로 넘기는 손질(`apply_git_auth.py`), 점검 `EiGitAuthCheck.java`
  - `kit/customize.py`(brand · slim) · `kit/menu/apply_menu.py` · `kit/defaults/apply_defaults.py` · `kit/brand/apply_brand.bat` · `kit/check_update.py`(새 판 점검)
- `bundled-plugins/<이름>/lib/*.jar` 는 빌드 때 본체에 들어간다. `release/플러그인설치.bat` 은 노트북 설치 대본.

## 바꿀 때마다 반영할 곳 (빠짐없이)
1. 본체 jar `D:\myide\bundled-plugins\<플러그인>\lib\*.jar`
2. 바로 시험할 사용자 플러그인 `%APPDATA%\JetBrains\EIIDE2026.2\plugins\<이름>-N.jar` (N 하나 올림, 옛 것은 `_to_delete` 로 옮김)
3. kit 소스 (`D:\myide\kit\...`)
4. 이 저장소(깃허브 `guraking/ei-ide`) — 커밋 · 푸시
5. `README.md` · `docs/index.html` · `진행상황.md` · `수정내역.md`
6. 고치기 전 · 후 백업: `D:\myide\backup\ei-ide\<yyyyMMdd-HHmmss>-before-<이름>\`, `...-after-<이름>\`

## 지켜야 할 것
- **요청을 받으면 바로 개발하지 않는다. 먼저 「무엇을 어떻게 바꿀지」를 짧게 적고 「더 넣을 것 있으세요?」를 물어, 사용자가 덧붙일 내용을 다 말한 뒤(「진행」 · 「해줘」 · 「없어」) 한 번에 만든다.**
  (2026-10-24 이름 바꾸기 · 서버 표시 · 단추 위치가 몇 초 간격으로 이어 왔는데 하나씩 바로 만들고 시험판을 올려 「지침서에 바로 개발하지 말고 계속 내가 수용할 내용이 있는지 확인 후 개발하는 걸로」 지적을 받았다.)
- **「되돌려 · 롤백 · 예전껄로 · 다 빼줘」는 그 기능 전체를 처음 들어오기 전 모습으로 돌리라는 뜻이다. 최근 판에서 더한 것만 골라 빼지 말고, 그 기능에 붙은 단추 · 아이콘 · 메뉴 · 설정을 판을 거슬러 올라가 모두 찾아 뺀다.**
  (2026-10-24 「버튼들도 다 빼줘」를 1054 단추만으로 좁게 읽어 1053 의 Branches · Stash · Unstash 아이콘을 남겼고, 사용자가 세 번 다시 말해야 했다. 「분명 전부 이전껄로 해달라고 했어」)
  범위가 애매하면 넓은 쪽으로 하고, 무엇을 뺐는지 판별로 보고한다. 같은 요청을 다시 묻지 않는다.
- 사용자가 고치라고 한 것만 고친다. 원래 있던 코드를 바꾸거나 지울 때는 먼저 무엇을 어떻게 바꿀지 묻는다. 눈에 띈 다른 문제는 보고만.
- 할 수 있는 일은 사용자에게 단계를 시키지 말고 끝까지 직접 한다. 윈도우 명령은 cmd 기준(파워쉘 아님).
- 화면 글 · 알림 · 주석은 한국어. 회사 이름 대신 「당사」. 회사 소스는 클라우드로 옮기지 않는다.
- 주석은 스펙만(작업 일지 · 감탄 금지). 향상된 for 문(stream.forEach 금지). 하드코딩 금지(이름 붙은 상수). 검증 · 에러 처리는 빼지 않는다.
- 만드는 SQL: Oracle 구식 조인(콤마 · WHERE AND · `(+)`), 쉼표는 줄 앞(`     , 칼럼`), SELECT 는 키워드만 한 줄, 표 · 칼럼 대문자 · 별칭 소문자, MyBatis 부등호는 CDATA.
- 화면 글이 칸을 넘치거나 겹치지 않게. 설명서는 1700 · 1100 · 390 폭에서 넘침 검사.
- `.bat` 은 CP949 · CRLF 로 저장한다. 실행 중인 `.bat` 은 고치지 않는다(cmd 가 위치로 다시 읽어 엉뚱한 줄을 실행할 수 있음).
- `수정내역.md` 의 맨 위 「## 」 절이 다음 빌드의 GitHub 릴리스 설명이 된다(`build-windows.bat`). 릴리스 뒤 바뀐 것은 새 절을 맨 위에 만든다.

## 빌드 (사용자 PC, cmd)
```
get-source.bat
python kit\customize.py intellij-community "EI IDE" brand
python kit\customize.py intellij-community "EI IDE" slim
python kit\menu\apply_menu.py intellij-community
python kit\defaults\apply_defaults.py intellij-community
python kit\git-auth\apply_git_auth.py intellij-community
python kit\ko\apply_ko.py
kit\brand\apply_brand.bat
build-windows.bat
```
- 판 번호 `262.10968.N`, N 은 GitHub 마지막 릴리스 번호와 이 PC 의 `build-number.txt` 중 큰 쪽 + 1(1000 이상, 성공한 빌드만 기록). 노트북에서 빌드하는 법은 `docs/노트북-빌드.md`. 결과 `release\eiide-262.10968.N.exe`, 끝나면 GitHub 릴리스로 올림.
- 커뮤니티 새 판: `python kit\check_update.py` (새 소스를 `intellij-community-new` 에 받아 kit 적용 · 플러그인 컴파일 · 판 번호 박힌 곳 보고).

## 플러그인 컴파일 · 점검
- JDK 21 이상의 javac, `--release 21 -proc:none -XDaddTypeAnnotationsToSymbol=false`, classpath 는 같은 판 IDE 의 `lib/**/*.jar` 와 `plugins/**/lib/*.jar`
  (`kit/check_update.py` 의 `compile_plugins` 가 같은 일을 한다. 플랫폼 jar 는 `ideaIC-<판>.zip` 을 intellij-repository 에서 받을 수 있다).
- 결과 jar: 컴파일 결과 + 리소스(`resources/` 또는 `src/main/resources/`)를 묶는다.
- 순수 논리(파서 · SQL 만들기 · 파일 형식)는 IntelliJ 클래스 없이 두고 `*Check.java`(main 자체 점검)로 확인. 모든 점검이 통과해야 반영한다.
