# EI IDE

인텔리제이 오픈소스(`intellij-community`)를 바탕으로, 이클립스에서 쓰던 기능 몇 가지를 얹은 가벼운 자바 개발 도구입니다. 개인 학습용 프로젝트입니다.

**[화면으로 보는 사용 설명서](https://raw.githack.com/guraking/ei-ide/main/docs/index.html)** — 설치 순서, 프로젝트 열기 · Maven · Gradle 설정, 서버 창(스프링 부트 · WAS 설정), Synchronize, 데이터베이스, 단축키 (`docs/index.html`)

**[수정 내역](수정내역.md)** — 날짜별로 무엇이 바뀌었는지

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
| 커밋 전 코드 분석 · TODO 검사(Analyze code · Check TODO) | 끔 (경고 · 새 TODO 가 있어도 커밋 · 푸시가 멈추지 않음) | 켬 |
| 상위 폴더로 열기 | 바로 아래 폴더의 `build.gradle` · `pom.xml` 자동 연결 | 연결 안 됨 |
| 저장 | 자동 빌드, 자바 import 정리, 바뀐 줄만 서식 | 끔 |
| 콘솔 검색 | 실행 창 콘솔에서 Ctrl+F | Eclipse 단축키에서는 없음 |
| 형상 관리 | Git · GitHub · GitLab · **SVN** | SVN 없음 |
| 프론트 파일 색 | JS · JSX(React) · TS · TSX · HTML · CSS · SCSS · LESS · JSON · **Vue · Svelte · Astro** (TextMate 문법, 색·괄호·접기까지) | Vue · Svelte · Astro 없음 |
| Gradle 빌드 · 실행 | IDE가 직접 (이클립스와 같음) | Gradle에 위임 |
| 다른 프로젝트 열기 | 새 창에 열기 (지금 창은 그대로) | 매번 묻기 |
| 콘솔에 남는 글 양 (서버 로그) | 50 MB | 1 MB |
| 편집기 여백선 | 숨김 (120자 세로선) | 보임 |
| properties 한글 | `\uXXXX` 를 한글로 보이고 저장할 때 다시 바꿈(투명한 native-to-ascii) | `\uXXXX` 그대로 |
| 설치 화면 「설치 옵션」 | 바로가기 · PATH · 「폴더를 프로젝트로 열기」 · 파일 연결 모두 체크 | 모두 빈 칸 |
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
- **Unsafe 경고 끄기**: JDK 24 이상으로 자바 프로그램을 실행하면 VM 옵션에 `--sun-misc-unsafe-memory-access=allow` 를 더해, 오래된 라이브러리(jvm-driver 등)가 찍는 `sun.misc.Unsafe::putInt has been called` 경고 네 줄을 없앱니다. VM 옵션에 같은 옵션을 직접 넣었으면 그 값을 따릅니다.
- **글꼴 내장**: JetBrainsMonoHangul 4종(Regular · Bold · Italic · BoldItalic)을 켤 때 JVM 에 등록합니다. 설정 → 에디터 → 글꼴 목록에 바로 나옵니다.
- **새 판 알림**: IDE 를 켤 때 이 저장소의 최신 릴리스를 보고 지금 판보다 새 것이 있으면 오른쪽 아래에 「내려받기」 풍선을 띄웁니다. 인터넷이 안 되면 조용히 넘어갑니다.
- **도구 창 아이콘 순서**: 늘 있는 창(Git · 문제 · 터미널 · 서비스 · 서버 · 동기화 · DB 결과)이 앞, 쓸 때만 생기는 창(빌드 · 실행 · 디버그 · 찾기 · ERD)이 뒤라 아이콘 자리가 흔들리지 않습니다. 쓰던 설치본은 끌어 정리한 뒤 「창 → 레이아웃 → 현재 레이아웃에 변경 저장」.
- **직전 창 다시 열기**: 아래쪽 도구 창을 닫으면 그 창이 가렸던 직전 창을 다시 엽니다(한 단계만).
- **URL 찾기** (Ctrl+Alt+Shift+U, Navigate · Search 메뉴): 스프링 `@RequestMapping` · `@GetMapping` · `@PostMapping` … 을 모두 모아 HTTP 방식 · URL · 클래스.메서드로 보입니다. 클래스 앞 경로와 상수도 풉니다. `/api/order/123` 같은 실제 주소를 쳐도 `/api/order/{id}` 가 걸리고, Enter 로 그 메서드로 갑니다. 코틀린 컨트롤러 · XML 로 연결한 컨트롤러는 나오지 않습니다.
- **API 테스트(Swagger)**: Run → 「API 테스트(Swagger)…」 또는 URL 찾기의 「요청 보내기」. 아래쪽 「API」 창에 프로젝트의 스프링 컨트롤러로 만든 OpenAPI 사양을 Swagger UI 로 보이고, 「Try it out」 으로 실행 중인 서버에 요청을 보냅니다(주소는 서버 포트 · `server.servlet.context-path` 로 채움). 인증은 「Authorize」 에서 Bearer · Basic 을 넣으면 기억합니다. 창 위 「openapi.yaml 로 저장」 으로 사양 파일을 만들 수 있고, `openapi.yaml` · `swagger.json` 같은 사양 파일을 열면 편집기 옆에 같은 미리보기가 붙고 자동 완성 · 오류 표시(OpenAPI 3.0 · Swagger 2.0)가 됩니다. Swagger UI 는 IDE 안 브라우저로 띄우며 인터넷이 없어도 됩니다.
- **JavaScript · TypeScript · Vue**: .js · .jsx · .ts · .tsx · .vue 파일을 열면 언어 서버(vtsls · Vue 언어 서버)가 붙어 자동 완성 · 정의로 이동 · 사용처 찾기 · 이름 바꾸기 · 오류 표시 · 빠른 수정 · 정리가 됩니다. 처음에는 알림의 「Node.js 받기」(Node 가 없을 때) · 「언어 서버 받기」 를 한 번 누릅니다. 받은 것은 사용자 폴더의 `.ei-web` 에 둡니다. 프로젝트에 TypeScript 5 가 있으면 그 판을 씁니다.
- **AI(Claude Code)**: 편집기 우클릭 「AI」 → 「설명해 줘」 · 「고쳐 줘」 · 「질문하기…」, 실행 콘솔에서 오류 글을 골라 우클릭 「AI」 → 「이 오류 원인 찾기」. 고른 코드(없으면 커서 줄)와 파일 위치를 아래 터미널에 새 「Claude」 탭을 열어 Claude Code 에 넘깁니다. Claude Code(`claude`)가 PATH 에 있어야 합니다.
- **표기법 바꾸기**: 편집기에서 글을 골라 우클릭 「표기법 바꾸기」 → 카멜(userName) · 파스칼(UserName) · 스네이크(user_name) · 대문자 스네이크(USER_NAME). 고른 글 안의 식별자만 바꾸고 문자열 · 주석은 둡니다. 식별자 하나만 고르면 그것을 바꾸고, 여러 줄을 고르면 두 낱말 이상인 이름만 바꿉니다(public · String · UserDto 같은 키워드 · 타입 이름은 둠). Ctrl+Z 한 번으로 되돌립니다. 사용처까지 함께 바꾸려면 Refactor → Rename 을 씁니다.
- **뷰 이름 → JSP**: `@Controller` 의 `return "order/list"` · `new ModelAndView("…")` · `setViewName("…")` 에서 Ctrl+클릭하면 JSP 로 갑니다. prefix · suffix 는 `spring.mvc.view.*` 나 XML 의 InternalResourceViewResolver 에서 읽고, 없으면 `/WEB-INF/views/` · `.jsp`. JSP 편집기 우클릭 「컨트롤러로 이동」은 반대 방향입니다.
- **설정 키 이동**: 주석 속성의 `${db.url}` 에서 Ctrl+클릭하면 `application*.yml` · `properties` 의 그 키 줄로 갑니다. 프로필 파일마다 따로 고를 수 있고, `-` · `_` · 대소문자는 가리지 않습니다.
- **프로그램 목록 엑셀**: Project 메뉴 또는 프로젝트 트리 우클릭 「프로그램 목록 엑셀로 내보내기…」. 소스 · 리소스 · 웹 폴더의 전체 파일을 구분(Controller · Service · DAO·Mapper · VO·DTO · JSP · Mapper XML …) · 경로 · 패키지 · 클래스 · 설명(Javadoc 첫 문장 · JSP 제목 · 매퍼 namespace) · 줄 수 · 수정일로, URL 매핑은 둘째 시트로 냅니다(프로그램 사양서용).
- **로그 거르기**: 실행 · 서버 콘솔 우클릭 → ERROR · WARN · INFO · DEBUG 와 글자(정규식 선택)로 거른 줄 목록. 스택 줄은 앞 오류에 붙여 같이 보이고, 「따라가기」면 새 로그도 반영, 두 번 누르면 콘솔의 그 줄로.
- **WAS 추가**: Tomcat · JBoss/WildFly · WebLogic · JEUS. 설치 폴더는 건드리지 않고 서버마다 전용 실행 폴더를 씁니다. 올릴 프로젝트와 컨텍스트 경로를 고르면 war로 묶지 않고 그 자리에서 연결하거나 바뀐 파일만 복사합니다. 디버그로 시작하면 디버거가 자동으로 붙습니다.
  - Tomcat 9 · WildFly 26으로 시작 · 배포 · 반영 · 종료를 확인했습니다. WebLogic · JEUS는 실제 서버로 아직 확인하지 못했습니다.
  - 시작 전에 HTTP · 관리 · 디버그 포트가 비었는지 보고, 다른 프로그램이 쓰고 있으면 띄우지 않고 알립니다(다른 서버를 잘못 끄는 일 방지). WebLogic 은 autodeploy 에 같은 이름의 직접 둔 앱이 있으면 건드리지 않고 멈춥니다. 반영 실패는 실행 창과 알림으로 보입니다.

### Synchronize (`kinggura-sync`)
이클립스 Team → Synchronize Workspace와 같은 창입니다. 프로젝트 안의 모든 저장소(Git 여러 개, SVN)를 저장소별로 묶어 들어올 변경 · 나갈 변경 · 충돌을 보여 주고, 우클릭으로 무시 목록 추가 · 되돌리기 · **Assume Unchanged(변경 모른 척하기, skip-worktree)** 를 합니다. 새로 추가 · 이름 바꿈 · 복사한 파일은 되돌리기에서 빠지고 「스테이징만 풀기」로 갑니다(파일은 그대로). 「Assume Unchanged」는 커밋 창의 파일 우클릭 메뉴에도, 「Assume Unchanged · No Assume Unchanged」는 프로젝트 트리의 파일 우클릭 메뉴에도 있습니다. Assume Unchanged 인 파일은 「모두 보기」 맨 아래 「Assume Unchanged」 묶음에 모이고, 우클릭 「No Assume Unchanged」로 되돌립니다(파일 내용은 그대로). 프로젝트를 열면 Git 저장소마다 `.git/info/exclude`에 `*.iml` · `.idea/` · 이클립스의 `.project` · `.classpath` · `.settings/`를 넣어 IDE 파일이 변경 목록에 뜨지 않게 합니다(팀 `.gitignore`와 서버에는 영향 없음). 충돌은 빨강, 들어올 변경은 파랑, 나갈 변경은 보라로 보입니다. 프로젝트 트리 우클릭 → Git(SVN 은 Subversion) → 「작업 공간 동기화」로 열면 그 저장소만 비교합니다. 「고른 저장소만」은 트리에서 고른 저장소 하나만 원격을 받아 와 다시 비교합니다(「전체 새로고침」은 모든 저장소). 트리에서 저장소(또는 그 아래 파일)를 고르고 받기(Pull) · 올리기(Push)를 누르면 그 저장소로 창이 열립니다. 「커밋…」도 고른 파일 · 묶음 · 저장소만 커밋 창에 체크합니다(고른 것이 없으면 전체). 「전체 받기」는 고른 것과 상관없이 모든 저장소를 한 번에 받습니다(IDE 의 Update Project). SVN 저장소에서는 받기가 `svn update` 를 돌리고, 올리기는 따로 없습니다(커밋이 바로 서버에 올림). 커밋 · 올리기(Push) 뒤 자동으로 다시 비교합니다. 명령이 2분 안에 끝나지 않으면 멈추고 알립니다. SVN은 본체에 같이 넣은 svn 명령줄 도구(VisualSVN, `plugins\kinggura-sync\svn`)를 써서 따로 설치할 것이 없습니다.

### 데이터베이스 (`ei-db`)
DataGrip 은 커뮤니티 판에 넣을 수 없어 직접 만든 DB 도구입니다. 오른쪽 「데이터베이스」 창(탐색기)과 아래 「결과」 창으로 씁니다.

**접속**
- Oracle · MySQL · PostgreSQL. 비밀번호는 IDE 암호 저장소에 둡니다. 「저장」을 끄면 연결할 때 묻고 IDE 를 끌 때까지만 기억합니다.
- JDBC 드라이버는 `~/.m2` 에 있는 jar 를 찾아 쓰고, 없으면 Maven 저장소(`~/.m2/settings.xml` 에 사내 mirror 가 있으면 그쪽)에서 최신 판을 받아 `~/.m2` 에 둡니다(SHA-1 확인, 플러그인에 넣지 않음). 접속 창의 「내려받기」로 미리 받을 수도 있습니다.
- 운영 접속 · 읽기 전용 표시를 접속마다 켭니다(아래 「운영 보호」).
- 자동 커밋은 접속마다 하나라 같은 접속의 콘솔이 모두 같은 값을 씁니다. 자동 커밋이 꺼진 Oracle · MySQL 에서 DDL(CREATE · DROP …)을 실행하면 앞 변경까지 커밋된다고 먼저 묻습니다. 접속을 끊거나 닫을 때 커밋하지 않은 변경은 롤백합니다.
- 생성하는 SQL 의 이름: Oracle · PostgreSQL 은 대문자(대소문자가 섞인 이름은 따옴표), MySQL 은 대소문자를 가리므로 메타에서 읽은 그대로 씁니다.
- 도구줄 「⋯」 → 접속 내보내기 · 가져오기(JSON, 비밀번호는 빼고). 같은 이름이 있으면 「이름 (가져옴)」.

**탐색기**
- 접속 → 스키마 → 표 · 뷰 → 칼럼(PK · FK · 형식 · 한글 주석).
- 표 더블클릭 → 데이터 보기.
- 표 우클릭 → SELECT · INSERT · UPDATE 문 만들기, 매퍼 뼈대, ERD.
- 표 여러 개를 Ctrl 로 고르고 우클릭 → 「SELECT 문 만들기 (표 N개 조인)」: FK 로 조인 조건을 만들고(구식 조인), FK 가 없으면 PK 와 이름이 같은 칼럼으로 추정(주석 표시), 못 이은 표는 직접 쓰라는 줄을 남깁니다. SELECT · INSERT · UPDATE 문 모두 표 · 칼럼 이름 대문자, 별칭만 소문자, 쉼표는 줄 앞, 칼럼마다 한 줄에 한글 주석.
- 도구줄 돋보기 「찾기」: 칼럼 이름 · 한글 주석 · 표 이름 · 표 주석으로 표와 칼럼을 찾습니다(최대 500건). 두 번 누르면 그 표로 가거나 SELECT 문을 엽니다.
- **Oracle 프로그램**: 스키마 아래 「프로그램」에 패키지 · 프로시저 · 함수(깨진 것은 INVALID). 두 번 누르면 소스를 `CREATE OR REPLACE … /` 로 콘솔에 붙입니다(이름에 소유자를 넣어 다른 스키마를 봐도 그 스키마에 만들어짐). 고쳐 실행하면 컴파일되고, 우클릭 「컴파일 오류 보기」로 ALL_ERRORS 의 줄 · 위치 · 오류를 결과 창에 봅니다.
- **세션 · 잠금 보기**: 접속 우클릭. 잠금 보기는 누가 어떤 표를 잡고 있는지(MySQL 은 커밋 안 한 트랜잭션)와 끝낼 때 쓸 문장(KILL_SQL)을 보입니다. 끝내기는 콘솔에서 직접 실행합니다. Oracle 은 V$SESSION · V$LOCKED_OBJECT 조회 권한이 있어야 합니다.
- 「새 콘솔」은 누를 때마다 새 탭(`콘솔 · 접속.sql`, `콘솔 · 접속 (2).sql` …)을 엽니다. 같은 접속의 콘솔은 연결 하나(미반영 변경 · 커밋 · 롤백)를 같이 씁니다. 탐색기 · MyBatis · 로그에서 보내는 SQL 은 그 접속에서 마지막으로 연 콘솔에 붙습니다.

**SQL 콘솔**
- 도구줄: 실행 · 전체 실행 · 정렬 · 대문자 · 소문자 · 중지 · 실행 계획 · 기록 · 접속 바꾸기 · 조회 건수 · 자동 커밋 · 미반영 건수 · 커밋 · 롤백. 자동 커밋은 기본 꺼짐.
- **자동 저장**: 콘솔 글을 접속(과 콘솔 번호)별로 IDE 설정 폴더에 저장해 두고, 다음에 그 콘솔을 열면 되살립니다.
- **중지**: 실행 중인 쿼리를 끊습니다(진행 막대의 취소도 같음). 결과 창에 「취소했습니다」.
- **실행 기록**: 실행한 문장을 최근 500개까지(시각 · 접속 · 걸린 시간 · 건수 · 성공/실패) 남깁니다. 고르면 콘솔에 붙여 넣고, 두 번 누르면 같은 값으로 다시 실행합니다.
- **바인드 변수**: 문장에 `:name` · `#{name}` 이 있으면 실행 전에 값을 묻습니다(비우면 NULL, `'abc'` 는 문자열, 숫자는 숫자, 마지막 값 기억). `${name}` 은 글자 그대로 바뀝니다.
- **실행 계획** (Ctrl+Shift+E): 문장을 실행하지 않고 계획만 결과 창에 보입니다(Oracle DBMS_XPLAN, PostgreSQL · MySQL EXPLAIN).
- 표 · 칼럼 자동완성: 「별칭.」 뒤에는 그 표의 칼럼, 한글 주석을 함께 보여 줍니다. 소문자로 치면 소문자로 넣습니다.
- **정렬**: 절 키워드 6칸 오른쪽 맞춤, SELECT · SET · GROUP BY · ORDER BY 는 칼럼마다 한 줄에 쉼표는 줄 앞(SELECT 는 키워드만 한 줄, 칼럼은 다음 줄부터), WHERE 의 AND · OR 는 줄마다, FROM 의 표 목록은 한 줄(구식 조인), 하위 질의는 들여쓰기. INSERT 는 칼럼 · 값을 줄마다(쉼표 앞) 두고 값 줄 끝에 `-- 칼럼 이름`. 줄 끝 `-- 주석` · 칼럼 뒤 `/* 주석 */` 은 그 줄에 남기고 한 열에 맞춥니다. 키워드만 대문자로 바꾸고 이름은 그대로 둡니다.
- **대문자 · 소문자**: 문자열 · 따옴표 이름 · 주석 · `#{}` · `${}` 는 그대로 두고 바꿉니다.
- 정렬 · 대소문자는 선택 영역에만, 선택이 없으면 콘솔 전체에. Ctrl+Z 로 되돌립니다.
- **조회 건수**: 도구줄 칸에서 500 · 1000 · 5000 · 10000 을 고르거나 숫자를 직접 쓰고 Enter. 이번 세션 동안 프로젝트 전체(모든 콘솔 · 데이터 보기)에 적용됩니다.

| 단축키 (콘솔 안) | 하는 일 |
|---|---|
| Ctrl+Enter | 커서가 있는 문장(또는 선택 영역) 실행 |
| Ctrl+Shift+Enter | 모든 문장을 차례로 실행 |
| Ctrl+Shift+F | SQL 정렬 |
| Ctrl+Shift+X · Ctrl+Shift+Y | 대문자 · 소문자 |
| Ctrl+Shift+E | 실행 계획 |

콘솔 밖에서는 같은 단축키가 원래 기능으로 동작합니다(예: Ctrl+Shift+F 는 경로에서 찾기).

**결과 창**
- 실행마다 탭 「결과 N · 표 이름」, 10개가 넘으면 오래된 것부터 닫힘. 탭 ✕ · 가운데 단추 · 우클릭 「다른 탭 닫기 · 모든 탭 닫기」로 닫습니다.
- 칼럼 머리에 한글 주석. 칸 단위 선택(머리를 누르면 칼럼 전체, Ctrl 로 더하기).
- 정렬: 머리를 두 번 누르면 오름 · 내림차순이 번갈아 바뀌고, 우클릭에 「오름차순 · 내림차순 정렬 · 정렬 풀기」. 복사 · INSERT · UPDATE · CSV 는 정렬된 순서로 나갑니다.
- 우클릭: 값 · 고른 행 · 칼럼 값(줄마다) · 칼럼 값 IN 목록 · 칼럼 이름 · 칼럼 이름 모두 복사.
- **찾기 · 거르기**: 결과 위 검색칸(결과 창에서 Ctrl+F)에 글자를 넣으면 그 글자가 든 행만 보입니다(「N / 전체 M건」). 복사 · 내보내기는 보이는 행 기준.
- 단추: 복사 · CSV · **엑셀**(.xlsx, 머리 행 굵게 · 틀 고정 · 한글 주석 행) · INSERT · UPDATE(앞 쉼표 여러 줄, PK 를 표 정의에서 찾아 WHERE, 못 찾으면 첫 칼럼 + 확인 주석) · DTO.
- **칸 바로 고치기**: 표 하나만 읽은 단순 조회(별칭 · 조인 · UNION · GROUP BY · 하위 조회 없음)에서 칸을 두 번 눌러 고치면 노란색으로 표시되고, 「반영 (N)」 → 문장 미리보기 확인 → PK 조건 UPDATE 를 바인드 값으로 실행합니다(운영 보호 그대로). 「되돌리기」로 취소.
- **결과 비교**: 탭 우클릭 「다른 결과와 비교…」 → 다른 탭을 고르면, 기준 칼럼(기본은 아는 PK, 없으면 첫 칼럼)으로 행을 맞춰 같음 · 다름 · 왼쪽에만 · 오른쪽에만을 세고 다른 칸을 `a → b` 로 색칠합니다. 숫자는 값으로(1 = 1.0) 비교하고, 불러온 행만 비교합니다.
- 잘렸으면 「N건에서 자름」 옆에 「500건 더」와 건수 입력칸 + 「조회」. 같은 문장을 그 건수까지 처음부터 다시 읽어 그 탭을 바꿉니다(MyBatis 매개변수 포함).

**운영 보호**
- WHERE 없는 UPDATE · DELETE, DROP · TRUNCATE, 운영 접속의 모든 변경은 표 이름을 입력해야 실행.
- 읽기 전용 접속은 변경 문장을 거절.

**MyBatis 인터페이스 ↔ XML**
- 매퍼 인터페이스 · 메서드 옆 아이콘이나 Ctrl+클릭 → 같은 namespace XML 의 그 문장으로. XML 문장 옆 아이콘 → 인터페이스 메서드로.
- XML 안에서 `<include refid>` → `<sql>`, `resultMap` · `extends` → `<resultMap>` 으로 Ctrl+클릭. `다른.namespace.id` 면 그 매퍼 XML 로 갑니다.
- XML 의 `#{필드}` 가 parameterType DTO 에 없으면 경고(「ProductDto 에 productNm 필드가 없습니다」). map · 기본형 · foreach 항목은 건너뜁니다.

**MyBatis 바로 실행** (Ctrl+Alt+Enter)
- 매퍼 XML 문장 옆 ▶ → 매개변수 입력 → `<if>` · `<foreach>` 등을 풀어 실제 SQL 미리보기 → 실행.
- resultType DTO 와 SELECT 칼럼이 안 맞으면 실행 창에 경고.

**SQL 로그 → 실행 SQL**
- 서버 콘솔의 MyBatis `Preparing:` / `Parameters:` 로그 우클릭 → 「데이터베이스 콘솔에서 실행」 · 「쿼리 콘솔로 보내기」.
- 실행 · 서버 콘솔 우클릭 「쿼리 콘솔로 보내기」: 실행하지 않고 콘솔에 붙입니다. MyBatis 로그면 값을 채운 SQL, 아니면 선택한 글 그대로.
- 「쿼리 콘솔로 보내기」 · 「데이터베이스 콘솔에서 실행」은 콘솔에 쿼리가 여럿이면 보낼 쿼리를 목록에서 고르고(최근 것이 위), 이어서 보낼 콘솔 탭을 고릅니다(「새 콘솔…」도 있음). 로그를 선택해 두면 쿼리 목록 없이 그것을 보냅니다.

**매퍼 뼈대 · DTO 만들기**
- 표나 결과에서 resultMap · select · insert · update · delete · 인터페이스 · DTO 생성.
- 구식 조인, 부등호 CDATA, `WHERE 1 = 1`, 표 · 칼럼 이름 대문자 · 별칭 소문자. 있는 파일은 덮어쓰지 않고 덧붙입니다.

**기능 한 벌 · 계층 코드**
- 표 우클릭(또는 Source 메뉴) 「기능 한 벌 만들기…」: DTO · 매퍼 XML · 매퍼 인터페이스 · 서비스(인터페이스 + Impl 나누기 선택) · 컨트롤러(@RestController 또는 @Controller)를 한 창에서 미리 보고 한 번에 만듭니다. 목록 검색 조건 칼럼을 고를 수 있고, 있는 파일은 덮어쓰지 않습니다.
- 매퍼 XML 문장 · 매퍼 인터페이스 메서드 우클릭 「계층 코드 만들기…」: 컨트롤러 · 서비스 · 매퍼 메서드 코드를 고쳐서 복사하거나 고른 클래스에 넣습니다. 「부모-자식」 탭은 문장 두 개와 잇는 칼럼을 골라, 자식 목록을 한 번에 읽어 부모마다 넣는 서비스 코드를 만듭니다.
- 매퍼 필드를 가진 클래스에서 메서드 이름을 치면 아직 없는 매퍼 문장이 자동 완성에 뜨고, 고르면 서비스 메서드가 통째로 들어갑니다.
- 컨트롤러 · 서비스 메서드 옆 아이콘: 서비스 → 매퍼 → XML 로 이동. 매퍼 XML 문장에 인터페이스 메서드가 없거나 그 반대면 노란 밑줄.

**ERD**
- 표 카드(한글 주석) + FK 선, 끌어 옮기기 · 확대 · PNG 저장. 카드 더블클릭 → 데이터 보기.

### 한국어 · Claude Code · One Dark 테마
한국어 언어 팩(JetBrains), Claude Code 플러그인(Anthropic, 마켓 `com.anthropic.code.plugin`), One Dark 테마(마켓 `com.markskelton.one-dark-theme`, VS Code 색감의 UI 테마 + 편집기 색 구성표)는 남의 것이라 본체에 넣지 않습니다. `플러그인설치.bat`이 없으면 내려받아(한국어 팩 → 릴리스 「plugins」, Claude Code · One Dark → JetBrains 마켓) 사용자 플러그인 폴더에 복사합니다. 한국어 팩 262 판은 마켓에 없고 IDEA 2026.2.3 배포판에만 들어 있어 릴리스에 그대로 올려 둔 것입니다. 한국어 언어 팩은 정식 IntelliJ IDEA 같은 판에서 `plugins/localization-ko`를 사용자 플러그인 폴더(`%APPDATA%\JetBrains\EIIDE2026.2\plugins`)에 복사하면 설정 → Language and Region에서 고를 수 있습니다.

## 설치와 판 올리기

1. `eiide-262.10968.N.exe`를 실행해 설치합니다. 서버 창 · Synchronize · 데이터베이스 · Lombok · SVN은 본체에 들어 있어 따로 넣을 것이 없습니다.
2. EI IDE를 한 번 켰다가 끕니다(설정 폴더가 만들어집니다).
3. `release\플러그인설치.bat`을 더블클릭합니다. 한국어 언어 팩 · Claude Code · One Dark 테마를 사용자 플러그인 폴더에 넣고(옆 `plugins` 폴더에 없으면 한국어 팩은 이 저장소의 릴리스 「plugins」에서, Claude Code · One Dark 는 JetBrains 마켓에서 내려받음), 시험용으로 따로 넣어 둔 본체 플러그인 사본(boot-servers · kinggura · ei-db)은 본체와 겹치지 않게 치웁니다. 옆 `jdk` · `gradle` · `drivers` 폴더가 있으면 JDK(Temurin 17 · 21 · 27)를 `%USERPROFILE%\.jdks` 에, Gradle 8.14.6 을 wrapper 가 찾는 자리에, DB 드라이버(Oracle · MySQL · PostgreSQL)를 `~/.m2` 에 풀어 둡니다(인터넷 없이, 이미 있으면 건너뜀). JDK 는 EI IDE 를 켤 때 SDK 목록에 자동 등록됩니다. IDE가 켜져 있으면 「먼저 종료」만 띄우고 멈춥니다.
4. EI IDE를 켜고 설정 → Language and Region에서 Korean을 고릅니다.

판을 올릴 때도 같은 순서입니다. 설정(단축키 · 실행 설정 · 플러그인)은 `%APPDATA%\JetBrains\EIIDE2026.2`에 있어 재설치해도 남습니다.

플러그인만 고쳐서 jar가 새로 나왔을 때는 빌드 없이 `release\plugins`에 넣고 3번만 다시 돌리면 됩니다.

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
- **새 판 점검**: `python kit\check_update.py` — JetBrains 최신 정식판이 지금 판보다 새로우면 그 소스를 `intellij-community-new` 에 받아 위 대본(brand · slim · 메뉴 · 기본값)을 적용해 보고, 새 판 플랫폼 jar 로 우리 플러그인 세 개를 컴파일해 보고, 판 번호가 박혀 손봐야 할 줄을 찾아 `logs\새판점검-<판>.txt` 에 적습니다. 지금 소스 · 대본은 바꾸지 않습니다. 특정 판을 보려면 `python kit\check_update.py 262.11234.5`.

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
kit/                  소스를 고치는 대본, 주 메뉴 코드, 플러그인 소스(boot-servers · sync · ei-db)
bundled-plugins/      본체에 넣을 플러그인 jar
plugins/              플러그인 판별 jar (사용자 플러그인 폴더에 직접 넣을 때)
release/             설치 파일 + 플러그인 묶음 + JDK · Gradle · DB 드라이버(저장소에는 올리지 않음) + 플러그인설치.bat
backup/               고치기 전 · 후 원본
진행상황.md           작업 일지
```

## 하기로 한 것

- 로고 · 시작 화면 교체 (지금은 IntelliJ 로고가 남아 있음 — JetBrains 상표라 공개 배포 전에 바꿔야 함)
- WebLogic · JEUS 실제 서버로 확인
- 화면 묶음(퍼스펙티브)은 창 배치가 제대로 안 바뀌어 뺐음. 다시 넣으려면 배치 적용부터 손봐야 함

## 라이선스

바탕이 되는 intellij-community는 Apache License 2.0입니다. 이 저장소의 대본과 플러그인 소스도 같은 조건으로 둡니다. 들어 있는 글꼴 JetBrainsMonoHangul 과 TextMate 문법(vue · svelte · astro)은 각각 SIL OFL 1.1 · MIT 이며 원문 라이선스를 함께 둡니다. IntelliJ · JetBrains 이름과 로고는 JetBrains의 상표입니다.
