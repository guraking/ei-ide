@echo off
chcp 949 >nul
rem 위 줄: 이전 실행에서 바뀐 코드 페이지가 남아 있어도 아래 한글 줄을 바르게 읽도록, 한글이 나오기 전에 949 로 맞춘다.
rem KingGura IDE 소스 받기. 2026.3 과 같은 태그로 intellij-community 와 android 모듈을 받는다.
rem 이미 받은 것이 있으면 건너뛴다. 10~30분 걸린다.
setlocal
cd /d "%~dp0"
set "TAG=idea/263.7307.200"

where git >nul 2>nul || (
    echo git 이 없습니다. https://git-scm.com 에서 설치한 뒤 다시 실행하세요.
    exit /b 1
)
rem Git 설정은 받는 저장소에만 건다. PC 전체 설정(--global)은 바꾸지 않아 회사 저장소 · Moa Git 창에 영향이 없다.
rem   core.autocrlf=input : 손질 대본의 원문이 LF 줄끝 기준이다.
rem   core.longpaths=true : 소스에 윈도우 경로 한도 260자를 넘는 파일이 있다.
set "CLONE_CFG=-c core.autocrlf=input -c core.longpaths=true"

rem 이미 받은 소스가 다른 판이면 받지 않고 멈춘다(커뮤니티 판을 올린 뒤 옛 소스로 빌드하지 않게).
for %%r in ("intellij-community" "intellij-community\android") do (
    if exist "%%~r\.git" git -C %%r rev-parse -q --verify "refs/tags/%TAG%" >nul 2>nul || goto othertag
)

if not exist "intellij-community\.git" (
    echo intellij-community 받는 중...
    git clone %CLONE_CFG% --depth 1 --branch %TAG% https://github.com/JetBrains/intellij-community.git intellij-community || goto fail
)
if not exist "intellij-community\android\.git" (
    echo android 모듈 받는 중...
    git clone %CLONE_CFG% --depth 1 --branch %TAG% https://github.com/JetBrains/android.git intellij-community\android || goto fail
)
rem 예전에 받아 둔 저장소에도 같은 설정을 그 저장소에만 둔다.
for %%r in ("intellij-community" "intellij-community\android") do (
    git -C %%r config core.autocrlf input || goto fail
    git -C %%r config core.longpaths true || goto fail
)
echo 완료. 이제 주무셔도 됩니다.
exit /b 0

:othertag
echo 이미 있는 intellij-community 폴더가 %TAG% 판이 아닙니다. 폴더 이름을 바꾸거나 지운 뒤 다시 실행하세요.
exit /b 1

:fail
echo 받기 실패. 이 창의 오류 문구를 그대로 알려 주세요. 다시 실행하기 전에 덜 받은 폴더는 지워야 합니다.
exit /b 1
