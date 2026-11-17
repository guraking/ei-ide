#!/usr/bin/env python3
"""make_plugin_zips.stock_xml 점검."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_plugin_zips as m  # noqa: E402

MOA = """<idea-plugin>
  <id>local.bootservers</id>
  <version>2026.1118.0820</version>
  <actions>
    <action id="A"><add-to-group group-id="KG.SearchMenu" anchor="last"/></action>
    <action id="B"><add-to-group group-id="KG.SourceMenu" anchor="last"/></action>
    <action id="C"><add-to-group group-id="KG.ProjectMenu" anchor="last"/></action>
    <action id="D"><add-to-group group-id="ToolsMenu" anchor="last"/></action>
  </actions>
</idea-plugin>"""

xml, version = m.stock_xml(MOA)
assert version == "2026.1118.0820", version
assert '<id>local.bootservers</id>\n  <idea-version since-build="263"/>' in xml, xml
assert "KG." not in xml, xml
assert 'group-id="FindMenuGroup"' in xml and 'group-id="CodeMenu"' in xml and xml.count('group-id="ToolsMenu"') == 2, xml

for bad in (xml, MOA.replace("<version>2026.1118.0820</version>", "")):
    try:
        m.stock_xml(bad)
        raise AssertionError("멈춰야 함: " + bad[:80])
    except SystemExit:
        pass
print("make_plugin_zips_check 통과")
