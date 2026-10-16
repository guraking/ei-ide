@echo off
chcp 949 >nul
rem 위 줄: 이전 실행에서 바뀐 코드 페이지가 남아 있어도 아래 한글 줄을 바르게 읽도록, 한글이 나오기 전에 949 로 맞춘다.
rem KingGura IDE 소스 받기. 2026.2.3 과 같은 태그로 intellij-community 와 android 모듈을 받는다.
rem 이미 받은 것이 있으면 건너뛴다. 10~30분 걸린다.
setlocal
cd /d "%~dp0"
set "TAG=idea/262.10968.63"

where git >nul 2>nul || (
    echo git 이 없습니다. https://git-scm.com 에서 설치한 뒤 다시 실행하세요.
    exit /b 1
)
git config --global core.longpaths true
git config --global core.autocrlf input

if not exist "intellij-community\.git" (
    echo intellij-community 받는 중...
    git clone --depth 1 --branch %TAG% https://github.com/JetBrains/intellij-community.git intellij-community || goto fail
)
if not exist "intellij-community\android\.git" (
    echo android 모듈 받는 중...
    git clone --depth 1 --branch %TAG% https://github.com/JetBrains/android.git intellij-community\android || goto fail
)
echo 완료. 이제 주무셔도 됩니다.
exit /b 0

:fail
echo 받기 실패. 이 창의 오류 문구를 그대로 알려 주세요. 다시 실행하기 전에 덜 받은 폴더는 지워야 합니다.
exit /b 1
