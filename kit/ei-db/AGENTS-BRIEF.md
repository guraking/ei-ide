# EI Database 플러그인 — 공통 작업 지침

EI IDE(인텔리제이 커뮤니티 2026.2 기반 개인 IDE, 빌드 262.10968.x)에 넣을 데이터베이스 플러그인이다. 네 명이 동시에 나눠 만든다.

## 작업 위치 (중요)
- 자기 복사본에서만 일한다: `/home/claude/ei-db-<담당글자>/` (A·B·C·D). 원본 `/home/claude/ei-db/` 는 건드리지 않는다.
- 자기 패키지 폴더만 만들고 고친다. A=`src/ei/db/ui`, B=`src/ei/db/console`, C=`src/ei/db/mybatis`, D=`src/ei/db/gen`.
- `src/ei/db/*.java`(공통 핵심)와 다른 담당의 패키지는 읽기만 한다. 핵심에 꼭 필요한 것이 없으면 자기 패키지 안에 만들고 보고에 적는다.
- 자기 패키지에 있는 자리표시 클래스(stub)는 **서명 그대로** 실제 구현으로 바꾼다. 다른 담당이 그 서명을 부른다.
- plugin.xml: 자기 복사본의 `resources/META-INF/plugin.xml` 에서 자기 표시(`<!-- @@A -->`, `<!-- @@A-actions -->` 등) 바로 아래에만 넣는다. 나중에 합친다.
- 컴파일: 복사본 폴더에서 `./build.sh`(성공해야 끝). 순수 자바 논리(파서 등)는 `test/` 에 main 으로 자체 점검을 두고 돌려 통과시킨다.

## 공통 핵심 (src/ei/db, 이미 있음 — 먼저 읽을 것)
- `DbConfig` 접속 설정(공개 필드), `DbKind`(ORACLE·MYSQL·POSTGRES), `DbSettings.getInstance()`(접속 목록, put/remove/find), `Passwords.get/set`.
- `DbSessions.get(project)`: `connection(c)`, `run(c, sql, binds)`, `commit/rollback/close(id)`, `pending(id)`, `isOpen(id)`, `addListener(disposable, runnable)`, 정적 `test(c, password)`. **화면 스레드에서 부르지 않는다.**
- `SqlRunner.run(project, c, sql, binds)`: 실행 공통 입구(읽기 전용 · 위험 문장 · 운영 확인 → 백그라운드 실행 → ResultView). 화면 스레드에서 부른다.
- `Meta`: `schemaOf(c)`, `schemas`, `tables(conn,c,schema,types)`, `columns(conn,c,schema,table)`(PK·FK·주석 포함), `importedKeys`.
- `Comments.ofTable / forQuery / clear`, `QueryResult`(columns, columnTypes, rows, updateCount, millis, truncated, comments), `SqlGuard.isWrite/danger/table`, `Notify.info/error`(알림 그룹 "EI Database").
- 다른 담당의 입구(서명 고정): `console.ConsoleOpener.open(project,c,initialSql)`, `console.ResultView.show/showError`, `console.GuardDialog.confirm`, `ui.ConnectDialog.edit(project, existingOrNull)`, `gen.DtoDialog.show(project,c,result)`, `gen.MapperDialog.show(project,c,schema,table)`, `gen.ErdView.open(project,c,schema,tablesOrNull)`.

## 화면 기준
- 확정된 목업: `/tmp/claude-0/-home-claude/1430a897-9ca2-50f9-8007-1cf8ec6091fe/scratchpad/dbmock/project/*.dc.html` (HTML). 자기 담당 화면 파일을 열어 문구 · 배치 · 동작을 그대로 따른다. 색은 하드코딩하지 말고 IDE 테마 색(JBColor, UIUtil, JBUI)을 쓴다. 위험 · 경고 · 성공 색만 `JBColor.namedColor` 또는 `JBColor(라이트, 다크)` 로.
- 인텔리제이 부품을 쓴다: `DialogWrapper`, `JBTable`, `Tree`/`SimpleTree`, `JBTextField`, `ComboBox`, `JBCheckBox`, `ToolWindowFactory`, `ActionToolbar`, `EditorFactory`, `FileEditorManager`, `Messages`, `JBPopupFactory`, `TextFieldWithBrowseButton`, `FormBuilder`.
- 사용 가능한 클래스는 `build.sh` 의 클래스패스 jar 안에 있는 것뿐이다(`unzip -l` 로 확인). XML PSI(`com.intellij.psi.xml`)는 없다 → XML 은 문서 글자로 다룬다.

## 코드 규칙 (사용자 지침)
- 화면 글 · 알림 · 오류 문구는 모두 한국어(코드 식별자 · SQL 키워드만 영어).
- 주석은 스펙(보장하는 것, 경계값, 왜 이렇게 했는지)만. 작업 일지 · 감탄 · 이모지 금지. 6개월 뒤 동료에게 설명하듯 담백하게.
- 단순 순회는 향상된 for 문. `stream().forEach` 금지, 긴 람다 금지. API 가 함수형 인터페이스를 요구할 때만 람다.
- 값 하드코딩 금지: 의미 있는 값은 이름 붙은 상수로. 한 번 쓰고 뜻이 뻔한 값은 그대로.
- 지금 필요한 것만(YAGNI). 단, 입력 검증 · 에러 처리(실패 알림, null, 빈 결과)는 빼지 않는다. 예외를 삼키지 않는다(무시가 맞으면 이유를 주석으로).
- JDBC · 파일 검색 같은 느린 일은 `Task.Backgroundable` 또는 `executeOnPooledThread`, 화면 갱신은 `invokeLater`. 파일 쓰기는 `WriteCommandAction`.
- 자원은 Disposable 로 정리. 리스너는 `DbSessions.addListener(disposable, …)`.

## 하지 말 것
- 원본 폴더 · 다른 담당 패키지 · 공통 핵심 수정, 게시, 사용자 PC 접근, 인터넷에서 라이브러리 받기.
- 보고는 짧게: 만든 파일 목록, plugin.xml 에 넣은 줄, 핵심에 없어 자기 패키지에 만든 것, 못 한 것 · 확인 못 한 것.
