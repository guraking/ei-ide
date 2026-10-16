#!/usr/bin/env bash
# 나만의 개발 도구 빌드: 소스 받기 → 이름 바꾸기(brand) → [플러그인 덜기(slim)] → 윈도우용 배포판 빌드.
#
# 사용법: build.sh <작업 폴더> <제품 이름> [slim]
#   작업 폴더  소스와 결과물을 둘 곳. 소스는 수 GB 이므로 여유 공간 50GB 이상.
#   제품 이름  창 제목 · 설치 폴더에 쓰인다. 예: "My IDE"
#   slim       붙이면 안 쓰는 번들 플러그인을 뺀다. 첫 빌드는 붙이지 않는다.
#
# 결과물: <작업 폴더>/dist/ 아래 윈도우용 zip. 기록: <작업 폴더>/logs/
# 이미 받은 소스가 있으면 다시 받지 않는다.
set -euo pipefail

WORK="${1:?작업 폴더를 주세요}"
NAME="${2:?제품 이름을 주세요}"
STEP="${3:-}"
# 2026.2.3 과 같은 릴리스 태그. android 저장소도 같은 태그로 맞춰야 한다.
TAG="idea/262.10968.63"
KIT="$(cd "$(dirname "$0")" && pwd)"
SRC="$WORK/intellij-community"

mkdir -p "$WORK/logs" "$WORK/dist"
LOG="$WORK/logs/build-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

echo "== 준비 상태"
command -v git >/dev/null || { echo "git 이 없습니다"; exit 1; }
command -v python3 >/dev/null || { echo "python3 이 없습니다"; exit 1; }
df -h "$WORK" | tail -1
free -g 2>/dev/null | head -2 || true
nproc

if [ ! -d "$SRC/.git" ]; then
  echo "== intellij-community 받기 ($TAG)"
  git clone --depth 1 --branch "$TAG" https://github.com/JetBrains/intellij-community.git "$SRC"
fi
if [ ! -d "$SRC/android/.git" ]; then
  # getPlugins.sh 는 git:// 주소를 써서 막히는 경우가 많아 GitHub 사본을 같은 태그로 받는다.
  echo "== android 모듈 받기 ($TAG)"
  git clone --depth 1 --branch "$TAG" https://github.com/JetBrains/android.git "$SRC/android"
fi

echo "== 이름 바꾸기"
python3 "$KIT/customize.py" "$SRC" "$NAME" brand
if [ "$STEP" = "slim" ]; then
  echo "== 플러그인 덜기"
  python3 "$KIT/customize.py" "$SRC" "$NAME" slim
fi

echo "== 빌드 (윈도우 x64 배포판)"
cd "$SRC"
./installers.cmd \
  -Dintellij.build.target.os=windows \
  -Dintellij.build.target.arch=x64 \
  -Dintellij.build.incremental.compilation=true

echo "== 결과물 복사"
found=0
while IFS= read -r f; do
  cp -v "$f" "$WORK/dist/"
  found=1
done < <(find "$SRC/out" -path '*artifacts*' \( -name '*.win.zip' -o -name '*.exe' \) 2>/dev/null)
[ "$found" = 1 ] || { echo "빌드는 끝났지만 윈도우 결과물을 찾지 못했습니다. out/ 아래를 확인하세요."; exit 1; }
echo "== 완료: $WORK/dist"
