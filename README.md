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
| Gradle 빌드 · 실행 | IDE가 직접 (이클립스와 같음) | Gradle에 위임 |

## 들어 있는 플러그인

본체에 함께 들어가며, 소스는 이 저장소에 있습니다.

### 서버 창 (`boot-servers`)
아래쪽 「서버」 창에서 여러 서버를 한꺼번에 다룹니다. STS의 부트 대시보드와 이클립스 Servers 뷰를 합친 것입니다.
- `@SpringBootApplication` 모듈의 실행 설정마다 한 줄씩 보입니다. 상태 · 포트 · 프로필 · 실행 시간.
- 「서버 복제」로 같은 모듈을 다른 프로필(yml) · 포트로 하나 더 띄웁니다.
- 여러 줄을 고르고 한꺼번에 시작 · 디버그 · 재시작 · 중지. 포트를 누르면 브라우저로 엽니다.
- 오른쪽 칸에서 프로필 · VM 옵션 · 포트를 바로 고칩니다.
- 프로젝트 트리의 모듈 옆에도 상태와 포트가 표시되고, 우클릭 메뉴에서도 시작 · 중지할 수 있습니다.
- **WAS 추가**: Tomcat · JBoss/WildFly · WebLogic · JEUS. 설치 폴더는 건드리지 않고 서버마다 전용 실행 폴더를 씁니다. 올릴 프로젝트와 컨텍스트 경로를 고르면 war로 묶지 않고 그 자리에서 연결하거나 바뀐 파일만 복사합니다. 디버그로 시작하면 디버거가 자동으로 붙습니다.
  - Tomcat 9 · WildFly 26으로 시작 · 배포 · 반영 · 종료를 확인했습니다. WebLogic · JEUS는 실제 서버로 아직 확인하지 못했습니다.

### Synchronize (`kinggura-sync`)
이클립스 Team → Synchronize Workspace와 같은 창입니다. 프로젝트 안의 모든 저장소(Git 여러 개, SVN)를 저장소별로 묶어 들어올 변경 · 나갈 변경 · 충돌을 보여 주고, 우클릭으로 무시 목록 추가 · 되돌리기를 합니다. 커밋 · Push 뒤 자동으로 다시 비교합니다. SVN은 `svn` 명령줄 도구가 PATH에 있어야 합니다.

### 한국어
한국어 언어 팩은 JetBrains 것이라 본체에 넣지 않습니다. 정식 IntelliJ IDEA 같은 판에서 `plugins/localization-ko`를 사용자 플러그인 폴더(`%APPDATA%\JetBrains\EIIDE2026.2\plugins`)에 복사하면 설정 → Language and Region에서 고를 수 있습니다.

## 빌드

Windows 기준입니다. 빌드는 Bazel을 써서 처음은 30분 안팎, 그 뒤 바뀐 부분만 다시 컴파일할 때는 10분 안팎 걸립니다.

```
get-source.bat          rem intellij-community + android 소스 받기 (태그 idea/262.10968.63)
python kit\customize.py intellij-community "EI IDE" brand
python kit\customize.py intellij-community "EI IDE" slim
python kit\rename.py intellij-community "EI IDE" "EI IDE"
python kit\menu\apply_menu.py intellij-community
python kit\defaults\apply_defaults.py intellij-community
kit\brand\apply_brand.bat
build-windows.bat
```

- 결과: `intellij-community\out\idea-ce\artifacts\eiide-262.10968.N.exe` (N은 빌드마다 하나씩 올라감, `build-number.txt`)
- `bundled-plugins\<이름>\lib\*.jar`에 둔 플러그인은 본체에 함께 들어갑니다.
- 소스를 고치는 대본은 모두 두 번 돌려도 같은 결과가 되고, 고치기 전 원본을 `backup\`에 남깁니다.

| 대본 | 하는 일 |
|---|---|
| `kit/customize.py brand` | 제품 이름 · 설치 폴더 · 설정 폴더 이름 |
| `kit/customize.py slim` | 번들 플러그인 덜어 내기, Android 빌드 제외 |
| `kit/rename.py` | 제품 이름 · 설치 폴더 · 설정 폴더 · 설치 파일 이름 바꾸기 |
| `kit/menu/apply_menu.py` | 이클립스식 주 메뉴, 트리 우클릭에 Maven · Gradle 다시 읽기 |
| `kit/defaults/apply_defaults.py` | 위 「기본값」 표의 항목들, SVN 번들, 플러그인 본체 포함 |
| `kit/brand/apply_brand.bat` | EI 모노그램 아이콘 · 시작 화면 · 설치 프로그램 그림 |

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

- WebLogic · JEUS 실제 서버로 확인
- 화면 묶음(퍼스펙티브)은 창 배치가 제대로 안 바뀌어 뺐음. 다시 넣으려면 배치 적용부터 손봐야 함
- 빌드가 끝나면 GitHub 릴리스로 자동 올리기

## 라이선스

바탕이 되는 intellij-community는 Apache License 2.0입니다. 이 저장소의 대본과 플러그인 소스도 같은 조건으로 둡니다. IntelliJ · JetBrains 이름과 로고는 JetBrains의 상표입니다.
