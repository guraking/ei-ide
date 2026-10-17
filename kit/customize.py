#!/usr/bin/env python3
"""intellij-community 소스를 나만의 개발 도구로 바꾼다.

사용법: python3 customize.py <소스 폴더> <제품 이름> brand|slim

brand: 이름 · 설치 폴더 이름 · 설정 폴더 이름만 바꾼다. 첫 빌드는 이것만으로 한다.
slim : 번들 플러그인에서 안 쓰는 것을 뺀다. brand 빌드가 성공한 뒤에 따로 적용한다.

대상 태그: idea/262.10968.63 (2026.2.3). 다른 태그에서 고칠 줄을 못 찾으면
어느 파일의 어느 문구인지 알리고 아무 파일도 바꾸지 않은 채 멈춘다.
같은 단계를 두 번 실행해도 이미 바뀐 파일은 건너뛴다.
"""
import re
import sys
from pathlib import Path

APP_INFO = "community-resources/resources/idea/IdeaApplicationInfo.xml"
PROPS = "build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt"

# slim 에서 번들에서 빼는 플러그인 모듈. Java · Maven · Gradle · Groovy(그래들 스크립트용) · Git ·
# GitHub · GitLab 연동 · Markdown · YAML · Properties · 이클립스 키맵 · 이클립스 프로젝트 가져오기는 남긴다.
SLIM_REMOVE = [
    "KotlinPluginBuilder.MAIN_KOTLIN_PLUGIN_MODULE",
    '"intellij.compose.ide.plugin"',
    '"intellij.android.gradle.declarative.lang.ide"',
    '"intellij.featuresTrainer"',
    '"intellij.performanceTesting"',
    '"intellij.keymap.visualStudio"',
    '"intellij.keymap.netbeans"',
    '"intellij.mcpserver"',
    '"intellij.grazie"',
    '"intellij.toml"',
    '"intellij.webp"',
    '"intellij.mermaid"',
    '"intellij.testng"',
    '"intellij.java.i18n"',
    # AI 에이전트 연결(Agent Client Protocol)과 플러그인 개발용 실행 도구는 쓰지 않는다.
    '"intellij.platform.acp.plugin"',
    '"intellij.dev"',
]


def brand_edits(name: str):
    slug = re.sub(r"[^A-Za-z0-9]", "", name)
    if not slug:
        raise SystemExit("제품 이름에는 영문자나 숫자가 하나 이상 있어야 합니다(설정 폴더 이름에 쓰입니다).")
    return [
        (APP_INFO, 'fullname="IntelliJ IDEA"', f'fullname="{name}"'),
        (APP_INFO, 'motto="The Leading IDE for Professional Development in Java and Kotlin"',
         'motto="Personal Java IDE"'),
        (PROPS, 'fullName { "IntelliJ IDEA Open Source" }', f'fullName {{ "{name}" }}'),
        (PROPS, 'installDirNameHandler { "IntelliJ IDEA OSS" }', f'installDirNameHandler {{ "{name}" }}'),
        # 설정 · 캐시 폴더 이름. 원래 인텔리제이와 섞이지 않게 한다.
        (PROPS, 'return "IdeaIC${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"',
         f'return "{slug}${{appInfo.majorVersion}}.${{appInfo.minorVersionMainPart}}"'),
        (PROPS, '= "ideaIC-$buildNumber"', f'= "{slug.lower()}-$buildNumber"'),
    ]


def slim_edits():
    removal = ",\n      ".join(SLIM_REMOVE)
    return [
        (PROPS,
         'productLayout.bundledPluginModules = IDEA_BUNDLED_PLUGINS + sequenceOf(\n      "intellij.javaFX.community"\n    )',
         'productLayout.bundledPluginModules = IDEA_BUNDLED_PLUGINS.removeAll(listOf(\n      ' + removal + ',\n    ))'),
        (PROPS, '      CommunityRepositoryModules.androidPlugin(allPlatforms = true),\n', ''),
        (PROPS, 'import org.jetbrains.intellij.build.kotlin.KotlinBinaries\n',
         'import org.jetbrains.intellij.build.kotlin.KotlinBinaries\n'
         'import org.jetbrains.intellij.build.kotlin.KotlinPluginBuilder\n'),
    ]


def read_source(path: Path):
    """줄끝을 \\n 으로 맞춘 글과 원래 줄끝을 돌려준다. 원문 비교는 \\n 기준이고, 쓸 때 원래 줄끝으로 되돌린다.
    모든 줄이 \\r\\n 일 때만 바꾼다. LF · CRLF 가 섞인 파일은 그대로 두어(쓸 때도 그대로) 한쪽으로 통일되지 않게 한다.
    섞인 파일에서 CRLF 줄에 걸친 원문은 찾지 못해 「못 찾음」으로 멈춘다(파일은 쓰지 않는다)."""
    raw = path.read_bytes().decode("utf-8")
    crlf = raw.count("\r\n")
    if crlf > 0 and crlf == raw.count("\n"):
        return raw.replace("\r\n", "\n"), "\r\n"
    return raw, "\n"


def apply(root: Path, edits):
    # 모든 수정이 찾아질 때만 쓴다. 하나라도 못 찾으면 아무것도 바꾸지 않는다.
    texts = {}
    newlines = {}
    for rel, old, new in edits:
        path = root / rel
        if not path.is_file():
            raise SystemExit(f"파일이 없습니다: {rel}")
        if rel not in texts:
            texts[rel], newlines[rel] = read_source(path)
    changed = set()
    for rel, old, new in edits:
        text = texts[rel]
        if (new and new in text) or (not new and old not in text):
            continue  # 이미 바뀐 상태
        if text.count(old) != 1:
            raise SystemExit(f"고칠 문구를 정확히 한 번 찾지 못했습니다 ({text.count(old)}번): {rel}\n  {old[:80]}")
        texts[rel] = text.replace(old, new)
        changed.add(rel)
    for rel in changed:
        # 바이트로 써서 운영체제가 줄끝을 바꾸지 않게 한다(윈도우의 write_text 는 \n 을 \r\n 으로 바꾼다).
        (root / rel).write_bytes(texts[rel].replace("\n", newlines[rel]).encode("utf-8"))
    return sorted(changed)


def main():
    if len(sys.argv) != 4 or sys.argv[3] not in ("brand", "slim"):
        raise SystemExit(__doc__)
    root, name, step = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    edits = brand_edits(name) if step == "brand" else slim_edits()
    changed = apply(root, edits)
    print(f"{step}: {len(changed)}개 파일 수정" + "".join(f"\n  {c}" for c in changed))


if __name__ == "__main__":
    main()
