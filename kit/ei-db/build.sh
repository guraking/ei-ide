#!/bin/bash
# 컴파일 확인: ./build.sh  → out/ 에 클래스. ./build.sh jar → /mnt/user-data/outputs/ei-db.jar 도 만든다.
set -e
S=/tmp/claude-0/-home-claude/1430a897-9ca2-50f9-8007-1cf8ec6091fe/scratchpad
cd "$(dirname "$0")"
OUT=${OUT:-out}
rm -rf "$OUT" && mkdir -p "$OUT"
CP=$(ls $S/cp/*.jar $S/cp2/*.jar $S/cp3/*.jar $S/cp4/*.jar | tr '\n' ':')
set +e
javac -encoding UTF-8 --release 21 -proc:none -nowarn -Xlint:none -cp "$CP" -d "$OUT" $(find src -name '*.java') > "$OUT.log" 2>&1
rc=$?
set -e
grep -v "JAVA_TOOL_OPTIONS\|^Note:" "$OUT.log" || true
[ $rc -eq 0 ] || { echo "컴파일 실패"; exit 1; }
if [ "$1" = jar ]; then cp -r resources/. "$OUT"/ && jar --create --file /mnt/user-data/outputs/ei-db.jar -C "$OUT" . && echo "jar: /mnt/user-data/outputs/ei-db.jar"; fi
echo "컴파일 성공"
