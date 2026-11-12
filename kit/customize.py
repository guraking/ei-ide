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

from patchlib import apply_edits

APP_INFO = "community-resources/resources/idea/IdeaApplicationInfo.xml"
PROPS = "build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt"

# slim 에서 번들에서 빼는 플러그인 모듈. Java · Maven · Gradle · Groovy(그래들 스크립트용) · Git ·
# GitHub · GitLab 연동 · Markdown · YAML · Properties · 이클립스 키맵 · 이클립스 프로젝트 가져오기는 남긴다.
# JavaFX(intellij.javaFX.community)도 넣지 않는다: 원래는 IDEA_BUNDLED_PLUGINS 에 덧붙이는데(slim_edits 원문의 sequenceOf),
# 그 덧붙임을 이 목록을 빼는 식으로 바꾸며 함께 없앴다. 쓰는 화면이 없어 일부러 뺀 상태로 둔다.
# 마지막 항목 뒤가 apply_defaults.py 11)(SVN 번들 추가)의 원문이 된다(그쪽은 SLIM_REMOVE[-1] 로 따라간다).
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


# 예전 판에서 쓴 제품 이름. 이미 그 이름으로 바뀐 소스도 새 이름으로 바꿀 수 있게 원문 후보에 넣는다.
PREVIOUS_NAMES = ["EI IDE"]


def brand_edits(name: str):
    slug = re.sub(r"[^A-Za-z0-9]", "", name)
    if not slug:
        raise SystemExit("제품 이름에는 영문자나 숫자가 하나 이상 있어야 합니다(설정 폴더 이름에 쓰입니다).")
    olds = [(n, re.sub(r"[^A-Za-z0-9]", "", n)) for n in PREVIOUS_NAMES]
    return [
        (APP_INFO, ('fullname="IntelliJ IDEA"', *[f'fullname="{n}"' for n, _ in olds]), f'fullname="{name}"'),
        (APP_INFO, 'motto="The Leading IDE for Professional Development in Java and Kotlin"',
         'motto="Personal Java IDE"'),
        (PROPS, ('fullName { "IntelliJ IDEA Open Source" }', *[f'fullName {{ "{n}" }}' for n, _ in olds]),
         f'fullName {{ "{name}" }}'),
        (PROPS, ('installDirNameHandler { "IntelliJ IDEA OSS" }', *[f'installDirNameHandler {{ "{n}" }}' for n, _ in olds]),
         f'installDirNameHandler {{ "{name}" }}'),
        # 설정 · 캐시 폴더 이름. 원래 인텔리제이와 섞이지 않게 한다.
        (PROPS, ('return "IdeaIC${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"',
                 *[f'return "{s}${{appInfo.majorVersion}}.${{appInfo.minorVersionMainPart}}"' for _, s in olds]),
         f'return "{slug}${{appInfo.majorVersion}}.${{appInfo.minorVersionMainPart}}"'),
        (PROPS, ('= "ideaIC-$buildNumber"', *[f'= "{s.lower()}-$buildNumber"' for _, s in olds]),
         f'= "{slug.lower()}-$buildNumber"'),
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


def main():
    if len(sys.argv) != 4 or sys.argv[3] not in ("brand", "slim"):
        raise SystemExit(__doc__)
    root, name, step = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
    edits = brand_edits(name) if step == "brand" else slim_edits()
    changed = apply_edits(root, edits, backup=False)
    print(f"{step}: {len(changed)}개 파일 수정" + "".join(f"\n  {c}" for c in changed))


if __name__ == "__main__":
    main()
