#!/usr/bin/env python3
"""KingGura IDE 이클립스식 주 메뉴를 intellij-community 소스에 넣는다.

사용법: python3 apply_menu.py <intellij-community 폴더>

java/ide-customization 모듈(번들 플러그인 com.intellij.java.ide)에
  1) 주 메뉴 순서를 바꾸는 KingGuraMainMenuCustomizer.java 를 복사하고
  2) plugin.xml 에 Source · Search · Project 메뉴와 Window › Show View 를 등록한다.
두 번 실행해도 같은 결과가 된다.
"""
import shutil
import sys
from pathlib import Path

MODULE = "java/ide-customization"
JAVA_DIR = "src/com/intellij/ide/customization/java"
PLUGIN_XML = "resources/META-INF/plugin.xml"
MARK = "<!-- KingGura: eclipse menus -->"

EXTENSION = """    <actionConfigurationCustomizer implementation="com.intellij.ide.customization.java.KingGuraMainMenuCustomizer"/>
"""

# 참조하는 동작 ID 는 2026.2.3 소스(LangActions.xml · PlatformActions.xml · JavaActions.xml)에서 확인한 것만 쓴다.
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


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    module = Path(sys.argv[1]) / MODULE
    xml_path = module / PLUGIN_XML
    if not xml_path.is_file():
        raise SystemExit(f"plugin.xml 이 없습니다: {xml_path}")

    java_src = Path(__file__).with_name("KingGuraMainMenuCustomizer.java")
    shutil.copyfile(java_src, module / JAVA_DIR / java_src.name)

    xml = xml_path.read_text(encoding="utf-8")
    if MARK in xml:
        print("plugin.xml: 이미 적용됨")
        return
    # 기존 extensions 블록 끝에 등록하고, 메뉴 정의는 파일 끝에 붙인다.
    if xml.count("</extensions>") != 1 or xml.count("</idea-plugin>") != 1:
        raise SystemExit("plugin.xml 구조가 예상과 다릅니다(extensions · idea-plugin 닫는 태그가 하나씩이어야 함).")
    xml = xml.replace("</extensions>", EXTENSION + "  </extensions>")
    xml = xml.replace("</idea-plugin>", ACTIONS + "</idea-plugin>")
    xml_path.write_text(xml, encoding="utf-8")
    print("적용 완료: KingGuraMainMenuCustomizer.java, plugin.xml")


if __name__ == "__main__":
    main()
