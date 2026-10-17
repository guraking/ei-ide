# EI IDE

인텔리제이 오픈소스(`intellij-community`)를 바탕으로, 이클립스에서 쓰던 기능 몇 가지를 얹은 가벼운 자바 개발 도구입니다. 개인 학습용 프로젝트입니다.

- 바탕: IntelliJ IDEA Community 2026.2.3 (태그 `idea/262.10968.63`, Apache 2.0)
- 목표: 이클립스의 익숙한 흐름(서버 창 · Synchronize · 화면 묶음 · 우클릭 메뉴)은 가져오고, 무거운 것과 쓰지 않는 것은 덜어 낸다
- 남긴 것: Java · Maven · Gradle · Groovy · Git · GitHub · GitLab · Markdown · YAML · Properties · Lombok · JUnit · 터미널 · 이클립스 단축키
- 뺀 것: Kotlin · Android · Compose · AI 기능 · MCP 서버 · 기능 안내 · 성능 시험 · 맞춤법 · TestNG · TOML · WebP · Mermaid · 다른 단축키 묶음

## 기본값 (처음 켤 때부터)

| 항목 | EI IDE | 원래 인텔리제이 |
|---|---|---|
| 주 메뉴 | File · Edit · Source · Refactor · Navigate · Search · Project · Run · Git · Window · Help | View · Code · Build · Tools 포함 |
| 단축키 | Eclipse | Windows |
| 실행 설정 창 | VM 옵션 칸이 항상 보임 | 「Modify options」로 꺼내야 함 |
| 편집기 탭 줄 | 오른쪽에 「세로로 분할」 단추 | 없음 |
| 커밋 뒤 | 커밋 메시지 비움 | 남김 |
| 상위 폴더로 열기 | 바로 아래 폴더의 `build.gradle` · `pom.xml` 자동 연결 | 연결 안 됨 |
| 저장 | 자동 빌드, 자바 import 정리, 바뀐 줄만 서식 | 끔 |
| 콘솔 검색 | 실행 창 콘솔에서 Ctrl+F | Eclipse 단축키에서는 없음 |
| 형상 관리 | Git · GitHub · GitLab · **SVN** | SVN 없음 |
| 프론트 파일 색 | JS · JSX(React) · TS · TSX · HTML · CSS · SCSS · LESS · JSON · **Vue · Svelte · Astro** (TextMate 문법, 색·괄호·접기까지) | Vue · Svelte · Astro 없음 |
| Gradle 빌드 · 실행 | IDE가 직접 (이클립스와 같음) | Gradle에 위임 |
| 편집기 여백선 | 숨김 (120자 세로선) | 보임 |
| 업데이트 확인 | 꺼짐 (IDE · 플러그인 모두). 새 판은 GitHub 릴리스를 보고 알림 | JetBrains 서버에 자동 확인 |
| 편집기 글꼴 | **JetBrainsMonoHangul** (한글 폭이 영문 두 칸에 딱 맞는 JetBrains Mono + D2Coding, OFL). 글꼴 파일이 본체에 들어 있어 OS 에 설치할 필요 없음 | JetBrains Mono (한글은 다른 글꼴로 대체돼 폭이 어긋남) |

## 들어 있는 플러그인

본체에 함께 들어가며, 소스는 이 저장소에 있습니다.

