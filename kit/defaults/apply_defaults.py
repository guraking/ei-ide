#!/usr/bin/env python3
"""KingGura IDE 기본값을 intellij-community 소스에 넣는다.

사용법: python3 apply_defaults.py <intellij-community 폴더>

  1) 실행 설정 창의 VM 옵션 칸을 처음부터 보이게 한다(원래는 값이 있을 때만 보임).
  2) 에디터 탭 줄 오른쪽에 「세로로 분할」 단추를 둔다(VS Code 의 분할 단추 자리).
  3) 커밋 후 커밋 메시지를 비우는 것을 기본으로 한다.
  4~9) 이클립스 단축키 · 하위 빌드 파일 자동 연결 · 저장 시 자동 빌드 · 저장 시 import 정리와 바뀐 줄 서식 · 플러그인 본체 포함.
  10) AI 에이전트 연결 · 플러그인 개발용 실행 도구 플러그인 제외.
  11) SVN 플러그인 번들 추가.
  15) 설치 화면 「설치 옵션」 칸을 모두 체크한 채로 띄운다.
  16) properties 파일의 「투명한 native-to-ascii 변환」을 기본으로 켠다.
  17) 커밋 전 「Analyze code」(코드 분석) · 「Check TODO」 검사를 기본으로 끈다.
  18) 다른 프로젝트를 열 때 기본으로 새 창에 연다.
  19) SVN 실행 파일 기본 경로를 설치본에 같이 넣은 svn.exe(plugins/kinggura-sync/svn/bin)로 한다.
  20) 콘솔에 남기는 글 양(cycle buffer)을 기본 1 MB 에서 50 MB 로 늘린다.
  21) 프로젝트 창에서 바뀐 파일 · 새 파일이 든 폴더 · 패키지 이름도 그 색으로 보인다.
고치기 전 원본은 <intellij-community>/../backup/<시각>-before/ 에 같은 상대경로로 남긴다.
모든 원문을 먼저 찾아 본 뒤에 쓴다. 하나라도 못 찾으면 아무 파일도 바꾸지 않는다. 줄끝(LF · CRLF)은 파일마다 원래대로 둔다.
두 번 실행해도 같은 결과가 된다.
"""
import shutil
import sys
import time
from pathlib import Path

