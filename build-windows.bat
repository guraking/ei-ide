@echo off
chcp 949 >nul
rem 위 줄: 이전 실행에서 바뀐 코드 페이지가 남아 있어도 아래 한글 줄을 바르게 읽도록, 한글이 나오기 전에 949 로 맞춘다.
rem KingGura IDE 윈도우 빌드. 이 파일과 같은 폴더에 intellij-community 소스가 받아져 있어야 한다.
rem 결과물: intellij-community\out\idea-ce\artifacts\eiide-*.exe. 기록: logs\build-windows.log
setlocal
cd /d "%~dp0"

if not exist "intellij-community\installers.cmd" (
    echo 소스가 없습니다: %CD%\intellij-community
    exit /b 1
)
if not exist "intellij-community\android\.git" (
    echo android 모듈이 없습니다: %CD%\intellij-community\android
    exit /b 1
)
findstr /c:"EI IDE" "intellij-community\community-resources\resources\idea\IdeaApplicationInfo.xml" >nul || (
    echo 이름 바꾸기가 적용되지 않은 소스입니다. 먼저 이름 바꾸기를 적용해야 합니다.
    exit /b 1
)

rem 개인용 설치 파일에 필요 없는 단계는 건너뛴다(메이븐 배포 묶음 · 소스 압축 · 검색 색인 · 다른 OS 용 묶음 등).
rem 메이븐 배포 묶음 단계는 이 PC에서 멈춘 적이 있다.
set "SKIP=maven_artifacts,sources_archive,search_index,cross_platform_dist,doc_authoring_assets,third_party_libraries,prebuild_shared_indexes,fus_metadata_bundle_step,repair_utility_bundle_step,lib_url_check,broken_plugins_list,windows_zip_archive"

if not exist logs mkdir logs
set "LOG=%CD%\logs\build-windows.log"

rem 빌드마다 판 번호 마지막 자리를 하나 올린다(262.10968.N). 번호는 build-number.txt 에 남는다.
if not exist build-number.txt >build-number.txt echo 0
set /p N=<build-number.txt
set /a N+=1
>build-number.txt echo %N%
set "BUILD_NUMBER=262.10968.%N%"
echo 판 번호: %BUILD_NUMBER%
echo 빌드를 시작합니다. 기록: %LOG%

cd intellij-community
call installers.cmd -Dbuild.number=%BUILD_NUMBER% -Dintellij.build.target.os=current -Dintellij.build.target.arch=current -Dintellij.build.incremental.compilation=true -Dintellij.build.skip.build.steps=%SKIP% > "%LOG%" 2>&1
if errorlevel 1 (
    echo 빌드 실패. 기록 마지막 부분:
    powershell -NoProfile -Command "Get-Content -Tail 40 '%LOG%'"
    pause
    exit /b 1
)
echo 완료. 결과물은 %CD%\out\idea-ce\artifacts 에 있습니다.
rem 노트북에 가져갈 수 있게 설치 파일을 노트북용 폴더에도 둔다.
copy /Y "%~dp0intellij-community\out\idea-ce\artifacts\eiide-*.exe" "%~dp0노트북용\" >nul && echo 설치 파일을 노트북용 폴더에도 복사했습니다.
rem GitHub 릴리스로 올린다. gh 명령줄 도구가 있고 로그인돼 있어야 한다(winget install GitHub.cli → gh auth login). 없으면 건너뛴다.
where gh >nul 2>&1 && (
    gh release create "v%BUILD_NUMBER%" "%~dp0노트북용\eiide-%BUILD_NUMBER%.exe" --repo guraking/ei-ide --title "EI IDE %BUILD_NUMBER%" --notes "빌드 %BUILD_NUMBER%" && echo GitHub 릴리스 v%BUILD_NUMBER% 로 올렸습니다.
) || echo gh 명령줄 도구가 없어 GitHub 릴리스 업로드는 건너뜁니다.
rem 더블클릭으로 돌렸을 때 결과를 읽을 수 있게 창을 닫지 않는다.
pause
exit /b 0