### 서버 창 (`boot-servers`)
아래쪽 「서버」 창에서 여러 서버를 한꺼번에 다룹니다. STS의 부트 대시보드와 이클립스 Servers 뷰를 합친 것입니다.
- `@SpringBootApplication` 모듈의 실행 설정마다 한 줄씩 보입니다. 상태 · 포트 · 프로필 · 실행 시간.
- 「서버 복제」로 같은 모듈을 다른 프로필(yml) · 포트로 하나 더 띄웁니다.
- 여러 줄을 고르고 한꺼번에 시작 · 디버그 · 재시작 · 중지. 포트를 누르면 브라우저로 엽니다.
- 오른쪽 칸에서 프로필 · VM 옵션 · 포트를 바로 고칩니다.
- 프로젝트 트리의 모듈 옆에도 상태와 포트가 표시되고, 우클릭 메뉴에서도 시작 · 중지할 수 있습니다.
- **글꼴 내장**: JetBrainsMonoHangul 4종(Regular · Bold · Italic · BoldItalic)을 켤 때 JVM 에 등록합니다. 설정 → 에디터 → 글꼴 목록에 바로 나옵니다.
- **새 판 알림**: IDE 를 켤 때 이 저장소의 최신 릴리스를 보고 지금 판보다 새 것이 있으면 오른쪽 아래에 「내려받기」 풍선을 띄웁니다. 인터넷이 안 되면 조용히 넘어갑니다.
- **WAS 추가**: Tomcat · JBoss/WildFly · WebLogic · JEUS. 설치 폴더는 건드리지 않고 서버마다 전용 실행 폴더를 씁니다. 올릴 프로젝트와 컨텍스트 경로를 고르면 war로 묶지 않고 그 자리에서 연결하거나 바뀐 파일만 복사합니다. 디버그로 시작하면 디버거가 자동으로 붙습니다.
  - Tomcat 9 · WildFly 26으로 시작 · 배포 · 반영 · 종료를 확인했습니다. WebLogic · JEUS는 실제 서버로 아직 확인하지 못했습니다.

### Synchronize (`kinggura-sync`)
이클립스 Team → Synchronize Workspace와 같은 창입니다. 프로젝트 안의 모든 저장소(Git 여러 개, SVN)를 저장소별로 묶어 들어올 변경 · 나갈 변경 · 충돌을 보여 주고, 우클릭으로 무시 목록 추가 · 되돌리기 · **변경 모른 척하기(assume-unchanged)** 를 합니다. 「변경 모른 척하기」는 커밋 창의 파일 우클릭 메뉴에도 있습니다. 프로젝트를 열면 Git 저장소마다 `.git/info/exclude`에 `*.iml` · `.idea/`를 넣어 IDE 파일이 변경 목록에 뜨지 않게 합니다(팀 `.gitignore`와 서버에는 영향 없음). 커밋 · Push 뒤 자동으로 다시 비교합니다. SVN은 `svn` 명령줄 도구가 PATH에 있어야 합니다.

