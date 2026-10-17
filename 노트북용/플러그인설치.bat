@echo off
chcp 949 >nul
rem EI IDE 를 새로 설치하거나 판을 올린 뒤 한 번 실행한다.
rem 본체에 들어 있지 않은 플러그인(한국어 언어 팩 · Claude Code · One Dark 테마)을 사용자 플러그인 폴더에 넣고,
rem 예전에 사용자 폴더에 따로 넣었던 플러그인(서버 창 · Synchronize · 롬복 · 화면 묶음)은 본체와 겹치지 않게 치운다.
rem 옆의 plugins 폴더에 없는 것은 내려받는다: 한국어 팩은 GitHub 릴리스 「plugins」, Claude Code 는 JetBrains 마켓.
setlocal
set "DEST=%APPDATA%\JetBrains\EIIDE2026.2\plugins"
set "SRC=%~dp0plugins"
set "KO_ZIP=https://github.com/guraking/ei-ide/releases/download/plugins/localization-ko-262.10968.63.zip"
set "CC_ZIP=https://plugins.jetbrains.com/pluginManager?action=download&id=com.anthropic.code.plugin&build=IC-262.10968.63"
set "OD_ZIP=https://plugins.jetbrains.com/pluginManager?action=download&id=com.markskelton.one-dark-theme&build=IC-262.10968.63"

tasklist /fi "imagename eq idea64.exe" | find /i "idea64.exe" >nul && (
  echo EI IDE 를 먼저 종료한 뒤 다시 실행하세요.
  pause
  exit /b 1
)
if not exist "%SRC%" mkdir "%SRC%"

if not exist "%SRC%\localization-ko" (
  echo 한국어 언어 팩을 내려받습니다...
  curl -L -o "%TEMP%\ko.zip" "%KO_ZIP%" && tar -xf "%TEMP%\ko.zip" -C "%SRC%" || (
    echo 한국어 언어 팩을 받지 못했습니다. 인터넷 연결을 확인하세요.
    pause
    exit /b 1
  )
)
if not exist "%SRC%\claude-code-jetbrains-plugin" (
  echo Claude Code 플러그인을 내려받습니다...
  curl -L -o "%TEMP%\cc.zip" "%CC_ZIP%" && tar -xf "%TEMP%\cc.zip" -C "%SRC%" || (
    echo Claude Code 플러그인을 받지 못했습니다. 인터넷 연결을 확인하세요.
    pause
    exit /b 1
  )
)

if not exist "%SRC%\one-dark-theme" (
  echo One Dark 테마를 내려받습니다...
  curl -L -o "%TEMP%\od.zip" "%OD_ZIP%" && tar -xf "%TEMP%\od.zip" -C "%SRC%" || echo One Dark 테마는 받지 못했습니다. 건너뜁니다.
)

if not exist "%DEST%" mkdir "%DEST%"
for %%f in ("%DEST%\boot-servers*.jar" "%DEST%\kinggura-sync*.jar" "%DEST%\kinggura-perspectives*.jar" "%DEST%\ei-db*.jar") do if exist %%f (del /q %%f && echo 치움: %%~nxf)
if exist "%DEST%\lombok" (rmdir /s /q "%DEST%\lombok" && echo 치움: lombok)
xcopy /E /I /Y "%SRC%" "%DEST%" >nul || (
  echo 복사에 실패했습니다: %DEST%
  pause
  exit /b 1
)
echo 플러그인 설치 완료: %DEST%
echo EI IDE 를 켜고 설정에서 Language and Region 을 Korean 으로 고르세요.
pause
