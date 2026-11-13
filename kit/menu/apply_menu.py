#!/usr/bin/env python3
"""KingGura IDE 이클립스식 주 메뉴를 intellij-community 소스에 넣는다.

사용법: python3 apply_menu.py <intellij-community 폴더>

java/ide-customization 모듈(번들 플러그인 com.intellij.java.ide)에
  1) 주 메뉴 순서를 바꾸는 KingGuraMainMenuCustomizer.java 를 복사하고
  2) plugin.xml 에 Source · Search · Project 메뉴와 Window › Show View 를 등록한다.
plugin.xml 구조를 먼저 확인하고, 맞지 않으면 아무 파일도 바꾸지 않는다. 줄끝(LF · CRLF)은 원래대로 둔다.
Java 파일은 이미 적용된 소스에도 늘 다시 복사해 kit 의 최신 내용을 따른다. 두 번 실행해도 같은 결과가 된다.
"""
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from patchlib import read_source, write_source  # noqa: E402

MODULE = "java/ide-customization"
JAVA_DIR = "src/com/intellij/ide/customization/java"
PLUGIN_XML = "resources/META-INF/plugin.xml"
MARK = "<!-- KingGura: eclipse menus -->"

# 메뉴 대본이 쓰는 AnAction(editor.ui) · ProjectUtil · SeparatorAction(ide.core) · DefaultActionGroup(ide)은
# 2026.3 의 Bazel 빌드에서 모듈이 직접 의존해야 보인다(건너 의존은 컴파일 경로에 들지 않는다).
# (파일, 이 글 바로 뒤에 넣음, 넣을 글). 앞 글은 파일에 정확히 한 번 있어야 한다.
DEPS = [
    ("BUILD.bazel", '        "//fleet/rpc",\n',
     '        "//platform/editor-ui-api:editor-ui",\n        "//platform/ide-core",\n        "//platform/platform-api:ide",\n'),
    ("intellij.java.ide.customization.iml", '    <orderEntry type="module" module-name="fleet.rpc" />\n',
     '    <orderEntry type="module" module-name="intellij.platform.editor.ui" />\n'
     '    <orderEntry type="module" module-name="intellij.platform.ide.core" />\n'
     '    <orderEntry type="module" module-name="intellij.platform.ide" />\n'),
]

EXTENSION = """    <actionConfigurationCustomizer implementation="com.intellij.ide.customization.java.KingGuraMainMenuCustomizer"/>
"""

# 참조하는 동작 ID 는 2026.3 소스(LangActions.xml · PlatformActions.xml · JavaActions.xml)에서 확인한 것만 쓴다.
ACTIONS = MARK + """
  <actions>
    <group id="KG.SourceMenu" text="_Source" popup="true">
      <reference ref="CommentByLineComment"/>
      <reference ref="CommentByBlockComment"/>
      <separator/>
      <reference ref="ReformatCode"/>
      <reference ref="OptimizeImports"/>
      <separator/>
      <reference ref="OverrideMethods"/>
      <reference ref="ImplementMethods"/>
      <reference ref="GenerateGetterAndSetter"/>
      <reference ref="GenerateConstructor"/>
      <reference ref="Generate"/>
      <separator/>
      <reference ref="SurroundWith"/>
    </group>

    <group id="KG.SearchMenu" text="Se_arch" popup="true">
      <reference ref="FindInPath"/>
      <reference ref="ReplaceInPath"/>
      <separator/>
      <reference ref="FindUsages"/>
      <reference ref="GotoImplementation"/>
      <reference ref="HighlightUsagesInFile"/>
    </group>

    <group id="KG.ProjectMenu" text="_Project" popup="true">
      <reference ref="CloseProject"/>
      <separator/>
      <reference ref="CompileDirty"/>
      <reference ref="MakeModule"/>
      <reference ref="CompileProject"/>
      <separator/>
      <reference ref="ShowProjectStructureSettings"/>
    </group>

    <group id="KG.ShowView" text="Show _View" popup="true">
      <reference ref="ToolWindowsGroup"/>
      <add-to-group group-id="WindowMenu" anchor="first"/>
    </group>

    <group id="KG.Appearance" text="_Appearance" popup="true">
      <reference ref="ViewAppearanceGroup"/>
      <add-to-group group-id="WindowMenu" anchor="after" relative-to-action="KG.ShowView"/>
    </group>
  </actions>
"""


def deps_applied(module):
    for name, _anchor, added in DEPS:
        path = module / name
        if not path.is_file() or added not in read_source(path)[0]:
            return False
    return True


def apply_deps(module):
    """모듈 의존을 넣는다. 하나라도 자리를 못 찾으면 아무 파일도 쓰지 않는다."""
    writes = []
    for name, anchor, added in DEPS:
        path = module / name
        if not path.is_file():
            raise SystemExit(f"아무 파일도 바꾸지 않았습니다. 파일이 없습니다: {path}")
        text, newline = read_source(path)
        if added in text:
            continue
        if text.count(anchor) != 1:
            raise SystemExit(f"아무 파일도 바꾸지 않았습니다. {name} 에서 의존을 넣을 자리를 정확히 한 번 찾지 못했습니다.")
        writes.append((path, text.replace(anchor, anchor + added), newline))
    for path, text, newline in writes:
        write_source(path, text, newline)
    if writes:
        print("모듈 의존 넣음: " + ", ".join(path.name for path, _text, _newline in writes))


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    module = Path(sys.argv[1]) / MODULE
    xml_path = module / PLUGIN_XML
    if not xml_path.is_file():
        raise SystemExit(f"plugin.xml 이 없습니다: {xml_path}")

    xml, newline = read_source(xml_path)
    applied = MARK in xml
    # 기존 extensions 블록 끝에 등록하고, 메뉴 정의는 파일 끝에 붙인다.
    if not applied and (xml.count("</extensions>") != 1 or xml.count("</idea-plugin>") != 1):
        raise SystemExit("아무 파일도 바꾸지 않았습니다. plugin.xml 구조가 예상과 다릅니다(extensions · idea-plugin 닫는 태그가 하나씩이어야 함).")
    apply_deps(module)
    java_src = Path(__file__).with_name("KingGuraMainMenuCustomizer.java")
    shutil.copyfile(java_src, module / JAVA_DIR / java_src.name)
    if applied:
        print("plugin.xml: 이미 적용됨, KingGuraMainMenuCustomizer.java 복사")
        return
    xml = xml.replace("</extensions>", EXTENSION + "  </extensions>")
    xml = xml.replace("</idea-plugin>", ACTIONS + "</idea-plugin>")
    write_source(xml_path, xml, newline)
    print("적용 완료: KingGuraMainMenuCustomizer.java, plugin.xml")


if __name__ == "__main__":
    main()