### 데이터베이스 (`ei-db`)
DataGrip 은 커뮤니티 판에 넣을 수 없어 직접 만든 DB 도구입니다. 오른쪽 「데이터베이스」 창에서 씁니다.
- Oracle · MySQL · PostgreSQL 접속. 비밀번호는 IDE 암호 저장소, JDBC 드라이버는 `~/.m2` 에 있는 jar 를 찾아 쓰고, 없으면 Maven 저장소(`~/.m2/settings.xml` 에 사내 mirror 가 있으면 그쪽)에서 최신 판을 받아 `~/.m2` 에 둡니다(SHA-1 확인, 플러그인에 넣지 않음).
- 탐색기: 접속 → 스키마 → 표 · 뷰 → 칼럼(PK · FK · 형식 · 한글 주석). 표 더블클릭 → 데이터, 우클릭 → SELECT · INSERT · UPDATE 문 · 매퍼 뼈대 · ERD. 만드는 문장의 표 · 칼럼 이름은 대문자, 별칭만 소문자.
- SQL 콘솔: Ctrl+Enter 커서 문장 실행, Ctrl+Shift+Enter 전체. 표 · 칼럼 자동완성(「별칭.」 뒤엔 그 표 칼럼, 한글 주석 표시). 「새 콘솔」은 누를 때마다 새 탭(같은 접속은 연결을 같이 씀). 조회 건수 콤보(500 ~ 전부)와 결과의 「500건 더」 · 「전부」. Ctrl+Shift+F SQL 정렬, Ctrl+Shift+X · Y 대 · 소문자(문자열 · 주석 · `#{}` 는 그대로). 자동 커밋 기본 꺼짐, 미반영 건수 · 커밋 · 롤백.
- 결과 표: 칼럼 머리에 한글 주석, 칸 단위 선택(머리 누르면 칼럼 전체), 우클릭으로 값 · 칼럼 값 · 칼럼 이름 · IN 목록 복사, 복사 · CSV · INSERT 문 · UPDATE 문(PK 를 표 정의에서 찾아 WHERE 로) · DTO 만들기.
- **운영 보호**: WHERE 없는 UPDATE · DELETE, DROP · TRUNCATE, 운영 접속의 모든 변경은 표 이름을 입력해야 실행. 읽기 전용 접속은 변경 거절.
- **MyBatis 바로 실행**: 매퍼 XML 문장 옆 ▶(또는 Ctrl+Alt+Enter) → 매개변수 입력 → `<if>` · `<foreach>` 등을 풀어 실제 SQL 미리보기 → 실행. resultType DTO 와 SELECT 칼럼이 안 맞으면 경고.
- **SQL 로그 → 실행 SQL**: 서버 콘솔의 MyBatis `Preparing:` / `Parameters:` 로그 우클릭 → 값을 채운 SQL 복사 · 콘솔에서 실행 · 실행 창으로 열기.
- **매퍼 뼈대 · DTO 만들기**: 표나 결과에서 resultMap · select · insert · update · delete · 인터페이스 · DTO 생성(구식 조인, 부등호 CDATA, `WHERE 1 = 1`). 있는 파일은 덮어쓰지 않고 덧붙임.
- **ERD**: 표 카드(한글 주석) + FK 선, 끌어 옮기기 · 확대 · PNG 저장.

### 한국어 · Claude Code · One Dark 테마
한국어 언어 팩(JetBrains), Claude Code 플러그인(Anthropic, 마켓 `com.anthropic.code.plugin`), One Dark 테마(마켓 `com.markskelton.one-dark-theme`, VS Code 색감의 UI 테마 + 편집기 색 구성표)는 남의 것이라 본체에 넣지 않습니다. `플러그인설치.bat`이 없으면 내려받아(한국어 팩 → 릴리스 「plugins」, Claude Code → JetBrains 마켓) 사용자 플러그인 폴더에 복사합니다. 한국어 팩 262 판은 마켓에 없고 IDEA 2026.2.3 배포판에만 들어 있어 릴리스에 그대로 올려 둔 것입니다. 한국어 언어 팩은 정식 IntelliJ IDEA 같은 판에서 `plugins/localization-ko`를 사용자 플러그인 폴더(`%APPDATA%\JetBrains\EIIDE2026.2\plugins`)에 복사하면 설정 → Language and Region에서 고를 수 있습니다.

## 설치와 판 올리기

1. `eiide-262.10968.N.exe`를 실행해 설치합니다. 서버 창 · Synchronize · Lombok · SVN은 본체에 들어 있어 따로 넣을 것이 없습니다.
2. EI IDE를 한 번 켰다가 끕니다(설정 폴더가 만들어집니다).
3. `노트북용\플러그인설치.bat`을 더블클릭합니다. 한국어 언어 팩과 Claude Code 플러그인을 사용자 플러그인 폴더에 넣고(옆 `plugins` 폴더에 없으면 한국어 팩은 이 저장소의 릴리스 「plugins」에서, Claude Code 는 JetBrains 마켓에서 내려받음), 예전에 따로 넣어 둔 플러그인 사본은 본체와 겹치지 않게 치웁니다. IDE가 켜져 있으면 「먼저 종료」만 띄우고 멈춥니다.
4. EI IDE를 켜고 설정 → Language and Region에서 Korean을 고릅니다.

판을 올릴 때도 같은 순서입니다. 설정(단축키 · 실행 설정 · 플러그인)은 `%APPDATA%\JetBrains\EIIDE2026.2`에 있어 재설치해도 남습니다.

