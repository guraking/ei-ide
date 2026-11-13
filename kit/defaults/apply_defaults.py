#!/usr/bin/env python3
"""KingGura IDE 기본값을 intellij-community 소스에 넣는다.

사용법: python3 apply_defaults.py <intellij-community 폴더>

  1) 실행 설정 창의 VM 옵션 칸을 처음부터 보이게 한다(원래는 값이 있을 때만 보임).
  2) 에디터 탭 줄 오른쪽에 「세로로 분할」 단추를 둔다(VS Code 의 분할 단추 자리).
  3) 커밋 후 커밋 메시지를 비우는 것을 기본으로 한다.
  4~9) 이클립스 단축키 · 하위 빌드 파일 자동 연결(두 단계 아래까지) · 저장 시 자동 빌드 · 저장 시 import 정리와 바뀐 줄 서식 · 플러그인 본체 포함.
  11) SVN 플러그인 번들 추가.
  15) 설치 화면 「설치 옵션」 칸을 모두 체크한 채로 띄운다.
  16) properties 파일의 「투명한 native-to-ascii 변환」을 기본으로 켠다.
  17) 커밋 전 「Analyze code」(코드 분석) · 「Check TODO」 검사를 기본으로 끈다.
  18) 다른 프로젝트를 열 때 기본으로 새 창에 연다.
  19) SVN 실행 파일 기본 경로를 설치본에 같이 넣은 svn.exe(plugins/kinggura-sync/svn/bin)로 한다.
  20) 콘솔에 남기는 글 양(cycle buffer)을 기본 1 MB 에서 30 MB 로 늘린다.
  21) 프로젝트 창에서 바뀐 파일 · 새 파일이 든 폴더 · 패키지 이름도 그 색으로 보인다.
  22) 첫 실행 기본 어두운 테마를 Moa Graphite(boot-servers 플러그인)로 한다. 예전에 넣은 다른 default-laf 줄(EI Glass · EI Graphite 등)은 지운다.
  23) 테마 키가 있을 때만 켜지는 그리기: 창 전체 빛 배경, 트리 · 목록 선택 테두리, 툴바 알약, 열린 툴 창 단추 색(Moa Graphite · Moa White 는 키를 두지 않아 그리지 않는다).
  24) 프로젝트 머리글자 아이콘에 한글 같은 JetBrains Mono 에 없는 글자가 오면 대체 글꼴이 있는 Dialog 글꼴로 그린다.
  26) 프로젝트 창 모듈 줄을 모두 폴더 이름만 굵게 그린다(뒤의 [모듈 이름] 을 뺀다).
  27) TextMate html 번들이 .jsp 를 잡지 않게 하여 .jsp 는 언제나 jsp 번들(지시문 · 스크립틀릿 · ${} 을 아는 쪽)이 맡는다.
  28) 설치 파일(.exe) 압축을 lzma 에서 zlib 로 바꿔 빌드 시간을 줄인다.
  29) 단추 글자를 글자 상자가 아니라 대문자 · 한글 높이 기준으로 세로 가운데에 둔다(맑은 고딕에서 1~2px 아래로 처지던 것).
  30) 새 설정 폴더(MoaIDE…)로 처음 켤 때, 이름을 바꾸기 전 설정 폴더(EIIDE…)도 가져올 후보로 본다.
  31) IntelliJ 의 20분마다 원격 커밋 확인을 기본으로 끈다.
  32) 예전 도구로 묶은 jar(zip64 칸 검사에 걸리는 것)도 열도록 설치본 vmoptions 에 검사 끄기 속성을 넣는다.
  33) 옛 설정 폴더를 가져올 때 우리 플러그인 옛 시험본과 JetBrains AI 플러그인은 옮기지 않는다.
  34) Maven 은 Maven Wrapper(mvnw) 대신 언제나 내장 Maven 3 을 쓰고, Wrapper 배포본을 받지 않는다.
  35) Gradle 은 Wrapper 대신 언제나 설치본에 넣은 Gradle(kit/gradle/apply_gradle.py)을 쓴다.
      프로젝트의 wrapper 가 9 보다 낮은 판을 적었으면 설치본의 8 대, 그 밖에는 9 대다.
고치기 전 원본은 <intellij-community>/../backup/<시각>-before/ 에 같은 상대경로로 남긴다.
모든 원문을 먼저 찾아 본 뒤에 쓴다. 하나라도 못 찾으면 아무 파일도 바꾸지 않는다. 줄끝(LF · CRLF)은 파일마다 원래대로 둔다.
두 번 실행해도 같은 결과가 된다.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from customize import SLIM_REMOVE  # noqa: E402
from patchlib import apply_edits  # noqa: E402

# (파일, 바꿀 원문, 바꿀 내용). 원문은 2026.3(263.7307.200) 소스에서 정확히 한 번 나오는 것만 쓴다.
BUTTON_UI_FILE = "platform/platform-impl/src/com/intellij/ide/ui/laf/darcula/ui/DarculaButtonUI.java"
DEFAULT_LAF_FILE = "community-resources/resources/idea/IdeaApplicationInfo.xml"
DEFAULT_LAF_LINE = '  <default-laf dark="Moa Graphite"/>\n'
FONT_FILE = "platform/editor-ui-api/src/com/intellij/openapi/editor/colors/FontPreferences.java"
# 글꼴 목록을 만드는 서비스. Moa Mono 를 목록보다 먼저 등록하는 곳(12-1).
FONT_SERVICE_FILE = "platform/platform-impl/src/com/intellij/openapi/editor/impl/FontFamilyServiceImpl.java"
FONT_ORIGINAL = '    if (SystemInfo.isJetBrainsJvm) {\n      return JETBRAINS_MONO;\n    }\n'
# 5) 자동 연결. Clone(CloneIntoProject.BUILD_DEPTH)처럼 두 단계 아래까지 본다. 빌드 파일을 찾은 폴더 아래와 빌드 결과 폴더로는 내려가지 않는다.
AUTOLINK_FILE = "platform/external-system-impl/src/com/intellij/openapi/externalSystem/autolink/UnlinkedProjectStartupActivity.kt"
AUTOLINK_ORIGINAL = "        return\n      }\n    }\n    if (LOG.isDebugEnabled) {"
AUTOLINK_DEPTH2 = (
    "        return\n      }\n"
    "      if (isExpectedAutoLink && unlinkedProjects.isEmpty() && linkedProjects.isEmpty()) {\n"
    "        // KingGura: 이클립스의 기존 프로젝트 가져오기처럼 두 단계 아래 폴더까지 본다(Clone 과 같은 깊이). 숨김 폴더는 건너뛴다.\n"
    "        // 빌드 파일이 있거나 이미 연결된 폴더 아래, 빌드 결과 폴더 아래로는 내려가지 않는다(다중 모듈은 맨 위 파일이 아래까지 읽는다).\n"
    "        val eiSkipDirs = setOf(\"target\", \"build\", \"out\", \"bin\", \"node_modules\")\n"
    "        var eiParents = listOf(externalProjectPath)\n"
    "        repeat(2) { _ ->\n"
    "          val parents = eiParents\n"
    "          val childPaths = readAction {\n"
    "            parents.flatMap { parentPath ->\n"
    "              StandardFileSystems.local().findFileByPath(parentPath)?.children.orEmpty()\n"
    "                .filter { it.isDirectory && !it.name.startsWith(\".\") }\n"
    "                .map { it.path }\n"
    "            }\n"
    "          }\n"
    "          val eiNext = ArrayList<String>()\n"
    "          for (childPath in childPaths.sorted()) {\n"
    "            val (childLinked, childUnlinked) = findLinkedAndUnlinkedProjects(project, childPath)\n"
    "            if (childLinked.isEmpty() && childUnlinked.isNotEmpty()) {\n"
    "              childUnlinked.first().linkAndLoadProjectAsync(project, childPath)\n"
    "            }\n"
    "            else if (childLinked.isEmpty() && childPath.substringAfterLast('/') !in eiSkipDirs) {\n"
    "              eiNext.add(childPath)\n"
    "            }\n"
    "          }\n"
    "          eiParents = eiNext\n"
    "        }\n"
    "        return\n"
    "      }\n"
    "    }\n    if (LOG.isDebugEnabled) {")
# 35) 설치본에 넣은 Gradle 들의 IDE 설치 폴더 기준 위치. kit/gradle/apply_gradle.py 의 TARGET · VERSIONS 와 같아야 한다.
GRADLE_SETTINGS_FILE = "plugins/gradle/settings/src/settings/GradleProjectSettings.java"
GRADLE_HOME = "plugins/boot-servers/gradle/gradle-9.9.1"
GRADLE_HOME_8 = "plugins/boot-servers/gradle/gradle-8.14.7"
# wrapper 가 적은 판의 맨 앞 숫자가 이보다 낮으면 GRADLE_HOME_8 을 쓴다(GRADLE_HOME 의 맨 앞 숫자).
GRADLE_MAJOR = 9
GRADLE_HOME_ORIGINAL = "  public @Nullable Path getGradleHomePath() {\n    return myGradleHome;\n  }\n"
GRADLE_TYPE_ORIGINAL = "  public @Nullable DistributionType getDistributionType() {\n    return myDistributionType;\n  }\n"
# 1106~1108 이 넣은 35(판을 가리지 않고 9 대 하나만 씀). OLD_EDITS 가 원문으로 되돌린 뒤 지금 것을 넣는다.
GRADLE_HOME_1106 = (
     "  public @Nullable Path getGradleHomePath() {\n"
     "    Path moaGradle = moaGradleHome();\n"
     "    return moaGradle != null ? moaGradle : myGradleHome;\n"
     "  }\n"
     "\n"
     "  // Moa IDE: 설치본에 넣은 Gradle(IDE 설치 폴더 기준). 사내망은 Wrapper 배포본 받기가 막히므로 언제나 이것을 쓴다.\n"
     f"  private static final String MOA_GRADLE_HOME = \"{GRADLE_HOME}\";\n"
     "\n"
     "  /** Moa IDE: 설치본의 Gradle 폴더. 없으면(옛 설치) null 이라 프로젝트 설정을 그대로 쓴다. */\n"
     "  private static @Nullable Path moaGradleHome() {\n"
     "    Path home = Path.of(com.intellij.openapi.application.PathManager.getHomePath(), MOA_GRADLE_HOME);\n"
     "    return java.nio.file.Files.isDirectory(home.resolve(\"lib\")) ? home : null;\n"
     "  }\n")
# 9 · 11) 빌드 설정. 번들 제외 목록은 customize.py slim 이 만든다.
PROPS_FILE = "build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt"
SVN_BUNDLE_ORIGINAL = f"      {SLIM_REMOVE[-1]},\n    ))\n"
# 12-1) 글꼴 목록 앞에 넣는 등록 코드와 그 자리(원문). 설치 폴더의 TTF 파일을 먼저 보고, 없으면 boot-servers jar 안을 읽는다.
FONT_ANCHOR = '  private static boolean isUnsupportedFont('
FONT_REGISTER = (
    '  // Moa IDE: Moa Mono 글꼴. 설치 폴더의 EI_FONT_FILES 아래 TTF 를 파일 그대로 등록하고(임시 파일을 만들지 않음),\n'
    '  // 그 폴더에 TTF 가 없으면(옛 설치본) 본체에 든 boot-servers 플러그인 jar 의 EI_FONT_DIR 아래 TTF 를 읽는다. 위치는 IDE 설치 폴더 기준.\n'
    '  private static final String EI_FONT_FILES = "plugins/boot-servers/fonts";\n'
    '  private static final String EI_FONT_JAR = "plugins/boot-servers/lib/boot-servers.jar";\n'
    '  private static final String EI_FONT_DIR = "fonts/";\n'
    '  private static final String EI_FONT_EXT = ".ttf";\n'
    '\n'
    '  /** Moa IDE: Moa Mono 를 등록한다. 파일 · jar 를 읽지 못하면 기록만 남기고 넘어간다. */\n'
    '  private static void registerEiFonts() {\n'
    '    java.nio.file.Path home = java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getHomePath());\n'
    '    if (!registerEiFontFiles(home.resolve(EI_FONT_FILES))) {\n'
    '      registerEiFontJar(home.resolve(EI_FONT_JAR));\n'
    '    }\n'
    '  }\n'
    '\n'
    '  /** dir 아래 TTF 를 파일로 등록한다. TTF 가 하나라도 있었으면 true(등록에 실패한 파일은 기록만 남긴다). */\n'
    '  private static boolean registerEiFontFiles(java.nio.file.Path dir) {\n'
    '    if (!java.nio.file.Files.isDirectory(dir)) return false;\n'
    '    boolean found = false;\n'
    '    try (java.nio.file.DirectoryStream<java.nio.file.Path> files = java.nio.file.Files.newDirectoryStream(dir, "*" + EI_FONT_EXT)) {\n'
    '      for (java.nio.file.Path file : files) {\n'
    '        found = true;\n'
    '        try {\n'
    '          GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(Font.createFont(Font.TRUETYPE_FONT, file.toFile()));\n'
    '        }\n'
    '        catch (Exception e) {\n'
    '          LOG.warn("Moa IDE 글꼴 등록 실패: " + file, e);\n'
    '        }\n'
    '      }\n'
    '    }\n'
    '    catch (java.io.IOException | java.nio.file.DirectoryIteratorException e) {\n'
    '      LOG.warn("Moa IDE 글꼴 폴더를 읽지 못함: " + dir, e);\n'
    '    }\n'
    '    return found;\n'
    '  }\n'
    '\n'
    '  /** jar 의 EI_FONT_DIR 아래 TTF 를 모두 등록한다. jar 가 없거나 읽지 못하면 기록만 남기고 넘어간다. */\n'
    '  private static void registerEiFontJar(java.nio.file.Path jar) {\n'
    '    if (!java.nio.file.Files.isRegularFile(jar)) {\n'
    '      LOG.warn("Moa IDE 글꼴 jar 가 없음: " + jar);\n'
    '      return;\n'
    '    }\n'
    '    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {\n'
    '      java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();\n'
    '      while (entries.hasMoreElements()) {\n'
    '        java.util.zip.ZipEntry entry = entries.nextElement();\n'
    '        String name = entry.getName();\n'
    '        if (!name.startsWith(EI_FONT_DIR) || !name.endsWith(EI_FONT_EXT)) continue;\n'
    '        try (java.io.InputStream in = zip.getInputStream(entry)) {\n'
    '          GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(Font.createFont(Font.TRUETYPE_FONT, in));\n'
    '        }\n'
    '        catch (Exception e) {\n'
    '          LOG.warn("Moa IDE 글꼴 등록 실패: " + name, e);\n'
    '        }\n'
    '      }\n'
    '    }\n'
    '    catch (java.io.IOException e) {\n'
    '      LOG.warn("Moa IDE 글꼴 jar 를 읽지 못함: " + jar, e);\n'
    '    }\n'
    '  }\n'
    '\n')
# 예전 판(1103 까지)이 FONT_ANCHOR 앞에 넣은 jar 만 읽는 등록 코드. 이미 손질된 소스는 먼저 원문으로 되돌린 뒤 FONT_REGISTER 를 넣는다.
FONT_REGISTER_JAR_ONLY = (
    '  // EI IDE: 본체에 든 boot-servers 플러그인 jar 안의 글꼴(EI Mono). 위치는 IDE 설치 폴더 기준.\n'
    '  private static final String EI_FONT_JAR = "plugins/boot-servers/lib/boot-servers.jar";\n'
    '  private static final String EI_FONT_DIR = "fonts/";\n'
    '  private static final String EI_FONT_EXT = ".ttf";\n'
    '\n'
    '  /** EI IDE: EI_FONT_JAR 의 EI_FONT_DIR 아래 TTF 를 모두 등록한다. jar 가 없거나 읽지 못하면 기록만 남기고 넘어간다. */\n'
    '  private static void registerEiFonts() {\n'
    '    java.nio.file.Path jar = java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getHomePath(), EI_FONT_JAR);\n'
    '    if (!java.nio.file.Files.isRegularFile(jar)) {\n'
    '      LOG.warn("EI IDE 글꼴 jar 가 없음: " + jar);\n'
    '      return;\n'
    '    }\n'
    '    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {\n'
    '      java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();\n'
    '      while (entries.hasMoreElements()) {\n'
    '        java.util.zip.ZipEntry entry = entries.nextElement();\n'
    '        String name = entry.getName();\n'
    '        if (!name.startsWith(EI_FONT_DIR) || !name.endsWith(EI_FONT_EXT)) continue;\n'
    '        try (java.io.InputStream in = zip.getInputStream(entry)) {\n'
    '          GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(Font.createFont(Font.TRUETYPE_FONT, in));\n'
    '        }\n'
    '        catch (Exception e) {\n'
    '          LOG.warn("EI IDE 글꼴 등록 실패: " + name, e);\n'
    '        }\n'
    '      }\n'
    '    }\n'
    '    catch (java.io.IOException e) {\n'
    '      LOG.warn("EI IDE 글꼴 jar 를 읽지 못함: " + jar, e);\n'
    '    }\n'
    '  }\n'
    '\n'
    )
# 예전 판이 넣은 수정 → 원문. 값만 바뀐 수정은 이미 손질된 소스에서 원문을 못 찾으므로, 읽은 뒤 먼저 원문으로 되돌린다.
OLD_EDITS = {FONT_SERVICE_FILE: [(FONT_REGISTER_JAR_ONLY + FONT_ANCHOR, FONT_ANCHOR)],
             GRADLE_SETTINGS_FILE: [(GRADLE_HOME_1106, GRADLE_HOME_ORIGINAL)]}
# 수정 전에 지울 옛 줄: 파일 → 줄 정규식. 지금 넣을 줄과 같은 줄은 남긴다.
STALE_LINES = {DEFAULT_LAF_FILE: re.compile(r'^ *<default-laf [^\n]*/>\n', re.M)}

EDITS = [
    ("java/execution/impl/src/com/intellij/execution/ui/CommonJavaFragments.java",
     "configuration -> StringUtil.isNotEmpty(configuration.getVMParameters()));",
     "configuration -> true);"),
    ("platform/platform-impl/resources/idea/PlatformActions.xml",
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
    # 4-1) 이클립스처럼 F2 로 이름 바꾸기. F2 는 원래 설명 보기(QuickJavaDoc)에도 묶여 있어,
    #      편집기 안은 설명 보기, 프로젝트 창 등은 이름 바꾸기가 먼저 잡히게 boot-servers 의 F2RenamePromoter 가 고른다.
    ("plugins/keymaps/eclipse-keymap/resources/keymaps/Eclipse.xml",
     '  <action id="RenameElement">\n    <keyboard-shortcut first-keystroke="shift alt R"/>\n  </action>',
     '  <action id="RenameElement">\n    <keyboard-shortcut first-keystroke="shift alt R"/>\n    <keyboard-shortcut first-keystroke="F2"/>\n  </action>'),
    # 5) 상위 폴더로 처음 열었는데 그 폴더에 빌드 파일이 없으면, 아래 두 단계 폴더들의 build.gradle · pom.xml 을 각각 연결한다.
    (AUTOLINK_FILE, AUTOLINK_ORIGINAL, AUTOLINK_DEPTH2),
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
     "  protected open fun bundleExternalPlugins(context: BuildContext, targetDirectory: Path) {}\n",
     "  protected open fun bundleExternalPlugins(context: BuildContext, targetDirectory: Path) {}\n\n"
     "  // KingGura: 폴더가 없으면 아무것도 넣지 않는다. 각 하위 폴더는 lib/*.jar 를 가져야 한다(빌드가 검사함).\n"
     "  override fun getAdditionalPluginPaths(context: BuildContext): List<Path> {\n"
     "    val dir = communityHomeDir.parent.resolve(\"bundled-plugins\")\n"
     "    if (!Files.isDirectory(dir)) return emptyList()\n"
     "    return Files.list(dir).use { paths -> paths.filter { Files.isDirectory(it) }.sorted().toList() }\n"
     "  }\n"),
    # 11) SVN(Subversion) 플러그인을 번들에 넣는다. 커뮤니티 소스에 있지만 기본 번들 목록에는 없다(plugins/svn4idea, 모듈 intellij.vcs.svn).
    #     원문은 customize.py slim 이 만든 번들 제외 목록의 끝(마지막 항목 SLIM_REMOVE[-1]). slim 목록에 항목을 더해도 따라간다.
    (PROPS_FILE, SVN_BUNDLE_ORIGINAL,
     f'      {SLIM_REMOVE[-1]},\n    )).add("intellij.vcs.svn")  // KingGura: SVN 저장소도 쓴다\n'),
    # 12) 편집기 기본 글꼴을 Moa Mono(JetBrains Mono + Pretendard 한글)로 한다. 글꼴 파일은 boot-servers 플러그인이 켤 때 등록한다(BundledFonts).
    (FONT_FILE, FONT_ORIGINAL,
     '    if (SystemInfo.isJetBrainsJvm) {\n      return "Moa Mono";  // Moa IDE: boot-servers 플러그인이 등록하는 한글 글꼴\n    }\n'),
    # 12-1) Moa Mono 를 IDE 글꼴 목록(FontFamilyServiceImpl)이 만들어지기 전에 등록한다. 목록은 처음 한 번 getAllFonts() 로 만들고 다시 읽지 않는다.
    #       boot-servers 플러그인은 첫 창이 뜰 때 등록하므로, 편집기 설정에 Moa Mono 가 적혀 있으면 그보다 먼저 목록이 만들어져
    #       설정 창 글꼴 상자에 「Font missing: Moa Mono」로 보인다. 글꼴은 설치 폴더 plugins/boot-servers/fonts/*.ttf 를 파일 그대로 등록한다
    #       (jar 에서 풀어 임시 파일로 쓰지 않음, compile_plugins.py 가 그 폴더를 채운다). 그 폴더가 없는 옛 설치본은 boot-servers jar 의 /fonts/*.ttf 를 읽는다.
    (FONT_SERVICE_FILE,
     '    try {\n      Font[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();\n',
     '    registerEiFonts();  // EI IDE: 목록을 읽기 전에 EI Mono 를 등록한다\n'
     '    try {\n      Font[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();\n'),
    (FONT_SERVICE_FILE, FONT_ANCHOR,
     FONT_REGISTER + FONT_ANCHOR),
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
    # 19) SVN 기능은 svn 명령줄 도구를 불러 쓴다. 설치본 plugins/kinggura-sync/svn 에 같이 넣은
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
    # 19-1) PC 에 Git 이 없으면 설치본에 같이 넣은 MinGit(kit/mingit)을 쓴다. PATH · Program Files 의 Git 과 설정에서 고른 경로가 우선이다.
    ("plugins/git4idea/backend/src/config/GitExecutableDetector.java",
     '    return SystemInfo.isWindows ? WIN_EXECUTABLE : UNIX_EXECUTABLE;\n',
     '    if (SystemInfo.isWindows) {\n'
     '      // EI IDE: 설치본에 같이 넣은 MinGit\n'
     '      File bundled = new File(com.intellij.openapi.application.PathManager.getHomePath(), "plugins/kinggura-sync/git/cmd/git.exe");\n'
     '      if (bundled.isFile()) return bundled.getPath();\n'
     '    }\n'
     '    return SystemInfo.isWindows ? WIN_EXECUTABLE : UNIX_EXECUTABLE;\n'),
    # 20) 콘솔은 앞부분을 버려 cycle buffer 크기(원래 1024 KB)만 남긴다. 서버 로그가 금방 잘리므로
    #     Settings > Editor > General > Console 의 「Override console cycle buffer size」를 켠 채 30720 KB(30 MB)로 둔다.
    #     설정값은 기본값과 다를 때만 저장되므로, 이 칸을 건드린 적 없는 기존 설정에도 그대로 적용된다.
    ("platform/editor-ui-api/src/com/intellij/ide/ui/UISettingsState.kt",
     "var overrideConsoleCycleBufferSize: Boolean by property(false)",
     "var overrideConsoleCycleBufferSize: Boolean by property(true)"),
    ("platform/editor-ui-api/src/com/intellij/ide/ui/UISettingsState.kt",
     "var consoleCycleBufferSizeKb: Int by property(1024)",
     "var consoleCycleBufferSizeKb: Int by property(30720)"),
    # 21) 프로젝트 창에서 바뀐 파일 · 새 파일이 아래에 있는 폴더 · 패키지 이름을 그 상태 색으로 칠한다(이클립스의 상위 폴더 변경 표시).
    #     ChangeListManager 의 상태를 쓰므로 Git · SVN 모두 된다. 설정 → Version Control 「Show directories with changed descendants」 의 기본값이다.
    #     VcsConfiguration 은 기본값과 다른 값만 workspace.xml 에 남기므로, 이 칸을 건드린 적 없는 기존 프로젝트에도 적용된다.
    ("platform/vcs-api/shared/src/com/intellij/openapi/vcs/VcsConfiguration.java",
     "public boolean SHOW_DIRTY_RECURSIVELY = false;",
     "public boolean SHOW_DIRTY_RECURSIVELY = true;"),
    # 22) 새 UI 의 기본 어두운 테마는 제품 정보의 default-laf dark 값이다(없으면 Islands Dark). Moa Graphite 는 boot-servers 의
    #     themeProvider 로 들어온다. 그 테마를 못 찾으면 IDE 가 오류를 남기고 Islands Dark 로 연다.
    #     이미 고른 테마가 laf.xml 에 남아 있는 설정 폴더는 그 테마를 따른다.
    #     예전 판이 넣은 다른 default-laf 줄은 main 이 먼저 지운다(STALE_LINES). 두 줄이 남으면 앞의 옛 테마가 쓰인다.
    (DEFAULT_LAF_FILE,
     '  <icon-eap svg="/idea-ce-eap.svg" svg-small="/idea-ce-eap_16.svg"/>\n',
     '  <icon-eap svg="/idea-ce-eap.svg" svg-small="/idea-ce-eap_16.svg"/>\n'
     + DEFAULT_LAF_LINE),
    # 23) 테마 키로 켜는 그리기. 모두 테마 키가 있을 때만 그리므로, 키를 두지 않은 테마(Moa Graphite · Moa White 포함)는 원래 모습 그대로다.
    #  23-1) 창 전체 빛 배경: Island.colorGradient 가 참이면 레지스트리 idea.islands.color.gradient.enabled 를 켠 것과 같이
    #        창 바탕 · 툴바 · 옆줄 · 상태 줄에 ProjectGradients 색을 깐다. 테마를 바꾼 뒤에는 IDE 를 다시 켜야 툴 창 바탕까지 맞는다.
    ("platform/platform-impl/src/com/intellij/openapi/application/impl/islands/IslandsGradientPainter.kt",
     'internal fun isColorIslandGradient(): Boolean = Registry.`is`("idea.islands.color.gradient.enabled", false)',
     'internal fun isColorIslandGradient(): Boolean =\n'
     '  Registry.`is`("idea.islands.color.gradient.enabled", false) || javax.swing.UIManager.getBoolean("Island.colorGradient")'),
    #  23-2) Island.colorGradient.mirrored 가 참이면 대각선 색과 둥근 빛을 좌우로 뒤집는다(Color1 · 둥근 빛이 오른쪽 위, Color4 가 왼쪽 아래).
    ("platform/platform-impl/src/com/intellij/openapi/application/impl/islands/IslandsGradientPainter.kt",
     "  g.paint = LinearGradientPaint(0f, 0f, width.toFloat(), height.toFloat(),",
     '  val mirrored = javax.swing.UIManager.getBoolean("Island.colorGradient.mirrored")\n'
     "  g.paint = LinearGradientPaint(if (mirrored) width.toFloat() else 0f, 0f, if (mirrored) 0f else width.toFloat(), height.toFloat(),"),
    ("platform/platform-impl/src/com/intellij/openapi/application/impl/islands/IslandsGradientPainter.kt",
     "  val ovalCenterX = width * 0.2f",
     "  val ovalCenterX = width * (if (mirrored) 0.8f else 0.2f)"),
    #  23-3) 트리 선택 줄 테두리: Tree.Selection.borderColor. 하나만 선택된 줄(위아래로 이어진 선택이 아닌 줄)에만 그린다. 마우스만 올린 줄은 그리지 않는다.
    ("platform/ui.tree/src/com/intellij/ui/tree/ui/DefaultTreeUI.java",
     "                int arc = JBUI.CurrentTheme.Tree.ARC.get();\n"
     "                FILL.paint((Graphics2D)g, left, bounds.y, right - left, bounds.height, arc);\n",
     "                int arc = JBUI.CurrentTheme.Tree.ARC.get();\n"
     "                FILL.paint((Graphics2D)g, left, bounds.y, right - left, bounds.height, arc);\n"
     '                Color selectionBorder = selected && !shouldPaintTop && !shouldPaintBottom ? UIManager.getColor("Tree.Selection.borderColor") : null;\n'
     "                if (selectionBorder != null) {\n"
     "                  g.setColor(selectionBorder);\n"
     "                  DRAW.paint((Graphics2D)g, left, bounds.y, right - left, bounds.height, arc);\n"
     "                  g.setColor(background);\n"
     "                }\n"),
    #  23-4) 팝업 목록 · Search Everywhere · 자동 완성의 선택 줄 테두리: List.Selection.borderColor. 네 모서리가 모두 둥근 선택에만 그린다.
    ("platform/platform-api/src/com/intellij/ui/popup/list/SelectablePanel.kt",
     "      if (selectionArc == 0 || selectionArcCorners == SelectionArcCorners.ALL) {\n"
     "        g2.fillRoundRect(rectX, rectY, rectWidth, rectHeight, selectionArc, selectionArc)\n",
     "      if (selectionArc == 0 || selectionArcCorners == SelectionArcCorners.ALL) {\n"
     "        g2.fillRoundRect(rectX, rectY, rectWidth, rectHeight, selectionArc, selectionArc)\n"
     '        javax.swing.UIManager.getColor("List.Selection.borderColor")?.let {\n'
     "          g2.color = it\n"
     "          g2.drawRoundRect(rectX, rectY, rectWidth - 1, rectHeight - 1, selectionArc, selectionArc)\n"
     "        }\n"),
    #  23-5) 툴바의 프로젝트 · 브랜치 칸을 늘 알약으로 그린다: MainToolbar.Dropdown.pillBackground(바탕) · pillBorderColor(1px 테두리).
    #        모양은 MainToolbar.Dropdown.arc 를 따른다. 프로젝트 색 강조 · 마우스 올림은 그 위에 그대로 그린다.
    ("platform/platform-impl/src/com/intellij/ide/ui/laf/darcula/ui/ToolbarComboButtonUI.kt",
     "      combo.highlightBackground?.let(innerRectPainter)",
     '      javax.swing.UIManager.getColor("MainToolbar.Dropdown.pillBackground")?.let(innerRectPainter)\n'
     '      javax.swing.UIManager.getColor("MainToolbar.Dropdown.pillBorderColor")?.let {\n'
     "        DarculaNewUIUtil.drawRoundedRectangle(g2, innerRect, it, arc.toFloat(), JBUI.scale(1).toFloat())\n"
     "      }\n"
     "      combo.highlightBackground?.let(innerRectPainter)"),
    #  23-6) 실행 설정 칸도 같은 키로 알약을 그린다.
    ("platform/execution-impl/src/com/intellij/execution/ui/RedesignedRunWidget.kt",
     "  override fun getMargins(): Insets = JBInsets(0, 10, 0, 6)",
     "  override fun getMargins(): Insets = JBInsets(0, 10, 0, 6)\n"
     "\n"
     "  override fun paintComponent(g: Graphics) {\n"
     "    val rect = Rectangle(size).also { JBInsets.removeFrom(it, insets) }\n"
     "    val arc = JBUI.CurrentTheme.MainToolbar.Dropdown.hoverArc().float\n"
     '    javax.swing.UIManager.getColor("MainToolbar.Dropdown.pillBackground")?.let { com.intellij.ui.DrawUtil.fillRoundedRectangle(g, rect, it, arc) }\n'
     '    javax.swing.UIManager.getColor("MainToolbar.Dropdown.pillBorderColor")?.let { com.intellij.ide.ui.laf.darcula.DarculaNewUIUtil.drawRoundedRectangle(g, rect, it, arc, JBUIScale.scale(1f)) }\n'
     "    super.paintComponent(g)\n"
     "  }"),
    #  23-7) 열려 있지만 초점이 없는 툴 창의 옆줄 단추 바탕: ToolWindow.Button.visibleBackground(없으면 원래 눌림 색).
    ("platform/platform-impl/src/com/intellij/openapi/wm/impl/SquareStripeButtonLook.kt",
     '      if (button.isFocused()) return UIManager.getColor("ToolWindow.Button.selectedBackground")?: color',
     '      if (button.isFocused()) return UIManager.getColor("ToolWindow.Button.selectedBackground")?: color\n'
     '      if (button.toolWindow.isVisible) return UIManager.getColor("ToolWindow.Button.visibleBackground") ?: color'),
    # 24) 프로젝트 머리글자 아이콘(툴바 프로젝트 칸 · 최근 프로젝트 목록)은 JetBrains Mono 로 그림 파일에 직접 그려서 대체 글꼴이 없다.
    #     머리글자에 그 글꼴에 없는 글자(한글 등)가 있으면 네모로 나오므로, 그때만 자바 논리 글꼴 Dialog(운영체제 대체 글꼴을 씀)로 그린다.
    ("platform/util/ui/src/com/intellij/util/ui/AvatarUtils.kt",
     "    g2.font = getFont(size)\n",
     "    val font = getFont(size)\n"
     "    g2.font = if (font.canDisplayUpTo(presentation.shortName) == -1) font else Font(Font.DIALOG, Font.BOLD, font.size)\n"),
    # 26) 프로젝트 창은 모듈 이름을 굵게 쓴다. 폴더 이름과 모듈 이름이 같으면 이름 전체가 굵고, 다르면 「폴더 [모듈]」 중 [모듈] 만 굵어
    #     줄마다 굵기가 달라 보인다(PsiDirectoryNode.updateImpl). 레지스트리 ide.hide.real.module.name 이 켜지면 모든 모듈 줄을
    #     폴더 이름만 굵게 그린다. 레지스트리는 기본값과 다른 값만 저장하므로 이 값을 건드린 적 없는 기존 설정에도 적용된다.
    ("platform/util/resources/misc/registry.properties",
     "ide.hide.real.module.name=false\n",
     "ide.hide.real.module.name=true\n"),
    # 27) TextMate 는 내장 번들을 병렬로 등록해 같은 확장자를 둘이 가지면 나중에 끝난 쪽이 이긴다. html 번들과 jsp 번들이 둘 다 .jsp 를
    #     가져서 실행마다 html 문법이 걸리기도 하는데, html 문법은 <%@ · <% 를 잘못된 꺾쇠(빨강)로 보고 지시문 · 스크립틀릿 · ${} 안을
    #     칠하지 못한다. html 번들에서 .jsp 를 빼 jsp 번들만 맡게 한다. 커뮤니티판은 .jsp 에 다른 파일 종류가 없다(boot-servers PlaceholderFileTypes 가 자리표시 종류를 푼다).
    ("plugins/textmate/lib/bundles/html/package.json",
     '".mdoc",".jsp",".asp"',
     '".mdoc",".asp"'),
    # 28) NSIS lzma 압축은 한 줄기로 돌아 빌드 1062 에서 9분 52초(전체의 60%)가 걸렸다. zlib 는 몇 배 빠르고 설치 파일이 커진다.
    ("build/conf/nsis/idea.nsi",
     "SetCompressor lzma\n",
     "SetCompressor zlib\n"),
    # 29) 단추 글자의 기준선은 원래 글자 상자(ascent + descent)를 단추 가운데에 두는 값이다. 맑은 고딕처럼 ascent 가 큰 글꼴은
    #     실제 글자가 상자 아래쪽에 앉아 단추 글자가 1~2px 아래로 처진다. 대문자 · 한글이 차지하는 높이(「H가」의 잉크 높이)를
    #     가운데에 맞춘다. 내림 글자(g · y)는 기준선 아래로 내려가는 것이 정상이라 높이에 넣지 않는다. 우리 단추와 IntelliJ 대화 상자 단추 모두에 적용된다.
    (BUTTON_UI_FILE,
     "        textRect.x + getTextShiftOffset(),\n"
     "        textRect.y + metrics.getAscent() + getTextShiftOffset());\n",
     "        textRect.x + getTextShiftOffset(),\n"
     "        centeredBaseline(g, textRect, metrics) + getTextShiftOffset());\n"),
    (BUTTON_UI_FILE,
     "      textRect.x + getTextShiftOffset(),\n"
     "      textRect.y + metrics.getAscent() + getTextShiftOffset());\n",
     "      textRect.x + getTextShiftOffset(),\n"
     "      centeredBaseline(g, textRect, metrics) + getTextShiftOffset());\n"),
    (BUTTON_UI_FILE,
     "  protected int getMnemonicIndex(AbstractButton b) {\n",
     "  // EI IDE: 대문자 · 한글 잉크 높이의 가운데가 글자 칸 가운데에 오는 기준선. 잉크를 못 재면 원래 값(위 + ascent).\n"
     "  private static final String CENTER_SAMPLE = \"H\\uAC00\";\n"
     "\n"
     "  private static int centeredBaseline(Graphics g, Rectangle textRect, FontMetrics metrics) {\n"
     "    Font font = metrics.getFont();\n"
     "    String sample = font.canDisplayUpTo(CENTER_SAMPLE) == -1 ? CENTER_SAMPLE : CENTER_SAMPLE.substring(0, 1);\n"
     "    java.awt.geom.Rectangle2D ink = font.createGlyphVector(((Graphics2D)g).getFontRenderContext(), sample).getVisualBounds();\n"
     "    if (ink.isEmpty()) return textRect.y + metrics.getAscent();\n"
     "    return (int)Math.round(textRect.getCenterY() - ink.getCenterY());\n"
     "  }\n"
     "\n"
     "  protected int getMnemonicIndex(AbstractButton b) {\n"),
    # 30) 설정 폴더 이름은 제품 이름에서 온다(customize.py brand). EI IDE → Moa IDE 로 바꾸면 새 설정 폴더(MoaIDE2026.2)가 비어
    #     첫 실행이 새 설치처럼 된다. 처음 켤 때 가져올 후보에 옛 이름(EIIDE2026.2)을 더해 설정 · 플러그인을 저절로 가져오게 한다.
    #     새 이름의 설정 폴더가 이미 있으면 가져오기는 돌지 않는다(ConfigImportHelper 는 새 설정 폴더가 없을 때만 가져온다).
    ("platform/platform-impl/initial-config-import/src/com/intellij/openapi/application/ConfigImportHelper.java",
     "    var otherEditionPrefixes = settings != null ? settings.getEditionsToImportFrom() : List.<String>of();\n",
     "    var otherEditionPrefixes = ContainerUtil.append(settings != null ? settings.getEditionsToImportFrom() : List.<String>of(),\n"
     "                                                    \"EIIDE\");  // Moa IDE: 이름을 바꾸기 전 설정 폴더도 가져온다\n"),
    # 31) IntelliJ 가 20분마다 모든 Git 저장소의 원격에 ls-remote 로 들어온 커밋을 묻는 확인을 기본으로 끈다.
    #     회사 PC 는 git 한 번에 0.4~0.9 초라 저장소 8개면 30 초가량 git 이 몰린다. 원격 확인은 동기화 창이 fetch 로 한다.
    #     설정 → 버전 관리 → Git 「원격에 받을 변경이 있는지 확인」 기본값이다. 프로젝트에서 직접 고른 값이 있으면 그 값을 따른다.
    ("plugins/git4idea/shared/src/git4idea/config/GitVcsOptions.kt",
     "  var incomingCommitsCheckStrategy: GitIncomingRemoteCheckStrategy by enum<GitIncomingRemoteCheckStrategy>(GitIncomingRemoteCheckStrategy.LS_REMOTE)\n",
     "  var incomingCommitsCheckStrategy: GitIncomingRemoteCheckStrategy by enum<GitIncomingRemoteCheckStrategy>(GitIncomingRemoteCheckStrategy.NONE)  // Moa IDE: 원격 확인은 동기화 창이 한다\n"),
    # 32) JBR 21 부터 jar 의 zip64 덧붙임 칸 크기를 엄격히 검사해, 예전 도구로 묶은 jar(예: aspectjweaver 1.8.10)를 IDE 가 열지 못하고
    #     그 jar 의 클래스가 편집기에서 빨갛게 보인다. 설치본 vmoptions 에 검사를 끄는 JDK 속성을 넣는다(JDK-8302483 이 둔 끄는 길).
    ("platform/build-scripts/src/org/jetbrains/intellij/build/impl/VmOptionsGenerator.kt",
     '  "-Djava.util.zip.use.nio.for.zip.file.access=true", // IJPL-149160\n',
     '  "-Djava.util.zip.use.nio.for.zip.file.access=true", // IJPL-149160\n'
     '  "-Djdk.util.zip.disableZip64ExtraFieldValidation=true", // Moa IDE: 예전 도구로 묶은 jar 도 연다\n'),
    # 33) 옛 설정 폴더(EIIDE 등)를 가져올 때 플러그인 폴더에서 옮기지 않을 것: 본체에 든 우리 플러그인 셋의 옛 시험본(본체 판을 가린다)과
    #     JetBrains AI(com.intellij.ml.llm, 라이선스 없이 켜 두면 30초마다 JetBrains 서버에 묻고 실패한다).
    ("platform/platform-impl/initial-config-import/src/com/intellij/openapi/application/ConfigImportHelper.java",
     "    pluginsToMigrate.removeIf(filter);\n    if (!pluginsToMigrate.isEmpty()) {\n",
     "    // Moa IDE: 우리 플러그인 옛 시험본과 JetBrains AI 는 옮기지 않는다.\n"
     "    var moaNotMigrated = java.util.Set.of(\"local.bootservers\", \"ei.database\", \"kinggura.sync\", \"com.intellij.ml.llm\");\n"
     "    pluginsToMigrate.removeIf(descriptor -> moaNotMigrated.contains(descriptor.getPluginId().getIdString()));\n"
     "    pluginsToDownload.removeIf(descriptor -> moaNotMigrated.contains(descriptor.getPluginId().getIdString()));\n"
     "    pluginsToMigrate.removeIf(filter);\n    if (!pluginsToMigrate.isEmpty()) {\n"),
    # 34) 저장소에 .mvn/wrapper 가 있으면 IntelliJ 는 처음 연결할 때 프로젝트 전체 Maven 을 Wrapper 로 바꾸고 배포본을 인터넷에서 받는다.
    #     사내망은 보안 프로그램이 막아 오류 알림 뒤 내장으로 돌아가고, 워크스페이스의 다른 저장소까지 그 판 Maven 으로 바뀐다.
    #     Maven 설정의 모든 쓰기(연결 · 첫 가져오기 · 저장된 설정 읽기 · 설정 창 · 빠른 수정)가 이 한 곳을 지나므로 여기서 내장으로 바꾼다.
    #     저장소(사내 넥서스 미러 등)는 settings.xml 을 그대로 따른다.
    ("plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenGeneralSettings.java",
     "  private void setMavenHome(final @NotNull MavenHomeType mavenHome, boolean fireChanged) {\n"
     "    MavenHomeType mavenHomeToSet = mavenHome;\n",
     "  private void setMavenHome(final @NotNull MavenHomeType mavenHome, boolean fireChanged) {\n"
     "    // Moa IDE: 사내망은 Wrapper 배포본 받기가 막히므로 언제나 내장 Maven 3 을 쓴다.\n"
     "    MavenHomeType mavenHomeToSet = mavenHome instanceof MavenWrapper ? BundledMaven3.INSTANCE : mavenHome;\n"),
    #  34-1) 처음 연결할 때는 Maven 설정과 상관없이 신뢰한 프로젝트면 Wrapper 배포본을 받는다. Wrapper 를 쓸 때만 받게 한다.
    ("plugins/maven/src/main/java/org/jetbrains/idea/maven/wizards/MavenProjectAsyncBuilder.kt",
     "    if (TrustedProjects.isProjectTrusted(project)) {\n"
     "      withBackgroundProgress(project, MavenProjectBundle.message(\"maven.installing.wrapper\"), false) {\n",
     "    if (TrustedProjects.isProjectTrusted(project) && MavenUtil.isWrapper(generalSettings)) {  // Moa IDE: 34-1\n"
     "      withBackgroundProgress(project, MavenProjectBundle.message(\"maven.installing.wrapper\"), false) {\n"),
    # 35) Gradle 도 사내망에서 Wrapper 배포본 받기가 막힌다. 프로젝트마다 고른 배포 방식 · 경로를 읽는 곳(동기화 · 빌드 · 실행 · 설정 창)이
    #     모두 이 두 getter 를 지나므로, 설치본의 Gradle 이 있으면 「지정 위치」와 그 경로를 돌려준다. 없으면(옛 설치) 원래 값을 쓴다.
    (GRADLE_SETTINGS_FILE, GRADLE_HOME_ORIGINAL,
     "  public @Nullable Path getGradleHomePath() {\n"
     "    Path moaGradle = moaGradleHome();\n"
     "    return moaGradle != null ? moaGradle : myGradleHome;\n"
     "  }\n"
     "\n"
     "  // Moa IDE: 설치본에 넣은 Gradle(IDE 설치 폴더 기준). 사내망은 Wrapper 배포본 받기가 막히므로 언제나 이것을 쓴다.\n"
     f"  private static final String MOA_GRADLE_HOME = \"{GRADLE_HOME}\";\n"
     "  // Moa IDE: wrapper 가 MOA_GRADLE_MAJOR 보다 낮은 판을 적은 프로젝트가 쓰는 Gradle. Gradle 9 가 없앤 API 를 부르는 옛 플러그인이 그런 프로젝트에 남아 있다.\n"
     f"  private static final String MOA_GRADLE_HOME_8 = \"{GRADLE_HOME_8}\";\n"
     f"  private static final int MOA_GRADLE_MAJOR = {GRADLE_MAJOR};\n"
     "  // gradle-wrapper.properties 의 distributionUrl 줄에서 Gradle 판의 맨 앞 숫자(…/gradle-8.14.3-bin.zip 의 8).\n"
     "  private static final java.util.regex.Pattern MOA_WRAPPER_MAJOR =\n"
     "    java.util.regex.Pattern.compile(\"^\\\\s*distributionUrl\\\\s*[=:].*gradle-(\\\\d{1,3})\\\\.\", java.util.regex.Pattern.MULTILINE);\n"
     "\n"
     "  /**\n"
     "   * Moa IDE: 설치본의 Gradle 폴더. 프로젝트의 wrapper 가 MOA_GRADLE_MAJOR 보다 낮은 판을 적었으면 8 대, 그 밖에는(wrapper 없음 포함) 9 대.\n"
     "   * 8 대 폴더가 없으면 9 대를 쓰고, 그것도 없으면(옛 설치) null 이라 프로젝트 설정을 그대로 쓴다.\n"
     "   */\n"
     "  private @Nullable Path moaGradleHome() {\n"
     "    Path ide = Path.of(com.intellij.openapi.application.PathManager.getHomePath());\n"
     "    int major = moaWrapperMajor();\n"
     "    Path older = ide.resolve(MOA_GRADLE_HOME_8);\n"
     "    if (major > 0 && major < MOA_GRADLE_MAJOR && java.nio.file.Files.isDirectory(older.resolve(\"lib\"))) return older;\n"
     "    Path home = ide.resolve(MOA_GRADLE_HOME);\n"
     "    return java.nio.file.Files.isDirectory(home.resolve(\"lib\")) ? home : null;\n"
     "  }\n"
     "\n"
     "  /** Moa IDE: 프로젝트의 gradle/wrapper/gradle-wrapper.properties 가 적은 Gradle 판의 맨 앞 숫자. 파일이 없거나 읽지 못하거나 판을 못 찾으면 0. */\n"
     "  private int moaWrapperMajor() {\n"
     "    String project = getExternalProjectPath();\n"
     "    if (project == null) return 0;\n"
     "    try {\n"
     "      Path wrapper = Path.of(project, \"gradle\", \"wrapper\", \"gradle-wrapper.properties\");\n"
     "      if (!java.nio.file.Files.isRegularFile(wrapper)) return 0;\n"
     "      java.util.regex.Matcher found = MOA_WRAPPER_MAJOR.matcher(java.nio.file.Files.readString(wrapper));\n"
     "      return found.find() ? Integer.parseInt(found.group(1)) : 0;\n"
     "    }\n"
     "    catch (java.io.IOException | RuntimeException e) {\n"
     "      return 0;\n"
     "    }\n"
     "  }\n"),
    (GRADLE_SETTINGS_FILE, GRADLE_TYPE_ORIGINAL,
     "  public @Nullable DistributionType getDistributionType() {\n"
     "    return moaGradleHome() != null ? DistributionType.LOCAL : myDistributionType;  // Moa IDE: 35\n"
     "  }\n"),
    # 36) 2026.3 부터 프로젝트를 열지 않고 켜면 시작 화면 대신 「시작 프로젝트」가 든 IDE 창이 뜬다(도구 창은 프로젝트 · 터미널 · 알림만).
    #     Moa 는 폴더를 여는 것이 곧 워크스페이스이고 우리 창(서버 · 동기화 · 데이터베이스)은 연 폴더를 기준으로 돌므로, 예전 시작 화면을 기본으로 둔다.
    #     레지스트리 idea.welcome.screen.non.modal.enabled 를 켜면 새 화면을 쓴다.
    ("java/ide-customization/backend/resources/intellij.java.ide.customization.backend.xml",
     '    <registryKey key="idea.welcome.screen.non.modal.enabled" defaultValue="true" restartRequired="true"\n',
     '    <registryKey key="idea.welcome.screen.non.modal.enabled" defaultValue="false" restartRequired="true"\n'),
]


def prepare(rel, text):
    """옛 손질을 원문으로 되돌리고(OLD_EDITS) 옛 줄을 지운다(STALE_LINES)."""
    for prev, original in OLD_EDITS.get(rel, []):
        if prev in text:
            print(f"옛 수정 되돌림: {rel}")
            text = text.replace(prev, original)
    stale = STALE_LINES.get(rel)
    if stale:
        kept = stale.sub(lambda m: m.group(0) if m.group(0) == DEFAULT_LAF_LINE else "", text)
        if kept != text:
            print(f"옛 줄 지움: {rel}")
            text = kept
    return text


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    # 모든 수정을 메모리에서 먼저 검사한다. 하나라도 못 찾으면 아무 파일도 쓰지 않는다. 원본은 backup/<시각>-before/ 에 남긴다.
    apply_edits(Path(sys.argv[1]), EDITS, prepare=prepare)


if __name__ == "__main__":
    main()