# (파일, 바꿀 원문, 바꿀 내용). 원문은 2026.2.3 소스에서 정확히 한 번 나오는 것만 쓴다.
EDITS = [
    ("java/execution/impl/src/com/intellij/execution/ui/CommonJavaFragments.java",
     "configuration -> StringUtil.isNotEmpty(configuration.getVMParameters()));",
     "configuration -> true);"),
    ("platform/platform-resources/src/idea/PlatformActions.xml",
     '<group id="EditorTabsToolbarActions" searchable="false"/>',
     '<group id="EditorTabsToolbarActions" searchable="false">\n'
     '      <reference ref="SplitVertically"/>\n'
     '    </group>'),
    ("platform/vcs-api/shared/src/com/intellij/openapi/vcs/VcsConfiguration.java",
     "public boolean CLEAR_INITIAL_COMMIT_MESSAGE = false;",
     "public boolean CLEAR_INITIAL_COMMIT_MESSAGE = true;"),
    # 17) 커밋 전 코드 분석 · TODO 검사는 경고(새 TODO)가 하나만 있어도 커밋 · 푸시를 멈추고 확인을 기다리므로 기본으로 끈다(커밋 창 톱니에서 다시 켤 수 있음).
    ("platform/vcs-api/shared/src/com/intellij/openapi/vcs/VcsConfiguration.java",
     "public boolean CHECK_CODE_SMELLS_BEFORE_PROJECT_COMMIT =\n"
     "    !PlatformUtils.isPyCharm() && !PlatformUtils.isRubyMine() && !PlatformUtils.isCLion();",
     "public boolean CHECK_CODE_SMELLS_BEFORE_PROJECT_COMMIT = false;"),
    ("platform/vcs-api/shared/src/com/intellij/openapi/vcs/VcsConfiguration.java",
     "public boolean CHECK_NEW_TODO = true;",
     "public boolean CHECK_NEW_TODO = false;"),
    # 4) 윈도우 첫 실행 단축키를 이클립스로 한다(intellij.keymap.eclipse 는 번들에 남아 있음).
    ("platform/platform-impl/src/com/intellij/openapi/keymap/impl/DefaultKeymap.kt",
     "else -> KeymapManager.DEFAULT_IDEA_KEYMAP",
     'else -> "Eclipse"'),
    # 5) 상위 폴더로 처음 열었는데 그 폴더에 빌드 파일이 없으면, 바로 아래 폴더들의 build.gradle · pom.xml 을 각각 연결한다.
    ("platform/external-system-impl/src/com/intellij/openapi/externalSystem/autolink/UnlinkedProjectStartupActivity.kt",
     "        return\n      }\n    }\n    if (LOG.isDebugEnabled) {",
     "        return\n      }\n"
     "      if (isExpectedAutoLink && unlinkedProjects.isEmpty() && linkedProjects.isEmpty()) {\n"
     "        // KingGura: 이클립스의 기존 프로젝트 가져오기처럼 한 단계 아래 폴더까지만 본다. 숨김 폴더는 건너뛴다.\n"
     "        val childPaths = readAction {\n"
     "          LocalFileSystem.getInstance().findFileByPath(externalProjectPath)?.children.orEmpty()\n"
     "            .filter { it.isDirectory && !it.name.startsWith(\".\") }\n"
     "            .map { it.path }\n"
     "        }\n"
     "        for (childPath in childPaths.sorted()) {\n"
     "          val (childLinked, childUnlinked) = findLinkedAndUnlinkedProjects(project, childPath)\n"
     "          if (childLinked.isEmpty() && childUnlinked.isNotEmpty()) {\n"
     "            childUnlinked.first().linkAndLoadProjectAsync(project, childPath)\n"
     "          }\n"
     "        }\n"
     "        return\n"
     "      }\n"
     "    }\n    if (LOG.isDebugEnabled) {"),
    # 6) 저장하면 자동 빌드(이클립스 Build Automatically). 그래들 위임 빌드에서는 자동 빌드가 돌지 않으므로 새 그래들 프로젝트는 자체 빌드를 기본으로 한다.
    ("java/compiler/openapi/src/com/intellij/compiler/CompilerWorkspaceConfiguration.java",
     "public boolean MAKE_PROJECT_ON_SAVE = false;",
     "public boolean MAKE_PROJECT_ON_SAVE = true;"),
    ("plugins/gradle/settings/src/settings/GradleProjectSettings.java",
     "public static final boolean DEFAULT_DELEGATE = true;",
     "public static final boolean DEFAULT_DELEGATE = false;"),
    # 7) 저장할 때 자바 import 정리.
    ("platform/lang-impl/src/com/intellij/codeInsight/actions/onSave/OptimizeImportsOnSaveOptions.java",
     "      super(DefaultsProvider::getFileTypesWithOptimizeImportsOnSaveByDefault);\n    }",
     "      super(DefaultsProvider::getFileTypesWithOptimizeImportsOnSaveByDefault);\n"
     "      // KingGura: 다른 플러그인이 기본값을 주지 않았으면 자바 파일만 켠다.\n"
     "      if (mySelectedFileTypes.isEmpty()) {\n"
     "        myRunOnSave = true;\n"
     "        myAllFileTypesSelected = false;\n"
     "        mySelectedFileTypes.add(\"JAVA\");\n"
     "      }\n    }"),
    # 8) 저장할 때 자바 서식 맞춤. 바뀐 줄만 맞춰서 손대지 않은 줄의 회사 서식은 그대로 둔다.
    ("platform/lang-impl/src/com/intellij/codeInsight/actions/onSave/FormatOnSaveOptions.java",
     "      super(DefaultsProvider::getFileTypesFormattedOnSaveByDefault);\n    }",
     "      super(DefaultsProvider::getFileTypesFormattedOnSaveByDefault);\n"
     "      // KingGura: 다른 플러그인이 기본값을 주지 않았으면 자바 파일만, 바뀐 줄만 맞춘다.\n"
     "      if (mySelectedFileTypes.isEmpty()) {\n"
     "        myRunOnSave = true;\n"
     "        myAllFileTypesSelected = false;\n"
     "        mySelectedFileTypes.add(\"JAVA\");\n"
     "      }\n"
     "      myFormatOnlyChangedLines = true;\n    }"),
    # 9) <작업 폴더>/bundled-plugins/<플러그인>/lib/*.jar 를 기본 플러그인으로 함께 넣는다(서버 시작 · 동기화 · 롬복).
    ("build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt",
     "import java.nio.file.Path\n",
     "import java.nio.file.Files\nimport java.nio.file.Path\n"),
    ("build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt",
     "  protected open suspend fun bundleExternalPlugins(context: BuildContext, targetDirectory: Path) {}\n",
     "  protected open suspend fun bundleExternalPlugins(context: BuildContext, targetDirectory: Path) {}\n\n"
     "  // KingGura: 폴더가 없으면 아무것도 넣지 않는다. 각 하위 폴더는 lib/*.jar 를 가져야 한다(빌드가 검사함).\n"
     "  override suspend fun getAdditionalPluginPaths(context: BuildContext): List<Path> {\n"
     "    val dir = communityHomeDir.parent.resolve(\"bundled-plugins\")\n"
     "    if (!Files.isDirectory(dir)) return emptyList()\n"
     "    return Files.list(dir).use { paths -> paths.filter { Files.isDirectory(it) }.sorted().toList() }\n"
     "  }\n"),
    # 10) 가볍게: AI 에이전트 연결(Agent Client Protocol) · 플러그인 개발용 실행 도구(DevKit Runtime)를 번들에서 뺀다.
    #     남기는 플러그인(Java · Maven · Gradle · Git · GitHub · GitLab · Markdown · YAML · Properties · Lombok · JUnit · Groovy · 터미널)은 이 둘에 의존하지 않는다.
    ("build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt",
     '      "intellij.java.i18n",\n',
     '      "intellij.java.i18n",\n'
     '      "intellij.platform.acp.plugin",  // KingGura: AI 에이전트 연결은 쓰지 않는다\n'
     '      "intellij.dev",  // KingGura: 플러그인 개발용 실행 도구는 쓰지 않는다\n'),
    # 11) SVN(Subversion) 플러그인을 번들에 넣는다. 커뮤니티 소스에 있지만 기본 번들 목록에는 없다(plugins/svn4idea, 모듈 intellij.vcs.svn).
    ("build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt",
     '      "intellij.dev",  // KingGura: 플러그인 개발용 실행 도구는 쓰지 않는다\n    ))\n',
     '      "intellij.dev",  // KingGura: 플러그인 개발용 실행 도구는 쓰지 않는다\n    )).add("intellij.vcs.svn")  // KingGura: SVN 저장소도 쓴다\n'),
    # 12) 편집기 기본 글꼴을 한글 폭이 맞는 JetBrainsMonoHangul 로 한다. 글꼴 파일은 boot-servers 플러그인이 켤 때 등록한다(BundledFonts).
    ("platform/editor-ui-api/src/com/intellij/openapi/editor/colors/FontPreferences.java",
     '    if (SystemInfo.isJetBrainsJvm) {\n      return JETBRAINS_MONO;\n    }\n',
     '    if (SystemInfo.isJetBrainsJvm) {\n      return "JetBrainsMonoHangul";  // EI IDE: boot-servers 플러그인이 등록하는 한글 글꼴\n    }\n'),
    # 13) JetBrains 쪽 자동 업데이트 확인을 끈다. IDE 새 판은 GitHub 릴리스를 보는 boot-servers 가 알리고,
    #     플러그인 확인은 마켓의 번들 플러그인(Java · Gradle 등)을 「새 판」으로 받아 와 여기서 고친 기본값을 덮는 일이 있었다.
    ("platform/platform-impl/src/com/intellij/openapi/updateSettings/impl/UpdateOptions.kt",
     '  @get:OptionTag("CHECK_NEEDED")\n  var isCheckNeeded: Boolean by property(true)\n',
     '  @get:OptionTag("CHECK_NEEDED")\n  var isCheckNeeded: Boolean by property(false)  // EI IDE: 새 판은 GitHub 릴리스로 알린다\n'),
    ("platform/platform-impl/src/com/intellij/openapi/updateSettings/impl/UpdateOptions.kt",
     '  @get:OptionTag("PLUGINS_CHECK_NEEDED")\n  var isPluginsCheckNeeded: Boolean by property(true)\n',
     '  @get:OptionTag("PLUGINS_CHECK_NEEDED")\n  var isPluginsCheckNeeded: Boolean by property(false)  // EI IDE: 번들 플러그인이 마켓 판으로 덮이지 않게\n'),
    # 14) 편집기 오른쪽 여백선(120자 세로선)을 기본으로 숨긴다. 설정 → 에디터 → 일반 → 모양새 「시각적 안내선 표시」와 같은 값.
    ("platform/ide-core-impl/src/com/intellij/openapi/editor/ex/EditorSettingsExternalizable.java",
     '    public boolean IS_RIGHT_MARGIN_SHOWN = true;\n',
     '    public boolean IS_RIGHT_MARGIN_SHOWN = false;  // EI IDE: 여백선은 기본으로 숨긴다\n'),
    # 15) 설치 화면 「설치 옵션」의 체크 칸(데스크톱 바로가기 · PATH · 폴더를 프로젝트로 열기 · 파일 연결)을 모두 체크한 채로 띄운다.
    #     칸마다 위치 값이 달라 State 앞의 위치 줄까지 붙여야 원문이 한 번만 나온다. 조용히 설치(/S)는 silent.config 값을 따른다.
] + [("build/conf/nsis/desktop.ini", f"{pos}\nState=0\n", f"{pos}\nState=1\n") for pos in (
    "Right=135\nTop=25\nBottom=35",   # Field 2  데스크톱 바로가기
    "Right=-1\nTop=25\nBottom=35",    # Field 4  PATH 에 bin 추가
    "Right=135\nTop=60\nBottom=70",   # Field 6  폴더를 프로젝트로 열기
    "Right=43\nTop=95\nBottom=105",   # Field 8~14  파일 연결
    "Right=86\nTop=95\nBottom=105",
    "Right=129\nTop=95\nBottom=105",
    "Right=172\nTop=95\nBottom=105",
    "Right=215\nTop=95\nBottom=105",
    "Right=258\nTop=95\nBottom=105",
    "Right=301\nTop=95\nBottom=105",
)] + [
    # 16) properties 파일의 「투명한 native-to-ascii 변환」을 기본으로 켠다(messages_ko.properties 의 \uXXXX 를 한글로 보인다).
    #     encodings.xml 에 값이 없는 새 프로젝트 · 기존 프로젝트 모두 켜짐으로 읽고, 끈 경우에만 false 를 남긴다.
    #     문자 집합은 그대로 둔다(기본 UTF-8, UTF-8 이 아닌 바이트가 있으면 ISO-8859-1 · 저장은 \uXXXX 이스케이프).
    ("platform/platform-impl/src/com/intellij/openapi/vfs/encoding/EncodingProjectManagerImpl.java",
     "  private boolean myNative2AsciiForPropertiesFiles;\n",
     "  private boolean myNative2AsciiForPropertiesFiles = true;  // EI IDE: properties 의 유니코드 이스케이프를 글자로 보인다\n"),
    ("platform/platform-impl/src/com/intellij/openapi/vfs/encoding/EncodingProjectManagerImpl.java",
     '    if (myNative2AsciiForPropertiesFiles) {\n'
     '      element.setAttribute("native2AsciiForPropertiesFiles", Boolean.toString(true));\n'
     '    }\n',
     '    if (!myNative2AsciiForPropertiesFiles) {\n'
     '      element.setAttribute("native2AsciiForPropertiesFiles", Boolean.toString(false));  // EI IDE: 기본이 켜짐이므로 끈 것만 남긴다\n'
     '    }\n'),
    ("platform/platform-impl/src/com/intellij/openapi/vfs/encoding/EncodingProjectManagerImpl.java",
     '    myNative2AsciiForPropertiesFiles = Boolean.parseBoolean(element.getAttributeValue("native2AsciiForPropertiesFiles"));\n',
     '    myNative2AsciiForPropertiesFiles = !Boolean.toString(false).equalsIgnoreCase(element.getAttributeValue("native2AsciiForPropertiesFiles"));  // EI IDE: 값이 없으면 켜짐\n'),
    # 18) 다른 프로젝트를 열 때 지금 창을 닫지 않고 새 창에 연다. 설정 → 시스템 설정 「Open project in」의 기본값이다.
    #     사용자가 고른 값이 ide.general.xml 에 남아 있으면 그 값을 따른다.
    ("platform/ide-core/src/com/intellij/ide/GeneralSettings.kt",
     "    fun defaultConfirmNewProject(): Int = OPEN_PROJECT_ASK\n",
     "    fun defaultConfirmNewProject(): Int = OPEN_PROJECT_NEW_WINDOW  // EI IDE: 다른 프로젝트는 새 창에 연다\n"),
    # 19) 2026.2 의 SVN 기능은 svn 명령줄 도구를 불러 쓴다. 설치본 plugins/kinggura-sync/svn 에 같이 넣은
    #     VisualSVN 명령줄 도구가 있으면 그 svn.exe 를 기본 경로로 한다. 없으면 원래대로 PATH 의 svn 이다.
    #     설정 → Version Control → Subversion 에서 고른 경로가 other.xml 에 남아 있으면 그 값을 따른다.
    ("plugins/svn4idea/src/org/jetbrains/idea/svn/SvnApplicationSettings.java",
     '    public @NlsSafe String mySvnCommandLine = "svn";\n',
     '    public @NlsSafe String mySvnCommandLine = defaultSvnCommandLine();\n'
     '\n'
     '    // EI IDE: 설치본에 같이 넣은 svn.exe 가 있으면 그것을, 없으면 PATH 의 svn 을 쓴다.\n'
     '    private static final String BUNDLED_SVN = "plugins/kinggura-sync/svn/bin/svn.exe";\n'
     '\n'
     '    private static String defaultSvnCommandLine() {\n'
     '      File bundled = new File(PathManager.getHomePath(), BUNDLED_SVN);\n'
     '      return bundled.isFile() ? bundled.getPath() : "svn";\n'
     '    }\n'),
    # 20) 콘솔은 앞부분을 버려 cycle buffer 크기(원래 1024 KB)만 남긴다. 서버 로그가 금방 잘리므로
    #     Settings > Editor > General > Console 의 「Override console cycle buffer size」를 켠 채 51200 KB(50 MB)로 둔다.
    #     설정값은 기본값과 다를 때만 저장되므로, 이 칸을 건드린 적 없는 기존 설정에도 그대로 적용된다.
    ("platform/editor-ui-api/src/com/intellij/ide/ui/UISettingsState.kt",
     "var overrideConsoleCycleBufferSize: Boolean by property(false)",
     "var overrideConsoleCycleBufferSize: Boolean by property(true)"),
    ("platform/editor-ui-api/src/com/intellij/ide/ui/UISettingsState.kt",
     "var consoleCycleBufferSizeKb: Int by property(1024)",
     "var consoleCycleBufferSizeKb: Int by property(51200)"),
    # 21) 프로젝트 창에서 바뀐 파일 · 새 파일이 아래에 있는 폴더 · 패키지 이름을 그 상태 색으로 칠한다(이클립스의 상위 폴더 변경 표시).
    #     ChangeListManager 의 상태를 쓰므로 Git · SVN 모두 된다. 설정 → Version Control 「Show directories with changed descendants」 의 기본값이다.
    #     VcsConfiguration 은 기본값과 다른 값만 workspace.xml 에 남기므로, 이 칸을 건드린 적 없는 기존 프로젝트에도 적용된다.
    ("platform/vcs-api/shared/src/com/intellij/openapi/vcs/VcsConfiguration.java",
     "public boolean SHOW_DIRTY_RECURSIVELY = false;",
     "public boolean SHOW_DIRTY_RECURSIVELY = true;"),
]