플러그인만 고쳐서 jar가 새로 나왔을 때는 빌드 없이 `노트북용\plugins`에 넣고 3번만 다시 돌리면 됩니다.

## 빌드

Windows 기준입니다. 빌드는 Bazel을 써서 처음은 30분 안팎, 그 뒤 바뀐 부분만 다시 컴파일할 때는 10분 안팎 걸립니다.

```
get-source.bat          rem intellij-community + android 소스 받기 (태그 idea/262.10968.63)
python kit\customize.py intellij-community "EI IDE" brand
python kit\customize.py intellij-community "EI IDE" slim
python kit\menu\apply_menu.py intellij-community
python kit\defaults\apply_defaults.py intellij-community
build-windows.bat
```

- 결과: `intellij-community\out\idea-ce\artifacts\eiide-262.10968.N.exe` (N은 빌드마다 하나씩 올라감, `build-number.txt`)
- N은 **1000부터** 씁니다. 바탕 판(262.10968.63)보다 작으면 IDE가 JetBrains 마켓의 Java · Gradle · Maven 등 번들 플러그인을 「새 판」으로 보고 사용자 플러그인 폴더에 내려받아 본체(와 여기서 고친 기본값)를 덮어 버립니다.
- `bundled-plugins\<이름>\lib\*.jar`에 둔 플러그인은 본체에 함께 들어갑니다.
- 소스를 고치는 대본은 모두 두 번 돌려도 같은 결과가 되고, 고치기 전 원본을 `backup\`에 남깁니다.

| 대본 | 하는 일 |
|---|---|
| `kit/customize.py brand` | 제품 이름 · 설치 폴더 · 설정 폴더 이름 |
| `kit/customize.py slim` | 번들 플러그인 덜어 내기, Android 빌드 제외 |
| `kit/rename.py` | 이미 brand 를 적용한 소스의 제품 이름을 다른 이름으로 바꾸기 (`rename.py intellij-community "지금 이름" "새 이름"`) |
| `kit/menu/apply_menu.py` | 이클립스식 주 메뉴, 트리 우클릭에 Maven · Gradle 다시 읽기 |
| `kit/defaults/apply_defaults.py` | 위 「기본값」 표의 항목들(편집기 글꼴 포함), SVN 번들, 플러그인 본체 포함 |
| `kit/textmate-bundles/` | 본체 TextMate 번들에 더 넣을 문법 — `vue` · `svelte` · `astro` (각 공식 VS Code 확장의 문법만, MIT). `build-windows.bat`이 빌드 전에 자동으로 복사한다 |

## 폴더

```
intellij-community/   바탕 소스 (받은 그대로 + 대본이 고친 부분)
kit/                  소스를 고치는 대본, 주 메뉴 코드, 플러그인 소스(boot-servers · sync)
bundled-plugins/      본체에 넣을 플러그인 jar
plugins/              플러그인 판별 jar (사용자 플러그인 폴더에 직접 넣을 때)
노트북용/             설치 파일 + 플러그인 묶음 + 플러그인설치.bat
backup/               고치기 전 · 후 원본
진행상황.md           작업 일지
```

## 하기로 한 것

- 로고 · 시작 화면 교체 (지금은 IntelliJ 로고가 남아 있음 — JetBrains 상표라 공개 배포 전에 바꿔야 함)
- WebLogic · JEUS 실제 서버로 확인
- 화면 묶음(퍼스펙티브)은 창 배치가 제대로 안 바뀌어 뺐음. 다시 넣으려면 배치 적용부터 손봐야 함

## 라이선스

바탕이 되는 intellij-community는 Apache License 2.0입니다. 이 저장소의 대본과 플러그인 소스도 같은 조건으로 둡니다. 들어 있는 글꼴 JetBrainsMonoHangul 과 TextMate 문법(vue · svelte · astro)은 각각 SIL OFL 1.1 · MIT 이며 원문 라이선스를 함께 둡니다. IntelliJ · JetBrains 이름과 로고는 JetBrains의 상표입니다.