def read_source(path):
    """줄끝을 \\n 으로 맞춘 글과 원래 줄끝을 돌려준다. 원문 비교는 \\n 기준이고, 쓸 때 원래 줄끝으로 되돌린다.
    모든 줄이 \\r\\n 일 때만 바꾼다. LF · CRLF 가 섞인 파일은 그대로 두어(쓸 때도 그대로) 한쪽으로 통일되지 않게 한다.
    섞인 파일에서 CRLF 줄에 걸친 원문은 찾지 못해 「못 찾음」으로 멈춘다(파일은 쓰지 않는다)."""
    raw = path.read_bytes().decode("utf-8")
    crlf = raw.count("\r\n")
    if crlf > 0 and crlf == raw.count("\n"):
        return raw.replace("\r\n", "\n"), "\r\n"
    return raw, "\n"


def write_source(path, text, newline):
    # 바이트로 써서 운영체제가 줄끝을 바꾸지 않게 한다(윈도우의 write_text 는 \n 을 \r\n 으로 바꾼다).
    path.write_bytes(text.replace("\n", newline).encode("utf-8"))


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    root = Path(sys.argv[1])
    # 1단계: 모든 수정을 메모리에서만 검사 · 적용한다. 하나라도 못 찾으면 아무 파일도 쓰지 않고 멈춘다.
    texts = {}
    changed = []
    errors = []
    for rel, old, new in EDITS:
        if rel not in texts:
            path = root / rel
            if not path.is_file():
                errors.append(f"파일이 없습니다: {rel}")
                texts[rel] = None
                continue
            texts[rel] = read_source(path)
        if texts[rel] is None:
            continue
        text, newline = texts[rel]
        # 바꿀 내용이 원문을 품는 수정(줄 추가)도 있으므로 바꿀 내용이 이미 있으면 적용된 것으로 본다.
        if new in text:
            print(f"이미 적용됨: {rel}")
            continue
        if text.count(old) != 1:
            errors.append(f"원문을 정확히 한 번 찾지 못했습니다({text.count(old)}번): {rel}\n  {old[:80]}")
            continue
        texts[rel] = (text.replace(old, new), newline)
        if rel not in changed:
            changed.append(rel)
    if errors:
        raise SystemExit("아무 파일도 바꾸지 않았습니다.\n" + "\n".join(errors))
    # 2단계: 원본을 남기고 쓴다.
    backup = root.parent / "backup" / (time.strftime("%Y%m%d-%H%M%S") + "-before")
    for rel in changed:
        path = root / rel
        dest = backup / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
        write_source(path, *texts[rel])
        print(f"적용: {rel}")


if __name__ == "__main__":
    main()
